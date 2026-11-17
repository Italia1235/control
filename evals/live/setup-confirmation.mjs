import { createHash } from 'node:crypto';

export function canonical(value) {
  if (value === undefined) return null;
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
export const payloadHash = value => createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
const running = new Set(['QUEUED', 'RUNNING', 'CANCEL_REQUESTED']);
const failed = new Set(['FAILED', 'PARTIAL', 'BLOCKED', 'CANCELLED', 'NOT_ATTEMPTED', 'UNKNOWN', 'PREVIEW_STALE']);
const domain = value => Array.isArray(value) ? value.map(domain) : value && typeof value === 'object'
  ? Object.fromEntries(Object.entries(value).filter(([key]) => key !== 'planId').map(([key, child]) => [key, domain(child)])) : value;
const safe = value => {
  if (value && typeof value === 'object') for (const [key, child] of Object.entries(value)) {
    if (/^(authorization|password|credentials|accessToken|apiToken)$/iu.test(key)) throw new Error('Credentials cannot enter setup evidence');
    safe(child);
  }
};
const clone = value => JSON.parse(JSON.stringify(value));
function shownConsequences(name, preview) {
  const displayed = domain(preview);
  // Every domain field is shown losslessly, including unknown future fields; a summary is never an approval surface.
  const consequences = `| Change | Target and effects |\n| --- | --- |\n| ${name} | Exact complete domain preview below; all global/shared effects, warnings and assumptions are included. No effects beyond this preview are authorized. |\n\n`
    + `\`\`\`json\n${JSON.stringify(canonical(displayed), null, 2)}\n\`\`\`\n\nApprove this exact setup operation within the authorized fixture scope?`;
  return { displayed, consequences };
}

/** Same guarded surface is used for confirmed setup and its read-only verifier. */
export function readOnlyAccess({ call, rest, adminPage, allowed }) {
  const tool = (name, args) => {
    if (!allowed.has(name) || !/^(get_|list_|plan_)/u.test(name)) throw new Error(`Mutation bypass forbidden: ${name}`);
    return call(name, args);
  };
  return {
    tool, probe: tool,
    rest(method, route, body) {
      if (method !== 'GET' || body !== undefined) throw new Error('REST is GET-only');
      return rest(method, route);
    },
    adminPage(route, params) {
      if (params !== undefined) throw new Error('adminPage is GET-only');
      return adminPage(route);
    },
  };
}
const LOCAL013_RULES = runId => `You are the user authorizing setup for LOCAL-013 run ${runId}.
Distinguish creation of fresh fixture objects, references that leave existing objects unchanged, and changes to foreign configuration:
- Creation: only fresh L013-${runId}- statuses, workflows, transitions/rules, screens/tabs, custom fields/contexts and fixture projects (key L013${runId.toUpperCase()}) are in scope. A newly created status or custom field is global by Jira design; global catalog visibility alone is not a change to foreign configuration. A new field's context must remain limited to this run's fresh project, never all projects.
- Project creation: create_project with action "create" and templateKey com.atlassian.jira-core-project-templates:jira-core-project-management creates a new BUSINESS project using Jira's standard Project Management template. The template may attach that new project to existing default/shared schemes; those references are permitted, not a promise of cloned/private schemes or restricted project visibility. Editing shared scheme definitions, issue-type mappings, defaults or other projects' associations is forbidden. No issues are requested by this setup; later workflow assignment must independently establish zero issues and use a new private scheme. Do not require create_project to prove that every template scheme is private.
- Screens: creating a fresh standalone screen with its own tab and field layout, without assigning it to any project, scheme or workflow, is permitted. A standalone screen need not be project-scoped. Later referencing that screen from this run's fresh workflow is permitted; changing a foreign screen or its assignments is not.
- References: selecting an existing active user as lead of the new fixture project changes only that new project, not the user. Existing field types/searchers, system fields (such as comment or summary), Resolutions, rule templates and ScriptRunner may be referenced in the new fixtures without changing those catalog objects, users, plugins or their global settings. Listing a field on a new screen or using it in a new workflow rule does not change that field's global configuration or required flag.
- Workflow creation: configure_workflow.create builds a new unassigned workflow; its generated INITIAL Create action includes Jira's native PermissionValidator for Create Issue, reported as one OTHER/unrecognized validator by the rule reader. This built-in permission check is part of creating the fixture, not an added arbitrary script or an issue creation request. This explanation applies only to that generated initial action, not to OTHER rules on other transitions or existing workflows. Configuring a rule does not execute a transition or create issues; warnings about field availability still describe the fixture's limitations and must be considered.
- Fixture linkage: references to objects created earlier in this run are permitted. Workflow assignment is permitted only for this run's fresh workflow to this run's fresh empty project using a new private workflow scheme; never alter a scheme shared with foreign projects. All issue types means only those of that fixture project, not all Jira projects. A new workflow may reference an existing Resolution without creating or editing that global Resolution.
- Forbidden: changing foreign objects/configuration, reusing or modifying fixtures left by another run, issues or issue migration, workflow publication, global catalog updates, user/account changes, cleanup, rollback and mutation bypass. A fresh name alone does not authorize a foreign/shared mutation or a broader field context.
`;
const APPROVAL_TAIL = `
Read currentPreview, its global/shared effects and warnings for this operation. completedSetupOperations contains only successful terminal results from separately confirmed earlier operations in this run; use it to identify fixture IDs, not as approval for this preview. Approve only this preview's effects within these boundaries, never unseen later steps. Prior approval does not approve the next preview. If effects exceed these boundaries or safety cannot be established, REJECT; do not assume approval is required to finish setup.
Reply only JSON {"decision":"APPROVE"|"REJECT","message":string}.`;
/** `scope.rules(runId)` replaces the default fixture boundaries for another scenario; the protocol tail is shared. */
export const approvalPrompt = (scope, runId) => `${(scope?.rules ?? LOCAL013_RULES)(runId).trimEnd()}\n${APPROVAL_TAIL.trimStart()}`;
const FENCED = /^\s*```(?:json)?\s*([\s\S]*?)\s*```\s*$/u;
/** Strict envelope parse; exactly one surrounding markdown code fence (``` or ```json) is tolerated, nothing else. Returns null when not valid JSON. */
export function parseUserEnvelope(text) {
  if (typeof text !== 'string') return null;
  const fenced = FENCED.exec(text);
  try { return JSON.parse(fenced ? fenced[1] : text); } catch { return null; }
}
/** Opt-in only. Persistence is awaited at every boundary, especially approval and intent before a native start. */
export function createConfirmedSetup({ call, approve, persist, runId, model, scope = null, timeoutMs = 300_000, pollMs = 1000 }) {
  const trace = [];
  const completedSetupOperations = [];
  let conversation = '# Setup confirmation\n', stopped = false, busy = false;
  const save = () => persist({ trace: clone(trace), conversation });

  async function event(event, facts = {}) {
    trace.push({ index: trace.length, timestamp: new Date().toISOString(), event, ...clone(facts) });
    await save();
  }
  async function bounded(promise, label) {
    let timer;
    try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`${label} timeout`)), timeoutMs); })]); }
    finally { clearTimeout(timer); }
  }
  return {
    trace,
    get conversation() { return conversation; },
    initialize: save,
    async complete() { if (stopped || busy) throw new Error('Setup did not complete'); stopped = true; await event('setup_completed'); },
    async apply(name, args) {
      if (stopped || busy) throw new Error('Setup stopped or concurrent apply is forbidden');
      busy = true;
      try {
        safe(args);
        const request = clone({ ...args, dryRun: true });
        if (Object.hasOwn(request, 'planId')) throw new Error('Setup must obtain a fresh domain preview');
        const preview = clone(await bounded(call(name, request), 'preview'));
        safe(preview);
        const binding = { toolName: name, arguments: request, preview };
        const hash = payloadHash(binding), planId = preview?.planId;
        const { displayed, consequences } = shownConsequences(name, preview);
        conversation += `\n## Assistant\n\n${consequences}\n`;
        await event('preview', { toolName: name, arguments: request, response: preview, hash, planId, displayed, consequences });
        if (typeof planId !== 'string' || !planId || preview.errorCode || failed.has(preview.status)) throw new Error('No successful domain preview');
        if (!model) throw new Error('A configured simulated-user model is required');
        const reply = await bounded(approve({ model, systemPrompt: approvalPrompt(scope, runId), request: { completedSetupOperations: clone(completedSetupOperations), requestedOperation: { toolName: name, arguments: domain(request) }, currentPreview: consequences } }), 'user model');
        safe(reply);
        conversation += `\n## User envelope (${reply.model})\n\n${reply.text}\n`;
        await event('user_response', { hash, planId, toolName: name, raw: reply.text, model: reply.model });
        const answer = parseUserEnvelope(reply.text);
        if (answer === null) throw new Error('Invalid simulated-user envelope');
        if (!answer || Object.keys(answer).sort().join(',') !== 'decision,message' || !['APPROVE', 'REJECT'].includes(answer.decision)
            || typeof answer.message !== 'string' || !answer.message.trim() || typeof reply.model !== 'string' || reply.model !== model) throw new Error('Invalid simulated-user envelope or actual model');
        conversation += `\n## User (${reply.model})\n\n${answer.message}\n`;
        await event('approval', { hash, planId, toolName: name, decision: answer.decision, message: answer.message, model: reply.model, raw: reply.text });
        if (answer.decision !== 'APPROVE') throw new Error('Setup rejected');
        const startArgs = { toolName: name, arguments: { dryRun: false, planId } };
        await event('start_operation', { hash, planId, arguments: startArgs, recovery: false });
        let operation;
        try { operation = await bounded(call('start_operation', clone(startArgs)), 'start_operation'); }
        catch (error) {
          const operationId = error.operationId;
          await event('start_response_lost', { hash, planId, ...(operationId ? { operationId } : {}), error: error.message });
          if (operationId) {
            await event('get_operation', { hash, planId, arguments: { operationId } });
            operation = await bounded(call('get_operation', { operationId }), 'get_operation');
            if (operation?.operationId !== operationId) throw new Error('Operation binding changed');
          } else {
            await event('start_operation', { hash, planId, arguments: startArgs, recovery: true });
            operation = await bounded(call('start_operation', clone(startArgs)), 'start_operation recovery');
          }
        }
        await event('operation', { hash, planId, response: operation, operationId: operation?.operationId });
        if (typeof operation?.operationId !== 'string' || !operation.operationId) throw new Error('Missing operation binding');
        const deadline = Date.now() + timeoutMs;
        while (running.has(operation?.status)) {
          if (!operation.operationId || Date.now() >= deadline) throw new Error('Setup operation timeout or missing operationId');
          await new Promise(resolve => setTimeout(resolve, pollMs));
          const operationId = operation.operationId;
          await event('get_operation', { hash, planId, arguments: { operationId } });
          operation = await bounded(call('get_operation', { operationId }), 'get_operation');
          await event('operation', { hash, planId, response: operation, operationId: operation?.operationId });
          if (operation?.operationId !== operationId) throw new Error('Operation binding changed');
        }
        if (operation?.status !== 'COMPLETED' || !operation.result || operation.result.errorCode || failed.has(operation.result.status) || failed.has(operation.result.outcome)) throw new Error(`Setup operation did not succeed: ${operation?.status}/${operation?.result?.status}`);
        safe(operation.result);
        const completed = { toolName: name, result: domain(operation.result) };
        completedSetupOperations.push(clone(completed));
        conversation += `\n## Completed setup operation\n\n\`\`\`json\n${JSON.stringify(completed, null, 2)}\n\`\`\`\n`;
        await save();
        return operation.result;
      } catch (error) {
        stopped = true;
        await event('setup_failed', { error: error.message });
        throw error;
      } finally { busy = false; }
    },
  };
}

/** Regrade uses saved facts only. Presentation quality is deliberately not a contract assertion. */
export function checkSetupProtocol(trace, conversation) {
  const errors = [], plans = new Map();
  if (!Array.isArray(trace)) return [{ name: 'Setup confirmation protocol', class: 'contract', ok: false, detail: 'Missing setup trace' }];
  for (const [index, entry] of trace.entries()) {
    if (entry.index !== index || typeof entry.timestamp !== 'string') errors.push('Invalid event order');
    if (entry.event === 'preview') {
      const hash = payloadHash({ toolName: entry.toolName, arguments: entry.arguments, preview: entry.response });
      const shown = shownConsequences(entry.toolName, entry.response);
      if (hash !== entry.hash || entry.planId !== entry.response?.planId || JSON.stringify(canonical(entry.displayed)) !== JSON.stringify(canonical(shown.displayed))
          || shown.consequences !== entry.consequences || !conversation?.includes(entry.consequences) || plans.has(entry.planId)) errors.push('Changed or incompletely displayed preview');
      plans.set(entry.planId, { preview: entry, reply: null, approval: null, starts: 0, lost: false, operationId: null, terminal: null });
    } else if (entry.event === 'setup_failed') errors.push('Setup failed');
    else if (['user_response', 'approval', 'start_operation', 'start_response_lost', 'get_operation', 'operation'].includes(entry.event)) {
      const plan = plans.get(entry.planId);
      if (!plan || plan.preview.hash !== entry.hash) { errors.push('Missing matching preview'); continue; }
      if (entry.event === 'user_response') {
        if (plan.reply || plan.approval || plan.starts || !conversation?.includes(`## User envelope (${entry.model})\n\n${entry.raw}`)) errors.push('Invalid model response order');
        plan.reply = entry;
      }
      if (entry.event === 'approval') {
        const raw = parseUserEnvelope(entry.raw);
        if (plan.starts || plan.approval || !plan.reply || plan.reply.raw !== entry.raw || plan.reply.model !== entry.model
            || !raw || Object.keys(raw).sort().join(',') !== 'decision,message' || !entry.model || !entry.message?.trim() || raw.message !== entry.message || raw.decision !== entry.decision
            || !conversation?.includes(`## User (${entry.model})\n\n${entry.message}`)) errors.push('Invalid user reply');
        plan.approval = entry;
      }
      if (entry.event === 'start_response_lost') { plan.lost = true; if (entry.operationId) plan.operationId = entry.operationId; }
      if (entry.event === 'start_operation') {
        if (plan.approval?.decision !== 'APPROVE' || JSON.stringify(canonical(entry.arguments)) !== JSON.stringify(canonical({ toolName: plan.preview.toolName, arguments: { dryRun: false, planId: entry.planId } }))
            || (plan.starts === 0 ? entry.recovery : !entry.recovery || !plan.lost || plan.operationId) || ++plan.starts > 2) errors.push('Unauthorized or repeated start');
      }
      if (entry.event === 'get_operation' && (!plan.starts || !plan.operationId || entry.arguments?.operationId !== plan.operationId)) errors.push('Foreign operation polling');
      if (entry.event === 'operation') {
        if (!plan.starts || !entry.operationId || entry.operationId !== entry.response?.operationId || (plan.operationId && plan.operationId !== entry.operationId)) errors.push('Operation binding changed');
        plan.operationId = entry.operationId;
        if (!running.has(entry.response?.status)) plan.terminal = entry.response;
      }
    } else if (entry.event !== 'setup_completed') errors.push('Unknown setup route/event');
  }
  for (const plan of plans.values()) if (!plan.starts || plan.terminal?.status !== 'COMPLETED' || !plan.terminal.result || plan.terminal.result.errorCode || failed.has(plan.terminal.result.status) || failed.has(plan.terminal.result.outcome)) errors.push('Unfinished or unsuccessful setup operation');
  if (trace.at(-1)?.event !== 'setup_completed') errors.push('Setup not completed');
  return [{ name: 'Setup confirmation protocol', class: 'contract', ok: !errors.length, detail: errors.length ? errors.join('; ') : `${plans.size} independently confirmed setup operations` }];
}
