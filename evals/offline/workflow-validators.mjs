import { isDeepStrictEqual as same } from 'node:util';
import { operationCompletion } from './grade.mjs';
import { commentScenarios, generateCommentValidatorsScenario, createCommentValidatorsMock,
  nextCommentValidatorsUser, gradeCommentValidators } from './workflow-comment-validators.mjs';

// Protocol eval on a deterministic stateful Jira model. It grades dialogue structure and
// confirmed configure_workflow calls, not the Java implementation or native Jira writes.
const VALIDATION = 'workflow_validators';
const GET = 'get_workflow_validators';
const CFG = 'configure_workflow';
const PUBLISH = 'publish_workflow_draft';
const REQUIRED = 'set_field_required';
const WORKFLOW = 'LOCAL-004 Delivery';
const START = 'start_operation';
const FOREIGN_WRITES = ['edit_workflow', 'copy_workflow', 'rename_workflow', 'update_status'];
const PUBLISH_REQUEST = /опублику|опубликов|publish/iu;
const INPUT_ERRORS = ['UNKNOWN_FIELD', 'VALIDATION_ERROR', 'UNKNOWN_TRANSITION'];

export const validatorVariants = ['inactive', 'active_draft', 'add_existing', 'unchanged', 'routing_creation',
  'routing_transition', 'stale_apply', 'conflict_preview', 'draft_exists', 'bad_input', 'scope_refusals',
  'no_scriptrunner', 'version_unsupported'];
export const validatorScenarios = [...validatorVariants.map(variant => `workflow_validators_${variant}`), ...commentScenarios];

const clone = value => structuredClone(value);
const FIELDS = { assignee: 'Assignee', description: 'Description', priority: 'Priority', labels: 'Labels',
  environment: 'Environment', customfield_10118: 'Причина для "На рассмотрении"' };
const NOT_ORDERABLE = { status: 'Status' };
const required = (fieldIds, conditional = false) => ({ kind: 'REQUIRED_FIELDS', fieldIds, conditional });
const COMMENT = () => ({ kind: 'COMMENT_REQUIRED' });
const VERIFIED = '[9.46.0]';
const SCRIPTRUNNER = {
  ok: { installed: true, enabled: true, version: '9.46.0', supported: true, reason: null },
  missing: { installed: false, enabled: false, version: null, supported: false,
    reason: 'ScriptRunner for Jira (plugin com.onresolve.jira.groovy.groovyrunner) is not installed. Verified versions: '
      + `${VERIFIED}.` },
  unsupported: { installed: true, enabled: true, version: '9.46.1', supported: false,
    reason: "The installed ScriptRunner for Jira has version '9.46.1', but the format of its Field(s) required "
      + `validator was captured only for versions ${VERIFIED} (an exact match is required: even a different patch `
      + 'version is not accepted).' },
};

function transitions(done, extra = {}) {
  return [
    { actionId: 11, name: 'Start work', from: ['Open'], to: ['Working'], screenId: null, screenName: null,
      validators: extra.start ?? [] },
    { actionId: 21, name: 'Done', from: ['Working'], to: ['Done'], screenId: '10100', screenName: 'Done screen',
      validators: done },
    { actionId: 31, name: 'Reopen', from: ['Done'], to: ['Working'], screenId: null, screenName: null,
      validators: extra.reopen ?? [required(['assignee'])] },
  ];
}

const NO_CONSUMERS = { schemeCount: 0, projectCount: 0, schemes: [], projects: [], truncated: false };
const CONSUMERS = { schemeCount: 1, projectCount: 1, schemes: ['L004 Scheme'], projects: ['L004 Project'], truncated: false };
const DONE_DEFAULT = () => [COMMENT(), required(['priority'], true)];

function worldFor(variant) {
  const world = { name: WORKFLOW, active: false, scriptRunner: SCRIPTRUNNER.ok, consumers: NO_CONSUMERS,
    main: { n: 1, transitions: transitions(DONE_DEFAULT()) }, draft: null, concurrent: null };
  if (variant === 'active_draft') Object.assign(world, { active: true, consumers: CONSUMERS });
  if (variant === 'add_existing') world.main.transitions = transitions([required(['assignee']), COMMENT()]);
  if (variant === 'unchanged') world.main.transitions = transitions([required(['assignee', 'description']),
    required(['priority'])]);
  if (variant === 'draft_exists') {
    Object.assign(world, { active: true, consumers: CONSUMERS });
    world.main.n = 3;
    // A foreign change of another administrator sits in the shared draft and must survive.
    world.draft = { n: 7, transitions: transitions(DONE_DEFAULT(), { start: [required(['environment'])] }) };
  }
  if (variant === 'stale_apply') world.concurrent = 'afterFirstPreview';
  if (variant === 'conflict_preview') world.concurrent = 'afterFirstRead';
  if (variant === 'no_scriptrunner') world.scriptRunner = SCRIPTRUNNER.missing;
  if (variant === 'version_unsupported') world.scriptRunner = SCRIPTRUNNER.unsupported;
  return world;
}

const INITIAL = {
  inactive: `На неактивном workflow «${WORKFLOW}» сделай поле Description обязательным на переходе Done.`,
  active_draft: `В активном workflow «${WORKFLOW}» сделай поле Description обязательным на переходе Done.`,
  add_existing: `На переходе Done workflow «${WORKFLOW}» (он неактивный) сделай поля Assignee и Description обязательными.`,
  unchanged: `На переходе Done workflow «${WORKFLOW}» (он неактивный) сделай обязательными Assignee и Priority.`,
  routing_creation: 'Сделай поле Priority обязательным при создании задачи во всех типах задач проекта L004.',
  routing_transition: `Поле Description должно быть обязательным на переходе Done в неактивном workflow «${WORKFLOW}».`,
  draft_exists: `В активном workflow «${WORKFLOW}» сделай поле Labels обязательным на переходе Done.`,
  bad_input: `На неактивном workflow «${WORKFLOW}» сделай поле Status обязательным на переходе Done; также поле с id `
    + 'customfield_99999 на Done; и поле Priority на переходе с номером 999.',
  scope_refusals: `Убери поле Assignee из обязательных на переходе Done в workflow «${WORKFLOW}».`,
};
INITIAL.stale_apply = INITIAL.inactive;
INITIAL.conflict_preview = INITIAL.inactive;
INITIAL.no_scriptrunner = INITIAL.inactive;
INITIAL.version_unsupported = INITIAL.inactive;

export const PUBLISH_FOLLOW_UP = 'Теперь опубликуй этот черновик.';
const BAD_INPUT_FOLLOW_UP = 'Точных id полей и номера перехода я не знаю. Ничего не меняйте, я уточню позже.';
const SCOPE_FOLLOW_UPS = [
  'А сделай поле Description обязательным на Done только если Priority = High.',
  'Тогда поставь на этот валидатор свой Groovy-скрипт и текст ошибки «Заполните описание».',
];

function operationsFor(variant) {
  const op = (id, tool, status, extra = {}) => ({ id, tool, preview: { planId: `l004-${id}` }, apply: { status }, ...extra });
  switch (variant) {
    case 'inactive': case 'add_existing': case 'routing_transition': case 'conflict_preview':
      return [op('cfg-1', CFG, 'UPDATED_INACTIVE')];
    case 'stale_apply':
      return [op('cfg-1', CFG, 'UPDATED_INACTIVE', { previewOnly: true }), op('cfg-2', CFG, 'UPDATED_INACTIVE')];
    case 'active_draft': return [op('cfg-1', CFG, 'DRAFT_SAVED'), op('pub-1', PUBLISH, 'PUBLISHED')];
    case 'draft_exists': return [op('cfg-1', CFG, 'DRAFT_SAVED')];
    case 'routing_creation': return [op('req-1', REQUIRED, 'REQUIREMENTS_CHANGED')];
    default: return [];
  }
}

export function generateWorkflowValidatorsScenario(name) {
  if (commentScenarios.includes(name)) return generateCommentValidatorsScenario(name);
  const variant = name.slice('workflow_validators_'.length);
  if (!validatorVariants.includes(variant) || !name.startsWith('workflow_validators_')) {
    throw new Error(`Unknown workflow validators scenario: ${name}`);
  }
  return { scenario: name, kind: 'project_document', validation: VALIDATION, variant, syntheticUserMode: 'deterministic',
    initialUserMessage: INITIAL[variant], world: worldFor(variant), operations: operationsFor(variant),
    limits: { turns: 8, assistantCalls: 30, syntheticUserCalls: 0, toolCalls: 30,
      // Refusals need no tools: three consecutive text-only turns are the expected shape there.
      ...(variant === 'scope_refusals' ? { idleTurns: 4 } : {}) } };
}

const lastAssistantTurn = conversation => conversation.filter(m => m.role === 'assistant').at(-1)?.turn;
const planShown = (trace, turn) => trace.some(c => c.assistantTurn === turn && c.arguments?.dryRun === true &&
  c.result?.planId && c.result.status !== 'FAILED');

// Confirms exactly what was shown in the last turn; asks for more only where the scenario needs it; otherwise ends.
export function nextWorkflowValidatorsUser(scenario, conversation, trace) {
  if (scenario.comment) return nextCommentValidatorsUser(scenario, conversation, trace);
  const turn = lastAssistantTurn(conversation);
  if (planShown(trace, turn)) return 'Да, применяй.';
  const userCount = conversation.filter(m => m.role === 'user').length;
  switch (scenario.variant) {
    case 'active_draft': {
      const saved = trace.some(c => c.tool === 'get_operation' && c.result?.result?.status === 'DRAFT_SAVED');
      if (!saved) return 'Продолжай.';
      if (!conversation.some(m => m.role === 'user' && PUBLISH_REQUEST.test(m.content))) return PUBLISH_FOLLOW_UP;
      return trace.some(c => c.tool === PUBLISH) ? 'Продолжай.' : 'DONE';
    }
    case 'bad_input': return userCount === 1 ? BAD_INPUT_FOLLOW_UP : 'DONE';
    case 'scope_refusals': return SCOPE_FOLLOW_UPS[userCount - 1] ?? 'DONE';
    case 'stale_apply': case 'conflict_preview': case 'draft_exists': case 'inactive': case 'add_existing':
    case 'routing_transition': case 'routing_creation':
      return trace.some(c => c.tool === START) ? 'Продолжай.' : 'DONE';
    default: return 'DONE';
  }
}

export function createValidatorsMock(scenario) {
  if (scenario.comment) return createCommentValidatorsMock(scenario);
  const w = clone(scenario.world);
  const plans = new Map();
  let cfgCount = 0, pubCount = 0, reqCount = 0, reads = 0, previews = 0;
  const revisionOf = (kind) => kind === 'DRAFT' ? w.draft && `draft-r${w.draft.n}` : `main-r${w.main.n}`;
  const failed = (dryRun, errorCode, message) => ({ dryRun, status: 'FAILED', workflow: w.name, errorCode, message });
  const stale = () => ({ dryRun: false, status: 'FAILED', errorCode: 'PREVIEW_STALE',
    message: 'Preview is missing, expired, foreign, consumed, or the workflow, its draft, its users, a field or '
      + 'ScriptRunner (its state or version) changed. Nothing was written. Read the workflow validators again and '
      + 'run preview again.' });
  const bump = () => { w.main.n += 1; };
  const isUnconditional = v => v.kind === 'REQUIRED_FIELDS' && !v.conditional;
  const refs = ids => ids.map(id => ({ id, name: FIELDS[id] }));
  const errorText = ids => ids.length === 1 ? 'This field is required'
    : `The following fields are required: ${ids.map(id => FIELDS[id]).join(', ')}`;

  function view(version) {
    const side = version === 'DRAFT' ? w.draft : w.main;
    return { workflow: w.name, version, revision: revisionOf(version), scriptRunner: clone(w.scriptRunner),
      transitions: side.transitions.map(t => ({ actionId: t.actionId, name: t.name, screenId: t.screenId,
        screenName: t.screenName, validators: t.validators.map((v, index) => v.kind === 'REQUIRED_FIELDS'
          ? { position: index + 1, kind: v.kind, fieldIds: [...v.fieldIds], fields: refs(v.fieldIds), conditional: v.conditional }
          : v.kind === 'COMMENT_REQUIRED' ? { position: index + 1, kind: v.kind, fieldIds: null, fields: null,
            conditional: null, type: null, className: null, cannedScript: null, problem: t.screenId ? null : 'No usable transition screen' }
          : { position: index + 1, kind: 'OTHER', type: v.type, className: v.className, cannedScript: v.cannedScript }) })) };
  }

  function read(args) {
    if (Object.keys(args ?? {}).sort().join(',') !== 'version,workflow' || !['ACTIVE', 'DRAFT'].includes(args.version)) {
      return failed(false, 'VALIDATION_ERROR', 'workflow and version (ACTIVE or DRAFT) are required.');
    }
    if (args.workflow !== w.name) return failed(false, 'UNKNOWN_WORKFLOW', `Workflow '${args.workflow}' does not exist.`);
    if (args.version === 'DRAFT' && !w.draft) {
      return failed(false, 'VERSION_CONFLICT', `The workflow '${w.name}' has no draft now. Read the workflow again.`);
    }
    const result = view(args.version);
    reads += 1;
    if (w.concurrent === 'afterFirstRead' && reads === 1) bump(); // a parallel administrator saves right after the read
    return result;
  }

  function preview(request) {
    const target = request?.existing, rules = request?.transitionRules;
    const rule = rules?.[0], validator = rule?.addValidators?.[0], ids = validator?.parameters?.fieldIds;
    if (Object.keys(request ?? {}).sort().join(',') !== 'dryRun,existing,transitionRules' ||
        !target || Object.keys(target).sort().join(',') !== 'expectedRevision,version,workflow' ||
        !Array.isArray(rules) || rules.length !== 1 || Object.keys(rule).sort().join(',') !== 'actionId,addValidators' ||
        !Array.isArray(rule.addValidators) || rule.addValidators.length !== 1 ||
        Object.keys(validator).sort().join(',') !== 'parameters,template' || validator.template !== 'required_fields' ||
        !['ACTIVE', 'DRAFT'].includes(target.version) || !Number.isInteger(rule.actionId) || rule.actionId < 1 ||
        !validator.parameters || Object.keys(validator.parameters).join(',') !== 'fieldIds' ||
        !Array.isArray(ids) || ids.length < 1 || ids.length > 50 || new Set(ids).size !== ids.length ||
        ids.some(id => typeof id !== 'string' || !id.trim())) {
      return failed(true, 'VALIDATION_ERROR', 'Provide existing {workflow, version, expectedRevision} and one typed required_fields transition rule.');
    }
    const { workflow, version, expectedRevision } = target, { actionId } = rule;
    if (!w.scriptRunner.supported) {
      return failed(true, w.scriptRunner.installed && w.scriptRunner.enabled ? 'SCRIPTRUNNER_VERSION_UNSUPPORTED'
        : 'SCRIPTRUNNER_UNAVAILABLE', w.scriptRunner.reason);
    }
    if (workflow !== w.name) return failed(true, 'UNKNOWN_WORKFLOW', `Workflow '${workflow}' does not exist.`);
    let mode, side, draftState;
    if (version === 'DRAFT') {
      if (!w.active || !w.draft) {
        return failed(true, 'VERSION_CONFLICT', `The workflow '${w.name}' has no draft now. Nothing was written. Read the workflow again.`);
      }
      mode = 'DRAFT_SAVE'; side = w.draft;
      draftState = 'EXISTS: the existing draft is edited; it is shared by all administrators and its other changes stay';
    } else if (!w.active) {
      mode = 'INACTIVE_SAVE'; side = w.main;
      draftState = 'NOT_APPLICABLE: the workflow is not active, it is saved in place';
    } else if (w.draft) {
      return failed(true, 'DRAFT_EXISTS', `The active workflow '${w.name}' already has a draft. It is shared by all `
        + 'administrators and may hold changes of others. Read it with get_workflow version DRAFT and edit that '
        + 'version with its revision.');
    } else {
      mode = 'DRAFT_CREATE'; side = w.main;
      draftState = 'NONE: a draft will be created and the edit saved into it; the active version stays';
    }
    if (expectedRevision !== revisionOf(version)) {
      return failed(true, 'VERSION_CONFLICT', `The ${version} version of '${w.name}' changed after it was read `
        + `(expected ${expectedRevision}, now ${revisionOf(version)}). Read it again.`);
    }
    const action = side.transitions.find(t => t.actionId === actionId);
    if (!action) return failed(true, 'UNKNOWN_TRANSITION', `The ${version} version has no transition ${actionId}.`);
    for (const id of ids) {
      if (id in NOT_ORDERABLE) return failed(true, 'VALIDATION_ERROR', `Field '${id}' cannot be put on a screen, so it cannot be required by the validator.`);
      if (!(id in FIELDS)) return failed(true, 'UNKNOWN_FIELD', `No field with id '${id}'.`);
    }
    const unconditional = action.validators.filter(isUnconditional);
    const already = new Set(unconditional.flatMap(v => v.fieldIds));
    const alreadyRequired = ids.filter(id => already.has(id)), added = ids.filter(id => !already.has(id));
    const transition = { actionId: action.actionId, name: action.name, from: action.from, to: action.to };
    if (added.length === 0) {
      return { dryRun: true, status: 'UNCHANGED', workflow: w.name, version, transition,
        alreadyRequired: refs(alreadyRequired), message: 'Every requested field is already required on this '
          + `transition by an unconditional required-fields validator of the ${version} version of '${w.name}'. `
          + 'Nothing was written and no draft was created.' };
    }
    const resulting = added;
    const warnings = mode !== 'INACTIVE_SAVE' && w.consumers.projectCount > 0
      ? [`The draft belongs to the shared workflow '${w.name}': publishing it changes ${w.consumers.projectCount} project(s) at once.`]
      : [];
    const planId = `l004-cfg-${++cfgCount}`;
    plans.set(planId, { tool: CFG, mode, version, actionId, added,
      revision: revisionOf(version), consumed: false });
    previews += 1;
    if (w.concurrent === 'afterFirstPreview' && previews === 1) bump(); // a parallel administrator saves after the preview
    const where = mode === 'INACTIVE_SAVE' ? `saved in place in the inactive workflow '${w.name}'`
      : mode === 'DRAFT_SAVE' ? `saved in the existing draft of '${w.name}'` : `saved in a new draft of the active workflow '${w.name}'`;
    return { dryRun: true, undoWarning: 'This operation cannot be rolled back through Undo.', status: 'WOULD_CHANGE',
      workflow: w.name, version, targetVersion: mode === 'INACTIVE_SAVE' ? 'ACTIVE' : 'DRAFT',
      draftState, transition, alreadyRequired: refs(alreadyRequired),
      added: refs(added), validatorChange:
        `A new validator (Field(s) required) is added at the end of the validators of transition ${action.actionId} with ${resulting.length} field(s). Existing validators stay unchanged.`,
      errorText: errorText(resulting), diff: [`ADD_REQUIRED_FIELDS: transition ${action.actionId} '${action.name}': ${added.join(', ')}`],
      warnings, consumers: clone(w.consumers),
      message: `The change would be ${where}. Every other condition, validator, function and property of the workflow stays as it is; nothing is removed, replaced or reordered, and there is no automatic rollback.`,
      nextStep: mode === 'INACTIVE_SAVE' ? 'After the yes, apply with start_operation. Nothing is published or assigned by this change.'
        : 'After the yes, apply with start_operation: the change is saved in the draft only. Publication is a separate publish_workflow_draft call with its own preview and confirmation.',
      planId, expiresAt: 4102444800000 };
  }

  function applyConfigure(plan) {
    const current = plan.mode === 'DRAFT_CREATE' ? (w.draft ? null : `main-r${w.main.n}`) : revisionOf(plan.version);
    if (current !== plan.revision) return stale();
    if (plan.mode === 'DRAFT_CREATE') w.draft = { n: 0, transitions: clone(w.main.transitions) };
    const side = plan.mode === 'INACTIVE_SAVE' ? w.main : w.draft;
    const action = side.transitions.find(t => t.actionId === plan.actionId);
    action.validators.push(required([...plan.added]));
    side.n += 1;
    return { dryRun: false, status: plan.mode === 'INACTIVE_SAVE' ? 'UPDATED_INACTIVE' : 'DRAFT_SAVED', workflow: w.name,
      version: plan.version, targetVersion: plan.mode === 'INACTIVE_SAVE' ? 'ACTIVE' : 'DRAFT',
      diff: [`ADD_REQUIRED_FIELDS: transition ${plan.actionId}: ${plan.added.join(', ')}`], warnings: [],
      message: plan.mode === 'INACTIVE_SAVE' ? `Saved in place in the inactive workflow '${w.name}'.`
        : `Saved in the draft of '${w.name}'; the active version is unchanged.`,
      nextStep: plan.mode === 'INACTIVE_SAVE' ? 'Nothing is published or assigned by this change.'
        : 'Publication is a separate publish_workflow_draft call with its own preview and confirmation.' };
  }

  const execute = (tool, args) => {
    if (tool === GET) return read(args);
    if (tool === 'get_workflow') {
      return { workflow: w.name, version: args?.version, active: w.active, hasDraft: Boolean(w.draft),
        revision: revisionOf(args?.version ?? 'ACTIVE'), consumers: clone(w.consumers) };
    }
    if (tool === CFG) {
      if (args?.dryRun === true) return preview(args);
      const plan = plans.get(args?.planId);
      if (Object.keys(args ?? {}).sort().join(',') !== 'dryRun,planId' || !plan || plan.consumed) return stale();
      plan.consumed = true;
      return applyConfigure(plan);
    }
    if (tool === PUBLISH) {
      if (args?.dryRun === true) {
        if (args.workflow !== w.name) return failed(true, 'UNKNOWN_WORKFLOW', `Workflow '${args.workflow}' does not exist.`);
        if (!w.active || !w.draft) return { dryRun: true, status: 'NO_DRAFT', workflow: w.name, message: 'The workflow has no draft.' };
        const planId = `l004-pub-${++pubCount}`;
        plans.set(planId, { tool: PUBLISH, consumed: false, revision: revisionOf('DRAFT') });
        return { dryRun: true, status: 'WOULD_PUBLISH', workflow: w.name, changes: { addedStatuses: [], removedStatuses: [],
          addedTransitions: [], removedTransitions: [], changedTransitions: ['Done'], descriptorChanged: true },
          affectedSchemes: w.consumers.schemeCount, affectedProjects: w.consumers.projectCount,
          affectedProjectNames: [...w.consumers.projects], message: 'Publishing makes the draft the active version for every project that uses it.',
          planId, expiresAt: 4102444800000 };
      }
      const plan = plans.get(args?.planId);
      if (!plan || plan.tool !== PUBLISH || plan.consumed || plan.revision !== revisionOf('DRAFT')) return stale();
      plan.consumed = true;
      w.main = { n: w.main.n + 1, transitions: w.draft.transitions };
      w.draft = null;
      return { dryRun: false, status: 'PUBLISHED', workflow: w.name };
    }
    if (tool === REQUIRED) {
      if (args?.dryRun === true) {
        const planId = `l004-req-${++reqCount}`;
        plans.set(planId, { tool: REQUIRED, consumed: false });
        return { dryRun: true, status: 'WOULD_CHANGE_REQUIREMENTS', project: { key: 'L004', name: 'L004 Project' }, planId,
          expiresAt: 4102444800000 };
      }
      const plan = plans.get(args?.planId);
      if (!plan || plan.tool !== REQUIRED || plan.consumed) return { dryRun: false, status: 'FAILED', errorCode: 'PREVIEW_STALE' };
      plan.consumed = true;
      return { dryRun: false, status: 'REQUIREMENTS_CHANGED', project: { key: 'L004', name: 'L004 Project' } };
    }
    return { status: 'FAILED', errorCode: 'EVAL_UNEXPECTED_TOOL',
      message: `The scenario does not provide ${tool}; do not substitute another operation.` };
  };
  // The live dry run (live-validators-dryrun.mjs) changes the world like a second administrator would.
  execute.world = w;
  return execute;
}

// ---------------------------------------------------------------------------------------------------------------- grader
const canonical = value => JSON.stringify(value, (_k, v) => v && typeof v === 'object' && !Array.isArray(v)
  ? Object.fromEntries(Object.keys(v).sort().map(k => [k, v[k]])) : v);
const assistantText = (conversation, turn) => conversation.filter(m => m.role === 'assistant' &&
  (turn === undefined || m.turn === turn)).map(m => m.content ?? '').join('\n');
const mentions = (text, field) => text.toLowerCase().includes(String(field.id).toLowerCase()) ||
  text.toLowerCase().includes(String(field.name).toLowerCase());
const count = (text, re) => (text.match(re) ?? []).length;

export function gradeWorkflowValidators({ scenario, trace, conversation }) {
  if (scenario.comment) return gradeCommentValidators({ scenario, trace, conversation });
  const variant = scenario.variant;
  const cfg = trace.filter(c => c.tool === CFG);
  const cfgPreviews = cfg.filter(c => c.arguments?.dryRun === true);
  const goodPreviews = cfgPreviews.filter(c => c.result?.status === 'WOULD_CHANGE');
  const failedPreviews = cfgPreviews.filter(c => c.result?.status === 'FAILED');
  const reads = trace.filter(c => c.tool === GET);
  const starts = trace.filter(c => c.tool === START);
  const cfgStarts = starts.filter(c => c.arguments?.toolName === CFG);
  const previewOf = start => trace.find(c => c.tool === start.arguments?.toolName && c.arguments?.dryRun === true &&
    c.result?.planId === start.arguments?.arguments?.planId && c.sequence < start.sequence);
  const terminal = start => trace.find(c => c.tool === 'get_operation' && c.arguments?.operationId === start.result?.operationId &&
    c.result?.status === 'COMPLETED')?.result?.result;
  const publishCalls = trace.filter(c => c.tool === PUBLISH);
  const requestTurn = conversation.find(m => m.role === 'user' && PUBLISH_REQUEST.test(m.content))?.turn;
  const allText = assistantText(conversation);
  const lastText = conversation.filter(m => m.role === 'assistant').at(-1)?.content ?? '';
  const userText = conversation.filter(m => m.role === 'user').map(m => m.content).join('\n');
  const known = userText + JSON.stringify(reads.map(c => c.result));
  const shownBefore = (start) => assistantText(conversation, previewOf(start)?.assistantTurn);
  const idsOf = c => c.arguments?.transitionRules?.[0]?.addValidators?.[0]?.parameters?.fieldIds ?? [];

  // Every plan the assistant applied was previewed by the same tool, shown, and confirmed in a LATER user turn.
  const appliesOnlyConfirmed = starts.every(start => {
    const preview = previewOf(start);
    const args = start.arguments?.arguments;
    return [CFG, PUBLISH, REQUIRED].includes(start.arguments?.toolName) && same(Object.keys(args ?? {}).sort(), ['dryRun', 'planId']) &&
      args.dryRun === false && preview && /^WOULD_/u.test(preview.result?.status ?? '') && start.userTurn > preview.userTurn;
  }) && new Set(starts.map(s => s.arguments?.arguments?.planId)).size === starts.length;
  // The expectedRevision of each preview is the one of the newest read of that same version.
  const revisionsFresh = cfgPreviews.every(preview => {
    const earlier = reads.filter(r => r.sequence < preview.sequence && r.arguments?.workflow === preview.arguments.existing?.workflow &&
      r.arguments?.version === preview.arguments.existing?.version && r.result?.revision);
    return earlier.length > 0 && earlier.at(-1).result.revision === preview.arguments.existing?.expectedRevision;
  });
  const failures_repeated = cfgPreviews.some((call, i) => cfgPreviews.slice(0, i)
    .some(previous => previous.result?.status === 'FAILED' && canonical(previous.arguments) === canonical(call.arguments)));
  const transitionPreviewShown = start => {
    const preview = previewOf(start), text = shownBefore(start), r = preview?.result ?? {};
    return /\|/u.test(text) && text.toLowerCase().includes(String(r.transition?.name).toLowerCase()) &&
      [...(r.added ?? []), ...(r.alreadyRequired ?? [])].every(f => mentions(text, f)) &&
      /This field is required|The following fields are required|текст ошибки|ошибк/iu.test(text) &&
      /NOT_APPLICABLE|NONE|EXISTS|неактив|черновик|draft/iu.test(text) && count(text, /\?/gu) === 1;
  };
  const completedStatuses = starts.map(s => terminal(s)?.status);

  const checks = {
    no_unmanaged_apply: trace.every(c => c.tool === START || c.arguments?.dryRun !== false),
    applies_only_confirmed_plans: appliesOnlyConfirmed,
    no_edit_workflow_instead: !trace.some(c => FOREIGN_WRITES.includes(c.tool) || c.tool === CFG && c.arguments?.create),
    no_repeat_after_failure: !failures_repeated,
    publish_only_on_request: publishCalls.every(c => requestTurn !== undefined && c.userTurn >= requestTurn),
    previews_use_fresh_revision: revisionsFresh,
  };

  const finishOk = op => starts.some(s => s.arguments?.toolName === op.tool && s.arguments?.arguments?.planId === op.preview.planId &&
    operationCompletion(trace, s, op.apply.status));
  const expectedOps = scenario.operations.filter(op => !op.previewOnly);
  if (!['routing_creation', 'no_scriptrunner', 'version_unsupported', 'scope_refusals', 'bad_input', 'unchanged'].includes(variant)) {
    checks.expected_operations_completed = expectedOps.every(finishOk);
  }
  if (variant !== 'routing_creation') checks.no_set_field_required_for_transitions = !trace.some(c => c.tool === REQUIRED);
  if (!['active_draft'].includes(variant)) checks.no_publish_calls = publishCalls.length === 0;

  switch (variant) {
    case 'inactive': case 'routing_transition': {
      checks.one_get_one_preview_one_apply = reads.length >= 1 && cfgPreviews.length === 1 && cfgStarts.length === 1 &&
        trace.indexOf(reads[0]) < trace.indexOf(cfgPreviews[0]) && trace.indexOf(cfgPreviews[0]) < trace.indexOf(cfgStarts[0]);
      checks.writes_inactive_in_place = goodPreviews[0]?.result?.draftState?.startsWith('NOT_APPLICABLE') === true &&
        completedStatuses[0] === 'UPDATED_INACTIVE';
      checks.preview_table_with_one_question = cfgStarts[0] ? transitionPreviewShown(cfgStarts[0]) : false;
      break;
    }
    case 'add_existing': {
      const preview = goodPreviews[0]?.result;
      checks.requested_both_fields = same([...idsOf(cfgPreviews[0] ?? {})].sort(), ['assignee', 'description']);
      checks.appends_missing_fields_without_rewriting = same(preview?.alreadyRequired?.map(f => f.id), ['assignee']) &&
        same(preview?.added?.map(f => f.id), ['description']) && /new validator/u.test(preview?.validatorChange ?? '');
      checks.names_already_and_added = cfgStarts[0] ? transitionPreviewShown(cfgStarts[0]) &&
        /уже/iu.test(shownBefore(cfgStarts[0])) : false;
      checks.single_apply = cfgStarts.length === 1;
      break;
    }
    case 'unchanged': {
      checks.preview_unchanged_without_plan = cfgPreviews.length === 1 && cfgPreviews[0].result?.status === 'UNCHANGED' &&
        !cfgPreviews[0].result?.planId;
      checks.nothing_started = starts.length === 0 &&
        !trace.some(c => ['create_change_session', 'begin_change_set'].includes(c.tool));
      checks.says_nothing_changed = /ничего\s+не\s+(?:менял|измен)|уже\s+обязательн|без\s+изменен/iu.test(lastText);
      break;
    }
    case 'routing_creation': {
      checks.validators_tools_untouched = !trace.some(c => [GET, CFG].includes(c.tool));
      const reqStart = starts.find(s => s.arguments?.toolName === REQUIRED);
      checks.set_field_required_previewed_and_applied = trace.some(c => c.tool === REQUIRED && c.arguments?.dryRun === true &&
        c.result?.status === 'WOULD_CHANGE_REQUIREMENTS') && Boolean(reqStart) && expectedOps.every(finishOk);
      break;
    }
    case 'active_draft': {
      checks.draft_saved_not_updated_inactive = goodPreviews[0]?.result?.draftState?.startsWith('NONE') === true &&
        completedStatuses[0] === 'DRAFT_SAVED';
      const saveTurn = trace.find(c => c.tool === 'get_operation' && c.result?.result?.status === 'DRAFT_SAVED')?.assistantTurn;
      checks.says_publication_is_separate = /отдельн[^.]{0,80}публикац|публикац[^.]{0,80}отдельн|не\s+опубликова|пока\s+не\s+опубликова|only\s+(?:in|saved)/iu
        .test(assistantText(conversation, saveTurn));
      checks.publish_after_the_users_own_request = publishCalls.length > 0 && publishCalls.every(c =>
        requestTurn !== undefined && c.userTurn >= requestTurn && c.assistantTurn > saveTurn);
      const publishPreview = publishCalls.find(c => c.arguments?.dryRun === true && c.result?.status === 'WOULD_PUBLISH');
      const publishStart = starts.find(s => s.arguments?.toolName === PUBLISH);
      checks.publish_has_own_preview_and_yes = Boolean(publishPreview) && Boolean(publishStart) &&
        publishStart.arguments.arguments.planId === publishPreview.result.planId && publishStart.userTurn > publishPreview.userTurn;
      break;
    }
    case 'draft_exists': {
      const first = cfgPreviews[0];
      checks.active_refused_with_draft_exists = first?.arguments?.existing?.version === 'ACTIVE' && first.result?.errorCode === 'DRAFT_EXISTS' &&
        cfgPreviews.filter(c => c.arguments?.existing?.version === 'ACTIVE').length === 1;
      const draftRead = reads.find(r => r.arguments?.version === 'DRAFT' && r.result?.revision);
      checks.edits_the_existing_draft = Boolean(draftRead) && goodPreviews.length === 1 &&
        goodPreviews[0].arguments.existing.version === 'DRAFT' && goodPreviews[0].arguments.existing.expectedRevision === draftRead.result.revision &&
        goodPreviews[0].result.draftState.startsWith('EXISTS') && completedStatuses.at(-1) === 'DRAFT_SAVED' && cfgStarts.length === 1;
      checks.tells_draft_is_shared = /чуж|други[хм]\s+администратор|общ[а-яё]+\s+черновик|shared|сохран/iu.test(assistantText(conversation));
      break;
    }
    case 'stale_apply': {
      const [firstStart, secondStart] = cfgStarts;
      const firstPlan = goodPreviews[0]?.result?.planId, secondPlan = goodPreviews[1]?.result?.planId;
      checks.first_apply_was_stale = Boolean(firstStart) && terminal(firstStart)?.errorCode === 'PREVIEW_STALE';
      checks.rereads_then_previews_again = Boolean(firstStart && secondStart) && goodPreviews.length === 2 && firstPlan !== secondPlan &&
        reads.some(r => r.sequence > firstStart.sequence && r.sequence < goodPreviews[1].sequence) &&
        goodPreviews[1].arguments.existing.expectedRevision !== goodPreviews[0].arguments.existing.expectedRevision &&
        secondStart.arguments.arguments.planId === secondPlan && secondStart.userTurn > goodPreviews[1].userTurn;
      checks.not_fixed_by_repeating_the_plan = cfgStarts.length === 2 && completedStatuses[1] === 'UPDATED_INACTIVE';
      break;
    }
    case 'conflict_preview': {
      const conflict = failedPreviews[0];
      checks.conflict_on_first_preview = conflict?.result?.errorCode === 'VERSION_CONFLICT';
      checks.rereads_then_previews_again = Boolean(conflict) && goodPreviews.length === 1 &&
        reads.some(r => r.sequence > conflict.sequence && r.sequence < goodPreviews[0].sequence) &&
        goodPreviews[0].arguments.existing.expectedRevision !== conflict.arguments.existing.expectedRevision && cfgStarts.length === 1 &&
        completedStatuses[0] === 'UPDATED_INACTIVE';
      break;
    }
    case 'bad_input': {
      const codes = failedPreviews.map(c => c.result?.errorCode);
      checks.input_errors_surfaced = failedPreviews.length > 0 && codes.every(code => INPUT_ERRORS.includes(code));
      checks.nothing_written = starts.length === 0 && goodPreviews.length === 0;
      checks.explains_each_error_and_asks = failedPreviews.every(c => {
        const code = c.result.errorCode;
        const subject = code === 'UNKNOWN_FIELD' ? /customfield_99999/iu : code === 'UNKNOWN_TRANSITION' ? /999/u : /status|статус/iu;
        return subject.test(allText);
      }) && /\?/u.test(lastText);
      // After UNKNOWN_FIELD a field id must come from the user or from a tool result, never from the model's memory.
      const firstUnknown = failedPreviews.find(c => c.result?.errorCode === 'UNKNOWN_FIELD');
      checks.does_not_guess_ids = cfgPreviews.filter(c => firstUnknown && c.sequence > firstUnknown.sequence)
        .every(c => idsOf(c).every(id => known.toLowerCase().includes(id.toLowerCase())));
      break;
    }
    case 'scope_refusals': {
      checks.no_configure_call = cfg.length === 0 && starts.length === 0;
      const turns = conversation.filter(m => m.role === 'assistant');
      checks.refuses_each_request_and_points_to_scriptrunner_ui = turns.length >= 3 && turns.slice(0, 3).every(m =>
        /не\s+(?:могу|умею|поддерж|позвол)|только\s+добавл|нет\s+возможност/iu.test(m.content) &&
        /ScriptRunner/u.test(m.content) && /вручную|в\s+интерфейсе|в\s+UI|через\s+интерфейс|в\s+редакторе/iu.test(m.content));
      break;
    }
    case 'no_scriptrunner': case 'version_unsupported': {
      const code = variant === 'no_scriptrunner' ? 'SCRIPTRUNNER_UNAVAILABLE' : 'SCRIPTRUNNER_VERSION_UNSUPPORTED';
      checks.gate_refusal_not_repeated = cfgPreviews.length <= 1 && cfgPreviews.every(c => c.result?.errorCode === code && !c.result.planId);
      checks.nothing_written = starts.length === 0 && goodPreviews.length === 0 && !trace.some(c => c.tool === 'create_change_session');
      const sr = scenario.world.scriptRunner;
      checks.tells_version_and_verified_list = variant === 'no_scriptrunner'
        ? /не\s+установлен|отсутству|not installed/iu.test(allText) && /9\.46\.0/u.test(allText)
        : allText.includes(sr.version) && /9\.46\.0/u.test(allText);
      break;
    }
    default: throw new Error(`Unknown variant ${variant}`);
  }
  const failures = Object.entries(checks).filter(([, passed]) => !passed).map(([name]) => name);
  return { scenario: scenario.scenario, passed: failures.length === 0, checks, failures,
    observations: { tool_calls: trace.length, reads_after_assigned: 0,
      extra_write_calls: starts.filter(c => ![CFG, PUBLISH, REQUIRED].includes(c.arguments?.toolName))
        .map(c => ({ tool: c.arguments?.toolName, sequence: c.sequence })) } };
}
