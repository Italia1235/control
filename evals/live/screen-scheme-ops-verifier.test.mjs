// Offline check of the screen-scheme-ops live helper (setup scope, verifiers, hooks) on synthetic Jira state and tool traces (no Jira, no models).
import test from 'node:test';
import assert from 'node:assert/strict';
import { effectiveScreens, normalizeOperation, observePrivate, observeShared, previewDefectsPrivate, previewDefectsShared, privateScope, schemeTable, setupPrivate, setupShared, sharedScope, verifyPrivate, verifyShared } from './screen-scheme-ops-common.mjs';
import privateHook from './scenarios/screen-scheme-ops-private/hooks.mjs';
import sharedHook from './scenarios/screen-scheme-ops-shared/hooks.mjs';

const TOOL = 'set_screen_scheme_operations';
const json = (value) => JSON.stringify(value);
const eff = (id, name, viaDefault) => ({ id, name, viaDefault });
const entry = (seq, turn, tool, args, result) => ({ seq, turn, tool, arguments: args, result: json(result) });
const preview = (seq, turn, planId, result) => entry(seq, turn, TOOL, { dryRun: true }, { dryRun: true, planId, ...result });
const start = (seq, turn, planId, operationId) => entry(seq, turn, 'start_operation', { toolName: TOOL, arguments: { dryRun: false, planId } }, { operationId, status: 'QUEUED' });
const done = (seq, turn, operationId, result) => entry(seq, turn, 'get_operation', { operationId }, { operationId, status: 'COMPLETED', result });
const table = (...cells) => `| a |\n| - |\n${cells.map((cell) => `| ${cell} |`).join('\n')}\n\nПодтверждаете?`;
const conversationOf = (...replies) => replies.flatMap((content) => [{ role: 'user', content: 'x' }, { role: 'assistant', content }]);
const failed = (checks, class_) => checks.filter((item) => !item.ok && (class_ ? item.class === class_ : item.class !== 'advisory')).map((item) => item.name);

/** The operation key get_project_screens really returns for a row (Jira i18n key); the tests use the short name and convert. */
const OPERATION_KEYS = { DEFAULT: 'admin.common.words.default', CREATE: 'admin.issue.operations.create', EDIT: 'admin.issue.operations.edit', VIEW: 'admin.issue.operations.view' };
const screenRows = (rows) => Object.entries(rows).map(([operation, id]) => ({ operation: OPERATION_KEYS[operation], id: Number(id), name: `screen-${id}`, otherProjects: 0, otherProjectNames: [] }));

/** get_project_screens of one project in the shape of the real response (ScreenSchemeServiceImpl, real tool trace): its Issue Type Screen Scheme and one issue type that goes through one Screen Scheme. */
const read = (itss, scheme, rows, { otherProjects = 0, schemeOthers = 0 } = {}) => ({
  project: { key: 'X', name: 'X' },
  scheme: { id: itss, name: `itss-${itss}`, otherProjects, otherProjectNames: [], isDefault: false },
  issueTypes: [{ issueType: { id: '1', name: 'Task' }, screenScheme: { id: scheme, name: `scheme-${scheme}`, otherProjects: schemeOthers, otherProjectNames: [], screens: screenRows(rows) } }],
});

/** Fake Jira: ITSS list page, row pages, Screen Scheme list page and get_project_screens by project. */
function jira({ itss, listed, rows, projects }) {
  const html = {
    '/secure/admin/ViewIssueTypeScreenSchemes.jspa': Object.keys(itss).map((id) => `<tr data-issue-type-screen-scheme-name="${id}"><td><a href="ConfigureIssueTypeScreenScheme.jspa?id=${id}"></a></td><td>${(listed[id] ?? []).map((key) => `<a href="/jira/plugins/servlet/project-config/${key}/summary">x</a>`).join('')}</td></tr>`).join(''),
    '/secure/admin/ViewFieldScreenSchemes.jspa': ['1', '2', '60'].map((id) => `<a href="ConfigureFieldScreenScheme.jspa?id=${id}"></a>`).join(''),
  };
  for (const [id, scheme] of Object.entries(rows)) {
    html[`/secure/admin/ConfigureIssueTypeScreenScheme.jspa?id=${id}`] = `<table id="issue-type-table"><tbody><tr data-default-association="true"><td><strong data-scheme-field=issue-type>Default</strong></td><td><a href="ConfigureFieldScreenScheme.jspa?id=${scheme}"></a></td></tr></tbody></table>`;
  }
  return {
    adminPage: async (route) => { if (!(route in html)) throw new Error(route); return { text: html[route] }; },
    tool: async (name, args) => { assert.equal(name, 'get_project_screens'); return projects[args.project]; },
  };
}

// --- screen-scheme-ops-private --------------------------------------------------------------------------------------
const initial = { CREATE: '11', DEFAULT: '13', EDIT: '11' };
const privatePrepared = { project: 'L017PX', itss: { id: '50', name: 'ITSS' }, scheme: { id: '60', name: 'S' },
  screens: { full: { id: '11', name: 'full' }, noSummary: { id: '12', name: 'nosummary' }, default: { id: '13', name: 'default' } },
  itssIds: ['50'], screenSchemeIds: ['1', '2', '60'],
  table: [{ operation: 'CREATE', screen: '11' }, { operation: 'DEFAULT', screen: '13' }, { operation: 'EDIT', screen: '11' }] };
const planRow = (operation, action, before, after) => ({ operation, action, requestedAction: action, change: 'CHANGE', before, after });
const privatePlan = (extra = {}) => ({ status: 'WOULD_CHANGE', screenScheme: { id: 60, name: 'S' },
  rows: [planRow('CREATE', 'REPLACE', eff(11, 'full', false), eff(12, 'nosummary', false)), planRow('EDIT', 'REMOVE', eff(11, 'full', false), eff(13, 'default', true))],
  warnings: [{ code: 'REQUIRED_FIELDS_MISSING_ON_CREATE', requiredFields: { pairs: 1, truncated: false, projects: [{ key: 'L017PX', issueTypes: [{ id: '1', name: 'Task' }] }], fields: [{ id: 'summary', name: 'Summary' }] } }],
  usage: { linked: 1, truncated: false }, projects: { linked: 1, changing: 1, truncated: false, listed: [{ key: 'L017PX', archived: false, changedIssueTypes: 1 }] },
  scopeText: 'N и M', limitations: ['без отката'], ...extra });
function privateTrace({ plan = privatePlan(), applied = 'APPLIED', mutate = false } = {}) {
  const result = { status: 'CHANGED', readBack: true, screenScheme: { id: 60 }, rows: [
    { operation: 'CREATE', result: applied, observed: { id: 12 } }, { operation: 'EDIT', result: 'APPLIED' }] };
  return [
    entry(1, 1, 'get_project_screens', { project: 'L017PX' }, {}),
    preview(2, 1, 'plan-1', plan),
    start(3, 2, 'plan-1', 'op-1'), done(4, 2, 'op-1', result),
    ...(mutate ? [entry(5, 2, 'create_screen', { dryRun: true }, {})] : []),
  ];
}
const privateJira = (final = true) => jira({ itss: { 50: 1 }, listed: { 50: ['L017PX'] }, rows: { 50: 60 },
  projects: { L017PX: read(50, 60, final ? { CREATE: '12', DEFAULT: '13' } : initial) } });

test('private verifier passes the exact dialogue and fails state, warning or protocol defects as contract', async () => {
  const good = await verifyPrivate({ prepared: privatePrepared, trace: privateTrace(), conversation: conversationOf(table('nosummary', 'N=1, M=1', 'Summary'), 'Готово'), ...privateJira() });
  assert.deepEqual(failed(good), []);
  assert.ok(good.some((item) => item.class === 'advisory'));
  assert.deepEqual(failed(good, 'advisory'), []);
  assert.ok(failed(await verifyPrivate({ prepared: privatePrepared, trace: privateTrace(), conversation: [], ...privateJira(false) })).some((name) => name.startsWith('Jira: строки Screen Scheme')));
  assert.ok(failed(await verifyPrivate({ prepared: privatePrepared, trace: privateTrace({ applied: 'UNKNOWN' }), conversation: [], ...privateJira() })).some((name) => name.startsWith('apply 1')));
  assert.ok(failed(await verifyPrivate({ prepared: privatePrepared, trace: privateTrace({ mutate: true }), conversation: [], ...privateJira() })).some((name) => name.startsWith('других записей нет')));
  const noWarning = privateTrace({ plan: privatePlan({ warnings: [] }) });
  assert.ok(failed(await verifyPrivate({ prepared: privatePrepared, trace: noWarning, conversation: [], ...privateJira() })).some((name) => name.includes('REQUIRED_FIELDS_MISSING_ON_CREATE')));
  const noSummary = privateTrace({ plan: privatePlan({ warnings: [{ code: 'REQUIRED_FIELDS_MISSING_ON_CREATE', requiredFields: { pairs: 1, projects: [{ key: 'L017PX' }], fields: [{ id: 'description', name: 'Description' }] } }] }) });
  assert.ok(failed(await verifyPrivate({ prepared: privatePrepared, trace: noSummary, conversation: [], ...privateJira() })).some((name) => name.includes('REQUIRED_FIELDS_MISSING_ON_CREATE')));
});

test('private verifier: a new Screen Scheme or another scheme of the project fails the state checks; presentation is advisory only', async () => {
  const copied = jira({ itss: { 50: 1 }, listed: { 50: ['L017PX'] }, rows: { 50: 61 }, projects: { L017PX: read(50, 61, { CREATE: '12', DEFAULT: '13' }) } });
  assert.ok(failed(await verifyPrivate({ prepared: privatePrepared, trace: privateTrace(), conversation: [], ...copied })).length >= 1);
  const plain = await verifyPrivate({ prepared: privatePrepared, trace: privateTrace(), conversation: conversationOf('без таблицы', 'Готово'), ...privateJira() });
  assert.deepEqual(failed(plain), []);
  assert.ok(failed(plain, 'advisory').length >= 1);
});

test('the private hook stops on a preview of another scheme or with other rows and lets the good plan through', async () => {
  const run = (plan) => privateHook({ prepared: privatePrepared, trace: [preview(1, 1, 'p', plan)] });
  await run(privatePlan());
  assert.equal(observePrivate, privateHook);
  await assert.rejects(run(privatePlan({ screenScheme: { id: 1, name: 'Default Screen Scheme' } })), /not the fresh private scheme/u);
  await assert.rejects(run(privatePlan({ rows: [planRow('VIEW', 'REPLACE', eff(13, 'default', true), eff(12, 'nosummary', false))] })), /not only REPLACE CREATE/u);
  await assert.rejects(run(privatePlan({ rows: [planRow('CREATE', 'REPLACE', eff(11, 'full', false), eff(14, 'other', false))] })), /not only REPLACE CREATE/u);
  assert.deepEqual(previewDefectsPrivate(privatePrepared, { result: privatePlan() }), []);
});

// --- screen-scheme-ops-shared ---------------------------------------------------------------------------------------
const sharedPrepared = { projects: { A: 'L017AX', B: 'L017BX' }, itss: { A: { id: '50', name: 'A' }, B: { id: '51', name: 'B' } }, scheme: { id: '60', name: 'S' },
  screens: { orig: { id: '11', name: 'orig' }, next: { id: '12', name: 'next' }, default: { id: '13', name: 'default' } }, table: privatePrepared.table,
  itssTables: { A: [{ type: 'DEFAULT', scheme: '60' }], B: [{ type: 'DEFAULT', scheme: '60' }] }, itssIds: ['50', '51'], screenSchemeIds: ['1', '2', '60'] };
const sharedPlan = (before, after, extra = {}) => ({ status: 'WOULD_CHANGE', screenScheme: { id: 60, name: 'S' },
  rows: [planRow('EDIT', 'REPLACE', eff(before, 'b', false), eff(after, 'a', false))],
  usage: { linked: 2, truncated: false }, projects: { linked: 2, changing: 2, truncated: false, listed: [{ key: 'L017AX', changedIssueTypes: 1 }, { key: 'L017BX', changedIssueTypes: 1 }] },
  scopeText: 'N и M', limitations: ['без отката'], ...extra });
const sharedResult = (observed) => ({ status: 'CHANGED', readBack: true, screenScheme: { id: 60 }, rows: [{ operation: 'EDIT', result: 'APPLIED', observed: { id: observed } }] });
const sharedTrace = (plans = [sharedPlan(11, 12), sharedPlan(12, 11)]) => [
  entry(1, 1, 'get_project_screens', { project: 'L017AX' }, {}),
  preview(2, 1, 'p1', plans[0]), start(3, 2, 'p1', 'op-1'), done(4, 2, 'op-1', sharedResult(12)),
  preview(5, 3, 'p2', plans[1]), start(6, 4, 'p2', 'op-2'), done(7, 4, 'op-2', sharedResult(11)),
];
const effective = (screen) => [{ type: 'Task', scheme: '60', screen }];
const lifecycle = (first = '12', second = '11') => ({ observed: [
  { planId: 'p1', status: 'CHANGED', A: effective(first), B: effective(first), table: [{ operation: 'CREATE', screen: '11' }, { operation: 'DEFAULT', screen: '13' }, { operation: 'EDIT', screen: '12' }] },
  { planId: 'p2', status: 'CHANGED', A: effective(second), B: effective(second), table: privatePrepared.table },
] });
const sharedJira = ({ edit = '11', itssRowsB = 60 } = {}) => jira({ itss: { 50: 1, 51: 1 }, listed: { 50: ['L017AX'], 51: ['L017BX'] }, rows: { 50: 60, 51: itssRowsB },
  projects: { L017AX: read(50, 60, { CREATE: '11', DEFAULT: '13', EDIT: edit }, { schemeOthers: 1 }), L017BX: read(51, 60, { CREATE: '11', DEFAULT: '13', EDIT: edit }, { schemeOthers: 1 }) } });
const sharedConversation = () => conversationOf(table('next', 'N=2', 'M=2'), 'Готово 1', table('orig', 'N=2', 'M=2'), 'Готово 2');

test('shared verifier passes the exact two-plan dialogue', async () => {
  const checks = await verifyShared({ prepared: sharedPrepared, trace: sharedTrace(), conversation: sharedConversation(), lifecycle: lifecycle(), ...sharedJira() });
  assert.deepEqual(failed(checks), []);
  assert.deepEqual(failed(checks, 'advisory'), []);
});

test('shared verifier fails as contract on M < N, a missing return, a missing hook observation, changed ITSS links or a second apply of one plan', async () => {
  const names = async (options) => failed(await verifyShared({ prepared: sharedPrepared, conversation: [], lifecycle: lifecycle(), ...sharedJira(), ...options }));
  assert.ok((await names({ trace: sharedTrace([sharedPlan(11, 12, { projects: { linked: 2, changing: 1, truncated: false, listed: [] } }), sharedPlan(12, 11)]) })).some((name) => name.startsWith('превью 1: N=2')));
  assert.ok((await names({ trace: sharedTrace(), ...sharedJira({ edit: '12' }) })).some((name) => name.startsWith('Jira в конце')));
  assert.ok((await names({ trace: sharedTrace(), lifecycle: { observed: [] } })).some((name) => name.startsWith('Jira после apply 1')));
  assert.ok((await names({ trace: sharedTrace(), lifecycle: lifecycle('12', '12') })).some((name) => name.startsWith('Jira после возврата')));
  assert.ok((await names({ trace: sharedTrace(), ...sharedJira({ itssRowsB: 61 }) })).some((name) => name.startsWith('Jira: ITSS проектов прежние')));
  const twice = [...sharedTrace(), start(8, 5, 'p1', 'op-3')];
  assert.ok((await names({ trace: twice })).some((name) => name.includes('start_operation')));
  assert.ok((await names({ trace: [...sharedTrace(), entry(9, 5, 'edit_screen_layout', { dryRun: true }, {})] })).some((name) => name.startsWith('других записей нет')));
});

test('the shared hook records the real state once per completed apply and writes nothing', async () => {
  const state = { observed: [] };
  const calls = [];
  const tool = async (name, args) => { calls.push([name, args.project]); return read(50, 60, { CREATE: '11', DEFAULT: '13', EDIT: '12' }, { schemeOthers: 1 }); };
  const trace = sharedTrace().slice(0, 4);
  await sharedHook({ prepared: sharedPrepared, lifecycle: state, trace, tool });
  await sharedHook({ prepared: sharedPrepared, lifecycle: state, trace, tool });
  assert.equal(observeShared, sharedHook);
  assert.equal(state.observed.length, 1);
  assert.deepEqual(state.observed[0].A, effective('12'));
  assert.deepEqual(state.observed[0].table, [{ operation: 'CREATE', screen: '11' }, { operation: 'DEFAULT', screen: '13' }, { operation: 'EDIT', screen: '12' }]);
  assert.deepEqual(calls, [['get_project_screens', 'L017AX'], ['get_project_screens', 'L017BX']]);
});

test('the shared hook stops on another scheme or a wrong row and accepts each plan by the number of applies before it', async () => {
  const run = (trace) => sharedHook({ prepared: sharedPrepared, lifecycle: { observed: [] }, trace, tool: async () => read(50, 60, initial) });
  await run(sharedTrace([sharedPlan(11, 12), sharedPlan(12, 11)]).slice(0, 2));
  await run(sharedTrace());
  await assert.rejects(run([preview(1, 1, 'p', sharedPlan(11, 12, { screenScheme: { id: 1, name: 'Default Screen Scheme' } }))]), /not the fresh shared scheme/u);
  await assert.rejects(run([preview(1, 1, 'p', sharedPlan(11, 14))]), /not exactly REPLACE EDIT/u);
  await assert.rejects(run([preview(1, 1, 'p', sharedPlan(11, 12, { rows: [planRow('CREATE', 'REPLACE', eff(11, 'o', false), eff(12, 'n', false))] }))]), /not exactly REPLACE EDIT/u);
  // the second plan before any apply would be a second change to E, not the return
  await assert.rejects(run([preview(1, 1, 'p1', sharedPlan(11, 12)), preview(2, 1, 'p2', sharedPlan(12, 11))]), /not exactly REPLACE EDIT → next/u);
  assert.deepEqual(previewDefectsShared(sharedPrepared, { result: sharedPlan(12, 11) }, 1), []);
});

// --- helpers ----------------------------------------------------------------------------------------------------------
test('schemeTable lists the explicit rows sorted by operation and fails for a scheme the project does not use', () => {
  assert.deepEqual(schemeTable(read(50, 60, { EDIT: '11', DEFAULT: '13', CREATE: '12' }), '60'), [{ operation: 'CREATE', screen: '12' }, { operation: 'DEFAULT', screen: '13' }, { operation: 'EDIT', screen: '11' }]);
  assert.throws(() => schemeTable(read(50, 60, initial), '61'), /not effective/u);
});

test('setup confirmation scopes name LOCAL-017; only the shared scope allows the one DEFAULT-row replace of project B', () => {
  const rules = { private: privateScope.rules('AB12'), shared: sharedScope.rules('AB12') };
  for (const text of Object.values(rules)) {
    assert.match(text, /LOCAL-017 run AB12/u);
    assert.match(text, /set_screen_scheme_operations is never a setup step/u);
    assert.doesNotMatch(text, /LOCAL-013|LOCAL-016/u);
  }
  assert.match(rules.private, /never a setup step of this scenario/u);
  assert.match(rules.shared, /ONLY ONCE/u);
  assert.match(rules.shared, /L017BAB12/u);
  assert.match(rules.shared, /L017AAB12/u);
  assert.match(rules.shared, /REJECT any other setup preview that edits rows/u);
});

// --- the real get_project_screens shape (operation keys are Jira i18n keys) -----------------------------------------------
test('normalizeOperation maps the real Jira keys and passes normalized names through', () => {
  assert.deepEqual(['admin.common.words.default', 'admin.issue.operations.create', 'admin.issue.operations.edit', 'admin.issue.operations.view', 'DEFAULT', 'CREATE', 'EDIT', 'VIEW', null, '']
    .map(normalizeOperation), ['DEFAULT', 'CREATE', 'EDIT', 'VIEW', 'DEFAULT', 'CREATE', 'EDIT', 'VIEW', 'DEFAULT', 'DEFAULT']);
});

test('table and effective screens are read from a response copied from a real trace (admin.issue.operations.* keys)', () => {
  const real = { project: { key: 'MWDEY', name: 'MWDEY' }, scheme: { id: 10634, name: 'MWDEY: Operation Screens', otherProjects: 0, otherProjectNames: [], isDefault: false },
    issueTypes: [{ issueType: { id: '10000', name: 'Задача' }, screenScheme: { id: 10634, name: 'MWDEY: Operation Screen Scheme 10630', otherProjects: 0, otherProjectNames: [], screens: [
      { operation: 'admin.common.words.default', id: 10795, name: 'default', otherProjects: 0, otherProjectNames: [] },
      { operation: 'admin.issue.operations.create', id: 10805, name: 'MWDEY CREATE', otherProjects: 0, otherProjectNames: [] },
      { operation: 'admin.issue.operations.edit', id: 10807, name: 'MWDEY EDIT', otherProjects: 0, otherProjectNames: [] },
      { operation: 'admin.issue.operations.view', id: 10808, name: 'MWDEY VIEW', otherProjects: 0, otherProjectNames: [] }] } }] };
  assert.deepEqual(schemeTable(real, '10634'), [{ operation: 'CREATE', screen: '10805' }, { operation: 'DEFAULT', screen: '10795' }, { operation: 'EDIT', screen: '10807' }, { operation: 'VIEW', screen: '10808' }]);
  assert.deepEqual(effectiveScreens(real, 'EDIT'), [{ type: 'Задача', scheme: '10634', screen: '10807' }]);
  real.issueTypes[0].screenScheme.screens.splice(2, 1);
  assert.deepEqual(effectiveScreens(real, 'EDIT'), [{ type: 'Задача', scheme: '10634', screen: '10795' }], 'without an explicit row the DEFAULT row is effective');
});

/** Fake Control for the setup: screens by creation order (11, 12, ...), default screen 13; get_project_screens in the real shape. */
function setupWorld({ linked = () => false } = {}) {
  const screens = {}; const created = [];
  const tool = async (name, args) => {
    if (name === 'list_project_leads') return [{ username: 'admin' }];
    if (name === 'list_projects') return [];
    if (name === 'get_screen_contents') {
      const screen = screens[args.screenId];
      return { screen: { name: screen.name }, tabs: [{ name: 'Field Tab', fields: screen.fieldIds.map((fieldId) => ({ fieldId })) }] };
    }
    if (name === 'get_field_configurations') return { fields: [{ configurations: [{ present: true, required: true, hidden: false }] }] };
    if (name === 'get_project_screens') return worldRead(args.project, screens, linked());
    throw new Error(`unexpected read ${name}`);
  };
  const apply = async (name, args) => {
    created.push(name);
    if (name === 'create_screen') { const id = 11 + Object.keys(screens).length; screens[id] = { name: args.name, fieldIds: args.fields.map((field) => field.name) }; return { screenId: id }; }
    return {};
  };
  return { tool, apply, created, rest: async () => ({ screens: [], total: 0 }), log: () => {} };
}

const idOf = (screens, suffix) => Number(Object.keys(screens).find((id) => screens[id].name.endsWith(suffix)));
/** The real response of the fixture projects: A owns scheme 60 (CREATE/EDIT → the fresh screen, DEFAULT 13); B is on scheme 70 until it is linked to 60. */
function worldRead(project, screens, linked) {
  const full = idOf(screens, 'screen-full') || idOf(screens, 'screen-orig');
  const isB = project.startsWith('L017B');
  const scheme = isB && !linked ? 70 : 60;
  const rows = scheme === 70 ? { DEFAULT: 13 } : { DEFAULT: 13, CREATE: full, EDIT: full, VIEW: 13 };
  const others = scheme === 60 && linked ? 1 : 0;
  return read(isB ? 51 : 50, scheme, rows, { schemeOthers: others });
}

test('setupPrivate prepares the fresh project on the real response shape (i18n operation keys)', async () => {
  const world = setupWorld();
  const prepared = await setupPrivate({ runId: 'ab12', ...world, adminPage: jira({ itss: { 50: 1 }, listed: { 50: ['L017PAB12'] }, rows: { 50: 60 }, projects: {} }).adminPage });
  assert.equal(prepared.project, 'L017PAB12');
  assert.deepEqual(prepared.table, [{ operation: 'CREATE', screen: '11' }, { operation: 'DEFAULT', screen: '13' }, { operation: 'EDIT', screen: '11' }, { operation: 'VIEW', screen: '13' }]);
  assert.equal(prepared.screens.default.id, '13');
  assert.equal(prepared.screens.full.id, '11');
  assert.deepEqual(world.created, ['create_project', 'create_screen', 'create_screen', 'set_project_operation_screens']);
});

test('setupShared links project B to the Screen Scheme of A on the real response shape and writes only the one ITSS replace', async () => {
  let linked = false;
  const world = setupWorld({ linked: () => linked });
  const apply = async (name, args) => { if (name === 'set_issue_type_screen_scheme_mappings') linked = true; return world.apply(name, args); };
  const page = (rowsB) => jira({ itss: { 50: 1, 51: 1 }, listed: { 50: ['L017AAB12'], 51: ['L017BAB12'] }, rows: { 50: 60, 51: rowsB }, projects: {} }).adminPage;
  const [before, after] = [page(70), page(60)];
  const prepared = await setupShared({ runId: 'ab12', ...world, apply, adminPage: (route) => (linked ? after : before)(route) });
  assert.deepEqual(prepared.projects, { A: 'L017AAB12', B: 'L017BAB12' });
  assert.equal(prepared.scheme.id, '60');
  assert.equal(prepared.screens.orig.id, '11');
  assert.equal(prepared.screens.default.id, '13');
  assert.deepEqual(prepared.itssTables.B, [{ type: 'DEFAULT', scheme: '60' }]);
  assert.deepEqual(world.created.filter((name) => name.startsWith('set_')), ['set_project_operation_screens', 'set_issue_type_screen_scheme_mappings']);
});

test('setupPrivate stops with STOP_CONDITION_5 when the rows lack the explicit CREATE and EDIT of the fresh screen', async () => {
  const world = setupWorld();
  const broken = { ...world, tool: async (name, args) => {
    const result = await world.tool(name, args);
    if (name === 'get_project_screens') result.issueTypes[0].screenScheme.screens = screenRows({ DEFAULT: 13 });
    return result;
  } };
  await assert.rejects(setupPrivate({ runId: 'ab12', ...broken, adminPage: jira({ itss: { 50: 1 }, listed: { 50: ['L017PAB12'] }, rows: { 50: 60 }, projects: {} }).adminPage }), /STOP_CONDITION_5/u);
});

test('the fixture carries the real i18n operation keys and the helper reads them', async () => {
  // verifyPrivate, observePrivate/observeShared and the setups all run on `read`, which carries the i18n keys.
  const response = read(50, 60, { DEFAULT: 13, CREATE: 12 });
  assert.deepEqual(response.issueTypes[0].screenScheme.screens.map((row) => row.operation), ['admin.common.words.default', 'admin.issue.operations.create']);
  assert.deepEqual(schemeTable(response, '60'), [{ operation: 'CREATE', screen: '12' }, { operation: 'DEFAULT', screen: '13' }]);
});
