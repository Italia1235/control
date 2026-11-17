// Live scenario of assign_issue_type_screen_scheme (itss-assign-shared).
// Setup writes only fresh `L018-<runId>-` objects through Control with the runner's simulated-user confirmation (GET-only
// REST/admin pages): two fresh projects A and B, two standalone screens and the private Issue Type Screen Schemes / Screen
// Schemes that set_project_operation_screens derives. The dialogue switches ONLY the association of B to the Issue Type Screen
// Scheme of A and back. verify.mjs and hooks.mjs only read: Control readers, REST and GET admin pages, never XML/DB/UI writes.
// Contract = Jira state, tool trace and tool response fields; table/wording of the replies is advisory only.
import { PLUGIN_TOOLS, applies, hasTable, parseResult } from './checks.mjs';
import { advisoryCheck, check, createScreen, outcomeOf, previewTable, same, screenFields, screensNamed } from './configure-common.mjs';
import { itssIds, itssProjectKeys, itssTable, screenSchemeIds } from './itss-mappings-common.mjs';
import { projectManagement } from './project-templates.mjs';
import { normalizeOperation } from './screen-scheme-ops-common.mjs';

export const TOOL = 'assign_issue_type_screen_scheme';
export const ORPHANED = 'ITSS_ORPHANED';
export const SHARED = 'SHARED_TARGET';
const OPERATIONS = ['CREATE', 'EDIT', 'VIEW'];
const READS = new Set([...PLUGIN_TOOLS].filter((name) => /^(get_|list_|plan_)/u.test(name)));

/** Fixture boundaries for the simulated user who authorizes setup previews. */
export const confirmationScope = {
  rules: (runId) => `You are the user authorizing setup for LOCAL-018 run ${runId} (scenario itss-assign-shared).
Distinguish creation of fresh fixture objects, references that leave existing objects unchanged, and changes to foreign configuration:
- Creation: only fresh L018-${runId}- objects are in scope: standalone screens, fixture projects (key L018 + one letter + ${runId.toUpperCase()}) and the Issue Type Screen Schemes / Screen Schemes that set_project_operation_screens derives for those projects.
- Project creation: create_project with action "create" and templateKey com.atlassian.jira-core-project-templates:jira-core-project-management creates a new BUSINESS project using Jira's standard Project Management template. The template may attach that new project to existing default/shared schemes or give it its own; those references are permitted, not a promise of cloned/private schemes. Selecting an existing active user as lead changes only the new project, not the user. No issues are requested.
- Screens: creating a fresh standalone screen with its own tab and fields, without assigning it to any project, scheme or workflow, is permitted; listing existing system fields such as summary or description on it does not change those fields.
- set_project_operation_screens: only for a fixture project of this run, only with allIssueTypes and only with this run's fresh screens. If the project's Issue Type Screen Scheme / Screen Scheme is private it is updated in place; if it is shared or Jira's default, ONE new copy replaces the association of that project only and the source stays unchanged. Approve only when the preview changes nothing used by another project: never edit a scheme shared with foreign projects, Jira's default scheme or another project's association.
- assign_issue_type_screen_scheme and set_issue_type_screen_scheme_mappings are never setup steps; the first is the subject of the dialogue. REJECT any setup preview of either.
- Forbidden: changing foreign objects/configuration, reusing or modifying fixtures left by another run, issues or issue migration, workflows, issue types, global catalog updates, user/account changes, deletion, cleanup, rollback and mutation bypass. A fresh name alone does not authorize a foreign/shared mutation.
`,
};

// --- reading Jira ------------------------------------------------------------------------------------------------

const ref = (value) => ({ id: String(value.id), name: value.name });
const byType = (a, b) => Number(a.type) - Number(b.type);

/**
 * A project's effective screen setup from get_project_screens: its Issue Type Screen Scheme id and, per issue type id,
 * the Screen Scheme id and the effective CREATE/EDIT/VIEW screen ids (the DEFAULT row when an operation has no row).
 */
export function projectState(read) {
  return {
    itss: String(read.scheme?.id ?? ''),
    types: (read.issueTypes ?? []).map((row) => {
      const screens = row.screenScheme?.screens ?? [];
      const screenOf = (operation) => {
        const found = screens.find((item) => normalizeOperation(item.operation) === operation) ?? screens.find((item) => normalizeOperation(item.operation) === 'DEFAULT');
        return found ? String(found.id) : null;
      };
      return { type: String(row.issueType?.id), scheme: row.screenScheme ? String(row.screenScheme.id) : null,
        ...Object.fromEntries(OPERATIONS.map((operation) => [operation, screenOf(operation)])) };
    }).sort(byType),
  };
}

const stateOrNull = (read) => { try { return projectState(read); } catch { return null; } };

// --- setup -------------------------------------------------------------------------------------------------------

async function freshNames({ runId, rest }, names) {
  const prefix = `L018-${runId}-`;
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

const stop = (reason, detail) => new Error(`STOP_CONDITION_5: ${reason}: ${JSON.stringify(detail)}`);

/** STOP_CONDITION_5 unless the project has its own (not Default, unshared) Issue Type Screen Scheme and every issue type goes through the wanted screen on every operation. */
function privateSetup(read, project, screen) {
  const state = stateOrNull(read);
  if (!read.scheme || read.scheme.isDefault || read.scheme.otherProjects !== 0) throw stop(`project ${project} has no private Issue Type Screen Scheme`, read.scheme ?? null);
  if (!state || state.types.length === 0 || state.types.some((row) => row.scheme === null || OPERATIONS.some((operation) => row[operation] !== screen.id))) {
    throw stop(`not every issue type of ${project} has the fresh screen ${screen.name} for CREATE, EDIT and VIEW`, state);
  }
  return state;
}

/**
 * itss-assign-shared: two fresh projects A and B, each with its own private Issue Type Screen Scheme whose effective CREATE/EDIT/VIEW
 * screens are the project's own fresh screen (so the two schemes show different tables). Every step stops the setup (STOP_CONDITION_5)
 * when the expected state is not confirmed by re-reading.
 */
export async function setupAssign({ runId, tool, apply, rest, adminPage, log }) {
  const names = { A: `L018-${runId}-screen-a`, B: `L018-${runId}-screen-b` };
  const prefix = await freshNames({ runId, rest }, names);
  const suffix = runId.toUpperCase();
  const keys = { A: `L018A${suffix}`, B: `L018B${suffix}` };
  const lead = await projectLead(tool);
  for (const side of ['A', 'B']) await createProject({ tool, apply }, keys[side], `${prefix}project-${side.toLowerCase()}`, lead);
  const screens = { A: await freshScreen(apply, tool, names.A, ['summary', 'description']), B: await freshScreen(apply, tool, names.B, ['summary', 'description']) };
  for (const side of ['A', 'B']) {
    const operations = Object.fromEntries(OPERATIONS.map((operation) => [operation, screens[side].name]));
    await apply('set_project_operation_screens', { project: keys[side], mappings: [{ allIssueTypes: true, screens: operations }] });
  }

  const reads = { A: await tool('get_project_screens', { project: keys.A }), B: await tool('get_project_screens', { project: keys.B }) };
  const states = { A: privateSetup(reads.A, keys.A, screens.A), B: privateSetup(reads.B, keys.B, screens.B) };
  if (states.A.itss === states.B.itss || reads.A.scheme.name === reads.B.scheme.name
      || !same(states.A.types.map((row) => row.type), states.B.types.map((row) => row.type))
      || states.A.types.some((row, index) => row.scheme === states.B.types[index].scheme)) {
    throw stop('the fresh projects must have different Issue Type Screen Schemes (different names) and Screen Schemes over the same issue types', { A: states.A, B: states.B });
  }
  const itss = { A: ref(reads.A.scheme), B: ref(reads.B.scheme) };
  const projectKeys = { A: await itssProjectKeys(adminPage, itss.A.id), B: await itssProjectKeys(adminPage, itss.B.id) };
  if (!same(projectKeys.A, [keys.A]) || !same(projectKeys.B, [keys.B])) {
    throw stop('the admin page does not list exactly the own project for each fresh Issue Type Screen Scheme', projectKeys);
  }
  const itssTables = { A: await itssTable(adminPage, itss.A.id), B: await itssTable(adminPage, itss.B.id) };
  log(`Prepared ${keys.A} (ITSS ${itss.A.id} «${itss.A.name}») and ${keys.B} (ITSS ${itss.B.id} «${itss.B.name}»); only the association of ${keys.B} changes; no issues, cleanup or rollback`);
  return {
    prefix, projects: keys, lead, itss, screens: { A: ref(screens.A), B: ref(screens.B) }, states, itssTables,
    itssIds: await itssIds(adminPage), screenSchemeIds: await screenSchemeIds(adminPage),
    known: { projectA: keys.A, projectB: keys.B, schemeOfProjectA: itss.A.name, previousSchemeOfProjectB: itss.B.name },
  };
}

// --- verification ------------------------------------------------------------------------------------------------

const plansOf = (trace) => trace.filter((entry) => entry.tool === TOOL && entry.arguments?.dryRun === true)
  .map((entry) => ({ entry, result: parseResult(entry.result) ?? {} })).filter(({ result }) => result.planId);

/** Contract: two starts of the tool, each by the planId of the LAST preview of the tool before it; no other write in the dialogue. */
function protocolChecks(trace) {
  const plans = plansOf(trace);
  const starts = trace.filter((entry) => entry.tool === 'start_operation');
  const runs = applies(trace, TOOL);
  const lastBefore = (start) => plans.filter((plan) => plan.entry.seq < start.seq).at(-1);
  const foreign = trace.filter((entry) => !READS.has(entry.tool) && entry.tool !== TOOL && entry.tool !== 'start_operation');
  return [
    check(`ровно два start_operation ${TOOL}, каждый по planId последнего превью до него; планы различны`,
      starts.length === 2 && runs.length === 2 && runs.every((run) => run.preview && run.planId === lastBefore(run.start)?.result.planId && run.preview.seq < run.start.seq)
        && runs[0].planId !== runs[1].planId,
      `превью с планом ${plans.length}, start_operation ${starts.length}, по плану ${runs.length}`),
    check(`других записей нет: только чтение, ${TOOL} и start_operation`,
      foreign.length === 0 && starts.every((start) => start.arguments?.toolName === TOOL),
      foreign.map((entry) => `#${entry.seq} ${entry.tool}`).join(', ') || 'нет'),
  ];
}

const codes = (plan) => (plan.warnings ?? []).map((warning) => warning.code);
const warningOf = (plan, code) => (plan.warnings ?? []).find((warning) => warning.code === code);
const sameScheme = (value, expected) => Boolean(value) && String(value.id) === expected.id && value.name === expected.name && value.isDefault === false;

/**
 * What the preview of switching a project from state `from` (scheme `fromItss`) to state `to` (scheme `toItss`) must show: both schemes
 * (id, name), one entry per issue type of the project with the Screen Scheme and the CREATE/EDIT/VIEW screens before → after and CHANGE
 * (here always: the schemes differ). Returns the list of defects (empty = exact).
 */
export function planDefects(plan, { project, from, to, fromItss, toItss }) {
  const defects = [];
  if (plan.status !== 'WOULD_CHANGE' || plan.dryRun !== true || !plan.planId) defects.push(`status ${plan.status}, dryRun ${plan.dryRun}, planId ${Boolean(plan.planId)}`);
  if (plan.project?.key !== project) defects.push(`project ${plan.project?.key}`);
  if (!sameScheme(plan.currentIssueTypeScreenScheme, fromItss)) defects.push(`current ${JSON.stringify(plan.currentIssueTypeScreenScheme ?? null)}`);
  if (!sameScheme(plan.newIssueTypeScreenScheme, toItss)) defects.push(`new ${JSON.stringify(plan.newIssueTypeScreenScheme ?? null)}`);
  const rows = plan.issueTypes ?? [];
  if (rows.length !== from.types.length) defects.push(`issue types ${rows.length}, expected ${from.types.length}`);
  for (const before of from.types) {
    const after = to.types.find((row) => row.type === before.type);
    const row = rows.find((item) => String(item.issueType?.id) === before.type);
    if (!row || !after) { defects.push(`type ${before.type} missing in the plan or in the target`); continue; }
    const operations = (name) => row.screens?.find((item) => item.operation === name);
    const screensOk = OPERATIONS.every((name) => String(operations(name)?.before?.id) === String(before[name]) && String(operations(name)?.after?.id) === String(after[name]));
    const expectedChange = before.scheme !== after.scheme || OPERATIONS.some((name) => before[name] !== after[name]) ? 'CHANGE' : 'NO_CHANGE';
    if (row.change !== expectedChange || String(row.screenSchemeBefore?.id) !== before.scheme || String(row.screenSchemeAfter?.id) !== after.scheme || !screensOk) {
      defects.push(`type ${before.type}: ${JSON.stringify({ change: row.change, before: row.screenSchemeBefore?.id, after: row.screenSchemeAfter?.id, screens: row.screens?.map((item) => [item.operation, item.before?.id, item.after?.id]) })}`);
    }
  }
  return defects;
}

/**
 * The warnings the preview must carry by the fixture facts: the first plan leaves B's private scheme without projects (ITSS_ORPHANED)
 * and A's scheme has one other project, A (SHARED_TARGET); the return leaves A's scheme in use by A (no ITSS_ORPHANED) and moves B to
 * its own scheme that nobody else uses (no SHARED_TARGET).
 */
export function warningDefects(plan, { orphaned, sharedWith }) {
  const defects = [];
  if ((warningOf(plan, ORPHANED) !== undefined) !== orphaned) defects.push(`${ORPHANED} ${orphaned ? 'expected' : 'not expected'}: ${JSON.stringify(codes(plan))}`);
  const shared = warningOf(plan, SHARED);
  if (sharedWith.length > 0) {
    const listed = (shared?.projects ?? []).map((item) => item.key).sort();
    if (!shared || shared.count !== sharedWith.length || !same(listed, [...sharedWith].sort())) defects.push(`${SHARED} expected for ${sharedWith.join(', ')}: ${JSON.stringify(shared ?? null)}`);
  } else if (shared) defects.push(`${SHARED} not expected: ${JSON.stringify(shared)}`);
  return defects;
}

const appliedPlans = (trace) => applies(trace, TOOL).map((run) => parseResult(run.preview?.result) ?? {});

export async function verifyAssign({ prepared, trace, conversation, lifecycle = {}, tool, adminPage }) {
  const { projects: keys, itss, states } = prepared;
  const [first, second] = appliedPlans(trace);
  const [readA, readB] = [await tool('get_project_screens', { project: keys.A }), await tool('get_project_screens', { project: keys.B })];
  const now = { A: stateOrNull(readA), B: stateOrNull(readB) };
  const itssTablesNow = { A: await itssTable(adminPage, itss.A.id), B: await itssTable(adminPage, itss.B.id) };
  const itssKeys = { A: await itssProjectKeys(adminPage, itss.A.id), B: await itssProjectKeys(adminPage, itss.B.id) };
  const [afterSwitch, afterReturn] = lifecycle.observed ?? [];
  const outcomeCheck = (run, index, target) => {
    const outcome = outcomeOf(trace, run);
    const result = outcome.result;
    return check(`apply ${index + 1}: COMPLETED / CHANGED, связь перечитана и равна целевой ITSS «${target.name}»`,
      outcome.envelope?.status === 'COMPLETED' && result?.status === 'CHANGED' && result.readBack === true
        && sameScheme(result.observedIssueTypeScreenScheme, target) && !result.errorCode,
      JSON.stringify({ operation: outcome.envelope?.status, status: result?.status, readBack: result?.readBack, observed: result?.observedIssueTypeScreenScheme ?? null, errorCode: result?.errorCode }));
  };
  const runs = applies(trace, TOOL);
  const switched = (record) => record?.status === 'CHANGED' && same(record.B, states.A) && same(record.A, states.A)
    && same(record.keys?.A, [keys.A, keys.B].sort()) && same(record.keys?.B, []);
  const restored = (record) => record?.status === 'CHANGED' && same(record.A, states.A) && same(record.B, states.B)
    && same(record.keys?.A, [keys.A]) && same(record.keys?.B, [keys.B]);
  return [
    ...protocolChecks(trace),
    check('превью 1: WOULD_CHANGE, проект B, ITSS B → ITSS A (id, имя), по каждому типу задач проекта Screen Scheme и экраны CREATE/EDIT/VIEW до → после, CHANGE',
      first !== undefined && planDefects(first, { project: keys.B, from: states.B, to: states.A, fromItss: itss.B, toItss: itss.A }).length === 0,
      first ? planDefects(first, { project: keys.B, from: states.B, to: states.A, fromItss: itss.B, toItss: itss.A }).join('; ') || 'точно' : 'нет плана'),
    check('превью 1: предупреждения — SHARED_TARGET (ещё один проект: A) и ITSS_ORPHANED (прежняя приватная ITSS B останется без проектов)',
      first !== undefined && warningDefects(first, { orphaned: true, sharedWith: [keys.A] }).length === 0,
      first ? warningDefects(first, { orphaned: true, sharedWith: [keys.A] }).join('; ') || 'есть' : 'нет плана'),
    check('превью 2 (возврат): WOULD_CHANGE, проект B, ITSS A → прежняя ITSS B, типы, Screen Scheme и экраны до → после, CHANGE',
      second !== undefined && planDefects(second, { project: keys.B, from: states.A, to: states.B, fromItss: itss.A, toItss: itss.B }).length === 0,
      second ? planDefects(second, { project: keys.B, from: states.A, to: states.B, fromItss: itss.A, toItss: itss.B }).join('; ') || 'точно' : 'нет плана'),
    check('превью 2: ITSS A остаётся у проекта A (нет ITSS_ORPHANED), прежняя ITSS B никем не используется (нет SHARED_TARGET)',
      second !== undefined && warningDefects(second, { orphaned: false, sharedWith: [] }).length === 0,
      second ? warningDefects(second, { orphaned: false, sharedWith: [] }).join('; ') || 'нет' : 'нет плана'),
    outcomeCheck(runs[0], 0, itss.A), outcomeCheck(runs[1], 1, itss.B),
    check('Jira после apply 1 (наблюдение hook): get_project_screens B = состояние A (id ITSS, Screen Scheme и экраны по типам), A не изменился; страница ITSS: у A [A, B], у прежней ITSS B проектов нет',
      switched(afterSwitch), JSON.stringify(afterSwitch ?? null)),
    check('Jira после возврата (наблюдение hook): A и B в исходном состоянии, страница ITSS: у A [A], у B [B]',
      restored(afterReturn), JSON.stringify(afterReturn ?? null)),
    check('Jira в конце: связи и таблицы A и B равны исходным (get_project_screens), проекты ITSS по странице админки — свои',
      same(now.A, states.A) && same(now.B, states.B) && same(itssKeys.A, [keys.A]) && same(itssKeys.B, [keys.B]),
      JSON.stringify({ now, itssKeys })),
    check('Jira: строки обеих ITSS не менялись, новых и удалённых Issue Type Screen Scheme и Screen Scheme нет',
      same(itssTablesNow, prepared.itssTables) && same(await itssIds(adminPage), prepared.itssIds) && same(await screenSchemeIds(adminPage), prepared.screenSchemeIds),
      JSON.stringify({ itssTablesNow })),
    ...advisories(conversation, trace, prepared),
  ];
}

const replyOfTurn = (conversation, entry) => conversation.filter((message) => message.role === 'assistant')[entry.turn - 1]?.content ?? '';

/** Advisory: one table before each confirmation names project B and both schemes of the change. */
function advisories(conversation, trace, prepared) {
  const runs = applies(trace, TOOL).filter((run) => run.preview);
  return runs.flatMap((run, index) => {
    const [from, to] = index === 0 ? [prepared.itss.B, prepared.itss.A] : [prepared.itss.A, prepared.itss.B];
    const reply = replyOfTurn(conversation, run.preview);
    return [
      previewTable(conversation, run.preview, { words: [prepared.projects.B, from.name, to.name], label: `Таблица последствий плана ${index + 1}`, allowEarlier: true }),
      advisoryCheck(`План ${index + 1}: таблица показывает Screen Scheme и экраны до → после`, hasTable(reply), `таблица ${hasTable(reply)}`),
    ];
  });
}

// --- hook (read-only) --------------------------------------------------------------------------------------------

/** Defects of a plan preview: only project B, and only the A→ then B-back switch (A's scheme before the first apply, B's own after it). */
export function previewDefects(prepared, { result }, appliedCount) {
  const defects = [];
  const target = appliedCount === 0 ? prepared.itss.A : appliedCount === 1 ? prepared.itss.B : null;
  if (result.project?.key !== prepared.projects.B) defects.push(`the preview targets project ${result.project?.key ?? 'none'}, not the fresh project ${prepared.projects.B}`);
  if (!target) defects.push(`a plan after ${appliedCount} applies: the scenario has exactly two`);
  else if (String(result.newIssueTypeScreenScheme?.id) !== target.id) {
    defects.push(`the preview switches to Issue Type Screen Scheme ${result.newIssueTypeScreenScheme?.id ?? 'none'}, not ${target.id} «${target.name}»`);
  }
  return defects;
}

/**
 * Hook (read-only). First it stops the dialogue, before the simulated user's turn, when the latest plan preview of the tool is outside
 * the spec (another project or scheme); then, after each completed apply, it records the real Jira state of both projects and the
 * project lists of both schemes before the next user message.
 */
export async function observeAfterApply({ prepared, lifecycle, trace, tool, adminPage }) {
  lifecycle.observed ??= [];
  const latest = plansOf(trace).at(-1);
  if (latest) {
    const defects = previewDefects(prepared, latest, applies(trace, TOOL).filter((run) => run.start.seq < latest.entry.seq).length);
    if (defects.length > 0) throw new Error(`STOP: preview #${latest.entry.seq} of ${TOOL} is outside the spec: ${defects.join('; ')}`);
  }
  const known = new Set(lifecycle.observed.map((item) => item.planId));
  for (const run of applies(trace, TOOL)) {
    if (known.has(run.planId)) continue;
    const outcome = outcomeOf(trace, run);
    if (outcome.envelope?.status !== 'COMPLETED') continue;
    const [readA, readB] = [await tool('get_project_screens', { project: prepared.projects.A }), await tool('get_project_screens', { project: prepared.projects.B })];
    lifecycle.observed.push({ planId: run.planId, turn: run.start.turn, status: outcome.result?.status, A: stateOrNull(readA), B: stateOrNull(readB),
      keys: { A: await itssProjectKeys(adminPage, prepared.itss.A.id), B: await itssProjectKeys(adminPage, prepared.itss.B.id) } });
  }
}
