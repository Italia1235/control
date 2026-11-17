import { CFG, parseResult, readState } from '../../configure-common.mjs';

/**
 * Once, after the assistant turn that previewed configure_workflow (a planId was shown) and before any start_operation:
 * a second administrator adds the permitted EXISTING Done status to the same inactive workflow with Jira's own admin
 * action (no global status is created; the plugin is not involved). The changed revision makes the shown plan stale.
 */
export default async function afterAssistant({ lifecycle, trace, prepared, turn, tool, adminPage, log }) {
  if (lifecycle.injection) return;
  const previewed = trace.some((e) => e.tool === CFG && e.turn === turn && e.arguments?.dryRun === true && parseResult(e.result)?.planId);
  const applied = trace.some((e) => e.tool === 'start_operation' && e.arguments?.toolName === CFG);
  if (!previewed || applied) return;
  const { workflow, spare } = prepared;
  const before = await readState(tool, workflow);
  if (spare.name !== 'Done' || before.steps.some((s) => s.statusId === spare.id)) {
    throw new Error('Для внешнего шага нужен существующий Done, ещё не включённый в тестовый workflow');
  }
  await adminPage(`/secure/admin/workflows/EditWorkflowDispatcher.jspa?wfName=${encodeURIComponent(workflow)}`);
  await adminPage('/secure/admin/workflows/AddWorkflowStep.jspa', { workflowName: workflow, workflowMode: 'live', stepName: spare.name, stepStatus: spare.id });
  const after = await readState(tool, workflow);
  if (after.revision === before.revision) throw new Error(`revision не изменилась после добавления статуса ${spare.name}`);
  lifecycle.injection = { turn, status: spare.name, statusId: spare.id, revisionBefore: before.revision, revisionAfter: after.revision, steps: after.steps.map((s) => s.name) };
  log(`внешний шаг «${spare.name}» (ход ${turn}); revision ${before.revision} → ${after.revision}`);
}
