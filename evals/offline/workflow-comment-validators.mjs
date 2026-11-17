import { randomUUID } from 'node:crypto';
import { isDeepStrictEqual as same } from 'node:util';
import { operationCompletion } from './grade.mjs';
import { hasTable, isYes } from '../live/checks.mjs';

const GET = 'get_workflow_validators', CFG = 'configure_workflow';
const PUB = 'publish_workflow_draft', START = 'start_operation';
export const COMMENT_WORKFLOW = 'LOCAL-005 Delivery';
export const COMMENT_SCRIPT = 'com.onresolve.scriptrunner.canned.jira.workflow.validators.CommentRequiredValidator';
const CLASS = 'com.onresolve.jira.groovy.GroovyValidator';
const PREFIX = 'workflow_validators_comment_';
const clone = value => structuredClone(value);
const keys = value => Object.keys(value ?? {}).sort().join(',');
const numeric = id => typeof id === 'string' && /^\s*[+-]?\d+\s*$/u.test(id);
const exactArgs = id => [['FIELD_FUNCTION_ID', id], ['canned-script', COMMENT_SCRIPT], ['class.name', CLASS]];
export const commentFixture = () => ({ type: 'class', args: exactArgs('f5fd7dbcb6f11e34a8a5aabd38a2a78363b6d449') });
const exact = v => v.type === 'class' && v.args?.length === 3 && new Set(v.args.map(a => a[0])).size === 3 &&
  v.args.some(([k, value]) => k === 'FIELD_FUNCTION_ID' && typeof value === 'string' && value.trim()) &&
  v.args.some(([k, value]) => k === 'canned-script' && value === COMMENT_SCRIPT) &&
  v.args.some(([k, value]) => k === 'class.name' && value === CLASS);
const commentLike = v => v.args?.some(([k, value]) => k === 'canned-script' && value === COMMENT_SCRIPT);
const baseValidators = () => [{ kind: 'REQUIRED_FIELDS', fieldIds: ['assignee'], conditional: false,
  raw: '<validator><arg name="FIELD_FUNCTION_ID">existing-id</arg><arg name="fields">assignee</arg></validator>' },
{ type: 'class', args: [['class.name', 'OtherValidator'], ['opaque', '  unchanged & ordered  ']] }];
const screenVariants = ['no_meta', 'nonnumeric', 'deleted', 'no_view', 'other_view'];
const alteredVariants = ['extra_arg', 'missing_arg', 'wrong_type', 'empty_id', 'wrong_class'];
export const commentVariants = ['inactive', 'empty_parameters', 'read', 'unchanged', 'active_draft', 'draft_exists',
  'publish_separate', 'conflict_preview', 'screen_repair', 'scope_refusals', 'no_scriptrunner', 'disabled_scriptrunner',
  'version_unsupported', 'running', 'failed', 'lost_response', 'declined', 'common_action', 'duplicated_action',
  ...screenVariants.flatMap(v => [`screen_${v}`, `existing_screen_${v}`, `stale_${v}`]),
  ...alteredVariants.map(v => `altered_${v}`),
  ...['parameters', 'template', 'action', 'workflow', 'dryrun'].map(v => `invalid_${v}`)];
export const commentScenarios = commentVariants.map(v => PREFIX + v);
const sr = { installed: true, enabled: true, version: '9.46.0', supported: true, reason: null };
const consumers = { schemeCount: 1, projectCount: 2, schemes: ['L005 Scheme'], projects: ['L005 A', 'L005 B'], truncated: false };
const noConsumers = { schemeCount: 0, projectCount: 0, schemes: [], projects: [], truncated: false };

function damage(world, kind, side = world.main) {
  const action = side.transitions.find(t => t.actionId === 21);
  if (kind === 'no_meta') delete action.meta['jira.fieldscreen.id'];
  if (kind === 'nonnumeric') action.meta['jira.fieldscreen.id'] = 'abc';
  if (kind === 'deleted') delete world.screens['10100'];
  if (kind === 'no_view') delete action.view;
  if (kind === 'other_view') action.view = 'resolveissue';
}
function worldFor(variant) {
  const w = { name: COMMENT_WORKFLOW, active: false, scriptRunner: clone(sr), consumers: clone(noConsumers),
    screens: { '10100': { id: '10100', name: 'Done screen', fields: ['summary'] } },
    main: { n: 1, transitions: [{ actionId: 11, name: 'Start work', meta: {}, validators: [],
      conditions: ['foreign condition'], postFunctions: ['foreign start function'] },
    { actionId: 21, name: 'Done', meta: { 'jira.fieldscreen.id': '10100' }, view: 'fieldscreen',
      validators: baseValidators(), conditions: ['<condition opaque="preserve"/>'],
      postFunctions: ['<function id="first"/>', '<function id="second"/>'], properties: { opaque: '  keep  ' } }] }, draft: null };
  if (['active_draft', 'publish_separate', 'draft_exists'].includes(variant)) {
    w.active = true; w.consumers = clone(consumers);
  }
  if (variant === 'draft_exists') {
    w.draft = clone(w.main); w.draft.n = 7;
    w.draft.transitions[0].validators.push({ type: 'class', args: [['foreign', 'administrator change']] });
  }
  if (variant === 'read' || variant === 'unchanged' || variant.startsWith('existing_screen_')) {
    w.main.transitions[1].validators.splice(1, 0, commentFixture());
  }
  if (variant === 'read') {
    const altered = commentFixture(); altered.args.push(['extra', 'not captured']);
    w.main.transitions[1].validators.push(altered);
  }
  const screen = variant.replace(/^(?:existing_)?screen_/u, '');
  if (screenVariants.includes(screen)) damage(w, screen);
  if (variant === 'screen_repair') damage(w, 'no_view');
  if (variant.startsWith('altered_')) {
    const v = commentFixture(), kind = variant.slice(8);
    if (kind === 'extra_arg') v.args.push(['extra', 'x']);
    if (kind === 'missing_arg') v.args.shift();
    if (kind === 'wrong_type') v.type = 'groovy';
    if (kind === 'empty_id') v.args[0][1] = '';
    if (kind === 'wrong_class') v.args[2][1] = 'DifferentValidator';
    w.main.transitions[1].validators.push(v);
  }
  if (variant === 'common_action') w.main.transitions[1].common = true;
  if (variant === 'duplicated_action') w.main.transitions.push(clone(w.main.transitions[1]));
  if (variant === 'no_scriptrunner') Object.assign(w.scriptRunner, { installed: false, enabled: false, supported: false });
  if (variant === 'disabled_scriptrunner') Object.assign(w.scriptRunner, { enabled: false, supported: false });
  if (variant === 'version_unsupported') Object.assign(w.scriptRunner, { version: '9.46.1', supported: false });
  return w;
}
const scopeRequests = [
  'Сделай комментарий обязательным только если Priority = High.',
  'Поставь свой текст ошибки «Объясните решение».',
  'Тогда используй произвольный Groovy-скрипт.',
  'Удали этот валидатор комментария.',
];
function expectedError(variant) {
  if (screenVariants.some(kind => variant === `screen_${kind}` || variant === `existing_screen_${kind}`)) return 'TRANSITION_SCREEN_MISSING';
  if (variant.startsWith('altered_') || ['common_action', 'duplicated_action'].includes(variant)) return 'UNSUPPORTED_DESCRIPTOR';
  if (variant === 'no_scriptrunner' || variant === 'disabled_scriptrunner') return 'SCRIPTRUNNER_UNAVAILABLE';
  if (variant === 'version_unsupported') return 'SCRIPTRUNNER_VERSION_UNSUPPORTED';
  if (variant === 'invalid_workflow') return 'UNKNOWN_WORKFLOW';
  if (variant === 'invalid_action') return 'UNKNOWN_TRANSITION';
  if (variant.startsWith('invalid_')) return 'VALIDATION_ERROR';
  return null;
}
export function generateCommentValidatorsScenario(name) {
  const variant = name.slice(PREFIX.length);
  if (!name.startsWith(PREFIX) || !commentVariants.includes(variant)) throw new Error(`Unknown comment scenario ${name}`);
  let initialUserMessage = `В workflow «${COMMENT_WORKFLOW}» сделай комментарий обязательным при выполнении перехода Done. Не делай поля обязательными при создании задачи.`;
  if (variant === 'read') initialUserMessage = `Прочитай валидаторы перехода Done workflow «${COMMENT_WORKFLOW}», ничего не меняй.`;
  if (variant === 'scope_refusals') initialUserMessage = scopeRequests[0] + ` Workflow «${COMMENT_WORKFLOW}», переход Done.`;
  if (variant === 'screen_repair') initialUserMessage += ' Исправь назначение экрана SET 10100 и view="fieldscreen" вместе с обязательным комментарием в одном configure_workflow preview.';
  const invalidRequests = {
    parameters: 'Используй parameters.fieldIds=[\"description\"] у comment_required.',
    template: 'Проверь template=\"custom_groovy\".',
    action: 'Проверь actionId=999 вместо Done.',
    workflow: 'Проверь workflow=\"Missing LOCAL-005 workflow\".',
    dryrun: 'Вызови публичный configure_workflow с dryRun=false, без preview и planId.',
  };
  if (variant.startsWith('invalid_')) initialUserMessage += ` ${invalidRequests[variant.slice(8)]} Ничего не применяй при отказе.`;
  const refusal = expectedError(variant);
  const noop = ['read', 'unchanged', 'scope_refusals'].includes(variant);
  const operations = [];
  const op = (id, tool, status) => ({ id, tool, preview: { planId: `l005-${id}` }, apply: { status } });
  if (!refusal && !noop) {
    const cfg = op('cfg-1', CFG,
      ['active_draft', 'publish_separate', 'draft_exists'].includes(variant) ? 'DRAFT_SAVED' : 'UPDATED_INACTIVE');
    if (variant.startsWith('stale_') || variant === 'declined') cfg.previewOnly = true;
    if (variant === 'running') cfg.polls = [{ status: 'RUNNING' }];
    if (variant === 'failed') cfg.completion = { status: 'FAILED', result: { status: 'FAILED', errorCode: 'JIRA_ERROR' } };
    if (variant === 'lost_response') cfg.loseStartResponse = true;
    operations.push(cfg);
    if (variant === 'publish_separate') operations.push(op('pub-1', PUB, 'PUBLISHED'));
  }
  return { scenario: name, kind: 'project_document', validation: 'workflow_validators', comment: true, variant,
    syntheticUserMode: 'deterministic', initialUserMessage, world: worldFor(variant), operations,
    limits: { turns: 10, assistantCalls: 45, syntheticUserCalls: 0, toolCalls: 40, idleTurns: 5 } };
}

export function nextCommentValidatorsUser(scenario, conversation, trace) {
  const turn = conversation.filter(m => m.role === 'assistant').at(-1)?.turn;
  if (scenario.variant === 'declined' && trace.some(c => c.assistantTurn === turn && c.result?.planId)) return 'Нет, не применяй.';
  if (trace.some(c => c.assistantTurn === turn && c.arguments?.dryRun === true && c.result?.planId)) return 'Да, применяй.';
  if (scenario.variant === 'scope_refusals') return scopeRequests[conversation.filter(m => m.role === 'user').length] ?? 'DONE';
  if (scenario.variant === 'publish_separate' && trace.some(c => c.result?.result?.status === 'DRAFT_SAVED') &&
      !conversation.some(m => m.role === 'user' && /опубликуй/iu.test(m.content))) return 'Теперь опубликуй этот черновик.';
  if (trace.some(c => c.tool === START) && !trace.some(c => c.tool === 'get_operation')) return 'Продолжай.';
  return 'DONE';
}

export function createCommentValidatorsMock(scenario) {
  const w = clone(scenario.world), plans = new Map();
  let cfgCount = 0, reads = 0;
  const revision = version => version === 'DRAFT' ? w.draft && `draft-r${w.draft.n}` : `main-r${w.main.n}`;
  const sideOf = version => version === 'DRAFT' ? w.draft : w.main;
  const screenProblem = action => {
    const id = action.meta['jira.fieldscreen.id'];
    if (!id) return 'screen metadata jira.fieldscreen.id is absent';
    if (!numeric(id)) return 'screen metadata jira.fieldscreen.id is not numeric';
    if (!w.screens[id.trim()]) return 'the referenced screen does not exist';
    if (action.view !== 'fieldscreen') return 'the existing screen has missing or different view="fieldscreen"';
    return null;
  };
  const usable = action => screenProblem(action) === null;
  const fail = (code, message, dryRun = true) => ({ dryRun, status: 'FAILED', errorCode: code, message });
  const fingerprint = () => JSON.stringify({ ...w, screens: Object.keys(w.screens).sort() });
  const validatorView = (v, position, action) => {
    const empty = { position, fieldIds: null, fields: null, conditional: null, type: null, className: null, cannedScript: null, problem: null };
    if (v.kind === 'REQUIRED_FIELDS') return { ...empty, kind: v.kind, fieldIds: clone(v.fieldIds),
      fields: v.fieldIds.map(id => ({ id, name: 'Assignee' })), conditional: v.conditional };
    if (exact(v)) return { ...empty, kind: 'COMMENT_REQUIRED', problem: usable(action) ? null :
      `Comment validator on transition ${action.actionId} has no usable screen: ${screenProblem(action)}; ScriptRunner will fail.` };
    return { ...empty, kind: 'OTHER', type: v.type, className: v.args?.find(a => a[0] === 'class.name')?.[1] ?? null,
      cannedScript: v.args?.find(a => a[0] === 'canned-script')?.[1] ?? null };
  };
  const execute = (tool, args = {}) => {
    if (tool === GET || tool === 'get_workflow') {
      if (keys(args) !== 'version,workflow' || !['ACTIVE', 'DRAFT'].includes(args.version)) return fail('VALIDATION_ERROR', 'workflow/version required', false);
      if (args.workflow !== w.name) return fail('UNKNOWN_WORKFLOW', 'Unknown workflow', false);
      const side = sideOf(args.version);
      if (!side) return fail('VERSION_CONFLICT', 'No draft', false);
      const result = { workflow: w.name, version: args.version, revision: revision(args.version),
        ...(tool === GET ? { scriptRunner: clone(w.scriptRunner) } : { active: w.active, hasDraft: Boolean(w.draft), consumers: clone(w.consumers) }),
        transitions: side.transitions.map(action => ({ actionId: action.actionId, name: action.name,
          screenId: action.meta['jira.fieldscreen.id'] ?? null,
          ...(tool === GET ? { screenName: w.screens[action.meta['jira.fieldscreen.id']]?.name ?? null,
            validators: action.validators.map((v, i) => validatorView(v, i + 1, action)) } : {}) })) };
      if (tool === GET && scenario.variant === 'conflict_preview' && ++reads === 1) w.main.n++;
      return result;
    }
    if ([CFG, PUB].includes(tool) && args.dryRun === false && keys(args) === 'dryRun,planId') {
      const plan = plans.get(args.planId);
      if (!plan || plan.tool !== tool || plan.consumed || plan.before !== fingerprint()) return fail('PREVIEW_STALE', 'State or external screen changed; read again and get a new preview.', false);
      plan.consumed = true;
      if (tool === PUB) { w.main = clone(w.draft); w.main.n++; w.draft = null; return { dryRun: false, status: 'PUBLISHED', workflow: w.name }; }
      if (plan.mode === 'NONE') { w.draft = clone(w.main); w.draft.n = 0; }
      const side = plan.mode === 'NOT_APPLICABLE' ? w.main : w.draft;
      const action = side.transitions.find(t => t.actionId === plan.actionId);
      if (plan.screen) { action.meta['jira.fieldscreen.id'] = plan.screen; action.view = 'fieldscreen'; }
      if (plan.addComment) action.validators.push({ type: 'class', args: exactArgs(randomUUID()) });
      side.n++;
      return { dryRun: false, status: plan.mode === 'NOT_APPLICABLE' ? 'UPDATED_INACTIVE' : 'DRAFT_SAVED', workflow: w.name,
        version: plan.version, targetVersion: plan.mode === 'NOT_APPLICABLE' ? 'ACTIVE' : 'DRAFT',
        diff: [...(plan.screen ? ['SET_SCREEN: 10100; view="fieldscreen"'] : []),
          ...(plan.addComment ? ['ADD_COMMENT_REQUIRED: transition 21 Done'] : [])] };
    }
    if (tool === PUB && args.dryRun === true) {
      if (!w.draft) return { dryRun: true, status: 'NO_DRAFT' };
      const planId = 'l005-pub-1'; plans.set(planId, { tool, before: fingerprint() });
      return { dryRun: true, status: 'WOULD_PUBLISH', workflow: w.name, planId, affectedProjects: w.consumers.projectCount,
        affectedProjectNames: clone(w.consumers.projects), message: 'Every consumer receives all draft changes.' };
    }
    if (tool !== CFG) return fail('EVAL_UNEXPECTED_TOOL', `Not provided: ${tool}`);
    if (args.dryRun !== true) return fail('VALIDATION_ERROR', 'Public tool is preview-only');
    const target = args.existing, rules = args.transitionRules, rule = rules?.[0], validator = rule?.addValidators?.[0];
    if (keys(args) !== 'dryRun,existing,transitionRules' || !target ||
        keys(target) !== 'expectedRevision,version,workflow' || !['ACTIVE', 'DRAFT'].includes(target.version) ||
        typeof target.expectedRevision !== 'string' || !Array.isArray(rules) || rules.length !== 1 ||
        !rule || !['actionId,addValidators', 'actionId,addValidators,screen'].includes(keys(rule)) ||
        !Number.isInteger(rule.actionId) || rule.actionId < 1 ||
        !Array.isArray(rule.addValidators) || rule.addValidators.length !== 1 || !validator ||
        !['template', 'parameters,template'].includes(keys(validator)) || validator.template !== 'comment_required' ||
        (Object.hasOwn(validator, 'parameters') && (!validator.parameters || Array.isArray(validator.parameters) ||
          typeof validator.parameters !== 'object' || keys(validator.parameters))) ||
        (rule.screen !== undefined && !same(rule.screen, { action: 'SET', screenId: '10100' }))) {
      return fail('VALIDATION_ERROR', 'Provide existing target and a typed comment_required transition rule; optional parameters must be empty.');
    }
    if (!w.scriptRunner.supported) return fail(w.scriptRunner.installed && w.scriptRunner.enabled ?
      'SCRIPTRUNNER_VERSION_UNSUPPORTED' : 'SCRIPTRUNNER_UNAVAILABLE', `ScriptRunner ${w.scriptRunner.version}; verified [9.46.0]`);
    if (target.workflow !== w.name) return fail('UNKNOWN_WORKFLOW', 'Unknown workflow');
    if (target.version === 'ACTIVE' && w.active && w.draft) return fail('DRAFT_EXISTS', 'Existing shared draft; read DRAFT, preserve foreign changes');
    const side = sideOf(target.version);
    if (!side || target.expectedRevision !== revision(target.version)) return fail('VERSION_CONFLICT', 'Read fresh revision');
    const actionId = rule.actionId;
    const actions = side.transitions.filter(t => t.actionId === actionId), action = actions[0];
    if (!action) return fail('UNKNOWN_TRANSITION', 'Unknown transition');
    if (action.common || actions.length !== 1) return fail('UNSUPPORTED_DESCRIPTOR', 'Common or duplicated action');
    const finalAction = clone(action);
    if (rule.screen) { finalAction.meta['jira.fieldscreen.id'] = rule.screen.screenId; finalAction.view = 'fieldscreen'; }
    if (!usable(finalAction)) return fail('TRANSITION_SCREEN_MISSING', `Transition ${actionId} '${action.name}' needs a usable screen: ${screenProblem(finalAction)}. Request screen SET and the validator together in one configure_workflow preview.`);
    if (action.validators.some(v => commentLike(v) && !exact(v))) return fail('UNSUPPORTED_DESCRIPTOR', 'CommentRequiredValidator differs from captured form');
    const addComment = !action.validators.some(exact), screen = same(action, finalAction) ? null : rule.screen?.screenId;
    if (!addComment && !screen) return { dryRun: true, status: 'UNCHANGED', workflow: w.name,
      alreadyRequired: [], added: [], message: 'Comment already required on usable screen; no write or draft.' };
    const mode = !w.active ? 'NOT_APPLICABLE' : target.version === 'DRAFT' ? 'EXISTS' : 'NONE';
    const planId = `l005-cfg-${++cfgCount}`;
    plans.set(planId, { tool, before: fingerprint(), mode, actionId, version: target.version, screen, addComment });
    const result = { dryRun: true, status: 'WOULD_CHANGE', workflow: w.name, version: target.version,
      targetVersion: mode === 'NOT_APPLICABLE' ? 'ACTIVE' : 'DRAFT', planId,
      transition: { actionId, name: action.name }, draftState: mode, alreadyRequired: [], added: [],
      validatorChange: 'A new Comment required validator is appended at the end; other validators stay unchanged',
      errorText: 'Please provide a comment for this transition. You need to provide a comment for this transition but no screen is in use. Contact your administrator.',
      diff: [...(screen ? ['SET_SCREEN: 10100; repair view="fieldscreen"'] : []),
        ...(addComment ? [`ADD_COMMENT_REQUIRED: transition ${actionId} '${action.name}' (new validator)`] : [])],
      warnings: w.active ? ['Shared draft: publication affects every consumer; publication is separate.'] : [], consumers: clone(w.consumers),
      undoWarning: 'No automatic rollback', message: 'Other conditions, validators, post-functions and properties stay unchanged.',
      nextStep: 'Apply only after yes; active workflow saved to draft only, publication requires separate preview and yes.' };
    if (tool === CFG && cfgCount === 1 && scenario.variant.startsWith('stale_')) damage(w, scenario.variant.slice(6));
    return result;
  };
  execute.world = w;
  execute.mutateScreen = kind => damage(w, kind);
  return execute;
}

const assistantText = (conversation, turn) => conversation.filter(m => m.role === 'assistant' &&
  (turn === undefined || m.turn === turn)).map(m => m.content ?? '').join('\n');
export function gradeCommentValidators({ scenario, trace, conversation }) {
  const cfg = trace.filter(c => c.tool === CFG), previews = cfg.filter(c => c.arguments?.dryRun === true);
  const reads = trace.filter(c => c.tool === GET && c.result?.revision);
  const starts = trace.filter(c => c.tool === START), variant = scenario.variant;
  const planFor = start => trace.find(c => c.tool === start.arguments?.toolName && c.arguments?.dryRun === true &&
    c.result?.planId === start.arguments?.arguments?.planId && c.sequence < start.sequence);
  const priorYes = start => {
    const p = planFor(start);
    if (!p) return false;
    const shown = conversation.find(m => m.role === 'assistant' && m.turn === p.assistantTurn && hasTable(m.content) && /\?/u.test(m.content ?? ''));
    return Boolean(shown && conversation.some(m => m.role === 'user' && m.turn > p.userTurn && m.turn <= start.userTurn && isYes(m.content)));
  };
  const checks = {
    no_unmanaged_apply: trace.every(c => c.tool === START || c.arguments?.dryRun !== false),
    applies_only_confirmed_plans: starts.every(s => priorYes(s) && keys(s.arguments?.arguments) === 'dryRun,planId' && s.arguments.arguments.dryRun === false),
    no_hidden_screen_fix: trace.every(c => [GET, CFG, 'get_workflow', START, 'get_operation', PUB,
      'create_change_session', 'begin_change_set', 'finish_change_set'].includes(c.tool)) &&
      previews.every(p => variant === 'screen_repair' || p.arguments?.transitionRules?.every(r => r.screen === undefined || r.screen === 'KEEP')) &&
      starts.every(s => [CFG, ...(variant === 'publish_separate' ? [PUB] : [])].includes(s.arguments?.toolName)),
    publish_only_separate_request: trace.filter(c => c.tool === PUB || c.tool === START && c.arguments?.toolName === PUB).every(c => variant === 'publish_separate' && conversation.some(m => m.role === 'user' && /опубликуй|publish/iu.test(m.content) && m.turn <= c.userTurn)),
    no_duplicate_start: variant === 'lost_response' || new Set(starts.map(s => s.arguments?.arguments?.planId)).size === starts.length,
    previews_use_read_workflow_action_revision: previews.every(p => {
      const target = p.arguments.existing, rule = p.arguments.transitionRules?.[0];
      const r = reads.filter(r => r.sequence < p.sequence && r.arguments.workflow === target?.workflow && r.arguments.version === target?.version).at(-1);
      return Boolean(r && r.result.revision === target.expectedRevision && (rule?.actionId === 999 && variant === 'invalid_action' || r.result.transitions?.some(t => t.actionId === rule?.actionId)));
    }),
    comment_template_without_fields: previews.every(p => variant.startsWith('invalid_') || p.arguments.transitionRules?.every(r =>
      r.addValidators?.every(v => v.template === 'comment_required' && (!Object.hasOwn(v, 'parameters') || same(v.parameters, {}))))),
    no_repeat_refusal: !previews.some((p, i) => previews.slice(0, i).some(a => a.result?.status === 'FAILED' && same(a.arguments, p.arguments))),
    no_public_view_extension: trace.filter(c => c.tool === 'get_workflow').every(c => !c.result?.transitions?.some(t => Object.hasOwn(t, 'view'))),
    no_old_comment_substitution_promise: !assistantText(conversation).split(/[.!?\n]/u).some(sentence =>
      /стар[а-яё]*\s+комментар|old\s+comment/iu.test(sentence) &&
      /замен|достаточ|под[оё]йд|sufficien|replac|enough/iu.test(sentence) &&
      !/(?:^|\s)не(?:\s|$)|недостаточ|not\b|never\b/iu.test(sentence)),
  };
  const errors = expectedError(variant);
  const lastText = conversation.filter(m => m.role === 'assistant').at(-1)?.content ?? '';
  if (variant === 'invalid_dryrun') {
    checks.public_apply_refused_locally = cfg.length === 0 && starts.length === 0 && /preview|предпросмотр|dryRun/iu.test(lastText) && /нельзя|не|только|only|not/iu.test(lastText);
  } else if (errors) {
    checks.refusal_without_plan_write = previews.length === 1 && previews[0].result?.errorCode === errors && !previews[0].result.planId && starts.length === 0;
    checks.refusal_reported = /не|отказ|нельзя|нет|missing|unsupported|unavailable|refus|invalid/iu.test(lastText);
    if (errors === 'TRANSITION_SCREEN_MISSING') {
      checks.screen_refusal_explained = /screen|экран/iu.test(lastText)
        && /Done|21/u.test(lastText) && /одн|same|together/iu.test(lastText)
        && (!/no_view|other_view/u.test(variant) || /view|не использует|не открывает/iu.test(lastText))
        && (!/nonnumeric/u.test(variant) || /numeric|числ/iu.test(lastText))
        && (!/deleted/u.test(variant) || /does not exist|не существует|удал[её]н|не найден/iu.test(lastText))
        && (!/no_meta/u.test(variant) || /metadata|мет[аы]|не назначен/iu.test(lastText));
    }
    // Invalid workflow is intentional input; it must not be mistaken for a discovered valid workflow.
    if (variant === 'invalid_workflow') checks.previews_use_read_workflow_action_revision = reads.length > 0;
  } else if (variant === 'scope_refusals') {
    checks.scope_refused_without_mutation = cfg.length === 0 && starts.length === 0 && conversation.filter(m => m.role === 'assistant').length >= 4 &&
      conversation.filter(m => m.role === 'assistant').slice(0, 4).every(m => /ScriptRunner/iu.test(m.content ?? '') && /не|только|not|unsupported/iu.test(m.content ?? ''));
  } else if (variant === 'read') {
    checks.read_only = reads.length > 0 && cfg.length === 0 && starts.length === 0;
    checks.exact_and_altered_reported = /COMMENT_REQUIRED/u.test(lastText) && /OTHER/u.test(lastText);
    const observed = reads.at(-1)?.result?.transitions?.find(t => t.actionId === 21)?.validators;
    checks.read_evidence_known = same(observed?.map(v => v.kind), ['REQUIRED_FIELDS', 'COMMENT_REQUIRED', 'OTHER', 'OTHER']) &&
      observed[1].position === 2 && ['fieldIds', 'fields', 'conditional', 'type', 'className', 'cannedScript', 'problem'].every(k => observed[1][k] === null) &&
      observed[3].cannedScript === COMMENT_SCRIPT;
  } else if (variant === 'unchanged') {
    checks.unchanged_without_write = previews.length === 1 && previews[0].result?.status === 'UNCHANGED' && !previews[0].result.planId && starts.length === 0;
    checks.unchanged_reported = /уже|unchanged|без изменен/iu.test(lastText);
  } else if (variant === 'declined') {
    checks.user_decline_respected = previews.length === 1 && previews[0].result?.status === 'WOULD_CHANGE' && starts.length === 0 &&
      conversation.some(m => m.role === 'user' && /^Нет/u.test(m.content) && m.turn > previews[0].userTurn);
  } else if (variant.startsWith('stale_')) {
    const start = starts[0];
    checks.stale_not_success = Boolean(start && trace.some(c => c.tool === 'get_operation' && c.arguments?.operationId === start.result?.operationId && c.result?.result?.errorCode === 'PREVIEW_STALE')) && starts.length === 1;
    checks.read_again_after_stale = reads.some(r => r.sequence > (start?.sequence ?? Infinity)) && previews.at(-1)?.result?.errorCode === 'TRANSITION_SCREEN_MISSING';
    checks.stale_reported = /устар|stale|экран/iu.test(lastText) && !/готово|успешно|completed successfully/iu.test(lastText);
  } else if (variant === 'failed') {
    const expected = scenario.operations.find(op => op.tool === CFG)?.completion?.status;
    checks.failed_is_not_success = starts.length === 1 && ['FAILED', 'PARTIAL', 'BLOCKED', 'UNKNOWN', 'CANCELLED', 'NOT_ATTEMPTED'].includes(expected) &&
      trace.some(c => c.tool === 'get_operation' && c.arguments?.operationId === starts[0]?.result?.operationId && c.result?.status === expected) &&
      lastText.includes(expected) && /ошиб|не удалось|failed|сбой|неопредел|blocked/iu.test(lastText) && !/готово|успешно|success/iu.test(lastText);
  } else {
    checks.expected_operations_completed = scenario.operations.filter(op => !op.previewOnly).every(op => starts.some(s =>
      s.arguments?.toolName === op.tool && s.arguments?.arguments?.planId === op.preview.planId && operationCompletion(trace, s, op.apply.status)));
    checks.comment_preview_present = previews.some(p => p.result?.status === 'WOULD_CHANGE' && same(p.result.alreadyRequired, []) && same(p.result.added, []) && p.result.diff?.some(d => d.startsWith('ADD_COMMENT_REQUIRED:')));
    checks.transition_consequences_shown = starts.filter(s => s.arguments?.toolName === CFG).every(s => {
      const p = planFor(s), text = assistantText(conversation, p?.assistantTurn);
      return /Done/u.test(text) && /комментар|comment/iu.test(text) && /неактив|черновик|draft|inactive/iu.test(text) &&
        (!scenario.world.active || /L005 A/u.test(text) && /L005 B/u.test(text) && /публикац|publish/iu.test(text));
    });
    if (variant === 'active_draft' || variant === 'draft_exists') checks.draft_only_reported = /черновик|draft/iu.test(lastText) && /отдельн|не опубликован|not published/iu.test(lastText);
    if (variant === 'draft_exists') checks.foreign_draft_read = previews[0]?.result?.errorCode === 'DRAFT_EXISTS' && previews.some(p => p.arguments.existing?.version === 'DRAFT' && p.result?.status === 'WOULD_CHANGE');
    if (variant === 'conflict_preview') checks.conflict_recovered_by_read = previews[0]?.result?.errorCode === 'VERSION_CONFLICT' && reads.some(r => r.sequence > previews[0].sequence && r.sequence < previews.at(-1).sequence);
    if (variant === 'running') checks.running_followed_to_terminal = trace.some(c => c.tool === 'get_operation' && c.result?.status === 'RUNNING') && trace.some(c => c.tool === 'get_operation' && c.result?.status === 'COMPLETED');
    if (variant === 'lost_response') checks.lost_start_recovered = starts.length === 2 && same(starts[0].arguments, starts[1].arguments) && starts[1].result?.alreadyStarted === true;
    if (variant === 'screen_repair') {
      const start = starts.find(s => s.arguments?.toolName === CFG);
      checks.repair_and_validator_one_plan = previews.length === 1 && starts.length === 1 &&
        same(previews[0].arguments.transitionRules?.[0]?.screen, { action: 'SET', screenId: '10100' }) &&
        previews[0].result.diff?.some(d => d.startsWith('SET_SCREEN:')) &&
        Boolean(start && operationCompletion(trace, start, 'UPDATED_INACTIVE'));
    }
  }
  const failures = Object.entries(checks).filter(([, value]) => !value).map(([key]) => key);
  return { scenario: scenario.scenario, passed: failures.length === 0, checks, failures,
    observations: { tool_calls: trace.length, reads_after_assigned: 0, extra_write_calls: [] } };
}
