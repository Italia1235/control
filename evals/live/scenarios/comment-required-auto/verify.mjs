import { additionChecks, exactComment, screenOf, actionOf, exportXml, parseXml } from '../../comment-validators-common.mjs';
import { applies } from '../../checks.mjs';

export default async function verify({ prepared, trace, tool, adminPage }) {
  const out = [];
  const check = (name, ok, detail = '') => out.push({ name, ok: Boolean(ok), detail });
  const { workflow, actionId, noScreenActionId } = prepared;
  const view = await tool('get_workflow_validators', { workflow, version: 'ACTIVE' });
  const state = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  const comments = (id) => view.transitions.find((t) => t.actionId === id)?.validators.filter((v) => v.kind === 'COMMENT_REQUIRED') ?? [];
  check('Start work: ровно один COMMENT_REQUIRED', comments(actionId).length === 1, JSON.stringify(comments(actionId)));
  check('Pause и Finish без COMMENT_REQUIRED', view.transitions.filter((t) => t.actionId !== actionId).every((t) => !t.validators.some((v) => v.kind === 'COMMENT_REQUIRED')));
  check('workflow остался неактивным, без draft и потребителей',
    state.active === false && state.hasDraft === false && state.consumers.projectCount === 0 && state.consumers.schemeCount === 0,
    JSON.stringify({ active: state.active, hasDraft: state.hasDraft }));
  const xml = await exportXml(adminPage, workflow);
  for (const c of additionChecks(prepared.xml, xml, actionId)) check(c.name, c.ok, c.detail);
  const s = screenOf(actionOf(xml, actionId));
  check('экран перехода не изменён (meta + view=fieldscreen)', s.id === String(prepared.screenId) && s.view === 'fieldscreen', JSON.stringify(s));
  const pause = screenOf(actionOf(xml, noScreenActionId));
  check('Pause остался без экрана', !pause.id && !pause.view, JSON.stringify(pause));
  const mutations = applies(trace);
  check('применён ровно один план configure_workflow и больше ничего',
    mutations.length === 1 && mutations[0].toolName === 'configure_workflow', mutations.map((a) => a.toolName).join(','));
  const request = mutations[0]?.preview?.arguments ?? {}, rules = request.transitionRules ?? [];
  const added = rules[0]?.addValidators ?? [];
  check('план — configure_workflow для существующего workflow: одно правило Start work, только comment_required, экран не меняется',
    !request.create && request.existing?.workflow === workflow && rules.length === 1 && rules[0].actionId === actionId
    && added.length === 1 && added[0].template === 'comment_required'
    && (rules[0].screen === undefined || rules[0].screen === 'KEEP') && Object.keys(rules[0]).every((key) => ['actionId', 'screen', 'addValidators'].includes(key)),
    JSON.stringify(request).slice(0, 400));
  return out;
}
