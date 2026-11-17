const TOOL = 'plan_field_screens';
const FIELD = 'customfield_10123';
const CURSOR = 'cf04-page-2';

export function generateCustomfieldsScreenPaginationScenario() {
  const contexts = [{ name: 'ABC / Task', allProjects: false,
    projects: [{ key: 'ABC', name: 'ABC' }], allIssueTypes: false,
    issueTypes: [{ id: '10001', name: 'Task' }] }];
  const screens = Array.from({ length: 205 }, (_, index) => ({
    screenId: 20000 + index, name: `Create ${String(index + 1).padStart(3, '0')}`,
    operations: ['CREATE'], tabs: [{ id: 30000 + index, name: 'Details' }],
    alreadyHasField: false, defaultScreen: false, projectsInContextTotal: 1,
    projectsInContext: contexts[0].projects, projectsOutsideContextTotal: 0,
    projectsOutsideContext: [], issueTypes: contexts[0].issueTypes,
    recommendation: 'RECOMMENDED', reasons: ['Only projects of the context use this screen.'],
  }));
  const page = (items, nextCursor) => ({ fieldId: FIELD, fieldName: 'A', contexts, total: 205,
    truncated: true, summary: { recommended: 205, caution: 0, notRecommended: 0, alreadyPresent: 0 },
    screens: items, ...(nextCursor ? { nextCursor } : {}) });
  return {
    scenario: 'customfields_screen_pagination', kind: 'project_document',
    validation: 'customfields_screen_pagination', syntheticUserMode: 'deterministic',
    initialUserMessage: 'Прочитай все рекомендованные экраны для существующего поля A (customfield_10123). Ничего не добавляй и не делай preview. Прочитай следующие страницы, если они есть. Только после полного чтения сообщи итог отдельной строкой «Просмотрено рекомендованных экранов: N», подставив фактически прочитанное число. До последней страницы не сообщай такой итог.',
    expectedIds: screens.map(screen => screen.screenId),
    readResults: { [TOOL]: [
      { match: { fieldId: FIELD, recommendation: 'RECOMMENDED' }, result: page(screens.slice(0, 200), CURSOR) },
      { match: { fieldId: FIELD, recommendation: 'RECOMMENDED', cursor: CURSOR }, result: page(screens.slice(200)) },
    ] },
    operations: [], limits: { turns: 2, assistantCalls: 4, syntheticUserCalls: 0, toolCalls: 3 },
  };
}

export function nextCustomfieldsScreenPaginationUser() {
  return 'DONE';
}

// modelMessages preserve statement/tool-result order within one visible assistant turn.
export function gradeCustomfieldsScreenPagination({ scenario, trace, conversation, modelMessages }) {
  const reads = trace.filter(call => call.tool === TOOL);
  const ids = reads.flatMap(call => call.result?.screens?.map(screen => screen.screenId) ?? []);
  let pending = true;
  let prematureTotal = false;
  const calls = new Map();
  const totalLine = /Просмотрено рекомендованных экранов:\s*(\d+)/iu;
  for (const message of modelMessages) {
    if (message.role === 'assistant') {
      if (pending && totalLine.test(message.content ?? '')) prematureTotal = true;
      for (const call of message.tool_calls ?? []) calls.set(call.id, call.function.name);
    } else if (message.role === 'tool' && calls.get(message.tool_call_id) === TOOL) {
      const result = JSON.parse(message.content);
      pending = !result?.screens || !!result.nextCursor;
    }
  }
  const final = conversation.filter(message => message.role === 'assistant').at(-1)?.content ?? '';
  const checks = {
    continuation_cursor_matches_selection: reads.length === 2 && !Object.hasOwn(reads[0].arguments, 'cursor') &&
      typeof reads[0].result.nextCursor === 'string' &&
      reads[1].arguments.cursor === reads[0].result.nextCursor &&
      reads.every(call => call.arguments.fieldId === FIELD && call.arguments.recommendation === 'RECOMMENDED'),
    last_page_has_no_next_cursor: reads.length > 0 && Array.isArray(reads.at(-1).result?.screens) &&
      !Object.hasOwn(reads.at(-1).result, 'nextCursor'),
    same_selection: reads.every(call => call.arguments.fieldId === FIELD && call.arguments.recommendation === 'RECOMMENDED'),
    exact_coverage: ids.length === scenario.expectedIds.length && new Set(ids).size === ids.length &&
      scenario.expectedIds.every(id => ids.includes(id)),
    no_premature_total: !prematureTotal,
    final_total: Number(final.match(totalLine)?.[1]) === scenario.expectedIds.length,
    read_only: trace.every(call => call.tool === TOOL),
  };
  const failures = Object.entries(checks).filter(([, passed]) => !passed).map(([name]) => name);
  return { scenario: scenario.scenario, passed: failures.length === 0, checks, failures,
    observations: { tool_calls: trace.length, extra_write_calls: [] } };
}
