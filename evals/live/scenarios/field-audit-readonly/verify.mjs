export default async function verify({ prepared, trace, conversation, tool }) {
  const checks = [];
  const writes = trace.filter((entry) => entry.tool === 'start_operation' || entry.arguments?.dryRun !== undefined);
  checks.push({ name: 'только чтение: ни превью, ни apply', ok: writes.length === 0, detail: writes.map((entry) => `#${entry.seq} ${entry.tool}`).join(', ') || `${trace.length} вызовов чтения` });

  const firstAnswer = conversation.find((turn) => turn.role === 'assistant')?.content ?? '';
  const missing = [prepared.selectField, prepared.textField].filter((id) => !firstAnswer.includes(id));
  checks.push({ name: 'первый ответ показывает оба поля с одним названием (по id)', ok: missing.length === 0, detail: missing.length ? `нет id: ${missing.join(', ')}` : 'оба id показаны' });

  checks.push({ name: 'экраны проекта прочитаны (get_screen_contents)', ok: trace.some((entry) => entry.tool === 'get_screen_contents'), detail: `${trace.filter((entry) => entry.tool === 'get_screen_contents').length} вызовов` });

  const select = await tool('get_field_contexts', { field: prepared.selectField });
  const text = await tool('get_field_contexts', { field: prepared.textField });
  checks.push({ name: 'контексты полей не изменились',
    ok: select.contexts.length === 1 && !select.contexts[0].allProjects && text.contexts.length === 1 && text.contexts[0].allProjects,
    detail: `список: ${select.contexts.length} контекст, allProjects=${select.contexts[0]?.allProjects}; текст: ${text.contexts.length}, allProjects=${text.contexts[0]?.allProjects}` });
  return checks;
}
