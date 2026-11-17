import { createConditionsWorkflow, createEmptyGroup, requirePopulatedGroups, requireRoles } from '../../conditions-common.mjs';

// Fresh inactive workflow «LOCAL-006-group-<RUN>» whose Finish already carries a group and a project-role
// condition (seeded with Control's own configure tool), a fresh EMPTY group «LOCAL-006-empty-<RUN>» (the only object
// besides the workflow that is created; no membership is touched) and two existing populated groups that are only read.
export const SEED_GROUP = 'jira-administrators';
export const TARGET_GROUP = 'jira-software-users';

export default async function setup(context) {
  const { runId, rest, log } = context;
  await requirePopulatedGroups(rest, [SEED_GROUP, TARGET_GROUP]);
  const [seedRole] = await requireRoles(rest, ['Administrators']);
  const emptyGroup = await createEmptyGroup(rest, `LOCAL-006-empty-${runId}`);
  log(`группа «${emptyGroup}» создана (пустая)`);
  const workflow = `LOCAL-006-group-${runId}`;
  const delivery = await createConditionsWorkflow(context, {
    workflow,
    seeds: [
      { transition: 'Finish', template: 'group', parameters: { group: SEED_GROUP } },
      { transition: 'Finish', template: 'project_role', parameters: { roles: [seedRole.name] } },
    ],
  });
  const finish = delivery.snapshot.find((t) => t.actionId === delivery.actionIds.Finish);
  const start = delivery.snapshot.find((t) => t.actionId === delivery.actionIds['Start work']);
  const kinds = finish.root?.children?.map((c) => c.kind).join(',');
  if (finish.root?.operator !== 'AND' || kinds !== 'GROUP,PROJECT_ROLE' || start.root !== null) {
    throw new Error(`Исходные conditions не такие, как задумано: Finish=${JSON.stringify(finish.root)}, Start work=${JSON.stringify(start.root)}`);
  }
  return { ...delivery, emptyGroup, seedGroup: SEED_GROUP, targetGroup: TARGET_GROUP, seedRole,
    known: { workflow, transitions: Object.keys(delivery.actionIds), emptyGroup, populatedGroup: TARGET_GROUP } };
}
