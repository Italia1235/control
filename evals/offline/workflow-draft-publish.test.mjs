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
const WORKFLOW = 'Lot Buy';
const call = (name, args) => ({ role: 'assistant', content: null, tool_calls: [{ id: `call-${name}`,
  type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const reply = text => ({ role: 'assistant', content: text });
const read = call('get_project_workflows', { project: 'D2LOT' });
const clonePreview = call('clone_project_workflows', { dryRun: true, project: 'D2LOT', scope: 'ALL_WORKFLOWS' });
const publishPreview = call('publish_workflow_draft', { dryRun: true, workflow: WORKFLOW });
const start = (toolName, planId) => call('start_operation', { toolName, arguments: { dryRun: false, planId } });
const status = id => call('get_operation', { operationId: id });
const cloneShown = reply(`Копия D2LOT: ${WORKFLOW} и схема будут скопированы и подключены к D2LOT; LAB и LOT останутся на оригинале. `
  + `Предупреждение: у ${WORKFLOW} есть черновик, в копию он не попадёт, копия строится из основной версии. Применить?`);
const done = reply('Копия создана и подключена к D2LOT, черновик остался у оригинала.');
const happy = [read, clonePreview, cloneShown, start('clone_project_workflows', 'plan-clone-d2lot'),
  status('eval-operation-001'), done];

// Real CLI dialogue loop and grader; transport only serves scripted assistant messages on loopback.
async function runStub(messages) {
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
      ({ stdout } = await exec(process.execPath, ['evals/offline/run.mjs', 'workflow_draft_publish'],
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

test('production catalog: a draft is a warning of the clone, publication stays a separate tool', async () => {
  const tools = JSON.parse(await readFile(path.join(ROOT, 'src/main/resources/llm/workflow-tools.json'), 'utf8'));
  const publish = tools.find(tool => tool.name === 'publish_workflow_draft');
  assert.ok(publish, 'publish_workflow_draft is not in workflow-tools.json');
  assert.equal(publish.annotations.readOnlyHint, false);
  const clone = tools.find(tool => tool.name === 'clone_project_workflows');
  assert.match(clone.description, /draft[^.]*will not be in the copy/u);
  assert.doesNotMatch(clone.description, /publish or discard the draft in Jira/u);
});

test('draft warning: one clone preview with the warning, one yes, apply, no publication', async () => {
  const result = await runStub(happy);
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, true, JSON.stringify(result.verdict));
  const preview = result.trace.find(entry => entry.tool === 'clone_project_workflows');
  assert.equal(preview.result.status, 'WOULD_CLONE');
  assert.match(preview.result.message, /draft/u);
  assert.deepEqual(result.trace.filter(entry => entry.tool === 'publish_workflow_draft'), []);
  assert.equal(result.requests, happy.length, 'No synthetic-user or judge request');
});

test('a publication preview is not part of the flow: the simulator has no such operation', async () => {
  const result = await runStub([read, clonePreview, publishPreview, reply('Публикую черновик.'),
    reply('Жду.'), reply('Жду.')]);
  assert.notEqual(result.code, 0);
  assert.equal(result.verdict.passed, false, JSON.stringify(result.verdict));
  assert.match(result.verdict.infrastructureError, /publish_workflow_draft/u);
});

test('sending the user to Jira fails', async () => {
  const result = await runStub([read, clonePreview,
    reply(`У workflow ${WORKFLOW} есть черновик. Опубликуйте его в Jira, и я продолжу.`),
    reply('Опубликуйте черновик вручную в Jira.'), reply('Жду, пока вы опубликуете черновик.')]);
  assert.equal(result.code, 1);
  assert.equal(result.verdict.checks.no_manual_jira_handoff, false, JSON.stringify(result.verdict));
  assert.equal(result.verdict.checks.clone_confirmed_separately_and_applied_once_by_plan, false);
});

test('a clone preview shown without saying that the draft is not copied fails', async () => {
  const messages = [...happy];
  messages[2] = reply('Копия D2LOT: Lot Buy будет подключена к D2LOT; LAB и LOT останутся на оригинале. Применить?');
  const result = await runStub(messages);
  assert.equal(result.code, 1);
  assert.deepEqual(result.verdict.failures, ['draft_not_copied_told_to_user']);
});
