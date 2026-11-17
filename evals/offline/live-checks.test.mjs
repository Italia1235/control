import test from 'node:test';
import assert from 'node:assert/strict';
import { traceChecks } from '../live/checks.mjs';

const preview = (seq, turn, planId) => ({ seq, turn, tool: 'create_resolution', arguments: { dryRun: true, name: 'Done' },
  result: { status: 'WOULD_CREATE', planId } });
const apply = (seq, turn, planId) => ({ seq, turn, tool: 'start_operation',
  arguments: { toolName: 'create_resolution', arguments: { dryRun: false, planId } }, result: { status: 'QUEUED' } });
const dialogue = (...pairs) => pairs.flatMap(([user, assistant]) =>
  [{ role: 'user', content: user }, ...(assistant === undefined ? [] : [{ role: 'assistant', content: assistant }])]);
const verdict = (trace, conversation, name) => traceChecks(trace, conversation).find((item) => item.name.startsWith(name));

const clean = { trace: [preview(1, 1, 'plan-aaaa-1111'), apply(2, 2, 'plan-aaaa-1111')],
  conversation: dialogue(['создай Done', '| Изменение | Влияние |\n| --- | --- |\n| Done | Глобальная резолюция |\nСоздать?'], ['Да, применяй', 'Готово.']) };

test('a clean preview, consent and apply passes the protocol checks', () => {
  for (const item of traceChecks(clean.trace, clean.conversation)) assert.equal(item.ok, true, item.name);
});

test('any tool outside the plugin catalog fails, such as a fetch used as a timer', () => {
  const trace = [...clean.trace, { seq: 3, turn: 2, tool: 'fetch', arguments: { url: 'https://httpbin.org/delay/3' } }];
  const result = verdict(trace, clean.conversation, 'только инструменты плагина');
  assert.equal(result.ok, false);
  assert.match(result.detail, /#3 fetch/u);
});

test('a planId in an assistant reply fails; the same id in user text or tool arguments does not', () => {
  const leaked = dialogue(['создай Done', 'План plan-aaaa-1111 готов. Создать?'], ['Да', 'Готово.']);
  assert.equal(verdict(clean.trace, leaked, 'planId не показан').ok, false);
  const quoted = dialogue(['создай Done, plan-aaaa-1111', 'Создать?'], ['Да', 'Готово.']);
  assert.equal(verdict(clean.trace, quoted, 'planId не показан').ok, true);
});

test('asking again after a confirmation of the shown plan fails', () => {
  const trace = [preview(1, 1, 'plan-aaaa-1111'), apply(2, 3, 'plan-aaaa-1111')];
  const conversation = dialogue(['создай Done', 'Создать?'], ['Да, применяй', 'Точно создать?'], ['да', 'Готово.']);
  const result = verdict(trace, conversation, 'после «да»');
  assert.equal(result.ok, false);
  assert.match(result.detail, /ход 2/u);
});

test('a fresh preview after consent, a refusal and an unanswered consent are not a second question', () => {
  const stale = [preview(1, 1, 'plan-aaaa-1111'), preview(2, 2, 'plan-bbbb-2222'), apply(3, 3, 'plan-bbbb-2222')];
  const staleTalk = dialogue(['создай Done', 'Создать?'], ['Да', 'Каталог изменился, новый план. Создать?'], ['да', 'Готово.']);
  assert.equal(verdict(stale, staleTalk, 'после «да»').ok, true);
  const refused = dialogue(['создай Done', 'Создать?'], ['Нет, да не надо', 'Хорошо, не создаю.']);
  assert.equal(verdict([preview(1, 1, 'plan-aaaa-1111')], refused, 'после «да»').ok, true);
  const providerFailed = dialogue(['создай Done', 'Создать?'], ['Да, применяй']);
  assert.equal(verdict([preview(1, 1, 'plan-aaaa-1111')], providerFailed, 'после «да»').ok, true);
});

test('apply requires a table followed by actual consent, not merely a later turn', () => {
  for (const user of ['Нет', 'Покажи подробности', 'Да, не применяй']) {
    const conversation = structuredClone(clean.conversation);
    conversation[2].content = user;
    assert.equal(verdict(clean.trace, conversation, 'apply подтверждён').ok, false, user);
  }
  const conversation = structuredClone(clean.conversation);
  conversation[1].content = 'Создать?';
  assert.equal(verdict(clean.trace, conversation, 'apply подтверждён').ok, false);
});

test('a foreign plan and a fresh plan without its own table and yes are rejected', () => {
  assert.equal(verdict([preview(1, 1, 'shown'), apply(2, 2, 'foreign')], clean.conversation,
    'каждый apply идёт').ok, false);
  const trace = [preview(1, 1, 'old'), preview(2, 2, 'new'), apply(3, 2, 'new')];
  assert.equal(verdict(trace, clean.conversation, 'apply подтверждён').ok, false);
});

test('only lost start response recovery can reuse a plan, and it returns the existing operation', () => {
  const lost = { ...apply(2, 2, 'plan-aaaa-1111'), isError: true, result: null };
  const recovery = { ...apply(3, 2, 'plan-aaaa-1111'),
    result: { operationId: 'owned-operation', status: 'RUNNING', alreadyStarted: true } };
  assert.equal(verdict([clean.trace[0], lost, recovery], clean.conversation, 'planId применён').ok, true);
  assert.equal(verdict([clean.trace[0], clean.trace[1], recovery], clean.conversation, 'planId применён').ok, false);
});

test('configure classification separates missing preview table and leaked plan from consent', () => {
  const conversation = structuredClone(clean.conversation);
  conversation[1].content = 'План plan-aaaa-1111 готов. Создать?';
  const checks = traceChecks(clean.trace, conversation, { classifyPresentation: true });
  assert.ok(checks.every((c) => ['contract', 'advisory'].includes(c.class)));
  assert.deepEqual(checks.filter((c) => !c.ok).map((c) => [c.class, c.name]), [
    ['advisory', 'Markdown-таблица именно этого превью перед подтверждением'],
    ['advisory', 'planId не показан пользователю'],
  ]);
  const consent = checks.find((c) => c.name.startsWith('apply подтверждён'));
  assert.equal(consent.class, 'contract');
  assert.equal(consent.ok, true);
  assert.equal(verdict(clean.trace, conversation, 'apply подтверждён').ok, false, 'other scenarios retain the strict default');
});

test('configure classification keeps missing, negative and wrong-preview consent contract failures', () => {
  for (const user of ['Нет', 'Покажи подробности', 'Да, не применяй', '']) {
    const conversation = structuredClone(clean.conversation);
    conversation[2].content = user;
    const consent = traceChecks(clean.trace, conversation, { classifyPresentation: true }).find((c) => c.name.startsWith('apply подтверждён'));
    assert.equal(consent.class, 'contract');
    assert.equal(consent.ok, false, user);
  }
  for (const trace of [
    [preview(1, 1, 'old'), preview(2, 2, 'new'), apply(3, 2, 'new')],
    [preview(1, 1, 'shown'), apply(2, 2, 'foreign')],
    [preview(1, 2, 'plan-aaaa-1111'), apply(2, 2, 'plan-aaaa-1111')],
  ]) {
    const checks = traceChecks(trace, clean.conversation, { classifyPresentation: true });
    assert.ok(checks.some((c) => !c.ok && c.class === 'contract'), JSON.stringify(trace));
    assert.equal(checks.find((c) => c.name.startsWith('apply подтверждён')).ok, false);
  }
});

test('configure classification preserves every structural trace guard', () => {
  const trace = [
    ...clean.trace, apply(3, 3, 'plan-aaaa-1111'),
    { seq: 4, turn: 3, tool: 'configure_workflow', arguments: { dryRun: false } },
    { seq: 5, turn: 3, tool: 'fetch', arguments: {} },
  ];
  const checks = traceChecks(trace, clean.conversation, { classifyPresentation: true });
  for (const name of ['нет прямого', 'planId применён', 'только инструменты']) {
    const check = checks.find((c) => c.name.startsWith(name));
    assert.equal(check.class, 'contract');
    assert.equal(check.ok, false, name);
  }
  const reasked = traceChecks([preview(1, 1, 'p')], dialogue(['создай', 'Создать?'], ['Да', 'Точно?']), { classifyPresentation: true })
    .find((c) => c.name.startsWith('после «да»'));
  assert.equal(reasked.class, 'contract');
  assert.equal(reasked.ok, false);
});
