import { operationCompletion } from './grade.mjs';
import { isDeepStrictEqual } from 'node:util';

const ADD = 'add_field_to_screens';
const FIELD = 'customfield_10100';
const FIELD_NAME = 'LAB — Код эксперимента 17';
const QUESTION = /\?|подтверд|добавить[^.]*на оба/iu;

export function generateLabTwoScreensScenario() {
  const contexts = [{ name: 'LAB / Task', allProjects: false,
    projects: [{ id: 20000, key: 'LAB', name: 'LAB' }], allIssueTypes: false,
    issueTypes: [{ id: '20001', name: 'Task' }] }];
  const screens = [[21001, 'LAB Create', ['CREATE']], [21002, 'LAB Edit/View', ['EDIT', 'VIEW']]]
    .map(([screenId, name, operations]) => ({ screenId, name, operations,
      tabs: [{ id: screenId + 10, name: 'Вкладка поля' }], alreadyHasField: false,
      defaultScreen: false, recommendation: 'RECOMMENDED', projectsInContext: contexts[0].projects,
      projectsInContextTotal: 1, projectsOutsideContext: [], projectsOutsideContextTotal: 0,
      issueTypes: contexts[0].issueTypes, reasons: [] }));
  const match = { fieldIds: [FIELD], screens: screens.map(({ screenId }) => ({ screenId })) };
  const response = (dryRun) => ({ dryRun, fields: [{ fieldId: FIELD, name: FIELD_NAME,
    results: screens.map(({ screenId, name }) => ({ screenId, name,
      status: dryRun ? 'WOULD_ADD' : 'ADDED', tab: 'Вкладка поля', errorCode: null, message: null })) }],
    errorCode: null, message: null });
  return {
    scenario: 'customfields_lab_two_screens', kind: 'project_document', validation: 'customfields_lab_two_screens',
    syntheticUserMode: 'deterministic', fieldId: FIELD, screens,
    initialUserMessage: `Поле «${FIELD_NAME}» (${FIELD}) уже создано для LAB / Task и необязательно. Подбери экраны, на которых его можно показать, и предложи добавление.`,
    readResults: { plan_field_screens: [{ match: { fieldId: FIELD }, result: {
      fieldId: FIELD, fieldName: FIELD_NAME, contexts, screens, total: 2, truncated: false,
      summary: { recommended: 2, caution: 0, notRecommended: 0, alreadyPresent: 0 } } }] },
    operations: [{ id: 'lab-screens', tool: ADD, match,
      preview: { ...response(true), planId: 'lab-screens-plan', expiresAt: 4102444800000 }, apply: response(false) }],
    limits: { turns: 5, assistantCalls: 10, syntheticUserCalls: 0, toolCalls: 12 },
  };
}

// The user also answers an early question, so the violator can finish and be graded on order.
export function nextLabTwoScreensUser(_scenario, conversation) {
  return QUESTION.test(conversation.at(-1)?.content ?? '') ? 'Да, применяй.' : 'Продолжай до результата.';
}

export function gradeLabTwoScreens({ scenario, trace, conversation, modelMessages = [] }) {
  const previews = trace.filter(c => c.tool === ADD && c.arguments?.dryRun === true);
  const preview = previews[0];
  const starts = trace.filter(c => c.tool === 'start_operation');
  const start = starts[0];
  const completion = operationCompletion(trace, start, undefined);
  const questions = conversation.filter(m => m.role === 'assistant' && QUESTION.test(m.content));
  const approvals = conversation.filter(m => m.role === 'user' && /^(да|yes)[,.!\s]/iu.test(m.content));
  const toolReceiptIndex = modelMessages.findIndex(m => m.role === 'tool' && (() => {
    try { return JSON.parse(m.content).planId === preview?.result?.planId; } catch { return false; }
  })());
  const questionIndex = modelMessages.findIndex(m => m.role === 'assistant' && QUESTION.test(m.content ?? ''));
  const approvalIndex = modelMessages.findIndex(m => m.role === 'user' &&
    m.content === approvals[0]?.content);
  const completionIndex = completion ? modelMessages.findIndex(m => m.role === 'tool' && (() => {
    try { return isDeepStrictEqual(JSON.parse(m.content), completion.result); }
    catch { return false; }
  })()) : -1;
  // Visible text after approval is a result report, never a replacement for a terminal receipt.
  // Include content accompanying tool calls: an early success must not be hidden in such a message.
  const reports = modelMessages.map((message, index) => ({ message, index }))
    .filter(({ message, index }) => index > approvalIndex &&
      message.role === 'assistant' && message.content?.trim());
  const shown = questions[0]?.content ?? '';
  const rows = shown.split(/\r?\n/u).filter(line => line.trim().startsWith('|'))
    .map(line => line.trim().replace(/^\||\|$/gu, '').split('|').map(cell => cell.trim()));
  const placements = preview?.result?.fields?.find(field => field.fieldId === FIELD)?.results ?? [];
  const plans = trace.filter(c => c.tool === 'plan_field_screens');
  // Sharing is returned by the preceding planner, not by the placement preview DTO.
  const plannedScreens = plans.filter(call => call.arguments?.fieldId === FIELD &&
    trace.indexOf(call) < trace.indexOf(preview)).flatMap(call => call.result?.screens ?? []);
  const sharing = (preview?.arguments?.screens ?? []).map(({ screenId }) => {
    const screen = plannedScreens.find(candidate => candidate.screenId === screenId);
    const total = screen?.projectsOutsideContextTotal;
    const projects = screen?.projectsOutsideContext ?? [];
    const countShown = Number.isSafeInteger(total) && total > 0 &&
      new RegExp(`(?<![\\p{L}\\p{N}_])${total}(?![\\p{L}\\p{N}_])`, 'u').test(shown);
    const namesShown = total > 0 && projects.length === total && projects.every(project =>
      [project.name, project.key].some(name => typeof name === 'string' && name.length > 0 && shown.includes(name)));
    return { screenId, projectsOutsideContextTotal: total ?? null, countShown, namesShown };
  });
  const sharingVisible = sharing.length > 0 && sharing.every(screen =>
    screen.projectsOutsideContextTotal === 0 || screen.countShown || screen.namesShown);
  const previewVisible = (shown.includes(FIELD_NAME) || shown.includes(FIELD)) &&
    placements.length === scenario.screens.length && scenario.screens.every(screen => {
      const placement = placements.find(row => row.screenId === screen.screenId);
      return placement && rows.some(cells => cells.length >= 3 &&
        (cells[0].includes(screen.name) || cells[0].includes(String(screen.screenId))) &&
        cells[1].length > 0 && cells.slice(2).some(cell => cell.includes(placement.tab)));
    }) && sharingVisible;
  const checks = {
    planned_before_preview: plans.length === 1 && plans[0].arguments?.fieldId === FIELD &&
      trace.indexOf(plans[0]) < trace.indexOf(preview),
    preview_before_first_question: !!preview?.result?.planId && toolReceiptIndex >= 0 && questionIndex > toolReceiptIndex,
    exact_preview: previews.length === 1 && JSON.stringify(preview.arguments.fieldIds) === JSON.stringify([FIELD]) &&
      preview.arguments.screens?.length === 2 && scenario.screens.every(s => preview.arguments.screens.some(x => x.screenId === s.screenId)),
    one_screen_approval: questions.length === 1 && approvals.length === 1 &&
      approvals[0].turn > preview?.userTurn && approvals[0].turn <= start?.userTurn,
    preview_visible: previewVisible,
    managed_once_by_plan: starts.length === 1 && start.arguments?.toolName === ADD &&
      JSON.stringify(Object.keys(start.arguments.arguments).sort()) === JSON.stringify(['dryRun', 'planId']) &&
      start.arguments.arguments.dryRun === false && start.arguments.arguments.planId === preview?.result?.planId,
    terminal_pairs: !!completion,
    final_after_terminal: approvalIndex >= 0 && completionIndex > approvalIndex && reports.length === 1 &&
      reports[0].index > completionIndex && !reports[0].message.tool_calls?.length &&
      reports[0].index === modelMessages.length - 1,
    no_other_writes: trace.every(c => ['plan_field_screens', ADD, 'start_operation', 'get_operation',
      'create_change_session', 'begin_change_set', 'finish_change_set'].includes(c.tool) &&
      !(c.tool === ADD && c.arguments?.dryRun !== true)),
    group_finished: trace.filter(c => c.tool === 'finish_change_set' && c.result?.status === 'FINISHED').length === 1,
  };
  const failures = Object.entries(checks).filter(([, ok]) => !ok).map(([key]) => key);
  return { scenario: scenario.scenario, passed: failures.length === 0, checks, failures,
    observations: { tool_calls: trace.length, reads_after_assigned: 0, extra_write_calls: [],
      sharing_disclosures: sharing,
      sharing_wording_observed: /других проектов\s*[:—-]?\s*0|другие проекты не|только LAB/iu.test(shown) } };
}
