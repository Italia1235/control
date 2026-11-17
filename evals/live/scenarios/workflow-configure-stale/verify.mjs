import { screenOf, actionOf, parseXml } from '../../comment-validators-common.mjs';
import {
  READERS, advisoryCheck, appliedWith, calls, check, conditionNodes, configureApplies, exportXml, noTrace, outcomeOf, parseResult, plannedOf,
  previewTable, previewsOf, readState, same, screensNamed, toolChecks, validatorNodes,
} from '../../configure-common.mjs';

// Between the first preview and its apply an outside administrator changes the workflow (hooks.mjs).
// The apply fails with PREVIEW_STALE before ANY write (the workflow equals the outside change, the new screen does not
// exist, no rule appeared); the assistant explains it, does NOT apply the old plan again, reads the workflow again, makes
// a NEW preview with the new revision and asks for confirmation again. The user declines the new plan, so the final state
// is exactly the outside change.
export default async function verify({ prepared, lifecycle, trace, conversation, tool, rest, adminPage }) {
  const { workflow, screenName, actions, spare } = prepared;
  const injection = lifecycle.injection;
  const planned = plannedOf(trace), [oldPlan, newPlan] = planned;
  const starts = trace.filter((e) => e.tool === 'start_operation');
  const [apply] = configureApplies(trace);
  const outcome = outcomeOf(trace, apply).result;
  const checks = [...toolChecks(trace, { target: 'existing' })];

  checks.push(check('внешняя правка workflow выполнена штатным действием админки после первого preview и до apply, revision изменилась',
    Boolean(injection?.revisionAfter) && injection.revisionBefore !== injection.revisionAfter && Boolean(oldPlan) && injection.turn >= oldPlan.entry.turn
      && (!apply || injection.turn < apply.start.turn), JSON.stringify(injection)));
  checks.push(check('AC-4: первый preview — WOULD_CHANGE неактивного workflow с planId: version ACTIVE, targetVersion ACTIVE (сохранение на месте, без draft)',
    oldPlan?.result.status === 'WOULD_CHANGE' && oldPlan.result.targetVersion === 'ACTIVE' && !(oldPlan.result.objects ?? []).some((o) => o.kind === 'DRAFT')
      && oldPlan.args.existing?.workflow === workflow && oldPlan.args.existing?.version === 'ACTIVE',
    JSON.stringify({ status: oldPlan?.result.status, target: oldPlan?.result.targetVersion, existing: oldPlan?.args.existing })));
  checks.push(check('AC-4/AC-13: первый preview — один план: comment_required, required_fields[description] и новый экран по screenKey на Start work',
    (() => {
      const rule = oldPlan?.args.transitionRules?.[0], screen = oldPlan?.args.newScreens?.[0];
      return oldPlan?.args.transitionRules?.length === 1 && rule?.actionId === actions['Start work']
        && rule.addValidators?.some((v) => v.template === 'comment_required')
        && rule.addValidators?.some((v) => v.template === 'required_fields' && same(v.parameters?.fieldIds, ['description']))
        && rule.screen?.action === 'SET' && rule.screen?.screenKey === screen?.key && screen?.name === screenName && same(screen?.fieldIds, ['summary', 'description']);
    })(), JSON.stringify(oldPlan?.args.transitionRules ?? null).slice(0, 400)));
  if (oldPlan) checks.push(previewTable(conversation, oldPlan.entry, { label: 'AC-13 первый план', words: [workflow, 'Start work', screenName, 'Description'] }));

  // 1. The old plan: applied exactly once, answered PREVIEW_STALE, nothing written.
  checks.push(check('старый план применён ровно один раз (один start_operation в диалоге до нового preview), по planId первого preview',
    starts.length === 1 && apply?.planId === oldPlan?.result.planId && apply.preview === oldPlan?.entry, `start_operation: ${starts.length}`));
  checks.push(check('AC-13: apply отклонён как PREVIEW_STALE до записи (результат не UPDATED_INACTIVE)',
    appliedWith(trace, apply, 'PREVIEW_STALE') && outcome?.status !== 'UPDATED_INACTIVE' && outcome?.status !== 'PARTIAL',
    JSON.stringify(outcome ? { status: outcome.status, errorCode: outcome.errorCode } : null)));

  // 2. The assistant explains, reads again, previews again with the new revision and asks again; the old plan is not repeated.
  const reads = apply && newPlan ? calls(trace, READERS).filter((e) => e.seq > apply.start.seq && e.seq < newPlan.entry.seq && !e.isError
    && e.arguments?.workflow === workflow && parseResult(e.result)?.revision === injection?.revisionAfter) : [];
  checks.push(check('AC-13: после PREVIEW_STALE — перечитан workflow (свежая revision) и построен НОВЫЙ preview с другим planId и expectedRevision = revision после внешней правки',
    Boolean(apply && newPlan) && newPlan.entry.seq > apply.start.seq && newPlan.result.planId !== oldPlan?.result.planId && reads.length > 0
      && newPlan.args.existing?.expectedRevision === injection?.revisionAfter && newPlan.result.status === 'WOULD_CHANGE',
    `новый preview: ${newPlan ? `${newPlan.result.status}, revision ${newPlan.args.existing?.expectedRevision}` : 'нет'}; после hook ${injection?.revisionAfter}; чтений ${reads.length}`));
  if (newPlan) {
    checks.push(previewTable(conversation, newPlan.entry, { label: 'AC-13 новый план', allowEarlier: true, words: [workflow, 'Start work', screenName] }));
    const between = conversation.filter((c) => c.role === 'assistant').slice(apply.start.turn - 1, newPlan.entry.turn).map((c) => c.content).join('\n');
    checks.push(advisoryCheck('AC-13: ассистент объяснил, что план устарел из-за изменения workflow и что ничего не записано, до нового вопроса',
      /устар|изменил|изменён|обнов|stale|changed/iu.test(between) && /ничего\s+не|не\s+(?:был[оаи]?\s+)?(?:записан|применён|применен|сохранён|сохранен)|no\s+changes|nothing/iu.test(between),
      `«${between.slice(0, 260)}»`));
  } else {
    checks.push(check('AC-13: новый preview после PREVIEW_STALE получен', false, `preview: ${previewsOf(trace).length}`));
    checks.push(advisoryCheck('AC-13: новый preview показан таблицей и вопросом', false, 'нет нового preview для показа'));
  }
  checks.push(check('AC-13: старый план не применён повторно, новый — без подтверждения не применён (всего один start_operation, второго planId в apply нет)',
    starts.length === 1 && !starts.some((e) => e.arguments?.arguments?.planId === newPlan?.result.planId), `start_operation: ${starts.length}`));

  // 3. The final state in Jira: the outside change only, no write of the stale plan.
  const state = await readState(tool, workflow);
  checks.push(check('AC-13: workflow равен внешнему изменению — revision после hook, добавлен только разрешённый существующий Done, прежние шаги и граф сохранены, неактивен, без draft и потребителей',
    state.revision === injection?.revisionAfter && spare.name === 'Done' && injection?.statusId === spare.id && noTrace(state)
      && same(state.graph, prepared.state.graph) && same(state.steps.filter((s) => s.statusId !== spare.id), prepared.state.steps)
      && state.steps.filter((s) => s.statusId === spare.id && s.name === spare.name).length === 1
      && state.steps.length === prepared.state.steps.length + 1,
    JSON.stringify({ revision: state.revision, hook: injection?.revisionAfter, steps: state.steps.map((s) => s.name) })));
  // The native INITIAL (Create) permission validator is part of every workflow: it must stay exactly as prepared, while the
  // transitions the plan targets (Start work, Finish) must still have no validators, conditions or screen.
  const working = Object.values(actions);
  checks.push(check('AC-13: правил нет нигде — на Start work и Finish нет ни COMMENT_REQUIRED, ни REQUIRED_FIELDS, ни условий, ни экранов; прочие переходы, в том числе нативный INITIAL PermissionValidator, равны исходным',
    working.every((id) => Array.isArray(state.validators[id]) && state.validators[id].length === 0 && state.conditions[id] === null && state.screens[id] === null)
      && same(state.validators, prepared.state.validators) && same(state.conditions, prepared.state.conditions) && same(state.screens, prepared.state.screens),
    JSON.stringify({ validators: state.validators, conditions: state.conditions, screens: state.screens })));
  const named = await screensNamed(rest, screenName);
  checks.push(check('AC-13: новый экран «LOCAL-008-screen-…» не создан (stale до первой записи)', named.length === 0, JSON.stringify(named)));
  const xml = await exportXml(adminPage, workflow);
  const tree = parseXml(xml);
  checks.push(check('AC-13: в дескрипторе у Start work и Finish нет validators, условий и меты экрана',
    Object.values(actions).every((id) => validatorNodes(xml, id).length === 0 && conditionNodes(xml, id).length === 0 && !screenOf(actionOf(tree, id)).id && !screenOf(actionOf(tree, id)).view),
    'validators/restrict-to/meta'));
  return checks;
}
