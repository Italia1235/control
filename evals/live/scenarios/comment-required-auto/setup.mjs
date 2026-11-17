import { pickStatuses } from '../../validators-common.mjs';
import { exportXml } from '../../comment-validators-common.mjs';

/**
 * Fully automatic required-comment fixture: fresh inactive workflow, fresh screen(s) without Comment.
 * Created only with Control's own tools (create_screen, configure_workflow create); no XML/REST bypass.
 * `screens` = number of fresh screens (the first one is assigned to "Start work").
 */
export async function prepareAuto({ runId, tool, apply, adminPage, log }, { screens = 1 } = {}) {
  const { start, working, done } = await pickStatuses(tool);
  const resolutions = await tool('get_resolutions', {});
  const resolution = resolutions.find((r) => /^(fixed|done|completed|resolved|готово|выполнено)$/iu.test(r.name)) ?? resolutions[0];
  if (!resolution) throw new Error('Нет существующей Resolution');
  const workflow = `LOCAL-005-auto-${runId}`;
  const screenList = [];
  for (let i = 1; i <= screens; i += 1) {
    const name = `LOCAL-005-auto-screen${i}-${runId}`;
    const result = await apply('create_screen', { name, fields: [{ name: 'summary' }] });
    if (!result.screenId) throw new Error(`create_screen не вернул screenId: ${JSON.stringify(result)}`);
    screenList.push({ id: result.screenId, name });
  }
  await apply('configure_workflow', { create: {
    name: workflow,
    statuses: [start, working, done].map(({ name, category }) => ({ name, category })),
    initialStatus: start.name,
    transitions: [
      { key: 'start-work', name: 'Start work', from: start.name, to: working.name, screen: String(screenList[0].id) },
      { key: 'pause', name: 'Pause', from: working.name, to: start.name },
      { key: 'finish', name: 'Finish', from: working.name, to: done.name, resolution: { action: 'SET', value: resolution.id } },
    ],
  } });
  const view = await tool('get_workflow_validators', { workflow, version: 'ACTIVE' });
  const state = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  if (state.active || state.hasDraft || state.consumers.projectCount !== 0 || state.consumers.schemeCount !== 0)
    throw new Error('Фикстура должна быть неактивной, без draft и потребителей');
  const byName = (n) => view.transitions.find((t) => t.name === n);
  const target = byName('Start work'), noScreen = byName('Pause');
  if (String(target?.screenId) !== String(screenList[0].id) || noScreen?.screenId) throw new Error('Экран назначен не так, как задумано');
  const xml = await exportXml(adminPage, workflow);
  log(`workflow «${workflow}», экраны ${screenList.map((s) => `${s.id} «${s.name}»`).join(', ')}, Start work=${target.actionId}, Pause=${noScreen.actionId}`);
  return { workflow, screens: screenList, screenId: screenList[0].id, screenName: screenList[0].name,
    actionId: target.actionId, noScreenActionId: noScreen.actionId, xml, revision: view.revision,
    known: { workflow, screens: screenList.map((s) => s.name), transition: 'Start work', noScreenTransition: 'Pause' } };
}

export default (context) => prepareAuto(context);
