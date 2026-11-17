import { operationCompletion } from './grade.mjs';
import { protocolCard, protocolEvents, protocolMetrics, operationForCall, same } from './dialogue-protocol-trace.mjs';

export const protocolVariants = ['complete', 'incomplete', 'finding_within', 'finding_outside'];
const reference = { from: 'screen', field: 'screenId' };
export function assertProtocolOfflineConfiguration(env) {
  let url;
  try { url = new URL(env.EVAL_BASE_URL); } catch { /* Rejected below before any transport call. */ }
  if (env.EVAL_DIALOGUE_PROTOCOL_SCRIPTED !== '1' || env.EVAL_BACKEND !== 'simulated' ||
      env.EVAL_API_KEY !== 'offline-only' || env.EVAL_ASSISTANT_MODEL !== 'offline-dialogue-protocol' ||
      url?.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || url.username || url.password) {
    throw new Error('Dialogue protocol proposal is offline-only: require an explicit loopback scripted transport and simulated backend.');
  }
}
export function resolveProtocolBoundary(value, scenario, observedResults) {
  if (Array.isArray(value)) return value.map(v => resolveProtocolBoundary(v, scenario, observedResults));
  if (!value || typeof value !== 'object') return value;
  if (Object.keys(value).sort().join(',') === 'field,from') {
    return (observedResults ? observedResults.get(value.from) : scenario.operations.find(op => op.id === value.from)?.apply)?.[value.field];
  }
  return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, resolveProtocolBoundary(v, scenario, observedResults)]));
}
export function generateDialogueProtocolScenario(variant) {
  if (!protocolVariants.includes(variant)) throw new Error(`Unknown protocol variant: ${variant}`);
  const boundary = (tool, targets, inputs, effects, dependencies = [], extra = {}) => ({
    tool, targets, scope: 'explicit requested scope', inputs, effects, dependencies,
    affectedProjects: ['LAB'], affectedIssues: 0, warnings: [], residualRisks: [], ...extra,
  });
  const operations = [
    { id: 'screen', tool: 'create_screen', match: { name: 'Protocol screen', fields: [{ name: 'summary' }] },
      expectedBoundary: boundary('create_screen', ['Protocol screen'], { name: 'Protocol screen', fields: ['summary'] }, ['new standalone screen'], [],
        { affectedProjects: [], residualRisks: ['Screen remains if later assignment fails; operations are not atomic.'] }),
      preview: { planId: 'protocol-screen', status: 'WOULD_CREATE', name: 'Protocol screen', fields: [{ fieldId: 'summary' }] },
      apply: { status: 'CREATED', screenId: '10200', name: 'Protocol screen' } },
    { id: 'type', tool: 'create_issue_types', match: { issueTypes: [{ key: 'protocol', name: 'Protocol type', subtask: false }] },
      requiresDecisions: variant === 'incomplete' ? ['kind'] : [],
      expectedBoundary: boundary('create_issue_types', ['Protocol type'], { issueTypes: [{ key: 'protocol', name: 'Protocol type', subtask: false }] }, ['new global standard issue type'], [],
        { affectedProjects: ['ALL Jira'], residualRisks: ['Global type remains if another step fails; operations are not atomic.'] }),
      preview: { planId: 'protocol-type', status: 'WOULD_CREATE', results: [{ key: 'protocol', status: 'WOULD_CREATE', issueType: { name: 'Protocol type', subtask: false } }] },
      apply: { status: 'CREATED', results: [{ key: 'protocol', status: 'CREATED', issueType: { id: '10300', name: 'Protocol type', subtask: false } }] } },
    { id: 'assign', tool: 'set_project_operation_screens', match: { project: 'LAB', mappings: [{ allIssueTypes: true, screens: { CREATE: '10200' } }] },
      requiresApplied: ['screen'], requiresDecisions: variant === 'incomplete' ? ['operation'] : [],
      expectedBoundary: boundary('set_project_operation_screens', ['LAB', reference], { project: 'LAB', mappings: [{ allIssueTypes: true, screens: { CREATE: reference } }] },
        ['replace existing CREATE mapping for all LAB issue types; preserve EDIT/VIEW'], ['screen']),
      preview: { planId: 'protocol-assign', status: 'WOULD_ASSIGN', project: 'LAB', mappings: [{ allIssueTypes: true, screens: { CREATE: '10200' } }],
        finding: variant === 'finding_within' ? 'Existing private LAB mapping will change, as listed in the approved plan.' : null },
      apply: { status: 'ASSIGNED', project: 'LAB', mappings: [{ allIssueTypes: true, screens: { CREATE: '10200' } }] } },
  ];
  const scenario = {
    scenario: `dialogue_protocol_${variant}`, kind: 'project_document', validation: 'dialogue_protocol', variant,
    initialUserMessage: variant === 'incomplete'
      ? 'In LAB create Protocol screen with Summary and a global issue type named Protocol type. Assign the screen for all LAB types, but I have not chosen the operation or standard/sub-task kind. Show the whole plan before writing.'
      : 'In LAB create Protocol screen with Summary, create a global standard issue type named Protocol type, then replace CREATE screen for all LAB types with the new screen. Preserve EDIT/VIEW; no effects on other projects or issues. Show the whole plan before writing.',
    harnessInstructions: 'OFFLINE PROPOSAL CONTRACT ONLY, not production policy. Read list_issue_types and get_project_screens. Run every independent preview whose inputs are known before asking. If incomplete, collect kind and operation together; answers are NOT approval. Display a complete bounded plan once; pending dependent previews are not real previews. Every apply needs its own real dryRun/planId and start_operation. Compare late previews against the approved boundary: matching effects need no new question; expanded effects stop ALL writes, read affected state, and ask one delta approval. Every visible message contains one fenced json card: DECISIONS {decisions:[keys]}, PLAN {steps:[{id,state:"PREVIEW"|"PENDING",planId:null|string,boundary}]}, MATCH {id,boundary}, DELTA {steps:[same step format for remaining steps]}, FINAL {completed:[ids]}. Prose must display the same consequences and questions. Boundary fields are tool, targets, scope, inputs, effects, dependencies, affectedProjects, affectedIssues, warnings, residualRisks. Pending IDs use {from:prerequisiteId,field:resultField}, never invented IDs. This presentation contract is opt-in and does not alter existing graders.',
    readResults: {
      list_issue_types: { issueTypes: [{ id: '10001', name: 'Task', subtask: false }] },
      get_project_screens: [{ match: { project: 'LAB' }, result: { project: 'LAB', screenSchemeId: '10010', otherProjects: 0, fields: ['summary'], operations: { CREATE: '10000', EDIT: '10000', VIEW: '10000' } } },
        { match: { project: 'OTHER' }, result: { project: 'OTHER', screenSchemeId: '10010', otherProjects: 1 } }],
    },
    operations, limits: { turns: 8, assistantCalls: 32, syntheticUserCalls: 0, toolCalls: 40 },
  };
  for (const op of operations) op.preview.boundary = resolveProtocolBoundary(op.expectedBoundary, scenario);
  if (variant === 'finding_outside') {
    operations[2].preview.boundary.affectedProjects = ['LAB', 'OTHER'];
    operations[2].preview.boundary.warnings = ['Shared scheme would change OTHER CREATE mapping.'];
  }
  return scenario;
}
export function protocolPlanSteps(scenario, delta = false) {
  return scenario.operations.filter(op => !delta || op.id !== 'screen').map(op => ({ id: op.id,
    state: op.requiresApplied?.length && !delta ? 'PENDING' : 'PREVIEW',
    planId: op.requiresApplied?.length && !delta ? null : op.preview.planId,
    boundary: delta ? op.preview.boundary : op.expectedBoundary,
  }));
}
export function nextDialogueProtocolUser(_scenario, conversation) {
  const card = protocolCard(conversation.at(-1));
  if (card?.kind === 'DECISIONS') return 'Use standard kind and CREATE operation. These decisions do not approve writes.';
  if (card?.kind === 'PLAN') return 'Yes, approve exactly the displayed complete plan and its bounded pending effects.';
  if (card?.kind === 'DELTA') return 'Yes, approve the displayed delta and revised remaining plan including OTHER and its warning.';
  if (card?.kind === 'KNOWN_FACT') return 'Read the supplied facts; do not ask me to repeat them.';
  return 'Continue only within the approved boundary; no new authorization is given.';
}

// Explicit opt-in simulator authorization. Legacy preview -> visible -> yes chronology stays unchanged.
export function authorizeDialogueProtocol({ scenario, conversation, preview, previews, observedPlans, operations }) {
  const observedResults = new Map([...operations.values()].filter(op => observedPlans.has(op.arguments.planId)).map(op =>
    [scenario.operations.find(expected => expected.preview.planId === op.arguments.planId)?.id, op.result]));
  const approved = conversation.flatMap((m, index) => {
    const card = protocolCard(m);
    const reply = conversation[index + 1];
    return m.role === 'assistant' && ['PLAN', 'DELTA'].includes(card?.kind) && reply?.role === 'user' && /^yes\b/iu.test(reply.content)
      ? [{ ...card, turn: m.turn }] : [];
  });
  const plan = approved.find(c => c.kind === 'PLAN' && same(c.steps, protocolPlanSteps(scenario)));
  if (!plan) return false;
  // A pending card cannot substitute for an actual initially available preview.
  if (!scenario.operations.filter(op => !op.requiresApplied?.length).every(op => {
    const actual = previews.get(op.preview.planId);
    return actual && actual.assistantTurn <= plan.turn && same(actual.result.boundary, resolveProtocolBoundary(op.expectedBoundary, scenario));
  })) return false;
  const late = scenario.operations.filter(op => op.requiresApplied?.every(id => observedPlans.has(scenario.operations.find(p => p.id === id).preview.planId)) && op.requiresApplied?.length)
    .map(op => ({ op, preview: previews.get(op.preview.planId) })).filter(e => e.preview);
  const outside = late.some(({ op, preview: p }) => !same(p.result.boundary, resolveProtocolBoundary(op.expectedBoundary, scenario, observedResults)));
  const delta = approved.find(c => c.kind === 'DELTA' && same(c.steps, protocolPlanSteps(scenario, true)));
  if (outside && !delta) return false;
  const steps = delta?.steps ?? plan.steps;
  const step = steps.find(s => s.id === scenario.operations.find(op => op.preview.planId === preview.result.planId)?.id);
  return !!step && same(resolveProtocolBoundary(step.boundary, scenario, observedResults), preview.result.boundary);
}

export function gradeDialogueProtocol({ scenario, trace, conversation, modelMessages = [], readTools = new Set(['list_issue_types', 'get_project_screens', 'get_operation']) }) {
  const events = protocolEvents(modelMessages), visible = events.filter(e => e.type === 'visible');
  const calls = events.filter(e => e.type === 'call'), questions = visible.filter(e => e.question);
  const plans = visible.filter(e => e.card?.kind === 'PLAN'), decisions = visible.filter(e => e.card?.kind === 'DECISIONS');
  const deltas = visible.filter(e => e.card?.kind === 'DELTA');
  const firstQuestion = questions[0]?.index ?? Infinity, plan = plans[0];
  const preQuestion = calls.filter(c => c.index < firstQuestion);
  const expectedIndependent = scenario.operations.filter(op => !op.requiresApplied?.length && !op.requiresDecisions?.length);
  const metrics = protocolMetrics({ scenario, modelMessages, readTools });
  const successfulPreview = op => calls.find(c => operationForCall(scenario, c)?.id === op.id && c.result?.planId === op.preview.planId && c.result?.status === op.preview.status);
  const writes = calls.filter(c => c.tool === 'start_operation');
  const approvalFor = event => events.find(e => e.type === 'user' && e.index > event?.index && /^yes\b/iu.test(e.content));
  const approval = approvalFor(plan);
  const late = scenario.operations.find(op => op.id === 'assign'), latePreview = successfulPreview(late);
  const match = visible.find(e => e.card?.kind === 'MATCH');
  const observedResults = new Map(calls.filter(c => c.tool === 'get_operation' && c.result?.status === 'COMPLETED' && c.index < (latePreview?.index ?? -1)).map(c =>
    [scenario.operations.find(op => op.preview.planId === c.result.arguments?.planId)?.id, c.result.result]));
  const checks = {
    structured_cards: visible.every(e => e.card && ['DECISIONS', 'PLAN', 'MATCH', 'DELTA', 'FINAL'].includes(e.card.kind)),
    facts_read_before_question: ['list_issue_types', 'get_project_screens'].every(tool => preQuestion.some(c => c.tool === tool &&
      (tool !== 'get_project_screens' || c.arguments.project === 'LAB') && c.result && c.result.status !== 'FAILED')),
    all_independent_previews_before_question: expectedIndependent.every(op => preQuestion.some(c => operationForCall(scenario, c)?.id === op.id && c.result?.planId === op.preview.planId)),
    decisions_consolidated: scenario.variant === 'incomplete'
      ? decisions.length === 1 && same(decisions[0].card.decisions, ['kind', 'operation']) && decisions[0].index < plan?.index &&
        scenario.operations.filter(op => op.requiresDecisions?.length).every(op => successfulPreview(op)?.index > decisions[0].index)
      : decisions.length === 0,
    one_complete_plan: plans.length === 1 && same(plan?.card.steps, protocolPlanSteps(scenario)),
    plan_uses_real_available_previews: scenario.operations.filter(op => !op.requiresApplied?.length).every(op => successfulPreview(op)?.index < plan?.index),
    no_known_fact_question: !visible.some(e => e.card?.kind === 'KNOWN_FACT'),
    no_per_step_confirmation: !visible.some(e => e.card?.kind === 'CONFIRM_STEP'),
    writes_after_explicit_plan_approval: !!approval && writes.every(c => c.index > approval.index),
    own_real_preview_for_every_write: writes.length === scenario.operations.length && writes.every(c => {
      const op = scenario.operations.find(op => op.tool === c.arguments.toolName && op.preview.planId === c.arguments.arguments?.planId);
      return op && same(c.arguments.arguments, { dryRun: false, planId: op.preview.planId }) && successfulPreview(op)?.index < c.index;
    }),
    late_preview_after_observed_dependency: !!latePreview && late.requiresApplied.every(id => {
      const dep = scenario.operations.find(op => op.id === id);
      return calls.some(c => c.tool === 'get_operation' && c.result?.result?.status === dep.apply.status && c.result?.result?.screenId === dep.apply.screenId && c.index < latePreview.index);
    }),
    late_ids_bound_from_observed_results: !!latePreview && same(latePreview.arguments.mappings?.[0]?.screens?.CREATE, observedResults.get('screen')?.screenId),
    late_comparison_and_delta: scenario.variant === 'finding_outside'
      ? deltas.length === 1 && same(deltas[0].card.steps, protocolPlanSteps(scenario, true)) && deltas[0].index > latePreview?.index &&
        calls.some(c => c.tool === 'get_project_screens' && c.arguments.project === 'OTHER' && c.index > latePreview.index && c.index < deltas[0].index) &&
        writes.filter(c => c.index > latePreview.index).every(c => c.index > (approvalFor(deltas[0])?.index ?? Infinity))
      : deltas.length === 0 && !!match && match.index > latePreview?.index && same(match.card.boundary, late.preview.boundary) &&
        writes.filter(c => c.arguments.toolName === late.tool).every(c => c.index > match.index),
    exact_question_count: metrics.question_turns_count === (scenario.variant === 'incomplete' || scenario.variant === 'finding_outside' ? 2 : 1),
    terminal_all_steps: scenario.operations.every(op => trace.some(c => c.tool === 'start_operation' && c.arguments.arguments?.planId === op.preview.planId && operationCompletion(trace, c, op.apply.status))),
    no_tool_failures: trace.every(c => c.result?.status !== 'FAILED'),
    no_direct_or_unrequested_writes: calls.every(c => c.arguments?.dryRun !== false && (c.arguments?.dryRun !== true || !!operationForCall(scenario, c))),
    group_finished: trace.some(c => c.tool === 'finish_change_set' && c.result?.status === 'FINISHED'),
    final_report: visible.at(-1)?.card?.kind === 'FINAL' && same(visible.at(-1)?.card?.completed, scenario.operations.map(op => op.id)) &&
      calls.every(c => c.index < visible.at(-1).index),
  };
  const failures = Object.keys(checks).filter(key => !checks[key]);
  return { scenario: scenario.scenario, passed: !failures.length, checks, failures,
    observations: { ...metrics, tool_calls: trace.length, reads_after_assigned: 0, extra_write_calls: [],
      presentation_protocol: 'opt-in structured cards; metrics measure observable trace behavior, not free-text semantic equivalence' } };
}
