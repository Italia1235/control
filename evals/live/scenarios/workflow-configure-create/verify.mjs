import {
  actionIdsOf, advisoryCheck, check, conditionNodes, configureApplies, conveysAnd, conveysAnyRole, conveysEmptyGroup, conveysNarrowing,
  exportXml, isExactComment, isExactGroup, isExactRoles, noNobodyWithoutCaveat, noTrace, oneOfEach, outcomeOf, plannedOf, previewTable,
  previewsOf, readState, readbackShown, replyOf, same, screenFields, screensNamed, sorted, toolChecks, validatorNodes,
} from '../../configure-common.mjs';
import { actionOf, parseXml, screenOf } from '../../comment-validators-common.mjs';

// The workflow, both validators, both conditions in AND and the new
// screen come from ONE configure_workflow create plan; the verifier reads the final Jira state (readers + exported
// descriptor) and the trace. State and protocol are contract; table/question/wording defects are advisory observations.
const rulesOf = (args) => args.transitionRules ?? [];

export default async function verify({ prepared, trace, conversation, tool, rest, adminPage }) {
  const { workflow, group, roles, statuses, screenName, screenFieldIds } = prepared;
  const previews = previewsOf(trace), planned = plannedOf(trace), first = planned[0];
  const [apply] = configureApplies(trace);
  const outcome = outcomeOf(trace, apply).result;
  const roleIds = roles.map((r) => r.id);
  const checks = [...toolChecks(trace, { target: 'create' })];

  // --- the request the assistant formed: one create plan with the whole content ---------------------------------------
  const args = first?.args ?? {}, create = args.create ?? {}, rule = rulesOf(args)[0] ?? {};
  const transition = create.transitions?.[0] ?? {};
  const validators = rule.addValidators ?? [];
  const requiredFields = validators.filter((v) => v.template === 'required_fields');
  const conditionRules = rule.addConditions?.rules ?? [];
  const groupRule = conditionRules.filter((r) => r.template === 'group'), roleRule = conditionRules.filter((r) => r.template === 'project_role');
  const requestedRoleIds = (roleRule[0]?.parameters?.roles ?? []).map((r) => /^\d+$/u.test(String(r)) ? Number(r) : roles.find((x) => x.name === r)?.id);
  const screen = args.newScreens?.[0] ?? {};
  checks.push(oneOfEach(trace, 'AC-2'));
  checks.push(check('AC-2: preview — create «workflow» с двумя существующими статусами, начальный To Do, один local-переход Start To Do → In Progress с ключом',
    create.name === workflow && same(sorted((create.statuses ?? []).map((s) => s.name)), sorted([statuses.todo.name, statuses.progress.name]))
      && create.initialStatus === statuses.todo.name && create.transitions?.length === 1 && transition.name === 'Start'
      && transition.from === statuses.todo.name && transition.to === statuses.progress.name && !transition.fromAny && Boolean(transition.key),
    JSON.stringify(create).slice(0, 400)));
  checks.push(check('AC-2: в том же preview один блок правил перехода по его ключу: comment_required, required_fields[description], условия AND(group, project_role) и экран SET по screenKey',
    args.transitionRules?.length === 1 && rule.transitionKey === transition.key && validators.length === 2
      && validators.some((v) => v.template === 'comment_required' && (!v.parameters || same(v.parameters, {})))
      && requiredFields.length === 1 && same(requiredFields[0].parameters?.fieldIds, ['description'])
      && rule.addConditions?.operator === 'AND' && conditionRules.length === 2 && groupRule.length === 1 && roleRule.length === 1
      && groupRule[0].parameters?.group === group && !roleRule[0].parameters?.invert && same(sorted(requestedRoleIds), sorted(roleIds))
      && rule.screen?.action === 'SET' && rule.screen?.screenKey === screen.key && Boolean(screen.key),
    JSON.stringify(rule).slice(0, 600)));
  checks.push(check('AC-2/AC-9: ровно один новый экран «LOCAL-008-screen-…» с полями Summary, Description в этом порядке (newScreens), без создания экрана отдельным вызовом',
    args.newScreens?.length === 1 && screen.name === screenName && same(screen.fieldIds, screenFieldIds),
    JSON.stringify(args.newScreens ?? null)));
  checks.push(check('AC-2/AC-17: preview — WOULD_CREATE с planId, версия ACTIVE, существующие статусы переиспользованы (новых нет), один экран в objects',
    first?.result.status === 'WOULD_CREATE' && first.result.targetVersion === 'ACTIVE'
      && (first.result.statuses ?? []).length === 2 && (first.result.statuses ?? []).every((s) => s.new !== true && s.isNew !== true)
      && (first.result.objects ?? []).filter((o) => o.kind === 'SCREEN').length === 1 && (first.result.objects ?? []).filter((o) => o.kind === 'STATUS').length === 0,
    JSON.stringify({ status: first?.result.status, target: first?.result.targetVersion, objects: (first?.result.objects ?? []).map((o) => o.kind) })));

  // --- the dialogue ---------------------------------------------------------------------------------------------------
  if (first) {
    const reply = replyOf(conversation, first.entry.turn);
    const withoutGroup = reply.split(group).join(' ');
    checks.push(previewTable(conversation, first.entry, { label: 'AC-2/AC-11',
      words: [workflow, 'Start', group, screenName, 'Description', ...roles.map((r) => r.name)] }));
    checks.push(advisoryCheck('AC-11: в таблице оба validators (комментарий, обязательное поле) и условия: AND («все условия») и «любая из ролей»',
      /комментар|comment/iu.test(reply) && conveysAnd(reply) && conveysAnyRole(reply), `«${reply.slice(0, 260)}»`));
    checks.push(advisoryCheck('AC-11: сказано, что условия сужают доступ (и для будущих проектов workflow), без отказа «никому» без оговорки',
      conveysNarrowing(reply) && noNobodyWithoutCaveat(reply), conveysNarrowing(reply) ? 'сужение названо' : 'сужение не названо'));
    checks.push(advisoryCheck('AC-11: пустая группа названа наблюдением на момент проверки (пока пуста — условию не удовлетворит никто; состав может измениться)',
      conveysEmptyGroup(withoutGroup) && /измени|наблюд|на\s+момент|observ|may\s+change|пока/iu.test(reply), `«${reply.slice(0, 260)}»`));
  } else {
    checks.push(check('AC-2/AC-11: preview с planId получен', false, `preview: ${previews.map(({ result }) => result.errorCode ?? result.status).join(', ') || 'нет'}`));
    checks.push(advisoryCheck('AC-2/AC-11: preview показан таблицей', false, 'нет preview для показа'));
  }

  // --- the apply ------------------------------------------------------------------------------------------------------
  checks.push(check('AC-2: результат операции CREATED (без PARTIAL/FAILED/UNKNOWN)',
    outcome?.status === 'CREATED', JSON.stringify(outcome?.status ?? outcome?.errorCode ?? null)));
  checks.push(...readbackShown(trace, conversation, apply, [group, screenName, 'Start', 'Description'],
    'AC-2: после apply ассистент перечитал workflow из Jira и показал фактическую конфигурацию (группа, экран, поле, переход)'));

  // --- the final state in Jira ----------------------------------------------------------------------------------------
  const state = await readState(tool, workflow);
  const actions = actionIdsOf(state), startId = actions.Start;
  checks.push(check('AC-2/AC-17: workflow создан неактивным, без draft и потребителей, статусы — существующие (те же id), один переход Start (local)',
    noTrace(state) && same(state.steps.map((s) => s.statusId).sort(), [statuses.todo.id, statuses.progress.id].sort())
      && state.steps.find((s) => s.initial)?.statusId === statuses.todo.id
      && Object.keys(actions).length === 1 && Boolean(startId) && state.graph.find((t) => t.actionId === startId)?.kind === 'LOCAL',
    JSON.stringify({ steps: state.steps, actions, graph: state.graph.map((t) => `${t.name}/${t.kind}`) })));
  const finalValidators = state.validators[startId] ?? [];
  checks.push(check('AC-2: на Start ровно два validators — COMMENT_REQUIRED и безусловный REQUIRED_FIELDS [description]',
    finalValidators.length === 2 && finalValidators.filter((v) => v.kind === 'COMMENT_REQUIRED').length === 1
      && finalValidators.filter((v) => v.kind === 'REQUIRED_FIELDS' && !v.conditional && same(v.fieldIds, ['description'])).length === 1,
    JSON.stringify(finalValidators)));
  const root = state.conditions[startId];
  const children = root?.children ?? [];
  const groupNode = children.filter((c) => c.kind === 'GROUP'), roleNode = children.filter((c) => c.kind === 'PROJECT_ROLE');
  checks.push(check('AC-2: условия Start — один корень AND с ровно двумя прямыми потомками: group «LOCAL-008-group-…» и project_role с обеими ролями (не invert)',
    root?.kind === 'COMPOUND' && root.operator === 'AND' && children.length === 2 && groupNode.length === 1 && roleNode.length === 1
      && groupNode[0].group === group && same(sorted(roleNode[0].roleIds), sorted(roleIds)) && roleNode[0].inverted === false,
    JSON.stringify(root)));
  const screenId = state.screens[startId];
  const named = await screensNamed(rest, screenName);
  checks.push(check('AC-2/AC-9: создан ровно один экран «LOCAL-008-screen-…» и назначен на Start; Field Tab с Summary, Description в порядке',
    named.length === 1 && String(screenId) === named[0]?.id, `экраны с именем: ${JSON.stringify(named)}, на Start: ${screenId}`));
  const contents = named[0] ? await screenFields(tool, named[0].id) : { tabs: [], fieldIds: [] };
  checks.push(check('AC-2/AC-9: на экране один Field Tab и поля summary, description в заданном порядке',
    same(contents.tabs, ['Field Tab']) && same(contents.fieldIds, screenFieldIds), JSON.stringify(contents)));

  // --- the exported descriptor: the exact forms and the screen view ------------------------------------------------------
  const xml = await exportXml(adminPage, workflow);
  const action = actionOf(parseXml(xml), startId);
  const s = screenOf(action);
  checks.push(check('AC-2: у action Start jira.fieldscreen.id = новый экран и view="fieldscreen" записаны вместе',
    s.id === String(named[0]?.id) && s.view === 'fieldscreen', JSON.stringify(s)));
  const vNodes = validatorNodes(xml, startId);
  checks.push(check('AC-2: в дескрипторе ровно два validators, один из них — comment_required в точной captured-форме с UUID',
    vNodes.length === 2 && vNodes.filter(isExactComment).length === 1, `validators: ${vNodes.length}`));
  const cNodes = conditionNodes(xml, startId);
  checks.push(check('AC-2: в дескрипторе ровно два условия: group в captured-форме и ScriptRunner-роль с обеими ролями (не invert)',
    cNodes.length === 2 && cNodes.filter((c) => isExactGroup(c, group)).length === 1 && cNodes.filter((c) => isExactRoles(c, roleIds, false)).length === 1,
    `условий: ${cNodes.length}`));
  checks.push(check('AC-2: у action Start ровно один блок restrict-to (условия не раздвоены)',
    nodesOf(action, 'restrict-to').length === 1, `restrict-to: ${nodesOf(action, 'restrict-to').length}`));
  return checks;
}

function nodesOf(node, tag) { return node.children.filter((n) => typeof n !== 'string' && n.tag === tag); }
