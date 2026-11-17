import {readFile} from 'node:fs/promises';
import {isDeepStrictEqual} from 'node:util';
import Ajv2020 from 'ajv/dist/2020.js';
import {DEFAULT_RESOLUTIONS, workflowResolutionPreview} from './resolution-mock.mjs';

const tools = JSON.parse(await readFile(new URL('../../src/main/resources/llm/workflow-tools.json', import.meta.url), 'utf8'));
const validate = new Ajv2020({strict:true, strictRequired:false, allErrors:true, allowUnionTypes:true})
  .compile(tools.find(tool => tool.name === 'configure_workflow').input_schema);

// Scripted traces exercise this metric, not the performance of any model.
function previewGraph(workflow) {
  return {...workflow, transitions:workflow.transitions?.map(edge => ({
    ...edge, resolution:{action:edge.resolution?.action ?? 'PRESERVE', value:edge.resolution?.value ?? null},
  }))};
}
function projectGraph(value, expected) {
  if (Array.isArray(expected)) return Array.isArray(value) && value.length === expected.length
    ? value.map((item, index) => projectGraph(item, expected[index])) : value;
  if (expected && typeof expected === 'object') return Object.fromEntries(
    Object.entries(expected).map(([key, field]) => [key, projectGraph(value?.[key], field)]));
  return value;
}
// A later repair never erases errors preceding the first correct preview.
export function gradeWorkflowResolutionFirstPreview({trace, workflow, catalog = DEFAULT_RESOLUTIONS}) {
  const expected = workflowResolutionPreview(workflow, catalog);
  if (expected.status === 'FAILED') throw new Error('Expected workflow must have valid Resolution semantics');
  let schemaErrors = 0;
  let domainValidationErrors = 0;
  let firstCorrectPreviewSequence = null;
  for (const [index, call] of trace.entries()) {
    if (call.tool !== 'configure_workflow') continue;
    const schemaValid = validate(call.arguments);
    if (!schemaValid) schemaErrors++;
    if (call.result?.errorCode === 'VALIDATION_ERROR') domainValidationErrors++;
    if (!schemaValid || call.result?.isError === true || call.isError === true ||
        call.result?.status !== 'WOULD_CREATE' || call.result?.dryRun !== true) continue;
    const actual = workflowResolutionPreview(call.arguments.create, catalog);
    const returned = Object.fromEntries(Object.keys(expected).map(key => [key, call.result[key]]));
    const expectedGraph = previewGraph(expected);
    if (isDeepStrictEqual(actual, expected) &&
        isDeepStrictEqual(projectGraph(previewGraph(returned), expectedGraph), expectedGraph)) {
      firstCorrectPreviewSequence = call.sequence ?? index + 1;
      break;
    }
  }
  return {
    passed:firstCorrectPreviewSequence !== null && schemaErrors === 0 && domainValidationErrors === 0,
    schemaErrors, domainValidationErrors, firstCorrectPreviewSequence,
  };
}
