import { optionChecks, optionRequests, parseResult, turnTexts } from '../../checks.mjs';
import { readOptions } from '../../options-fixture.mjs';

const same = (actual, expected) => JSON.stringify(actual) === JSON.stringify(expected);

export default async function verify({ prepared, trace, conversation, tool }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  if (!prepared?.fieldId || !prepared.byValue) return [{ name: 'автоматическая подготовка сохранена', ok: false, detail: 'нет полного setup.json' }];
  const { fieldId, contextId, byValue } = prepared;
  const text = turnTexts(conversation);

  // The refusal has no planId, so there is no accepted preview to put in a table.
  checks.push(...optionChecks(trace, conversation, { preview: false }));
  const requests = optionRequests(trace);
  const refused = requests.find((entry) => parseResult(entry.result)?.errorCode === 'OPTION_PARENT_HAS_ENABLED_CHILDREN');
  const disabled = refused?.arguments?.contexts?.[0]?.options?.disable?.map(String);
  add('запрос отключения «Европа» без дочерних отклонён сервисом',
    Boolean(refused) && disabled?.includes(String(byValue['Европа']))
      && !disabled.includes(String(byValue['Европа/Франция'])) && !disabled.includes(String(byValue['Европа/Германия']))
      && !parseResult(refused.result).planId,
    JSON.stringify(refused?.arguments));
  const refusal = parseResult(refused?.result);
  add('отказ перечисляет включённые дочерние опции', /Франция/u.test(refusal?.message ?? '') && /Германия/u.test(refusal?.message ?? ''),
    refusal?.message);
  const after = Array.from({ length: Math.max(1, ...trace.map((entry) => entry.turn ?? 1)) }, (_, index) => ({ turn: index + 1, reply: text.assistant(index + 1) }))
    .filter(({ turn }) => turn >= (refused?.turn ?? Infinity));
  const explained = after.map(({ reply }) => reply).join('\n');
  add('ассистент объяснил отказ: родителя только вместе с дочерними',
    /дочерн|потомк|вложенн/iu.test(explained) && /вместе|одновременно|только\s+(?:если|когда|после)/iu.test(explained) && /Франци|Германи/u.test(explained),
    explained.slice(0, 600));
  add('ни одного start_operation и ни одной записи',
    !trace.some((entry) => entry.tool === 'start_operation' || entry.arguments?.dryRun === false), 'записей нет');

  const final = await readOptions(tool, fieldId, contextId);
  add('живое чтение: все опции включены, идентификаторы прежние',
    final.items.length === 5 && final.items.every((item) => item.disabled === false)
      && same(Object.entries(final.byValue).sort(), Object.entries(byValue).sort()),
    JSON.stringify(final.items));
  return checks;
}
