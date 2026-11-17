import { createOptionsField } from '../../options-fixture.mjs';

// All fixtures are isolated by the runner's run-specific names and keys.
export default async function setup({ names, runId, tool, apply, rest, log }) {
  const prepared = await createOptionsField({ names, runId, apply, tool, rest, label: 'Приоритет',
    options: ['Низкий', 'Средний', 'Высокий'].map((value) => ({ value })) });
  const values = prepared.items.map((item) => item.value);
  if (values.join(',') !== 'Низкий,Средний,Высокий' || prepared.items.some((item) => item.disabled))
    throw new Error(`Неверные исходные опции: ${JSON.stringify(prepared.items)}`);
  log(`Подготовлено ${prepared.fieldId}, контекст ${prepared.contextId}`);
  return prepared;
}
