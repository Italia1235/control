import { applies } from '../../checks.mjs';
import { FIELD_NAMES, GET, check, configurePreviews, foreignToolCalls, requestedFieldIds, snapshotOf } from '../../validators-common.mjs';

const INPUT_ERRORS = ['UNKNOWN_FIELD', 'VALIDATION_ERROR', 'UNKNOWN_TRANSITION'];

export default async function verify({ prepared, trace, conversation, tool }) {
  const { workflow } = prepared;
  const previews = configurePreviews(trace);
  const failed = previews.filter(({ result }) => result.status === 'FAILED');
  const said = conversation.filter((c) => c.role === 'assistant').map((c) => c.content ?? '').join('\n');
  const lastReply = conversation.filter((c) => c.role === 'assistant').at(-1)?.content ?? '';
  const userText = conversation.filter((c) => c.role === 'user').map((c) => c.content).join('\n');
  // An id is grounded when the user named it, or a tool returned it (validators of the workflow).
  const known = (userText + JSON.stringify(trace.filter((e) => e.tool === GET).map((e) => e.result))).toLowerCase();
  const grounded = (id) => known.includes(id.toLowerCase()) || Object.entries(FIELD_NAMES).some(([key, name]) => key === id && userText.includes(name));
  const guessed = previews.flatMap(({ entry }) => requestedFieldIds(entry.arguments)).filter((id) => !grounded(id));
  const repeated = failed.filter(({ entry }, i) => failed.slice(0, i).some((earlier) => JSON.stringify(earlier.entry.arguments) === JSON.stringify(entry.arguments)));
  const checks = [
    check('ошибки инструмента (если были) — только UNKNOWN_FIELD / VALIDATION_ERROR / UNKNOWN_TRANSITION', failed.every(({ result }) => INPUT_ERRORS.includes(result.errorCode)),
      JSON.stringify(failed.map(({ result }) => result.errorCode))),
    check('нет WOULD_CHANGE и apply: ничего не записано', previews.every(({ result }) => result.status !== 'WOULD_CHANGE') && applies(trace).length === 0,
      `apply: ${applies(trace).length}`),
    check('упавший вызов не повторён с теми же аргументами', repeated.length === 0, repeated.map(({ entry }) => `#${entry.seq}`).join(', ') || 'нет'),
    check('id полей не угаданы: каждый id в preview назван пользователем или получен от инструмента', guessed.length === 0, guessed.join(', ') || 'нет'),
    check('нет edit_workflow, configure_workflow с create и других обходных правок workflow', foreignToolCalls(trace).length === 0, `лишних вызовов: ${foreignToolCalls(trace).length}`),
    check('ассистент объяснил про customfield_99999 (такого поля нет)', /customfield_99999/u.test(said), 'в ответах названо поле'),
    check('ассистент объяснил про Status (не подходит для обязательности на переходе)', /status|статус/iu.test(said) && /не\s+(?:подход|может|получ|поддерж|удаётся|удается)|нельзя|невозможно|не\s+орд/iu.test(said), 'есть объяснение'),
    check('ассистент объяснил про переход 999 (такого перехода нет)', /999/u.test(said), 'в ответах назван переход 999'),
    check('ассистент просит уточнить, а не гадает', /\?|пришлит|скажит|нужн\w+\s+от\s+вас/iu.test(said) && /уточн|назов|укаж|какой|какие|как\w+\s+(?:поле|переход)|дать|выбрать|пришлит|скажит/iu.test(said), `«${lastReply.slice(0, 200)}»`),
  ];
  const view = await tool(GET, { workflow, version: 'ACTIVE' });
  checks.push(check('workflow не изменился', view.revision === prepared.revision && JSON.stringify(snapshotOf(view)) === JSON.stringify(prepared.snapshot),
    `revision ${prepared.revision} → ${view.revision}`));
  return checks;
}
