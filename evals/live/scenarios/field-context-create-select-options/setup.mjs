import { projectManagement } from '../../project-templates.mjs';
// Fresh target/source projects; a select and a text field, each with one old context source × Task only.
const decode = (text) => text.replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
  .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');

/** Options of one context as Jira's own default-value form lists them, in display order. */
export async function contextOptions(adminPage, fieldId, contextId, configId) {
  const page = await adminPage(`/secure/admin/EditCustomFieldDefaults!default.jspa?fieldConfigSchemeId=${contextId}&fieldConfigId=${configId}`);
  const select = page.text.match(new RegExp(`<select\\b[^>]*name="${fieldId}"[^>]*>([\\s\\S]*?)</select>`));
  if (!select) throw new Error(`нет списка ${fieldId} в форме default контекста ${contextId}`);
  return [...select[1].matchAll(/<option\b([^>]*)>([^<]*)<\/option>/g)].map((match) => ({
    id: match[1].match(/value="([^"]+)"/)?.[1], value: decode(match[2].trim()), selected: /\bselected\b/.test(match[1]),
  })).filter((option) => option.id !== '-1');
}

export default async function setup({ names, runId, tool, apply, rest, adminPage, log }) {
  const keys = { target: names['KEY:target'], source: names['KEY:source'] };
  const types = await rest('GET', '/rest/api/2/issuetype');
  for (const name of ['Bug', 'Task']) {
    const found = types.find((type) => type.name === name);
    if (!found) await apply('create_issue_types', { issueTypes: [{ key: 'type', name, subtask: false }] });
    else if (found.subtask) throw new Error(`${name}: неожиданный подтип`);
  }
  for (const role of ['target', 'source']) {
    await apply('create_project', projectManagement({ name: names[`NAME:${role}`], key: keys[role], lead: 'admin' }));
    await apply('set_project_issue_types', { project: keys[role], issueTypes: ['Bug', 'Task'] });
  }
  const selectName = `Система ${runId}`, textName = `Серийный номер ${runId}`;
  const created = await apply('create_custom_fields', { fields: [
    { name: selectName, typeKey: 'select', options: ['КА Demo1', 'ШС', 'ТК'].map((value) => ({ value })),
      contexts: [{ name: `Старый ${runId}`, projects: [keys.source], issueTypes: ['Task'] }] },
    { name: textName, typeKey: 'textfield',
      contexts: [{ name: `Старый ${runId}`, projects: [keys.source], issueTypes: ['Task'] }] },
  ] });
  const fieldIds = (created.report?.created ?? []).filter((item) => item.kind === 'CUSTOM_FIELD');
  const selectId = fieldIds.find((item) => item.name === selectName)?.id;
  const textId = fieldIds.find((item) => item.name === textName)?.id;
  if (!selectId || !textId) throw new Error(`поля не созданы: ${JSON.stringify(created)}`);

  const selectBaseline = await tool('get_field_contexts', { field: selectId });
  const textBaseline = await tool('get_field_contexts', { field: textId });
  for (const baseline of [selectBaseline, textBaseline]) {
    if (baseline.contexts?.length !== 1 || baseline.contexts[0].projects.some((project) => project.key === keys.target)) {
      throw new Error(`неверное исходное состояние: ${JSON.stringify(baseline)}`);
    }
  }
  const old = selectBaseline.contexts[0];
  const oldOptions = await contextOptions(adminPage, selectId, old.contextId, old.configs[0].configId);
  if (oldOptions.map((option) => option.value).join('|') !== 'КА Demo1|ШС|ТК') {
    throw new Error(`неверные опции старого контекста: ${JSON.stringify(oldOptions)}`);
  }
  log(`Система ${selectId}, Серийный номер ${textId}: по одному контексту ${keys.source} × Task; ${keys.target} вне контекстов`);
  return { keys, selectName, textName, selectId, textId, selectBaseline, textBaseline, oldOptions };
}
