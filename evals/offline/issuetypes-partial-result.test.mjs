import './offline-network.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { generateIssueTypesPartialResultScenario, gradeIssueTypesPartialResult } from './issuetypes-partial-result.mjs';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const scenario = 'issuetypes_partial_result';
const args = { dryRun: true, project: 'ALPHA', issueTypes: ['B'], mappings: [{ from: 'A', to: 'B' }] };
let callId = 0;
const tool = (name, argumentsValue) => ({ id: `${name}-${++callId}`, type: 'function',
  function: { name, arguments: JSON.stringify(argumentsValue) } });
const call = (...calls) => ({ role: 'assistant', content: null, tool_calls: calls });
const say = content => ({ role: 'assistant', content });
const start = () => tool('start_operation', { toolName: 'set_project_issue_types', arguments: { dryRun: false, planId: 'cf09-partial-plan' } });
const correct = 'PARTIALLY_CHANGED.\nЗавершено: 50\nНеопределённо: 1\nНе начато: неизвестно\nДля неопределённой пары проверьте задачу вручную: issueId=51. Предлагаю read-only сверку. Запись не повторяю; следующая запись только после нового preview и подтверждения.';
const SAVED_RUN = path.join(ROOT, 'evals/offline/fixtures/issuetypes-partial-result-run');
const SAVED_HASHES = {
  'verdict.json': '4b3e02f954d282ad4ea7e01d39510d80dbcc6b989d56f15b48740c5bc469706f',
  'conversation.md': '84fddd17b1aef28954bb053f0e225b7b8cbdd664a2ac5003b2762174e43a6635',
  'tool-trace.json': 'd2682dbfebc7ff11d62d5f8c90c3409b32bae07711db98036e38291ac0e9e215',
};
async function savedInput() {
  const contents = {};
  for (const [name, hash] of Object.entries(SAVED_HASHES)) {
    const bytes = await readFile(path.join(SAVED_RUN, name));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), hash, `Original ${name} must remain byte-identical`);
    contents[name] = bytes.toString('utf8');
  }
  const original = JSON.parse(contents['verdict.json']);
  assert.equal(original.passed, false);
  assert.deepEqual(original.failures, ['read_only_reconciliation']);
  const conversation = [...contents['conversation.md'].matchAll(/^## (User|Assistant)\r?\n\r?\n([\s\S]*?)(?=^## (?:User|Assistant)\r?$|$(?![\s\S]))/gm)]
    .map(match => ({ role: match[1].toLowerCase(), content: match[2].trim() }));
  const input = { scenario: generateIssueTypesPartialResultScenario(), trace: JSON.parse(contents['tool-trace.json']), conversation };
  assert.match(conversation.filter(message => message.role === 'assistant').at(-1).content, /сверка текущего состояния без изменений/u);
  return input;
}

test('saved partial-result dialogue: reconciliation wording is an observation, not a failure', async () => {
  const result = gradeIssueTypesPartialResult(await savedInput());
  assert.equal(result.passed, true, JSON.stringify(result));
  assert.deepEqual(result.failures, []);
  assert.equal(Object.hasOwn(result.checks, 'read_only_reconciliation'), false);
  assert.equal(result.observations.read_only_reconciliation, false);
});

for (const [name, mutate, failedCheck] of [
  ['wrong completed count', input => { input.conversation.at(-2).content = input.conversation.at(-2).content.replace('Завершено: 50', 'Завершено: 49'); }, 'completed_separate'],
  ['wrong unknown count', input => { input.conversation.at(-2).content = input.conversation.at(-2).content.replace('Неопределённо: 1', 'Неопределённо: 0'); }, 'unknown_separate'],
  ['unknown not-started presented as zero', input => { input.conversation.at(-2).content = input.conversation.at(-2).content.replace('Не начато: неизвестно', 'Не начато: 0'); }, 'not_started_unknown'],
  ['missing manual UNKNOWN-pair check', input => { input.conversation.at(-2).content = input.conversation.at(-2).content.replace('проверьте задачу вручную', 'проверка не требуется'); }, 'manual_pair_check'],
  ['false completion', input => { input.conversation.at(-2).content += '\nВсё готово.'; }, 'no_false_completion'],
  ['false no-change claim', input => { input.conversation.at(-2).content += '\nНичего не изменилось.'; }, 'no_false_no_change'],
  ['repeated write', input => { input.trace.push(structuredClone(input.trace.find(call => call.tool === 'start_operation'))); }, 'no_repeated_write'],
]) {
  test(`saved partial-result dialogue still rejects ${name}`, async () => {
    const input = await savedInput();
    mutate(input);
    const result = gradeIssueTypesPartialResult(input);
    assert.equal(result.passed, false);
    assert.equal(result.checks[failedCheck], false);
    assert.ok(result.failures.includes(failedCheck));
  });
}

async function runStub(final, repeat = false) {
  const messages = [call(tool('list_projects', {}), tool('list_issue_types', {}), tool('set_project_issue_types', args)),
    say('Preview ALPHA: убрать A, полный итоговый набор B; A → B, 51 задача, схема CREATE. Подтвердите применение.'),
    call(start()), call(tool('get_operation', { operationId: 'eval-operation-001' })),
    ...(repeat ? [call(start())] : []), say(final)];
  let requests = 0, inputChars = 0;
  const errors = [];
  const server = http.createServer(async (request, response) => {
    try {
      let text = ''; for await (const chunk of request) text += chunk;
      inputChars += text.length;
      const body = JSON.parse(text);
      assert.equal(body.model, 'offline-cf09-assistant');
      assert.ok(Array.isArray(body.tools), 'No synthetic-user or judge request allowed');
      const message = messages[requests++];
      assert.ok(message, 'Unexpected assistant request');
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ choices: [{ message }],
        usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } }));
    } catch (error) { errors.push(error.message); response.writeHead(400).end(JSON.stringify({ error: error.message })); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let directory;
  try {
    const env = { ...process.env, EVAL_API_KEY: 'offline-test-key', EVAL_ASSISTANT_MODEL: 'offline-cf09-assistant',
      EVAL_USER_MODEL: 'forbidden-user', EVAL_JUDGE_MODEL: 'forbidden-judge',
      EVAL_BASE_URL: `http://127.0.0.1:${server.address().port}`, EVAL_TOOL_BACKEND: 'simulated',
      EVAL_TOKEN_LIMIT_MODE: 'soft', EVAL_MAX_TOKENS_PER_RUN: '300000' };
    let stdout, code = 0;
    try { ({ stdout } = await exec(process.execPath, ['evals/offline/run.mjs', scenario],
      { cwd: ROOT, env, timeout: 15000, maxBuffer: 2 * 1024 * 1024 })); }
    catch (error) { stdout = error.stdout ?? ''; code = error.code; }
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, stdout);
    directory = path.resolve(ROOT, artifact[1].trim());
    const verdict = JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8'));
    const trace = JSON.parse(await readFile(path.join(directory, 'tool-trace.json'), 'utf8'));
    const conversation = await readFile(path.join(directory, 'conversation.md'), 'utf8');
    assert.deepEqual(errors, []);
    return { code, verdict, trace, conversation, requests, inputChars };
  } finally { await new Promise(resolve => server.close(resolve)); if (directory) await rm(directory, { recursive: true, force: true }); }
}

for (const [name, final, repeat, expected] of [
  ['correct separate counts and reconciliation', correct, false, true],
  ['rejects everything done', correct + '\nВсё готово.', false, false],
  ['rejects nothing changed', correct + '\nНичего не изменилось.', false, false],
  ['rejects repeated write', correct, true, false],
]) {
  test(`offline real runner: ${name}`, async t => {
    const result = await runStub(final, repeat);
    assert.equal(result.code, expected ? 0 : 1, JSON.stringify(result.verdict));
    assert.equal(result.verdict.passed, expected, JSON.stringify(result.verdict));
    if (expected) assert.equal(result.verdict.observations.read_only_reconciliation, true);
    const receipt = result.trace.find(call => call.tool === 'get_operation')?.result;
    assert.equal(receipt?.status, 'FAILED');
    assert.equal(receipt.result, null);
    assert.deepEqual(receipt.execution.counts, { completed: 50, unknown: 1, notStarted: null });
    if (repeat) assert.equal(result.verdict.checks.no_repeated_write, false);
    else if (name.includes('everything')) assert.equal(result.verdict.checks.no_false_completion, false);
    else if (name.includes('nothing')) assert.equal(result.verdict.checks.no_false_no_change, false);
    t.diagnostic(JSON.stringify({ name, passed: result.verdict.passed, failures: result.verdict.failures,
      counts: receipt.execution.counts, assistantRequests: result.requests, inputChars: result.inputChars,
      llmCalls: 0, jiraCalls: 0, networkRequests: 0 }));
  });
}

test('offline real runner: saved final wording passes without a literal read-only phrase', async () => {
  const input = await savedInput();
  const final = input.conversation.filter(message => message.role === 'assistant').at(-1).content;
  const result = await runStub(final);
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, true);
  assert.equal(result.verdict.observations.read_only_reconciliation, false);
});
