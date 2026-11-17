import { exactComment, screenOf, actionOf, exportXml, parseXml, xmlEquivalent } from '../../comment-validators-common.mjs';
import { parseResult } from '../../checks.mjs';

export default async function verify({ prepared, lifecycle, trace, tool, adminPage }) {
  const out = [];
  const check = (name, ok, detail = '') => out.push({ name, ok: Boolean(ok), detail });
  const { workflow, actionId } = prepared;
  check('внешний CLEAR экрана выполнен до применения плана', lifecycle.injection?.status, JSON.stringify(lifecycle.injection));
  const starts = trace.filter((e) => e.tool === 'start_operation' && e.arguments?.toolName === 'configure_workflow');
  check('старый план configure_workflow применён ровно один раз', starts.length === 1, String(starts.length));
  const stalePlan = trace.find((e) => e.tool === 'configure_workflow' && e.arguments?.dryRun === true && parseResult(e.result)?.planId === starts[0]?.arguments?.arguments?.planId);
  const rules = stalePlan?.arguments?.transitionRules ?? [];
  check('применённый план — comment_required на Start work для существующего workflow, без правки экрана',
    !stalePlan?.arguments?.create && stalePlan?.arguments?.existing?.workflow === workflow && rules.length === 1 && rules[0].actionId === actionId
    && rules[0].addValidators?.length === 1 && rules[0].addValidators[0].template === 'comment_required'
    && (rules[0].screen === undefined || rules[0].screen === 'KEEP'), JSON.stringify(stalePlan?.arguments ?? null).slice(0, 400));
  const outcomes = trace.filter((e) => e.tool === 'get_operation' || e.tool === 'start_operation').map((e) => parseResult(e.result))
    .filter((r) => r?.status === 'BLOCKED' || r?.result?.errorCode === 'PREVIEW_STALE' || r?.error?.errorCode === 'PREVIEW_STALE');
  check('apply отклонён как PREVIEW_STALE / BLOCKED', outcomes.length > 0, JSON.stringify(outcomes[0] ?? {}).slice(0, 300));
  const view = await tool('get_workflow_validators', { workflow, version: 'ACTIVE' });
  check('COMMENT_REQUIRED нигде не записан', view.transitions.every((t) => !t.validators.some((v) => v.kind === 'COMMENT_REQUIRED')));
  const xml = await exportXml(adminPage, workflow);
  const s = screenOf(actionOf(xml, actionId));
  check('экран снят внешним CLEAR (нет меты и view), стейл-apply ничего не добавил', !s.id && !s.view, JSON.stringify(s));
  const state = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  check('workflow неактивен, без draft и потребителей', !state.active && !state.hasDraft && state.consumers.projectCount === 0);
  return out;
}
