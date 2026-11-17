import { OPERATIONS, OPERATION_KEY, readLayout } from '../screen-own-layout/setup.mjs';
import { projectManagement } from '../../project-templates.mjs';

export default async function setup({ names, apply, tool, log }) {
  const main = names['KEY:main'];
  const fieldNames = ['f1', 'f2', 'f3', 'f4'];
  await apply('create_project', projectManagement({ name: `Раскладка ${main}`, key: main, lead: 'admin' }));
  const request = {
    fields: fieldNames.map(name => ({ name, typeKey: 'textfield', contexts: [{ projects: [main], allIssueTypes: true }] })),
  };
  const preview = await tool('create_custom_fields', {dryRun: true, ...request});
  const created = preview.planId ? await apply('create_custom_fields', request) : preview;
  const fieldIds = fieldNames.map(name => {
    const row = created.results?.find(item => item.name === name);
    if (!row?.customFieldId) throw new Error(`Поле ${name} не найдено в результате: ${JSON.stringify(created)}`);
    return row.customFieldId;
  });
  // Fixed field names are intentional: the user's first utterance must remain literal.
  // Reused fields keep every old project scope; add this fixture only to an all-issue-type context.
  for (const fieldId of fieldIds) {
    const state = await tool('get_field_contexts', { field: fieldId });
    if (state.status !== 'READ') throw new Error(`Не прочитаны контексты ${fieldId}: ${JSON.stringify(state)}`);
    if (state.contexts.some(context => context.allIssueTypes && (context.allProjects
      || context.projects?.some(project => project.key === main)))) continue;
    const context = state.contexts.find(candidate => candidate.allIssueTypes && candidate.projectsComplete === true);
    if (!context) throw new Error(`Нет полного контекста всех типов для добавления ${main}: ${fieldId}`);
    await apply('update_field_contexts', { fieldId, contexts: [{
      contextId: context.contextId, projects: [...context.projects.map(project => project.key), main], allIssueTypes: true,
    }] });
  }
  const screenNames = Object.fromEntries(OPERATIONS.map(op => [op, `${main} ${op}`]));
  for (const op of OPERATIONS) {
    await apply('create_screen', { name: screenNames[op], fields: ['Summary', 'Description', ...fieldIds.slice(0, 3)].map(name => ({ name })) });
  }
  await apply('set_project_operation_screens', { project: main, mappings: [{ allIssueTypes: true, screens: screenNames }] });
  const before = await readLayout(tool, main);
  if (before.scheme.isDefault || before.scheme.otherProjects !== 0) throw new Error('Схема экранов не собственная');
  const expected = ['summary', 'description', ...fieldIds.slice(0, 3)];
  for (const entry of before.operations) {
    const ids = OPERATIONS.map(op => entry.screens[OPERATION_KEY[op]]);
    if (ids.some(id => !id) || new Set(ids).size !== 3) throw new Error('У операций нет трёх разных экранов');
    for (const id of ids) {
      const fields = before.contents[id].tabs.flatMap(tab => tab.fields);
      if (JSON.stringify(fields) !== JSON.stringify(expected)) throw new Error(`Экран ${id}: ${JSON.stringify(fields)}`);
    }
  }
  log(`Проект ${main}, собственная схема ${before.scheme.id}, поля ${fieldIds.join(', ')}`);
  return { main, fieldNames, fieldIds, before };
}
