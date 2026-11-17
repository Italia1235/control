import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateCreateScreenNamesScenario, createScreenNamesMock, gradeCreateScreenNames } from './customfields-create-screen-names.mjs';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const SCENARIO = 'customfields_create_screen_names';
const NAME = 'LAB — Создание эксперимента 17';
const FIELD = 'LAB — Код эксперимента 17';
const IDS = ['summary', 'customfield_10100', 'description'];
const PLAN = 'create-screen-names-plan';
const call = (name, args) => ({ role: 'assistant', content: null, tool_calls: [{ id: `call-${name}`,
  type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const say = content => ({ role: 'assistant', content });
const decision = (data, text) => say(`${text}\n\n\`\`\`json\n${JSON.stringify(data)}\n\`\`\``);
const preview = (fields = ['summary', FIELD, 'description'], extra = {}) => call('create_screen', {
  dryRun: true, name: NAME, fields: fields.map(name => ({ name })), ...extra });
const approval = () => decision({ kind: 'APPROVE_SCREEN', planId: PLAN, name: NAME,
  fieldIds: IDS, tab: 'Field Tab', requiredFieldsCheck: 'UNAVAILABLE' },
  `Проверить обязательные поля проекта доступными инструментами нельзя. Можно создать отдельный экран с перечисленными полями без назначения проекту.\n${NAME}: Тема, ${FIELD}, Описание. Одна вкладка Field Tab. Создать этот экран?`);
const start = () => call('start_operation', { toolName: 'create_screen', arguments: { dryRun: false, planId: PLAN } });
const terminal = () => call('get_operation', { operationId: 'eval-operation-001' });
const final = () => decision({ kind: 'SCREEN_CREATED', screenId: 10106, name: NAME, fieldIds: IDS, tab: 'Field Tab' },
  `Создан отдельный экран ${NAME}, ID 10106. Обязательные поля не проверены, проекту экран не назначен.`);

async function runStub(messages) {
  let requests = 0;
  const errors = [];
  const server = http.createServer(async (request, response) => {
    try {
      let text = '';
      for await (const chunk of request) text += chunk;
      const body = JSON.parse(text);
      assert.equal(body.model, 'offline-create-screen');
      assert.ok(Array.isArray(body.tools), 'No user model or judge may be called');
      const message = messages[requests++];
      assert.ok(message, 'Unexpected extra assistant request');
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ choices: [{ message }], usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } }));
    } catch (error) {
      errors.push(error.message);
      response.writeHead(400).end(JSON.stringify({ error: 'Offline stub rejected request' }));
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let directory;
  try {
    const env = { ...process.env, EVAL_API_KEY: 'offline-only', EVAL_ASSISTANT_MODEL: 'offline-create-screen',
      EVAL_USER_MODEL: 'forbidden-user', EVAL_JUDGE_MODEL: 'forbidden-judge',
      EVAL_BASE_URL: `http://127.0.0.1:${server.address().port}`, EVAL_BACKEND: 'simulated',
      EVAL_TOKEN_LIMIT_MODE: 'soft', EVAL_MAX_TOKENS_PER_RUN: '300000' };
    let stdout, stderr = '', code = 0;
    try {
      ({ stdout } = await exec(process.execPath, ['evals/offline/run.mjs', SCENARIO],
        { cwd: ROOT, env, timeout: 15000, maxBuffer: 2 * 1024 * 1024 }));
    } catch (error) { stdout = error.stdout ?? ''; stderr = error.stderr ?? ''; code = error.code; }
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, stdout + stderr);
    directory = path.resolve(ROOT, artifact[1].trim());
    const verdict = JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8'));
    const trace = JSON.parse(await readFile(path.join(directory, 'tool-trace.json'), 'utf8'));
    assert.deepEqual(errors, []);
    return { code, verdict, trace, requests };
  } finally {
    await new Promise(resolve => server.close(resolve));
    if (directory) await rm(directory, { recursive: true, force: true });
  }
}

const saved = JSON.parse(await readFile(new URL('./fixtures/create-screen-names-saved.json', import.meta.url), 'utf8'));
const gradeSaved = data => gradeCreateScreenNames({ scenario: generateCreateScreenNamesScenario(), ...data });

test('create screen names: saved source verdict, conversation and trace remain byte-identical', async () => {
  for (const [name, sha256] of Object.entries(saved.sourceSha256)) {
    const bytes = await readFile(path.join(ROOT, saved.sourceRun, name));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), sha256, name);
    if (name === 'verdict.json') assert.equal(JSON.parse(bytes).passed, false, 'Original FAIL is not overwritten');
    if (name === 'tool-trace.json') assert.deepEqual(JSON.parse(bytes), saved.trace);
  }
});

test('create screen names: saved trace recovers one domain error and grades the applied successful preview', () => {
  const verdict = gradeSaved(structuredClone(saved));
  assert.equal(verdict.passed, true, JSON.stringify(verdict));
  assert.equal(verdict.observations.recovered_field_error_previews, 1);
});

test('create screen names: actual offline runner accepts the exact saved assistant replies', async () => {
  const result = await runStub(saved.modelMessages.filter(message => message.role === 'assistant'));
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, true);
});

test('create screen names: two error previews cannot be excused by later success', () => {
  const data = structuredClone(saved);
  data.trace.unshift(structuredClone(data.trace[0]));
  const verdict = gradeSaved(data);
  assert.equal(verdict.passed, false);
  assert.equal(verdict.checks.one_valid_preview, false);
  assert.equal(verdict.checks.no_validation_errors, false);
});

test('create screen names: real runner rejects two domain errors before corrected success', async () => {
  const failed = preview(['Тема', FIELD, 'Описание']);
  const result = await runStub([failed, failed, preview(), approval(), start(), terminal(), final()]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.checks.one_valid_preview, false);
  assert.equal(result.verdict.checks.no_validation_errors, false);
  assert.equal(result.verdict.checks.terminal_screen, true);
});

test('create screen names: ambiguous names may recover but field provisioning errors may not', () => {
  for (const [errorCode, allowed] of [['AMBIGUOUS_FIELD', true], ['EVAL_UNREQUESTED_FIELD_CREATION', false]]) {
    const data = structuredClone(saved);
    for (const field of data.trace[0].result.fields.filter(field => field.status === 'FAILED')) field.errorCode = errorCode;
    const verdict = gradeSaved(data);
    assert.equal(verdict.passed, allowed, JSON.stringify(verdict));
    assert.equal(verdict.checks.no_validation_errors, allowed);
  }
});

test('create screen names: a domain error after a successful preview is not recovered', () => {
  const data = structuredClone(saved);
  [data.trace[0], data.trace[1]] = [data.trace[1], data.trace[0]];
  const verdict = gradeSaved(data);
  assert.equal(verdict.passed, false);
  assert.equal(verdict.checks.one_valid_preview, false);
  assert.equal(verdict.checks.no_validation_errors, false);
});

test('create screen names: apply cannot select an unsuccessful preview', () => {
  const data = structuredClone(saved);
  data.trace[0].result.planId = 'failed-preview-plan';
  data.trace.find(call => call.tool === 'start_operation').arguments.arguments.planId = 'failed-preview-plan';
  const verdict = gradeSaved(data);
  assert.equal(verdict.passed, false);
  assert.equal(verdict.checks.managed_once_by_plan, false);
  assert.equal(verdict.checks.visible_preview_data, false);
});

test('create screen names: matching applied plan selects preview data, not an unrelated successful preview', () => {
  const data = structuredClone(saved);
  const unrelated = structuredClone(data.trace[1]);
  unrelated.arguments.name = unrelated.result.name = 'Unrelated screen';
  unrelated.result.planId = 'unrelated-success-plan';
  unrelated.result.fields.reverse();
  data.trace.unshift(unrelated);
  data.modelMessages.unshift({ role: 'tool', content: JSON.stringify(unrelated.result) });
  const verdict = gradeSaved(data);
  assert.equal(verdict.passed, false, 'Extra successful previews are still forbidden');
  assert.equal(verdict.checks.one_valid_preview, false);
  assert.equal(verdict.checks.requested_fields_only, true);
  assert.equal(verdict.checks.visible_preview_data, true);
  assert.equal(verdict.checks.managed_once_by_plan, true);
});

test('create screen names: actual runner resolves system IDs and existing custom name in one preview', async t => {
  const result = await runStub([preview(), approval(), start(), terminal(), final()]);
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, true);
  assert.deepEqual(result.trace.find(call => call.tool === 'get_operation').result.result.fields.map(field => field.fieldId), IDS);
  t.diagnostic(JSON.stringify({ requests: result.requests, llmCalls: 0, jiraCalls: 0 }));
});

test('create screen names: current English display names and custom ID also resolve', async () => {
  const result = await runStub([preview(['SUMMARY', 'customfield_10100', 'Description']), approval(), start(), terminal(), final()]);
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, true);
});

test('create screen names: Russian labels reproduce FIELD_ERROR, not a silently created replacement', async () => {
  const result = await runStub([preview(['Тема', FIELD, 'Описание']), preview(), approval(), start(), terminal(), final()]);
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  const failed = result.trace.find(call => call.tool === 'create_screen').result;
  assert.equal(failed.status, 'FAILED');
  assert.equal(failed.errorCode, 'FIELD_ERROR');
  assert.deepEqual(failed.fields.map(field => field.errorCode), ['MISSING_TYPE', null, 'MISSING_TYPE']);
  assert.deepEqual(failed.fields.map(field => field.suggestions), [[], [], []]);
  assert.equal(result.verdict.checks.no_validation_errors, true);
  assert.equal(result.verdict.checks.one_valid_preview, true);
  assert.equal(result.verdict.checks.terminal_screen, true, 'Recovery still completes; first-call failure remains visible');
});

for (const args of [
  { dryRun: true, name: NAME, fields: IDS.map(name => ({ name })), project: 'LAB' },
  { projectKey: 'LAB', arguments: { dryRun: true, name: NAME, fields: IDS.map(name => ({ name })) } },
]) {
  test(`create screen names: malformed schema is not a clean path: ${JSON.stringify(Object.keys(args))}`, async () => {
    const result = await runStub([call('create_screen', args), preview(), approval(), start(), terminal(), final()]);
    assert.equal(result.code, 1, JSON.stringify(result.verdict));
    assert.equal(result.trace.find(call => call.tool === 'create_screen').result.errorCode, 'SCHEMA_VALIDATION');
    assert.equal(result.verdict.checks.no_validation_errors, false);
  });
}

test('create screen names: unrelated description reads exceed N=0 before preview', async () => {
  const reads = ['set_field_required', 'set_project_operation_screens', 'clone_project_field_configurations']
    .map(toolName => call('read_tool_description', { toolName }));
  const result = await runStub([...reads, preview(), approval(), start(), terminal(), final()]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.checks.bounded_reads_before_preview, false);
  assert.equal(result.verdict.observations.unrelated_description_reads_before_preview, 3);
  assert.equal(result.verdict.checks.terminal_screen, true);
});

test('create screen names: reading the actual create_screen schema once is allowed', async () => {
  const result = await runStub([call('read_tool_description', { toolName: 'create_screen' }),
    preview(), approval(), start(), terminal(), final()]);
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.checks.bounded_reads_before_preview, true);
});

test('create screen names: asking for a required-field list fails even after a valid preview', async () => {
  const result = await runStub([preview(), decision({ kind: 'REQUEST_REQUIRED_FIELDS' },
    'Пришлите список обязательных полей проекта LAB.'), approval(), start(), terminal(), final()]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.checks.no_required_fields_list_request, false);
  assert.equal(result.verdict.checks.one_preview_approval, false);
  assert.equal(result.verdict.checks.terminal_screen, true);
});

test('create screen names: direct apply is rejected by the real runner', async () => {
  const result = await runStub([preview(), approval(), call('create_screen', { dryRun: false, planId: PLAN })]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.trace.at(-1).result.errorCode, 'EVAL_UNMANAGED_APPLY');
  assert.equal(result.verdict.checks.managed_once_by_plan, false);
  assert.equal(result.verdict.checks.terminal_screen, false);
});

test('create screen names: invented field cannot be substituted into the requested screen', async () => {
  const result = await runStub([preview(['summary', 'customfield_99999', 'description']),
    preview(), approval(), start(), terminal(), final()]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.trace[0].result.fields[1].errorCode, 'UNKNOWN_FIELD');
  assert.equal(result.verdict.checks.requested_fields_only, false);
  assert.equal(result.verdict.checks.no_validation_errors, false);
});

test('create screen names: correct fields in the wrong order still fail after creation', async () => {
  const result = await runStub([preview(['description', FIELD, 'summary']), approval(), start(), terminal(), final()]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.checks.requested_fields_only, false);
  assert.equal(result.verdict.checks.visible_preview_data, false);
  assert.equal(result.verdict.checks.terminal_screen, false);
});

test('create screen names: success on QUEUED does not become a terminal report', async () => {
  const result = await runStub([preview(), approval(), start(), final(), final(), final()]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.checks.terminal_screen, false);
  assert.equal(result.verdict.checks.final_after_terminal, false);
});

test('create screen names: early report attached to start is not excused by a later terminal result', async () => {
  const early = start();
  early.content = final().content;
  const result = await runStub([preview(), approval(), early, terminal(), final()]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.checks.terminal_screen, true);
  assert.equal(result.verdict.checks.final_after_terminal, false);
});

test('create screen names: preview approval card cannot claim required fields were checked', async () => {
  const wrong = approval();
  wrong.content = wrong.content.replace('"UNAVAILABLE"', '"CHECKED"');
  const result = await runStub([preview(), wrong, start(), terminal(), final()]);
  assert.equal(result.code, 1, JSON.stringify(result.verdict));
  assert.equal(result.verdict.checks.required_fields_limitation, false);
});

test('create screen names: localized Jira display names are accepted when they actually match', () => {
  const scenario = generateCreateScreenNamesScenario();
  scenario.orderableFields[0].name = 'Тема';
  scenario.orderableFields[2].name = 'Описание';
  const mock = createScreenNamesMock(scenario);
  const result = mock({ dryRun: true, name: NAME, fields: ['Тема', FIELD, 'Описание'].map(name => ({ name })) });
  assert.equal(result.status, 'WOULD_CREATE');
  assert.deepEqual(result.fields.map(field => field.fieldId), IDS);
});

test('create screen names: ambiguity gives actual candidates and exact ID avoids it', () => {
  const scenario = generateCreateScreenNamesScenario();
  scenario.orderableFields.push({ fieldId: 'customfield_10101', name: FIELD, typeKey: 'text' });
  const mock = createScreenNamesMock(scenario);
  const failed = mock({ dryRun: true, name: NAME, fields: [{ name: FIELD }] });
  assert.equal(failed.errorCode, 'FIELD_ERROR');
  assert.equal(failed.fields[0].errorCode, 'AMBIGUOUS_FIELD');
  assert.deepEqual(failed.fields[0].suggestions, [`customfield_10100 (${FIELD})`, `customfield_10101 (${FIELD})`]);
  const resolved = mock({ dryRun: true, name: NAME, fields: [{ name: 'customfield_10101' }] });
  assert.equal(resolved.fields[0].fieldId, 'customfield_10101');
  assert.equal(resolved.status, 'WOULD_CREATE');
});
