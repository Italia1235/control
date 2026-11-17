import { createEmptyGroup, requireRoles, requireStatuses } from '../../configure-common.mjs';

// configure_workflow create. The only object setup creates is the fresh EMPTY group «LOCAL-008-group-<RUN>» (no membership is ever
// changed). Existing statuses, global roles and system fields are only read; the workflow, its rules and its screen are
// created by the assistant in ONE configure_workflow plan, so nothing of them exists yet (names carry the fresh run id).
export const ROLE_NAMES = ['Administrators', 'Service Desk Team'];
export const STATUS_NAMES = ['To Do', 'In Progress'];
export const SCREEN_FIELDS = ['summary', 'description'];

export default async function setup({ runId, tool, rest, log }) {
  const [todo, progress] = await requireStatuses(tool, STATUS_NAMES);
  const roles = await requireRoles(rest, ROLE_NAMES);
  const fields = await rest('GET', '/rest/api/2/field');
  const missing = SCREEN_FIELDS.filter((id) => !fields.some((field) => field.id === id));
  if (missing.length) throw new Error(`Нет системных полей: ${missing.join(', ')}`);
  const group = await createEmptyGroup(rest, `LOCAL-008-group-${runId}`);
  const workflow = `LOCAL-008-create-${runId}`, screenName = `LOCAL-008-screen-${runId}`;
  log(`группа «${group}» (пустая), роли ${roles.map((r) => `${r.name}=${r.id}`).join(', ')}, статусы ${todo.name}=${todo.id}, ${progress.name}=${progress.id}`);
  return { workflow, group, roles, statuses: { todo, progress }, screenName, screenFieldIds: SCREEN_FIELDS, transition: 'Start',
    known: { workflow, group, roles: ROLE_NAMES, statuses: STATUS_NAMES, screen: screenName, screenFields: ['Summary', 'Description'] } };
}
