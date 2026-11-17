import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { copyFile, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const PRELOAD = new URL('./fixtures/live-runner-preload.mjs', import.meta.url).href;
const SCENARIO = 'runner-offline-boundary';
const PORTABLE_CONFIG = 'modelRoles:\n  default: offline/default\nshellPath: offline-shell\n';

async function fixture(scenarioName = SCENARIO) {
  const directory = await mkdtemp(path.join(tmpdir(), 'control-runner-offline-'));
  const live = path.join(directory, 'evals/live');
  const scenario = path.join(live, 'scenarios', scenarioName);
  const source = path.join(directory, 'source-agent');
  await Promise.all([mkdir(scenario, { recursive: true }), mkdir(source)]);
  for (const file of ['run.mjs', 'checks.mjs', 'issue-type.mjs', 'check-results.mjs', 'setup-confirmation.mjs']) {
    try { await copyFile(path.join(ROOT, 'evals/live', file), path.join(live, file)); }
    catch (error) { if (error.code !== 'ENOENT' || file !== 'check-results.mjs') throw error; }
  }
  // checks.mjs discovers the actual plugin catalog; keep its path intact in the isolated snapshot.
  const catalog = path.join(directory, 'src/main/resources/llm');
  await mkdir(catalog, { recursive: true });
  for (const file of await readdir(path.join(ROOT, 'src/main/resources/llm'))) {
    if (file.endsWith('-tools.json')) await copyFile(path.join(ROOT, 'src/main/resources/llm', file), path.join(catalog, file));
  }
  await writeFile(path.join(scenario, 'scenario.md'), '# Offline boundary fixture\n\n## Первая реплика\n\nНачни проверку.\n\n## Что ты знаешь\n\nSCENARIO_VISIBLE_FACT\n');
  await writeFile(path.join(scenario, 'setup.mjs'), 'export default async () => ({ known: { fact: "KNOWN_VISIBLE_FACT" }, private: "PRIVATE_SETUP_FACT" });\n');
  await writeFile(path.join(source, 'config.yml'), PORTABLE_CONFIG);
  await writeFile(path.join(source, 'mcp.json'), JSON.stringify({ mcpServers: { forbidden: { type: 'http', url: 'http://127.0.0.1:1/forbidden' } } }));
  await writeFile(path.join(source, 'rules.md'), 'PRIVATE_SOURCE_RULES');
  const requests = [];
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const request = JSON.parse(Buffer.concat(chunks));
    requests.push(request);
    const result = request.method === 'initialize'
      ? { instructions: 'PRIVATE_MCP_SERVER_INSTRUCTIONS', serverInfo: { version: 'offline' } }
      : request.method === 'tools/call'
        ? { content: [{ type: 'text', text: JSON.stringify(request.params.name === 'configure_workflow'
          ? { dryRun: true, status: 'WOULD_CREATE', planId: 'setup-plan', workflow: 'L013-offline-Workflow', warnings: ['Global effect'], objects: [{ kind: 'WORKFLOW', name: 'L013-offline-Workflow' }] }
          : { operationId: 'setup-operation', status: 'COMPLETED', result: { status: 'CREATED', id: '123' } }) }] }
        : { tools: [{ name: 'PRIVATE_TOOL_CATALOG', description: 'PRIVATE_TOOL_DESCRIPTION' }] };
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ jsonrpc: '2.0', id: request.id, result }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const log = path.join(directory, 'boundary.jsonl');
  return { directory, live, source, scenario, scenarioName, log, requests,
    url: `http://127.0.0.1:${server.address().port}/jira`,
    async close() { await new Promise(resolve => server.close(resolve)); await rm(directory, { recursive: true, force: true }); } };
}

async function invoke(f, flags = [], overrides = {}, input = null) {
  const env = { ...process.env, JIRA_BASE_URL: f.url, JIRA_TOKEN: 'offline-fixture-token',
    PI_CODING_AGENT_DIR: f.source, OMP_EXECUTABLE: 'offline-only-omp', RUNNER_BOUNDARY_LOG: f.log,
    NODE_OPTIONS: `--import=${PRELOAD}`, DIALOG_ASSISTANT_MODEL: '', DIALOG_USER_MODEL: '', DIALOG_THINKING: '',
    LOCAL005_EVIDENCE_FILE: '', ...overrides };
  const child = spawn(process.execPath, [path.join(f.live, 'run.mjs'), ...(flags[0] === '--regrade' ? flags : [f.scenarioName, ...flags])],
    { env, cwd: f.directory, stdio: ['pipe', 'pipe', 'pipe'] });
  let stdout = '', stderr = '';
  child.stdout.on('data', chunk => { stdout += chunk; });
  child.stderr.on('data', chunk => { stderr += chunk; });
  // Send human input only once readline has an active question (not before its listener exists).
  if (input !== null) child.stdout.on('data', chunk => { if (String(chunk).includes('Пользователь (/done')) child.stdin.write(input); });
  const timer = setTimeout(() => child.kill(), 30_000);
  const code = await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', resolve); });
  clearTimeout(timer);
  let calls = [];
  try { calls = (await readFile(f.log, 'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const runs = await readdir(path.join(f.scenario, 'runs')).catch(() => []);
  const run = runs.length ? path.join(f.scenario, 'runs', runs[0]) : null;
  return { code, stdout, stderr, calls, run,
    markdown: run ? await readFile(path.join(run, 'conversation.md'), 'utf8') : null };
}

const model = call => call.args.find(arg => arg.startsWith('--model='))?.slice('--model='.length) ?? null;

test('CLI model flags override each corresponding environment independently at the omp boundary', async () => {
  const f = await fixture();
  try {
    const result = await invoke(f, ['--assistant-model', 'anthropic/claude-sonnet-5-5', '--user-model', 'anthropic/claude-haiku-5-5', '--max-turns', '1'],
      { DIALOG_ASSISTANT_MODEL: 'env/assistant', DIALOG_USER_MODEL: 'env/user' });
    assert.equal(result.code, 0, result.stderr);
    assert.deepEqual(result.calls.map(call => [call.role, model(call)]), [
      ['assistant', 'anthropic/claude-sonnet-5-5'], ['user', 'anthropic/claude-haiku-5-5'],
    ]);
  } finally { await f.close(); }
});

for (const role of ['assistant', 'user']) test(`CLI ${role} selection leaves the other role on its environment fallback`, async () => {
  const f = await fixture();
  try {
    const result = await invoke(f, [`--${role}-model`, `cli/${role}`, '--max-turns', '1'],
      { DIALOG_ASSISTANT_MODEL: 'env/assistant', DIALOG_USER_MODEL: 'env/user' });
    assert.equal(result.code, 0, result.stderr);
    for (const call of result.calls) assert.equal(model(call), `${call.role === role ? 'cli' : 'env'}/${call.role}`);
  } finally { await f.close(); }
});

test('environment fallback, user isolation and actual max-turns loop survive the real subprocess boundary', async () => {
  const f = await fixture();
  try {
    const result = await invoke(f, ['--max-turns', '2'], { DIALOG_ASSISTANT_MODEL: 'env/assistant', DIALOG_USER_MODEL: 'env/user' });
    assert.equal(result.code, 0, result.stderr);
    assert.deepEqual(result.calls.map(call => [call.role, model(call)]), [
      ['assistant', 'env/assistant'], ['user', 'env/user'], ['assistant', 'env/assistant'], ['user', 'env/user'],
    ]);
    assert.match(result.markdown, /Завершение: лимит 2 ходов/u);
    assert.equal((result.markdown.match(/^## Ассистент$/gmu) ?? []).length, 2);
    assert.deepEqual(f.requests.map(request => request.method), ['initialize', 'tools/list']);
    for (const call of result.calls.filter(call => call.role === 'user')) {
      assert.ok(call.args.includes('--no-tools'));
      assert.ok(call.args.includes('--no-rules'));
      assert.ok(call.args.includes('--no-session'));
      assert.equal(call.cwd, call.directory);
      assert.equal(call.mcp, null);
      assert.equal(call.config, PORTABLE_CONFIG);
      assert.equal(call.files.includes('rules.md'), false);
      assert.deepEqual(Object.keys(call.request), ['messages']);
      const request = JSON.stringify(call.request);
      for (const allowed of ['SCENARIO_VISIBLE_FACT', 'KNOWN_VISIBLE_FACT', 'Видимый ответ ассистента.']) assert.ok(request.includes(allowed));
      for (const forbidden of ['PRIVATE_MCP_SERVER_INSTRUCTIONS', 'PRIVATE_TOOL_CATALOG', 'PRIVATE_TOOL_DESCRIPTION', 'PRIVATE_SETUP_FACT', 'PRIVATE_SOURCE_RULES', 'mcpServers', 'tools']) assert.ok(!request.includes(forbidden), forbidden);
    }
    for (const call of result.calls.filter(call => call.role === 'assistant')) {
      assert.equal(call.mcp.mcpServers['jira-control'].url, `${f.url}/rest/control/1.0/mcp`);
      assert.equal(call.mcp.mcpServers.forbidden, undefined);
    }
  } finally { await f.close(); }
});

test('omitted models retain omp defaults and omitted max-turns actually stops after 15 assistant turns', async () => {
  const f = await fixture();
  try {
    const result = await invoke(f);
    assert.equal(result.code, 0, result.stderr);
    assert.ok(result.calls.every(call => model(call) === null));
    assert.equal(result.calls.filter(call => call.role === 'assistant').length, 15);
    assert.equal((result.markdown.match(/^## Ассистент$/gmu) ?? []).length, 15);
    assert.match(result.markdown, /Завершение: лимит 15 ходов/u);
  } finally { await f.close(); }
});


for (const [name, checks, exitCode, observationCount] of [
  ['advisory-only', [{ name: 'Формулировка', class: 'advisory', ok: false, detail: 'Нет таблицы последствий' }], 0, 1],
  ['contract', [{ name: 'Чтение после apply', class: 'contract', ok: false, detail: 'Чтение отсутствует' }], 3, 0],
  ['mixed', [{ name: 'Формулировка', class: 'advisory', ok: false, detail: 'Нет таблицы последствий' },
    { name: 'Чтение после apply', class: 'contract', ok: false, detail: 'Чтение отсутствует' }], 3, 1],
  ['legacy', [{ name: 'Прежняя проверка', ok: false, detail: 'Обязательный сбой' }], 3, 0],
  ['unknown-class', [{ name: 'Неизвестный класс', class: 'typo', ok: false, detail: 'Не может пройти' }], 3, 0],
]) test(`runner reporting ${name} preserves classification, observations and exit code`, async () => {
  const f = await fixture();
  try {
    await writeFile(path.join(f.scenario, 'verify.mjs'), `export default async () => (${JSON.stringify(checks)});\n`);
    const result = await invoke(f, ['--max-turns', '1']);
    assert.equal(result.code, exitCode, result.stderr);
    const verdict = JSON.parse(await readFile(path.join(result.run, 'verdict.json'), 'utf8'));
    assert.equal(verdict.passed, exitCode === 0);
    assert.equal(verdict.observations.length, observationCount);
    for (const check of checks.filter(check => check.class === 'advisory')) {
      const observation = verdict.observations.find(item => item.name === check.name);
      assert.equal(observation.class, 'advisory');
      assert.equal(observation.detail, check.detail);
      assert.ok(result.stdout.includes(check.detail));
    }
    if (observationCount) assert.match(result.stdout, /OBSERVATION/u);
    assert.ok(result.stdout.includes(`ИТОГ: ${exitCode === 0 ? 'PASS' : 'FAIL'}`));
  } finally { await f.close(); }
});

test('a thrown verifier remains a blocking contract failure through the runner', async () => {
  const f = await fixture();
  try {
    await writeFile(path.join(f.scenario, 'verify.mjs'), 'export default async () => { throw new Error(\"offline verifier failure\"); };\n');
    const result = await invoke(f, ['--max-turns', '1']);
    assert.equal(result.code, 3, result.stderr);
    const verdict = JSON.parse(await readFile(path.join(result.run, 'verdict.json'), 'utf8'));
    assert.equal(verdict.passed, false);
    assert.ok(verdict.checks.some(item => item.class === 'contract' && item.ok === false && item.detail === 'offline verifier failure'));
  } finally { await f.close(); }
});

for (const scenarioName of [SCENARIO, 'workflow-configure-create', 'workflow-configure-existing', 'workflow-configure-stale', 'workflow-configure-new-statuses']) {
  test(`runner opts into advisory trace presentation only for configure scenarios: ${scenarioName}`, async () => {
    const f = await fixture(scenarioName);
    try {
      const classified = scenarioName !== SCENARIO;
      const result = await invoke(f, ['--max-turns', '1'], { RUNNER_BOUNDARY_EXPOSE_PLAN: '1' });
      assert.equal(result.code, classified ? 0 : 3, result.stderr);
      const verdict = JSON.parse(await readFile(path.join(result.run, 'verdict.json'), 'utf8'));
      const leak = verdict.checks.find(item => item.name === 'planId не показан пользователю');
      assert.equal(leak.ok, false);
      assert.equal(leak.class, classified ? 'advisory' : 'contract');
      assert.equal(verdict.passed, classified);
      assert.equal(verdict.observations.some(item => item.name === leak.name), classified);
    } finally { await f.close(); }
  });
}

for (const flag of ['--check', '--setup-only']) test(`${flag} accepts model flags without launching either model`, async () => {
  const f = await fixture();
  try {
    const result = await invoke(f, [flag, '--assistant-model', 'cli/assistant', '--user-model', 'cli/user']);
    assert.equal(result.code, 0, result.stderr);
    assert.deepEqual(result.calls, []);
    assert.deepEqual(f.requests.map(request => request.method), ['initialize', 'tools/list']);
  } finally { await f.close(); }
});

test('human-user bypasses the user model even when CLI and environment name one', async () => {
  const f = await fixture();
  try {
    const result = await invoke(f, ['--human-user', '--assistant-model', 'cli/assistant', '--user-model', 'cli/never-user', '--max-turns', '1'],
      { DIALOG_USER_MODEL: 'env/never-user' }, '/done\n');
    assert.equal(result.code, 0, result.stderr);
    assert.deepEqual(result.calls.map(call => [call.role, model(call)]), [['assistant', 'cli/assistant']]);
    assert.match(result.markdown, /- Пользователь: human/u);
    assert.match(result.markdown, /пользователь завершил \(DONE\)/u);
  } finally { await f.close(); }
});

for (const args of [
  ['--assistant-model'], ['--user-model'], ['--assistant-model', ''], ['--user-model', ''],
  ['--assistant-model', '   '], ['--user-model', '   '], ['--assistant-model', '--check'], ['--user-model', '--check'],
  ['--max-turns', '0'], ['--max-turns', '-1'], ['--max-turns', '1.5'], ['--max-turns'], ['--max-turns', '9007199254740992'],
]) test(`invalid arguments fail before Jira and omp: ${JSON.stringify(args)}`, async () => {
  const f = await fixture();
  try {
    const result = await invoke(f, args);
    assert.equal(result.code, 2);
    assert.match(result.stderr, /Usage:/u);
    assert.deepEqual(f.requests, []);
    assert.deepEqual(result.calls, []);
    assert.equal(result.run, null);
  } finally { await f.close(); }
});

test('confirmed setup rejection preserves evidence and never starts the main dialogue', async () => {
  const f = await fixture();
  try {
    await writeFile(path.join(f.scenario, 'setup.mjs'), 'export const confirmation = \"simulated-user\"; export default async ({apply}) => apply(\"configure_workflow\",{create:{name:\"L013-offline-Workflow\"}});');
    const result = await invoke(f, ['--user-model', 'offline/fixture'], { RUNNER_SETUP_DECISION: 'REJECT' });
    assert.equal(result.code, 2, result.stderr);
    assert.deepEqual(result.calls.map(call => call.role), ['user']);
    assert.equal(f.requests.filter(x => x.params?.name === 'start_operation').length, 0);
    const trace = JSON.parse(await readFile(path.join(result.run, 'setup-trace.json'), 'utf8'));
    assert.equal(trace.at(-1).event, 'setup_failed');
    assert.ok(trace.some(x => x.event === 'approval' && x.decision === 'REJECT'));
    assert.ok((await readFile(path.join(result.run, 'setup-conversation.md'), 'utf8')).includes('Global effect'));
    assert.equal(JSON.parse(await readFile(path.join(result.run, 'verdict.json'), 'utf8')).passed, false);
  } finally { await f.close(); }
});

test('setup-only uses the same real bridge reply and durable approval before one start', async () => {
  const f = await fixture();
  try {
    await writeFile(path.join(f.scenario, 'setup.mjs'), 'export const confirmation = \"simulated-user\"; export default async ({apply}) => apply(\"configure_workflow\",{create:{name:\"L013-offline-Workflow\"}});');
    const result = await invoke(f, ['--setup-only', '--user-model', 'offline/fixture'], { RUNNER_SETUP_DECISION: 'APPROVE' });
    assert.equal(result.code, 0, result.stderr);
    assert.deepEqual(result.calls.map(call => call.role), ['user']);
    assert.equal(f.requests.filter(x => x.params?.name === 'start_operation').length, 1);
    const trace = JSON.parse(await readFile(path.join(result.run, 'setup-trace.json'), 'utf8'));
    assert.equal(trace.at(-1).event, 'setup_completed');
    assert.ok(trace.findIndex(x => x.event === 'approval') < trace.findIndex(x => x.event === 'start_operation'));
    assert.deepEqual(JSON.parse(await readFile(path.join(result.run, 'setup.json'), 'utf8')), { status: 'CREATED', id: '123' });
    assert.equal(JSON.parse(await readFile(path.join(result.run, 'verdict.json'), 'utf8')).passed, true);
    await writeFile(path.join(f.scenario, 'verify.mjs'), 'export default async ({setupTrace,setupConversation,tool}) => [{name:\"saved setup evidence reaches verify\",class:\"contract\",ok:setupTrace.at(-1).event===\"setup_completed\" && setupConversation.includes(\"Own simulated-user reply\")}];');
    const regraded = await invoke(f, ['--regrade', result.run]);
    assert.equal(regraded.code, 0, regraded.stderr);
    assert.equal(regraded.calls.length, 1, 'regrade must not call models');
    assert.equal(f.requests.filter(x => x.params?.name === 'start_operation').length, 1, 'regrade must not run setup or apply');
    await rm(path.join(result.run, 'setup-trace.json'));
    await writeFile(path.join(f.scenario, 'verify.mjs'), 'export default async ({tool}) => { await tool(\"configure_workflow\", {dryRun:true,create:{name:\"forbidden\"}}); return []; };');
    const rejected = await invoke(f, ['--regrade', result.run]);
    assert.equal(rejected.code, 3, rejected.stderr);
    assert.equal(f.requests.filter(x => x.params?.name === 'configure_workflow').length, 1, 'missing evidence must not expose a mutable verifier surface');
  } finally { await f.close(); }
});

test('check validates opt-in metadata without setup, model calls or mutating tools', async () => {
  const f = await fixture('workflow-configure-existing-new-status-inactive');
  try {
    let result = await invoke(f, ['--check']);
    assert.equal(result.code, 2);assert.equal(result.calls.length, 0);
    assert.equal(f.requests.filter(x => x.method === 'tools/call').length, 0);
    await writeFile(path.join(f.scenario, 'setup.mjs'), 'export const confirmation = \"simulated-user\"; export default async () => { throw new Error(\"must not execute\"); };');
    result = await invoke(f, ['--check']);
    assert.equal(result.code, 0, result.stderr);assert.equal(result.calls.length, 0);assert.equal(result.run, null);
    assert.equal(f.requests.filter(x => x.method === 'tools/call').length, 0);
  } finally { await f.close(); }
});
