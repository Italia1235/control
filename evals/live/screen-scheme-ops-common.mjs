// Live scenarios of set_screen_scheme_operations (screen-scheme-ops-private, screen-scheme-ops-shared).
// Setup writes only fresh `L017-<runId>-` objects through Control with the runner's simulated-user confirmation (GET-only
// REST/admin pages): fresh projects, standalone screens, the private schemes set_project_operation_screens derives, and (shared
// scenario only) ONE set_issue_type_screen_scheme_mappings REPLACE of the DEFAULT row of the fresh project B's private Issue Type
// Screen Scheme. verify.mjs and hooks.mjs only read: Control readers, REST and GET admin pages, never XML/DB/UI writes.
// Contract = Jira state, tool trace and tool response fields; table/wording of the replies is advisory only.
import { PLUGIN_TOOLS, applies, hasTable, parseResult } from './checks.mjs';
import { advisoryCheck, check, createScreen, outcomeOf, previewTable, same, screenFields, screensNamed } from './configure-common.mjs';
import { itssIds, itssProjectKeys, itssTable, screenSchemeIds } from './itss-mappings-common.mjs';
import { projectManagement } from './project-templates.mjs';

export const TOOL = 'set_screen_scheme_operations';
export const REQUIRED_WARNING = 'REQUIRED_FIELDS_MISSING_ON_CREATE';
const READS = new Set([...PLUGIN_TOOLS].filter((name) => /^(get_|list_|plan_)/u.test(name)));

// --- setup confirmation (fixture boundaries for the simulated user who authorizes setup previews) ---------------------

const scopeRules = (runId, scenario, mappingsRule) => `You are the user authorizing setup for LOCAL-017 run ${runId} (scenario ${scenario}).
Distinguish creation of fresh fixture objects, references that leave existing objects unchanged, and changes to foreign configuration:
- Creation: only fresh L017-${runId}- objects are in scope: standalone screens, fixture projects (key L017 + one letter + ${runId.toUpperCase()}) and the Issue Type Screen Schemes / Screen Schemes that set_project_operation_screens derives for those projects.
- Project creation: create_project with action "create" and templateKey com.atlassian.jira-core-project-templates:jira-core-project-management creates a new BUSINESS project using Jira's standard Project Management template. The template may attach that new project to existing default/shared schemes or give it its own; those references are permitted, not a promise of cloned/private schemes. Selecting an existing active user as lead changes only the new project, not the user. No issues are requested.
- Screens: creating a fresh standalone screen with its own tab and fields, without assigning it to any project, scheme or workflow, is permitted; listing existing system fields such as summary or description on it does not change those fields.
- set_project_operation_screens: only for a fixture project of this run, only with allIssueTypes or the project's own issue types, and only with this run's fresh screens. If the project's Issue Type Screen Scheme / Screen Scheme is private it is updated in place; if it is shared or Jira's default, ONE new copy replaces the association of that project only and the source stays unchanged. Approve only when the preview changes nothing used by another project: never edit a scheme shared with foreign projects, Jira's default scheme or another project's association.
${mappingsRule}
- set_screen_scheme_operations is never a setup step; it is the subject of the dialogue. REJECT any setup preview of it.
- Forbidden: changing foreign objects/configuration, reusing or modifying fixtures left by another run, issues or issue migration, workflows, issue types, global catalog updates, user/account changes, deletion, cleanup, rollback and mutation bypass. A fresh name alone does not authorize a foreign/shared mutation.
`;

export const privateScope = {
  rules: (runId) => scopeRules(runId, 'screen-scheme-ops-private',
    '- set_issue_type_screen_scheme_mappings is never a setup step of this scenario. REJECT any setup preview that edits rows of an existing Issue Type Screen Scheme.'),
};
export const sharedScope = {
  rules: (runId) => scopeRules(runId, 'screen-scheme-ops-shared',
    `- set_issue_type_screen_scheme_mappings is a setup step ONLY ONCE and ONLY as: the Issue Type Screen Scheme is the private scheme of the fixture project L017B${runId.toUpperCase()} (the preview must show that no project other than L017B${runId.toUpperCase()} uses it and that it is not Jira's Default scheme), the only row is REPLACE of the DEFAULT row, and the new Screen Scheme is the private Screen Scheme of the fixture project L017A${runId.toUpperCase()} (the preview must show L017A${runId.toUpperCase()} as the only project that uses it). That makes two fresh projects share one fresh Screen Scheme; APPROVE only exactly that. REJECT any other setup preview that edits rows of an existing Issue Type Screen Scheme (another scheme, another row, ADD/REMOVE, a foreign Screen Scheme, Jira's Default schemes).`),
};

// --- reading Jira ------------------------------------------------------------------------------------------------

const ref = (value) => ({ id: String(value.id), name: value.name });
const byOperation = (a, b) => a.operation.localeCompare(b.operation);

/**
 * get_project_screens shows the operation of a row as Jira's i18n key (admin.common.words.default, admin.issue.operations.create|edit|view);
 * everywhere else in this helper (tables, plans, hooks) an operation is DEFAULT, CREATE, EDIT or VIEW. Already normalized names pass through.
 */
const OPERATION_KEYS = { 'admin.common.words.default': 'DEFAULT', 'admin.issue.operations.create': 'CREATE', 'admin.issue.operations.edit': 'EDIT', 'admin.issue.operations.view': 'VIEW' };
export const normalizeOperation = (operation) => {
  const text = String(operation ?? '').trim();
  return OPERATION_KEYS[text.toLowerCase()] ?? (text === '' ? 'DEFAULT' : text.toUpperCase());
};

/** The explicit rows of one Screen Scheme as get_project_screens shows them: [{ operation, screen }] sorted by operation (DEFAULT is the default row). */
export function schemeTable(read, schemeId) {
  const view = (read.issueTypes ?? []).map((row) => row.screenScheme).find((value) => value && String(value.id) === String(schemeId));
  if (!view) throw new Error(`Screen Scheme ${schemeId} is not effective for any issue type of the project`);
  return (view.screens ?? []).map((row) => ({ operation: normalizeOperation(row.operation), screen: String(row.id) })).sort(byOperation);
}

/** One row of a table as { id, name } of its screen, from the same read. */
function screenOf(read, schemeId, operation) {
  const view = (read.issueTypes ?? []).map((row) => row.screenScheme).find((value) => value && String(value.id) === String(schemeId));
  const row = view?.screens?.find((item) => normalizeOperation(item.operation) === operation);
  return row ? { id: String(row.id), name: row.name } : null;
}

/** The effective screen of one operation for every issue type of the project: [{ type, scheme, screen }] sorted by type. */
export function effectiveScreens(read, operation) {
  return (read.issueTypes ?? []).map((row) => {
    const screens = row.screenScheme?.screens ?? [];
    const found = screens.find((item) => normalizeOperation(item.operation) === operation) ?? screens.find((item) => normalizeOperation(item.operation) === 'DEFAULT');
    return { type: row.issueType?.name, scheme: row.screenScheme ? String(row.screenScheme.id) : null, screen: found ? String(found.id) : null };
  }).sort((a, b) => String(a.type).localeCompare(String(b.type)));
}

const withRow = (table, operation, screen) => [...table.filter((row) => row.operation !== operation), { operation, screen: String(screen) }].sort(byOperation);
const withoutRow = (table, operation) => table.filter((row) => row.operation !== operation);

// --- setup -------------------------------------------------------------------------------------------------------

async function freshNames({ runId, rest }, names) {
  const prefix = `L017-${runId}-`;
  for (const name of Object.values(names)) {
    if ((await screensNamed(rest, name)).length > 0) throw new Error(`Run prefix already exists: screen ${name}`);
  }
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

/** A fresh screen created by Control with exactly the wanted fields (re-read through get_screen_contents). */
async function freshScreen(apply, tool, name, fieldIds) {
  const screen = await createScreen(apply, name, fieldIds);
  const contents = await screenFields(tool, screen.id);
  if (!same([...contents.fieldIds].sort(), [...fieldIds].sort())) throw new Error(`Screen ${name} has fields [${contents.fieldIds.join(', ')}], expected [${fieldIds.join(', ')}]`);
  return screen;
}

async function snapshot(adminPage) {
  return { itssIds: await itssIds(adminPage), screenSchemeIds: await screenSchemeIds(adminPage) };
}

/** STOP_CONDITION_5 unless the project has its own Issue Type Screen Scheme and ONE Screen Scheme for all of its issue types. */
function onePrivateScheme(read, project, { otherProjectsOfScheme }) {
  const stop = (reason) => new Error(`STOP_CONDITION_5: ${reason}: ${JSON.stringify({ project, scheme: read.scheme, types: (read.issueTypes ?? []).map((row) => ({ type: row.issueType?.name, scheme: row.screenScheme?.id, other: row.screenScheme?.otherProjects })) })}`);
  if (!read.scheme || read.scheme.isDefault || read.scheme.otherProjects !== 0) throw stop(`project ${project} has no private Issue Type Screen Scheme`);
  const schemes = [...new Set((read.issueTypes ?? []).map((row) => row.screenScheme?.id))];
  if ((read.issueTypes ?? []).length === 0 || schemes.length !== 1 || schemes[0] == null) throw stop(`issue types of ${project} do not share exactly one Screen Scheme`);
  const view = read.issueTypes[0].screenScheme;
  if (otherProjectsOfScheme !== null && view.otherProjects !== otherProjectsOfScheme) throw stop(`Screen Scheme ${view.id} is used by ${view.otherProjects} other project(s), expected ${otherProjectsOfScheme}`);
  return view;
}

/** screen-scheme-ops-private: a fresh project with a private Issue Type Screen Scheme and a private Screen Scheme with explicit CREATE and EDIT rows. */
export async function setupPrivate({ runId, tool, apply, rest, adminPage, log }) {
  const names = { full: `L017-${runId}-screen-full`, noSummary: `L017-${runId}-screen-nosummary` };
  const prefix = await freshNames({ runId, rest }, names);
  const project = `L017P${runId.toUpperCase()}`;
  const lead = await projectLead(tool);
  await createProject({ tool, apply }, project, `${prefix}private-project`, lead);
  const full = await freshScreen(apply, tool, names.full, ['summary', 'description']);
  const noSummary = await freshScreen(apply, tool, names.noSummary, ['description']);
  await apply('set_project_operation_screens', { project, mappings: [{ allIssueTypes: true, screens: { CREATE: full.name, EDIT: full.name } }] });

  const read = await tool('get_project_screens', { project });
  const view = onePrivateScheme(read, project, { otherProjectsOfScheme: 0 });
  const table = schemeTable(read, view.id);
  const defaultScreen = screenOf(read, view.id, 'DEFAULT');
  const [create, edit] = [screenOf(read, view.id, 'CREATE'), screenOf(read, view.id, 'EDIT')];
  if (!defaultScreen || create?.id !== full.id || edit?.id !== full.id || [full.id, noSummary.id].includes(defaultScreen.id)) {
    throw new Error(`STOP_CONDITION_5: the private Screen Scheme has no explicit CREATE and EDIT rows of the fresh full screen next to a different DEFAULT row: ${JSON.stringify(table)}`);
  }
  const keys = await itssProjectKeys(adminPage, read.scheme.id);
  if (!same(keys, [project])) throw new Error(`STOP_CONDITION_5: the private Issue Type Screen Scheme lists other projects: ${keys.join(', ')}`);
  const configuration = await tool('get_field_configurations', { project, fields: ['summary'] });
  const states = configuration.fields?.[0]?.configurations ?? [];
  if (states.length === 0 || !states.every((state) => state.present && state.required && !state.hidden)) {
    throw new Error(`STOP_CONDITION_5: Summary is not required in the field configuration of ${project}: ${JSON.stringify(states)}`);
  }
  const base = await snapshot(adminPage);
  log(`Prepared ${project}: ITSS ${read.scheme.id}, Screen Scheme ${view.id} «${view.name}» (CREATE/EDIT → ${full.name}, DEFAULT → ${defaultScreen.name}); no issues, cleanup or rollback`);
  return {
    prefix, project, lead, itss: ref(read.scheme), scheme: ref(view),
    screens: { full: ref(full), noSummary: ref(noSummary), default: ref(defaultScreen) },
    types: (read.issueTypes ?? []).map((row) => row.issueType?.name), table, ...base,
    known: { project, newCreateScreen: noSummary.name, fullScreen: full.name, schemeName: view.name },
  };
}

/**
 * screen-scheme-ops-shared: two fresh projects A and B with private Issue Type Screen Schemes; the private Screen Scheme S of A (explicit CREATE
 * and EDIT rows) becomes the DEFAULT row of B's private Issue Type Screen Scheme through Control (set_issue_type_screen_scheme_mappings).
 * Every step stops the setup (STOP_CONDITION_5) when the sharing is not confirmed by re-reading.
 */
export async function setupShared({ runId, tool, apply, rest, adminPage, log }) {
  const names = { orig: `L017-${runId}-screen-orig`, next: `L017-${runId}-screen-edit-new` };
  const prefix = await freshNames({ runId, rest }, names);
  const suffix = runId.toUpperCase();
  const keys = { A: `L017A${suffix}`, B: `L017B${suffix}` };
  const lead = await projectLead(tool);
  for (const key of [keys.A, keys.B]) await createProject({ tool, apply }, key, `${prefix}project-${key === keys.A ? 'a' : 'b'}`, lead);
  const orig = await freshScreen(apply, tool, names.orig, ['summary', 'description']);
  const next = await freshScreen(apply, tool, names.next, ['summary', 'description']);
  await apply('set_project_operation_screens', { project: keys.A, mappings: [{ allIssueTypes: true, screens: { CREATE: orig.name, EDIT: orig.name } }] });

  const [readA0, readB0] = [await tool('get_project_screens', { project: keys.A }), await tool('get_project_screens', { project: keys.B })];
  const sView = onePrivateScheme(readA0, keys.A, { otherProjectsOfScheme: 0 });
  onePrivateScheme(readB0, keys.B, { otherProjectsOfScheme: null }); // B is never edited by Control before the sharing: its Screen Scheme may be a shared one, only its Issue Type Screen Scheme must be private
  const tableS = schemeTable(readA0, sView.id);
  const defaultScreen = screenOf(readA0, sView.id, 'DEFAULT');
  if (!defaultScreen || screenOf(readA0, sView.id, 'CREATE')?.id !== orig.id || screenOf(readA0, sView.id, 'EDIT')?.id !== orig.id || [orig.id, next.id].includes(defaultScreen.id)) {
    throw new Error(`STOP_CONDITION_5: the Screen Scheme of ${keys.A} has no explicit EDIT row of the fresh original screen next to a different DEFAULT row: ${JSON.stringify(tableS)}`);
  }
  const itssA = ref(readA0.scheme), itssB = ref(readB0.scheme);
  const [tableB0, keysA0, keysB0] = [await itssTable(adminPage, itssB.id), await itssProjectKeys(adminPage, itssA.id), await itssProjectKeys(adminPage, itssB.id)];
  if (!same(keysA0, [keys.A]) || !same(keysB0, [keys.B]) || tableB0.length !== 1 || tableB0[0].type !== 'DEFAULT') {
    throw new Error(`STOP_CONDITION_5: the Issue Type Screen Schemes of the fresh projects are not private DEFAULT-only schemes: ${JSON.stringify({ keysA0, keysB0, tableB0 })}`);
  }

  await apply('set_issue_type_screen_scheme_mappings', { issueTypeScreenScheme: itssB.id, rows: [{ action: 'REPLACE', issueType: 'DEFAULT', screenScheme: String(sView.id) }] });

  const [readA, readB] = [await tool('get_project_screens', { project: keys.A }), await tool('get_project_screens', { project: keys.B })];
  const shared = (read, key, itss) => {
    const view = onePrivateScheme(read, key, { otherProjectsOfScheme: 1 });
    if (String(read.scheme.id) !== itss.id || read.scheme.isDefault || read.scheme.otherProjects !== 0 || String(view.id) !== String(sView.id)) {
      throw new Error(`STOP_CONDITION_5: ${key} is not on its own private Issue Type Screen Scheme ${itss.id} with the shared Screen Scheme ${sView.id}: ${JSON.stringify({ scheme: read.scheme, view: ref(view) })}`);
    }
    if (!effectiveScreens(read, 'EDIT').every((row) => row.scheme === String(sView.id) && row.screen === orig.id)) {
      throw new Error(`STOP_CONDITION_5: the effective EDIT screens of ${key} are not the original screen of the shared scheme: ${JSON.stringify(effectiveScreens(read, 'EDIT'))}`);
    }
  };
  shared(readA, keys.A, itssA);
  shared(readB, keys.B, itssB);
  const itssTables = { A: await itssTable(adminPage, itssA.id), B: await itssTable(adminPage, itssB.id) };
  if (!same(itssTables.B, [{ type: 'DEFAULT', scheme: String(sView.id) }]) || !same(schemeTable(readB, sView.id), tableS)) {
    throw new Error(`STOP_CONDITION_5: the Issue Type Screen Scheme of ${keys.B} does not point only at the shared Screen Scheme ${sView.id}: ${JSON.stringify(itssTables.B)}`);
  }
  const base = await snapshot(adminPage);
  log(`Prepared ${keys.A} and ${keys.B}: Screen Scheme ${sView.id} «${sView.name}» shared through their private ITSS ${itssA.id} and ${itssB.id}; EDIT → ${orig.name}; no cleanup`);
  return {
    prefix, projects: keys, lead, itss: { A: itssA, B: itssB }, scheme: ref(sView),
    screens: { orig: ref(orig), next: ref(next), default: ref(defaultScreen) },
    table: tableS, itssTables, ...base,
    known: { projectA: keys.A, projectB: keys.B, newEditScreen: next.name, originalEditScreen: orig.name, schemeName: sView.name },
  };
}

// --- verification ------------------------------------------------------------------------------------------------

const plansOf = (trace) => trace.filter((entry) => entry.tool === TOOL && entry.arguments?.dryRun === true)
  .map((entry) => ({ entry, result: parseResult(entry.result) ?? {} })).filter(({ result }) => result.planId);
const rowOf = (result, operation) => result.rows?.find((row) => row.operation === operation);
/** An effective screen read from a response: id, and whether it is reached through the DEFAULT row. */
const shows = (value, id, viaDefault) => Boolean(value) && String(value.id) === String(id) && Boolean(value.viaDefault) === viaDefault;
const wordsIn = (reply, number) => new RegExp(`(?<![\\d])${number}(?![\\d])`, 'u').test(reply);

/** Contract: exactly `count` plans of the tool, one start_operation each by its own plan (the last preview before the «да»), and no other write in the dialogue. */
function protocolChecks(trace, count) {
  const plans = plansOf(trace);
  const starts = trace.filter((entry) => entry.tool === 'start_operation');
  const runs = applies(trace, TOOL);
  const foreign = trace.filter((entry) => !READS.has(entry.tool) && entry.tool !== TOOL && entry.tool !== 'start_operation');
  return [
    check(`${count} план(а) ${TOOL} с planId, по одному start_operation на каждый по плану из последнего превью до «да»`,
      plans.length === count && starts.length === count && runs.length === count
        && runs.every((run, index) => run.preview === plans[index].entry && run.planId === plans[index].result.planId && run.preview.seq < run.start.seq),
      `планов ${plans.length}, start_operation ${starts.length}, по плану ${runs.length}`),
    check(`других записей нет: только чтение, ${TOOL} и start_operation`,
      foreign.length === 0 && starts.every((start) => start.arguments?.toolName === TOOL),
      foreign.map((entry) => `#${entry.seq} ${entry.tool}`).join(', ') || 'нет'),
  ];
}

/** Contract: each apply is COMPLETED/CHANGED with a re-read scheme of the same id; every row APPLIED. */
function outcomeChecks(trace, expectedRows, schemeId) {
  return applies(trace, TOOL).map((run, index) => {
    const outcome = outcomeOf(trace, run);
    const result = outcome.result;
    const rows = result?.rows ?? [];
    return check(`apply ${index + 1}: COMPLETED / CHANGED, схема перечитана (тот же id), строки APPLIED`,
      outcome.envelope?.status === 'COMPLETED' && result?.status === 'CHANGED' && result.readBack === true
        && String(result.screenScheme?.id) === String(schemeId) && rows.length === expectedRows[index] && rows.every((row) => row.result === 'APPLIED'),
      JSON.stringify({ operation: outcome.envelope?.status, status: result?.status, readBack: result?.readBack, scheme: result?.screenScheme?.id, rows: rows.map((row) => row.result) }));
  });
}

const scopeText = (plan) => typeof plan.result.scopeText === 'string' && plan.result.scopeText.trim() !== ''
  && Array.isArray(plan.result.limitations) && plan.result.limitations.length > 0;

/** The row read back at an apply equals what the preview promised: an explicit `after` is observed, an `after` through DEFAULT means no row. */
function observedMatchesPlan(planned, applied) {
  if (!planned || !applied) return false;
  return planned.after?.viaDefault ? (applied.observed ?? null) === null : String(applied.observed?.id) === String(planned.after?.id);
}

const replyOfTurn = (conversation, entry) => conversation.filter((message) => message.role === 'assistant')[entry.turn - 1]?.content ?? '';

function advisories(conversation, plans, words, extra = () => []) {
  return plans.flatMap(({ entry, result }, index) => {
    const reply = replyOfTurn(conversation, entry);
    const projects = result.projects ?? {};
    return [
      previewTable(conversation, entry, { words: words(index), label: `Таблица последствий плана ${index + 1}`, allowEarlier: true }),
      advisoryCheck(`План ${index + 1}: ответ называет N и M проектов`, hasTable(reply) && wordsIn(reply, projects.linked) && wordsIn(reply, projects.changing),
        `N=${projects.linked}, M=${projects.changing}`),
      ...extra({ entry, result, reply }),
    ];
  });
}

/** The Jira state read in the verifier: tolerates an unreadable scheme (the check fails, the verifier does not throw). */
function tableOrNull(read, schemeId) {
  try { return schemeTable(read, schemeId); } catch { return null; }
}

export async function verifyPrivate({ prepared, trace, conversation, tool, adminPage }) {
  const { project, scheme, itss, screens } = prepared;
  const plans = plansOf(trace);
  const plan = plans[0];
  const p = plan?.result ?? {};
  const create = rowOf(p, 'CREATE'), edit = rowOf(p, 'EDIT');
  const warning = (p.warnings ?? []).find((item) => item.code === REQUIRED_WARNING);
  const missing = warning?.requiredFields?.fields ?? [];
  const run = applies(trace, TOOL)[0];
  const result = outcomeOf(trace, run).result ?? {};
  const read = await tool('get_project_screens', { project });
  const table = tableOrNull(read, scheme.id);
  const expectedTable = withoutRow(withRow(prepared.table, 'CREATE', screens.noSummary.id), 'EDIT');
  const keys = await itssProjectKeys(adminPage, itss.id);
  const schemesNow = [...new Set((read.issueTypes ?? []).map((row) => String(row.screenScheme?.id)))];
  return [
    ...protocolChecks(trace, 1),
    check('превью: WOULD_CHANGE для той же приватной Screen Scheme проекта (тот же id), две строки',
      p.status === 'WOULD_CHANGE' && String(p.screenScheme?.id) === scheme.id && p.rows?.length === 2, JSON.stringify({ status: p.status, scheme: p.screenScheme ?? null, rows: p.rows?.length })),
    check('превью: REPLACE CREATE полный→«без Summary» и REMOVE EDIT полный→DEFAULT-экран, обе строки CHANGE, других строк нет',
      p.rows?.length === 2 && create?.action === 'REPLACE' && create.change === 'CHANGE' && shows(create.before, screens.full.id, false) && shows(create.after, screens.noSummary.id, false)
        && edit?.action === 'REMOVE' && edit.change === 'CHANGE' && shows(edit.before, screens.full.id, false) && shows(edit.after, screens.default.id, true),
      JSON.stringify(p.rows ?? null)),
    check(`превью: предупреждение ${REQUIRED_WARNING}, среди отсутствующих полей Summary, проект сценария назван`,
      Boolean(warning) && warning.requiredFields?.pairs >= 1 && missing.some((field) => field.id === 'summary')
        && (warning.requiredFields?.projects ?? []).some((item) => item.key === project) && scopeText(plan ?? { result: p }),
      JSON.stringify({ warnings: (p.warnings ?? []).map((item) => item.code), fields: missing, scopeText: Boolean(p.scopeText), limitations: p.limitations?.length })),
    ...outcomeChecks(trace, [2], scheme.id),
    check('apply: перечитанные строки равны превью (CREATE = «без Summary», строки EDIT нет)',
      observedMatchesPlan(create, rowOf(result, 'CREATE')) && observedMatchesPlan(edit, rowOf(result, 'EDIT')), JSON.stringify((result.rows ?? []).map((row) => ({ op: row.operation, observed: row.observed ?? null })))),
    check('Jira: у проекта прежняя ITSS (тот же id, только он), одна и та же Screen Scheme (тот же id) для всех типов, без других проектов',
      String(read.scheme?.id) === itss.id && read.scheme.otherProjects === 0 && read.scheme.isDefault === false && same(keys, [project])
        && same(schemesNow, [scheme.id]) && read.issueTypes.every((row) => row.screenScheme.otherProjects === 0),
      JSON.stringify({ itss: read.scheme?.id, keys, schemes: schemesNow })),
    check('Jira: строки Screen Scheme равны исходным с CREATE→«без Summary» и без строки EDIT (get_project_screens)', same(table, expectedTable), JSON.stringify({ table, expectedTable })),
    check('Jira: новых Issue Type Screen Scheme и Screen Scheme нет',
      same(await itssIds(adminPage), prepared.itssIds) && same(await screenSchemeIds(adminPage), prepared.screenSchemeIds), 'списки id равны снятым после подготовки'),
    ...advisories(conversation, plans, () => [screens.noSummary.name], ({ result: planned, reply }) => [
      advisoryCheck('План 1: ответ называет предупреждение об обязательных полях CREATE (имя или id поля из ответа tool)',
        (planned.warnings ?? []).filter((item) => item.code === REQUIRED_WARNING).flatMap((item) => item.requiredFields?.fields ?? [])
          .some((field) => reply.toLowerCase().includes(String(field.name).toLowerCase()) || reply.toLowerCase().includes(String(field.id).toLowerCase())),
        'имя/id отсутствующего поля'),
    ]),
  ];
}

const effectiveOf = (read, operation) => effectiveScreens(read, operation);
const allOn = (rows, schemeId, screenId) => rows.length > 0 && rows.every((row) => row.scheme === String(schemeId) && row.screen === String(screenId));

export async function verifyShared({ prepared, trace, conversation, lifecycle = {}, tool, adminPage }) {
  const { projects: keys, scheme, screens, itss } = prepared;
  const plans = plansOf(trace);
  const [first, second] = plans.map(({ result }) => result);
  const [readA, readB] = [await tool('get_project_screens', { project: keys.A }), await tool('get_project_screens', { project: keys.B })];
  const tables = { A: tableOrNull(readA, scheme.id), B: tableOrNull(readB, scheme.id) };
  const itssTablesNow = { A: await itssTable(adminPage, itss.A.id), B: await itssTable(adminPage, itss.B.id) };
  const itssKeys = { A: await itssProjectKeys(adminPage, itss.A.id), B: await itssProjectKeys(adminPage, itss.B.id) };
  const observed = lifecycle.observed ?? [];
  const [afterEdit, afterReturn] = observed;
  const outcomes = applies(trace, TOOL).map((run) => outcomeOf(trace, run).result ?? {});
  const planned = [first, second].map((result) => rowOf(result ?? {}, 'EDIT'));
  const scale = (result, label) => {
    const projects = result?.projects ?? {};
    const listed = (projects.listed ?? []).map((item) => item.key).sort();
    return check(`превью ${label}: N=2 (проекты A и B), M=2, список без обрезки, ITSS две; масштаб и ограничения заданы`,
      projects.linked === 2 && projects.changing === 2 && projects.truncated === false && same(listed, [keys.A, keys.B].sort())
        && result?.usage?.linked === 2 && result.usage.truncated === false && typeof result.scopeText === 'string' && result.scopeText.trim() !== ''
        && Array.isArray(result.limitations) && result.limitations.length > 0,
      JSON.stringify({ projects: { linked: projects.linked, changing: projects.changing, truncated: projects.truncated, listed }, itss: result?.usage?.linked }));
  };
  const state = (record, screenId, expectedTable) => record?.status === 'CHANGED' && allOn(record.A ?? [], scheme.id, screenId) && allOn(record.B ?? [], scheme.id, screenId)
    && same(record.table, expectedTable);
  return [
    ...protocolChecks(trace, 2),
    check('превью 1: WOULD_CHANGE для общей Screen Scheme (тот же id), REPLACE EDIT исходный→новый экран E, CHANGE, одна строка',
      first?.status === 'WOULD_CHANGE' && String(first.screenScheme?.id) === scheme.id && first.rows?.length === 1
        && planned[0]?.action === 'REPLACE' && planned[0].change === 'CHANGE' && shows(planned[0].before, screens.orig.id, false) && shows(planned[0].after, screens.next.id, false),
      JSON.stringify(first?.rows ?? null)),
    check('превью 2: WOULD_CHANGE для той же схемы, REPLACE EDIT E→исходный экран, CHANGE, одна строка',
      second?.status === 'WOULD_CHANGE' && String(second.screenScheme?.id) === scheme.id && second.rows?.length === 1
        && planned[1]?.action === 'REPLACE' && planned[1].change === 'CHANGE' && shows(planned[1].before, screens.next.id, false) && shows(planned[1].after, screens.orig.id, false),
      JSON.stringify(second?.rows ?? null)),
    scale(first, '1'), scale(second, '2'),
    ...outcomeChecks(trace, [1, 1], scheme.id),
    check('apply: перечитанная строка EDIT = E (apply 1) и = исходному экрану (apply 2)',
      String(rowOf(outcomes[0] ?? {}, 'EDIT')?.observed?.id) === screens.next.id && String(rowOf(outcomes[1] ?? {}, 'EDIT')?.observed?.id) === screens.orig.id,
      JSON.stringify(outcomes.map((result) => rowOf(result, 'EDIT')?.observed ?? null))),
    check('Jira после apply 1 (наблюдение hook): get_project_screens A и B показывает E для EDIT всех типов, строки схемы = исходные с EDIT→E',
      state(afterEdit, screens.next.id, withRow(prepared.table, 'EDIT', screens.next.id)), JSON.stringify(afterEdit ?? null)),
    check('Jira после возврата (наблюдение hook): A и B показывает исходный экран для EDIT, строки схемы = исходной таблице',
      state(afterReturn, screens.orig.id, prepared.table), JSON.stringify(afterReturn ?? null)),
    check('Jira в конце: id Screen Scheme прежний, EDIT всех типов A и B = исходный экран, таблица схемы (все операции) равна исходной',
      allOn(effectiveOf(readA, 'EDIT'), scheme.id, screens.orig.id) && allOn(effectiveOf(readB, 'EDIT'), scheme.id, screens.orig.id)
        && same(tables.A, prepared.table) && same(tables.B, prepared.table),
      JSON.stringify({ A: tables.A, B: tables.B })),
    check('Jira: ITSS проектов прежние (те же id, свои для каждого проекта), их строки и связи не менялись',
      String(readA.scheme?.id) === itss.A.id && String(readB.scheme?.id) === itss.B.id && same(itssTablesNow, prepared.itssTables)
        && same(itssKeys.A, [keys.A]) && same(itssKeys.B, [keys.B]),
      JSON.stringify({ A: readA.scheme?.id, B: readB.scheme?.id, tables: itssTablesNow, keys: itssKeys })),
    check('Jira: новых Issue Type Screen Scheme и Screen Scheme нет',
      same(await itssIds(adminPage), prepared.itssIds) && same(await screenSchemeIds(adminPage), prepared.screenSchemeIds), 'списки id равны снятым после подготовки'),
    ...advisories(conversation, plans, (index) => [index === 0 ? screens.next.name : screens.orig.name]),
  ];
}

// --- hooks (read-only) -------------------------------------------------------------------------------------------

/** Defects of a plan preview of the private scenario: only this scheme, only CREATE → «без Summary» and REMOVE EDIT. */
export function previewDefectsPrivate(prepared, { result }) {
  const defects = [];
  if (String(result.screenScheme?.id) !== String(prepared.scheme.id)) {
    defects.push(`the preview targets Screen Scheme ${result.screenScheme?.id ?? 'none'}, not the fresh private scheme ${prepared.scheme.id}`);
  }
  const allowed = (row) => (row.operation === 'CREATE' && row.action === 'REPLACE' && String(row.after?.id) === prepared.screens.noSummary.id && row.after.viaDefault === false)
    || (row.operation === 'EDIT' && row.action === 'REMOVE');
  if (!Array.isArray(result.rows) || result.rows.length === 0 || !result.rows.every(allowed)) {
    defects.push(`the planned rows are not only REPLACE CREATE → ${prepared.screens.noSummary.name} and REMOVE EDIT: ${JSON.stringify((result.rows ?? []).map((row) => [row.operation, row.action, row.after?.id ?? null]))}`);
  }
  return defects;
}

/** Defects of a plan preview of the shared scenario: only this scheme, one REPLACE EDIT row to E before the first apply started, to the original screen after it. */
export function previewDefectsShared(prepared, { result }, appliedCount) {
  const defects = [];
  if (String(result.screenScheme?.id) !== String(prepared.scheme.id)) {
    defects.push(`the preview targets Screen Scheme ${result.screenScheme?.id ?? 'none'}, not the fresh shared scheme ${prepared.scheme.id}`);
  }
  const target = appliedCount === 0 ? prepared.screens.next : prepared.screens.orig;
  const rows = result.rows;
  if (!Array.isArray(rows) || rows.length !== 1 || rows[0].operation !== 'EDIT' || rows[0].action !== 'REPLACE' || String(rows[0].after?.id) !== target.id || rows[0].after.viaDefault !== false) {
    defects.push(`the planned rows are not exactly REPLACE EDIT → ${target.name}: ${JSON.stringify(Array.isArray(rows) ? rows.map((row) => [row.operation, row.action, row.after?.id ?? null]) : null)}`);
  }
  return defects;
}

function stopOnDefect(trace, defectsOf) {
  const latest = plansOf(trace).at(-1);
  const defects = latest ? defectsOf(latest) : [];
  if (defects.length > 0) {
    throw new Error(`STOP: preview #${latest.entry.seq} of ${TOOL} is outside the spec: ${defects.join('; ')}`);
  }
}

/** Hook (read-only) of the private scenario: stops the dialogue, before the simulated user's turn, on a preview outside the spec. */
export async function observePrivate({ prepared, trace }) {
  stopOnDefect(trace, (plan) => previewDefectsPrivate(prepared, plan));
}

/**
 * Hook (read-only) of the shared scenario: first it stops the dialogue on a preview outside the spec; then, after each completed apply,
 * it records the real Jira state of both projects before the next user message.
 */
export async function observeShared({ prepared, lifecycle, trace, tool }) {
  lifecycle.observed ??= [];
  stopOnDefect(trace, (plan) => previewDefectsShared(prepared, plan, applies(trace, TOOL).filter((run) => run.start.seq < plan.entry.seq).length));
  const known = new Set(lifecycle.observed.map((item) => item.planId));
  for (const run of applies(trace, TOOL)) {
    if (known.has(run.planId)) continue;
    const outcome = outcomeOf(trace, run);
    if (outcome.envelope?.status !== 'COMPLETED') continue;
    const [readA, readB] = [await tool('get_project_screens', { project: prepared.projects.A }), await tool('get_project_screens', { project: prepared.projects.B })];
    lifecycle.observed.push({ planId: run.planId, turn: run.start.turn, status: outcome.result?.status,
      A: effectiveOf(readA, 'EDIT'), B: effectiveOf(readB, 'EDIT'), table: tableOrNull(readA, prepared.scheme.id) });
  }
}
