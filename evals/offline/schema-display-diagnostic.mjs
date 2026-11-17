import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

const hypotheticalMode = 'hypothetical-object-oneOf-unknown';
const catalogs = [
  {name: 'configure_workflow', file: 'workflow-tools.json'},
  {name: 'create_custom_fields', file: 'customfields-tools.json'},
];
const mapKeywords = new Set(['properties', 'patternProperties', '$defs', 'definitions', 'dependentSchemas']);
const arrayKeywords = new Set(['oneOf', 'anyOf', 'allOf', 'prefixItems']);
const singleKeywords = new Set(['items', 'additionalItems', 'additionalProperties', 'unevaluatedProperties',
  'unevaluatedItems', 'not', 'contains', 'propertyNames', 'if', 'then', 'else']);
const childKeywords = new Set([...mapKeywords, ...arrayKeywords, ...singleKeywords]);
// Visit use-site structure before definitions. The fallback check itself is always pre-order.
const childOrder = ['properties', 'items', 'prefixItems', 'oneOf', 'allOf', 'anyOf', 'not',
  'additionalProperties', 'patternProperties', 'additionalItems', 'unevaluatedProperties',
  'unevaluatedItems', 'contains', 'propertyNames', 'if', 'then', 'else', 'dependentSchemas', '$defs', 'definitions'];
const transitionTokens = ['properties', 'create', 'properties', 'transitions', 'items'];
const resolutionTokens = [...transitionTokens, 'properties', 'resolution'];

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const schemaValue = value => typeof value === 'boolean' || object(value);
const appendPath = (path, key) => typeof key === 'number' ? `${path}[${key}]` :
  /^[A-Za-z_$][A-Za-z0-9_$]*$/u.test(key) ? `${path}.${key}` : `${path}[${JSON.stringify(key)}]`;
const pathFor = (path, tokens) => tokens.reduce(appendPath, path);

function children(schema) {
  if (!object(schema)) return [];
  const result = [];
  for (const key of childOrder) {
    const value = schema[key];
    if (mapKeywords.has(key) && object(value)) {
      for (const [name, child] of Object.entries(value)) result.push({tokens: [key, name], schema: child});
    } else if ((arrayKeywords.has(key) || key === 'items') && Array.isArray(value)) {
      value.forEach((child, index) => result.push({tokens: [key, index], schema: child}));
    } else if (singleKeywords.has(key) && schemaValue(value)) {
      result.push({tokens: [key], schema: value});
    }
  }
  return result;
}

function localReference(root, ref, usePath) {
  if (typeof ref !== 'string' || (ref !== '#' && !ref.startsWith('#/'))) {
    throw new Error(`Only local $ref JSON pointers are supported at ${usePath}: ${String(ref)}`);
  }
  let tokens;
  try {
    tokens = ref === '#' ? [] : decodeURIComponent(ref.slice(2)).split('/')
      .map(token => token.replace(/~1/gu, '/').replace(/~0/gu, '~'));
  } catch {
    throw new Error(`Unresolved local $ref at ${usePath}: ${ref} (invalid fragment encoding)`);
  }
  let target = root;
  for (const token of tokens) {
    if ((!object(target) && !Array.isArray(target)) || !Object.hasOwn(target, token)) {
      throw new Error(`Unresolved local $ref at ${usePath}: ${ref}`);
    }
    target = target[token];
  }
  if (!schemaValue(target)) throw new Error(`Unresolved local $ref schema at ${usePath}: ${ref}`);
  return {schema: target, sourcePath: pathFor('$', tokens)};
}

function displayType(schema) {
  if (typeof schema === 'boolean') return schema ? 'any' : 'never';
  if (Array.isArray(schema.type)) return schema.type.join('|');
  if (schema.type) return schema.type;
  if (Object.hasOwn(schema, 'properties')) return 'object';
  if (Object.hasOwn(schema, 'items')) return 'array';
  return 'unspecified';
}

/** Structural display, not a validator or a replica of any MCP client's renderer.
 * Trace paths describe displayed use sites; sourcePath identifies the source schema,
 * including when a local reference expands under a synthetic .$refTarget display edge.
 */
export function renderSchema(schema, {mode = 'faithful'} = {}) {
  if (mode !== 'faithful' && mode !== hypotheticalMode) throw new Error(`Unknown display mode: ${mode}`);
  const trace = [], fallbacks = [];
  const ancestors = new Set();
  function visit(node, path, sourcePath, depth, viaRef) {
    if (!schemaValue(node)) throw new Error(`Expected a JSON Schema object or boolean at ${sourcePath}`);
    const location = {path, sourcePath, depth, ...(viaRef ? {viaRef} : {})};
    if (ancestors.has(node)) {
      trace.push({...location, kind: 'circular-ref', displayType: 'recursive-reference', keywords: {}});
      return;
    }
    const isObject = object(node) && (node.type === 'object' ||
      (Array.isArray(node.type) && node.type.includes('object')) || Object.hasOwn(node, 'properties'));
    if (mode === hypotheticalMode && isObject && Array.isArray(node.oneOf)) {
      const fallback = {path, sourcePath, keywordPath: appendPath(sourcePath, 'oneOf'),
        reason: 'HYPOTHETICAL_OBJECT_ONEOF_FALLBACK'};
      fallbacks.push(fallback);
      trace.push({...location, kind: 'unknown', displayType: 'unknown', keywords: {}, ...fallback});
      return;
    }
    const keywords = object(node) ? Object.fromEntries(Object.entries(node)
      .filter(([key]) => !childKeywords.has(key)).map(([key, value]) => [key, structuredClone(value)])) : {};
    trace.push({...location, kind: 'schema', displayType: displayType(node), keywords});
    if (!object(node)) return;
    ancestors.add(node);
    if (Object.hasOwn(node, '$ref')) {
      const target = localReference(schema, node.$ref, sourcePath);
      visit(target.schema, appendPath(path, '$refTarget'), target.sourcePath, depth + 1, node.$ref);
    }
    for (const child of children(node)) {
      visit(child.schema, pathFor(path, child.tokens), pathFor(sourcePath, child.tokens), depth + 1, viaRef);
    }
    ancestors.delete(node);
  }
  visit(schema, '$', '$', 0);
  const text = trace.map(node => {
    const details = Object.entries(node.keywords).filter(([key]) => key !== 'type')
      .map(([key, value]) => `${key}=${JSON.stringify(value)}`);
    if (node.path !== node.sourcePath) details.push(`source=${node.sourcePath}`);
    if (node.kind === 'unknown') details.push('HYPOTHETICAL: object with oneOf; subtree not expanded');
    return `${'  '.repeat(node.depth)}${node.path}: ${node.displayType}${details.length ? ` ${details.join(' ')}` : ''}`;
  }).join('\n');
  return {mode, text, trace, fallbacks, firstFallback: fallbacks[0] ?? null};
}

/** Read repository inputs only: no tools/list, model request, tool handler or network. */
export async function loadDiagnosticSchemas() {
  return Object.fromEntries(await Promise.all(catalogs.map(async ({name, file}) => {
    const tools = JSON.parse(await readFile(new URL(`../../src/main/resources/llm/${file}`, import.meta.url), 'utf8'));
    const tool = tools.find(candidate => candidate.name === name);
    if (!tool?.input_schema) throw new Error(`Missing ${name}.input_schema in ${file}`);
    return [name, tool.input_schema];
  })));
}

function at(schema, tokens) {
  let value = schema;
  for (const token of tokens) {
    if (!object(value) || !Object.hasOwn(value, token)) return undefined;
    value = value[token];
  }
  return value;
}

function deleteAt(schema, tokens, removedPaths) {
  const parent = at(schema, tokens.slice(0, -1));
  const key = tokens.at(-1);
  if (object(parent) && Object.hasOwn(parent, key)) {
    delete parent[key];
    removedPaths.push(pathFor('$', tokens));
  }
}

// Traverse schema positions, not arbitrary JSON: a property named "not" is not a keyword.
function stripKeywords(schema, names, removedPaths, path = '$') {
  if (!object(schema)) return;
  for (const name of names) {
    if (Object.hasOwn(schema, name)) {
      delete schema[name];
      removedPaths.push(appendPath(path, name));
    }
  }
  for (const child of children(schema)) stripKeywords(child.schema, names, removedPaths, pathFor(path, child.tokens));
}

function controlsFor(schema) {
  const names = [];
  // The union controls isolate the oneOf positions of the former nested configure_workflow schema; a flat schema has none.
  if (at(schema, ['oneOf']) || at(schema, [...transitionTokens, 'oneOf']) || at(schema, [...resolutionTokens, 'oneOf'])) {
    names.push('without-target-oneOf', 'without-resolution-oneOf-and-not', 'without-transition-oneOf', 'without-both-oneOf');
  }
  names.push('without-$ref', 'without-not', 'without-additionalProperties');
  return names.map(name => {
    const copy = structuredClone(schema), removedPaths = [];
    // Isolate the inner graph unions only after removing the new exclusive-target union.
    if (['without-target-oneOf', 'without-resolution-oneOf-and-not', 'without-transition-oneOf', 'without-both-oneOf'].includes(name)) {
      deleteAt(copy, ['oneOf'], removedPaths);
    }
    if (name === 'without-resolution-oneOf-and-not' || name === 'without-both-oneOf') {
      stripKeywords(at(copy, resolutionTokens), ['not'], removedPaths, pathFor('$', resolutionTokens));
      deleteAt(copy, [...resolutionTokens, 'oneOf'], removedPaths);
    }
    if (name === 'without-transition-oneOf' || name === 'without-both-oneOf') {
      deleteAt(copy, [...transitionTokens, 'oneOf'], removedPaths);
    }
    if (name === 'without-$ref') stripKeywords(copy, ['$ref'], removedPaths);
    if (name === 'without-not') stripKeywords(copy, ['not'], removedPaths);
    if (name === 'without-additionalProperties') stripKeywords(copy, ['additionalProperties'], removedPaths);
    return {name, removedPaths, faithful: renderSchema(copy), hypothetical: renderSchema(copy, {mode: hypotheticalMode})};
  });
}

export function diagnoseSchemas(schemas) {
  return {
    hypothesis: 'Hypothetical client fallback: an object with its own oneOf becomes unknown before child traversal; not an actual client reproduction.',
    tools: Object.entries(schemas).map(([name, schema]) => ({
      name,
      faithful: renderSchema(schema),
      hypothetical: renderSchema(schema, {mode: hypotheticalMode}),
      controls: controlsFor(schema),
    })),
  };
}

export function formatDiagnostic(report) {
  const first = rendering => rendering.firstFallback?.path ?? 'none';
  const lines = ['Offline schema-display diagnostic: repository source schemas only; no network.', report.hypothesis];
  for (const tool of report.tools) {
    lines.push(`${tool.name}:`,
      `  faithful: ${tool.faithful.trace.length} nodes; first fallback: ${first(tool.faithful)}`,
      `  hypothetical: ${tool.hypothetical.trace.length} nodes; first fallback: ${first(tool.hypothetical)}`);
    for (const control of tool.controls) {
      lines.push(`  ${control.name}: ${control.removedPaths.length} removals; first fallback: ${first(control.hypothetical)}`);
    }
  }
  lines.push('Controls are disposable, non-equivalent schema copies, not production proposals or validation evidence.');
  return lines.join('\n');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  console.log(formatDiagnostic(diagnoseSchemas(await loadDiagnosticSchemas())));
}
