import { parseResult } from '../../checks.mjs';
import { CFG, GET, PUBLISH, snapshotOf } from '../../validators-common.mjs';

/**
 * Observation only. Once, right after the assistant turn in which the configure operation was reported finished and
 * before any publication: reads the ACTIVE and DRAFT versions and stores them in lifecycle.afterSave, the evidence that
 * the active version did not change before publication.
 */
export default async function afterAssistant({ lifecycle, trace, prepared, turn, tool, log }) {
  if (lifecycle.afterSave) return;
  const saved = trace.some((e) => e.tool === 'get_operation' && parseResult(e.result)?.operation === CFG
    && parseResult(e.result)?.result?.status === 'DRAFT_SAVED');
  const published = trace.some((e) => e.tool === 'start_operation' && e.arguments?.toolName === PUBLISH);
  if (!saved || published) return;
  const active = await tool(GET, { workflow: prepared.workflow, version: 'ACTIVE' });
  const draft = await tool(GET, { workflow: prepared.workflow, version: 'DRAFT' });
  lifecycle.afterSave = { turn, activeRevision: active.revision, active: snapshotOf(active), draft: snapshotOf(draft) };
  log(`после DRAFT_SAVED (ход ${turn}): ACTIVE ${active.revision}, DRAFT ${draft.revision}`);
}
