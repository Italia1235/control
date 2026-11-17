import { projectManagement } from '../../project-templates.mjs';
// Fresh projects and two same-named QA fields (User Picker, Number); the User Picker has an old source+other context.
export async function prepare({ names, runId, tool, apply, rest, log }) {
  const keys = { target: names['KEY:target'], source: names['KEY:source'], other: names['KEY:other'], outside: names['KEY:outside'] };
  const types = await rest('GET', '/rest/api/2/issuetype');
  for (const [name, subtask] of [['Bug', false], ['Task', false], ['Sub-task', true], ['Sub-bug', true]]) {
    const found = types.find((type) => type.name === name);
    if (!found) await apply('create_issue_types', { issueTypes: [{ key: 'type', name, subtask }] });
    else if (Boolean(found.subtask) !== subtask) throw new Error(`${name}: неожиданный вид типа`);
  }
  for (const role of ['target', 'source', 'other', 'outside']) {
    await apply('create_project', projectManagement({ name: names[`NAME:${role}`], key: keys[role], lead: 'admin' }));
  }
  const all = ['Bug', 'Task', 'Sub-bug', 'Sub-task'];
  for (const role of ['target', 'source', 'other']) await apply('set_project_issue_types', { project: keys[role], issueTypes: all });

  const fieldName = `QA ${runId}`;
  const created = await apply('create_custom_fields', { fields: [
    { name: fieldName, typeKey: 'userpicker', contexts: [
      { name: `Старый ${runId}`, projects: [keys.source, keys.other], issueTypes: ['Bug', 'Task'] }] },
  ] });
  const userId = created.report?.created?.find((item) => item.kind === 'CUSTOM_FIELD')?.id;
  if (!userId) throw new Error(`User Picker не создан: ${JSON.stringify(created)}`);
  // Same name, other type: create_custom_fields would answer NAME_CONFLICT, Jira's REST allows the duplicate.
  const number = await rest('POST', '/rest/api/2/field', {
    name: fieldName, type: 'com.atlassian.jira.plugin.system.customfieldtypes:float',
    searcherKey: 'com.atlassian.jira.plugin.system.customfieldtypes:exactnumber' });
  const numberId = number?.id;
  if (!numberId) throw new Error(`Number не создан: ${JSON.stringify(number)}`);

  let screens = await tool('get_project_screens', { project: keys.target });
  if (screens.issueTypes.some((type) => type.screenScheme.screens.some((screen) => screen.otherProjects > 0))) {
    await apply('clone_project_screens', { project: keys.target, scope: 'ALL', copyScreens: true });
    screens = await tool('get_project_screens', { project: keys.target });
  }
  const screenIds = [...new Set(screens.issueTypes.flatMap((type) => type.screenScheme.screens.map((screen) => screen.id)))];
  if (!screenIds.length) throw new Error('нет экранов целевого проекта');
  await apply('add_field_to_screens', { fieldIds: [userId], screens: screenIds.map((screenId) => ({ screenId })) });

  const baseline = await tool('get_field_contexts', { field: userId });
  const numberBaseline = await tool('get_field_contexts', { field: numberId });
  if (baseline.contexts.length !== 1 || baseline.contexts[0].projects.some((project) => project.key === keys.target)) {
    throw new Error(`неверное исходное состояние: ${JSON.stringify(baseline)}`);
  }
  log(`User Picker ${userId}: один старый контекст; Number ${numberId}; target ${keys.target} вне контекстов`);
  return { keys, fieldName, userId, numberId, screenIds, baseline, numberBaseline,
    oldContextId: baseline.contexts[0].contextId, oldConfigId: baseline.contexts[0].configs[0].configId };
}

export default async function setup(context) { return prepare(context); }
