import test from 'node:test';
import assert from 'node:assert/strict';
import { scenarioSimulator } from './run.mjs';
import { generateWorkflowValidatorsScenario } from './workflow-validators.mjs';

function fixture(options = {}) {
  const scenario = generateWorkflowValidatorsScenario('workflow_validators_inactive');
  Object.assign(scenario.operations[0], options);
  const execute = scenarioSimulator(scenario);
  const read = () => execute('get_workflow_validators', { workflow: scenario.world.name, version: 'ACTIVE' });
  const before = read();
  const action = before.transitions.find(t => t.name === 'Done');
  const preview = execute('configure_workflow', { dryRun: true, existing: { workflow: before.workflow, version: 'ACTIVE', expectedRevision: before.revision }, transitionRules: [{ actionId: action.actionId, addValidators: [{ template: 'required_fields', parameters: { fieldIds: ['description'] } }] }] });
  assert.equal(preview.status, 'WOULD_CHANGE');
  const args = { toolName: 'configure_workflow', arguments: { dryRun: false, planId: preview.planId } };
  const context = { userTurn: 2, assistantTurn: 2, conversation: [
    { role: 'user', turn: 1, content: scenario.initialUserMessage },
    { role: 'assistant', turn: 1, content: '| Изменение | Где |\n| --- | --- |\n| Description required | Done |\nПрименить?' },
    { role: 'user', turn: 2, content: 'Да' },
  ] };
  return { execute, read, args, context, before };
}

test('a lost start response is recovered by the same plan without applying twice', () => {
  const { execute, read, args, context, before } = fixture({ loseStartResponse: true });
  assert.throws(() => execute('start_operation', args, context), /response was lost/);
  const afterMutation = read();
  assert.notEqual(afterMutation.revision, before.revision);
  const recovery = execute('start_operation', args, context);
  assert.equal(recovery.alreadyStarted, true);
  const outcome = execute('get_operation', { operationId: recovery.operationId });
  assert.equal(outcome.status, 'COMPLETED');
  assert.equal(outcome.result.status, 'UPDATED_INACTIVE');
  assert.deepEqual(read(), afterMutation);
  const session = execute('list_change_sessions', {}).sessions[0];
  assert.equal(execute('list_session_changes', { sessionId: session.sessionId }).operations.length, 1);
});

test('queued and running observations do not expose the terminal domain result prematurely', () => {
  const { execute, args, context } = fixture({ polls: [{ status: 'QUEUED' }, { status: 'RUNNING' }] });
  const started = execute('start_operation', args, context);
  assert.equal(started.status, 'QUEUED');
  for (const status of ['QUEUED', 'RUNNING']) {
    const observed = execute('get_operation', { operationId: started.operationId });
    assert.equal(observed.status, status);
    assert.equal(observed.result, undefined);
  }
  const terminal = execute('get_operation', { operationId: started.operationId });
  assert.equal(terminal.status, 'COMPLETED');
  assert.equal(terminal.result.status, 'UPDATED_INACTIVE');
});
