import { doneResolution, requireRoles } from '../../configure-common.mjs';

// Setup creates NOTHING: the three global statuses and the workflow are created by the assistant in ONE
// configure_workflow plan. It only reads what the request needs (role Administrators, field assignee, an existing Resolution)
// and proves that the fresh names «L010-<RUN>-…» are not taken yet, so a pass cannot come from a reused object.
export const TRANSITIONS = { first: 'В работу', second: 'Завершить' };

export default async function setup({ runId, tool, rest, log }) {
  const prefix = `L010-${runId}`;
  const statuses = [
    { name: `${prefix}-Бэк`, category: 'TO_DO' },
    { name: `${prefix}-Рабим`, category: 'IN_PROGRESS' },
    { name: `${prefix}-Готовченко`, category: 'DONE' },
  ];
  const workflow = `${prefix}-wf`;
  const [admin] = await requireRoles(rest, ['Administrators']);
  const fields = await rest('GET', '/rest/api/2/field');
  if (!fields.some((field) => field.id === 'assignee')) throw new Error('Нет системного поля assignee');
  const resolution = await doneResolution(tool);
  const { statuses: taken } = await tool('list_statuses', { query: prefix, limit: 100 });
  if (taken.some((status) => status.name.startsWith(prefix))) throw new Error(`Статусы с префиксом «${prefix}» уже существуют: прогон должен начинаться со свежих имён`);
  log(`префикс ${prefix}; роль Administrators=${admin.id}; resolution «${resolution.name}»=${resolution.id}; статусы не создаются`);
  return { prefix, workflow, statuses, transitions: TRANSITIONS, role: admin, resolution,
    known: { prefix, workflow, statuses: statuses.map((s) => s.name), transitions: Object.values(TRANSITIONS), role: 'Administrators', field: 'Assignee', resolution: resolution.name } };
}
