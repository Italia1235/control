import { applies } from '../../checks.mjs';

const keysOf = (projects = []) => projects.map((project) => project.key).sort().join(',');
const namesOf = (types = []) => types.map((type) => type.name).sort().join(',');

export default async function verify({ prepared, trace, tool }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  if (!prepared?.userId) return [{ name: 'автоматическая подготовка сохранена', ok: false, detail: 'нет setup.json' }];
  const { keys, userId, numberId, oldContextId, oldConfigId, baseline, numberBaseline } = prepared;
  const now = await tool('get_field_contexts', { field: userId });
  const old = now.contexts?.find((context) => context.contextId === oldContextId);
  const fresh = now.contexts?.filter((context) => context.contextId !== oldContextId) ?? [];
  add('ровно один новый контекст User Picker', now.contexts?.length === 2 && fresh.length === 1, now.contexts?.length);
  add('старый контекст не изменён', old && old.configs?.[0]?.configId === oldConfigId
    && JSON.stringify(old.projects) === JSON.stringify(baseline.contexts[0].projects)
    && JSON.stringify(old.issueTypes) === JSON.stringify(baseline.contexts[0].issueTypes), JSON.stringify(old));
  const created = fresh[0];
  add('новый контекст: target × Bug/Sub-bug/Sub-task, без Task',
    created && created.allProjects === false && created.allIssueTypes === false
    && keysOf(created.projects) === keys.target && namesOf(created.issueTypes) === 'Bug,Sub-bug,Sub-task',
    JSON.stringify(created));
  add('у нового контекста один FieldConfig без опций и default',
    created?.configs?.length === 1 && created.configs[0].configId !== oldConfigId
    && !created.options?.count && created.defaultValue !== 'PRESENT', JSON.stringify(created?.configs));
  const number = await tool('get_field_contexts', { field: numberId });
  add('Number QA не изменён', JSON.stringify(number.contexts) === JSON.stringify(numberBaseline.contexts));
  const writes = applies(trace, 'create_field_context');
  add('ровно один apply create_field_context',
    writes.length === 1 && Object.keys(writes[0].start.arguments?.arguments ?? {}).sort().join() === 'dryRun,planId',
    writes.length);
  const forbidden = ['update_field_contexts', 'create_custom_fields'].filter((name) => applies(trace, name).length);
  add('запрещённые записи отсутствуют', forbidden.length === 0, forbidden.join());
  return checks;
}
