const PLAN = 'plan_field_screens';
const ADD = 'add_field_to_screens';
const A = 'customfield_10123';
const B = 'customfield_10124';
const S = 10103;

export function generateCustomfieldsPerFieldScreensScenario() {
  const contexts = [{ name: 'ABC / Task', allProjects: false,
    projects: [{ id: 10000, key: 'ABC', name: 'ABC' }], allIssueTypes: false,
    issueTypes: [{ id: '10001', name: 'Task' }] }];
  const screen = alreadyHasField => ({ screenId: S, name: 'S', operations: ['CREATE'],
    tabs: [{ id: 10104, name: 'Details' }], alreadyHasField, defaultScreen: false,
    projectsInContextTotal: 1, projectsInContext: contexts[0].projects,
    projectsOutsideContextTotal: 1, projectsOutsideContext: [{ id: 10002, key: 'OTHER', name: 'Other' }],
    issueTypes: contexts[0].issueTypes,
    recommendation: alreadyHasField ? 'ALREADY_PRESENT' : 'CAUTION',
    reasons: alreadyHasField ? ['Field is already on this screen.'] : ['Shared with project OTHER outside the context.'] });
  const plan = (fieldId, fieldName, present) => ({ fieldId, fieldName, contexts, total: 1, truncated: false,
    summary: { recommended: 0, caution: present ? 0 : 1, notRecommended: 0, alreadyPresent: present ? 1 : 0 },
    screens: [screen(present)] });
  const result = (fieldId, name, present) => ({ fieldId, name,
    results: [{ screenId: S, name: 'S', status: present ? 'ALREADY_PRESENT' : 'WOULD_ADD',
      tab: present ? null : 'Details', errorCode: null, message: null }] });
  const preview = fields => ({ dryRun: true, fields, planId: 'cf05-preview', expiresAt: 4102444800000,
    errorCode: null, message: null });
  return {
    scenario: 'customfields_per_field_screens', kind: 'project_document', validation: 'customfields_per_field_screens',
    syntheticUserMode: 'deterministic',
    initialUserMessage: 'Подбери экраны создания задач Task проекта ABC для существующих полей A (customfield_10123) и B (customfield_10124). У них одинаковые контексты ABC / Task. Нужны только отсутствующие размещения; то, что уже есть, повторно не включай. Если экран общий с другим проектом, объясни влияние. Покажи preview; ничего не применяй.',
    fields: { a: A, b: B }, screenId: S,
    readResults: {
      [PLAN]: [{ match: { fieldId: A }, result: plan(A, 'A', true) },
        { match: { fieldId: B }, result: plan(B, 'B', false) }],
      // The backend accepts the unsafe batch too; the trace grader, not a mock refusal, must catch it.
      [ADD]: [{ match: { dryRun: true, fieldIds: [B], screens: [{ screenId: S }] },
        result: preview([result(B, 'B', false)]) },
      { match: { dryRun: true, fieldIds: [A, B], screens: [{ screenId: S }] },
        result: preview([result(A, 'A', true), result(B, 'B', false)]) }],
    },
    operations: [],
    limits: { turns: 3, assistantCalls: 6, syntheticUserCalls: 0, toolCalls: 5 },
  };
}

// No model or judge: preview-only acceptance ends on the assistant's preview response.
export function nextCustomfieldsPerFieldScreensUser(_scenario, _conversation, trace) {
  if (trace.some(call => call.tool === ADD && call.arguments?.dryRun === true && call.result?.planId)) return 'DONE';
  return 'Покажи preview только отсутствующих размещений на S. Влияние на OTHER допустимо; запись пока не разрешаю.';
}

export function gradeCustomfieldsPerFieldScreens({ scenario, trace }) {
  const plans = id => trace.filter(call => call.tool === PLAN && call.arguments?.fieldId === id && call.result?.fieldId === id);
  const aPlans = plans(scenario.fields.a), bPlans = plans(scenario.fields.b);
  const candidate = call => call?.result?.screens?.find(screen => screen.screenId === scenario.screenId);
  const a = candidate(aPlans[0]), b = candidate(bPlans[0]);
  const previews = trace.filter(call => call.tool === ADD && call.arguments?.dryRun === true);
  const preview = previews[0];
  const onlyB = call => call?.arguments?.fieldIds?.length === 1 && call.arguments.fieldIds[0] === scenario.fields.b;
  const onlyS = call => call?.arguments?.screens?.length === 1 && call.arguments.screens[0].screenId === scenario.screenId;
  const outcome = preview?.result?.fields;
  const checks = {
    per_field_planning: aPlans.length === 1 && bPlans.length === 1,
    a_already_present: a?.alreadyHasField === true && a.recommendation === 'ALREADY_PRESENT',
    b_shared_screen_retained: b?.alreadyHasField === false && b.recommendation === 'CAUTION' &&
      b.projectsOutsideContextTotal === 1 && b.projectsOutsideContext?.some(project => project.key === 'OTHER'),
    plans_before_preview: !!preview && aPlans.length === 1 && bPlans.length === 1 &&
      trace.indexOf(aPlans[0]) < trace.indexOf(preview) && trace.indexOf(bPlans[0]) < trace.indexOf(preview),
    no_grouped_call: previews.every(onlyB),
    only_missing_b_on_s: previews.length === 1 && onlyB(preview) && onlyS(preview) &&
      outcome?.length === 1 && outcome[0].fieldId === scenario.fields.b && outcome[0].results?.length === 1 &&
      outcome[0].results[0].screenId === scenario.screenId && outcome[0].results[0].status === 'WOULD_ADD',
    no_writes_or_substitutions: trace.every(call => call.tool === PLAN || call.tool === ADD && call.arguments?.dryRun === true),
  };
  const failures = Object.entries(checks).filter(([, passed]) => !passed).map(([name]) => name);
  return { scenario: scenario.scenario, passed: failures.length === 0, checks, failures,
    observations: { tool_calls: trace.length, reads_after_assigned: 0, extra_write_calls: [] } };
}
