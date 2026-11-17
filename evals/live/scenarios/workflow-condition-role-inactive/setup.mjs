import { createConditionsWorkflow, requirePopulatedGroups, requireRoles } from '../../conditions-common.mjs';

// Fresh inactive workflow «LOCAL-006-role-<RUN>» whose Finish already carries a group condition and a project
// role condition of ANOTHER role (both seeded with Control's own configure tool). Existing groups and roles are only read.
export const SEED_GROUP = 'jira-administrators';
export const TARGET_ROLES = ['Administrators', 'Service Desk Team'];
export const SEED_ROLE = 'Service Desk Customers';

export default async function setup(context) {
  const { runId, rest } = context;
  await requirePopulatedGroups(rest, [SEED_GROUP]);
  const [seedRole] = await requireRoles(rest, [SEED_ROLE]);
  const targetRoles = await requireRoles(rest, TARGET_ROLES);
  const workflow = `LOCAL-006-role-${runId}`;
  const delivery = await createConditionsWorkflow(context, {
    workflow, needScriptRunner: true,
    seeds: [
      { transition: 'Finish', template: 'group', parameters: { group: SEED_GROUP } },
      { transition: 'Finish', template: 'project_role', parameters: { roles: [seedRole.name] } },
    ],
  });
  const finish = delivery.snapshot.find((t) => t.actionId === delivery.actionIds.Finish);
  const kinds = finish.root?.children?.map((c) => c.kind).join(',');
  if (finish.root?.operator !== 'AND' || kinds !== 'GROUP,PROJECT_ROLE') {
    throw new Error(`Исходные conditions Finish не такие, как задумано: ${JSON.stringify(finish.root)}`);
  }
  return { ...delivery, seedGroup: SEED_GROUP, seedRole, targetRoles,
    known: { workflow, transitions: Object.keys(delivery.actionIds), roles: TARGET_ROLES } };
}
