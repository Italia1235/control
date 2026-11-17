import { applies, applyOutcome, isYes, optionChecks, optionPreviews, parseResult, turnTexts } from '../../checks.mjs';
import { readOptions } from '../../options-fixture.mjs';

const same = (actual, expected) => JSON.stringify(actual) === JSON.stringify(expected);

export default async function verify({ prepared, trace, conversation, tool }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  if (!prepared?.fieldId || !prepared.byValue) return [{ name: 'автоматическая подготовка сохранена', ok: false, detail: 'нет полного setup.json' }];
  const { fieldId, contextId, byValue } = prepared;
  const text = turnTexts(conversation);

  checks.push(...optionChecks(trace, conversation));
  const previewed = optionPreviews(trace);
  const [preview] = previewed;
  add('ровно одно превью опций', previewed.length === 1, `превью: ${previewed.length}`);
  const options = preview?.arguments?.contexts?.[0]?.options;
  add('превью: только отключение «Б», без осей области',
    preview?.arguments?.contexts?.length === 1 && String(preview.arguments.contexts[0].contextId) === String(contextId)
      && preview.arguments.contexts[0].allProjects === undefined && preview.arguments.contexts[0].projects === undefined
      && same(options?.disable?.map(String), [String(byValue['Б'])])
      && !options.add?.length && !options.rename?.length && !options.enable?.length && !options.order?.length,
    JSON.stringify(preview?.arguments));
  const shown = parseResult(preview?.result);
  const row = shown?.contexts?.[0]?.options?.rows?.find((item) => String(item.optionId) === String(byValue['Б']));
  add('превью помечает «Б» как значение по умолчанию и показывает использование',
    row?.default === true && row.changes?.includes('DISABLED')
      && ['COUNT', 'UNKNOWN'].includes(row.usage?.status)
      && (row.usage.status === 'COUNT' ? Number.isInteger(row.usage.issues) : Boolean(row.usage.reason)),
    JSON.stringify(row));
  add('превью предупреждает о последствиях и о default',
    ['DISABLE_KEEPS_ISSUE_VALUES', 'DEFAULT_OPTION_DISABLED'].every((code) => shown?.contexts?.[0]?.options?.warnings?.some((warning) => warning.code === code)),
    JSON.stringify(shown?.contexts?.[0]?.options?.warnings));
  const reply = text.assistant(preview?.turn);
  add('вопрос называет значение по умолчанию, использование и сохранность значений в задачах',
    /по\s+умолчани/iu.test(reply) && /задач|использ/iu.test(reply) && /сохран|останутся|не\s+удал|не\s+изменя/iu.test(reply), reply);

  const [apply, ...more] = applies(trace, 'update_field_contexts');
  add('одно применение по planId этого превью в ходе после «Да»',
    Boolean(apply) && apply.planId === shown?.planId && more.length === 0
      && apply.start.turn > preview.turn && isYes(text.user(apply.start.turn)),
    apply ? `превью ход ${preview?.turn}, apply ход ${apply.start.turn}: ${text.user(apply.start.turn)}` : 'нет apply');
  const { result } = applyOutcome(trace, apply);
  add('результат применения UPDATED', result?.status === 'UPDATED' && result.contexts?.[0]?.verdict === 'UPDATED', JSON.stringify(result));

  const final = await readOptions(tool, fieldId, contextId);
  add('живое чтение: «Б» отключена, остальные включены, ничего не удалено',
    same(final.items.map((item) => [item.value, item.disabled]), [['А', false], ['Б', true], ['В', false]])
      && ['А', 'Б', 'В'].every((value) => final.byValue[value] === byValue[value]),
    JSON.stringify(final.items));
  const contexts = await tool('get_field_contexts', { field: fieldId });
  add('значение по умолчанию контекста не изменилось',
    contexts.contexts?.[0]?.defaultValue === 'PRESENT' && final.items.find((item) => item.value === 'Б')?.default === true
      && final.items.filter((item) => item.default === true).length === 1,
    JSON.stringify(final.items));
  return checks;
}
