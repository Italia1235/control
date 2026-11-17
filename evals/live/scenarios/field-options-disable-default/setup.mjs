import { createOptionsField, readOptions, setDefault } from '../../options-fixture.mjs';

// All fixtures are isolated by the runner's run-specific names and keys.
export default async function setup({ names, runId, tool, apply, rest, adminPage, log }) {
  const prepared = await createOptionsField({ names, runId, apply, tool, rest, label: 'Категория',
    options: ['А', 'Б', 'В'].map((value) => ({ value })) });
  const defaultValue = await setDefault({ adminPage, fieldId: prepared.fieldId, contextId: prepared.contextId,
    configId: prepared.configId, value: 'Б' });
  const read = await readOptions(tool, prepared.fieldId, prepared.contextId);
  const defaults = read.items.filter((item) => item.default === true).map((item) => item.value);
  if (read.items.map((item) => item.value).join(',') !== 'А,Б,В' || defaults.join(',') !== 'Б')
    throw new Error(`Неверные исходные опции или default: ${JSON.stringify(read.items)}`);
  log(`Подготовлено ${prepared.fieldId}, контекст ${prepared.contextId}, default «Б»`);
  return { ...prepared, ...read, defaultValue };
}
