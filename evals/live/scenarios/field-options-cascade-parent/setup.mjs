import { createOptionsField } from '../../options-fixture.mjs';

// All fixtures are isolated by the runner's run-specific names and keys.
export default async function setup({ names, runId, tool, apply, rest, log }) {
  const prepared = await createOptionsField({ names, runId, apply, tool, rest, label: 'Регион', typeKey: 'cascadingselect',
    options: [
      { value: 'Европа', children: [{ value: 'Франция' }, { value: 'Германия' }] },
      { value: 'Азия', children: [{ value: 'Япония' }] },
    ] });
  const expected = ['Европа', 'Азия', 'Европа/Франция', 'Европа/Германия', 'Азия/Япония'].sort();
  if (JSON.stringify(Object.keys(prepared.byValue).sort()) !== JSON.stringify(expected) || prepared.items.some((item) => item.disabled))
    throw new Error(`Неверные исходные опции: ${JSON.stringify(prepared.items)}`);
  log(`Подготовлено ${prepared.fieldId}, контекст ${prepared.contextId}`);
  return prepared;
}
