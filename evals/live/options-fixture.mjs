// Shared preparation of the field-options-* dialogues: an isolated project and a select / cascading select field whose
// "Основной" context has known options. Used by their setup.mjs and hooks.mjs; reads and writes go through the plugin's tools
// and Jira's own admin forms, never through mocks.

import { optionItems } from './checks.mjs';
import { projectManagement } from './project-templates.mjs';

/**
 * Creates the project and the field. `options` is [{ value, children? }]; the first context of the field is the project's.
 * Returns { fieldId, fieldName, project, contextId, configId, byValue: { value: optionId }, items }.
 */
export async function createOptionsField({ names, runId, apply, tool, rest, label, typeKey = 'select', options }) {
  const key = names['KEY:proj'];
  await apply('create_project', projectManagement({ name: names['NAME:proj'], key, lead: 'admin' }));
  const project = await rest('GET', `/rest/api/2/project/${key}`);
  const fieldName = `${label} ${runId}`;
  const created = await apply('create_custom_fields', {
    fields: [{ name: fieldName, typeKey, contexts: [
      { name: 'Основной', projects: [project.key], allIssueTypes: true, options },
    ] }],
  });
  const fieldId = created.report?.created?.find((item) => item.kind === 'CUSTOM_FIELD')?.id;
  if (!fieldId) throw new Error(`Поле не создано: ${JSON.stringify(created)}`);
  const contexts = await tool('get_field_contexts', { field: fieldId });
  const context = contexts.contexts?.find((item) => item.name === 'Основной');
  if (!context || context.configs?.length !== 1) throw new Error(`Нет единственной конфигурации «Основной»: ${JSON.stringify(contexts)}`);
  const read = await readOptions(tool, fieldId, context.contextId);
  return { fieldId, fieldName, project: { key: project.key, id: String(project.id) }, contextId: context.contextId,
    configId: context.configs[0].configId, ...read };
}

/** Options of one context through the plugin's own read: { items, byValue } (top-level values; children as "Родитель/Ребёнок"). */
export async function readOptions(tool, fieldId, contextId) {
  const result = await tool('get_field_contexts', { field: fieldId, contextId, includeOptions: true });
  if (result.status !== 'READ') throw new Error(`Чтение опций не удалось: ${JSON.stringify(result)}`);
  const items = optionItems(result, contextId);
  const valueOf = new Map(items.map((item) => [item.id, item.value]));
  const byValue = Object.fromEntries(items.map((item) => [
    item.parentId === null || item.parentId === undefined ? item.value : `${valueOf.get(item.parentId)}/${item.value}`, item.id]));
  return { items, byValue };
}

/** Sets the context's default value through Jira's own "Set Default" form and checks it through the form again. */
export async function setDefault({ adminPage, fieldId, contextId, configId, value }) {
  const path = `/secure/admin/EditCustomFieldDefaults!default.jspa?fieldConfigSchemeId=${contextId}&fieldConfigId=${configId}`;
  const choices = (html) => {
    const select = html.match(new RegExp(`<select\\b[^>]*name="${fieldId}"[^>]*>([\\s\\S]*?)</select>`));
    if (!select) throw new Error('Нет списка поля в форме значения по умолчанию');
    return [...select[1].matchAll(/<option\b([^>]*)>([^<]*)<\/option>/g)].map((match) => ({
      id: match[1].match(/value="([^"]+)"/)?.[1], value: match[2].trim(), selected: /\bselected\b/.test(match[1]),
    })).filter((option) => option.id && option.id !== '-1' && option.id !== '');
  };
  const form = await adminPage(path);
  const wanted = choices(form.text).find((option) => option.value === value);
  const token = form.text.match(/name="atl_token"[\s\S]*?value="([^"]+)"/)?.[1];
  if (!wanted || !token) throw new Error(`Нет опции «${value}» или XSRF-токена в форме значения по умолчанию`);
  await adminPage('/secure/admin/EditCustomFieldDefaults.jspa', {
    fieldConfigSchemeId: String(contextId), fieldConfigId: String(configId), [fieldId]: wanted.id,
    atl_token: token, 'Set Default': 'Set Default',
  });
  if (choices((await adminPage(path)).text).find((option) => option.selected)?.id !== wanted.id)
    throw new Error(`Значение по умолчанию «${value}» не сохранено`);
  return { id: wanted.id, value };
}

/**
 * A real concurrent change of the options by another administrator, through Jira's own "Add option" form (not through
 * this plugin). Proves itself by reading the options back; throws when the new option is not there.
 */
export async function addOptionLikeAnotherAdmin({ adminPage, tool, prepared, value }) {
  const page = `/secure/admin/EditCustomFieldOptions!default.jspa?fieldConfigId=${prepared.configId}&fieldId=${prepared.fieldId}`;
  const editor = await adminPage(page);
  const token = editor.text.match(/name="atl_token"[\s\S]*?value="([^"]+)"/)?.[1]
    ?? editor.text.match(/name="atlassian-token"\s+content="([^"]+)"/)?.[1];
  if (!token) throw new Error('Нет XSRF-токена административной формы опций');
  const response = await adminPage('/secure/admin/EditCustomFieldOptions!add.jspa', [
    ['fieldConfigId', String(prepared.configId)], ['addValue', value], ['selectedParentOptionId', ''], ['atl_token', token],
  ]);
  const after = await readOptions(tool, prepared.fieldId, prepared.contextId);
  if (!(value in after.byValue)) throw new Error(`Вмешательство через форму Jira не добавило опцию «${value}»: HTTP ${response.status}`);
  return { value, status: response.status, after };
}
