const normalize = value => String(value ?? '').trim().replace(/\s+/gu, ' ').toLocaleLowerCase('en-US');
const identity = entity => entity.id != null ? `id:${entity.id}` : `name:${entity.name}`;
const category = value => {
  const key = value?.key ?? value;
  return ({new:'TO_DO', indeterminate:'IN_PROGRESS', done:'DONE'})[key] ?? (key == null ? null : String(key).toUpperCase());
};

function canonical(workflow) {
  return {
    name: normalize(workflow.name),
    statuses: (workflow.statuses ?? []).map(status => [normalize(status.name), category(status.category)]).sort((a,b) => JSON.stringify(a).localeCompare(JSON.stringify(b))),
    initialStatus: normalize(workflow.initialStatus),
    transitions: (workflow.transitions ?? []).map(edge => [normalize(edge.name), edge.from == null || edge.from === '*' ? '*' : normalize(edge.from), normalize(edge.to), normalize(edge.screen)]).sort((a,b) => JSON.stringify(a).localeCompare(JSON.stringify(b))),
  };
}

export function compareWorkflow(expected, actual) {
  const wanted = canonical(expected);
  const observed = actual == null ? null : canonical(actual);
  const diff = Object.keys(wanted).filter(field => observed == null || JSON.stringify(wanted[field]) !== JSON.stringify(observed[field])).map(field => ({field, expected:wanted[field], actual:observed?.[field] ?? null}));
  return {matches:diff.length === 0, diff};
}

function decodeHtml(value) {
  return value.replace(/&(#x[\da-f]+|#\d+|amp|quot|apos|lt|gt);/gi, (entity, code) => {
    if (code[0] === '#') return String.fromCodePoint(code[1].toLowerCase() === 'x' ? parseInt(code.slice(2),16) : Number(code.slice(1)));
    return ({amp:'&',quot:'"',apos:"'",lt:'<',gt:'>'})[code.toLowerCase()] ?? entity;
  });
}

function attributes(tag) {
  const result = {};
  for (const match of tag.matchAll(/([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g)) result[match[1].toLowerCase()] = decodeHtml(match[2] ?? match[3] ?? match[4]);
  return result;
}

function forms(html) {
  if (typeof html !== 'string') throw new Error('Jira did not return a native administration page');
  return [...html.matchAll(/<form\b([^>]*)>([\s\S]*?)<\/form>/gi)].map(match => {
    const fields = new URLSearchParams();
    const submits = [];
    for (const input of match[2].matchAll(/<input\b[^>]*>/gi)) {
      const attr = attributes(input[0]);
      if (!attr.name || /\bdisabled(?:\s|=|>)/i.test(input[0])) continue;
      if (attr.type?.toLowerCase() === 'hidden') fields.append(attr.name, attr.value ?? '');
      if (attr.type?.toLowerCase() === 'submit') submits.push(attr);
    }
    return {...attributes(match[1]), fields, submits};
  });
}

function formPath(action, source) {
  // Guarded request accepts only local paths. Never follow a form to another origin.
  const url = new URL(action, `http://localhost:8080${source}`);
  if (url.origin !== 'http://localhost:8080' || url.username || url.password || url.hash) throw new Error('Unsupported native Jira form action');
  return url.pathname + url.search;
}

export class JiraWorkflowState {
  #request;
  #username;
  #password;

  constructor(request, {username, password} = {}) {
    this.#request = request;
    this.#username = username;
    this.#password = password;
  }

  async snapshot() {
    const [workflows, visibleStatuses, allStatuses] = await Promise.all([
      this.#request('/rest/api/2/workflow'),
      this.#request('/rest/api/2/status'),
      this.#request('/rest/workflowDesigner/1.0/statuses', {headers:{'X-Atlassian-Token':'no-check'}}),
    ]);
    if (!Array.isArray(workflows) || !Array.isArray(visibleStatuses) || !Array.isArray(allStatuses) || workflows.some(x => !x.name) || [...visibleStatuses,...allStatuses].some(x => x.id == null || !x.name)) throw new Error('Invalid independent Jira snapshot');
    // /api/2/status is project-permission filtered, even for administrators.
    // Designer's global REST list includes unused and unassociated statuses.
    const visible = new Map(visibleStatuses.map(x => [String(x.id),x]));
    const statuses = new Map(allStatuses.map(x => [String(x.id),x]));
    for (const status of visibleStatuses) statuses.set(String(status.id),status);
    return {
      // Jira 8.3 returns no workflow id: the exact name is its native identity.
      workflows:workflows.map(x => ({...(x.id != null ? {id:String(x.id)} : {}), name:x.name})),
      statuses:[...statuses.values()].map(x => ({id:String(x.id), name:x.name, category:category(visible.get(String(x.id))?.statusCategory)})),
    };
  }

  async listScreens() {
    // Jira 8.3's native endpoint returns the complete unpaginated catalog.
    // Designer's separate screen list includes a synthetic id=0 "None" option.
    const screens = await this.#request('/rest/api/2/screens');
    if (!Array.isArray(screens) || screens.some(screen => !/^[1-9]\d*$/u.test(String(screen?.id ?? '')) || typeof screen.name !== 'string' || !screen.name.trim()) || new Set(screens.map(screen => String(screen.id))).size !== screens.length) throw new Error('Invalid independent Jira screen catalog');
    return screens.map(screen => ({id:String(screen.id), name:screen.name}));
  }

  async readWorkflow(name) {
    const snapshot = await this.snapshot();
    const workflow = snapshot.workflows.find(x => normalize(x.name) === normalize(name));
    if (!workflow) return null;
    const [graph, categories] = await Promise.all([
      this.#request(`/rest/workflowDesigner/1.0/workflows?name=${encodeURIComponent(workflow.name)}&draft=false`, {headers:{'X-Atlassian-Token':'no-check'}}),
      this.#request('/rest/api/2/statuscategory'),
    ]);
    if (!Array.isArray(categories)) throw new Error('Invalid independent Jira status categories');
    const categoryById = new Map(categories.map(x => [String(x.id),category(x)]));
    const layout = graph?.layout;
    if (!Array.isArray(layout?.statuses) || !Array.isArray(layout?.transitions)) throw new Error('Invalid independent Jira workflow graph');
    const screenEdges = layout.transitions.filter(edge => !edge.initial);
    if (screenEdges.some(edge => edge.screenId == null && edge.screenName != null && String(edge.screenName).trim())) throw new Error('Workflow graph screen binding has no independently resolvable id');
    const assignedScreens = screenEdges.some(edge => edge.screenId != null && String(edge.screenId) !== '0');
    const byScreenId = new Map((assignedScreens ? await this.listScreens() : []).map(screen => [screen.id,screen.name]));
    const byStatusId = new Map(snapshot.statuses.map(x => [x.id,x]));
    const byNode = new Map();
    const statuses = [];
    for (const node of layout.statuses) {
      if (node.initial) continue;
      const status = byStatusId.get(String(node.statusId));
      if (!status) throw new Error('Workflow graph references a status absent from Jira REST');
      byNode.set(String(node.id), status.name);
      const observedCategory = status.category ?? categoryById.get(String(node.statusCategory?.id));
      if (observedCategory == null) throw new Error('Workflow graph status has no independently readable category');
      statuses.push({...status, category:observedCategory});
    }
    const initials = layout.transitions.filter(x => x.initial);
    if (initials.length !== 1 || !byNode.has(String(initials[0].targetId))) throw new Error('Workflow graph has no unique initial transition target');
    const transitions = [];
    const globals = new Set();
    for (const edge of layout.transitions) {
      if (edge.initial) continue;
      const to = byNode.get(String(edge.targetId));
      const from = edge.globalTransition ? null : byNode.get(String(edge.sourceId));
      if (to == null || (!edge.globalTransition && from == null)) throw new Error('Workflow graph has an unresolved transition endpoint');
      if (edge.globalTransition) {
        // Designer may render a single global action from several source nodes.
        const key = JSON.stringify([edge.actionId ?? edge.name, to]);
        if (globals.has(key)) continue;
        globals.add(key);
      }
      const screenId = edge.screenId == null || String(edge.screenId) === '0' ? null : String(edge.screenId);
      const screen = screenId == null ? null : byScreenId.get(screenId);
      if (screenId != null && screen == null) throw new Error('Workflow graph references a screen absent from Jira REST');
      transitions.push({name:edge.name, from, to, ...(screen != null ? {screen} : {})});
    }
    return {...workflow, statuses, initialStatus:byNode.get(String(initials[0].targetId)), transitions};
  }

  async #page(path, options = {}) {
    let response = await this.#request(path, {...options, raw:true, allowLocalRedirect:true, headers:{accept:'text/html', ...options.headers}});
    for (let redirects = 0; response?.redirect; redirects++) {
      if (redirects === 5) throw new Error('Too many native Jira page redirects');
      path = response.redirect;
      response = await this.#request(path, {raw:true, allowLocalRedirect:true, headers:{accept:'text/html'}});
    }
    return {html:response, path};
  }

  async #delete(path, action, key, value, confirmation) {
    let page = await this.#page(path);
    let auth = forms(page.html).find(form => form.action === '/secure/admin/WebSudoAuthenticate.jspa');
    if (auth) {
      if (!this.#username || !this.#password) throw new Error('Native Jira cleanup requires WebSudo credentials');
      const user = [...page.html.matchAll(/<meta\b[^>]*>/gi)].map(x => attributes(x[0])).find(x => x.name === 'ajs-remote-user')?.content;
      if (user !== this.#username) throw new Error('Native Jira WebSudo session does not match the configured user');
      if (auth.method?.toLowerCase() !== 'post' || !auth.fields.get('atl_token') || auth.fields.get('webSudoDestination') !== path || auth.fields.get('webSudoIsPost') !== 'false') throw new Error('Unsupported native Jira WebSudo form');
      auth.fields.set('webSudoPassword', this.#password);
      await this.#page(auth.action, {method:'POST', body:auth.fields.toString(), headers:{'content-type':'application/x-www-form-urlencoded'}});
      page = await this.#page(path);
    }
    const form = forms(page.html).find(form => formPath(form.action ?? '',page.path) === action);
    if (!form || form.method?.toLowerCase() !== 'post' || form.fields.get(key) !== String(value) || form.fields.get(confirmation) !== 'true' || !form.fields.get('atl_token')) throw new Error('Jira did not offer a supported native deletion form (entity may be in use)');
    const submit = form.submits.find(x => x.name === 'Delete');
    if (!submit) throw new Error('Jira native deletion is unavailable (entity may be in use)');
    form.fields.set(submit.name, submit.value ?? 'Delete');
    // Submit Jira's own CSRF token and confirmation. No REST DELETE, force flag,
    // usage-check bypass, or X-Atlassian-Token override is used for deletion.
    await this.#page(action, {method:'POST', body:form.fields.toString(), headers:{'content-type':'application/x-www-form-urlencoded'}});
    const after = await this.snapshot();
    const entities = key === 'workflowName' ? after.workflows : after.statuses;
    if (entities.some(entity => String(key === 'workflowName' ? entity.name : entity.id) === String(value))) throw new Error('Jira native deletion did not remove the entity (it may be in use)');
  }

  async deleteWorkflow(entity) {
    await this.#delete(`/secure/admin/workflows/DeleteWorkflow!default.jspa?workflowName=${encodeURIComponent(entity.name)}`, '/secure/admin/workflows/DeleteWorkflow.jspa', 'workflowName', entity.name, 'confirmedDelete');
  }

  async deleteStatus(entity) {
    await this.#delete(`/secure/admin/DeleteStatus!default.jspa?id=${encodeURIComponent(entity.id)}`, '/secure/admin/DeleteStatus.jspa', 'id', entity.id, 'confirm');
  }
}

const fallback = 'Cleanup requires manual review. No reset.sh exists in this repository; a hypothetical reset.sh fallback would need to be supplied separately and used only for this disposable Jira instance.';

export async function cleanupCreated({api, before, workflow, created}) {
  const warnings = [];
  const deleted = {workflowNames:[], statusIds:[]};
  const oldWorkflows = new Set(before.workflows.map(identity));
  const oldStatuses = new Set(before.statuses.map(identity));
  const statusNames = new Set(workflow.statuses.map(x => x.name));
  const evidenceWorkflows = new Set(created?.workflowNames ?? []);
  const evidenceStatuses = new Set((created?.statusIds ?? []).map(String));
  let after;
  let previousPreserved = null;
  const matchingWorkflows = snapshot => snapshot.workflows.filter(x => !oldWorkflows.has(identity(x)) && x.name === workflow.name);
  const matchingStatuses = snapshot => snapshot.statuses.filter(x => !oldStatuses.has(identity(x)) && statusNames.has(x.name));
  const preserved = snapshot => before.workflows.every(x => snapshot.workflows.some(y => identity(y) === identity(x))) && before.statuses.every(x => snapshot.statuses.some(y => identity(y) === identity(x)));
  try {
    after = await api.snapshot();
    for (const entity of matchingWorkflows(after)) {
      if (!evidenceWorkflows.has(entity.name)) {
        warnings.push(`Refusing to delete workflow ${entity.name}: no dialogue creation evidence.`);
        continue;
      }
      try { await api.deleteWorkflow(entity); deleted.workflowNames.push(entity.name); }
      catch (error) { warnings.push(`Workflow cleanup: ${error.message}`); }
    }
    // Read independently after workflow deletion, before deleting any statuses.
    after = await api.snapshot();
    for (const entity of matchingStatuses(after)) {
      if (!evidenceStatuses.has(String(entity.id))) {
        warnings.push(`Refusing to delete status ${entity.name}: no dialogue creation evidence.`);
        continue;
      }
      if (matchingWorkflows(after).length) {
        warnings.push(`Preserving status ${entity.name}: the new workflow has not been removed.`);
        continue;
      }
      try { await api.deleteStatus(entity); deleted.statusIds.push(String(entity.id)); }
      catch (error) { warnings.push(`Status cleanup: ${error.message}`); }
    }
  } catch (error) { warnings.push(`Cleanup REST snapshot: ${error.message}`); }
  try {
    after = await api.snapshot();
    previousPreserved = preserved(after);
    if (!previousPreserved) warnings.push('REST verification found missing pre-existing workflow/status identities.');
    if (matchingWorkflows(after).length || matchingStatuses(after).length) warnings.push('REST verification found new expected workflow/status entities still present.');
  } catch (error) { warnings.push(`Cleanup REST verification: ${error.message}`); }
  if (warnings.length) warnings.push(fallback);
  return {status:warnings.length ? 'WARNING' : 'CLEAN', warnings, previousPreserved, deleted, remaining:after ? {workflows:matchingWorkflows(after), statuses:matchingStatuses(after)} : null};
}
