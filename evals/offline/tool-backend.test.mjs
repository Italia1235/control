import test from 'node:test';
import assert from 'node:assert/strict';
import { createToolBackend, LiveToolBackend, InfrastructureError, ToolExecutionError } from './tool-backend.mjs';

function fixture(overrides = {}) {
  const calls = [];
  const tools = ['configure_workflow', 'start_operation', 'get_operation', 'finish_change_set'].map((name) => ({
    name, description: `Production ${name}`, inputSchema: { type: 'object', properties: {} },
  }));
  let time = 0;
  const polls = [];
  const request = async (path, options = {}) => {
    calls.push({ path, ...options });
    if (Object.hasOwn(overrides, path)) return overrides[path];
    if (path === '/status') return { state: 'RUNNING' };
    if (path === '/rest/plugins/1.0/ru.rasulov.control-key') return { key: 'ru.rasulov.control', enabled: true };
    if (path.endsWith('/changes/sessions')) return { sessionId: 'owned-session' };
    if (path.endsWith('/owned-session/sets')) return { changeSetId: 'owned-group' };
    const { id, method, params } = options.body;
    let result;
    if (method === 'initialize') result = { instructions: 'Production rules', serverInfo: { version: '1.2.3' } };
    if (method === 'tools/list') result = { tools };
    if (method === 'tools/call') {
      let value;
      if (params.name === 'start_operation') value = { operationId: 'owned-operation', status: 'QUEUED' };
      else if (params.name === 'get_operation') {
        polls.push(time);
        value = { operationId: 'owned-operation', status: 'COMPLETED', operation: 'configure_workflow',
          result: { status: 'CREATED', name: 'Example', statuses: [{ id: '10001', isNew: true }] } };
      } else value = { status: 'WOULD_CREATE', planId: 'plan' };
      result = { content: [{ type: 'text', text: JSON.stringify(value) }], isError: false };
    }
    return { jsonrpc: '2.0', id, result };
  };
  return { calls, tools, polls, backend: new LiveToolBackend({ request, now: () => time, sleep: async (ms) => { time += ms; } }) };
}

const start = { toolName: 'configure_workflow', arguments: { dryRun: false, planId: 'plan' } };
const workflowPreview = {dryRun: true, create: {name: 'Transport test',
  statuses: [{name: 'Open', category: 'TO_DO'}, {name: 'Done', category: 'DONE'}], initialStatus: 'Open',
  transitions: [{key: 'finish', name: 'Finish', from: 'Open', to: 'Done', resolution: {action: 'SET', value: '1'}}]}};

test('backend selection rejects unsupported modes and missing simulated implementation', () => {
  assert.throws(() => createToolBackend({ mode: 'unknown' }), /Invalid EVAL_BACKEND/);
  assert.throws(() => createToolBackend({ mode: 'simulated' }), /requires a tool callback/);
});

test('live operations lazily own session/group, inject IDs, and reject foreign identities', async () => {
  const { backend, calls } = fixture();
  await backend.call('configure_workflow', workflowPreview);
  assert.equal(calls.filter((call) => call.path.includes('/changes/')).length, 0);
  await assert.rejects(backend.call('start_operation', { ...start, sessionId: 'foreign', changeSetId: 'foreign' }), /Foreign/);
  await backend.call('start_operation', start);
  const effective = { ...start, sessionId: 'owned-session', changeSetId: 'owned-group' };
  assert.deepEqual(backend.effectiveArguments('start_operation', start), effective);
  assert.deepEqual(calls.at(-1).body.params.arguments, effective);
  assert.equal(Object.hasOwn(start, 'sessionId'), false);
  await backend.call('start_operation', effective);
  assert.equal(calls.filter((call) => call.path.includes('/changes/')).length, 2);
  await assert.rejects(backend.call('get_operation', { operationId: 'foreign' }), /Foreign/);
});

test('live get_operation serializes concurrent polling at least one second apart and preserves domain evidence', async () => {
  const { backend, polls } = fixture();
  await backend.call('start_operation', start);
  const results = await Promise.all(Array.from({ length: 3 }, () => backend.call('get_operation', { operationId: 'owned-operation' })));
  assert.deepEqual(polls, [0, 1000, 2000]);
  assert.deepEqual(results[0].result, { status: 'CREATED', name: 'Example', statuses: [{ id: '10001', isNew: true }] });
});

test('live startup failures are infrastructure errors, not fabricated tool results', async () => {
  for (const overrides of [
    { '/status': { state: 'STARTING' } },
    { '/rest/plugins/1.0/ru.rasulov.control-key': { key: 'ru.rasulov.control', enabled: false } },
    { '/rest/control/1.0/mcp': { jsonrpc: '2.0', id: 1, error: { code: -32603 } } },
  ]) await assert.rejects(fixture(overrides).backend.initialize(), InfrastructureError);
  await assert.rejects(new LiveToolBackend({ request: async () => { throw new Error('network failure'); } }).initialize(), InfrastructureError);
  assert.throws(() => new LiveToolBackend({ env: {} }), InfrastructureError);
  assert.throws(() => new LiveToolBackend({ env: { JIRA_BASE_URL: 'https://example.com', JIRA_TOKEN: 'unused' } }), /allowed/);
});

test('malformed successful tool content fails as an infrastructure error', async () => {
  for (const result of [
    { isError: false, content: [{ type: 'text', text: 'not JSON' }] },
    { content: [] },
  ]) {
    const { backend } = fixture();
    await backend.initialize();
    backend.request = async (_path, { body }) => ({ jsonrpc: '2.0', id: body.id, result });
    await assert.rejects(backend.call('configure_workflow', workflowPreview), InfrastructureError);
  }
});

test('MCP execution errors retain structured diagnostics and plain text without becoming infrastructure failures', async () => {
  const domain = { status: 'FAILED', errorCode: 'VALIDATION_ERROR',
    message: 'create.transitions[0].resolution.value is forbidden for CLEAR.', suggestions: ['Omit value for CLEAR'] };
  for (const value of [domain, 'Unknown owned operation']) {
    const { backend } = fixture();
    await backend.initialize();
    let attempts = 0;
    backend.request = async (_path, { body }) => {
      attempts++;
      return { jsonrpc: '2.0', id: body.id, result: { isError: true,
        content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value) }] } };
    };
    await assert.rejects(backend.call('configure_workflow', workflowPreview), error => {
      assert.ok(error instanceof ToolExecutionError);
      assert.ok(!(error instanceof InfrastructureError));
      assert.deepEqual(error.result, value);
      assert.equal(error.tool, 'configure_workflow');
      return true;
    });
    assert.equal(attempts, 1);
  }
});
