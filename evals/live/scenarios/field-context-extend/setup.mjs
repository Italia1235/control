import { projectManagement } from '../../project-templates.mjs';
// Fresh projects and field; new global issue types also become available in Jira's default type scheme.
export async function prepare({ names, runId, tool, apply, rest, adminPage, log }, conflict = false) {
  const source = names['KEY:source'];
  const target = names['KEY:target'];
  const fieldName = `Поставщик ${runId}`;
  const purchaseType = `Задача на закупку ${runId}`;
  const task = (await rest('GET', '/rest/api/2/issuetype')).find((type) => type.name === 'Task');
  if (!task) await apply('create_issue_types', { issueTypes: [{ key: 'task', name: 'Task', subtask: false }] });
  else if (task.subtask) throw new Error('Task должен быть стандартным типом, не подзадачей');
  await apply('create_issue_types', { issueTypes: [{ key: 'purchase', name: purchaseType, subtask: false }] });
  for (const [key, name] of [[source, names['NAME:source']], [target, names['NAME:target']]]) {
    await apply('create_project', projectManagement({ name, key, lead: 'admin' }));
  }
  await apply('set_project_issue_types', { project: source, issueTypes: [purchaseType, 'Task'] });
  await apply('set_project_issue_types', { project: target, issueTypes: [purchaseType] });
  for (const [key, expected] of [[source, [purchaseType, 'Task']], [target, [purchaseType]]]) {
    const project = await rest('GET', `/rest/api/2/project/${key}`);
    if (JSON.stringify(project.issueTypes.map((type) => type.name).sort()) !== JSON.stringify(expected.sort())) {
      throw new Error(`${key}: неожиданные типы задач`);
    }
  }
  log(`проекты ${source}, ${target}: Task есть только в исходном`);

  const contexts = [{ name: 'Закупки', projects: [source], issueTypes: [purchaseType, 'Task'] }];
  if (conflict) contexts.push({ name: 'Второй контекст', projects: [target], issueTypes: [purchaseType] });
  const created = await apply('create_custom_fields', {
    fields: [{ name: fieldName, typeKey: 'select', options: ['А', 'Б', 'В'].map((value) => ({ value })), contexts }],
  });
  const fieldId = created.report.created.find((item) => item.kind === 'CUSTOM_FIELD')?.id;
  if (!fieldId) throw new Error(`поле не создано: ${JSON.stringify(created)}`);
  const initial = await tool('get_field_contexts', { field: fieldId });
  const context = initial.contexts.find((item) => item.name === 'Закупки');
  if (!context || context.configs.length !== 1) throw new Error('нет единственной конфигурации «Закупки»');
  const configId = context.configs[0].configId;

  // Jira Server has no REST endpoint to set this default. Use its actual admin form, not database writes.
  const defaultPath = `/secure/admin/EditCustomFieldDefaults!default.jspa?fieldConfigSchemeId=${context.contextId}&fieldConfigId=${configId}`;
  const formOptions = (html) => {
    const select = html.match(new RegExp(`<select\\b[^>]*name="${fieldId}"[^>]*>([\\s\\S]*?)</select>`));
    if (!select) throw new Error('нет списка поля в форме значения по умолчанию');
    return [...select[1].matchAll(/<option\b([^>]*)>([^<]*)<\/option>/g)].map((match) => ({
      id: match[1].match(/value="([^"]+)"/)?.[1], value: match[2].trim(), selected: /\bselected\b/.test(match[1]),
    })).filter((option) => option.id !== '-1');
  };
  const form = await adminPage(defaultPath);
  const options = formOptions(form.text);
  const defaultOption = options.find((option) => option.value === 'Б');
  if (options.map((option) => option.value).join(',') !== 'А,Б,В' || !defaultOption) {
    throw new Error('неверные опции исходного контекста');
  }
  const token = form.text.match(/name="atl_token"[\s\S]*?value="([^"]+)"/)?.[1];
  if (!token) throw new Error('нет XSRF-токена формы');
  await adminPage('/secure/admin/EditCustomFieldDefaults.jspa', {
    fieldConfigSchemeId: String(context.contextId), fieldConfigId: String(configId),
    [fieldId]: defaultOption.id, atl_token: token, 'Set Default': 'Set Default',
  });
  const savedOptions = formOptions((await adminPage(defaultPath)).text);
  if (savedOptions.find((option) => option.selected)?.id !== defaultOption.id) {
    throw new Error('значение по умолчанию «Б» не сохранено');
  }

  // Put the field on private target screens before the dialogue; context extension alone must make it visible.
  let screens = await tool('get_project_screens', { project: target });
  if (screens.issueTypes.some((type) => type.screenScheme.screens.some((screen) => screen.otherProjects > 0))) {
    await apply('clone_project_screens', { project: target, scope: 'ALL', copyScreens: true });
    screens = await tool('get_project_screens', { project: target });
  }
  const screenIds = [...new Set(screens.issueTypes.flatMap((type) => type.screenScheme.screens.map((screen) => screen.id)))];
  if (!screenIds.length) throw new Error('нет экранов целевого проекта');
  await apply('add_field_to_screens', { fieldIds: [fieldId], screens: screenIds.map((screenId) => ({ screenId })) });

  const baseline = await tool('get_field_contexts', { field: fieldId });
  const primary = baseline.contexts.find((item) => item.contextId === context.contextId);
  const secondary = baseline.contexts.find((item) => item.name === 'Второй контекст');
  if (baseline.status !== 'READ' || baseline.contexts.length !== (conflict ? 2 : 1)
      || primary?.configs[0]?.configId !== configId || primary.options?.count !== 3
      || primary.options?.disabledCount !== 0 || primary.defaultValue !== 'PRESENT'
      || primary.projects.map((project) => project.key).join(',') !== source
      || (conflict && secondary?.projects.map((project) => project.key).join(',') !== target)) {
    throw new Error(`неверное исходное состояние: ${JSON.stringify(baseline)}`);
  }
  log(`поле ${fieldId}, контекст ${context.contextId}, configId ${configId}, три опции, по умолчанию Б${conflict ? ', конфликт подготовлен' : ''}`);
  return {
    source, target, fieldName, fieldId, purchaseType, contextId: context.contextId, configId,
    options: savedOptions, defaultValue: { id: defaultOption.id, value: 'Б' },
    conflictContextId: secondary?.contextId ?? null, screenIds, baseline,
  };
}

export default async function setup(context) {
  return prepare(context);
}
