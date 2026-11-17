// Offline checks of the ITSS binding helper: form parsing, the allowlist guards (accepted vs refused targets and
// requests) and the setup flow against a fake Jira. No real Jira, no models.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {
  BIND_ROUTE, FORM_ROUTE, LIST_ROUTE, assertBindTarget, bindingContext, createItssBinder, createProjectRegistry, fetchTransport, guardTransport, parseBindForm,
} from './itss-bind.mjs';
import { setupShared } from './itss-mappings-common.mjs';

const BASE = 'http://localhost:2990/jira';
const TOKEN = 'TEST-FORM-TOKEN-0000_00000000_lin';

/** The «Select Issue Type Screen Scheme» form as the stand renders it (noise of the real page kept). */
const formPage = (projectId, { token = TOKEN, options = [['10110', 'AJHNG: Схема'], ['1', 'Default Issue Type Screen Scheme']], extraForm = '' } = {}) => `<html><body>
<form action="/jira/secure/QuickSearch.jspa" method="get"><input type="text" name="searchString" value=""/></form>
<form action="SelectIssueTypeScreenScheme.jspa" method="post" name="jiraform" onsubmit="if (this.submitted) return false; this.submitted = true; return true" >
<table class="jiraform maxWidth"><tr><td><fieldset class="hidden parameters"><input type="hidden" class="list" title="summaries" value=""/><input type="hidden" class="list" title="summaries" value=""/>
<input type="hidden" title="paramName" value="schemeId"/></fieldset>
<select name="schemeId" id="schemeId_select" >${options.map(([id, label]) => `<option value="${id}" >\n ${label}\n </option>`).join('')}</select></td></tr>
<input type="hidden" id="projectId" name="projectId" value="${projectId}" />
<tr><td><input type="submit" name="Associate" value="Associate" id="associate_submit" accesskey="s" class="aui-button " /></td></tr></table>
<input type="hidden" id="atl_token" name="atl_token" value="${token}" />${extraForm}</form></body></html>`;

// --- form parsing --------------------------------------------------------------------------------------------------

test('the association form is parsed: token, project id, scheme options, submit button', () => {
  assert.deepEqual(parseBindForm(formPage('10701')), {
    atlToken: TOKEN, projectId: '10701', submit: 'Associate',
    options: [{ id: '10110', label: 'AJHNG: Схема' }, { id: '1', label: 'Default Issue Type Screen Scheme' }],
  });
});

test('a page without exactly one association form, token, project id or select is refused', () => {
  assert.throws(() => parseBindForm('<html><form action="Other.jspa"></form></html>'), /on the page 0 times/u);
  assert.throws(() => parseBindForm(formPage('1') + formPage('2')), /on the page 2 times/u);
  assert.throws(() => parseBindForm(formPage('1').replace(/<input type="hidden" id="atl_token"[^>]*>/u, '')), /0 inputs named atl_token/u);
  assert.throws(() => parseBindForm(formPage('1').replace(/<input type="hidden" id="projectId"[^>]*>/u, '')), /0 inputs named projectId/u);
  assert.throws(() => parseBindForm(formPage('1', { extraForm: '<input type="hidden" name="atl_token" value="x"/>' })), /2 inputs named atl_token/u);
  assert.throws(() => parseBindForm(formPage('1').replace(/<select[\s\S]*?<\/select>/u, '')), /no schemeId select/u);
});

// --- guards ------------------------------------------------------------------------------------------------------

function registryWith(...created) {
  const registry = createProjectRegistry();
  for (const [key, id] of created) {
    registry.record('create_project', { action: 'create', key }, { status: 'CREATED', key, projectId: id, report: { created: [{ kind: 'PROJECT', id: String(id) }], reused: [] } });
  }
  return registry;
}

test('the registry keeps only projects Control reports as created in this run', () => {
  const created = (extra = {}, args = { action: 'create', key: 'L016AX1' }) => {
    const registry = createProjectRegistry();
    registry.record('create_project', args, { status: 'CREATED', key: 'L016AX1', projectId: 10800, report: { created: [{ kind: 'PROJECT', id: '10800' }], reused: [] }, ...extra });
    return registry.idOf('L016AX1');
  };
  assert.equal(created(), '10800');
  assert.equal(created({ status: 'EXISTS' }), null, 'an existing project is not created by this run');
  assert.equal(created({ report: { created: [], reused: [{ kind: 'PROJECT', id: '10800' }] } }), null);
  assert.equal(created({ report: { created: [{ kind: 'PROJECT', id: '10999' }] } }), null, 'the reported id must be the project id');
  assert.equal(created({ projectId: '10800' }), null);
  assert.equal(created({ key: 'L016AX2' }), null);
  assert.equal(created({}, { action: 'list_templates', key: 'L016AX1' }), null);
  const registry = createProjectRegistry();
  registry.record('create_screen', { action: 'create', key: 'L016AX1' }, { status: 'CREATED', key: 'L016AX1', projectId: 1, report: { created: [{ kind: 'PROJECT', id: '1' }] } });
  assert.equal(registry.idOf('L016AX1'), null);
});

test('only fresh A and B projects of this run and only the Default scheme are accepted as bind targets', () => {
  const registry = registryWith(['L016AX1', 10800], ['L016BX1', 10801], ['L016CX1', 10802], ['L016PX1', 10803], ['L016AOTHER', 10804]);
  assert.deepEqual(assertBindTarget({ runId: 'x1', registry, key: 'L016AX1', schemeId: '1' }), { key: 'L016AX1', projectId: '10800' });
  assert.deepEqual(assertBindTarget({ runId: 'X1', registry, key: 'L016BX1', schemeId: 1 }), { key: 'L016BX1', projectId: '10801' });
  const refused = (target, pattern) => assert.throws(() => assertBindTarget({ runId: 'X1', registry, schemeId: '1', ...target }), pattern);
  refused({ key: 'L016CX1' }, /not a fresh L016A\/L016B project/u);
  refused({ key: 'L016PX1' }, /not a fresh L016A\/L016B project/u);
  refused({ key: 'L016AOTHER' }, /not a fresh L016A\/L016B project of run X1/u);
  refused({ key: 'L016ZPRC' }, /not a fresh L016A\/L016B project/u);
  refused({ key: 'L016ZTSK' }, /not a fresh L016A\/L016B project/u);
  refused({ key: 'CGATEA' }, /not a fresh L016A\/L016B project/u);
  refused({ key: 'L016AX1', schemeId: '10110' }, /only the Default Issue Type Screen Scheme/u);
  refused({ key: 'L016AX1', schemeId: '11' }, /only the Default Issue Type Screen Scheme/u);
  refused({ key: undefined }, /not a fresh/u);
  assert.throws(() => assertBindTarget({ runId: 'X1', registry: registryWith(), key: 'L016AX1', schemeId: '1' }), /was not created by this run's setup/u);
  assert.throws(() => assertBindTarget({ runId: '', registry, key: 'L016AX1', schemeId: '1' }), /no valid run id/u);
  assert.throws(() => assertBindTarget({ runId: '.*', registry, key: 'L016AX1', schemeId: '1' }), /no valid run id/u);
});

test('the guarded transport sends only the allowlisted GETs and the one association POST', async () => {
  const sent = [];
  const guarded = guardTransport({ get: async (route) => { sent.push(['GET', route]); return {}; }, post: async (route, fields) => { sent.push(['POST', route, fields]); return {}; } });
  await guarded.get(LIST_ROUTE);
  await guarded.get(`${FORM_ROUTE}?projectId=10800`);
  const fields = { projectId: '10800', schemeId: '1', atl_token: TOKEN, Associate: 'Associate' };
  await guarded.post(BIND_ROUTE, fields);
  assert.equal(sent.length, 3);
  const before = sent.length;
  for (const route of ['/rest/api/2/project/10800', '/secure/admin/ViewIssueTypeScreenSchemes.jspa?x=1', `${FORM_ROUTE}?projectId=abc`, `${FORM_ROUTE}?projectId=1&projectId=2`,
    '/secure/admin/DeleteIssueTypeScreenScheme.jspa?id=1', '/secure/project/DeleteProject.jspa?pid=1']) {
    assert.throws(() => guarded.get(route), /not an allowlisted admin page/u, route);
  }
  for (const route of ['/secure/admin/ViewIssueTypeScreenSchemes.jspa', '/secure/admin/DeleteIssueTypeScreenScheme.jspa', '/secure/admin/SelectIssueTypeScreenScheme!default.jspa', '/rest/api/2/project']) {
    assert.throws(() => guarded.post(route, fields), /not the association action/u, route);
  }
  assert.throws(() => guarded.post(BIND_ROUTE, { ...fields, extra: 'x' }), /differ from the association form/u);
  assert.throws(() => guarded.post(BIND_ROUTE, { projectId: '1', schemeId: '1', atl_token: TOKEN }), /differ from the association form/u);
  assert.throws(() => guarded.post(BIND_ROUTE, { ...fields, schemeId: '10110' }), /not the Default scheme/u);
  assert.throws(() => guarded.post(BIND_ROUTE, { ...fields, projectId: '10800; DROP' }), /not a numeric project id/u);
  assert.throws(() => guarded.post(BIND_ROUTE, { ...fields, atl_token: '' }), /without the form token/u);
  assert.equal(sent.length, before, 'refusals never reach the transport');
});

// --- real fetch transport against a local server -----------------------------------------------------------------------

test('the fetch transport sends the loader token, passes the session cookies of the GET to the POST and does not follow the redirect', async () => {
  const seen = [];
  const server = http.createServer((request, response) => {
    let body = '';
    request.on('data', (chunk) => { body += chunk; });
    request.on('end', () => {
      seen.push({ method: request.method, url: request.url, authorization: request.headers.authorization, cookie: request.headers.cookie, type: request.headers['content-type'], body });
      if (request.method === 'GET') response.writeHead(200, { 'set-cookie': ['JSESSIONID=abc; Path=/jira; HttpOnly', 'atlassian.xsrf.token=xyz; Path=/jira'] }).end('<html>form</html>');
      else response.writeHead(302, { location: '/jira/plugins/servlet/project-config/L016AX1/screens' }).end();
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const transport = fetchTransport({ baseUrl: `http://127.0.0.1:${server.address().port}/jira`, authorization: 'Bearer loader-token' });
    const page = await transport.get('/secure/admin/x');
    assert.equal(page.cookies, 'JSESSIONID=abc; atlassian.xsrf.token=xyz');
    const post = await transport.post('/secure/admin/y', { projectId: '1', schemeId: '1', atl_token: 'a b', Associate: 'Associate' }, page.cookies);
    assert.equal(post.status, 302);
    assert.equal(post.location, '/jira/plugins/servlet/project-config/L016AX1/screens');
    assert.deepEqual(seen.map(({ method, url, authorization, cookie }) => ({ method, url, authorization, cookie })), [
      { method: 'GET', url: '/jira/secure/admin/x', authorization: 'Bearer loader-token', cookie: undefined },
      { method: 'POST', url: '/jira/secure/admin/y', authorization: 'Bearer loader-token', cookie: 'JSESSIONID=abc; atlassian.xsrf.token=xyz' },
    ]);
    assert.equal(seen[1].type, 'application/x-www-form-urlencoded');
    assert.equal(seen[1].body, 'projectId=1&schemeId=1&atl_token=a+b&Associate=Associate');
  } finally { server.close(); }
  assert.throws(() => fetchTransport({ baseUrl: 'https://jira.example.com', authorization: 'x' }), /not a local test Jira/u);
});

// --- fake Jira -----------------------------------------------------------------------------------------------------

/** A fake Jira: the schemes list page, the association form and POST, the Default scheme's rows; counts every request. */
function fakeJira({ defaultKeys = ['AJHNG'], postEffect = 'bind', postStatus = 302, formOptions } = {}) {
  const state = { defaultKeys: [...defaultKeys], privateKeys: [], projects: new Map(), requests: [], issueTypes: [], created: [] };
  const list = () => `<table>${[['1', state.defaultKeys], ['60', state.privateKeys]].map(([id, keys]) => `<tr data-issue-type-screen-scheme-name="${id}"><td><a href="ConfigureIssueTypeScreenScheme.jspa?id=${id}"></a></td><td>${keys.map((key) => `<a href="/jira/plugins/servlet/project-config/${key}/summary">x</a>`).join('')}</td></tr>`).join('')}</table>`;
  const rows = `<table id="issue-type-table"><tbody><tr data-default-association="true"><td><strong data-scheme-field=issue-type>Default</strong></td><td><a href="ConfigureFieldScreenScheme.jspa?id=42"></a></td></tr></tbody></table>`;
  const pages = { [LIST_ROUTE]: list, '/secure/admin/ConfigureIssueTypeScreenScheme.jspa?id=1': () => rows,
    '/secure/admin/ViewFieldScreenSchemes.jspa': () => '<a href="ConfigureFieldScreenScheme.jspa?id=42"></a>' };
  const keyOf = (id) => [...state.projects].find(([, value]) => value === id)?.[0];
  const reply = (status, body = '', headers = {}) => { const response = new Response(body, { status: status === 302 ? 200 : status, headers }); return status === 302 ? Object.defineProperty(response, 'status', { value: 302 }) : response; };
  return {
    state,
    adminPage: async (route) => { if (!(route in pages)) throw new Error(`unexpected admin page ${route}`); return { text: pages[route]() }; },
    fetchImpl: async (url, init) => {
      const route = url.slice(BASE.length);
      state.requests.push({ method: init.method, route, headers: init.headers, body: init.body });
      if (init.method === 'GET' && route in pages) return reply(200, pages[route](), { 'set-cookie': 'JSESSIONID=s1; Path=/jira' });
      const form = /^\/secure\/admin\/SelectIssueTypeScreenScheme!default\.jspa\?projectId=(\d+)$/u.exec(route);
      if (init.method === 'GET' && form) {
        const response = reply(200, formPage(form[1], { options: formOptions }));
        response.headers.append('set-cookie', 'JSESSIONID=s2; Path=/jira');
        response.headers.append('set-cookie', 'atlassian.xsrf.token=t2; Path=/jira');
        return response;
      }
      if (init.method === 'POST' && route === BIND_ROUTE) {
        const fields = new URLSearchParams(init.body);
        const valid = init.headers.cookie === 'JSESSIONID=s2; atlassian.xsrf.token=t2' && fields.get('atl_token') === TOKEN && fields.get('Associate') === 'Associate';
        const key = keyOf(fields.get('projectId'));
        if (valid && key && postEffect === 'bind') state.defaultKeys.push(key);
        if (valid && key && postEffect === 'bind-extra') state.defaultKeys.push(key, 'SOMEONE');
        return reply(postStatus, '', postStatus === 302 ? { location: '/jira/plugins/servlet/project-config/x/screens' } : {});
      }
      throw new Error(`unexpected request ${init.method} ${route}`);
    },
  };
}
const writes = (jira) => jira.state.requests.filter(({ method }) => method !== 'GET');
const created = (key, id) => ({ status: 'CREATED', key, projectId: id, report: { created: [{ kind: 'PROJECT', id: String(id) }], reused: [] } });

function binderOf(jira, ...projects) {
  const registry = createProjectRegistry();
  for (const [key, id] of projects) { jira.state.projects.set(key, String(id)); registry.record('create_project', { action: 'create', key }, created(key, id)); }
  return createItssBinder({ runId: 'X1', registry, transport: fetchTransport({ baseUrl: BASE, authorization: 'Bearer t', fetchImpl: jira.fetchImpl }) });
}

test('binding a fresh project: list, form, one POST with the form fields and cookies, list again; evidence returned', async () => {
  const jira = fakeJira();
  const result = await binderOf(jira, ['L016AX1', 10800]).bind('L016AX1');
  assert.deepEqual(result, { key: 'L016AX1', projectId: '10800', schemeId: '1', before: ['AJHNG'], after: ['AJHNG', 'L016AX1'], post: { status: 302, location: '/jira/plugins/servlet/project-config/x/screens' } });
  assert.deepEqual(jira.state.requests.map(({ method, route }) => `${method} ${route}`), [
    `GET ${LIST_ROUTE}`, 'GET /secure/admin/SelectIssueTypeScreenScheme!default.jspa?projectId=10800', `POST ${BIND_ROUTE}`, `GET ${LIST_ROUTE}`,
  ]);
  const [post] = writes(jira);
  assert.equal(post.body, `projectId=10800&schemeId=1&atl_token=${encodeURIComponent(TOKEN)}&Associate=Associate`);
  assert.equal(post.headers.authorization, 'Bearer t');
});

test('a refused target sends no request at all', async () => {
  const jira = fakeJira();
  const binder = binderOf(jira, ['L016AX1', 10800], ['L016CX1', 10802]);
  jira.state.projects.set('L016ZPRC', '10701');
  for (const key of ['L016CX1', 'L016ZPRC', 'AJHNG', 'L016AOTHER']) await assert.rejects(binder.bind(key), /Refused/u, key);
  assert.equal(jira.state.requests.length, 0);
});

test('binding stops (STOP_CONDITION_5) before the POST when the project is already listed, the form is for another project or lacks the Default scheme', async () => {
  const listed = fakeJira({ defaultKeys: ['L016AX1'] });
  await assert.rejects(binderOf(listed, ['L016AX1', 10800]).bind('L016AX1'), /STOP_CONDITION_5.*already listed/u);
  const noDefault = fakeJira({ formOptions: [['10110', 'AJHNG: Схема']] });
  await assert.rejects(binderOf(noDefault, ['L016AX1', 10800]).bind('L016AX1'), /STOP_CONDITION_5.*does not offer the Default/u);
  const other = fakeJira();
  const swapped = other.fetchImpl;
  other.fetchImpl = async (url, init) => url.includes('SelectIssueTypeScreenScheme!default') ? new Response(formPage('10999'), { status: 200 }) : swapped(url, init);
  const wrongForm = binderOf(other, ['L016AX1', 10800]);
  await assert.rejects(wrongForm.bind('L016AX1'), /STOP_CONDITION_5.*form is for project 10999, not 10800/u);
  for (const jira of [listed, noDefault, other]) assert.equal(writes(jira).length, 0);
});

test('binding stops when the POST is not a redirect, binds nothing or changes more than the one project', async () => {
  await assert.rejects(binderOf(fakeJira({ postStatus: 200 }), ['L016AX1', 10800]).bind('L016AX1'), /returned HTTP 200 instead of a redirect/u);
  await assert.rejects(binderOf(fakeJira({ postEffect: 'none' }), ['L016AX1', 10800]).bind('L016AX1'), /binding of L016AX1 not confirmed.*lists \[AJHNG\], expected \[AJHNG, L016AX1\]/u);
  await assert.rejects(binderOf(fakeJira({ postEffect: 'bind-extra' }), ['L016AX1', 10800]).bind('L016AX1'), /not confirmed.*SOMEONE/u);
});

test('bindingContext records only this run\'s created projects through the wrapped apply and binds them', async () => {
  const jira = fakeJira();
  const context = bindingContext({ runId: 'X1', baseUrl: BASE, authorization: 'Bearer t', fetchImpl: jira.fetchImpl });
  jira.state.projects.set('L016AX1', '10800');
  jira.state.projects.set('L016BX1', '10801');
  await assert.rejects(context.bindToDefaultItss('L016AX1'), /was not created by this run's setup/u);
  const apply = context.recording(async (name, args) => (name === 'create_project' ? created(args.key, args.key === 'L016AX1' ? 10800 : 10801) : { status: 'CREATED' }));
  await apply('create_project', { action: 'create', key: 'L016AX1' });
  await apply('create_screen', { name: 'x' });
  assert.equal((await context.bindToDefaultItss('L016AX1')).after.includes('L016AX1'), true);
  await assert.rejects(context.bindToDefaultItss('L016BX1'), /was not created by this run's setup/u);
  assert.deepEqual(jira.state.defaultKeys, ['AJHNG', 'L016AX1']);
});

// --- setup flow against the fake Jira -------------------------------------------------------------------------------

function setupWorld({ jira, bindProjects = true, controlOnDefault = true } = {}) {
  const type = 'L016-X1-Type', scheme = 'L016-X1-S';
  const context = bindingContext({ runId: 'X1', baseUrl: BASE, authorization: 'Bearer t', fetchImpl: jira.fetchImpl });
  const calls = [];
  const ids = { L016AX1: 10800, L016BX1: 10801, L016CX1: 10802 };
  const apply = context.recording(async (name, args) => {
    calls.push(name === 'create_project' ? `${name}:${args.key}` : name);
    if (name === 'create_project') { jira.state.projects.set(args.key, String(ids[args.key])); if (args.key === 'L016CX1') jira.state.privateKeys.push(args.key); return created(args.key, ids[args.key]); }
    if (name === 'create_issue_types') jira.state.issueTypes.push({ id: '31', name: type, subtask: false });
    if (name === 'create_screen') return { screenId: 7 };
    return { status: 'CHANGED' };
  });
  const effective = (id, name) => ({ issueType: { name: type }, screenScheme: { id, name } });
  const tool = async (name, args) => {
    calls.push(`${name}:${args?.project ?? ''}`);
    if (name === 'list_project_leads') return [{ username: 'admin' }];
    if (name === 'list_projects') return [];
    if (name === 'get_project_screens') {
      return controlOnDefault && jira.state.defaultKeys.includes(args.project)
        ? { scheme: { id: 1, name: 'Default Issue Type Screen Scheme', isDefault: true, otherProjects: 2 }, issueTypes: [effective(42, 'D')] }
        : { scheme: { id: 60, name: 'Private', isDefault: false, otherProjects: 0 }, issueTypes: [effective(41, scheme)] };
    }
    throw new Error(`unexpected tool ${name}`);
  };
  return {
    calls,
    run: () => setupShared({
      runId: 'X1', tool, apply, adminPage: jira.adminPage, log: () => {}, rest: async () => jira.state.issueTypes,
      ...(bindProjects ? { bindToDefaultItss: context.bindToDefaultItss } : {}),
    }),
  };
}

test('setupShared creates A and B, binds exactly those two to the DEFAULT scheme and records the evidence the verifier checks', async () => {
  const jira = fakeJira();
  const world = setupWorld({ jira });
  const prepared = await world.run();
  assert.deepEqual(jira.state.defaultKeys, ['AJHNG', 'L016AX1', 'L016BX1']);
  assert.deepEqual(prepared.projectKeys, ['AJHNG', 'L016AX1', 'L016BX1']);
  assert.deepEqual(prepared.binding.defaultBefore, ['AJHNG']);
  assert.deepEqual(prepared.binding.bound.map(({ key, projectId, before, after }) => ({ key, projectId, before, after })), [
    { key: 'L016AX1', projectId: '10800', before: ['AJHNG'], after: ['AJHNG', 'L016AX1'] },
    { key: 'L016BX1', projectId: '10801', before: ['AJHNG', 'L016AX1'], after: ['AJHNG', 'L016AX1', 'L016BX1'] },
  ]);
  assert.equal(writes(jira).length, 2, 'two association POSTs, nothing else');
  // Each project is bound right after its own creation and before the next one is created; C is never bound.
  const order = world.calls.filter((call) => /^create_project|get_project_screens:L016[AB]/u.test(call));
  assert.deepEqual(order.slice(0, 4), ['create_project:L016AX1', 'get_project_screens:L016AX1', 'create_project:L016BX1', 'get_project_screens:L016BX1']);
  assert.ok(!jira.state.defaultKeys.includes('L016CX1'));
});

test('setupShared stops with STOP_CONDITION_5 when the binding is not confirmed or the runner offers none', async () => {
  const jira = fakeJira({ postEffect: 'none' });
  const world = setupWorld({ jira });
  await assert.rejects(world.run(), /STOP_CONDITION_5.*binding of L016AX1 not confirmed/u);
  assert.deepEqual(world.calls.filter((call) => call.startsWith('create_')), ['create_project:L016AX1'], 'no later fixture is created');
  const none = setupWorld({ jira: fakeJira(), bindProjects: false });
  await assert.rejects(none.run(), /STOP_CONDITION_5: the runner offers no bindToDefaultItss/u);
  assert.deepEqual(none.calls, []);
});

test('setupShared stops when Control still reports the project off the DEFAULT scheme after its binding', async () => {
  const world = setupWorld({ jira: fakeJira(), controlOnDefault: false });
  await assert.rejects(world.run(), /STOP_CONDITION_5: fresh project L016AX1 is not on Jira's DEFAULT Issue Type Screen Scheme after its binding/u);
  assert.deepEqual(world.calls.filter((call) => call.startsWith('create_')), ['create_project:L016AX1']);
});
