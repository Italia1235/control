import { actionIdsOf, exportXml, readState, requireStatuses } from '../../configure-common.mjs';

// configure_workflow stale plan. Fresh inactive workflow «LOCAL-008-stale-<RUN>» (To Do ↔ In Progress: Start work, Finish), no
// rules, no screen, created with Control's own tool. The permitted EXISTING Done status is reserved for hooks.mjs;
// no global status is created. The screen «LOCAL-008-screen-<RUN>» does not exist: the assistant's plan would create it.
export const STATUS_NAMES = ['To Do', 'In Progress', 'Done'];

export default async function setup({ runId, tool, apply, adminPage, log }) {
  const [todo, progress, spare] = await requireStatuses(tool, STATUS_NAMES);
  const workflow = `LOCAL-008-stale-${runId}`, screenName = `LOCAL-008-screen-${runId}`;
  await apply('configure_workflow', {
    create: {
      name: workflow, initialStatus: todo.name,
      statuses: [todo, progress].map(({ name, category }) => ({ name, category })),
      transitions: [
        { key: 'start', name: 'Start work', from: todo.name, to: progress.name },
        { key: 'finish', name: 'Finish', from: progress.name, to: todo.name },
      ],
    },
  });
  const state = await readState(tool, workflow);
  const actions = actionIdsOf(state);
  const initial = state.graph.filter((action) => action.kind === 'INITIAL');
  const expectedInitial = [{ kind: 'OTHER', type: 'class', className: 'com.atlassian.jira.workflow.validator.PermissionValidator' }];
  if (state.active || state.hasDraft || state.consumers.projectCount !== 0 || state.consumers.schemeCount !== 0
      || Object.keys(actions).length !== 2 || !actions['Start work'] || !actions.Finish
      || Object.values(actions).some((id) => !Array.isArray(state.validators[id]) || state.validators[id].length
        || state.conditions[id] !== null || state.screens[id] !== null)
      || initial.length !== 1 || JSON.stringify(state.validators[initial[0].actionId]) !== JSON.stringify(expectedInitial)
      || state.screens[initial[0].actionId] || state.conditions[initial[0].actionId]) {
    throw new Error(`Исходное состояние workflow не такое, как задумано: ${JSON.stringify({ actions, validators: state.validators, screens: state.screens })}`);
  }
  const xml = await exportXml(adminPage, workflow);
  log(`workflow «${workflow}» ${JSON.stringify(actions)}; запасной статус «${spare.name}»`);
  return { workflow, screenName, actions, spare, state, xml, known: { workflow, transitions: Object.keys(actions), screen: screenName } };
}
