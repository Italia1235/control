import { createOptionsField } from '../../options-fixture.mjs';

// All fixtures are isolated by the runner's run-specific names and keys.
export default async function setup({ names, runId, tool, apply, rest, log }) {
  const prepared = await createOptionsField({ names, runId, apply, tool, rest, label: 'Статус заказа',
    options: ['Один', 'Два'].map((value) => ({ value })) });
  if (prepared.items.map((item) => item.value).join(',') !== 'Один,Два' || prepared.items.some((item) => item.disabled))
    throw new Error(`Неверные исходные опции: ${JSON.stringify(prepared.items)}`);
  log(`Подготовлено ${prepared.fieldId}, контекст ${prepared.contextId}`);
  return prepared;
}
