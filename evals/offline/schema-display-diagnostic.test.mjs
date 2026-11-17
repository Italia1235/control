import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import test from 'node:test';
import {
  diagnoseSchemas, formatDiagnostic, loadDiagnosticSchemas, renderSchema,
} from './schema-display-diagnostic.mjs';

const schemas = await loadDiagnosticSchemas();
const transition = '$.properties.create.properties.transitions.items';
const resolution = `${transition}.properties.resolution`;
const action = `${resolution}.properties.action`;
const mode = 'hypothetical-object-oneOf-unknown';
const entry = (rendering, path) => rendering.trace.find(node => node.path === path);
const toolReport = (report, name) => report.tools.find(tool => tool.name === name);
const control = (tool, name) => tool.controls.find(item => item.name === name);

// The former nested configure_workflow shape (root target oneOf, transition oneOf, Resolution oneOf+not, $ref/$defs), kept as
// an inline fixture so the hypothetical-fallback and ablation mechanics stay tested after the real schema was flattened.
const legacyWorkflow = {
  $defs: {
    nameText: {type: 'string', minLength: 1, allOf: [{$ref: '#/$defs/xmlText'}]},
    xmlText: {type: 'string', minLength: 1},
  },
  type: 'object', required: ['dryRun'], additionalProperties: false,
  oneOf: [{required: ['create'], not: {required: ['existing']}}, {required: ['existing'], not: {required: ['create']}}],
  properties: {
    dryRun: {type: 'boolean', enum: [true]},
    create: {
      type: 'object', required: ['name', 'statuses', 'initialStatus', 'transitions'], additionalProperties: false,
      properties: {
        name: {allOf: [{$ref: '#/$defs/nameText'}]},
        statuses: {type: 'array', items: {type: 'object', additionalProperties: false, properties: {name: {type: 'string'}}}},
        initialStatus: {type: 'string'},
        transitions: {type: 'array', items: {
          type: 'object', required: ['name', 'to', 'key'], additionalProperties: false,
          oneOf: [
            {required: ['from'], properties: {fromAny: {enum: [false]}}},
            {required: ['fromAny'], properties: {fromAny: {enum: [true]}}},
          ],
          properties: {
            name: {type: 'string'}, from: {type: 'string'}, fromAny: {type: 'boolean'}, to: {type: 'string'}, key: {type: 'string'},
            resolution: {
              type: ['object', 'null'], additionalProperties: false,
              properties: {action: {type: 'string', enum: ['SET', 'CLEAR']}, value: {type: 'string'}},
              oneOf: [
                {type: 'null'},
                {required: ['value'], properties: {action: {enum: ['SET']}}},
                {properties: {action: {enum: ['CLEAR']}}, not: {required: ['value']}},
              ],
            },
          },
        }},
      },
    },
    transitionRules: {type: 'array', items: {
      type: 'object', oneOf: [{required: ['transitionKey']}, {required: ['actionId']}],
      properties: {transitionKey: {type: 'string'}, actionId: {type: 'integer'}},
    }},
  },
};
const legacySchemas = {configure_workflow: legacyWorkflow, create_custom_fields: schemas.create_custom_fields};

test('diagnostic loads the actual public workflow and custom-fields schemas, not simplified stand-ins', () => {
  assert.deepEqual(Object.keys(schemas).sort(), ['configure_workflow', 'create_custom_fields']);
  const workflow = schemas.configure_workflow;
  assert.deepEqual(workflow.required, ['dryRun']);
  assert.equal(workflow.oneOf, undefined);
  assert.equal(workflow.$defs, undefined);
  assert.deepEqual(workflow.properties.create.required, ['name', 'statuses', 'initialStatus', 'transitions']);
  assert.equal(workflow.properties.create.properties.transitions.items.oneOf, undefined);
  assert.deepEqual(workflow.properties.create.properties.transitions.items.properties.resolution.type, ['object', 'null']);
  assert.equal(workflow.properties.create.properties.name.allOf, undefined);
  assert.equal(schemas.create_custom_fields.properties.fields.items.properties.contexts.items.properties
    .options.items.properties.children.items.properties.value.type, 'string');
});

test('faithful rendering recursively preserves the flat transition object, its nested Resolution and the rule forms', () => {
  const rendered = renderSchema(schemas.configure_workflow);
  const rules = '$.properties.transitionRules.items.properties';
  assert.equal(rendered.mode, 'faithful');
  assert.equal(rendered.firstFallback, null);
  assert.deepEqual(rendered.fallbacks, []);
  assert.equal(entry(rendered, transition).displayType, 'object');
  assert.deepEqual(entry(rendered, transition).keywords.required, ['name', 'to', 'key']);
  assert.deepEqual(entry(rendered, resolution).keywords.type, ['object', 'null']);
  assert.deepEqual(entry(rendered, action).keywords.enum, ['SET', 'CLEAR']);
  assert.equal(entry(rendered, `${resolution}.additionalProperties`).displayType, 'never');
  assert.equal(entry(rendered, `${transition}.properties.fromAny`).displayType, 'boolean');
  assert.deepEqual(entry(rendered, `${rules}.screen`).keywords.type, ['string', 'object']);
  assert.deepEqual(entry(rendered, `${rules}.addValidators.items.properties.template`).keywords.enum, ['comment_required', 'required_fields']);
  assert.deepEqual(entry(rendered, `${rules}.addConditions.properties.rules.items.properties.template`).keywords.enum, ['group', 'project_role']);
  assert.match(rendered.text, /resolution\.properties\.action.*enum=\["SET","CLEAR"\]/u);
});

test('the hypothetical fallback loses the exclusive workflow target first in the former nested shape, before the graph', () => {
  const rendered = renderSchema(legacyWorkflow, {mode});
  assert.equal(rendered.firstFallback.path, '$');
  assert.equal(rendered.firstFallback.keywordPath, '$.oneOf');
  assert.equal(rendered.firstFallback.reason, 'HYPOTHETICAL_OBJECT_ONEOF_FALLBACK');
  assert.equal(entry(rendered, '$').kind, 'unknown');
  assert.equal(entry(rendered, transition), undefined);
  assert.equal(entry(rendered, resolution), undefined);
  assert.equal(rendered.fallbacks.length, 1);
  const isolated = control(toolReport(diagnoseSchemas(legacySchemas), 'configure_workflow'), 'without-target-oneOf').hypothetical;
  assert.equal(isolated.firstFallback.path, transition);
  assert.equal(entry(isolated, '$.properties.create.properties.statuses.items').displayType, 'object');
});

test('the real flat workflow schema has no object-with-oneOf: the hypothetical fallback never fires and equals the faithful rendering', () => {
  const faithful = renderSchema(schemas.configure_workflow);
  const hypothetical = renderSchema(schemas.configure_workflow, {mode});
  assert.equal(hypothetical.firstFallback, null);
  assert.deepEqual(hypothetical.fallbacks, []);
  assert.deepEqual(hypothetical.trace, faithful.trace);
  assert.equal(hypothetical.trace.some(node => /\.(oneOf|anyOf|allOf|not)\b/u.test(node.path)), false);
});

test('actual custom-fields nesting is retained in both renderers; it is a comparison, not a proven-good client', () => {
  const faithful = renderSchema(schemas.create_custom_fields);
  const hypothetical = renderSchema(schemas.create_custom_fields, {mode});
  const nested = '$.properties.fields.items.properties.contexts.items.properties.options.items.properties.children.items.properties.value';
  assert.equal(entry(faithful, nested).displayType, 'string');
  assert.equal(entry(hypothetical, nested).displayType, 'string');
  assert.deepEqual(hypothetical.trace, faithful.trace);
  assert.equal(hypothetical.firstFallback, null);
  assert.equal(hypothetical.trace.some(node => node.path.includes('.oneOf[')), false);
});

test('local refs expand at their use sites with original source paths and leave sibling constraints visible', () => {
  const schema = {
    $defs: {'value/type': {type: 'string', enum: ['SET', 'CLEAR']}},
    type: 'object', properties: {
      resolution: {$ref: '#/$defs/value~1type', description: 'At the use site'},
    },
  };
  const rendered = renderSchema(schema);
  const use = entry(rendered, '$.properties.resolution');
  const target = entry(rendered, '$.properties.resolution.$refTarget');
  assert.equal(use.keywords.$ref, '#/$defs/value~1type');
  assert.equal(use.keywords.description, 'At the use site');
  assert.equal(target.sourcePath, '$.$defs["value/type"]');
  assert.equal(target.viaRef, '#/$defs/value~1type');
  assert.deepEqual(target.keywords.enum, ['SET', 'CLEAR']);
  assert.equal(target.displayType, 'string');
  const actual = renderSchema(schemas.configure_workflow);
  assert.equal(actual.trace.some(node => node.path.includes('$refTarget') || Object.hasOwn(node.keywords, '$ref')), false,
    'the flat configure_workflow schema has no local refs to expand');
});

test('reference handling is local-only, resolves escaped fragments, and stops recursive refs without truncating siblings', () => {
  const schema = {$defs: {'a b~c': {type: 'string'}}, properties: {
    encoded: {$ref: '#/$defs/a%20b~0c'}, self: {$ref: '#'}, tail: {type: 'boolean'},
  }};
  const rendered = renderSchema(schema);
  assert.equal(entry(rendered, '$.properties.encoded.$refTarget').displayType, 'string');
  assert.equal(entry(rendered, '$.properties.self.$refTarget').kind, 'circular-ref');
  assert.equal(entry(rendered, '$.properties.tail').displayType, 'boolean');
  assert.throws(() => renderSchema({$ref: 'https://example.invalid/schema.json'}), /local.*\$ref/iu);
  assert.throws(() => renderSchema({$ref: '#/$defs/missing'}), /unresolved.*\$ref/iu);
});

test('properties, items, oneOf, not and schema-valued additionalProperties retain complete trace paths', () => {
  const schema = {type: 'array', items: {oneOf: [
    {type: 'object', properties: {'odd.name': {type: 'integer'}}, additionalProperties: {type: 'string'}},
    {type: 'string', not: {enum: ['forbidden']}},
  ]}};
  const rendered = renderSchema(schema);
  assert.equal(entry(rendered, '$.items.oneOf[0].properties["odd.name"]').displayType, 'integer');
  assert.equal(entry(rendered, '$.items.oneOf[0].additionalProperties').displayType, 'string');
  assert.deepEqual(entry(rendered, '$.items.oneOf[1].not').keywords.enum, ['forbidden']);
  assert.equal(renderSchema(schema, {mode}).firstFallback, null, 'a union alone is not an object-with-oneOf');
  assert.equal(renderSchema({type: 'string', oneOf: [{const: 'a'}, {const: 'b'}]}, {mode}).firstFallback,
    null, 'do not mislabel scalar alternatives as the hypothetical object fallback');
  const refObject = {$defs: {choice: {type: 'object', oneOf: [{required: ['a']}, {required: ['b']}]}},
    type: 'object', properties: {choice: {$ref: '#/$defs/choice'}}};
  const fallback = renderSchema(refObject, {mode}).firstFallback;
  assert.equal(fallback.path, '$.properties.choice.$refTarget');
  assert.equal(fallback.sourcePath, '$.$defs.choice');
});

test('ablations isolate the ancestor transition union from the descendant Resolution union without mutating inputs', () => {
  const before = JSON.stringify(legacySchemas);
  const report = diagnoseSchemas(legacySchemas);
  const workflow = toolReport(report, 'configure_workflow');
  const noResolution = control(workflow, 'without-resolution-oneOf-and-not');
  const noTransition = control(workflow, 'without-transition-oneOf');
  const neither = control(workflow, 'without-both-oneOf');
  assert.equal(noResolution.hypothetical.firstFallback.path, transition);
  assert.equal(entry(noResolution.faithful, `${resolution}.oneOf[0]`), undefined);
  assert.deepEqual(entry(noResolution.faithful, action).keywords.enum, ['SET', 'CLEAR']);
  assert.equal(noTransition.hypothetical.firstFallback.path, resolution);
  assert.equal(entry(noTransition.hypothetical, transition).displayType, 'object');
  assert.equal(entry(noTransition.hypothetical, `${transition}.properties.to`).displayType, 'string');
  assert.equal(neither.hypothetical.firstFallback.path, '$.properties.transitionRules.items');
  assert.deepEqual(entry(neither.hypothetical, action).keywords.enum, ['SET', 'CLEAR']);
  assert(noResolution.removedPaths.includes(`${resolution}.oneOf`));
  assert(noTransition.removedPaths.includes(`${transition}.oneOf`));
  assert.equal(JSON.stringify(legacySchemas), before);
});

test('$ref, not and additionalProperties controls do not explain the first loss in the stated hypothesis (former nested shape)', () => {
  const report = diagnoseSchemas(legacySchemas);
  const workflow = toolReport(report, 'configure_workflow');
  for (const [name, keyword] of [
    ['without-$ref', '$ref'], ['without-not', 'not'], ['without-additionalProperties', 'additionalProperties'],
  ]) {
    const ablated = control(workflow, name);
    assert(ablated.removedPaths.length > 0, `${name} must actually change the former workflow shape`);
    assert.equal(ablated.hypothetical.firstFallback.path, '$');
    assert.equal(ablated.faithful.trace.some(node => Object.hasOwn(node.keywords, keyword)), false);
    assert.equal(ablated.faithful.trace.some(node => node.path.endsWith(`.${keyword}`)), false);
    assert.equal(control(toolReport(report, 'create_custom_fields'), name).hypothetical.firstFallback, null);
  }
});

test('the real flat workflow schema has no union, $ref or not for the controls to remove; only additionalProperties is left', () => {
  const workflow = toolReport(diagnoseSchemas(schemas), 'configure_workflow');
  assert.deepEqual(workflow.controls.map(item => item.name), ['without-$ref', 'without-not', 'without-additionalProperties']);
  assert.equal(control(workflow, 'without-$ref').removedPaths.length, 0);
  assert.equal(control(workflow, 'without-not').removedPaths.length, 0);
  assert(control(workflow, 'without-additionalProperties').removedPaths.length > 0);
  for (const item of workflow.controls) assert.equal(item.hypothetical.firstFallback, null, item.name);
});

test('report and CLI state the hypothetical boundary and show both actual tools without network or client claims', () => {
  const report = diagnoseSchemas(schemas);
  assert.match(report.hypothesis, /hypothetical/iu);
  const text = formatDiagnostic(report);
  assert.match(text, /configure_workflow:\n  faithful: \d+ nodes; first fallback: none\n  hypothetical: \d+ nodes; first fallback: none/u);
  assert.match(text, /create_custom_fields:\n  faithful: \d+ nodes; first fallback: none/u);
  assert.doesNotMatch(text, /without-(target|resolution|transition|both)-oneOf/u);
  assert.match(text, /not an actual client reproduction/iu);
  const legacyText = formatDiagnostic(diagnoseSchemas(legacySchemas));
  assert.match(legacyText, /without-target-oneOf: 1 removals; first fallback: \$\.properties\.create\.properties\.transitions\.items/u);
  assert.match(legacyText, /without-resolution-oneOf-and-not/u);
  assert.match(legacyText, /without-transition-oneOf/u);
  const child = spawnSync(process.execPath, [
    '--import', new URL('./offline-network.mjs', import.meta.url).href,
    fileURLToPath(new URL('./schema-display-diagnostic.mjs', import.meta.url)),
  ], {cwd: tmpdir(), encoding: 'utf8', timeout: 10000, env: {...process.env, NODE_OPTIONS: ''}});
  assert.equal(child.error, undefined);
  assert.equal(child.status, 0, child.stderr);
  assert.equal(child.stderr, '');
  assert.equal(child.stdout, `${text}\n`);
});
