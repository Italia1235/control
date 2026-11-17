import { projectManagement } from '../../project-templates.mjs';
// New project with its own field configuration scheme where Задача and Ошибка share ONE field configuration and a
// third type has its own. Closer to a real configuration: three fresh text fields (optional everywhere) with
// descriptions, a non-default renderer, and a hidden field in the shared configuration. The dialogue then makes all
// three required for Задача only, one after another, so the 2nd and 3rd go through the already assigned copy.
const TASK = 'Задача';
const BUG = 'Ошибка';
const THIRD = ['История', 'Story', 'Эпик', 'Epic'];
const TEXT_RENDERER = 'jira-text-renderer';

// Missing standard types are created (global, joins Jira's default type scheme): use a test Jira only.
async function ensureType({ apply, rest }, candidates, createName) {
  const catalog = await rest('GET', '/rest/api/2/issuetype');
  const found = candidates.map((name) => catalog.find((type) => type.name === name)).find(Boolean);
  if (found) {
    if (found.subtask) throw new Error(`«${found.name}» должен быть стандартным типом, не подзадачей`);
    return found;
  }
  await apply('create_issue_types', { issueTypes: [{ key: 'created', name: createName, subtask: false }] });
  return (await rest('GET', '/rest/api/2/issuetype')).find((type) => type.name === createName);
}

const token = (html) => html.match(/name="atl_token"[\s\S]*?value="([^"]+)"/)?.[1];

/** Jira admin forms for settings the plugin does not (and must not) manage: description, renderer, hidden. */
function layoutAdmin(adminPage, layoutId) {
  const itemPage = (action, fieldId) => `/secure/admin/${action}?id=${layoutId}&fieldId=${encodeURIComponent(fieldId)}`;
  return {
    async describe(fieldId, description) {
      const form = (await adminPage(itemPage('EditFieldLayoutItem!default.jspa', fieldId))).text;
      await adminPage('/secure/admin/EditFieldLayoutItem.jspa', {
        description, id: String(layoutId), fieldId, Update: 'Update', atl_token: token(form) });
    },
    async render(fieldId, renderer) {
      const form = (await adminPage(itemPage('EditFieldLayoutItemRenderer!default.jspa', fieldId))).text;
      const confirmation = (await adminPage('/secure/admin/EditFieldLayoutItemRendererConfirmation.jspa', {
        selectedRendererType: renderer, rendererEdit: '', id: String(layoutId), fieldId, Update: 'Update', atl_token: token(form) })).text;
      await adminPage('/secure/admin/EditFieldLayoutItemRenderer.jspa', {
        selectedRendererType: renderer, rendererEdit: '', id: String(layoutId), fieldId, confirmed: 'true',
        Update: 'Update', atl_token: token(confirmation) });
    },
    /** Jira's hide/show toggle addresses an item by its index in the name-sorted list on the configure page. */
    async toggleAt(index) {
      const form = (await adminPage(itemPage('EditFieldLayoutItem!default.jspa', 'summary'))).text;
      await adminPage(`/secure/admin/EditFieldLayoutHide.jspa?id=${layoutId}&hide=${index}&atl_token=${token(form)}`);
    },
    async description(fieldId) {
      const form = (await adminPage(itemPage('EditFieldLayoutItem!default.jspa', fieldId))).text;
      const raw = (form.match(/<textarea[^>]*name="description"[^>]*>([\s\S]*?)<\/textarea>/)?.[1] ?? '').trim();
      return raw.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;|&#x27;/g, "'").replace(/&amp;/g, '&');
    },
    async renderer(fieldId) {
      const form = (await adminPage(itemPage('EditFieldLayoutItemRenderer!default.jspa', fieldId))).text;
      return form.match(/<option value="([^"]*)"\s+SELECTED/)?.[1] ?? null;
    },
  };
}

export default async function setup({ names, apply, tool, rest, adminPage, log }) {
  const project = names['KEY:proj'];
  const fieldNames = [names['NAME:field1'], names['NAME:field2'], names['NAME:field3']];
  const ctx = { apply, rest };
  const task = await ensureType(ctx, [TASK], TASK);
  const bug = await ensureType(ctx, [BUG], BUG);
  const third = await ensureType(ctx, THIRD, THIRD[0]);

  await apply('create_project', projectManagement({ name: names['NAME:project'], key: project, lead: 'admin' }));
  log(`проект ${project} создан`);
  await apply('set_project_issue_types', { project, issueTypes: [task.name, bug.name, third.name] });
  await apply('clone_project_field_configurations', { project });
  const cloned = await tool('get_field_configurations', { project });
  if (cloned.scheme.defaultScheme || cloned.schemeOtherProjects.total > 0
      || cloned.fieldConfigurations.some((use) => use.otherProjects.total > 0)) {
    throw new Error(`у ${project} нет своих конфигураций полей: ${JSON.stringify(cloned.scheme)}`);
  }
  const shared = cloned.mappings.find((entry) => entry.issueTypeName === task.name).fieldConfiguration;

  const createdFields = await apply('create_custom_fields', {
    fields: fieldNames.map((name) => ({ name, typeKey: 'textfield',
      contexts: [{ projects: [project], issueTypes: [task.name, bug.name, third.name] }] })),
  });
  const fieldIds = fieldNames.map((name) => {
    const id = createdFields.report.created.find((item) => item.kind === 'CUSTOM_FIELD' && item.name === name)?.id;
    if (!id) throw new Error(`поле «${name}» не создано: ${JSON.stringify(createdFields)}`);
    return id;
  });

  // Non-default attributes in the shared configuration, set through Jira's own admin forms and read back.
  const admin = layoutAdmin(adminPage, shared.id);
  const createMeta = async () => {
    const meta = await rest('GET', `/rest/api/2/issue/createmeta/${project}/issuetypes/${task.id}?maxResults=200`);
    return (meta.fields ?? meta.values).map((field) => field.fieldId);
  };
  const visibleBefore = await createMeta();
  const descriptions = {};
  for (const [index, id] of [...fieldIds, 'priority'].entries()) {
    descriptions[id] = `Описание ${index + 1}: <b>важно</b> & «кавычки» ${project}`;
    await admin.describe(id, descriptions[id]);
  }
  const rendered = ['description', 'environment'];
  for (const id of rendered) await admin.render(id, TEXT_RENDERER);
  // Hide exactly one field that is on the create form: scan indices, undo toggles that change nothing visible.
  let hiddenField = null;
  const protectedFields = new Set([...rendered, 'summary', 'issuetype', 'project', 'reporter']);
  for (let index = 0; index < 500 && !hiddenField; index++) {
    try { await admin.toggleAt(index); } catch { continue; }
    const now = await createMeta();
    const gone = visibleBefore.filter((id) => !now.includes(id));
    if (gone.length === 1 && !protectedFields.has(gone[0])) hiddenField = gone[0];
    else await admin.toggleAt(index);
  }
  if (!hiddenField) throw new Error('не удалось скрыть ни одно поле формы создания');
  for (const [id, text] of Object.entries(descriptions)) {
    if (await admin.description(id) !== text) throw new Error(`описание ${id} не сохранилось`);
  }
  for (const id of rendered) {
    if (await admin.renderer(id) !== TEXT_RENDERER) throw new Error(`renderer ${id} не сохранился`);
  }
  if ((await createMeta()).includes(hiddenField)) throw new Error(`поле ${hiddenField} не скрыто`);
  log(`в «${shared.name}»: описания ${Object.keys(descriptions).join(', ')}, text renderer ${rendered.join(', ')}, скрыто ${hiddenField}`);

  // The third type gets its own copy (made after customisation, so it carries the same settings).
  const created = await apply('create_field_configuration', {
    name: `${project}: ${third.name} fields`, copyFrom: String(shared.id),
  });
  const ownId = created.fieldConfiguration?.id;
  if (!ownId) throw new Error(`конфигурация не создана: ${JSON.stringify(created)}`);
  await apply('set_field_configuration_mappings', {
    scheme: String(cloned.scheme.id), mappings: [{ issueType: third.name, fieldConfiguration: String(ownId) }],
  });

  const read = await tool('get_field_configurations', { project, fields: fieldIds });
  const mappings = read.mappings.map((m) => ({ issueTypeId: m.issueTypeId, issueTypeName: m.issueTypeName, fieldConfiguration: m.fieldConfiguration }));
  const configOf = (name) => mappings.find((m) => m.issueTypeName === name)?.fieldConfiguration.id;
  if (configOf(task.name) !== configOf(bug.name)) throw new Error(`${task.name} и ${bug.name} не делят конфигурацию: ${JSON.stringify(mappings)}`);
  if (configOf(third.name) === configOf(task.name)) throw new Error(`${third.name} делит конфигурацию с ${task.name}`);
  if (read.fields.some((field) => field.configurations.some((state) => state.required))) throw new Error('поле уже обязательно');
  if (read.scheme.defaultScheme || read.schemeOtherProjects.total > 0) throw new Error('схема проекта не изолирована');
  log(`схема ${read.scheme.name}; типы: ${mappings.map((m) => `${m.issueTypeName} → ${m.fieldConfiguration.name}`).join(', ')}`);
  return {
    project, fieldIds, fieldNames, scheme: read.scheme, mappings, descriptions, rendered, hidden: hiddenField, renderer: TEXT_RENDERER,
    types: { task: task.name, bug: bug.name, third: third.name },
  };
}

export { layoutAdmin };
