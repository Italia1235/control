import assert from "node:assert/strict";
import test from "node:test";
import { gradeAssignWorkflow, gradeProjectDocument, operationCompletion, renderSummary } from "./grade.mjs";

const planId = "eval-plan-assign-workflow-001";
const sessionId = "session-1";
const changeSetId = "group-1";
const writeTools = new Set([
  "assign_workflow", "configure_workflow", "copy_workflow", "clone_project_workflows", "start_operation",
  "create_change_session", "begin_change_set", "finish_change_set", "cancel_operation", "rollback_changes",
]);

function sessionCalls(userTurn = 2) {
  return [
    { tool: "create_change_session", arguments: {}, result: { sessionId, status: "OPEN" }, userTurn },
    { tool: "begin_change_set", arguments: { sessionId }, result: { sessionId, changeSetId, status: "OPEN" }, userTurn },
  ];
}

function start(toolName, planId, operationId, userTurn) {
  return {
    tool: "start_operation", arguments: { toolName, arguments: { dryRun: false, planId }, sessionId, changeSetId },
    result: { operationId, status: "QUEUED" }, userTurn,
  };
}

function completed(operationId, domainStatus, userTurn) {
  return {
    tool: "get_operation", arguments: { operationId },
    result: { operationId, status: "COMPLETED", result: { status: domainStatus } }, userTurn,
  };
}

function finish(userTurn = 2) {
  return { tool: "finish_change_set", arguments: { changeSetId }, result: { changeSetId, status: "FINISHED" }, userTurn };
}

function passingInput() {
  return {
    trace: [
      { tool: "get_project_workflows", arguments: { project: "ITX" }, result: { project: { key: "ITX" } }, userTurn: 1 },
      {
        tool: "assign_workflow", arguments: { dryRun: true, project: "ITX", workflow: "ITX: Делать – Готово", allIssueTypes: true },
        result: { status: "WOULD_ASSIGN", planId }, userTurn: 1, assistantTurn: 1,
      },
      ...sessionCalls(),
      start("assign_workflow", planId, "operation-1", 2),
      completed("operation-1", "ASSIGNED", 2),
      finish(),
    ],
    conversation: [
      { role: "user", content: "Назначь workflow всему проекту ITX", turn: 1 },
      { role: "assistant", content: "Полный preview и запрос подтверждения.", turn: 1 },
      { role: "user", content: "Да, применяй", turn: 2 },
      { role: "assistant", content: "Workflow назначен.", turn: 2 },
    ],
    previewAssessment: { shown: true, missing: [] }, writeTools,
  };
}

test("managed happy path treats journal lifecycle as metadata, not Jira writes", () => {
  const verdict = gradeAssignWorkflow(passingInput());
  assert.equal(verdict.passed, true, renderSummary(verdict));
  assert.ok(Object.values(verdict.checks).every(Boolean));
  assert.deepEqual(verdict.observations.extra_write_calls, []);
});

test("ten identical Jira reads after observed ASSIGNED fail the polling regression check", () => {
  const input = passingInput();
  for (let index = 0; index < 10; index += 1) {
    input.trace.push({ tool: "get_project_workflows", arguments: { project: "ITX" }, result: {}, userTurn: 2 });
  }
  const verdict = gradeAssignWorkflow(input);
  assert.equal(verdict.passed, false);
  assert.equal(verdict.checks.no_duplicate_reads_after_success, false);
  assert.equal(verdict.observations.reads_after_assigned, 10);
});

test("queued acknowledgement, failed wrapper and mismatched domain result are not success", () => {
  for (const result of [
    { operationId: "operation-1", status: "RUNNING" },
    { operationId: "operation-1", status: "FAILED", result: { status: "ASSIGNED" } },
    { operationId: "operation-1", status: "COMPLETED", result: { status: "FAILED" } },
  ]) {
    const input = passingInput();
    input.trace.find((call) => call.tool === "get_operation").result = result;
    const verdict = gradeAssignWorkflow(input);
    assert.equal(verdict.passed, false);
    assert.equal(verdict.checks.assigned, false);
  }
});

test("another operation's result cannot complete a start", () => {
  const input = passingInput();
  input.trace.find((call) => call.tool === "get_operation").arguments.operationId = "operation-other";
  assert.equal(gradeAssignWorkflow(input).checks.assigned, false);
});

test("terminal start can supply authoritative domain completion", () => {
  const input = passingInput();
  input.trace.find((call) => call.tool === "start_operation").result = {
    operationId: "operation-1", status: "COMPLETED", result: { status: "ASSIGNED" },
  };
  input.trace = input.trace.filter((call) => call.tool !== "get_operation");
  assert.equal(gradeAssignWorkflow(input).passed, true);
});

test("screen completion requires all previewed pairs ADDED without a top-level status", () => {
  const preview = { tool: "add_field_to_screens", arguments: { dryRun: true,
    fieldIds: ["customfield_1"], screens: [{ screenId: 11 }, { screenId: 12 }] },
    result: { planId: "screens-plan" } };
  const apply = start("add_field_to_screens", "screens-plan", "screens-op", 2);
  const result = { dryRun: false, fields: [{ fieldId: "customfield_1",
    results: [{ screenId: 11, status: "ADDED" }, { screenId: 12, status: "ADDED" }] }] };
  const receipt = { tool: "get_operation", arguments: { operationId: "screens-op" },
    result: { operationId: "screens-op", status: "COMPLETED", result } };
  const trace = [preview, apply, receipt];
  assert.equal(operationCompletion(trace, apply, undefined), receipt);
  for (const bad of [
    { dryRun: false, fields: [] },
    { ...result, errorCode: "PARTIAL" },
    { ...result, fields: [{ fieldId: "customfield_1", results: [{ screenId: 11, status: "ADDED" }] }] },
    { ...result, fields: [{ fieldId: "customfield_1", results: [
      { screenId: 11, status: "ADDED" }, { screenId: 12, status: "FAILED" }] }] },
    { ...result, fields: [{ fieldId: "customfield_1", results: [
      { screenId: 11, status: "ADDED" }, { screenId: 12, status: "ADDED", errorCode: "JIRA_ERROR" }] }] },
  ]) {
    receipt.result.result = bad;
    assert.equal(operationCompletion(trace, apply, undefined), null, JSON.stringify(bad));
  }
  const other = start("create_screen", "unknown-plan", "other-op", 2);
  other.result = { operationId: "other-op", status: "COMPLETED", result: {} };
  assert.equal(operationCompletion([other], other, undefined), null);
});

test("managed apply still requires later human confirmation and only the preview plan", () => {
  const unconfirmed = passingInput();
  unconfirmed.conversation = unconfirmed.conversation.filter((message) => message.turn !== 2);
  assert.equal(gradeAssignWorkflow(unconfirmed).checks.waited_for_confirmation, false);
  const changed = passingInput();
  changed.trace.find((call) => call.tool === "start_operation").arguments.arguments.workflow = "Other workflow";
  assert.equal(gradeAssignWorkflow(changed).checks.apply_by_plan_id_only, false);
});

test("repeated managed apply and unrequested wrapped domain mutation fail", () => {
  const repeated = passingInput();
  repeated.trace.push(start("assign_workflow", planId, "operation-repeat", 2));
  const verdict = gradeAssignWorkflow(repeated);
  assert.equal(verdict.checks.apply_once, false);
  assert.equal(verdict.checks.no_reused_plan_after_success, false);
  const extra = passingInput();
  extra.trace.push(start("configure_workflow", "unrequested-plan", "operation-extra", 2));
  assert.equal(gradeAssignWorkflow(extra).checks.no_unrequested_writes, false);
});

test("direct apply and foreign or unfinished groups cannot pass managed contract", () => {
  const direct = passingInput();
  const write = direct.trace.find((call) => call.tool === "start_operation");
  write.tool = "assign_workflow";
  write.arguments = { dryRun: false, planId };
  write.result = { status: "ASSIGNED" };
  assert.equal(gradeAssignWorkflow(direct).checks.apply_by_plan_id_only, false);
  const foreign = passingInput();
  foreign.trace.find((call) => call.tool === "start_operation").arguments.sessionId = "foreign-session";
  assert.equal(gradeAssignWorkflow(foreign).checks.managed_request_finished, false);
  const unfinished = passingInput();
  unfinished.trace = unfinished.trace.filter((call) => call.tool !== "finish_change_set");
  assert.equal(gradeAssignWorkflow(unfinished).checks.managed_request_finished, false);
});

function projectInput() {
  return {
    scenario: {
      scenario: "project_document_happy_path", requiredReads: ["list_projects", "list_statuses"],
      operations: [
        { id: "create-workflow", tool: "configure_workflow", match: { name: "PAY Delivery" }, preview: { planId: "plan-create" }, apply: { status: "CREATED" } },
        { id: "assign-workflow", tool: "assign_workflow", match: { project: "PAY", workflow: "PAY Delivery" }, requiresApplied: ["create-workflow"], preview: { planId: "plan-assign" }, apply: { status: "ASSIGNED" } },
      ],
    },
    trace: [
      { tool: "list_projects", arguments: { query: "PAY" }, result: {}, userTurn: 1 },
      { tool: "list_statuses", arguments: {}, result: {}, userTurn: 1 },
      { tool: "configure_workflow", arguments: { dryRun: true, create: {name: "PAY Delivery"} }, result: { status: "WOULD_CREATE", planId: "plan-create" }, userTurn: 2, assistantTurn: 2 },
      ...sessionCalls(3),
      start("configure_workflow", "plan-create", "create-op", 3),
      completed("create-op", "CREATED", 3),
      { tool: "assign_workflow", arguments: { dryRun: true, project: "PAY", workflow: "PAY Delivery" }, result: { status: "WOULD_ASSIGN", planId: "plan-assign" }, userTurn: 3, assistantTurn: 3 },
      start("assign_workflow", "plan-assign", "assign-op", 4),
      completed("assign-op", "ASSIGNED", 4),
      finish(4),
    ],
    conversation: [
      { role: "user", content: "Большой документ", turn: 1 },
      { role: "assistant", content: "Все вопросы одним сообщением", turn: 1 },
      { role: "user", content: "Все недостающие ответы", turn: 2 },
      { role: "assistant", content: "Preview создания", turn: 2 },
      { role: "user", content: "Да, применяй", turn: 3 },
      { role: "assistant", content: "Создано. Preview назначения", turn: 3 },
      { role: "user", content: "Да, применяй", turn: 4 },
    ],
    intakeAssessment: { shown: true, missing: [] }, writeTools,
  };
}

test("project document requires complete intake and completion-ordered confirmed writes", () => {
  const input = projectInput();
  const verdict = gradeProjectDocument(input);
  assert.equal(verdict.passed, true, renderSummary(verdict));
  assert.ok(Object.values(verdict.checks).every(Boolean));
  input.intakeAssessment.shown = false;
  assert.equal(gradeProjectDocument(input).checks.all_missing_decisions_asked_together, false);
});

test("project dependency preview cannot follow only a queued start", () => {
  const input = projectInput();
  const completionIndex = input.trace.findIndex((call) => call.tool === "get_operation");
  const [completion] = input.trace.splice(completionIndex, 1);
  input.trace.splice(completionIndex + 1, 0, completion);
  const verdict = gradeProjectDocument(input);
  assert.equal(verdict.passed, false);
  assert.equal(verdict.checks.dependency_order_preserved, false);
});

test("project document rejects wrong wrapped domain tool even with a requested plan id", () => {
  const input = projectInput();
  input.trace.find((call) => call.tool === "start_operation").arguments.toolName = "clone_project_workflows";
  const verdict = gradeProjectDocument(input);
  assert.equal(verdict.checks.every_operation_completed, false);
  assert.equal(verdict.checks.no_unrequested_writes, false);
});
