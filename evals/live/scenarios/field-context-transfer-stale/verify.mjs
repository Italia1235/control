import { applies, parseResult } from '../../checks.mjs';

const same = (actual, expected) => JSON.stringify(actual) === JSON.stringify(expected);
const ids = (values = []) => values.map(String).sort();
const option = (value) => value && typeof value === 'object' ? { id: String(value.id), value: value.value } : null;
// Every write is checked through the plugin's own reads; Jira's display and search of issue values is out of scope.

function outcome(trace, apply) {
  if (!apply) return {};
  const started = parseResult(apply.start.result);
  const responses = [apply.start, ...trace.filter((entry) => entry.seq > apply.start.seq
    && entry.tool === 'get_operation' && started?.operationId
    && entry.arguments?.operationId === started.operationId)];
  for (const entry of responses) {
    const response = parseResult(entry.result);
    const result = response?.result ?? response;
    if (result?.errorCode || ['UPDATED', 'PARTIAL', 'FAILED'].includes(result?.status)) return { result, entry };
  }
  return {};
}

export default async function verify({ prepared, trace, conversation, lifecycle, tool }) {
  const checks = [];
  const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail });
  if (!prepared?.fieldId || !prepared.projects || !prepared.main || !prepared.warehouse) {
    return [{ name: 'автоматическая подготовка сохранена', ok: false, detail: 'нет полного setup.json' }];
  }
  const { fieldId, projects, main, warehouse } = prepared;
  const states = {
    initial: { main: ['source', 'moving'], warehouse: ['warehouse'] },
    firstForward: { main: ['source'], warehouse: ['warehouse', 'moving'] },
    injected: { main: ['source', 'moving', 'concurrent'], warehouse: ['warehouse'] },
    forward: { main: ['source', 'concurrent'], warehouse: ['warehouse', 'moving'] },
    reverse: { main: ['source', 'concurrent', 'moving'], warehouse: ['warehouse'] },
  };
  const contextFor = (value, config) => value?.contexts?.find((context) => String(context.contextId) === String(config.contextId));
  const expectedKeys = (roles) => ids(roles.map((role) => projects[role].key));
  const scope = (value, roles) => value?.allProjects === false && value.allIssueTypes === true
    && Array.isArray(value.projects) && same(ids(value.projects.map((project) => project.key)), expectedKeys(roles))
    && Array.isArray(value.issueTypes) && value.issueTypes.length === 0;
  const state = (value, expected) => value?.status === 'READ' && String(value.field?.id) === String(fieldId)
    && value.contexts?.length === 2 && ['main', 'warehouse'].every((role) => {
      const config = prepared[role];
      const context = contextFor(value, config);
      return scope(context, expected[role]) && context.projectsComplete === true
        && context.configs?.length === 1 && String(context.configs[0].configId) === String(config.configId)
        && context.options?.count === config.options.length && context.options.disabledCount === 0
        && context.defaultValue === (role === 'main' ? 'PRESENT' : 'ABSENT');
    });
  const request = (entry, expected) => entry?.tool === 'update_field_contexts' && entry.arguments?.dryRun === true
    && String(entry.arguments.fieldId) === String(fieldId) && entry.arguments.contexts?.length === 2
    && ['main', 'warehouse'].every((role) => {
      const context = entry.arguments.contexts.find((item) => String(item.contextId) === String(prepared[role].contextId));
      const wanted = expected[role];
      return context && context.allProjects !== true && context.allIssueTypes === true
        && (!context.issueTypes || context.issueTypes.length === 0) && context.projects?.length === wanted.length
        && same(ids(context.projects.map((reference) => {
          const project = Object.values(projects).find((project) => [String(project.id), project.key].includes(String(reference)));
          return project?.key ?? reference;
        })), expectedKeys(wanted));
    });
  const transition = (result, before, after, verdict) => result?.status === (verdict === 'UPDATED' ? 'UPDATED' : 'WOULD_UPDATE')
    && String(result.field?.id) === String(fieldId) && result.contexts?.length === 2
    && (!result.unchangedContexts || result.unchangedContexts.length === 0)
    && ['main', 'warehouse'].every((role) => {
      const config = prepared[role];
      const context = contextFor(result, config);
      return context?.verdict === verdict && scope(context.before, before[role]) && scope(context.after, after[role])
        && same(ids(context.preservedConfigIds), [String(config.configId)])
        && (verdict !== 'UPDATED' || scope(context.actual, after[role]));
    });
  const narrowing = (result, role) => contextFor(result, prepared[role])?.warnings?.some((warning) => warning.code === 'NARROWING');
  const messages = [];
  let turn = 0;
  for (const message of conversation ?? []) {
    if (message.role === 'user') turn++;
    messages.push({ ...message, turn: message.turn ?? turn });
  }
  const userAt = (number) => messages.find((message) => message.role === 'user' && message.turn === number)?.content ?? '';
  const assistantAt = (number) => messages.find((message) => message.role === 'assistant' && message.turn === number)?.content ?? '';
  const confirmed = (apply) => apply?.preview && apply.start.turn > apply.preview.turn
    && /^\s*да(?:\s|[,.!]|$)/iu.test(userAt(apply.start.turn)) && !/не\s+(?:примен|дел|меня)/iu.test(userAt(apply.start.turn))
    && assistantAt(apply.preview.turn).trim().length > 0;

  add('исходные области, конфигурации и опции подготовлены', state(prepared.baseline, states.initial), JSON.stringify(prepared.baseline));
  add('добавление занятого проекта без снятия отклонено без planId',
    prepared.invalidPreview?.status === 'FAILED' && prepared.invalidPreview.errorCode === 'PROJECT_IN_OTHER_CONTEXT'
      && !prepared.invalidPreview.planId && String(prepared.invalidPreview.message ?? '').includes(String(main.contextId)),
    JSON.stringify(prepared.invalidPreview));
  add('подготовлены разные опции и default Б',
    same(main.options.map((item) => item.value), ['А', 'Б', 'В'])
      && same(warehouse.options.map((item) => item.value), ['Г', 'Д'])
      && main.defaultValue?.value === 'Б' && main.options.some((item) => same(option(item), option(main.defaultValue))),
    JSON.stringify({ main, warehouse }));

  const transfers = applies(trace, 'update_field_contexts');
  const [stale, forward, reverse] = transfers;
  const staleOutcome = outcome(trace, stale);
  const forwardOutcome = outcome(trace, forward);
  const reverseOutcome = outcome(trace, reverse);
  add('только три применения переноса: устаревший, прямой, обратный',
    transfers.length === 3 && applies(trace).length === 3
      && !trace.some((entry) => entry.tool !== 'start_operation' && entry.arguments?.dryRun === false),
    transfers.map((apply) => `#${apply.start.seq} ${apply.planId}`).join('; '));
  add('первый план атомарно переносит проект между двумя контекстами',
    request(stale?.preview, states.firstForward)
      && transition(parseResult(stale?.preview?.result), states.initial, states.firstForward, 'WOULD_UPDATE')
      && narrowing(parseResult(stale?.preview?.result), 'main'),
    JSON.stringify(parseResult(stale?.preview?.result)));
  add('конкурентный проект добавлен после первого плана и до его применения',
    lifecycle?.injection && lifecycle.injection.previewPlanId === stale?.planId
      && lifecycle.injection.previewSeq === stale?.preview?.seq
      && lifecycle.injection.turn === stale?.preview?.turn && stale?.preview?.seq < stale?.start?.seq
      && state(lifecycle.injection.before, states.initial) && state(lifecycle.injection.after, states.injected),
    JSON.stringify(lifecycle?.injection));
  add('устаревшее применение действительно отказало PREVIEW_STALE',
    staleOutcome.result?.status === 'FAILED' && staleOutcome.result.errorCode === 'PREVIEW_STALE',
    JSON.stringify(staleOutcome.result));
  add('после PREVIEW_STALE области и конфигурации не перезаписаны',
    staleOutcome.result?.errorCode === 'PREVIEW_STALE'
      && same(lifecycle?.stale?.result, staleOutcome.result)
      && lifecycle.stale.turn === staleOutcome.entry?.turn
      && state(lifecycle.stale.contexts, states.injected), JSON.stringify(lifecycle?.stale));
  add('после отказа прочитано актуальное состояние перед новым планом',
    trace.some((entry) => entry.tool === 'get_field_contexts'
      && entry.seq > (staleOutcome.entry?.seq ?? Infinity) && entry.seq < (forward?.preview?.seq ?? -1)
      && state(parseResult(entry.result), states.injected)), 'ожидается живое чтение с конкурентным проектом');
  add('свежий план сохраняет конкурентный проект до и после прямого переноса',
    forward?.planId !== stale?.planId && forward?.preview?.seq > (staleOutcome.entry?.seq ?? Infinity)
      && request(forward?.preview, states.forward)
      && transition(parseResult(forward?.preview?.result), states.injected, states.forward, 'WOULD_UPDATE')
      && narrowing(parseResult(forward?.preview?.result), 'main'),
    JSON.stringify(parseResult(forward?.preview?.result)));
  add('каждый показанный план имеет отдельное последующее Да',
    [stale, forward, reverse].every(confirmed)
      && stale?.start.turn < forward?.start.turn && forward?.start.turn < reverse?.start.turn,
    transfers.map((apply) => `превью ${apply.preview?.turn}, применение ${apply.start.turn}: ${userAt(apply.start.turn)}`).join('; '));
  const warningText = assistantAt(stale?.preview?.turn);
  add('пользователю объяснены сохранность данных и риск невидимости/поиска',
    /не\s+удаля|сохран[яеё]|остан[еу]/iu.test(warningText)
      && /невидим|не\s+(?:видн|отображ)|скрыт/iu.test(warningText)
      && /поиск|искать|JQL/iu.test(warningText), warningText);
  add('прямое применение UPDATED для обоих контекстов с прежними configId',
    transition(forwardOutcome.result, states.injected, states.forward, 'UPDATED'), JSON.stringify(forwardOutcome.result));
  add('обратный перенос запрошен после прямого, не подтверждён заранее',
    reverse?.preview?.seq > (forwardOutcome.entry?.seq ?? Infinity)
      && reverse?.preview?.turn > forward?.start.turn
      && /верн|обрат/iu.test(userAt(reverse?.preview?.turn)) && /Основн/iu.test(userAt(reverse?.preview?.turn)),
    userAt(reverse?.preview?.turn));
  add('обратный план атомарный, независимый и сохраняет конкурентный проект',
    reverse?.planId !== forward?.planId && reverse?.planId !== stale?.planId
      && request(reverse?.preview, states.reverse)
      && transition(parseResult(reverse?.preview?.result), states.forward, states.reverse, 'WOULD_UPDATE')
      && narrowing(parseResult(reverse?.preview?.result), 'warehouse'), JSON.stringify(parseResult(reverse?.preview?.result)));
  add('обратное применение UPDATED для обоих контекстов с прежними configId',
    transition(reverseOutcome.result, states.forward, states.reverse, 'UPDATED'), JSON.stringify(reverseOutcome.result));

  for (const direction of ['forward', 'reverse']) {
    const phase = lifecycle?.[direction];
    const expectedResult = direction === 'forward' ? forwardOutcome.result : reverseOutcome.result;
    add(`${direction}: сохранён настоящий результат и снимок областей`,
      expectedResult?.status === 'UPDATED' && same(phase?.result, expectedResult) && state(phase?.contexts, states[direction]),
      JSON.stringify(phase?.contexts));
  }

  const final = await tool('get_field_contexts', { field: fieldId });
  add('живое конечное чтение: проект вернулся, конкурентный сохранён, конфигурации прежние',
    state(final, states.reverse), JSON.stringify(final));
  return checks;
}
