// Live evidence for the required transition comment. Setup and hooks only read; all changes belong to the approved MCP dialogue.
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { applies, parseResult, hasTable, questionCount, isYes } from './checks.mjs';

export const COMMENT_SCRIPT = 'com.onresolve.scriptrunner.canned.jira.workflow.validators.CommentRequiredValidator';
export const GROOVY_CLASS = 'com.onresolve.jira.groovy.GroovyValidator';
export const CFG = 'configure_workflow';
const GET = 'get_workflow_validators';
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const check = (name, ok, detail = '') => ({ name, ok: Boolean(ok), detail: ok ? detail : `NOT_VERIFIED: ${detail || name}` });
const requireThat = (ok, message) => { if (!ok) throw new Error(`BLOCKED: ${message}`); };

// configure_workflow request parts: one `existing` target and typed per-transition rules (actionId for an existing workflow).
const rulesOf = (args) => args?.transitionRules ?? [];
const commentRuleOf = (args) => rulesOf(args).find((r) => r.addValidators?.some((v) => v.template === 'comment_required'));
const screenSetOf = (rule) => rule?.screen?.action === 'SET' ? rule.screen : null;
const noParameters = (v) => v.parameters == null || Object.keys(v.parameters).length === 0;

// The generic helper recognizes only older domain statuses.
function applyOutcome(trace, apply) {
  if (!apply) return {};
  const started = parseResult(apply.start.result);
  const replies = [apply.start, ...trace.filter((entry) => entry.seq > apply.start.seq && entry.tool === 'get_operation'
    && started?.operationId && entry.arguments?.operationId === started.operationId)];
  for (const entry of replies) {
    const response = parseResult(entry.result);
    if (['COMPLETED', 'FAILED', 'PARTIAL', 'BLOCKED', 'CANCELLED'].includes(response?.status))
      return { entry, envelope: response, result: response.result ?? response.error ?? response };
  }
  return {};
}
const decode = (s) => s.replace(/&#x([\da-f]+);|&#(\d+);|&(lt|gt|amp|quot|apos);/giu,
  (_, hex, dec, named) => hex || dec ? String.fromCodePoint(parseInt(hex || dec, hex ? 16 : 10)) : ({ lt: '<', gt: '>', amp: '&', quot: '"', apos: "'" })[named]);

/** Deliberately small descriptor parser: preserves child order/text, rejects incomplete XML, never resolves entities. */
export function parseXml(input) {
  requireThat(typeof input === 'string' && /<workflow(?:\s|>)/u.test(input), 'real workflow XML is missing (HTML/status echoes are not XML)');
  const root = { tag: '#document', attrs: {}, children: [] }, stack = [root];
  const tokens = input.match(/<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<\?[\s\S]*?\?>|<!DOCTYPE(?:[^>\[]|\[[\s\S]*?\])*>|<[^>]+>|[^<]+/gu) ?? [];
  for (const token of tokens) {
    if (/^<\?|^<!--|^<!DOCTYPE/u.test(token)) continue;
    if (token.startsWith('<![CDATA[')) { stack.at(-1).children.push(token.slice(9, -3)); continue; }
    if (token.startsWith('</')) {
      const name = token.slice(2, -1).trim();
      requireThat(stack.length > 1 && stack.at(-1).tag === name, `malformed XML closing ${name}`); stack.pop();
    } else if (token.startsWith('<')) {
      const name = /^<([\w.:-]+)/u.exec(token)?.[1];
      requireThat(name, 'malformed XML opening');
      const attrs = {};
      for (const m of token.matchAll(/([\w.:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/gu)) {
        requireThat(!(m[1] in attrs), `duplicate XML attribute ${m[1]}`); attrs[m[1]] = decode(m[2] ?? m[3]);
      }
      const node = { tag: name, attrs, children: [] }; stack.at(-1).children.push(node);
      if (!token.endsWith('/>')) stack.push(node);
    } else if (token.trim() || ['arg', 'meta'].includes(stack.at(-1).tag)) stack.at(-1).children.push(decode(token));
  }
  requireThat(stack.length === 1 && root.children.length === 1 && root.children[0].tag === 'workflow', 'incomplete workflow XML');
  return root.children[0];
}
export const nodes = (node, tag) => node.children.filter((n) => typeof n !== 'string' && (!tag || n.tag === tag));
export const text = (node) => node?.children.filter((n) => typeof n === 'string').join('') ?? '';
const walk = (node, predicate) => [node, ...nodes(node).flatMap((n) => walk(n, predicate))].filter(predicate);
const canonical = (node) => typeof node === 'string' ? node : { tag: node.tag,
  attrs: Object.fromEntries(Object.entries(node.attrs).sort(([a], [b]) => a.localeCompare(b))),
  children: node.children.filter((n) => !(node.tag === 'workflow' && typeof n !== 'string' && n.tag === 'meta' && n.attrs.name === 'jira.updated.date')).map(canonical) };
export const xmlEquivalent = (before, after) => equal(canonical(parseXml(before)), canonical(parseXml(after)));
export function actionOf(xml, id) {
  const found = walk(typeof xml === 'string' ? parseXml(xml) : xml, (n) => n.tag === 'action' && String(n.attrs.id) === String(id));
  requireThat(found.length === 1, `expected unique action ${id}, found ${found.length}`); return found[0];
}
const validators = (action) => nodes(nodes(action, 'validators')[0] ?? { children: [] }, 'validator');
const args = (validator) => nodes(validator, 'arg').map((n) => [n.attrs.name, text(n)]);
const isComment = (v) => args(v).some(([name, value]) => name === 'canned-script' && value === COMMENT_SCRIPT);
// configure_workflow writes name-based (version 3) FIELD_FUNCTION_IDs; the checks of what it wrote accept any canonical UUID. The
// default of exactComment stays version 4 for callers whose id does not come from configure_workflow.
export const CANONICAL_UUID = /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/iu;
export function exactComment(validator, uuid = false, uuidPattern = /^[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/iu) {
  if (!validator) return false;
  const a = args(validator);
  return validator.attrs.type === 'class' && nodes(validator).length === 3 && a.length === 3
    && equal(a.map(([name]) => name), ['FIELD_FUNCTION_ID', 'canned-script', 'class.name'])
    && Boolean(a[0][1]) && (!uuid || uuidPattern.test(a[0][1]))
    && a[1][1] === COMMENT_SCRIPT && a[2][1] === GROOVY_CLASS;
}
export function screenOf(action) {
  return { id: text(nodes(action, 'meta').find((n) => n.attrs.name === 'jira.fieldscreen.id')) || null, view: action.attrs.view ?? null };
}
/**
 * Compare the entire graph after removing only the expected new validator (and, when `screenId` is given because the same plan
 * also SET the transition screen, only the screen metadata and view), not a hand-picked subset of rules.
 */
export function additionChecks(beforeXml, afterXml, actionId, { screenId } = {}) {
  const before = parseXml(beforeXml), after = parseXml(afterXml), b = actionOf(before, actionId), a = actionOf(after, actionId);
  const old = validators(b), now = validators(a), added = now.at(-1);
  const existingIds = walk(before, (n) => n.tag === 'arg' && n.attrs.name === 'FIELD_FUNCTION_ID').map(text);
  const checks = [check('one exact three-argument UUID validator appended', now.length === old.length + 1 && !old.some(isComment) && exactComment(added, true, CANONICAL_UUID)
      && !existingIds.includes(args(added)[0][1])),
    check('existing validators retain arguments and order', equal(old.map(canonical), now.slice(0, -1).map(canonical)))];
  if (screenId !== undefined) {
    const written = screenOf(a);
    checks.push(check('screen SET in the same plan wrote metadata and view together', written.id === String(screenId) && written.view === 'fieldscreen'));
    for (const action of [b, a]) { delete action.attrs.view; action.children = action.children.filter((n) => typeof n === 'string' || n.tag !== 'meta' || n.attrs.name !== 'jira.fieldscreen.id'); }
  }
  if (added) nodes(a, 'validators')[0]?.children.splice(nodes(a, 'validators')[0].children.indexOf(added), 1);
  // An absent validators container and an empty container have the same pre-add semantics.
  if (!nodes(b, 'validators').length) a.children = a.children.filter((n) => typeof n === 'string' || n.tag !== 'validators' || n.children.length);
  checks.push(check('all foreign attributes, conditions, post-functions and neighboring actions preserved', equal(canonical(before), canonical(after))));
  return checks;
}
export function screenChecks(beforeXml, afterXml, actionId, { operation, screenId, preserveOtherView = false } = {}) {
  const before = parseXml(beforeXml), after = parseXml(afterXml), b = actionOf(before, actionId), a = actionOf(after, actionId);
  const bs = screenOf(b), as = screenOf(a);
  const expectedView = operation === 'SET' ? 'fieldscreen' : bs.view === 'fieldscreen' ? null : bs.view;
  const out = [check(`${operation} screen metadata and view`, as.id === (operation === 'SET' ? String(screenId) : null) && as.view === expectedView),
    check('foreign view fixture is present when requested', !preserveOtherView || Boolean(bs.view && bs.view !== 'fieldscreen'))];
  for (const action of [b, a]) { delete action.attrs.view; action.children = action.children.filter((n) => typeof n === 'string' || n.tag !== 'meta' || n.attrs.name !== 'jira.fieldscreen.id'); }
  out.push(check('screen edit leaves every other descriptor element untouched', equal(canonical(before), canonical(after))));
  return out;
}

export async function artifact(ref, directory, json = false) {
  requireThat(ref?.path && /^[\da-f]{64}$/iu.test(ref.sha256 ?? ''), 'artifact path and SHA-256 are required');
  requireThat(directory, 'evidenceDirectory is required');
  const bytes = await readFile(path.resolve(directory, ref.path));
  requireThat(createHash('sha256').update(bytes).digest('hex') === ref.sha256.toLowerCase(), `artifact hash mismatch: ${ref.path}`);
  const content = bytes.toString('utf8'); return json ? JSON.parse(content) : content;
}
export async function exportXml(adminPage, workflow, version = 'ACTIVE') {
  const page = await adminPage(`/secure/admin/workflows/ViewWorkflowXml.jspa?workflowMode=${version === 'DRAFT' ? 'draft' : 'live'}&workflowName=${encodeURIComponent(workflow)}`);
  requireThat(page.status === 200, `XML export HTTP ${page.status}`);
  let xml = page.text;
  if (!/<workflow(?:\s|>)/u.test(xml)) xml = decode(xml.match(/<(?:pre|textarea)\b[^>]*>([\s\S]*?)<\/(?:pre|textarea)>/iu)?.[1] ?? '');
  parseXml(xml); return xml;
}

/** Verify trace facts, not a supplied success flag; generic runner protocol checks still run as well. */
export function approvalChecks(trace = [], conversation = []) {
  const out = [];
  for (const apply of applies(trace)) {
    const { start, preview, toolName } = apply;
    const users = conversation.filter((c) => c.role === 'user'), assistants = conversation.filter((c) => c.role === 'assistant');
    const question = assistants[(preview?.turn ?? 0) - 1]?.content ?? '';
    const yes = users[start.turn - 1];
    const outcome = applyOutcome(trace, apply);
    out.push(check(`${toolName}: shown preview and real human approval before apply`, preview && preview.turn < start.turn && hasTable(question)
      && questionCount(question) === 1 && yes?.source === 'human' && isYes(yes.content)),
    check(`${toolName}: terminal operation observed`, outcome.entry && (outcome.envelope.status === 'COMPLETED'
      || outcome.result?.errorCode === 'PREVIEW_STALE' && outcome.envelope.status === 'BLOCKED'), JSON.stringify(outcome.result ?? {})));
  }
  return out;
}
const resultEntries = (trace, tool) => trace.filter((e) => e.tool === tool).map((e) => ({ entry: e, result: parseResult(e.result) ?? {} }));
const transition = (view, id) => view?.transitions?.find((t) => String(t.actionId) === String(id));
const commentRead = (view, id) => transition(view, id)?.validators?.filter((v) => v.kind === 'COMMENT_REQUIRED') ?? [];

/** Previously approved fresh fixtures only. No apply/rest/admin POST is available to this function. */
export async function prepareComment(context, scenario) {
  const { evidence: e, evidenceDirectory, tool, adminPage, runId } = context;
  requireThat(e?.schemaVersion === 1 && e.scenario === scenario, `${scenario} evidence manifest is required`);
  requireThat(/^LOCAL-005-/u.test(e.workflow ?? '') && Number.isInteger(e.actionId), 'isolated workflow prefix and real actionId required');
  if (e.project) requireThat(/^L005[A-Z0-9]*$/u.test(e.project), 'project must have L005 prefix');
  const setupTrace = await artifact(e.setupTrace, evidenceDirectory, true), setupConversation = await artifact(e.setupConversation, evidenceDirectory, true);
  requireThat(setupTrace.length && approvalChecks(setupTrace, setupConversation).length && approvalChecks(setupTrace, setupConversation).every((c) => c.ok), 'fixture setup must have approved terminal tool operations');
  requireThat(applies(setupTrace).some((a) => (a.toolName === CFG && a.preview?.arguments?.create?.name === e.workflow
    || a.toolName === 'copy_workflow' && a.preview?.arguments?.newName === e.workflow)
    && applyOutcome(setupTrace, a).result?.status === 'CREATED'), 'fresh workflow must originate in approved CREATED setup operation, not EXISTS');
  const state = await tool('get_workflow', { workflow: e.workflow, version: 'ACTIVE' });
  const consumers = state.consumers;
  requireThat(consumers && Number.isInteger(consumers.projectCount), 'consumer scope was not returned');
  const projects = consumers.projects ?? [];
  requireThat(!consumers.truncated && (consumers.projectCount === 0 || (consumers.projectCount === 1 && e.project && projects.length === 1
    && String(projects[0]).startsWith(`${e.project} (`))), 'workflow has foreign or incompletely enumerated consumers');
  requireThat(Number.isInteger(consumers.schemeCount) && (consumers.projectCount === 0 ? consumers.schemeCount === 0
    : consumers.schemeCount === 1 && consumers.schemes?.length === 1 && /^(?:LOCAL-005-|L005[A-Z0-9]*[: -])/u.test(consumers.schemes[0])),
  'workflow scheme scope must also be isolated and fully enumerated');
  if (e.project) await tool('get_project_workflows', { project: e.project });
  const screens = [];
  for (const id of e.screenIds ?? []) {
    const screen = await tool('get_screen_contents', { screenId: id });
    requireThat(String(screen.screen?.id) === String(id) && /^LOCAL-005-/u.test(screen.name ?? screen.screen?.name ?? ''), `screen ${id} is not isolated`);
    requireThat(applies(setupTrace, 'create_screen').some((a) => a.preview?.arguments?.name === (screen.name ?? screen.screen?.name)
      && applyOutcome(setupTrace, a).result?.status === 'CREATED' && String(applyOutcome(setupTrace, a).result?.screenId) === String(id)),
      `screen ${id} must originate in approved isolated CREATED preparation`);
    requireThat(screen.complete === true && Array.isArray(screen.tabs) && screen.tabs.every((tab) => Array.isArray(tab.fields)), 'complete actual screen layout required');
    if (scenario === 'L1' || scenario === 'L2') requireThat(!screen.tabs.flatMap((tab) => tab.fields).some((f) => (f.fieldId ?? f.id) === 'comment'),
      'Comment must be absent from the layout; transition comment must work without it');
    screens.push(screen);
  }
  requireThat(e.screenIds?.length, 'real test screen IDs required');
  const view = await tool(GET, { workflow: e.workflow, version: 'ACTIVE' });
  requireThat(view.scriptRunner?.supported && transition(view, e.actionId), 'supported ScriptRunner and real target action required');
  const xml = await exportXml(adminPage, e.workflow);
  actionOf(xml, e.actionId);
  requireThat(!state.hasDraft, 'fixture must start without a draft');
  requireThat(scenario === 'L2' ? state.active && e.project : !state.active, 'fixture activity does not match scenario');
  if (scenario === 'L1' || scenario === 'L4') {
    const action = actionOf(xml, e.actionId), s = screenOf(action);
    requireThat(s.view === 'fieldscreen' && e.screenIds.map(String).includes(s.id) && !validators(action).some(isComment),
      'fixture target must have a real usable test screen and no comment validator');
  }
  if (scenario === 'L2') {
    requireThat(Number.isInteger(e.noScreenActionId) && e.noScreenActionId !== e.actionId, 'separate optional no-screen fixture action is required');
    const noScreen = actionOf(xml, e.noScreenActionId);
    requireThat(validators(noScreen).some((v) => exactComment(v)) && (!screenOf(noScreen).id || screenOf(noScreen).view !== 'fieldscreen'),
      'optional exact-validator/no-screen fixture unavailable; do not fabricate it');
  }
  if (scenario === 'L3') {
    const action = actionOf(xml, e.actionId), s = screenOf(action);
    requireThat(s.id && s.view !== 'fieldscreen' && validators(action).some((v) => exactComment(v)), 'optional legacy meta-without-view + exact comment fixture unavailable; no XML/UI write bypass');
    requireThat(Number.isInteger(e.missingCommentActionId) && e.missingCommentActionId !== e.actionId,
      'second legacy action without validator unavailable; both branches require evidence');
    const absent = actionOf(xml, e.missingCommentActionId), absentScreen = screenOf(absent);
    requireThat(absentScreen.id && absentScreen.view !== 'fieldscreen' && !validators(absent).some(isComment),
      'second legacy action must have no comment validator and meta without view');
  }
  if (scenario === 'L1') {
    const target = actionOf(xml, e.actionId);
    requireThat(nodes(target, 'restrict-to').length && walk(target, (n) => n.tag === 'post-functions').length
      && transition(view, e.actionId).validators.some((v) => v.kind === 'REQUIRED_FIELDS')
      && transition(view, e.actionId).validators.some((v) => v.kind === 'OTHER'),
    'L1 needs real neighboring conditions, required-fields, OTHER validator and post-functions');
  }
  if (scenario === 'L5') requireThat(Number.isInteger(e.otherViewActionId) && Number.isInteger(e.noScreenActionId)
    && screenOf(actionOf(xml, e.otherViewActionId)).view && screenOf(actionOf(xml, e.otherViewActionId)).view !== 'fieldscreen',
  'optional foreign-view fixture unavailable; do not fabricate it through XML writes');
  if (scenario === 'L5') requireThat(new Set(e.screenIds.map(String)).size === 2 && /^LOCAL-005-/u.test(e.createdWorkflow ?? '')
    && e.createdWorkflow !== e.workflow, 'L5 needs two distinct screens and a separate fresh createdWorkflow');
  return { scenario, workflow: e.workflow, project: e.project, actionId: e.actionId, screenIds: e.screenIds,
    createdWorkflow: e.createdWorkflow, noScreenActionId: e.noScreenActionId, otherViewActionId: e.otherViewActionId, missingCommentActionId: e.missingCommentActionId,
    xml, view, state, screens, runId, known: { workflow: e.workflow, project: e.project, actionId: e.actionId, screenIds: e.screenIds,
      createdWorkflow: e.createdWorkflow, noScreenActionId: e.noScreenActionId, otherViewActionId: e.otherViewActionId, missingCommentActionId: e.missingCommentActionId,
      instruction: 'Only these isolated approved fixtures; show each preview table and wait for a real human yes. Setup did not write.' } };
}

/** Per-turn snapshots preserve the draft-before-publication and stale-before-apply boundaries. */
export async function observeComment({ prepared, lifecycle, trace, turn, tool, adminPage }) {
  lifecycle.snapshots ??= [];
  const state = await tool('get_workflow', { workflow: prepared.workflow, version: 'ACTIVE' });
  const snapshot = { turn, seq: Math.max(0, ...trace.map((e) => e.seq)), state, activeXml: await exportXml(adminPage, prepared.workflow),
    active: await tool(GET, { workflow: prepared.workflow, version: 'ACTIVE' }) };
  if (state.hasDraft) { snapshot.draftXml = await exportXml(adminPage, prepared.workflow, 'DRAFT'); snapshot.draft = await tool(GET, { workflow: prepared.workflow, version: 'DRAFT' }); }
  lifecycle.snapshots.push(snapshot);
}

const issueStatus = (issue) => issue?.fields?.status?.id;
const comments = (issue) => issue?.fields?.comment?.comments;
export async function uiChecks(e, directory, prepared) {
  const checks = [];
  const independent = ['empty', 'old-comment', 'nonempty', 'whitespace', 'no-screen']
    .map(kind => e.ui?.find(event => event.kind === kind)?.issueKey).filter(Boolean);
  checks.push(check('independent transition observations use distinct isolated issues',
    independent.length === 5 && new Set(independent).size === independent.length, 'use separate issues; no implicit status restoration'));
  for (const kind of ['dialog', 'empty', 'old-comment', 'nonempty', 'whitespace', 'no-screen', 'sr-open-save']) {
    const event = e.ui?.find((v) => v.kind === kind);
    if (!event) { checks.push(check(`UI ${kind}: evidence present`, false, 'missing raw UI and readback artifacts')); continue; }
    const eventActionId = kind === 'no-screen' ? e.noScreenActionId : prepared.actionId;
    requireThat(event.workflow === prepared.workflow && event.actionId === eventActionId, `UI ${kind} binding differs`);
    const html = await artifact(event.page, directory);
    const visible = decode(html.replace(/<script\b[\s\S]*?<\/script>/giu, '').replace(/<[^>]*>/gu, ' '));
    if (kind === 'sr-open-save') {
      const before = await artifact(event.beforeXml, directory), after = await artifact(event.afterXml, directory);
      const readback = await artifact(event.readback, directory, true);
      const afterValidators = validators(actionOf(after, prepared.actionId));
      const b = validators(actionOf(before, prepared.actionId)).filter(isComment), a = afterValidators.filter(isComment);
      const original = prepared.publishedXml ? validators(actionOf(prepared.publishedXml, prepared.actionId)).filter(isComment) : b;
      requireThat(equal(b.map(canonical), original.map(canonical)), 'ScriptRunner editor must open the validator added by this run');
      const editor = await artifact(event.openPage, directory);
      checks.push(check('ScriptRunner editor opened and saved without errors', /<form\b/iu.test(editor) && /Require a comment|Comment required/iu.test(editor)
        && /Update|Save/iu.test(editor) && /Require a comment|Comment required/iu.test(visible)
        && !/aui-message-error|errorMessage|exception|stacktrace/iu.test(editor + html)),
      check('ScriptRunner save rereads exact COMMENT_REQUIRED and records actual FIELD_FUNCTION_ID', b.length === 1 && a.length === 1 && exactComment(a[0])
        && equal(args(b[0]).slice(1), args(a[0]).slice(1)) && commentRead(readback, prepared.actionId).length === 1
        && commentRead(readback, prepared.actionId)[0].position === afterValidators.indexOf(a[0]) + 1,
      `FIELD_FUNCTION_ID before=${b[0] ? args(b[0])[0]?.[1] : 'missing'}; after=${a[0] ? args(a[0])[0]?.[1] : 'missing'}`));
      continue;
    }
    requireThat(/^L005[A-Z0-9]*-\d+$/u.test(event.issueKey ?? ''), 'UI issue must be isolated');
    requireThat(html.includes(event.issueKey), `UI ${kind} page must identify the issue`);
    const before = await artifact(event.beforeIssue, directory, true), after = await artifact(event.afterIssue, directory, true);
    requireThat(before.key === event.issueKey && after.key === event.issueKey && issueStatus(before) && issueStatus(after)
      && Array.isArray(comments(before)) && Array.isArray(comments(after)), 'actual complete Jira issue readbacks required');
    if (kind === 'dialog') checks.push(check('transition opens actual comment dialog', /role\s*=\s*["']dialog["']|aui-dialog/iu.test(html)
      && /<(?:textarea|input)\b[^>]*(?:name|id)\s*=\s*["']comment["']/iu.test(html)));
    if (['empty', 'old-comment', 'no-screen'].includes(kind)) {
      const message = kind === 'no-screen' ? /no screen is in use/iu : /Please provide a comment for this transition/iu;
      checks.push(check(`UI ${kind}: ScriptRunner rejection and unchanged status/comments`, message.test(visible) && issueStatus(before) === issueStatus(after)
        && equal(comments(before), comments(after)) && event.comment === '' && (kind !== 'old-comment' || comments(before).length > 0)));
      if (kind === 'no-screen') {
        const xml = await artifact(event.beforeXml, directory), action = actionOf(xml, eventActionId);
        const current = prepared.publishedXml ?? prepared.xml;
        requireThat(current && equal(canonical(action), canonical(actionOf(current, eventActionId))), 'no-screen UI must belong to this run fixture, not historical XML');
        checks.push(check('no-screen rejection belongs to exact validator on unusable screen', validators(action).some((v) => exactComment(v))
          && (!screenOf(action).id || screenOf(action).view !== 'fieldscreen')));
      }
    }
    if (kind === 'nonempty') checks.push(check('nonempty current comment transitions and is saved', typeof event.comment === 'string' && event.comment.trim()
      && issueStatus(before) !== issueStatus(after) && comments(after).some((c) => c.body === event.comment && !comments(before).some((old) => old.id === c.id))));
    if (kind === 'whitespace') {
      const rejected = /Please provide a comment for this transition/iu.test(visible) && issueStatus(before) === issueStatus(after) && equal(comments(before), comments(after));
      const accepted = issueStatus(before) !== issueStatus(after);
      checks.push(check('whitespace input observed (not a promised behavior)', /^\s+$/u.test(event.comment ?? '') && (rejected || accepted), rejected ? 'observed rejection' : 'observed transition'));
    }
  }
  return checks;
}

/**
 * `expected` describes the single configure_workflow rule an existing-workflow step must carry:
 * { actionId, comment: boolean, screenId? } (screenId = the id SET together with the rule; absent = no screen block).
 * The result's `shape` is false when the previewed request differs; structural/create steps are checked separately below.
 */
async function stepData(e, directory, name, trace, expected) {
  const step = e.steps?.find((s) => s.name === name);
  requireThat(step, `${name} XML boundary evidence missing`);
  const preview = trace.find((v) => v.seq === step.previewSeq), apply = applies(trace).find((a) => a.preview === preview);
  requireThat(preview && apply && parseResult(apply.start.result)?.operationId === step.operationId && applyOutcome(trace, apply).entry, `${name} trace/operation binding missing`);
  requireThat(preview.tool === CFG, `${name} preview must use configure_workflow`);
  const request = preview.arguments ?? {};
  const targetName = request.create ? request.create.name : request.existing?.workflow;
  requireThat(targetName === (request.create ? (e.createdWorkflow ?? e.workflow) : e.workflow), `${name} preview targets a different workflow`);
  const outcome = applyOutcome(trace, apply);
  requireThat(outcome.envelope?.status === 'COMPLETED', `${name} mutation did not complete`);
  let shape = true;
  if (request.existing?.changes) {
    requireThat(preview.tool === CFG && !request.create && !request.newScreens?.length && !request.transitionRules?.length,
      `${name} must be one structural-only configure_workflow request`);
    const changes = request.existing.changes;
    requireThat(outcome.result?.status === 'UPDATED_INACTIVE'
      && outcome.result.execution?.steps?.some((s) => s.kind === 'WORKFLOW_SAVE' && s.writeStatus === 'APPLIED' && s.verification === 'MATCH'),
      `${name} configure_workflow save/read-back receipt missing`);
    requireThat(changes?.length === 1, `${name} must edit one exact transition`);
    const change = changes[0], add = name === 'add-with-screen';
    const clear = ['clear', 'other-view-clear', 'external-screen-clear'].includes(name);
    const id = name === 'other-view-clear' ? e.otherViewActionId : e.actionId;
    requireThat(change.op === (add ? 'ADD_TRANSITION' : 'UPDATE_TRANSITION') && (add || change.actionId === id)
      && change.screen?.action === (clear ? 'CLEAR' : 'SET'), `${name} preview screen operation differs from evidence`);
    if (!add) requireThat(Object.keys(change).every((key) => ['op', 'actionId', 'screen'].includes(key)),
      `${name} must not combine unrelated descriptor changes`);
    if (!clear) requireThat(String(change.screen.screenId) === String(e.screenIds?.[name === 'replace' ? 1 : 0]),
      `${name} screen id differs from approved fixture`);
  }
  if (!expected && name !== 'create-with-screen') requireThat(request.existing?.changes, `${name} structural changes missing`);
  if (expected) {
    // ONE configure_workflow preview: one existing target, one transition rule, no new screens, only the stated screen SET and/or comment_required.
    const rule = request.transitionRules?.[0], set = screenSetOf(rule), added = rule?.addValidators ?? [];
    shape = preview.tool === CFG && !request.create && !request.newScreens?.length
      && equal(Object.keys(request).sort(), ['dryRun', 'existing', 'transitionRules'])
      && equal(Object.keys(request.existing ?? {}).sort(), ['expectedRevision', 'version', 'workflow'])
      && request.transitionRules.length === 1 && rule.actionId === expected.actionId
      && Object.keys(rule).every((key) => ['actionId', 'screen', 'addValidators'].includes(key))
      && (expected.screenId === undefined ? !set && (rule.screen === undefined || rule.screen === 'KEEP')
        : Boolean(set) && set.screenId != null && String(set.screenId) === String(expected.screenId) && e.screenIds.map(String).includes(String(expected.screenId)))
      && (expected.comment ? added.length === 1 && added[0].template === 'comment_required' && noParameters(added[0]) : added.length === 0);
  }
  return { before: await artifact(step.beforeXml, directory), after: await artifact(step.afterXml, directory), preview, apply, result: outcome.result, shape };
}

export async function verifyComment(context, scenario) {
  const { prepared: p, evidence: e, evidenceDirectory: directory, lifecycle = {}, trace = [], conversation = [], tool, adminPage } = context;
  requireThat(p?.scenario === scenario && e?.scenario === scenario, 'prepared scenario/evidence mismatch');
  requireThat(e.workflow === p.workflow && e.actionId === p.actionId && equal(e.screenIds, p.screenIds)
    && ['project', 'createdWorkflow', 'noScreenActionId', 'otherViewActionId', 'missingCommentActionId'].every((key) => e[key] === p[key]),
    'final evidence must retain the prepared workflow/action/screens scope');
  const checks = approvalChecks(trace, conversation);
  const allowed = scenario === 'L2' ? [CFG, 'publish_workflow_draft'] : [CFG];
  const expectedApplies = { L1: 1, L2: 2, L3: 2, L4: 2, L5: 6 }[scenario];
  const onScope = (a) => {
    const request = a.preview?.arguments ?? {};
    if (a.toolName !== CFG) return request.workflow === p.workflow;
    // L5 creates a separate workflow and edits only the prepared integration workflow.
    return request.create ? scenario === 'L5' && request.create.name === e.createdWorkflow && !request.existing
      : request.existing?.workflow === p.workflow;
  };
  checks.push(check('only exact approved scenario mutations on isolated scope', applies(trace).length === expectedApplies
    && applies(trace).every((a) => allowed.includes(a.toolName) && onScope(a))));
  const finalXml = await exportXml(adminPage, p.workflow), final = await tool(GET, { workflow: p.workflow, version: 'ACTIVE' });
  const snapshots = lifecycle.snapshots ?? [];
  const configures = resultEntries(trace, CFG).filter(({ entry }) => entry.arguments?.existing);
  const previews = configures.filter(({ entry }) => commentRuleOf(entry.arguments));
  const plainComment = ({ entry }) => !screenSetOf(commentRuleOf(entry.arguments));
  const diffOf = (result) => Array.isArray(result?.diff) ? result.diff : [];
  if (scenario !== 'L5') checks.push(check('configure previews use real target and newest validator revision', previews.length && configures.filter(({ entry }) => rulesOf(entry.arguments).length).every(({ entry }) => {
    const { workflow, version, expectedRevision } = entry.arguments.existing;
    const read = trace.filter((r) => r.seq < entry.seq && r.tool === GET && r.arguments?.workflow === workflow && r.arguments?.version === version).at(-1);
    return workflow === p.workflow && [p.actionId, e.missingCommentActionId].includes(rulesOf(entry.arguments)[0]?.actionId)
      && parseResult(read?.result)?.revision === expectedRevision;
  })));
  if (scenario === 'L1' || scenario === 'L2') {
    const screenId = scenario === 'L2' ? p.screenIds[0] : undefined;
    const step = await stepData(e, directory, 'comment-add', trace, { actionId: p.actionId, comment: true, screenId });
    const preview = parseResult(step.preview.result), diff = diffOf(preview);
    const previewed = preview?.transitions?.find((t) => String(t.actionId) === String(p.actionId))?.validators ?? [];
    checks.push(check('comment-add: ONE configure_workflow request for the stated transition' + (scenario === 'L2' ? ' with screen SET and comment_required together' : ''), step.shape),
      ...additionChecks(step.before, step.after, p.actionId, scenario === 'L2' ? { screenId } : {}),
      check('comment preview has no invented changes', preview?.status === 'WOULD_CHANGE' && Boolean(preview.planId)
        && diff.filter((d) => d === `ADD_VALIDATOR: ${p.actionId} comment_required`).length === 1
        && diff.length === (scenario === 'L2' ? 2 : 1) && diff.some((d) => /^SET_SCREEN:/u.test(d)) === (scenario === 'L2')
        && previewed.length === (transition(p.view, p.actionId)?.validators.length ?? NaN) + 1
        && previewed.at(-1)?.kind === 'comment_required' && previewed.filter((v) => v.kind === 'comment_required').length === 1
        && /no screen is in use/iu.test(previewed.at(-1)?.errorText ?? '') && /Please provide a comment/iu.test(previewed.at(-1)?.errorText ?? '')),
      check('correct inactive/draft save outcome', step.result?.status === (scenario === 'L1' ? 'UPDATED_INACTIVE' : 'DRAFT_SAVED')
        && preview?.targetVersion === (scenario === 'L1' ? 'ACTIVE' : 'DRAFT') && step.result?.targetVersion === preview.targetVersion));
    const actual = validators(actionOf(step.after, p.actionId)), readValidators = transition(final, p.actionId)?.validators ?? [];
    checks.push(check('reread COMMENT_REQUIRED kind, original position and no parameter leakage', readValidators.length === actual.length
      && readValidators.at(-1)?.kind === 'COMMENT_REQUIRED' && readValidators.at(-1)?.position === actual.length
      && ['fieldIds', 'fields', 'conditional', 'type', 'className', 'cannedScript', 'problem'].every((key) => readValidators.at(-1)?.[key] == null)));
    checks.push(check('existing validator readback retains kind, parameters and order', equal(transition(p.view, p.actionId)?.validators, readValidators.slice(0, -1))));
    const unchanged = previews.filter(({ result }) => result.status === 'UNCHANGED' && !result.planId);
    checks.push(check('repeat preview unchanged and never applied', unchanged.length === 1 && unchanged[0].entry.seq > step.apply.start.seq
      && !applies(trace, CFG).some((a) => a.preview === unchanged[0].entry)),
    check('repeat no write, duplicate or draft', commentRead(final, p.actionId).length === 1 && snapshots.some((s) => s.seq >= (unchanged[0]?.entry.seq ?? Infinity)
      && !s.state.hasDraft && xmlEquivalent(s.activeXml, finalXml))));
    if (scenario === 'L1') checks.push(check('inactive baseline and final descriptor match saved addition', xmlEquivalent(p.xml, step.before) && xmlEquivalent(step.after, finalXml)));
    if (scenario === 'L2') {
      checks.push(check('screen SET and comment saved by ONE configure_workflow apply, no separate edit_workflow', step.preview.tool === CFG
        && !trace.some((t) => t.tool === 'edit_workflow') && !applies(trace).some((a) => a.toolName === 'edit_workflow')),
      check('screen SET and validator preserve approved boundary from the initial descriptor', xmlEquivalent(step.before, p.xml)
        && diff.some((d) => new RegExp(`^SET_SCREEN: transition ${p.actionId} .*view .*fieldscreen`, 'u').test(d))),
      check('ACTIVE refuses editing when draft exists', previews.some(({ entry, result }) => entry.arguments.existing.version === 'ACTIVE'
        && entry.seq > step.apply.start.seq && result.errorCode === 'DRAFT_EXISTS' && !result.planId)));
      const draftSnapshot = snapshots.find((s) => s.draftXml && s.seq >= step.apply.start.seq && !trace.some((t) => t.seq <= s.seq
        && t.tool === 'start_operation' && t.arguments?.toolName === 'publish_workflow_draft' && t.seq > step.apply.start.seq));
      checks.push(check('draft changed; ACTIVE structurally unchanged before publication', draftSnapshot && xmlEquivalent(p.xml, draftSnapshot.activeXml)
        && xmlEquivalent(step.after, draftSnapshot.draftXml)));
      const publication = applies(trace, 'publish_workflow_draft').filter((a) => a.start.seq > step.apply.start.seq);
      checks.push(check('publication has separate preview, approval and terminal PUBLISHED', publication.length === 1 && publication[0].preview?.turn > step.apply.start.turn
        && applyOutcome(trace, publication[0]).result?.status === 'PUBLISHED'),
      check('published XML equals approved draft structurally', xmlEquivalent(step.after, finalXml)));
      checks.push(...await uiChecks(e, directory, { ...p, publishedXml: step.after }));
    }
  }
  if (scenario === 'L3') {
    const b = actionOf(p.xml, p.actionId), screen = screenOf(b);
    checks.push(check('legacy reader diagnoses exact validator without view', commentRead(p.view, p.actionId).length === 1
      && Boolean(commentRead(p.view, p.actionId)[0].problem) && screen.id && screen.view !== 'fieldscreen'));
    const ruleAction = ({ entry }) => commentRuleOf(entry.arguments)?.actionId;
    const rejected = previews.find((v) => plainComment(v) && ruleAction(v) === p.actionId && v.result.errorCode === 'TRANSITION_SCREEN_MISSING' && !v.result.planId);
    checks.push(check('legacy exact existing validator refused, not unchanged; explains view', rejected && /view|fieldscreen/iu.test(JSON.stringify(rejected.result))));
    checks.push(check('legacy action without validator also refused before unchanged/add decision', Number.isInteger(e.missingCommentActionId)
      && previews.some((v) => plainComment(v) && ruleAction(v) === e.missingCommentActionId
        && v.result.errorCode === 'TRANSITION_SCREEN_MISSING' && !v.result.planId)));
    // Existing exact validator: screen SET + comment_required in ONE plan; the diff repairs the view and adds no second validator.
    const repair = await stepData(e, directory, 'same-id-repair', trace, { actionId: p.actionId, comment: true, screenId: e.screenIds[0] });
    const repairDiff = diffOf(parseResult(repair.preview.result));
    checks.push(check('same-id repair is ONE configure_workflow plan: SET of the same screen id together with comment_required', repair.shape && String(e.screenIds[0]) === screen.id));
    checks.push(check('legacy refusal made no change before separately approved repair', xmlEquivalent(p.xml, repair.before)));
    checks.push(...screenChecks(repair.before, repair.after, p.actionId, { operation: 'SET', screenId: screen.id }),
    check('same-id repair diff reports view correction and no second validator', repairDiff.some((d) => new RegExp(`^SET_SCREEN: transition ${p.actionId} .*view .*fieldscreen`, 'u').test(d))
      && !repairDiff.some((d) => /^ADD_VALIDATOR/u.test(d))),
    check('repair needs no duplicate validator', previews.some((v) => ruleAction(v) === p.actionId && v.entry.seq > repair.apply.start.seq
      && v.result.status === 'UNCHANGED' && !v.result.planId)
      && commentRead(final, p.actionId).length === 1 && !commentRead(final, p.actionId)[0].problem),
    check('existing validator repair preserves its full descriptor boundary', xmlEquivalent(repair.before, p.xml)));
    // Action without a validator: screen-only SET through configure_workflow (no addValidators), the comment is NOT added.
    const absentBefore = actionOf(p.xml, e.missingCommentActionId), absentScreen = screenOf(absentBefore);
    const absentRepair = await stepData(e, directory, 'missing-comment-repair', trace, { actionId: e.missingCommentActionId, comment: false, screenId: absentScreen.id });
    const absentDiff = diffOf(parseResult(absentRepair.preview.result));
    checks.push(check('absent-validator repair is a screen-only configure_workflow SET of the same screen id', absentRepair.shape),
      ...screenChecks(absentRepair.before, absentRepair.after, e.missingCommentActionId,
      { operation: 'SET', screenId: absentScreen.id }),
    check('absent-validator legacy fixture is repaired separately with same screen id and view diff',
      absentScreen.id && absentScreen.view !== 'fieldscreen' && !validators(absentBefore).some(isComment)
      && absentDiff.some((d) => new RegExp(`^SET_SCREEN: transition ${e.missingCommentActionId} .*view .*fieldscreen`, 'u').test(d)) && !absentDiff.some((d) => /^ADD_VALIDATOR/u.test(d))),
    check('both repairs preserve continuous descriptor boundaries', repair.apply.start.seq < absentRepair.preview.seq
      && xmlEquivalent(repair.after, absentRepair.before) && xmlEquivalent(absentRepair.after, finalXml)));
    const ready = previews.find((v) => plainComment(v) && ruleAction(v) === e.missingCommentActionId && v.entry.seq > absentRepair.apply.start.seq
      && v.result.status === 'WOULD_CHANGE' && v.result.planId);
    checks.push(check('repaired usable transition without validator would change, without applying it', Boolean(ready)
      && !applies(trace).some((a) => a.preview === ready.entry) && commentRead(final, e.missingCommentActionId).length === 0));
  }
  if (scenario === 'L4') {
    const clear = await stepData(e, directory, 'external-screen-clear', trace);
    checks.push(...screenChecks(clear.before, clear.after, p.actionId, { operation: 'CLEAR' }),
    check('separate approved external change uses allowed screen CLEAR only', clear.preview.tool === CFG
      && /CLEAR/u.test(JSON.stringify(clear.preview.arguments)) && !trace.some((t) => /delete.*screen/iu.test(t.tool))));
    const stale = applies(trace, CFG).find((a) => a.preview?.seq < clear.preview.seq && a.start.seq > clear.apply.start.seq);
    const outcome = applyOutcome(trace, stale);
    checks.push(check('old comment plan blocked PREVIEW_STALE after external CLEAR', stale && commentRuleOf(stale.preview?.arguments)?.actionId === p.actionId
      && outcome.result?.errorCode === 'PREVIEW_STALE'),
    check('stale attempt adds no write beyond external change', xmlEquivalent(clear.after, finalXml) && !commentRead(final, p.actionId).length));
    checks.push(check('external CLEAR starts from previewed descriptor and no draft remains', xmlEquivalent(clear.before, p.xml)
      && snapshots.at(-1)?.state.hasDraft === false));
  }
  if (scenario === 'L5') {
    const ordered = e.steps?.filter((s) => ['add-with-screen', 'set', 'replace', 'clear', 'other-view-clear'].includes(s.name))
      .sort((a, b) => a.previewSeq - b.previewSeq) ?? [];
    requireThat(ordered.length === 5, 'five distinct integration edit boundaries required');
    let previous = p.xml;
    for (const boundary of ordered) {
      const before = await artifact(boundary.beforeXml, directory), after = await artifact(boundary.afterXml, directory);
      checks.push(check(`${boundary.name}: online XML boundary continuous`, xmlEquivalent(previous, before)));
      previous = after;
    }
    for (const [name, operation, id, preserveOtherView] of [['set', 'SET', p.screenIds[0], false], ['replace', 'SET', p.screenIds[1], false],
      ['clear', 'CLEAR', null, false], ['other-view-clear', 'CLEAR', null, true]]) {
      const step = await stepData(e, directory, name, trace);
      checks.push(...screenChecks(step.before, step.after, name === 'other-view-clear' ? e.otherViewActionId : p.actionId,
        { operation, screenId: id, preserveOtherView }), check(`${name} performed through configure_workflow.existing.changes`,
          step.preview.tool === CFG && step.preview.arguments.existing?.changes?.length === 1 && step.result?.status === 'UPDATED_INACTIVE'));
    }
    for (const name of ['create-with-screen', 'add-with-screen']) {
      const id = name === 'create-with-screen' ? e.createActionId : e.addActionId;
      requireThat(Number.isInteger(id), `${name} returned action id required`);
      const step = await stepData(e, directory, name, trace), created = screenOf(actionOf(step.after, id));
      checks.push(check(`${name} XML has both screen components`, step.preview.tool === CFG && created.id === String(p.screenIds[0]) && created.view === 'fieldscreen'));
      if (name === 'create-with-screen') {
        const request = step.preview.arguments, requested = request.create?.transitions ?? [], rules = rulesOf(request), graph = parseXml(step.after);
        checks.push(check('create is configure_workflow create of the separate workflow, not an edit of an existing one', Boolean(request.create) && !request.existing
          && request.create.name === e.createdWorkflow && step.result?.status === 'CREATED'));
        checks.push(check('create step contains only the approved graph and existing screens',
          Object.keys(request).every((key) => ['dryRun', 'create'].includes(key))));
        checks.push(check('create transition screen was explicitly requested with the actual id', requested.some((t) =>
          t.name === actionOf(graph, id).attrs.name && String(t.screen) === String(p.screenIds[0]))));
        const withoutScreen = requested.filter((t) => t.screen == null && !rules.some((r) => r.transitionKey === t.key && r.screen && r.screen !== 'KEEP'));
        checks.push(check('create transitions without screen have neither meta nor view', withoutScreen.length > 0 && withoutScreen.every((t) => {
          const actions = walk(graph, (n) => n.tag === 'action' && n.attrs.name === t.name);
          return actions.length === 1 && screenOf(actions[0]).id === null && screenOf(actions[0]).view === null;
        })));
        const state = await tool('get_workflow', { workflow: e.createdWorkflow, version: 'ACTIVE' });
        checks.push(check('created workflow isolated and real saved XML agrees', state.active === false && !state.hasDraft && state.consumers?.projectCount === 0
          && xmlEquivalent(step.after, await exportXml(adminPage, e.createdWorkflow))));
      }
      if (name === 'add-with-screen') {
        const before = parseXml(step.before), after = parseXml(step.after), added = actionOf(after, id);
        checks.push(check('added action matches requested name and is not an existing action id', added.attrs.name === step.preview.arguments.existing?.changes?.[0]?.name
          && walk(before, (n) => n.tag === 'action' && String(n.attrs.id) === String(id)).length === 0));
        const parent = walk(after, (n) => n.children.includes(added))[0]; parent.children.splice(parent.children.indexOf(added), 1);
        checks.push(check('ADD leaves all previous actions/foreign attributes intact', equal(canonical(before), canonical(after))));
      }
    }
    requireThat(Number.isInteger(e.noScreenActionId), 'no-screen fixture action required');
    const lastStep = e.steps?.filter((s) => ['set', 'replace', 'clear', 'other-view-clear', 'add-with-screen'].includes(s.name))
      .sort((a, b) => a.previewSeq - b.previewSeq).at(-1);
    requireThat(lastStep, 'last integration step missing');
    checks.push(check('integration final XML equals last approved saved descriptor', xmlEquivalent(await artifact(lastStep.afterXml, directory), finalXml)));
    checks.push(check('no-screen transition untouched throughout integration steps', equal(canonical(actionOf(p.xml, e.noScreenActionId)), canonical(actionOf(finalXml, e.noScreenActionId)))));
  }
  return checks;
}
