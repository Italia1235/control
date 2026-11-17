import { screenOf, actionOf, exportXml, parseXml } from '../../comment-validators-common.mjs';
import { applies, parseResult } from '../../checks.mjs';

export default async function verify({ prepared, trace, tool, adminPage }) {
  const out = [];
  const check = (name, ok, detail = '') => out.push({ name, ok: Boolean(ok), detail });
  const { workflow, actionId, noScreenActionId, screens } = prepared;
  const xml = await exportXml(adminPage, workflow);
  const pause = screenOf(actionOf(xml, noScreenActionId));
  check('Pause: мета экрана = screen2 и view=fieldscreen после SET → replace', pause.id === String(screens[1].id) && pause.view === 'fieldscreen', JSON.stringify(pause));
  const start = screenOf(actionOf(xml, actionId));
  check('Start work: экран 1 не тронут', start.id === String(screens[0].id) && start.view === 'fieldscreen', JSON.stringify(start));
  const same = JSON.stringify(actionOf(parseXml(xml), actionId)) === JSON.stringify(actionOf(parseXml(prepared.xml), actionId));
  check('Start work: дескриптор целиком не изменён', same);
  const view = await tool('get_workflow_validators', { workflow, version: 'ACTIVE' });
  const comments = (id) => view.transitions.find((t) => t.actionId === id)?.validators.filter((v) => v.kind === 'COMMENT_REQUIRED') ?? [];
  check('Pause: ровно один COMMENT_REQUIRED', comments(noScreenActionId).length === 1, JSON.stringify(comments(noScreenActionId)));
  check('остальные переходы без COMMENT_REQUIRED', view.transitions.filter((t) => t.actionId !== noScreenActionId).every((t) => !t.validators.some((v) => v.kind === 'COMMENT_REQUIRED')));
  const state = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  check('workflow неактивен, без draft и потребителей', !state.active && !state.hasDraft && state.consumers.projectCount === 0);
  const done = applies(trace), [first, second] = done;
  check('применены ровно два плана: configure_workflow (экран 1 + комментарий), затем замена экрана на screen2', done.length === 2
    && first.toolName === 'configure_workflow' && second?.toolName === 'configure_workflow', done.map((a) => a.toolName).join(','));
  // Plan 1: ONE configure_workflow preview, SET of screen 1 (meta + view together) and comment_required on Pause in the same rule.
  const refersTo = (set, screen) => set?.action === 'SET' && (set.screenId != null ? String(set.screenId) === String(screen.id) : set.screenName === screen.name);
  const request = first?.preview?.arguments ?? {}, rules = request.transitionRules ?? [], added = rules[0]?.addValidators ?? [];
  check('план 1: одно правило Pause — SET экрана screen1 и comment_required вместе, без create и новых экранов',
    first?.toolName === 'configure_workflow' && !request.create && !request.newScreens?.length && !request.existing?.changes && request.existing?.workflow === workflow
    && rules.length === 1 && rules[0].actionId === noScreenActionId && refersTo(rules[0].screen, screens[0])
    && added.length === 1 && added[0].template === 'comment_required'
    && Object.keys(rules[0]).every((key) => ['actionId', 'screen', 'addValidators'].includes(key)), JSON.stringify(request).slice(0, 400));
  const diff = parseResult(first?.preview?.result)?.diff ?? [];
  check('превью плана 1: diff пишет экран вместе с view=fieldscreen и добавление comment_required в одном плане',
    diff.some((d) => new RegExp(`^SET_SCREEN: transition ${noScreenActionId} .*view .*fieldscreen`, 'u').test(d)) && diff.includes(`ADD_VALIDATOR: ${noScreenActionId} comment_required`),
    JSON.stringify(diff));
  // Plan 2: one configure_workflow replacing only the screen, through a rule SET or existing.changes.
  const second_ = second?.preview?.arguments ?? {}, rule2 = second_.transitionRules?.[0], change = second_.existing?.changes?.[0];
  const replace = second?.toolName === 'configure_workflow'
    && !second_.create && !second_.newScreens?.length && second_.existing?.workflow === workflow
    && (!second_.existing.changes && second_.transitionRules?.length === 1
      && rule2.actionId === noScreenActionId && refersTo(rule2.screen, screens[1])
      && !(rule2.addValidators?.length) && !rule2.addConditions
      && Object.keys(rule2).every((key) => ['actionId', 'screen', 'addValidators'].includes(key))
      || !second_.transitionRules?.length && second_.existing.changes?.length === 1 && change.op === 'UPDATE_TRANSITION'
      && change.actionId === noScreenActionId && refersTo(change.screen, screens[1])
      && Object.keys(change).every((key) => ['op', 'actionId', 'screen'].includes(key)));
  check('план 2: только замена экрана Pause на screen2, без других правок', replace, JSON.stringify(second_).slice(0, 400));
  return out;
}
