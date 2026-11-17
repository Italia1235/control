import { applies, parseResult } from '../../checks.mjs';
import { contextOptions } from '../field-context-create-select-options/setup.mjs';
import { OPERATION_KEY } from '../screen-own-layout/setup.mjs';
import { canonical, flagMap, snapshot } from './setup.mjs';

export const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const keysOf = (projects = []) => projects.map((project) => project.key).sort().join(',');
const namesOf = (types = []) => types.map((type) => type.name).sort().join(',');
export const customId = (id) => (/^customfield_/.test(String(id)) ? String(id) : `customfield_${id}`);
export const AFFIRMATION = /^\s*(да|yes|ок|ok|подтверждаю|применяй)(?=[\s,.!:;)]|$)/iu;
const stable = (value) => JSON.stringify(value, (_, item) => (item && typeof item === 'object' && !Array.isArray(item)
  ? Object.fromEntries(Object.entries(item).sort(([a], [b]) => (a < b ? -1 : 1))) : item));

/** Result of an operation once it is terminal: the start answer, or the first later get_operation of the same id. */
export function terminal(trace, start) {
  const first = parseResult(start.result);
  if (first?.status === 'COMPLETED') return { seq: start.seq, operation: first };
  const poll = trace.find((entry) => entry.tool === 'get_operation' && entry.seq > start.seq
    && entry.arguments?.operationId === first?.operationId && parseResult(entry.result)?.status === 'COMPLETED');
  return poll ? { seq: poll.seq, operation: parseResult(poll.result) } : null;
}

export default async function verify({ prepared, runId, trace, conversation, tool, probe, adminPage }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  if (!prepared?.ids) return [{ name: 'автоматическая подготовка сохранена', ok: false, detail: 'нет setup.json' }];
  const { keys, ids, fieldNames: f, typeName } = prepared;
  const all = applies(trace);

  // ---- Протокол -----------------------------------------------------------------------------------------------
  const applyTurns = [...new Set(all.map((item) => item.start.turn))].sort((a, b) => a - b);
  const toolsIn = (turn) => all.filter((item) => item.start.turn === turn).map((item) => item.toolName).sort();
  const userAt = (turn) => conversation.filter((entry) => entry.role === 'user')[turn - 1]?.content ?? '';
  const affirmations = conversation.filter((entry, index) => entry.role === 'user' && index > 0 && AFFIRMATION.test(entry.content ?? '')).length;
  const groupA = ['create_custom_fields', 'create_field_context', 'create_field_context'];
  const groupB = ['edit_screen_layout', 'set_field_required'];
  add('P1 ровно два «да», после каждого apply: поля+контексты, затем экран+обязательность',
    applyTurns.length === 2 && applyTurns.every((turn) => AFFIRMATION.test(userAt(turn))) && affirmations === 2
      && same(toolsIn(applyTurns[0]), groupA.sort()) && same(toolsIn(applyTurns[1]), groupB.sort()),
    `ходы apply: ${applyTurns.join(',')}; «да» всего: ${affirmations}; ${applyTurns.map((turn) => `ход ${turn}: ${toolsIn(turn).join('+')}`).join('; ')}`);

  const editApply = applies(trace, 'edit_screen_layout');
  const requiredApply = applies(trace, 'set_field_required');
  const screenPreview = editApply[0]?.preview, requiredPreview = requiredApply[0]?.preview;
  const lateTurn = editApply[0]?.start.turn; // the «да» that applied the screen
  add('P2a предпросмотры edit_screen_layout и set_field_required в одном ходу ассистента, до «да» на применение экрана',
    editApply.length === 1 && requiredApply.length === 1 && screenPreview && requiredPreview
      && screenPreview.turn === requiredPreview.turn && screenPreview.turn < lateTurn,
    `превью: экран — ход ${screenPreview?.turn}, обязательность — ход ${requiredPreview?.turn}; «да» на экран — ход ${lateTurn}`);

  const stale = trace.some((entry) => (entry.tool === 'set_field_required' || entry.arguments?.toolName === 'set_field_required'
    || entry.tool === 'edit_screen_layout' || entry.arguments?.toolName === 'edit_screen_layout')
    && JSON.stringify(entry.result ?? '').includes('PREVIEW_STALE'));
  const reviewed = trace.filter((entry) => ['edit_screen_layout', 'set_field_required'].includes(entry.tool)
    && entry.arguments?.dryRun === true && entry.turn >= lateTurn);
  add('P2b в ходе «да» на экран нет новых превью экрана и обязательности (PREVIEW_STALE не было)',
    reviewed.length === 0 || stale, `новых превью: ${reviewed.map((entry) => `#${entry.seq} ${entry.tool}`).join(', ') || 'нет'}; PREVIEW_STALE: ${stale}`);

  const reply = conversation.filter((entry) => entry.role === 'assistant')[(screenPreview?.turn ?? 0) - 1]?.content ?? '';
  const prose = reply.split('\n').filter((line) => !/^\s*\|/u.test(line)).join('\n').replaceAll('Кто обнаружил?', 'Кто обнаружил');
  const questions = (prose.match(/\?/gu) ?? []).length;
  const mentions = (pattern) => pattern.test(reply);
  add('P2c экран и обязательность показаны в одной таблице, один вопрос',
    /^\s*\|/mu.test(reply) && mentions(/обязательн/iu) && mentions(/экран/iu) && questions === 1,
    `таблица: ${/^\s*\|/mu.test(reply)}; вопросов вне таблицы: ${questions}; ход ${screenPreview?.turn}`);

  const planCalls = trace.filter((entry) => entry.tool === 'plan_field_screens');
  const addToScreens = applies(trace, 'add_field_to_screens');
  add('P3 нет вызовов plan_field_screens и apply add_field_to_screens', planCalls.length === 0 && addToScreens.length === 0,
    `plan_field_screens: ${planCalls.length}; add_field_to_screens apply: ${addToScreens.length}`);

  // The same read twice with no write between them is a repeat; get_operation polls are not reads of configuration.
  const repeats = [];
  const seen = new Map();
  for (const entry of trace) {
    if (entry.tool === 'start_operation') { seen.clear(); continue; }
    if (!/^(get|list|plan)_/u.test(entry.tool) || entry.tool === 'get_operation' || entry.arguments?.dryRun === true) continue;
    const key = `${entry.tool} ${stable(entry.arguments ?? {})}`;
    if (seen.has(key)) repeats.push(`#${seen.get(key)} и #${entry.seq} ${key}`);
    else seen.set(key, entry.seq);
  }
  const count = (name) => trace.filter((entry) => entry.tool === name).length;
  add('P4 нет повторного чтения (тот же tool и аргументы) без записи между ними', repeats.length === 0,
    `get_field_configurations: ${count('get_field_configurations')}, list_custom_field_types: ${count('list_custom_field_types')}`
    + (repeats.length ? `; повторы: ${repeats.join('; ')}` : ''));

  const screenDone = editApply[0] ? terminal(trace, editApply[0].start) : null;
  const screenDomain = screenDone?.operation?.result?.status;
  const requiredStart = requiredApply[0]?.start;
  add('P5 start_operation(edit_screen_layout) завершён успешно раньше start_operation(set_field_required), без PREVIEW_STALE',
    Boolean(screenDone && requiredStart && !['FAILED', 'PARTIAL', 'UNKNOWN'].includes(screenDomain)
      && screenDone.seq < requiredStart.seq && editApply[0].start.seq !== requiredStart.seq) && !stale,
    `экран: старт #${editApply[0]?.start.seq}, завершение #${screenDone?.seq}, итог ${screenDomain ?? 'нет'}; обязательность: старт #${requiredStart?.seq}; PREVIEW_STALE: ${stale}`
    + '; порядок по завершению вызовов (seq): параллельный запуск трассой не отличим');

  const contextApplies = applies(trace, 'create_field_context');
  const contextFields = contextApplies.map((item) => canonical(item.preview?.arguments?.fieldId ?? item.preview?.arguments?.field)).sort();
  const created = applies(trace, 'create_custom_fields');
  const createdNames = created.flatMap((item) => (item.preview?.arguments?.fields ?? []).map((field) => field.name)).sort();
  const createdTypes = Object.fromEntries(created.flatMap((item) => (item.preview?.arguments?.fields ?? []).map((field) => [field.name, field.typeKey])));
  const wantedNew = [f.stage, f.risk, f.klass, f.placeNew].sort();
  add('P6 create_field_context для «Система» и «Серийный номер», create_custom_fields ровно для четырёх новых',
    same(contextFields, [canonical(ids.system), canonical(ids.serial)].sort()) && created.length === 1 && same(createdNames, wantedNew)
      && createdTypes[f.risk] === 'textarea' && [f.stage, f.klass, f.placeNew].every((name) => createdTypes[name] === 'select'),
    JSON.stringify({ contexts: contextFields, created: createdNames, types: createdTypes }));

  // ---- Состояние Jira -----------------------------------------------------------------------------------------
  const readField = (name) => tool('get_field_contexts', { field: name });
  const newField = Object.fromEntries(await Promise.all([['stage', f.stage], ['risk', f.risk], ['klass', f.klass], ['placeNew', f.placeNew]]
    .map(async ([role, name]) => [role, await readField(name)])));
  const newId = (role) => customId(newField[role]?.field?.id ?? '');

  const layout = await tool('get_project_screens', { project: keys.target });
  const screenIds = [...new Set(layout.issueTypes.filter((item) => item.issueType.name === typeName)
    .flatMap((item) => item.screenScheme.screens.filter((screen) => Object.values(OPERATION_KEY).includes(screen.operation))
      .map((screen) => screen.id)))];
  const content = screenIds.length === 1 ? await tool('get_screen_contents', { screenId: screenIds[0] }) : null;
  const order = content ? content.tabs.flatMap((tab) => tab.fields.map((field) => canonical(field.fieldId ?? field.id))) : [];
  const expectedOrder = ['summary', ids.system, ids.serial, 'assignee', 'reporter', newId('stage'), ids.trial, newId('placeNew'),
    newId('risk'), ids.who, newId('klass'), 'description'].map(canonical);
  add('S1 порядок экрана Summary, Система, Серийный номер, Assignee, Reporter, Этап, Тип испытаний, Место выявления, Риск, Кто обнаружил?, Классификация, Description; «Место обнаружения» убрано',
    screenIds.length === 1 && screenIds[0] === prepared.screenId && same(order, expectedOrder) && !order.includes(canonical(ids.placeOld)),
    JSON.stringify({ screens: screenIds, order, expectedOrder }));

  checks.push(...await configChecks({ prepared, probe, newId }));
  checks.push(...await contextChecks({ prepared, tool, adminPage, newField, newId }));
  return checks;
}

/** S2a/S2b: required flags of the six fields and the untouched rest of the field configuration. Shared with incident-screen-stale. */
export async function configChecks({ prepared, probe, newId }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  const { keys, ids, typeName } = prepared;
  const configProbe = await probe('get_field_configurations', {
    project: keys.target, fields: [...prepared.baselineFields, ids.system, ids.serial, ...['stage', 'risk', 'klass', 'placeNew'].map(newId)].filter((id) => id !== 'customfield_'),
  });
  const configRead = { mappings: [], fields: [], schemeOtherProjects: {}, ...(configProbe.isError ? {} : configProbe.data) };
  const bugConfig = configRead.mappings.find((entry) => entry.issueTypeName === typeName)?.fieldConfiguration;
  const requiredIn = (fieldId) => configRead.fields.find((item) => canonical(item.field.id) === canonical(fieldId))
    ?.configurations.find((state) => state.fieldConfiguration.id === bugConfig?.id)?.required;
  const mustBe = [[ids.system, true], [ids.serial, true], [newId('stage'), true], [newId('risk'), false], [newId('klass'), false], [newId('placeNew'), false]];
  add('S2a Система, Серийный номер, Этап — required; Риск, Классификация, Место выявления — optional',
    bugConfig?.id === prepared.bugConfigId && mustBe.every(([id, value]) => requiredIn(id) === value),
    mustBe.map(([id, value]) => `${id}: ${requiredIn(id)} (ожидалось ${value})`).join('; '));
  const nowFlags = flagMap(configRead);
  const changed = Object.entries(prepared.flags).filter(([key, state]) => !same(nowFlags[key], state)).map(([key]) => key);
  add('S2b остальные поля конфигурации (системные и поля экрана) не изменились; схема и конфигурация прежние',
    changed.length === 0 && configRead.scheme?.id === prepared.fieldScheme.id && configRead.schemeOtherProjects?.total === 0,
    changed.length ? `изменены: ${changed.join(', ')}` : `${Object.keys(prepared.flags).length} флагов без изменений`);
  return checks;
}

/** S3–S5: contexts and options of the six fields, source untouched. Shared with incident-screen-stale. */
export async function contextChecks({ prepared, tool, adminPage, newField, newId }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  const { keys, ids, fieldNames: f, typeName } = prepared;
  const readField = (name) => tool('get_field_contexts', { field: name });
  const targetType = (context) => context && context.allProjects === false && context.allIssueTypes === false
    && keysOf(context.projects) === keys.target && namesOf(context.issueTypes) === typeName;
  const unchanged = (old, before) => old && old.configs?.[0]?.configId === before.configs[0].configId
    && same(old.projects, before.projects) && same(old.issueTypes, before.issueTypes);
  const split = async (role) => {
    const now = await readField(ids[role]);
    const before = prepared.baselines[role].contexts[0];
    return { now, before, old: now.contexts?.find((context) => context.contextId === before.contextId),
      fresh: now.contexts?.filter((context) => context.contextId !== before.contextId) ?? [] };
  };
  // Jira's default-value form lists an id-less "None" entry once the field is required: not an option of the context.
  const optionsOf = async (id, context) => (await contextOptions(adminPage, customId(id), context.contextId, context.configs[0].configId))
    .filter((option) => option.id);
  const WANTED = ['Заря R&D', 'Заря-С', 'Зарница R&D'];

  const system = await split('system');
  add('S3a Система: ровно один новый контекст target × тип сценария', system.now.contexts?.length === 2 && system.fresh.length === 1 && targetType(system.fresh[0]),
    JSON.stringify(system.fresh));
  const fresh = system.fresh[0];
  const freshOptions = fresh?.configs?.length === 1 ? await optionsOf(ids.system, fresh) : null;
  add('S3b Система: опции нового контекста ровно Заря R&D, Заря-С, Зарница R&D в этом порядке, без default',
    freshOptions && freshOptions.map((option) => option.value).join('|') === WANTED.join('|') && !freshOptions.some((option) => option.selected)
      && fresh.configs[0].configId !== system.before.configs[0].configId,
    JSON.stringify(freshOptions));
  const oldOptions = system.old ? await optionsOf(ids.system, system.old) : null;
  add('S3c Система: старый контекст source × тип сценария, его опции и scope не изменены',
    unchanged(system.old, system.before) && same(oldOptions, prepared.oldOptions), JSON.stringify({ old: system.old, oldOptions }));

  const serial = await split('serial');
  add('S3d Серийный номер: ровно один новый контекст target × тип сценария, старый без изменений',
    serial.now.contexts?.length === 2 && serial.fresh.length === 1 && targetType(serial.fresh[0]) && unchanged(serial.old, serial.before),
    JSON.stringify({ fresh: serial.fresh, old: serial.old }));

  const expectedOptions = { stage: ['ПСИ', 'ПНР', 'Эксплуатация'], klass: ['Разработка', 'Качество'], placeNew: ['Рассвет', 'Аврора'] };
  for (const role of ['stage', 'risk', 'klass', 'placeNew']) {
    const contexts = newField[role].contexts ?? [];
    const only = contexts.length === 1 && targetType(contexts[0]) ? contexts[0] : null;
    let options = 'не select';
    let ok = Boolean(only);
    if (only && expectedOptions[role]) {
      options = await optionsOf(newId(role), only);
      ok = options.map((option) => option.value).join('|') === expectedOptions[role].join('|');
    }
    add(`S4 ${f[role]}: контекст ровно target × тип сценария${expectedOptions[role] ? ', опции по запросу и в порядке' : ''}`, ok,
      JSON.stringify({ contexts: contexts.map((context) => ({ id: context.contextId, projects: keysOf(context.projects), types: namesOf(context.issueTypes) })), options }));
  }

  const source = await snapshot(tool, keys.source);
  add('S5 экраны и конфигурации полей source не изменились', same(source.layout, prepared.sourceBefore.layout)
    && same(source.configs, prepared.sourceBefore.configs), `схема экранов ${source.layout.scheme?.id}, схема полей ${source.configs.scheme?.id}`);
  return checks;
}
