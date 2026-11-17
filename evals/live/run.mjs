// Prepared dialogue: LLM user simulator ↔ LLM assistant running in omp with the plugin's live MCP server on atlas-run
// Jira, called natively. Output is a transcript only; nothing is graded, mocked or cleaned up.
import { spawn } from 'node:child_process';
import { randomInt, randomUUID } from 'node:crypto';
import { access, copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { traceChecks, PLUGIN_TOOLS, setupProtocolChecks } from './checks.mjs';
import { createConfirmedSetup, readOnlyAccess } from './setup-confirmation.mjs';
import { reportChecks } from './check-results.mjs';
import { ISSUE_TYPE_ENV, resolveIssueType } from './issue-type.mjs';
import { homedir, tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline/promises';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const MCP_PATH = '/rest/control/1.0/mcp';
const MCP_SERVER = 'jira-control';
const MCP_TOOL_PREFIX = 'mcp__jira_control_';
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1']);
const USER_TIMEOUT_MS = 5 * 60_000;
const ASSISTANT_TIMEOUT_MS = 20 * 60_000;
const MCP_TIMEOUT_MS = 2 * 60_000;
const OPERATION_TIMEOUT_MS = 5 * 60_000;
const RUNNING_OPERATION = new Set(['QUEUED', 'RUNNING', 'CANCEL_REQUESTED']);
const USAGE = 'Usage: node evals/live/run.mjs <scenario-folder> [--assistant-model MODEL] [--user-model MODEL] [--max-turns N] [--human-user] [--check | --setup-only]\n'
  + '       node evals/live/run.mjs --regrade <path-to-run-folder>';

const USER_ENVELOPE = 'Ты играешь пользователя в диалоге. Ответь только JSON: '
  + '{"message": string, "signal": "CONTINUE" | "DONE" | "GIVE_UP"}.';

function parseArgs(argv) {
  const options = { scenario: null, maxTurns: 15, check: false, setupOnly: false, regrade: null, humanUser: false,
    assistantModel: null, userModel: null };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--check') options.check = true;
    else if (argv[i] === '--setup-only') options.setupOnly = true;
    else if (argv[i] === '--human-user') options.humanUser = true;
    else if (argv[i] === '--regrade' && argv[i + 1]) options.regrade = argv[++i];
    else if (argv[i] === '--assistant-model' || argv[i] === '--user-model') {
      const role = argv[i] === '--assistant-model' ? 'assistantModel' : 'userModel';
      const value = argv[++i];
      if (!value?.trim() || value.startsWith('--')) throw new Error(USAGE);
      options[role] = value;
    } else if (argv[i] === '--max-turns' && /^\d+$/u.test(argv[i + 1] ?? '')) {
      options.maxTurns = Number(argv[++i]);
      if (!Number.isSafeInteger(options.maxTurns) || options.maxTurns < 1) throw new Error(USAGE);
    }
    else if (!argv[i].startsWith('--') && !options.scenario) options.scenario = argv[i];
    else throw new Error(USAGE);
  }
  if (options.regrade ? options.scenario || options.check || options.setupOnly : !options.scenario || options.maxTurns < 1 || (options.check && options.setupOnly)) throw new Error(USAGE);
  return options;
}

const ADJECTIVES = ['Amber', 'Arctic', 'Brisk', 'Cobalt', 'Crimson', 'Dusty', 'Electric', 'Emerald', 'Frosty', 'Golden',
  'Hidden', 'Indigo', 'Jade', 'Lunar', 'Mellow', 'Misty', 'Nimble', 'Olive', 'Polar', 'Quiet', 'Rapid', 'Rustic',
  'Scarlet', 'Silent', 'Solar', 'Stormy', 'Sunny', 'Tidal', 'Velvet', 'Violet', 'Wild', 'Windy', 'Zesty', 'Copper',
  'Granite', 'Ivory', 'Maple', 'Onyx', 'Pearl', 'Saffron'];
const NOUNS = ['Falcon', 'Otter', 'Badger', 'Comet', 'Harbor', 'Lantern', 'Meadow', 'Pebble', 'Quartz', 'Raven', 'Summit',
  'Thistle', 'Walrus', 'Willow', 'Anchor', 'Beacon', 'Canyon', 'Dolphin', 'Ember', 'Fjord', 'Glacier', 'Heron',
  'Island', 'Juniper', 'Kestrel', 'Lagoon', 'Marmot', 'Nebula', 'Orchid', 'Panther', 'Reef', 'Sparrow', 'Tundra',
  'Vortex', 'Yarrow', 'Zephyr', 'Cactus', 'Dune', 'Fox', 'Lynx'];
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const KEY_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const pick = (list) => list[randomInt(list.length)];
const code = () => Array.from({ length: 3 }, () => CODE_ALPHABET[randomInt(CODE_ALPHABET.length)]).join('');
const projectKey = () => Array.from({ length: 5 }, () => KEY_ALPHABET[randomInt(KEY_ALPHABET.length)]).join('');

/**
 * Random names for one run, one value per placeholder key. `{{NAME:key}}` is "Adjective Noun CODE" (a name that is
 * practically never seen twice, so Jira never answers EXISTS from an earlier run); `{{WORD:key}}` is a single noun for
 * things like select options, different for every key of the run; `{{KEY:key}}` is a Jira project key of five Latin
 * capitals. The same key gives the same value everywhere in scenario.md, so the user agent and the opening line always
 * agree.
 */
function randomNames() {
  const names = new Map();
  const usedWords = new Set();
  return {
    names,
    resolve(kind, key) {
      const id = `${kind}:${key}`;
      if (!names.has(id)) {
        let value;
        if (kind === 'NAME') value = `${pick(ADJECTIVES)} ${pick(NOUNS)} ${code()}`;
        else if (kind === 'KEY') value = projectKey();
        else {
          do value = pick(NOUNS); while (usedWords.has(value));
          usedWords.add(value);
        }
        names.set(id, value);
      }
      return names.get(id);
    },
  };
}

/**
 * `{{RUN}}` in scenario.md becomes a short per-run id; `{{NAME:key}}`, `{{WORD:key}}` and `{{KEY:key}}` become random
 * values chosen anew for every run (see randomNames). `{{TYPE:issue}}` is the one existing standard issue type chosen
 * with LIVE_ISSUE_TYPE: `resolveType` (called only when the scenario uses it) returns { id, name } or throws, so a
 * missing, unknown, ambiguous or subtask type stops the run before anything is written. Any other `{{...}}` is an
 * error, not silently left in the text.
 */
async function loadScenario(name, resolveType) {
  const directory = path.join(HERE, 'scenarios', name);
  const file = path.join(directory, 'scenario.md');
  const runId = Date.now().toString(36).slice(-5).toUpperCase();
  const random = randomNames();
  const raw = await readFile(file, 'utf8');
  const typeKeys = [...raw.matchAll(/\{\{TYPE:([^}]*)\}\}/gu)].map((m) => m[1]);
  if (typeKeys.some((key) => key !== 'issue')) throw new Error(`${file}: поддерживается только {{TYPE:issue}}`);
  const issueType = typeKeys.length ? await resolveType() : null;
  const text = raw.replaceAll('{{RUN}}', runId).replaceAll('{{TYPE:issue}}', issueType?.name ?? '')
    .replace(/\{\{(NAME|WORD|KEY):([A-Za-z0-9_-]+)\}\}/gu, (_, kind, key) => random.resolve(kind, key));
  const leftover = /\{\{[^}]*\}\}/u.exec(text);
  if (leftover) throw new Error(`${file}: неизвестный placeholder ${leftover[0]}`);
  const opening = /^## Первая реплика\s*$([\s\S]*?)(?=^## |(?![\s\S]))/mu.exec(text)?.[1].trim();
  if (!opening) throw new Error(`${file}: нет раздела "## Первая реплика"`);
  const names = Object.fromEntries(random.names);
  if (issueType) names['TYPE:issue'] = issueType.name;
  return { name, directory, text, opening, runId, names, issueType };
}

function jiraConfig(env) {
  const baseUrl = (env.JIRA_BASE_URL || 'http://localhost:2990/jira').replace(/\/$/u, '');
  if (!LOCAL_HOSTS.has(new URL(baseUrl).hostname)) {
    throw new Error(`JIRA_BASE_URL=${baseUrl}: разрешена только локальная тестовая Jira (localhost/127.0.0.1)`);
  }
  const user = env.JIRA_USERNAME || 'admin';
  const password = env.JIRA_PASSWORD || 'admin';
  // The MCP endpoint accepts only a personal access token; Basic stays for the setup REST and admin pages.
  if (!env.JIRA_TOKEN) throw new Error('JIRA_TOKEN не задан: MCP-эндпоинт принимает только Authorization: Bearer <PAT>');
  return {
    baseUrl,
    authorization: `Basic ${Buffer.from(`${user}:${password}`).toString('base64')}`,
    mcpAuthorization: `Bearer ${env.JIRA_TOKEN}`,
  };
}

class Mcp {
  #id = 0;
  constructor({ baseUrl, mcpAuthorization }) { this.url = baseUrl + MCP_PATH; this.authorization = mcpAuthorization; }

  async #rpc(method, params = {}) {
    const id = ++this.#id;
    const response = await fetch(this.url, {
      method: 'POST',
      headers: { authorization: this.authorization, 'content-type': 'application/json', accept: 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
      signal: AbortSignal.timeout(MCP_TIMEOUT_MS),
    });
    if (!response.ok) throw new Error(`MCP ${method}: HTTP ${response.status}`);
    const reply = await response.json();
    if (reply.error || reply.id !== id) throw new Error(`MCP ${method}: ${JSON.stringify(reply.error ?? reply)}`);
    return reply.result;
  }

  async connect() {
    const init = await this.#rpc('initialize', {
      protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'control-live-dialogue', version: '1.0' },
    });
    const { tools } = await this.#rpc('tools/list');
    return { instructions: init.instructions ?? '', pluginVersion: init.serverInfo?.version ?? null, tools };
  }

  /** Calls one plugin tool; returns { isError, data } (data is the parsed JSON answer, or the text if it is not JSON). */
  async call(name, args) {
    const result = await this.#rpc('tools/call', { name, arguments: args });
    const text = (result.content ?? []).map((part) => part.text ?? '').join('');
    let data;
    try { data = JSON.parse(text); } catch { data = text; }
    return { isError: Boolean(result.isError), data, text };
  }

  /** Calls one plugin tool; returns its JSON result, throws on isError (a FAILED status may still come back as data). */
  async callTool(name, args) {
    const { isError, data, text } = await this.call(name, args);
    if (isError) throw new Error(`${name}: ${text.slice(0, 1000)}`);
    return data;
  }
}

// --- scenario setup ---------------------------------------------------------

/**
 * Jira REST and admin-page access for setup.mjs, with the runner's local-only Basic credentials. `rest` speaks JSON;
 * `adminPage` reaches Jira's admin actions that have no REST equivalent (GET without params, form POST with them).
 */
function jiraHttp({ baseUrl, authorization }) {
  const headers = { authorization, 'X-Atlassian-Token': 'no-check', accept: 'application/json' };
  return {
    async rest(method, apiPath, body) {
      const response = await fetch(baseUrl + apiPath, {
        method,
        headers: body === undefined ? headers : { ...headers, 'content-type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(MCP_TIMEOUT_MS),
      });
      const text = await response.text();
      if (!response.ok) throw new Error(`${method} ${apiPath}: HTTP ${response.status} ${text.slice(0, 500)}`);
      return text ? JSON.parse(text) : null;
    },
    async adminPage(pagePath, params) {
      const response = await fetch(baseUrl + pagePath, {
        method: params ? 'POST' : 'GET',
        headers: params ? { ...headers, 'content-type': 'application/x-www-form-urlencoded' } : headers,
        body: params ? new URLSearchParams(params).toString() : undefined,
        redirect: 'manual',
        signal: AbortSignal.timeout(MCP_TIMEOUT_MS),
      });
      if (response.status >= 400) throw new Error(`${pagePath}: HTTP ${response.status}`);
      return { status: response.status, location: response.headers.get('location'), text: await response.text() };
    },
  };
}

/** Preview, then apply through start_operation and wait for the terminal state; returns the domain result. */
async function applyTool(mcp, name, args) {
  const preview = await mcp.callTool(name, { ...args, dryRun: true });
  if (!preview.planId) throw new Error(`${name}: превью без плана: ${JSON.stringify(preview).slice(0, 1000)}`);
  let operation = await mcp.callTool('start_operation', { toolName: name, arguments: { dryRun: false, planId: preview.planId } });
  const deadline = Date.now() + OPERATION_TIMEOUT_MS;
  while (RUNNING_OPERATION.has(operation.status)) {
    if (Date.now() > deadline) throw new Error(`${name}: операция ${operation.operationId} не завершилась`);
    await new Promise((resolve) => setTimeout(resolve, 1000));
    operation = await mcp.callTool('get_operation', { operationId: operation.operationId });
  }
  if (operation.status !== 'COMPLETED') {
    throw new Error(`${name}: ${operation.status} ${JSON.stringify(operation.error ?? operation.result).slice(0, 1000)}`);
  }
  return operation.result;
}

/**
 * Optional scenarios/<name>/setup.mjs prepares Jira before the dialogue. Its default export receives the run's random
 * names (same values as in scenario.md), plugin tools (`tool` reads, `apply` previews and applies) and Jira HTTP;
 * it returns a JSON summary that the runner saves as setup.json. Returns null when the scenario has no setup.
 */
async function scenarioSetup(scenario) {
  const file = path.join(scenario.directory, 'setup.mjs');
  try { await access(file); } catch (error) { if (error.code === 'ENOENT') return null; throw error; }
  const module = await import(pathToFileURL(file).href);
  if (typeof module.default !== 'function') throw new Error('setup.mjs must export a setup function');
  if (module.confirmation !== undefined && module.confirmation !== 'simulated-user') throw new Error('Unsupported setup confirmation mode');
  const scope = module.confirmationScope;
  if (scope !== undefined && (module.confirmation !== 'simulated-user' || typeof scope?.rules !== 'function')) throw new Error('confirmationScope needs simulated-user confirmation and a rules(runId) function');
  return { run: module.default, confirmation: module.confirmation, scope };
}

async function loadEvidence(directory) {
  const file = process.env.LOCAL005_EVIDENCE_FILE || (directory && path.join(directory, 'evidence.json'));
  if (!file) return { evidence: null, evidenceDirectory: process.cwd() };
  const absolute = path.resolve(file);
  try { await access(absolute); } catch (error) {
    if (error.code !== 'ENOENT' || process.env.LOCAL005_EVIDENCE_FILE) throw error;
    return { evidence: null, evidenceDirectory: directory };
  }
  const evidence = JSON.parse(await readFile(absolute, 'utf8'));
  // Saved manifests remain usable on regrade even when the source manifest lived outside the run folder.
  const absoluteArtifacts = value => {
    if (!value || typeof value !== 'object') return;
    if (typeof value.path === 'string' && typeof value.sha256 === 'string') value.path = path.resolve(path.dirname(absolute), value.path);
    for (const child of Object.values(value)) absoluteArtifacts(child);
  };
  absoluteArtifacts(evidence);
  return { evidence, evidenceDirectory: path.dirname(absolute) };
}

// The only scenario whose setup may bind its own fresh projects to the Default Issue Type Screen Scheme (itss-bind.mjs).
const ITSS_BINDING_SCENARIOS = new Set(['itss-mappings-shared-default']);

async function runSetup(setup, scenario, jira, mcp, evidenceContext, confirmed) {
  const http = jiraHttp(jira);
  const reads = readOnlyAccess({ call: (name, args) => mcp.callTool(name, args), ...http, allowed: PLUGIN_TOOLS });
  // Loaded only for the scenario that may bind: no other run (or isolated runner snapshot) depends on the binder and its helpers.
  const binding = ITSS_BINDING_SCENARIOS.has(scenario.name)
    ? (await import('./itss-bind.mjs')).bindingContext({ runId: scenario.runId, baseUrl: jira.baseUrl, authorization: jira.mcpAuthorization, log: message => console.log(`[setup] ${message}`) }) : null;
  const recording = binding?.recording ?? (apply => apply);
  return setup.run({
    names: scenario.names, ...evidenceContext, runId: scenario.runId,
    tool: confirmed ? reads.tool : (name, args) => mcp.callTool(name, args),
    probe: confirmed ? reads.probe : (name, args) => mcp.call(name, args),
    apply: recording(confirmed ? (name, args) => {
      if (!PLUGIN_TOOLS.has(name) || /^(get_|list_|plan_|start_operation$)/u.test(name)) throw new Error(`Not a domain mutation tool: ${name}`);
      return confirmed.apply(name, args);
    } : (name, args) => applyTool(mcp, name, args)),
    rest: confirmed ? reads.rest : http.rest,
    adminPage: confirmed ? reads.adminPage : http.adminPage,
    ...(binding ? { bindToDefaultItss: binding.bindToDefaultItss } : {}),
    log: message => console.log(`[setup] ${message}`),
  });
}

/** Optional real-Jira intervention between the assistant's answer and the user's next reply. */
async function scenarioHooks(scenario) {
  const file = path.join(scenario.directory, 'hooks.mjs');
  try { await access(file); } catch { return null; }
  return (await import(pathToFileURL(file).href)).default;
}

// --- verification -----------------------------------------------------------

/**
 * After the dialogue: the protocol checks from checks.mjs over the tool trace, then the optional scenarios/<name>/
 * verify.mjs. Its default export receives { names, runId, prepared (setup.json), lifecycle (lifecycle.json),
 * trace, conversation, tool, probe, rest, adminPage, log } and returns [{ name, ok, detail }]. Reads only.
 */
async function verifyRun(scenario, { jira, mcp, trace, conversation, prepared, lifecycle = {}, evidenceContext = {}, setupTrace, setupConversation }) {
  const checks = traceChecks(trace, conversation, {
    classifyPresentation: ['workflow-configure-create', 'workflow-configure-existing', 'workflow-configure-stale', 'workflow-configure-new-statuses',
      'workflow-configure-existing-structure', 'workflow-configure-existing-draft',
      'workflow-configure-existing-new-status-inactive', 'itss-mappings-private', 'itss-mappings-shared-default',
      'screen-scheme-ops-private', 'screen-scheme-ops-shared', 'itss-assign-shared'].includes(scenario.name),
  });
  const confirmedSetup = (await scenarioSetup(scenario))?.confirmation === 'simulated-user'
    || scenario.name.startsWith('workflow-configure-existing-new-status-');
  if (confirmedSetup) checks.push(...setupProtocolChecks(setupTrace, setupConversation, trace));
  const file = path.join(scenario.directory, 'verify.mjs');
  let verify = null;
  try {
    await access(file);
    verify = (await import(pathToFileURL(file).href)).default;
    if (typeof verify !== 'function') throw new Error('verify.mjs must export a verifier function');
  } catch (error) {
    if (error.code !== 'ENOENT' || scenario.name.startsWith('workflow-comment-')) {
      checks.push({ name: 'verify.mjs загружен', ok: false, detail: `NOT_VERIFIED: ${error.message}` });
    }
  }
  if (verify) {
    try {
      const reads = readOnlyAccess({ call: (name, args) => mcp.callTool(name, args), ...jiraHttp(jira), allowed: PLUGIN_TOOLS });
      checks.push(...await verify({
        names: scenario.names, runId: scenario.runId, prepared, lifecycle, trace, conversation, setupTrace, setupConversation,
        ...evidenceContext,
        tool: (name, args) => mcp.callTool(name, args), probe: (name, args) => mcp.call(name, args),
        ...jiraHttp(jira), log: (message) => console.log(`[verify] ${message}`),
        ...(confirmedSetup ? reads : {}),
      }));
    } catch (error) {
      checks.push({ name: 'verify.mjs выполнен', ok: false, detail: error.message });
    }
  }
  return checks;
}


/** Re-checks a saved run folder against the saved trace and the current Jira; names and run id come from conversation.md. */
async function regrade(runFolder) {
  const directory = path.resolve(runFolder);
  const markdown = await readFile(path.join(directory, 'conversation.md'), 'utf8');
  const names = Object.fromEntries([...markdown.matchAll(/^- ((?:NAME|WORD|KEY|TYPE):[A-Za-z0-9_-]+) = (.*)$/gmu)].map((m) => [m[1], m[2].trim()]));
  const scenario = await loadScenario(path.basename(path.dirname(path.dirname(directory))), async () => {
    if (!names['TYPE:issue']) throw new Error('в conversation.md нет строки «TYPE:issue = …»: запуск был до перехода на LIVE_ISSUE_TYPE, повторная проверка невозможна');
    return { id: null, name: names['TYPE:issue'] };
  });
  scenario.runId = /^# .+ \(run ([^)]+)\)/mu.exec(markdown)?.[1] ?? scenario.runId;
  scenario.names = names;
  const conversation = [...markdown.matchAll(/^## (Пользователь|Ассистент)\r?\n\r?\n([\s\S]*?)(?=^## (?:Пользователь|Ассистент)\r?$|(?![\s\S]))/gmu)]
    .map((m) => ({ role: m[1] === 'Пользователь' ? 'user' : 'assistant', content: m[2].trim(),
      ...(m[1] === 'Пользователь' && /^- Пользователь: human$/mu.test(markdown) ? { source: 'human' } : {}) }));
  const trace = JSON.parse(await readFile(path.join(directory, 'tool-trace.json'), 'utf8'));
  let prepared = null;
  try { prepared = JSON.parse(await readFile(path.join(directory, 'setup.json'), 'utf8')); } catch { /* run without setup */ }
  let lifecycle = {};
  try { lifecycle = JSON.parse(await readFile(path.join(directory, 'lifecycle.json'), 'utf8')); } catch { /* run without hooks */ }
  const jira = jiraConfig(process.env);
  const mcp = new Mcp(jira);
  await mcp.connect();
  console.log(`Повторная проверка ${scenario.name}, ${path.basename(directory)}`);
  const evidenceContext = await loadEvidence(directory);
  let setupTrace, setupConversation;
  try {
    setupTrace = JSON.parse(await readFile(path.join(directory, 'setup-trace.json'), 'utf8'));
    setupConversation = await readFile(path.join(directory, 'setup-conversation.md'), 'utf8');
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const passed = await reportChecks(directory, await verifyRun(scenario, { jira, mcp, trace, conversation, prepared, lifecycle, evidenceContext, setupTrace, setupConversation }));
  if (!passed) process.exitCode = 3;
}

// --- omp model bridge -------------------------------------------------------

async function isolatedAgentDirectory() {
  const source = process.env.PI_CODING_AGENT_DIR || path.join(homedir(), '.omp', 'agent');
  const target = await mkdtemp(path.join(tmpdir(), 'control-live-dialogue-'));
  for (const file of ['agent.db', 'agent.db-wal', 'agent.db-shm', 'models.db', 'models.db-wal', 'models.db-shm', 'config.yml']) {
    try { await copyFile(path.join(source, file), path.join(target, file)); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  return target;
}

/** The error text a model call reports inside omp's JSON event stream (limits, auth), if there is one. */
function modelError(stdout) {
  for (const line of stdout.split(/\r?\n/u).reverse()) {
    try {
      const event = JSON.parse(line);
      const text = event.message?.errorMessage ?? event.finalError ?? event.errorMessage;
      if (text) return String(text);
    } catch { /* non-event line */ }
  }
  return null;
}

/** Runs omp; every JSON line of stdout goes to onEvent as it arrives. Resolves with the whole stdout. */
function runOmp(args, cwd, env, { timeoutMs, onEvent = () => {} }) {
  const executable = process.env.OMP_EXECUTABLE || (process.platform === 'win32' ? 'omp.exe' : 'omp');
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, { cwd, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', stderr = '', pending = '';
    const timer = setTimeout(() => { child.kill('SIGKILL'); reject(new Error(`omp timeout ${timeoutMs} ms`)); }, timeoutMs);
    const consume = (line) => {
      let event;
      try { event = JSON.parse(line); } catch { return; }
      onEvent(event);
    };
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
      const lines = (pending + chunk).split(/\r?\n/u);
      pending = lines.pop();
      lines.forEach(consume);
    });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.on('error', (error) => { clearTimeout(timer); reject(error); });
    child.on('close', (code) => {
      clearTimeout(timer);
      consume(pending);
      if (code === 0) resolve(stdout);
      else reject(new Error(`omp exit ${code}: ${(stderr.trim() || modelError(stdout) || '(нет вывода)').slice(-2000)}`));
    });
  });
}

/** The last assistant message of an omp run as { text, model }; throws on a model error or a missing answer. */
function finalAnswer(message, stdout, fallbackModel) {
  if (!message) throw new Error(`omp: нет завершённого ответа модели${modelError(stdout) ? `: ${modelError(stdout)}` : ''}`);
  if (message.stopReason === 'error') throw new Error(`модель ответила ошибкой: ${message.errorMessage ?? 'без текста'}`);
  const text = (message.content ?? []).filter((item) => item.type === 'text').map((item) => item.text).join('\n');
  return { text, model: [message.provider, message.model].filter(Boolean).join('/') || fallbackModel || 'default' };
}

/** One tool-less model call for the user simulator. Returns the final text and the resolved model. */
async function completeText({ systemPrompt, request, model, thinking }) {
  // Empty cwd: the repository's own agent context files must not leak into the user simulator.
  const directory = await isolatedAgentDirectory();
  try {
    const requestFile = path.join(directory, `request-${randomUUID()}.json`);
    await writeFile(requestFile, JSON.stringify(request));
    const args = ['-p', '--no-session', '--no-tools', '--no-rules', '--no-title', '--mode=json', `--system-prompt=${systemPrompt}`];
    if (model) args.push(`--model=${model}`);
    if (thinking) args.push(`--thinking=${thinking}`);
    args.push(`@${requestFile}`);
    let message;
    const stdout = await runOmp(args, directory, { ...process.env, PI_CODING_AGENT_DIR: directory }, {
      timeoutMs: USER_TIMEOUT_MS,
      onEvent(event) { if (event.type === 'message_end' && event.message?.role === 'assistant') message = event.message; },
    });
    return finalAnswer(message, stdout, model);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

/**
 * The assistant is a real omp client: its own agent directory with mcp.json pointing at the plugin, an empty cwd and a
 * session kept in the run folder. --no-tools removes omp's built-in tools but keeps MCP, so the plugin's tools are the
 * model's only native functions and the server instructions reach it exactly as they reach an omp user.
 */
async function assistantClient(jira, runDirectory) {
  const agentDirectory = await isolatedAgentDirectory();
  await writeFile(path.join(agentDirectory, 'mcp.json'), JSON.stringify({
    mcpServers: { [MCP_SERVER]: { type: 'http', url: jira.baseUrl + MCP_PATH, headers: { Authorization: jira.mcpAuthorization } } },
  }));
  const cwd = await mkdtemp(path.join(tmpdir(), 'control-live-assistant-'));
  return {
    cwd,
    sessionDirectory: path.join(runDirectory, 'assistant-session'),
    env: { ...process.env, PI_CODING_AGENT_DIR: agentDirectory, OMP_MCP_REQUIRE_READY: '1' },
    dispose: () => Promise.all([agentDirectory, cwd].map((dir) => rm(dir, { recursive: true, force: true }))),
  };
}

function parseJsonObject(text) {
  // Models sometimes put prose before the JSON; then take the last fenced json block.
  const fenced = [...text.matchAll(/```(?:json)?\s*(\{[\s\S]*?\})\s*```/giu)].at(-1)?.[1];
  const trimmed = text.trim().replace(/^```(?:json)?\s*/iu, '').replace(/\s*```$/u, '').trim();
  const body = trimmed.startsWith('{') ? trimmed : fenced;
  if (!body) return null;
  try {
    const value = JSON.parse(body);
    return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
  } catch { return null; }
}

// --- dialogue ---------------------------------------------------------------

function visibleTranscript(conversation) {
  return conversation.map((turn) => `${turn.role === 'user' ? 'Ты (пользователь)' : 'Ассистент'}: ${turn.content}`).join('\n\n');
}

async function assistantTurn(state, userText) {
  const client = state.assistant;
  const args = ['-p', '--mode=json', '--no-tools', '--no-rules', '--no-title', '--approval-mode=yolo',
    `--session-dir=${client.sessionDirectory}`];
  if (state.turn > 1) args.push('--continue');
  if (state.models.assistant) args.push(`--model=${state.models.assistant}`);
  if (state.thinking) args.push(`--thinking=${state.thinking}`);
  args.push(userText);
  const started = new Map();
  let message;
  const stdout = await runOmp(args, client.cwd, client.env, {
    timeoutMs: ASSISTANT_TIMEOUT_MS,
    onEvent(event) {
      if (event.type === 'tool_execution_start') started.set(event.toolCallId, { at: Date.now(), args: event.args });
      else if (event.type === 'tool_execution_end') {
        const start = started.get(event.toolCallId) ?? { at: Date.now(), args: null };
        const text = (event.result?.content ?? []).filter((item) => item.type === 'text').map((item) => item.text).join('\n');
        const entry = { seq: state.trace.length + 1, turn: state.turn, tool: event.toolName.replace(MCP_TOOL_PREFIX, ''),
          arguments: start.args, isError: event.isError === true, ms: Date.now() - start.at, result: parseJsonObject(text) ?? text };
        state.trace.push(entry);
        console.log(`  tool ${entry.tool}${entry.isError ? ' [error]' : ''} (${entry.ms} ms)`);
      } else if (event.type === 'message_end' && event.message?.role === 'assistant') message = event.message;
    },
  });
  const answer = finalAnswer(message, stdout, state.models.assistant);
  state.resolved.assistant = answer.model;
  return answer.text.trim() || '(пустой ответ)';
}

async function userTurn(state) {
  if (state.humanUser) {
    const message = (await state.humanUser.question('Пользователь (/done или /stop для завершения): ')).trim();
    return { message: /^\/(?:done|stop)$/u.test(message) ? '' : message,
      signal: message === '/done' ? 'DONE' : message === '/stop' ? 'GIVE_UP' : 'CONTINUE' };
  }
  const reply = await completeText({
    systemPrompt: USER_ENVELOPE,
    request: {
      messages: [
        { role: 'system', content: `Сценарий, по которому ты играешь пользователя:\n\n${state.scenario.text}`
          + (state.prepared?.known ? `\n\nФакты подготовки для раздела «Что ты знаешь» (говори только по вопросу):\n${JSON.stringify(state.prepared.known, null, 2)}` : '') },
        { role: 'user', content: `Диалог до сих пор:\n\n${visibleTranscript(state.conversation)}\n\n`
          + 'Напиши следующую реплику пользователя. DONE — задача решена или ты закончил; GIVE_UP — дальше бессмысленно.' },
      ],
    },
    model: state.models.user, thinking: state.thinking,
  });
  state.resolved.user = reply.model;
  const value = parseJsonObject(reply.text);
  if (!value || typeof value.message !== 'string' || !['CONTINUE', 'DONE', 'GIVE_UP'].includes(value.signal)) {
    throw new Error(`симулятор пользователя вернул не JSON нужной формы: ${reply.text.slice(0, 500)}`);
  }
  return value;
}

function conversationMarkdown(state, ending) {
  const header = [
    `# ${state.scenario.name} (run ${state.scenario.runId})`, '',
    `- Jira: ${state.jira.baseUrl} (control ${state.pluginVersion ?? '?'})`,
    `- Ассистент: ${state.resolved.assistant ?? state.models.assistant ?? 'default'} (omp, MCP ${MCP_SERVER}, --no-tools)`,
    `- MCP instructions: ${state.instructionsLength} символов`,
    `- Пользователь: ${state.resolved.user ?? state.models.user ?? 'default'}`,
    `- Tool calls: ${state.trace.length}`,
    ...Object.entries(state.scenario.names).map(([id, value]) => `- ${id} = ${value}`),
    `- Завершение: ${ending}`, '',
  ];
  const turns = state.conversation.map((turn) => `## ${turn.role === 'user' ? 'Пользователь' : 'Ассистент'}\n\n${turn.content}\n`);
  return [...header, ...turns].join('\n');
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.regrade) return regrade(options.regrade);
  const jira = jiraConfig(process.env);
  // Read-only: the type is chosen and validated before the scenario text, setup or any model call exists.
  const scenario = await loadScenario(options.scenario, () => resolveIssueType(jiraHttp(jira).rest, process.env[ISSUE_TYPE_ENV]));
  const evidenceContext = await loadEvidence();
  if (scenario.name.startsWith('workflow-comment-') && !options.check && !options.setupOnly && !options.humanUser) {
    throw new Error('LOCAL-005 requires --human-user: a simulated user cannot authorize live mutations.');
  }
  const mcp = new Mcp(jira);
  const { instructions, pluginVersion, tools } = await mcp.connect();
  console.log(`Jira ${jira.baseUrl}, control ${pluginVersion}, tools: ${tools.length}`);
  const setup = await scenarioSetup(scenario);
  const hook = await scenarioHooks(scenario);
  if (scenario.name.startsWith('workflow-configure-existing-new-status-') && setup?.confirmation !== 'simulated-user')
    throw new Error('LOCAL-013 requires simulated-user setup confirmation; legacy auto-apply is forbidden');
  if (options.check) {
    console.log(`Сценарий ${scenario.name}, первая реплика:\n${scenario.opening}`);
    for (const [id, value] of Object.entries(scenario.names)) {
      console.log(`  ${id} = ${value}${id === 'TYPE:issue' ? ` (id ${scenario.issueType.id}, из ${ISSUE_TYPE_ENV})` : ''}`);
    }
    console.log(setup ? 'Подготовка: setup.mjs (при --check не выполняется)' : 'Подготовка: нет');
    return;
  }
  for (const [id, value] of Object.entries(scenario.names)) console.log(`  ${id} = ${value}`);
  const stamp = new Date().toISOString().replace(/[:.]/gu, '-');
  const directory = path.join(scenario.directory, 'runs', stamp);
  await mkdir(directory, { recursive: true });
  if (evidenceContext.evidence) await writeFile(path.join(directory, 'evidence.json'), `${JSON.stringify(evidenceContext.evidence, null, 2)}\n`);
  const state = {
    scenario, jira, prepared: null, pluginVersion, instructionsLength: instructions.length, turn: 0, trace: [], conversation: [], resolved: {},
    humanUser: options.humanUser ? createInterface({ input: process.stdin, output: process.stdout }) : null,
    models: { assistant: options.assistantModel || process.env.DIALOG_ASSISTANT_MODEL || null,
      user: options.humanUser ? 'human' : options.userModel || process.env.DIALOG_USER_MODEL || null },
    thinking: process.env.DIALOG_THINKING || null,
    assistant: null,
  };
  const lifecycle = {};
  const save = async (ending) => {
    await writeFile(path.join(directory, 'conversation.md'), conversationMarkdown(state, ending));
    await writeFile(path.join(directory, 'tool-trace.json'), `${JSON.stringify(state.trace, null, 2)}\n`);
    if (hook) await writeFile(path.join(directory, 'lifecycle.json'), `${JSON.stringify(lifecycle, null, 2)}\n`);
  };
  await save('setup not completed');
  let confirmed;
  if (setup?.confirmation === 'simulated-user') {
    confirmed = createConfirmedSetup({
      call: (name, args) => mcp.callTool(name, args), approve: completeText,
      model: options.userModel || process.env.DIALOG_USER_MODEL || null, runId: scenario.runId, scope: setup.scope,
      persist: async ({ trace, conversation }) => {
        await writeFile(path.join(directory, 'setup-trace.json'), `${JSON.stringify(trace, null, 2)}\n`);
        await writeFile(path.join(directory, 'setup-conversation.md'), conversation);
      },
    });
    await confirmed.initialize();
  }
  let prepared;
  try {
    prepared = setup ? await runSetup(setup, scenario, jira, mcp, evidenceContext, confirmed) : null;
    if (setup) await writeFile(path.join(directory, 'setup.json'), `${JSON.stringify(prepared, null, 2)}\n`);
    await confirmed?.complete();
  } catch (error) {
    await save(`setup failed: ${error.message}`);
    state.humanUser?.close();
    await reportChecks(directory, [{ name: 'Setup completed', class: 'contract', ok: false, detail: error.message }]);
    throw error;
  }
  state.prepared = prepared;
  if (options.setupOnly) {
    await save('setup-only completed');
    state.humanUser?.close();
    if (confirmed && !await reportChecks(directory, setupProtocolChecks(confirmed.trace, confirmed.conversation))) process.exitCode = 3;
    console.log(`Подготовка выполнена. Evidence: ${directory}`);
    return;
  }
  state.assistant = await assistantClient(jira, directory);

  let userText = scenario.opening;
  let ending = `лимит ${options.maxTurns} ходов`;
  try {
    for (state.turn = 1; state.turn <= options.maxTurns; state.turn++) {
      state.conversation.push({ role: 'user', content: userText, ...(options.humanUser ? { source: 'human' } : {}) });
      console.log(`\n[${state.turn}] Пользователь: ${userText}`);
      const answer = await assistantTurn(state, userText);
      state.conversation.push({ role: 'assistant', content: answer });
      console.log(`[${state.turn}] Ассистент: ${answer}`);
      if (hook) {
        try {
          await hook({
            jira, prepared, lifecycle, trace: state.trace, conversation: state.conversation, turn: state.turn,
            ...evidenceContext,
            tool: (name, args) => mcp.callTool(name, args), ...jiraHttp(jira),
            log: (message) => console.log(`[hook] ${message}`),
          });
        } catch (error) {
          lifecycle.error = error.message;
          throw error;
        }
      }
      await save('в процессе');
      const next = await userTurn(state);
      if (next.signal !== 'CONTINUE') {
        if (next.message.trim()) state.conversation.push({ role: 'user', content: next.message.trim(), ...(options.humanUser ? { source: 'human' } : {}) });
        ending = next.signal === 'DONE' ? 'пользователь завершил (DONE)' : 'пользователь сдался (GIVE_UP)';
        break;
      }
      userText = next.message;
    }
  } catch (error) {
    ending = `ошибка: ${error.message}`;
    process.exitCode = 1;
  }
  await state.assistant.dispose();
  state.humanUser?.close();
  await save(ending);
  if (process.env.LOCAL005_EVIDENCE_FILE) {
    Object.assign(evidenceContext, await loadEvidence());
    await writeFile(path.join(directory, 'evidence.json'), `${JSON.stringify(evidenceContext.evidence, null, 2)}\n`);
  }
  console.log(`\n${ending}\nТранскрипт: ${path.relative(process.cwd(), directory)}`);
  const passed = await reportChecks(directory, await verifyRun(scenario, { jira, mcp, trace: state.trace, conversation: state.conversation, prepared, lifecycle, evidenceContext, setupTrace: confirmed?.trace, setupConversation: confirmed?.conversation }));
  if (!passed && !process.exitCode) process.exitCode = 3;
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 2;
});
