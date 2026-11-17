import test from 'node:test';
import assert from 'node:assert/strict';
import { complete } from './run.mjs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { tmpdir } from 'node:os';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const allowed = { paidRun: true, provider: 'omp', ompModel: 'openai-codex/gpt-6.1-sol',
  tokenLimit: { mode: 'soft', limit: 300000 } };
const SCREEN_NAMES = 'customfields_create_screen_names';
const PARTIAL_RESULT = 'issuetypes_partial_result';


for (const [name, overrides] of [
  ['model alias', { ompModel: 'slow' }],
  ['other model', { ompModel: 'openai-codex/gpt-6-luna' }],
  ['missing ceiling', { tokenLimit: { mode: 'hard', limit: null } }],
  ['higher ceiling', { tokenLimit: { mode: 'soft', limit: 300001 } }],
  ['wrong budget mode', { tokenLimit: { mode: 'hard', limit: 300000 } }],
  ['API transport override', { provider: 'api', assistantModel: 'openai-codex/gpt-6.1-sol' }],
]) test(`screen-names paid preflight refuses ${name} before request accounting or transport`, async () => {
  const context = new Proxy({}, { get() { assert.fail('No request state may be accessed before paid configuration is validated'); } });
  await assert.rejects(complete({ ...allowed, paidScenario: SCREEN_NAMES, ...overrides },
    { messages: [] }, 'assistant', context), /PAID_RUN_CONFIG/);
});

test('paid preflight refuses an unknown scenario before request accounting or transport', async () => {
  const context = new Proxy({}, { get() { assert.fail('Unknown scenarios must not access request state'); } });
  await assert.rejects(complete({ ...allowed, paidScenario: 'unknown' },
    { messages: [] }, 'assistant', context), /PAID_RUN_CONFIG/);
});
for (const [name, overrides] of [
  ['model alias', { ompModel: 'slow' }],
  ['other model', { ompModel: 'openai-codex/gpt-6-luna' }],
  ['missing ceiling', { tokenLimit: { mode: 'hard', limit: null } }],
  ['higher ceiling', { tokenLimit: { mode: 'soft', limit: 300001 } }],
  ['wrong budget mode', { tokenLimit: { mode: 'hard', limit: 300000 } }],
  ['API transport override', { provider: 'api', assistantModel: 'openai-codex/gpt-6.1-sol' }],
]) test(`paid preflight refuses ${name} before request accounting or transport`, async () => {
  const context = new Proxy({}, { get() { assert.fail('No request state may be accessed before paid configuration is validated'); } });
  await assert.rejects(complete({ ...allowed, ...overrides }, { messages: [] }, 'assistant', context), /PAID_RUN_CONFIG/);
});

for (const scenario of ['customfields_lab_two_screens', SCREEN_NAMES, PARTIAL_RESULT])
for (const [name, overrides, flags] of [
  ['lost environment', {}, []],
  ['other model', { EVAL_OMP_MODEL: 'openai-codex/gpt-6-luna',
    EVAL_MAX_TOKENS_PER_RUN: '300000', EVAL_TOKEN_LIMIT_MODE: 'soft' }, []],
  ['missing ceiling', { EVAL_OMP_MODEL: 'openai-codex/gpt-6.1-sol' }, []],
  ['higher ceiling', { EVAL_OMP_MODEL: 'openai-codex/gpt-6.1-sol',
    EVAL_MAX_TOKENS_PER_RUN: '300001', EVAL_TOKEN_LIMIT_MODE: 'soft' }, []],
  ['paid API override', { EVAL_API_KEY: 'offline-test-key', EVAL_ASSISTANT_MODEL: 'offline-forbidden',
    EVAL_BASE_URL: 'http://127.0.0.1:1', EVAL_MAX_TOKENS_PER_RUN: '300000',
    EVAL_TOKEN_LIMIT_MODE: 'soft' }, ['--paid']],
]) test(`${scenario} runner rejects ${name} before its first request`, async () => {
  const env = { ...process.env, EVAL_OMP_MODEL: '', EVAL_ASSISTANT_MODEL: '',
    EVAL_MAX_TOKENS_PER_RUN: '', EVAL_TOKEN_LIMIT_MODE: '', EVAL_BACKEND: 'simulated',
    OMP_EXECUTABLE: path.join(ROOT, 'offline-forbidden-omp-executable'),
    EVAL_SCENARIO_TIMEOUT_MS: '1000', EVAL_REQUEST_TIMEOUT_MS: '500', ...overrides };
  let stdout = '', stderr = '', code = 0, directory;
  try {
    try {
      ({ stdout } = await exec(process.execPath,
        ['evals/offline/run.mjs', scenario, ...flags],
        { cwd: ROOT, env, timeout: 10000 }));
    } catch (error) { stdout = error.stdout ?? ''; stderr = error.stderr ?? ''; code = error.code; }
    assert.notEqual(code, 0);
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, stdout + stderr);
    directory = path.resolve(ROOT, artifact[1].trim());
    const verdict = JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8'));
    assert.match(verdict.diagnostics.terminationReason, /PAID_RUN_CONFIG/);
    assert.equal(verdict.diagnostics.assistantCalls, 0);
    assert.equal(verdict.diagnostics.syntheticUserCalls, 0);
    assert.equal(verdict.diagnostics.judgeCalls, 0);
    assert.deepEqual(verdict.diagnostics.requestEvents, []);
  } finally {
    if (directory) await rm(directory, { recursive: true, force: true });
  }
});

test('paid launcher check overrides inherited transport/model/budget without starting OMP', async () => {
  const { stdout } = await exec(process.execPath, ['evals/offline/run-paid.mjs', '--check'], {
    cwd: ROOT, timeout: 10000,
    env: { ...process.env, EVAL_API_KEY: 'offline-test-key', EVAL_ASSISTANT_MODEL: 'wrong-api-model',
      EVAL_BACKEND: 'wrong-backend', EVAL_OMP_MODEL: 'slow', EVAL_TOKEN_LIMIT_MODE: 'hard',
      EVAL_MAX_TOKENS_PER_RUN: '999999', OMP_EXECUTABLE: path.join(ROOT, 'offline-forbidden-omp-executable') },
  });
  assert.deepEqual(JSON.parse(stdout), { scenario: 'customfields_lab_two_screens', backend: 'simulated',
    provider: 'omp', model: 'openai-codex/gpt-6.1-sol', tokenLimitMode: 'soft',
    maxTokensPerRun: 300000, checkOnly: true });
});

test('paid launcher completes the real runner with an offline OMP boundary and inherited bad config', async () => {
  const temporary = await mkdtemp(path.join(tmpdir(), 'lab-paid-offline-'));
  const preload = path.join(temporary, 'offline-omp.mjs');
  let directory;
  try {
    await writeFile(preload, `
      import childProcess from 'node:child_process';
      import { EventEmitter } from 'node:events';
      import { syncBuiltinESMExports } from 'node:module';
      import { readFileSync } from 'node:fs';
      const saved = JSON.parse(readFileSync(process.env.OFFLINE_LAB_FIXTURE, 'utf8'));
      const messages = saved.modelMessages.filter(m => m.role === 'assistant');
      let index = 0;
      childProcess.spawn = () => {
        const child = new EventEmitter();
        child.stdout = new EventEmitter(); child.stderr = new EventEmitter(); child.kill = () => {};
        process.nextTick(() => {
          if (index >= messages.length) {
            child.emit('error', new Error('Unexpected offline OMP request'));
            return;
          }
          child.stdout.emit('data', JSON.stringify({ type: 'message_end', message: {
            role: 'assistant', content: [{ type: 'text', text: JSON.stringify(messages[index++]) }] } }) + '\\n');
          child.emit('close', 0);
        });
        return child;
      };
      syncBuiltinESMExports();
    `);
    const { stdout } = await exec(process.execPath, ['evals/offline/run-paid.mjs'], {
      cwd: ROOT, timeout: 15000, maxBuffer: 2 * 1024 * 1024,
      env: { ...process.env, EVAL_API_KEY: 'offline-test-key', EVAL_ASSISTANT_MODEL: 'wrong-api-model',
        EVAL_BACKEND: 'wrong-backend', EVAL_OMP_MODEL: 'slow', EVAL_TOKEN_LIMIT_MODE: 'hard',
        EVAL_MAX_TOKENS_PER_RUN: '999999', PI_CODING_AGENT_DIR: temporary,
        OMP_EXECUTABLE: path.join(temporary, 'forbidden-real-omp'),
        OFFLINE_LAB_FIXTURE: path.join(ROOT, 'evals/offline/fixtures/lab-two-screens-saved.json'),
        NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --import=${pathToFileURL(preload).href}` },
    });
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, stdout);
    directory = path.resolve(ROOT, artifact[1].trim());
    const verdict = JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8'));
    assert.equal(verdict.passed, true, JSON.stringify(verdict));
    assert.deepEqual(verdict.diagnostics.paidConfiguration, { provider: 'omp',
      model: 'openai-codex/gpt-6.1-sol', tokenLimitMode: 'soft', maxTokensPerRun: 300000 });
    assert.equal(verdict.checks.terminal_pairs, true);
    assert.equal(verdict.diagnostics.syntheticUserCalls, 0);
    assert.equal(verdict.diagnostics.judgeCalls, 0);
  } finally {
    if (directory) await rm(directory, { recursive: true, force: true });
    await rm(temporary, { recursive: true, force: true });
  }
});

test('screen-names launcher check prints the exact effective configuration without any child', async () => {
  const temporary = await mkdtemp(path.join(tmpdir(), 'screen-names-check-'));
  const preload = path.join(temporary, 'no-child.mjs');
  try {
    await writeFile(preload, `
      import childProcess from 'node:child_process';
      import { syncBuiltinESMExports } from 'node:module';
      childProcess.spawn = childProcess.spawnSync = () => { throw new Error('Forbidden child process'); };
      syncBuiltinESMExports();
    `);
    const { stdout } = await exec(process.execPath,
      ['evals/offline/run-paid.mjs', '--scenario', SCREEN_NAMES, '--check'], {
        cwd: ROOT, timeout: 10000,
        env: { ...process.env, EVAL_ASSISTANT_MODEL: 'wrong-api-model', EVAL_BACKEND: 'live',
          EVAL_OMP_MODEL: 'slow', EVAL_TOKEN_LIMIT_MODE: 'hard', EVAL_MAX_TOKENS_PER_RUN: '999999',
          NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --import=${pathToFileURL(preload).href}` },
      });
    assert.deepEqual(JSON.parse(stdout), { scenario: SCREEN_NAMES, backend: 'simulated',
      provider: 'omp', model: 'openai-codex/gpt-6.1-sol', tokenLimitMode: 'soft',
      maxTokensPerRun: 300000, checkOnly: true });
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test('partial-result launcher check selects only the approved model, budget and simulated backend', async () => {
  const { stdout } = await exec(process.execPath,
    ['evals/offline/run-paid.mjs', '--scenario', PARTIAL_RESULT, '--check'], {
      cwd: ROOT, timeout: 10000,
      env: { ...process.env, EVAL_ASSISTANT_MODEL: 'wrong-api-model', EVAL_BACKEND: 'live',
        EVAL_OMP_MODEL: 'slow', EVAL_TOKEN_LIMIT_MODE: 'hard', EVAL_MAX_TOKENS_PER_RUN: '999999',
        OMP_EXECUTABLE: path.join(ROOT, 'offline-forbidden-omp-executable') },
    });
  assert.deepEqual(JSON.parse(stdout), { scenario: PARTIAL_RESULT, backend: 'simulated',
    provider: 'omp', model: 'openai-codex/gpt-6.1-sol', tokenLimitMode: 'soft',
    maxTokensPerRun: 300000, checkOnly: true });
});

test('screen-names paid launcher completes the real runner with an offline OMP boundary', async () => {
  const temporary = await mkdtemp(path.join(tmpdir(), 'screen-names-launch-'));
  const preload = path.join(temporary, 'offline-omp.mjs');
  const fieldIds = ['summary', 'customfield_10100', 'description'];
  const name = 'LAB — Создание эксперимента 17';
  const planId = 'create-screen-names-plan';
  const call = (name, args) => ({ role: 'assistant', content: null, tool_calls: [{
    id: `call-${name}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
  const card = (data, text) => ({ role: 'assistant', content: `${text}\n\`\`\`json\n${JSON.stringify(data)}\n\`\`\`` });
  const messages = [
    call('create_screen', { dryRun: true, name, fields: fieldIds.map(name => ({ name })) }),
    card({ kind: 'APPROVE_SCREEN', planId, name, fieldIds, tab: 'Field Tab', requiredFieldsCheck: 'UNAVAILABLE' },
      'Проверить обязательные поля нечем. Создать только показанный отдельный экран?'),
    call('start_operation', { toolName: 'create_screen', arguments: { dryRun: false, planId } }),
    call('get_operation', { operationId: 'eval-operation-001' }),
    card({ kind: 'SCREEN_CREATED', screenId: 10106, name, fieldIds, tab: 'Field Tab' },
      'Экран создан, проекту не назначен. Обязательные поля не проверены.'),
  ];
  let directory;
  try {
    await writeFile(preload, `
      import childProcess from 'node:child_process';
      import { EventEmitter } from 'node:events';
      import { syncBuiltinESMExports } from 'node:module';
      const messages = JSON.parse(process.env.OFFLINE_SCREEN_MESSAGES);
      let index = 0;
      childProcess.spawn = () => {
        const child = new EventEmitter();
        child.stdout = new EventEmitter(); child.stderr = new EventEmitter(); child.kill = () => {};
        process.nextTick(() => {
          if (index >= messages.length) return child.emit('error', new Error('Unexpected offline OMP request'));
          child.stdout.emit('data', JSON.stringify({ type: 'message_end', message: {
            role: 'assistant', content: [{ type: 'text', text: JSON.stringify(messages[index++]) }] } }) + '\\n');
          child.emit('close', 0);
        });
        return child;
      };
      syncBuiltinESMExports();
    `);
    const { stdout } = await exec(process.execPath, ['evals/offline/run-paid.mjs', '--scenario', SCREEN_NAMES], {
      cwd: ROOT, timeout: 15000, maxBuffer: 2 * 1024 * 1024,
      env: { ...process.env, EVAL_ASSISTANT_MODEL: 'wrong-api-model', EVAL_BACKEND: 'live',
        EVAL_OMP_MODEL: 'slow', EVAL_TOKEN_LIMIT_MODE: 'hard', EVAL_MAX_TOKENS_PER_RUN: '999999',
        PI_CODING_AGENT_DIR: temporary, OMP_EXECUTABLE: path.join(temporary, 'forbidden-real-omp'),
        OFFLINE_SCREEN_MESSAGES: JSON.stringify(messages),
        NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --import=${pathToFileURL(preload).href}` },
    });
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, stdout);
    directory = path.resolve(ROOT, artifact[1].trim());
    const verdict = JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8'));
    assert.equal(verdict.passed, true, JSON.stringify(verdict));
    assert.equal(verdict.checks.terminal_screen, true);
    assert.equal(verdict.checks.final_after_terminal, true);
    assert.equal(verdict.diagnostics.syntheticUserCalls, 0);
    assert.equal(verdict.diagnostics.judgeCalls, 0);
  } finally {
    if (directory) await rm(directory, { recursive: true, force: true });
    await rm(temporary, { recursive: true, force: true });
  }
});

for (const args of [
  ['--scenario', 'unknown'], ['--scenario', '__proto__'], ['--scenario', 'constructor', '--check'],
  ['--scenario'], ['--scenario', SCREEN_NAMES, '--scenario', SCREEN_NAMES],
  ['--scenario', SCREEN_NAMES, '--check', '--check'],
]) test(`paid launcher rejects ${JSON.stringify(args)} before spawning a child`, async () => {
  const temporary = await mkdtemp(path.join(tmpdir(), 'invalid-paid-launch-'));
  const preload = path.join(temporary, 'forbid-child.mjs');
  try {
    await writeFile(preload, `
      import childProcess from 'node:child_process';
      import { syncBuiltinESMExports } from 'node:module';
      childProcess.spawn = childProcess.spawnSync = () => { throw new Error('Forbidden child process'); };
      syncBuiltinESMExports();
    `);
    await assert.rejects(exec(process.execPath, ['evals/offline/run-paid.mjs', ...args], {
      cwd: ROOT, timeout: 10000,
      env: { ...process.env,
        NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --import=${pathToFileURL(preload).href}` },
    }), error => {
      assert.equal(error.code, 1);
      assert.match(error.stderr, /Usage:|PAID_RUN_CONFIG/);
      assert.doesNotMatch(error.stdout + error.stderr, /Forbidden child process|Soft ceiling/);
      return true;
    });
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
