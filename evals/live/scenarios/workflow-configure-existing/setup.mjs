import {
  actionIdsOf, createEmptyGroup, createScreen, doneResolution, exportXml, readState, requireRoles, requireStatuses, screenFields,
} from '../../configure-common.mjs';
import { requirePopulatedGroups } from '../../conditions-common.mjs';

// configure_workflow existing. Fresh objects only, all but the group created with Control's own tools:
//  - the existing screen «LOCAL-008-form-<RUN>» (Summary only), assigned to Pause;
//  - the empty group «LOCAL-008-group-<RUN>» (REST; no membership is ever changed);
//  - the inactive workflow «LOCAL-008-existing-<RUN>»: Start work (local) and Finish (global fromAny → Done) each carry a
//    foreign required_fields validator and a foreign AND of two conditions (group jira-administrators, role Service Desk
//    Customers); Pause (local) carries the same two conditions and the existing screen.
// Existing statuses, global roles and the populated group jira-administrators are only read.
export const STATUS_NAMES = ['To Do', 'In Progress', 'Done'];
export const SEED_GROUP = 'jira-administrators';
export const SEED_ROLE = 'Service Desk Customers';
export const TARGET_ROLES = ['Administrators', 'Service Desk Team'];

export default async function setup({ runId, tool, apply, rest, adminPage, log }) {
  const [todo, progress, done] = await requireStatuses(tool, STATUS_NAMES);
  await requirePopulatedGroups(rest, [SEED_GROUP]);
  const [seedRole] = await requireRoles(rest, [SEED_ROLE]);
  const targetRoles = await requireRoles(rest, TARGET_ROLES);
  const fixed = await doneResolution(tool);
  const group = await createEmptyGroup(rest, `LOCAL-008-group-${runId}`);
  const formName = `LOCAL-008-form-${runId}`, workflow = `LOCAL-008-existing-${runId}`;
  const existingScreen = await createScreen(apply, formName, ['summary']);
  const foreign = {
    addConditions: { operator: 'AND', rules: [{ template: 'group', parameters: { group: SEED_GROUP } }, { template: 'project_role', parameters: { roles: [seedRole.name] } }] },
  };
  await apply('configure_workflow', {
    create: {
      name: workflow, initialStatus: todo.name,
      statuses: [todo, progress, done].map(({ name, category }) => ({ name, category })),
      transitions: [
        { key: 'start', name: 'Start work', from: todo.name, to: progress.name },
        { key: 'finish', name: 'Finish', fromAny: true, to: done.name, resolution: { action: 'SET', value: fixed.id } },
        { key: 'pause', name: 'Pause', from: progress.name, to: todo.name, screen: existingScreen.id },
      ],
    },
    transitionRules: [
      { transitionKey: 'start', addValidators: [{ template: 'required_fields', parameters: { fieldIds: ['assignee'] } }], ...foreign },
      { transitionKey: 'finish', addValidators: [{ template: 'required_fields', parameters: { fieldIds: ['assignee', 'description'] } }], ...foreign },
      { transitionKey: 'pause', ...foreign },
    ],
  });
  const state = await readState(tool, workflow);
  const actions = actionIdsOf(state);
  const seedChildren = [{ kind: 'GROUP', group: SEED_GROUP },
    { kind: 'PROJECT_ROLE', roleIds: [seedRole.id], roles: [{ id: seedRole.id, name: seedRole.name }], inverted: false }];
  const expectConditions = { kind: 'COMPOUND', operator: 'AND', children: seedChildren };
  const ok = state.active === false && state.hasDraft === false && state.consumers.projectCount === 0 && state.consumers.schemeCount === 0
    && JSON.stringify(state.validators[actions['Start work']]) === JSON.stringify([{ kind: 'REQUIRED_FIELDS', fieldIds: ['assignee'], conditional: false }])
    && JSON.stringify(state.validators[actions.Finish]) === JSON.stringify([{ kind: 'REQUIRED_FIELDS', fieldIds: ['assignee', 'description'], conditional: false }])
    && state.validators[actions.Pause].length === 0
    && Object.values(actions).every((id) => JSON.stringify(state.conditions[id]) === JSON.stringify(expectConditions))
    && state.screens[actions['Start work']] === null && state.screens[actions.Finish] === null && String(state.screens[actions.Pause]) === existingScreen.id
    && state.graph.find((t) => t.actionId === actions.Finish)?.kind === 'GLOBAL';
  if (!ok) throw new Error(`Исходное состояние workflow не такое, как задумано: ${JSON.stringify({ actions, validators: state.validators, conditions: state.conditions, screens: state.screens })}`);
  const xml = await exportXml(adminPage, workflow);
  const formContents = await screenFields(tool, existingScreen.id);
  log(`workflow «${workflow}» ${JSON.stringify(actions)}; экран «${formName}» ${existingScreen.id}; группа «${group}»`);
  return { workflow, group, formName, existingScreen, actions, seedGroup: SEED_GROUP, seedRole, targetRoles, fixed, statuses: { todo, progress, done },
    state, xml, formContents,
    known: { workflow, transitions: Object.keys(actions), group, roles: TARGET_ROLES, newScreen: formName, screenFields: ['Summary', 'Assignee'] } };
}
