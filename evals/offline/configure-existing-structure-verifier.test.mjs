import test from 'node:test';
import assert from 'node:assert/strict';
import verify from '../live/scenarios/workflow-configure-existing-structure/verify.mjs';
import { readState } from '../live/existing-structure-common.mjs';

// Synthetic Control reader responses and dialogue events; no Jira, model, REST or descriptor shortcuts.
async function fixture() {
  const workflow = 'L012-REGRESSION-structure';
  const newScreen = 'L012-REGRESSION-completion-screen';
  const statuses = ['Todo', 'Working', 'Done'].map((name) => ({ name: `L012-REGRESSION-${name}` }));
  const resolution = { id: '10000', name: 'Done' };
  const graph = { revision: 'r1', active: false, hasDraft: false, consumers: { projectCount: 0, schemeCount: 0 },
    steps: statuses.map((status, i) => ({ stepId: i + 1, statusId: String(i + 1), name: status.name, initial: i === 0 })),
    transitions: [
      { actionId: 1, name: 'Create', kind: 'INITIAL', sourceStepIds: [], targetStepIds: [1] },
      { actionId: 11, name: 'Start work', kind: 'LOCAL', sourceStepIds: [1], targetStepIds: [2], resolution: { action: 'PRESERVE' } },
      { actionId: 21, name: 'Control return', kind: 'LOCAL', sourceStepIds: [2], targetStepIds: [1], resolution: { action: 'PRESERVE' } },
      { actionId: 31, name: 'Seed finish', kind: 'LOCAL', sourceStepIds: [2], targetStepIds: [3], resolution: { action: 'SET', value: resolution.id } },
    ] };
  const initialValidator = { kind: 'OTHER', type: 'class', className: 'com.atlassian.jira.workflow.validator.PermissionValidator' };
  const before = {
    graph,
    validators: { scriptRunner: { installed: true, enabled: true }, transitions: graph.transitions.map((t) => ({ actionId: t.actionId, validators: t.actionId === 1 ? [initialValidator] : [] })) },
    conditions: { transitions: graph.transitions.map((t) => ({ actionId: t.actionId, root: null })) },
  };
  const readers = (state) => async (name) => {
    if (name === 'get_workflow') return state.graph;
    if (name === 'get_workflow_validators') return state.validators;
    if (name === 'get_workflow_conditions') return state.conditions;
    if (name === 'get_screen_contents') return { screen: { id: 777, name: newScreen },
      tabs: [{ name: 'Field Tab', fields: [{ fieldId: 'summary' }, { fieldId: 'assignee' }] }] };
    assert.fail(`Unexpected verifier tool ${name}`);
  };
  const prepared = { workflow, statuses, resolution, newScreen, graph, actions: { 'Start work': 11, 'Control return': 21 },
    state: await readState(readers(before), workflow) };
  const actual = structuredClone(before);
  actual.graph.revision = 'r2';
  actual.graph.transitions.find((t) => t.actionId === 11).name = 'Started';
  actual.graph.transitions.push({ actionId: 32, name: 'Complete', kind: 'LOCAL', sourceStepIds: [2], targetStepIds: [3],
    screenId: '777', resolution: { action: 'SET', value: resolution.id, name: resolution.name } });
  actual.validators.transitions.push({ actionId: 32, validators: [{ kind: 'REQUIRED_FIELDS', fieldIds: ['assignee'], conditional: false }] });
  actual.conditions.transitions.push({ actionId: 32, root: null });
  const request = { dryRun: true, existing: { workflow, version: 'ACTIVE', expectedRevision: 'r1', changes: [
    { op: 'UPDATE_TRANSITION', actionId: 11, name: 'Started' },
    { op: 'ADD_TRANSITION', transitionKey: 'complete', name: 'Complete', source: { stepId: 2 }, targetStepId: 3,
      resolution: { action: 'SET', value: resolution.id } },
  ] }, newScreens: [{ key: 'form', name: newScreen, fieldIds: ['summary', 'assignee'] }],
  transitionRules: [{ transitionKey: 'complete', screen: { action: 'SET', screenKey: 'form' },
    addValidators: [{ template: 'required_fields', parameters: { fieldIds: ['assignee'] } }] }] };
  const trace = [];
  const entry = (turn, tool, args, result) => trace.push({ seq: trace.length + 1, turn, tool, arguments: args, result });
  entry(1, 'get_workflow', { workflow, version: 'ACTIVE' }, graph);
  entry(1, 'get_resolutions', {}, [resolution]);
  entry(2, 'configure_workflow', request, { dryRun: true, status: 'WOULD_CHANGE', planId: 'plan-1' });
  entry(3, 'start_operation', { toolName: 'configure_workflow', arguments: { dryRun: false, planId: 'plan-1' } }, { operationId: 'op-1', status: 'QUEUED' });
  entry(3, 'get_operation', { operationId: 'op-1' }, { status: 'COMPLETED', result: { status: 'UPDATED_INACTIVE',
    transitions: [{ transitionKey: 'complete', actionId: 32, screenId: '777' }], objects: [{ kind: 'SCREEN', key: 'form', screenId: '777' }] } });
  const conversation = [
    { role: 'user', content: 'Переименуй Start work и добавь Complete с новым экраном и обязательным Assignee.' },
    { role: 'assistant', content: '| Resolution | ID |\n|---|---|\n| Done | 10000 |\nКакое значение выбрать?' },
    { role: 'user', content: 'Done, 10000. Покажи общий план.' },
    { role: 'assistant', content: `| Объект | Последствия |\n|---|---|\n| ${workflow} | Start work → Started; Complete: Working → Done, Resolution Done |\n| ${newScreen} | Summary, Assignee; Assignee обязателен на Complete |\nПрименить этот общий план?` },
    { role: 'user', content: 'Да, подтверждаю этот общий план.' },
    { role: 'assistant', content: 'Операция успешно завершена.' },
  ];
  return { prepared, actual, request, trace, conversation, tool: readers(actual) };
}

const failures = (checks) => checks.filter((c) => !c.ok);
const contractFailures = (checks) => failures(checks).filter((c) => c.class === 'contract');
const assertContractFailure = (checks, fragment) => assert.ok(contractFailures(checks).some((c) => c.name.includes(fragment)), JSON.stringify(checks));

test('existing structure: requested screen, Resolution and assignee validator pass, including an earlier Resolution catalog table', async () => {
  assert.deepEqual(failures(await verify(await fixture())), []);
});

test('existing structure: every unrequested transition rule field is rejected in the plan', async () => {
  for (const extra of [
    { addConditions: { operator: 'AND', rules: [{ template: 'group', parameters: { group: 'jira-administrators' } }] } },
    { addConditions: { operator: 'AND', rules: [] } },
    { removeConditions: [] }, { replaceConditions: [] }, { removeValidators: [] }, { replaceValidators: [] },
    { resolution: { action: 'CLEAR' } }, { actionId: 32 }, { unknownRule: true },
  ]) {
    const world = await fixture();
    Object.assign(world.request.transitionRules[0], extra);
    assertContractFailure(await verify(world), 'One combined existing plan');
  }
  const world = await fixture();
  world.request.transitionRules[0].screen.screenId = '778';
  assertContractFailure(await verify(world), 'One combined existing plan');
});

test('existing structure: actual extra conditions fail even when the preview requested no conditions', async () => {
  for (const root of [
    { kind: 'GROUP', group: 'jira-administrators' },
    { kind: 'COMPOUND', operator: 'AND', children: [{ kind: 'GROUP', group: 'jira-administrators' }] },
    { kind: 'OTHER', type: 'class', className: 'UnrequestedCondition' },
    { kind: 'COMPOUND', operator: 'AND', children: [] },
  ]) {
    const world = await fixture();
    world.actual.conditions.transitions.find((t) => t.actionId === 32).root = root;
    const checks = await verify(world);
    assertContractFailure(checks, 'with no conditions');
    assert.equal(checks.find((c) => c.name.startsWith('One combined existing plan')).ok, true);
  }
});

test('existing structure: unrequested or altered actual validators, screens and Resolution remain rejected', async () => {
  const mutations = [
    (world) => world.actual.validators.transitions.find((t) => t.actionId === 32).validators.push({ kind: 'COMMENT_REQUIRED' }),
    (world) => { world.actual.validators.transitions.find((t) => t.actionId === 32).validators[0].fieldIds = ['description']; },
    (world) => { world.actual.validators.transitions.find((t) => t.actionId === 32).validators[0].conditional = true; },
    (world) => { world.actual.graph.transitions.find((t) => t.actionId === 32).screenId = null; },
    (world) => { world.actual.graph.transitions.find((t) => t.actionId === 32).resolution = { action: 'CLEAR' }; },
  ];
  for (const mutate of mutations) {
    const world = await fixture();
    mutate(world);
    assert.ok(contractFailures(await verify(world)).length > 0);
  }
});

test('existing structure: a catalog table does not substitute for the consequences table on the preview turn', async () => {
  const world = await fixture();
  world.conversation[3].content = `Изменить ${world.prepared.workflow}?`;
  const checks = await verify(world);
  assert.deepEqual(contractFailures(checks), []);
  assert.ok(failures(checks).some((c) => c.class === 'advisory' && c.name.startsWith('Consequences presentation')));
});
