const READ = 'get_project_workflows';
export const readNoCountsVariants = ['assign', 'clone'];

// A replace/copy request must be planned from configuration only: issue counts come from the preview,
// never from a full project scan (includeIssueCounts=true) the user did not ask for.
const VARIANTS = {
  assign: {
    tool: 'assign_workflow',
    initialUserMessage: 'Замени workflow у всех типов задач проекта ABC на существующий Target.',
    shared: false,
    match: { dryRun: true, project: 'ABC', workflow: 'Target', allIssueTypes: true },
    preview: { dryRun: true, status: 'WOULD_ASSIGN', project: { key: 'ABC', name: 'Alpha' }, workflow: 'Target',
      scheme: { action: 'UPDATE', id: 10100, name: 'ABC Workflow Scheme', otherProjectsAffected: 0 },
      issueTypes: [{ name: 'Bug', action: 'MIGRATE', issues: 76204 }, { name: 'Task', action: 'READY', issues: 0 }],
      planId: 'plan-assign-abc' },
    apply: { dryRun: false, status: 'ASSIGNED' },
  },
  clone: {
    tool: 'clone_project_workflows',
    initialUserMessage: 'Скопируй схему и все workflow проекта ABC, чтобы потом менять их отдельно от других проектов.',
    shared: true,
    match: { dryRun: true, project: 'ABC', scope: 'ALL_WORKFLOWS' },
    preview: { dryRun: true, status: 'WOULD_CLONE', project: { key: 'ABC', name: 'Alpha' }, scope: 'ALL_WORKFLOWS',
      currentScheme: { id: 10100, name: 'Shared Workflow Scheme', otherProjects: 1, otherProjectNames: ['Beta'] },
      scheme: { action: 'CREATE', name: 'ABC Workflow Scheme' },
      workflows: [{ source: 'jira', copy: 'ABC jira', verdict: 'COPY', issues: 76204 }],
      planId: 'plan-clone-abc' },
    apply: { dryRun: false, status: 'CLONED' },
  },
};

function configuration(shared) {
  return {
    project: { key: 'ABC', name: 'Alpha' },
    scheme: { id: 10100, name: shared ? 'Shared Workflow Scheme' : 'ABC Workflow Scheme', isDefault: false,
      hasDraft: false, otherProjects: shared ? 1 : 0, otherProjectNames: shared ? ['Beta'] : [] },
    defaultWorkflow: 'jira',
    issueCountsStatus: 'COMPLETE',
    issueCountsError: null,
    issueTypes: [
      { issueType: { id: '10001', name: 'Bug' }, workflow: 'jira', issues: 76204,
        statuses: [{ name: 'Open', issues: 76204 }] },
      { issueType: { id: '10002', name: 'Task' }, workflow: 'jira', issues: 0, statuses: [] },
    ],
  };
}

export function generateWorkflowReadNoCountsScenario(variant) {
  const spec = VARIANTS[variant];
  if (!spec) throw new Error(`Unknown workflow read variant: ${variant}`);
  return {
    scenario: `workflow_read_no_counts_${variant}`,
    kind: 'project_document', validation: 'workflow_read_no_counts', variant,
    syntheticUserMode: 'deterministic',
    initialUserMessage: spec.initialUserMessage,
    // The simulator nulls counts unless includeIssueCounts=true, exactly like the plugin.
    readResults: {
      list_projects: [{ match: {}, result: [{ key: 'ABC', name: 'Alpha' }] }],
      [READ]: [{ match: { project: 'ABC' }, result: configuration(spec.shared) }],
    },
    operations: [{ id: `${variant}-abc`, tool: spec.tool, match: spec.match, preview: spec.preview, apply: spec.apply }],
    limits: { turns: 3, assistantCalls: 6, syntheticUserCalls: 0, toolCalls: 6 },
  };
}

// The user stops after the preview: the scenario measures what the assistant read before it, not the write.
export function nextWorkflowReadNoCountsUser(scenario, _conversation, trace) {
  const tool = VARIANTS[scenario.variant].tool;
  return trace.some(call => call.tool === tool && call.arguments?.dryRun === true &&
    call.result?.status === VARIANTS[scenario.variant].preview.status)
    ? 'DONE' : 'Да, продолжай. Ничего не применяй без моего подтверждения.';
}

export function gradeWorkflowReadNoCounts({ scenario, trace }) {
  const spec = VARIANTS[scenario.variant];
  const previews = trace.filter(call => call.tool === spec.tool && call.arguments?.dryRun === true);
  const reads = trace.filter(call => call.tool === READ);
  const counted = reads.filter(call => call.arguments?.includeIssueCounts === true);
  const writes = trace.filter(call => call.tool === 'start_operation' || call.arguments?.dryRun === false);
  const checks = {
    configuration_read_before_preview: previews.length > 0 && reads.some(call =>
      call.arguments?.project === 'ABC' && call.sequence < previews[0].sequence),
    no_issue_counts: counted.length === 0,
    preview_once: previews.length === 1 && previews[0].result?.status === spec.preview.status,
    no_writes: writes.length === 0,
  };
  const failures = Object.entries(checks).filter(([, passed]) => !passed).map(([name]) => name);
  return { scenario: scenario.scenario, passed: failures.length === 0, checks, failures,
    observations: { tool_calls: trace.length, configuration_reads: reads.length, counted_reads: counted.length,
      reads_after_assigned: 0,
      extra_write_calls: writes.map(call => ({ tool: call.tool, sequence: call.sequence })) } };
}
