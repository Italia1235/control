import { applies, parseResult } from '../../checks.mjs';

const TASK = 'Задача';

export default async function verify({ names, prepared, trace, tool }) {
  const project = names['KEY:proj'];
  const schemeName = names['NAME:scheme'];
  const configName = names['NAME:cfg'];
  const checks = [];
  const read = await tool('get_field_configurations', { project, fields: ['duedate', 'priority', 'summary'] });
  const state = (fieldId) => read.fields.find((field) => field.field.id === fieldId);
  const flag = (fieldId, configuration) =>
    state(fieldId).configurations.find((entry) => entry.fieldConfiguration.name === configuration);

  // 1. Due Date optional everywhere.
  const dueDate = state('duedate').configurations;
  checks.push({ name: 'Due Date необязательна во всех конфигурациях проекта', ok: dueDate.every((entry) => !entry.required),
    detail: dueDate.map((entry) => `${entry.fieldConfiguration.name}: ${entry.required}`).join('; ') });
  const optional = applies(trace, 'set_field_required').filter(({ preview }) => preview?.arguments?.rows?.some((row) => row.required === false));
  checks.push({ name: 'снятие обязательности применено через required:false', ok: optional.length >= 1,
    detail: `${optional.length} apply с required:false` });

  // 2. Summary refused, never applied.
  const summaryApplies = applies(trace, 'set_field_required').filter(({ preview }) => preview?.arguments?.rows?.some((row) => row.fields?.includes('summary')));
  const locked = trace.some((entry) => entry.tool === 'set_field_required'
    && parseResult(entry.result)?.errorCode === 'LOCKED_FIELD');
  checks.push({ name: 'Summary: получен LOCKED_FIELD, apply нет', ok: locked && summaryApplies.length === 0,
    detail: `LOCKED_FIELD=${locked}, apply=${summaryApplies.length}` });
  checks.push({ name: 'Summary осталась обязательной', ok: state('summary').configurations.every((entry) => entry.required),
    detail: `locked=${state('summary').locked}` });

  // 3. New scheme assigned; Task on the new configuration, the rest on the original one.
  checks.push({ name: `проекту назначена схема «${schemeName}»`, ok: read.scheme.name === schemeName,
    detail: `${read.scheme.name} (было ${prepared.scheme.name})` });
  const mapping = (name) => read.mappings.find((entry) => entry.issueTypeName === name);
  const original = prepared.mappings.find((entry) => entry.issueTypeName !== TASK)?.fieldConfiguration;
  const others = read.mappings.filter((entry) => entry.issueTypeName !== TASK);
  checks.push({ name: `${TASK} на «${configName}», остальные типы на исходной конфигурации`,
    ok: mapping(TASK)?.fieldConfiguration.name === configName
      && others.every((entry) => entry.fieldConfiguration.id === original?.id),
    detail: read.mappings.map((entry) => `${entry.issueTypeName} → ${entry.fieldConfiguration.name}`).join('; ') });
  const configApply = applies(trace, 'create_field_configuration').at(-1);
  const schemeApply = applies(trace, 'create_field_configuration_scheme').at(-1);
  const assignApply = applies(trace, 'assign_field_configuration_scheme').at(-1);
  checks.push({ name: 'назначение схемы подтверждено в ходе позже создания',
    ok: Boolean(configApply && schemeApply && assignApply
      && assignApply.start.turn > configApply.start.turn && assignApply.start.turn > schemeApply.start.turn),
    detail: `конфигурация — ход ${configApply?.start.turn}, схема — ${schemeApply?.start.turn}, назначение — ${assignApply?.start.turn}` });
  const assignPreview = parseResult(assignApply?.preview?.result);
  checks.push({ name: 'превью назначения показало смену конфигурации у Задачи',
    ok: Boolean(assignPreview?.changes?.some((change) => change.issueTypeName === TASK)),
    detail: JSON.stringify(assignPreview?.changes?.map((change) => [change.issueTypeName, change.before?.name, change.after?.name])) });

  // 4. Priority required only in the Task configuration.
  const taskPriority = flag('priority', configName);
  const otherPriority = original ? flag('priority', original.name) : null;
  checks.push({ name: 'Priority обязательна только в конфигурации Задачи',
    ok: Boolean(taskPriority?.required && otherPriority && !otherPriority.required),
    detail: `${configName}: ${taskPriority?.required}; ${original?.name}: ${otherPriority?.required}` });
  return checks;
}
