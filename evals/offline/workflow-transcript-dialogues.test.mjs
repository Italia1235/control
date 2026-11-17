// Existing IPC transport forbids sockets, real Jira, synthetic-user LLMs and judge LLMs.
import './offline-network.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { transcriptDialogueScenarios, generateTranscriptDialogueScenario, createTranscriptDialogueMock,
  assertTranscriptOfflineConfiguration, gradeTranscriptDialogue, transcriptCard,
  nextTranscriptDialogueUser, TRANSCRIPT_CHOICE, TRANSCRIPT_CONSENT } from './workflow-transcript-dialogues.mjs';
import { scenarioSimulator } from './run.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const exec = promisify(execFile);
let sequence = 0;
const call = (name, args) => ({ role: 'assistant', content: null, tool_calls: [{ id: `transcript-${++sequence}`,
  type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const card = (data, prose = 'Все значения явно показаны в карточке ниже.') => ({ role: 'assistant',
  content: `${prose}\n\n\`\`\`json\n${JSON.stringify(data)}\n\`\`\`` });
function receipts(messages, name) {
  const calls = new Map(messages.flatMap(m => m.tool_calls ?? []).map(c => [c.id, c.function.name]));
  return messages.filter(m => m.role === 'tool' && calls.get(m.tool_call_id) === name).map(m => JSON.parse(m.content));
}
function submittedGraph(scenario, messages, diagnostic = false) {
  const graph = structuredClone(scenario.workflow);
  const catalog = receipts(messages, 'get_resolutions').flat();
  const completed = receipts(messages, 'get_operation').filter(r => r.status === 'COMPLETED').map(r => r.result);
  catalog.push(...completed.map(r => r.resolution).filter(Boolean));
  for (const edge of graph.transitions) {
    if (edge.screen) {
      if (!diagnostic) {
        const screen = completed.find(s => s?.screenId && s.name === edge.screen);
        assert.ok(screen?.screenId, 'Use a returned real screen ID, never an invented future ID');
        edge.screen = String(screen.screenId);
      } else delete edge.screen;
    }
    if (edge.resolution?.action === 'SET') {
      const resolution = catalog.find(r => r.name === edge.resolution.value);
      // Successful diagnostic deliberately uses an existing valid value for the missing future Resolution.
      assert.ok(resolution || diagnostic, 'Use actual created Resolution ID after terminal success');
      edge.resolution.value = resolution?.id ?? catalog[0].id;
    }
  }
  return graph;
}
function script(scenario, violation) {
  const steps = [() => call('get_resolutions', {}), () => call('list_statuses', {})];
  if (['resolution_string', 'resolutionId'].includes(violation)) steps.push(messages => {
    const graph = submittedGraph(scenario, messages, true);
    const target = graph.transitions.find(t => t.resolution?.action === 'SET');
    if (violation === 'resolution_string') target.resolution = target.resolution.value;
    else { target.resolutionId = target.resolution.value; delete target.resolution; }
    return call('configure_workflow', { dryRun: true, create: graph });
  });
  steps.push(messages => call('configure_workflow', { dryRun: true, create: submittedGraph(scenario, messages, true) }));
  steps.push(() => {
    const decisions = structuredClone(scenario.decisions);
    if (violation === 'hidden_categories') delete decisions.categories;
    return card({ kind: 'DECISIONS', decisions }, 'Выберите все перечисленные параметры; это ещё не разрешение на запись.');
  });
  if (violation === 'multiple_question_blocks') steps.push(() => card({ kind: 'DECISIONS', decisions: scenario.decisions }, 'Дополнительно подтвердите те же параметры?'));
  if (violation === 'discoverable_requirement') steps.push(() => card({ kind: 'REQUIREMENT_QUESTION', requirement: 'DONE requires SET' }, 'Нужен ли обязательный SET при переходе в DONE?'));
  for (const screen of scenario.screens) steps.push(() => call('create_screen', { dryRun: true, name: screen.name, fields: screen.fields.map(name => ({ name })) }));
  if (scenario.createResolution) steps.push(() => call('create_resolution', { dryRun: true, name: scenario.createResolution.name, description: '' }));
  steps.push(messages => {
    const plan = { workflow: scenario.workflow, screens: scenario.screens, createResolution: scenario.createResolution };
    if (violation === 'resolution_without_approval') plan.createResolution = null;
    const prerequisites = scenario.operations.filter(o => o.id !== 'workflow').map(o => {
      const preview = receipts(messages, o.tool).find(r => r.planId === o.preview.planId);
      assert.ok(preview, 'Actual dependency preview exists before showing the aggregate plan');
      return { tool: o.tool, planId: preview.planId };
    });
    return card({ kind: 'APPROVE_DEPENDENT_PLAN', plan, prerequisites, pendingWorkflowPreview: true, stopOnDrift: true },
      'ПРЕДЛОЖЕНИЕ ЭКСПЕРИМЕНТА, не действующая production policy. Это один зависимый план. Полного preview workflow пока нет: сначала отдельные записи зависимостей, которые останутся при сбое workflow, затем настоящий preview с возвращёнными ID. Запись workflow разрешена только при точном совпадении; при изменении остановиться. Разрешаете все явно перечисленные записи?');
  });
  for (const operation of scenario.operations.filter(o => o.id !== 'workflow')) {
    steps.push(messages => call('start_operation', { toolName: operation.tool, arguments: { dryRun: false,
      planId: receipts(messages, operation.tool).find(r => r.planId === operation.preview.planId).planId } }));
    steps.push(messages => call('get_operation', { operationId: receipts(messages, 'start_operation').at(-1).operationId }));
  }
  steps.push(messages => call('configure_workflow', { dryRun: true, create: submittedGraph(scenario, messages) }));
  steps.push(messages => call('start_operation', { toolName: 'configure_workflow', arguments: { dryRun: false,
    planId: receipts(messages, 'configure_workflow').filter(r => r.status === 'WOULD_CREATE').at(-1).planId } }));
  steps.push(messages => call('get_operation', { operationId: receipts(messages, 'start_operation').at(-1).operationId }));
  steps.push(() => card({ kind: 'WORKFLOW_CREATED', workflow: scenario.workflow }, 'Все операции завершены; workflow проектам не назначается.'));
  return steps;
}

async function runStub(scenario, violation) {
  const steps = script(scenario, violation), errors = [];
  let requests = 0, directory, modelMessages = [];
  const server = http.createServer(async (request, response) => {
    try {
      let text = '';
      for await (const chunk of request) text += chunk;
      const body = JSON.parse(text);
      assert.equal(request.url, '/chat/completions');
      assert.equal(body.model, 'offline-workflow-transcripts');
      assert.ok(Array.isArray(body.tools), 'Synthetic user and judge requests are forbidden');
      const step = steps[requests++];
      assert.ok(step, 'Unexpected extra assistant request');
      const message = step(body.messages);
      modelMessages = structuredClone([...body.messages, message]);
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ choices: [{ message }], usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } }));
    } catch (error) { errors.push(error.stack); response.writeHead(400).end(JSON.stringify({ error: 'Offline transcript stub rejected request' })); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const env = { ...process.env, EVAL_TRANSCRIPT_SCRIPTED: '1', EVAL_API_KEY: 'offline-only',
      EVAL_ASSISTANT_MODEL: 'offline-workflow-transcripts', EVAL_USER_MODEL: 'forbidden-user', EVAL_JUDGE_MODEL: 'forbidden-judge',
      EVAL_BASE_URL: `http://127.0.0.1:${server.address().port}`, EVAL_BACKEND: 'simulated',
      EVAL_MAX_TOKENS_PER_RUN: '', EVAL_TOKEN_LIMIT_MODE: 'hard' };
    let stdout = '', stderr = '', code = 0;
    try { ({ stdout } = await exec(process.execPath, ['evals/offline/run.mjs', scenario.scenario], { cwd: ROOT, env, timeout: 20000, maxBuffer: 2 * 1024 * 1024 })); }
    catch (error) { stdout = error.stdout ?? ''; stderr = error.stderr ?? ''; code = error.code; }
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, stdout + stderr);
    directory = path.resolve(ROOT, artifact[1].trim());
    const load = async name => JSON.parse(await readFile(path.join(directory, name), 'utf8'));
    let userTurn = 0, assistantTurn = 0;
    const conversation = modelMessages.filter(m => ['user', 'assistant'].includes(m.role) && m.content?.trim())
      .map(m => ({ role: m.role, content: m.content, turn: m.role === 'user' ? ++userTurn : ++assistantTurn }));
    const result = { code, requests, verdict: await load('verdict.json'), trace: await load('tool-trace.json'), conversation, modelMessages };
    assert.deepEqual(errors, []);
    assert.equal(requests, steps.length, 'Negative stubs must reach their real terminal workflow completion, not merely fail early');
    return result;
  } finally {
    await new Promise(resolve => server.close(resolve));
    if (directory) await rm(directory, { recursive: true, force: true });
  }
}

for (const name of transcriptDialogueScenarios) test(`${name}: real offline runner, zero failed probes and one aggregate consent`, async t => {
  const scenario = generateTranscriptDialogueScenario(name), result = await runStub(scenario);
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, true, JSON.stringify(result.verdict));
  const metrics = result.verdict.observations;
  assert.equal(metrics.validation_errors_before_first_successful_preview, 0);
  assert.equal(metrics.validation_errors_before_first_successful_workflow_preview, 0);
  assert.equal(metrics.decision_blocks, 1);
  assert.equal(metrics.displayed_plans, 1);
  assert.equal(metrics.explicit_write_consents, 1);
  assert.equal(metrics.parameter_question_blocks_before_first_write, 1);
  assert.equal(metrics.workflow_successful_previews, 2);
  assert.equal(metrics.questions_before_first_write, 2, 'Parameter choice and explicit write consent are distinct questions');
  assert.equal(result.trace.filter(c => c.tool === 'get_operation' && c.result?.status === 'COMPLETED').length, scenario.operations.length);
  const workflow = result.trace.filter(c => c.tool === 'configure_workflow').at(-1).arguments.create;
  if (scenario.screens.length) {
    assert.equal(workflow.initialStatus, 'To Do');
    assert.equal(workflow.statuses.some(s => s.name === 'New'), false);
    assert.equal(workflow.transitions.filter(t => t.screen).length, 2);
  } else {
    assert.equal(workflow.transitions.find(t => t.to === 'Completed').resolution.action, 'SET');
    assert.equal(workflow.transitions.find(t => t.name === 'Cancel Immediately').fromAny, true);
    assert.deepEqual(workflow.transitions.find(t => t.name === 'Return to Backlog').resolution, { action: 'CLEAR' });
  }
  // All mutations retain the successful real execution trace: presentation alone must still fail.
  for (const mutation of ['absent', 'wrong', 'moved']) {
    const data = structuredClone(result);
    const message = data.modelMessages.find(m => transcriptCard(m)?.kind === 'DECISIONS');
    const decision = transcriptCard(message);
    if (mutation === 'wrong') decision.decisions.categories[0].category = 'DONE';
    else {
      const categories = decision.decisions.categories;
      delete decision.decisions.categories;
      if (mutation === 'moved') {
        const planMessage = data.modelMessages.find(m => transcriptCard(m)?.kind === 'APPROVE_DEPENDENT_PLAN');
        const plan = transcriptCard(planMessage);
        plan.confirmedCategories = categories;
        planMessage.content = card(plan).content;
      }
    }
    message.content = card(decision).content;
    const grade = gradeTranscriptDialogue({ scenario, ...data });
    assert.equal(grade.passed, false);
    assert.equal(grade.checks.each_confirmed_decision_displayed_in_same_message, false, mutation);
  }
  t.diagnostic(JSON.stringify({ scenario: name, metrics, assistantRequests: result.requests, externalLLMCalls: 0, jiraCalls: 0, networkSockets: 0 }));
});

for (const [violation, check] of [
  ['resolution_string', 'zero_validation_errors_before_workflow_preview'], ['resolutionId', 'zero_validation_errors_before_workflow_preview'],
  ['multiple_question_blocks', 'one_decision_block'], ['discoverable_requirement', 'no_dry_run_discoverable_question'],
  ['resolution_without_approval', 'resolution_creation_explicitly_approved'], ['hidden_categories', 'each_confirmed_decision_displayed_in_same_message'],
]) test(`Purple Octopus: completed negative stub rejects ${violation}`, async t => {
  const scenario = generateTranscriptDialogueScenario(transcriptDialogueScenarios[1]);
  const result = await runStub(scenario, violation);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, false);
  assert.equal(result.verdict.checks[check], false, JSON.stringify(result.verdict));
  assert.equal(result.verdict.checks.exact_confirmed_writes, true, 'The workflow and Resolution actually completed in the simulated runner');
  assert.equal(result.verdict.checks.actual_dependent_preview_matches_approved_graph, true);
  if (['resolution_string', 'resolutionId'].includes(violation))
    assert.equal(result.verdict.observations.validation_errors_before_first_successful_workflow_preview, 1);
  t.diagnostic(JSON.stringify({ scenario: scenario.scenario, violation, passed: result.verdict.passed,
    failures: result.verdict.failures, metrics: result.verdict.observations }));
});

test('offline guard rejects every wired/paid/non-IPC entry path', () => {
  const safe = { EVAL_TRANSCRIPT_SCRIPTED: '1', EVAL_OFFLINE_IPC_CHILD: '1', EVAL_BACKEND: 'simulated',
    EVAL_API_KEY: 'offline-only', EVAL_ASSISTANT_MODEL: 'offline-workflow-transcripts', EVAL_BASE_URL: 'http://127.0.0.1:20001' };
  assert.doesNotThrow(() => assertTranscriptOfflineConfiguration(safe));
  for (const patch of [{ EVAL_BACKEND: 'wired' }, { EVAL_OFFLINE_IPC_CHILD: '' }, { EVAL_TRANSCRIPT_SCRIPTED: '' },
    { EVAL_API_KEY: 'paid' }, { EVAL_ASSISTANT_MODEL: 'paid' }, { EVAL_BASE_URL: 'https://api.openai.com' },
    { EVAL_BASE_URL: 'http://user:pass@127.0.0.1:20001' }])
    assert.throws(() => assertTranscriptOfflineConfiguration({ ...safe, ...patch }), /offline-only/u);
});

test('choice is not consent and empty traces cannot pass', () => {
  const scenario = generateTranscriptDialogueScenario();
  assert.equal(nextTranscriptDialogueUser(scenario, [card({ kind: 'DECISIONS', decisions: scenario.decisions })]), TRANSCRIPT_CHOICE);
  const hidden = structuredClone(scenario.decisions);
  delete hidden.categories;
  assert.notEqual(nextTranscriptDialogueUser(scenario, [card({ kind: 'DECISIONS', decisions: hidden })]), TRANSCRIPT_CHOICE);
  assert.notEqual(nextTranscriptDialogueUser(scenario, [card({ kind: 'APPROVE_DEPENDENT_PLAN' })]), TRANSCRIPT_CONSENT);
  assert.notEqual(TRANSCRIPT_CHOICE, TRANSCRIPT_CONSENT);
  assert.doesNotMatch(TRANSCRIPT_CHOICE, /^(?:да|yes)/iu);
  const verdict = gradeTranscriptDialogue({ scenario, trace: [], conversation: [] });
  assert.equal(verdict.passed, false);
  assert.equal(verdict.checks.zero_validation_errors_before_preview, false);
});

test('bounded mock requires real SET object and existing IDs, never a future fake ID', () => {
  const scenario = generateTranscriptDialogueScenario(transcriptDialogueScenarios[1]);
  const messages = [call('get_resolutions', {}), { role: 'tool', tool_call_id: `transcript-${sequence}`, content: JSON.stringify(scenario.resolutionCatalog) }];
  const valid = submittedGraph(scenario, messages, true);
  for (const resolution of ['10000', { value: '10000' }, { action: 'SET', value: 'future-resolution-id' }, undefined]) {
    const graph = structuredClone(valid);
    graph.transitions.find(t => t.to === 'Completed').resolution = resolution;
    assert.equal(createTranscriptDialogueMock(scenario)('configure_workflow', { dryRun: true, create: graph }).errorCode, 'VALIDATION_ERROR');
  }
  assert.equal(createTranscriptDialogueMock(scenario)('configure_workflow', { dryRun: true, create: valid }).status, 'WOULD_CREATE');
});

test('aggregate consent never authorizes a semantically changed workflow', () => {
  const scenario = generateTranscriptDialogueScenario(transcriptDialogueScenarios[1]);
  const sim = scenarioSimulator(scenario);
  const graph = structuredClone(scenario.workflow);
  for (const edge of graph.transitions) if (edge.resolution?.action === 'SET') edge.resolution.value = '10000';
  const preview = sim('configure_workflow', { dryRun: true, create: graph });
  const conversation = [{ role: 'assistant', turn: 1, ...card({ kind: 'APPROVE_DEPENDENT_PLAN',
    plan: { workflow: scenario.workflow, screens: [], createResolution: scenario.createResolution }, pendingWorkflowPreview: true, stopOnDrift: true }) },
    { role: 'user', turn: 2, content: TRANSCRIPT_CONSENT }];
  const result = sim('start_operation', { toolName: 'configure_workflow', arguments: { dryRun: false, planId: preview.planId } }, { conversation, userTurn: 2 });
  assert.equal(result.errorCode, 'EVAL_CONFIRMATION_REQUIRED');
});

test('parameter choice cannot create a global Resolution before aggregate write approval', () => {
  const scenario = generateTranscriptDialogueScenario(transcriptDialogueScenarios[1]);
  const sim = scenarioSimulator(scenario);
  const preview = sim('create_resolution', { dryRun: true, name: "Won't Do", description: '' });
  const conversation = [{ ...card({ kind: 'DECISIONS', decisions: scenario.decisions }), turn: 1 },
    { role: 'user', turn: 2, content: TRANSCRIPT_CHOICE }];
  const args = { toolName: 'create_resolution', arguments: { dryRun: false, planId: preview.planId } };
  assert.equal(sim('start_operation', args, { conversation, userTurn: 2 }).errorCode, 'EVAL_CONFIRMATION_REQUIRED');
  assert.equal(sim('get_resolutions', {}).some(r => r.name === "Won't Do"), false);
});

