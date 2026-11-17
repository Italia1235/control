import { projectManagement } from '../../project-templates.mjs';
// Project with a single-choice list scoped to it and placed on all its screens, plus a global single-line text field of
// the same name: the field name in the opening line is ambiguous on purpose.
export default async function setup({ names, tool, apply, rest, log }) {
  const key = names['KEY:proj'];
  const fieldName = names['NAME:field'];
  await apply('create_project', projectManagement({ name: names['NAME:project'], key, lead: 'admin' }));
  log(`проект ${key} создан`);

  const created = await apply('create_custom_fields', {
    fields: [{
      name: fieldName, typeKey: 'select',
      options: [{ value: 'High' }, { value: 'Medium' }, { value: 'Low' }],
      contexts: [{ projects: [key], allIssueTypes: true }],
    }],
  });
  const selectId = created.report.created.find((item) => item.kind === 'CUSTOM_FIELD').id;
  log(`список ${selectId} создан`);

  const { issueTypes } = await tool('get_project_screens', { project: key });
  const screenIds = [...new Set(issueTypes.flatMap((type) => type.screenScheme.screens.map((screen) => screen.id)))];
  await apply('add_field_to_screens', { fieldIds: [selectId], screens: screenIds.map((screenId) => ({ screenId })) });
  log(`список добавлен на экраны ${screenIds.join(', ')}`);

  const text = await rest('POST', '/rest/api/2/field', {
    name: fieldName,
    type: 'com.atlassian.jira.plugin.system.customfieldtypes:textfield',
    searcherKey: 'com.atlassian.jira.plugin.system.customfieldtypes:textsearcher',
  });
  log(`текстовое поле ${text.id} с тем же названием создано`);
  return { project: key, field: fieldName, selectField: selectId, selectScreens: screenIds, textField: text.id };
}
