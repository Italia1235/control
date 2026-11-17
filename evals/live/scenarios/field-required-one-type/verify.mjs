import { applies } from '../../checks.mjs';
import { layoutAdmin } from './setup.mjs';

export default async function verify({ prepared, trace, tool, rest, adminPage }) {
  const { project, fieldIds, fieldNames, types } = prepared;
  const read = await tool('get_field_configurations', { project, fields: fieldIds });
  const mapping = (name) => read.mappings.find((entry) => entry.issueTypeName === name)?.fieldConfiguration;
  const before = (name) => prepared.mappings.find((entry) => entry.issueTypeName === name)?.fieldConfiguration;
  const requiredIn = (fieldId, configId) => read.fields.find((f) => f.field.id === fieldId)
    ?.configurations.find((state) => state.fieldConfiguration.id === configId)?.required;
  const checks = [];

  const task = mapping(types.task), bug = mapping(types.bug), third = mapping(types.third);
  checks.push({ name: `${types.task}: все три поля обязательны`,
    ok: Boolean(task) && fieldIds.every((id) => requiredIn(id, task.id) === true),
    detail: fieldIds.map((id) => `${id}: ${requiredIn(id, task?.id)}`).join('; ') });
  checks.push({ name: `${types.task} на новой конфигурации, остальные на прежних`,
    ok: task?.id !== before(types.task).id && bug?.id === before(types.bug).id && third?.id === before(types.third).id,
    detail: `${before(types.task).name} → ${task?.name}; ${bug?.name}; ${third?.name}` });
  checks.push({ name: `${types.bug} и ${types.third}: поля необязательны`,
    ok: fieldIds.every((id) => requiredIn(id, bug?.id) === false && requiredIn(id, third?.id) === false),
    detail: fieldIds.map((id) => `${id}: ${requiredIn(id, bug?.id)}/${requiredIn(id, third?.id)}`).join('; ') });
  checks.push({ name: 'схема проекта прежняя, других проектов на ней нет',
    ok: read.scheme.id === prepared.scheme.id && read.schemeOtherProjects.total === 0,
    detail: `${read.scheme.name}, других проектов: ${read.schemeOtherProjects.total}` });

  // One copy for all three fields: no orphan or duplicate configurations of this project.
  const listing = (await adminPage('/secure/admin/ViewFieldLayouts.jspa')).text;
  const layouts = [...new Set([...listing.matchAll(new RegExp(`>(${project}: [^<]+)<`, 'g'))].map((m) => m[1])
    .filter((name) => !name.includes('Scheme')))];
  checks.push({ name: 'у проекта ровно три конфигурации полей (исходная, третьего типа, одна копия для Задачи)',
    ok: layouts.length === 3, detail: layouts.join(' | ') });

  // Settings not asked about survive the copy: descriptions, renderer, hidden.
  const original = layoutAdmin(adminPage, before(types.bug).id);
  const copy = layoutAdmin(adminPage, task?.id);
  const lost = [];
  for (const [id, text] of Object.entries(prepared.descriptions)) {
    if (await copy.description(id) !== text) lost.push(`описание ${id}`);
    if (await original.description(id) !== text) lost.push(`описание ${id} в исходной`);
  }
  for (const id of prepared.rendered) {
    if (await copy.renderer(id) !== prepared.renderer) lost.push(`renderer ${id}`);
    if (await original.renderer(id) !== prepared.renderer) lost.push(`renderer ${id} в исходной`);
  }
  checks.push({ name: 'описания и renderer сохранены и в копии, и в исходной', ok: lost.length === 0,
    detail: lost.length ? lost.join(', ') : `${Object.keys(prepared.descriptions).length} описаний, renderer ${prepared.rendered.join(', ')}` });

  const visible = async (typeName) => {
    const id = prepared.mappings.find((entry) => entry.issueTypeName === typeName).issueTypeId;
    const meta = await rest('GET', `/rest/api/2/issue/createmeta/${project}/issuetypes/${id}?maxResults=200`);
    return (meta.fields ?? meta.values).map((field) => field.fieldId);
  };
  const shown = { task: await visible(types.task), bug: await visible(types.bug) };
  checks.push({ name: `скрытое поле ${prepared.hidden} осталось скрытым и у Задачи, и у Ошибки`,
    ok: !shown.task.includes(prepared.hidden) && !shown.bug.includes(prepared.hidden), detail: prepared.hidden });

  const requiredApplies = applies(trace, 'set_field_required');
  const args = requiredApplies.map(({ preview }) => preview?.arguments);
  const taskId = prepared.mappings.find((entry) => entry.issueTypeName === types.task)?.issueTypeId;
  const batch = args[0];
  const row = Array.isArray(batch?.rows) && batch.rows.length === 1 ? batch.rows[0] : undefined;
  const covered = Array.isArray(row?.fields) && row.fields.length === fieldIds.length
    && fieldIds.every((id, i) => row.fields.filter((field) => [id, fieldNames[i]].includes(field)).length === 1);
  checks.push({ name: 'ровно один apply set_field_required для всех трёх полей, только для типа Задача, без allIssueTypes',
    ok: requiredApplies.length === 1 && row?.required === true && covered
      && !Object.hasOwn(row, 'allIssueTypes') && !Object.hasOwn(batch, 'field')
      && row.issueTypes?.length === 1 && [types.task, String(taskId)].includes(String(row.issueTypes[0])),
    detail: JSON.stringify(args) });
  checks.push({ name: 'записи только в этом проекте',
    ok: applies(trace).every(({ preview }) => !preview?.arguments?.project || preview.arguments.project === project),
    detail: `${applies(trace).length} apply` });
  return checks;
}
