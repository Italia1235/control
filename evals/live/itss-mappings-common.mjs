// Live scenarios of set_issue_type_screen_scheme_mappings (itss-mappings-private, itss-mappings-shared-default).
// Setup writes only fresh `L016-<runId>-` objects through Control with the runner's simulated-user confirmation (GET-only
// REST/admin pages), plus one harness step of itss-mappings-shared-default: `bindToDefaultItss` (itss-bind.mjs)
// binds that scenario's fresh projects A and B to the DEFAULT scheme. verify.mjs and hooks.mjs only read: Control readers, REST and GET admin pages, never XML/DB/UI writes.
// Contract = Jira state, tool trace and tool response fields; table/wording of the replies is advisory only.
import { PLUGIN_TOOLS, applies, hasTable, parseResult } from './checks.mjs';
import { advisoryCheck, check, createScreen, outcomeOf, previewTable, same } from './configure-common.mjs';
import { projectManagement } from './project-templates.mjs';

export const TOOL = 'set_issue_type_screen_scheme_mappings';
/** Jira's own Default Issue Type Screen Scheme always has id 1. */
export const DEFAULT_ITSS_ID = '1';
const READS = new Set([...PLUGIN_TOOLS].filter((name) => /^(get_|list_|plan_)/u.test(name)));

/** Fixture boundaries for the simulated user who authorizes setup previews (replaces the default boundaries of setup-confirmation.mjs). */
export const confirmationScope = {
  rules: (runId) => `You are the user authorizing setup for LOCAL-016 run ${runId}.
Distinguish creation of fresh fixture objects, references that leave existing objects unchanged, and changes to foreign configuration:
- Creation: only fresh L016-${runId}- objects are in scope: issue types, standalone screens, fixture projects (key L016 + one letter + ${runId.toUpperCase()}) and the Screen Schemes that set_project_operation_screens derives for those projects. A new issue type is global by Jira design (Jira adds it to its default issue type scheme); global catalog visibility alone is not a change to foreign configuration.
- Project creation: create_project with action "create" and templateKey com.atlassian.jira-core-project-templates:jira-core-project-management creates a new BUSINESS project using Jira's standard Project Management template. The template may attach that new project to existing default/shared schemes or give it its own; those references are permitted, not a promise of cloned/private schemes. Selecting an existing active user as lead changes only the new project, not the user. No issues are requested.
- set_project_issue_types: only for a fixture project of this run and only with this run's fresh L016-${runId}- issue types; the project's issue type scheme becoming private is part of the fixture.
- Screens: creating a fresh standalone screen with its own tab and fields, without assigning it to any project, scheme or workflow, is permitted; listing existing system fields such as summary on it does not change those fields.
- set_project_operation_screens: only for a fixture project of this run and only with this run's fresh screens. If the project's Issue Type Screen Scheme is private it is updated in place; if it is shared or Jira's default, ONE new scheme copy replaces the association of that project only and the source stays unchanged. Approve only when the preview changes nothing used by another project: never edit a scheme shared with foreign projects, Jira's default scheme or another project's association.
- set_issue_type_screen_scheme_mappings is never a setup step; it is the subject of the dialogue. REJECT any setup preview that edits rows of an existing Issue Type Screen Scheme.
- Forbidden: changing foreign objects/configuration, reusing or modifying fixtures left by another run, issues or issue migration, workflows, global catalog updates, user/account changes, deletion, cleanup, rollback and mutation bypass. A fresh name alone does not authorize a foreign/shared mutation.
`,
};

// --- Jira GET pages (admin, no REST equivalent) ------------------------------------------------------------------

const uniqueIds = (html, pattern) => [...new Set([...html.matchAll(pattern)].map((match) => match[1]))].sort((a, b) => Number(a) - Number(b));

/** Ids of every Issue Type Screen Scheme on Jira's list page. */
export async function itssIds(adminPage) {
  return uniqueIds((await adminPage('/secure/admin/ViewIssueTypeScreenSchemes.jspa')).text, /ConfigureIssueTypeScreenScheme\.jspa\?id=(\d+)/gu);
}

/** Ids of every Screen Scheme on Jira's list page. */
export async function screenSchemeIds(adminPage) {
  return uniqueIds((await adminPage('/secure/admin/ViewFieldScreenSchemes.jspa')).text, /ConfigureFieldScreenScheme\.jspa\?id=(\d+)/gu);
}

/** Keys of the projects the list page shows for one Issue Type Screen Scheme (sorted). */
export async function itssProjectKeys(adminPage, id) {
  const html = (await adminPage('/secure/admin/ViewIssueTypeScreenSchemes.jspa')).text;
  const row = [...html.matchAll(/<tr\b[^>]*data-issue-type-screen-scheme-name[\s\S]*?<\/tr>/gu)].map((match) => match[0])
    .find((candidate) => candidate.includes(`ConfigureIssueTypeScreenScheme.jspa?id=${id}"`));
  if (!row) throw new Error(`Issue Type Screen Scheme ${id} is not on the list page`);
  return [...new Set([...row.matchAll(/project-config\/([A-Za-z0-9_]+)\//gu)].map((match) => match[1]))].sort();
}

/** The own rows of one scheme as Jira's configure page shows them: [{ type, scheme }] sorted by type; DEFAULT is the default row. */
export async function itssTable(adminPage, id) {
  const html = (await adminPage(`/secure/admin/ConfigureIssueTypeScreenScheme.jspa?id=${id}`)).text;
  const table = /<table id="issue-type-table"[\s\S]*?<\/table>/u.exec(html)?.[0];
  if (!table) throw new Error(`Issue Type Screen Scheme ${id}: the row table is not on its page`);
  return [...table.matchAll(/<tr\b([^>]*)>([\s\S]*?)<\/tr>/gu)].map(([, attributes, cells]) => {
    const scheme = /ConfigureFieldScreenScheme\.jspa\?id=(\d+)/u.exec(cells)?.[1];
    const type = /data-scheme-field="?issue-type"?[^>]*>([^<]*)</u.exec(cells)?.[1];
    return scheme && type !== undefined ? { type: /data-default-association="?true"?/u.test(attributes) ? 'DEFAULT' : type.trim(), scheme } : null;
  }).filter(Boolean).sort((a, b) => a.type.localeCompare(b.type));
}

const sortedRows = (rows) => [...rows].sort((a, b) => a.type.localeCompare(b.type));
const withRow = (rows, type, scheme) => sortedRows([...rows.filter((row) => row.type !== type), { type, scheme: String(scheme) }]);
const withoutRow = (rows, type) => rows.filter((row) => row.type !== type);

// --- setup -------------------------------------------------------------------------------------------------------

async function freshPrefix({ runId, rest }) {
  const prefix = `L016-${runId}-`;
  const types = await rest('GET', '/rest/api/2/issuetype');
  if (types.some((type) => type.name.startsWith(prefix))) throw new Error('Run prefix already exists');
  return prefix;
}

async function projectLead(tool) {
  const lead = (await tool('list_project_leads', { query: 'admin' }))[0];
  if (!lead?.username) throw new Error('No active project lead returned by Control');
  return lead.username;
}

async function createProject({ tool, apply }, key, name, lead) {
  const found = await tool('list_projects', { query: key });
  if (JSON.stringify(found).includes(`"${key}"`)) throw new Error(`Project key ${key} already exists`);
  await apply('create_project', projectManagement({ key, name, lead }));
}

const effective = (read, typeName) => read.issueTypes?.find((row) => row.issueType?.name === typeName)?.screenScheme ?? null;
const ref = (scheme) => ({ id: String(scheme.id), name: scheme.name });

async function typeRefs(rest, names) {
  const all = await rest('GET', '/rest/api/2/issuetype');
  return Object.fromEntries(Object.entries(names).map(([key, name]) => {
    const found = all.filter((type) => type.name === name && !type.subtask);
    if (found.length !== 1) throw new Error(`Fresh issue type ${name} not found exactly once (${found.length})`);
    return [key, { id: String(found[0].id), name }];
  }));
}

async function snapshot(adminPage, itssId) {
  return { table: await itssTable(adminPage, itssId), itssIds: await itssIds(adminPage), screenSchemeIds: await screenSchemeIds(adminPage) };
}

/** itss-mappings-private: a fresh project with a private Issue Type Screen Scheme and two own Screen Schemes. */
export async function setupPrivate({ runId, tool, apply, rest, adminPage, log }) {
  const prefix = await freshPrefix({ runId, rest });
  const project = `L016P${runId.toUpperCase()}`;
  const typeNames = { A: `${prefix}TypeA`, B: `${prefix}TypeB`, C: `${prefix}TypeC` };
  const schemeNames = { S1: `${prefix}scheme-1`, S2: `${prefix}scheme-2` };
  const lead = await projectLead(tool);
  await apply('create_issue_types', { issueTypes: Object.entries(typeNames).map(([key, name]) => ({ key: `type${key}`, name, subtask: false })) });
  await createProject({ tool, apply }, project, `${prefix}private-project`, lead);
  await apply('set_project_issue_types', { project, issueTypes: Object.values(typeNames) });
  const screens = [await createScreen(apply, `${prefix}screen-1`, ['summary']), await createScreen(apply, `${prefix}screen-2`, ['summary'])];
  const operations = (screen) => ({ CREATE: screen.name, EDIT: screen.name, VIEW: screen.name });
  await apply('set_project_operation_screens', { project, mappings: [
    { issueTypes: [typeNames.A], screenSchemeName: schemeNames.S1, screens: operations(screens[0]) },
    { issueTypes: [typeNames.B], screenSchemeName: schemeNames.S2, screens: operations(screens[1]) },
  ] });

  const read = await tool('get_project_screens', { project });
  if (read.scheme.isDefault || read.scheme.otherProjects !== 0) throw new Error(`Project ${project} has no private Issue Type Screen Scheme: ${JSON.stringify(read.scheme)}`);
  const [s1, s2, d] = [typeNames.A, typeNames.B, typeNames.C].map((name) => effective(read, name));
  if (s1?.name !== schemeNames.S1 || s2?.name !== schemeNames.S2 || !d || [s1.id, s2.id].includes(d.id)) {
    throw new Error(`Unexpected effective Screen Schemes: ${JSON.stringify({ s1, s2, d })}`);
  }
  const types = await typeRefs(rest, typeNames);
  const base = await snapshot(adminPage, read.scheme.id);
  const defaultRows = base.table.filter((row) => row.type === 'DEFAULT');
  if (!same(base.table.filter((row) => [types.A.name, types.B.name].includes(row.type)).map((row) => row.scheme).sort(), [String(s1.id), String(s2.id)].sort())
      || base.table.some((row) => row.type === types.C.name) || !defaultRows.length) {
    throw new Error(`Unexpected rows of the private scheme: ${JSON.stringify(base.table)}`);
  }
  const keys = await itssProjectKeys(adminPage, read.scheme.id);
  if (!same(keys, [project])) throw new Error(`The private scheme lists other projects: ${keys.join(', ')}`);
  log(`Prepared ${project}: scheme ${read.scheme.id} «${read.scheme.name}», ${types.A.name}→${s1.name}, ${types.B.name}→${s2.name}; no issues, cleanup or rollback`);
  return {
    prefix, project, lead, types, itss: { id: String(read.scheme.id), name: read.scheme.name },
    schemes: { S1: ref(s1), S2: ref(s2), D: ref(d) }, ...base,
    known: { project, typeToReplace: types.A.name, typeToRemoveRow: types.B.name, targetScreenScheme: schemeNames.S2 },
  };
}

/**
 * itss-mappings-shared-default: two fresh projects bound to Jira's DEFAULT scheme by the harness (no Control tool does it),
 * a third with a private Screen Scheme S. Every step stops the setup (STOP_CONDITION_5) when the binding is not confirmed.
 */
export async function setupShared({ runId, tool, apply, rest, adminPage, bindToDefaultItss, log }) {
  if (typeof bindToDefaultItss !== 'function') throw new Error('STOP_CONDITION_5: the runner offers no bindToDefaultItss for this setup');
  const prefix = await freshPrefix({ runId, rest });
  const suffix = runId.toUpperCase();
  const keys = { A: `L016A${suffix}`, B: `L016B${suffix}`, C: `L016C${suffix}` };
  const typeName = `${prefix}Type`;
  const schemeName = `${prefix}S`;
  const lead = await projectLead(tool);
  const onDefault = async (project) => {
    const read = await tool('get_project_screens', { project });
    if (!read.scheme.isDefault || String(read.scheme.id) !== DEFAULT_ITSS_ID) {
      throw new Error(`STOP_CONDITION_5: fresh project ${project} is not on Jira's DEFAULT Issue Type Screen Scheme after its binding `
        + `(scheme ${read.scheme.id} «${read.scheme.name}», other projects ${read.scheme.otherProjects}); the LOCAL-016 spec stops here`);
    }
    return read;
  };
  const defaultBefore = await itssProjectKeys(adminPage, DEFAULT_ITSS_ID);
  const bound = [];
  // Created by Control in this run first, then bound at once: every later step is skipped when the binding is not confirmed.
  for (const key of [keys.A, keys.B]) {
    await createProject({ tool, apply }, key, `${prefix}project-${key === keys.A ? 'a' : 'b'}`, lead);
    bound.push(await bindToDefaultItss(key));
    await onDefault(key);
  }
  await createProject({ tool, apply }, keys.C, `${prefix}project-c`, lead);
  await apply('create_issue_types', { issueTypes: [{ key: 'type', name: typeName, subtask: false }] });
  for (const project of Object.values(keys)) await apply('set_project_issue_types', { project, issueTypes: [typeName] });
  const screen = await createScreen(apply, `${prefix}screen`, ['summary']);
  await apply('set_project_operation_screens', { project: keys.C, mappings: [
    { issueTypes: [typeName], screenSchemeName: schemeName, screens: { CREATE: screen.name, EDIT: screen.name, VIEW: screen.name } },
  ] });

  const [readA, readB, readC] = [await onDefault(keys.A), await onDefault(keys.B), await tool('get_project_screens', { project: keys.C })];
  const d = effective(readA, typeName), s = effective(readC, typeName);
  if (readA.scheme.id !== readB.scheme.id || !d || effective(readB, typeName)?.id !== d.id || !s || s.name !== schemeName || s.id === d.id
      || readC.scheme.isDefault || readC.scheme.otherProjects !== 0) {
    throw new Error(`Unexpected fixture state: ${JSON.stringify({ a: readA.scheme, b: readB.scheme, c: readC.scheme, d, s })}`);
  }
  const type = (await typeRefs(rest, { type: typeName })).type;
  const base = await snapshot(adminPage, readA.scheme.id);
  if (base.table.some((row) => row.type === typeName) || !base.table.some((row) => row.type === 'DEFAULT')) {
    throw new Error(`Unexpected rows of the DEFAULT scheme: ${JSON.stringify(base.table)}`);
  }
  const projectKeys = await itssProjectKeys(adminPage, readA.scheme.id);
  const expectedKeys = [...defaultBefore, keys.A, keys.B].sort();
  if (!same(projectKeys, expectedKeys)) {
    throw new Error(`STOP_CONDITION_5: the DEFAULT scheme lists [${projectKeys.join(', ')}], expected exactly [${expectedKeys.join(', ')}] (its earlier projects plus the two fresh ones)`);
  }
  log(`Prepared ${keys.A}, ${keys.B} bound to DEFAULT scheme ${readA.scheme.id} (${projectKeys.length} projects), ${keys.C} with ${schemeName}; no cleanup`);
  return {
    prefix, projects: keys, lead, type, itss: { id: String(readA.scheme.id), name: readA.scheme.name }, privateItss: String(readC.scheme.id),
    schemes: { S: ref(s), D: ref(d) }, projectKeys, ...base,
    binding: { defaultBefore, bound },
    known: { projectA: keys.A, projectB: keys.B, newIssueType: typeName, screenScheme: schemeName },
  };
}

// --- verification ------------------------------------------------------------------------------------------------

const plansOf = (trace) => trace.filter((entry) => entry.tool === TOOL && entry.arguments?.dryRun === true)
  .map((entry) => ({ entry, result: parseResult(entry.result) ?? {} })).filter(({ result }) => result.planId);
const rowOf = (result, typeId) => result.rows?.find((row) => String(row.issueType?.id) === String(typeId));
/** An effective Screen Scheme read from a response: id, and whether it is reached through the DEFAULT row. */
const scheme = (value, id, viaDefault) => Boolean(value) && String(value.id) === String(id) && Boolean(value.viaDefault) === viaDefault;
const wordsIn = (reply, number) => new RegExp(`(?<![\\d])${number}(?![\\d])`, 'u').test(reply);

/** Contract: exactly `count` plans of the tool, one start_operation each by its own plan, and no other write in the dialogue. */
function protocolChecks(trace, count) {
  const plans = plansOf(trace);
  const starts = trace.filter((entry) => entry.tool === 'start_operation');
  const runs = applies(trace, TOOL);
  const foreign = trace.filter((entry) => !READS.has(entry.tool) && entry.tool !== TOOL && entry.tool !== 'start_operation');
  return [
    check(`${count} план(а) ${TOOL} с planId, по одному start_operation на каждый по своему плану`,
      plans.length === count && starts.length === count && runs.length === count
        && runs.every((run, index) => run.preview === plans[index].entry && run.planId === plans[index].result.planId),
      `планов ${plans.length}, start_operation ${starts.length}, по плану ${runs.length}`),
    check(`других записей нет: только чтение, ${TOOL} и start_operation`,
      foreign.length === 0 && starts.every((start) => start.arguments?.toolName === TOOL),
      foreign.map((entry) => `#${entry.seq} ${entry.tool}`).join(', ') || 'нет'),
  ];
}

/** Contract: each apply is COMPLETED/CHANGED with a re-read table; every row APPLIED. */
function outcomeChecks(trace, expectedRows) {
  return applies(trace, TOOL).map((run, index) => {
    const outcome = outcomeOf(trace, run);
    const result = outcome.result;
    const rows = result?.rows ?? [];
    return check(`apply ${index + 1}: COMPLETED / CHANGED, таблица перечитана, строки APPLIED`,
      outcome.envelope?.status === 'COMPLETED' && result?.status === 'CHANGED' && result.readBack === true
        && rows.length === expectedRows[index] && rows.every((row) => row.result === 'APPLIED'),
      JSON.stringify({ operation: outcome.envelope?.status, status: result?.status, readBack: result?.readBack, rows: rows.map((row) => row.result) }));
  });
}

const scopeText = (plan) => typeof plan.result.scopeText === 'string' && plan.result.scopeText.trim() !== ''
  && Array.isArray(plan.result.limitations) && plan.result.limitations.length > 0;

function advisories(conversation, plans, words) {
  return plans.flatMap(({ entry, result }, index) => {
    const reply = conversation.filter((message) => message.role === 'assistant')[entry.turn - 1]?.content ?? '';
    const projects = result.projects ?? {};
    return [
      previewTable(conversation, entry, { words: words(index), label: `Таблица последствий плана ${index + 1}`, allowEarlier: true }),
      advisoryCheck(`План ${index + 1}: ответ называет N и M проектов`, hasTable(reply) && wordsIn(reply, projects.linked) && wordsIn(reply, projects.changing),
        `N=${projects.linked}, M=${projects.changing}`),
    ];
  });
}

export async function verifyPrivate({ prepared, trace, conversation, tool, adminPage }) {
  const { project, types, schemes, itss } = prepared;
  const plans = plansOf(trace);
  const plan = plans[0];
  const p = plan?.result ?? {};
  const a = rowOf(p, types.A.id), b = rowOf(p, types.B.id);
  const projects = p.projects ?? {};
  const read = await tool('get_project_screens', { project });
  const table = await itssTable(adminPage, itss.id);
  const expectedTable = withoutRow(withRow(prepared.table, types.A.name, schemes.S2.id), types.B.name);
  const keys = await itssProjectKeys(adminPage, itss.id);
  return [
    ...protocolChecks(trace, 1),
    check('превью: WOULD_CHANGE для той же приватной ITSS проекта', p.status === 'WOULD_CHANGE' && String(p.issueTypeScreenScheme?.id) === itss.id
      && p.issueTypeScreenScheme?.isDefault === false, JSON.stringify(p.issueTypeScreenScheme ?? null)),
    check('превью: REPLACE типа A S1→S2 и REMOVE типа B S2→DEFAULT-схема, обе строки CHANGE, других строк нет',
      p.rows?.length === 2 && a?.action === 'REPLACE' && a.change === 'CHANGE' && scheme(a.before, schemes.S1.id, false) && scheme(a.after, schemes.S2.id, false)
        && b?.action === 'REMOVE' && b.change === 'CHANGE' && scheme(b.before, schemes.S2.id, false) && scheme(b.after, schemes.D.id, true),
      JSON.stringify(p.rows ?? null)),
    check('превью: N=1, M=1, список без обрезки с проектом сценария; масштаб и ограничения заданы',
      projects.linked === 1 && projects.changing === 1 && projects.truncated === false && projects.listed?.length === 1
        && projects.listed[0].key === project && projects.listed[0].archived === false && scopeText(plan ?? { result: p }),
      JSON.stringify({ projects, scopeText: Boolean(p.scopeText), limitations: p.limitations?.length })),
    ...outcomeChecks(trace, [2]),
    check('apply: перечитанная строка A = S2, у B строки нет; id ITSS в результате прежний', (() => {
      const result = outcomeOf(trace, applies(trace, TOOL)[0]).result;
      const observed = (type) => rowOf(result ?? {}, type.id)?.observed ?? null;
      return String(observed(types.A)?.id) === schemes.S2.id && observed(types.B) === null && String(result?.issueTypeScreenScheme?.id) === itss.id;
    })(), 'observed в строках результата'),
    check('Jira: у проекта прежняя ITSS (тот же id, только его), типы A→S2, B и C по DEFAULT-строке',
      String(read.scheme?.id) === itss.id && read.scheme.otherProjects === 0 && read.scheme.isDefault === false && same(keys, [project])
        && String(effective(read, types.A.name)?.id) === schemes.S2.id && String(effective(read, types.B.name)?.id) === schemes.D.id
        && String(effective(read, types.C.name)?.id) === schemes.D.id,
      JSON.stringify({ scheme: read.scheme?.id, keys, A: effective(read, types.A.name)?.name, B: effective(read, types.B.name)?.name, C: effective(read, types.C.name)?.name })),
    check('Jira: строки ITSS равны исходным с A→S2 и без строки B (страница админки, GET)', same(table, expectedTable), JSON.stringify({ table, expectedTable })),
    check('Jira: новых Issue Type Screen Scheme и Screen Scheme нет',
      same(await itssIds(adminPage), prepared.itssIds) && same(await screenSchemeIds(adminPage), prepared.screenSchemeIds), 'списки id равны снятым после подготовки'),
    ...advisories(conversation, plans, () => [types.A.name, types.B.name, schemes.S2.name]),
  ];
}

/** Contract evidence of the harness binding step: A then B, fresh keys, scheme id 1, each step listed the earlier projects plus its own. */
function bindingConfirmed({ binding, projects: keys, itss }) {
  const { defaultBefore, bound } = binding ?? {};
  if (!Array.isArray(defaultBefore) || !Array.isArray(bound) || bound.length !== 2 || String(itss?.id) !== DEFAULT_ITSS_ID) return false;
  let listed = [...defaultBefore].sort();
  return [keys.A, keys.B].every((key, index) => {
    const step = bound[index], expected = [...listed, key].sort();
    const ok = step?.key === key && !listed.includes(key) && /^\d+$/u.test(String(step.projectId)) && String(step.schemeId) === DEFAULT_ITSS_ID
      && same(step.before, listed) && same(step.after, expected);
    listed = expected;
    return ok;
  });
}

export async function verifySharedDefault({ prepared, trace, conversation, lifecycle = {}, tool, adminPage }) {
  const { projects: keys, type, schemes, itss } = prepared;
  const plans = plansOf(trace);
  const [first, second] = plans.map(({ result }) => result);
  const rows = [first, second].map((result) => rowOf(result ?? {}, type.id));
  const keysNow = await itssProjectKeys(adminPage, itss.id);
  const listedOk = (result) => {
    const projects = result?.projects ?? {};
    const listed = Object.fromEntries((projects.listed ?? []).map((item) => [item.key, item]));
    return projects.linked === keysNow.length && projects.changing === 2 && projects.truncated === (keysNow.length > 10)
      && (projects.truncated || (listed[keys.A]?.changedIssueTypes === 1 && listed[keys.B]?.changedIssueTypes === 1 && !listed[keys.C]));
  };
  const readA = await tool('get_project_screens', { project: keys.A });
  const readB = await tool('get_project_screens', { project: keys.B });
  const readC = await tool('get_project_screens', { project: keys.C });
  const table = await itssTable(adminPage, itss.id);
  const observed = lifecycle.observed ?? [];
  const afterAdd = observed[0], afterRemove = observed[1];
  const outcomes = applies(trace, TOOL).map((run) => outcomeOf(trace, run).result);
  return [
    ...protocolChecks(trace, 2),
    check('превью 1: WOULD_CHANGE для DEFAULT ITSS, ADD свежего типа DEFAULT-схема D→S, CHANGE, одна строка',
      first?.status === 'WOULD_CHANGE' && String(first.issueTypeScreenScheme?.id) === itss.id && first.issueTypeScreenScheme?.isDefault === true
        && first.rows?.length === 1 && rows[0]?.action === 'ADD' && rows[0].change === 'CHANGE'
        && scheme(rows[0].before, schemes.D.id, true) && scheme(rows[0].after, schemes.S.id, false), JSON.stringify(first?.rows ?? null)),
    check('превью 2: WOULD_CHANGE для той же ITSS, REMOVE этой строки S→D (по DEFAULT), CHANGE, одна строка',
      second?.status === 'WOULD_CHANGE' && String(second.issueTypeScreenScheme?.id) === itss.id && second.rows?.length === 1
        && rows[1]?.action === 'REMOVE' && rows[1].change === 'CHANGE'
        && scheme(rows[1].before, schemes.S.id, false) && scheme(rows[1].after, schemes.D.id, true), JSON.stringify(second?.rows ?? null)),
    check('превью: N равно числу проектов DEFAULT ITSS по странице админки (GET), M=2 (A и B), у A и B по одному изменённому типу, C не связан',
      listedOk(first) && listedOk(second), JSON.stringify({ admin: keysNow.length, first: first?.projects?.linked, second: second?.projects?.linked, M: [first?.projects?.changing, second?.projects?.changing] })),
    check('превью: масштаб и ограничения заданы в обоих планах', plans.length === 2 && plans.every(scopeText), 'scopeText и limitations непусты'),
    ...outcomeChecks(trace, [1, 1]),
    check('apply 1: перечитанная строка = S; apply 2: строки нет; id DEFAULT ITSS в результатах не изменился',
      String(rowOf(outcomes[0] ?? {}, type.id)?.observed?.id) === schemes.S.id && (rowOf(outcomes[1] ?? {}, type.id)?.observed ?? null) === null
        && outcomes.every((result) => String(result?.issueTypeScreenScheme?.id) === itss.id), JSON.stringify(outcomes.map((result) => result?.rows?.[0]?.observed ?? null))),
    check('Jira после ADD (наблюдение hook): у A и B эффективная схема типа = S, C прежняя, таблица DEFAULT ITSS = исходная + строка типа',
      afterAdd?.status === 'CHANGED' && String(afterAdd.A?.id) === schemes.S.id && String(afterAdd.B?.id) === schemes.S.id
        && String(afterAdd.C?.id) === schemes.S.id && same(afterAdd.table, withRow(prepared.table, type.name, schemes.S.id)), JSON.stringify(afterAdd ?? null)),
    check('Jira после REMOVE (наблюдение hook): у A и B прежняя DEFAULT-схема, таблица = исходной',
      afterRemove?.status === 'CHANGED' && String(afterRemove.A?.id) === schemes.D.id && String(afterRemove.B?.id) === schemes.D.id
        && same(afterRemove.table, prepared.table), JSON.stringify(afterRemove ?? null)),
    check('Jira в конце: id DEFAULT ITSS и связи проектов не изменились, таблица равна исходной, C с S на частной ITSS',
      [readA, readB].every((read) => String(read.scheme?.id) === itss.id && read.scheme.isDefault === true)
        && String(effective(readA, type.name)?.id) === schemes.D.id && String(effective(readB, type.name)?.id) === schemes.D.id
        && String(readC.scheme?.id) === prepared.privateItss && String(effective(readC, type.name)?.id) === schemes.S.id
        && keysNow.includes(keys.A) && keysNow.includes(keys.B) && !keysNow.includes(keys.C) && same(table, prepared.table),
      JSON.stringify({ table, keysNow: keysNow.length })),
    check('setup: A и B привязаны к DEFAULT ITSS (admin POST харнесса): проекты свежие L016A/L016B, схема id 1, список DEFAULT ITSS «до + A + B» перечитан после каждой привязки',
      bindingConfirmed(prepared), JSON.stringify(prepared.binding ?? null)),
    check('Jira: новых Issue Type Screen Scheme и Screen Scheme нет',
      same(await itssIds(adminPage), prepared.itssIds) && same(await screenSchemeIds(adminPage), prepared.screenSchemeIds), 'списки id равны снятым после подготовки'),
    ...advisories(conversation, plans, () => [type.name, schemes.S.name]),
  ];
}

/**
 * Defects of a plan preview of the shared-default scenario: the spec allows only the fresh type's row in the DEFAULT scheme,
 * so a preview of another scheme or with any other row (requested or planned) must never reach the user's confirmation.
 */
export function previewDefects(prepared, { entry, result }) {
  const defects = [];
  if (String(result.issueTypeScreenScheme?.id) !== String(prepared.itss.id)) {
    defects.push(`the preview targets Issue Type Screen Scheme ${result.issueTypeScreenScheme?.id ?? 'none'}, not the DEFAULT scheme ${prepared.itss.id}`);
  }
  const isFresh = (value) => value === prepared.type.name || String(value) === String(prepared.type.id);
  const requested = entry.arguments?.rows;
  if (!Array.isArray(requested) || requested.length !== 1 || !requested.every((row) => isFresh(row?.issueType))) {
    defects.push(`the requested rows are not exactly the fresh type ${prepared.type.name}: ${JSON.stringify(requested ?? null)}`);
  }
  const planned = result.rows;
  if (!Array.isArray(planned) || planned.length !== 1 || !planned.every((row) => String(row.issueType?.id) === String(prepared.type.id))) {
    defects.push(`the planned rows are not exactly the fresh type ${prepared.type.name}: ${JSON.stringify(Array.isArray(planned) ? planned.map((row) => row.issueType?.name ?? null) : null)}`);
  }
  return defects;
}

/**
 * Hook (read-only). First it stops the dialogue, before the simulated user's turn, when the latest plan preview of the tool
 * is outside the spec (another scheme or other rows than the fresh type); then, after each completed apply, it records the
 * real Jira state before the next user message.
 */
export async function observeAfterApply({ prepared, lifecycle, trace, tool, adminPage }) {
  const latest = plansOf(trace).at(-1);
  const defects = latest ? previewDefects(prepared, latest) : [];
  if (defects.length > 0) {
    throw new Error(`STOP: preview #${latest.entry.seq} of ${TOOL} is outside the spec (only the fresh type's row in the DEFAULT scheme is allowed): ${defects.join('; ')}`);
  }
  lifecycle.observed ??= [];
  const known = new Set(lifecycle.observed.map((item) => item.planId));
  for (const run of applies(trace, TOOL)) {
    if (known.has(run.planId)) continue;
    const outcome = outcomeOf(trace, run);
    if (outcome.envelope?.status !== 'COMPLETED') continue;
    const states = [];
    for (const project of [prepared.projects.A, prepared.projects.B, prepared.projects.C]) {
      states.push(effective(await tool('get_project_screens', { project }), prepared.type.name));
    }
    const [A, B, C] = states.map((state) => state && { id: String(state.id), name: state.name });
    lifecycle.observed.push({ planId: run.planId, turn: run.start.turn, status: outcome.result?.status, A, B, C,
      table: await itssTable(adminPage, prepared.itss.id) });
  }
}
