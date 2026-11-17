import { parseResult } from '../../checks.mjs';
import { CFG, GET, addStep } from '../../validators-common.mjs';

/**
 * Two outside changes of the inactive workflow, each once, with Jira's own admin action (a status is added; the plugin
 * is not involved), like a second administrator would:
 *  1. after the first assistant turn that read the validators and made no configure call — the revision the assistant
 *     holds is now old (the next preview with it must answer VERSION_CONFLICT, or the assistant reads again);
 *  2. after a turn with a good configure preview and before any apply — the shown plan is stale (PREVIEW_STALE).
 * Evidence (turn, status, revision before and after) goes to lifecycle.first and lifecycle.second.
 */
export default async function afterAssistant({ lifecycle, trace, prepared, turn, tool, adminPage, log }) {
  const revision = async () => (await tool(GET, { workflow: prepared.workflow, version: 'ACTIVE' })).revision;
  const change = async (slot, status) => {
    const revisionBefore = await revision();
    await addStep({ adminPage }, prepared.workflow, status, 'live');
    const revisionAfter = await revision();
    if (revisionAfter === revisionBefore) throw new Error(`revision не изменилась после добавления статуса ${status.name}`);
    lifecycle[slot] = { turn, status: status.name, revisionBefore, revisionAfter };
    log(`${slot}: добавлен статус ${status.name} (ход ${turn}); revision ${revisionBefore} → ${revisionAfter}`);
  };
  const [one, two] = prepared.statuses.foreign;
  if (!lifecycle.first) {
    if (trace.some((e) => e.tool === GET) && !trace.some((e) => e.tool === CFG)) await change('first', one);
    return;
  }
  if (lifecycle.second) return;
  const previewed = trace.some((e) => e.tool === CFG && e.turn === turn && e.arguments?.dryRun === true && parseResult(e.result)?.planId);
  const applied = trace.some((e) => e.tool === 'start_operation' && e.arguments?.toolName === CFG);
  if (previewed && !applied) await change('second', two);
}
