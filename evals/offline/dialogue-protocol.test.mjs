import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { protocolVariants, generateDialogueProtocolScenario, protocolPlanSteps, assertProtocolOfflineConfiguration } from './dialogue-protocol.mjs';
import { protocolMetrics } from './dialogue-protocol-trace.mjs';
import { scenarioSimulator } from './run.mjs';

const exec = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
let nextCall = 0;
const call = (name, args) => ({ role: 'assistant', content: null, tool_calls: [{ id: `protocol-${++nextCall}`,
  type: 'function', function: { name, arguments: JSON.stringify(args) } }] });
const card = (value, prose = '') => ({ role: 'assistant', content: `${prose}\n\n\`\`\`json\n${JSON.stringify(value)}\n\`\`\`` });
const preview = op => call(op.tool, { dryRun: true, ...op.match });
const start = op => call('start_operation', { toolName: op.tool, arguments: { dryRun: false, planId: op.preview.planId } });
const terminal = n => call('get_operation', { operationId: `eval-operation-${String(n).padStart(3, '0')}` });
const explain = steps => steps.map(s => `${s.id}: ${s.state} ${JSON.stringify(s.boundary)}; planId=${s.planId ?? 'not available'}`).join('\n');
const plan = scenario => card({ kind: 'PLAN', steps: protocolPlanSteps(scenario) }, `${explain(protocolPlanSteps(scenario))}\nApprove this whole bounded plan?`);
const delta = scenario => card({ kind: 'DELTA', steps: protocolPlanSteps(scenario, true) }, `New shared-project effect, stop writes.\n${explain(protocolPlanSteps(scenario, true))}\nApprove the revised remaining plan?`);
const match = op => card({ kind: 'MATCH', id: op.id, boundary: op.preview.boundary }, `Late real preview matches the approved boundary: ${JSON.stringify(op.preview.boundary)}`);
const final = scenario => card({ kind: 'FINAL', completed: scenario.operations.map(op => op.id) }, 'Screen and global standard type created; LAB CREATE mapping assigned. Separate operations are not atomic.');
function script(scenario, violation) {
  const [screen, type, assign] = scenario.operations;
  const messages = [call('list_issue_types', {}), call('get_project_screens', { project: 'LAB' }), preview(screen)];
  if (scenario.variant === 'incomplete') {
    messages.push(card({ kind: 'DECISIONS', decisions: violation === 'serial' ? ['kind'] : ['kind', 'operation'] },
      violation === 'serial' ? '1. Standard or sub-task kind?' : '1. Standard or sub-task kind?\n2. Which operation: CREATE, EDIT, VIEW?'));
    if (violation === 'serial') messages.push(card({ kind: 'DECISIONS', decisions: ['operation'] }, 'Which operation: CREATE, EDIT, VIEW?'));
  }
  messages.push(preview(type));
  if (violation === 'known') messages.push(card({ kind: 'KNOWN_FACT', fact: 'screenSchemeId' }, 'What is the LAB screen scheme ID, already returned by get_project_screens?'));
  if (violation === 'before_approval') return [...messages, start(screen)];
  messages.push(plan(scenario));
  if (violation === 'each_step') messages.push(card({ kind: 'CONFIRM_STEP', id: 'screen' }, 'Confirm the screen step again?'));
  messages.push(start(screen), terminal(1));
  if (violation === 'each_step') messages.push(card({ kind: 'CONFIRM_STEP', id: 'type' }, 'Confirm the type step again?'));
  // Outside-plan coverage deliberately leaves this independent write pending when the delta is discovered.
  if (scenario.variant !== 'finding_outside') messages.push(start(type), terminal(2));
  messages.push(preview(assign));
  if (scenario.variant === 'finding_outside') {
    if (violation === 'outside') return [...messages, start(type)];
    messages.push(call('get_project_screens', { project: 'OTHER' }), delta(scenario), start(type), terminal(2));
  } else messages.push(match(assign));
  if (violation === 'each_step') messages.push(card({ kind: 'CONFIRM_STEP', id: 'assign' }, 'Confirm the assignment step again?'));
  messages.push(start(assign), terminal(3), final(scenario));
  return messages;
}
async function runStub(scenario, messages) {
  let requests = 0, directory;
  const errors = [];
  const server = http.createServer(async (request, response) => {
    try {
      assert.equal(request.url, '/chat/completions');
      let text = '';
      for await (const chunk of request) text += chunk;
      const body = JSON.parse(text);
      assert.equal(body.model, 'offline-dialogue-protocol');
      assert.ok(Array.isArray(body.tools), 'No synthetic-user/judge transport call is permitted');
      assert.match(body.messages[0].content, /OFFLINE PROPOSAL CONTRACT ONLY/u);
      const message = messages[requests++];
      assert.ok(message, 'Unexpected extra assistant request');
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ choices: [{ message }], usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } }));
    } catch (error) {
      errors.push(error.message);
      response.writeHead(400).end(JSON.stringify({ error: 'Offline protocol stub rejected request' }));
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const env = { ...process.env, EVAL_DIALOGUE_PROTOCOL_SCRIPTED: '1', EVAL_API_KEY: 'offline-only',
      EVAL_ASSISTANT_MODEL: 'offline-dialogue-protocol', EVAL_USER_MODEL: 'forbidden-user', EVAL_JUDGE_MODEL: 'forbidden-judge',
      EVAL_BASE_URL: `http://127.0.0.1:${server.address().port}`, EVAL_BACKEND: 'simulated',
      // No tokens are purchased; leave real soft-budget/request-count policy unchanged.
      EVAL_TOKEN_LIMIT_MODE: 'hard', EVAL_MAX_TOKENS_PER_RUN: '' };
    let stdout = '', stderr = '', code = 0;
    try { ({ stdout } = await exec(process.execPath, ['evals/offline/run.mjs', scenario.scenario], { cwd: ROOT, env, timeout: 20000, maxBuffer: 2 * 1024 * 1024 })); }
    catch (error) { stdout = error.stdout ?? ''; stderr = error.stderr ?? ''; code = error.code; }
    const artifact = stdout.match(/Артефакты:\s+(.+)$/mu);
    assert.ok(artifact, stdout + stderr);
    directory = path.resolve(ROOT, artifact[1].trim());
    const verdict = JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8'));
    const trace = JSON.parse(await readFile(path.join(directory, 'tool-trace.json'), 'utf8'));
    assert.deepEqual(errors, []);
    return { verdict, trace, code, requests };
  } finally {
    await new Promise(resolve => server.close(resolve));
    if (directory) await rm(directory, { recursive: true, force: true });
  }
}
for (const variant of protocolVariants) {
  test(`protocol proposal: real runner correct scripted ${variant}`, async t => {
    const scenario = generateDialogueProtocolScenario(variant);
    const result = await runStub(scenario, script(scenario));
    assert.equal(result.code, 0, JSON.stringify(result.verdict));
    assert.equal(result.verdict.passed, true);
    const m = result.verdict.observations;
    assert.equal(m.questions_before_first_write, variant === 'incomplete' ? 3 : 1);
    assert.equal(m.question_turns_count, ['incomplete', 'finding_outside'].includes(variant) ? 2 : 1);
    assert.equal(m.independent_dry_runs_before_first_question, variant === 'incomplete' ? 1 : 2);
    assert.equal(m.reads_before_first_question, 2);
    assert.equal(result.trace.filter(c => c.tool === 'start_operation').length, 3);
    assert.equal(result.trace.filter(c => c.arguments?.dryRun === true).length, 3);
    t.diagnostic(JSON.stringify({ scenario: scenario.scenario, metrics: m, scriptedRequests: result.requests, externalLLMCalls: 0, jiraCalls: 0 }));
  });
}
for (const [violation, variant, failedCheck] of [
  ['serial', 'incomplete', 'decisions_consolidated'],
  ['each_step', 'complete', 'no_per_step_confirmation'],
  ['before_approval', 'complete', 'writes_after_explicit_plan_approval'],
  ['known', 'complete', 'no_known_fact_question'],
  ['outside', 'finding_outside', 'late_comparison_and_delta'],
]) {
  test(`protocol proposal: real runner rejects ${violation}`, async () => {
    const scenario = generateDialogueProtocolScenario(variant);
    const result = await runStub(scenario, script(scenario, violation));
    assert.equal(result.code, 1, JSON.stringify(result.verdict));
    assert.equal(result.verdict.checks[failedCheck], false);
    if (['before_approval', 'outside'].includes(violation)) {
      assert.equal(result.trace.at(-1).result.errorCode, 'EVAL_CONFIRMATION_REQUIRED');
      assert.equal(result.trace.filter(c => c.tool === 'start_operation' && c.result.status === 'QUEUED').length, violation === 'outside' ? 1 : 0);
    } else assert.equal(result.verdict.checks.terminal_all_steps, true, 'Violation remains a failure even when all operations complete');
  });
}
function metricFixture(scenario, specs) {
  return specs.flatMap(spec => {
    if (spec.role) return [spec];
    const message = call(spec.tool, spec.args);
    return [message, { role: 'tool', tool_call_id: message.tool_calls[0].id, content: JSON.stringify(spec.result ?? {}) }];
  });
}
const reads = new Set(['get_project_screens', 'list_issue_types', 'get_operation']);
test('protocol metrics: no question/no write use full trace and null write denominator', () => {
  const scenario = generateDialogueProtocolScenario('complete');
  const messages = metricFixture(scenario, [
    { tool: 'list_issue_types', args: {} }, { tool: 'get_project_screens', args: { project: 'LAB' } },
    { tool: scenario.operations[0].tool, args: { dryRun: true, ...scenario.operations[0].match }, result: scenario.operations[0].preview },
  ]);
  assert.deepEqual(protocolMetrics({ scenario, modelMessages: messages, readTools: reads }), {
    questions_before_first_write: null, question_turns_count: 0, independent_dry_runs_before_first_question: 1,
    reads_before_first_question: 2, no_question: true, no_write: true,
  });
});
test('protocol metrics: failed/duplicate/dependent dry-runs do not inflate independent coverage', () => {
  const scenario = generateDialogueProtocolScenario('complete');
  const [screen, type, assign] = scenario.operations;
  const messages = metricFixture(scenario, [
    { tool: screen.tool, args: { dryRun: true, ...screen.match }, result: screen.preview },
    { tool: screen.tool, args: { dryRun: true, ...screen.match }, result: screen.preview },
    { tool: type.tool, args: { dryRun: true, ...type.match }, result: { status: 'FAILED' } },
    { tool: assign.tool, args: { dryRun: true, ...assign.match, requiresApplied: [] }, result: assign.preview },
    { tool: assign.tool, args: { dryRun: true, ...assign.match }, result: assign.preview },
    { tool: 'get_project_screens', args: { project: 'LAB' } }, plan(scenario),
    { role: 'user', content: 'Yes, approve.' }, { tool: 'list_issue_types', args: {} },
  ]);
  const m = protocolMetrics({ scenario, modelMessages: messages, readTools: reads });
  assert.equal(m.independent_dry_runs_before_first_question, 1);
  assert.equal(m.reads_before_first_question, 1, 'Previews are not factual reads; post-question reads are excluded');
  assert.equal(m.questions_before_first_write, null);
});
test('protocol metrics: user decision deps come from scenario, not scripted call labels', () => {
  const scenario = generateDialogueProtocolScenario('incomplete');
  const type = scenario.operations[1];
  const messages = metricFixture(scenario, [{ tool: type.tool, args: { dryRun: true, ...type.match }, result: type.preview }]);
  assert.equal(protocolMetrics({ scenario, modelMessages: messages, readTools: reads }).independent_dry_runs_before_first_question, 0);
});
test('protocol metrics: question turns group messages between user replies; no-question write gives zero', () => {
  const scenario = generateDialogueProtocolScenario('complete');
  const messages = metricFixture(scenario, [
    { role: 'user', content: 'request' }, card({ kind: 'DECISIONS' }, 'Kind?'), card({ kind: 'DECISIONS' }, 'Scope?'),
    { role: 'user', content: 'answer, not approval' }, plan(scenario), { role: 'user', content: 'Yes.' },
    { tool: 'start_operation', args: { toolName: 'create_screen', arguments: { dryRun: false, planId: 'x' } } },
  ]);
  const m = protocolMetrics({ scenario, modelMessages: messages, readTools: reads });
  assert.equal(m.question_turns_count, 2);
  assert.equal(m.questions_before_first_write, 3, 'Two decision questions and plan approval are distinct from two question turns');
  const bare = protocolMetrics({ scenario, modelMessages: metricFixture(scenario, [{ tool: 'create_screen', args: { dryRun: false, planId: 'x' } }]), readTools: reads });
  assert.equal(bare.questions_before_first_write, 0);
  assert.equal(bare.no_question, true);
  assert.equal(bare.no_write, false);
});
test('protocol simulator: dependent preview cannot precede observed prerequisite or use a pending plan as token', () => {
  const scenario = generateDialogueProtocolScenario('complete'), sim = scenarioSimulator(scenario);
  const [screen, , assign] = scenario.operations;
  assert.equal(sim(assign.tool, { dryRun: true, ...assign.match }).errorCode, 'EVAL_DEPENDENCY_NOT_APPLIED');
  assert.equal(sim('start_operation', { toolName: assign.tool, arguments: { dryRun: false, planId: assign.preview.planId } }).errorCode, 'PREVIEW_STALE');
  assert.equal(sim(screen.tool, { dryRun: false, planId: screen.preview.planId }).errorCode, 'EVAL_UNMANAGED_APPLY');
});
test('legacy simulator: opt-in proposal does not relax old late-preview approval chronology', () => {
  const scenario = generateDialogueProtocolScenario('complete');
  delete scenario.validation;
  const sim = scenarioSimulator(scenario), screen = scenario.operations[0];
  const conversation = [{ role: 'assistant', turn: 1, content: 'Plan.' }, { role: 'user', turn: 2, content: 'Yes.' }];
  sim(screen.tool, { dryRun: true, ...screen.match }, { assistantTurn: 2, userTurn: 2, conversation });
  assert.equal(sim('start_operation', { toolName: screen.tool, arguments: { dryRun: false, planId: screen.preview.planId } },
    { assistantTurn: 2, userTurn: 2, conversation }).errorCode, 'EVAL_CONFIRMATION_REQUIRED');
});
test('protocol scenarios: non-scripted or non-loopback transport is rejected before any network call', () => {
  assert.throws(() => assertProtocolOfflineConfiguration({}), /offline-only/u);
  for (const url of ['https://api.openai.com/v1', 'http://example.com:80', 'http://127.0.0.1.evil:1234']) {
    assert.throws(() => assertProtocolOfflineConfiguration({ EVAL_DIALOGUE_PROTOCOL_SCRIPTED: '1', EVAL_BACKEND: 'simulated',
      EVAL_API_KEY: 'offline-only', EVAL_ASSISTANT_MODEL: 'offline-dialogue-protocol', EVAL_BASE_URL: url }), /offline-only/u);
  }
});

test('protocol simulator: malformed approved boundaries cannot authorize a step', () => {
  for (const mutate of [
    steps => { steps[0].boundary.targets = ['Invented screen']; },
    steps => { steps[1].boundary.affectedProjects = ['LAB']; },
    steps => { steps[2].boundary.warnings = ['Pretend this was disclosed']; },
  ]) {
    const scenario = generateDialogueProtocolScenario('complete'), sim = scenarioSimulator(scenario);
    for (const op of scenario.operations.slice(0, 2)) sim(op.tool, { dryRun: true, ...op.match });
    const steps = structuredClone(protocolPlanSteps(scenario));
    mutate(steps);
    const conversation = [{ ...card({ kind: 'PLAN', steps }, 'Approve?'), turn: 1 }, { role: 'user', turn: 2, content: 'Yes.' }];
    const screen = scenario.operations[0];
    const result = sim('start_operation', { toolName: screen.tool, arguments: { dryRun: false, planId: screen.preview.planId } }, { conversation, userTurn: 2 });
    assert.equal(result.errorCode, 'EVAL_CONFIRMATION_REQUIRED');
  }
});

test('protocol simulator: late binding uses observed prerequisite output, not a future ID expectation', () => {
  const scenario = generateDialogueProtocolScenario('complete');
  // Keep the scripted late preview unchanged but return a different actual ID from creation.
  scenario.operations[0].apply.screenId = '20999';
  const sim = scenarioSimulator(scenario), [screen, type, assign] = scenario.operations;
  sim(screen.tool, { dryRun: true, ...screen.match });
  sim(type.tool, { dryRun: true, ...type.match });
  const conversation = [{ ...plan(scenario), turn: 1 }, { role: 'user', turn: 2, content: 'Yes.' }];
  const context = { conversation, userTurn: 2, assistantTurn: 2 };
  const started = sim('start_operation', { toolName: screen.tool, arguments: { dryRun: false, planId: screen.preview.planId } }, context);
  assert.equal(started.status, 'QUEUED');
  assert.equal(sim(assign.tool, { dryRun: true, ...assign.match }, context).errorCode, 'EVAL_DEPENDENCY_NOT_APPLIED', 'QUEUED is not observed completion');
  const observed = sim('get_operation', { operationId: started.operationId }, context);
  assert.equal(observed.result.screenId, '20999');
  assert.equal(sim(assign.tool, { dryRun: true, ...assign.match }, context).planId, assign.preview.planId);
  assert.equal(sim('start_operation', { toolName: assign.tool, arguments: { dryRun: false, planId: assign.preview.planId } }, context).errorCode,
    'EVAL_CONFIRMATION_REQUIRED', 'Expected future ID 10200 must not override actual returned ID 20999');
  assert.deepEqual(sim('start_operation', { toolName: screen.tool, arguments: { dryRun: false, planId: screen.preview.planId } }, context),
    { operationId: started.operationId, status: 'COMPLETED', alreadyStarted: true }, 'Recovery reads the same operation, not a second mutation');
});
