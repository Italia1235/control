import { parseResult } from '../../checks.mjs';
import { CFG } from '../../comment-validators-common.mjs';

const RUNNING = new Set(['QUEUED', 'RUNNING']);

/**
 * Once, after the assistant turn that previewed a configure_workflow comment_required plan and before any apply: a second
 * administrator removes the screen of the same transition with configure_workflow.existing.changes
 * (own preview + start_operation to the terminal state). The shown comment plan is now stale. Evidence goes to lifecycle.injection.
 */
export default async function afterAssistant({ lifecycle, trace, prepared, turn, tool, log }) {
  if (lifecycle.injection) return;
  const previewed = trace.some((e) => e.tool === CFG && e.turn === turn && e.arguments?.dryRun === true && parseResult(e.result)?.planId
    && e.arguments.existing && e.arguments.transitionRules?.some((r) => r.addValidators?.some((v) => v.template === 'comment_required')));
  const applied = trace.some((e) => e.tool === 'start_operation' && e.arguments?.toolName === CFG);
  if (!previewed || applied) return;
  const read = await tool('get_workflow', { workflow: prepared.workflow, version: 'ACTIVE' });
  const preview = await tool(CFG, { dryRun: true, existing: { workflow: prepared.workflow, version: 'ACTIVE', expectedRevision: read.revision,
    changes: [{ op: 'UPDATE_TRANSITION', actionId: prepared.actionId, screen: { action: 'CLEAR' } }] } });
  if (!preview.planId) throw new Error(`configure_workflow CLEAR без плана: ${JSON.stringify(preview).slice(0, 500)}`);
  let op = await tool('start_operation', { toolName: CFG, arguments: { dryRun: false, planId: preview.planId } });
  const deadline = Date.now() + 60000;
  while (RUNNING.has(op.status) && Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 1000));
    op = await tool('get_operation', { operationId: op.operationId });
  }
  if (op.status !== 'COMPLETED' || op.result?.status !== 'UPDATED_INACTIVE'
    || !op.result?.execution?.steps?.some((step) => step.kind === 'WORKFLOW_SAVE' && step.writeStatus === 'APPLIED' && step.verification === 'MATCH')) {
    throw new Error(`внешний CLEAR экрана: ${op.status} ${JSON.stringify(op.result ?? op.error).slice(0, 500)}`);
  }
  lifecycle.injection = { turn, revisionBefore: read.revision, status: op.result?.status ?? op.status };
  log(`внешний CLEAR экрана перехода ${prepared.actionId} после хода ${turn}: ${lifecycle.injection.status}`);
}
