import test from 'node:test';
import assert from 'node:assert/strict';
import {generateWorkflowResolutionScenario} from './workflow-resolution.mjs';
import {workflowResolutionPreview} from './resolution-mock.mjs';
import {gradeWorkflowResolutionFirstPreview} from './workflow-resolution-first-preview.mjs';

const scenario = generateWorkflowResolutionScenario(false);
const catalog = scenario.readResults.get_resolutions[0].result;
const workflow = scenario.operations[0].match;
function preview(args = workflow) {
  const result = workflowResolutionPreview(args, catalog);
  return {tool:'configure_workflow', arguments:{dryRun:true, create:structuredClone(args)}, result:result.status === 'FAILED'
    ? result : {...result, dryRun:true, status:'WOULD_CREATE', planId:'scripted-plan'}};
}
const grade = trace => gradeWorkflowResolutionFirstPreview({trace, workflow, catalog});

// No model is called: success establishes metric behavior only.
test('clean scripted first preview has zero schema and domain errors', () => {
  assert.deepEqual(grade([preview()]), {passed:true, schemaErrors:0, domainValidationErrors:0, firstCorrectPreviewSequence:1});
});

for (const [family, resolution] of [
  ['string resolution', '4'],
  ['missing action', {value:'4'}],
  ['lowercase action', {action:'set', value:'4'}],
  ['unknown action', {action:'ASSIGN', value:'4'}],
  ['extra resolutionId', {action:'SET', value:'4', resolutionId:'4'}],
]) test(`first-preview metric fails after ${family}, even when repaired`, () => {
  const args = structuredClone(workflow);
  args.transitions[0].resolution = resolution;
  // Client schema rejection prevents dispatch, so no domain response is recorded.
  const invalid = {tool:'configure_workflow', arguments:{dryRun:true, create:args}, result:{errorCode:'SCHEMA_ERROR'}};
  assert.deepEqual(grade([invalid, preview()]), {passed:false, schemaErrors:1, domainValidationErrors:0, firstCorrectPreviewSequence:2});
  // A server-side rejection is counted separately when dispatch does occur.
  assert.deepEqual(grade([preview(args), preview()]), {passed:false, schemaErrors:1, domainValidationErrors:1, firstCorrectPreviewSequence:2});
});

for (const [family, mutate] of [
  ['unknown catalog ID', args => { args.transitions[0].resolution.value = '999'; }],
  ['SET on non-DONE target', args => { args.transitions[1].resolution = {action:'SET', value:'4'}; }],
  ['missing mandatory SET', args => { delete args.transitions[0].resolution; }],
]) test(`schema-valid domain failure counts before correct preview: ${family}`, () => {
  const args = structuredClone(workflow);
  mutate(args);
  assert.deepEqual(grade([preview(args), preview()]), {passed:false, schemaErrors:0, domainValidationErrors:1, firstCorrectPreviewSequence:2});
});

test('errors after the first correct preview are outside this metric', () => {
  const invalid = structuredClone(workflow);
  invalid.transitions[0].resolution = '4';
  assert.deepEqual(grade([preview(), preview(invalid)]), grade([preview()]));
});

test('no preview and an incorrect successful preview cannot pass with zero errors', () => {
  assert.equal(grade([]).passed, false);
  const wrong = structuredClone(workflow);
  wrong.transitions[0].resolution.value = '1';
  assert.deepEqual(grade([preview(wrong)]), {passed:false, schemaErrors:0, domainValidationErrors:0, firstCorrectPreviewSequence:null});
});

test('an unsuccessful response cannot masquerade as a correct preview', () => {
  const call = preview();
  call.result.status = 'FAILED';
  assert.equal(grade([call]).passed, false);
});

test('server graph metadata and minimal CLEAR representation do not reject a correct preview', () => {
  const call = preview();
  call.result.statuses[0].statusId = '10001';
  call.result.transitions[0].actionId = 21;
  call.result.transitions[1].resolution = {action:'CLEAR'};
  assert.equal(grade([call]).passed, true);
});

test('a wrong returned graph cannot pass even with correct submitted arguments', () => {
  const call = preview();
  call.result.transitions[0].to = 'Open';
  assert.equal(grade([call]).passed, false);
});

for (const location of ['call', 'result']) test(`isError at ${location} rejects a contradictory successful payload`, () => {
  const call = preview();
  if (location === 'call') call.isError = true;
  else call.result.isError = true;
  assert.equal(grade([call]).passed, false);
});
