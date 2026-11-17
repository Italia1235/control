import { applies, parseResult } from '../../checks.mjs';

export default async function verify({ names, trace, tool, rest }) {
  const project = names['KEY:proj'];
  const workflow = names['NAME:wf'];
  const checks = [];

  const configuration = await tool('get_project_workflows', { project });
  const elsewhere = configuration.issueTypes.filter((type) => type.workflow !== workflow).map((type) => `${type.issueType.name}: ${type.workflow}`);
  checks.push({ name: `все типы задач ${project} на «${workflow}»`, ok: elsewhere.length === 0, detail: elsewhere.join('; ') || `${configuration.issueTypes.length} типа` });

  const { issues } = await rest('GET', `/rest/api/2/search?jql=${encodeURIComponent(`project = ${project}`)}&fields=status&maxResults=50`);
  const statuses = issues.map((issue) => issue.fields.status.name).sort();
  checks.push({ name: 'три задачи переехали в Closed, In Work, Open', ok: JSON.stringify(statuses) === JSON.stringify(['Closed', 'In Work', 'Open']), detail: statuses.join(', ') });

  const created = applies(trace, 'configure_workflow').filter((apply) => apply.preview?.arguments?.create).at(-1);
  const assigned = applies(trace, 'assign_workflow').at(-1);
  checks.push({ name: 'создание и назначение подтверждены в разных ходах', ok: Boolean(created && assigned && created.start.turn < assigned.start.turn),
    detail: created && assigned ? `создание — ход ${created.start.turn}, назначение — ход ${assigned.start.turn}` : 'нет одного из apply' });

  const [fixed] = await tool('get_resolutions', { query: 'Fixed' });
  const transitions = parseResult(created?.preview?.result)?.transitions ?? [];
  const close = transitions.find((transition) => transition.name === 'Close');
  const reopen = transitions.find((transition) => transition.name === 'Reopen');
  checks.push({ name: 'Close ставит Fixed, Reopen очищает Resolution в применённом плане',
    ok: close?.resolution?.action === 'SET' && close.resolution.value === fixed?.id && reopen?.resolution?.action === 'CLEAR',
    detail: `Close ${JSON.stringify(close?.resolution)}, Reopen ${JSON.stringify(reopen?.resolution)}, Fixed=${fixed?.id}` });
  return checks;
}
