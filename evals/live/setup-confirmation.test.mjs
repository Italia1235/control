import test from 'node:test';
import assert from 'node:assert/strict';
import { approvalPrompt, createConfirmedSetup, checkSetupProtocol, readOnlyAccess } from './setup-confirmation.mjs';
import { setupProtocolChecks } from './checks.mjs';
import { projectManagement } from './project-templates.mjs';

const name = 'configure_workflow';
const args = { create: { name: 'L013-test-Workflow' } };
const preview = { dryRun: true, status: 'WOULD_CREATE', workflow: 'L013-test-Workflow', planId: 'plan-secret',
  diff: ['Create workflow'], objects: [{ kind: 'WORKFLOW', name: 'L013-test-Workflow' }], warnings: ['Global effect'], consumers: { projects: [] } };
function fixture(decision = 'APPROVE', overrides = {}) {
  const calls = [], saved = [], model = [];
  const helper = createConfirmedSetup({
    call: async (tool, payload) => { calls.push({ tool, payload }); return tool === name ? structuredClone(preview)
      : { operationId: 'operation-1', status: 'COMPLETED', result: { status: 'CREATED', id: '123' } }; },
    approve: async (request) => { model.push(request); return { text: JSON.stringify({ decision, message: decision === 'APPROVE' ? 'Да, создать эти свежие объекты.' : 'Нет.' }), model: 'test/user' }; },
    persist: async (value) => saved.push(structuredClone(value)), runId: 'test', model: 'test/user', ...overrides,
  });
  return { helper, calls, saved, model };
}
test('exact shown preview gets a model reply persisted before one start', async () => {
  const f = fixture();
  assert.deepEqual(await f.helper.apply(name, args), { status: 'CREATED', id: '123' });
  assert.equal(f.model.length, 1);
  assert.ok(f.model[0].request.currentPreview.includes('Global effect'));
  assert.ok(!JSON.stringify(f.model[0].request).includes('plan-secret'));
  assert.equal(f.calls.filter(x => x.tool === 'start_operation').length, 1);
  const approval = f.saved.find(x => x.trace.some(e => e.event === 'approval'));
  assert.ok(approval);
  assert.equal(approval.trace.some(e => e.event === 'start_operation'), false);
});
test('rejection cannot start an operation', async () => {
  const f = fixture('REJECT');
  await assert.rejects(f.helper.apply(name, args));
  assert.equal(f.calls.filter(x => x.tool === 'start_operation').length, 0);
});

test('approval of a project does not authorize a later screen and rejection stops the remaining setup', async () => {
  const calls = [], decisions = ['APPROVE', 'REJECT'];
  const f = fixture('APPROVE', {
    call: async (tool, payload) => {
      calls.push({ tool, payload });
      return tool === 'start_operation'
        ? { operationId: 'project-op', status: 'COMPLETED', result: { status: 'CREATED', key: 'L013TEST' } }
        : { dryRun: true, status: 'WOULD_CREATE', planId: `${tool}-plan`, ...payload };
    },
    approve: async () => ({ model: 'test/user', text: JSON.stringify({ decision: decisions.shift(), message: 'This preview only.' }) }),
  });
  await f.helper.apply('create_project', projectManagement({ key: 'L013TEST', name: 'L013-test-project', lead: 'admin' }));
  await assert.rejects(f.helper.apply('create_screen', { name: 'L013-test-screen', fields: ['comment'] }), /Setup rejected/u);
  await assert.rejects(f.helper.apply(name, args), /stopped/u);
  await assert.rejects(f.helper.complete(), /did not complete/u);
  assert.deepEqual(calls.map(c => c.tool), ['create_project', 'start_operation', 'create_screen']);
  assert.deepEqual(calls[1].payload, { toolName: 'create_project', arguments: { dryRun: false, planId: 'create_project-plan' } });
  const approvals = f.helper.trace.filter(e => e.event === 'approval');
  assert.deepEqual(approvals.map(e => [e.toolName, e.planId, e.decision]), [
    ['create_project', 'create_project-plan', 'APPROVE'], ['create_screen', 'create_screen-plan', 'REJECT'],
  ]);
  assert.ok(f.saved.some(snapshot => snapshot.trace.at(-1)?.event === 'approval'
    && snapshot.trace.at(-1).decision === 'APPROVE' && !snapshot.trace.some(e => e.event === 'start_operation')));
  assert.ok(f.saved.some(snapshot => snapshot.trace.at(-1)?.event === 'approval'
    && snapshot.trace.at(-1).decision === 'REJECT'));
});

test('later approval sees completed fixture provenance, not an intermediate operation result', async () => {
  const requests = [];
  let polls = 0;
  const project = { status: 'CREATED', project: { id: '700', key: 'L013TEST' } };
  const f = fixture('APPROVE', {
    pollMs: 0,
    call: async (tool, payload) => {
      if (tool === 'start_operation') return { operationId: 'project-op', status: 'RUNNING', result: { status: 'PENDING' } };
      if (tool === 'get_operation') { polls++; return { operationId: 'project-op', status: 'COMPLETED', result: project }; }
      return { dryRun: true, status: 'WOULD_CREATE', planId: `${tool}-plan`, ...payload };
    },
    approve: async request => {
      requests.push(request);
      return { model: 'test/user', text: JSON.stringify({ decision: requests.length === 1 ? 'APPROVE' : 'REJECT', message: 'Independent decision.' }) };
    },
  });
  await f.helper.apply('create_project', projectManagement({ key: 'L013TEST', name: 'L013-test-project', lead: 'admin' }));
  await assert.rejects(f.helper.apply('create_custom_fields', { fields: [{ name: 'L013-test-field', contexts: [{ projects: ['L013TEST'] }] }] }), /Setup rejected/u);
  assert.equal(polls, 1);
  assert.deepEqual(requests[0].request.completedSetupOperations, []);
  assert.deepEqual(requests[1].request.completedSetupOperations, [{ toolName: 'create_project', result: project }]);
  assert.ok(f.helper.conversation.includes(JSON.stringify({ toolName: 'create_project', result: project }, null, 2)));
  assert.equal(f.helper.trace.filter(e => e.event === 'start_operation').length, 1);
});

for (const [label, approve] of [
  ['empty reply', async () => ({ text: '', model: 'test/user' })],
  ['malformed envelope', async () => ({ text: '{"decision":"APPROVE","message":""}', model: 'test/user' })],
  ['extra envelope keys', async () => ({ text: '{"decision":"APPROVE","message":"yes","planId":"other"}', model: 'test/user' })],
  ['unexpected model', async () => ({ text: '{"decision":"APPROVE","message":"yes"}', model: 'different/user' })],
  ['model error', async () => { throw new Error('model unavailable'); }],
  ['timeout', async () => new Promise(() => {})],
]) test(`${label} fails closed without a start`, async () => {
  const f = fixture('APPROVE', { approve, timeoutMs: 10 });
  await assert.rejects(f.helper.apply(name, args));
  assert.equal(f.calls.filter(x => x.tool === 'start_operation').length, 0);
  if (['empty reply', 'malformed envelope', 'extra envelope keys', 'unexpected model'].includes(label)) {
    const reply = f.helper.trace.find(x => x.event === 'user_response');
    assert.ok(reply);assert.equal(typeof reply.raw, 'string');
    assert.ok(f.saved.some(value => value.trace.some(x => x.event === 'user_response')));
  }
  await assert.rejects(f.helper.apply(name, args), /stopped/u);
});
const envelope = '{"decision":"APPROVE","message":"Да, создать."}';
for (const [label, text, ok] of [
  ['bare JSON', envelope, true],
  ['json-fenced JSON', '```json\n' + envelope + '\n```', true],
  ['plain-fenced JSON with surrounding whitespace', '\n  ```\n' + envelope + '\n```  \n', true],
  ['prose before a fence', 'Sure:\n```json\n' + envelope + '\n```', false],
  ['prose after a fence', '```json\n' + envelope + '\n```\nThanks', false],
  ['two fenced objects', '```json\n' + envelope + '\n```\n```json\n' + envelope + '\n```', false],
  ['two bare objects', envelope + '\n' + envelope, false],
  ['prose-wrapped bare JSON', 'Here: ' + envelope, false],
  ['unknown decision in a fence', '```json\n{"decision":"MAYBE","message":"x"}\n```', false],
  ['lowercase decision', '{"decision":"approve","message":"x"}', false],
  ['extra key in a fence', '```json\n{"decision":"APPROVE","message":"x","planId":"p"}\n```', false],
]) test(`simulated-user envelope: ${label} is ${ok ? 'accepted' : 'rejected without a start'}`, async () => {
  const f = fixture('APPROVE', { approve: async () => ({ text, model: 'test/user' }) });
  if (ok) {
    await f.helper.apply(name, args);await f.helper.complete();
    assert.equal(f.calls.filter(x => x.tool === 'start_operation').length, 1);
    assert.equal(f.helper.trace.find(x => x.event === 'user_response').raw, text);
    assert.equal(f.helper.trace.find(x => x.event === 'approval').decision, 'APPROVE');
    assert.equal(checkSetupProtocol(f.helper.trace, f.helper.conversation)[0].ok, true);
  } else {
    await assert.rejects(f.helper.apply(name, args), /Invalid simulated-user envelope/u);
    assert.equal(f.calls.filter(x => x.tool === 'start_operation').length, 0);
  }
});
for (const failure of ['preview', 'user_response', 'approval', 'start_operation']) test(`evidence failure at ${failure} prevents apply`, async () => {
  const f = fixture('APPROVE', { persist: async ({ trace }) => { if (trace.at(-1).event === failure) throw new Error('disk unavailable'); } });
  await assert.rejects(f.helper.apply(name, args), /disk unavailable/u);
  assert.equal(f.calls.filter(x => x.tool === 'start_operation').length, 0);
});
test('missing configured model does not silently choose another', async () => {
  const f = fixture('APPROVE', { model: null });
  await assert.rejects(f.helper.apply(name, args), /model is required/u);
  assert.equal(f.calls.length, 1);assert.equal(f.model.length, 0);
});
for (const status of ['PREVIEW_STALE', 'FAILED', 'PARTIAL', 'UNKNOWN', 'BLOCKED', 'CANCELLED']) test(`${status} stops without replanning or another apply`, async () => {
  const calls = [];
  const f = fixture('APPROVE', { call: async (tool, payload) => {
    calls.push({ tool, payload });return tool === name ? preview : { operationId: 'op', status: status === 'PREVIEW_STALE' ? 'FAILED' : status, result: { status } };
  } });
  await assert.rejects(f.helper.apply(name, args));
  await assert.rejects(f.helper.apply(name, args));
  assert.deepEqual(calls.map(x => x.tool), [name, 'start_operation']);
});
test('lost start response recovers only the identical plan once and persists the known ID before polling', async () => {
  const calls = [];let starts = 0;
  const f = fixture('APPROVE', { pollMs: 0, call: async (tool, payload) => {
    calls.push({ tool, payload: structuredClone(payload) });
    if (tool === name) return preview;
    if (tool === 'start_operation' && ++starts === 1) throw new Error('lost response');
    if (tool === 'start_operation') return { operationId: 'op', status: 'RUNNING', alreadyStarted: true };
    assert.ok(f.saved.some(saved => saved.trace.some(e => e.event === 'operation' && e.operationId === 'op')));
    return { operationId: 'op', status: 'COMPLETED', result: { status: 'CREATED' } };
  } });
  await f.helper.apply(name, args);await f.helper.complete();
  assert.equal(starts, 2);assert.deepEqual(calls[1], calls[2]);
  assert.equal(calls.filter(x => x.tool === name).length, 1);
  assert.equal(checkSetupProtocol(f.helper.trace, f.helper.conversation)[0].ok, true);
});
test('known operationId uses get_operation instead of another start', async () => {
  const calls = [];
  const f = fixture('APPROVE', { call: async (tool, payload) => {
    calls.push({ tool, payload });
    if (tool === name) return preview;
    if (tool === 'start_operation') throw Object.assign(new Error('lost'), { operationId: 'op' });
    return { operationId: 'op', status: 'COMPLETED', result: { status: 'CREATED' } };
  } });
  await f.helper.apply(name, args);await f.helper.complete();
  assert.deepEqual(calls.map(x => x.tool), [name, 'start_operation', 'get_operation']);
  assert.equal(checkSetupProtocol(f.helper.trace, f.helper.conversation)[0].ok, true);
});
test('second lost recovery stops after at most two identical starts', async () => {
  const calls = [];
  const f = fixture('APPROVE', { call: async (tool, payload) => {
    calls.push({ tool, payload });if (tool === name) return preview;throw new Error('lost twice');
  } });
  await assert.rejects(f.helper.apply(name, args));
  assert.equal(calls.length, 3);assert.deepEqual(calls[1], calls[2]);
});
test('regrade rejects transferred approval, changed display, missing completion and main-plan mixing', async () => {
  const f = fixture();await f.helper.apply(name, args);await f.helper.complete();
  assert.equal(checkSetupProtocol(f.helper.trace, f.helper.conversation)[0].ok, true);
  for (const mutate of [
    trace => { trace.find(x => x.event === 'approval').hash = 'other-preview'; },
    trace => { trace.find(x => x.event === 'preview').response.diff.push('another effect'); },
    trace => { trace.find(x => x.event === 'preview').displayed.warnings = []; },
    trace => { trace.find(x => x.event === 'approval').planId = 'another-plan'; },
    trace => { trace.pop(); },
    trace => { trace.find(x => x.event === 'start_operation').arguments.arguments.planId = 'another-plan'; },
    trace => { trace.find(x => x.event === 'operation').operationId = 'foreign-op'; },
    trace => { trace.find(x => x.event === 'user_response').raw = '{"decision":"APPROVE","message":"different preview"}'; },
  ]) {
    const trace = structuredClone(f.helper.trace);mutate(trace);
    assert.equal(checkSetupProtocol(trace, f.helper.conversation)[0].ok, false);
  }
});
test('tool/probe and HTTP wrappers reject every mutation bypass but retain reads', async () => {
  const calls = [];
  const api = readOnlyAccess({ allowed: new Set(['get_workflow', name, 'start_operation']),
    call: (...args) => calls.push(args), rest: (...args) => calls.push(args), adminPage: (...args) => calls.push(args) });
  for (const route of ['tool', 'probe']) for (const tool of [name, 'start_operation', 'tools/call', 'get_fake']) assert.throws(() => api[route](tool, {}));
  assert.throws(() => api.rest('POST', '/path', {}));assert.throws(() => api.rest('GET', '/path', {}));
  assert.throws(() => api.adminPage('/path', {}));assert.equal(calls.length, 0);
  api.tool('get_workflow', {});api.probe('get_workflow', {});api.rest('GET', '/path');api.adminPage('/path');
  assert.equal(calls.length, 4);
});
test('returned operation binding cannot be switched while polling', async () => {
  let polls = 0;
  const f = fixture('APPROVE', { pollMs: 0, call: async tool => tool === name ? preview : tool === 'start_operation'
    ? { operationId: 'op', status: 'RUNNING' } : { operationId: 'foreign', status: ++polls ? 'COMPLETED' : 'RUNNING', result: { status: 'CREATED' } } });
  await assert.rejects(f.helper.apply(name, args), /binding/u);
});

test('common checks reject a setup plan reused by the main dialogue', async () => {
  const f = fixture();await f.helper.apply(name, args);await f.helper.complete();
  assert.ok(setupProtocolChecks(f.helper.trace, f.helper.conversation).every(check => check.ok));
  const checks = setupProtocolChecks(f.helper.trace, f.helper.conversation, [{ arguments: { arguments: { planId: preview.planId } } }]);
  assert.equal(checks.find(check => check.name === 'Setup and main operations are separate').ok, false);
});

test('approval prompt keeps the LOCAL-013 boundaries by default and a scope replaces only the boundaries', () => {
  const base = approvalPrompt(undefined, 'ABC12');
  assert.match(base, /LOCAL-013 run ABC12/u);
  assert.match(base, /Reply only JSON \{"decision":"APPROVE"\|"REJECT","message":string\}\./u);
  const scoped = approvalPrompt({ rules: (runId) => `LOCAL-016 boundaries ${runId}\n` }, 'ABC12');
  assert.match(scoped, /^LOCAL-016 boundaries ABC12\nRead currentPreview/u);
  assert.doesNotMatch(scoped, /LOCAL-013/u);
  assert.match(scoped, /Reply only JSON/u);
});
