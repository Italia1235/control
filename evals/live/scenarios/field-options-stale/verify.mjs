import { applies, applyOutcome, isYes, optionChecks, optionPreviews, optionReads, parseResult, turnTexts } from '../../checks.mjs';
import { readOptions } from '../../options-fixture.mjs';

const same = (actual, expected) => JSON.stringify(actual) === JSON.stringify(expected);

export default async function verify({ prepared, trace, conversation, lifecycle, tool }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  if (!prepared?.fieldId || !prepared.byValue) return [{ name: 'автоматическая подготовка сохранена', ok: false, detail: 'нет полного setup.json' }];
  const { fieldId, contextId, byValue } = prepared;
  const text = turnTexts(conversation);

  checks.push(...optionChecks(trace, conversation));
  const previewed = optionPreviews(trace);
  const [first, second] = previewed;
  add('два превью опций: исходное и свежее после отказа', previewed.length === 2 && first.seq < second.seq, `превью: ${previewed.length}`);
  const renameOnly = (preview) => same(preview?.arguments?.contexts?.[0]?.options?.rename?.map((item) => [String(item.optionId), item.value]),
    [[String(byValue['Два']), 'Двойка']]) && !preview.arguments.contexts[0].options.add?.length
    && !preview.arguments.contexts[0].options.disable?.length && !preview.arguments.contexts[0].options.order?.length;
  add('оба превью — только переименование «Два» → «Двойка»', renameOnly(first) && renameOnly(second),
    JSON.stringify([first?.arguments, second?.arguments]));

  add('конкурентная опция «Три» добавлена после превью и до применения',
    lifecycle?.injection && lifecycle.injection.previewSeq === first?.seq && first?.seq < (applies(trace, 'update_field_contexts')[0]?.start.seq ?? -1)
      && same(lifecycle.injection.before.map((item) => item.value), ['Один', 'Два'])
      && same(lifecycle.injection.after.map((item) => item.value), ['Один', 'Два', 'Три']),
    JSON.stringify(lifecycle?.injection));

  const [stale, fresh, ...rest] = applies(trace, 'update_field_contexts');
  const staleOutcome = applyOutcome(trace, stale);
  const freshOutcome = applyOutcome(trace, fresh);
  add('два применения: устаревшее и свежее', Boolean(stale) && Boolean(fresh) && rest.length === 0 && stale.planId !== fresh.planId,
    applies(trace, 'update_field_contexts').map((apply) => `#${apply.start.seq} ${apply.planId}`).join('; '));
  add('первое применение отказало PREVIEW_STALE', staleOutcome.result?.errorCode === 'PREVIEW_STALE', JSON.stringify(staleOutcome.result));
  add('после PREVIEW_STALE опции не перезаписаны',
    same(lifecycle?.stale?.result, staleOutcome.result)
      && same(lifecycle.stale.options.map((item) => [item.value, item.disabled]), [['Один', false], ['Два', false], ['Три', false]]),
    JSON.stringify(lifecycle?.stale));
  add('после отказа опции прочитаны заново, затем построено свежее превью',
    optionReads(trace).some((entry) => entry.seq > (staleOutcome.entry?.seq ?? Infinity) && entry.seq < (second?.seq ?? -1)
      && parseResult(entry.result)?.contexts?.[0]?.optionList?.items?.some((item) => item.value === 'Три')),
    'ожидается чтение includeOptions=true с «Три» между отказом и новым превью');
  add('свежее превью построено по свежему planId и после отказа',
    fresh?.planId === parseResult(second?.result)?.planId && second?.seq > (staleOutcome.entry?.seq ?? Infinity)
      && stale?.planId === parseResult(first?.result)?.planId, `${stale?.planId} → ${fresh?.planId}`);
  add('каждое превью получило отдельное «Да» в последующем ходе',
    [[first, stale], [second, fresh]].every(([preview, apply]) => preview && apply && apply.start.turn > preview.turn && isYes(text.user(apply.start.turn))),
    [[first, stale], [second, fresh]].map(([preview, apply]) => `превью ${preview?.turn}, apply ${apply?.start.turn}: ${text.user(apply?.start.turn)}`).join('; '));
  add('второе применение UPDATED', freshOutcome.result?.status === 'UPDATED' && freshOutcome.result.contexts?.[0]?.verdict === 'UPDATED',
    JSON.stringify(freshOutcome.result));

  const final = await readOptions(tool, fieldId, contextId);
  add('живое чтение: «Один», «Двойка», «Три», все включены, идентификаторы прежние',
    same(final.items.map((item) => [item.value, item.disabled]), [['Один', false], ['Двойка', false], ['Три', false]])
      && final.byValue['Один'] === byValue['Один'] && final.byValue['Двойка'] === byValue['Два'],
    JSON.stringify(final.items));
  return checks;
}
