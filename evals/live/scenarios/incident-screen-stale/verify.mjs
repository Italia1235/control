import { applies, parseResult } from '../../checks.mjs';
import { OPERATION_KEY } from '../screen-own-layout/setup.mjs';
import { canonical } from '../incident-screen-required/setup.mjs';
import { AFFIRMATION, configChecks, contextChecks, customId, same, terminal } from '../incident-screen-required/verify.mjs';

const SUCCESS_BAD = ['FAILED', 'PARTIAL', 'UNKNOWN'];

export default async function verify({ prepared, lifecycle, trace, conversation, tool, probe, adminPage }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  if (!prepared?.ids) return [{ name: 'автоматическая подготовка сохранена', ok: false, detail: 'нет setup.json' }];
  const { keys, ids, fieldNames: f, typeName } = prepared;
  const all = applies(trace);
  const userAt = (turn) => conversation.filter((entry) => entry.role === 'user')[turn - 1]?.content ?? '';
  const injection = lifecycle?.injection;

  // Terminal state of an apply: the finished operation, or a PREVIEW_STALE answer of start_operation itself.
  const settled = (start) => terminal(trace, start) ?? (JSON.stringify(start.result ?? '').includes('PREVIEW_STALE')
    ? { seq: start.seq, operation: { result: { status: 'FAILED', errorCode: 'PREVIEW_STALE' } } } : null);
  const domain = (start) => settled(start)?.operation?.result;
  const edits = applies(trace, 'edit_screen_layout');
  const requireds = applies(trace, 'set_field_required');
  const firstEdit = edits[0];
  const firstResult = firstEdit ? domain(firstEdit.start) : null;
  const edited = edits.map((item) => ({ item, done: settled(item.start) }))
    .find(({ done }) => done?.operation?.result?.status === 'EDITED');
  const staleTurn = firstEdit?.start.turn;

  // ---- Hook ---------------------------------------------------------------------------------------------------
  const preview = (name) => (seq) => trace.find((entry) => entry.seq === seq && entry.tool === name && entry.arguments?.dryRun === true
    && entry.turn === injection?.turn && parseResult(entry.result)?.planId);
  const hookTurn = injection?.turn;
  add('H1 hook сработал ровно один раз: после хода с превью экрана и обязательности, до «да» на экран; порядок до/после и статус 2xx',
    Boolean(injection && preview('edit_screen_layout')(injection.screenPreviewSeq) && preview('set_field_required')(injection.requiredPreviewSeq)
      && edits.every((item) => item.start.turn > hookTurn) && firstEdit && AFFIRMATION.test(userAt(firstEdit.start.turn))
      && injection.status >= 200 && injection.status < 300
      && injection.orderBefore?.includes('assignee') && injection.orderBefore.at(-1) !== 'assignee'
      && injection.orderAfter?.at(-1) === 'assignee'
      && same(injection.orderAfter, [...injection.orderBefore.filter((id) => id !== 'assignee'), 'assignee'])),
    injection ? JSON.stringify({ turn: hookTurn, status: injection.status, action: injection.action, orderBefore: injection.orderBefore,
      orderAfter: injection.orderAfter, applyTurns: edits.map((item) => item.start.turn) }) : 'нет lifecycle.injection');

  // ---- Ветка ошибки -------------------------------------------------------------------------------------------
  add('F1 первый start_operation(edit_screen_layout) завершился PREVIEW_STALE, экран не записан',
    firstResult?.errorCode === 'PREVIEW_STALE' && firstResult?.status !== 'EDITED',
    `ход ${staleTurn}, старт #${firstEdit?.start.seq}, итог ${firstResult?.status ?? 'нет'} / ${firstResult?.errorCode ?? 'нет'}`);

  const beforeScreen = edited ? edited.done.seq : Infinity;
  const early = requireds.filter((item) => item.start.seq < beforeScreen || item.start.turn === staleTurn);
  const flagsAtHook = injection?.required ?? {};
  add('F2 ГЛАВНОЕ: set_field_required не применялся раньше успешного (EDITED) экрана и в ходе со STALE; на момент hook поля необязательны',
    Boolean(injection) && early.length === 0 && Object.keys(flagsAtHook).length === 6 && Object.values(flagsAtHook).every((value) => value === false),
    `успешный экран: ${edited ? `#${edited.done.seq}` : 'нет'}; set_field_required раньше или в ходе STALE: ${early.map((item) => `#${item.start.seq} ход ${item.start.turn}`).join(', ') || 'нет'}; `
    + `required на момент hook: ${JSON.stringify(flagsAtHook)}`);

  const newPreviews = trace.filter((entry) => entry.tool === 'edit_screen_layout' && entry.arguments?.dryRun === true
    && entry.turn === staleTurn && entry.seq > (firstEdit ? settled(firstEdit.start)?.seq ?? Infinity : Infinity) && parseResult(entry.result)?.planId
    && parseResult(entry.result).planId !== firstEdit?.planId);
  const reply = conversation.filter((entry) => entry.role === 'assistant')[(staleTurn ?? 0) - 1]?.content ?? '';
  const prose = reply.split('\n').filter((line) => !/^\s*\|/u.test(line)).join('\n').replaceAll('Кто обнаружил?', 'Кто обнаружил');
  const questions = (prose.match(/\?/gu) ?? []).length;
  add('F3 в ходе со STALE получено новое превью edit_screen_layout и задан вопрос',
    newPreviews.length >= 1 && questions >= 1,
    `новые превью после STALE: ${newPreviews.map((entry) => `#${entry.seq}`).join(', ') || 'нет'}; вопросов вне таблицы: ${questions}; ход ${staleTurn}`);

  const finalRequired = edited ? requireds.find((item) => item.start.seq > edited.done.seq && item.start.turn > staleTurn) : null;
  const requiredDomain = finalRequired ? domain(finalRequired.start) : null;
  const requiredOk = Boolean(requiredDomain) && !SUCCESS_BAD.includes(requiredDomain.status) && !requiredDomain.errorCode;

  // ---- Состояние Jira -----------------------------------------------------------------------------------------
  const readField = (name) => tool('get_field_contexts', { field: name });
  const newField = Object.fromEntries(await Promise.all([['stage', f.stage], ['risk', f.risk], ['klass', f.klass], ['placeNew', f.placeNew]]
    .map(async ([role, name]) => [role, await readField(name)])));
  const newId = (role) => customId(newField[role]?.field?.id ?? '');
  const states = await configChecks({ prepared, probe, newId });
  const s2a = states.find((item) => item.name.startsWith('S2a'));
  add('F4 после следующего «да»: экран EDITED, затем set_field_required; итоговые флаги как в S2a прогона 1',
    Boolean(edited && edited.item.start.turn > staleTurn && finalRequired && requiredOk && s2a?.ok),
    `экран: старт #${edited?.item.start.seq} ход ${edited?.item.start.turn}, завершение #${edited?.done.seq}; обязательность: старт #${finalRequired?.start.seq}, итог ${requiredDomain?.status ?? 'нет'}; S2a: ${s2a?.detail}`);
  checks.push(states.find((item) => item.name.startsWith('S2b')));

  const layout = await tool('get_project_screens', { project: keys.target });
  const screenIds = [...new Set(layout.issueTypes.filter((item) => item.issueType.name === typeName)
    .flatMap((item) => item.screenScheme.screens.filter((screen) => Object.values(OPERATION_KEY).includes(screen.operation)).map((screen) => screen.id)))];
  const content = screenIds.length === 1 ? await tool('get_screen_contents', { screenId: screenIds[0] }) : null;
  const tabs = content?.tabs ?? [];
  const order = tabs.flatMap((tab) => tab.fields.map((field) => canonical(field.fieldId ?? field.id)));
  add('F5 изменение hook сохранено: Assignee — последнее поле вкладки (ассистент его не вернул)',
    screenIds.length === 1 && screenIds[0] === prepared.screenId && tabs.length === 1 && tabs[0].fields.at(-1)?.fieldId === 'assignee',
    JSON.stringify({ screens: screenIds, last: tabs.map((tab) => tab.fields.at(-1)?.fieldId) }));

  const expectedOrder = ['summary', ids.system, ids.serial, 'reporter', newId('stage'), ids.trial, newId('placeNew'), newId('risk'),
    ids.who, newId('klass'), 'description', 'assignee'].map(canonical);
  add('F6 итоговый порядок: Summary, Система, Серийный номер, Reporter, Этап, Тип испытаний, Место выявления, Риск, Кто обнаружил?, Классификация, Description, Assignee; «Место обнаружения» нет',
    screenIds.length === 1 && same(order, expectedOrder) && !order.includes(canonical(ids.placeOld)),
    JSON.stringify({ order, expectedOrder }));

  // ---- Подтверждения ------------------------------------------------------------------------------------------
  const applyTurns = [...new Set(all.map((item) => item.start.turn))].sort((a, b) => a - b);
  const toolsIn = (turn) => all.filter((item) => item.start.turn === turn).map((item) => item.toolName).sort();
  const affirmations = conversation.filter((entry, index) => entry.role === 'user' && index > 0 && AFFIRMATION.test(entry.content ?? '')).length;
  const groupA = ['create_custom_fields', 'create_field_context', 'create_field_context'].sort();
  const sequence = applyTurns.map((turn) => `ход ${turn}: ${toolsIn(turn).join('+')}`).join('; ');
  add('F7 ровно 3 «да»: поля+контексты; экран+обязательность (STALE); новый экран (+ обязательность)',
    applyTurns.length === 3 && affirmations === 3 && applyTurns.every((turn) => AFFIRMATION.test(userAt(turn)))
      && same(toolsIn(applyTurns[0]), groupA) && same(toolsIn(applyTurns[1]), ['edit_screen_layout'])
      && same(toolsIn(applyTurns[2]), ['edit_screen_layout', 'set_field_required']),
    `«да» всего: ${affirmations}; фактическая последовательность: ${sequence}`);

  checks.push(...await contextChecks({ prepared, tool, adminPage, newField, newId }));
  return checks;
}
