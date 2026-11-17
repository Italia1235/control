import { isDeepStrictEqual as same } from 'node:util';
import { operationCompletion } from './grade.mjs';
import { normalizeWorkflowResolution, workflowResolutionPreview } from './resolution-mock.mjs';

const NAME = 'Manual QA — loops and resolutions';
const PLAN = 'manual-qa-loops-plan';
const CHOICE = 'Выбираю Fixed → Готово (10000), Duplicate → Дубликат (10002). Имя workflow: Manual QA — loops and resolutions. Это выбор параметров, не разрешение на запись.';
const APPROVAL = 'Да, создай только показанный workflow с полным набором статусов и всеми девятью переходами.';
export const manualQaLoopScenarios = ['workflow_manual_qa_loops', 'workflow_manual_qa_loops_original'];

export function generateManualQaLoopsScenario(original = false) {
  const statuses = [{ name: 'QA Open', category: 'TO_DO' }, { name: 'QA Working', category: 'IN_PROGRESS' },
    { name: 'QA Done', category: 'DONE' }];
  if (!original) statuses.push({ name: 'QA Review', category: 'IN_PROGRESS' }, { name: 'QA Paused', category: 'IN_PROGRESS' });
  const resolutions = [{ id: '10000', name: 'Готово', description: 'Для этой проблемы работа завершена.' },
    { id: '10001', name: 'Не будет выполнено', description: 'Эта проблема не будет выполнена.' },
    { id: '10002', name: 'Дубликат', description: 'Задача идентична существующей.' }];
  const set = value => ({ action: 'SET', value });
  const transitions = ([
    { name: 'Start', from: 'QA Open', to: 'QA Working' },
    { name: 'Repeat work', from: 'QA Working', to: 'QA Working' },
    { name: 'Finish', from: 'QA Working', to: 'QA Done', resolution: set('10000') },
    { name: 'Reject', from: 'QA Working', to: 'QA Done', resolution: set('10002') },
    { name: 'Keep outcome', from: 'QA Done', to: 'QA Done' },
    { name: 'Change outcome', from: 'QA Done', to: 'QA Done', resolution: set('10002') },
    { name: 'Reopen', from: 'QA Done', to: 'QA Open', resolution: { action: 'CLEAR' } },
    { name: 'Global finish', fromAny: true, to: 'QA Done', resolution: set('10000') },
    { name: 'Global reopen', fromAny: true, to: 'QA Open', resolution: { action: 'CLEAR' } },
  ]).map((transition, index) => ({ key: `transition-${index + 1}`, ...transition }))
  const match = { name: NAME, statuses, initialStatus: 'QA Open', transitions };
  const effective = workflowResolutionPreview(match, resolutions);
  return {
    scenario: manualQaLoopScenarios[original ? 1 : 0], kind: 'project_document', validation: 'workflow_manual_qa_loops',
    syntheticUserMode: 'deterministic', resolutionCatalog: resolutions,
    provenance: { source: 'evals/offline/fixtures/manual-qa-loops-request.md', sourceStatuses: 3, sourceTransitions: 9,
      syntheticExtension: !original, extension: original ? null : 'Five-status synthetic extension, not a transcript: QA Review and QA Paused are IN_PROGRESS states with no additional local edges. Both are included as sources of the two existing global transitions.' },
    initialUserMessage: `${original ? 'Создай workflow: 3 статуса и 9 переходов, как в исходном примере.' : 'Синтетическое расширение исходного примера: создай workflow с 5 статусами и 9 переходами. QA Review и QA Paused — дополнительные IN_PROGRESS статусы без новых локальных переходов; глобальные переходы включают их.'}\nСтатусы: ${statuses.map(s => `${s.name} — ${s.category}`).join('; ')}. Начальный QA Open.\nРезолюции: используй существующие Fixed и Duplicate. Сначала проверь каталог. Если их нет, ничего не создавай: покажи доступные и попроси выбрать замену.\nПереходы:\n1. Start: QA Open → QA Working.\n2. Repeat work: QA Working → QA Working.\n3. Finish: QA Working → QA Done, установить Fixed.\n4. Reject: QA Working → QA Done, установить Duplicate.\n5. Keep outcome: QA Done → QA Done, резолюцию сохранить.\n6. Change outcome: QA Done → QA Done, установить Duplicate.\n7. Reopen: QA Done → QA Open, очистить резолюцию.\n8. Global finish: из любого статуса → QA Done, включая QA Done, установить Fixed.\n9. Global reopen: из любого статуса → QA Open, включая QA Open, очистить резолюцию.`,
    harnessInstructions: 'OFFLINE SYNTHETIC EVAL ONLY, not production policy or a supplied transcript. Collect the missing workflow name and Russian catalog replacements together in one decision block before preview. Never create a Resolution. Each visible assistant message has one fenced json card: DECISIONS {workflowNameRequired:true,resolutionChoices:[full catalog entries],proposedReplacements:{Fixed:"10000",Duplicate:"10002"}}; APPROVE_WORKFLOW {planId,workflow:{name,statuses,initialStatus,transitions}} using the real preview graph including effective resolution consequences; WORKFLOW_CREATED {workflow:{name,statuses,initialStatus,transitions}} after terminal success. Explain the choice/consequences in ordinary text and request approval only after preview. SET uses canonical catalog IDs; preserving input edges omit resolution (PRESERVE is not an input action). All nine distinct named local/global transitions, including self-loops, are required. Approved pair policy: distinct normalized names may share a source/target pair; the same normalized name on the same pair/scope is rejected.',
    operations: [{ id: 'workflow', tool: 'configure_workflow', match,
      preview: { dryRun: true, status: 'WOULD_CREATE', ...effective, planId: PLAN },
      apply: { dryRun: false, status: 'CREATED', ...effective } }],
    limits: { turns: 5, assistantCalls: 16, syntheticUserCalls: 0, toolCalls: 20 },
  };
}

export function assertManualQaOfflineConfiguration(env) {
  let url;
  try { url = new URL(env.EVAL_BASE_URL); } catch { /* Rejected below. */ }
  if (env.EVAL_MANUAL_QA_SCRIPTED !== '1' || env.EVAL_OFFLINE_IPC_CHILD !== '1' ||
      env.EVAL_BACKEND !== 'simulated' || env.EVAL_API_KEY !== 'offline-only' ||
      env.EVAL_ASSISTANT_MODEL !== 'offline-manual-qa-loops' || url?.protocol !== 'http:' ||
      url.hostname !== '127.0.0.1' || !url.port || url.username || url.password) {
    throw new Error('Manual QA loops is offline-only: require the offline-network IPC preload, scripted assistant and simulated backend.');
  }
}

// Bounded graph mock: return the submitted graph, never replace missing edges with fixture edges.
export function createManualQaLoopsMock(scenario, { legacyPairs = false } = {}) {
  const plans = new Map();
  let nextPlan = 0;
  const invalid = message => ({ dryRun: true, status: 'FAILED', errorCode: 'VALIDATION_ERROR', message, planId: null });
  const key = name => typeof name === 'string' ? name.trim().toLowerCase() : '';
  return request => {
    const args = request?.dryRun === false ? request : request?.create;
    if (!args) return invalid('configure_workflow requires a create graph.');
    if (args?.dryRun === false) {
      if (Object.keys(args).sort().join(',') !== 'dryRun,planId' || !plans.has(args.planId))
        return { dryRun: false, status: 'FAILED', errorCode: 'PREVIEW_STALE', planId: null };
      const graph = plans.get(args.planId);
      plans.delete(args.planId);
      return { dryRun: false, status: 'CREATED', ...structuredClone(graph) };
    }
    if (request?.dryRun !== true || Object.keys(request).sort().join(',') !== 'create,dryRun' ||
        !key(args.name) || !Array.isArray(args.statuses) || !args.statuses.length ||
        !Array.isArray(args.transitions) || !args.transitions.length ||
        Object.keys(args).some(field => !['name', 'statuses', 'initialStatus', 'transitions'].includes(field)) ||
        args.statuses.some(s => !key(s?.name) || !['TO_DO', 'IN_PROGRESS', 'DONE'].includes(s.category)) ||
        new Set(args.statuses.map(s => key(s.name))).size !== args.statuses.length ||
        !args.statuses.some(s => key(s.name) === key(args.initialStatus))) return invalid('Invalid workflow graph arguments.');
    if (new Set(args.transitions.map(edge => edge.key)).size !== args.transitions.length) return invalid('Duplicate transition key.');
    const pairs = new Set();
    for (const edge of args.transitions) {
      if (!key(edge?.key) || !key(edge?.name) || Object.keys(edge).some(field => !['key', 'name', 'from', 'fromAny', 'to', 'resolution'].includes(field)))
        return invalid('Invalid named transition arguments.');
      const scope = edge.fromAny === true ? ['GLOBAL'] : ['LOCAL', key(edge.from)];
      const pair = JSON.stringify([...scope, key(edge.to), ...(legacyPairs ? [] : [key(edge.name)])]);
      if (pairs.has(pair)) return invalid('Duplicate normalized transition name on the same source/target pair and scope.');
      pairs.add(pair);
    }
    const normalized = normalizeWorkflowResolution(args, scenario.resolutionCatalog);
    if (normalized.status === 'FAILED') return normalized;
    const graph = workflowResolutionPreview(args, scenario.resolutionCatalog);
    nextPlan += 1;
    const planId = PLAN + (nextPlan === 1 ? '' : `-${nextPlan}`);
    plans.set(planId, graph);
    return { dryRun: true, status: 'WOULD_CREATE', ...graph, planId };
  };
}

export function manualQaCard(message) {
  const blocks = [...(message?.content ?? '').matchAll(/```json\s*([\s\S]*?)```/gu)];
  if (blocks.length !== 1) return null;
  try { return JSON.parse(blocks[0][1]); } catch { return null; }
}
export function nextManualQaLoopsUser(_scenario, conversation) {
  const card = manualQaCard(conversation.at(-1));
  if (card?.kind === 'DECISIONS') return CHOICE;
  if (card?.kind === 'APPROVE_WORKFLOW') return APPROVAL;
  return 'Сначала проверь каталог и собери все решения одним блоком; без новых резолюций и без изменения девяти переходов.';
}
const graphOf = result => result && ({ name: result.name, statuses: result.statuses,
  initialStatus: result.initialStatus, transitions: result.transitions });

export function gradeManualQaLoops({ scenario, trace, conversation, modelMessages = [] }) {
  const expected = scenario.operations[0];
  const previews = trace.filter(c => c.tool === 'configure_workflow' && c.arguments?.dryRun === true);
  const successful = previews.filter(c => c.result?.status === 'WOULD_CREATE' && c.result.planId);
  const first = trace.indexOf(successful[0]);
  const validationErrors = (first < 0 ? trace : trace.slice(0, first)).filter(c =>
    /VALIDATION|SCHEMA/iu.test(c.result?.errorCode ?? '') ||
    c.result?.status === 'FAILED' && /validation failed|expected object|resolution\.action/iu.test(c.result?.message ?? ''));
  const starts = trace.filter(c => c.tool === 'start_operation');
  const start = starts[0];
  const preview = successful.find(c => c.result.planId === start?.arguments?.arguments?.planId);
  const completion = operationCompletion(trace, start, 'CREATED');
  const visible = modelMessages.map((message, index) => ({ message, index, card: manualQaCard(message) }))
    .filter(e => e.message.role === 'assistant' && e.message.content?.trim());
  const decisions = visible.filter(e => e.card?.kind === 'DECISIONS');
  const approvals = visible.filter(e => e.card?.kind === 'APPROVE_WORKFLOW');
  const receipt = result => result ? modelMessages.findIndex(m => {
    if (m.role !== 'tool') return false;
    try { return same(JSON.parse(m.content), result); } catch { return false; }
  }) : -1;
  const catalog = trace.find(c => c.tool === 'get_resolutions' && same(c.result, scenario.resolutionCatalog));
  const choices = conversation.filter(m => m.role === 'user' && m.content === CHOICE);
  const confirms = conversation.filter(m => m.role === 'user' && m.content === APPROVAL);
  const choiceIndex = modelMessages.findIndex(m => m.role === 'user' && m.content === CHOICE);
  const confirmationIndex = modelMessages.findIndex(m => m.role === 'user' && m.content === APPROVAL);
  const previewRequestIndex = modelMessages.findIndex(m => m.tool_calls?.some(c => c.function.name === 'configure_workflow'));
  const final = visible.at(-1);
  const graph = graphOf(expected.preview);
  const allowed = new Set(['get_resolutions', 'configure_workflow', 'start_operation', 'get_operation',
    'create_change_session', 'begin_change_set', 'finish_change_set']);
  const checks = {
    zero_validation_errors_before_preview: successful.length > 0 && validationErrors.length === 0,
    no_failed_calls: trace.every(c => c.result?.status !== 'FAILED' && !c.result?.errorCode),
    one_successful_preview: previews.length === 1 && successful.length === 1 && !!preview,
    one_block_decisions: decisions.length === 1 && choices.length === 1 &&
      decisions[0].card.workflowNameRequired === true && same(decisions[0].card.resolutionChoices, scenario.resolutionCatalog) &&
      same(decisions[0].card.proposedReplacements, { Fixed: '10000', Duplicate: '10002' }) &&
      receipt(catalog?.result) >= 0 && decisions[0].index > receipt(catalog?.result) &&
      choiceIndex > decisions[0].index && previewRequestIndex > choiceIndex,
    all_statuses_and_nine_transitions: !!preview && same(graphOf(preview.result), graph) &&
      same(graphOf(completion?.result?.result), graph),
    canonical_resolution_ids_and_omit_preserve: previews.length > 0 && previews.every(c => {
      if (c.result?.status !== 'WOULD_CREATE') return false;
      const normalized = normalizeWorkflowResolution(c.arguments.create, scenario.resolutionCatalog);
      return normalized.status !== 'FAILED' && same(graphOf(normalized), graphOf(expected.match)) &&
        c.arguments.create.transitions.every(edge => {
          const requested = expected.match.transitions.find(item => item.name === edge.name);
          return requested && (Object.hasOwn(requested, 'resolution') || !Object.hasOwn(edge, 'resolution'));
        });
    }),
    visible_preview_and_confirmation: approvals.length === 1 && same(approvals[0].card.workflow, graph) &&
      approvals[0].card.planId === preview?.result?.planId && receipt(preview?.result) >= 0 &&
      approvals[0].index > receipt(preview?.result) && confirmationIndex > approvals[0].index &&
      confirms.length === 1 && confirms[0].turn > preview?.userTurn && confirms[0].turn <= start?.userTurn,
    one_confirmed_write: starts.length === 1 && start.arguments?.toolName === 'configure_workflow' &&
      same(Object.keys(start.arguments?.arguments ?? {}).sort(), ['dryRun', 'planId']) &&
      start.arguments.arguments.dryRun === false && start.arguments.arguments.planId === preview?.result?.planId &&
      start.result?.status === 'QUEUED' && !!completion,
    no_resolution_creation_or_other_writes: trace.every(c => allowed.has(c.tool) &&
      !(c.tool === 'configure_workflow' && c.arguments?.dryRun !== true)),
    final_after_terminal: receipt(completion?.result) >= 0 && final?.index > receipt(completion?.result) &&
      final.index === modelMessages.length - 1 && final.card?.kind === 'WORKFLOW_CREATED' && same(final.card.workflow, graph),
    structured_decisions: visible.length === 3 && visible.every(e => e.card &&
      ['DECISIONS', 'APPROVE_WORKFLOW', 'WORKFLOW_CREATED'].includes(e.card.kind)),
    group_finished: trace.filter(c => c.tool === 'finish_change_set' && c.result?.status === 'FINISHED').length === 1,
  };
  const failures = Object.entries(checks).filter(([, passed]) => !passed).map(([name]) => name);
  return { scenario: scenario.scenario, passed: failures.length === 0, checks, failures,
    observations: { tool_calls: trace.length, reads_after_assigned: 0, extra_write_calls: [],
      validation_errors_before_first_successful_preview: validationErrors.length,
      validation_errors_before_first_successful_preview_goal: 0,
      no_successful_preview: successful.length === 0, decision_blocks: decisions.length,
      source_statuses: 3, fixture_statuses: expected.match.statuses.length, fixture_transitions: 9,
      synthetic_extension: scenario.provenance.syntheticExtension,
      presentation_protocol: 'Structured decision cards; trace grading does not prove free-text semantics.' } };
}
