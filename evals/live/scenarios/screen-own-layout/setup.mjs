import { projectManagement } from '../../project-templates.mjs';
// Two fresh projects on ONE shared, non-standard Issue Type Screen Scheme whose CREATE/EDIT/VIEW operations use three
// own screens, each laid out Summary, Description, f1, f2, f3. Jira's default screen scheme stays untouched.
export const OPERATIONS = ['CREATE', 'EDIT', 'VIEW'];
export const OPERATION_KEY = { CREATE: 'admin.issue.operations.create', EDIT: 'admin.issue.operations.edit', VIEW: 'admin.issue.operations.view' };

const token = (html) => html.match(/name="atl_token"[\s\S]*?value="([^"]+)"/)?.[1];

/** Screen structure and per-screen tab/field order of one project, as the plugin's read tools report it. */
export async function readLayout(tool, project) {
  const screens = await tool('get_project_screens', { project });
  const contents = {};
  for (const type of screens.issueTypes) {
    for (const screen of type.screenScheme.screens) {
      if (contents[screen.id]) continue;
      const read = await tool('get_screen_contents', { screenId: screen.id });
      contents[screen.id] = { name: screen.name, tabs: read.tabs.map((tab) => ({ name: tab.name, fields: tab.fields.map((field) => field.fieldId ?? field.id) })) };
    }
  }
  const operations = screens.issueTypes.map((type) => ({
    issueType: type.issueType.name,
    screenScheme: type.screenScheme.id,
    screens: Object.fromEntries(type.screenScheme.screens.map((screen) => [screen.operation, screen.id])),
  }));
  return { scheme: screens.scheme, operations, contents };
}

export default async function setup({ names, apply, tool, rest, adminPage, log }) {
  const main = names['KEY:main'], other = names['KEY:other'];
  const fieldNames = [names['NAME:f1'], names['NAME:f2'], names['NAME:f3']];
  await apply('create_project', projectManagement({ name: `Основной ${main}`, key: main, lead: 'admin' }));
  await apply('create_project', projectManagement({ name: `Соседний ${other}`, key: other, lead: 'admin' }));
  log(`проекты ${main}, ${other} созданы`);

  const created = await apply('create_custom_fields', {
    fields: fieldNames.map((name) => ({ name, typeKey: 'textfield', contexts: [{ allProjects: true, allIssueTypes: true }] })),
  });
  const fieldIds = fieldNames.map((name) => {
    const id = created.report.created.find((item) => item.kind === 'CUSTOM_FIELD' && item.name === name)?.id;
    if (!id) throw new Error(`поле «${name}» не создано: ${JSON.stringify(created).slice(0, 500)}`);
    return id;
  });

  // Own screens per operation, assigned to the main project's (private) scheme.
  const screenNames = Object.fromEntries(OPERATIONS.map((op) => [op, `${main} ${op}`]));
  for (const op of OPERATIONS) {
    await apply('create_screen', { name: screenNames[op], fields: ['Summary', 'Description', ...fieldNames].map((name) => ({ name })) });
  }
  await apply('set_project_operation_screens', { project: main, mappings: [{ allIssueTypes: true, screens: screenNames }] });

  // The plugin has no "assign this scheme to another project" tool: use Jira's own association form.
  const before = await tool('get_project_screens', { project: main });
  if (before.scheme.isDefault || before.scheme.otherProjects !== 0) throw new Error(`схема ${main} не собственная: ${JSON.stringify(before.scheme)}`);
  const otherId = (await rest('GET', `/rest/api/2/project/${other}`)).id;
  const form = (await adminPage(`/secure/admin/SelectIssueTypeScreenScheme!default.jspa?projectId=${otherId}`)).text;
  await adminPage('/secure/admin/SelectIssueTypeScreenScheme.jspa', {
    projectId: String(otherId), schemeId: String(before.scheme.id), Associate: 'Associate', atl_token: token(form) });

  const mainLayout = await readLayout(tool, main);
  const otherLayout = await readLayout(tool, other);

  const expected = ['summary', 'description', ...fieldIds];
  if (mainLayout.scheme.id !== otherLayout.scheme.id) throw new Error('проекты не делят схему экранов');
  if (mainLayout.scheme.isDefault || mainLayout.scheme.otherProjects !== 1) throw new Error(`схема не общая для двух проектов: ${JSON.stringify(mainLayout.scheme)}`);
  for (const layout of [mainLayout, otherLayout]) {
    for (const entry of layout.operations) {
      const ids = OPERATIONS.map((op) => entry.screens[OPERATION_KEY[op]]);
      if (ids.some((id) => !id) || new Set(ids).size !== 3) throw new Error(`операции без своих экранов: ${JSON.stringify(entry)}`);
      for (const id of ids) {
        const flat = layout.contents[id].tabs.flatMap((tab) => tab.fields);
        if (JSON.stringify(flat) !== JSON.stringify(expected)) throw new Error(`экран ${id}: ${JSON.stringify(flat)}`);
      }
    }
  }
  log(`общая схема ${mainLayout.scheme.id} «${mainLayout.scheme.name}»; экраны ${JSON.stringify(mainLayout.operations[0].screens)}`);
  return { main, other, fieldIds, fieldNames, screenNames, scheme: mainLayout.scheme, main_layout: mainLayout, other_layout: otherLayout };
}
