// Offline check of the itss-mappings live verifiers on synthetic Jira state and tool traces (no Jira, no models).
import test from 'node:test';
import assert from 'node:assert/strict';
import { verifyPrivate, verifySharedDefault, observeAfterApply, previewDefects } from './itss-mappings-common.mjs';
import hook from './scenarios/itss-mappings-shared-default/hooks.mjs';

const TOOL = 'set_issue_type_screen_scheme_mappings';
const json = (value) => JSON.stringify(value);
const ref = (id, name) => ({ id, name });
const eff = (id, name, viaDefault) => ({ id, name, viaDefault });
const issueType = (id, name) => ({ id, name });

/** Fake Jira: ITSS rows per id, project keys per id, effective schemes per project. */
function jira({ itss, listed, rows, projects }) {
  const html = {
    '/secure/admin/ViewIssueTypeScreenSchemes.jspa': Object.keys(itss).map((id) => `<tr data-issue-type-screen-scheme-name="${id}"><td><a href="ConfigureIssueTypeScreenScheme.jspa?id=${id}"></a></td><td>${(listed[id] ?? []).map((key) => `<a href="/jira/plugins/servlet/project-config/${key}/summary">x</a>`).join('')}</td></tr>`).join(''),
    '/secure/admin/ViewFieldScreenSchemes.jspa': ['1', '2', '3', '4'].map((id) => `<a href="ConfigureFieldScreenScheme.jspa?id=${id}"></a>`).join(''),
  };
  for (const [id, table] of Object.entries(rows)) {
    html[`/secure/admin/ConfigureIssueTypeScreenScheme.jspa?id=${id}`] = `<table id="issue-type-table"><tbody>${table.map(({ type, scheme }) =>
      `<tr data-default-association="${type === 'DEFAULT'}"><td><strong data-scheme-field=issue-type>${type === 'DEFAULT' ? 'Default' : type}</strong></td><td><a href="ConfigureFieldScreenScheme.jspa?id=${scheme}"></a></td></tr>`).join('')}</tbody></table>`;
  }
  return {
    adminPage: async (route) => { if (!(route in html)) throw new Error(route); return { text: html[route] }; },
    tool: async (name, args) => { assert.equal(name, 'get_project_screens'); return projects[args.project]; },
  };
}
const read = (scheme, types) => ({ scheme, issueTypes: Object.entries(types).map(([name, value]) => ({ issueType: { name }, screenScheme: value })) });
const entry = (seq, turn, tool, args, result) => ({ seq, turn, tool, arguments: args, result: json(result) });
const preview = (seq, turn, planId, result, args = {}) => entry(seq, turn, TOOL, { dryRun: true, ...args }, { dryRun: true, planId, ...result });
const start = (seq, turn, planId, operationId) => entry(seq, turn, 'start_operation', { toolName: TOOL, arguments: { dryRun: false, planId } }, { operationId, status: 'QUEUED' });
const done = (seq, turn, operationId, result) => entry(seq, turn, 'get_operation', { operationId }, { operationId, status: 'COMPLETED', result });
const table = (...cells) => `| a |\n| - |\n${cells.map((cell) => `| ${cell} |`).join('\n')}\n\nПодтверждаете?`;

// --- itss-mappings-private ----------------------------------------------------------------------------------------
const P = { A: issueType('11', 'L016-X-TypeA'), B: issueType('12', 'L016-X-TypeB'), C: issueType('13', 'L016-X-TypeC') };
const S = { S1: ref('21', 'L016-X-scheme-1'), S2: ref('22', 'L016-X-scheme-2'), D: ref('23', 'D') };
const privatePrepared = { project: 'L016PX', types: P, schemes: S, itss: ref('50', 'ITSS'), itssIds: ['50'], screenSchemeIds: ['1', '2', '3', '4'],
  table: [{ type: 'DEFAULT', scheme: '23' }, { type: P.A.name, scheme: '21' }, { type: P.B.name, scheme: '22' }] };
const privateRow = (type, action, before, after, extra) => ({ issueType: type, action, change: 'CHANGE', before, after, ...extra });
function privateTrace({ applied = 'APPLIED', mutate = false } = {}) {
  const plan = { status: 'WOULD_CHANGE', issueTypeScreenScheme: { id: 50, name: 'ITSS', isDefault: false },
    rows: [privateRow(P.A, 'REPLACE', eff(21, S.S1.name, false), eff(22, S.S2.name, false)), privateRow(P.B, 'REMOVE', eff(22, S.S2.name, false), eff(23, 'D', true))],
    projects: { linked: 1, changing: 1, truncated: false, listed: [{ key: 'L016PX', archived: false, changedIssueTypes: 2 }] }, scopeText: 'N и M', limitations: ['без отката'] };
  const result = { status: 'CHANGED', readBack: true, issueTypeScreenScheme: { id: 50 }, rows: [
    { issueType: P.A, result: applied, observed: { id: 22 } }, { issueType: P.B, result: 'APPLIED', observed: null }] };
  return [
    entry(1, 1, 'get_project_screens', { project: 'L016PX' }, {}),
    preview(2, 1, 'plan-1', plan, { issueTypeScreenScheme: 'ITSS' }),
    start(3, 2, 'plan-1', 'op-1'), done(4, 2, 'op-1', result),
    ...(mutate ? [entry(5, 2, 'create_screen', { dryRun: true }, {})] : []),
  ];
}
const privateJira = (final = true) => jira({ itss: { 50: 1 }, listed: { 50: ['L016PX'] },
  rows: { 50: final ? [{ type: 'DEFAULT', scheme: '23' }, { type: P.A.name, scheme: '22' }] : privatePrepared.table },
  projects: { L016PX: read({ id: 50, isDefault: false, otherProjects: 0 }, final
    ? { [P.A.name]: eff(22, S.S2.name, false), [P.B.name]: eff(23, 'D', true), [P.C.name]: eff(23, 'D', true) }
    : { [P.A.name]: eff(21, S.S1.name, false), [P.B.name]: eff(22, S.S2.name, false), [P.C.name]: eff(23, 'D', true) }) } });
const conversationOf = (...replies) => replies.flatMap((content) => [{ role: 'user', content: 'x' }, { role: 'assistant', content }]);
const failed = (checks, class_) => checks.filter((item) => !item.ok && (class_ ? item.class === class_ : item.class !== 'advisory')).map((item) => item.name);

test('private verifier passes the exact dialogue and fails state or protocol defects as contract', async () => {
  const good = await verifyPrivate({ prepared: privatePrepared, trace: privateTrace(), conversation: conversationOf(table(P.A.name, P.B.name, S.S2.name, 'N=1, M=1'), 'Готово'), ...privateJira() });
  assert.deepEqual(failed(good), []);
  assert.ok(good.some((item) => item.class === 'advisory'));
  const stateNotChanged = await verifyPrivate({ prepared: privatePrepared, trace: privateTrace(), conversation: [], ...privateJira(false) });
  assert.ok(failed(stateNotChanged).length >= 2);
  const rowNotApplied = await verifyPrivate({ prepared: privatePrepared, trace: privateTrace({ applied: 'UNKNOWN' }), conversation: [], ...privateJira() });
  assert.ok(failed(rowNotApplied).some((name) => name.startsWith('apply 1')));
  const otherWrite = await verifyPrivate({ prepared: privatePrepared, trace: privateTrace({ mutate: true }), conversation: [], ...privateJira() });
  assert.ok(failed(otherWrite).some((name) => name.startsWith('других записей нет')));
});

test('presentation failures of the private dialogue are advisory only', async () => {
  const checks = await verifyPrivate({ prepared: privatePrepared, trace: privateTrace(), conversation: conversationOf('без таблицы', 'Готово'), ...privateJira() });
  assert.deepEqual(failed(checks), []);
  assert.ok(failed(checks, 'advisory').length >= 1);
});

// --- itss-mappings-shared-default ---------------------------------------------------------------------------------
const T = issueType('31', 'L016-X-Type');
const SS = { S: ref('41', 'L016-X-S'), D: ref('42', 'Default scheme') };
const sharedPrepared = { projects: { A: 'L016AX', B: 'L016BX', C: 'L016CX' }, type: T, itss: ref('1', 'Default Issue Type Screen Scheme'), privateItss: '60', schemes: SS,
  itssIds: ['1', '60'], screenSchemeIds: ['1', '2', '3', '4'], table: [{ type: 'DEFAULT', scheme: '42' }],
  binding: { defaultBefore: [], bound: [
    { key: 'L016AX', projectId: '10800', schemeId: '1', before: [], after: ['L016AX'] },
    { key: 'L016BX', projectId: '10801', schemeId: '1', before: ['L016AX'], after: ['L016AX', 'L016BX'] }] } };
const withS = [{ type: 'DEFAULT', scheme: '42' }, { type: T.name, scheme: '41' }];
const sharedPlan = (action, before, after, linked = 2) => ({ status: 'WOULD_CHANGE', issueTypeScreenScheme: { id: 1, name: 'Default Issue Type Screen Scheme', isDefault: true },
  rows: [{ issueType: T, action, change: 'CHANGE', before, after }],
  projects: { linked, changing: 2, truncated: false, listed: [{ key: 'L016AX', archived: false, changedIssueTypes: 1 }, { key: 'L016BX', archived: false, changedIssueTypes: 1 }] },
  scopeText: 'N и M', limitations: ['без отката'] });
const sharedResult = (observed) => ({ status: 'CHANGED', readBack: true, issueTypeScreenScheme: { id: 1 }, rows: [{ issueType: T, result: 'APPLIED', observed }] });
const sharedArgs = (action, issueType = T.name, scheme = 'Default Issue Type Screen Scheme') => ({ issueTypeScreenScheme: scheme, rows: [{ action, issueType, screenScheme: SS.S.name }] });
const sharedTrace = () => [
  preview(1, 1, 'p1', sharedPlan('ADD', eff(42, SS.D.name, true), eff(41, SS.S.name, false)), sharedArgs('ADD')), start(2, 2, 'p1', 'o1'), done(3, 2, 'o1', sharedResult({ id: 41 })),
  preview(4, 3, 'p2', sharedPlan('REMOVE', eff(41, SS.S.name, false), eff(42, SS.D.name, true)), sharedArgs('REMOVE')), start(5, 4, 'p2', 'o2'), done(6, 4, 'o2', sharedResult(null)),
];
const sharedJira = (rows) => jira({ itss: { 1: 1, 60: 1 }, listed: { 1: ['L016AX', 'L016BX'], 60: ['L016CX'] }, rows: { 1: rows },
  projects: { L016AX: read({ id: 1, isDefault: true }, { [T.name]: eff(42, SS.D.name, true) }), L016BX: read({ id: 1, isDefault: true }, { [T.name]: eff(42, SS.D.name, true) }),
    L016CX: read({ id: 60, isDefault: false, otherProjects: 0 }, { [T.name]: eff(41, SS.S.name, false) }) } });
const observation = (rows, status = 'CHANGED') => ({ status, table: rows, A: { id: '41' }, B: { id: '41' }, C: { id: '41' } });

test('shared verifier passes ADD then REMOVE and fails when the temporary row stays or the plan is applied twice', async () => {
  const lifecycle = { observed: [observation(withS), { status: 'CHANGED', table: sharedPrepared.table, A: { id: '42' }, B: { id: '42' }, C: { id: '41' } }] };
  const common = { prepared: sharedPrepared, conversation: conversationOf(table(T.name, SS.S.name, 'N=2 M=2'), 'ok', 'x', 'ok'), lifecycle };
  assert.deepEqual(failed(await verifySharedDefault({ ...common, trace: sharedTrace(), ...sharedJira(sharedPrepared.table) })), []);
  const stays = await verifySharedDefault({ ...common, trace: sharedTrace(), ...sharedJira(withS) });
  assert.ok(failed(stays).some((name) => name.startsWith('Jira в конце')));
  const doubled = sharedTrace(); doubled.push(start(7, 4, 'p2', 'o3'));
  assert.ok(failed(await verifySharedDefault({ ...common, trace: doubled, ...sharedJira(sharedPrepared.table) })).some((name) => name.includes('start_operation')));
  const wrongN = sharedTrace(); wrongN[0] = preview(1, 1, 'p1', sharedPlan('ADD', eff(42, SS.D.name, true), eff(41, SS.S.name, false), 7));
  assert.ok(failed(await verifySharedDefault({ ...common, trace: wrongN, ...sharedJira(sharedPrepared.table) })).some((name) => name.includes('N равно')));
});

test('shared verifier fails as contract when the setup did not record the binding of A and B to the DEFAULT scheme', async () => {
  const lifecycle = { observed: [observation(withS), { status: 'CHANGED', table: sharedPrepared.table, A: { id: '42' }, B: { id: '42' }, C: { id: '41' } }] };
  const run = (binding, extra = {}) => verifySharedDefault({ prepared: { ...sharedPrepared, binding, ...extra }, conversation: conversationOf(table(T.name, SS.S.name, 'N=2 M=2'), 'ok', 'x', 'ok'), lifecycle, trace: sharedTrace(), ...sharedJira(sharedPrepared.table) });
  const name = (checks) => failed(checks).filter((item) => item.startsWith('setup: A и B привязаны'));
  const { bound } = sharedPrepared.binding;
  assert.deepEqual(name(await run(sharedPrepared.binding)), []);
  assert.equal(name(await run(undefined)).length, 1, 'no evidence');
  assert.equal(name(await run({ defaultBefore: [], bound: [bound[1], bound[0]] })).length, 1, 'order');
  assert.equal(name(await run({ defaultBefore: [], bound: [bound[0]] })).length, 1, 'B missing');
  assert.equal(name(await run({ defaultBefore: [], bound: [bound[0], { ...bound[1], after: ['L016BX'] }] })).length, 1, 'B step does not list A and B');
  assert.equal(name(await run({ defaultBefore: [], bound: [{ ...bound[0], schemeId: '10110' }, bound[1]] })).length, 1, 'wrong scheme');
  assert.equal(name(await run({ defaultBefore: ['L016AX'], bound })).length, 1, 'A was already on the scheme');
  assert.equal(name(await run({ defaultBefore: [], bound: [{ ...bound[0], projectId: 'x' }, bound[1]] })).length, 1, 'project id');
});

test('the hook records the real state once per completed apply and writes nothing', async () => {
  const lifecycle = {}; const calls = [];
  const fake = sharedJira(sharedPrepared.table);
  const tool = async (name, args) => { calls.push(name); return fake.tool(name, args); };
  await observeAfterApply({ prepared: { ...sharedPrepared, projects: { A: 'L016AX', B: 'L016BX', C: 'L016CX' } }, lifecycle, trace: sharedTrace().slice(0, 3), tool, adminPage: fake.adminPage });
  await observeAfterApply({ prepared: sharedPrepared, lifecycle, trace: sharedTrace().slice(0, 3), tool, adminPage: fake.adminPage });
  assert.equal(lifecycle.observed.length, 1);
  assert.deepEqual([...new Set(calls)], ['get_project_screens']);
});

// --- the hook stops a defective preview before the simulated user's turn ---------------------------------------------
const hookRun = (trace) => {
  const lifecycle = {}; const calls = [];
  const fake = sharedJira(sharedPrepared.table);
  const tool = async (name, args) => { calls.push(name); return fake.tool(name, args); };
  return { lifecycle, calls, run: () => hook({ prepared: sharedPrepared, lifecycle, trace, tool, adminPage: fake.adminPage }) };
};
const withPreview = (result, args) => [preview(1, 1, 'p1', result, args)];
const defective = {
  'another Issue Type Screen Scheme (id)': withPreview({ ...sharedPlan('ADD', eff(42, SS.D.name, true), eff(41, SS.S.name, false)), issueTypeScreenScheme: { id: 60, name: 'Private', isDefault: false } }, sharedArgs('ADD')),
  'an extra row requested': withPreview(sharedPlan('ADD', eff(42, SS.D.name, true), eff(41, SS.S.name, false)),
    { ...sharedArgs('ADD'), rows: [...sharedArgs('ADD').rows, { action: 'REPLACE', issueType: 'Bug', screenScheme: SS.S.name }] }),
  'the DEFAULT row requested': withPreview(sharedPlan('REPLACE', eff(42, SS.D.name, true), eff(41, SS.S.name, false)), sharedArgs('REPLACE', 'DEFAULT')),
  'a row of another type planned': withPreview({ ...sharedPlan('ADD', eff(42, SS.D.name, true), eff(41, SS.S.name, false)), rows: [{ issueType: issueType('99', 'Bug'), action: 'ADD', change: 'CHANGE' }] }, sharedArgs('ADD')),
  'two planned rows': withPreview({ ...sharedPlan('ADD', eff(42, SS.D.name, true), eff(41, SS.S.name, false)), rows: [...sharedPlan('ADD').rows, { issueType: issueType('99', 'Bug'), action: 'ADD', change: 'CHANGE' }] }, sharedArgs('ADD')),
};

test('the hook throws before the next user turn on a preview of another scheme or with rows other than the fresh type', async () => {
  for (const [name, trace] of Object.entries(defective)) {
    const { lifecycle, calls, run } = hookRun(trace);
    await assert.rejects(run, /outside the spec/u, name);
    assert.deepEqual(calls, [], `${name}: nothing is read or recorded after the stop`);
    assert.equal(lifecycle.observed, undefined, name);
  }
});

test('the hook lets the exact good traces through (every preview of the dialogue and the first one alone)', async () => {
  for (const trace of [sharedTrace().slice(0, 1), sharedTrace().slice(0, 3), sharedTrace(), []]) {
    const { run } = hookRun(trace);
    await run();
  }
  assert.deepEqual(previewDefects(sharedPrepared, { entry: sharedTrace()[0], result: JSON.parse(sharedTrace()[0].result) }), []);
  const byId = sharedTrace()[0]; byId.arguments = sharedArgs('ADD', T.id);
  assert.deepEqual(previewDefects(sharedPrepared, { entry: byId, result: JSON.parse(byId.result) }), [], 'the type may be named by id');
});

test('only the latest preview decides: a defect after a good first plan stops, and an earlier fixed defect does not', async () => {
  const bad = [...sharedTrace().slice(0, 3), ...defective['an extra row requested'].map((item) => ({ ...item, seq: 4, turn: 3 }))];
  await assert.rejects(hookRun(bad).run, /outside the spec/u);
  const recovered = [...defective['an extra row requested'], ...sharedTrace()];
  await hookRun(recovered).run();
});
