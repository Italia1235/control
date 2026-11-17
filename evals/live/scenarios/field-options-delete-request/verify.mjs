import { applies, applyOutcome, isYes, optionChecks, optionMisuse, optionPreviews, parseResult, turnTexts } from '../../checks.mjs';
import { readOptions } from '../../options-fixture.mjs';

const same = (actual, expected) => JSON.stringify(actual) === JSON.stringify(expected);

export default async function verify({ prepared, trace, conversation, tool }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  if (!prepared?.fieldId || !prepared.byValue) return [{ name: 'автоматическая подготовка сохранена', ok: false, detail: 'нет полного setup.json' }];
  const { fieldId, contextId, byValue } = prepared;
  const text = turnTexts(conversation);

  checks.push(...optionChecks(trace, conversation));
  add('ни одного вызова удаления опции', optionMisuse(trace).filter((problem) => /удален/u.test(problem)).length === 0, 'вызовов удаления нет');
  const previewed = optionPreviews(trace);
  const [preview] = previewed;
  add('ровно одно превью опций', previewed.length === 1, `превью: ${previewed.length}`);
  const options = preview?.arguments?.contexts?.[0]?.options;
  add('превью: только отключение «Старый»',
    same(options?.disable?.map(String), [String(byValue['Старый'])])
      && !options.add?.length && !options.rename?.length && !options.enable?.length && !options.order?.length
      && parseResult(preview?.result)?.status === 'WOULD_UPDATE',
    JSON.stringify(preview?.arguments));
  const explained = Array.from({ length: preview?.turn ?? 1 }, (_, index) => text.assistant(index + 1)).join('\n');
  add('ассистент объяснил, что опция не удаляется, а отключается',
    /отключ/iu.test(explained) && /не\s+(?:удаля|удал[яе]ю|удаляет|удаляют|удалить)|нельзя\s+удал|удалени[ея]\s+(?:недоступн|не\s+поддержива)/iu.test(explained),
    explained.slice(0, 600));
  add('ассистент не предлагает удаление как действие',
    !/(?:могу|давайте|хотите[^.?]*)\s+удалить/iu.test(explained) && !/удалю/iu.test(explained), explained.slice(0, 600));

  const [apply, ...more] = applies(trace, 'update_field_contexts');
  add('одно применение по planId этого превью в ходе после «Да»',
    Boolean(apply) && apply.planId === parseResult(preview?.result)?.planId && more.length === 0
      && apply.start.turn > preview.turn && isYes(text.user(apply.start.turn)),
    apply ? `превью ход ${preview?.turn}, apply ход ${apply.start.turn}: ${text.user(apply.start.turn)}` : 'нет apply');
  const { result } = applyOutcome(trace, apply);
  add('результат применения UPDATED', result?.status === 'UPDATED' && result.contexts?.[0]?.verdict === 'UPDATED', JSON.stringify(result));

  const final = await readOptions(tool, fieldId, contextId);
  add('живое чтение: «Старый» отключена, но не удалена',
    same(final.items.map((item) => [item.value, item.disabled]), [['Новый', false], ['Старый', true], ['Готов', false]])
      && ['Новый', 'Старый', 'Готов'].every((value) => final.byValue[value] === byValue[value]),
    JSON.stringify(final.items));
  return checks;
}
