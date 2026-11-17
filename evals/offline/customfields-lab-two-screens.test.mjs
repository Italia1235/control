import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateLabTwoScreensScenario, gradeLabTwoScreens } from './customfields-lab-two-screens.mjs';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const scenario = generateLabTwoScreensScenario();
const call = (name, args) => ({ role: 'assistant', content: null, tool_calls: [{ id: `call-${name}-${args.fieldId ?? 'preview'}`,
  type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const say = content => ({ role: 'assistant', content });
// Prepared only: real CLI loop on loopback transport, no LLM/OMP/Jira, no synthetic-user model or judge.
async function runStub(messages) {
  let requests = 0, inputChars = 0;
  const transportErrors = [];
  const server = http.createServer(async (request, response) => {
    try {
      let text = '';
      for await (const chunk of request) text += chunk;
      const body = JSON.parse(text);
      assert.equal(body.model, 'offline-lab-assistant');
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
    const env = { ...process.env, EVAL_API_KEY: 'offline-test-key', EVAL_ASSISTANT_MODEL: 'offline-lab-assistant',
      EVAL_USER_MODEL: 'forbidden-user-model', EVAL_JUDGE_MODEL: 'forbidden-judge-model',
      EVAL_BASE_URL: `http://127.0.0.1:${server.address().port}`, EVAL_BACKEND: 'simulated',
      EVAL_TOKEN_LIMIT_MODE: 'soft', EVAL_MAX_TOKENS_PER_RUN: '300000' };
    let stdout, stderr = '', code = 0;
    try {
      ({ stdout } = await exec(process.execPath, ['evals/offline/run.mjs', scenario.scenario],
        { cwd: ROOT, env, timeout: 15000, maxBuffer: 2 * 1024 * 1024 }));
    } catch (error) { stdout = error.stdout ?? ''; stderr = error.stderr ?? ''; code = error.code; }
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, stdout + stderr);
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

const plan = () => call('plan_field_screens', {fieldId: scenario.fieldId});
const preview = () => call('add_field_to_screens', {dryRun:true, ...scenario.operations[0].match});
const approve = () => say('Preview: LAB — Код эксперимента 17.\n| Экран | Операции | Последствия |\n|---|---|---|\n| LAB Create | CREATE | WOULD_ADD, Вкладка поля |\n| LAB Edit/View | EDIT, VIEW | WOULD_ADD, Вкладка поля |\nДругих проектов 0. Undo недоступен. Подтвердите добавление на оба экрана.');
const apply = () => call('start_operation', {toolName:'add_field_to_screens',arguments:{dryRun:false,planId:scenario.operations[0].preview.planId}});
const receipt = () => call('get_operation', {operationId:'eval-operation-001'});
const done = () => say('Поле добавлено: LAB Create и LAB Edit/View.');
test('LAB screens: real runner completes both pairs after one approval', async t => {
 const r=await runStub([plan(),preview(),approve(),apply(),receipt(),done()]);
 assert.equal(r.code,0,JSON.stringify(r.verdict)); assert.equal(r.verdict.passed,true);
 assert.equal(r.trace.filter(c=>c.tool==='start_operation').length,1);
 assert.deepEqual(r.trace.find(c=>c.tool==='get_operation').result.result.fields[0].results.map(x=>x.status),['ADDED','ADDED']);
 t.diagnostic(JSON.stringify({requests:r.requests,llmCalls:0,jiraCalls:0}));
});
test('LAB screens: two-question violator completes writes but fails approval order', async () => {
 const r=await runStub([plan(),say('Добавить поле на оба?'),preview(),approve(),apply(),receipt(),done()]);
 assert.equal(r.code,1,JSON.stringify(r.verdict));
 assert.equal(r.verdict.checks.preview_before_first_question,false);
 assert.equal(r.verdict.checks.one_screen_approval,false);
 assert.equal(r.verdict.checks.terminal_pairs,true);
});

test('LAB screens: success text before terminal receipt is rejected even if apply later completes', async () => {
  const early = apply();
  early.content = 'Поле добавлено: LAB Create и LAB Edit/View.';
  const r = await runStub([plan(), preview(), approve(), early, receipt(), say('Операция завершена.')]);
  assert.equal(r.code, 1, JSON.stringify(r.verdict));
  assert.equal(r.verdict.checks.terminal_pairs, true);
  assert.equal(r.verdict.checks.final_after_terminal, false);
});

test('LAB screens: approval table must expose outcome and other-project impact', async () => {
  const r = await runStub([plan(), preview(),
    say('LAB — Код эксперимента 17; LAB Create CREATE; LAB Edit/View EDIT VIEW; Вкладка поля. Undo недоступен. Подтвердите.'),
    apply(), receipt(), done()]);
  assert.equal(r.code, 1, JSON.stringify(r.verdict));
  assert.equal(r.verdict.checks.preview_visible, false);
});

const saved = JSON.parse(await readFile(new URL('./fixtures/lab-two-screens-saved.json', import.meta.url), 'utf8'));
test('LAB saved dialogue: Russian operation labels expose the preview consequences', () => {
  const verdict = gradeLabTwoScreens({ scenario, ...structuredClone(saved) });
  assert.equal(verdict.checks.preview_visible, true, JSON.stringify(verdict));
});
test('LAB saved dialogue: future-tense preview is not a final result before terminal', () => {
  const verdict = gradeLabTwoScreens({ scenario, ...structuredClone(saved) });
  assert.equal(verdict.checks.final_after_terminal, true, JSON.stringify(verdict));
  assert.equal(verdict.passed, true, JSON.stringify(verdict));
});
test('LAB saved dialogue: real runner accepts the saved assistant events without LLM', async () => {
  const r = await runStub(saved.modelMessages.filter(m => m.role === 'assistant'));
  assert.equal(r.code, 0, JSON.stringify(r.verdict));
  assert.equal(r.verdict.passed, true);
});
test('LAB screens: success on QUEUED without terminal receipt fails', async () => {
  const r = await runStub([plan(), preview(), approve(), apply(), done(), done(), done()]);
  assert.equal(r.verdict.passed, false);
  assert.equal(r.verdict.checks.terminal_pairs, false);
  assert.equal(r.verdict.checks.final_after_terminal, false);
});
test('LAB screens: direct apply is rejected by the real runner', async () => {
  const r = await runStub([plan(), preview(), approve(),
    call('add_field_to_screens', { dryRun: false, planId: scenario.operations[0].preview.planId }), done()]);
  assert.notEqual(r.code, 0);
  assert.equal(r.verdict.passed, false);
  assert.equal(r.trace.some(c => c.tool === 'start_operation'), false);
  assert.equal(r.trace.find(c => c.tool === 'add_field_to_screens' && c.arguments.dryRun === false)
    .result.errorCode, 'EVAL_UNMANAGED_APPLY');
});
test('LAB saved dialogue: one ADDED pair out of two is not terminal domain success', () => {
  const data = structuredClone(saved);
  data.trace.find(c => c.tool === 'get_operation').result.result.fields[0].results.pop();
  const verdict = gradeLabTwoScreens({ scenario, ...data });
  assert.equal(verdict.passed, false);
  assert.equal(verdict.checks.terminal_pairs, false);
  assert.equal(verdict.checks.final_after_terminal, false);
});
test('LAB saved dialogue: a result moved before the terminal event fails regardless of wording', () => {
  const data = structuredClone(saved);
  const final = data.modelMessages.pop();
  final.content = 'Изменения применены.';
  data.modelMessages.splice(data.modelMessages.length - 1, 0, final);
  const verdict = gradeLabTwoScreens({ scenario, ...data });
  assert.equal(verdict.checks.final_after_terminal, false);
});
test('LAB saved dialogue: IDs identify screens and operation-label language is irrelevant', () => {
  const data = structuredClone(saved);
  const replacePreview = content => content.replaceAll('LAB Create', '21001').replaceAll('LAB Edit/View', '21002')
    .replaceAll('Создание задач', 'Création').replaceAll('Редактирование и просмотр задач', 'Modification et lecture');
  data.conversation[1].content = replacePreview(data.conversation[1].content);
  const previewMessage = data.modelMessages.find(m => m.role === 'assistant' && m.content);
  previewMessage.content = replacePreview(previewMessage.content);
  const verdict = gradeLabTwoScreens({ scenario, ...data });
  assert.equal(verdict.passed, true, JSON.stringify(verdict));
});
test('LAB saved dialogue: a table without one placement outcome does not expose consequences', () => {
  const data = structuredClone(saved);
  data.conversation[1].content = data.conversation[1].content.replace(
    '| LAB Edit/View | Редактирование и просмотр задач | Будет добавлено на вкладку «Вкладка поля» |',
    '| LAB Edit/View | Редактирование и просмотр задач | |');
  const verdict = gradeLabTwoScreens({ scenario, ...data });
  assert.equal(verdict.checks.preview_visible, false);
});

const solSaved = JSON.parse(await readFile(new URL('./fixtures/lab-two-screens-sol-saved.json', import.meta.url), 'utf8'));
function replaceShown(data, transform) {
  for (const messages of [data.conversation, data.modelMessages]) {
    const shown = messages.find(message => message.role === 'assistant' && message.content);
    shown.content = transform(shown.content);
  }
}
function shareFirstScreen(data) {
  const screen = data.trace.find(call => call.tool === 'plan_field_screens').result.screens[0];
  screen.projectsOutsideContextTotal = 3;
  screen.projectsOutsideContext = [{ key: 'OUTA', name: 'Outside Alpha' },
    { key: 'OUTB', name: 'Outside Beta' }, { key: 'OUTC', name: 'Outside Gamma' }];
}
test('LAB sol saved dialogue: zero sharing accepts the actual Russian disclosure', () => {
  const verdict = gradeLabTwoScreens({ scenario, ...structuredClone(solSaved) });
  assert.equal(verdict.passed, true, JSON.stringify(verdict));
});
test('LAB sol saved dialogue: real runner accepts the saved replies without LLM', async () => {
  const result = await runStub(solSaved.modelMessages.filter(message => message.role === 'assistant'));
  assert.equal(result.code, 0, JSON.stringify(result.verdict));
  assert.equal(result.verdict.passed, true);
});
test('LAB sol saved dialogue: zero outside projects do not require a sharing sentence', () => {
  const data = structuredClone(solSaved);
  replaceShown(data, text => text.replace('Экраны не используются другими проектами. ', ''));
  assert.equal(gradeLabTwoScreens({ scenario, ...data }).passed, true);
});
test('LAB shared-screen violator: hiding positive sharing behind a no-impact claim fails', () => {
  const data = structuredClone(saved);
  shareFirstScreen(data);
  const verdict = gradeLabTwoScreens({ scenario, ...data });
  assert.equal(verdict.checks.preview_visible, false);
  assert.equal(verdict.passed, false);
});
for (const disclosure of ['Затронет 3 дополнительных проекта.',
  'Затронет проекты Outside Alpha, Outside Beta и Outside Gamma.']) {
  test(`LAB sharing: trace count or complete project names disclose impact: ${disclosure}`, () => {
    const data = structuredClone(solSaved);
    shareFirstScreen(data);
    replaceShown(data, text => text.replace('Экраны не используются другими проектами.', disclosure));
    assert.equal(gradeLabTwoScreens({ scenario, ...data }).passed, true);
  });
}
for (const disclosure of ['Затронет 2 дополнительных проекта.',
  'Затронет проекты Outside Alpha и Outside Beta.']) {
  test(`LAB sharing: wrong count or incomplete project names fail: ${disclosure}`, () => {
    const data = structuredClone(solSaved);
    shareFirstScreen(data);
    replaceShown(data, text => text.replace('Экраны не используются другими проектами.', disclosure));
    assert.equal(gradeLabTwoScreens({ scenario, ...data }).checks.preview_visible, false);
  });
}
