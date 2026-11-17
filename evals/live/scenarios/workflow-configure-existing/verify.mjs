import {
  advisoryCheck, check, conditionNodes, configureApplies, conveysAnd, conveysAnyRole, conveysDuplicateName, conveysEmptyGroup, conveysNarrowing, conveysMissingField,
  descriptorChecks, exportXml, isExactGroup, isExactRoles, noNobodyWithoutCaveat, noTrace, oneOfEach, outcomeOf, plannedOf, previewTable,
  previewsOf, readState, readbackShown, replyOf, revisionFresh, same, screenFields, screensNamed, sorted, toolChecks,
} from '../../configure-common.mjs';

// Rules for two transitions (one of them global) and ONE new screen shared by
// both are added to an existing inactive workflow with ONE configure_workflow plan. The verifier reads the final Jira
// state: the foreign validators and conditions stay unchanged and FIRST, the additions are at the END, the overlapping
// required_fields are covered by the union without a duplicate, the new screen is a separate object with the same name as
// the existing one (the native API accepted the duplicate name; if it did not, the check FAILs and records the fact),
// the existing screen is untouched, and the whole descriptor equals the one before except what was added.
const SCREEN_FIELDS = ['summary', 'assignee'];
const ruleOf = (args, actionId) => (args.transitionRules ?? []).filter((rule) => rule.actionId === actionId);
const templates = (rule) => (rule?.addValidators ?? []).map((v) => v.template);
const fieldsOf = (rule) => sorted([...new Set((rule?.addValidators ?? []).filter((v) => v.template === 'required_fields').flatMap((v) => v.parameters?.fieldIds ?? []))]);
const rulesOfConditions = (rule) => rule?.addConditions?.rules ?? [];

export default async function verify({ prepared, trace, conversation, tool, rest, adminPage }) {
  const { workflow, group, formName, existingScreen, actions, seedGroup, seedRole, targetRoles } = prepared;
  const start = actions['Start work'], finish = actions.Finish, pause = actions.Pause;
  const targetIds = targetRoles.map((r) => r.id);
  const previews = previewsOf(trace), first = plannedOf(trace)[0];
  const [apply] = configureApplies(trace);
  const outcome = outcomeOf(trace, apply).result;
  const checks = [...toolChecks(trace, { target: 'existing' })];

  // --- the request ----------------------------------------------------------------------------------------------------
  const args = first?.args ?? {};
  const screen = args.newScreens?.[0] ?? {};
  const [rStart] = ruleOf(args, start), [rFinish] = ruleOf(args, finish), [rPause] = ruleOf(args, pause);
  const roleRule = rulesOfConditions(rPause)[0];
  const requestedRoleIds = (roleRule?.parameters?.roles ?? []).map((r) => /^\d+$/u.test(String(r)) ? Number(r) : targetRoles.find((x) => x.name === r)?.id);
  const sameBlock = (rule) => templates(rule).includes('comment_required') && same(fieldsOf(rule), ['assignee', 'description'])
    && rule.screen?.action === 'SET' && rule.screen?.screenKey === screen.key && !rule.screen.screenId && !rule.screen.screenName
    && rule.addConditions?.operator === 'AND' && rulesOfConditions(rule).length === 1 && rulesOfConditions(rule)[0].template === 'group'
    && rulesOfConditions(rule)[0].parameters?.group === group;
  checks.push(oneOfEach(trace, 'AC-3'));
  checks.push(revisionFresh(trace, workflow));
  checks.push(check('AC-3: preview — existing {workflow, ACTIVE, expectedRevision}, без create; три блока правил по actionId (Start work, Finish, Pause), не более одного на переход',
    Boolean(first) && args.existing?.workflow === workflow && args.existing?.version === 'ACTIVE' && !args.create
      && args.transitionRules?.length === 3 && Boolean(rStart) && Boolean(rFinish) && Boolean(rPause),
    JSON.stringify({ existing: args.existing, rules: (args.transitionRules ?? []).map((r) => r.actionId) })));
  checks.push(check('AC-9: ровно один новый экран «LOCAL-008-form-…» с полями Summary, Assignee в порядке (без Description); Start work и Finish ссылаются на него одним screenKey (не по имени и не по ID существующего)',
    args.newScreens?.length === 1 && screen.name === formName && same(screen.fieldIds, SCREEN_FIELDS) && Boolean(screen.key) && sameBlock(rStart) && sameBlock(rFinish),
    JSON.stringify({ newScreens: args.newScreens, start: rStart?.screen, finish: rFinish?.screen })));
  checks.push(check('AC-3/AC-6: Start work и Finish — comment_required, required_fields по объединению {assignee, description}, условие group (AND); Pause — одно условие project_role обеих ролей без invert, без валидаторов и без смены экрана',
    sameBlock(rStart) && sameBlock(rFinish) && !(rPause?.addValidators?.length) && (!rPause?.screen || rPause.screen === 'KEEP')
      && rPause?.addConditions?.operator === 'AND' && rulesOfConditions(rPause).length === 1 && roleRule?.template === 'project_role'
      && !roleRule.parameters?.invert && same(sorted(requestedRoleIds), sorted(targetIds)),
    JSON.stringify({ start: rStart, finish: rFinish, pause: rPause }).slice(0, 700)));

  // --- the preview and the dialogue -----------------------------------------------------------------------------------
  if (first) {
    const result = first.result, reply = replyOf(conversation, first.entry.turn), withoutGroup = reply.split(group).join(' ');
    checks.push(check('AC-3/AC-11: preview — WOULD_CHANGE с planId, версия ACTIVE неактивного workflow сохраняется на месте (targetVersion ACTIVE), без draft в objects',
      result.status === 'WOULD_CHANGE' && result.targetVersion === 'ACTIVE' && !(result.objects ?? []).some((o) => o.kind === 'DRAFT')
        && (result.objects ?? []).filter((o) => o.kind === 'SCREEN').length === 1,
      JSON.stringify({ status: result.status, target: result.targetVersion, objects: (result.objects ?? []).map((o) => o.kind) })));
    checks.push(check('AC-9/AC-11: preview предупреждает о совпадении имени нового экрана с существующим (не отказ, не переиспользование)',
      (result.warnings ?? []).some((w) => w.includes(formName) && /already exists/iu.test(w)), JSON.stringify(result.warnings ?? [])));
    checks.push(check('AC-10: preview предупреждает, что обязательное поле Description (description) отсутствует на экране и пользователь может не суметь его заполнить (workflow без потребителей)',
      (result.warnings ?? []).some((w) => /The workflow is not assigned to any project and issue type, so only the transition screen was checked: Field '[^']+' \(description\) is not on it, and a user may be unable to fill it in\./u.test(w)),
      JSON.stringify(result.warnings ?? [])));
    checks.push(advisoryCheck('AC-10/AC-11: до подтверждения ассистент объясняет, что обязательного Description нет на экране и пользователь может не суметь его заполнить',
      conveysMissingField(reply, formName),
      `«${reply.slice(0, 500)}»`));
    checks.push(previewTable(conversation, first.entry, { label: 'AC-11',
      words: [workflow, 'Start work', 'Finish', 'Pause', formName, group, seedGroup, ...targetRoles.map((r) => r.name)] }));
    checks.push(advisoryCheck('AC-11: таблица называет итоговые ограничения с прежними (посторонняя роль Service Desk Customers), обязательность комментария и полей Assignee/Description, AND («все условия») и «любая из ролей»',
      reply.includes(seedRole.name) && /комментар|comment/iu.test(reply) && /assignee/iu.test(reply) && /description|описан/iu.test(reply) && conveysAnd(reply) && conveysAnyRole(reply),
      `«${reply.slice(0, 260)}»`));
    checks.push(advisoryCheck('AC-9/AC-11: в ответе сказано, что одноимённый существующий экран не переиспользуется — создаётся отдельный новый',
      conveysDuplicateName(reply), `«${reply.slice(0, 260)}»`));
    checks.push(advisoryCheck('AC-11: сужение доступа названо, пустая группа — наблюдение на момент проверки, «никому» без оговорки нет',
      conveysNarrowing(reply) && conveysEmptyGroup(withoutGroup) && noNobodyWithoutCaveat(reply), `«${reply.slice(0, 260)}»`));
  } else {
    checks.push(check('AC-3/AC-11: preview с planId получен', false, `preview: ${previews.map(({ result }) => result.errorCode ?? result.status).join(', ') || 'нет'}`));
    checks.push(advisoryCheck('AC-3/AC-11: preview показан таблицей', false, 'нет preview для показа'));
  }

  // --- the apply -----------------------------------------------------------------------------------------------------
  checks.push(check('AC-3: результат операции UPDATED_INACTIVE (inactive workflow сохранён на месте)', outcome?.status === 'UPDATED_INACTIVE',
    JSON.stringify(outcome ? { status: outcome.status, errorCode: outcome.errorCode, message: outcome.message } : null)));
  checks.push(...readbackShown(trace, conversation, apply, [group, formName, 'Start work', 'Finish', 'Pause'],
    'AC-11: после apply ассистент перечитал workflow и показал фактическую конфигурацию трёх переходов и экранов'));

  // --- the final state ------------------------------------------------------------------------------------------------
  const before = prepared.state, after = await readState(tool, workflow);
  const named = await screensNamed(rest, formName);
  const newScreen = named.find((s) => s.id !== existingScreen.id);
  checks.push(check('AC-9: нативный API принял дубль имени: экранов с именем «LOCAL-008-form-…» ровно два (прежний и новый, разные id)',
    named.length === 2 && named.some((s) => s.id === existingScreen.id) && Boolean(newScreen),
    `экраны с именем: ${JSON.stringify(named)}; результат: ${JSON.stringify(outcome?.status ?? outcome?.errorCode ?? null)}${outcome?.status !== 'UPDATED_INACTIVE' ? ` ${JSON.stringify(outcome?.execution ?? outcome?.message ?? '').slice(0, 300)}` : ''}`));
  checks.push(check('AC-3: граф не изменён (статусы, переходы, kind LOCAL/GLOBAL, resolution), workflow неактивен, без draft и потребителей; публикации и назначения нет',
    noTrace(after) && same(after.graph, before.graph) && same(after.steps, before.steps), JSON.stringify({ active: after.active, draft: after.hasDraft, graph: after.graph })));
  const added = (id) => after.validators[id].slice(before.validators[id].length).map((v) => JSON.stringify(v)).sort();
  const keep = (id) => same(after.validators[id].slice(0, before.validators[id].length), before.validators[id]);
  const comment = JSON.stringify({ kind: 'COMMENT_REQUIRED', ...after.validators[start].find((v) => v.kind === 'COMMENT_REQUIRED') });
  const descriptionOnly = JSON.stringify({ kind: 'REQUIRED_FIELDS', fieldIds: ['description'], conditional: false });
  checks.push(check('AC-3/AC-6: Start work — посторонний required_fields [assignee] первым и неизменён, в конец добавлены ровно COMMENT_REQUIRED и отдельный REQUIRED_FIELDS [description] (только недостающее поле)',
    keep(start) && same(added(start), sorted([comment, descriptionOnly])), JSON.stringify(after.validators[start])));
  checks.push(check('AC-6: Finish — посторонний required_fields [assignee, description] первым и неизменён; требуемые поля покрыты им целиком (дубль не добавлен), добавлен только COMMENT_REQUIRED',
    keep(finish) && added(finish).length === 1 && added(finish)[0] === comment, JSON.stringify(after.validators[finish])));
  checks.push(check('AC-3/AC-5: Pause — validators не тронуты', same(after.validators[pause], before.validators[pause]), JSON.stringify(after.validators[pause])));
  const tree = (id) => after.conditions[id];
  const appended = (id, node) => tree(id)?.kind === 'COMPOUND' && tree(id).operator === 'AND' && same(tree(id).children.slice(0, -1), before.conditions[id].children)
    && same(tree(id).children.at(-1), node);
  const groupNode = { kind: 'GROUP', group };
  checks.push(check('AC-3/AC-7: Start work и Finish — корень AND, посторонние условия (group jira-administrators, роль Service Desk Customers) первыми и неизменны, group «LOCAL-008-group-…» добавлена последним прямым потомком',
    appended(start, groupNode) && appended(finish, groupNode), JSON.stringify({ start: tree(start), finish: tree(finish) })));
  const pauseRole = tree(pause)?.children?.at(-1);
  checks.push(check('AC-3: Pause — посторонние условия первыми и неизменны, последним добавлен один project_role с обеими ролями (не invert); group не добавлена',
    tree(pause)?.kind === 'COMPOUND' && tree(pause).operator === 'AND' && same(tree(pause).children.slice(0, -1), before.conditions[pause].children)
      && pauseRole?.kind === 'PROJECT_ROLE' && same(sorted(pauseRole.roleIds), sorted(targetIds)) && pauseRole.inverted === false,
    JSON.stringify(tree(pause))));
  const startScreen = after.screens[start], finishScreen = after.screens[finish];
  checks.push(check('AC-9: Start work и Finish назначены на ОДИН новый экран (не существующий одноимённый); Pause сохраняет прежний экран',
    Boolean(startScreen) && startScreen === finishScreen && startScreen === newScreen?.id && startScreen !== existingScreen.id && String(after.screens[pause]) === existingScreen.id,
    JSON.stringify(after.screens)));
  const created = newScreen ? await screenFields(tool, newScreen.id) : { tabs: [], fieldIds: [] };
  checks.push(check('AC-9: на новом экране один Field Tab и поля summary, assignee в заданном порядке, без description',
    same(created.tabs, ['Field Tab']) && same(created.fieldIds, SCREEN_FIELDS), JSON.stringify(created)));
  const old = await screenFields(tool, existingScreen.id);
  checks.push(check('AC-9: существующий экран не изменён (то же имя, те же вкладки и поля)', same(old, prepared.formContents), JSON.stringify(old)));
  checks.push(check('AC-10: обязательное Description сохранено на Start work и Finish, но отсутствует на общем новом экране (warning не блокирует apply); comment_required имеет экран',
    Boolean(startScreen) && startScreen === finishScreen && !created.fieldIds.includes('description')
      && [start, finish].every((id) => after.validators[id].some((v) => v.kind === 'REQUIRED_FIELDS' && v.fieldIds?.includes('description') && v.conditional === false)
        && after.validators[id].some((v) => v.kind === 'COMMENT_REQUIRED')),
    JSON.stringify({ screens: after.screens, fields: created.fieldIds, validators: after.validators })));

  // --- the exported descriptor: byte-for-byte except what was added ------------------------------------------------------
  const xml = await exportXml(adminPage, workflow);
  checks.push(...descriptorChecks(prepared.xml, xml, [
    { actionId: start, label: 'AC-3 Start work', validators: 2, conditions: 1, screen: newScreen?.id },
    { actionId: finish, label: 'AC-3 Finish (global)', validators: 1, conditions: 1, screen: newScreen?.id },
    { actionId: pause, label: 'AC-3 Pause', validators: 0, conditions: 1 },
  ]));
  const lastOf = (id) => conditionNodes(xml, id).at(-1);
  checks.push(check('AC-3: добавленные условия в дескрипторе — group в captured-форме (Start work, Finish) и ScriptRunner-роль с обеими ролями (Pause)',
    isExactGroup(lastOf(start), group) && isExactGroup(lastOf(finish), group) && isExactRoles(lastOf(pause), targetIds, false), 'последние условия корней'));
  return checks;
}
