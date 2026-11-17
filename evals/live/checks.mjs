// Structural checks over tool-trace.json and the visible conversation: the protocol rules every live dialogue must
// follow, whatever the scenario. A check is { name, ok, detail, class? }. Scenario-specific checks live in
// scenarios/<name>/verify.mjs and use the helpers below.

import { readdirSync, readFileSync } from 'node:fs';
import { checkSetupProtocol } from './setup-confirmation.mjs';

export function setupProtocolChecks(trace, conversation, mainTrace = []) {
  const checks = checkSetupProtocol(trace, conversation);
  const setupIds = new Set((trace ?? []).filter(entry => entry.event === 'preview').map(entry => entry.planId));
  checks.push({ name: 'Setup and main operations are separate', class: 'contract',
    ok: !mainTrace.some(entry => setupIds.has(entry.arguments?.arguments?.planId)),
    detail: 'Main trace must not replay a setup plan' });
  return checks;
}

const CATALOG_DIR = new URL('../../src/main/resources/llm/', import.meta.url);

/** Names of every tool the plugin publishes to the model (src/main/resources/llm/*-tools.json). */
export const PLUGIN_TOOLS = new Set(readdirSync(CATALOG_DIR).filter((file) => file.endsWith('-tools.json'))
  .flatMap((file) => JSON.parse(readFileSync(new URL(file, CATALOG_DIR), 'utf8')).map((tool) => tool.name)));

const CONFIRMATION = /^\s*(да|yes|ок|ok|подтверждаю|применяй)(?=[\s,.!:;)]|$)/iu;

/** The JSON object inside a tool result (error results are prefixed with "Error: "); null when there is none. */
export function parseResult(text) {
  const raw = typeof text === 'string' ? text : JSON.stringify(text ?? null);
  try { return JSON.parse(raw); } catch { /* fall through to the embedded object */ }
  const embedded = /\{[\s\S]*\}/u.exec(raw);
  try { return embedded ? JSON.parse(embedded[0]) : null; } catch { return null; }
}

/** Preview calls (dryRun:true with a planId in the answer), keyed by planId. */
export function previews(trace) {
  const byPlan = new Map();
  for (const entry of trace) {
    if (entry.arguments?.dryRun !== true) continue;
    const planId = parseResult(entry.result)?.planId;
    if (planId) byPlan.set(planId, entry);
  }
  return byPlan;
}

/** start_operation calls with their preview: { start, toolName, planId, preview }. Optionally only for one tool. */
export function applies(trace, toolName) {
  const byPlan = previews(trace);
  return trace
    .filter((entry) => entry.tool === 'start_operation')
    .map((start) => ({ start, toolName: start.arguments?.toolName, planId: start.arguments?.arguments?.planId, preview: byPlan.get(start.arguments?.arguments?.planId) }))
    .filter((apply) => !toolName || apply.toolName === toolName);
}

const check = (name, failures, detail) => ({ name, ok: failures.length === 0, detail: failures.length ? failures.join('; ') : detail });

/** User messages in order with whether the assistant answered; in a live run the t-th user message opens turn t. */
function userMessages(conversation) {
  return conversation.map((entry, index) => ({ entry, next: conversation[index + 1] }))
    .filter(({ entry }) => entry.role === 'user')
    .map(({ entry, next }) => ({ text: entry.content ?? '', answered: next?.role === 'assistant' }));
}

export function traceChecks(trace, conversation = [], { classifyPresentation = false } = {}) {
  const emit = (name, failures, detail, classification = 'contract') => {
    const result = check(name, failures, detail);
    return classifyPresentation ? { ...result, class: classification } : result;
  };
  const all = applies(trace);
  const seen = new Map();
  const repeated = [];
  for (const { planId, start } of all) {
    const earlier = seen.get(planId);
    if (earlier) {
      const first = parseResult(earlier.result);
      const recovered = parseResult(start.result);
      const lost = earlier.isError === true || first?.errorCode === 'TOOL_ERROR' || earlier.result == null;
      if (!lost || recovered?.alreadyStarted !== true || !recovered.operationId) repeated.push(planId);
    }
    seen.set(planId, start);
  }

  const planIds = [...new Set(trace.map((entry) => parseResult(entry.result)?.planId).filter((id) => typeof id === 'string' && id))];
  const leaks = conversation.map((entry, index) => ({ entry, index }))
    .filter(({ entry }) => entry.role === 'assistant')
    .flatMap(({ entry, index }) => planIds.filter((id) => (entry.content ?? '').includes(id))
      .map((id) => `реплика ${index + 1}: ${id.slice(0, 8)}…`));

  const reasked = [];
  userMessages(conversation).forEach(({ text, answered }, index) => {
    const turn = index + 1;
    if (turn < 2 || !answered || !CONFIRMATION.test(text)) return;
    const shown = new Set([...previews(trace)].filter(([, entry]) => entry.turn === turn - 1).map(([planId]) => planId));
    if (shown.size === 0) return;
    const applied = all.some(({ start, planId }) => start.turn === turn && shown.has(planId));
    const repreviewed = trace.some((entry) => entry.turn === turn && entry.arguments?.dryRun === true);
    if (!applied && !repreviewed) reasked.push(`ход ${turn}: «да» на план хода ${turn - 1}, но ни apply, ни нового превью`);
  });
  const turns = turnTexts(conversation);
  const unapproved = all.filter(({ preview, start }) => {
    if (!preview) return true;
    return (!classifyPresentation && !hasTable(turns.assistant(preview.turn))) ||
      !isYes(turns.user(preview.turn + 1)) || start.turn < preview.turn + 1;
  }).map(({ start, toolName }) => `ход ${start.turn} ${toolName}`);

  return [
    emit('нет прямого dryRun:false вне start_operation',
      trace.filter((entry) => entry.tool !== 'start_operation' && entry.arguments?.dryRun === false).map((entry) => `#${entry.seq} ${entry.tool}`),
      'записи только через start_operation'),
    emit('каждый apply идёт по planId своего превью',
      all.filter(({ preview, toolName, start }) => !preview || preview.tool !== toolName || preview.seq >= start.seq)
        .map(({ start, toolName }) => `#${start.seq} ${toolName}`),
      `${all.length} apply, у каждого есть превью того же инструмента`),
    emit('apply в ходе позже превью (показ и «да» не в одном ходу)',
      all.filter(({ preview, start }) => preview && preview.turn >= start.turn).map(({ start, toolName }) => `ход ${start.turn} ${toolName}`),
      'превью и apply в разных ходах'),
    emit(classifyPresentation ? 'apply подтверждён пользователем после именно этого превью' : 'apply подтверждён пользователем после таблицы именно этого превью', unapproved,
      classifyPresentation ? 'каждому применению предшествует отдельное «да» после его превью' : 'каждому применению предшествуют таблица и отдельное «да»'),
    ...(classifyPresentation ? [emit('Markdown-таблица именно этого превью перед подтверждением',
      all.filter(({ preview }) => preview && !hasTable(turns.assistant(preview.turn))).map(({ start }) => `ход ${start.turn}`),
      'перед подтверждением показана таблица', 'advisory')] : []),
    emit('planId применён не более одного раза', repeated, 'повторных apply нет'),
    emit('только инструменты плагина (нет внешних fetch, web, shell)',
      trace.filter((entry) => !PLUGIN_TOOLS.has(entry.tool)).map((entry) => `#${entry.seq} ${entry.tool}`),
      `${trace.length} вызовов, все из каталога плагина`),
    emit('planId не показан пользователю', leaks, `${planIds.length} planId, ни один не попал в ответы`, 'advisory'),
    emit('после «да» на показанный план нет повторного вопроса', reasked, 'каждое «да» привело к apply или новому превью'),
  ];
}

// --- Options of existing contexts (update_field_contexts contexts[].options, get_field_contexts includeOptions) ---------

const OPTION_KEYS = new Set(['add', 'rename', 'disable', 'enable', 'order']);

/** Previews of update_field_contexts that carry an options block, in call order. */
export function optionPreviews(trace) {
  return [...previews(trace).values()]
    .filter((entry) => entry.tool === 'update_field_contexts' && entry.arguments?.contexts?.some((context) => context.options))
    .sort((a, b) => a.seq - b.seq);
}

/** The options of one context from a get_field_contexts result read with includeOptions=true: [{ id, value, parentId, disabled, default, position }]. */
export function optionItems(result, contextId) {
  const context = result?.contexts?.find((item) => contextId === undefined || String(item.contextId) === String(contextId));
  return context?.optionList?.items ?? [];
}

/** Reads that asked for the options of one context (the only way to learn an optionId). */
export function optionReads(trace) {
  return trace.filter((entry) => entry.tool === 'get_field_contexts' && entry.arguments?.includeOptions === true
    && entry.arguments?.contextId !== undefined && parseResult(entry.result)?.status === 'READ');
}

/** Every optionId the model may legitimately use at a point of the trace: those a read with includeOptions returned before it. */
function knownOptionIds(trace, beforeSeq) {
  const known = new Set();
  for (const read of optionReads(trace)) {
    if (read.seq >= beforeSeq) continue;
    for (const item of optionItems(parseResult(read.result))) known.add(String(item.id));
  }
  return known;
}

/** Calls that delete an option, use a key outside add/rename/disable/enable/order, or name an optionId no read returned. */
export function optionMisuse(trace) {
  const problems = [];
  for (const entry of trace) {
    if (/delete|remove/iu.test(entry.tool ?? '') && /option/iu.test(JSON.stringify(entry.arguments ?? {})))
      problems.push(`#${entry.seq} ${entry.tool}: вызов удаления опции`);
    if (entry.tool !== 'update_field_contexts' || entry.arguments?.dryRun !== true) continue;
    const known = knownOptionIds(trace, entry.seq);
    for (const context of entry.arguments.contexts ?? []) {
      const options = context.options;
      if (!options) continue;
      for (const key of Object.keys(options)) if (!OPTION_KEYS.has(key)) problems.push(`#${entry.seq}: неизвестный ключ options.${key}`);
      const used = [
        ...(options.rename ?? []).map((item) => item.optionId), ...(options.disable ?? []), ...(options.enable ?? []),
        ...(options.add ?? []).map((item) => item.parentOptionId).filter((id) => id !== undefined),
        ...(options.order ?? []).flatMap((item) => [item.parentOptionId, ...(item.optionIds ?? [])]).filter((id) => id !== undefined),
      ];
      for (const id of used) if (!known.has(String(id))) problems.push(`#${entry.seq}: optionId ${id} не возвращался чтением includeOptions`);
    }
  }
  return problems;
}

/** A markdown table: at least a header row and one body row. */
export const hasTable = (text) => (String(text).match(/^\s*\|.*\|\s*$/gmu) ?? []).length >= 3;
/** Question marks in the reply: one preview shows one question. */
export const questionCount = (text) => (String(text).match(/\?/gu) ?? []).length;

/** Every update_field_contexts call with options in preview mode, accepted (planId) or refused. */
export function optionRequests(trace) {
  return trace.filter((entry) => entry.tool === 'update_field_contexts' && entry.arguments?.dryRun === true
    && entry.arguments?.contexts?.some((context) => context.options)).sort((a, b) => a.seq - b.seq);
}

/**
 * The protocol rules every options dialogue must follow: options are read with includeOptions before the first request,
 * no deletion and no guessed optionId, and (unless `preview` is false) every accepted preview of options is shown as one
 * table with exactly one question.
 */
export function optionChecks(trace, conversation = [], { preview = true } = {}) {
  const requested = optionRequests(trace);
  const shown = optionPreviews(trace);
  const reads = optionReads(trace);
  const replyAt = (turn) => turnTexts(conversation).assistant(turn);
  const badReplies = shown.map((entry) => ({ entry, reply: replyAt(entry.turn) }))
    .filter(({ reply }) => !hasTable(reply) || questionCount(reply) !== 1)
    .map(({ entry, reply }) => `ход ${entry.turn}: таблица ${hasTable(reply)}, вопросов ${questionCount(reply)}`);
  const checks = [
    check('опции прочитаны через includeOptions до первого запроса изменения опций',
      requested.length && reads.length && reads[0].seq < requested[0].seq ? [] : ['нет чтения includeOptions=true до запроса'],
      `чтение #${reads[0]?.seq} до запроса #${requested[0]?.seq}`),
    check('нет удаления опций и угаданных optionId', optionMisuse(trace), 'только optionId из чтения, ключи схемы, без удаления'),
  ];
  if (preview) checks.push(check('каждое превью опций показано одной таблицей и одним вопросом', shown.length ? badReplies : ['нет превью опций'],
    `${shown.length} превью, у каждого таблица и один вопрос`));
  return checks;
}

/** The terminal result of one apply (start_operation and its get_operation polls): { result, entry }, or {} while unknown. */
export function applyOutcome(trace, apply) {
  if (!apply) return {};
  const started = parseResult(apply.start.result);
  const responses = [apply.start, ...trace.filter((entry) => entry.seq > apply.start.seq
    && entry.tool === 'get_operation' && started?.operationId && entry.arguments?.operationId === started.operationId)];
  for (const entry of responses) {
    const response = parseResult(entry.result);
    const result = response?.result ?? response;
    if (result?.errorCode || ['UPDATED', 'PARTIAL', 'FAILED'].includes(result?.status)) return { result, entry };
  }
  return {};
}

/** Messages of the dialogue by turn: the t-th user message opens turn t, the assistant's replies follow it. */
export function turnTexts(conversation = []) {
  let turn = 0;
  const turned = conversation.map((message) => { if (message.role === 'user') turn++; return { ...message, turn: message.turn ?? turn }; });
  const text = (role, number) => turned.filter((message) => message.role === role && message.turn === number)
    .map((message) => message.content ?? '').join('\n');
  return { user: (number) => text('user', number), assistant: (number) => text('assistant', number) };
}

/** True when the user's message is a plain yes (a confirmation of the plan shown in the previous turn). */
export const isYes = (text) => CONFIRMATION.test(text ?? '') && !/(?:^|\s)не\s/iu.test(text ?? '');
