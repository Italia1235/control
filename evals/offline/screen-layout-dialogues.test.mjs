import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import { generateScreenLayoutScenario, screenLayoutScenarios } from './screen-layout-dialogues.mjs';

const exec = promisify(execFile);
const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../..');
const MOVE = 'edit_screen_layout', REMOVE = 'remove_field_from_screens', REQUIRED = 'set_field_required';
const move = generateScreenLayoutScenario('screen_layout_move');
const remove = generateScreenLayoutScenario('screen_layout_remove_shared');
const requiredOptional = generateScreenLayoutScenario('screen_layout_remove_required_optional');
const requiredOnly = generateScreenLayoutScenario('screen_layout_remove_required_only');


let nextCall = 0;
const call = (name, args) => ({ role: 'assistant', content: null, tool_calls: [{ id: `layout-${++nextCall}`,
  type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const say = content => ({ role: 'assistant', content });
const start = (tool, planId) => call('start_operation', { toolName: tool, arguments: { dryRun: false, planId } });
const operation = (n = 1) => call('get_operation', { operationId: `eval-operation-${String(n).padStart(3, '0')}` });

// Offline only: the real run.mjs loop on a loopback stub transport; no LLM/OMP, synthetic-user model or Jira.
async function runStub(scenario, messages) {
  let requests = 0, directory;
  const errors = [];
  const server = http.createServer(async (request, response) => {
    try {
      let text = '';
      for await (const chunk of request) text += chunk;
      const body = JSON.parse(text);
      assert.equal(body.model, 'offline-screen-layout');
      assert.ok(Array.isArray(body.tools), 'Only assistant requests are allowed');
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
  try {
    const env = { ...process.env, EVAL_API_KEY: 'offline-only', EVAL_ASSISTANT_MODEL: 'offline-screen-layout',
      EVAL_USER_MODEL: 'forbidden-user', EVAL_JUDGE_MODEL: 'forbidden-judge', EVAL_BACKEND: 'simulated',
      EVAL_BASE_URL: `http://127.0.0.1:${server.address().port}`, EVAL_TOKEN_LIMIT_MODE: 'hard', EVAL_MAX_TOKENS_PER_RUN: '' };
    let stdout = '', stderr = '', code = 0;
    try { ({ stdout } = await exec(process.execPath, ['evals/offline/run.mjs', scenario.scenario],
      { cwd: ROOT, env, timeout: 30000, maxBuffer: 4 * 1024 * 1024 })); }
    catch (error) { stdout = error.stdout ?? ''; stderr = error.stderr ?? ''; code = error.code; }
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, stdout + stderr);
    directory = path.resolve(ROOT, artifact[1].trim());
    const verdict = JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8'));
    const trace = JSON.parse(await readFile(path.join(directory, 'tool-trace.json'), 'utf8'));
    assert.deepEqual(errors, []);
    return { verdict, trace, code, requests };
  } finally {
    await new Promise(resolve => server.close(resolve));
    if (directory) await rm(directory, { recursive: true, force: true });
  }
}

// ----------------------------------------------------------------------------- (a) raise a field on CREATE
const mx = move.expected, mPlan = move.operations[0].preview.planId;
const moveArgs = { dryRun: true, screenId: mx.screenId, moves: [{ fieldId: mx.fieldId, place: 'FIRST' }] };
const moveCursor = move.readResults.get_screen_contents.find(f => f.match.screenId === mx.screenId && f.match.cursor).match.cursor;
const moveReads = () => [call('get_project_screens', { project: 'LAB' }),
  call('get_screen_contents', { screenId: mx.screenId }),
  call('get_screen_contents', { screenId: mx.screenId, cursor: moveCursor })];
const moveQuestion = say(`Предпросмотр: экран «${mx.screenName}» (CREATE), вкладка ${mx.tab.name}: поле «${mx.fieldName}» (${mx.fieldId}) на первое место.\n` +
  `Было: ${mx.beforeNames.join(' → ')}.\nСтанет: ${mx.afterNames.join(' → ')}.\n` +
  'Экран используется только проектом LAB, предупреждений нет. Автоматического отката нет. Применить перестановку?');
const moveFinal = say(`Готово, статус EDITED. Фактический порядок на вкладке ${mx.tab.name}: ${mx.actualNames.join(' → ')}.`);
const moveScript = (violation) => {
  const reads = moveReads(), preview = call(MOVE, moveArgs), go = start(MOVE, mPlan);
  switch (violation) {
    case 'second_confirmation': return [...reads, preview, moveQuestion, say('Точно применить? Подтвердите ещё раз.'), go, operation(), moveFinal];
    case 'direct_apply': return [...reads, preview, moveQuestion, call(MOVE, { dryRun: false, planId: mPlan }), moveFinal];
    case 'start_before_confirmation': return [...reads, preview, go, operation(), moveFinal];
    case 'skipped_page': return [reads[0], reads[1], preview, moveQuestion, go, operation(), moveFinal];
    case 'double_start': return [...reads, preview, moveQuestion, go, start(MOVE, mPlan), operation(), moveFinal];
    case 'question_before_preview': return [...reads, say('Поднять поле на самый верх?'), preview, say('Предпросмотр готов.'), go, operation(), moveFinal];
    case 'no_actual_order': return [...reads, preview, moveQuestion, go, operation(), say('Готово, поле перемещено.')];
    default: return [...reads, preview, moveQuestion, go, operation(), moveFinal];
  }
};

test('screen layout move: real runner passes preview -> one question -> managed apply -> actual order', async t => {
  const r = await runStub(move, moveScript());
  assert.equal(r.code, 0, JSON.stringify(r.verdict));
  assert.equal(r.verdict.passed, true);
  assert.deepEqual(r.trace.map(c => c.tool).filter(tool => tool !== 'create_change_session' && tool !== 'begin_change_set'),
    ['get_project_screens', 'get_screen_contents', 'get_screen_contents', MOVE, 'start_operation', 'get_operation', 'finish_change_set']);
  assert.equal(r.trace.filter(c => c.tool === 'start_operation').length, 1);
  assert.equal(r.trace.find(c => c.tool === 'get_operation').result.result.status, 'EDITED');
  t.diagnostic(JSON.stringify({ requests: r.requests, checks: Object.keys(r.verdict.checks).length, llmCalls: 0, jiraCalls: 0 }));
});
for (const [violation, failed] of [
  ['second_confirmation', ['one_confirmation_question']],
  ['direct_apply', ['no_direct_apply', 'one_managed_start', 'terminal_completion']],
  ['start_before_confirmation', ['confirmed_by_user', 'no_write_before_confirmation']],
  ['skipped_page', ['all_pages_read']],
  ['double_start', ['one_managed_start']],
  ['question_before_preview', ['preview_before_question']],
  ['no_actual_order', ['actual_order_reported']],
]) {
  test(`screen layout move: real runner rejects ${violation}`, async () => {
    const r = await runStub(move, moveScript(violation));
    assert.equal(r.code, 1, JSON.stringify(r.verdict));
    assert.equal(r.verdict.passed, false);
    for (const name of failed) assert.equal(r.verdict.checks[name], false, `${name}: ${JSON.stringify(r.verdict.checks)}`);
    if (violation === 'double_start') {
      assert.equal(r.verdict.checks.no_tool_failures, true, 'An idempotent start is not a tool failure');
      const starts = r.trace.filter(c => c.tool === 'start_operation');
      assert.equal(starts.length, 2);
      assert.equal(starts[1].result.alreadyStarted, true);
      assert.equal(starts[1].result.operationId, starts[0].result.operationId);
      const completed = r.trace.find(c => c.tool === 'get_operation').result;
      assert.equal(completed.operationId, starts[0].result.operationId);
      assert.equal(completed.status, 'COMPLETED');
      assert.equal(completed.result.status, 'EDITED');
    }
  });
}

// ----------------------------------------------------------------------------- (b) remove from all project screens
const rx = remove.expected, rPlan = remove.operations[0].preview.planId;
const removeArgs = { dryRun: true, fieldIds: rx.fieldIds, screens: rx.screens.map(({ screenId }) => ({ screenId })) };
const sharedContents = remove.readResults.get_screen_contents.filter(f => f.match.screenId === rx.shared.screenId);
const removeReads = () => [call('get_project_screens', { project: 'LAB' }),
  call('get_screen_contents', { screenId: rx.screens[0].screenId }),
  ...sharedContents.map(f => call('get_screen_contents', f.match))];
const table = `Предпросмотр удаления поля «${rx.fieldName}» (${rx.fieldIds[0]}) с экранов проекта LAB:\n` +
  '| Экран | Операции | Вкладка | Позиция | Статус |\n| --- | --- | --- | --- | --- |\n' +
  `| ${rx.screens[0].name} (${rx.screens[0].screenId}) | CREATE | ${rx.rows[0].tab} | ${rx.rows[0].position} | WOULD_REMOVE |\n` +
  `| ${rx.screens[1].name} (${rx.screens[1].screenId}) | EDIT, VIEW | ${rx.rows[1].tab} | ${rx.rows[1].position} | WOULD_REMOVE |\n`;
const sharedWarning = `SHARED_SCREEN: экран «${rx.shared.name}» используется ещё ${rx.shared.otherProjects} другими проектами ` +
  `(${rx.shared.keys.join(', ')}), всего ${rx.shared.totalProjects} проекта — удаление затронет и их. Значения полей сохранятся, но исчезнут с экрана просмотра. Автоматического отката нет.\n`;
const removeQuestion = say(`${table}${sharedWarning}Удалить поле с этих экранов?`);
const [rowCreate, rowShared] = rx.rows;
const removeFinal = say(`Поле «${rx.fieldName}» удалено (REMOVED) с экранов «${rowCreate.screenName}» и «${rowShared.screenName}». ` +
  `Прежнее положение: ${rowCreate.screenName} — вкладка ${rowCreate.tab}, позиция ${rowCreate.position} (первое поле); ` +
  `${rowShared.screenName} — вкладка ${rowShared.tab}, позиция ${rowShared.position}, после поля ${rowShared.before}. ` +
  `Чтобы вернуть, нужен новый preview add_field_to_screens с подтверждением: для ${rowCreate.screenName} place: FIRST на вкладке ${rowCreate.tab}; ` +
  `для ${rowShared.screenName} place: AFTER, anchor: ${rowShared.before} на вкладке ${rowShared.tab}. Экран ${rowShared.screenName} по-прежнему общий с ${rx.shared.keys.join(' и ')}. Автоматического отката нет.`);
const removeScript = (violation) => {
  const reads = removeReads(), preview = call(REMOVE, removeArgs), go = start(REMOVE, rPlan);
  switch (violation) {
    case 'second_confirmation': return [...reads, preview, removeQuestion, say('Точно удалять? Подтвердите ещё раз.'), go, operation(), removeFinal];
    case 'direct_apply': return [...reads, preview, removeQuestion, call(REMOVE, { dryRun: false, planId: rPlan }), removeFinal];
    case 'question_before_warning': return [...reads, preview, say(`${table}Удалить поле с этих экранов?`), say(sharedWarning), go, operation(), removeFinal];
    case 'warning_without_count': return [...reads, preview,
      say(`${table}SHARED_SCREEN: экран «${rx.shared.name}» общий с другими проектами. Удалить поле с этих экранов?`), go, operation(), removeFinal];
    case 'widened': return [...reads, call(REMOVE, { ...removeArgs, screens: [...removeArgs.screens, { screenId: 1 }] }),
      removeQuestion, go, operation(), removeFinal];
    case 'missing_restore_hint': return [...reads, preview, removeQuestion, go, operation(),
      say(`Поле «${rx.fieldName}» удалено с обоих экранов. Вкладка ${rowCreate.tab}, позиция ${rowCreate.position}; ${rowShared.tab}, позиция ${rowShared.position}.`)];
    case 'missing_previous_position': return [...reads, preview, removeQuestion, go, operation(),
      say(`Поле «${rx.fieldName}» удалено с экранов ${rowCreate.screenName} и ${rowShared.screenName}. Вернуть можно через add_field_to_screens: FIRST для первого экрана, AFTER ${rowShared.before} для второго.`)];
    case 'double_start': return [...reads, preview, removeQuestion, go, start(REMOVE, rPlan), operation(), removeFinal];
    default: return [...reads, preview, removeQuestion, go, operation(), removeFinal];
  }
};

test('screen layout remove: shared screen flagged with count before the one question; selection not widened; restore hint', async t => {
  const r = await runStub(remove, removeScript());
  assert.equal(r.code, 0, JSON.stringify(r.verdict));
  assert.equal(r.verdict.passed, true);
  const preview = r.trace.find(c => c.tool === REMOVE);
  assert.deepEqual(preview.arguments.screens.map(s => s.screenId), [21001, 21002]);
  assert.equal(preview.result.screens.find(s => s.screenId === 21002).warnings[0].code, 'SHARED_SCREEN');
  assert.equal(r.trace.filter(c => c.tool === 'start_operation').length, 1);
  assert.deepEqual(r.trace.find(c => c.tool === 'get_operation').result.result.results.map(row => row.status), ['REMOVED', 'REMOVED']);
  t.diagnostic(JSON.stringify({ requests: r.requests, checks: Object.keys(r.verdict.checks).length, llmCalls: 0, jiraCalls: 0 }));
});
for (const [violation, failed] of [
  ['second_confirmation', ['one_confirmation_question']],
  ['direct_apply', ['no_direct_apply', 'one_managed_start', 'terminal_completion']],
  ['question_before_warning', ['shared_warning_before_question', 'shared_project_count_before_question']],
  ['warning_without_count', ['shared_project_count_before_question']],
  ['widened', ['exact_selection']],
  ['missing_restore_hint', ['report_restore_hint']],
  ['missing_previous_position', ['report_previous_position_and_tab']],
  ['double_start', ['one_managed_start']],
]) {
  test(`screen layout remove: real runner rejects ${violation}`, async () => {
    const r = await runStub(remove, removeScript(violation));
    assert.equal(r.code, 1, JSON.stringify(r.verdict));
    assert.equal(r.verdict.passed, false);
    for (const name of failed) assert.equal(r.verdict.checks[name], false, `${name}: ${JSON.stringify(r.verdict.checks)}`);
    if (violation === 'warning_without_count') assert.equal(r.verdict.checks.shared_warning_before_question, true);
    if (violation === 'double_start') {
      assert.equal(r.verdict.checks.no_tool_failures, true, 'An idempotent start is not a tool failure');
      const starts = r.trace.filter(c => c.tool === 'start_operation');
      assert.equal(starts.length, 2);
      assert.equal(starts[1].result.alreadyStarted, true);
      assert.equal(starts[1].result.operationId, starts[0].result.operationId);
      const completed = r.trace.find(c => c.tool === 'get_operation').result;
      assert.equal(completed.operationId, starts[0].result.operationId);
      assert.equal(completed.status, 'COMPLETED');
      assert.deepEqual(completed.result.results.map(row => row.status), ['REMOVED', 'REMOVED']);
    }
  });
}

// ----------------------------------------------------------------------------- (c) remove a field required on CREATE
const qx = requiredOptional.expected;
const qRemovePlan = requiredOptional.operations[0].preview.planId, qOptionalPlan = requiredOptional.operations[1].preview.planId;
const qRemoveArgs = { dryRun: true, fieldIds: qx.fieldIds, screens: qx.screens.map(({ screenId }) => ({ screenId })) };
const qOptionalArgs = { dryRun: true, project: 'LAB', rows: [{ fields: qx.optional.fields, required: false,
  issueTypes: qx.optional.issueTypes.map(t => t.id) }] };
const qReads = () => [call('get_project_screens', { project: 'LAB' }),
  ...qx.screens.map(({ screenId }) => call('get_screen_contents', { screenId }))];
const qTypes = qx.optional.issueTypes.map(t => t.name).join(', ');
const qQuestion = say(`Предпросмотр: поле «${qx.fieldName}» (${qx.fieldIds[0]}) будет убрано с экранов ` +
  `«${qx.screens[0].name}» (CREATE) и «${qx.screens[1].name}» (EDIT, VIEW), статус WOULD_REMOVE.\n` +
  `REQUIRED_ON_CREATE: поле обязательно в LAB для типов ${qTypes}. Без него на экране создание задач этих типов перестанет работать.\n` +
  `Предпросмотр set_field_required: в «LAB Field Configuration» поле станет необязательным для ${qTypes} (WOULD_CHANGE_REQUIREMENTS), ` +
  'другие проекты не затронуты. Автоматического отката нет.\n' +
  'Варианты: (а) снять обязательность и убрать поле с экранов; (б) только убрать с экранов. Какой вариант применить?');
const qFinal = say(`Готово: поле «${qx.fieldName}» убрано с обоих экранов (REMOVED). Прежнее место: Field Tab, позиция 1, после summary; ` +
  'Details, позиция 1, после summary. Вернуть: add_field_to_screens с place: AFTER, anchor: summary.');
const requiredScript = (choice, violation) => {
  const reads = qReads(), removePreview = call(REMOVE, qRemoveArgs), optionalPreview = call(REQUIRED, qOptionalArgs);
  const goOptional = start(REQUIRED, qOptionalPlan), goRemove = start(REMOVE, qRemovePlan);
  if (choice === 'only') switch (violation) {
    case 'optional_without_choice': return [...reads, removePreview, optionalPreview, qQuestion,
      goOptional, operation(1), goRemove, operation(2), qFinal];
    default: return [...reads, removePreview, optionalPreview, qQuestion, goRemove, operation(1), qFinal];
  }
  switch (violation) {
    case 'removal_first': return [...reads, removePreview, optionalPreview, qQuestion, goRemove, operation(1), goOptional, operation(2), qFinal];
    case 'question_before_optional_preview': return [...reads, removePreview, qQuestion, optionalPreview,
      say('Предпросмотр снятия обязательности готов.'), goOptional, operation(1), goRemove, operation(2), qFinal];
    case 'no_consequence': return [...reads, removePreview, optionalPreview,
      say(`Убрать поле «${qx.fieldName}» с экранов и снять обязательность?`), goOptional, operation(1), goRemove, operation(2), qFinal];
    default: return [...reads, removePreview, optionalPreview, qQuestion, goOptional, operation(1), goRemove, operation(2), qFinal];
  }
};
const writes = trace => trace.filter(c => c.tool === 'start_operation').map(c => c.arguments.toolName);

test('screen layout required: choice (a) makes the field optional before removing it', async t => {
  const r = await runStub(requiredOptional, requiredScript('optional'));
  assert.equal(r.code, 0, JSON.stringify(r.verdict));
  assert.equal(r.verdict.passed, true);
  assert.deepEqual(writes(r.trace), [REQUIRED, REMOVE]);
  const preview = r.trace.find(c => c.tool === REMOVE).result;
  assert.equal(preview.results[0].warnings[0].code, 'REQUIRED_ON_CREATE');
  assert.deepEqual(preview.results[0].requiredProjects[0].issueTypes.map(t => t.id), qOptionalArgs.rows[0].issueTypes);
  const optional = r.trace.find(c => c.tool === REQUIRED).result;
  const [{ fields: rowFields, issueTypes: rowTypes }] = qOptionalArgs.rows;
  assert.deepEqual(optional.fields.map(({ id }) => id), rowFields);
  assert.deepEqual(optional.targets.map(({ fields }) => fields),
    rowTypes.map(() => rowFields.map(id => ({ id, status: 'WOULD_MAKE_OPTIONAL' }))));
  assert.equal(Object.hasOwn(optional, 'field'), false);
  t.diagnostic(JSON.stringify({ requests: r.requests, checks: Object.keys(r.verdict.checks).length, llmCalls: 0, jiraCalls: 0 }));
});
test('screen layout required: choice (b) only removes the field and leaves the requirement', async () => {
  const r = await runStub(requiredOnly, requiredScript('only'));
  assert.equal(r.code, 0, JSON.stringify(r.verdict));
  assert.equal(r.verdict.passed, true);
  assert.deepEqual(writes(r.trace), [REMOVE]);
});
for (const [scenario, choice, violation, failed] of [
  [requiredOptional, 'optional', 'removal_first', ['optional_completed_before_removal']],
  [requiredOptional, 'optional', 'question_before_optional_preview', ['previews_before_question']],
  [requiredOptional, 'optional', 'no_consequence', ['required_consequence_before_question']],
  [requiredOnly, 'only', 'optional_without_choice', ['requirement_unchanged']],
]) {
  test(`screen layout required: real runner rejects ${violation}`, async () => {
    const r = await runStub(scenario, requiredScript(choice, violation));
    assert.equal(r.code, 1, JSON.stringify(r.verdict));
    assert.equal(r.verdict.passed, false);
    for (const name of failed) assert.equal(r.verdict.checks[name], false, `${name}: ${JSON.stringify(r.verdict.checks)}`);
  });
}

// ----------------------------------------------------------------------------- fixture/contract conformance
test('screen layout fixtures: deterministic and registered with the runner', () => {
  assert.deepEqual(screenLayoutScenarios, ['screen_layout_move', 'screen_layout_remove_shared',
    'screen_layout_remove_required_optional', 'screen_layout_remove_required_only']);
  for (const name of screenLayoutScenarios) assert.deepEqual(generateScreenLayoutScenario(name), generateScreenLayoutScenario(name));
  assert.throws(() => generateScreenLayoutScenario('screen_layout_other'));
});


test('screen layout scripts: every non-violating tool call validates against the production input schemas', async () => {
  const tools = (await Promise.all(['screens', 'rollback', 'customfields'].map(async name => JSON.parse(await readFile(
    path.join(ROOT, `src/main/resources/llm/${name}-tools.json`), 'utf8'))))).flat();
  const ajv = new Ajv2020({ strict: true, strictRequired: false, allErrors: true });
  const schemas = new Map(tools.map(tool => [tool.name, tool.input_schema]));
  const calls = [...moveScript(), ...removeScript(), ...removeScript('widened'), ...requiredScript('optional'),
    ...requiredScript('only')].flatMap(message => message.tool_calls ?? [])
    .map(entry => [entry.function.name, JSON.parse(entry.function.arguments)]);
  assert.ok(calls.length >= 20);
  for (const [name, args] of calls) {
    assert.ok(schemas.has(name), `${name} is not in the screens/rollback/customfields catalogs`);
    const validate = ajv.getSchema(name) ?? (ajv.addSchema(schemas.get(name), name), ajv.getSchema(name));
    assert.equal(validate(args), true, `${name} ${JSON.stringify(args)}: ${JSON.stringify(validate.errors)}`);
  }
});
