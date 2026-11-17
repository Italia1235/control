// Isolated analogue of the D2LOT × Incident manual run. Fresh target and source projects
// with ONE issue type: the existing standard type chosen with LIVE_ISSUE_TYPE (names['TYPE:issue'], see issue-type.mjs).
// Target has its own Issue Type Screen Scheme / Screen Scheme with ONE screen for
// CREATE, EDIT and VIEW (Summary, Assignee, Reporter, Тип испытаний, Место обнаружения, Кто обнаружил?, Description) and
// its own Field Configuration Scheme and Field Configuration. «Система» (select) and «Серийный номер» (text) exist with
// one context source × that type each and are absent from target. Reused by later scenarios through prepare().
import { resolveIssueType } from '../../issue-type.mjs';
import { contextOptions } from '../field-context-create-select-options/setup.mjs';
import { OPERATION_KEY, readLayout } from '../screen-own-layout/setup.mjs';
import { projectManagement } from '../../project-templates.mjs';

export const SYSTEM_FIELDS = ['summary', 'description', 'assignee', 'reporter', 'priority', 'duedate', 'labels',
  'components', 'environment', 'fixVersions', 'versions'];

/** Field names of one run; the same strings are in scenario.md. */
export const fieldNames = (runId) => ({
  system: `Система ${runId}`, serial: `Серийный номер ${runId}`, stage: `Этап инцидента ${runId}`,
  risk: `Риск эксплуатации ${runId}`, klass: `Классификация несоответствия ${runId}`, placeNew: `Место выявления ${runId}`,
  trial: `Тип испытаний ${runId}`, placeOld: `Место обнаружения ${runId}`, who: `Кто обнаружил? ${runId}`,
});

export const canonical = (id) => String(id).replace(/^customfield_/, '');

/** "field@configuration" -> required/hidden, from a get_field_configurations read with fields. */
export function flagMap(read) {
  const flags = {};
  for (const field of read.fields ?? []) {
    for (const state of field.configurations ?? []) {
      flags[`${canonical(field.field.id)}@${state.fieldConfiguration.id}`] = { required: state.required, hidden: state.hidden };
    }
  }
  return flags;
}

/** Everything of a project that must stay as it is: screens and the field configuration structure. */
export async function snapshot(tool, project) {
  const layout = await readLayout(tool, project);
  const configs = await tool('get_field_configurations', { project });
  return { layout, configs: { scheme: configs.scheme, defaultMapping: configs.defaultMapping, mappings: configs.mappings } };
}

export async function prepare({ names, runId, tool, apply, rest, adminPage, log }) {
  const keys = { target: names['KEY:target'], source: names['KEY:source'] };
  const f = fieldNames(runId);
  // Checked here again, against the live Jira, even though the runner resolved it first: this is what setup writes with.
  const TYPE = (await resolveIssueType(rest, names['TYPE:issue'])).name;

  for (const role of ['target', 'source']) {
    await apply('create_project', projectManagement({ name: names[`NAME:${role}`], key: keys[role], lead: 'admin' }));
    await apply('set_project_issue_types', { project: keys[role], issueTypes: [TYPE] });
  }

  const created = await apply('create_custom_fields', { fields: [
    { name: f.system, typeKey: 'select', options: ['КА Demo1', 'ШС'].map((value) => ({ value })),
      contexts: [{ name: `Старый ${runId}`, projects: [keys.source], issueTypes: [TYPE] }] },
    { name: f.serial, typeKey: 'textfield', contexts: [{ name: `Старый ${runId}`, projects: [keys.source], issueTypes: [TYPE] }] },
    ...[f.trial, f.placeOld, f.who].map((name) => ({ name, typeKey: 'textfield', contexts: [{ projects: [keys.target], issueTypes: [TYPE] }] })),
  ] });
  const idOf = (name) => {
    const id = (created.report?.created ?? []).find((item) => item.kind === 'CUSTOM_FIELD' && item.name === name)?.id;
    if (!id) throw new Error(`поле «${name}» не создано: ${JSON.stringify(created).slice(0, 500)}`);
    return id;
  };
  const ids = { system: idOf(f.system), serial: idOf(f.serial), trial: idOf(f.trial), placeOld: idOf(f.placeOld), who: idOf(f.who) };

  // Own field configuration scheme and configuration for target (clone is exact, so the type keeps default settings).
  await apply('clone_project_field_configurations', { project: keys.target });
  const fieldConfig = await tool('get_field_configurations', { project: keys.target, fields: [...SYSTEM_FIELDS, ids.trial, ids.placeOld, ids.who] });
  if (fieldConfig.scheme.defaultScheme || fieldConfig.schemeOtherProjects.total > 0
      || fieldConfig.fieldConfigurations.some((use) => use.otherProjects.total > 0)) {
    throw new Error(`у ${keys.target} нет своих конфигураций полей: ${JSON.stringify(fieldConfig.scheme)}`);
  }
  const bugConfig = fieldConfig.mappings.find((entry) => entry.issueTypeName === TYPE)?.fieldConfiguration;
  if (!bugConfig) throw new Error(`нет конфигурации полей для ${TYPE}: ${JSON.stringify(fieldConfig.mappings)}`);
  const flags = flagMap(fieldConfig);

  // One screen for CREATE, EDIT and VIEW of target × the scenario type, in a private scheme.
  const screenName = `${keys.target} ${TYPE} экран`;
  const screenOrder = ['Summary', 'Assignee', 'Reporter', f.trial, f.placeOld, f.who, 'Description'];
  await apply('create_screen', { name: screenName, fields: screenOrder.map((name) => ({ name })) });
  await apply('set_project_operation_screens', {
    project: keys.target,
    mappings: [{ issueTypes: [TYPE], screens: { CREATE: screenName, EDIT: screenName, VIEW: screenName } }],
  });
  const layout = await readLayout(tool, keys.target);
  const entry = layout.operations.find((item) => item.issueType === TYPE);
  const operationScreens = Object.values(OPERATION_KEY).map((key) => entry?.screens?.[key]);
  const screenIds = [...new Set(operationScreens)];
  if (layout.scheme.isDefault || layout.scheme.otherProjects !== 0 || screenIds.length !== 1 || !screenIds[0]) {
    throw new Error(`экран ${keys.target} × ${TYPE} не собственный и единый: ${JSON.stringify({ scheme: layout.scheme, entry })}`);
  }
  const screenId = screenIds[0];
  const expected = ['summary', 'assignee', 'reporter', ids.trial, ids.placeOld, ids.who, 'description'].map(canonical);
  const order = layout.contents[screenId].tabs.flatMap((tab) => tab.fields).map(canonical);
  if (JSON.stringify(order) !== JSON.stringify(expected)) throw new Error(`порядок экрана ${screenId}: ${JSON.stringify(order)}`);
  const screenUsage = (await tool('get_project_screens', { project: keys.target })).issueTypes
    .flatMap((item) => item.screenScheme.screens).filter((screen) => screen.id === screenId);
  if (screenUsage.some((screen) => screen.otherProjects > 0)) throw new Error(`экран ${screenId} используют другие проекты`);

  // Existing fields: exactly one old context, target outside of it.
  const baselines = {};
  for (const role of ['system', 'serial']) {
    const baseline = await tool('get_field_contexts', { field: ids[role] });
    if (baseline.contexts?.length !== 1 || baseline.contexts[0].projects.some((project) => project.key === keys.target)
        || !baseline.contexts[0].projects.some((project) => project.key === keys.source)) {
      throw new Error(`неверное исходное состояние ${role}: ${JSON.stringify(baseline)}`);
    }
    baselines[role] = baseline;
  }
  const old = baselines.system.contexts[0];
  const oldOptions = await contextOptions(adminPage, ids.system, old.contextId, old.configs[0].configId);
  if (oldOptions.map((option) => option.value).join('|') !== 'КА Demo1|ШС') throw new Error(`опции старого контекста: ${JSON.stringify(oldOptions)}`);

  const sourceBefore = await snapshot(tool, keys.source);
  log(`target ${keys.target}: схема экранов ${layout.scheme.id}, экран ${screenId} на CREATE/EDIT/VIEW, схема полей ${fieldConfig.scheme.id}, конфигурация ${bugConfig.id}`);
  log(`Система ${ids.system}, Серийный номер ${ids.serial}: контекст ${keys.source} × ${TYPE}; source: экраны/конфигурации сняты`);
  return {
    keys, typeName: TYPE, fieldNames: f, ids, screenName, screenId, screenOrder: order, layout,
    fieldScheme: fieldConfig.scheme, bugConfigId: bugConfig.id, baselineFields: [...SYSTEM_FIELDS, ids.trial, ids.placeOld, ids.who], flags,
    baselines, oldOptions, sourceBefore,
    known: { 'Тип задачи': TYPE, 'Ключ целевого проекта': keys.target, 'Ключ соседнего проекта': keys.source },
  };
}

export default async function setup(context) {
  return prepare(context);
}
