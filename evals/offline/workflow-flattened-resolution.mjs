import './offline-network.mjs';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import Ajv from 'ajv';
import { SimulatedToolBackend } from './tool-backend.mjs';
import { workflowResolutionPreview } from './resolution-mock.mjs';

export const violations = ['string', 'missing_action', 'set_without_value', 'resolutionId', 'clear_done', 'omitted_done'];
const load = async file => JSON.parse(await readFile(new URL(`../../src/main/resources/llm/${file}`, import.meta.url), 'utf8'));
const request = 'Create workflow Offline Delivery: Open = TO_DO, QA = IN_PROGRESS, Released = DONE, нужна Fixed. Open → QA → Released → QA.';

// This is the observed documentation surface, NOT a claim to implement the real client's renderer.
function flattened(tool) {
  return { name: tool.name, description: tool.description, parameters: Object.fromEntries(
    Object.entries(tool.input_schema.properties).map(([name, schema]) => [name,
      schema.type === 'array' ? 'unknown[]' : schema.type])) };
}

export function scriptedResolutionModel(violation) {
  let step = 0;
  return async ({ messages }) => {
    if (step++ === 0) return { tool: 'get_resolutions', arguments: {} };
    const catalog = messages.find(m => m.tool === 'get_resolutions')?.result;
    const fixed = catalog?.find(r => r.name === 'Fixed');
    if (!fixed) throw new Error('Fixed must come from the catalog response');
    const graph = { name: 'Offline Delivery', statuses: [
      { name: 'Open', category: 'TO_DO' }, { name: 'QA', category: 'IN_PROGRESS' },
      { name: 'Released', category: 'DONE' }], initialStatus: 'Open', transitions: [
      { name: 'QA', from: 'Open', to: 'QA' },
      { name: 'Released', from: 'QA', to: 'Released', resolution: { action: 'SET', value: fixed.id } },
      { name: 'Reopen', from: 'Released', to: 'QA' }] };
    graph.transitions.forEach((transition, index) => { transition.key = `transition-${index + 1}`; });
    if (step === 2 && violation) {
      const edge = graph.transitions[1];
      if (violation === 'string') edge.resolution = fixed.id;
      if (violation === 'missing_action') edge.resolution = { value: fixed.id };
      if (violation === 'set_without_value') edge.resolution = { action: 'SET' };
      if (violation === 'resolutionId') { delete edge.resolution; edge.resolutionId = fixed.id; }
      if (violation === 'clear_done') edge.resolution = { action: 'CLEAR' };
      if (violation === 'omitted_done') delete edge.resolution;
    }
    return { tool: 'configure_workflow', arguments: {dryRun: true, create: graph} };
  };
}

export async function runFlattenedResolution(model, { fixedId = 'offline-fixed-73' } = {}) {
  const workflow = (await load('workflow-tools.json')).find(t => t.name === 'configure_workflow');
  const resolutions = (await load('resolution-tools.json')).find(t => t.name === 'get_resolutions');
  const validate = new Ajv({ strict: false, allErrors: true }).compile(workflow.input_schema);
  const catalog = [{ id: fixedId, name: 'Fixed', description: 'Synthetic offline catalog only' }];
  const backend = new SimulatedToolBackend((tool, args) => {
    if (tool === 'get_resolutions') return structuredClone(catalog);
    if (tool !== 'configure_workflow' || args.dryRun !== true) throw new Error('Only read-only offline previews allowed');
    if (!validate(args)) return { status: 'FAILED', errorCode: 'VALIDATION_ERROR',
      message: validate.errors.map(e => `${e.instancePath}: ${e.message}`).join('; ') };
    const result = workflowResolutionPreview(args.create, catalog);
    return result.status === 'FAILED' ? result : { ...result, status: 'WOULD_CREATE' };
  }, { tools: [flattened(workflow), flattened(resolutions)] });
  const metadata = await backend.initialize();
  const modelInput = { request, tools: metadata.tools };
  const trace = [];
  let preview = null, errors = 0;
  for (let turn = 0; turn < 5; turn++) {
    const action = await model({ ...structuredClone(modelInput), messages: structuredClone(trace) });
    if (!action) break;
    const result = await backend.call(action.tool, action.arguments);
    trace.push({ ...structuredClone(action), result });
    if (result.errorCode === 'VALIDATION_ERROR') errors++;
    if (action.tool === 'configure_workflow' && result.status === 'WOULD_CREATE') { preview = result; break; }
  }
  return { passed: preview !== null && errors === 0, validation_errors_before_first_successful_preview: errors,
    preview, trace, modelInput };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  for (const variant of ['correct', ...violations]) {
    const result = await runFlattenedResolution(scriptedResolutionModel(variant === 'correct' ? undefined : variant));
    console.log(JSON.stringify({ variant, passed: result.passed,
      validation_errors_before_first_successful_preview: result.validation_errors_before_first_successful_preview,
      preview: result.preview?.status ?? null }));
    if (result.passed !== (variant === 'correct')) process.exitCode = 1;
  }
}
