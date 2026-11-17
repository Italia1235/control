import { spawn } from "node:child_process";
import { appendFile, copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { gradeAssignWorkflow, gradeProjectDocument, operationCompletion, renderSummary } from "./grade.mjs";
import { domainSucceeded } from "./domain-completion.mjs";
import { createToolBackend } from "./tool-backend.mjs";
import { tokenLimitConfiguration, initializeTokenBudget, recordTokenUsage } from "./token-budget.mjs";
import { generateWorkflowNewScreenScenario, nextWorkflowNewScreenUser, gradeWorkflowNewScreen } from "./workflow-new-screen.mjs";
import { generateWorkflowResolutionScenario, nextWorkflowResolutionUser } from "./workflow-resolution.mjs";
import { generateWorkflowResolutionExtraScenario, nextWorkflowResolutionExtraUser, gradeWorkflowResolutionExtra, gradeWorkflowResolutionWithTrace } from "./workflow-resolution-extra.mjs";
import { createResolutionCatalog, normalizeWorkflowResolution, workflowResolutionPreview } from "./resolution-mock.mjs";
import { generateCustomfieldsPerFieldScreensScenario, nextCustomfieldsPerFieldScreensUser, gradeCustomfieldsPerFieldScreens } from "./customfields-per-field-screens.mjs";
import { generateCustomfieldsScreenPaginationScenario, nextCustomfieldsScreenPaginationUser, gradeCustomfieldsScreenPagination } from "./customfields-screen-pagination.mjs";
import { generateCustomfieldsGlobalScreensScenario, nextCustomfieldsGlobalScreensUser, gradeCustomfieldsGlobalScreens } from "./customfields-global-screens.mjs";
import { generateIssueTypesPartialResultScenario, nextIssueTypesPartialResultUser, gradeIssueTypesPartialResult } from "./issuetypes-partial-result.mjs";
import { generateLabTwoScreensScenario, nextLabTwoScreensUser, gradeLabTwoScreens } from "./customfields-lab-two-screens.mjs";
import { readNoCountsVariants, generateWorkflowReadNoCountsScenario, nextWorkflowReadNoCountsUser, gradeWorkflowReadNoCounts } from "./workflow-read-no-counts.mjs";
import { generateWorkflowDraftPublishScenario, nextWorkflowDraftPublishUser, gradeWorkflowDraftPublish } from "./workflow-draft-publish.mjs";
import { validatorScenarios, generateWorkflowValidatorsScenario, createValidatorsMock, nextWorkflowValidatorsUser, gradeWorkflowValidators } from "./workflow-validators.mjs";
import { PAID_RUNS, assertPaidConfiguration } from "./paid-run-configuration.mjs";
import { generateCreateScreenNamesScenario, createScreenNamesMock, nextCreateScreenNamesUser,
  gradeCreateScreenNames, SCREEN_DESCRIPTION_TOOL, readScreenToolDescription } from "./customfields-create-screen-names.mjs";
import { protocolVariants, generateDialogueProtocolScenario, nextDialogueProtocolUser,
  authorizeDialogueProtocol, gradeDialogueProtocol, assertProtocolOfflineConfiguration } from "./dialogue-protocol.mjs";
import { manualQaLoopScenarios, generateManualQaLoopsScenario, createManualQaLoopsMock,
  nextManualQaLoopsUser, gradeManualQaLoops, assertManualQaOfflineConfiguration } from "./workflow-manual-qa-loops.mjs";
import { transcriptDialogueScenarios, generateTranscriptDialogueScenario, createTranscriptDialogueMock,
  nextTranscriptDialogueUser, gradeTranscriptDialogue, assertTranscriptOfflineConfiguration,
  authorizeTranscriptDialogue } from "./workflow-transcript-dialogues.mjs";
import { screenLayoutScenarios, generateScreenLayoutScenario, createScreenLayoutMock,
  nextScreenLayoutUser, gradeScreenLayout } from "./screen-layout-dialogues.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "../..");
const TOOL_FILES = [
  "common-tools.json",
  "customfields-tools.json",
  "workflow-tools.json",
  "screens-tools.json",
  "projects-tools.json",
  "issuetypes-tools.json",
  "resolution-tools.json",
  "rollback-tools.json",
];

const DEFAULT_SCENARIO_TIMEOUT_MS = 120_000;
const DEFAULT_REQUEST_TIMEOUT_MS = 45_000;
const DEFAULT_MAX_TURNS = 12;
const DEFAULT_MAX_ASSISTANT_CALLS = 12;
const DEFAULT_MAX_SYNTHETIC_USER_CALLS = 12;
const DEFAULT_MAX_TOOL_CALLS = 24;
const MAX_RETRIES = 2;

class EvalTermination extends Error {
  constructor(reason) {
    super(reason);
    this.name = "EvalTermination";
  }
}

class InfrastructureFailure extends Error {}

class NormalizationError extends InfrastructureFailure {
  constructor(reason) {
    super(`NORMALIZATION_ERROR: ${reason}`);
    this.name = "NormalizationError";
  }
}

const INFRASTRUCTURE_CODES = new Set(["EVAL_APPLY_INVALID", "EVAL_SESSION_INVALID",
  "EVAL_CHANGE_SET_INVALID", "EVAL_CHANGE_SET_INCOMPLETE", "EVAL_NOT_OWNED",
  "EVAL_REQUEST_MISMATCH", "EVAL_UNEXPECTED_TOOL", "TOOL_ERROR"]);

function isInfrastructureResult(result) {
  return INFRASTRUCTURE_CODES.has(result?.errorCode);
}

function positiveInteger(name, fallback) {
  const raw = process.env[name];
  if (raw == null || raw === "") return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new EvalTermination(`${name} должен быть положительным целым числом, получено: ${raw}`);
  }
  return value;
}

function assertSupportedTokenBudget(provider, configuration=tokenLimitConfiguration()) {
  if (configuration.limit != null && configuration.mode === "hard") {
    throw new EvalTermination(
      `EVAL_MAX_TOKENS_PER_RUN=${configuration.limit}: hard token budget is not supported by the ${provider} transport; refusing LLM requests. A post-response usage check cannot enforce this ceiling.`,
    );
  }
  return configuration;
}

function elapsedLabel(startedAt) {
  const totalSeconds = Math.floor((Date.now() - startedAt) / 1000);
  return `${String(Math.floor(totalSeconds / 60)).padStart(2, "0")}:${String(totalSeconds % 60).padStart(2, "0")}`;
}

function progress(context, message) {
  console.log(`[${elapsedLabel(context.startedAt)}] ${context.diagnostics.tokenBudget ? `[${context.diagnostics.tokenBudget.notice}] ` : ""}${message}`);
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

async function jsonFile(relativePath) {
  return JSON.parse(await readFile(path.join(ROOT, relativePath), "utf8"));
}

async function loadToolCatalog() {
  const catalog = [];
  for (const file of TOOL_FILES) {
    catalog.push(...(await jsonFile(`src/main/resources/llm/${file}`)));
  }
  return catalog;
}

export function apiConfiguration() {
  const apiKey = process.env.EVAL_API_KEY || process.env.OPENAI_API_KEY;
  const assistantModel = process.env.EVAL_ASSISTANT_MODEL;
  const tokenLimit = assertSupportedTokenBudget(apiKey && assistantModel ? "api" : "omp");
  const common = {
    requestTimeoutMs: positiveInteger("EVAL_REQUEST_TIMEOUT_MS", DEFAULT_REQUEST_TIMEOUT_MS),
    tokenLimit,
  };
  if (!apiKey || !assistantModel) {
    return {
      ...common,
      provider: "omp",
      ompModel: process.env.EVAL_OMP_MODEL || "slow",
      ompUserModel: process.env.EVAL_USER_MODEL || null,
      assistantModel: "omp",
      userModel: "omp",
      judgeModel: "omp",
    };
  }
  return {
    ...common,
    provider: "api",
    apiKey,
    baseUrl: (process.env.EVAL_BASE_URL || process.env.OPENAI_BASE_URL || "https://api.openai.com/v1").replace(/\/$/, ""),
    assistantModel,
    userModel: process.env.EVAL_USER_MODEL || assistantModel,
    judgeModel: process.env.EVAL_JUDGE_MODEL || assistantModel,
  };
}

function run(command, args, signal, env = process.env) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: ROOT,
      env,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const finish = (callback, value) => {
      if (settled) return;
      settled = true;
      signal.removeEventListener("abort", abort);
      callback(value);
    };
    const abort = () => {
      child.kill("SIGKILL");
      const error = signal.reason instanceof Error ? signal.reason : new Error("request aborted");
      error.ompOutput = { stdout, stderr, exitCode: null };
      finish(reject, error);
    };
    signal.addEventListener("abort", abort, { once: true });
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("error", (error) => finish(reject, error));
    child.on("close", (code) => {
      if (code === 0) finish(resolve, { stdout, stderr, exitCode: code });
      else {
        const error = new Error(`omp завершился с кодом ${code}`);
        error.ompOutput = { stdout, stderr, exitCode: code };
        finish(reject, error);
      }
    });
  });
}

export function parseOmpMessage(text) {
  const unfenced = text.trim().replace(/^```(?:json)?\s*/iu, "").replace(/\s*```$/u, "").trim();
  // Only a standalone JSON document is a control envelope; prose examples stay prose.
  if (!unfenced.startsWith("{")) return { role: "assistant", content: text };
  let parsed;
  try {
    parsed = JSON.parse(unfenced);
  } catch {
    if (/"(?:content|tool_calls)"\s*:/u.test(unfenced)) {
      throw new NormalizationError("malformed control envelope");
    }
    return { role: "assistant", content: text };
  }
  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object" ||
      (!Object.hasOwn(parsed, "content") && !Object.hasOwn(parsed, "tool_calls"))) {
    return { role: "assistant", content: text };
  }
  if (Object.keys(parsed).some((key) => !["role", "content", "tool_calls"].includes(key)) ||
      (parsed.role != null && parsed.role !== "assistant") ||
      (parsed.content != null && typeof parsed.content !== "string") ||
      (Object.hasOwn(parsed, "tool_calls") && !Array.isArray(parsed.tool_calls))) {
    throw new NormalizationError("invalid control envelope schema");
  }
  if (typeof parsed.content === "string" && !parsed.tool_calls?.length) {
    const nested = parseOmpMessage(parsed.content);
    if (nested.tool_calls?.length || nested.content !== parsed.content) return nested;
  }
  const ids = new Set();
  const toolCalls = (parsed.tool_calls ?? []).map((call, index) => {
    if (!call || typeof call !== "object" || Array.isArray(call) ||
        Object.keys(call).some((key) => !["id", "type", "function"].includes(key)) ||
        (call.type != null && call.type !== "function") ||
        (call.id != null && (typeof call.id !== "string" || !call.id || ids.has(call.id))) ||
        !call.function || typeof call.function !== "object" || Array.isArray(call.function) ||
        Object.keys(call.function).some((key) => !["name", "arguments"].includes(key)) ||
        typeof call.function.name !== "string" || !call.function.name.trim()) {
      throw new NormalizationError("invalid tool call schema");
    }
    if (call.id) ids.add(call.id);
    let args = call.function.arguments;
    if (typeof args === "string") {
      try { args = JSON.parse(args); }
      catch { throw new NormalizationError("malformed tool arguments"); }
    }
    if (!args || typeof args !== "object" || Array.isArray(args)) {
      throw new NormalizationError("tool arguments must be an object");
    }
    return {
      id: call.id ?? `omp-${randomUUID()}-${index}`,
      type: "function",
      function: { name: call.function.name, arguments: JSON.stringify(args) },
    };
  });
  return { role: "assistant", content: parsed.content ?? null,
    ...(toolCalls.length ? { tool_calls: toolCalls } : {}) };
}

function parseOmpJsonStream(output) {
  let assistant;
  for (const line of output.split(/\r?\n/u)) {
    if (!line.trim()) continue;
    let event;
    try {
      event = JSON.parse(line);
    } catch {
      continue;
    }
    if (event.type === "message_end" && event.message?.role === "assistant") {
      assistant = event.message;
    }
  }
  if (!assistant) throw new NormalizationError("OMP stream has no completed assistant message");
  if (!Array.isArray(assistant.content) ||
      assistant.content.some((item) => !item || typeof item.type !== "string" ||
        (item.type === "text" && typeof item.text !== "string") ||
        item.type === "toolCall")) {
    throw new NormalizationError("invalid OMP assistant content");
  }
  const text = (assistant.content ?? [])
    .filter((item) => item.type === "text")
    .map((item) => item.text)
    .join("\n");
  return {
    text,
    usage: assistant.usage ?? null,
    model: {
      selector: null,
      provider: assistant.provider ?? null,
      model: assistant.model ?? null,
    },
  };
}

async function isolatedOmpAgentDirectory() {
  const source = process.env.PI_CODING_AGENT_DIR || path.join(homedir(), ".omp", "agent");
  const target = await mkdtemp(path.join(tmpdir(), "jira-control-eval-omp-"));
  const files = [
    "agent.db", "agent.db-wal", "agent.db-shm",
    "models.db", "models.db-wal", "models.db-shm",
    "config.yml",
  ];
  for (const file of files) {
    try {
      await copyFile(path.join(source, file), path.join(target, file));
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
  }
  return target;
}

function redactOmpOutput(text, config) {
  const secrets = Object.entries(process.env)
    .filter(([key, value]) => /(?:password|passwd|secret|api_?key|authorization|cookie|credential|(?:^|_)token$)/iu.test(key) && value)
    .map(([, value]) => value);
  if (config.apiKey) secrets.push(config.apiKey);
  if (process.env.JIRA_ADMIN_PASSWORD) {
    secrets.push(Buffer.from(`${process.env.JIRA_ADMIN_USER || "admin"}:${process.env.JIRA_ADMIN_PASSWORD}`).toString("base64"));
  }
  const variants = [...new Set(secrets.flatMap((secret) => [
    secret, JSON.stringify(secret).slice(1, -1), encodeURIComponent(secret),
    Buffer.from(secret).toString("base64"),
  ]))].sort((a, b) => b.length - a.length);
  let result = text;
  for (const secret of variants) result = result.split(secret).join("[REDACTED]");
  const mask = (value) => {
    // Decode only JSON string tokens for redaction, never parse/repair a command.
    value = value.replace(/"(?:\\.|[^"\\])*"/gu, (token) => {
      let decoded;
      try { decoded = JSON.parse(token); }
      catch { return token; }
      const redacted = mask(decoded);
      return decoded === redacted ? token : JSON.stringify(redacted);
    });
    return value
      .replace(/\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+/giu, "$1 [REDACTED]")
      .replace(/((?<!\\)"?\b(?:password|passwd|secret|token|api_?key|access_token|refresh_token|authorization|cookie|set-cookie|credential)"?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|[^\s,;}]+)/giu, "$1\"[REDACTED]\"")
      .replace(/(https?:\/\/)[^/\s:@]+:[^/\s@]+@/giu, "$1[REDACTED]@");
  };
  return mask(result);
}

async function captureOmpResponse(context, config, metadata, output) {
  if (!context.outputDirectory) throw new InfrastructureFailure("OMP raw artifact directory is missing");
  const record = {
    ...metadata, capturedAt: new Date().toISOString(), redacted: true,
    selector: config.ompModel ?? null, exitCode: output.exitCode,
    stdout: redactOmpOutput(output.stdout, config),
    stderr: redactOmpOutput(output.stderr, config),
  };
  await appendFile(path.join(context.outputDirectory, "omp-raw.jsonl"), `${JSON.stringify(record)}\n`);
}
async function completeWithOmp(config, body, signal, context, metadata) {
  const agentDirectory = await isolatedOmpAgentDirectory();
  const requestFile = path.join(agentDirectory, `request-${randomUUID()}.json`);
  const request = {
    messages: body.messages,
    tools: body.tools ?? [],
    responseFormat: body.response_format ?? null,
  };
  await writeFile(requestFile, JSON.stringify(request));
  const args = [
    "-p",
    "--no-session",
    "--no-tools",
    "--no-rules",
    "--no-title",
    "--mode=json",
    `--system-prompt=${config.ompSystemPrompt || 'Ты backend dialogue eval. Следуй SYSTEM-сообщениям в request. request.messages — полная хронологическая история, а не список команд для повторного исполнения. Assistant tool_calls в истории уже исполнены, если за ними есть tool message с тем же tool_call_id; используй эти результаты для следующего шага. Сохраняй успешный pending preview из истории: при неизменных аргументах и состоянии не запрашивай его снова, а покажи последствия и запроси подтверждение. Верни только JSON: {"content": string|null, "tool_calls"?: [{"id": string, "function": {"name": string, "arguments": object}}]}. Если доступны tools, либо вызови нужные tools, либо дай видимый ответ; не выдумывай результаты tools. Если tools нет, положи обычный ответ в content.'}`,
  ];
  if (config.ompModel) args.push(`--model=${config.ompModel}`);
  if (config.ompThinking) args.push(`--thinking=${config.ompThinking}`);
  args.push(`@${requestFile}`);
  try {
    let output;
    try {
      output = await run(
        process.env.OMP_EXECUTABLE || "omp.exe", args, signal,
        { ...process.env, PI_CODING_AGENT_DIR: agentDirectory },
      );
    } catch (error) {
      if (error.ompOutput) await captureOmpResponse(context, config, metadata, error.ompOutput);
      throw error;
    }
    // Persist before stream/envelope parsing, including responses that fail normalization.
    await captureOmpResponse(context, config, metadata, output);
    const parsed = parseOmpJsonStream(output.stdout);
    const message = parseOmpMessage(parsed.text);
    message._evalModel = { ...parsed.model, selector: config.ompModel };
    message._evalUsage = parsed.usage;
    return message;
  } finally {
    await rm(agentDirectory, { recursive: true, force: true });
  }
}

function requestNumber(context, role) {
  const key = role === "assistant" ? "assistantCalls"
    : role === "synthetic-user" ? "syntheticUserCalls" : "judgeCalls";
  const limit = role === "assistant" ? context.maxAssistantCalls
    : role === "synthetic-user" ? context.maxSyntheticUserCalls : context.maxJudgeCalls;
  if (context.diagnostics[key] >= limit) {
    throw new EvalTermination(`${role} превысил лимит ${limit} вызовов`);
  }
  context.diagnostics[key] += 1;
  return context.diagnostics[key];
}

function requestSignal(context, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(
    () => controller.abort(new EvalTermination(`request timeout ${timeoutMs} ms`)),
    timeoutMs,
  );
  return {
    controller,
    signal: AbortSignal.any([context.scenarioController.signal, controller.signal]),
    cancel: () => clearTimeout(timer),
  };
}

async function delay(ms, signal) {
  await new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => {
      clearTimeout(timer);
      reject(signal.reason);
    }, { once: true });
  });
}

export async function complete(config, body, role, context) {
  if (config.paidRun) assertPaidConfiguration(config, config.paidScenario);
  const tokenLimit = assertSupportedTokenBudget(config.provider, config.tokenLimit);
  initializeTokenBudget(context, tokenLimit);
  if (context.diagnostics.tokenBudget?.reached) throw new EvalTermination("TOKEN_BUDGET_SOFT");
  const number = requestNumber(context, role);
  progress(context, `${role} request #${number}`);
  let lastError;
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt += 1) {
    const startedAt = Date.now();
    const remainingMs = context.deadline - startedAt;
    if (remainingMs <= 0 || context.scenarioController.signal.aborted) {
      throw new EvalTermination(`scenario timeout ${context.scenarioTimeoutMs} ms во время ${role} request #${number}`);
    }
    const timeoutMs = Math.min(config.requestTimeoutMs, remainingMs);
    const request = requestSignal(context, timeoutMs);
    const signal = request.signal;
    let httpStatus = null;
    try {
      let message;
      if (config.provider === "omp") {
        message = await completeWithOmp(
          role === "synthetic-user" && config.ompUserModel
            ? { ...config, ompModel: config.ompUserModel } : config,
          body, signal, context, { role, request: number, attempt: attempt + 1 },
        );
      } else {
        const response = await fetch(`${config.baseUrl}/chat/completions`, {
          method: "POST",
          headers: {
            authorization: `Bearer ${config.apiKey}`,
            "content-type": "application/json",
          },
          body: JSON.stringify(body),
          signal,
        });
        httpStatus = response.status;
        const payload = await response.json().catch(() => null);
        if (!response.ok) {
          const error = new Error(`LLM API ${response.status}: ${JSON.stringify(payload)}`);
          error.retryable = response.status === 429 || response.status >= 500;
          throw error;
        }
        message = payload?.choices?.[0]?.message;
        if (message) message._evalUsage = payload.usage ?? null;
        if (!message) throw new Error(`LLM API не вернул message: ${JSON.stringify(payload)}`);
      }
      const model = message._evalModel ?? {
        selector: body.model,
        provider: config.provider,
        model: body.model,
      };
      delete message._evalModel;
      const usage = message._evalUsage ?? null;
      delete message._evalUsage;
      if (!context.diagnostics.models.some(
        (item) => item.selector === model.selector && item.provider === model.provider && item.model === model.model,
      )) {
        context.diagnostics.models.push(model);
        progress(context, `model: ${model.selector} -> ${model.provider}/${model.model}`);
      }
      const elapsedMs = Date.now() - startedAt;
      context.diagnostics.requestEvents.push({
        role, request: number, attempt: attempt + 1, elapsedMs, httpStatus, outcome: "ok", usage,
      });
      recordTokenUsage(context, role, usage);
      if (context.diagnostics.tokenBudget?.enforcement === "request-count-only") {
        progress(context, `Usage missing for a response; token total is unknown. Using request-count limit only: assistant <= ${context.maxAssistantCalls}.`);
      }
      progress(context, `${role} response #${number}; elapsed=${elapsedMs}ms; HTTP=${httpStatus ?? "n/a"}; attempt=${attempt + 1}`);
      request.cancel();
      return message;
    } catch (error) {
      request.cancel();
      const elapsedMs = Date.now() - startedAt;
      const scenarioTimedOut = context.scenarioController.signal.aborted || Date.now() >= context.deadline;
      const requestTimedOut = request.controller.signal.aborted && !scenarioTimedOut;
      const outcome = error instanceof NormalizationError ? "normalization-error"
        : scenarioTimedOut ? "scenario-timeout" : requestTimedOut ? "request-timeout" : "error";
      context.diagnostics.requestEvents.push({
        role, request: number, attempt: attempt + 1, elapsedMs, httpStatus, outcome,
      });
      progress(context, `${role} request #${number}; elapsed=${elapsedMs}ms; HTTP=${httpStatus ?? "n/a"}; attempt=${attempt + 1}; ${outcome}`);
      if (error instanceof NormalizationError) throw error;
      if (scenarioTimedOut) {
        throw new EvalTermination(`scenario timeout ${context.scenarioTimeoutMs} ms во время ${role} request #${number}`);
      }
      lastError = requestTimedOut
        ? new EvalTermination(`${role} request #${number} timeout после ${timeoutMs} ms`)
        : error;
      const retryable = requestTimedOut || error.retryable === true || error.name === "TypeError";
      if (!retryable || attempt === MAX_RETRIES) throw lastError;
      await delay(250 * (attempt + 1), context.scenarioController.signal);
    }
  }
  throw lastError;
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function statusOf(result) {
  return result && typeof result === "object" ? result.status ?? null : null;
}

function assignedReadback(scenario) {
  const result = clone(scenario.getProjectWorkflows);
  for (const issueType of result.issueTypes) issueType.workflow = scenario.preview.workflow;
  result.defaultWorkflow = scenario.preview.workflow;
  return result;
}

function workflowRead(source, args) {
  const result = clone(source);
  if (!result.issueTypes) return result;
  result.issueCountsStatus = args?.includeIssueCounts === true ? "COMPLETE" : "NOT_REQUESTED";
  result.issueCountsError = null;
  if (args?.includeIssueCounts !== true) {
    for (const type of result.issueTypes) {
      type.issues = null;
      type.statuses = null;
    }
  }
  return result;
}

function simulator(scenario) {
  let assigned = false;
  return function execute(tool, args) {
    if (tool === "list_projects") {
      return {
        total: 1,
        projects: [{
          key: scenario.getProjectWorkflows.project.key,
          name: scenario.getProjectWorkflows.project.name,
          issueTypes: scenario.getProjectWorkflows.issueTypes.map((item) => item.issueType.name),
        }],
      };
    }
    if (tool === "get_project_workflows") {
      if (args?.project !== "ITX") {
        return { status: "FAILED", errorCode: "UNKNOWN_PROJECT", message: "Unknown project.", suggestions: ["ITX"] };
      }
      return workflowRead(assigned ? assignedReadback(scenario) : scenario.getProjectWorkflows, args);
    }
    if (tool !== "assign_workflow") {
      return {
        status: "FAILED",
        errorCode: "EVAL_UNEXPECTED_TOOL",
        message: `The scenario does not provide ${tool}; do not substitute another operation.`,
      };
    }
    if (args?.dryRun === true) {
      if (
        args.project !== "ITX" ||
        args.workflow !== scenario.preview.workflow ||
        args.allIssueTypes !== true ||
        "issueTypes" in args
      ) {
        return {
          dryRun: true,
          status: "FAILED",
          errorCode: "VALIDATION_ERROR",
          message: "The preview request does not match the user's requested whole-project assignment.",
          suggestions: [],
        };
      }
      return clone(scenario.preview);
    }
    if (args?.dryRun === false) {
      const keys = Object.keys(args).sort();
      if (
        keys.length !== 2 ||
        keys[0] !== "dryRun" ||
        keys[1] !== "planId" ||
        args.planId !== scenario.planId
      ) {
        return {
          dryRun: false,
          status: "FAILED",
          errorCode: "PREVIEW_STALE",
          message: "Apply must contain only dryRun=false and the preview planId.",
          suggestions: [],
        };
      }
      if (assigned) {
        return {
          dryRun: false,
          status: "FAILED",
          errorCode: "PREVIEW_STALE",
          message: "The planId was already consumed.",
          suggestions: [],
        };
      }
      assigned = scenario.apply.status === "ASSIGNED";
      return clone(scenario.apply);
    }
    return {
      status: "FAILED",
      errorCode: "VALIDATION_ERROR",
      message: "dryRun must be true or false.",
      suggestions: [],
    };
  };
}

function containsSubset(actual, expected) {
  if (Array.isArray(expected)) {
    return Array.isArray(actual) &&
      expected.length === actual.length &&
      expected.every((item, index) => containsSubset(actual[index], item));
  }
  if (expected && typeof expected === "object") {
    return actual && typeof actual === "object" &&
      Object.entries(expected).every(([key, value]) => containsSubset(actual[key], value));
  }
  return actual === expected;
}

function matchesOperation(argumentsValue, operation) {
  const requested = operation.tool === "configure_workflow" ? argumentsValue.create : argumentsValue;
  return containsSubset(requested, operation.match) &&
    (!operation.matchAny || operation.matchAny.some((candidate) => containsSubset(requested, candidate)));
}

function projectDocumentSimulator(scenario) {
  const previewed = new Map();
  const applied = new Set();
  const screenNames = scenario.validation === "customfields_create_screen_names" ? createScreenNamesMock(scenario) : null;
  const manualQaLoops = scenario.validation === "workflow_manual_qa_loops" ? createManualQaLoopsMock(scenario) : null;
  const screenLayout = scenario.validation === "screen_layout_dialogues" ? createScreenLayoutMock(scenario) : null;
  const transcriptDialogue = scenario.validation === "workflow_transcript_dialogues" ? createTranscriptDialogueMock(scenario) : null;
  const validatorsMock = scenario.validation === "workflow_validators" ? createValidatorsMock(scenario) : null;
  const catalogFixture = scenario.resolutionCatalog ?? scenario.resolutions ??
    scenario.readResults?.get_resolutions?.[0]?.result;
  let resolutionPlans = 0;
  const resolutions = catalogFixture ? createResolutionCatalog(catalogFixture, {
    startId: scenario.resolutionMockOptions?.startingId,
    ...(scenario.resolutionMockOptions?.planId ? { planId: () => {
      resolutionPlans += 1;
      return scenario.resolutionMockOptions.planId + (resolutionPlans === 1 ? "" : `-${resolutionPlans}`);
    } } : {}),
  }) : null;
  return function execute(tool, args) {
    if (validatorsMock) return validatorsMock(tool, args);
    if (screenLayout) {
      const layoutResult = screenLayout(tool, args);
      if (layoutResult !== undefined) return layoutResult;
    }
    if (transcriptDialogue) return transcriptDialogue(tool, args);
    if (screenNames && tool === "create_screen") return screenNames(args);
    if (manualQaLoops && tool === "configure_workflow") return manualQaLoops(args);
    if (resolutions && tool === "get_resolutions") return resolutions.execute(tool, args);
    if (resolutions && tool === "create_resolution") {
      if (args?.dryRun !== false) return resolutions.execute(tool, args);
      const result = resolutions.apply(args);
      const operation = scenario.operations.find(candidate =>
        candidate.tool === tool && candidate.preview.planId === args.planId);
      if (result.status === "CREATED" && operation) applied.add(operation.id);
      return result;
    }
    if (resolutions && tool === "configure_workflow" && args?.dryRun === true) {
      const normalized = normalizeWorkflowResolution(args.create, resolutions);
      if (normalized.status === "FAILED") return normalized;
    }
    const readFixtures = scenario.readResults?.[tool];
    if (readFixtures) {
      const fixtures = Array.isArray(readFixtures) ? readFixtures : [{ result: readFixtures }];
      const fixture = fixtures.find((candidate) => containsSubset(args, candidate.match ?? {}) &&
        Object.hasOwn(args, "cursor") === Object.hasOwn(candidate.match ?? {}, "cursor") &&
        args.cursor === candidate.match?.cursor &&
        (!candidate.missingFieldType || args.fields?.some(field => !field.typeKey?.trim())));
      if (fixture) return tool === "get_project_workflows"
        ? workflowRead(fixture.result, args) : clone(fixture.result);
    }

    if (args?.dryRun === true) {
      const operation = scenario.operations.find(
        (candidate) => candidate.tool === tool && matchesOperation(args, candidate),
      );
      if (!operation) {
        return {
          status: "FAILED",
          errorCode: "EVAL_REQUEST_MISMATCH",
          message: `No requested ${tool} operation matches these arguments.`,
          suggestions: [],
        };
      }
      const missingDependency = (operation.requiresApplied ?? []).find((id) => !applied.has(id));
      if (missingDependency) {
        return {
          status: "FAILED",
          errorCode: "EVAL_DEPENDENCY_NOT_APPLIED",
          message: `${operation.id} requires successful ${missingDependency}.`,
          suggestions: [],
        };
      }
      previewed.set(operation.preview.planId, operation);
      const result = clone(operation.preview);
      if (resolutions && tool === "configure_workflow") {
        result.transitions = workflowResolutionPreview(args.create, resolutions).transitions;
      }
      return result;
    }

    if (args?.dryRun === false) {
      if (Object.keys(args).sort().join(",") !== "dryRun,planId") {
        return {
          status: "FAILED",
          errorCode: "PREVIEW_STALE",
          message: "Apply must contain only dryRun=false and planId.",
          suggestions: [],
        };
      }
      const operation = previewed.get(args.planId);
      if (!operation || operation.tool !== tool || applied.has(operation.id)) {
        return {
          status: "FAILED",
          errorCode: "PREVIEW_STALE",
          message: "The plan is missing or already consumed.",
          suggestions: [],
        };
      }
      if (domainSucceeded(operation.tool, operation.apply, operation.apply.status, operation.match)) {
        applied.add(operation.id);
      }
      return clone(operation.apply);
    }

    return {
      status: "FAILED",
      errorCode: "EVAL_UNEXPECTED_TOOL",
      message: `The scenario does not provide ${tool} for these arguments.`,
      suggestions: [],
    };
  };
}

export function scenarioSimulator(scenario) {
  const domainExecute = scenario.kind === "project_document" ? projectDocumentSimulator(scenario) : simulator(scenario);
  const sessionId = "eval-session-001";
  const changeSetId = "eval-change-set-001";
  const previews = new Map();
  const operations = new Map();
  const pendingPolls = new Map();
  const observedPlans = new Set();
  let session = null;
  let changeSet = null;
  const fail = (errorCode, message) => ({ status: "FAILED", errorCode, message });
  const exactKeys = (args, keys) => canonical(Object.keys(args ?? {}).sort()) === canonical([...keys].sort());
  return function execute(tool, args, { conversation = [], userTurn = 1, assistantTurn = 1 } = {}) {
    if (tool === "create_change_session") {
      if (!exactKeys(args, []) || session) return fail("EVAL_SESSION_INVALID", "Create one session per conversation.");
      session = { sessionId, status: "OPEN" };
      return { sessionId };
    }
    if (tool === "list_change_sessions") return { sessions: session ? [clone(session)] : [] };
    if (tool === "begin_change_set") {
      if (!exactKeys(args, ["sessionId"]) || !session || args.sessionId !== sessionId || changeSet) {
        return fail("EVAL_CHANGE_SET_INVALID", "Begin one change set in this conversation's owned session.");
      }
      changeSet = { sessionId, changeSetId, status: "OPEN" };
      return { changeSetId };
    }
    if (tool === "list_session_changes") {
      if (!session || args?.sessionId !== sessionId) return fail("EVAL_NOT_OWNED", "Unknown owned session.");
      return { sessionId, changeSets: changeSet ? [clone(changeSet)] : [], operations: [...operations.values()].map(clone) };
    }
    if (tool === "finish_change_set") {
      if (!exactKeys(args, ["changeSetId"]) || !changeSet || args.changeSetId !== changeSetId) {
        return fail("EVAL_NOT_OWNED", "Unknown owned change set.");
      }
      if ([...operations.values()].some((operation) => operation.status !== "COMPLETED")) {
        return fail("EVAL_CHANGE_SET_INCOMPLETE", "Wait for all operations to become terminal before finishing.");
      }
      changeSet.status = "FINISHED";
      return clone(changeSet);
    }
    if (tool === "start_operation") {
      const suppliedOwnership = Object.hasOwn(args ?? {}, "sessionId") || Object.hasOwn(args ?? {}, "changeSetId");
      const keys = suppliedOwnership ? ["toolName", "arguments", "sessionId", "changeSetId"] : ["toolName", "arguments"];
      if (!exactKeys(args, keys) ||
          !exactKeys(args.arguments, ["dryRun", "planId"]) || args.arguments.dryRun !== false) {
        return fail("EVAL_APPLY_INVALID", "Start accepts toolName, dryRun=false/planId, and either both ownership ids or neither.");
      }
      if (!suppliedOwnership) {
        session ??= { sessionId, status: "OPEN" };
        changeSet ??= { sessionId, changeSetId, status: "OPEN" };
      }
      if (!session || !changeSet || (suppliedOwnership &&
          (args.sessionId !== sessionId || args.changeSetId !== changeSetId)) ||
          changeSet.status !== "OPEN") return fail("EVAL_NOT_OWNED", "Start requires the owned open session and change set.");
      const preview = previews.get(args.arguments.planId);
      if (!preview || preview.tool !== args.toolName) {
        return fail("PREVIEW_STALE", "The plan is missing or belongs to another tool.");
      }
      const existing = [...operations.values()].find(operation => operation.arguments.planId === args.arguments.planId &&
        operation.toolName === args.toolName);
      if (existing) return { operationId: existing.operationId, status: existing.status, alreadyStarted: true };
      const shown = conversation.some((message) => message.role === "assistant" && message.turn === preview.assistantTurn);
      const confirmed = conversation.some((message) => message.role === "user" &&
        message.turn > preview.userTurn && message.turn <= userTurn &&
        /^(?:да|yes)(?:[,.!\s]|$)/iu.test(message.content.trim()));
      const authorized = scenario.validation === "workflow_transcript_dialogues"
        ? authorizeTranscriptDialogue({ scenario, conversation, preview, userTurn })
        : scenario.validation === "dialogue_protocol"
        ? authorizeDialogueProtocol({ scenario, conversation, preview, previews, observedPlans, operations })
        : shown && confirmed;
      if (!authorized) return fail("EVAL_CONFIRMATION_REQUIRED", "Show preview consequences and obtain explicit human confirmation.");
      const result = domainExecute(args.toolName, args.arguments);
      const operationId = `eval-operation-${String(operations.size + 1).padStart(3, "0")}`;
      const fixture = scenario.operations?.find(candidate => candidate.preview.planId === args.arguments.planId);
      const completion = fixture?.completion;
      operations.set(operationId, { operationId, sessionId, changeSetId, toolName: args.toolName,
        arguments: clone(args.arguments), ...(completion ? clone(completion) : { status: "COMPLETED", result: clone(result) }) });
      pendingPolls.set(operationId, clone(fixture?.polls ?? []));
      if (fixture?.loseStartResponse) {
        const error = new Error("The start_operation response was lost after the operation was persisted.");
        error.code = "START_RESPONSE_LOST";
        throw error;
      }
      return { operationId, status: "QUEUED" };
    }
    if (tool === "get_operation") {
      const operation = operations.get(args?.operationId);
      if (!exactKeys(args, Object.hasOwn(args ?? {}, "waitSeconds") ? ["operationId", "waitSeconds"] : ["operationId"]) ||
          !operation) return fail("EVAL_NOT_OWNED", "Unknown owned operation.");
      if (Object.hasOwn(args, "waitSeconds") &&
          (!Number.isInteger(args.waitSeconds) || args.waitSeconds < 0 || args.waitSeconds > 20)) {
        return fail("EVAL_ARGUMENT_INVALID", "waitSeconds must be an integer from 0 to 20.");
      }
      const pending = pendingPolls.get(operation.operationId);
      if (pending?.length) return { ...clone(operation), ...pending.shift(), result: undefined };
      const expected = scenario.kind === "project_document"
        ? scenario.operations.find((candidate) => candidate.preview.planId === operation.arguments.planId)
        : { tool: "assign_workflow", apply: scenario.apply };
      if (domainSucceeded(expected?.tool, operation.result, expected?.apply.status, expected?.match)) {
        observedPlans.add(operation.arguments.planId);
      }
      return clone(operation);
    }
    if (args?.dryRun === false) return fail("EVAL_UNMANAGED_APPLY", "Confirmed applies must use start_operation.");
    if (args?.dryRun === true) {
      const operation = scenario.operations?.find((candidate) => candidate.tool === tool && matchesOperation(args, candidate));
      const missing = operation?.requiresApplied?.find((id) => {
        const dependency = scenario.operations.find((candidate) => candidate.id === id);
        return !observedPlans.has(dependency.preview.planId);
      });
      if (missing) return operation.beforeDependency ? clone(operation.beforeDependency)
        : fail("EVAL_DEPENDENCY_NOT_APPLIED", `Observe successful ${missing} before the next preview.`);
    }
    const result = domainExecute(tool, args);
    if (args?.dryRun === true && result.planId && result.status !== "FAILED") {
      previews.set(result.planId, { tool, userTurn, assistantTurn,
        ...(["dialogue_protocol", "workflow_transcript_dialogues"].includes(scenario.validation) ? { result: clone(result) } : {}) });
    }
    return result;
  };
}

function assistantTools(catalog) {
  return catalog.map((tool) => ({
    type: "function",
    function: {
      name: tool.name,
      description: tool.description,
      parameters: tool.input_schema,
    },
  }));
}

function transcriptForUser(conversation) {
  return conversation
    .map((message) => `${message.role === "user" ? "Пользователь" : "Ассистент"}: ${message.content}`)
    .join("\n\n");
}

async function nextSyntheticUser(config, userPrompt, scenario, conversation, trace, context) {
  if (scenario.validation === "dialogue_protocol") return nextDialogueProtocolUser(scenario, conversation);
  if (scenario.validation === "workflow_manual_qa_loops") return nextManualQaLoopsUser(scenario, conversation);
  if (scenario.validation === "workflow_transcript_dialogues") return nextTranscriptDialogueUser(scenario, conversation);
  if (scenario.validation === "workflow_new_screen") return nextWorkflowNewScreenUser(scenario, conversation, trace);
  if (scenario.validation === "workflow_resolution") return nextWorkflowResolutionUser(scenario, conversation, trace);
  if (scenario.validation === "workflow_resolution_extra") return nextWorkflowResolutionExtraUser(scenario, conversation, trace);
  if (scenario.validation === "customfields_per_field_screens") return nextCustomfieldsPerFieldScreensUser(scenario, conversation, trace);
  if (scenario.validation === "screen_layout_dialogues") return nextScreenLayoutUser(scenario, conversation);
  if (scenario.validation === "customfields_lab_two_screens") return nextLabTwoScreensUser(scenario, conversation);
  if (scenario.validation === "customfields_create_screen_names") return nextCreateScreenNamesUser(scenario, conversation);
  if (scenario.validation === "customfields_screen_pagination") return nextCustomfieldsScreenPaginationUser();
  if (scenario.validation === "customfields_global_screens") return nextCustomfieldsGlobalScreensUser(scenario, conversation);
  if (scenario.validation === "workflow_read_no_counts") return nextWorkflowReadNoCountsUser(scenario, conversation, trace);
  if (scenario.validation === "workflow_draft_publish") return nextWorkflowDraftPublishUser(scenario, conversation, trace);
  if (scenario.validation === "workflow_validators") return nextWorkflowValidatorsUser(scenario, conversation, trace);
  if (scenario.validation === "issuetypes_partial_result") return nextIssueTypesPartialResultUser(scenario, conversation, trace);
  if (scenario.syntheticUserMode === "deterministic") {
    const userMessages = conversation.filter((message) => message.role === "user");
    if (userMessages.length === 1) return scenario.syntheticUserReplies.intake;
    const lastAssistantTurn = [...conversation].reverse().find((message) => message.role === "assistant")?.turn;
    const hasPreview = trace.some(
      (call) => call.assistantTurn === lastAssistantTurn && call.arguments?.dryRun === true && call.result?.planId,
    );
    return hasPreview ? scenario.syntheticUserReplies.confirm : scenario.syntheticUserReplies.continue;
  }
  const message = await complete(config, {
    model: config.userModel,
    messages: [
      { role: "system", content: `${userPrompt}\n\n${scenario.syntheticUserPrompt ?? ""}\n\nОтветь только следующей репликой пользователя, без пояснений и меток роли.` },
      { role: "user", content: transcriptForUser(conversation) },
    ],
  }, "synthetic-user", context);
  return (message.content ?? "").trim();
}

async function assessPreview(config, scenario, assistantMessage, context) {
  if (!assistantMessage) return { shown: false, missing: ["сообщение с preview"] };
  const message = await complete(config, {
    model: config.judgeModel,
    response_format: { type: "json_object" },
    messages: [
      {
        role: "system",
        content:
          "Ты проверяешь только полноту показа preview. Не требуй точных фраз. Верни JSON " +
          '{"shown":boolean,"missing":string[]}. shown=true, только если понятны проект, workflow, каждый issue type и его READY/MIGRATE/ALREADY (или однозначный перевод), а также warning.',
      },
      {
        role: "user",
        content: `Структурированный preview:\n${JSON.stringify(scenario.preview, null, 2)}\n\nСообщение ассистента:\n${assistantMessage}`,
      },
    ],
  }, "judge", context);
  try {
    const assessment = JSON.parse(message.content ?? "");
    return {
      shown: assessment.shown === true,
      missing: Array.isArray(assessment.missing) ? assessment.missing.map(String) : [],
    };
  } catch {
    return { shown: false, missing: ["judge не вернул валидный JSON"] };
  }
}

async function assessProjectDocumentIntake(config, scenario, conversation, context) {
  const firstAssistant = conversation.find((message) => message.role === "assistant")?.content;
  if (!firstAssistant) return { shown: false, missing: scenario.missingDecisions };
  const message = await complete(config, {
    model: config.judgeModel,
    response_format: { type: "json_object" },
    messages: [
      {
        role: "system",
        content:
          "Проверь только первое уточнение ассистента по большому Jira-документу. Верни JSON " +
          '{"shown":boolean,"missing":string[]}. shown=true только если ассистент одним сообщением явно запросил ' +
          "каждое решение из списка; перефразирование допустимо. Не требуй вопросов о фактах, доступных через Jira tools.",
      },
      {
        role: "user",
        content:
          `Решения, которых не было в документе:\n${JSON.stringify(scenario.missingDecisions, null, 2)}` +
          `\n\nПервое сообщение ассистента:\n${firstAssistant}`,
      },
    ],
  }, "judge", context);
  try {
    const assessment = JSON.parse(message.content ?? "");
    return {
      shown: assessment.shown === true,
      missing: Array.isArray(assessment.missing) ? assessment.missing.map(String) : [],
    };
  } catch {
    return { shown: false, missing: ["judge не вернул валидный JSON"] };
  }
}

function markdownConversation(conversation) {
  return `${conversation
    .map((message) => `## ${message.role === "user" ? "User" : "Assistant"}\n\n${message.content}`)
    .join("\n\n")}\n`;
}

function timestamp() {
  return new Date().toISOString().replace(/[:.]/g, "-");
}

function detailedSummary(verdict) {
  const diagnostics = verdict.diagnostics;
  return `${verdict.status === "ERROR" ? "ERROR" : renderSummary(verdict).trimEnd()}\n` +
    `Причина завершения: ${diagnostics.terminationReason}.\n` +
    `Длительность: ${diagnostics.durationMs} ms.\n` +
    `Вызовы: assistant=${diagnostics.assistantCalls}, synthetic-user=${diagnostics.syntheticUserCalls}, ` +
    `judge=${diagnostics.judgeCalls}, tools=${diagnostics.toolCalls}.\n`;
}

async function saveArtifacts(outputDirectory, conversation, trace, verdict) {
  await mkdir(outputDirectory, { recursive: true });
  await Promise.all([
    writeFile(path.join(outputDirectory, "conversation.md"), markdownConversation(conversation)),
    writeFile(path.join(outputDirectory, "tool-trace.json"), `${JSON.stringify(trace, null, 2)}\n`),
    writeFile(path.join(outputDirectory, "verdict.json"), `${JSON.stringify(verdict, null, 2)}\n`),
    writeFile(path.join(outputDirectory, "summary.txt"), detailedSummary(verdict)),
  ]);
}

async function main() {
  const startedAt = Date.now();
  const scenarioController = new AbortController();
  const scenarioName = process.argv[2] || process.env.EVAL_SCENARIO || "assign_workflow_happy_path";
  if (!/^[a-z0-9_]+$/u.test(scenarioName)) throw new EvalTermination(`Недопустимое имя сценария: ${scenarioName}`);
  const context = {
    startedAt,
    scenarioTimeoutMs: DEFAULT_SCENARIO_TIMEOUT_MS,
    deadline: startedAt + DEFAULT_SCENARIO_TIMEOUT_MS,
    scenarioController,
    maxAssistantCalls: DEFAULT_MAX_ASSISTANT_CALLS,
    maxSyntheticUserCalls: DEFAULT_MAX_SYNTHETIC_USER_CALLS,
    maxJudgeCalls: 1,
    maxToolCalls: DEFAULT_MAX_TOOL_CALLS,
    diagnostics: {
      terminationReason: null,
      durationMs: 0,
      assistantCalls: 0,
      syntheticUserCalls: 0,
      judgeCalls: 0,
      toolCalls: 0,
      models: [],
      requestEvents: [],
    },
  };
  let timer;
  const outputDirectory = path.join(HERE, "runs", `${timestamp()}-${scenarioName}`);
  context.outputDirectory = outputDirectory;
  await mkdir(outputDirectory, { recursive: true });
  await writeFile(path.join(outputDirectory, "omp-raw.jsonl"), "");
  const conversation = [];
  const trace = [];
  const modelMessages = [];
  let writeTools = new Set();
  let verdict;
  let scenario;

  progress(context, "starting scenario");
  try {
    scenario = transcriptDialogueScenarios.includes(scenarioName)
      ? generateTranscriptDialogueScenario(scenarioName)
      : manualQaLoopScenarios.includes(scenarioName)
      ? generateManualQaLoopsScenario(scenarioName === "workflow_manual_qa_loops_original")
      : ["workflow_new_screen", "workflow_obvious_screen"].includes(scenarioName)
      ? generateWorkflowNewScreenScenario(Number(process.env.EVAL_SEED ?? 0),
          scenarioName === "workflow_obvious_screen" ? "obvious" : "ambiguous")
      : ["workflow_resolution_obvious", "workflow_resolution_ambiguous"].includes(scenarioName)
      ? generateWorkflowResolutionScenario(scenarioName === "workflow_resolution_ambiguous")
      : ["workflow_resolution_missing", "workflow_resolution_unresolved"].includes(scenarioName)
      ? generateWorkflowResolutionExtraScenario(scenarioName.slice("workflow_resolution_".length))
      : protocolVariants.some(variant => scenarioName === `dialogue_protocol_${variant}`)
      ? generateDialogueProtocolScenario(scenarioName.slice("dialogue_protocol_".length))
      : screenLayoutScenarios.includes(scenarioName)
      ? generateScreenLayoutScenario(scenarioName)
      : scenarioName === "customfields_lab_two_screens"
      ? generateLabTwoScreensScenario()
      : scenarioName === "customfields_create_screen_names"
      ? generateCreateScreenNamesScenario()
      : scenarioName === "customfields_per_field_screens"
      ? generateCustomfieldsPerFieldScreensScenario()
      : scenarioName === "customfields_screen_pagination"
      ? generateCustomfieldsScreenPaginationScenario()
      : scenarioName === "customfields_global_screens"
      ? generateCustomfieldsGlobalScreensScenario()
      : scenarioName === "issuetypes_partial_result"
      ? generateIssueTypesPartialResultScenario()
      : readNoCountsVariants.some(variant => scenarioName === `workflow_read_no_counts_${variant}`)
      ? generateWorkflowReadNoCountsScenario(scenarioName.slice("workflow_read_no_counts_".length))
      : validatorScenarios.includes(scenarioName)
      ? generateWorkflowValidatorsScenario(scenarioName)
      : scenarioName === "workflow_draft_publish"
      ? generateWorkflowDraftPublishScenario()
      : await jsonFile(`evals/offline/${scenarioName}.json`);
    context.softAssistantCallLimit = scenario.validation === "customfields_global_screens" ? 18
      : ["workflow_new_screen", "workflow_resolution_extra", "workflow_draft_publish"]
        .includes(scenario.validation) ? 12 : 10;
    const scenarioTimeoutMs = positiveInteger(
      "EVAL_SCENARIO_TIMEOUT_MS",
      scenario.limits?.scenarioTimeoutMs ?? DEFAULT_SCENARIO_TIMEOUT_MS,
    );
    context.scenarioTimeoutMs = scenarioTimeoutMs;
    context.deadline = startedAt + scenarioTimeoutMs;
    context.maxAssistantCalls = scenario.limits?.assistantCalls ?? DEFAULT_MAX_ASSISTANT_CALLS;
    context.maxSyntheticUserCalls = scenario.limits?.syntheticUserCalls ?? DEFAULT_MAX_SYNTHETIC_USER_CALLS;
    context.maxToolCalls = scenario.limits?.toolCalls ?? DEFAULT_MAX_TOOL_CALLS;
    timer = setTimeout(
      () => scenarioController.abort(new EvalTermination(`scenario timeout ${scenarioTimeoutMs} ms`)),
      scenarioTimeoutMs,
    );
    if (scenario.validation === "dialogue_protocol") assertProtocolOfflineConfiguration(process.env);
    if (scenario.validation === "workflow_manual_qa_loops") assertManualQaOfflineConfiguration(process.env);
    if (scenario.validation === "workflow_transcript_dialogues") assertTranscriptOfflineConfiguration(process.env);
    const config = apiConfiguration();
    if (!process.env.EVAL_REQUEST_TIMEOUT_MS && scenario.limits?.requestTimeoutMs) {
      config.requestTimeoutMs = scenario.limits.requestTimeoutMs;
    }
    if (config.provider === "omp" && !process.env.EVAL_OMP_MODEL && scenario.ompModel) {
      config.ompModel = scenario.ompModel;
    }
    if (config.provider === "omp" && scenario.ompThinking) {
      config.ompThinking = scenario.ompThinking;
    }
    config.paidScenario = scenarioName;
    config.paidRun = process.argv.includes("--paid") ||
      Object.hasOwn(PAID_RUNS, scenarioName) && config.provider === "omp";
    if (config.paidRun) {
      if (!Object.hasOwn(PAID_RUNS, scenarioName) || process.env.EVAL_BACKEND !== "simulated") {
        throw new EvalTermination("PAID_RUN_CONFIG: require a whitelisted scenario and explicit simulated backend");
      }
      const effective = assertPaidConfiguration(config, scenarioName);
      context.diagnostics.paidConfiguration = effective;
      console.log(`Paid configuration: ${JSON.stringify(effective)}`);
    }
    const rules = await readFile(path.join(ROOT, "src/main/resources/llm/rules.md"), "utf8");
    const userPrompt = await readFile(path.join(ROOT, "evals/offline/user-prompt.txt"), "utf8");
    const catalog = await loadToolCatalog();
    if (scenario.validation === "customfields_create_screen_names") catalog.push(SCREEN_DESCRIPTION_TOOL);
    const tools = assistantTools(catalog);
    writeTools = new Set(
      catalog.filter((tool) => tool.annotations?.readOnlyHint === false).map((tool) => tool.name),
    );
    const readTools = new Set(
      catalog.filter((tool) => tool.annotations?.readOnlyHint === true).map((tool) => tool.name),
    );
    const backend = createToolBackend({ simulated: scenarioSimulator(scenario) });
    if (backend.mode !== "simulated") throw new EvalTermination("Для live используйте npm run eval:workflow-live");
    modelMessages.push(
      { role: "system", content: scenario.harnessInstructions ? `${rules}\n\n${scenario.harnessInstructions}` : rules },
      { role: "user", content: scenario.initialUserMessage },
    );
    conversation.push({ role: "user", content: scenario.initialUserMessage, turn: 1 });
    let userTurn = 1;
    let assistantTurn = 0;
    let previousTool = null;
    let completed = false;
    let idleTurns = 0;
    let runnerOwnership = null;

    for (let turn = 1; turn <= (scenario.limits?.turns ?? DEFAULT_MAX_TURNS); turn += 1) {
      if (Date.now() >= context.deadline) throw new EvalTermination(`scenario timeout ${scenarioTimeoutMs} ms`);
      assistantTurn += 1;
      const traceAtTurnStart = trace.length;
      const visibleFragments = [];
      while (true) {
        const message = await complete(config, {
          model: config.assistantModel,
          messages: modelMessages,
          tools,
          tool_choice: "auto",
        }, "assistant", context);
        modelMessages.push(message);
        if (message.content?.trim()) visibleFragments.push(message.content.trim());
        const calls = message.tool_calls ?? [];
        if (calls.length === 0) break;

        for (const call of calls) {
          if (context.diagnostics.toolCalls >= context.maxToolCalls) {
            throw new EvalTermination(`tool calls превысили лимит ${context.maxToolCalls}`);
          }
          context.diagnostics.toolCalls += 1;
          let args;
          try {
            args = JSON.parse(call.function.arguments || "{}");
          } catch (error) {
            args = { _invalidJson: call.function.arguments };
          }
          const tool = call.function.name;
          const readOnly = readTools.has(tool);
          const duplicateRead = readOnly &&
            previousTool?.readOnly === true &&
            previousTool.tool === tool &&
            previousTool.arguments === canonical(args) &&
            !(tool === "get_operation" && ["QUEUED", "RUNNING", "CANCEL_REQUESTED"].includes(previousTool.status));
          if (duplicateRead) {
            const result = {
              status: "FAILED",
              errorCode: "EVAL_DUPLICATE_READ_LOOP",
              message: `Duplicate read-only call: ${tool} with identical arguments.`,
            };
            trace.push({
              sequence: trace.length + 1,
              tool,
              arguments: args,
              result,
              status: result.status,
              userTurn,
              assistantTurn,
            });
            throw new EvalTermination(`duplicate/polling loop: ${tool} ${canonical(args)}`);
          }

          let result;
          try {
            if (tool === "start_operation" && !Object.hasOwn(args, "sessionId") && !Object.hasOwn(args, "changeSetId")) {
              if (!runnerOwnership) {
                const sessionResult = await backend.call("create_change_session", {}, { conversation, userTurn, assistantTurn });
                trace.push({ sequence: trace.length + 1, tool: "create_change_session", arguments: {},
                  result: sessionResult, status: statusOf(sessionResult), userTurn, assistantTurn });
                if (isInfrastructureResult(sessionResult)) throw new InfrastructureFailure(sessionResult.message);
                const groupArgs = { sessionId: sessionResult.sessionId };
                const groupResult = await backend.call("begin_change_set", groupArgs, { conversation, userTurn, assistantTurn });
                trace.push({ sequence: trace.length + 1, tool: "begin_change_set", arguments: groupArgs,
                  result: groupResult, status: statusOf(groupResult), userTurn, assistantTurn });
                if (isInfrastructureResult(groupResult)) throw new InfrastructureFailure(groupResult.message);
                runnerOwnership = { sessionId: sessionResult.sessionId, changeSetId: groupResult.changeSetId };
              }
              args = { ...args, ...runnerOwnership };
            }
            result = tool === "read_tool_description" && scenario.validation === "customfields_create_screen_names"
              ? readScreenToolDescription(catalog, args)
              : await backend.call(tool, args, { conversation, userTurn, assistantTurn });
          } catch (error) {
            result = error.code === "START_RESPONSE_LOST"
              ? { status: "UNKNOWN", errorCode: error.code, message: String(error.message) }
              : { status: "FAILED", errorCode: "TOOL_ERROR", message: String(error.message) };
          }
          trace.push({
            sequence: trace.length + 1,
            tool,
            arguments: args,
            result,
            status: statusOf(result),
            userTurn,
            assistantTurn,
          });
          const phase = tool === "start_operation" ? "apply" : args?.dryRun === true ? "preview" : "read";
          progress(context, `tool: ${tool} [${phase}] -> ${statusOf(result) ?? "OK"}`);
          if (isInfrastructureResult(result)) {
            throw new InfrastructureFailure(`${tool}: ${result.errorCode}: ${result.message}`);
          }
          if (scenario.kind === "project_document" && /^EVAL_/u.test(result?.errorCode ?? "")) {
            throw new EvalTermination(`${tool}: ${result.errorCode}: ${result.message}`);
          }
          modelMessages.push({ role: "tool", tool_call_id: call.id, content: JSON.stringify(result) });
          previousTool = { readOnly, tool, arguments: canonical(args), status: statusOf(result) };
          if (tool === "assign_workflow" && result?.status === "WOULD_ASSIGN") {
            progress(context, "preview received");
          }
        }
      }

      const visible = visibleFragments.join("\n\n").trim();
      if (!visible) throw new EvalTermination("Ассистент не вернул пользовательский текст после tool calls");
      conversation.push({ role: "assistant", content: visible, turn: assistantTurn });
      idleTurns = trace.length === traceAtTurnStart ? idleTurns + 1 : 0;
      if (scenario.kind === "project_document" && idleTurns >= (scenario.limits?.idleTurns ?? 2)) {
        throw new EvalTermination("narrative loop: два хода без tool calls при незавершённом плане");
      }
      // previewOnly: the scenario expects the preview to be shown but never applied (the user declines that step).
      const expectedOperations = scenario.kind === "project_document" ? scenario.operations.filter(op => !op.previewOnly) : [{
        tool: "assign_workflow", preview: { planId: scenario.planId }, apply: scenario.apply,
      }];
      const operationsCompleted = expectedOperations.every((operation) => trace.some((call) =>
        call.tool === "start_operation" && call.arguments?.toolName === operation.tool &&
        call.arguments?.arguments?.planId === operation.preview.planId &&
        operationCompletion(trace, call, operation.apply.status)));
      if (operationsCompleted && runnerOwnership &&
          !trace.some(call => call.tool === "finish_change_set")) {
        const args = { changeSetId: runnerOwnership.changeSetId };
        const result = await backend.call("finish_change_set", args);
        trace.push({ sequence: trace.length + 1, tool: "finish_change_set", arguments: args,
          result, status: statusOf(result), userTurn, assistantTurn });
        if (isInfrastructureResult(result)) throw new InfrastructureFailure(`${result.errorCode}: ${result.message}`);
      }
      completed = operationsCompleted &&
        trace.some((call) => call.tool === "finish_change_set" && call.result?.status === "FINISHED");
      // Global-field acceptance continues with project and all-screens requests after creation.
      if (completed && scenario.validation !== "customfields_global_screens") break;

      const userMessage = await nextSyntheticUser(config, userPrompt, scenario, conversation, trace, context);
      if (!userMessage) throw new EvalTermination("Synthetic User вернул пустую реплику");
      userTurn += 1;
      conversation.push({ role: "user", content: userMessage, turn: userTurn });
      modelMessages.push({ role: "user", content: userMessage });
      if (userMessage === "DONE" && (scenario.validation === "customfields_per_field_screens" ||
          scenario.validation === "customfields_screen_pagination" ||
          scenario.validation === "customfields_global_screens" ||
          scenario.validation === "issuetypes_partial_result" ||
          scenario.validation === "workflow_read_no_counts" ||
          scenario.validation === "workflow_validators" ||
          scenario.validation === "workflow_resolution_extra" && scenario.variant === "unresolved")) break;
      if (turn === (scenario.limits?.turns ?? DEFAULT_MAX_TURNS)) {
        throw new EvalTermination(`dialogue turns превысили лимит ${scenario.limits?.turns ?? DEFAULT_MAX_TURNS}`);
      }
    }

    if (scenario.validation === "workflow_transcript_dialogues") {
      verdict = gradeTranscriptDialogue({ scenario, trace, conversation, modelMessages });
    } else if (scenario.validation === "workflow_manual_qa_loops") {
      verdict = gradeManualQaLoops({ scenario, trace, conversation, modelMessages });
    } else if (scenario.validation === "dialogue_protocol") {
      verdict = gradeDialogueProtocol({ scenario, trace, conversation, modelMessages, readTools });
    } else if (scenario.validation === "workflow_new_screen") {
      verdict = gradeWorkflowNewScreen({ scenario, trace, conversation, writeTools });
    } else if (scenario.validation === "workflow_resolution") {
      verdict = gradeWorkflowResolutionWithTrace({ scenario, trace, conversation, writeTools });
    } else if (scenario.validation === "workflow_resolution_extra") {
      verdict = gradeWorkflowResolutionExtra({ scenario, trace, conversation, writeTools });
    } else if (scenario.validation === "screen_layout_dialogues") {
      verdict = gradeScreenLayout({ scenario, trace, conversation, modelMessages });
    } else if (scenario.validation === "customfields_lab_two_screens") {
      verdict = gradeLabTwoScreens({ scenario, trace, conversation, modelMessages });
    } else if (scenario.validation === "customfields_create_screen_names") {
      verdict = gradeCreateScreenNames({ scenario, trace, conversation, modelMessages });
    } else if (scenario.validation === "customfields_per_field_screens") {
      verdict = gradeCustomfieldsPerFieldScreens({ scenario, trace, conversation, writeTools });
    } else if (scenario.validation === "customfields_screen_pagination") {
      verdict = gradeCustomfieldsScreenPagination({ scenario, trace, conversation, modelMessages });
    } else if (scenario.validation === "customfields_global_screens") {
      verdict = gradeCustomfieldsGlobalScreens({ scenario, trace, conversation, modelMessages });
    } else if (scenario.validation === "issuetypes_partial_result") {
      verdict = gradeIssueTypesPartialResult({ scenario, trace, conversation });
    } else if (scenario.validation === "workflow_read_no_counts") {
      verdict = gradeWorkflowReadNoCounts({ scenario, trace });
    } else if (scenario.validation === "workflow_draft_publish") {
      verdict = gradeWorkflowDraftPublish({ scenario, trace, conversation });
    } else if (scenario.validation === "workflow_validators") {
      verdict = gradeWorkflowValidators({ scenario, trace, conversation });
    } else if (scenario.kind === "project_document") {
      const intakeAssessment = await assessProjectDocumentIntake(config, scenario, conversation, context);
      verdict = gradeProjectDocument({ scenario, trace, conversation, intakeAssessment, writeTools });
    } else {
      const previewCall = trace.find(
        (call) => call.tool === "assign_workflow" && call.arguments?.dryRun === true,
      );
      const previewMessage = conversation.find(
        (message) => message.role === "assistant" && message.turn === previewCall?.assistantTurn,
      )?.content;
      const previewAssessment = await assessPreview(config, scenario, previewMessage, context);
      verdict = gradeAssignWorkflow({ trace, conversation, previewAssessment, writeTools });
    }
    context.diagnostics.terminationReason = verdict.passed ? "completed" : "grader-failed";
  } catch (error) {
    context.diagnostics.terminationReason = error instanceof Error ? error.message : String(error);
    verdict = error instanceof InfrastructureFailure
      ? { scenario: scenario?.scenario ?? scenarioName, status: "ERROR", passed: false, checks: {},
          failures: [], violations: [], infrastructureError: context.diagnostics.terminationReason,
          observations: { tool_calls: trace.length, reads_after_assigned: 0, extra_write_calls: [] } }
      : scenario?.validation === "workflow_transcript_dialogues"
      ? gradeTranscriptDialogue({ scenario, trace, conversation, modelMessages })
      : scenario?.validation === "workflow_manual_qa_loops"
      ? gradeManualQaLoops({ scenario, trace, conversation, modelMessages })
      : scenario?.validation === "dialogue_protocol"
      ? gradeDialogueProtocol({ scenario, trace, conversation, modelMessages })
      : scenario?.validation === "workflow_new_screen"
      ? gradeWorkflowNewScreen({ scenario, trace, conversation, writeTools })
      : scenario?.validation === "workflow_resolution"
      ? gradeWorkflowResolutionWithTrace({ scenario, trace, conversation, writeTools })
      : scenario?.validation === "workflow_resolution_extra"
      ? gradeWorkflowResolutionExtra({ scenario, trace, conversation, writeTools })
      : scenario?.validation === "screen_layout_dialogues"
      ? gradeScreenLayout({ scenario, trace, conversation, modelMessages })
      : scenario?.validation === "customfields_lab_two_screens"
      ? gradeLabTwoScreens({ scenario, trace, conversation, modelMessages })
      : scenario?.validation === "customfields_create_screen_names"
      ? gradeCreateScreenNames({ scenario, trace, conversation, modelMessages })
      : scenario?.validation === "customfields_global_screens"
      ? gradeCustomfieldsGlobalScreens({ scenario, trace, conversation, modelMessages })
      : scenario?.validation === "workflow_draft_publish"
      ? gradeWorkflowDraftPublish({ scenario, trace, conversation })
      : scenario?.validation === "workflow_validators"
      ? gradeWorkflowValidators({ scenario, trace, conversation })
      : scenario?.kind === "project_document"
      ? gradeProjectDocument({
          scenario,
          trace,
          conversation,
          intakeAssessment: { shown: false, missing: ["оценка не завершена"] },
          writeTools,
        })
      : gradeAssignWorkflow({
          trace,
          conversation,
          previewAssessment: { shown: false, missing: ["оценка не завершена"] },
          writeTools,
        });
    verdict.passed = false;
    verdict.failures.unshift(context.diagnostics.terminationReason);
  } finally {
    clearTimeout(timer);
    context.diagnostics.durationMs = Date.now() - startedAt;
    context.diagnostics.toolCalls = trace.length;
    if (!verdict) {
      verdict = {
        scenario: scenario?.scenario ?? scenarioName,
        passed: false,
        checks: {},
        failures: ["runner завершился без verdict"],
        observations: { tool_calls: trace.length, reads_after_assigned: 0, extra_write_calls: [] },
      };
      context.diagnostics.terminationReason ||= "runner завершился без verdict";
    }
    verdict.diagnostics = context.diagnostics;
    await saveArtifacts(outputDirectory, conversation, trace, verdict);
  }

  progress(context, verdict.status ?? (verdict.passed ? "PASS" : "FAIL"));
  console.log(detailedSummary(verdict).trim());
  console.log(`Артефакты: ${path.relative(ROOT, outputDirectory)}`);
  process.exitCode = verdict.status === "ERROR" ? 2 : verdict.passed ? 0 : 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(`Не удалось сохранить eval artifacts: ${error.message}`);
    process.exitCode = 2;
  });
}
