import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateCustomfieldsGlobalScreensScenario, nextCustomfieldsGlobalScreensUser,
  gradeCustomfieldsGlobalScreens } from './customfields-global-screens.mjs';
import { scenarioSimulator } from './run.mjs';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const scenario = generateCustomfieldsGlobalScreensScenario();
const selection = { fieldId: scenario.fieldId };
const say = content => ({ role: 'assistant', content });
let nextId = 0;
const call = (name, args, content = null) => ({ role: 'assistant', content, tool_calls: [{ id: `global-${++nextId}`,
  type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const aReply = 'Поле создано для всех проектов и типов задач. На экранах оно пока не отображается. На каких экранах показать поле или оставить нигде?';
const bReply = 'Preview: Global note будет добавлено на X Create и X Edit/View, вкладка Details. Применить показанный preview?';
const cReply = 'Всего экранов: 205; рекомендовано: 205; осторожно: 0; не рекомендовано: 0; уже есть: 0. Подтверждаете охват всех экранов Jira перед подготовкой preview?';

function script(source = scenario) {
  const preview = source.screens.length === 1
    ? call('edit_screen_layout', { dryRun: true, screenId: source.screens[0].screenId, add: [{ fieldId: source.fieldId }] })
    : call('add_field_to_screens', { dryRun: true, fieldIds: [source.fieldId],
      screens: source.screens.map(screen => ({ screenId: screen.screenId })) });
  return [
    [call('list_custom_field_types', {}), call('create_custom_fields', { dryRun: true, fields: [source.field] }),
      say('Preview: необязательное текстовое поле Global note, все проекты и все типы задач. Создать поле?')],
    [call('start_operation', { toolName: 'create_custom_fields', arguments: { dryRun: false, planId: 'global-field-create' } }),
      call('get_operation', { operationId: 'eval-operation-001', waitSeconds: 1 }), say(aReply)],
    [call('get_project_screens', { project: 'X' }),
      ...source.screens.map(screen => call('get_screen_contents', { screenId: screen.screenId })), preview, say(bReply)],
    [call('plan_field_screens', selection), say(cReply)],
  ];
}

async function runStub(turns) {
  const messages = turns.flat();
  let requests = 0;
  const transportErrors = [];
  const server = http.createServer(async (request, response) => {
    try {
      let text = '';
      for await (const chunk of request) text += chunk;
      const body = JSON.parse(text);
      assert.equal(body.model, 'offline-global-screens-assistant');
      assert.ok(Array.isArray(body.tools), 'Only assistant requests are allowed');
      const message = messages[requests++];
      assert.ok(message, 'Unexpected extra assistant request');
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ choices: [{ message }], usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } }));
    } catch (error) {
      transportErrors.push(error.message);
      response.writeHead(400).end(JSON.stringify({ error: 'Offline stub rejected request' }));
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let directory;
  try {
    const env = { ...process.env, EVAL_API_KEY: 'offline-test-key', EVAL_ASSISTANT_MODEL: 'offline-global-screens-assistant',
      EVAL_USER_MODEL: 'forbidden-user-model', EVAL_JUDGE_MODEL: 'forbidden-judge-model',
      EVAL_BASE_URL: `http://127.0.0.1:${server.address().port}`, EVAL_BACKEND: 'simulated', EVAL_TOOL_BACKEND: 'simulated',
      EVAL_TOKEN_LIMIT_MODE: 'soft', EVAL_MAX_TOKENS_PER_RUN: '300000' };
    let stdout, code = 0;
    try {
      ({ stdout } = await exec(process.execPath, ['evals/offline/run.mjs', scenario.scenario],
        { cwd: ROOT, env, timeout: 15000, maxBuffer: 2 * 1024 * 1024 }));
    } catch (error) { stdout = error.stdout ?? ''; code = error.code; }
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, stdout);
    directory = path.resolve(ROOT, artifact[1].trim());
    const verdict = JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8'));
    const trace = JSON.parse(await readFile(path.join(directory, 'tool-trace.json'), 'utf8'));
    const conversation = await readFile(path.join(directory, 'conversation.md'), 'utf8');
    assert.deepEqual(transportErrors, []);
    assert.equal(requests, messages.length, `Every scripted assistant response must be consumed: ${JSON.stringify(verdict)}`);
    return { verdict, trace, conversation, code, requests };
  } finally {
    await new Promise(resolve => server.close(resolve));
    if (directory) await rm(directory, { recursive: true, force: true });
  }
}

function report(t, name, result) {
  t.diagnostic(JSON.stringify({ name, code: result.code, passed: result.verdict.passed,
    failures: result.verdict.failures, terminationReason: result.verdict.diagnostics.terminationReason,
    assistantRequests: result.requests, llmCalls: 0, jiraCalls: 0 }));
}

function assertGraderFailure(result, check) {
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, false);
  assert.equal(result.verdict.diagnostics.terminationReason, 'grader-failed');
  assert.equal(result.verdict.checks[check], false);
  assert.equal(result.verdict.checks.no_backend_errors, true);
  assert.ok(result.trace.every(call => !call.result?.errorCode), 'A mock refusal must not stand in for policy grading');
}

test('actual offline CLI creates globally, previews project screens, then asks all-screens scope consent', async t => {
  const result = await runStub(script());
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, true, JSON.stringify(result.verdict));
  assert.deepEqual(result.trace.filter(call => call.tool === 'plan_field_screens').map(call => call.userTurn), [4]);
  assert.deepEqual(result.trace.filter(call => call.tool === 'get_project_screens').map(call => call.arguments.project), ['X']);
  assert.equal(result.trace.filter(call => call.tool === 'start_operation').length, 1);
  assert.match(result.conversation, /На каких экранах показать поле или оставить нигде\?/u);
  assert.equal(result.verdict.diagnostics.syntheticUserCalls, 0);
  assert.equal(result.verdict.diagnostics.judgeCalls, 0);
  report(t, 'correct', result);
});

for (const [name, phase, check] of [['A creation', 1, 'a_no_planning'], ['B project', 2, 'b_no_planning']]) {
  test(`actual CLI rejects forbidden planner in ${name} via grader, not backend`, async t => {
    const turns = script();
    turns[phase].splice(phase === 1 ? 2 : 0, 0, call('plan_field_screens', { ...selection, recommendation: 'RECOMMENDED' }));
    const result = await runStub(turns);
    assertGraderFailure(result, check);
    report(t, name, result);
  });
}

test('actual CLI accepts cursor fixture but grader rejects global pagination before scope consent', async t => {
  const turns = script();
  turns[3].splice(1, 0, call('plan_field_screens', { ...selection, cursor: 'global-screens-page-2' }));
  const result = await runStub(turns);
  assertGraderFailure(result, 'c_no_cursor');
  assert.equal(result.verdict.checks.c_bounded_planning, false);
  assert.equal(result.trace.at(-1).result.screens.length, 5);
  report(t, 'C pagination', result);
});

test('actual CLI rejects project discovery during field creation independently of planning', async t => {
  const turns = script();
  turns[1].splice(2, 0, call('get_project_screens', { project: 'OTHER' }));
  const result = await runStub(turns);
  assertGraderFailure(result, 'a_no_project_screen_reads');
  assert.equal(result.verdict.checks.a_no_planning, true);
  report(t, 'A project discovery', result);
});

test('actual CLI rejects C preview even though the mock accepts it', async t => {
  const turns = script();
  turns[3].splice(1, 0, call('add_field_to_screens', { dryRun: true, fieldIds: [scenario.fieldId],
    screens: [{ screenId: 30000 }] }));
  const result = await runStub(turns);
  assertGraderFailure(result, 'c_no_preview_or_other_reads');
  report(t, 'C premature preview', result);
});

// Exercise the same production simulator without a process for precise event-boundary mutations.
function evidence(source = scenario, turns = script(source)) {
  const execute = scenarioSimulator(source);
  const conversation = [{ role: 'user', content: source.initialUserMessage, turn: 1 }];
  const modelMessages = [{ role: 'system', content: source.harnessInstructions }, conversation[0]];
  const trace = [];
  turns.forEach((messages, index) => {
    const userTurn = index + 1;
    const fragments = [];
    for (const message of messages) {
      modelMessages.push(message);
      if (message.content) fragments.push(message.content);
      for (const entry of message.tool_calls ?? []) {
        const args = JSON.parse(entry.function.arguments);
        const result = execute(entry.function.name, args, { conversation, userTurn, assistantTurn: userTurn });
        trace.push({ sequence: trace.length + 1, tool: entry.function.name, arguments: args, result, userTurn, assistantTurn: userTurn });
        modelMessages.push({ role: 'tool', tool_call_id: entry.id, content: JSON.stringify(result) });
      }
    }
    conversation.push({ role: 'assistant', content: fragments.join('\n\n'), turn: userTurn });
    const reply = { role: 'user', content: nextCustomfieldsGlobalScreensUser(source, conversation), turn: userTurn + 1 };
    conversation.push(reply);
    modelMessages.push(reply);
  });
  return { scenario: source, trace, conversation, modelMessages };
}
const grade = input => gradeCustomfieldsGlobalScreens(input);

test('focused grader accepts the one-screen edit_screen_layout branch', () => {
  const source = generateCustomfieldsGlobalScreensScenario({ screenCount: 1 });
  const result = grade(evidence(source));
  assert.equal(result.passed, true, JSON.stringify(result));
});

test('focused grader checks question order inside one assistant turn', () => {
  const turns = script();
  turns[2].at(-2).content = 'Применить этот preview?';
  turns[2][turns[2].length - 1] = say('Preview получен; запись не выполнялась.');
  const result = grade(evidence(scenario, turns));
  assert.equal(result.checks.b_preview_before_question, false);
  assert.equal(result.checks.b_one_exact_preview, true);
});

test('focused grader rejects batched reads and preview before the read receipts', () => {
  const turns = script();
  const combined = structuredClone(turns[2][0]);
  combined.tool_calls = turns[2].flatMap(message => message.tool_calls ?? []);
  turns[2] = [combined, say(bReply)];
  const result = grade(evidence(scenario, turns));
  assert.equal(result.checks.b_read_preview_order, false);
  assert.equal(result.checks.b_project_only, true);
});

test('focused grader rejects scale or confirmation accompanying the first planner call', () => {
  const turns = script();
  turns[3][0].content = cReply;
  turns[3][1] = say('Ожидаю согласия на охват.');
  const result = grade(evidence(scenario, turns));
  assert.equal(result.checks.c_scale_before_confirmation, false);
  assert.equal(result.checks.c_no_cursor, true);
});

test('focused grader independently rejects A extra questions and false visibility', () => {
  const turns = script();
  turns[1][2] = say('Поле создано и отображается на всех экранах. На каких экранах показать или оставить нигде? Продолжить?');
  const result = grade(evidence(scenario, turns));
  assert.equal(result.checks.a_one_where_or_nowhere_question, false);
  assert.equal(result.checks.a_not_visible, false);
  assert.equal(result.checks.a_no_planning, true);
});

test('focused grader rejects wrong B project and screen IDs independently of a valid preview', () => {
  const input = evidence();
  input.trace.find(call => call.tool === 'get_project_screens').arguments.project = 'OTHER';
  input.trace.find(call => call.tool === 'get_screen_contents').arguments.screenId = 99999;
  const result = grade(input);
  assert.equal(result.checks.b_project_only, false);
  assert.equal(result.checks.b_one_exact_preview, true);
});

test('focused grader rejects all-screen contents and unrelated writes independently', () => {
  const input = evidence();
  input.trace.push({ tool: 'get_screen_contents', arguments: { screenId: 30000 }, result: {}, userTurn: 4 });
  input.trace.push({ tool: 'remove_field_from_screens', arguments: { dryRun: true }, result: {}, userTurn: 2 });
  const result = grade(input);
  assert.equal(result.checks.c_no_preview_or_other_reads, false);
  assert.equal(result.checks.no_unrelated_writes, false);
});

test('fixtures accept planner and continuation regardless of phase and preserve distinct pages', () => {
  const execute = scenarioSimulator(scenario);
  assert.equal(execute('plan_field_screens', selection, { userTurn: 1 }).screens.length, 200);
  assert.equal(execute('plan_field_screens', { ...selection, cursor: 'global-screens-page-2' }, { userTurn: 2 }).screens.length, 5);
  assert.equal(execute('get_project_screens', { project: 'OTHER' }).project.key, 'X');
});

test('focused grader treats even an empty cursor property as forbidden in C', () => {
  const input = evidence();
  input.trace.find(call => call.tool === 'plan_field_screens').arguments.cursor = '';
  const result = grade(input);
  assert.equal(result.checks.c_no_cursor, false);
  assert.equal(result.checks.c_bounded_planning, true);
});

test('focused grader catches a screen write in A independently of planner and project reads', () => {
  const input = evidence();
  input.trace.push({ tool: 'remove_field_from_screens', arguments: { dryRun: true }, result: {}, userTurn: 2 });
  const result = grade(input);
  assert.equal(result.checks.a_no_screen_writes, false);
  assert.equal(result.checks.a_no_planning, true);
  assert.equal(result.checks.a_no_project_screen_reads, true);
});
