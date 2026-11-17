import { createConditionsWorkflow, requirePopulatedGroups, requireRoles } from '../../conditions-common.mjs';

// Only a fresh inactive workflow «LOCAL-006-routing-<RUN>» without conditions; the group and the role of the
// requests exist already and are only read. Nothing is applied in this scenario, so nothing else is prepared.
export const GROUP = 'jira-administrators';
export const ROLE = 'Administrators';

export default async function setup(context) {
  const { runId, rest } = context;
  await requirePopulatedGroups(rest, [GROUP]);
  const [role] = await requireRoles(rest, [ROLE]);
  const workflow = `LOCAL-006-routing-${runId}`;
  const delivery = await createConditionsWorkflow(context, { workflow, needScriptRunner: true });
  if (delivery.snapshot.some((t) => t.root !== null)) throw new Error('У фикстуры не должно быть conditions');
  return { ...delivery, group: GROUP, role,
    known: { workflow, transitions: Object.keys(delivery.actionIds), group: GROUP, role: ROLE } };
}
