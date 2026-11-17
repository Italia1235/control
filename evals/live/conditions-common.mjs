// Shared helpers of the live workflow-condition-* scenarios on the single configure_workflow tool: fresh test workflow
// preparation, condition snapshots, exact expected form of the two conditions Control writes (captured from the
// descriptor exported by ScriptRunner 9.46.0 on Jira 10.3.24, the same samples as ConditionFixtures.java) and trace
// checks. setup.mjs creates only test objects (its own workflow, a fresh empty group; existing groups and roles are only
// read); verify.mjs only reads.
import { isDeepStrictEqual } from 'node:util';
import { applies, hasTable, parseResult, questionCount } from './checks.mjs';
import { actionOf, exportXml, nodes, parseXml, text } from './comment-validators-common.mjs';
import { check, pickStatuses, replyOf } from './validators-common.mjs';

export { check, replyOf, isDeepStrictEqual };
export const GET_C = 'get_workflow_conditions';
export const CFG_C = 'configure_workflow';
export const GROUP_CLASS = 'com.atlassian.jira.workflow.condition.UserInGroupCondition';
export const ROLE_CANNED = 'com.onresolve.scriptrunner.canned.jira.workflow.conditions.ProjectRoleCondition';
export const ROLE_CLASS = 'com.onresolve.jira.groovy.GroovyCondition';
/** Captured separator of several role ids and the captured values of FIELD_INVERT_CONDITION (observed in real Jira exports). */
export const ROLE_SEPARATOR = '|||';
export const INVERT_ON = 'on';
const UUID_V4 = /^[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/iu;
const SAME = isDeepStrictEqual;
/**
 * Tools that must not carry a "who can execute the transition" request (and are never needed for it). Besides them,
 * configure_workflow itself is foreign when it creates a workflow or carries anything but the conditions of an existing
 * workflow: new screens, a SET screen, validators (see foreignCalls).
 */
export const FOREIGN_TOOLS = ['edit_workflow', 'copy_workflow', 'rename_workflow', 'update_status', 'publish_workflow_draft'];

// --- Jira objects (setup) ----------------------------------------------------------------------------------------
export async function groupMemberCount(rest, name) {
  const page = await rest('GET', `/rest/api/2/group/member?groupname=${encodeURIComponent(name)}&maxResults=1`);
  return page.total;
}

/** Existing groups with at least one member: only read, the scenario text names them literally. */
export async function requirePopulatedGroups(rest, names) {
  for (const name of names) {
    const total = await groupMemberCount(rest, name).catch(() => null);
    if (!(total > 0)) throw new Error(`Группа «${name}» должна существовать и иметь участников (сейчас: ${total ?? 'не найдена'})`);
  }
}

/** A fresh test group with the run prefix and no members: its own object, no membership is ever changed. */
export async function createEmptyGroup(rest, name) {
  const created = await rest('POST', '/rest/api/2/group', { name });
  const actual = created?.name;
  if (typeof actual !== 'string' || actual !== name) {
    throw new Error(`Jira создала группу «${actual ?? 'без имени в ответе'}» вместо «${name}»: сценарий называет группу буквально`);
  }
  const total = await groupMemberCount(rest, actual);
  if (total !== 0) throw new Error(`Новая группа «${actual}» должна быть пустой, участников: ${total}`);
  return actual;
}

/** Existing global project roles by exact name: [{ id, name }] in the order of `names`; each name must be unique. */
export async function requireRoles(rest, names) {
  const roles = await rest('GET', '/rest/api/2/role');
  return names.map((name) => {
    const found = roles.filter((role) => role.name === name);
    if (found.length !== 1) throw new Error(`Роль проекта «${name}» должна существовать и быть единственной (найдено: ${found.length})`);
    return { id: Number(found[0].id), name };
  });
}

// --- the workflow and its snapshots ------------------------------------------------------------------------------

/** Condition node → comparable form: exactly what get_workflow_conditions exposes per node, in order. */
export function treeSummary(node) {
  if (node == null) return null;
  if (node.kind === 'COMPOUND') return { kind: node.kind, operator: node.operator, children: node.children.map(treeSummary) };
  if (node.kind === 'GROUP') return { kind: node.kind, group: node.group };
  if (node.kind === 'PROJECT_ROLE') return { kind: node.kind, roleIds: node.roleIds, roles: node.roles.map((r) => ({ id: r.id, name: r.name })), inverted: node.inverted };
  return { kind: node.kind, type: node.type, className: node.className, cannedScript: node.cannedScript };
}
export const snapshotOf = (view) => view.transitions.map((t) => ({ actionId: t.actionId, name: t.name, root: treeSummary(t.root) }));

/** The one `existing` target + `transitionRules` request that adds `rules` (conditions, in order) to transitions by name. */
function conditionRules(actionIds, seeds) {
  const byTransition = new Map();
  for (const seed of seeds) {
    if (!byTransition.has(seed.transition)) byTransition.set(seed.transition, []);
    byTransition.get(seed.transition).push({ template: seed.template, parameters: seed.parameters });
  }
  return [...byTransition].map(([transition, rules]) => ({ actionId: actionIds[transition], addConditions: { operator: 'AND', rules } }));
}

/**
 * The inactive test workflow `workflow`: Open → In Progress → Done with Start work, Finish (Done, resolution SET) and
 * Reopen (resolution CLEAR); existing global statuses only. `seeds` [{ transition, template, parameters }] put the
 * conditions the dialogue starts from (in this order, one AND root per transition) with the product's own
 * configure_workflow tool: one preview and one apply (no XML or REST bypass). Returns the facts the dialogue and the checks
 * need, including the exported descriptor before the dialogue.
 */
export async function createConditionsWorkflow({ tool, apply, adminPage, log }, { workflow, seeds = [], needScriptRunner = false }) {
  const s = await pickStatuses(tool);
  const resolutions = await tool('get_resolutions', {});
  const fixed = resolutions.find((r) => /^(fixed|done|completed|resolved|готово|выполнено)$/iu.test(r.name)) ?? resolutions[0];
  if (!fixed) throw new Error('Нет существующей Resolution для перехода Finish');
  await apply(CFG_C, {
    create: {
      name: workflow, initialStatus: s.start.name,
      statuses: [s.start, s.working, s.done].map(({ name, category }) => ({ name, category })),
      transitions: [
        { key: 'start', name: 'Start work', from: s.start.name, to: s.working.name },
        { key: 'finish', name: 'Finish', from: s.working.name, to: s.done.name, resolution: { action: 'SET', value: fixed.id } },
        { key: 'reopen', name: 'Reopen', from: s.done.name, to: s.working.name, resolution: { action: 'CLEAR' } },
      ],
    },
  });
  let view = await tool(GET_C, { workflow, version: 'ACTIVE' });
  if ((needScriptRunner || seeds.some((seed) => seed.template === 'project_role')) && !view.scriptRunner?.supported) {
    throw new Error(`ScriptRunner не готов для project_role: ${view.scriptRunner?.reason}`);
  }
  const actionIds = Object.fromEntries(view.transitions.map((t) => [t.name, t.actionId]));
  if (seeds.length) {
    await apply(CFG_C, { existing: { workflow, version: 'ACTIVE', expectedRevision: view.revision }, transitionRules: conditionRules(actionIds, seeds) });
  }
  view = await tool(GET_C, { workflow, version: 'ACTIVE' });
  const state = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  if (state.active || state.hasDraft || state.consumers.projectCount !== 0 || state.consumers.schemeCount !== 0) {
    throw new Error('Фикстура должна быть неактивной, без draft и потребителей');
  }
  if (view.revision !== state.revision) throw new Error('revision get_workflow_conditions и get_workflow различаются сразу после подготовки');
  const xml = await exportXml(adminPage, workflow);
  log(`workflow «${workflow}» (${s.start.name} → ${s.working.name} → ${s.done.name}); actionId: ${JSON.stringify(actionIds)}`);
  return { workflow, actionIds, statuses: s, revision: view.revision, snapshot: snapshotOf(view), xml };
}

// --- the descriptor (XML) ----------------------------------------------------------------------------------------

const argsOf = (condition) => nodes(condition, 'arg').map((n) => [n.attrs.name, text(n)]);
const argMap = (condition) => Object.fromEntries(argsOf(condition));
const sorted = (list) => [...list].sort();
const rootOf = (action) => { const restrict = nodes(action, 'restrict-to')[0]; return restrict ? nodes(restrict, 'conditions')[0] ?? null : null; };
const membersOf = (root) => nodes(root).filter((n) => n.tag === 'condition' || n.tag === 'conditions');

/** The comparable form of a descriptor subtree: attributes sorted, the arguments of one condition in name order. */
function norm(node) {
  if (typeof node === 'string') return node;
  let children = node.children.filter((n) => !(node.tag === 'workflow' && typeof n !== 'string' && n.tag === 'meta' && n.attrs.name === 'jira.updated.date'));
  const elements = children.filter((n) => typeof n !== 'string');
  if (elements.length && elements.every((n) => n.tag === 'arg')) children = [...children].sort((a, b) => a.attrs.name.localeCompare(b.attrs.name));
  return { tag: node.tag, attrs: Object.fromEntries(Object.entries(node.attrs).sort(([a], [b]) => a.localeCompare(b))), children: children.map(norm) };
}

/** `<condition type="class">` of the Jira «User in group» condition in exactly the captured form (two arguments). */
export function exactGroup(condition, group) {
  if (!condition || condition.tag !== 'condition' || condition.attrs.type !== 'class') return false;
  const names = argsOf(condition).map(([name]) => name);
  const map = argMap(condition);
  return nodes(condition).length === 2 && SAME(sorted(names), ['class.name', 'group']) && map['class.name'] === GROUP_CLASS && map.group === group;
}

/** The ScriptRunner «Project role(s) condition» in exactly the captured form: five arguments, `|||`, `on`/empty, UUID. */
export function exactRole(condition, { ids, invert }, knownFunctionIds = [], uuidPattern = UUID_V4) {
  if (!condition || condition.tag !== 'condition' || condition.attrs.type !== 'class') return false;
  const map = argMap(condition);
  return nodes(condition).length === 5
    && SAME(sorted(Object.keys(map)), ['FIELD_FUNCTION_ID', 'FIELD_INVERT_CONDITION', 'FIELD_PROJECT_ROLE_IDS', 'canned-script', 'class.name'])
    && map.FIELD_PROJECT_ROLE_IDS === ids.join(ROLE_SEPARATOR)
    && map.FIELD_INVERT_CONDITION === (invert ? INVERT_ON : '')
    && uuidPattern.test(map.FIELD_FUNCTION_ID) && !knownFunctionIds.includes(map.FIELD_FUNCTION_ID)
    && map['canned-script'] === ROLE_CANNED && map['class.name'] === ROLE_CLASS;
}

/** FIELD_FUNCTION_ID values of an exported descriptor. */
export function functionIdsOf(xml) {
  const found = [];
  const visit = (node) => { for (const child of nodes(node)) { if (child.tag === 'arg' && child.attrs.name === 'FIELD_FUNCTION_ID') found.push(text(child)); visit(child); } };
  visit(parseXml(xml));
  return found;
}

/**
 * The exact effect of the applies on the exported descriptor: on every target transition the
 * root gains exactly one condition at its end (existing ones equal, same order; a missing root is created as AND), the
 * new one satisfies `isExpected`; with those additions removed the whole descriptor equals the one before.
 * `targets`: [{ actionId, label, isExpected(conditionNode) }].
 */
export function additionChecks(beforeXml, afterXml, targets) {
  const before = parseXml(beforeXml), after = parseXml(afterXml);
  const out = [];
  for (const { actionId, label, isExpected } of targets) {
    const b = actionOf(before, actionId), a = actionOf(after, actionId);
    const bRoot = rootOf(b), aRoot = rootOf(a);
    const old = bRoot ? membersOf(bRoot) : [], now = aRoot ? membersOf(aRoot) : [];
    const added = now.at(-1);
    out.push(check(`${label}: в корень добавлено ровно одно условие, в конец; прежние условия, их аргументы и порядок те же`,
      now.length === old.length + 1 && SAME(old.map(norm), now.slice(0, -1).map(norm)),
      `было ${old.length}, стало ${now.length}`));
    out.push(check(`${label}: добавленное условие — ровно снятый образец`, isExpected(added),
      added ? JSON.stringify(argsOf(added)).slice(0, 400) : 'условия нет'));
    // Jira stores the root of one condition without `type` and of several as "AND": the same meaning.
    out.push(check(`${label}: корень AND`, Boolean(aRoot) && (aRoot.attrs.type === 'AND' || !aRoot.attrs.type && now.length === 1),
      `type=${aRoot?.attrs.type ?? '—'}, условий ${now.length}`));
    if (aRoot && now.length) {
      if (bRoot) {
        aRoot.children.splice(aRoot.children.indexOf(added), 1);
        if (!bRoot.attrs.type && aRoot.attrs.type === 'AND') delete aRoot.attrs.type;
      } else {
        const restrict = nodes(a, 'restrict-to')[0];
        restrict.children.splice(restrict.children.indexOf(aRoot), 1);
        if (!nodes(restrict).length && !nodes(b, 'restrict-to').length) a.children.splice(a.children.indexOf(restrict), 1);
      }
    }
  }
  out.push(check('кроме добавленных условий дескриптор workflow равен исходному (прочие переходы, валидаторы, функции, свойства)',
    SAME(norm(before), norm(after)), 'сравнение после удаления добавленного'));
  return out;
}

// --- trace -------------------------------------------------------------------------------------------------------

export const readsOf = (trace, workflow) => trace.filter((e) => e.tool === GET_C && e.arguments?.workflow === workflow);
/** The first SUCCESSFUL conditions read of the workflow (not isError, has transitions): { entry, view } or {}; a failed read is skipped. */
export function firstGoodRead(trace, workflow) {
  for (const entry of readsOf(trace, workflow)) {
    const view = entry.isError ? null : parseResult(entry.result);
    if (Array.isArray(view?.transitions)) return { entry, view };
  }
  return {};
}
/** A successful get_workflow_conditions exists and precedes the first configure_workflow preview. */
export function readBeforePreview(trace, workflow) {
  const { entry } = firstGoodRead(trace, workflow);
  const first = configurePreviews(trace)[0];
  return check('AC-1: успешный get_workflow_conditions до первого preview configure_workflow',
    Boolean(entry) && (!first || entry.seq < first.entry.seq),
    entry ? `чтение #${entry.seq}, первый preview ${first ? `#${first.entry.seq}` : 'нет'}` : `успешного чтения нет (вызовов: ${readsOf(trace, workflow).length})`);
}
/**
 * What of a configure_workflow request is foreign to a "who can execute the transition" dialogue on an existing
 * workflow: a `create` target, new screens, a SET screen, validators (the conditions are the only thing asked for;
 * `screen: "KEEP"` changes nothing and is allowed). Returns the reasons, empty when the request is clean.
 */
export function foreignParts(args) {
  const out = [];
  if (args?.create !== undefined) out.push('create');
  if (args?.existing && Object.hasOwn(args.existing, 'changes')) out.push('changes');
  if (Array.isArray(args?.newScreens) && args.newScreens.length) out.push('newScreens');
  for (const rule of Array.isArray(args?.transitionRules) ? args.transitionRules : []) {
    if (rule?.screen && rule.screen !== 'KEEP' && rule.screen.action !== 'KEEP') out.push('screen');
    if (Array.isArray(rule?.addValidators) && rule.addValidators.length) out.push('addValidators');
  }
  return [...new Set(out)];
}
/** Calls of tools that must not be used (FOREIGN_TOOLS) and configure_workflow previews carrying anything but conditions of an existing workflow. */
export const foreignCalls = (trace) => trace.filter((e) => FOREIGN_TOOLS.includes(e.tool)
  || e.tool === CFG_C && e.arguments?.dryRun === true && foreignParts(e.arguments).length > 0);
export const foreignLabel = (e) => e.tool === CFG_C ? `#${e.seq} ${e.tool}(${foreignParts(e.arguments).join('+')})` : `#${e.seq} ${e.tool}`;
/**
 * The condition rules a configure_workflow request adds, flattened: [{ workflow, version, actionId, template, parameters }]
 * in request order (the target comes from `existing`, the transition from `transitionRules[i].actionId`).
 */
export function conditionRequests(args) {
  const target = args?.existing ?? {};
  return (Array.isArray(args?.transitionRules) ? args.transitionRules : []).flatMap((rule) =>
    (Array.isArray(rule?.addConditions?.rules) ? rule.addConditions.rules : [])
      .map((r) => ({ workflow: target.workflow, version: target.version, actionId: Number(rule.actionId), template: r?.template, parameters: r?.parameters ?? {} })));
}
/** Every configure_workflow preview in call order with its parsed result, its arguments and the condition rules it requests. */
export const configurePreviews = (trace) => trace.filter((e) => e.tool === CFG_C && e.arguments?.dryRun === true)
  .map((entry) => ({ entry, result: parseResult(entry.result) ?? {}, args: entry.arguments ?? {}, requests: conditionRequests(entry.arguments) }));
export const configureApplies = (trace) => applies(trace, CFG_C);
/** The preview's own view of one transition (`transitions[]` of the response): { actionId, conditions, ... } or undefined. */
export const transitionOf = (result, actionId) => (Array.isArray(result.transitions) ? result.transitions : []).find((t) => Number(t.actionId) === Number(actionId));
/** The terminal domain result of one apply (start_operation answer or its get_operation polls): { result, envelope } or {}. */
export function outcomeOf(trace, apply) {
  if (!apply) return {};
  const started = parseResult(apply.start.result);
  const replies = [apply.start, ...trace.filter((e) => e.seq > apply.start.seq && e.tool === 'get_operation' && started?.operationId && e.arguments?.operationId === started.operationId)];
  for (const entry of replies) {
    const envelope = parseResult(entry.result);
    if (['COMPLETED', 'FAILED', 'PARTIAL', 'BLOCKED', 'CANCELLED'].includes(envelope?.status)) return { entry, envelope, result: envelope.result ?? envelope.error ?? envelope };
  }
  return {};
}
export const warningsOf = (result) => Array.isArray(result.warnings) ? result.warnings : [];

/**
 * Every configure_workflow preview used `existing.expectedRevision` equal to the revision of the newest
 * get_workflow_conditions read of that workflow and version before it.
 */
export function revisionsFresh(trace) {
  const stale = configurePreviews(trace).filter(({ entry, args }) => {
    const read = trace.filter((e) => e.seq < entry.seq && e.tool === GET_C && e.arguments?.workflow === args.existing?.workflow
      && e.arguments?.version === args.existing?.version).at(-1);
    return !read || parseResult(read.result)?.revision !== args.existing?.expectedRevision;
  });
  return check('get_workflow_conditions перед каждым preview; existing.expectedRevision — из последнего чтения той же версии', stale.length === 0,
    stale.length ? `preview #${stale.map(({ entry }) => entry.seq).join(', #')}` : `${configurePreviews(trace).length} preview`);
}

/** The reply of the preview's turn: a table, one question, the transition and every given word; "narrows access" included. */
export function previewQuestion(conversation, previewEntry, { transition, words = [], label }) {
  const reply = replyOf(conversation, previewEntry.turn);
  const lower = reply.toLowerCase();
  const missing = words.filter((word) => !lower.includes(word.toLowerCase()));
  const narrows = /сужа|сузи|сужен|narrow/iu.test(reply);
  const ok = hasTable(reply) && lower.includes(transition.toLowerCase()) && missing.length === 0 && questionCount(reply) === 1 && narrows;
  return check(`${label}: ответ хода ${previewEntry.turn} — таблица (переход, что добавляется), «сужает доступ» и один вопрос`, ok,
    ok ? 'есть' : `таблица ${hasTable(reply)}, переход ${lower.includes(transition.toLowerCase())}, нет слов: ${missing.join(', ') || '—'}, сужение ${narrows}, вопросов ${questionCount(reply)}; «${reply.slice(0, 200)}»`);
}

/**
 * AND root in the assistant's own words: «нужно удовлетворить все условия», judged by
 * the sense: all conditions, both, simultaneously, an AND root named as the root.
 */
export const conveysAllConditions = (reply) => /(?:все|всем|всех|оба|обоим|обе)\s+(?:\p{L}+\s+){0,3}(?:услови|ограничени)|(?:услови|ограничени)\p{L}*[^.?!\n]{0,60}(?:одновременно|вместе|все\s+сразу)|(?:корн\p{L}*|root)[^.?!\n]{0,30}\bAND\b|\bAND\b[^.?!\n]{0,40}(?:корн\p{L}*|root)|all\s+(?:of\s+)?(?:the\s+)?conditions/iu.test(reply);

// «Everyone except the members of the roles» in the assistant's own words, judged by the sense, not by one phrasing. Every
// sign is tied to the ROLES: «не смогут» about anyone else («Пользователи не смогут отменить изменение») is no sign. A bare
// «кроме» («кроме того», «остальные условия, кроме добавляемого») is none either, nor the name of the parameter («invert: true»
// is in every table: the product's warning starts with it), nor the user's own «запрет для членов ролей» echoed back: the echo
// counts only together with the consequence for the other PEOPLE.
const W = String.raw`(?<!\p{L})`; // a word starts here
const ROLE = String.raw`${W}(?:рол\p{L}*|roles?(?!\p{L})|(?<![\p{L}-])(?:Administrators|Service\s+Desk\s+Team)(?!\p{L}))`;
const MEMBER = String.raw`(?<!не\s)(?<!не\s+(?:явля|состоя|входящ)\p{L}*\s)(?<!только\s)${W}(?:член|участник|members?(?!\p{L}))`;
const CAN = String.raw`(?<!не\s)${W}(?:смогут|сможет|могут|может)(?!\p{L})`;
// Denial must concern access or executing a transition, not an unrelated action such as undoing a change.
const CANNOT = String.raw`(?:${W}не\s+(?:смогут|сможет|могут|может)\s+(?:(?:выполнить|выполнять|совершить)\s+(?:этот\s+)?переход|получить\s+доступ)|${W}не\s+получ\p{L}*\s+доступ|${W}не\s+(?:будет|будут)\s+доступ\p{L}*|${W}не\s+допускаются|(?<!не\s)${W}(?:запрещ|недоступ|закрыт|заблокирован)\p{L}*|(?<!не\s)${W}исключ[её]н(?:а|о|ы)?(?!\p{L})|${W}нельзя(?:\s+выполнять\s+переход)?|cannot\s+(?:execute|perform)\s+(?:the\s+)?transition|can't\s+(?:execute|perform)\s+(?:the\s+)?transition|blocked)`;
// One clause only. An exception or another subject must not connect a denial to the role members.
const GAP = String.raw`(?:(?!${W}(?:только|остальн|прочи|вне|без|кроме|except)|не\s+(?:входящ|состо|из)|${CAN})[^.?!\n;,:—–]){0,60}?`;
const ROLE_MEMBER = String.raw`(?:${MEMBER}\p{L}*${GAP}${ROLE}|${W}пользовател\p{L}*\s+с\s+${ROLE}|${W}тем,\s*у\s+кого\s+${ROLE})`;
const nonMember = new RegExp(String.raw`не\s+(?:входящ|входит|состо|являющ|являет|принадлеж|относ)\p{L}*\s+(?:ни\s+)?(?:(?:в|к)\s+)?(?:одн|любо)\p{L}*[^.?!\n;]{0,40}?${ROLE}`, 'giu');
const deniedMember = new RegExp(String.raw`${ROLE_MEMBER}${GAP}(?:,\s*(?:переход\s+)?)?${CANNOT}|${CANNOT}${GAP}${ROLE_MEMBER}`, 'iu');
// Immediately name the excluded people/roles: never search through a comma into a neighbouring condition.
const exceptRoles = new RegExp(String.raw`${W}(?:кроме\s+(?:(?:член|участник|пользовател)\p{L}*(?:\s+(?:любой|одной|из|этих|тех|двух|запрещённых))*\s+${ROLE}|${ROLE})|except\s+(?:the\s+)?members\s+(?:of\s+)?(?:the\s+|those\s+)?roles?)`, 'giu');
const negativeAccess = /не\s+(?:будет\s+)?доступ|недоступ|закрыт|запрещ|заблокирован|никто|никому|не\s+(?:смогут|сможет|могут|может|получ)|нельзя|blocked|cannot|can't|nobody|no\s+one/iu;
const banEcho = /запрет\p{L}*\s+(?:для|на)\s+(?:член|участник|рол)/iu;
// Whole words and an immediate human subject prevent regex backtracking from turning «остальные правила» into people.
const others = String.raw`(?:все\s+остальные(?:\s+(?:пользователи|сотрудники|люди))?|остальные\s+(?:пользователи|сотрудники|люди))(?!\p{L})`;
const consequence = new RegExp(String.raw`${W}${others}\s+(?:(?:смогут|могут)\s+(?:выполнить\s+переход|получить\s+доступ)|допускаются|проходят)(?!\p{L})`, 'iu');
export function conveysInversion(reply) {
  const text = reply.replace(/[*_`]/gu, '');
  if (banEcho.test(text) && consequence.test(text)) return true;
  for (const statement of text.split(/[.?!\n;|]/u)) {
    for (const match of statement.matchAll(nonMember)) {
      const outsideMembership = statement.slice(0, match.index) + ' ' + statement.slice(match.index + match[0].length);
      if (!negativeAccess.test(outsideMembership)) return true;
    }
    // A denial after «except the members» concerns everyone else, not those members.
    if (!/(?:кроме(?!\s+того)|except(?!\s+that)|за\s+исключением)/iu.test(statement) && deniedMember.test(statement)) return true;
    for (const match of statement.matchAll(exceptRoles)) {
      const outsideException = statement.slice(0, match.index) + ' ' + statement.slice(match.index + match[0].length);
      if (!negativeAccess.test(outsideException)) return true;
    }
  }
  return false;
}

// «The group has no members» in prose (the group's own name already cut out by the caller).
const NO_MEMBERS = [
  String.raw`нет[ \t]+(?:ни[ \t]+одного[ \t]+)?(?:участник|член|пользовател)`,
  String.raw`(?:участник|член|пользовател)\p{L}*(?:[ \t]+в[ \t]+группе)?[ \t]+нет`,
  String.raw`никого\s+нет`,
  String.raw`без\s+(?:участников|членов|пользователей)`,
  String.raw`\b0\s+(?:участник|член|пользовател)`,
  String.raw`(?:участник|член)\p{L}*(?:[ \t]+в[ \t]+группе)?[\s|:—-]*\b0(?![\d.,])`,
  String.raw`(?<!затронут\p{L}*\s)пользовател\p{L}*(?:[ \t]+в[ \t]+группе)?[ \t]*[|:][ \t]*0(?![\d.,])`,
  String.raw`никто\s+не\s+(?:сможет|может)\s+(?:выполнить|сделать)`,
  // Expected wording: «пока группа пуста, этому условию не удовлетворит никто»
  String.raw`не\s+удовлетвор\p{L}*\s+(?:ему\s+)?никто|никто\s+не\s+удовлетвор\p{L}*|nobody\s+satisfies`,
  String.raw`(?<!\p{L})пуст(?:а|о|ая|ой|ую|ые|ых|ое|ого|ым|ыми|ом)?(?!\p{L})`,
  String.raw`не\s+(?:имеет|содержит)\s+(?:ни\s+одного\s+)?(?:участник|член|пользовател)`,
  String.raw`no\s+members`,
  String.raw`has\s+no\s+users`,
  String.raw`(?:group|it)\s+is\s+empty`,
];
const noMembersRe = new RegExp(NO_MEMBERS.join('|'), 'iu');
export const conveysEmptyGroup = (prose) => noMembersRe.test(prose);

/** Roles of a request → ids in the order the assistant sent them (a number is an id, anything else an exact name). */
export function requestedRoleIds(roles, known) {
  return roles.map((role) => /^\d+$/u.test(String(role)) ? Number(role) : known.find((k) => k.name === role)?.id);
}
