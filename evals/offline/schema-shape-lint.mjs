import {readFile, readdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

const catalogDirectory = new URL('../../src/main/resources/llm/', import.meta.url);
const baselineFile = new URL('./fixtures/schema-shape-baseline.json', import.meta.url);
const mapKeywords = new Set(['properties', 'patternProperties', '$defs', 'definitions', 'dependentSchemas']);
const listKeywords = new Set(['oneOf', 'anyOf', 'allOf', 'prefixItems']);
const singleKeywords = new Set(['items', 'additionalItems', 'additionalProperties', 'unevaluatedProperties',
  'unevaluatedItems', 'not', 'contains', 'propertyNames', 'if', 'then', 'else', 'contentSchema']);
const watchedKeywords = ['oneOf', 'anyOf', 'not', '$ref'];
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const schemaValue = value => object(value) || typeof value === 'boolean';
const appendPath = (path, key) => typeof key === 'number' ? `${path}[${key}]` :
  /^[A-Za-z_$][A-Za-z0-9_$]*$/u.test(key) ? `${path}.${key}` : `${path}[${JSON.stringify(key)}]`;
const pathFor = (path, tokens) => tokens.reduce(appendPath, path);

// Walk schema-valued keywords only, never examples/default/const/enum or annotation objects.
function children(schema) {
  const result = [];
  for (const key of Object.keys(schema).sort()) {
    const value = schema[key];
    if (mapKeywords.has(key) && object(value)) {
      for (const name of Object.keys(value).sort()) {
        if (schemaValue(value[name])) result.push({tokens: [key, name], schema: value[name], key});
      }
    } else if ((listKeywords.has(key) || key === 'items') && Array.isArray(value)) {
      value.forEach((child, index) => {
        if (schemaValue(child)) result.push({tokens: [key, index], schema: child, key});
      });
    } else if (singleKeywords.has(key) && schemaValue(value)) {
      result.push({tokens: [key], schema: value, key});
    } else if (key === 'dependencies' && object(value)) {
      for (const name of Object.keys(value).sort()) {
        if (schemaValue(value[name])) result.push({tokens: [key, name], schema: value[name], key});
      }
    }
  }
  return result;
}

const objectShape = schema => object(schema) && (schema.type === 'object' ||
  (Array.isArray(schema.type) && schema.type.includes('object')) || Object.hasOwn(schema, 'properties'));

function objectItem(schema, root, seen = new Set()) {
  if (!object(schema) || seen.has(schema)) return false;
  if (objectShape(schema)) return true;
  const next = new Set(seen).add(schema);
  if (['oneOf', 'anyOf', 'allOf'].some(key => Array.isArray(schema[key]) &&
      schema[key].some(branch => objectItem(branch, root, next)))) return true;
  if (typeof schema.$ref !== 'string' || !schema.$ref.startsWith('#/')) return false;
  try {
    const tokens = decodeURIComponent(schema.$ref.slice(2)).split('/')
      .map(token => token.replace(/~1/gu, '/').replace(/~0/gu, '~'));
    let target = root;
    for (const token of tokens) {
      if (!object(target) && !Array.isArray(target)) return false;
      if (!Object.hasOwn(target, token)) return false;
      target = target[token];
    }
    return objectItem(target, root, next);
  } catch {
    return false;
  }
}

/** Source-site inventory, not validation or a model of an actual client's renderer.
 * References are recorded at every source site, not fetched or expanded. Nested
 * schema keywords remain visible even below oneOf/not; paths are JSONPath-like.
 */
export function lintInputSchema(schema) {
  const findings = [], positions = [], transitionLikeArrays = [];
  const rootPosition = {path: '$', kind: 'root', keywordPaths: []};
  positions.push(rootPosition);
  function visit(node, path, position, oneOfBranches, itemComposition) {
    if (!object(node)) return;
    for (const keyword of watchedKeywords) {
      if (!Object.hasOwn(node, keyword)) continue;
      const keywordPath = appendPath(path, keyword);
      const finding = {keyword, path: keywordPath, schemaPath: path,
        positionPath: position.path, positionKind: position.kind, oneOfBranches: [...oneOfBranches]};
      if (keyword === '$ref') finding.reference = node[keyword];
      if (Array.isArray(node[keyword])) finding.branchCount = node[keyword].length;
      findings.push(finding);
      position.keywordPaths.push(keywordPath);
      if ((keyword === 'oneOf' || keyword === 'not') && itemComposition) {
        itemComposition.keywordPaths.push(keywordPath);
      }
    }
    if (oneOfBranches.length && Object.hasOwn(node, 'additionalProperties')) {
      const keywordPath = appendPath(path, 'additionalProperties');
      findings.push({keyword: 'additionalProperties', path: keywordPath, schemaPath: path,
        positionPath: position.path, positionKind: position.kind, oneOfBranches: [...oneOfBranches],
        value: typeof node.additionalProperties === 'boolean' ? node.additionalProperties : 'schema'});
      position.keywordPaths.push(keywordPath);
    }
    for (const child of children(node)) {
      const childPath = pathFor(path, child.tokens);
      let childPosition = position;
      let composition = ['oneOf', 'anyOf', 'allOf', 'not', 'if', 'then', 'else'].includes(child.key)
        ? itemComposition : null;
      const branches = child.key === 'oneOf' ? [...oneOfBranches, childPath] : oneOfBranches;
      if (['items', 'prefixItems', 'additionalItems'].includes(child.key)) {
        const isObject = objectItem(child.schema, schema);
        childPosition = {path: childPath, kind: isObject ? 'array-object-item' : 'array-item', keywordPaths: []};
        positions.push(childPosition);
        if (isObject) {
          composition = {path, itemPath: childPath, keywordPaths: []};
          transitionLikeArrays.push(composition);
        }
      }
      visit(child.schema, childPath, childPosition, branches, composition);
    }
  }
  visit(schema, '$', rootPosition, [], null);
  return {positions, findings,
    transitionLikeArrays: transitionLikeArrays.filter(array => array.keywordPaths.length)};
}

/** Discover every current *-tools.json catalog; no network, Jira calls or paid models. */
export async function inventoryCatalogs(directory = catalogDirectory) {
  const files = (await readdir(directory)).filter(name => name.endsWith('-tools.json')).sort();
  const tools = [];
  for (const file of files) {
    const location = directory instanceof URL ? new URL(file, directory) : resolve(directory, file);
    const entries = JSON.parse(await readFile(location, 'utf8'));
    if (!Array.isArray(entries)) throw new Error(`Expected a tool array in ${file}`);
    entries.forEach((tool, toolIndex) => {
      if (!object(tool) || typeof tool.name !== 'string' || !schemaValue(tool.input_schema)) {
        throw new Error(`Missing tool name/input_schema at ${file}:$[${toolIndex}]`);
      }
      tools.push({catalog: file, name: tool.name, schemaPath: `$[${toolIndex}].input_schema`,
        ...lintInputSchema(tool.input_schema)});
    });
  }
  return {formatVersion: 1, catalogs: files, tools};
}

/** Baseline contains structural inventory only, never schema descriptions or hashes. */
export function createBaseline(inventory) {
  return structuredClone(inventory);
}

/** Informational drift; changed findings appear as removed + added records. */
export function compareBaseline(current, baseline) {
  const records = inventory => {
    const result = new Map();
    for (const catalog of inventory.catalogs) result.set(`catalog:${catalog}`, {kind: 'catalog', catalog});
    for (const tool of inventory.tools) {
      const identity = {catalog: tool.catalog, name: tool.name};
      const add = record => result.set(JSON.stringify(record), record);
      add({kind: 'tool', ...identity, schemaPath: tool.schemaPath});
      for (const position of tool.positions) add({kind: 'position', ...identity, ...position});
      for (const finding of tool.findings) add({kind: 'finding', ...identity, ...finding});
      for (const array of tool.transitionLikeArrays) add({kind: 'transition-like-array', ...identity, ...array});
    }
    return result;
  };
  const now = records(current), before = records(baseline);
  return {added: [...now].filter(([key]) => !before.has(key)).map(([, value]) => value),
    removed: [...before].filter(([key]) => !now.has(key)).map(([, value]) => value)};
}

const code = text => `\`${String(text).replaceAll('`', '\\`').replaceAll('|', '\\|')}\``;
export function formatReport(inventory, delta = null) {
  const lines = ['# Schema-shape informational inventory', '',
    'Offline source structure only. Findings and baseline drift are informational, never a lint failure.',
    'No actual client renderer was exercised. References below are source sites, not expanded targets.', '',
    `Catalogs (${inventory.catalogs.length}): ${inventory.catalogs.map(code).join(', ')}.`, '',
    '| Catalog | Tool | oneOf | anyOf | not | $ref | additionalProperties inside oneOf | Transition-like items |',
    '| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |'];
  for (const tool of inventory.tools) {
    const count = keyword => tool.findings.filter(finding => finding.keyword === keyword).length;
    lines.push(`| ${code(tool.catalog)} | ${code(tool.name)} | ${count('oneOf')} | ${count('anyOf')} | ${count('not')} | ${count('$ref')} | ${count('additionalProperties')} | ${tool.transitionLikeArrays.length} |`);
  }
  lines.push('', '## Exact source paths', '',
    'Paths start at each input_schema ($); prepend the listed catalog tool path to locate the original JSON.',
    'Root/item owners include descendants, not only direct keywords. All nested keyword sites are retained.', '');
  for (const tool of inventory.tools) {
    lines.push(`### ${tool.name} (${tool.catalog}:${tool.schemaPath})`, '');
    if (!tool.findings.length) lines.push('No watched keyword sites.', '');
    for (const finding of tool.findings) {
      const detail = finding.keyword === '$ref' ? ` → ${code(finding.reference)}` :
        finding.branchCount !== undefined ? ` (${finding.branchCount} branches)` :
        finding.keyword === 'additionalProperties' ? ` = ${code(finding.value)}` : '';
      const branches = finding.oneOfBranches.length ? `; inside ${finding.oneOfBranches.map(code).join(', ')}` : '';
      lines.push(`- ${code(finding.path)}${detail}; owner ${finding.positionKind} ${code(finding.positionPath)}${branches}.`);
    }
    if (tool.findings.length) lines.push('');
  }
  lines.push('## Transition-like arrays', '',
    'Structural heuristic: an object item with oneOf/not at its own schema or composition branches (including branch-local not).',
    'This does not assert transition semantics; nested property schemas do not make the containing item a candidate.', '');
  const arrays = inventory.tools.flatMap(tool => tool.transitionLikeArrays.map(array => ({tool, array})));
  if (!arrays.length) lines.push('None.', '');
  for (const {tool, array} of arrays) {
    lines.push(`- ${code(tool.name)} ${code(array.path)}, item ${code(array.itemPath)}: ${array.keywordPaths.map(code).join(', ')}.`);
  }
  lines.push('', '## create_custom_fields comparison', '');
  const comparisons = inventory.tools.filter(tool => tool.name === 'create_custom_fields');
  for (const tool of comparisons) {
    lines.push(`${code(tool.name)}: ${tool.findings.length} watched keyword sites; ${tool.positions.filter(position => position.kind === 'array-object-item').length} object-item positions.`, '');
  }
  if (!comparisons.length) lines.push('Not present in these catalogs.', '');
  lines.push('The user reports that create_custom_fields renders entirely. This report preserves that observation but does not independently reproduce it.',
    'Presence/absence of these constructs is a comparison, not proof of why a client does or does not render a schema.', '');
  if (delta) {
    lines.push('## Baseline delta (informational)', '', `Added records: ${delta.added.length}; removed records: ${delta.removed.length}.`, '');
    for (const [label, entries] of [['Added', delta.added], ['Removed', delta.removed]]) {
      for (const entry of entries) lines.push(`- ${label}: ${code(JSON.stringify(entry))}`);
    }
  }
  return `${lines.join('\n')}\n`;
}

export async function main(args = process.argv.slice(2)) {
  if (args.some(arg => arg !== '--json' && arg !== '--baseline-json')) {
    throw new Error('Usage: node evals/offline/schema-shape-lint.mjs [--json | --baseline-json]');
  }
  const inventory = await inventoryCatalogs();
  if (args.includes('--baseline-json')) return `${JSON.stringify(createBaseline(inventory), null, 2)}\n`;
  let delta = null, baselineNote = '';
  try {
    delta = compareBaseline(inventory, JSON.parse(await readFile(baselineFile, 'utf8')));
  } catch (error) {
    baselineNote = `\nBaseline unavailable (${error.message}); inventory remains informational.\n`;
  }
  return args.includes('--json') ? `${JSON.stringify({inventory, delta, baselineNote}, null, 2)}\n` :
    formatReport(inventory, delta) + baselineNote;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().then(text => process.stdout.write(text)).catch(error => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
