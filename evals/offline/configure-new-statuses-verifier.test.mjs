import test from 'node:test';
import assert from 'node:assert/strict';
import verify from '../live/scenarios/workflow-configure-new-statuses/verify.mjs';
import setup from '../live/scenarios/workflow-configure-new-statuses/setup.mjs';
import { traceChecks } from '../live/checks.mjs';

// workflow-configure-new-statuses verifier on synthetic Jira state and trace (no Jira, no model): the complete dialogue passes, and each
// missing behaviour fails the contract check that guards it (no vacuous pass); presentation defects stay advisory.
const P = 'L010-RUN';
const wanted = [{ name: `${P}-Бэк`, category: 'TO_DO' }, { name: `${P}-Рабим`, category: 'IN_PROGRESS' }, { name: `${P}-Готовченко`, category: 'DONE' }];
const prepared = { prefix: P, workflow: `${P}-wf`, statuses: wanted, transitions: { first: 'В работу', second: 'Завершить' }, role: { id: 10002, name: 'Administrators' } };
const entry = (trace, turn, tool, args, result) => { trace.push({ seq: trace.length + 1, turn, tool, arguments: args, result, isError: false }); return trace.at(-1); };
const failing = (checks) => checks.filter((c) => !c.ok);

function world(o = {}) {
  const trace = [];
  const diff = o.diff ?? [`CREATE_WORKFLOW: ${prepared.workflow}`, 'ADD_VALIDATOR: 2 required_fields [assignee]', 'ADD_CONDITION: 3 project_role [10002]'];
  const table = `| Объект | Что |\n|---|---|\n| Workflow | ${prepared.workflow} |\n| Статусы | ${wanted.map((w) => w.name).join(', ')} — новые, глобальные (общие для всей Jira) |\n| Переходы | В работу, Завершить |\n\nПрименить?`;
  const conversation = [{ role: 'user', content: 'Создай workflow' }, { role: 'assistant', content: o.table ?? table }, { role: 'user', content: 'Да, применяй' },
    { role: 'assistant', content: 'Готово.' }];
  for (let i = 0; i < (o.previews ?? 1); i++) {
    entry(trace, 1, 'configure_workflow', { dryRun: true, create: { name: prepared.workflow } }, { dryRun: true, status: 'WOULD_CREATE', planId: `p${i + 1}`, diff });
  }
  const steps = o.steps ?? [1, 2, 3].map((n) => ({ key: `status:${n}`, kind: 'STATUS', writeStatus: 'APPLIED', verification: 'MATCH', id: String(100 + n) }));
  entry(trace, 2, 'start_operation', { toolName: 'configure_workflow', arguments: { dryRun: false, planId: 'p1' } }, { operationId: 'op1', status: 'QUEUED' });
  entry(trace, 2, 'get_operation', { operationId: 'op1' }, { status: o.operation ?? 'COMPLETED', operation: 'configure_workflow',
    result: { status: o.result ?? 'CREATED', outcome: o.result ?? 'CREATED', execution: { steps } } });
  entry(trace, 2, 'get_workflow', { workflow: prepared.workflow, version: 'ACTIVE' }, { revision: 'r' });

  const listed = o.statuses ?? wanted.map((w, i) => ({ id: String(101 + i), name: w.name, category: w.category }));
  const validators = o.validators ?? [{ kind: 'REQUIRED_FIELDS', fieldIds: ['assignee'], conditional: false }];
  const role = { kind: 'PROJECT_ROLE', roleIds: o.roleIds ?? [10002], roles: [{ id: 10002, name: 'Administrators' }], inverted: o.inverted ?? false };
  const root = 'root' in o ? o.root : { kind: 'COMPOUND', operator: 'AND', children: [role] };
  const resolution = o.resolution ?? { action: 'SET', value: '10000' };
  const view = { revision: 'r', active: false, hasDraft: false, consumers: { projectCount: 0, schemeCount: 0 },
    steps: o.steps3 ?? [{ stepId: 1, name: 'a', statusId: '101', initial: true }, { stepId: 2, name: 'b', statusId: '102', initial: false }, { stepId: 3, name: 'c', statusId: '103', initial: false }],
    transitions: [{ actionId: 1, name: 'Create', kind: 'INITIAL', sourceStepIds: [], targetStepIds: [1] },
      { actionId: 2, name: 'В работу', kind: 'LOCAL', sourceStepIds: [1], targetStepIds: [2] },
      { actionId: 3, name: 'Завершить', kind: 'LOCAL', sourceStepIds: [2], targetStepIds: [3], resolution },
      ...(o.extraMove ? [{ actionId: 4, name: 'Лишний', kind: 'LOCAL', sourceStepIds: [1], targetStepIds: [3] }] : [])] };
  const tool = async (name) => ({
    list_statuses: { statuses: listed }, get_workflow: view,
    get_workflow_validators: { transitions: [{ actionId: 2, validators }, { actionId: 3, validators: [] }], scriptRunner: { supported: true } },
    get_workflow_conditions: { transitions: [{ actionId: 2, root: o.firstRoot ?? null }, { actionId: 3, root }] },
    get_resolutions: [{ id: '10000', name: 'Готово' }],
  })[name];
  return { prepared, trace, conversation, tool, rest: async () => ({}), lifecycle: {} };
}
const verifyWorld = (w) => verify(w);

test('workflow-configure-new-statuses: a complete one-plan dialogue passes every check', async () => {
  const w = world();
  const checks = [...traceChecks(w.trace, w.conversation, { classifyPresentation: true }), ...await verifyWorld(w)];
  assert.deepEqual(failing(checks).map((c) => c.name), []);
  assert.ok(checks.every((c) => ['contract', 'advisory'].includes(c.class)));
  assert.ok(checks.filter((c) => c.class === 'contract').length >= 8);
});

test('workflow-configure-new-statuses: each missing behaviour fails the contract check that guards it', async () => {
  const miss = [];
  const cases = [
    ['second preview', { previews: 2 }, 'один preview'],
    ['no validator row in diff', { diff: ['CREATE_WORKFLOW: x', 'ADD_CONDITION: 3 project_role'] }, 'ADD_VALIDATOR'],
    ['no condition row in diff', { diff: ['ADD_VALIDATOR: 2 required_fields [assignee]'] }, 'ADD_CONDITION'],
    ['validator without assignee', { diff: ['ADD_VALIDATOR: 2 required_fields [description]', 'ADD_CONDITION: 3'] }, 'ADD_VALIDATOR'],
    ['status MISMATCH', { steps: [{ kind: 'STATUS', writeStatus: 'APPLIED', verification: 'MISMATCH' }, { kind: 'STATUS', writeStatus: 'APPLIED', verification: 'MATCH' }, { kind: 'STATUS', writeStatus: 'APPLIED', verification: 'MATCH' }], result: 'PARTIAL' }, 'COMPLETED'],
    ['only two status steps', { steps: [{ kind: 'STATUS', writeStatus: 'APPLIED', verification: 'MATCH' }, { kind: 'STATUS', writeStatus: 'APPLIED', verification: 'MATCH' }] }, 'COMPLETED'],
    ['operation failed', { operation: 'FAILED' }, 'COMPLETED'],
    ['status category differs', { statuses: wanted.map((w, i) => ({ id: String(101 + i), name: w.name, category: 'TO_DO' })) }, 'три статуса'],
    ['a status missing in Jira', { statuses: wanted.slice(0, 2).map((w, i) => ({ id: String(101 + i), name: w.name, category: w.category })) }, 'три статуса'],
    ['no validator in Jira', { validators: [] }, 'validator REQUIRED_FIELDS'],
    ['validator on other field', { validators: [{ kind: 'REQUIRED_FIELDS', fieldIds: ['description'], conditional: false }] }, 'validator REQUIRED_FIELDS'],
    ['no condition in Jira', { root: null }, 'project_role'],
    ['wrong role', { roleIds: [10001] }, 'project_role'],
    ['inverted role', { inverted: true }, 'project_role'],
    ['OR root', { root: { kind: 'COMPOUND', operator: 'OR', children: [{ kind: 'PROJECT_ROLE', roleIds: [10002], roles: [], inverted: false }] } }, 'project_role'],
    ['condition on first transition', { firstRoot: { kind: 'PROJECT_ROLE', roleIds: [10002], roles: [], inverted: false } }, 'project_role'],
    ['no resolution', { resolution: { action: 'PRESERVE' } }, 'resolution'],
    ['unknown resolution', { resolution: { action: 'SET', value: '99999' } }, 'resolution'],
    ['extra transition', { extraMove: true }, 'workflow существует'],
    ['wrong step count', { steps3: [{ stepId: 1, name: 'a', statusId: '101', initial: true }, { stepId: 2, name: 'b', statusId: '102', initial: false }] }, 'workflow существует'],
  ];
  for (const [label, options, fragment] of cases) {
    const w = world(options);
    const checks = [...traceChecks(w.trace, w.conversation, { classifyPresentation: true }), ...await verifyWorld(w)];
    if (!failing(checks).some((c) => c.class === 'contract' && c.name.includes(fragment))) miss.push(`${label}: «${fragment}», failing: ${JSON.stringify(failing(checks).map((c) => c.name))}`);
  }
  assert.deepEqual(miss, []);
});

test('workflow-configure-new-statuses: no apply at all fails the contract (no vacuous pass)', async () => {
  const w = world();
  w.trace = w.trace.filter((e) => e.tool !== 'start_operation' && e.tool !== 'get_operation');
  const checks = await verifyWorld(w);
  assert.ok(failing(checks).some((c) => c.class === 'contract' && c.name.includes('COMPLETED')));
  assert.ok(failing(checks).some((c) => c.class === 'contract' && c.name.includes('один preview')));
});

test('workflow-configure-new-statuses: a table-less preview reply is only an advisory observation', async () => {
  const w = world({ table: 'Создать workflow? Применить?' });
  const checks = await verifyWorld(w);
  assert.deepEqual(failing(checks).filter((c) => c.class === 'contract'), []);
  assert.ok(failing(checks).some((c) => c.class === 'advisory'));
});

test('workflow-configure-new-statuses: setup creates nothing and refuses taken names', async () => {
  const calls = [];
  const tool = async (name, args) => { calls.push(name); return name === 'list_statuses' ? { statuses: [] } : [{ id: '10000', name: 'Готово' }]; };
  const rest = async (method, url) => { calls.push(`${method} ${url}`); return url.endsWith('/role') ? [{ id: 10002, name: 'Administrators' }] : [{ id: 'assignee' }]; };
  const prepared = await setup({ runId: 'RUN', tool, rest, log: () => {} });
  assert.deepEqual(prepared.statuses.map((s) => s.name), wanted.map((w) => w.name));
  assert.ok(calls.every((c) => /^GET |^list_statuses$|^get_resolutions$/u.test(c)), calls.join(','));
  await assert.rejects(() => setup({ runId: 'RUN', rest, log: () => {}, tool: async (name) => name === 'list_statuses' ? { statuses: [{ name: `${P}-Бэк` }] } : [{ id: '1', name: 'Готово' }] }), /уже существуют/u);
});
