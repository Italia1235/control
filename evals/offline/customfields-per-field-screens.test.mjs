import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateCustomfieldsPerFieldScreensScenario } from './customfields-per-field-screens.mjs';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const scenario = generateCustomfieldsPerFieldScreensScenario();
const call = (name, args) => ({ role: 'assistant', content: null, tool_calls: [{ id: `call-${name}-${args.fieldId ?? 'preview'}`,
  type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const say = content => ({ role: 'assistant', content });
const planA = () => call('plan_field_screens', { fieldId: scenario.fields.a });
const planB = () => call('plan_field_screens', { fieldId: scenario.fields.b });
const preview = fieldIds => call('add_field_to_screens', { dryRun: true, fieldIds, screens: [{ screenId: scenario.screenId }] });

// Prepared only: real CLI loop on loopback transport, no LLM/OMP/Jira, no synthetic-user model or judge.
async function runStub(messages) {
  let requests = 0, inputChars = 0;
  const transportErrors = [];
  const server = http.createServer(async (request, response) => {
    try {
      let text = '';
      for await (const chunk of request) text += chunk;
      const body = JSON.parse(text);
      assert.equal(body.model, 'offline-cf05-assistant');
      assert.ok(Array.isArray(body.tools), 'Only assistant requests are allowed');
      inputChars += text.length;
      const message = messages[requests++];
      assert.ok(message, 'Unexpected extra assistant request');
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ choices: [{ message }], usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } }));
    } catch (error) {
      transportErrors.push(error.message);
      response.writeHead(400).end(JSON.stringify({ error: 'Offline stub rejected request' }));
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let directory;
  try {
    const env = { ...process.env, EVAL_API_KEY: 'offline-test-key', EVAL_ASSISTANT_MODEL: 'offline-cf05-assistant',
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
    assert.deepEqual(transportErrors, []);
    return { verdict, trace, code, requests, inputChars };
  } finally {
    await new Promise(resolve => server.close(resolve));
    if (directory) await rm(directory, { recursive: true, force: true });
  }
}

test('separate field plans retain shared S for B; preview contains only missing B placement', async t => {
  const result = await runStub([planA(), planB(), preview([scenario.fields.b]),
    say('A уже на S. B будет добавлено на S, общий с OTHER: затронут ещё один проект. Это только preview, ничего не применено.')]);
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, true, JSON.stringify(result.verdict));
  assert.deepEqual(result.trace.map(call => call.tool), ['plan_field_screens', 'plan_field_screens', 'add_field_to_screens']);
  assert.equal(result.requests, 4);
  t.diagnostic(JSON.stringify({ assistantRequests: result.requests, inputChars: result.inputChars, llmCalls: 0, jiraCalls: 0 }));
});

test('violating assistant plans A once and groups A+B; accepted backend preview still fails trace grading', async () => {
  const result = await runStub([planA(), preview([scenario.fields.a, scenario.fields.b]),
    say('Контексты одинаковые, поэтому одного плана A достаточно. Preview: добавим оба поля на S.')]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, false);
  assert.equal(result.verdict.checks.per_field_planning, false);
  assert.equal(result.verdict.checks.no_grouped_call, false);
  assert.ok(result.verdict.failures.includes('per_field_planning'));
  assert.ok(result.verdict.failures.includes('no_grouped_call'));
  assert.equal(result.trace.at(-1).result.fields[1].results[0].status, 'WOULD_ADD', 'Mock must accept unsafe batch; grader rejects behavior');
  assert.equal(result.requests, 3);
});
