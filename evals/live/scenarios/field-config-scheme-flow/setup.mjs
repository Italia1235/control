import { projectManagement } from '../../project-templates.mjs';
// New project with its own copy of the field configuration scheme (never touch Jira's shared default); Due Date then
// becomes required for every issue type so the dialogue has a requirement to remove.
export default async function setup({ names, apply, tool, log }) {
  const project = names['KEY:proj'];
  await apply('create_project', projectManagement({ name: names['NAME:project'], key: project, lead: 'admin' }));
  log(`проект ${project} создан`);
  await apply('clone_project_field_configurations', { project });
  const isolated = await tool('get_field_configurations', { project });
  if (isolated.scheme.defaultScheme || isolated.schemeOtherProjects.total > 0
      || isolated.fieldConfigurations.some((use) => use.otherProjects.total > 0)) {
    throw new Error(`у ${project} нет своих конфигураций полей: ${JSON.stringify(isolated.scheme)}`);
  }
  log(`у ${project} своя схема ${isolated.scheme.name}`);

  await apply('set_field_required', { project, rows: [{ fields: ['duedate'], required: true, allIssueTypes: true }] });
  const read = await tool('get_field_configurations', { project, fields: ['duedate', 'priority'] });
  const [dueDate, priority] = read.fields;
  if (!dueDate.configurations.every((state) => state.required)) throw new Error('Due Date не стала обязательной');
  if (priority.configurations.some((state) => state.required)) throw new Error('Priority уже обязательна');
  if (read.scheme.defaultScheme || read.schemeOtherProjects.total > 0) {
    throw new Error(`у ${project} нет своей схемы конфигураций полей: ${JSON.stringify(read.scheme)}`);
  }
  log(`схема ${read.scheme.name}; типы: ${read.mappings.map((m) => `${m.issueTypeName} → ${m.fieldConfiguration.name}`).join(', ')}`);
  return {
    project,
    scheme: read.scheme,
    mappings: read.mappings.map((m) => ({ issueTypeId: m.issueTypeId, issueTypeName: m.issueTypeName, fieldConfiguration: m.fieldConfiguration })),
  };
}
