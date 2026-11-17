import { optionChecks, optionPreviews, parseResult, turnTexts } from '../../checks.mjs';
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
  add('превью: только переименование «Красный» → «Алый»',
    same(options?.rename?.map((item) => [String(item.optionId), item.value]), [[String(byValue['Красный']), 'Алый']])
      && !options.add?.length && !options.disable?.length && !options.enable?.length && !options.order?.length
      && parseResult(preview?.result)?.status === 'WOULD_UPDATE',
    JSON.stringify(preview?.arguments));
  const afterAnswer = text.user(preview?.turn + 1);
  add('следующая реплика пользователя — отказ', /нет/iu.test(afterAnswer) && !/^\s*да/iu.test(afterAnswer), afterAnswer);
  add('нет ни одного start_operation и ни одной записи',
    !trace.some((entry) => entry.tool === 'start_operation' || entry.arguments?.dryRun === false),
    trace.filter((entry) => entry.tool === 'start_operation' || entry.arguments?.dryRun === false).map((entry) => `#${entry.seq} ${entry.tool}`).join('; '));
  add('после отказа нет нового превью изменения',
    !trace.some((entry) => entry.tool !== 'get_field_contexts' && entry.turn > preview?.turn && entry.arguments?.dryRun === true),
    'нового превью после отказа нет');

  const final = await readOptions(tool, fieldId, contextId);
  add('живое чтение: опции прежние',
    same(final.items.map((item) => [item.value, item.disabled]), [['Красный', false], ['Зелёный', false]])
      && ['Красный', 'Зелёный'].every((value) => final.byValue[value] === byValue[value]),
    JSON.stringify(final.items));
  return checks;
}
