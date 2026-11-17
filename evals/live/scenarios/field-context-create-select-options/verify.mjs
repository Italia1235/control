import { applies } from '../../checks.mjs';
import { contextOptions } from './setup.mjs';

const keysOf = (projects = []) => projects.map((project) => project.key).sort().join(',');
const namesOf = (types = []) => types.map((type) => type.name).sort().join(',');
const WANTED = ['Заря R&D', 'Заря-С', 'Зарница R&D'];

export default async function verify({ prepared, trace, tool, adminPage }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  if (!prepared?.selectId) return [{ name: 'автоматическая подготовка сохранена', ok: false, detail: 'нет setup.json' }];
  const { keys, selectId, textId, selectBaseline, textBaseline, oldOptions } = prepared;

  const split = async (fieldId, baseline) => {
    const now = await tool('get_field_contexts', { field: fieldId });
    const before = baseline.contexts[0];
    const old = now.contexts?.find((context) => context.contextId === before.contextId);
    const fresh = now.contexts?.filter((context) => context.contextId !== before.contextId) ?? [];
    return { now, before, old, fresh };
  };
  const unchanged = (old, before) => old && old.configs?.[0]?.configId === before.configs[0].configId
    && JSON.stringify(old.projects) === JSON.stringify(before.projects)
    && JSON.stringify(old.issueTypes) === JSON.stringify(before.issueTypes);
  const targetBug = (context) => context && context.allProjects === false && context.allIssueTypes === false
    && keysOf(context.projects) === keys.target && namesOf(context.issueTypes) === 'Bug';

  const select = await split(selectId, selectBaseline);
  add('Система: ровно один новый контекст', select.now.contexts?.length === 2 && select.fresh.length === 1,
    select.now.contexts?.length);
  const created = select.fresh[0];
  add('Система: новый контекст target × Bug', targetBug(created), JSON.stringify(created));
  add('Система: у нового контекста своя конфигурация без default',
    created?.configs?.length === 1 && created.configs[0].configId !== select.before.configs[0].configId
    && created.defaultValue !== 'PRESENT', JSON.stringify({ configs: created?.configs, defaultValue: created?.defaultValue }));
  if (created?.configs?.length === 1) {
    const options = await contextOptions(adminPage, selectId, created.contextId, created.configs[0].configId);
    add('Система: опции нового контекста ровно запрошенные и в порядке',
      options.map((option) => option.value).join('|') === WANTED.join('|') && !options.some((option) => option.selected),
      JSON.stringify(options));
  } else add('Система: опции нового контекста ровно запрошенные и в порядке', false, 'нет единственной конфигурации');
  add('Система: старый контекст не изменён (scope/config)', unchanged(select.old, select.before), JSON.stringify(select.old));
  const oldNow = select.old ? await contextOptions(adminPage, selectId, select.old.contextId, select.old.configs[0].configId) : [];
  add('Система: опции старого контекста не изменены', JSON.stringify(oldNow) === JSON.stringify(oldOptions), JSON.stringify(oldNow));

  const text = await split(textId, textBaseline);
  add('Серийный номер: ровно один новый контекст target × Bug',
    text.now.contexts?.length === 2 && text.fresh.length === 1 && targetBug(text.fresh[0]), JSON.stringify(text.fresh));
  add('Серийный номер: старый контекст не изменён', unchanged(text.old, text.before), JSON.stringify(text.old));

  const writes = applies(trace, 'create_field_context');
  const fieldOf = (write) => write.preview?.arguments?.fieldId;
  add('по одному apply create_field_context на каждое поле, только dryRun/planId',
    writes.length === 2 && new Set(writes.map(fieldOf)).size === 2
    && writes.every((write) => Object.keys(write.start.arguments?.arguments ?? {}).sort().join() === 'dryRun,planId'),
    JSON.stringify(writes.map((write) => ({ field: fieldOf(write), args: write.start.arguments?.arguments }))));
  const forbidden = ['create_custom_fields', 'update_field_contexts', 'edit_screen_layout', 'add_field_to_screens',
    'set_field_required'].filter((name) => applies(trace, name).length);
  add('запрещённые записи отсутствуют', forbidden.length === 0, forbidden.join());
  return checks;
}
