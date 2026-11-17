import assert from 'node:assert/strict';
import {mkdtemp, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import test from 'node:test';
import {compareBaseline, createBaseline, formatReport, inventoryCatalogs, lintInputSchema, main}
  from './schema-shape-lint.mjs';

const paths = result => result.findings.map(finding => finding.path);
const inventory = (schema, name = 'example') => ({formatVersion: 1, catalogs: ['example-tools.json'],
  tools: [{catalog: 'example-tools.json', name, schemaPath: '$[0].input_schema', ...lintInputSchema(schema)}]});

test('root, item, nested combinator and branch-local not paths are all retained', () => {
  const result = lintInputSchema({type: 'object', anyOf: [{not: {required: ['hidden']}}],
    properties: {transitions: {type: 'array', items: {type: 'object',
      oneOf: [{not: {required: ['fromAny']}}, {anyOf: [{$ref: '#/$defs/from'}]}],
      properties: {resolution: {oneOf: [{type: 'null'}, {not: {required: ['value']}}]}}}}},
    $defs: {from: {not: {required: ['invalid']}}}});
  assert.deepEqual(new Set(paths(result)), new Set([
    '$.anyOf', '$.anyOf[0].not', '$.$defs.from.not', '$.properties.transitions.items.oneOf',
    '$.properties.transitions.items.oneOf[0].not', '$.properties.transitions.items.oneOf[1].anyOf',
    '$.properties.transitions.items.oneOf[1].anyOf[0].$ref',
    '$.properties.transitions.items.properties.resolution.oneOf',
    '$.properties.transitions.items.properties.resolution.oneOf[1].not',
  ]));
  assert.deepEqual(result.transitionLikeArrays, [{path: '$.properties.transitions',
    itemPath: '$.properties.transitions.items', keywordPaths: [
      '$.properties.transitions.items.oneOf', '$.properties.transitions.items.oneOf[0].not',
    ]}]);
  const reference = result.findings.find(finding => finding.keyword === '$ref');
  assert.equal(reference.reference, '#/$defs/from');
  assert.equal(reference.positionKind, 'array-object-item');
  assert.equal(reference.positionPath, '$.properties.transitions.items');
  assert.deepEqual(reference.oneOfBranches, ['$.properties.transitions.items.oneOf[1]']);
});

test('additionalProperties is inventoried at any depth inside a oneOf branch only', () => {
  const result = lintInputSchema({additionalProperties: false, oneOf: [{additionalProperties: true,
    properties: {nested: {anyOf: [{additionalProperties: {not: {required: ['x']}}}]},
      deeper: {oneOf: [{additionalProperties: false}]}}}],
    properties: {outside: {additionalProperties: false}}});
  const found = result.findings.filter(finding => finding.keyword === 'additionalProperties');
  assert.deepEqual(found.map(finding => finding.path), [
    '$.oneOf[0].additionalProperties',
    '$.oneOf[0].properties.deeper.oneOf[0].additionalProperties',
    '$.oneOf[0].properties.nested.anyOf[0].additionalProperties',
  ]);
  assert.deepEqual(found.map(finding => finding.value), [true, false, 'schema']);
  assert.deepEqual(found[1].oneOfBranches, ['$.oneOf[0]', '$.oneOf[0].properties.deeper.oneOf[0]']);
  assert.ok(paths(result).includes('$.oneOf[0].properties.nested.anyOf[0].additionalProperties.not'));
});

test('annotation and literal payloads do not masquerade as schemas', () => {
  const fake = {oneOf: [{not: {}, additionalProperties: false}], $ref: '#/imaginary'};
  const result = lintInputSchema({type: 'object', description: 'oneOf not $ref',
    examples: [fake], default: fake, const: fake, enum: [fake], 'x-annotation': fake,
    dependencies: {names: ['oneOf', 'not']}, properties: {safe: {type: 'string'}}});
  assert.deepEqual(result.findings, []);
});

test('legacy, conditional and modern schema edges preserve exact quoted paths', () => {
  const result = lintInputSchema({type: 'object',
    properties: {'a.b': {type: 'array', prefixItems: [{type: 'object', not: {}}, true],
      items: [{type: 'object', anyOf: [{}]}], additionalItems: {type: 'object', oneOf: [{}]}}},
    dependencies: {old: {not: {}}}, dependentSchemas: {current: {anyOf: [{}]}},
    patternProperties: {'^x/y': {$ref: 'https://example.invalid/schema'}},
    if: {not: {}}, then: {oneOf: [{}]}, else: {anyOf: [{}]},
    contains: {not: {}}, contentSchema: {$ref: '#/missing'}});
  for (const path of ['$.properties["a.b"].prefixItems[0].not',
    '$.properties["a.b"].items[0].anyOf', '$.properties["a.b"].additionalItems.oneOf',
    '$.dependencies.old.not', '$.dependentSchemas.current.anyOf',
    '$.patternProperties["^x/y"].$ref', '$.if.not', '$.then.oneOf', '$.else.anyOf',
    '$.contains.not', '$.contentSchema.$ref']) assert.ok(paths(result).includes(path), path);
  assert.equal(result.positions.find(position => position.path.endsWith('prefixItems[1]')).kind, 'array-item');
});

test('object items inferred from composition or local refs keep source-site ownership', () => {
  const result = lintInputSchema({$defs: {'a/b': {type: 'object'}, recursive: {$ref: '#/$defs/recursive'}},
    properties: {union: {type: 'array', items: {oneOf: [{type: 'object'}, {type: 'null'}]}},
      referenced: {type: 'array', items: {$ref: '#/$defs/a~1b'}},
      cycle: {type: 'array', items: {$ref: '#/$defs/recursive'}},
      nestedOnly: {type: 'array', items: {type: 'object', properties: {value: {oneOf: [{not: {}}]}}}}}});
  assert.equal(result.positions.find(position => position.path === '$.properties.referenced.items').kind,
    'array-object-item');
  assert.equal(result.transitionLikeArrays.length, 1);
  assert.equal(result.transitionLikeArrays[0].path, '$.properties.union');
  assert.deepEqual(result.findings.filter(finding => finding.keyword === '$ref').map(finding => finding.path),
    ['$.$defs.recursive.$ref', '$.properties.cycle.items.$ref', '$.properties.referenced.items.$ref']);
  assert.equal(result.positions.find(position => position.path === '$.properties.cycle.items').kind, 'array-item');
});

test('baseline ignores descriptions and reports structural drift without enforcement', () => {
  const original = inventory({type: 'object', description: 'before', oneOf: [{not: {required: ['x']}}]});
  const baseline = createBaseline(original);
  const rewritten = inventory({type: 'object', description: 'after', oneOf: [{not: {required: ['x']}}]});
  assert.deepEqual(compareBaseline(rewritten, baseline), {added: [], removed: []});
  const changed = inventory({type: 'object', anyOf: [{}]});
  const delta = compareBaseline(changed, baseline);
  assert.ok(delta.added.some(entry => entry.keyword === 'anyOf'));
  assert.ok(delta.removed.some(entry => entry.keyword === 'oneOf'));
  assert.match(formatReport(changed, delta), /Baseline delta \(informational\)/u);
  assert.match(formatReport(changed, delta), /never a lint failure/u);
  original.tools[0].findings.length = 0;
  assert.ok(baseline.tools[0].findings.length, 'baseline is a detached structural snapshot');
});

test('discovery includes every tool and zero-finding tools from every matching file', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'schema-shape-'));
  try {
    const tool = (name, input_schema) => ({name, description: 'not part of baseline', input_schema});
    await writeFile(join(directory, 'z-tools.json'), JSON.stringify([tool('z', {not: {}})]));
    await writeFile(join(directory, 'a-tools.json'), JSON.stringify([
      tool('create_custom_fields', {type: 'object', properties: {}}), tool('second', true),
    ]));
    await writeFile(join(directory, 'not-a-catalog.json'), 'not json');
    const current = await inventoryCatalogs(directory);
    assert.deepEqual(current.catalogs, ['a-tools.json', 'z-tools.json']);
    assert.deepEqual(current.tools.map(entry => entry.name), ['create_custom_fields', 'second', 'z']);
    assert.equal(current.tools[1].schemaPath, '$[1].input_schema');
    const report = formatReport(current);
    assert.match(report, /\| `a-tools.json` \| `create_custom_fields` \| 0 \| 0 \| 0 \| 0 \| 0 \| 0 \|/u);
    assert.match(report, /user reports that create_custom_fields renders entirely/u);
    assert.match(report, /No actual client renderer was exercised/u);
    assert.doesNotMatch(JSON.stringify(createBaseline(current)), /not part of baseline/u);
  } finally {
    await rm(directory, {recursive: true, force: true});
  }
});

test('current catalog coverage is discovered, not pinned to production wording or counts', async t => {
  const directory = new URL('../../src/main/resources/llm/', import.meta.url);
  const files = (await readdir(directory)).filter(file => file.endsWith('-tools.json')).sort();
  const current = await inventoryCatalogs();
  assert.deepEqual(current.catalogs, files);
  for (const file of files) {
    const tools = JSON.parse(await readFile(new URL(file, directory), 'utf8'));
    assert.deepEqual(current.tools.filter(tool => tool.catalog === file).map(tool => tool.name),
      tools.map(tool => tool.name));
  }
  const fixedBaseline = JSON.parse(await readFile(new URL('./fixtures/schema-shape-baseline.json', import.meta.url), 'utf8'));
  const delta = compareBaseline(current, fixedBaseline);
  assert.ok(Array.isArray(delta.added) && Array.isArray(delta.removed));
  // Do not assert no drift: the lint deliberately does not freeze catalog structure or prose.
  t.diagnostic(formatReport(current, delta));
  const jsonOutput = JSON.parse(await main(['--json']));
  assert.deepEqual(jsonOutput.inventory, current);
  assert.deepEqual(jsonOutput.delta, delta);
  assert.deepEqual(JSON.parse(await main(['--baseline-json'])), createBaseline(current));
});
