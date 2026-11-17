// Offline check of the itss-assign live helper (setup scope, verifier, hook) on synthetic Jira state and tool traces (no Jira, no models).
import test from 'node:test';
import assert from 'node:assert/strict';
import { confirmationScope, observeAfterApply, planDefects, previewDefects, projectState, setupAssign, verifyAssign, warningDefects } from './itss-assign-common.mjs';
import hook from './scenarios/itss-assign-shared/hooks.mjs';
import verify from './scenarios/itss-assign-shared/verify.mjs';
import setup, { confirmationScope as setupScope } from './scenarios/itss-assign-shared/setup.mjs';

const TOOL = 'assign_issue_type_screen_scheme';
const json = (value) => JSON.stringify(value);
const entry = (seq, turn, tool, args, result) => ({ seq, turn, tool, arguments: args, result: json(result) });
const preview = (seq, turn, planId, result) => entry(seq, turn, TOOL, { dryRun: true }, { dryRun: true, planId, ...result });
const start = (seq, turn, planId, operationId) => entry(seq, turn, 'start_operation', { toolName: TOOL, arguments: { dryRun: false, planId } }, { operationId, status: 'QUEUED' });
const done = (seq, turn, operationId, result) => entry(seq, turn, 'get_operation', { operationId }, { operationId, status: 'COMPLETED', result });
const table = (...cells) => `| a |\n| - |\n${cells.map((cell) => `| ${cell} |`).join('\n')}\n\nПодтверждаете?`;
const conversationOf = (...replies) => replies.flatMap((content) => [{ role: 'user', content: 'x' }, { role: 'assistant', content }]);
const failed = (checks, class_) => checks.filter((item) => !item.ok && (class_ ? item.class === class_ : item.class !== 'advisory')).map((item) => item.name);

const OPERATION_KEYS = { DEFAULT: 'admin.common.words.default', CREATE: 'admin.issue.operations.create', EDIT: 'admin.issue.operations.edit', VIEW: 'admin.issue.operations.view' };
const TYPES = [{ id: '1', name: 'Task' }, { id: '2', name: 'Epic' }];

/** get_project_screens in the shape of the real response: the project's ITSS and every issue type through one Screen Scheme with CREATE/EDIT/VIEW rows of one screen. */
const read = (itss, scheme, screen, { otherProjects = 0 } = {}) => ({
  project: { key: 'X', name: 'X' },
  scheme: { id: itss, name: `itss-${itss}`, otherProjects, otherProjectNames: [], isDefault: false },
  issueTypes: TYPES.map((issueType) => ({ issueType, screenScheme: { id: scheme, name: `scheme-${scheme}`, otherProjects: 0, otherProjectNames: [],
    screens: [...['CREATE', 'EDIT', 'VIEW'].map((operation) => ({ operation: OPERATION_KEYS[operation], id: Number(screen), name: `screen-${screen}` })), { operation: OPERATION_KEYS.DEFAULT, id: 99, name: 'Default' }] } })),
});

const keys = { A: 'L018AX', B: 'L018BX' };
const itss = { A: { id: '50', name: 'itss-50' }, B: { id: '51', name: 'itss-51' } };
const readA = () => read(50, 60, 11), readB = () => read(51, 61, 12);
const states = { A: projectState(readA()), B: projectState(readB()) };
const prepared = { projects: keys, itss, screens: { A: { id: '11', name: 'a' }, B: { id: '12', name: 'b' } }, states,
  itssTables: { A: [{ type: 'DEFAULT', scheme: '60' }], B: [{ type: 'DEFAULT', scheme: '61' }] }, itssIds: ['50', '51'], screenSchemeIds: ['1', '60', '61'] };

const eff = (id) => ({ id, name: `n${id}`, viaDefault: false });
const typePlan = (issueType, from, to, change = 'CHANGE') => ({ issueType, change, screenSchemeBefore: eff(from.scheme), screenSchemeAfter: eff(to.scheme),
  screens: ['CREATE', 'EDIT', 'VIEW'].map((operation) => ({ operation, before: eff(from[operation]), after: eff(to[operation]) })) });
const plan = (from, to, fromItss, toItss, warnings, extra = {}) => ({ status: 'WOULD_CHANGE', project: { key: keys.B, name: 'B' },
  currentIssueTypeScreenScheme: { ...fromItss, isDefault: false }, newIssueTypeScreenScheme: { ...toItss, isDefault: false },
  issueTypes: TYPES.map((type, index) => typePlan(type, from.types[index], to.types[index])), limitations: ['без отката'], warnings, ...extra });
const orphaned = { code: 'ITSS_ORPHANED', message: 'm' };
const shared = { code: 'SHARED_TARGET', message: 'm', count: 1, projects: [{ key: keys.A, name: 'A' }] };
const planOne = (extra) => plan(states.B, states.A, itss.B, itss.A, [shared, orphaned], extra);
const planTwo = (extra) => plan(states.A, states.B, itss.A, itss.B, [], extra);
const result = (observed) => ({ status: 'CHANGED', readBack: true, observedIssueTypeScreenScheme: { ...observed, isDefault: false }, project: { key: keys.B } });
function goodTrace({ plans = [planOne(), planTwo()], extra = [] } = {}) {
  return [
    entry(1, 1, 'get_project_screens', { project: keys.A }, {}),
    preview(2, 1, 'p1', plans[0]), start(3, 2, 'p1', 'op-1'), done(4, 2, 'op-1', result(itss.A)),
    preview(5, 3, 'p2', plans[1]), start(6, 4, 'p2', 'op-2'), done(7, 4, 'op-2', result(itss.B)),
    ...extra,
  ];
}
const lifecycle = () => ({ observed: [
  { planId: 'p1', status: 'CHANGED', A: states.A, B: states.A, keys: { A: [keys.A, keys.B], B: [] } },
  { planId: 'p2', status: 'CHANGED', A: states.A, B: states.B, keys: { A: [keys.A], B: [keys.B] } },
] });

const listPage = (listed) => Object.keys(listed).map((id) => `<tr data-issue-type-screen-scheme-name="${id}"><td><a href="ConfigureIssueTypeScreenScheme.jspa?id=${id}"></a></td><td>${listed[id].map((key) => `<a href="/jira/plugins/servlet/project-config/${key}/summary">x</a>`).join('')}</td></tr>`).join('');
const rowsPage = (scheme) => `<table id="issue-type-table"><tbody><tr data-default-association="true"><td><strong data-scheme-field=issue-type>Default</strong></td><td><a href="ConfigureFieldScreenScheme.jspa?id=${scheme}"></a></td></tr></tbody></table>`;
/** Fake Jira: ITSS list page with the projects of each scheme, row pages, Screen Scheme list and get_project_screens by project. */
function jira({ listed = { 50: [keys.A], 51: [keys.B] }, projects = { [keys.A]: readA(), [keys.B]: readB() }, rows = { 50: 60, 51: 61 }, ids = ['50', '51'] } = {}) {
  const html = {
    '/secure/admin/ViewIssueTypeScreenSchemes.jspa': listPage(Object.fromEntries(ids.map((id) => [id, listed[id] ?? []]))),
    '/secure/admin/ViewFieldScreenSchemes.jspa': ['1', '60', '61'].map((id) => `<a href="ConfigureFieldScreenScheme.jspa?id=${id}"></a>`).join(''),
  };
  for (const [id, scheme] of Object.entries(rows)) html[`/secure/admin/ConfigureIssueTypeScreenScheme.jspa?id=${id}`] = rowsPage(scheme);
  return {
    adminPage: async (route) => { if (!(route in html)) throw new Error(route); return { text: html[route] }; },
    tool: async (name, args) => { assert.equal(name, 'get_project_screens'); return projects[args.project]; },
  };
}
const goodConversation = () => conversationOf(table(keys.B, itss.B.name, itss.A.name), 'Готово 1', table(keys.B, itss.A.name, itss.B.name), 'Готово 2');
const run = (overrides = {}) => verifyAssign({ prepared, trace: goodTrace(), conversation: goodConversation(), lifecycle: lifecycle(), ...jira(), ...overrides });

test('verifier passes the exact dialogue', async () => {
  const checks = await run();
  assert.deepEqual(failed(checks), []);
  assert.deepEqual(failed(checks, 'advisory'), []);
  assert.ok(checks.some((item) => item.class === 'advisory'));
  assert.equal(verify, verifyAssign);
});

test('contract: a wrong plan (project, scheme, types, screens, change flag) fails the preview checks', async () => {
  const wrongTarget = planOne({ newIssueTypeScreenScheme: { id: 1, name: 'Default', isDefault: true } });
  assert.ok(failed(await run({ trace: goodTrace({ plans: [wrongTarget, planTwo()] }) })).some((name) => name.startsWith('превью 1: WOULD_CHANGE')));
  assert.ok(failed(await run({ trace: goodTrace({ plans: [planOne({ project: { key: keys.A } }), planTwo()] }) })).some((name) => name.startsWith('превью 1: WOULD_CHANGE')));
  assert.ok(failed(await run({ trace: goodTrace({ plans: [planOne({ issueTypes: planOne().issueTypes.slice(0, 1) }), planTwo()] }) })).some((name) => name.startsWith('превью 1: WOULD_CHANGE')));
  const sameScreens = planOne(); sameScreens.issueTypes[0].screens[1].after = eff(states.B.types[0].EDIT);
  assert.ok(failed(await run({ trace: goodTrace({ plans: [sameScreens, planTwo()] }) })).some((name) => name.startsWith('превью 1: WOULD_CHANGE')));
  const noChange = planTwo(); noChange.issueTypes[1].change = 'NO_CHANGE';
  assert.ok(failed(await run({ trace: goodTrace({ plans: [planOne(), noChange] }) })).some((name) => name.startsWith('превью 2')));
  assert.deepEqual(planDefects({ dryRun: true, planId: 'p', ...planOne() }, { project: keys.B, from: states.B, to: states.A, fromItss: itss.B, toItss: itss.A }), []);
});

test('contract: warnings follow the fixture facts (SHARED_TARGET and ITSS_ORPHANED by the first plan, neither by the return)', async () => {
  assert.ok(failed(await run({ trace: goodTrace({ plans: [plan(states.B, states.A, itss.B, itss.A, [orphaned]), planTwo()] }) })).some((name) => name.includes('SHARED_TARGET')));
  assert.ok(failed(await run({ trace: goodTrace({ plans: [plan(states.B, states.A, itss.B, itss.A, [shared]), planTwo()] }) })).some((name) => name.includes('SHARED_TARGET')));
  assert.ok(failed(await run({ trace: goodTrace({ plans: [plan(states.B, states.A, itss.B, itss.A, [{ ...shared, projects: [{ key: 'FOREIGN' }] }, orphaned]), planTwo()] }) })).some((name) => name.includes('SHARED_TARGET')));
  assert.ok(failed(await run({ trace: goodTrace({ plans: [planOne(), plan(states.A, states.B, itss.A, itss.B, [orphaned])] }) })).some((name) => name.startsWith('превью 2: ITSS A')));
  assert.deepEqual(warningDefects(planOne(), { orphaned: true, sharedWith: [keys.A] }), []);
});

test('contract: protocol — one start per plan of the last preview, no other writes, outcome read back', async () => {
  const stale = goodTrace(); stale[2] = start(3, 2, 'p-old', 'op-1');
  assert.ok(failed(await run({ trace: stale })).some((name) => name.startsWith('ровно два start_operation')));
  const reprev = [preview(2, 1, 'p0', planOne()), ...goodTrace().slice(1)]; reprev[1] = preview(3, 1, 'p1', planOne());
  assert.deepEqual(failed(await run({ trace: [reprev[0], reprev[1], ...goodTrace().slice(2)].map((item, index) => ({ ...item, seq: index + 1 })) })), []);
  assert.ok(failed(await run({ trace: goodTrace({ extra: [entry(8, 4, 'create_screen', { dryRun: true }, {})] }) })).some((name) => name.startsWith('других записей нет')));
  const once = goodTrace().slice(0, 4);
  assert.ok(failed(await run({ trace: once })).some((name) => name.startsWith('ровно два start_operation')));
  const unknown = goodTrace(); unknown[3] = { ...done(4, 2, 'op-1', { ...result(itss.A), status: 'UNKNOWN' }) };
  assert.ok(failed(await run({ trace: unknown })).some((name) => name.startsWith('apply 1')));
  const noReadBack = goodTrace(); noReadBack[6] = done(7, 4, 'op-2', { ...result(itss.B), readBack: false });
  assert.ok(failed(await run({ trace: noReadBack })).some((name) => name.startsWith('apply 2')));
  const wrongObserved = goodTrace(); wrongObserved[6] = done(7, 4, 'op-2', result(itss.A));
  assert.ok(failed(await run({ trace: wrongObserved })).some((name) => name.startsWith('apply 2')));
});

test('contract: Jira state — hook observations, final association, ITSS rows and lists', async () => {
  const noSwitch = lifecycle(); noSwitch.observed[0].B = states.B;
  assert.ok(failed(await run({ lifecycle: noSwitch })).some((name) => name.startsWith('Jira после apply 1')));
  const aTouched = lifecycle(); aTouched.observed[0].A = states.B;
  assert.ok(failed(await run({ lifecycle: aTouched })).some((name) => name.startsWith('Jira после apply 1')));
  const listStale = lifecycle(); listStale.observed[0].keys = { A: [keys.A], B: [keys.B] };
  assert.ok(failed(await run({ lifecycle: listStale })).some((name) => name.startsWith('Jira после apply 1')));
  assert.ok(failed(await run({ lifecycle: { observed: [] } })).some((name) => name.startsWith('Jira после')));
  const stuck = jira({ projects: { [keys.A]: readA(), [keys.B]: readA() }, listed: { 50: [keys.A, keys.B], 51: [] } });
  assert.ok(failed(await run(stuck)).some((name) => name.startsWith('Jira в конце')));
  const rowsChanged = jira({ rows: { 50: 60, 51: 60 } });
  assert.ok(failed(await run(rowsChanged)).some((name) => name.startsWith('Jira: строки обеих ITSS')));
  const created = jira({ ids: ['50', '51', '52'], rows: { 50: 60, 51: 61, 52: 60 } });
  assert.ok(failed(await run(created)).some((name) => name.startsWith('Jira: строки обеих ITSS')));
});

test('presentation is advisory only: a missing table never fails the contract', async () => {
  const plain = await run({ conversation: conversationOf('без таблицы', 'Готово', 'без таблицы', 'Готово') });
  assert.deepEqual(failed(plain), []);
  assert.ok(failed(plain, 'advisory').length >= 1);
});

test('the hook stops on a preview of another project or scheme, lets the good plans through and records the state after each apply', async () => {
  const ctx = (trace, observed = []) => ({ prepared, trace, lifecycle: { observed }, ...jira() });
  await hook(ctx([preview(1, 1, 'p1', planOne())]));
  await assert.rejects(hook(ctx([preview(1, 1, 'p1', planOne({ project: { key: keys.A } }))])), /not the fresh project/u);
  await assert.rejects(hook(ctx([preview(1, 1, 'p1', planOne({ newIssueTypeScreenScheme: { id: 1, name: 'Default' } }))])), /not 50/u);
  await assert.rejects(hook(ctx([preview(1, 1, 'p1', planTwo())])), /not 50/u);
  await assert.rejects(hook(ctx(goodTrace({ plans: [planOne(), planOne()] }))), /not 51/u);
  assert.equal(observeAfterApply, hook);
  const lifecycleOf = { observed: [] };
  await hook({ prepared, trace: goodTrace().slice(0, 4), lifecycle: lifecycleOf, ...jira({ projects: { [keys.A]: readA(), [keys.B]: readA() }, listed: { 50: [keys.A, keys.B], 51: [] } }) });
  assert.deepEqual(lifecycleOf.observed.map((item) => ({ planId: item.planId, status: item.status, B: item.B, keys: item.keys })), [{ planId: 'p1', status: 'CHANGED', B: states.A, keys: { A: [keys.A, keys.B], B: [] } }]);
  await hook({ prepared, trace: goodTrace().slice(0, 4), lifecycle: lifecycleOf, ...jira() });
  assert.equal(lifecycleOf.observed.length, 1);
  assert.deepEqual(previewDefects(prepared, { result: planOne() }, 0), []);
  assert.deepEqual(previewDefects(prepared, { result: planTwo() }, 1), []);
  assert.equal(previewDefects(prepared, { result: planTwo() }, 2).length > 0, true);
});

test('setup: fresh objects only, the confirmation scope rejects the dialogue tool, and a shared, default or equal scheme stops the setup', async () => {
  assert.equal(setupScope, confirmationScope);
  const rules = confirmationScope.rules('ABCDE');
  assert.match(rules, /L018-ABCDE-/u);
  assert.match(rules, /L018 \+ one letter \+ ABCDE/u);
  assert.match(rules, /REJECT any setup preview of either/u);
  const own = jira({ listed: { 50: ['L018AABCDE'], 51: ['L018BABCDE'] } });
  const context = (projects, { page = own, calls = [] } = {}) => ({
    runId: 'abcde', log() {},
    rest: async () => ({ screens: [], total: 0 }),
    tool: async (name, args) => {
      if (name === 'list_project_leads') return [{ username: 'admin' }];
      if (name === 'list_projects') return [];
      if (name === 'get_screen_contents') return { screen: { name: 'x' }, tabs: [{ name: 'Field Tab', fields: [{ fieldId: 'summary' }, { fieldId: 'description' }] }] };
      if (name === 'get_project_screens') return projects[args.project];
      throw new Error(name);
    },
    apply: async (name, args) => { calls.push([name, args]); return name === 'create_screen' ? { screenId: calls.filter(([n]) => n === 'create_screen').length === 1 ? 11 : 12 } : {}; },
    adminPage: page.adminPage,
  });
  const calls = [];
  const prepared = await setupAssign(context({ L018AABCDE: readA(), L018BABCDE: readB() }, { calls }));
  assert.deepEqual(calls.map(([name]) => name), ['create_project', 'create_project', 'create_screen', 'create_screen', 'set_project_operation_screens', 'set_project_operation_screens']);
  assert.deepEqual(calls.at(-1)[1], { project: 'L018BABCDE', mappings: [{ allIssueTypes: true, screens: { CREATE: 'L018-abcde-screen-b', EDIT: 'L018-abcde-screen-b', VIEW: 'L018-abcde-screen-b' } }] });
  assert.deepEqual(prepared.states, states);
  assert.deepEqual(prepared.itss, itss);
  assert.deepEqual(prepared.known, { projectA: 'L018AABCDE', projectB: 'L018BABCDE', schemeOfProjectA: 'itss-50', previousSchemeOfProjectB: 'itss-51' });
  const shared = read(50, 60, 11, { otherProjects: 1 });
  await assert.rejects(setupAssign(context({ L018AABCDE: shared, L018BABCDE: readB() })), /STOP_CONDITION_5.*no private Issue Type Screen Scheme/u);
  const isDefault = readA(); isDefault.scheme.isDefault = true;
  await assert.rejects(setupAssign(context({ L018AABCDE: isDefault, L018BABCDE: readB() })), /STOP_CONDITION_5/u);
  await assert.rejects(setupAssign(context({ L018AABCDE: readA(), L018BABCDE: read(50, 60, 12) })), /STOP_CONDITION_5.*different/u);
  await assert.rejects(setupAssign(context({ L018AABCDE: readA(), L018BABCDE: readB() }, { page: jira({ listed: { 50: ['L018AABCDE', 'L018BABCDE'], 51: [] } }) })), /STOP_CONDITION_5.*admin page/u);
  assert.equal(typeof setup, 'function');
});
