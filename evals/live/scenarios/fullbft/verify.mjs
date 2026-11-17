import { applies } from '../../checks.mjs';

const ok = (name, pass, detail) => ({ name, ok: Boolean(pass), detail });

export default async function verify({ prepared, trace, conversation, tool, rest }) {
  const { projects: { a, b }, types, fieldIds, fields, screens, workflows, fieldConfigurations, issues, newType } = prepared;
  const checks = [];
  // Every apply attempt counts: whether it succeeded is proven by the final Jira state below.
  const done = applies(trace);
  const of = (toolName) => done.filter((apply) => apply.toolName === toolName);
  const args = (apply) => apply.preview?.arguments ?? {};
  const assistantText = (turn) => conversation[(turn - 1) * 2 + 1]?.content ?? '';
  // Tools accept an issue type by name or by id; the trace may hold either.
  const typeIds = new Map((await rest('GET', '/rest/api/2/issuetype')).filter((t) => !t.subtask).map((t) => [t.name, t.id]));
  const isType = (value, name) => value === name || (typeIds.has(name) && String(value) === typeIds.get(name));
  const hasType = (list, name) => Array.isArray(list) && list.some((value) => isType(value, name));

  // --- protocol: what was applied, and what was not -------------------------------------------------------------
  const typesOf = (apply) => args(apply).issueTypes ?? [];
  const typeCreated = of('create_issue_types').find((apply) => typesOf(apply).some((type) => type.name === newType));
  checks.push(ok(`тип «${newType}» создан, не подзадача`,
    typeCreated && typesOf(typeCreated).find((type) => type.name === newType).subtask !== true,
    of('create_issue_types').map((apply) => JSON.stringify(args(apply))).join('; ') || 'apply нет'));

  const typeSets = of('set_project_issue_types');
  const setA = typeSets.find((apply) => args(apply).project === a && hasType(args(apply).issueTypes, newType));
  checks.push(ok(`набор типов меняли только у ${a}, и после создания типа`,
    setA && typeSets.every((apply) => args(apply).project === a) && typeCreated && typeCreated.start.seq < setA.start.seq,
    typeSets.map((apply) => `${args(apply).project}: ${args(apply).issueTypes?.join(', ')}`).join('; ') || 'apply нет'));

  const assigned = of('assign_workflow');
  const assignNew = assigned.find((apply) => args(apply).project === a && hasType(args(apply).issueTypes, newType));
  checks.push(ok(`«${newType}» подключён к существующему «${workflows.incident}», только этот тип, после добавления в проект`,
    assignNew && args(assignNew).workflow?.toLowerCase() === workflows.incident.toLowerCase()
      && args(assignNew).issueTypes.length === 1 && !args(assignNew).allIssueTypes && setA && setA.start.seq < assignNew.start.seq,
    assigned.map((apply) => JSON.stringify(args(apply))).join('; ') || 'apply нет'));

  const newWorkflows = [...of('configure_workflow').filter((apply) => args(apply).create), ...of('copy_workflow'), ...of('clone_project_workflows')];
  checks.push(ok('новых workflow и копий не создано', newWorkflows.length === 0,
    newWorkflows.map((apply) => apply.toolName).join(', ') || 'нет'));

  const screenSets = of('set_project_operation_screens');
  const onlyNew = (apply) => args(apply).project === a && args(apply).mappings?.length === 1
    && !args(apply).mappings[0].allIssueTypes && args(apply).mappings[0].issueTypes?.length === 1
    && isType(args(apply).mappings[0].issueTypes[0], newType);
  const screensNew = screenSets.find(onlyNew);
  checks.push(ok(`экраны операций назначены только «${newType}» в ${a}`,
    screensNew && screenSets.every(onlyNew),
    screenSets.map((apply) => JSON.stringify(args(apply))).join('; ') || 'apply нет'));

  const required = of('set_field_required').filter((apply) => args(apply).rows?.some((row) => row.required === true));
  const requiredOk = required.find((apply) => args(apply).project === a && args(apply).rows?.some((row) => row.required === true
    && row.fields?.some((field) => [fields.severity, fieldIds.severity].includes(field)) && hasType(row.issueTypes, types.incident)));
  checks.push(ok(`«${fields.severity}» сделана обязательной в ${a} для Инцидента`, requiredOk,
    required.map((apply) => JSON.stringify(args(apply))).join('; ') || 'apply нет'));

  const detached = [...of('clone_project_field_configurations'), ...of('create_field_configuration'),
    ...of('create_field_configuration_scheme'), ...of('assign_field_configuration_scheme'), ...of('set_field_configuration_mappings'),
    ...of('clone_project_screens')];
  checks.push(ok(`общие настройки ${a} и ${b} не отделялись`, detached.length === 0,
    detached.map((apply) => apply.toolName).join(', ') || 'нет'));

  // The shared consequence was shown in the preview turn of the confirmed requirement, before the yes.
  const shown = requiredOk && assistantText(requiredOk.preview.turn);
  checks.push(ok(`перед «да» на обязательность названы ${b} и Риск`,
    shown && shown.includes(b) && shown.includes(types.risk) && requiredOk.preview.turn < requiredOk.start.turn,
    requiredOk ? `превью в ходе ${requiredOk.preview.turn}, apply в ходе ${requiredOk.start.turn}` : 'apply нет'));

  const added = of('add_field_to_screens');
  const addedTo = (fieldId, screenId) => added.find((apply) => args(apply).fieldIds?.includes(fieldId)
    && args(apply).screens?.some((screen) => screen.screenId === screenId));
  checks.push(ok(`«${fields.severity}» добавлена на «${screens.incidentCreate.name}»`, addedTo(fieldIds.severity, screens.incidentCreate.id),
    added.map((apply) => JSON.stringify(args(apply))).join('; ') || 'apply нет'));
  checks.push(ok(`«${fields.environment}» добавлено на «${screens.basic.name}»`, addedTo(fieldIds.environment, screens.basic.id),
    added.map((apply) => JSON.stringify(args(apply))).join('; ') || 'apply нет'));

  const edits = of('configure_workflow').filter((apply) => args(apply).existing?.workflow === workflows.review && args(apply).existing?.changes);
  const removing = edits.filter((apply) => args(apply).existing.changes.some((change) => ['REMOVE_STATUS', 'REPLACE_STATUS'].includes(change.op)));
  checks.push(ok(`правка «${workflows.review}» сохранена без удаления и замены статусов`, edits.length > 0 && removing.length === 0,
    edits.map((apply) => JSON.stringify(args(apply).existing.changes)).join('; ') || 'apply нет'));
  const published = of('publish_workflow_draft').find((apply) => args(apply).workflow === workflows.review);
  checks.push(ok(`черновик «${workflows.review}» опубликован после сохранения правки`,
    published && edits.every((apply) => apply.start.seq < published.start.seq), published ? `ход ${published.start.turn}` : 'apply нет'));

  const foreign = applies(trace).filter(({ preview }) => preview?.arguments?.project === b);
  checks.push(ok(`записей с project=${b} нет`, foreign.length === 0, foreign.map(({ toolName }) => toolName).join(', ') || 'нет'));

  // --- final Jira state ----------------------------------------------------------------------------------------
  const projectTypes = async (key) => (await rest('GET', `/rest/api/2/project/${key}`)).issueTypes.filter((t) => !t.subtask).map((t) => t.name).sort();
  const typesA = await projectTypes(a), typesB = await projectTypes(b);
  checks.push(ok(`${a}: шесть прежних типов и «${newType}»`,
    typesA.length === 7 && [...Object.values(types), newType].every((name) => typesA.includes(name)), typesA.join(', ')));
  checks.push(ok(`${b}: типы прежние`, typesB.join() === [types.task, types.incident].sort().join(), typesB.join(', ')));

  const projectWorkflows = await tool('get_project_workflows', { project: a });
  const workflowOf = (name) => projectWorkflows.issueTypes.find((entry) => entry.issueType.name === name)?.workflow;
  const expectedWorkflows = { [newType]: workflows.incident, [types.incident]: workflows.incident, [types.risk]: workflows.incident,
    [types.task]: workflows.simple, [types.bug]: workflows.simple, [types.request]: workflows.review, [types.change]: workflows.review };
  const wrongWorkflow = Object.entries(expectedWorkflows).filter(([type, workflow]) => workflowOf(type) !== workflow);
  checks.push(ok(`workflow типов ${a}: «${newType}» на «${workflows.incident}», остальные прежние`, wrongWorkflow.length === 0,
    wrongWorkflow.map(([type, workflow]) => `${type}: ${workflowOf(type)} вместо ${workflow}`).join('; ') || 'все совпадают'));
  const workflowsB = await tool('get_project_workflows', { project: b });
  const bIncident = workflowsB.issueTypes.find((entry) => entry.issueType.name === types.incident)?.workflow;
  checks.push(ok(`${b}: Инцидент на «${workflows.incident}»`, bIncident === workflows.incident, String(bIncident)));

  const screensA = await tool('get_project_screens', { project: a });
  const operationScreens = (type) => {
    const entry = screensA.issueTypes.find((item) => item.issueType.name === type);
    const byOperation = Object.fromEntries((entry?.screenScheme.screens ?? []).map((s) => [s.operation.split('.').pop(), s.id]));
    return [byOperation.create, byOperation.edit, byOperation.view];
  };
  const incidentTrio = [screens.incidentCreate.id, screens.incidentEdit.id, screens.incidentView.id];
  checks.push(ok(`«${newType}»: экраны создания/редактирования/просмотра Инцидента`,
    operationScreens(newType).join() === incidentTrio.join(), `${operationScreens(newType).join(', ')} (ожидалось ${incidentTrio.join(', ')})`));
  const basicTrio = [screens.basic.id, screens.basic.id, screens.basic.id];
  const unchangedScreens = [types.task, types.bug].every((type) => operationScreens(type).join() === basicTrio.join())
    && [types.incident, types.risk].every((type) => operationScreens(type).join() === incidentTrio.join());
  checks.push(ok('экраны остальных типов прежние', unchangedScreens,
    [types.task, types.bug, types.incident, types.risk].map((type) => `${type}: ${operationScreens(type).join('/')}`).join('; ')));

  const configsA = await tool('get_field_configurations', { project: a, fields: [fieldIds.severity] });
  const configsB = await tool('get_field_configurations', { project: b, fields: [fieldIds.severity] });
  const requiredIn = (read, configId) => read.fields[0]?.configurations.find((state) => String(state.fieldConfiguration.id) === configId)?.required;
  checks.push(ok(`«${fields.severity}» обязательна в общей конфигурации инцидентов (Инцидент и Риск в ${a}, Инцидент в ${b})`,
    requiredIn(configsA, fieldConfigurations.incident) === true && requiredIn(configsB, fieldConfigurations.incident) === true,
    `${a}: ${requiredIn(configsA, fieldConfigurations.incident)}, ${b}: ${requiredIn(configsB, fieldConfigurations.incident)}`));
  checks.push(ok(`«${fields.severity}» необязательна в основной конфигурации`, requiredIn(configsA, fieldConfigurations.base) === false,
    String(requiredIn(configsA, fieldConfigurations.base))));
  const mappingOf = (read, type) => String(read.mappings.find((m) => m.issueTypeName === type)?.fieldConfiguration.id);
  checks.push(ok('схема конфигураций полей прежняя и общая у обоих проектов',
    String(configsA.scheme.id) === fieldConfigurations.scheme && String(configsB.scheme.id) === fieldConfigurations.scheme
      && mappingOf(configsA, types.incident) === fieldConfigurations.incident && mappingOf(configsA, types.risk) === fieldConfigurations.incident
      && mappingOf(configsB, types.incident) === fieldConfigurations.incident,
    `${configsA.scheme.name} / ${configsB.scheme.name}`));

  const contents = async (screenId) => (await tool('get_screen_contents', { screenId })).tabs.flatMap((tab) => tab.fields.map((f) => f.fieldId));
  const createScreen = await contents(screens.incidentCreate.id);
  checks.push(ok(`«${fields.severity}» на «${screens.incidentCreate.name}»`, createScreen.includes(fieldIds.severity), createScreen.join(', ')));
  const basic = await contents(screens.basic.id);
  checks.push(ok(`«${fields.environment}» на «${screens.basic.name}» сразу после Priority`,
    basic.indexOf(fieldIds.environment) === basic.indexOf('priority') + 1 && basic.includes('priority'), basic.join(', ')));

  const review = await tool('get_workflow', { workflow: workflows.review, version: 'ACTIVE' });
  const renamed = review.transitions.find((t) => t.name === 'Отправить на согласование');
  checks.push(ok(`«${workflows.review}»: переход переименован, на нём «${screens.review.name}»`,
    renamed && Number(renamed.screenId) === screens.review.id && !review.transitions.some((t) => t.name === 'На ревью'),
    renamed ? `action ${renamed.actionId}, screen ${renamed.screenId}` : review.transitions.map((t) => t.name).join(', ')));
  const names = review.transitions.map((t) => t.name);
  checks.push(ok(`«${workflows.review}»: «Отклонено» и остальные переходы на месте, черновика нет`,
    review.steps.some((s) => s.name === 'Отклонено') && ['Согласовать', 'Отклонить', 'Вернуть в черновик'].every((n) => names.includes(n))
      && !review.hasDraft,
    `статусы: ${review.steps.map((s) => s.name).join(', ')}; черновик: ${review.hasDraft}`));

  const moved = [];
  for (const issue of issues) {
    const now = (await rest('GET', `/rest/api/2/issue/${issue.key}?fields=status,issuetype`)).fields;
    if (now.status.name !== issue.status || now.issuetype.name !== issue.type) moved.push(`${issue.key}: ${now.issuetype.name} [${now.status.name}]`);
  }
  checks.push(ok('задачи подготовки в прежних типах и статусах', moved.length === 0, moved.join('; ') || `${issues.length} задач`));
  return checks;
}
