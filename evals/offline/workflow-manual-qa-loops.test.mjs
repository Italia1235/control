// Install the existing IPC transport by default: these tests never listen/connect sockets.
import './offline-network.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateManualQaLoopsScenario, createManualQaLoopsMock, assertManualQaOfflineConfiguration,
  nextManualQaLoopsUser, gradeManualQaLoops } from './workflow-manual-qa-loops.mjs';
import { scenarioSimulator } from './run.mjs';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
let sequence = 0;
const call = (name, args) => ({ role: 'assistant', content: null, tool_calls: [{ id: `manual-qa-${++sequence}`,
  type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const card = (data, content) => ({ role: 'assistant', content: `${content}\n\n\`\`\`json\n${JSON.stringify(data)}\n\`\`\`` });
const graphOf = result => ({ name: result.name, statuses: result.statuses, initialStatus: result.initialStatus, transitions: result.transitions });
const decisions = scenario => card({ kind: 'DECISIONS', workflowNameRequired: true,
  resolutionChoices: scenario.resolutionCatalog, proposedReplacements: { Fixed: '10000', Duplicate: '10002' } },
  'В Jira нет Fixed и Duplicate. Доступны Готово (10000), Не будет выполнено (10001), Дубликат (10002). Выбрать Fixed → Готово и Duplicate → Дубликат? Также укажите имя workflow. Ничего не создаю.');
const start = planId => call('start_operation', { toolName: 'configure_workflow', arguments: { dryRun: false, planId } });
function script(scenario, violation) {
  const match = structuredClone(scenario.operations[0].match);
  const messages = [call('get_resolutions', {}), decisions(scenario)];
  if (violation === 'resolution_creation') messages.push(call('create_resolution', { dryRun: true, name: 'Fixed' }));
  if (['string', 'preserve', 'missing_action', 'name_not_id'].includes(violation)) {
    const bad = structuredClone(match);
    if (violation === 'string') bad.transitions[2].resolution = '10000';
    if (violation === 'preserve') bad.transitions[4].resolution = { action: 'PRESERVE' };
    if (violation === 'missing_action') bad.transitions[2].resolution = { value: '10000' };
    if (violation === 'name_not_id') bad.transitions[2].resolution.value = 'Готово';
    messages.push(call('configure_workflow', { dryRun: true, create: bad }));
  }
  if (violation === 'dropped_transition') match.transitions = match.transitions.filter(t => t.name !== 'Reject');
  if (violation === 'preserve_null') match.transitions[4].resolution = null;
  // Derive the displayed graph from the same real bounded mock, not a canned full fixture.
  const preview = createManualQaLoopsMock(scenario)({ dryRun: true, create: match });
  assert.equal(preview.status, 'WOULD_CREATE');
  const graph = graphOf(preview);
  messages.push(call('configure_workflow', { dryRun: true, create: match }),
    card({ kind: 'APPROVE_WORKFLOW', planId: preview.planId, workflow: graph },
      `Preview: ${JSON.stringify(graph)}. Finish и Global finish устанавливают Готово; Reject и Change outcome — Дубликат. Start, Repeat work и Keep outcome сохраняют Resolution; Reopen и Global reopen очищают до Unresolved. Глобальные переходы включают целевой статус. Создать только этот workflow?`),
    start(preview.planId));
  if (violation === 'repeated_write') messages.push(start(preview.planId));
  messages.push(call('get_operation', { operationId: 'eval-operation-001' }),
    card({ kind: 'WORKFLOW_CREATED', workflow: graph }, `Workflow создан: ${JSON.stringify(graph)}. Новые Resolution не создавались.`));
  return messages;
}

async function runStub(scenario, messages) {
  let requests = 0, directory, modelMessages = [];
  const errors = [];
  const server = http.createServer(async (request, response) => {
    try {
      let text = '';
      for await (const chunk of request) text += chunk;
      const body = JSON.parse(text);
      assert.equal(request.url, '/chat/completions');
      assert.equal(body.model, 'offline-manual-qa-loops');
      assert.ok(Array.isArray(body.tools), 'No synthetic-user/judge model requests');
      const message = messages[requests++];
      assert.ok(message, 'Unexpected extra assistant request');
      modelMessages = structuredClone([...body.messages, message]);
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ choices: [{ message }], usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } }));
    } catch (error) {
      errors.push(error.message);
      response.writeHead(400).end(JSON.stringify({ error: 'Offline Manual QA stub rejected request' }));
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const env = { ...process.env, EVAL_MANUAL_QA_SCRIPTED: '1', EVAL_API_KEY: 'offline-only',
      EVAL_ASSISTANT_MODEL: 'offline-manual-qa-loops', EVAL_USER_MODEL: 'forbidden-user', EVAL_JUDGE_MODEL: 'forbidden-judge',
      EVAL_BASE_URL: `http://127.0.0.1:${server.address().port}`, EVAL_BACKEND: 'simulated',
      EVAL_MAX_TOKENS_PER_RUN: '', EVAL_TOKEN_LIMIT_MODE: 'hard' };
    let stdout = '', stderr = '', code = 0;
    try { ({ stdout } = await exec(process.execPath, ['evals/offline/run.mjs', scenario.scenario],
      { cwd: ROOT, env, timeout: 20000, maxBuffer: 2 * 1024 * 1024 })); }
    catch (error) { stdout = error.stdout ?? ''; stderr = error.stderr ?? ''; code = error.code; }
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, stdout + stderr);
    directory = path.resolve(ROOT, artifact[1].trim());
    const load = async name => JSON.parse(await readFile(path.join(directory, name), 'utf8'));
    let userTurn = 0, assistantTurn = 0;
    const conversation = modelMessages.filter(m => ['user', 'assistant'].includes(m.role) && m.content?.trim())
      .map(m => ({ role: m.role, content: m.content, turn: m.role === 'user' ? ++userTurn : ++assistantTurn }));
    const result = { code, requests, verdict: await load('verdict.json'), trace: await load('tool-trace.json'),
      conversation, modelMessages };
    assert.deepEqual(errors, []);
    return result;
  } finally {
    await new Promise(resolve => server.close(resolve));
    if (directory) await rm(directory, { recursive: true, force: true });
  }
}

for (const original of [false, true]) test(`manual QA loops: offline end-to-end smoke (${original ? 'original three statuses' : 'synthetic five statuses'})`, async t => {
  const scenario = generateManualQaLoopsScenario(original);
  const result = await runStub(scenario, script(scenario));
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, true, JSON.stringify(result.verdict));
  assert.equal(result.verdict.observations.validation_errors_before_first_successful_preview, 0);
  assert.equal(result.verdict.observations.decision_blocks, 1);
  assert.equal(result.requests, 7);
  assert.equal(result.trace.filter(c => c.tool === 'start_operation').length, 1);
  assert.equal(result.trace.filter(c => c.tool === 'create_resolution').length, 0);
  const submitted = result.trace.find(c => c.tool === 'configure_workflow').arguments.create;
  assert.equal(submitted.statuses.length, original ? 3 : 5);
  assert.equal(submitted.transitions.length, 9);
  for (const name of ['Start', 'Repeat work', 'Keep outcome'])
    assert.equal(Object.hasOwn(submitted.transitions.find(t => t.name === name), 'resolution'), false);
  assert.equal(result.verdict.observations.synthetic_extension, !original);
  // Card forgery, serial independent questions and missing Russian catalog choices cannot pass.
  for (const mutate of [
    data => { data.modelMessages.find(m => m.content?.includes('"DECISIONS"')).content = 'Укажите имя workflow?'; },
    data => { const m = data.modelMessages.find(m => m.content?.includes('"DECISIONS"')); data.modelMessages.splice(data.modelMessages.indexOf(m), 0, structuredClone(m)); },
    data => { const m = data.modelMessages.find(m => m.content?.includes('"DECISIONS"')); m.content = m.content.replace('"name":"Дубликат"', '"name":"Duplicate"'); },
  ]) {
    const data = structuredClone(result); mutate(data);
    assert.equal(gradeManualQaLoops({ scenario, ...data }).checks.one_block_decisions, false);
  }
  t.diagnostic(JSON.stringify({ scenario: scenario.scenario, assistantRequests: result.requests, externalLLMCalls: 0, jiraCalls: 0, networkSockets: 0 }));
});

for (const [violation, check] of [
  ['string', 'zero_validation_errors_before_preview'], ['preserve', 'zero_validation_errors_before_preview'],
  ['missing_action', 'zero_validation_errors_before_preview'], ['name_not_id', 'zero_validation_errors_before_preview'],
  ['dropped_transition', 'all_statuses_and_nine_transitions'],
  ['resolution_creation', 'no_resolution_creation_or_other_writes'], ['repeated_write', 'one_confirmed_write'],
  ['preserve_null', 'canonical_resolution_ids_and_omit_preserve'],
]) test(`manual QA loops: runner rejects ${violation} despite later completion`, async () => {
  const scenario = generateManualQaLoopsScenario();
  const result = await runStub(scenario, script(scenario, violation));
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, false);
  assert.equal(result.verdict.checks[check], false, JSON.stringify(result.verdict));
  assert.equal(result.verdict.checks.one_confirmed_write, violation !== 'repeated_write');
  if (['string', 'preserve', 'missing_action', 'name_not_id'].includes(violation))
    assert.equal(result.verdict.observations.validation_errors_before_first_successful_preview, 1);
});

test('manual QA loops: original request has three statuses; synthetic extension never overwrites it', async () => {
  const transcript = await readFile(path.join(ROOT, 'evals/offline/fixtures/manual-qa-loops-request.md'), 'utf8');
  const suppliedStatuses = transcript.split('Резолюции:')[0];
  assert.match(suppliedStatuses, /QA Open.*TO_DO/su);
  assert.match(suppliedStatuses, /QA Working.*IN_PROGRESS/su);
  assert.match(suppliedStatuses, /QA Done.*DONE/su);
  assert.doesNotMatch(suppliedStatuses, /QA Review|QA Paused/u);
  const original = generateManualQaLoopsScenario(true), extension = generateManualQaLoopsScenario();
  assert.deepEqual(original.operations[0].match.transitions, extension.operations[0].match.transitions);
  assert.match(extension.initialUserMessage, /Синтетическое расширение/u);
  assert.match(extension.provenance.extension, /not a transcript/u);
});

test('manual QA loops: approved pair policy allows distinct names and preserves local/global scopes', () => {
  const scenario = generateManualQaLoopsScenario(), spec = scenario.operations[0].match;
  const run = (match, options) => createManualQaLoopsMock(scenario, options)({ dryRun: true, create: match });
  assert.equal(run(spec).status, 'WOULD_CREATE');
  assert.equal(run(spec, { legacyPairs: true }).errorCode, 'VALIDATION_ERROR', 'Reproduce original pair-only rejection');
  const duplicate = structuredClone(spec);
  duplicate.transitions[3].name = '  FINISH  ';
  assert.equal(run(duplicate).errorCode, 'VALIDATION_ERROR');
  const scope = structuredClone(spec);
  scope.transitions[7].name = 'Finish';
  assert.equal(run(scope).status, 'WOULD_CREATE', 'Same name and target in different source scope is allowed');
  const source = structuredClone(spec);
  source.transitions[0].name = 'Finish';
  assert.equal(run(source).status, 'WOULD_CREATE', 'Same name on different pair is allowed');
});

test('manual QA loops: mock action and catalog validation precedes preview', () => {
  const scenario = generateManualQaLoopsScenario();
  for (const resolution of ['10000', {}, { value: '10000' }, { action: 'PRESERVE' },
    { action: 'SET', value: 'Готово' }, { action: 'SET', value: 'Fixed' }, { action: 'SET', value: '99999' }]) {
    const spec = structuredClone(scenario.operations[0].match);
    spec.transitions[2].resolution = resolution;
    const result = createManualQaLoopsMock(scenario)({ dryRun: true, create: spec });
    assert.equal(result.status, 'FAILED', JSON.stringify(resolution));
    assert.equal(result.errorCode, 'VALIDATION_ERROR');
    assert.ok(!result.planId);
  }
  const mock = createManualQaLoopsMock(scenario), preview = mock({ dryRun: true, create: scenario.operations[0].match });
  assert.deepEqual(preview.transitions.map(t => t.resolution.action),
    ['PRESERVE', 'PRESERVE', 'SET', 'SET', 'PRESERVE', 'SET', 'CLEAR', 'SET', 'CLEAR']);
  assert.equal(mock({ dryRun: false, planId: preview.planId }).status, 'CREATED');
  assert.equal(mock({ dryRun: false, planId: preview.planId }).errorCode, 'PREVIEW_STALE');
});

test('manual QA loops: actual simulator enforces confirmation and idempotent plan recovery', () => {
  const scenario = generateManualQaLoopsScenario(), sim = scenarioSimulator(scenario);
  const preview = sim('configure_workflow', { dryRun: true, create: scenario.operations[0].match });
  const args = { toolName: 'configure_workflow', arguments: { dryRun: false, planId: preview.planId } };
  assert.equal(sim('start_operation', args).errorCode, 'EVAL_CONFIRMATION_REQUIRED');
  const conversation = [{ role: 'assistant', turn: 1, content: 'Preview shown' }, { role: 'user', turn: 2, content: 'Да, создай.' }];
  const context = { conversation, userTurn: 2, assistantTurn: 2 };
  const started = sim('start_operation', args, context);
  assert.equal(started.status, 'QUEUED');
  const completed = sim('get_operation', { operationId: started.operationId });
  assert.equal(completed.status, 'COMPLETED');
  assert.equal(completed.result.transitions.length, 9);
  assert.deepEqual(sim('start_operation', args, context),
    { operationId: started.operationId, status: 'COMPLETED', alreadyStarted: true });
  assert.deepEqual(sim('get_operation', { operationId: started.operationId }), completed,
    'Recovery preserves the original operation and its domain result');
});

test('manual QA loops: deterministic decisions are not write approval', () => {
  const scenario = generateManualQaLoopsScenario();
  const answer = nextManualQaLoopsUser(scenario, [decisions(scenario)]);
  assert.doesNotMatch(answer, /^(?:да|yes)/iu);
  assert.match(answer, /Готово \(10000\).*Дубликат \(10002\)/u);
  assert.match(answer, /Manual QA — loops and resolutions/u);
  assert.match(nextManualQaLoopsUser(scenario, [card({ kind: 'APPROVE_WORKFLOW' }, 'Preview')]), /^Да,/u);
});

test('manual QA loops: refuse paid, wired or non-IPC transports before requests', () => {
  const safe = { EVAL_MANUAL_QA_SCRIPTED: '1', EVAL_OFFLINE_IPC_CHILD: '1', EVAL_BACKEND: 'simulated',
    EVAL_API_KEY: 'offline-only', EVAL_ASSISTANT_MODEL: 'offline-manual-qa-loops', EVAL_BASE_URL: 'http://127.0.0.1:20001' };
  assert.doesNotThrow(() => assertManualQaOfflineConfiguration(safe));
  for (const patch of [{ EVAL_BACKEND: 'live' }, { EVAL_OFFLINE_IPC_CHILD: '' }, { EVAL_MANUAL_QA_SCRIPTED: '' },
    { EVAL_API_KEY: 'paid-key' }, { EVAL_ASSISTANT_MODEL: 'paid-model' }, { EVAL_BASE_URL: 'https://example.com' }])
    assert.throws(() => assertManualQaOfflineConfiguration({ ...safe, ...patch }), /offline-only/u);
});

test('manual QA loops: empty run cannot obtain a vacuous zero-error pass', () => {
  const verdict = gradeManualQaLoops({ scenario: generateManualQaLoopsScenario(), trace: [], conversation: [] });
  assert.equal(verdict.passed, false);
  assert.equal(verdict.checks.zero_validation_errors_before_preview, false);
  assert.equal(verdict.observations.no_successful_preview, true);
});

test('manual QA loops: Russian catalog choice does not consent to global Resolution creation', () => {
  const scenario = generateManualQaLoopsScenario(), sim = scenarioSimulator(scenario);
  const preview = sim('create_resolution', { dryRun: true, name: 'Fixed' });
  assert.equal(preview.status, 'WOULD_CREATE');
  const question = { role: 'assistant', turn: 1, content: '```json\n{"kind":"DECISIONS"}\n```' };
  const choice = { role: 'user', turn: 2, content: nextManualQaLoopsUser(scenario, [question]) };
  const args = { toolName: 'create_resolution', arguments: { dryRun: false, planId: preview.planId } };
  const result = sim('start_operation', args, { conversation: [question, choice], userTurn: 2, assistantTurn: 2 });
  assert.equal(result.errorCode, 'EVAL_CONFIRMATION_REQUIRED');
  assert.deepEqual(sim('get_resolutions', {}), scenario.resolutionCatalog, 'No new global Resolution was created');
  const verdict = gradeManualQaLoops({ scenario, conversation: [question, choice], trace: [
    { tool: 'create_resolution', arguments: { dryRun: true, name: 'Fixed' }, result: preview },
    { tool: 'start_operation', arguments: args, result },
  ] });
  assert.equal(verdict.passed, false);
  assert.equal(verdict.checks.no_resolution_creation_or_other_writes, false);
  assert.equal(verdict.checks.one_confirmed_write, false);
});
