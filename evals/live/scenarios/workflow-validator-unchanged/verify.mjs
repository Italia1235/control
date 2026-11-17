import { applies } from '../../checks.mjs';
import { GET, PUBLISH, check, configurePreviews, foreignToolCalls, revisionsFresh, snapshotOf } from '../../validators-common.mjs';

export default async function verify({ prepared, trace, conversation, tool }) {
  const { workflow } = prepared;
  const previews = configurePreviews(trace);
  const lastReply = conversation.filter((c) => c.role === 'assistant').at(-1)?.content ?? '';
  const checks = [
    revisionsFresh(trace),
    check('preview (если вызван) даёт UNCHANGED без planId; read-only ответ без preview тоже допустим',
      previews.every(({ result }) => result.status === 'UNCHANGED' && !result.planId),
      JSON.stringify(previews.map(({ result }) => `${result.status}${result.planId ? '+plan' : ''}`)) + ` (preview: ${previews.length})`),
    check('вызов не повторён (не больше одного preview configure)', previews.length <= 1, `preview: ${previews.length}`),
    check('нет start_operation и публикаций', applies(trace).length === 0 && applies(trace, PUBLISH).length === 0, `apply: ${applies(trace).length}`),
    check('нет edit_workflow, configure_workflow с create и других правок', foreignToolCalls(trace).length === 0, `лишних вызовов: ${foreignToolCalls(trace).length}`),
    check('ассистент честно говорит, что ничего не менялось / поля уже обязательны',
      /ничего\s+не\s+(?:менял|измен|запис)|уже\s+обязательн|без\s+изменен|изменений\s+не\s+потребовал/iu.test(lastReply), `«${lastReply.slice(0, 240)}»`),
    check('ассистент не просит подтверждения применить (нет вопроса «применить?»)', !/примен\w+\s*\?|продолж\w+\s*\?/iu.test(lastReply), `«${lastReply.slice(-160)}»`),
  ];
  const after = await tool(GET, { workflow, version: 'ACTIVE' });
  checks.push(check('состояние workflow не изменилось (revision и валидаторы)', after.revision === prepared.revision
    && JSON.stringify(snapshotOf(after)) === JSON.stringify(prepared.snapshot), `revision ${prepared.revision} → ${after.revision}`));
  const state = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  checks.push(check('draft не создан, workflow неактивен', !state.hasDraft && !state.active, `active=${state.active}, hasDraft=${state.hasDraft}`));
  return checks;
}
