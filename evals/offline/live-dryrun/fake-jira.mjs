import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { createValidatorsMock } from '../workflow-validators.mjs';

// A loopback stand-in for the Jira of the live scenarios, only for the dry run of evals/live/scenarios/workflow-validator-*:
// MCP JSON-RPC (tools/call of the tools those scenarios use), the REST and admin pages their setup, hooks and verify
// read, and a stateful workflow model (the validators mock of the offline eval plus statuses and drafts). No LLM, no real Jira.
const CATALOG = new URL('../../../src/main/resources/llm/', import.meta.url);
const STATUSES = [['1', 'Open', 'TO_DO'], ['3', 'In Progress', 'IN_PROGRESS'], ['10001', 'Done', 'DONE'], ['4', 'In Review', 'IN_PROGRESS'],
  ['5', 'Testing', 'IN_PROGRESS']].map(([id, name, category]) => ({ id, name, category }));
const FIELDS = ['assignee:Assignee', 'description:Description', 'environment:Environment', 'labels:Labels', 'priority:Priority',
  'status:Status'].map((entry) => ({ id: entry.split(':')[0], name: entry.split(':')[1] }));
const NO_CONSUMERS = { schemeCount: 0, projectCount: 0, schemes: [], projects: [], truncated: false };

export async function startFakeJira({ scriptRunner = 'ok', exportXml = true } = {}) {
  const workflows = new Map();
  const plans = new Map();
  const operations = new Map();
  const planOwner = new Map();
  const log = [];
  let counter = 0;
  const tools = readdirSync(CATALOG).filter((file) => file.endsWith('-tools.json'))
    .flatMap((file) => JSON.parse(readFileSync(new URL(file, CATALOG), 'utf8')));

  const record = (name) => workflows.get(name);
  const failed = (errorCode, message) => ({ status: 'FAILED', errorCode, message });
  const sync = (entry) => { // a draft made by the validators mock starts with the statuses of the main version
    if (entry.mock.world.draft && !entry.statuses.draft) entry.statuses.draft = new Set(entry.statuses.main);
    if (!entry.mock.world.draft) entry.statuses.draft = null;
  };

  function createWorkflow(args) {
    const world = { name: args.name, active: false, scriptRunner: scriptRunnerState(scriptRunner), consumers: NO_CONSUMERS, draft: null,
      concurrent: null, main: { n: 1, transitions: args.transitions.map((t, i) => ({ actionId: 11 + 10 * i, name: t.name, from: [t.from], to: [t.to],
        screenId: null, screenName: null, validators: [] })) } };
    workflows.set(args.name, { mock: createValidatorsMock({ world }), statuses: { main: new Set(args.statuses.map((s) => s.name)), draft: null } });
    return { dryRun: false, status: 'CREATED', workflow: args.name };
  }

  function assign(args) {
    const entry = record(args.workflow);
    if (!entry) return failed('UNKNOWN_WORKFLOW', 'no such workflow');
    Object.assign(entry.mock.world, { active: true, consumers: { schemeCount: 1, projectCount: 1, schemes: ['Scheme'], projects: [args.project], truncated: false } });
    return { dryRun: false, status: 'ASSIGNED', project: args.project };
  }

  function handle(name, args = {}) {
    log.push({ name, args });
    if (name === 'list_statuses') return { total: STATUSES.length, statuses: STATUSES };
    if (name === 'get_resolutions') return [{ id: '10000', name: 'Fixed' }];
    if (name === 'get_workflow') {
      const entry = record(args.workflow);
      if (!entry) return failed('UNKNOWN_WORKFLOW', 'no such workflow');
      const view = entry.mock('get_workflow_validators', { workflow: args.workflow, version: args.version });
      const side = args.version === 'DRAFT' ? entry.statuses.draft : entry.statuses.main;
      const w = entry.mock.world;
      return { workflow: args.workflow, version: args.version, active: w.active, hasDraft: Boolean(w.draft), revision: view.revision,
        consumers: w.consumers, statuses: [...(side ?? [])].map((s) => ({ name: s })) };
    }
    if (['get_workflow_validators', 'publish_workflow_draft'].includes(name) ||
        name === 'configure_workflow' && (args.existing || args.dryRun === false && planOwner.has(args.planId))) {
      const workflow = name === 'configure_workflow' ? args.existing?.workflow : args.workflow;
      const entry = args.dryRun === false ? planOwner.get(args.planId) : record(workflow);
      if (!entry && args.dryRun !== false) return failed('UNKNOWN_WORKFLOW', `Workflow '${workflow}' does not exist.`);
      if (!entry) return failed('PREVIEW_STALE', 'unknown plan');
      const result = entry.mock(name, args);
      if (result.planId) planOwner.set(result.planId, entry);
      sync(entry);
      if (name === 'publish_workflow_draft' && result.status === 'WOULD_PUBLISH') {
        result.changes.addedStatuses = [...entry.statuses.draft].filter((s) => !entry.statuses.main.has(s));
      }
      return result;
    }
    if (args.dryRun === true) {
      const planId = `fake-plan-${++counter}`;
      plans.set(planId, { tool: name, args });
      return { dryRun: true, status: name === 'set_field_required' ? 'WOULD_CHANGE_REQUIREMENTS' : 'WOULD_CREATE', planId, expiresAt: 4102444800000 };
    }
    if (args.dryRun === false) {
      const plan = plans.get(args.planId);
      if (!plan || plan.tool !== name) return failed('PREVIEW_STALE', 'plan');
      plans.delete(args.planId);
      if (name === 'configure_workflow') return createWorkflow(plan.args.create);
      if (name === 'assign_workflow') return assign(plan.args);
      return { dryRun: false, status: name === 'set_field_required' ? 'REQUIREMENTS_CHANGED' : 'CREATED' };
    }
    return failed('EVAL_UNEXPECTED_TOOL', `${name} is not provided by the fake Jira`);
  }

  function callTool(name, args) {
    if (name === 'start_operation') {
      const operationId = `op-${++counter}`;
      const result = handle(args.toolName, args.arguments);
      operations.set(operationId, { operationId, status: 'COMPLETED', operation: args.toolName, result });
      return { operationId, status: 'QUEUED' };
    }
    if (name === 'get_operation') return operations.get(args.operationId) ?? failed('NOT_FOUND', 'operation');
    return handle(name, args);
  }

  // Admin pages: the actions the scenarios use as "another administrator".
  function adminPage(method, url, body) {
    const path = url.pathname;
    if (path.endsWith('/ViewWorkflowXml.jspa')) {
      if (!exportXml) return [404, 'not found'];
      const entry = record(url.searchParams.get('workflowName'));
      const side = url.searchParams.get('workflowMode') === 'draft' ? entry?.mock.world.draft : entry?.mock.world.main;
      if (!side) return [404, 'no such version'];
      const args = side.transitions.flatMap((t) => t.validators.filter((v) => v.kind === 'REQUIRED_FIELDS').map((v) => {
        v.functionId ??= randomUUID();
        return `<validator name="" type="class"><arg name="FIELD_FUNCTION_ID">${v.functionId}</arg><arg name="FIELD_REQUIRED_FIELDS">${v.fieldIds.join('|||')}</arg></validator>`;
      }));
      return [200, `<?xml version="1.0"?><workflow name="x">${args.join('')}</workflow>`];
    }
    if (path.endsWith('/EditWorkflowDispatcher.jspa')) return [200, 'ok'];
    if (path.endsWith('/AddWorkflowStep.jspa') && method === 'POST') {
      const form = new URLSearchParams(body);
      const entry = record(form.get('workflowName'));
      if (!entry) return [404, 'no such workflow'];
      const w = entry.mock.world;
      if (form.get('workflowMode') === 'draft') {
        if (!w.active) return [400, 'only an active workflow has a draft'];
        w.draft ??= { n: 1, transitions: structuredClone(w.main.transitions) };
        sync(entry);
        entry.statuses.draft.add(form.get('stepName'));
        w.draft.n += 1;
      } else {
        if (w.active) return [400, 'an active workflow is edited through its draft'];
        entry.statuses.main.add(form.get('stepName'));
        w.main.n += 1;
      }
      return [302, ''];
    }
    return [404, 'not found'];
  }

  const server = http.createServer(async (request, response) => {
    let body = '';
    for await (const chunk of request) body += chunk;
    const url = new URL(request.url, 'http://127.0.0.1');
    const send = (status, payload, type = 'application/json') => { response.writeHead(status, { 'content-type': type }); response.end(payload); };
    try {
      if (url.pathname === '/rest/control/1.0/mcp') {
        const { id, method, params } = JSON.parse(body);
        if (method === 'initialize') return send(200, JSON.stringify({ jsonrpc: '2.0', id, result: { serverInfo: { version: 'dryrun' }, instructions: 'dry run' } }));
        if (method === 'tools/list') return send(200, JSON.stringify({ jsonrpc: '2.0', id, result: { tools } }));
        const result = callTool(params.name, params.arguments);
        return send(200, JSON.stringify({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: JSON.stringify(result) }], isError: false } }));
      }
      if (url.pathname === '/rest/api/2/field') return send(200, JSON.stringify(FIELDS));
      const [status, text] = adminPage(request.method, url, body);
      response.writeHead(status, status === 302 ? { location: '/secure/admin/workflows/ViewWorkflowSteps.jspa' } : {});
      return response.end(text);
    } catch (error) {
      return send(500, JSON.stringify({ error: String(error.stack ?? error) }));
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { url: `http://127.0.0.1:${server.address().port}`, workflows, log, callTool,
    close: () => new Promise((resolve) => server.close(resolve)) };
}

function scriptRunnerState(kind) {
  if (kind === 'ok') return { installed: true, enabled: true, version: '9.46.0', supported: true, reason: null };
  throw new Error(`unknown ScriptRunner state ${kind}`);
}
