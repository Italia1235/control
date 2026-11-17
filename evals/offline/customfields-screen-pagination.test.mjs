import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateCustomfieldsScreenPaginationScenario, gradeCustomfieldsScreenPagination } from './customfields-screen-pagination.mjs';
import { scenarioSimulator } from './run.mjs';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const scenario = generateCustomfieldsScreenPaginationScenario();
const selection = { fieldId: 'customfield_10123', recommendation: 'RECOMMENDED' };
const call = args => ({ role: 'assistant', content: null, tool_calls: [{ id: `page-${args.cursor ?? 'first'}`,
  type: 'function', function: { name: 'plan_field_screens', arguments: JSON.stringify(args) } }] });
const say = content => ({ role: 'assistant', content });

async function runStub(messages) {
  let requests = 0;
  const transportErrors = [];
  const server = http.createServer(async (request, response) => {
    try {
      let text = '';
      for await (const chunk of request) text += chunk;
      const body = JSON.parse(text);
      assert.equal(body.model, 'offline-cf04-assistant');
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
    const env = { ...process.env, EVAL_API_KEY: 'offline-test-key', EVAL_ASSISTANT_MODEL: 'offline-cf04-assistant',
      EVAL_USER_MODEL: 'forbidden-user-model', EVAL_JUDGE_MODEL: 'forbidden-judge-model',
      EVAL_BASE_URL: `http://127.0.0.1:${server.address().port}`, EVAL_TOOL_BACKEND: 'simulated',
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
    return { verdict, trace, conversation, code, requests, stdout };
  } finally {
    await new Promise(resolve => server.close(resolve));
    if (directory) await rm(directory, { recursive: true, force: true });
  }
}

function report(t, name, result) {
  const ids = result.trace.flatMap(call => call.result?.screens?.map(screen => screen.screenId) ?? []);
  t.diagnostic(JSON.stringify({ name, code: result.code, passed: result.verdict.passed,
    uniqueScreens: new Set(ids).size, expectedScreens: 205, failures: result.verdict.failures,
    terminationReason: result.verdict.diagnostics.terminationReason,
    assistantRequests: result.requests, llmCalls: 0, jiraCalls: 0 }));
}

test('offline runner reads both pages before reporting complete coverage', async t => {
  const result = await runStub([call(selection), call({ ...selection, cursor: 'cf04-page-2' }),
    say('Просмотрено рекомендованных экранов: 205')]);
  assert.equal(result.trace[1].result.screens.length, 5, 'Continuation must return the last five screens, not page one');
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, true, JSON.stringify(result.verdict));
  assert.deepEqual(result.trace.flatMap(call => call.result.screens.map(screen => screen.screenId)), scenario.expectedIds);
  assert.equal(result.verdict.checks.no_premature_total, true);
  assert.match(result.conversation, /Просмотрено рекомендованных экранов: 205/u);
  report(t, 'correct', result);
});

test('offline runner rejects a completeness claim after the first page', async t => {
  const result = await runStub([call(selection), say('Просмотрено рекомендованных экранов: 205')]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, false);
  assert.equal(result.verdict.checks.exact_coverage, false);
  assert.equal(result.verdict.checks.no_premature_total, false);
  report(t, 'premature completeness', result);
});

test('offline runner rejects a request that does not repeat the supplied cursor', async t => {
  const result = await runStub([call(selection), call({ ...selection, cursor: 'wrong-cursor' })]);
  assert.equal(result.verdict.passed, false);
  assert.equal(result.code, 2, JSON.stringify(result.verdict));
  assert.equal(result.trace[1].result.errorCode, 'EVAL_UNEXPECTED_TOOL');
  assert.equal(result.verdict.status, 'ERROR');
  assert.match(result.verdict.diagnostics.terminationReason, /EVAL_UNEXPECTED_TOOL/u);
  report(t, 'wrong cursor', result);
});

test('fixture matching distinguishes cursor values and missing cursor independently of fixture order', () => {
  const reversed = structuredClone(scenario);
  reversed.readResults.plan_field_screens.reverse();
  const execute = scenarioSimulator(reversed);
  assert.equal(execute('plan_field_screens', selection).screens[0].screenId, 20000);
  assert.equal(execute('plan_field_screens', { ...selection, cursor: 'cf04-page-2' }).screens[0].screenId, 20200);
  assert.equal(execute('plan_field_screens', { ...selection, cursor: 'wrong-cursor' }).errorCode, 'EVAL_UNEXPECTED_TOOL');
});

test('offline runner rejects an omitted continuation cursor as a duplicate read', async t => {
  const result = await runStub([call(selection), call(selection), say('Просмотрено рекомендованных экранов: 205')]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, false);
  assert.equal(result.trace[1].result.errorCode, 'EVAL_DUPLICATE_READ_LOOP');
  assert.match(result.verdict.diagnostics.terminationReason, /duplicate\/polling loop/u);
  report(t, 'missing continuation cursor', result);
});

test('cursor selection and terminal-page checks fail independently', () => {
  const fixtures = scenario.readResults.plan_field_screens;
  const trace = fixtures.map(fixture => ({ tool: 'plan_field_screens',
    arguments: structuredClone(fixture.match), result: structuredClone(fixture.result) }));
  const grade = rows => gradeCustomfieldsScreenPagination({ scenario, trace: rows,
    conversation: [say('Просмотрено рекомендованных экранов: 205')], modelMessages: [] }).checks;
  trace[1].result.nextCursor = 'unexpected-third-page';
  assert.equal(grade(trace).continuation_cursor_matches_selection, true);
  assert.equal(grade(trace).last_page_has_no_next_cursor, false);
  delete trace[1].result.nextCursor;
  trace[1].arguments.cursor = 'wrong-cursor';
  assert.equal(grade(trace).continuation_cursor_matches_selection, false);
  assert.equal(grade(trace).last_page_has_no_next_cursor, true);
  trace[1].arguments.cursor = fixtures[0].result.nextCursor;
  trace[1].arguments.fieldId = 'customfield_99999';
  assert.equal(grade(trace).continuation_cursor_matches_selection, false);
  trace[1].arguments.fieldId = selection.fieldId;
  delete trace[1].arguments.recommendation;
  assert.equal(grade(trace).continuation_cursor_matches_selection, false);
});
