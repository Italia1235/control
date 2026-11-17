import test from 'node:test';
import assert from 'node:assert/strict';
import verifyRouting from '../live/scenarios/workflow-validator-routing/verify.mjs';
import { foreignToolCalls, foreignParts } from '../live/validators-common.mjs';

// configure_workflow migration of the workflow-validator-* live scenarios: the "no other edits" check must not be weaker than
// in baseline, where a screen could only change through edit_workflow and conditions only through configure_workflow_conditions
// (both forbidden or separate tools). Now they are parts of the one configure_workflow request, so the check has to see them.
// Synthetic Jira state and trace; no Jira, no model.
const WORKFLOW = 'LOCAL-004-routing-RUN';
const FINISH = 12;
const view = { revision: 'r1', transitions: [{ actionId: FINISH, name: 'Finish', validators: [] }] };
const prepared = { workflow: WORKFLOW, actionIds: { Finish: FINISH }, revision: 'r1', snapshot: [{ actionId: FINISH, name: 'Finish', validators: [] }] };
const conversation = [
  { role: 'user', content: 'Поле Description должно быть обязательным при создании задач.' },
  { role: 'assistant', content: 'Готово.' },
  { role: 'user', content: 'А теперь сделай Description обязательным на переходе Finish.' },
  { role: 'assistant', content: 'Покажу план.' },
];
const required = { template: 'required_fields', parameters: { fieldIds: ['description'] } };
const request = (rule = {}, top = {}) => ({ dryRun: true, existing: { workflow: WORKFLOW, version: 'ACTIVE', expectedRevision: 'r1' },
  transitionRules: [{ actionId: FINISH, addValidators: [required], ...rule }], ...top });

function dialogue(configure) {
  const trace = [];
  const add = (turn, tool, args, result) => trace.push({ seq: trace.length + 1, turn, tool, arguments: args, result, isError: false });
  add(1, 'set_field_required', { project: 'LOCAL004' }, '{"status":"WOULD_CHANGE","planId":"p0"}');
  add(2, 'get_workflow_validators', { workflow: WORKFLOW, version: 'ACTIVE' }, JSON.stringify(view));
  add(2, 'configure_workflow', configure, '{"status":"WOULD_CHANGE","planId":"p1"}');
  return trace;
}
const run = (trace) => verifyRouting({ prepared, trace, conversation, tool: async () => view });
const failing = (checks) => checks.filter((c) => !c.ok).map((c) => c.name);
const FOREIGN_CHECK = 'нет edit_workflow, configure_workflow с create и других правок workflow';

test('validators: a clean required_fields request passes every check (KEEP screen is not a change)', async () => {
  assert.deepEqual(failing(await run(dialogue(request()))), []);
  assert.deepEqual(failing(await run(dialogue(request({ screen: 'KEEP' })))), []);
});

test('validators: configure_workflow carrying anything but validators fails the "no other edits" check', async () => {
  const defects = {
    'a new workflow (create)': request({}, { create: { name: 'LOCAL-004-other' } }),
    'structural changes alongside validators': request({}, { existing: { ...request().existing, changes: [{ op: 'UPDATE_METADATA', description: 'unasked' }] } }),
    'new screens next to the validator': request({}, { newScreens: [{ key: 'k', name: 'LOCAL-004-screen', fieldIds: ['description'] }] }),
    'a SET screen by id': request({ screen: { action: 'SET', screenId: '10001' } }),
    'a SET screen by a new screenKey': request({ screen: { action: 'SET', screenKey: 'k' } }, { newScreens: [{ key: 'k', name: 'LOCAL-004-screen', fieldIds: ['description'] }] }),
    'a non-KEEP screen block': request({ screen: { action: 'KEEP' } }),
    'conditions on the transition': request({ addConditions: { operator: 'AND', rules: [{ template: 'group', parameters: { group: 'jira-administrators' } }] } }),
  };
  for (const [name, configure] of Object.entries(defects)) {
    assert.deepEqual(failing(await run(dialogue(configure))), [FOREIGN_CHECK], name);
  }
});

test('validators: the other forbidden tools still fail the check', async () => {
  for (const tool of ['edit_workflow', 'copy_workflow', 'rename_workflow', 'update_status']) {
    const trace = dialogue(request());
    trace.push({ seq: trace.length + 1, turn: 2, tool, arguments: { dryRun: true }, result: '{}', isError: false });
    assert.deepEqual(failing(await run(trace)), [FOREIGN_CHECK], tool);
  }
});

test('validators: foreignParts names each foreign part once', () => {
  assert.deepEqual(foreignParts(request()), []);
  assert.deepEqual(foreignParts({ create: {}, newScreens: [{}], transitionRules: [{ screen: { action: 'SET' }, addConditions: {} }, { screen: { action: 'SET' } }] }),
    ['create', 'newScreens', 'screen', 'addConditions']);
  assert.equal(foreignToolCalls([{ tool: 'configure_workflow', arguments: request({ screen: 'KEEP' }) }]).length, 0);
  assert.deepEqual(foreignParts(request({ screen: null })), ['screen']);
});
