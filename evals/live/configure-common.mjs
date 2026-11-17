// Shared helpers of the workflow-configure-* live scenarios: fresh test objects (setup only; every write goes
// through Control's own tools, groups through Jira's REST like the workflow-condition-* scenarios), the views and descriptor
// comparison of the final Jira state, and trace checks of the one-preview / one-question / one-apply dialogue.
// setup.mjs creates only fresh run-prefixed objects; verify.mjs and hooks.mjs only read (the hook changes the workflow only with
// Jira's own admin action, like a second administrator).
import { isDeepStrictEqual as same } from 'node:util';
import { applies, hasTable, parseResult, questionCount } from './checks.mjs';
import { CANONICAL_UUID, actionOf, exactComment, exportXml, nodes, parseXml, screenOf } from './comment-validators-common.mjs';
import { conveysEmptyGroup, createEmptyGroup, exactGroup, exactRole, requireRoles, treeSummary } from './conditions-common.mjs';
import { check as baseCheck, replyOf } from './validators-common.mjs';

// These helpers belong only to the three configure scenarios; other verifiers retain their legacy classification.
const check = (name, ok, detail) => ({ ...baseCheck(name, ok, detail), class: 'contract' });
export const advisoryCheck = (name, ok, detail) => ({ ...baseCheck(name, ok, detail), class: 'advisory' });

export { check, replyOf, same, exportXml, createEmptyGroup, requireRoles, treeSummary, applies, parseResult, conveysEmptyGroup };
export const CFG = 'configure_workflow';
export const GET_W = 'get_workflow';
export const GET_V = 'get_workflow_validators';
export const GET_C = 'get_workflow_conditions';
export const READERS = [GET_W, GET_V, GET_C];
/** Tools replaced by configure_workflow (never in the catalog; a call is a protocol failure either way). */
export const REMOVED_TOOLS = ['create_workflow', 'configure_workflow_validators', 'configure_workflow_conditions'];
/** Every other tool that writes workflows, screens or projects: the request is ONE configure_workflow plan. */
export const OTHER_WRITES = ['edit_workflow', 'copy_workflow', 'rename_workflow', 'update_status', 'publish_workflow_draft',
  'assign_workflow', 'clone_project_workflows', 'create_screen', 'add_field_to_screens', 'edit_screen_layout',
  'remove_field_from_screens', 'create_project'];
export const SCREEN_VIEW = 'fieldscreen';

// --- Jira objects (setup) ----------------------------------------------------------------------------------------

/** Existing statuses by exact name, in the order of `names`; a missing or duplicated name fails loudly (no new status). */
export async function requireStatuses(tool, names) {
  const found = [];
  for (const name of names) {
    const { statuses } = await tool('list_statuses', { query: name, limit: 100 });
    const exact = statuses.filter((status) => status.name === name);
    if (exact.length !== 1) throw new Error(`Статус «${name}» должен существовать и быть единственным (найдено: ${exact.length}); новые глобальные статусы сценарий не создаёт`);
    found.push(exact[0]);
  }
  return found;
}

/** The Resolution of a Done transition: «Fixed»-like, else the first existing one. */
export async function doneResolution(tool) {
  const resolutions = await tool('get_resolutions', {});
  const found = resolutions.find((r) => /^(fixed|done|completed|resolved|готово|выполнено)$/iu.test(r.name)) ?? resolutions[0];
  if (!found) throw new Error('Нет существующей Resolution для перехода в Done');
  return found;
}

/** A fresh standalone screen with existing fields (create_screen); returns { id, name, fields }. */
export async function createScreen(apply, name, fieldIds) {
  const result = await apply('create_screen', { name, fields: fieldIds.map((id) => ({ name: id })) });
  if (!result.screenId) throw new Error(`create_screen не вернул screenId: ${JSON.stringify(result)}`);
  return { id: String(result.screenId), name, fieldIds };
}

/** Every Jira screen with exactly this name, in the order Jira lists them: [{ id, name }]. */
export async function screensNamed(rest, name) {
  const found = [];
  for (let startAt = 0; ; startAt += 100) {
    const page = await rest('GET', `/rest/api/2/screens?startAt=${startAt}&maxResults=100`);
    found.push(...page.screens.filter((screen) => screen.name === name));
    if (startAt + page.screens.length >= page.total || page.screens.length === 0) return found.map((s) => ({ id: String(s.id), name: s.name }));
  }
}

/** The field ids of a screen in order, with its tab names: { name, tabs, fieldIds }. */
export async function screenFields(tool, screenId) {
  const contents = await tool('get_screen_contents', { screenId: Number(screenId) });
  return { name: contents.screen?.name, tabs: (contents.tabs ?? []).map((tab) => tab.name),
    fieldIds: (contents.tabs ?? []).flatMap((tab) => tab.fields.map((field) => field.fieldId)) };
}

// --- views and snapshots -----------------------------------------------------------------------------------------

// A key with an undefined value does not survive JSON (setup.json, `--regrade`): the live shape must not carry such keys.
const defined = (shape) => Object.fromEntries(Object.entries(shape).filter(([, value]) => value !== undefined));
const validatorShape = (v) => v.kind === 'REQUIRED_FIELDS' || v.kind === 'required_fields'
  ? defined({ kind: 'REQUIRED_FIELDS', fieldIds: v.fieldIds, conditional: v.conditional })
  : defined({ kind: v.kind, type: v.type, className: v.className, cannedScript: v.cannedScript });

/** The current Jira state of one workflow version as the readers expose it. */
export async function readState(tool, workflow, version = 'ACTIVE') {
  const [graph, validators, conditions] = await Promise.all([
    tool(GET_W, { workflow, version }), tool(GET_V, { workflow, version }), tool(GET_C, { workflow, version })]);
  // The JSON round trip also drops the other undefined keys (INITIAL resolution, condition summaries), so the state is comparable with a persisted one.
  return JSON.parse(JSON.stringify({
    revision: graph.revision, active: graph.active, hasDraft: graph.hasDraft, consumers: graph.consumers,
    steps: graph.steps.map((s) => ({ name: s.name, statusId: s.statusId, initial: s.initial })),
    graph: graph.transitions.map((t) => ({ actionId: t.actionId, name: t.name, kind: t.kind, from: t.sourceStepIds, to: t.targetStepIds, resolution: t.resolution })),
    screens: Object.fromEntries(graph.transitions.map((t) => [t.actionId, t.screenId ?? null])),
    validators: Object.fromEntries(validators.transitions.map((t) => [t.actionId, t.validators.map(validatorShape)])),
    conditions: Object.fromEntries(conditions.transitions.map((t) => [t.actionId, treeSummary(t.root)])),
    scriptRunner: validators.scriptRunner,
  }));
}
export const actionIdsOf = (state) => Object.fromEntries(state.graph.filter((t) => t.kind !== 'INITIAL').map((t) => [t.name, t.actionId]));
export const noTrace = (state) => state.active === false && state.hasDraft === false
  && state.consumers?.projectCount === 0 && state.consumers?.schemeCount === 0;

// --- descriptor comparison ---------------------------------------------------------------------------------------

const rootOf = (action) => { const restrict = nodes(action, 'restrict-to')[0]; return restrict ? nodes(restrict, 'conditions')[0] ?? null : null; };
const membersOf = (root) => nodes(root).filter((n) => n.tag === 'condition' || n.tag === 'conditions');
const validatorsOf = (action) => nodes(nodes(action, 'validators')[0] ?? { children: [] }, 'validator');
const argsOf = (node) => Object.fromEntries(nodes(node, 'arg').map((n) => [n.attrs.name, n.children.filter((c) => typeof c === 'string').join('')]));

/** Comparable form of a descriptor subtree: attributes sorted, `jira.updated.date` ignored, the arguments of one node in name order. */
export function norm(node) {
  if (typeof node === 'string') return node;
  let children = node.children.filter((n) => !(node.tag === 'workflow' && typeof n !== 'string' && n.tag === 'meta' && n.attrs.name === 'jira.updated.date'));
  const elements = children.filter((n) => typeof n !== 'string');
  if (elements.length && elements.every((n) => n.tag === 'arg')) children = [...children].sort((a, b) => a.attrs.name.localeCompare(b.attrs.name));
  return { tag: node.tag, attrs: Object.fromEntries(Object.entries(node.attrs).sort(([a], [b]) => a.localeCompare(b))), children: children.map(norm) };
}

/**
 * The exact effect of one configure_workflow apply on the exported descriptor: on every target
 * action the validators and conditions only GAIN the expected number of entries at the end (existing ones, their
 * arguments and order equal), the screen is exactly the expected one (meta + view together) or untouched; with those
 * additions removed the whole descriptor equals the one before (other actions, post-functions, properties).
 * targets: [{ actionId, label, validators, conditions, screen? }] — counts of added entries, `screen` = new screen id.
 */
export function descriptorChecks(beforeXml, afterXml, targets) {
  const before = parseXml(beforeXml), after = parseXml(afterXml), out = [];
  for (const { actionId, label, validators, conditions, screen } of targets) {
    const b = actionOf(before, actionId), a = actionOf(after, actionId);
    const oldV = validatorsOf(b), nowV = validatorsOf(a);
    out.push(check(`${label}: прежние validators с аргументами и порядком на месте, добавлено ровно ${validators} в конец`,
      nowV.length === oldV.length + validators && same(oldV.map(norm), nowV.slice(0, oldV.length).map(norm)), `было ${oldV.length}, стало ${nowV.length}`));
    const container = nodes(a, 'validators')[0];
    if (container) for (const v of nowV.slice(oldV.length)) container.children.splice(container.children.indexOf(v), 1);
    if (!nodes(b, 'validators').length) a.children = a.children.filter((n) => typeof n === 'string' || n.tag !== 'validators' || nodes(n).length);

    const bRoot = rootOf(b), aRoot = rootOf(a);
    const oldC = bRoot ? membersOf(bRoot) : [], nowC = aRoot ? membersOf(aRoot) : [];
    out.push(check(`${label}: прежние условия корня с аргументами и порядком на месте, добавлено ровно ${conditions} в конец`,
      nowC.length === oldC.length + conditions && same(oldC.map(norm), nowC.slice(0, oldC.length).map(norm)), `было ${oldC.length}, стало ${nowC.length}`));
    if (conditions > 0) {
      out.push(check(`${label}: корень условий AND`, Boolean(aRoot) && (aRoot.attrs.type === 'AND' || !aRoot.attrs.type && nowC.length === 1),
        `type=${aRoot?.attrs.type ?? '—'}, условий ${nowC.length}`));
      if (aRoot) {
        for (const added of nowC.slice(oldC.length)) aRoot.children.splice(aRoot.children.indexOf(added), 1);
        if (bRoot) { if (!bRoot.attrs.type && aRoot.attrs.type === 'AND') delete aRoot.attrs.type; }
        else {
          const restrict = nodes(a, 'restrict-to')[0];
          restrict.children.splice(restrict.children.indexOf(aRoot), 1);
          if (!nodes(restrict).length && !nodes(b, 'restrict-to').length) a.children.splice(a.children.indexOf(restrict), 1);
        }
      }
    }
    if (screen !== undefined) {
      const s = screenOf(a);
      out.push(check(`${label}: экран ${screen} назначен вместе с view="${SCREEN_VIEW}" (meta и view записаны вместе)`,
        s.id === String(screen) && s.view === SCREEN_VIEW, JSON.stringify(s)));
      for (const action of [b, a]) { delete action.attrs.view; action.children = action.children.filter((n) => typeof n === 'string' || n.tag !== 'meta' || n.attrs.name !== 'jira.fieldscreen.id'); }
    }
  }
  out.push(check('кроме добавленного дескриптор workflow равен исходному (прочие переходы, validators, conditions, функции, свойства)',
    same(norm(before), norm(after)), 'сравнение после удаления добавленного'));
  return out;
}

/** `<condition>` nodes of an action's root, and `<validator>` nodes: for exact-form checks of what was added. */
export const conditionNodes = (xml, actionId) => { const root = rootOf(actionOf(xml, actionId)); return root ? membersOf(root) : []; };
export const validatorNodes = (xml, actionId) => validatorsOf(actionOf(xml, actionId));
// configure_workflow requires canonical UUID identities, not a particular UUID generation version (CANONICAL_UUID).
export const isExactComment = (node) => exactComment(node, true, CANONICAL_UUID);
export const isExactGroup = (node, group) => exactGroup(node, group);
/** The ScriptRunner role condition for these role ids in ANY order of ids (the order the assistant sent them is free). */
export function isExactRoles(node, ids, invert = false) {
  const orders = ids.length === 2 ? [ids, [...ids].reverse()] : [ids];
  return orders.some((order) => exactRole(node, { ids: order, invert }, [], CANONICAL_UUID));
}
export const roleArgsOf = (node) => argsOf(node);

// --- trace -------------------------------------------------------------------------------------------------------

/** Every configure_workflow preview (dryRun:true) in call order: { entry, args, result }. */
export const previewsOf = (trace) => trace.filter((e) => e.tool === CFG && e.arguments?.dryRun === true)
  .map((entry) => ({ entry, args: entry.arguments ?? {}, result: parseResult(entry.result) ?? {} }));
export const plannedOf = (trace) => previewsOf(trace).filter(({ result }) => result.planId);
export const configureApplies = (trace) => applies(trace, CFG);
export const calls = (trace, tools) => trace.filter((e) => tools.includes(e.tool));
export const sorted = (list) => [...list].sort();

/** The terminal domain result of one apply (start_operation answer or its get_operation polls): { result, envelope } or {}. */
export function outcomeOf(trace, apply) {
  if (!apply) return {};
  const started = parseResult(apply.start.result);
  const replies = [apply.start, ...trace.filter((e) => e.seq > apply.start.seq && e.tool === 'get_operation' && started?.operationId && e.arguments?.operationId === started.operationId)];
  for (const entry of replies) {
    const envelope = parseResult(entry.result);
    if (['COMPLETED', 'FAILED', 'PARTIAL', 'BLOCKED', 'CANCELLED'].includes(envelope?.status)) {
      return { entry, envelope, result: envelope.result ?? envelope.error ?? envelope };
    }
  }
  return {};
}
/** True when any answer of the apply (start_operation / its polls) names the code. */
export function appliedWith(trace, apply, code) {
  if (!apply) return false;
  const started = parseResult(apply.start.result);
  return [apply.start, ...trace.filter((e) => e.seq > apply.start.seq && e.tool === 'get_operation' && started?.operationId && e.arguments?.operationId === started.operationId)]
    .some((entry) => JSON.stringify(entry.result ?? '').includes(code));
}

/** Neither a removed tool nor another writing tool is called; configure_workflow only with the expected target. */
export function toolChecks(trace, { target }) {
  const removed = calls(trace, REMOVED_TOOLS), other = calls(trace, OTHER_WRITES);
  const wrongTarget = previewsOf(trace).filter(({ args }) => target === 'create' ? !args.create || args.existing : !args.existing || args.create);
  const wrongStart = trace.filter((e) => e.tool === 'start_operation' && e.arguments?.toolName !== CFG);
  return [
    check('AC-1/AC-19: вызовов удалённых create_workflow / configure_workflow_validators / configure_workflow_conditions нет', removed.length === 0,
      removed.map((e) => `#${e.seq} ${e.tool}`).join(', ') || 'нет'),
    check('одна операция одним configure_workflow: нет edit_workflow, отдельного create_screen, publish, assign и других записей',
      other.length === 0 && wrongStart.length === 0 && wrongTarget.length === 0,
      [...other, ...wrongStart].map((e) => `#${e.seq} ${e.tool}${e.arguments?.toolName ? `(${e.arguments.toolName})` : ''}`).join(', ')
        + (wrongTarget.length ? ` configure_workflow с неверной целью: #${wrongTarget.map(({ entry }) => entry.seq).join(', #')}` : '') || 'нет'),
  ];
}

/** Every reader's `revision` before each preview: expectedRevision of an existing target is the newest read of that workflow and version. */
export function revisionFresh(trace, workflow) {
  const stale = previewsOf(trace).filter(({ entry, args }) => {
    if (!args.existing) return false;
    const read = trace.filter((e) => e.seq < entry.seq && READERS.includes(e.tool) && e.arguments?.workflow === args.existing.workflow
      && e.arguments?.version === args.existing.version && parseResult(e.result)?.revision).at(-1);
    return !read || parseResult(read.result).revision !== args.existing.expectedRevision || args.existing.workflow !== workflow;
  });
  return check('expectedRevision каждого preview — revision последнего чтения того же workflow и версии (get_workflow / get_workflow_validators / get_workflow_conditions)',
    stale.length === 0, stale.length ? `preview #${stale.map(({ entry }) => entry.seq).join(', #')}` : `${previewsOf(trace).length} preview`);
}

// --- the dialogue ------------------------------------------------------------------------------------------------

const ANDS = /(?<!\p{L})(?:все|всем|всех|всеми|оба|обе|обоим)(?!\p{L})[^.?!\n]{0,50}услови|услови\p{L}*[^.?!\n]{0,70}(?:одновременно|(?<!\p{L})(?:все|оба|обе)(?!\p{L}))|одновременно|(?<![\p{L}\d])AND(?![\p{L}\d])|и\s+то,?\s+и\s+другое|all\s+(?:of\s+the\s+)?conditions/iu;
const ANY_ROLE = /любо\p{L}*[^.?!\n]{0,60}рол|рол\p{L}*[^.?!\n]{0,60}любо\p{L}*|одн\p{L}*\s+из[^.?!\n]{0,40}рол|(?:хотя\s+бы|как\s+минимум)\s+(?:в\s+)?одн\p{L}*[^.?!\n]{0,40}рол|any\s+(?:one\s+)?of[^.?!\n]{0,40}roles?/iu;
const accessClauses = (reply) => String(reply).replace(/[*`]/gu, '').split(/[.?!\n]+/u);
const ACCESS = /услови|доступ|членств|член\s|групп|рол|conditions?|access|group|role/iu;
const NEGATED_AND = /не\s+(?:нужно|надо|требу|обязатель|все|оба|одновременно|AND)|(?:AND|услови\p{L}*)\s+не\s+(?:требу|обязатель|примен)|услови\p{L}*\s+необязатель/iu;
// «group … and … role» is a conjunction only when «and» joins two membership predicates: the role mention follows it directly
// («и роль», «и член любой из ролей», «и хотя бы в одной роли»), not a list of transitions («на Start work и Finish, роль — на Pause»).
const MEMBERSHIP_ROLE = String.raw`(?:(?:хотя\s+бы|как\s+минимум)\s+)?(?:(?:в|быть|член\p{L}*|участник\p{L}*|состоять|иметь)\s+){0,3}(?:(?:одн\p{L}*|люб\p{L}*)\s+)?(?:из\s+)?(?:рол|role)`;
const CONJUNCTION = String.raw`(?:и|AND|а\s+также)`;
const GROUP_AND_ROLE = new RegExp(String.raw`(?:групп|group)(?:(?!\sна\s)[^.!?\n;—–]){0,140}?\s${CONJUNCTION}\s+${MEMBERSHIP_ROLE}`, 'iu');
const MEMBERSHIP_GROUP = String.raw`(?:(?:в|быть|член\p{L}*|участник\p{L}*|состоять|иметь)\s+){0,3}(?:групп|group)`;
const ROLE_AND_GROUP = new RegExp(String.raw`(?:рол|role)(?:(?!\sна\s)[^.!?\n;—–]){0,140}?\s${CONJUNCTION}\s+${MEMBERSHIP_GROUP}`, 'iu');
// OR in an access clause is judged per operand, not by regex alternatives over the whole clause. A clause is split into table cells (`|`) and
// segments at every other coordinator («и», AND, «а также», comma, semicolon, brackets, dashes); a segment is an OR chain when «или/либо/OR»
// stands in it. The chain is rejected as the OR of the conditions when
//  - its operands are of different kinds, group and role («группа G или роль R», «роли: A или член группы G»), or an operand is a condition
//    («условие 1 или условие 2»);
//  - OR is only named as the combining operator («через OR», «условия объединены OR»): an operand is missing or follows «через/via/by».
// An OR between role names (a role list, «любой из ролей A OR B OR C», in either alphabet) is the any-role membership and does not cut AND:
// operands without a group or role word are neutral names, and a neutral operand takes its kind from the label before «:» («роли проекта: A»).
// «Any/one of the conditions is enough» is a clause-level statement and is rejected wherever it stands.
const DISJUNCTOR = String.raw`(?<![\p{L}\d])(?:или|либо|OR)(?![\p{L}\d])`;
const DISJUNCTOR_SPLIT = new RegExp(DISJUNCTOR, 'giu');
const BOUNDARY_BEFORE_DISJUNCTOR = new RegExp(String.raw`[,;(—–]\s*(?=${DISJUNCTOR})`, 'giu');
const SEGMENT_BOUNDARY = /\s(?:и|AND|а\s+также)\s|\s-\s|[,;()—–]/iu;
const ANY_CONDITION = new RegExp([
  String.raw`(?:любо\p{L}*|одн\p{L}*)\s+из\s+(?:этих\s+|двух\s+)?услови`,
  String.raw`(?:достаточно|хватит|хватает)\s+(?:выполнить\s+)?(?:одного|любого|одно|любое)(?!\p{L})`,
  String.raw`хотя\s+бы\s+одн(?:о|ого)\s+(?:из\s+)?услови`,
].join('|'), 'iu');
const GROUP_WORD = /групп|group/iu;
const ROLE_WORD = /рол|role/iu;
// A whole operand that is a bare condition: «условие 1», «первое условие», «условия».
const CONDITION_OPERAND = /^(?:\p{L}+\s+){0,2}(?:услови\p{L}*|conditions?)(?:\s*[\d№#]+)?$/iu;
const OPERATOR_BEFORE = /(?:^|\s)(?:через|посредством|оператор\p{L}*|via|by|using|как)$/iu;
const operandKinds = (operand) => {
  const colon = operand.lastIndexOf(':');
  const label = colon < 0 ? '' : operand.slice(0, colon);
  const body = operand.slice(colon + 1).trim();
  const kinds = new Set();
  if (GROUP_WORD.test(body)) kinds.add('group');
  if (ROLE_WORD.test(body)) kinds.add('role');
  if (!kinds.size && ROLE_WORD.test(label)) kinds.add('role');
  if (!kinds.size && GROUP_WORD.test(label)) kinds.add('group');
  if (!kinds.size && CONDITION_OPERAND.test(body)) kinds.add('conditions');
  return kinds;
};
const disjunctionOfConditions = (segment) => {
  const operands = segment.split(DISJUNCTOR_SPLIT).map((operand) => operand.replace(/[«»"'`]/gu, '').trim());
  if (operands.length < 2) return false;
  // OR only named as the operator: nothing on one side, or «через OR».
  if (operands.some((operand, index) => !operand || (index < operands.length - 1 && OPERATOR_BEFORE.test(operand)))) return true;
  const kinds = new Set(operands.flatMap((operand) => [...operandKinds(operand)]));
  return kinds.has('conditions') || (kinds.has('group') && kinds.has('role'));
};
const withoutNegatedOr = (clause) => clause.replace(/не\s+(?:через\s+|как\s+)?[«"']?(?:OR|или|либо)(?![\p{L}\d])[»"']?/giu, '').replace(/не\s+(?:достаточно|хватит)/giu, '');
const hasConditionOr = (clause) => {
  const text = withoutNegatedOr(clause);
  return ANY_CONDITION.test(text) || text.split('|').some((cell) =>
    cell.replace(BOUNDARY_BEFORE_DISJUNCTOR, ' ').split(SEGMENT_BOUNDARY).some(disjunctionOfConditions));
};
export const conveysAnd = (reply) => {
  const clauses = accessClauses(reply).filter((clause) => ACCESS.test(clause));
  return !clauses.some(hasConditionOr)
    && clauses.some((clause) => !NEGATED_AND.test(clause) && (ANDS.test(clause) || GROUP_AND_ROLE.test(clause) || ROLE_AND_GROUP.test(clause)));
};
// «Not a member of any of the roles» is the inversion, not the any-role membership.
export const conveysAnyRole = (reply) => accessClauses(reply).some((clause) =>
  !/не\s+(?:достаточ|любо|хотя|требу|нужно|обязатель|член|состо|име|входит)|(?<!\p{L})ни\s+(?:в\s+)?одн|всех\s+рол|кажд\p{L}*\s+рол|экран[^.!?\n]*рол/iu.test(clause) && ANY_ROLE.test(clause));
export function conveysNarrowing(reply) {
  const clauses = accessClauses(reply);
  if (clauses.some((clause) => /доступ[^.!?\n]{0,20}не\s+огранич|не\s+(?:сужа|сузи|ограничива)|ограничени\p{L}*(?:\s+доступа)?\s+не\s+действ|сужени\p{L}*\s+(?:доступа\s+)?(?:нет|не\s+будет)|(?<![\p{L}])нет\s+сужени|(?<!(?:друг|ин|проч|дополнительн)\p{L}*\s)ограничени\p{L}*\s+(?:доступа\s+)?(?:нет|не\s+будет)|доступ[^.!?\n]{0,20}не\s+будет\s+(?:огранич|суж)/iu.test(clause))) return false;
  if (clauses.some((clause) => /(?:сужа|сузи|сужен|ограничива|narrow|restrict)[^.!?\n]{0,70}(?:доступ|access)/iu.test(clause))) return true;
  // A noun such as "restrictions" is not enough: the same preview must explain AND membership and its additional scope.
  return conveysAnd(reply) && /групп|group/iu.test(reply) && /рол|role/iu.test(reply) && clauses.some((clause) =>
    /ограничени/iu.test(clause) && !/ограничени\p{L}*\s+экран/iu.test(clause)
      && /действ|примен/iu.test(clause) && /дополнительно|всех\s+проект|всех\s+потребител/iu.test(clause));
}

/** The requested required field is absent on this plan's screen and cannot be filled there, in either clause order. */
export function conveysMissingField(reply, screenName) {
  const clauses = accessClauses(reply);
  const field = /description|описан/iu;
  if (!String(reply).includes(screenName)) return false;
  const screenReference = (clause) => clause.includes(screenName)
    || /(?:новом|этом|общем)\s+экран\p{L}*|на\s+н[её]м|экран\p{L}*\s*(?=[;:,|]|$)/iu.test(clause);
  const relevant = clauses.filter((clause) => field.test(clause));
  if (relevant.some((clause) => /необязател|не\s+обязател|уже\s+заполн|(?<!\p{L})можно(?:\s+будет)?\s+заполн|(?<!\p{L})доступ\p{L}*\s+для\s+заполн/iu.test(clause))) return false;
  const absent = relevant.some((clause) => screenReference(clause)
    && /нет|отсутств|не\s+(?:включ|добав|вывед|представ)|без\s+description|not\s+on|missing/iu.test(clause));
  const cannotFill = clauses.some((clause) => screenReference(clause) && /заполн|ввест|fill|enter/iu.test(clause)
    && /(?:не\s+(?:смож|суме)|невозмож|не\s+удаст|нельзя|unable|cannot|can't)/iu.test(clause)
    && (field.test(clause) || /(?<!\p{L})(?:его|поле)(?!\p{L})/iu.test(clause)) && !/assignee|summary/iu.test(clause));
  return absent && cannotFill;
}
/** «Nobody can» without the observation caveat is forbidden: the caveat is a reference to the membership at check time / change. */
export const noNobodyWithoutCaveat = (reply) => !/никому|никто\s+не\s+(?:смож|может)|nobody/iu.test(reply) || /наблюд|на\s+момент|может\s+измениться|состав|observ|may\s+change/iu.test(reply);
export const conveysDuplicateName = (reply) => /уже\s+(?:существу|есть)|одноимённ|такое\s+же\s+имя|то\s+же\s+имя|совпад\p{L}*\s+(?:имен|назван)|already\s+exists|same\s+name|дубл/iu.test(reply);

/**
 * The reply of the preview's turn is ONE question with a table that shows every word; the earlier replies hold
 * no table (one preview table in the dialogue before the confirmation); `allowEarlier` is for a re-preview after PREVIEW_STALE.
 */
export function previewTable(conversation, previewEntry, { words, label, allowEarlier = false }) {
  const reply = replyOf(conversation, previewEntry.turn);
  const lower = reply.toLowerCase();
  const missing = words.filter((word) => !lower.includes(String(word).toLowerCase()));
  const earlier = conversation.filter((c) => c.role === 'assistant').slice(0, previewEntry.turn - 1).filter((c) => hasTable(c.content));
  const ok = hasTable(reply) && missing.length === 0 && questionCount(reply) === 1 && (allowEarlier || earlier.length === 0);
  return advisoryCheck(`${label}: ответ хода ${previewEntry.turn} — одна таблица со всеми объектами и один вопрос${allowEarlier ? '' : '; до неё таблиц нет'}`, ok,
    ok ? 'есть' : `таблица ${hasTable(reply)}, нет слов: ${missing.join(', ') || '—'}, вопросов ${questionCount(reply)}, ранних таблиц ${earlier.length}; «${reply.slice(0, 240)}»`);
}

/** Separate the required read after apply from the advisory description of the resulting configuration. */
export function readbackShown(trace, conversation, apply, words, label) {
  const start = apply?.start;
  const reads = start ? calls(trace, READERS).filter((e) => e.seq > start.seq && !e.isError) : [];
  const replies = conversation.filter((c) => c.role === 'assistant');
  const final = replies.at(-1)?.content ?? '';
  const after = start ? replies.slice(start.turn - 1).map((c) => c.content).join('\n').toLowerCase() : '';
  const missing = words.filter((word) => !after.includes(String(word).toLowerCase()));
  return [
    check(`${label}: перечитал workflow после apply`, reads.length > 0, `чтений после apply: ${reads.length}`),
    advisoryCheck(`${label}: показал фактическую конфигурацию`, missing.length === 0 && final.length > 0,
      `в ответах после apply нет слов: ${missing.join(', ') || '—'}; финальный ответ ${final.length ? 'непустой' : 'пустой'}`),
  ];
}

/** Exactly one preview call in the dialogue, with a planId, one start_operation by that planId, not repeated. */
export function oneOfEach(trace, label) {
  const previews = previewsOf(trace), runs = configureApplies(trace), starts = trace.filter((e) => e.tool === 'start_operation');
  const plan = previews[0]?.result.planId;
  return check(`${label}: один preview (вызов dryRun:true) с planId, один start_operation по этому planId; повторных preview/apply нет`,
    previews.length === 1 && Boolean(plan) && starts.length === 1 && runs.length === 1 && runs[0].planId === plan && runs[0].preview === previews[0].entry,
    `preview: ${previews.length} (${previews.map(({ result }) => result.status ?? result.errorCode).join(', ')}), start_operation: ${starts.length}`);
}
