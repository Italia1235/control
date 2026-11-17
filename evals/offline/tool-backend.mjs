import { setTimeout as delay } from 'node:timers/promises';
import { jiraClient } from './guard.mjs';

const MCP = '/rest/control/1.0/mcp';
const CHANGES = '/rest/control/1.0/changes';
const INTERNAL_TOOLS = new Set([
  'create_change_session', 'list_change_sessions', 'begin_change_set',
  'finish_change_set', 'list_session_changes', 'cancel_operation', 'rollback_changes',
]);
const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const identity = (value) => typeof value === 'string' && value.length > 0;

export class InfrastructureError extends Error {
  constructor(message, options) {
    super(message, options);
    this.name = 'InfrastructureError';
  }
}

export class ToolExecutionError extends Error {
  constructor(tool, result) {
    super(`MCP tool ${tool} reported an execution error`);
    this.name = 'ToolExecutionError';
    this.tool = tool;
    this.result = result;
  }
}

export class ToolBackend {
  async initialize() { throw new Error('ToolBackend.initialize must be implemented'); }
  async call() { throw new Error('ToolBackend.call must be implemented'); }
}

export class SimulatedToolBackend extends ToolBackend {
  constructor(callback, { instructions = '', tools = [], pluginVersion = null } = {}) {
    super();
    if (typeof callback !== 'function') throw new TypeError('Simulated backend requires a tool callback');
    this.mode = 'simulated';
    this.callback = callback;
    this.metadata = { instructions, tools, pluginVersion };
  }
  async initialize() { return this.metadata; }
  call(...args) { return this.callback(...args); }
}

export class LiveToolBackend extends ToolBackend {
  #initialization;
  #tools;
  #rpcId = 0;
  #sessionId;
  #changeSetId;
  #group;
  #operations = new Set();
  #lastPoll = null;
  #pollQueue = Promise.resolve();

  get ownership() {
    return { sessionId: this.#sessionId, changeSetId: this.#changeSetId };
  }

  effectiveArguments(name, args) {
    return name === 'start_operation' && this.#changeSetId
      ? { ...args, ...this.ownership } : args;
  }

  constructor({ env = process.env, request, now = Date.now, sleep = delay } = {}) {
    super();
    this.mode = 'live';
    if (!request && (!env.JIRA_BASE_URL || !env.JIRA_TOKEN)) {
      throw new InfrastructureError('Live backend requires JIRA_BASE_URL and JIRA_TOKEN (the MCP endpoint accepts only Bearer PAT)');
    }
    this.request = request ?? jiraClient({
      baseUrl: env.JIRA_BASE_URL, username: env.JIRA_USERNAME,
      password: env.JIRA_PASSWORD, token: env.JIRA_TOKEN,
    });
    this.now = now;
    this.sleep = sleep;
  }

  async #request(resource, options) {
    try { return await this.request(resource, options); }
    catch (cause) {
      const httpStatus = /HTTP \d{3}/u.exec(cause.message ?? '')?.[0];
      throw new InfrastructureError(`Live Jira request failed: ${resource.split('?')[0]}${httpStatus ? `: ${httpStatus}` : ''}`, { cause });
    }
  }

  async #rpc(method, params = {}) {
    const id = ++this.#rpcId;
    const reply = await this.#request(MCP, { method: 'POST', body: { jsonrpc: '2.0', id, method, params } });
    if (!object(reply) || reply.jsonrpc !== '2.0' || reply.id !== id || reply.error || !Object.hasOwn(reply, 'result')) {
      throw new InfrastructureError(`Invalid or failed MCP ${method} response`);
    }
    return reply.result;
  }

  initialize() {
    this.#initialization ??= this.#initialize();
    return this.#initialization;
  }

  async #initialize() {
    const status = await this.#request('/status');
    if (status?.state !== 'RUNNING') throw new InfrastructureError('Local Jira is not RUNNING');
    const plugin = await this.#request('/rest/plugins/1.0/ru.rasulov.control-key', {
      headers: { accept: 'application/vnd.atl.plugins.plugin+json' },
    });
    if (plugin?.key !== 'ru.rasulov.control' || plugin.enabled !== true) {
      throw new InfrastructureError('The ru.rasulov.control Jira plugin is not enabled');
    }
    const initialized = await this.#rpc('initialize', {
      protocolVersion: '2025-03-26', capabilities: {},
      clientInfo: { name: 'control-dialogue-eval', version: '1.0' },
    });
    if (typeof initialized?.instructions !== 'string' || !initialized.instructions.trim() ||
        !identity(initialized?.serverInfo?.version)) {
      throw new InfrastructureError('MCP initialize did not return production instructions and plugin version');
    }
    const listed = await this.#rpc('tools/list');
    if (!Array.isArray(listed?.tools) || listed.tools.some((tool) =>
      !identity(tool?.name) || typeof tool.description !== 'string' || !object(tool.inputSchema))) {
      throw new InfrastructureError('MCP tools/list returned an invalid tool catalog');
    }
    const tools = listed.tools.filter((tool) => !INTERNAL_TOOLS.has(tool.name));
    this.#tools = new Map(tools.map((tool) => [tool.name, tool]));
    if (!this.#tools.has('start_operation') || !this.#tools.has('get_operation')) {
      throw new InfrastructureError('MCP catalog does not expose operation lifecycle');
    }
    return { instructions: initialized.instructions, tools, pluginVersion: initialized.serverInfo.version };
  }

  async #tool(name, args) {
    const result = await this.#rpc('tools/call', { name, arguments: args });
    if (!object(result) || !Array.isArray(result.content) || result.content.length === 0) {
      throw new InfrastructureError(`MCP tool ${name} returned invalid content`);
    }
    const values = result.content.map((content) => {
      if (content?.type !== 'text' || typeof content.text !== 'string') {
        throw new InfrastructureError(`MCP tool ${name} returned non-text content`);
      }
      try { return JSON.parse(content.text); }
      catch (cause) {
        if (result.isError === true) return content.text;
        throw new InfrastructureError(`MCP tool ${name} returned invalid JSON content`, { cause });
      }
    });
    const value = values.length === 1 ? values[0] : values;
    if (result.isError === true) throw new ToolExecutionError(name, value);
    return value;
  }

  async #ensureGroup() {
    this.#group ??= (async () => {
      const session = await this.#request(`${CHANGES}/sessions`, { method: 'POST', body: {} });
      if (!identity(session?.sessionId)) throw new InfrastructureError('Session creation returned no identity');
      this.#sessionId = session.sessionId;
      const group = await this.#request(`${CHANGES}/sessions/${encodeURIComponent(this.#sessionId)}/sets`, {
        method: 'POST', body: {},
      });
      if (!identity(group?.changeSetId)) throw new InfrastructureError('Group creation returned no identity');
      this.#changeSetId = group.changeSetId;
    })();
    await this.#group;
  }

  async call(name, args = {}) {
    await this.initialize();
    if (!this.#tools.has(name)) throw new Error(`Tool is not exposed by the live backend: ${name}`);
    if (!object(args)) throw new TypeError('Tool arguments must be an object');
    if (name === 'start_operation') {
      // Never adopt model-provided journal identities, including before the first start.
      if (Object.hasOwn(args, 'sessionId') !== Object.hasOwn(args, 'changeSetId') ||
          (Object.hasOwn(args, 'sessionId') && (!identity(args.sessionId) || args.sessionId !== this.#sessionId)) ||
          (Object.hasOwn(args, 'changeSetId') && (!identity(args.changeSetId) || args.changeSetId !== this.#changeSetId))) {
        throw new Error('Foreign session/group identity rejected');
      }
      await this.#ensureGroup();
      const result = await this.#tool(name, { ...args, sessionId: this.#sessionId, changeSetId: this.#changeSetId });
      if (!identity(result?.operationId)) throw new InfrastructureError('Operation start returned no identity; do not resubmit');
      this.#operations.add(result.operationId);
      return result;
    }
    if (name === 'get_operation') {
      if (!this.#operations.has(args.operationId)) throw new Error('Foreign operation identity rejected');
      const poll = this.#pollQueue.then(async () => {
        if (this.#lastPoll !== null) {
          const remaining = 1000 - (this.now() - this.#lastPoll);
          if (remaining > 0) await this.sleep(remaining);
        }
        this.#lastPoll = this.now();
        return this.#tool(name, args);
      });
      this.#pollQueue = poll.then(() => undefined, () => undefined);
      return poll;
    }
    return this.#tool(name, args);
  }
}

export function createToolBackend({ mode = process.env.EVAL_BACKEND || 'simulated', simulated, ...options } = {}) {
  if (mode === 'simulated') return new SimulatedToolBackend(simulated, options);
  if (mode === 'live') return new LiveToolBackend(options);
  throw new Error(`Invalid EVAL_BACKEND: ${mode}; expected simulated or live`);
}
