// Shared helpers of the workflow-validator-* live scenarios, migrated to the single
// configure_workflow tool: test workflow preparation, validator snapshots, request/preview inspection and the exact
// expected effect of one configure_workflow apply. setup.mjs creates only test objects with random names; verify.mjs and
// hooks.mjs only read.
import { applies, parseResult } from './checks.mjs';
import { projectManagement } from './project-templates.mjs';

export const GET = 'get_workflow_validators';
export const CFG = 'configure_workflow';
export const PUBLISH = 'publish_workflow_draft';
/**
 * Tools the validator scenarios forbid for the assistant. Besides them, configure_workflow is forbidden when it carries
 * anything but validators of an existing workflow (see foreignParts / foreignToolCalls).
 */
export const NOT_THESE_TOOLS = ['edit_workflow', 'copy_workflow', 'rename_workflow', 'update_status'];
/** System fields the scenario texts name in English; setup fails loudly when the Jira shows other names. */
export const FIELD_NAMES = { assignee: 'Assignee', description: 'Description', environment: 'Environment', labels: 'Labels', priority: 'Priority' };
const ALL = (a, b) => JSON.stringify(a) === JSON.stringify(b);

export async function requireSystemFields(rest) {
  const fields = await rest('GET', '/rest/api/2/field');
  const wrong = Object.entries(FIELD_NAMES).filter(([id, name]) => !fields.some((f) => f.id === id && f.name === name));
  if (wrong.length) throw new Error(`Поля Jira называются иначе, чем в тексте сценария: ${wrong.map(([id, name]) => `${id}≠«${name}»`).join(', ')}`);
}

export async function requireNoField(rest, id) {
  const fields = await rest('GET', '/rest/api/2/field');
  if (fields.some((f) => f.id === id)) throw new Error(`Поле ${id} существует; сценарию нужен несуществующий id`);
}

/** Existing statuses only (no new global status): TO_DO, IN_PROGRESS and DONE for the graph, `spare` more for foreign edits. */
export async function pickStatuses(tool, spare = 0) {
  const { statuses } = await tool('list_statuses', { limit: 100 });
  const used = new Set();
  const choose = (preferred, category) => {
    const candidates = statuses.filter((s) => s.category === category && !used.has(s.id));
    const selected = candidates.find((s) => s.name === preferred) ?? [...candidates].sort((a, b) => a.name.localeCompare(b.name))[0];
    if (!selected) throw new Error(`Нет существующего статуса ${category}`);
    used.add(selected.id);
    return selected;
  };
  const start = choose('Open', 'TO_DO'), working = choose('In Progress', 'IN_PROGRESS'), done = choose('Done', 'DONE');
  const foreign = statuses.filter((s) => !used.has(s.id) && s.category === 'IN_PROGRESS').slice(0, spare);
  if (foreign.length < spare) throw new Error(`Нужно ${spare} запасных существующих статуса IN_PROGRESS, есть ${foreign.length}`);
  return { start, working, done, foreign };
}

/** Compact, comparable form of get_workflow_validators: what the tool exposes of every validator, in order. */
export function snapshotOf(view) {
  return view.transitions.map((t) => ({
    actionId: t.actionId, name: t.name,
    validators: t.validators.map((v) => v.kind === 'REQUIRED_FIELDS'
      ? { kind: v.kind, fieldIds: v.fieldIds, conditional: v.conditional }
      : { kind: v.kind, type: v.type, className: v.className, cannedScript: v.cannedScript }),
  }));
}

/**
 * Creates the inactive test workflow «names['NAME:wf']»: Open → In Progress → Done with transitions Start work, Finish
 * (Done, resolution SET), Reopen (resolution CLEAR). `seed` {transitionName: fieldIds} adds required-fields validators
 * with the product's own configure tool; Reopen always gets one on `environment` so that "other transitions stay" is
 * observable. Returns the facts the dialogue and the checks need.
 */
export async function createDelivery({ names, tool, apply, rest, log }, { seed = {}, spare = 0 } = {}) {
  await requireSystemFields(rest);
  const s = await pickStatuses(tool, spare);
  const resolutions = await tool('get_resolutions', {});
  const fixed = resolutions.find((r) => /^fixed$/iu.test(r.name)) ?? resolutions[0];
  if (!fixed) throw new Error('Нет ни одной Resolution для перехода Finish');
  const workflow = names['NAME:wf'];
  await apply(CFG, {
    create: {
      name: workflow, initialStatus: s.start.name,
      statuses: [s.start, s.working, s.done].map(({ name, category }) => ({ name, category })),
      transitions: [
        { key: 'start', name: 'Start work', from: s.start.name, to: s.working.name },
        { key: 'finish', name: 'Finish', from: s.working.name, to: s.done.name, resolution: { action: 'SET', value: fixed.id } },
        { key: 'reopen', name: 'Reopen', from: s.done.name, to: s.working.name, resolution: { action: 'CLEAR' } },
      ],
    },
  });
  let view = await tool(GET, { workflow, version: 'ACTIVE' });
  if (!view.scriptRunner?.supported) throw new Error(`ScriptRunner не готов к настройке: ${view.scriptRunner?.reason}`);
  const actionIds = Object.fromEntries(view.transitions.map((t) => [t.name, t.actionId]));
  for (const [transition, fieldIds] of Object.entries({ Reopen: ['environment'], ...seed })) {
    view = await tool(GET, { workflow, version: 'ACTIVE' });
    await apply(CFG, { existing: { workflow, version: 'ACTIVE', expectedRevision: view.revision },
      transitionRules: [{ actionId: actionIds[transition], addValidators: [{ template: 'required_fields', parameters: { fieldIds } }] }] });
  }
  view = await tool(GET, { workflow, version: 'ACTIVE' });
  log(`workflow «${workflow}» (${s.start.name} → ${s.working.name} → ${s.done.name}); actionId: ${JSON.stringify(actionIds)}`);
  return { workflow, actionIds, statuses: s, revision: view.revision, snapshot: snapshotOf(view) };
}

/** A test project and its own use of the workflow: the workflow becomes active (no draft yet). */
export async function activate({ names, tool, apply, log }, workflow) {
  const project = names['KEY:proj'];
  await apply('create_project', projectManagement({ name: names['NAME:project'], key: project, lead: 'admin' }));
  await apply('assign_workflow', { project, workflow, allIssueTypes: true });
  const state = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  if (!state.active || state.hasDraft) throw new Error(`workflow должен быть активным без draft: ${JSON.stringify({ active: state.active, hasDraft: state.hasDraft })}`);
  log(`проект ${project} использует «${workflow}»`);
  return { project };
}

/** The "other administrator" of the draft: Jira's own admin actions, like the UI would do (no plugin involved). */
export async function addStep({ adminPage }, workflow, status, mode) {
  await adminPage(`/secure/admin/workflows/EditWorkflowDispatcher.jspa?wfName=${encodeURIComponent(workflow)}`);
  await adminPage('/secure/admin/workflows/AddWorkflowStep.jspa', {
    workflowName: workflow, workflowMode: mode, stepName: status.name, stepStatus: status.id,
  });
}

/** FIELD_FUNCTION_ID values of the exported descriptor in order, or null when Jira's XML export page is not reachable. */
export async function functionIds(adminPage, workflow, mode = 'live') {
  try {
    const page = await adminPage(`/secure/admin/workflows/ViewWorkflowXml.jspa?workflowMode=${mode}&workflowName=${encodeURIComponent(workflow)}`);
    const ids = [...page.text.matchAll(/FIELD_FUNCTION_ID["']?\s*>\s*([0-9a-f-]{36})\s*</giu)].map((m) => m[1]);
    return page.text.includes('<workflow') ? ids : null;
  } catch { return null; }
}

const unconditionalFields = (validators) => validators.filter((v) => v.kind === 'REQUIRED_FIELDS' && !v.conditional).flatMap((v) => v.fieldIds);
const sameList = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/** The `existing` target of a configure_workflow request: { workflow, version, expectedRevision } (empty when absent). */
export const existingOf = (args) => args?.existing ?? {};
/** Every required_fields request of a configure_workflow request: [{ actionId, fieldIds }] over transitionRules × addValidators. */
export const requiredRequests = (args) => (args?.transitionRules ?? []).flatMap((rule) => (rule?.addValidators ?? [])
  .filter((v) => v?.template === 'required_fields').map((v) => ({ actionId: rule.actionId, fieldIds: v.parameters?.fieldIds ?? [] })));
/** The field ids requested by a configure_workflow request for one transition (every transition when actionId is omitted). */
export const requestedFieldIds = (args, actionId) => requiredRequests(args).filter((r) => actionId === undefined || r.actionId === actionId)
  .flatMap((r) => r.fieldIds);
/** The field lists of the `ADD_VALIDATOR: <actionId> required_fields [a, b]` lines of a preview/result diff, for one transition. */
export const addedRequiredFields = (result, actionId) => (result?.diff ?? [])
  .map((line) => new RegExp(`^ADD_VALIDATOR: ${actionId} required_fields \\[([^\\]]*)\\]$`, 'u').exec(line))
  .filter(Boolean).map((m) => m[1].split(',').map((id) => id.trim()).filter(Boolean));
/** The transition as the preview shows it (the final state, including the validators that existed before). */
export const previewTransition = (result, actionId) => (result?.transitions ?? []).find((t) => t.actionId === actionId);
/** True when the preview/result creates a draft of the workflow itself (the objects list names it); an existing draft is not created. */
export const createsDraft = (result) => (result?.objects ?? []).some((o) => o.kind === 'DRAFT');

/**
 * The exact observable effect of one configure_workflow apply (additive semantics): other transitions equal; on the
 * target transition the existing validators stay exactly as they were and, when some requested fields are not covered by
 * the unconditional required-fields validators already there, ONE new validator with exactly the missing fields is
 * appended at the end (an existing validator is never extended); when all are covered nothing changes.
 */
export function changeChecks({ before, after, actionId, requested, label }) {
  const target = (snapshot) => snapshot.find((t) => t.actionId === actionId);
  const rest = (snapshot) => snapshot.filter((t) => t.actionId !== actionId);
  const have = unconditionalFields(target(before).validators);
  const missing = requested.filter((id) => !have.includes(id));
  const prior = target(before).validators;
  const got = target(after)?.validators ?? [];
  const kept = got.slice(0, prior.length);
  const added = got.slice(prior.length);
  const addedOk = missing.length
    ? added.length === 1 && added[0].kind === 'REQUIRED_FIELDS' && !added[0].conditional && sameList([...added[0].fieldIds].sort(), [...missing].sort())
    : added.length === 0;
  return [
    { name: `${label}: прочие переходы и их валидаторы не изменились`, ok: sameList(rest(before), rest(after)),
      detail: sameList(rest(before), rest(after)) ? 'совпадают' : JSON.stringify(rest(after)).slice(0, 400) },
    { name: `${label}: прежние валидаторы целевого перехода не изменены и идут первыми, в том же порядке`, ok: sameList(kept, prior),
      detail: `ожидалось ${JSON.stringify(prior)}; получено ${JSON.stringify(kept)}`.slice(0, 600) },
    { name: missing.length
      ? `${label}: в конец добавлен один новый валидатор только с недостающими полями (${missing.join(', ')})`
      : `${label}: недостающих полей нет — новых валидаторов нет`,
      ok: addedOk, detail: `добавлено после прежних: ${JSON.stringify(added)}`.slice(0, 600) },
  ];
}

/** Every configure_workflow preview (dryRun:true) with its parsed result. A malformed call without `existing` is a preview too. */
export const configurePreviews = (trace) => trace.filter((e) => e.tool === CFG && e.arguments?.dryRun === true)
  .map((entry) => ({ entry, result: parseResult(entry.result) ?? {} }));
export const calls = (trace, tool) => trace.filter((e) => e.tool === tool);
export const configureApplies = (trace) => applies(trace, CFG);
/**
 * What of a configure_workflow request is foreign to a "required fields on a transition" dialogue on an existing workflow:
 * a `create` target, new screens, a SET screen (`screen: "KEEP"` changes nothing and is allowed), conditions. The validators
 * are the only thing asked for. In baseline (create_workflow / edit_workflow / configure_workflow_conditions) every one of these
 * was a separate tool the dialogue did not use. Returns the reasons, empty when the request is clean.
 */
export function foreignParts(args) {
  const out = [];
  if (args?.create !== undefined) out.push('create');
  if (args?.existing && Object.hasOwn(args.existing, 'changes')) out.push('changes');
  if (Array.isArray(args?.newScreens) && args.newScreens.length) out.push('newScreens');
  for (const rule of Array.isArray(args?.transitionRules) ? args.transitionRules : []) {
    if (rule?.screen !== undefined && rule.screen !== 'KEEP') out.push('screen');
    if (rule?.addConditions) out.push('addConditions');
  }
  return [...new Set(out)];
}
/** Calls these scenarios forbid: NOT_THESE_TOOLS and configure_workflow carrying anything but validators of an existing workflow. */
export const foreignToolCalls = (trace) => trace.filter((e) => NOT_THESE_TOOLS.includes(e.tool) || (e.tool === CFG && foreignParts(e.arguments).length > 0));
/** Terminal results of operations in the order the assistant observed them: [{ toolName, status, result }]. */
export function operationResults(trace) {
  return trace.filter((e) => e.tool === 'get_operation').map((e) => parseResult(e.result)).filter((r) => r?.status === 'COMPLETED')
    .map((r) => ({ toolName: r.operation, status: r.result?.status, errorCode: r.result?.errorCode, result: r.result }));
}
/** The assistant's visible reply of turn t (the t-th user message opens turn t). */
export const replyOf = (conversation, turn) => conversation.filter((c) => c.role === 'assistant')[turn - 1]?.content ?? '';
export const check = (name, ok, detail) => ({ name, ok: Boolean(ok), detail });

export async function readViews(tool, workflow) {
  const out = {};
  for (const version of ['ACTIVE', 'DRAFT']) {
    try { out[version] = await tool(GET, { workflow, version }); } catch { out[version] = null; }
  }
  return out;
}

/** Every configure preview was made with the revision of the newest read of that workflow and version before it. */
export function revisionsFresh(trace) {
  const stale = configurePreviews(trace).filter(({ entry }) => {
    const { workflow, version, expectedRevision } = existingOf(entry.arguments);
    const read = trace.filter((e) => e.seq < entry.seq && e.tool === GET && e.arguments?.workflow === workflow
      && e.arguments?.version === version).at(-1);
    return !workflow || !read || parseResult(read.result)?.revision !== expectedRevision;
  });
  return check('get_workflow_validators перед каждым preview; existing.expectedRevision — из последнего чтения той же версии', stale.length === 0,
    stale.length ? `preview #${stale.map(({ entry }) => entry.seq).join(', #')}` : `${configurePreviews(trace).length} preview`);
}

/** The reply of the preview's turn is one question with a table of the transition, the fields and the error text. */
export function previewQuestion(conversation, previewEntry, { transition, fieldWords }) {
  const text = replyOf(conversation, previewEntry.turn);
  const question = (text.match(/\?/gu) ?? []).length;
  const missing = fieldWords.filter((word) => !text.toLowerCase().includes(word.toLowerCase()));
  const ok = /\|/u.test(text) && text.toLowerCase().includes(transition.toLowerCase()) && missing.length === 0 && question === 1
    && /This field is required|The following fields are required|текст ошибки|ошибк/iu.test(text);
  return check(`ответ хода ${previewEntry.turn}: таблица (переход, поля, текст ошибки) и один вопрос`, ok,
    ok ? 'есть' : `таблица ${/\|/u.test(text)}, переход ${text.toLowerCase().includes(transition.toLowerCase())}, нет слов: ${missing.join(', ') || '—'}, вопросов: ${question}; «${text.slice(0, 200)}»`);
}
