const SCREEN_READS = new Set(['plan_field_screens', 'get_project_screens', 'get_screen_contents']);
const SCREEN_WRITES = new Set(['add_field_to_screens', 'edit_screen_layout', 'remove_field_from_screens', 'create_screen',
  'clone_project_screens', 'set_project_operation_screens']);

export default async function verify({ names, trace, conversation, tool }) {
  const checks = [];
  const name = names['NAME:field'];
  const created = trace.filter((e) => e.tool === 'start_operation');
  checks.push({ name: 'поле создано одним apply', ok: created.length === 1, detail: `${created.length} start_operation` });

  const reads = trace.filter((e) => SCREEN_READS.has(e.tool));
  checks.push({ name: 'экраны не перебираются (нет plan_field_screens / get_project_screens / get_screen_contents)', ok: reads.length === 0,
    detail: reads.map((e) => `#${e.seq} ${e.tool}`).join(', ') || 'ни одного' });
  const writes = trace.filter((e) => SCREEN_WRITES.has(e.tool));
  checks.push({ name: 'экраны не менялись', ok: writes.length === 0, detail: writes.map((e) => `#${e.seq} ${e.tool}`).join(', ') || 'ни одного' });

  const assistant = conversation.filter((t) => t.role === 'assistant');
  const lastWithCreate = assistant.findLast((t) => /не видн|ни на одн|не отобража|no screen|not visible/iu.test(t.content));
  checks.push({ name: 'в отчёте сказано, что поле пока не на экранах', ok: Boolean(lastWithCreate), detail: lastWithCreate ? 'есть' : 'нет' });
  const report = assistant.find((t) => /не видн|ни на одн|не отобража|not visible/iu.test(t.content));
  const questions = (report?.content.match(/\?/gu) ?? []).length;
  const asksScreens = /экран|screen/iu.test(report?.content ?? '');
  checks.push({ name: 'в отчёте ровно один вопрос (про экраны)', ok: questions === 1 && asksScreens, detail: `вопросов: ${questions}, про экраны: ${asksScreens}` });

  const field = await tool('get_field_contexts', { field: name });
  const ctx = field.contexts ?? [];
  checks.push({ name: 'контекст глобальный', ok: ctx.length === 1 && ctx[0].allProjects === true && ctx[0].allIssueTypes === true,
    detail: JSON.stringify(ctx.map((c) => ({ allProjects: c.allProjects, allIssueTypes: c.allIssueTypes }))) });
  return checks;
}
