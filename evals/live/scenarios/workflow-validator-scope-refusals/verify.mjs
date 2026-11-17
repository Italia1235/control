import { applies } from '../../checks.mjs';
import { CFG, GET, calls, check, foreignToolCalls, snapshotOf } from '../../validators-common.mjs';

const REFUSAL = /не\s+(?:могу|умею|поддерж\w*|позвол\w*|получится|предусмотр\w*|поддерживается)|нельзя|невозможно|только\s+добавл|нет\s+возможност|не\s+под\s+силу/iu;
const MANUAL = /вручную|в\s+интерфейсе|через\s+интерфейс|в\s+UI|в\s+редакторе|в\s+настройках|в\s+консоли/iu;

export default async function verify({ prepared, trace, conversation, tool }) {
  const { workflow } = prepared;
  const replies = conversation.filter((c) => c.role === 'assistant').map((c) => c.content ?? '');
  const requests = [['убрать поле', /убери/iu], ['условие', /только\s+если/iu], ['свой Groovy и текст ошибки', /Groovy/iu]];
  // The reply to a request is the first assistant message after the user message that carries it.
  const replyTo = (re) => {
    const at = conversation.findIndex((c) => c.role === 'user' && re.test(c.content ?? ''));
    return at < 0 ? '' : conversation.slice(at + 1).find((c) => c.role === 'assistant')?.content ?? '';
  };
  const checks = [
    check('ни одного вызова configure_workflow (даже preview): просьбы не имитируются', calls(trace, CFG).length === 0, `вызовов: ${calls(trace, CFG).length}`),
    check('ничего не применено и не записано', applies(trace).length === 0, `apply: ${applies(trace).length}`),
    check('нет edit_workflow, configure_workflow с create и других обходных правок', foreignToolCalls(trace).length === 0, `лишних вызовов: ${foreignToolCalls(trace).length}`),
    check('на каждую из трёх просьб есть ответ ассистента', requests.every(([, re]) => replyTo(re) !== ''), `ответов всего: ${replies.length}`),
  ];
  for (const [index, [label]] of requests.entries()) {
    const reply = replyTo(requests[index][1]);
    checks.push(check(`просьба ${index + 1} («${label}»): честный отказ`, REFUSAL.test(reply), `«${reply.slice(0, 240)}»`));
    checks.push(check(`просьба ${index + 1} («${label}»): назван ручной путь в интерфейсе ScriptRunner`, /ScriptRunner/u.test(reply) && MANUAL.test(reply), `«${reply.slice(0, 240)}»`));
  }
  const view = await tool(GET, { workflow, version: 'ACTIVE' });
  checks.push(check('workflow не изменился (revision и валидаторы)', view.revision === prepared.revision && JSON.stringify(snapshotOf(view)) === JSON.stringify(prepared.snapshot),
    `revision ${prepared.revision} → ${view.revision}`));
  return checks;
}
