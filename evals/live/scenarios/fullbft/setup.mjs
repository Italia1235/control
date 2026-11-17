import { projectManagement } from '../../project-templates.mjs';
// A lived-in project {{KEY:a}} with six issue types on three workflows, five screens and four existing fields, and a
// neighbour {{KEY:b}} that shares with it: the Incident workflow, the Incident and Basic screens, and one field
// configuration scheme in which Инцидент and Риск use ONE field configuration. Issues stand in several statuses,
// one of them in «Отклонено» so that this status cannot be removed from the Review workflow.
const TYPES = {
  task: 'Задача', bug: 'Ошибка', request: 'Заявка', change: 'Изменение', incident: 'Инцидент', risk: 'Риск',
};
const SELECT = 'com.atlassian.jira.plugin.system.customfieldtypes:select';
const DATE = 'com.atlassian.jira.plugin.system.customfieldtypes:datepicker';
const USER = 'com.atlassian.jira.plugin.system.customfieldtypes:userpicker';
const GLOBAL = [{ allProjects: true, allIssueTypes: true }];

// Missing types are created (global, they join Jira's default type scheme): use a test Jira only.
async function ensureType({ apply, rest }, name) {
  const found = (await rest('GET', '/rest/api/2/issuetype')).find((type) => type.name === name && !type.subtask);
  if (found) return found;
  await apply('create_issue_types', { issueTypes: [{ key: 'type', name, subtask: false }] });
  return (await rest('GET', '/rest/api/2/issuetype')).find((type) => type.name === name && !type.subtask);
}

async function screenByName(rest, name) {
  for (let startAt = 0; ; startAt += 100) {
    const page = await rest('GET', `/rest/api/2/screens?startAt=${startAt}&maxResults=100`);
    const found = page.screens.find((screen) => screen.name === name);
    if (found) return found;
    if (startAt + page.screens.length >= page.total || page.screens.length === 0) return null;
  }
}

/** assign_workflow refuses a shared scheme with SCHEME_SHARED; a fresh project then gets a scheme of its own. */
async function assignWorkflow(apply, project, workflow, issueTypes) {
  try {
    return await apply('assign_workflow', { project, workflow, issueTypes });
  } catch (error) {
    if (!String(error.message).includes('SCHEME_SHARED')) throw error;
    return apply('assign_workflow', { project, workflow, issueTypes, newScheme: true });
  }
}

async function transition(rest, key, name) {
  const { transitions } = await rest('GET', `/rest/api/2/issue/${key}/transitions`);
  const found = transitions.find((candidate) => candidate.name === name);
  if (!found) throw new Error(`${key}: нет перехода «${name}» (есть ${transitions.map((t) => t.name).join(', ')})`);
  await rest('POST', `/rest/api/2/issue/${key}/transitions`, { transition: { id: found.id } });
}

export default async function setup({ names, runId, apply, tool, rest, log }) {
  const [a, b] = [names['KEY:a'], names['KEY:b']];
  const workflows = { simple: names['NAME:wfSimple'], review: names['NAME:wfReview'], incident: names['NAME:wfIncident'] };
  const fields = {
    severity: `Критичность ${runId}`, environment: `Окружение ${runId}`,
    approvalDate: `Срок согласования ${runId}`, riskOwner: `Владелец риска ${runId}`,
  };
  const ctx = { apply, rest };
  const types = {};
  for (const [id, name] of Object.entries(TYPES)) types[id] = (await ensureType(ctx, name)).name;

  await apply('create_project', projectManagement({ name: names['NAME:projectA'], key: a, lead: 'admin' }));
  await apply('create_project', projectManagement({ name: names['NAME:projectB'], key: b, lead: 'admin' }));
  await apply('set_project_issue_types', { project: a, issueTypes: Object.values(types) });
  await apply('set_project_issue_types', { project: b, issueTypes: [types.task, types.incident] });
  log(`проекты ${a} (${Object.values(types).join(', ')}) и ${b} (${types.task}, ${types.incident})`);

  // Existing fields: global contexts, so they are in context for every type of both projects (and the new type).
  const created = await apply('create_custom_fields', { fields: [
    { name: fields.severity, typeKey: SELECT, contexts: GLOBAL, options: ['Низкая', 'Средняя', 'Высокая'].map((value) => ({ value })) },
    { name: fields.environment, typeKey: SELECT, contexts: GLOBAL, options: ['Тест', 'Прод'].map((value) => ({ value })) },
    { name: fields.approvalDate, typeKey: DATE, contexts: GLOBAL },
    { name: fields.riskOwner, typeKey: USER, contexts: GLOBAL },
  ] });
  const fieldIds = {};
  for (const [id, name] of Object.entries(fields)) {
    fieldIds[id] = created.report?.created?.find((item) => item.kind === 'CUSTOM_FIELD' && item.name === name)?.id;
    if (!fieldIds[id]) throw new Error(`поле «${name}» не создано: ${JSON.stringify(created).slice(0, 1000)}`);
  }

  // Five screens; the Incident CREATE screen lacks «Критичность» on purpose.
  const screenFields = {
    basic: ['summary', 'priority', 'assignee', 'duedate', 'description'],
    review: ['summary', fieldIds.approvalDate, 'assignee', 'description'],
    incidentCreate: ['summary', 'priority', 'description'],
    incidentEdit: ['summary', 'priority', fieldIds.severity, fieldIds.riskOwner, 'assignee', 'description'],
    incidentView: ['summary', 'priority', fieldIds.severity, fieldIds.riskOwner, 'assignee', 'description'],
  };
  const screenNames = {
    basic: `${a}: Базовый экран`, review: `${a}: Экран согласования`, incidentCreate: `${a}: Инцидент — создание`,
    incidentEdit: `${a}: Инцидент — редактирование`, incidentView: `${a}: Инцидент — просмотр`,
  };
  const screens = {};
  for (const [id, name] of Object.entries(screenNames)) {
    await apply('create_screen', { name, fields: screenFields[id].map((field) => ({ name: field })) });
    const screen = await screenByName(rest, name);
    if (!screen) throw new Error(`экран «${name}» не найден после создания`);
    screens[id] = { id: screen.id, name };
  }
  const all = (id) => ({ CREATE: String(screens[id].id), EDIT: String(screens[id].id), VIEW: String(screens[id].id) });
  const incident = { CREATE: String(screens.incidentCreate.id), EDIT: String(screens.incidentEdit.id), VIEW: String(screens.incidentView.id) };
  await apply('set_project_operation_screens', { project: a, mappings: [
    { issueTypes: [types.task, types.bug], screens: all('basic') },
    { issueTypes: [types.request, types.change], screens: all('review') },
    { issueTypes: [types.incident, types.risk], screens: incident },
  ] });
  await apply('set_project_operation_screens', { project: b, mappings: [
    { issueTypes: [types.task], screens: all('basic') },
    { issueTypes: [types.incident], screens: incident },
  ] });
  log(`экраны: ${Object.values(screens).map((s) => `${s.name} (${s.id})`).join(', ')}`);

  // One field configuration scheme for both projects: Инцидент and Риск share one configuration.
  const configId = async (name) => {
    const result = await apply('create_field_configuration', { name, copyFrom: 'DEFAULT' });
    const id = result.fieldConfiguration?.id;
    if (!id) throw new Error(`конфигурация «${name}» не создана: ${JSON.stringify(result).slice(0, 500)}`);
    return String(id);
  };
  const incidentConfig = await configId(`${a}+${b}: поля инцидентов`);
  const baseConfig = await configId(`${a}+${b}: основные поля`);
  const scheme = await apply('create_field_configuration_scheme', {
    name: `${a}+${b}: схема конфигураций полей`,
    mappings: [
      { issueType: types.incident, fieldConfiguration: incidentConfig },
      { issueType: types.risk, fieldConfiguration: incidentConfig },
      { issueType: 'DEFAULT', fieldConfiguration: baseConfig },
    ],
  });
  const schemeId = String(scheme.scheme?.id ?? scheme.fieldConfigurationScheme?.id ?? scheme.id ?? '');
  if (!schemeId) throw new Error(`схема конфигураций не создана: ${JSON.stringify(scheme).slice(0, 500)}`);
  for (const project of [a, b]) await apply('assign_field_configuration_scheme', { project, scheme: schemeId });

  // Three different workflows, created after the screens because «Решить» uses one.
  const resolution = async (name) => (await tool('get_resolutions', { query: name }))[0]?.id;
  const resolutionDone = await resolution('Готово');
  const resolutionWontDo = await resolution('Не будет выполнено');
  if (!resolutionDone || !resolutionWontDo) throw new Error('нет Resolution «Готово» или «Не будет выполнено»');
  await apply('configure_workflow', {
    create: {
      name: workflows.simple, initialStatus: 'Открыта',
      statuses: [{ name: 'Открыта', category: 'TO_DO' }, { name: 'В работе', category: 'IN_PROGRESS' }, { name: 'Выполнена', category: 'DONE' }],
      transitions: [
        { key: 'start', name: 'Начать', from: 'Открыта', to: 'В работе' },
        { key: 'complete', name: 'Выполнить', from: 'В работе', to: 'Выполнена', resolution: { action: 'SET', value: resolutionDone } },
        { key: 'reopen', name: 'Переоткрыть', from: 'Выполнена', to: 'Открыта', resolution: { action: 'CLEAR' } },
      ],
    },
  });
  await apply('configure_workflow', {
    create: {
      name: workflows.review, initialStatus: 'Черновик',
      statuses: [{ name: 'Черновик', category: 'TO_DO' }, { name: 'На согласовании', category: 'IN_PROGRESS' },
        { name: 'Согласовано', category: 'DONE' }, { name: 'Отклонено', category: 'DONE' }],
      transitions: [
        { key: 'to-review', name: 'На ревью', from: 'Черновик', to: 'На согласовании' },
        { key: 'approve', name: 'Согласовать', from: 'На согласовании', to: 'Согласовано', resolution: { action: 'SET', value: resolutionDone } },
        { key: 'reject', name: 'Отклонить', from: 'На согласовании', to: 'Отклонено', resolution: { action: 'SET', value: resolutionWontDo } },
        { key: 'back-to-draft', name: 'Вернуть в черновик', from: 'Отклонено', to: 'Черновик', resolution: { action: 'CLEAR' } },
      ],
    },
  });
  await apply('configure_workflow', {
    create: {
      name: workflows.incident, initialStatus: 'Новый',
      statuses: [{ name: 'Новый', category: 'TO_DO' }, { name: 'Расследование', category: 'IN_PROGRESS' },
        { name: 'Ожидание', category: 'IN_PROGRESS' }, { name: 'Решён', category: 'DONE' }, { name: 'Закрыт', category: 'DONE' }],
      transitions: [
        { key: 'take', name: 'Взять в работу', from: 'Новый', to: 'Расследование' },
        { key: 'wait', name: 'Ждать', from: 'Расследование', to: 'Ожидание' },
        { key: 'continue', name: 'Продолжить', from: 'Ожидание', to: 'Расследование' },
        { key: 'resolve', name: 'Решить', from: 'Расследование', to: 'Решён', screen: String(screens.incidentEdit.id), resolution: { action: 'SET', value: resolutionDone } },
        { key: 'close', name: 'Закрыть', from: 'Решён', to: 'Закрыт', resolution: { action: 'SET', value: resolutionDone } },
        { key: 'reopen', name: 'Переоткрыть', from: 'Закрыт', to: 'Расследование', resolution: { action: 'CLEAR' } },
      ],
    },
  });
  await assignWorkflow(apply, a, workflows.simple, [types.task, types.bug]);
  await assignWorkflow(apply, a, workflows.review, [types.request, types.change]);
  await assignWorkflow(apply, a, workflows.incident, [types.incident, types.risk]);
  await assignWorkflow(apply, b, workflows.incident, [types.incident]);
  log(`workflow: ${Object.values(workflows).join(', ')}`);

  // Two issues of every type in {{KEY:a}}, one Инцидент in {{KEY:b}}; some moved on.
  const issues = [];
  const createIssue = async (project, type, summary) => {
    const { key } = await rest('POST', '/rest/api/2/issue', { fields: { project: { key: project }, issuetype: { name: type }, summary } });
    issues.push({ key, project, type });
    return key;
  };
  const keys = {};
  for (const [id, type] of Object.entries(types)) {
    keys[id] = [await createIssue(a, type, `${type} 1`), await createIssue(a, type, `${type} 2`)];
  }
  keys.bIncident = await createIssue(b, types.incident, `${types.incident} ${b}`);
  await transition(rest, keys.request[0], 'На ревью');
  await transition(rest, keys.request[0], 'Отклонить');
  await transition(rest, keys.incident[0], 'Взять в работу');
  await transition(rest, keys.incident[0], 'Ждать');
  await transition(rest, keys.task[0], 'Начать');
  for (const issue of issues) {
    issue.status = (await rest('GET', `/rest/api/2/issue/${issue.key}?fields=status`)).fields.status.name;
  }
  const rejected = issues.find((issue) => issue.key === keys.request[0]);
  if (rejected.status !== 'Отклонено') throw new Error(`${rejected.key} не в «Отклонено»: ${rejected.status}`);
  log(`задачи: ${issues.map((issue) => `${issue.key} ${issue.type} [${issue.status}]`).join(', ')}`);

  // Read back the sharing the dialogue depends on.
  const configs = await tool('get_field_configurations', { project: a, fields: [fieldIds.severity] });
  const configOf = (type) => configs.mappings.find((m) => m.issueTypeName === type)?.fieldConfiguration;
  if (String(configOf(types.incident)?.id) !== incidentConfig || String(configOf(types.risk)?.id) !== incidentConfig) {
    throw new Error(`Инцидент и Риск не делят конфигурацию: ${JSON.stringify(configs.mappings)}`);
  }
  if (configs.schemeOtherProjects.total !== 1) throw new Error(`схема конфигураций не общая с ${b}: ${JSON.stringify(configs.schemeOtherProjects)}`);
  if (configs.fields?.[0]?.configurations?.some((state) => state.required)) throw new Error('Критичность уже обязательна');

  return {
    projects: { a, b }, types, fields, fieldIds, screens, workflows,
    fieldConfigurations: { scheme: schemeId, incident: incidentConfig, base: baseConfig },
    issues, keys, rejected: rejected.key, newType: `Проблема ${runId}`,
  };
}
