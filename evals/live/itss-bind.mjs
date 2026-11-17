// The ONE admin write the live harness may do. Setup of `itss-mappings-shared-default` binds each of
// its two fresh projects (created by Control in the same run) to Jira's Default Issue Type Screen Scheme (id 1) with the form
// POST of the Jira UI («Select Issue Type Screen Scheme»), because no Control tool associates a project with an existing scheme.
//
// This is an allowlist, not general write access:
//  - the guard accepts only `L016A<RUN>` / `L016B<RUN>` projects whose creation (status CREATED, same id) was observed in this run;
//  - the HTTP layer sends only GET of the schemes list / the form page of one numeric project and the one POST below, with
//    exactly the form's four fields and schemeId=1; every other route, method or field set is refused before any request;
//  - setup.mjs gets only `bindToDefaultItss(key)`; hooks, verify and the runner's `adminPage` (GET-only when setup is confirmed)
//    never receive a way to write.
import { DEFAULT_ITSS_ID, itssProjectKeys } from './itss-mappings-common.mjs';
import { same } from './configure-common.mjs';

export const LIST_ROUTE = '/secure/admin/ViewIssueTypeScreenSchemes.jspa';
export const FORM_ROUTE = '/secure/admin/SelectIssueTypeScreenScheme!default.jspa';
export const BIND_ROUTE = '/secure/admin/SelectIssueTypeScreenScheme.jspa';
const FORM_FIELDS = ['Associate', 'atl_token', 'projectId', 'schemeId'];
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

const refuse = (message) => { throw new Error(`Refused: ${message}`); };

// --- guard -------------------------------------------------------------------------------------------------------

/** Records, from Control's terminal results, the projects this run created (reused projects and mismatching ids never count). */
export function createProjectRegistry() {
  const created = new Map();
  return {
    record(toolName, args, result) {
      if (toolName !== 'create_project' || args?.action !== 'create' || result?.status !== 'CREATED') return;
      const id = result.projectId;
      const listed = result.report?.created?.some((item) => item.kind === 'PROJECT' && String(item.id) === String(id));
      if (typeof result.key === 'string' && result.key === args.key && Number.isInteger(id) && id > 0 && listed) created.set(result.key, String(id));
    },
    idOf: (key) => created.get(key) ?? null,
  };
}

/** The project the harness may bind: a fresh A or B project of this run, created in this run, and only to the Default scheme. */
export function assertBindTarget({ runId, registry, key, schemeId }) {
  if (String(schemeId) !== DEFAULT_ITSS_ID) refuse(`only the Default Issue Type Screen Scheme (id ${DEFAULT_ITSS_ID}) may be bound, not ${schemeId}`);
  if (typeof runId !== 'string' || !/^[A-Za-z0-9]+$/u.test(runId)) refuse('no valid run id');
  if (!new RegExp(`^L016[AB]${runId.toUpperCase()}$`, 'u').test(String(key))) refuse(`project ${key} is not a fresh L016A/L016B project of run ${runId}`);
  const id = registry.idOf(key);
  if (!id) refuse(`project ${key} was not created by this run's setup`);
  return { key, projectId: id };
}

// --- form --------------------------------------------------------------------------------------------------------

const attribute = (tag, name) => new RegExp(`(?:^|\\s)${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))`, 'u').exec(tag)?.slice(1).find((value) => value !== undefined);

/** The association form of Jira's «Select Issue Type Screen Scheme» page: hidden projectId and atl_token, the schemeId options, the submit button. */
export function parseBindForm(html) {
  const forms = [...String(html).matchAll(/<form\b[^>]*>[\s\S]*?<\/form>/gu)].map((match) => match[0])
    .filter((form) => /action\s*=\s*"SelectIssueTypeScreenScheme\.jspa"/u.test(form.slice(0, form.indexOf('>') + 1)));
  if (forms.length !== 1) throw new Error(`The association form is on the page ${forms.length} times, expected once`);
  const form = forms[0];
  const inputs = [...form.matchAll(/<input\b[^>]*>/gu)].map((match) => match[0]);
  const single = (name) => {
    const found = inputs.filter((tag) => attribute(tag, 'name') === name);
    if (found.length !== 1) throw new Error(`The association form has ${found.length} inputs named ${name}, expected one`);
    return found[0];
  };
  const select = /<select\b[^>]*\bname="schemeId"[^>]*>([\s\S]*?)<\/select>/u.exec(form)?.[1];
  if (select === undefined) throw new Error('The association form has no schemeId select');
  const options = [...select.matchAll(/<option\b([^>]*)>([^<]*)/gu)].map(([, tag, label]) => ({ id: attribute(tag, 'value'), label: label.trim() }));
  const submit = single('Associate');
  return {
    atlToken: attribute(single('atl_token'), 'value') ?? '',
    projectId: attribute(single('projectId'), 'value') ?? '',
    submit: attribute(submit, 'value') ?? 'Associate',
    options,
  };
}

// --- http (guarded) ----------------------------------------------------------------------------------------------

const formRoute = (route) => /^\/secure\/admin\/SelectIssueTypeScreenScheme!default\.jspa\?projectId=\d+$/u.test(route);

/**
 * Wraps a raw transport `{ get(route, cookies), post(route, fields, cookies) }` into the only requests the binder may send.
 * Refusals throw before the transport is touched.
 */
export function guardTransport(raw) {
  return {
    get(route, cookies) {
      if (route !== LIST_ROUTE && !formRoute(route)) refuse(`GET ${route} is not an allowlisted admin page`);
      return raw.get(route, cookies);
    },
    post(route, fields, cookies) {
      if (route !== BIND_ROUTE) refuse(`POST ${route} is not the association action`);
      if (!same(Object.keys(fields ?? {}).sort(), FORM_FIELDS)) refuse(`POST fields ${Object.keys(fields ?? {}).join(',')} differ from the association form`);
      if (String(fields.schemeId) !== DEFAULT_ITSS_ID) refuse(`POST schemeId ${fields.schemeId} is not the Default scheme`);
      if (!/^\d+$/u.test(String(fields.projectId))) refuse(`POST projectId ${fields.projectId} is not a numeric project id`);
      if (typeof fields.atl_token !== 'string' || !fields.atl_token) refuse('POST without the form token');
      return raw.post(route, fields, cookies);
    },
  };
}

const cookieHeader = (response) => (response.headers.getSetCookie?.() ?? []).map((cookie) => cookie.split(';')[0]).join('; ');

/** Local-only fetch transport with the loader token; redirects are not followed, the session cookies of the GET go with the POST. */
export function fetchTransport({ baseUrl, authorization, timeoutMs = 60_000, fetchImpl = fetch }) {
  if (!LOCAL_HOSTS.has(new URL(baseUrl).hostname)) refuse(`${baseUrl} is not a local test Jira`);
  const send = async (route, init, cookies) => {
    const response = await fetchImpl(baseUrl + route, {
      ...init, redirect: 'manual', signal: AbortSignal.timeout(timeoutMs),
      headers: { authorization, accept: 'text/html', 'X-Atlassian-Token': 'no-check', ...(cookies ? { cookie: cookies } : {}), ...init.headers },
    });
    return { status: response.status, location: response.headers.get('location'), text: await response.text(), cookies: cookieHeader(response) };
  };
  return {
    get: (route, cookies) => send(route, { method: 'GET' }, cookies),
    post: (route, fields, cookies) => send(route, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(fields).toString() }, cookies),
  };
}

// --- binder ------------------------------------------------------------------------------------------------------

/**
 * `bind(key)`: guard → read the Default scheme's projects → GET the form (token, project id, Default option) → POST →
 * re-read the Default scheme's projects, which must be exactly the earlier ones plus this project. Returns the evidence.
 */
export function createItssBinder({ runId, registry, transport, log = () => {} }) {
  const http = guardTransport(transport);
  const readKeys = () => itssProjectKeys(async (route) => http.get(route), DEFAULT_ITSS_ID);
  return {
    async bind(key) {
      const { projectId } = assertBindTarget({ runId, registry, key, schemeId: DEFAULT_ITSS_ID });
      const before = await readKeys();
      if (before.includes(key)) throw new Error(`STOP_CONDITION_5: fresh project ${key} is already listed on the Default Issue Type Screen Scheme`);
      const page = await http.get(`${FORM_ROUTE}?projectId=${projectId}`);
      const form = parseBindForm(page.text);
      if (form.projectId !== projectId) throw new Error(`STOP_CONDITION_5: the association form is for project ${form.projectId}, not ${projectId}`);
      if (!form.options.some((option) => option.id === DEFAULT_ITSS_ID)) throw new Error('STOP_CONDITION_5: the association form does not offer the Default Issue Type Screen Scheme');
      const post = await http.post(BIND_ROUTE, { projectId, schemeId: DEFAULT_ITSS_ID, atl_token: form.atlToken, Associate: form.submit }, page.cookies);
      if (post.status < 300 || post.status >= 400) throw new Error(`STOP_CONDITION_5: the association POST for ${key} returned HTTP ${post.status} instead of a redirect`);
      const after = await readKeys();
      if (!same(after, [...before, key].sort())) {
        throw new Error(`STOP_CONDITION_5: binding of ${key} not confirmed: the Default scheme lists [${after.join(', ')}], expected [${[...before, key].sort().join(', ')}]`);
      }
      log(`Bound ${key} (project ${projectId}) to the Default Issue Type Screen Scheme: ${before.length} → ${after.length} projects`);
      return { key, projectId, schemeId: DEFAULT_ITSS_ID, before, after, post: { status: post.status, location: post.location } };
    },
  };
}

/** The runner's wiring: records this run's created projects and offers the one allowed write to setup. */
export function bindingContext({ runId, baseUrl, authorization, log, fetchImpl }) {
  const registry = createProjectRegistry();
  const binder = createItssBinder({ runId, registry, transport: fetchTransport({ baseUrl, authorization, fetchImpl }), log });
  return {
    recording: (apply) => async (name, args) => {
      const result = await apply(name, args);
      registry.record(name, args, result);
      return result;
    },
    bindToDefaultItss: (key) => binder.bind(key),
  };
}
