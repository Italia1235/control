import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const call = (name, args) => ({ role: 'assistant', content: null, tool_calls: [{ id: `call-${name}`,
  type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const read = (extra = {}) => call('get_project_workflows', { project: 'ABC', ...extra });
const reply = text => ({ role: 'assistant', content: text });
const previews = {
  assign: call('assign_workflow', { dryRun: true, project: 'ABC', workflow: 'Target', allIssueTypes: true }),
  clone: call('clone_project_workflows', { dryRun: true, project: 'ABC', scope: 'ALL_WORKFLOWS' }),
};
const shown = 'Preview: схема ABC изменится, Bug мигрирует 76204 задачи, Task готов. Применить?';

// Real CLI dialogue loop and grader; transport only serves scripted assistant messages on loopback.
// No OMP subprocess, model, Jira backend, synthetic-user model or judge is invoked.
async function runStub(variant, messages) {
  let requests = 0;
  const transportErrors = [];
  const instance = http.createServer(async (request, response) => {
    try {
      let body = '';
      for await (const chunk of request) body += chunk;
      const payload = JSON.parse(body);
      assert.equal(payload.model, 'offline-assistant');
      assert.ok(Array.isArray(payload.tools), 'Only assistant requests are allowed');
      const message = messages[requests++];
      assert.ok(message, 'Unexpected extra assistant request');
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ choices: [{ message }], usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } }));
    } catch (error) {
      transportErrors.push(error.message);
      response.writeHead(400).end(JSON.stringify({ error: 'Offline stub rejected request' }));
    }
  });
  await new Promise(resolve => instance.listen(0, '127.0.0.1', resolve));
  let directory;
  try {
    let stdout, code = 0;
    const env = { ...process.env, EVAL_API_KEY: 'offline-test-key', EVAL_ASSISTANT_MODEL: 'offline-assistant',
      EVAL_USER_MODEL: 'forbidden-user-model', EVAL_JUDGE_MODEL: 'forbidden-judge-model',
      EVAL_BASE_URL: `http://127.0.0.1:${instance.address().port}`, EVAL_TOOL_BACKEND: 'simulated',
      EVAL_TOKEN_LIMIT_MODE: 'hard' };
    delete env.EVAL_MAX_TOKENS_PER_RUN;
    try {
      ({ stdout } = await exec(process.execPath, ['evals/offline/run.mjs', `workflow_read_no_counts_${variant}`],
        { cwd: ROOT, env, timeout: 15_000, maxBuffer: 2 * 1024 * 1024 }));
    } catch (error) { stdout = error.stdout ?? ''; code = error.code; }
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, `Missing artifacts: ${stdout}`);
    directory = path.resolve(ROOT, artifact[1].trim());
    const verdict = JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8'));
    const trace = JSON.parse(await readFile(path.join(directory, 'tool-trace.json'), 'utf8'));
    assert.deepEqual(transportErrors, []);
    return { verdict, trace, code, requests };
  } finally {
    await new Promise(resolve => instance.close(resolve));
    if (directory) await rm(directory, { recursive: true, force: true });
  }
}

for (const variant of ['assign', 'clone']) {
  test(`${variant}: configuration read without counts, then preview`, async () => {
    const result = await runStub(variant, [read(), previews[variant], reply(shown)]);
    assert.equal(result.code, 0, JSON.stringify(result.verdict));
    assert.equal(result.verdict.passed, true, JSON.stringify(result.verdict));
    const configuration = result.trace.find(entry => entry.tool === 'get_project_workflows');
    assert.equal(configuration.result.issueCountsStatus, 'NOT_REQUESTED');
    assert.equal(configuration.result.issueTypes[0].issues, null);
    assert.equal(result.requests, 3, 'No synthetic-user or judge request');
  });

  test(`${variant}: unrequested full issue count fails`, async () => {
    const result = await runStub(variant, [read({ includeIssueCounts: true }), previews[variant], reply(shown)]);
    assert.equal(result.code, 1);
    assert.equal(result.verdict.checks.no_issue_counts, false, JSON.stringify(result.verdict));
    assert.deepEqual(result.verdict.failures, ['no_issue_counts']);
  });

  test(`${variant}: preview without reading configuration fails`, async () => {
    const result = await runStub(variant, [previews[variant], reply(shown)]);
    assert.equal(result.code, 1);
    assert.deepEqual(result.verdict.failures, ['configuration_read_before_preview']);
  });
}
