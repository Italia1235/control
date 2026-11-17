import { applies, applyOutcome, optionPreviews } from '../../checks.mjs';
import { addOptionLikeAnotherAdmin, readOptions } from '../../options-fixture.mjs';

/**
 * Uses Jira's native admin form for the concurrent edit (another administrator), right after the assistant showed the first
 * preview of options; records only plugin reads and the state after the refusal, never issue data.
 */
export default async function afterAssistant({ prepared, lifecycle, trace, turn, tool, adminPage, log }) {
  if (!lifecycle.injection) {
    const preview = optionPreviews(trace).find((entry) => entry.turn === turn);
    if (preview) {
      const before = await readOptions(tool, prepared.fieldId, prepared.contextId);
      if (before.items.map((item) => item.value).join(',') !== 'Один,Два') throw new Error('Неожиданные опции до вмешательства');
      const injected = await addOptionLikeAnotherAdmin({ adminPage, tool, prepared, value: 'Три' });
      lifecycle.injection = { previewSeq: preview.seq, turn, before: before.items, after: injected.after.items,
        status: injected.status, action: '/secure/admin/EditCustomFieldOptions!add.jspa' };
      log('после превью в список добавлена опция «Три» через административную форму Jira');
    }
  }
  for (const operation of applies(trace, 'update_field_contexts')) {
    const { result, entry } = applyOutcome(trace, operation);
    if (result?.errorCode === 'PREVIEW_STALE' && !lifecycle.stale) {
      lifecycle.stale = { result, options: (await readOptions(tool, prepared.fieldId, prepared.contextId)).items, turn: entry?.turn ?? turn };
      log('PREVIEW_STALE: зафиксировано состояние опций после отказа без переименования');
    }
  }
}
