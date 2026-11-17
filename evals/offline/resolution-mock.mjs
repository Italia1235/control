import {randomUUID} from 'node:crypto';

export const DEFAULT_RESOLUTIONS = Object.freeze([
  Object.freeze({id:'1', name:'Completed', description:'Process completed'}),
]);
const GLOBAL_WARNING = 'Resolution is global for all Jira, not just this workflow. Resolution creation and workflow creation are separate operations: if workflow creation fails, the global Resolution remains. Build a fresh workflow preview using its real ID.';
const own = (object, key) => Object.hasOwn(object, key);
const failure = (dryRun, errorCode, message) => ({status:'FAILED', dryRun, resolution:null, message, errorCode, planId:null, planExpiresAt:null});
const response = (status, dryRun, resolution, message) => ({status, dryRun, resolution:structuredClone(resolution), message, errorCode:null, planId:null, planExpiresAt:null});
function allowed(args, keys) {
  if (!args || typeof args !== 'object' || Array.isArray(args) || Object.keys(args).some(key => !keys.includes(key))) throw new Error('Invalid Resolution tool arguments');
}
const valuesOf = catalog => typeof catalog.snapshot === 'function' ? catalog.snapshot() : catalog;

// Mode and response shapes mirror ResolutionTools and ResolutionServiceImpl.
export function createResolutionCatalog(catalog = DEFAULT_RESOLUTIONS, options = {}) {
  const values = structuredClone(catalog);
  if (!Array.isArray(values) || values.some(value => typeof value.id !== 'string' || !value.id.trim() || typeof value.name !== 'string' || typeof value.description !== 'string' && value.description != null) || new Set(values.map(value => value.id)).size !== values.length) throw new Error('Invalid Resolution catalog');
  const plans = new Map();
  const fingerprint = () => JSON.stringify([...values].sort((a,b) => a.id.localeCompare(b.id)));
  let nextId = options.startId ?? Math.max(0, ...values.map(value => /^\d+$/u.test(value.id) ? Number(value.id) : 0)) + 1;
  const prepare = args => {
    const name = args.name?.trim() ?? '';
    if (!name || ['unresolved','нерешено'].includes(name.toLowerCase())) return failure(true,'VALIDATION_ERROR','Provide a non-blank Resolution name. Unresolved / Нерешено is absence of Resolution, not a catalog value.');
    const description = args.description ?? '';
    const matches = values.filter(value => value.name.toLowerCase() === name.toLowerCase());
    if (matches.length > 1) return failure(true,'NAME_CONFLICT','Several Resolutions match this name; resolve the ambiguity without overwriting existing values.');
    if (matches.length && (matches[0].description ?? '') !== description) return failure(true,'NAME_CONFLICT','A Resolution with this name has a different description; the existing value was not changed.');
    if (matches.length) return response('EXISTS',true,matches[0],'An identical Resolution already exists; nothing was changed.');
    return response('WOULD_CREATE',true,{id:null,name,description},`${GLOBAL_WARNING} Show this preview and obtain confirmation before applying planId.`);
  };
  const validate = args => {
    allowed(args,['dryRun','name','description','planId']);
    if (typeof args.dryRun !== 'boolean' || ['name','description','planId'].some(key => own(args,key) && typeof args[key] !== 'string')) return failure(args.dryRun !== false,'VALIDATION_ERROR','Send valid Resolution arguments and dryRun explicitly as true or false.');
    if (args.dryRun && own(args,'planId')) return failure(true,'VALIDATION_ERROR','Preview takes name and description, not planId.');
    if (!args.dryRun && (own(args,'name') || own(args,'description') || !args.planId?.trim())) return failure(false,'VALIDATION_ERROR','Apply takes only dryRun=false and the confirmed preview\'s planId.');
    return null;
  };
  return {
    snapshot: () => structuredClone(values),
    execute(tool, args = {}) {
      if (tool === 'get_resolutions') {
        allowed(args,['query']);
        if (own(args,'query') && typeof args.query !== 'string') throw new Error('query must be a string');
        const query = args.query?.trim() ?? '';
        return structuredClone(values.filter(value => !query || value.id === query || value.name.toLowerCase() === query.toLowerCase()));
      }
      if (tool !== 'create_resolution') throw new Error(`Unsupported Resolution tool: ${tool}`);
      if (args.dryRun === false) throw new Error('Direct apply prohibited');
      const invalid = validate(args);
      if (invalid) return invalid;
      const preview = prepare(args);
      if (preview.status !== 'WOULD_CREATE') return preview;
      const planId = options.planId ? options.planId() : randomUUID();
      const planExpiresAt = Date.now() + (options.planLifetimeMs ?? 300000);
      plans.set(planId,{resolution:preview.resolution,fingerprint:fingerprint(),planExpiresAt});
      return {...preview,planId,planExpiresAt};
    },
    apply(args) {
      const invalid = validate(args);
      if (invalid) return invalid;
      if (args.dryRun !== false) return failure(false,'VALIDATION_ERROR','Managed apply requires dryRun=false.');
      const planId = args.planId.trim();
      const plan = plans.get(planId);
      if (plan) plans.delete(planId);
      if (!plan || plan.planExpiresAt <= Date.now() || plan.fingerprint !== fingerprint()) return failure(false,'PREVIEW_STALE','Preview is missing, expired, consumed, or the Resolution catalog changed. Build a fresh preview and obtain confirmation.');
      const id = String(options.nextId ? options.nextId() : nextId++);
      if (!id.trim() || values.some(value => value.id === id)) throw new Error('Resolution allocator returned an invalid or existing ID');
      const created = {...plan.resolution,id};
      values.push(created);
      return response('CREATED',false,created,GLOBAL_WARNING);
    },
  };
}

// A stored graph uses minimal SET/CLEAR; preserving edges have no native Resolution function.
export function normalizeWorkflowResolution(workflow, catalog = DEFAULT_RESOLUTIONS) {
  const normalized = structuredClone(workflow);
  const values = valuesOf(catalog);
  const invalid = message => ({status:'FAILED',dryRun:true,errorCode:'VALIDATION_ERROR',message});
  const key = name => typeof name === 'string' ? name.trim().toLowerCase() : null;
  const statuses = new Map((normalized.statuses ?? []).map(status => [key(status.name),status]));
  for (const [index, edge] of (normalized.transitions ?? []).entries()) {
    if (!edge || own(edge,'fromAny') && typeof edge.fromAny !== 'boolean') return invalid(`transitions[${index}] has invalid source arguments.`);
    const hasFrom = typeof edge.from === 'string' && Boolean(edge.from.trim());
    if (hasFrom === (edge.fromAny === true)) return invalid(`transitions[${index}] must have exactly one of from and fromAny:true.`);
    const target = statuses.get(key(edge.to));
    const source = edge.fromAny === true ? null : statuses.get(key(edge.from));
    if (!target || edge.fromAny !== true && !source) return invalid(`transitions[${index}] refers to an unknown status.`);
    const targetDone = target.category === 'DONE';
    const mustSet = targetDone && (!source || source !== target);
    const mustClear = !targetDone && (!source || source.category === 'DONE');
    const rule = edge.resolution;
    if (rule == null) {
      if (mustSet) return invalid(`transitions[${index}].resolution requires SET with a resolution ID from get_resolutions.`);
      if (mustClear) edge.resolution = {action:'CLEAR'};
      else delete edge.resolution;
      continue;
    }
    if (typeof rule !== 'object' || Array.isArray(rule) || Object.keys(rule).some(field => !['action','value'].includes(field)) || !['SET','CLEAR'].includes(rule.action)) return invalid(`transitions[${index}].resolution.action must be SET or CLEAR.`);
    if (rule.action === 'CLEAR') {
      if (own(rule,'value') || targetDone) return invalid(`transitions[${index}].resolution CLEAR requires a non-DONE target and no value.`);
      edge.resolution = {action:'CLEAR'};
    } else {
      if (!targetDone || typeof rule.value !== 'string' || !rule.value || rule.value !== rule.value.trim() || !values.some(value => value.id === rule.value)) return invalid(`transitions[${index}].resolution SET requires a DONE target and an existing canonical catalog ID.`);
      edge.resolution = {action:'SET',value:rule.value};
    }
  }
  return normalized;
}

export function workflowResolutionPreview(workflow, catalog = DEFAULT_RESOLUTIONS) {
  const normalized = normalizeWorkflowResolution(workflow,catalog);
  if (normalized.status === 'FAILED') return normalized;
  const values = valuesOf(catalog);
  for (const edge of normalized.transitions ?? []) {
    const resolution = edge.resolution;
    const value = resolution?.action === 'SET' ? values.find(item => item.id === resolution.value) : null;
    edge.resolution = {action:resolution?.action ?? 'PRESERVE',value:value?.id ?? null,name:value?.name ?? null,description:value?.description ?? null};
  }
  return normalized;
}
