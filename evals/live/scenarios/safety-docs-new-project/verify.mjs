import { applies } from '../../checks.mjs';
import { projectSnapshot, workflowGraph } from './setup.mjs';

const ok = (name, pass, detail) => ({ name, ok: Boolean(pass), detail });
const CF = 'com.atlassian.jira.plugin.system.customfieldtypes:';
const OPERATIONS = ['create', 'edit', 'view'];

export default async function verify({ runId, prepared, trace, tool, rest }) {
  const { project, projectName, lead, neighbour, sourceWorkflow, sourceGraph, neighbourState } = prepared;
  const checks = [];
  const t = {
    pay: `Оплаты ${runId}`, ka: `Работа с КА ${runId}`, paper: `Бумажные документы ${runId}`,
    consult: `Консультация ${runId}`, supply: `Снабжение ${runId}`,
  };
  // Custom fields of the request: type, issue types of its one context in the project, option count.
  const custom = {
    subtype: { name: `Тип подзадачи ${runId}`, type: 'select', types: [t.pay, t.ka, t.paper], options: 8 },
    contact: { name: `Контакт контрагента ${runId}`, type: 'textarea', types: [t.pay, t.ka] },
    payDue: { name: `Срок оплаты ${runId}`, type: 'datepicker', types: [t.pay] },
    details: { name: `Реквизиты компании ${runId}`, type: 'textfield', types: [t.ka] },
    office: { name: `Офис ${runId}`, type: 'select', types: [t.supply], options: 15 },
    recipient: { name: `Получатель ${runId}`, type: 'textfield', types: [t.supply] },
  };

  // --- protocol ---------------------------------------------------------------------------------------------------
  const done = applies(trace);
  const args = (apply) => apply.preview?.arguments ?? {};
  const copies = done.filter((apply) => apply.toolName === 'copy_workflow');
  const isCreate = (apply) => apply.toolName === 'configure_workflow' && Boolean(args(apply).create);
  checks.push(ok(`workflow скопирован из «${sourceWorkflow}», а не собран заново`,
    copies.some((apply) => args(apply).workflow === sourceWorkflow) && !done.some(isCreate),
    done.filter((apply) => apply.toolName === 'copy_workflow' || isCreate(apply))
      .map((apply) => `${apply.toolName} ${JSON.stringify(args(apply))}`).join('; ') || 'apply нет'));
  const foreign = done.filter((apply) => args(apply).project === neighbour
    || (['edit_workflow', 'publish_workflow_draft', 'rename_workflow'].includes(apply.toolName) && args(apply).workflow === sourceWorkflow)
    || (apply.toolName === 'configure_workflow' && args(apply).existing?.workflow === sourceWorkflow));
  checks.push(ok(`записей в ${neighbour} и в «${sourceWorkflow}» нет`, foreign.length === 0,
    foreign.map((apply) => apply.toolName).join(', ') || 'нет'));
  const globalWrites = done.filter((apply) => ['update_status', 'create_resolution'].includes(apply.toolName));
  checks.push(ok('глобальные статусы и резолюции не менялись', globalWrites.length === 0,
    globalWrites.map((apply) => apply.toolName).join(', ') || 'нет'));

  // --- project, types, workflow ----------------------------------------------------------------------------------
  const info = await rest('GET', `/rest/api/2/project/${project}`).catch((error) => ({ error: error.message }));
  if (info.error) return [...checks, ok(`проект ${project} создан`, false, info.error)];
  checks.push(ok(`проект ${project} «${projectName}», РП ${lead}`, info.name === projectName && info.lead?.name === lead,
    `${info.name}, РП ${info.lead?.name}`));
  const typeNames = info.issueTypes.map((type) => type.name).sort();
  checks.push(ok('в проекте ровно пять типов из ТЗ, не подзадачи',
    typeNames.join() === Object.values(t).sort().join() && info.issueTypes.every((type) => !type.subtask), typeNames.join(', ')));

  const workflows = await tool('get_project_workflows', { project });
  const used = [...new Set(Object.values(t).map((type) => workflows.issueTypes.find((e) => e.issueType.name === type)?.workflow))];
  const copy = used.length === 1 && used[0] && used[0] !== sourceWorkflow
    ? await tool('get_workflow', { workflow: used[0], version: 'ACTIVE' }) : null;
  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  checks.push(ok(`все пять типов на одной копии «${sourceWorkflow}» с тем же графом`, copy && same(workflowGraph(copy), sourceGraph),
    `workflow: ${used.join(', ')}` + (copy && !same(workflowGraph(copy), sourceGraph) ? `; граф: ${JSON.stringify(workflowGraph(copy))}` : '')));
  const source = await tool('get_workflow', { workflow: sourceWorkflow, version: 'ACTIVE' });
  checks.push(ok(`«${sourceWorkflow}» не изменён, черновика нет`, same(workflowGraph(source), sourceGraph) && !source.hasDraft,
    `черновик: ${source.hasDraft}`));

  // --- fields and contexts ---------------------------------------------------------------------------------------
  const allFields = (await rest('GET', '/rest/api/2/field')).filter((field) => field.custom);
  const ours = allFields.filter((field) => field.name.includes(runId)).map((field) => field.name).sort();
  checks.push(ok('созданы ровно шесть полей из ТЗ, лишних нет', ours.join() === Object.values(custom).map((f) => f.name).sort().join(),
    ours.join(', ') || 'нет'));
  const ids = {};
  for (const [key, spec] of Object.entries(custom)) {
    const found = allFields.filter((field) => field.name === spec.name);
    if (found.length !== 1) { checks.push(ok(`«${spec.name}»: одно поле`, false, `найдено ${found.length}`)); continue; }
    ids[key] = found[0].id;
    const typeKey = found[0].schema?.custom;
    const read = await tool('get_field_contexts', { field: found[0].id });
    const inProject = read.contexts.filter((context) => context.allProjects || context.projects.some((p) => p.key === project));
    const orphans = read.contexts.filter((context) => !context.allProjects && context.projectCount === 0);
    const context = inProject[0];
    const contextTypes = context ? (context.allIssueTypes ? Object.values(t) : context.issueTypes.map((type) => type.name)) : [];
    const pass = typeKey === CF + spec.type && inProject.length === 1 && orphans.length === 0
      && spec.types.every((type) => contextTypes.includes(type)) && (!spec.options || context.options?.count === spec.options);
    checks.push(ok(`«${spec.name}»: ${spec.type}, один контекст в ${project} на ${spec.types.join(', ')}${spec.options ? `, ${spec.options} опций` : ''}, без пустых контекстов`,
      pass, `${typeKey}; контекстов в проекте ${inProject.length}, пустых ${orphans.length}; типы: ${contextTypes.join(', ')}; опций: ${context?.options?.count}`));
  }

  // --- screens: own, every field of the type on CREATE/EDIT/VIEW ---------------------------------------------------
  const fieldsOf = {
    [t.pay]: ['summary', ids.subtype, ids.contact, 'description', 'attachment', 'priority', ids.payDue],
    [t.ka]: ['summary', ids.subtype, 'description', ids.details, ids.contact, 'duedate', 'priority'],
    [t.paper]: ['summary', ids.subtype, 'description', 'priority', 'duedate', 'attachment'],
    [t.consult]: ['summary', 'priority', 'description'],
    [t.supply]: ['summary', 'priority', 'description', ids.office, ids.recipient, 'duedate'],
  };
  const screens = await tool('get_project_screens', { project });
  checks.push(ok('схема экранов проекта своя', screens.scheme && !screens.scheme.isDefault && screens.scheme.otherProjects === 0,
    `${screens.scheme?.name}: default ${screens.scheme?.isDefault}, других проектов ${screens.scheme?.otherProjects}`));
  const contents = new Map();
  const fieldIdsOn = async (screenId) => {
    if (!contents.has(screenId)) contents.set(screenId, (await tool('get_screen_contents', { screenId })).tabs.flatMap((tab) => tab.fields.map((f) => f.fieldId)));
    return contents.get(screenId);
  };
  for (const [type, expected] of Object.entries(fieldsOf)) {
    const entry = screens.issueTypes.find((item) => item.issueType.name === type);
    const byOperation = Object.fromEntries((entry?.screenScheme.screens ?? []).map((s) => [s.operation.split('.').pop(), s]));
    const problems = [];
    if (!entry) problems.push('тип не найден');
    else if (entry.screenScheme.otherProjects !== 0) problems.push(`схема экранов общая (${entry.screenScheme.otherProjects})`);
    for (const operation of OPERATIONS) {
      const screen = byOperation[operation] ?? byOperation.default;
      if (!screen) { problems.push(`${operation}: нет экрана`); continue; }
      if (screen.otherProjects !== 0) problems.push(`${operation}: экран «${screen.name}» общий (${screen.otherProjects})`);
      const on = await fieldIdsOn(screen.id);
      const missing = expected.filter((field) => !field || !on.includes(field));
      if (missing.length) problems.push(`${operation} «${screen.name}»: нет ${missing.join(', ')}`);
    }
    checks.push(ok(`${type}: свои экраны CREATE/EDIT/VIEW со всеми полями ТЗ`, problems.length === 0, problems.join('; ') || 'все поля на месте'));
  }

  // --- required flags: unconditional «Да» only; conditional and «Нет» stay optional ------------------------------------
  // Custom fields by key of `custom`, system fields by id.
  const requiredOf = {
    [t.pay]: { yes: ['subtype', 'contact', 'payDue'], no: ['description', 'priority'] },
    [t.ka]: { yes: ['subtype'], no: ['description', 'details', 'contact', 'duedate', 'priority'] },
    [t.paper]: { yes: ['subtype', 'description', 'duedate'], no: ['priority', 'attachment'] },
    [t.consult]: { yes: [], no: ['priority', 'description'] },
    [t.supply]: { yes: ['office', 'recipient', 'duedate'], no: ['priority', 'description'] },
  };
  const fieldId = (key) => (custom[key] ? ids[key] : key);
  const label = (key) => custom[key]?.name ?? key;
  const asked = [...new Set(Object.values(requiredOf).flatMap(({ yes, no }) => [...yes, ...no]).map(fieldId).filter(Boolean))];
  const configs = await tool('get_field_configurations', { project, fields: asked });
  const shared = (configs.fieldConfigurations ?? []).filter((item) => item.otherProjects?.total > 0);
  checks.push(ok('схема и конфигурации полей проекта не общие с другими проектами',
    !configs.scheme?.defaultScheme && !(configs.schemeOtherProjects?.total > 0) && shared.length === 0,
    `${configs.scheme?.name}; общих конфигураций: ${shared.map((item) => item.fieldConfiguration.name).join(', ') || 'нет'}`));
  for (const [type, { yes, no }] of Object.entries(requiredOf)) {
    const config = configs.mappings.find((mapping) => mapping.issueTypeName === type)?.fieldConfiguration?.name;
    const required = (key) => configs.fields.find((item) => item.field.id === fieldId(key))?.configurations
      .find((state) => state.fieldConfiguration.name === config)?.required;
    const wrong = [...yes.map((field) => [field, true]), ...no.map((field) => [field, false])]
      .filter(([key, expected]) => !fieldId(key) || required(key) !== expected).map(([key, expected]) => `${label(key)}≠${expected}`);
    checks.push(ok(`${type}: обязательны ${yes.length ? yes.map(label).join(', ') : 'только системные'}; условные и «Нет» необязательны`,
      config && wrong.length === 0, `конфигурация «${config}»; ${wrong.join(', ') || 'флаги верны'}`));
  }

  // --- neighbour on Jira's defaults is untouched -------------------------------------------------------------------
  const now = await projectSnapshot(tool, neighbour);
  checks.push(ok(`${neighbour}: конфигурации полей, экраны и workflow прежние`, same(now, neighbourState),
    same(now, neighbourState) ? 'без изменений' : JSON.stringify(now).slice(0, 800)));
  return checks;
}
