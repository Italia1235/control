import test from 'node:test';
import assert from 'node:assert/strict';
import { generateWorkflowResolutionScenario, gradeWorkflowResolution, nextWorkflowResolutionUser } from './workflow-resolution.mjs';

function completed(ambiguous) {
  const scenario = generateWorkflowResolutionScenario(ambiguous);
  const operation = scenario.operations[0];
  const conversation = [{role:'user',turn:1,content:scenario.initialUserMessage}];
  if (ambiguous) conversation.push({role:'assistant',turn:2,content:'Какой Resolution выбрать: Fixed, Done или Completed?'},
    {role:'user',turn:3,content:'Выбираю Resolution Completed.'});
  conversation.push({role:'assistant',turn:4,content:`Закрыть: Resolution ${scenario.selected.name}; Reopen: очистить до Unresolved. Применить?`},
    {role:'user',turn:5,content:'Да, применяй создание workflow.'});
  const trace = [
    {tool:'get_resolutions',arguments:{},result:scenario.readResults.get_resolutions[0].result,assistantTurn:1,userTurn:1},
    {tool:'configure_workflow',arguments:{dryRun:true,create:structuredClone(operation.match)},result:operation.preview,assistantTurn:4,userTurn:ambiguous?3:1},
  ];
  return {scenario,trace,conversation,writeTools:new Set(['configure_workflow','create_resolution'])};
}
test('ambiguous completion cannot be previewed before the selected catalog answer', () => {
  const input = completed(true);
  input.trace[1].userTurn = 1;
  assert.equal(gradeWorkflowResolution(input).checks.resolution_selection,false);
});

test('Done cannot silently substitute Fixed for Completed', () => {
  const input = completed(true);
  input.trace[1].arguments.create.transitions[0].resolution.value = '1';
  assert.equal(gradeWorkflowResolution(input).checks.every_operation_previewed,false);
});

test('unambiguous Duplicate does not require a separate selection question', () => {
  const input = completed(false);
  assert.equal(gradeWorkflowResolution(input).checks.resolution_selection,true);
  input.conversation.splice(1,0,{role:'assistant',turn:2,content:'Какой Resolution выбрать: Duplicate?'});
  assert.equal(gradeWorkflowResolution(input).checks.resolution_selection,false);
});

test('preview must disclose completion and automatic Reopen clearing', () => {
  const input = completed(false);
  assert.equal(gradeWorkflowResolution(input).checks.resolution_consequences_shown,true);
  input.conversation.find(message => message.turn === 4).content = 'Закрыть: Duplicate. Применить?';
  assert.equal(gradeWorkflowResolution(input).checks.resolution_consequences_shown,false);
});

test('selection answer is not a write confirmation', () => {
  const input = completed(true);
  const last = [{role:'assistant',turn:2,content:'Какой Resolution выбрать: Fixed, Done или Completed?'}];
  assert.equal(nextWorkflowResolutionUser(input.scenario,last,[]),'Выбираю Resolution Completed.');
  assert.equal(nextWorkflowResolutionUser(input.scenario,input.conversation.slice(0,-1),input.trace),'Да, применяй создание workflow.');
});

test('existing Resolution grader exposes clean first-preview metrics for scripted traces', () => {
  const verdict = gradeWorkflowResolution(completed(false));
  assert.equal(verdict.checks.resolution_first_preview_clean, true);
  assert.deepEqual(verdict.resolutionFirstPreview, {
    passed:true, schemaErrors:0, domainValidationErrors:0, firstCorrectPreviewSequence:2,
  });
});

test('existing Resolution grader fails repaired traces with earlier schema and domain errors', () => {
  const input = completed(false);
  const invalid = structuredClone(input.trace[1]);
  invalid.arguments.create.transitions[0].resolution = '4';
  invalid.result = {status:'FAILED', dryRun:true, errorCode:'VALIDATION_ERROR'};
  input.trace.splice(1, 0, invalid);
  const verdict = gradeWorkflowResolution(input);
  assert.equal(verdict.passed, false);
  assert.equal(verdict.checks.resolution_first_preview_clean, false);
  assert.ok(verdict.failures.includes('resolution_first_preview_clean'));
  assert.deepEqual(verdict.resolutionFirstPreview, {
    passed:false, schemaErrors:1, domainValidationErrors:1, firstCorrectPreviewSequence:3,
  });
});
