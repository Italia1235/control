import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import http from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";
import { fileURLToPath, pathToFileURL } from "node:url";
import { parseOmpMessage, scenarioSimulator } from "./run.mjs";
import { generateWorkflowNewScreenScenario } from "./workflow-new-screen.mjs";

const execFileAsync = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

async function server(handler) {
  const instance = http.createServer(handler);
  await new Promise((resolve) => instance.listen(0, "127.0.0.1", resolve));
  return instance;
}

async function runEval(instance, extraEnv = {}) {
  let stdout = "";
  let stderr = "";
  let code = 0;
  try {
    const result = await execFileAsync(process.execPath, ["evals/offline/run.mjs"], {
      cwd: ROOT,
      env: {
        ...process.env,
        EVAL_API_KEY: "test",
        EVAL_ASSISTANT_MODEL: "test-assistant",
        EVAL_USER_MODEL: "test-user",
        EVAL_JUDGE_MODEL: "test-judge",
        EVAL_BASE_URL: instance ? `http://127.0.0.1:${instance.address().port}` : "http://127.0.0.1:1",
        ...extraEnv,
      },
      timeout: 10_000,
      maxBuffer: 2 * 1024 * 1024,
    });
    stdout = result.stdout;
    stderr = result.stderr;
  } catch (error) {
    code = error.code;
    stdout = error.stdout ?? "";
    stderr = error.stderr ?? "";
  }
  const match = stdout.match(/Артефакты:\s+(.+)$/mu);
  assert.ok(match, `artifact path missing\nstdout=${stdout}\nstderr=${stderr}`);
  const artifactDirectory = path.resolve(ROOT, match[1].trim());
  const verdict = JSON.parse(await readFile(path.join(artifactDirectory, "verdict.json"), "utf8"));
  const raw = await readFile(path.join(artifactDirectory, "omp-raw.jsonl"), "utf8");
  const transcript = await readFile(path.join(artifactDirectory, "conversation.md"), "utf8");
  const trace = JSON.parse(await readFile(path.join(artifactDirectory, "tool-trace.json"), "utf8"));
  await rm(artifactDirectory, { recursive: true, force: true });
  return { code, stdout, verdict, trace, raw, transcript };
}

function openAi(message) {
  return JSON.stringify({ choices: [{ message }] });
}


const malformedEnvelopes = [
  '{"content":null,"tool_calls":[{"id":"apply-workflow","function":{"name":"start_operation","arguments":{"toolName":"configure_workflow","arguments":{"dryRun":false,"planId":"workflow-plan-0"}}}]}]}',
  '{"content":null,"tool_calls":[{"id":"call_3","function":{"name":"start_operation","arguments":{"toolName":"create_screen","arguments":{"dryRun":false,"planId":"screen-plan-0"}}}]}]}',
];

for (const [index, fragment] of malformedEnvelopes.entries()) {
  test(`saved malformed apply fragment ${index + 1} is rejected without bracket repair`, () => {
    assert.throws(() => parseOmpMessage(fragment), /NORMALIZATION_ERROR: malformed control envelope/);
    assert.throws(() => parseOmpMessage(JSON.stringify({content:fragment})), /NORMALIZATION_ERROR/);
  });
}

test("valid and nested envelopes preserve command arguments", () => {
  const envelope = JSON.stringify({content:null,tool_calls:[{
    id:"nested-call",function:{name:"list_projects",arguments:{query:"PAY"}},
  }]});
  for (const text of [envelope, JSON.stringify({content:envelope}), `\`\`\`json\n${envelope}\n\`\`\``]) {
    const message = parseOmpMessage(text);
    assert.equal(message.content, null);
    assert.deepEqual(message.tool_calls, [{
      id:"nested-call",type:"function",function:{name:"list_projects",arguments:'{"query":"PAY"}'},
    }]);
  }
  assert.deepEqual(parseOmpMessage(JSON.stringify({content:JSON.stringify({content:"Ответ."})})),
    {role:"assistant",content:"Ответ."});
});

test("ordinary prose and non-command JSON examples remain chat without tool execution", () => {
  for (const text of ["Обычный ответ.", '{"example":{"name":"start_operation","arguments":{"dryRun":false}}}',
    'Пример JSON: {"content":null,"tool_calls":[]} — это пример, не команда.']) {
    assert.deepEqual(parseOmpMessage(text), {role:"assistant",content:text});
  }
});

test("invalid schema, arguments and trailing brackets reject the entire envelope", () => {
  for (const envelope of [
    {content:42}, {tool_calls:{}},
    {tool_calls:[{function:{name:"start_operation",arguments:'{"dryRun":false'}}]},
    {tool_calls:[{function:{name:"start_operation",arguments:[]}}]},
    {tool_calls:[{function:{arguments:{}}}]},
    {tool_calls:[{function:{name:"start_operation",arguments:{},extra:true}}]},
    {tool_calls:[{function:{name:"start_operation",arguments:{}},extra:true}]},
  ]) assert.throws(() => parseOmpMessage(JSON.stringify(envelope)), /NORMALIZATION_ERROR/);
  assert.throws(() => parseOmpMessage('{"content":"ok"}]}'), /NORMALIZATION_ERROR/);
});

test("offline OMP runner captures redacted raw output and stops malformed apply with ERROR", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "omp-offline-regression-"));
  const preload = path.join(directory, "fake-omp.mjs");
  // Replace the subprocess boundary, never invoke OMP or a model.
  await writeFile(preload, `
    import childProcess from 'node:child_process';
    import {EventEmitter} from 'node:events';
    import {syncBuiltinESMExports} from 'node:module';
    childProcess.spawn = () => {
      const child = new EventEmitter();
      child.stdout = new EventEmitter(); child.stderr = new EventEmitter();
      child.kill = () => {};
      process.nextTick(() => {
        child.stdout.emit('data', process.env.OFFLINE_OMP_STREAM);
        child.stderr.emit('data', 'Bearer unknown-credential password="unknown-password"');
        child.emit('close', 0);
      });
      return child;
    };
    syncBuiltinESMExports();
  `);
  try {
    for (const fragment of malformedEnvelopes) {
      const stream = JSON.stringify({type:"message_end",message:{role:"assistant",
        content:[{type:"text",text:fragment},{type:"text",text:"secret-fixture"}]}}) + "\n";
      const diagnostic = JSON.stringify({type:"diagnostic",password:"unseen-password",
        text:JSON.stringify({apiKey:'unseen"key'})}) + "\n";
      const result = await runEval(null, {
        EVAL_ASSISTANT_MODEL:"", EVAL_API_KEY:"secret-fixture", EVAL_OMP_MODEL:"offline-fixture",
        EVAL_TOKEN_LIMIT_MODE:"soft", EVAL_MAX_TOKENS_PER_RUN:"300000", PI_CODING_AGENT_DIR:directory,
        NODE_OPTIONS:`--import=${pathToFileURL(preload).href}`,
        OFFLINE_OMP_STREAM:stream + diagnostic,
      });
      assert.equal(result.code, 2, result.verdict.diagnostics.terminationReason);
      assert.equal(result.verdict.status, "ERROR");
      assert.match(result.verdict.infrastructureError, /NORMALIZATION_ERROR/);
      assert.deepEqual(result.trace, []);
      assert.equal(result.verdict.diagnostics.assistantCalls, 1);
      assert.equal(result.verdict.diagnostics.requestEvents.length, 1);
      assert.equal(result.verdict.diagnostics.requestEvents[0].outcome, "normalization-error");
      assert.ok(!result.transcript.includes("tool_calls"));
      const records = result.raw.trim().split("\n").map(JSON.parse);
      assert.equal(records.length, 1);
      assert.equal(records[0].stdout.split("\n")[0] + "\n", stream.replaceAll("secret-fixture", "[REDACTED]"));
      const savedDiagnostic = JSON.parse(records[0].stdout.trim().split("\n")[1]);
      assert.equal(savedDiagnostic.password, "[REDACTED]");
      assert.deepEqual(JSON.parse(savedDiagnostic.text), {apiKey:"[REDACTED]"});
      assert.ok(!result.raw.includes("unknown-credential"));
      assert.ok(!result.raw.includes("unknown-password"));
      assert.ok(!result.raw.includes("secret-fixture"));
    }
  } finally {
    await rm(directory, {recursive:true,force:true});
  }
});

test("request timeout retries twice, fails, and persists diagnostics", async () => {
  const instance = await server((_request, _response) => {});
  try {
    const result = await runEval(instance, {
      EVAL_REQUEST_TIMEOUT_MS: "100",
      EVAL_SCENARIO_TIMEOUT_MS: "3000",
    });
    assert.equal(result.code, 1);
    assert.equal(result.verdict.passed, false);
    assert.match(result.verdict.diagnostics.terminationReason, /assistant request #1 timeout/);
    assert.equal(result.verdict.diagnostics.requestEvents.length, 3);
    assert.deepEqual(
      result.verdict.diagnostics.requestEvents.map((event) => event.attempt),
      [1, 2, 3],
    );
    assert.ok(result.verdict.diagnostics.requestEvents.every((event) => event.outcome === "request-timeout"));
    assert.match(result.stdout, /attempt=3; request-timeout/);
  } finally {
    instance.closeAllConnections();
    await new Promise((resolve) => instance.close(resolve));
  }
});

test("identical consecutive read-only calls fail as a polling loop", async () => {
  let calls = 0;
  const instance = await server((request, response) => {
    request.resume();
    calls += 1;
    const id = `call-${calls}`;
    response.writeHead(200, { "content-type": "application/json" });
    response.end(openAi({
      role: "assistant",
      content: null,
      tool_calls: [{
        id,
        type: "function",
        function: { name: "get_project_workflows", arguments: "{\"project\":\"ITX\"}" },
      }],
    }));
  });
  try {
    const result = await runEval(instance, {
      EVAL_REQUEST_TIMEOUT_MS: "1000",
      EVAL_SCENARIO_TIMEOUT_MS: "5000",
    });
    assert.equal(result.code, 1);
    assert.match(result.verdict.diagnostics.terminationReason, /duplicate\/polling loop/);
    assert.equal(result.verdict.diagnostics.toolCalls, 2);
    assert.match(result.stdout, /tool: get_project_workflows/);
  } finally {
    instance.closeAllConnections();
    await new Promise((resolve) => instance.close(resolve));
  }
});

async function assignmentScenario() {
  return JSON.parse(await readFile(path.join(ROOT, "evals/offline/assign_workflow_happy_path.json"), "utf8"));
}

function confirmedContext() {
  return {
    userTurn: 2, assistantTurn: 2,
    conversation: [
      { role: "user", content: "Назначь workflow", turn: 1 },
      { role: "assistant", content: "Последствия preview. Применить?", turn: 1 },
      { role: "user", content: "Да, применяй", turn: 2 },
    ],
  };
}

function managedArguments(execute, toolName, planId) {
  const { sessionId } = execute("create_change_session", {});
  const { changeSetId } = execute("begin_change_set", { sessionId });
  return { toolName, arguments: { dryRun: false, planId }, sessionId, changeSetId };
}

test("managed start applies domain state once and persists result through operation reads", async () => {
  const scenario = await assignmentScenario();
  const execute = scenarioSimulator(scenario);
  execute("assign_workflow", {
    dryRun: true, project: "ITX", workflow: scenario.preview.workflow, allIssueTypes: true,
  });
  const args = managedArguments(execute, "assign_workflow", scenario.planId);
  const acknowledgement = execute("start_operation", args, confirmedContext());
  assert.equal(acknowledgement.status, "QUEUED");
  assert.ok(acknowledgement.operationId);
  const readback = execute("get_project_workflows", { project: "ITX" });
  assert.ok(readback.issueTypes.every((item) => item.workflow === scenario.preview.workflow));
  const operation = execute("get_operation", { operationId: acknowledgement.operationId });
  assert.equal(operation.status, "COMPLETED");
  assert.equal(operation.result.status, "ASSIGNED");
  assert.deepEqual(execute("start_operation", args, confirmedContext()),
    { operationId: acknowledgement.operationId, status: "COMPLETED", alreadyStarted: true });
  assert.equal(execute("get_operation", { operationId: acknowledgement.operationId }).result.status, "ASSIGNED");
  assert.equal(execute("finish_change_set", { changeSetId: args.changeSetId }).status, "FINISHED");
  assert.equal(execute("start_operation", args, confirmedContext()).status, "FAILED");
});

test("managed operation reads validate optional waitSeconds without changing terminal results", async () => {
  const scenario = await assignmentScenario();
  const execute = scenarioSimulator(scenario);
  execute("assign_workflow", {
    dryRun: true, project: "ITX", workflow: scenario.preview.workflow, allIssueTypes: true,
  });
  const args = managedArguments(execute, "assign_workflow", scenario.planId);
  const { operationId } = execute("start_operation", args, confirmedContext());
  const expected = execute("get_operation", { operationId });
  assert.equal(expected.status, "COMPLETED");
  for (const waitSeconds of [0, 1, 10, 20])
    assert.deepEqual(execute("get_operation", { operationId, waitSeconds }), expected);
  for (const waitSeconds of [-1, 21, 1.5, "20", null, true, {}, []])
    assert.equal(execute("get_operation", { operationId, waitSeconds }).errorCode, "EVAL_ARGUMENT_INVALID");
  assert.equal(execute("get_operation", { operationId, waitSeconds: 20, unexpected: true }).errorCode, "EVAL_NOT_OWNED");
  assert.equal(execute("get_operation", { operationId: "foreign", waitSeconds: 20 }).errorCode, "EVAL_NOT_OWNED");
});

test("managed start without ownership ids uses one owned group and keeps plans single-use", async () => {
  const scenario = await assignmentScenario();
  const execute = scenarioSimulator(scenario);
  execute("assign_workflow", { dryRun: true, project: "ITX", workflow: scenario.preview.workflow, allIssueTypes: true });
  const args = { toolName: "assign_workflow", arguments: { dryRun: false, planId: scenario.planId } };
  assert.equal(execute("start_operation", { ...args, sessionId: "partial" }, confirmedContext()).errorCode, "EVAL_APPLY_INVALID");
  const started = execute("start_operation", args, confirmedContext());
  const operation = execute("get_operation", { operationId: started.operationId });
  assert.equal(operation.status, "COMPLETED");
  assert.equal(operation.result.status, "ASSIGNED");
  assert.deepEqual(execute("start_operation", args, confirmedContext()),
    { operationId: started.operationId, status: "COMPLETED", alreadyStarted: true });
  const sessions = execute("list_change_sessions", {}).sessions;
  assert.equal(sessions.length, 1);
  assert.equal(operation.sessionId, sessions[0].sessionId);
  assert.equal(execute("finish_change_set", { changeSetId: operation.changeSetId }).status, "FINISHED");
});

test("managed writes reject foreign ownership, absent approval, tool mismatch and direct apply", async () => {
  const scenario = await assignmentScenario();
  const execute = scenarioSimulator(scenario);
  execute("assign_workflow", {
    dryRun: true, project: "ITX", workflow: scenario.preview.workflow, allIssueTypes: true,
  });
  const args = managedArguments(execute, "assign_workflow", scenario.planId);
  assert.equal(execute("start_operation", { ...args, sessionId: "foreign" }, confirmedContext()).status, "FAILED");
  assert.equal(execute("start_operation", { ...args, changeSetId: "foreign" }, confirmedContext()).status, "FAILED");
  assert.equal(execute("get_operation", { operationId: "foreign" }).status, "FAILED");
  assert.equal(execute("start_operation", args).errorCode, "EVAL_CONFIRMATION_REQUIRED");
  const oldApproval = confirmedContext();
  oldApproval.conversation[2].turn = 1;
  assert.equal(execute("start_operation", args, oldApproval).errorCode, "EVAL_CONFIRMATION_REQUIRED");
  const invisible = confirmedContext();
  invisible.conversation = invisible.conversation.filter((message) => message.role !== "assistant");
  assert.equal(execute("start_operation", args, invisible).errorCode, "EVAL_CONFIRMATION_REQUIRED");
  assert.equal(execute("start_operation", { ...args, toolName: "configure_workflow" }, confirmedContext()).errorCode, "PREVIEW_STALE");
  assert.equal(execute("assign_workflow", args.arguments).errorCode, "EVAL_UNMANAGED_APPLY");
  const original = execute("get_project_workflows", { project: "ITX" });
  assert.ok(original.issueTypes.some((item) => item.workflow !== scenario.preview.workflow));
});

test("dependent preview waits for observed domain completion, not a start acknowledgement", () => {
  const scenario = {
    kind: "project_document",
    operations: [
      { id: "first", tool: "configure_workflow", match: { name: "First" },
        preview: { status: "WOULD_CREATE", planId: "first-plan" }, apply: { status: "CREATED" } },
      { id: "second", tool: "assign_workflow", match: { project: "PAY" }, requiresApplied: ["first"],
        preview: { status: "WOULD_ASSIGN", planId: "second-plan" }, apply: { status: "ASSIGNED" } },
    ],
  };
  const execute = scenarioSimulator(scenario);
  execute("configure_workflow", { dryRun: true, create: {name: "First"} });
  const args = managedArguments(execute, "configure_workflow", "first-plan");
  const acknowledgement = execute("start_operation", args, confirmedContext());
  assert.equal(execute("assign_workflow", { dryRun: true, project: "PAY" }).errorCode, "EVAL_DEPENDENCY_NOT_APPLIED");
  execute("get_operation", { operationId: acknowledgement.operationId });
  assert.equal(execute("assign_workflow", { dryRun: true, project: "PAY" }).status, "WOULD_ASSIGN");
});

test("failed domain result remains persisted but cannot unlock dependent preview", () => {
  const scenario = {
    kind: "project_document",
    operations: [
      { id: "first", tool: "configure_workflow", match: { name: "First" },
        preview: { status: "WOULD_CREATE", planId: "first-plan" }, apply: { status: "FAILED", errorCode: "DOMAIN_FAILURE" } },
      { id: "second", tool: "assign_workflow", match: { project: "PAY" }, requiresApplied: ["first"],
        preview: { status: "WOULD_ASSIGN", planId: "second-plan" }, apply: { status: "ASSIGNED" } },
    ],
  };
  const execute = scenarioSimulator(scenario);
  execute("configure_workflow", { dryRun: true, create: {name: "First"} });
  const args = managedArguments(execute, "configure_workflow", "first-plan");
  const acknowledgement = execute("start_operation", args, confirmedContext());
  const operation = execute("get_operation", { operationId: acknowledgement.operationId });
  assert.equal(operation.status, "COMPLETED");
  assert.equal(operation.result.errorCode, "DOMAIN_FAILURE");
  assert.equal(execute("assign_workflow", { dryRun: true, project: "PAY" }).errorCode, "EVAL_DEPENDENCY_NOT_APPLIED");
});

for (const automaticOwnership of [false, true]) {
test(`runner completes confirmed assignment with ${automaticOwnership ? "runner-owned" : "explicit"} lifecycle`, async () => {
  const scenario = await assignmentScenario();
  let assistantRequest = 0;
  const resultFor = (body, name) => {
    const call = body.messages.flatMap((message) => message.tool_calls ?? []).find((item) => item.function.name === name);
    return JSON.parse(body.messages.find((message) => message.role === "tool" && message.tool_call_id === call.id).content);
  };
  const instance = await server(async (request, response) => {
    let text = "";
    for await (const chunk of request) text += chunk;
    const body = JSON.parse(text);
    let message;
    if (body.model === "test-user") {
      message = { role: "assistant", content: "Да, применяй" };
    } else if (body.model === "test-judge") {
      message = { role: "assistant", content: JSON.stringify({ shown: true, missing: [] }) };
    } else {
      assistantRequest += 1;
      const toolCall = (name, args) => ({
        id: `managed-${assistantRequest}-${name}`, type: "function",
        function: { name, arguments: JSON.stringify(args) },
      });
      let calls = [];
      if (automaticOwnership && assistantRequest === 3) {
        calls = [toolCall("start_operation", {
          toolName: "assign_workflow", arguments: { dryRun: false, planId: resultFor(body, "assign_workflow").planId },
        })];
      } else if (automaticOwnership && assistantRequest === 4) {
        calls = [toolCall("get_operation", { operationId: resultFor(body, "start_operation").operationId })];
      } else
      if (assistantRequest === 1) {
        calls = [
          toolCall("get_project_workflows", { project: "ITX" }),
          toolCall("assign_workflow", { dryRun: true, project: "ITX", workflow: scenario.preview.workflow, allIssueTypes: true }),
        ];
      } else if (!automaticOwnership && assistantRequest === 3) {
        calls = [toolCall("create_change_session", {})];
      } else if (!automaticOwnership && assistantRequest === 4) {
        calls = [toolCall("begin_change_set", { sessionId: resultFor(body, "create_change_session").sessionId })];
      } else if (!automaticOwnership && assistantRequest === 5) {
        calls = [toolCall("start_operation", {
          toolName: "assign_workflow", arguments: { dryRun: false, planId: resultFor(body, "assign_workflow").planId },
          sessionId: resultFor(body, "create_change_session").sessionId,
          changeSetId: resultFor(body, "begin_change_set").changeSetId,
        })];
      } else if (!automaticOwnership && assistantRequest === 6) {
        calls = [toolCall("get_operation", { operationId: resultFor(body, "start_operation").operationId })];
      } else if (!automaticOwnership && assistantRequest === 7) {
        calls = [toolCall("finish_change_set", { changeSetId: resultFor(body, "begin_change_set").changeSetId })];
      }
      message = calls.length ? { role: "assistant", content: null, tool_calls: calls } : {
        role: "assistant", content: assistantRequest === 2
          ? "ITX: Делать – Готово для ITX: Задача MIGRATE (4 задачи), Ошибка ALREADY, История READY. Колонки досок не изменятся. Применить?"
          : "Workflow назначен. Запрос завершён.",
      };
    }
    response.writeHead(200, { "content-type": "application/json" });
    response.end(openAi(message));
  });
  try {
    const result = await runEval(instance, { EVAL_REQUEST_TIMEOUT_MS: "1000", EVAL_SCENARIO_TIMEOUT_MS: "5000" });
    assert.equal(result.code, 0, JSON.stringify(result.verdict));
    assert.equal(result.verdict.passed, true);
    assert.equal(result.verdict.checks.managed_request_finished, true);
    assert.deepEqual(result.verdict.observations.extra_write_calls, []);
    const operation = result.trace.find((call) => call.tool === "get_operation").result;
    assert.equal(operation.status, "COMPLETED");
    assert.equal(operation.result.status, "ASSIGNED");
    assert.equal(operation.result.workflow, scenario.preview.workflow);
    assert.equal(result.trace.some((call) => call.tool === "assign_workflow" && call.arguments.dryRun === false), false);
  } finally {
    instance.closeAllConnections();
    await new Promise((resolve) => instance.close(resolve));
  }
});
}

for (const [tool, args, code] of [
  ["start_operation", { toolName: "create_screen", arguments: { dryRun: true } }, "EVAL_APPLY_INVALID"],
  ["get_operation", { operationId: "missing" }, "EVAL_NOT_OWNED"],
]) {
  test(`runner reports ${code} as ERROR without secondary violations`, async () => {
    let requests = 0;
    const instance = await server((request, response) => {
      request.resume();
      requests += 1;
      response.writeHead(200, { "content-type": "application/json" });
      response.end(openAi({ role: "assistant", content: null, tool_calls: [{
        id: "infra", type: "function", function: { name: tool, arguments: JSON.stringify(args) },
      }] }));
    });
    try {
      const result = await runEval(instance, { EVAL_SCENARIO: "workflow_new_screen" });
      assert.equal(result.code, 2);
      assert.equal(result.verdict.status, "ERROR");
      assert.equal(result.verdict.passed, false);
      assert.match(result.verdict.infrastructureError, new RegExp(code));
      assert.deepEqual(result.verdict.checks, {});
      assert.deepEqual(result.verdict.violations, []);
      assert.equal(result.verdict.failures.length, 1);
      assert.match(result.verdict.failures[0], new RegExp(code));
      assert.equal(requests, 1);
      assert.match(result.stdout, /ERROR/);
    } finally {
      instance.closeAllConnections();
      await new Promise(resolve => instance.close(resolve));
    }
  });
}

test("runner completes obvious date field scenario without asking about type", async () => {
  const scenario = generateWorkflowNewScreenScenario(2,"obvious");
  let requests = 0;
  const instance = await server(async (request, response) => {
    let text = "";
    for await (const chunk of request) text += chunk;
    const body = JSON.parse(text);
    requests += 1;
    const step = requests - 2;
    const toolCall = (name, args) => ({ id: `obvious-${step}-${name}`, type: "function",
      function: { name, arguments: JSON.stringify(args) } });
    const latestStart = () => {
      const call = body.messages.flatMap(message => message.tool_calls ?? [])
        .filter(item => item.function.name === "start_operation").at(-1);
      return JSON.parse(body.messages.find(message => message.role === "tool" && message.tool_call_id === call.id).content);
    };
    let calls = [];
    if (requests === 2) calls = [toolCall("list_custom_field_types",{}),toolCall("get_resolutions",{})];
    if (step === 1) calls = [toolCall("create_screen",{dryRun:true,...scenario.operations[0].match})];
    if (step === 3) calls = [toolCall("start_operation",{toolName:"create_screen",arguments:{dryRun:false,planId:"screen-plan-2"}})];
    if (step === 4 || step === 8) calls = [toolCall("get_operation",{operationId:latestStart().operationId})];
    if (step === 5) calls = [toolCall("configure_workflow", { dryRun: true, create: scenario.operations[1].match })];
    if (step === 7) calls = [toolCall("start_operation",{toolName:"configure_workflow",arguments:{dryRun:false,planId:"workflow-plan-2"}})];
    const content = step === 2 ? "Preview: новый экран и поле «Срок исполнения», тип «Дата», глобально. Подтверждаете создание?"
      : step === 6 ? "Preview: workflow Исполнение EV2, экран 31002 на переходе Закрыть. Подтверждаете создание?"
      : requests === 1 ? "Подготовлю предварительный просмотр создания экрана и workflow."
      : "Workflow и экран с полем Срок исполнения типа Дата созданы.";
    response.writeHead(200,{"content-type":"application/json"});
    const payload = JSON.parse(openAi(calls.length ? {role:"assistant",content:null,tool_calls:calls} : {role:"assistant",content}));
    payload.usage = {total_tokens:1000,prompt_tokens_details:{cached_tokens:0}};
    response.end(JSON.stringify(payload));
  });
  try {
    const result = await runEval(instance,{EVAL_SCENARIO:"workflow_obvious_screen",EVAL_SEED:"2",
      EVAL_TOKEN_LIMIT_MODE:"soft",EVAL_MAX_TOKENS_PER_RUN:"300000"});
    assert.equal(result.code,0,JSON.stringify(result.verdict));
    assert.equal(result.verdict.passed,true);
    assert.equal(result.verdict.observations.unnecessary_question,0);
    assert.equal(result.verdict.checks.managed_request_finished,true);
    const created = result.trace.filter(call => call.tool === "get_operation").map(call => call.result.result);
    assert.equal(created[0].fields[0].typeKey,"datepicker");
    assert.equal(created[1].transitions[1].screen,String(created[0].screenId));
    assert.equal(requests,11);
    assert.equal(result.verdict.diagnostics.tokenBudget.maxAssistantCalls,12);
    assert.equal(result.verdict.diagnostics.tokenBudget.totalTokens,11000);
  } finally {
    instance.closeAllConnections();
    await new Promise(resolve => instance.close(resolve));
  }
});
