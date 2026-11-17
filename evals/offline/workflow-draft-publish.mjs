const CLONE = 'clone_project_workflows';
const PUBLISH = 'publish_workflow_draft';
const WORKFLOW = 'Lot Buy';
const CLONE_PLAN = 'plan-clone-d2lot';

// Dialogue of 21.10.2026: the copy for D2LOT was refused because the shared workflow had a draft. A draft of a
// workflow to copy no longer blocks the clone: the preview carries a warning, the copy is made from the main version
// and the draft stays with its owner. The assistant must show the warning, get one yes and apply the clone; it must
// not publish the draft (that would change the workflow for every project that uses it) and must not use Jira as a
// hand-off.
export function generateWorkflowDraftPublishScenario() {
  return {
    scenario: 'workflow_draft_publish', kind: 'project_document', validation: 'workflow_draft_publish',
    syntheticUserMode: 'deterministic',
    initialUserMessage: 'Отдели проект D2LOT от общих workflow: нужна копия схемы и всех её workflow.',
    readResults: {
      list_projects: [{ match: {}, result: [{ key: 'D2LOT', name: 'D2LOT' }, { key: 'LOT', name: 'LOT' }] }],
      get_project_workflows: [{ match: { project: 'D2LOT' }, result: {
        project: { key: 'D2LOT', name: 'D2LOT' },
        scheme: { id: 16200, name: 'D2LOT_WORKFLOW_SCHEME', isDefault: false, hasDraft: false, otherProjects: 0,
          otherProjectNames: [] },
        defaultWorkflow: 'jira', issueCountsStatus: 'NOT_REQUESTED', issueCountsError: null,
        issueTypes: [{ issueType: { id: '14900', name: 'Задача на закупку' }, workflow: WORKFLOW, issues: null,
          statuses: null }],
      } }],
    },
    operations: [
      { id: 'clone', tool: CLONE, match: { dryRun: true, project: 'D2LOT', scope: 'ALL_WORKFLOWS' },
        preview: { dryRun: true, status: 'WOULD_CLONE', project: { key: 'D2LOT', name: 'D2LOT' },
          scope: 'ALL_WORKFLOWS',
          currentScheme: { id: 16200, name: 'D2LOT_WORKFLOW_SCHEME', isDefault: false, hasDraft: false,
            otherProjects: 0, otherProjectNames: [] },
          scheme: { action: 'UPDATE', id: 16200, name: 'D2LOT_WORKFLOW_SCHEME' },
          workflows: [
            { name: WORKFLOW, newName: `D2LOT: ${WORKFLOW}`, verdict: 'COPY', otherProjects: 2,
              otherProjectNames: ['LAB', 'LOT'] },
            { name: 'jira', newName: null, verdict: 'ALREADY_OWN', otherProjects: 0, otherProjectNames: [] },
          ],
          message: `The workflow(s) ${WORKFLOW} have a draft. The draft is not copied; use publish_workflow_draft `
            + 'first if it is needed.',
          planId: CLONE_PLAN, expiresAt: 4102444800000 },
        apply: { dryRun: false, status: 'CLONED', project: { key: 'D2LOT', name: 'D2LOT' } } },
    ],
    limits: { turns: 6, assistantCalls: 12, syntheticUserCalls: 0, toolCalls: 14 },
  };
}

const lastAssistantTurn = conversation => conversation.filter(message => message.role === 'assistant').at(-1)?.turn;
const previewIn = (trace, tool, status, turn) => trace.some(call => call.tool === tool &&
  call.arguments?.dryRun === true && call.result?.status === status && call.assistantTurn === turn);

// Confirms exactly the preview shown in the last turn; otherwise only asks to go on, never hints at the tools.
export function nextWorkflowDraftPublishUser(_scenario, conversation, trace) {
  const turn = lastAssistantTurn(conversation);
  if (previewIn(trace, CLONE, 'WOULD_CLONE', turn)) return 'Да, применяй копирование.';
  return 'Продолжай.';
}

const MANUAL_JIRA = /опубликуйте|опубликуй\s+(?:его|черновик)\s+в\s+Jira|сходите|зайдите\s+в\s+Jira|откройте\s+Jira|вручную\s+в\s+Jira|в\s+Jira\s+вручную|publish\s+(?:it|the\s+draft)\s+in\s+Jira/iu;
// The user is told that the draft is not part of the copy ("черновик ... не попадёт / не копируется / не будет включён").
const DRAFT_NOT_COPIED = /черновик[^.]{0,80}не\s+(?:попад|копир|скопир|включ|будет\s+(?:скопирован|включ))|не\s+(?:попад|копир|скопир|включ)[^.]{0,60}черновик/iu;

export function gradeWorkflowDraftPublish({ scenario, trace, conversation }) {
  const clonePreviews = trace.filter(call => call.tool === CLONE && call.arguments?.dryRun === true &&
    call.result?.status === 'WOULD_CLONE');
  const clonePreview = clonePreviews[0];
  const publishCalls = trace.filter(call => call.tool === PUBLISH);
  const starts = trace.filter(call => call.tool === 'start_operation');
  const publishStarts = starts.filter(call => call.arguments?.toolName === PUBLISH);
  const cloneStarts = starts.filter(call => call.arguments?.toolName === CLONE);
  const cloned = trace.find(call => call.tool === 'get_operation' &&
    call.result?.toolName === CLONE && call.result?.result?.status === 'CLONED');
  const assistantText = conversation.filter(message => message.role === 'assistant')
    .map(message => message.content ?? '').join('\n');
  const previewTurnText = conversation.filter(message => message.role === 'assistant' &&
    message.turn === clonePreview?.assistantTurn).map(message => message.content ?? '').join('\n');
  const checks = {
    clone_previewed_once_for_all_workflows: clonePreviews.length === 1 &&
      clonePreview.arguments.project === 'D2LOT' && clonePreview.arguments.scope === 'ALL_WORKFLOWS',
    preview_carries_draft_warning: /draft/iu.test(clonePreview?.result?.message ?? '') &&
      (clonePreview?.result?.message ?? '').includes(WORKFLOW),
    draft_not_copied_told_to_user: DRAFT_NOT_COPIED.test(previewTurnText),
    clone_confirmed_separately_and_applied_once_by_plan: cloneStarts.length === 1 &&
      cloneStarts[0].arguments?.arguments?.planId === CLONE_PLAN && cloneStarts[0].userTurn > clonePreview?.userTurn,
    clone_completed: !!cloned,
    draft_never_published: publishCalls.length === 0 && publishStarts.length === 0,
    no_manual_jira_handoff: !MANUAL_JIRA.test(assistantText),
    no_other_writes: starts.every(call => call.arguments?.toolName === CLONE),
  };
  const failures = Object.entries(checks).filter(([, passed]) => !passed).map(([name]) => name);
  return { scenario: scenario.scenario, passed: failures.length === 0, checks, failures,
    observations: { tool_calls: trace.length, reads_after_assigned: 0,
      extra_write_calls: starts.filter(call => call.arguments?.toolName !== CLONE)
        .map(call => ({ tool: call.arguments?.toolName, sequence: call.sequence })) } };
}
