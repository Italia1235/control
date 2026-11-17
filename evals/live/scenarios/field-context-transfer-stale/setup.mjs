import { parseResult } from '../../checks.mjs';
import { projectManagement } from '../../project-templates.mjs';

// All fixtures are isolated by the runner's run-specific names and keys.
export default async function setup({ names, runId, tool, probe, apply, rest, adminPage, log }) {
  const projects = {};
  for (const role of ['source', 'moving', 'warehouse', 'concurrent']) {
    const key = names[`KEY:${role}`];
    await apply('create_project', projectManagement({ name: names[`NAME:${role}`], key, lead: 'admin' }));
    const project = await rest('GET', `/rest/api/2/project/${key}`);
    projects[role] = { key: project.key, id: String(project.id) };
  }

  const fieldName = `Поставщик ${runId}`;
  const created = await apply('create_custom_fields', {
    fields: [{ name: fieldName, typeKey: 'select', contexts: [
      { name: 'Основной', projects: [projects.source.key, projects.moving.key], allIssueTypes: true,
        options: ['А', 'Б', 'В'].map((value) => ({ value })) },
      { name: 'Склад', projects: [projects.warehouse.key], allIssueTypes: true,
        options: ['Г', 'Д'].map((value) => ({ value })) },
    ] }],
  });
  const fieldId = created.report.created.find((item) => item.kind === 'CUSTOM_FIELD')?.id;
  if (!fieldId) throw new Error(`Поле не создано: ${JSON.stringify(created)}`);
  const initial = await tool('get_field_contexts', { field: fieldId });
  const contextData = (name) => {
    const context = initial.contexts?.find((item) => item.name === name);
    if (!context || context.configs.length !== 1) throw new Error(`Нет единственной конфигурации «${name}»`);
    return { contextId: context.contextId, configId: context.configs[0].configId };
  };
  const main = contextData('Основной');
  const warehouse = contextData('Склад');
  const defaultPath = (context) => `/secure/admin/EditCustomFieldDefaults!default.jspa?fieldConfigSchemeId=${context.contextId}&fieldConfigId=${context.configId}`;
  const formOptions = (html) => {
    const select = html.match(new RegExp(`<select\\b[^>]*name="${fieldId}"[^>]*>([\\s\\S]*?)</select>`));
    if (!select) throw new Error('Нет списка поля в форме значения по умолчанию');
    return [...select[1].matchAll(/<option\b([^>]*)>([^<]*)<\/option>/g)].map((match) => ({
      id: match[1].match(/value="([^"]+)"/)?.[1], value: match[2].trim(), selected: /\bselected\b/.test(match[1]),
    })).filter((option) => option.id && option.id !== '-1' && option.id !== '');
  };
  const form = await adminPage(defaultPath(main));
  const options = formOptions(form.text);
  const defaultOption = options.find((option) => option.value === 'Б');
  if (options.map((option) => option.value).join(',') !== 'А,Б,В' || !defaultOption) {
    throw new Error('Неверные опции основного контекста');
  }
  const token = form.text.match(/name="atl_token"[\s\S]*?value="([^"]+)"/)?.[1];
  if (!token) throw new Error('Нет XSRF-токена формы');
  await adminPage('/secure/admin/EditCustomFieldDefaults.jspa', {
    fieldConfigSchemeId: String(main.contextId), fieldConfigId: String(main.configId),
    [fieldId]: defaultOption.id, atl_token: token, 'Set Default': 'Set Default',
  });
  const savedOptions = formOptions((await adminPage(defaultPath(main))).text);
  if (savedOptions.find((option) => option.selected)?.id !== defaultOption.id) {
    throw new Error('Значение по умолчанию «Б» не сохранено');
  }
  main.options = savedOptions.map(({ id, value }) => ({ id, value }));
  main.defaultValue = { id: defaultOption.id, value: defaultOption.value };
  warehouse.options = formOptions((await adminPage(defaultPath(warehouse))).text).map(({ id, value }) => ({ id, value }));
  if (warehouse.options.map((option) => option.value).join(',') !== 'Г,Д') throw new Error('Неверные опции склада');

  const baseline = await tool('get_field_contexts', { field: fieldId });
  for (const [context, expectedProjects, expectedCount, expectedDefault] of [
    [main, [projects.source.key, projects.moving.key], 3, 'PRESENT'],
    [warehouse, [projects.warehouse.key], 2, 'ABSENT'],
  ]) {
    const actual = baseline.contexts?.find((item) => item.contextId === context.contextId);
    if (baseline.status !== 'READ' || baseline.contexts.length !== 2
        || actual?.configs?.length !== 1 || actual.configs[0].configId !== context.configId
        || actual.options?.count !== expectedCount || actual.options?.disabledCount !== 0
        || actual.defaultValue !== expectedDefault
        || JSON.stringify(actual.projects.map((project) => project.key).sort()) !== JSON.stringify(expectedProjects.sort())
        || actual.allIssueTypes !== true) throw new Error(`Неверное исходное состояние: ${JSON.stringify(baseline)}`);
  }
  const invalidPreview = parseResult((await probe('update_field_contexts', { dryRun: true, fieldId, contexts: [
    { contextId: warehouse.contextId, projects: [projects.warehouse.key, projects.moving.key], allIssueTypes: true },
  ] })).data);
  if (invalidPreview.errorCode !== 'PROJECT_IN_OTHER_CONTEXT' || invalidPreview.planId) {
    throw new Error(`Ожидался конфликт без planId: ${JSON.stringify(invalidPreview)}`);
  }
  log(`Подготовлены ${fieldId}, контексты ${main.contextId}/${warehouse.contextId}`);
  return { fieldId, fieldName, projects, main, warehouse, baseline, invalidPreview };
}
