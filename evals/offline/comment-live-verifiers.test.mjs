import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { additionChecks, screenChecks, xmlEquivalent, parseXml, actionOf, screenOf, exactComment,
  approvalChecks, artifact, uiChecks, verifyComment, prepareComment, COMMENT_SCRIPT, GROOVY_CLASS } from '../live/comment-validators-common.mjs';
import verifyAuto from '../live/scenarios/comment-required-auto/verify.mjs';

const UUID = '11111111-2222-4333-8444-555555555555';
const WORKFLOW = 'LOCAL-005-verifier-fixture';
const validator = (id = UUID) => `<validator name="" type="class"><arg name="FIELD_FUNCTION_ID">${id}</arg><arg name="canned-script">${COMMENT_SCRIPT}</arg><arg name="class.name">${GROOVY_CLASS}</arg></validator>`;
const REQUIRED = '<validator type="class"><arg name="FIELD_FUNCTION_ID">old-id</arg><arg name="canned-script">FieldRequiredValidator</arg><arg name="field">description</arg></validator>';
const OTHER = '<validator type="class"><arg name="class.name">foreign.Validator</arg><arg name="secret">retain exactly &amp; ordered</arg></validator>';
function xml({ screen = '101', view = 'fieldscreen', comment = false, updated = 'one', otherView = 'foreign-view', extra = '' } = {}) {
  return `<?xml version="1.0"?><workflow><meta name="jira.updated.date">${updated}</meta><steps><step id="1" name="Open"><actions><action id="21" name="Finish" auto="false"${view === null ? '' : ` view="${view}"`}><meta name="foreign">untouched</meta>${screen === null ? '' : `<meta name="jira.fieldscreen.id">${screen}</meta>`}<restrict-to><conditions type="AND"><condition type="class"><arg name="class.name">foreign.Condition</arg></condition></conditions></restrict-to><validators>${REQUIRED}${OTHER}${comment ? validator() : ''}</validators><results><unconditional-result old-status="Finished" status="Done" step="2"><post-functions><function type="class"><arg name="class.name">foreign.Function</arg></function></post-functions></unconditional-result></results></action><action id="22" name="Other" view="${otherView}"><meta name="jira.fieldscreen.id">101</meta><validators>${OTHER}</validators></action><action id="23" name="No screen"><validators>${OTHER}</validators></action>${extra}</actions></step></steps></workflow>`;
}
const noScreenFixture = (input) => input.replace(`<action id="22" name="Other" view="foreign-view"><meta name="jira.fieldscreen.id">101</meta><validators>${OTHER}</validators></action>`,
  `<action id="22" name="Other"><validators>${validator('b'.repeat(40))}</validators></action>`);
const view = (comment = false, problem = null) => ({ revision: 'r1', scriptRunner: { supported: true }, transitions: [{ actionId: 21, name: 'Finish',
  validators: [{ kind: 'REQUIRED_FIELDS', position: 1 }, { kind: 'OTHER', position: 2 }, ...(comment ? [{ kind: 'COMMENT_REQUIRED', position: 3, problem }] : [])] }] });
const state = { active: false, hasDraft: false, consumers: { projectCount: 0, schemeCount: 0, projects: [], schemes: [], truncated: false } };
const allPass = (checks) => assert.deepEqual(checks.filter((c) => !c.ok), []);
// Contexts carry functions (tool/adminPage), so only the data parts are deep-copied for mutation checks.
const cloneContext = (context) => ({ ...context, ...Object.fromEntries(['evidence', 'trace', 'lifecycle', 'prepared'].filter((key) => context[key]).map((key) => [key, structuredClone(context[key])])) });
const table = '| Change | Impact |\n|---|---|\n| Comment | Isolated workflow |\nApply?';
const conversation = (...turns) => turns.flatMap((reply) => [{ role: 'user', source: 'human', content: reply }, { role: 'assistant', content: table }]);
function read(seq, turn = 1) { return { seq, turn, tool: 'get_workflow_validators', arguments: { workflow: WORKFLOW, version: 'ACTIVE' }, result: view() }; }
const CFG = 'configure_workflow', ERROR_TEXT = 'Please provide a comment for this transition. If no screen is in use: You need to provide a comment for this transition but no screen is in use';
/** One configure_workflow request for an existing workflow: one transition rule, optional screen SET and optional comment_required. */
const request = (actionId, { version = 'ACTIVE', screenId, comment = true } = {}) => ({ existing: { workflow: WORKFLOW, version, expectedRevision: 'r1' },
  transitionRules: [{ actionId, ...(screenId === undefined ? {} : { screen: { action: 'SET', screenId: String(screenId) } }),
    ...(comment ? { addValidators: [{ template: 'comment_required' }] } : {}) }] });
const structural = (changes) => ({ existing: { workflow: WORKFLOW, version: 'ACTIVE', expectedRevision: 'r1', changes } });
const saved = { execution: { steps: [{ key: 'workflow-save', kind: 'WORKFLOW_SAVE', writeStatus: 'APPLIED', verification: 'MATCH', id: WORKFLOW }] } };
const previewedTransition = (actionId = 21) => [{ actionId, name: 'Finish', validators: [{ kind: 'required_fields' }, { kind: 'OTHER' }, { kind: 'comment_required', errorText: ERROR_TEXT }] }];
function preview(seq, turn, tool, args = {}, result = {}) {
  return { seq, turn, tool, arguments: { dryRun: true, ...(tool === CFG ? {} : { workflow: WORKFLOW }), ...args }, result: { status: 'WOULD_CHANGE', planId: `p${seq}`, ...result } };
}
function apply(seq, turn, p, result = { status: 'UPDATED_INACTIVE' }, status = 'COMPLETED') {
  return [{ seq, turn, tool: 'start_operation', arguments: { toolName: p.tool, arguments: { dryRun: false, planId: p.result.planId } }, result: { operationId: `o${seq}`, status: 'QUEUED' } },
    { seq: seq + 1, turn, tool: 'get_operation', arguments: { operationId: `o${seq}` }, result: { operationId: `o${seq}`, status, operation: p.tool, result } }];
}
async function fixture(t) {
  const directory = await mkdtemp(path.join(tmpdir(), 'comment-live-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  let n = 0;
  const save = async (value) => {
    const content = typeof value === 'string' ? value : JSON.stringify(value), filename = `${++n}.capture`;
    await writeFile(path.join(directory, filename), content);
    return { path: filename, sha256: createHash('sha256').update(content).digest('hex') };
  };
  return { directory, save };
}
async function contextFor(t, scenario = 'L1') {
  const f = await fixture(t), before = xml(), after = xml({ comment: true, updated: 'two' });
  const p = preview(2, 1, CFG, request(21), { diff: ['ADD_VALIDATOR: 21 comment_required'], targetVersion: 'ACTIVE', transitions: previewedTransition() });
  const trace = [read(1), p, ...apply(3, 2, p, { status: 'UPDATED_INACTIVE', targetVersion: 'ACTIVE' }), read(5, 3), preview(6, 3, CFG, request(21), { status: 'UNCHANGED', planId: null })];
  const ctx = { evidenceDirectory: f.directory, evidence: { schemaVersion: 1, scenario, workflow: WORKFLOW, actionId: 21, screenIds: [101],
    steps: [{ name: 'comment-add', beforeXml: await f.save(before), afterXml: await f.save(after), previewSeq: 2, operationId: 'o3' }] },
  prepared: { scenario, workflow: WORKFLOW, actionId: 21, screenIds: [101], xml: before, view: view(), state },
  lifecycle: { snapshots: [{ seq: 6, state, activeXml: after }] }, trace, conversation: conversation('Please preview', 'Да, применяй', 'Repeat'),
  tool: async () => view(true), adminPage: async () => ({ status: 200, text: after }) };
  return { ...f, before, after, ctx };
}

test('L1 structural addition compares complete foreign graph, ordered args and new UUID', () => {
  allPass(additionChecks(xml(), xml({ comment: true, updated: 'different Jira timestamp' }), 21));
  const damaged = xml({ comment: true }).replace('foreign.Condition', 'changed.Condition');
  assert.ok(additionChecks(xml(), damaged, 21).some((c) => !c.ok));
  assert.ok(additionChecks(xml(), xml({ comment: true }).replace('retain exactly', 'changed'), 21).some((c) => !c.ok));
  assert.ok(additionChecks(xml(), xml({ comment: true }).replace(UUID, 'not-a-uuid'), 21).some((c) => !c.ok));
});

// configure_workflow writes name-based (version 3) function ids, so the checks of its results accept any canonical UUID.
test('comment additions written by configure_workflow keep a canonical name-based UUID (L1 structure and comment-required-auto)', async () => {
  const V3 = '9820371e-9625-38f0-934d-1296b7c9c4d5';
  const written = xml({ comment: true, updated: 'two' }).replace(UUID, V3);
  allPass(additionChecks(xml(), written, 21));
  assert.ok(additionChecks(xml(), written.replace(V3, 'not-a-uuid'), 21).some((c) => !c.ok));
  assert.ok(additionChecks(xml(), written.replace(V3, '9820371e-9625-38f0-934d-1296b7c9c4d'), 21).some((c) => !c.ok));
  const auto = (text) => verifyAuto({ prepared: { workflow: WORKFLOW, actionId: 21, noScreenActionId: 21, screenId: 101, xml: xml() }, trace: [],
    tool: async (name) => (name === 'get_workflow' ? state : view(true)), adminPage: async () => ({ status: 200, text }) });
  const uuidCheck = (checks) => checks.find((c) => c.name === 'one exact three-argument UUID validator appended');
  assert.equal(uuidCheck(await auto(written)).ok, true);
  assert.equal(uuidCheck(await auto(written.replace(V3, 'not-a-uuid'))).ok, false);
});

test('exact three-argument shape rejects extras, wrong type, UUID version and reordered arguments', () => {
  const v = (s) => actionOf(`<workflow><action id="21"><validators>${s}</validators></action></workflow>`, 21).children[0].children[0];
  assert.equal(exactComment(v(validator()), true), true);
  assert.equal(exactComment(v(validator('f'.repeat(40)))), true);
  assert.equal(exactComment(v(validator('f'.repeat(40))), true), false);
  assert.equal(exactComment(v(validator().replace('</validator>', '<arg name="extra">x</arg></validator>'))), false);
  assert.equal(exactComment(v(validator().replace('type="class"', 'type="other"'))), false);
  assert.equal(exactComment(v(validator(UUID.replace('-4333-', '-1333-'))), true), false);
  const reversed = validator().replace(`<arg name="FIELD_FUNCTION_ID">${UUID}</arg>`, '').replace('</validator>', `<arg name="FIELD_FUNCTION_ID">${UUID}</arg></validator>`);
  assert.equal(exactComment(v(reversed)), false);
});

test('missing, malformed or HTML-only XML cannot satisfy preservation', () => {
  for (const bad of [null, '', '<html>COMPLETED</html>', '<workflow><action></workflow>', '<workflow>']) assert.throws(() => parseXml(bad), /BLOCKED/u);
  assert.throws(() => actionOf('<workflow><action id="21"/><action id="21"/></workflow>', 21), /unique/u);
});

test('structural equality tolerates Jira timestamp/attribute order but not foreign metadata or rule text', () => {
  assert.equal(xmlEquivalent(xml(), xml({ updated: 'changed' }).replace('id="21" name="Finish"', 'name="Finish" id="21"')), true);
  assert.equal(xmlEquivalent(xml(), xml().replace('untouched', 'changed')), false);
  assert.equal(xmlEquivalent(xml(), xml().replace('retain exactly', 'retain  exactly')), false);
  const whitespaceArg = xml().replace('retain exactly &amp; ordered', ' ');
  assert.equal(xmlEquivalent(whitespaceArg, xml().replace('retain exactly &amp; ordered', '  ')), false);
});

test('AC12 SET/replace/CLEAR and same-id repair compare graph structurally', () => {
  allPass(screenChecks(xml({ view: null }), xml(), 21, { operation: 'SET', screenId: 101 }));
  allPass(screenChecks(xml(), xml({ screen: '202' }), 21, { operation: 'SET', screenId: 202 }));
  allPass(screenChecks(xml(), xml({ screen: null, view: null }), 21, { operation: 'CLEAR' }));
  allPass(screenChecks(xml({ view: 'foreign-view' }), xml({ screen: null, view: 'foreign-view' }), 21, { operation: 'CLEAR', preserveOtherView: true }));
  assert.ok(screenChecks(xml(), xml({ screen: null }), 21, { operation: 'CLEAR' }).some((c) => !c.ok));
  assert.ok(screenChecks(xml(), xml({ screen: '202' }).replace('auto="false"', 'auto="true"'), 21, { operation: 'SET', screenId: 202 }).some((c) => !c.ok));
});

test('L1 complete verifier positive uses real artifact bytes, terminal result and readback', async (t) => {
  const { ctx } = await contextFor(t);
  allPass(await verifyComment(ctx, 'L1'));
});

test('L1 migrated configure request rejects screens, conditions and extra validators even with clean XML readback', async (t) => {
  const { ctx } = await contextFor(t);
  const defects = [
    (args) => { args.newScreens = [{ key: 'extra', name: 'unasked', fieldIds: ['description'] }]; },
    (args) => { args.transitionRules[0].screen = { action: 'SET', screenId: '101' }; },
    (args) => { args.transitionRules[0].addConditions = { operator: 'AND', rules: [{ template: 'group', parameters: { group: 'jira-administrators' } }] }; },
    (args) => { args.transitionRules[0].addValidators.push({ template: 'required_fields', parameters: { fieldIds: ['description'] } }); },
    (args) => { args.transitionRules.push({ actionId: 22, addValidators: [{ template: 'comment_required' }] }); },
  ];
  for (const defect of defects) {
    const changed = cloneContext(ctx);
    defect(changed.trace.find((entry) => entry.seq === 2).arguments);
    const checks = await verifyComment(changed, 'L1');
    assert.equal(checks.find((check) => check.name.startsWith('comment-add: ONE configure_workflow request')).ok, false);
  }
});

test('L1 missing XML is BLOCKED, even when every operation says success', async (t) => {
  const { ctx } = await contextFor(t); delete ctx.evidence.steps[0].afterXml;
  await assert.rejects(verifyComment(ctx, 'L1'), /artifact path/u);
});

test('L1 status/readback echo without actual added XML is not a pass', async (t) => {
  const { ctx, save, before } = await contextFor(t); ctx.evidence.steps[0].afterXml = await save(before);
  assert.ok((await verifyComment(ctx, 'L1')).some((c) => !c.ok));
});

test('artifact hash mismatch and missing files cannot be accepted as current evidence', async (t) => {
  const { save, directory } = await fixture(t), ref = await save(xml());
  await assert.rejects(artifact({ ...ref, sha256: '0'.repeat(64) }, directory), /hash mismatch/u);
  await assert.rejects(artifact({ path: 'missing.xml', sha256: '0'.repeat(64) }, directory), /ENOENT/u);
});

test('approval checker requires actual human source, shown table, later yes and terminal operation', () => {
  const p = preview(1, 1, 'configure_workflow'), trace = [p, ...apply(2, 2, p)];
  allPass(approvalChecks(trace, conversation('Preview', 'Да, применяй')));
  assert.ok(approvalChecks(trace, conversation('Preview', 'No')).some((c) => !c.ok));
  const simulated = conversation('Preview', 'Да'); simulated[2].source = 'simulated';
  assert.ok(approvalChecks(trace, simulated).some((c) => !c.ok));
  assert.ok(approvalChecks(trace.slice(0, -1), conversation('Preview', 'Да')).some((c) => !c.ok));
});

test('all seven missing UI observations fail; prose success flags never count', async () => {
  const checks = await uiChecks({ ui: [], uiPassed: true }, '.', { workflow: WORKFLOW, actionId: 21 });
  assert.ok(checks.every((c) => !c.ok && c.detail.includes('NOT_VERIFIED')));
});

async function uiFixture(t) {
  const f = await fixture(t), target = noScreenFixture(xml({ comment: true }));
  const old = [{ id: '1', body: 'an existing issue comment' }];
  const issue = (key, status, list = old) => ({ key, fields: { status: { id: status }, comment: { comments: list } } });
  const list = [];
  for (const kind of ['dialog', 'empty', 'old-comment', 'nonempty', 'whitespace', 'no-screen']) {
    const comment = kind === 'nonempty' ? 'new transition comment' : kind === 'whitespace' ? '   ' : '';
    const issueKey = `L005T-${list.length + 1}`;
    const beforeIssue = await f.save(issue(issueKey, '1')), unchanged = await f.save(issue(issueKey, '1'));
    const html = kind === 'dialog' ? `<div role="dialog">${issueKey}<textarea name="comment"></textarea></div>`
      : `<div>${issueKey} ${kind === 'no-screen' ? 'no screen is in use' : kind === 'nonempty' ? 'Done' : 'Please provide a comment for this transition.'}</div>`;
    list.push({ kind, workflow: WORKFLOW, actionId: kind === 'no-screen' ? 22 : 21, issueKey, comment,
      page: await f.save(html), beforeIssue, afterIssue: kind === 'nonempty' ? await f.save(issue(issueKey, '2', [...old, { id: '2', body: comment }])) : unchanged,
      ...(kind === 'no-screen' ? { beforeXml: await f.save(target) } : {}) });
  }
  list.push({ kind: 'sr-open-save', workflow: WORKFLOW, actionId: 21,
    openPage: await f.save('<form><h2>Require a comment on transition</h2><button>Update</button></form>'),
    page: await f.save('<div>Require a comment on transition</div>'), beforeXml: await f.save(target), afterXml: await f.save(target), readback: await f.save(view(true)) });
  return { ...f, e: { noScreenActionId: 22, ui: list }, p: { workflow: WORKFLOW, actionId: 21, publishedXml: target } };
}

test('UI evidence positive checks actual DOM, issue status/comments, old comment and SR UUID retention', async (t) => {
  const { e, p, directory } = await uiFixture(t);
  allPass(await uiChecks(e, directory, p));
});

test('independent transition observations cannot silently reuse an issue after its status changed', async (t) => {
  const { e, p, directory } = await uiFixture(t);
  const whitespace = e.ui.find(event => event.kind === 'whitespace');
  const nonempty = e.ui.find(event => event.kind === 'nonempty');
  Object.assign(whitespace, nonempty, { kind: 'whitespace', comment: '   ' });
  const checks = await uiChecks(e, directory, p);
  assert.equal(checks.find(check => check.name.startsWith('independent transition')).ok, false);
});

test('nonempty success must save current comment; old-comment rejection must preserve status', async (t) => {
  const { e, p, directory, save } = await uiFixture(t);
  e.ui.find((v) => v.kind === 'nonempty').afterIssue = e.ui.find((v) => v.kind === 'nonempty').beforeIssue;
  const old = e.ui.find(v => v.kind === 'old-comment');
  const issue = await artifact(old.beforeIssue, directory, true);
  issue.fields.status.id = 'changed';
  old.afterIssue = await save(issue);
  const checks = await uiChecks(e, directory, p);
  assert.equal(checks.find(check => check.name.startsWith('nonempty current')).ok, false);
  assert.equal(checks.find(check => check.name.startsWith('UI old-comment:')).ok, false);
});

test('SR UI save records actual generated ID, while missing XML or a substituted original cannot pass', async (t) => {
  const { e, p, directory, save } = await uiFixture(t), event = e.ui.find((v) => v.kind === 'sr-open-save');
  const beforeRef = event.beforeXml, original = await artifact(beforeRef, directory);
  const afterId = 'f'.repeat(40);
  event.afterXml = await save(original.replace(UUID, afterId));
  const checks = await uiChecks(e, directory, p);
  allPass(checks);
  assert.ok(checks.find(check => check.name.startsWith('ScriptRunner save rereads')).detail.includes(afterId));
  event.beforeXml = event.afterXml;
  await assert.rejects(uiChecks(e, directory, p), /editor must open the validator added/u);
  delete event.beforeXml;
  await assert.rejects(uiChecks(e, directory, p), /artifact path/u);
});

test('read-only preparation refuses absent optional fixtures before any mutation path', async () => {
  let calls = 0;
  await assert.rejects(prepareComment({ evidence: null, tool: async () => { calls++; } }, 'L3'), /BLOCKED/u);
  assert.equal(calls, 0);
});

test('L3 verifier checks refused exact existing validator and same-id view repair through ONE configure_workflow plan each', async (t) => {
  const f = await fixture(t);
  const legacyAbsent = input => input.replace('name="Other" view="foreign-view"', 'name="Other"');
  const before = legacyAbsent(xml({ comment: true, view: null })), after = legacyAbsent(xml({ comment: true }));
  const bothRepaired = after.replace('id="22" name="Other"', 'id="22" name="Other" view="fieldscreen"');
  const refusal = preview(2, 1, CFG, request(21), { status: 'FAILED', errorCode: 'TRANSITION_SCREEN_MISSING', planId: null, message: 'requires view="fieldscreen"' });
  // Existing exact validator: screen SET and comment_required together; the diff repairs the view and adds no second validator.
  const repair = preview(3, 2, CFG, request(21, { screenId: 101 }), { diff: ["SET_SCREEN: transition 21 screen 101 -> 101; view 'none' -> fieldscreen"], targetVersion: 'ACTIVE' });
  const repeat = preview(7, 4, CFG, request(21), { status: 'UNCHANGED', planId: null });
  const absentRefusal = preview(2.5, 1, CFG, request(22), { status: 'FAILED', errorCode: 'TRANSITION_SCREEN_MISSING', planId: null });
  // Action without validator: screen-only SET, the comment is not added; the later comment preview stays unapplied.
  const absentRepair = preview(8, 4, CFG, request(22, { screenId: 101, comment: false }), { diff: ["SET_SCREEN: transition 22 screen 101 -> 101; view 'none' -> fieldscreen"], targetVersion: 'ACTIVE' });
  const absentReady = preview(12, 6, CFG, request(22));
  const ctx = { evidenceDirectory: f.directory, evidence: { scenario: 'L3', workflow: WORKFLOW, actionId: 21, screenIds: [101], missingCommentActionId: 22,
    steps: [{ name: 'same-id-repair', previewSeq: 3, operationId: 'o4', beforeXml: await f.save(before), afterXml: await f.save(after) },
      { name: 'missing-comment-repair', previewSeq: 8, operationId: 'o9', beforeXml: await f.save(after), afterXml: await f.save(bothRepaired) }] },
    prepared: { scenario: 'L3', workflow: WORKFLOW, actionId: 21, screenIds: [101], missingCommentActionId: 22, xml: before, view: view(true, 'meta without view') },
    trace: [read(1), refusal, absentRefusal, repair, ...apply(4, 3, repair), read(6, 4), repeat,
      absentRepair, ...apply(9, 5, absentRepair), read(11, 6), absentReady],
    conversation: conversation('Inspect', 'Repair', 'Да', 'Repair absent', 'Да', 'Fresh previews'),
    tool: async () => view(true), adminPage: async () => ({ status: 200, text: bothRepaired }) };
  allPass(await verifyComment(ctx, 'L3'));
  const withTrace = (mutate) => { const copy = cloneContext(ctx); mutate(copy.trace); return copy; };
  const failing = async (context) => (await verifyComment(context, 'L3')).some((c) => !c.ok);
  // The repair must be a configure_workflow plan, not a separate edit_workflow screen change.
  await assert.rejects(verifyComment(withTrace((trace) => { const p = trace.find((e) => e.seq === 3); p.tool = 'edit_workflow';
    p.arguments = { dryRun: true, workflow: WORKFLOW, changes: [{ op: 'UPDATE_TRANSITION', actionId: 21, screen: { action: 'SET', screenId: 101 } }] };
    trace.find((e) => e.seq === 4).arguments.toolName = 'edit_workflow'; }), 'L3'), /BLOCKED/u);
  // The same-id repair must carry the comment_required rule and must not add a validator.
  assert.ok(await failing(withTrace((trace) => { trace.find((e) => e.seq === 3).arguments = { dryRun: true, ...request(21, { screenId: 101, comment: false }) }; })));
  assert.ok(await failing(withTrace((trace) => { trace.find((e) => e.seq === 3).result.diff.push('ADD_VALIDATOR: 21 comment_required'); })));
  // The absent-validator repair is screen-only: adding comment_required in the same plan is not the approved branch.
  assert.ok(await failing(withTrace((trace) => { trace.find((e) => e.seq === 8).arguments = { dryRun: true, ...request(22, { screenId: 101 }) }; })));
  // A stale expectedRevision of a repair plan is not accepted.
  assert.ok(await failing(withTrace((trace) => { trace.find((e) => e.seq === 3).arguments.existing.expectedRevision = 'r0'; })));
  // The comment preview on the repaired transition must stay unapplied and WOULD_CHANGE.
  assert.ok(await failing(withTrace((trace) => { trace.find((e) => e.seq === 12).result = { status: 'UNCHANGED', planId: null }; })));
  ctx.evidence.steps[0].afterXml = await f.save(after.replace('foreign.Function', 'changed.Function'));
  assert.ok((await verifyComment(ctx, 'L3')).some((c) => !c.ok));
  ctx.evidence.steps.pop();
  await assert.rejects(verifyComment(ctx, 'L3'), /missing-comment-repair XML boundary/u);
});

test('L4 compares stale configure_workflow result to separately approved external CLEAR, not initial state', async (t) => {
  const f = await fixture(t), before = xml(), after = xml({ screen: null, view: null });
  const comment = preview(2, 1, CFG, request(21));
  const clear = preview(3, 2, CFG, structural([{ op: 'UPDATE_TRANSITION', actionId: 21, screen: { action: 'CLEAR' } }]));
  const trace = [read(1), comment, clear, ...apply(4, 3, clear, { status: 'UPDATED_INACTIVE', ...saved }), ...apply(6, 4, comment, { status: 'FAILED', errorCode: 'PREVIEW_STALE' }, 'BLOCKED')];
  const ctx = { evidenceDirectory: f.directory, evidence: { scenario: 'L4', workflow: WORKFLOW, actionId: 21, screenIds: [101],
    steps: [{ name: 'external-screen-clear', previewSeq: 3, operationId: 'o4', beforeXml: await f.save(before), afterXml: await f.save(after) }] },
    prepared: { scenario: 'L4', workflow: WORKFLOW, actionId: 21, screenIds: [101], xml: before }, lifecycle: { snapshots: [{ state }] }, trace,
    conversation: conversation('Preview', 'Clear only', 'Да', 'Да, apply the previously shown stale plan'), tool: async () => view(), adminPage: async () => ({ status: 200, text: after }) };
  allPass(await verifyComment(ctx, 'L4'));
  ctx.adminPage = async () => ({ status: 200, text: xml({ comment: true, screen: null, view: null }) });
  assert.ok((await verifyComment(ctx, 'L4')).some((c) => !c.ok));
  // A stale result that is not PREVIEW_STALE (the old plan went through) is not accepted.
  ctx.adminPage = async () => ({ status: 200, text: after });
  const applied = cloneContext(ctx); applied.trace = [read(1), comment, clear, ...apply(4, 3, clear, { status: 'UPDATED_INACTIVE', ...saved }), ...apply(6, 4, comment, { status: 'UPDATED_INACTIVE' })];
  assert.ok((await verifyComment(applied, 'L4')).some((c) => !c.ok));
});

test('L2 complete draft/publication verifier binds ONE configure_workflow plan with screen SET and comment, and real UI artifacts', async (t) => {
  const f = await uiFixture(t);
  const before = noScreenFixture(xml({ screen: null, view: null })), after = noScreenFixture(xml({ comment: true }));
  const comment = preview(2, 1, CFG, request(21, { version: 'ACTIVE', screenId: 101 }), { targetVersion: 'DRAFT', transitions: previewedTransition(),
    diff: ["SET_SCREEN: transition 21 screen none -> 101; view 'none' -> fieldscreen", 'ADD_VALIDATOR: 21 comment_required'] });
  const refused = preview(6, 3, CFG, request(21), { status: 'FAILED', errorCode: 'DRAFT_EXISTS', planId: null });
  const publish = preview(7, 4, 'publish_workflow_draft', {}, { status: 'WOULD_PUBLISH' });
  const repeat = preview(11, 6, CFG, request(21), { status: 'UNCHANGED', planId: null });
  const trace = [read(1), comment, ...apply(3, 2, comment, { status: 'DRAFT_SAVED', targetVersion: 'DRAFT' }),
    read(5, 3), refused, publish, ...apply(8, 5, publish, { status: 'PUBLISHED' }), read(10, 6), repeat];
  const ctx = { evidenceDirectory: f.directory, evidence: { ...f.e, scenario: 'L2', workflow: WORKFLOW, actionId: 21, screenIds: [101],
    steps: [{ name: 'comment-add', beforeXml: await f.save(before), afterXml: await f.save(after), previewSeq: 2, operationId: 'o3' }] },
    prepared: { scenario: 'L2', workflow: WORKFLOW, actionId: 21, screenIds: [101], noScreenActionId: 22, xml: before, view: view() },
    lifecycle: { snapshots: [{ seq: 5, activeXml: before, draftXml: after, state: { ...state, active: true, hasDraft: true } },
      { seq: 11, activeXml: after, state }] }, trace, conversation: conversation('Plan', 'Да', 'Check draft', 'Now publish', 'Да', 'Repeat'),
    tool: async () => view(true), adminPage: async () => ({ status: 200, text: after }) };
  allPass(await verifyComment(ctx, 'L2'));
  const failing = async (context) => (await verifyComment(context, 'L2')).some((c) => !c.ok);
  const mutated = (mutate) => { const copy = cloneContext(ctx); mutate(copy); return copy; };
  ctx.lifecycle.snapshots[0].activeXml = after;
  assert.ok(await failing(ctx));
  ctx.lifecycle.snapshots[0].activeXml = before;
  // Without the screen SET in the same plan (comment only) the approved L2 request shape is not met.
  assert.ok(await failing(mutated((c) => { c.trace.find((e) => e.seq === 2).arguments = { dryRun: true, ...request(21, { version: 'ACTIVE' }) }; })));
  // A separate edit_workflow screen change is not an allowed mutation of L2 any more.
  assert.ok(await failing(mutated((c) => { c.trace.push(preview(20, 7, 'edit_workflow', { changes: [] })); })));
  assert.ok(await failing(mutated((c) => { c.trace.find((e) => e.seq === 3).arguments.toolName = 'edit_workflow'; })));
  // SET must write metadata and view together: a saved descriptor with the screen metadata but without the view is not a pass.
  const metaOnly = await f.save(noScreenFixture(xml({ comment: true, view: null })));
  assert.ok(await failing(mutated((c) => { c.evidence.steps[0].afterXml = metaOnly; })));
});

test('L5 no-model integration verifier checks continuous create/ADD/SET/replace/CLEAR XML readbacks', async (t) => {
  const f = await fixture(t), createdWorkflow = 'LOCAL-005-separate-created';
  const created = '<workflow><action id="99" name="Created" view="fieldscreen"><meta name="jira.fieldscreen.id">101</meta></action><action id="100" name="Without screen"/></workflow>';
  const added = '<action id="24" name="Added" view="fieldscreen"><meta name="jira.fieldscreen.id">101</meta></action>';
  const base = xml({ screen: null, view: null });
  const withAdded = (options = {}) => xml({ screen: null, view: null, ...options, extra: added });
  const addedXml = withAdded(), setXml = withAdded({ screen: '101', view: 'fieldscreen' }), replacement = withAdded({ screen: '202', view: 'fieldscreen' });
  const cleared = withAdded(), final = cleared.replace('<action id="22" name="Other" view="foreign-view"><meta name="jira.fieldscreen.id">101</meta>',
    '<action id="22" name="Other" view="foreign-view">');
  const trace = [], turns = [], steps = [];
  async function step(name, before, after, toolName, arguments_) {
    const seq = trace.length + 1, turn = turns.length + 1, p = preview(seq, turn, toolName, arguments_);
    trace.push(p, ...apply(seq + 1, turn + 1, p, { status: arguments_.create ? 'CREATED' : 'UPDATED_INACTIVE', ...saved }));
    turns.push('Preview the next step', 'Да');
    steps.push({ name, beforeXml: await f.save(before), afterXml: await f.save(after), previewSeq: seq, operationId: `o${seq + 1}` });
  }
  const createRequest = (without = {}) => ({ create: { name: createdWorkflow, initialStatus: 'Open',
    statuses: [{ name: 'Open', category: 'TO_DO' }, { name: 'Done', category: 'DONE' }],
    transitions: [{ key: 'created', name: 'Created', from: 'Open', to: 'Done', screen: '101' }, { key: 'plain', name: 'Without screen', from: 'Open', to: 'Done', ...without }] } });
  await step('create-with-screen', base, created, CFG, createRequest());
  await step('add-with-screen', base, addedXml, CFG, structural([{ op: 'ADD_TRANSITION', name: 'Added', source: { stepId: 1 }, targetStepId: 1, screen: { action: 'SET', screenId: '101' } }]));
  await step('set', addedXml, setXml, CFG, structural([{ op: 'UPDATE_TRANSITION', actionId: 21, screen: { action: 'SET', screenId: '101' } }]));
  await step('replace', setXml, replacement, CFG, structural([{ op: 'UPDATE_TRANSITION', actionId: 21, screen: { action: 'SET', screenId: '202' } }]));
  await step('clear', replacement, cleared, CFG, structural([{ op: 'UPDATE_TRANSITION', actionId: 21, screen: { action: 'CLEAR' } }]));
  await step('other-view-clear', cleared, final, CFG, structural([{ op: 'UPDATE_TRANSITION', actionId: 22, screen: { action: 'CLEAR' } }]));
  const ctx = { evidenceDirectory: f.directory, evidence: { scenario: 'L5', workflow: WORKFLOW, actionId: 21, screenIds: [101, 202], createdWorkflow,
    createActionId: 99, addActionId: 24, otherViewActionId: 22, noScreenActionId: 23, steps },
    prepared: { scenario: 'L5', workflow: WORKFLOW, actionId: 21, screenIds: [101, 202], createdWorkflow, otherViewActionId: 22, noScreenActionId: 23, xml: base }, trace, conversation: conversation(...turns),
    tool: async (name) => name === 'get_workflow' ? state : view(), adminPage: async (url) => ({ status: 200, text: url.includes(createdWorkflow) ? created : final }) };
  allPass(await verifyComment(ctx, 'L5'));
  const failing = async (context) => (await verifyComment(context, 'L5')).some((c) => !c.ok);
  const original = ctx.evidence.steps[2].beforeXml;
  ctx.evidence.steps[2].beforeXml = await f.save(base);
  assert.ok(await failing(ctx));
  ctx.evidence.steps[2].beforeXml = original;
  allPass(await verifyComment(ctx, 'L5'));
  // The create plan must be configure_workflow create: giving the transition without screen a screen breaks the no-screen expectation.
  const withScreenEverywhere = cloneContext(ctx); withScreenEverywhere.trace.find((e) => e.seq === 1).arguments = { dryRun: true, ...createRequest({ screen: '101' }) };
  assert.ok(await failing(withScreenEverywhere));
  // Only create may target the separate createdWorkflow; substituting the main existing workflow is outside scope.
  const edited = cloneContext(ctx); edited.trace.find((e) => e.seq === 1).arguments = { dryRun: true, ...request(21) };
  assert.ok(await failing(edited));
  for (const extra of [
    { transitionRules: [{ transitionKey: 'created', addValidators: [{ template: 'comment_required' }] }] },
    { transitionRules: [{ transitionKey: 'created', addConditions: { operator: 'AND', rules: [{ template: 'group', group: 'jira-administrators' }] } }] },
    { newScreens: [{ key: 'extra', name: 'LOCAL-005-extra', fieldIds: ['summary'] }],
      transitionRules: [{ transitionKey: 'created', screen: { action: 'SET', screenKey: 'extra' } }] },
  ]) {
    const outsideScope = cloneContext(ctx);
    outsideScope.trace.find((e) => e.seq === 1).arguments = { dryRun: true, ...createRequest(), ...extra };
    const checks = await verifyComment(outsideScope, 'L5');
    assert.ok(checks.some((c) => !c.ok),
      `L5 must reject unrequested configure parts: ${JSON.stringify(extra)}`);
  }
  // The create step must be the configure_workflow tool: another tool in its place is rejected as unbound evidence.
  const substituted = cloneContext(ctx); substituted.trace.find((e) => e.seq === 1).tool = 'edit_workflow';
  await assert.rejects(verifyComment(substituted, 'L5'), /BLOCKED/u);
});

test('final evidence cannot switch approved workflow/action/screen scope', async (t) => {
  const { ctx } = await contextFor(t); ctx.evidence.actionId = 999;
  await assert.rejects(verifyComment(ctx, 'L1'), /retain the prepared/u);
});

test('preparation uses only reads, returns actual IDs and checks complete layout without Comment', async (t) => {
  const f = await fixture(t), copy = preview(1, 1, 'copy_workflow', { newName: WORKFLOW });
  const screen = preview(4, 3, 'create_screen', { name: 'LOCAL-005-fixture-screen' });
  const setupTrace = [copy, ...apply(2, 2, copy, { status: 'CREATED' }), screen, ...apply(5, 4, screen, { status: 'CREATED', screenId: 101 })];
  const evidence = { schemaVersion: 1, scenario: 'L1', workflow: WORKFLOW, actionId: 21, screenIds: [101],
    setupTrace: await f.save(setupTrace), setupConversation: await f.save(conversation('Copy', 'Да', 'Screen', 'Да')) };
  let layout = { status: 'READ', complete: true, screen: { id: 101, name: 'LOCAL-005-fixture-screen' }, tabs: [{ fields: [{ fieldId: 'summary' }] }] };
  const called = [];
  const context = { evidence, evidenceDirectory: f.directory, runId: 'isolated', tool: async (name) => {
    called.push(name);
    if (name === 'get_workflow') return state;
    if (name === 'get_workflow_validators') return view();
    if (name === 'get_screen_contents') return layout;
    throw new Error(`unexpected mutation ${name}`);
  }, adminPage: async () => ({ status: 200, text: xml() }) };
  const prepared = await prepareComment(context, 'L1');
  assert.equal(prepared.actionId, 21); assert.deepEqual(prepared.screenIds, [101]);
  assert.deepEqual(new Set(called), new Set(['get_workflow', 'get_workflow_validators', 'get_screen_contents']));
  layout = { ...layout, tabs: [{ fields: [{ fieldId: 'comment' }] }] };
  await assert.rejects(prepareComment(context, 'L1'), /Comment must be absent/u);
  layout = { ...layout, complete: false, tabs: [] };
  await assert.rejects(prepareComment(context, 'L1'), /complete actual screen/u);
});

test('fixture workflow created by configure_workflow create is accepted only with CREATED, never EXISTS, a foreign name or the existing form', async (t) => {
  const f = await fixture(t), create = (arguments_) => preview(1, 1, CFG, arguments_);
  const screen = preview(4, 3, 'create_screen', { name: 'LOCAL-005-fixture-screen' });
  const named = { create: { name: WORKFLOW, initialStatus: 'Open', statuses: [], transitions: [] } };
  const contextFor = async (setup, outcome = { status: 'CREATED' }) => {
    const setupTrace = [setup, ...apply(2, 2, setup, outcome), screen, ...apply(5, 4, screen, { status: 'CREATED', screenId: 101 })];
    return { evidence: { schemaVersion: 1, scenario: 'L1', workflow: WORKFLOW, actionId: 21, screenIds: [101],
      setupTrace: await f.save(setupTrace), setupConversation: await f.save(conversation('Create', 'Да', 'Screen', 'Да')) },
    evidenceDirectory: f.directory, runId: 'isolated', adminPage: async () => ({ status: 200, text: xml() }),
    tool: async (name) => name === 'get_workflow' ? state : name === 'get_workflow_validators' ? view()
      : { status: 'READ', complete: true, screen: { id: 101, name: 'LOCAL-005-fixture-screen' }, tabs: [{ fields: [{ fieldId: 'summary' }] }] } };
  };
  assert.equal((await prepareComment(await contextFor(create(named)), 'L1')).actionId, 21);
  await assert.rejects(async () => prepareComment(await contextFor(create(named), { status: 'EXISTS' }), 'L1'), /originate in approved CREATED/u);
  await assert.rejects(async () => prepareComment(await contextFor(create({ create: { ...named.create, name: 'LOCAL-005-other' } })), 'L1'), /originate in approved CREATED/u);
  await assert.rejects(async () => prepareComment(await contextFor(create(request(21))), 'L1'), /originate in approved CREATED/u);
});
