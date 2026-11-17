import { applies, applyOutcome, isYes, optionChecks, optionPreviews, parseResult, turnTexts } from '../../checks.mjs';
import { readOptions } from '../../options-fixture.mjs';

const same = (actual, expected) => JSON.stringify(actual) === JSON.stringify(expected);

export default async function verify({ prepared, trace, conversation, tool }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  if (!prepared?.fieldId || !prepared.byValue) return [{ name: 'автоматическая подготовка сохранена', ok: false, detail: 'нет полного setup.json' }];
  const { fieldId, contextId, configId, byValue } = prepared;
  const text = turnTexts(conversation);

  checks.push(...optionChecks(trace, conversation));
  const previewed = optionPreviews(trace);
  const [preview] = previewed;
  add('ровно одно превью опций', previewed.length === 1, `превью: ${previewed.length}`);
  const context = preview?.arguments?.contexts?.[0];
  const options = context?.options;
  add('превью только с options, без осей области',
    preview?.arguments?.contexts?.length === 1 && String(context.contextId) === String(contextId)
      && context.projects === undefined && context.allProjects === undefined
      && context.issueTypes === undefined && context.allIssueTypes === undefined && context.assets === undefined,
    JSON.stringify(preview?.arguments));
  add('переименование, добавление и порядок одним запросом',
    same(options?.rename?.map((item) => [String(item.optionId), item.value]), [[String(byValue['Средний']), 'Нормальный']])
      && same(options?.add?.map((item) => [item.value, item.parentOptionId]), [['Срочный', undefined]])
      && options?.order?.length === 1 && options.order[0].parentOptionId === undefined
      && same(options.order[0].optionIds.map(String), [byValue['Высокий'], byValue['Низкий'], byValue['Средний']].map(String))
      && !options.disable?.length && !options.enable?.length,
    JSON.stringify(options));
  const shown = parseResult(preview?.result);
  const rows = shown?.contexts?.[0]?.options?.rows ?? [];
  add('превью: WOULD_UPDATE и строки RENAMED, ADDED, MOVED',
    shown?.status === 'WOULD_UPDATE' && ['RENAMED', 'ADDED', 'MOVED'].every((kind) => rows.some((row) => row.changes?.includes(kind))),
    JSON.stringify(rows));

  const [apply, ...more] = applies(trace, 'update_field_contexts');
  add('одно применение по planId этого превью в ходе после «Да»',
    Boolean(apply) && apply.planId === shown?.planId && more.length === 0
      && apply.start.turn > preview.turn && isYes(text.user(apply.start.turn)),
    `${apply ? `превью ход ${preview?.turn}, apply ход ${apply.start.turn}: ${text.user(apply.start.turn)}` : 'нет apply'}`);
  const { result } = applyOutcome(trace, apply);
  add('результат применения UPDATED', result?.status === 'UPDATED' && result.contexts?.[0]?.verdict === 'UPDATED', JSON.stringify(result));
  add('правка опций не требует переиндексации', result?.reindexRecommended === false, JSON.stringify(result?.reindexRecommended));

  const final = await readOptions(tool, fieldId, contextId);
  add('живое чтение: порядок и состояние опций',
    same(final.items.map((item) => item.value), ['Высокий', 'Низкий', 'Нормальный', 'Срочный'])
      && final.items.every((item) => item.disabled === false)
      && same(final.items.map((item) => item.position), [0, 1, 2, 3]),
    JSON.stringify(final.items));
  add('исходные опции сохранили идентификаторы', ['Низкий', 'Высокий'].every((value) => final.byValue[value] === byValue[value])
    && final.byValue['Нормальный'] === byValue['Средний'], JSON.stringify(final.byValue));
  const contexts = await tool('get_field_contexts', { field: fieldId });
  add('контекст и конфигурация прежние',
    contexts.contexts?.length === 1 && contexts.contexts[0].contextId === contextId && contexts.contexts[0].configs?.[0]?.configId === configId,
    JSON.stringify(contexts.contexts?.map((item) => [item.contextId, item.configs])));
  return checks;
}
