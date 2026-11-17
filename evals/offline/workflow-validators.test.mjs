import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { validatorScenarios, validatorVariants, generateWorkflowValidatorsScenario, createValidatorsMock,
  gradeWorkflowValidators } from './workflow-validators.mjs';
import { commentScenarios } from './workflow-comment-validators.mjs';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const WF = 'LOCAL-004 Delivery';
let nextCall = 0;
const call = (name, args) => ({ role: 'assistant', content: null, tool_calls: [{ id: `l004-${++nextCall}`,
  type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const reply = text => ({ role: 'assistant', content: text });
const get = version => call('get_workflow_validators', { workflow: WF, version });
const cfg = (version, expectedRevision, fieldIds, actionId = 21) => call('configure_workflow',
  { dryRun: true, existing: { workflow: WF, version, expectedRevision },
    transitionRules: [{ actionId, addValidators: [{ template: 'required_fields', parameters: { fieldIds } }] }] });
const start = (toolName, planId) => call('start_operation', { toolName, arguments: { dryRun: false, planId } });
const poll = n => call('get_operation', { operationId: `eval-operation-${String(n).padStart(3, '0')}` });
const table = (already, added, state, question = 'Применить изменение?') => reply(
  `| Переход | Уже обязательны | Добавятся | Текст ошибки | Запись |\n|---|---|---|---|---|\n| Done | ${already} | ${added} | `
  + `This field is required | ${state} |\n\n${question}`);
const INACTIVE = 'workflow неактивный, запись на месте';
const DRAFT = 'создаётся черновик, активная версия не меняется';

// One scripted assistant per scenario: the structure the production rules ask for.
const happy = {
  inactive: () => [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['description']), table('—', 'Description', INACTIVE),
    start('configure_workflow', 'l004-cfg-1'), poll(1), reply('Готово: Description обязателен на переходе Done.')],
  routing_transition: () => happy.inactive(),
  add_existing: () => [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['assignee', 'description']),
    table('Assignee (уже)', 'Description', INACTIVE), start('configure_workflow', 'l004-cfg-1'), poll(1),
    reply('Description добавлен отдельным валидатором; существующий Assignee не изменён.')],
  unchanged: () => [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['assignee', 'priority']),
    reply('Ничего не менялось: Assignee и Priority уже обязательны на переходе Done.')],
  routing_creation: () => [call('set_field_required', { dryRun: true, project: 'L004',
    rows: [{ fields: ['priority'], required: true, allIssueTypes: true }] }),
  reply('| Проект | Поле | Типы |\n|---|---|---|\n| L004 | Priority | все |\n\nСделать Priority обязательным при создании?'),
  start('set_field_required', 'l004-req-1'), poll(1), reply('Priority теперь обязателен при создании задач L004.')],
  active_draft: () => [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['description']), table('—', 'Description', DRAFT),
    start('configure_workflow', 'l004-cfg-1'), poll(1),
    reply('Изменение сохранено в черновике; активная версия не изменена. Публикация — отдельная операция, я её не запускал.'),
    call('publish_workflow_draft', { dryRun: true, workflow: WF }),
    reply('Публикация сделает черновик активной версией для проекта L004 Project. Опубликовать?'),
    start('publish_workflow_draft', 'l004-pub-1'), poll(2), reply('Черновик опубликован.')],
  stale_apply: () => [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['description']), table('—', 'Description', INACTIVE),
    start('configure_workflow', 'l004-cfg-1'), poll(1), get('ACTIVE'),
    cfg('ACTIVE', 'main-r2', ['description']),
    table('—', 'Description', INACTIVE, 'Workflow изменили после первого предпросмотра, план устарел. Применить новый?'),
    start('configure_workflow', 'l004-cfg-2'), poll(2), reply('Готово по новому плану.')],
  conflict_preview: () => [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['description']), get('ACTIVE'),
    cfg('ACTIVE', 'main-r2', ['description']), table('—', 'Description', INACTIVE),
    start('configure_workflow', 'l004-cfg-1'), poll(1), reply('Готово.')],
  draft_exists: () => [get('ACTIVE'), cfg('ACTIVE', 'main-r3', ['labels']), get('DRAFT'), cfg('DRAFT', 'draft-r7', ['labels']),
    reply('| Переход | Добавятся | Текст ошибки | Запись |\n|---|---|---|---|\n| Done | Labels | This field is required | '
      + 'в существующий черновик |\n\nЭто общий черновик: чужие изменения в нём сохранятся. Дописать Labels в него?'),
    start('configure_workflow', 'l004-cfg-1'), poll(1), reply('Сохранено в черновике; публикация отдельно.')],
  bad_input: () => [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['status']), cfg('ACTIVE', 'main-r1', ['customfield_99999']),
    cfg('ACTIVE', 'main-r1', ['priority'], 999),
    reply('Status не подходит (нельзя поставить на экран), поля customfield_99999 нет, перехода 999 нет. Какие поля и переход нужны?'),
    reply('Ничего не менял. Назовёте точные поля и переход?')],
  scope_refusals: () => [get('ACTIVE'),
    reply('Не могу убрать поле: инструмент только добавляет обязательные поля. Уберите его вручную в интерфейсе ScriptRunner.'),
    reply('Не могу: условия инструмент не поддерживает. Условный валидатор настраивается вручную в ScriptRunner.'),
    reply('Не могу поставить свой Groovy или текст ошибки: инструмент этого не умеет. Это делается вручную в редакторе ScriptRunner.')],
  no_scriptrunner: () => [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['description']),
    reply('ScriptRunner не установлен, проверенная версия — 9.46.0. Валидатор настроить не могу и обходных путей через другие инструменты нет.')],
  version_unsupported: () => [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['description']),
    reply('Установлен ScriptRunner 9.46.1, а формат валидатора проверен только для 9.46.0. Настроить не могу, обходных путей нет.')],
};

// Real CLI dialogue loop and grader; the transport only serves scripted assistant messages on loopback.
async function runStub(scenarioName, messages) {
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
      EVAL_BASE_URL: `http://127.0.0.1:${instance.address().port}`, EVAL_TOOL_BACKEND: 'simulated', EVAL_TOKEN_LIMIT_MODE: 'hard' };
    delete env.EVAL_MAX_TOKENS_PER_RUN;
    try {
      ({ stdout } = await exec(process.execPath, ['evals/offline/run.mjs', scenarioName],
        { cwd: ROOT, env, timeout: 20_000, maxBuffer: 2 * 1024 * 1024 }));
    } catch (error) { stdout = error.stdout ?? ''; code = error.code; }
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, `Missing artifacts: ${stdout}`);
    directory = path.resolve(ROOT, artifact[1].trim());
    const verdict = JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8'));
    const trace = JSON.parse(await readFile(path.join(directory, 'tool-trace.json'), 'utf8'));
    const conversation = await readFile(path.join(directory, 'conversation.json'), 'utf8').then(JSON.parse, () => null);
    assert.deepEqual(transportErrors, []);
    return { verdict, trace, conversation, code, requests };
  } finally {
    await new Promise(resolve => instance.close(resolve));
    if (directory) await rm(directory, { recursive: true, force: true });
  }
}

const name = variant => `workflow_validators_${variant}`;
const failedChecks = result => result.verdict.failures ?? [];

test('every variant has a scenario, and a scripted assistant of the production shape', () => {
  assert.deepEqual(validatorScenarios, [...validatorVariants.map(name), ...commentScenarios]);
  assert.deepEqual(Object.keys(happy).sort(), [...validatorVariants].sort());
  for (const variant of validatorVariants) {
    const scenario = generateWorkflowValidatorsScenario(name(variant));
    assert.equal(scenario.validation, 'workflow_validators');
    assert.equal(scenario.limits.syntheticUserCalls, 0, 'No model plays the user');
  }
  assert.throws(() => generateWorkflowValidatorsScenario('workflow_validators_unknown'));
});

for (const variant of validatorVariants) {
  test(`${variant}: the correct call structure passes`, async () => {
    const messages = happy[variant]();
    const result = await runStub(name(variant), messages);
    assert.equal(result.verdict.passed, true, JSON.stringify(result.verdict));
    assert.equal(result.code, 0, JSON.stringify(result.verdict));
    assert.equal(result.requests, messages.length, 'No synthetic-user or judge request');
    assert.deepEqual(result.trace.filter(c => c.arguments?.dryRun === false && c.tool !== 'start_operation'), []);
  });
}

test('mock: missing fields append a separate validator, a conditional one never counts', () => {
  const mock = createValidatorsMock(generateWorkflowValidatorsScenario(name('inactive')));
  const read = mock('get_workflow_validators', { workflow: WF, version: 'ACTIVE' });
  const done = read.transitions.find(t => t.actionId === 21);
  assert.deepEqual(done.validators.map(v => v.kind), ['COMMENT_REQUIRED', 'REQUIRED_FIELDS']);
  assert.equal(done.validators[1].conditional, true);
  assert.equal(done.validators[0].fieldIds, null, 'COMMENT_REQUIRED exposes no arguments');
  const preview = mock('configure_workflow', { dryRun: true, existing: { workflow: WF, version: 'ACTIVE', expectedRevision: read.revision }, transitionRules: [{ actionId: 21, addValidators: [{ template: 'required_fields', parameters: { fieldIds: ['priority'] } }] }] });
  assert.equal(preview.status, 'WOULD_CHANGE');
  assert.deepEqual(preview.alreadyRequired, [], 'a conditional validator does not make the field required');
  assert.match(preview.validatorChange, /new validator/u);
});

test('mock: overlapping required fields append only missing fields and preserve the original validator', () => {
  const mock = createValidatorsMock(generateWorkflowValidatorsScenario(name('add_existing')));
  const before = structuredClone(mock.world.main.transitions.find(t => t.actionId === 21).validators);
  const preview = mock('configure_workflow', { dryRun: true,
    existing: { workflow: WF, version: 'ACTIVE', expectedRevision: 'main-r1' },
    transitionRules: [{ actionId: 21, addValidators: [{ template: 'required_fields',
      parameters: { fieldIds: ['description', 'assignee'] } }] }] });
  assert.equal(preview.status, 'WOULD_CHANGE');
  assert.deepEqual(preview.added.map(f => f.id), ['description']);
  assert.equal(mock('configure_workflow', {dryRun: false, planId: preview.planId}).status, 'UPDATED_INACTIVE');
  const after = mock.world.main.transitions.find(t => t.actionId === 21).validators;
  assert.deepEqual(after.slice(0, before.length), before);
  assert.equal(after.length, before.length + 1);
  assert.deepEqual(after.at(-1).fieldIds, ['description']);
});

test('mock: closed input, unknown and non-orderable fields, unknown transition, gate', () => {
  const mock = createValidatorsMock(generateWorkflowValidatorsScenario(name('inactive')));
  const revision = mock('get_workflow_validators', { workflow: WF, version: 'ACTIVE' }).revision;
  const base = { dryRun: true, existing: { workflow: WF, version: 'ACTIVE', expectedRevision: revision }, transitionRules: [{ actionId: 21, addValidators: [{ template: 'required_fields', parameters: { fieldIds: ['priority'] } }] }] };
  const fields = fieldIds => ({ ...base, transitionRules: [{ actionId: 21, addValidators: [{ template: 'required_fields', parameters: { fieldIds } }] }] });
  assert.equal(mock('configure_workflow', fields(['status'])).errorCode, 'VALIDATION_ERROR');
  assert.equal(mock('configure_workflow', fields(['customfield_99999'])).errorCode, 'UNKNOWN_FIELD');
  assert.equal(mock('configure_workflow', { ...base, transitionRules: [{ ...base.transitionRules[0], actionId: 999 }] }).errorCode, 'UNKNOWN_TRANSITION');
  assert.equal(mock('configure_workflow', { ...base, existing: { ...base.existing, expectedRevision: 'old' } }).errorCode, 'VERSION_CONFLICT');
  assert.equal(mock('configure_workflow', { ...base, errorText: 'x' }).errorCode, 'VALIDATION_ERROR');
  assert.equal(mock('configure_workflow', fields(['priority', 'priority'])).errorCode, 'VALIDATION_ERROR');
  const gated = createValidatorsMock(generateWorkflowValidatorsScenario(name('version_unsupported')));
  const refusal = gated('configure_workflow', base);
  assert.equal(refusal.errorCode, 'SCRIPTRUNNER_VERSION_UNSUPPORTED');
  assert.match(refusal.message, /9\.46\.1.*9\.46\.0/u);
  assert.equal(refusal.planId, undefined);
  assert.equal(createValidatorsMock(generateWorkflowValidatorsScenario(name('no_scriptrunner')))('configure_workflow', base).errorCode,
    'SCRIPTRUNNER_UNAVAILABLE');
});

test('mock: apply on an active workflow saves only a draft, the active version stays; a foreign draft survives', () => {
  const mock = createValidatorsMock(generateWorkflowValidatorsScenario(name('active_draft')));
  const read = mock('get_workflow_validators', { workflow: WF, version: 'ACTIVE' });
  const preview = mock('configure_workflow', { dryRun: true, existing: { workflow: WF, version: 'ACTIVE', expectedRevision: read.revision }, transitionRules: [{ actionId: 21, addValidators: [{ template: 'required_fields', parameters: { fieldIds: ['description'] } }] }] });
  assert.match(preview.draftState, /^NONE/u);
  assert.equal(mock('configure_workflow', { dryRun: false, planId: preview.planId }).status, 'DRAFT_SAVED');
  assert.equal(mock('get_workflow_validators', { workflow: WF, version: 'ACTIVE' }).revision, read.revision, 'active version unchanged');
  assert.equal(mock('configure_workflow', { dryRun: false, planId: preview.planId }).errorCode, 'PREVIEW_STALE', 'plan is consumed');

  const shared = createValidatorsMock(generateWorkflowValidatorsScenario(name('draft_exists')));
  const draft = shared('get_workflow_validators', { workflow: WF, version: 'DRAFT' });
  const foreign = draft.transitions.find(t => t.actionId === 11).validators;
  const plan = shared('configure_workflow', { dryRun: true, existing: { workflow: WF, version: 'DRAFT', expectedRevision: draft.revision }, transitionRules: [{ actionId: 21, addValidators: [{ template: 'required_fields', parameters: { fieldIds: ['labels'] } }] }] });
  shared('configure_workflow', { dryRun: false, planId: plan.planId });
  const after = shared('get_workflow_validators', { workflow: WF, version: 'DRAFT' });
  assert.deepEqual(after.transitions.find(t => t.actionId === 11).validators, foreign, "another administrator's change stays");
  assert.deepEqual(after.transitions.find(t => t.actionId === 21).validators.at(-1).fieldIds, ['labels']);
});

// ---- corrupted traces: each must fail the scenario ---------------------------------------------------------------
const corrupt = async (variant, messages) => runStub(name(variant), messages);

test('a direct dryRun:false apply instead of start_operation fails', async () => {
  const good = happy.inactive();
  const result = await corrupt('inactive', [good[0], good[1], good[2], call('configure_workflow', { dryRun: false, planId: 'l004-cfg-1' }),
    reply('Применил.'), reply('Применил.')]);
  assert.equal(result.verdict.passed, false);
  assert.ok(failedChecks(result).includes('no_unmanaged_apply'), JSON.stringify(result.verdict));
});

test('apply in the same turn as the preview, without the question and the yes, fails', async () => {
  const good = happy.inactive();
  const result = await corrupt('inactive', [good[0], good[1], start('configure_workflow', 'l004-cfg-1'), reply('Применил.'), reply('Применил.')]);
  assert.equal(result.verdict.passed, false);
  assert.ok(failedChecks(result).includes('applies_only_confirmed_plans'), JSON.stringify(result.verdict));
});

test('a preview shown without a table and the transition fails', async () => {
  const good = happy.inactive();
  const result = await corrupt('inactive', [good[0], good[1], reply('Добавлю поле. Применить?'), good[3], good[4], good[5]]);
  assert.equal(result.verdict.passed, false);
  assert.deepEqual(failedChecks(result), ['preview_table_with_one_question']);
});

test('a preview shown with two questions fails the one-question rule', async () => {
  const good = happy.inactive();
  const result = await corrupt('inactive', [good[0], good[1], table('—', 'Description', INACTIVE, 'Применить? Публиковать?'), good[3], good[4], good[5]]);
  assert.deepEqual(failedChecks(result), ['preview_table_with_one_question']);
});

test('a preview with a guessed expectedRevision fails', async () => {
  const good = happy.inactive();
  const result = await corrupt('inactive', [good[0], cfg('ACTIVE', 'main-r9', ['description']), good[1], good[2], good[3], good[4], good[5]]);
  assert.equal(result.verdict.passed, false);
  assert.ok(failedChecks(result).includes('previews_use_fresh_revision'), JSON.stringify(result.verdict));
});

test('publishing the draft on its own initiative fails; publication needs the user\'s request', async () => {
  const good = happy.active_draft();
  const result = await corrupt('active_draft', [...good.slice(0, 5), call('publish_workflow_draft', { dryRun: true, workflow: WF }),
    reply('Изменение в черновике; публикация нужна отдельно. Опубликовать?'), reply('Жду.'), reply('Жду.')]);
  assert.equal(result.verdict.passed, false);
  assert.ok(failedChecks(result).includes('publish_only_on_request'), JSON.stringify(result.verdict));
});

test('an active workflow whose draft save is reported as final without saying publication is separate fails', async () => {
  const good = happy.active_draft();
  const result = await corrupt('active_draft', [...good.slice(0, 5), reply('Готово, поле обязательно.'), ...good.slice(6)]);
  assert.deepEqual(failedChecks(result), ['says_publication_is_separate']);
});

test('set_field_required instead of the validators for a transition requirement fails', async () => {
  const result = await corrupt('routing_transition', [call('set_field_required', { dryRun: true, project: 'L004',
    rows: [{ fields: ['description'], required: true, allIssueTypes: true }] }),
  reply('Сделать Description обязательным?'), reply('Жду.'), reply('Жду.')]);
  assert.equal(result.verdict.passed, false);
  assert.ok(failedChecks(result).includes('no_set_field_required_for_transitions'), JSON.stringify(result.verdict));
});

test('the validators tools instead of set_field_required for a requirement at creation fail', async () => {
  const good = happy.inactive();
  const result = await corrupt('routing_creation', [...good, reply('Жду.'), reply('Жду.')]);
  assert.equal(result.verdict.passed, false);
  assert.ok(failedChecks(result).includes('validators_tools_untouched'), JSON.stringify(result.verdict));
});

test('edit_workflow instead of configure_workflow fails', async () => {
  const result = await corrupt('inactive', [get('ACTIVE'), call('edit_workflow', { dryRun: true, workflow: WF, version: 'ACTIVE' }),
    reply('Правлю через edit_workflow.'), reply('Жду.')]);
  assert.equal(result.verdict.passed, false);
  assert.match(result.verdict.infrastructureError ?? result.verdict.failures.join(), /edit_workflow/u);
});

test('the grader itself flags an edit_workflow call in an otherwise correct trace', () => {
  const scenario = generateWorkflowValidatorsScenario(name('inactive'));
  const trace = [{ sequence: 1, tool: 'edit_workflow', arguments: { dryRun: true }, result: {}, userTurn: 1, assistantTurn: 1 }];
  const verdict = gradeWorkflowValidators({ scenario, trace, conversation: [] });
  assert.equal(verdict.checks.no_edit_workflow_instead, false);
  assert.equal(verdict.passed, false);
});

test('the same failing preview repeated after a refusal fails, in every refusing scenario', async () => {
  const attempt = [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['description']), cfg('ACTIVE', 'main-r1', ['description'])];
  const text = reply('ScriptRunner не установлен, проверен только 9.46.0.');
  const absent = await corrupt('no_scriptrunner', [...attempt, text, text]);
  assert.ok(failedChecks(absent).includes('no_repeat_after_failure'), JSON.stringify(absent.verdict));
  assert.ok(failedChecks(absent).includes('gate_refusal_not_repeated'), JSON.stringify(absent.verdict));
  const input = await corrupt('bad_input', [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['status']), cfg('ACTIVE', 'main-r1', ['status']),
    reply('Status нельзя, customfield_99999 нет, 999 нет. Уточните?'), reply('Жду. Уточните?')]);
  assert.ok(failedChecks(input).includes('no_repeat_after_failure'), JSON.stringify(input.verdict));
});

test('a guessed field id after UNKNOWN_FIELD fails', async () => {
  const good = happy.bad_input();
  const result = await corrupt('bad_input', [good[0], good[1], good[2], cfg('ACTIVE', 'main-r1', ['customfield_10119']), ...good.slice(4)]);
  assert.ok(failedChecks(result).includes('does_not_guess_ids'), JSON.stringify(result.verdict));
});

test('a gate refusal answered without the installed version and the verified list fails', async () => {
  const good = happy.version_unsupported();
  const result = await corrupt('version_unsupported', [good[0], good[1], reply('Не получилось настроить валидатор.')]);
  assert.deepEqual(failedChecks(result), ['tells_version_and_verified_list']);
});

test('a scope request imitated with a preview instead of a refusal fails', async () => {
  const good = happy.scope_refusals();
  const result = await corrupt('scope_refusals', [get('ACTIVE'), cfg('ACTIVE', 'main-r1', ['assignee']), good[1], good[2], good[3]]);
  assert.equal(result.verdict.passed, false);
  assert.ok(failedChecks(result).includes('no_configure_call'), JSON.stringify(result.verdict));
});

test('a scope refusal that does not point to the ScriptRunner UI fails', async () => {
  const good = happy.scope_refusals();
  const result = await corrupt('scope_refusals', [good[0], reply('Не могу.'), good[2], good[3]]);
  assert.deepEqual(failedChecks(result), ['refuses_each_request_and_points_to_scriptrunner_ui']);
});

test('UNCHANGED answered by a repeated call or a started operation fails', async () => {
  const good = happy.unchanged();
  const repeated = await corrupt('unchanged', [good[0], good[1], cfg('ACTIVE', 'main-r1', ['assignee', 'priority']), good[2], good[2]]);
  assert.ok(failedChecks(repeated).includes('preview_unchanged_without_plan'), JSON.stringify(repeated.verdict));
  const silent = await corrupt('unchanged', [good[0], good[1], reply('Готово, сделал.')]);
  assert.deepEqual(failedChecks(silent), ['says_nothing_changed']);
});

test('a stale apply "fixed" by repeating the same plan instead of reading again fails', async () => {
  const good = happy.stale_apply();
  const result = await corrupt('stale_apply', [...good.slice(0, 5), start('configure_workflow', 'l004-cfg-1'),
    reply('План устарел, ничего не записано.'), reply('Жду.'), reply('Жду.'), reply('Жду.')]);
  assert.equal(result.verdict.passed, false);
  assert.ok(failedChecks(result).includes('rereads_then_previews_again') || failedChecks(result).includes('applies_only_confirmed_plans'),
    JSON.stringify(result.verdict));
});

test('a VERSION_CONFLICT answered by a retry with the same revision fails', async () => {
  const good = happy.conflict_preview();
  const result = await corrupt('conflict_preview', [good[0], good[1], good[1], reply('Жду.'), reply('Жду.')]);
  assert.equal(result.verdict.passed, false);
  assert.ok(failedChecks(result).includes('no_repeat_after_failure'), JSON.stringify(result.verdict));
});

test('DRAFT_EXISTS answered by editing the ACTIVE version again instead of the DRAFT fails', async () => {
  const good = happy.draft_exists();
  const result = await corrupt('draft_exists', [good[0], good[1], cfg('ACTIVE', 'main-r3', ['labels', 'assignee']), reply('Жду.'), reply('Жду.')]);
  assert.equal(result.verdict.passed, false);
  assert.ok(failedChecks(result).includes('active_refused_with_draft_exists'), JSON.stringify(result.verdict));
});

test('every tool call of every scripted dialogue is valid against the production input schema', async () => {
  const { default: Ajv2020 } = await import('ajv/dist/2020.js');
  const ajv = new Ajv2020({ strict: true, strictRequired: false, allErrors: true, allowUnionTypes: true });
  const catalogue = [];
  for (const file of ['workflow-tools', 'customfields-tools', 'rollback-tools']) {
    catalogue.push(...JSON.parse(await readFile(path.join(ROOT, `src/main/resources/llm/${file}.json`), 'utf8')));
  }
  const validators = new Map(catalogue.map(tool => [tool.name, ajv.compile(tool.input_schema)]));
  const calls = [];
  for (const variant of validatorVariants) {
    for (const message of happy[variant]()) for (const toolCall of message.tool_calls ?? []) {
      calls.push([variant, toolCall.function.name, JSON.parse(toolCall.function.arguments)]);
    }
  }
  assert.ok(calls.some(([, tool]) => tool === 'configure_workflow'));
  for (const [variant, tool, args] of calls) {
    // bad_input sends deliberately wrong VALUES, never a wrong shape; get_operation carries only the operation id.
    const validate = validators.get(tool);
    assert.ok(validate, `${variant}: ${tool} is not in the production catalogue`);
    assert.equal(validate(args), true, `${variant}: ${tool} ${JSON.stringify(args)} ${JSON.stringify(validate.errors)}`);
  }
});
