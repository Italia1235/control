import { isDeepStrictEqual } from 'node:util';

export function protocolCard(message) {
  const blocks = [...(message?.content ?? '').matchAll(/```json\s*([\s\S]*?)```/gu)];
  if (blocks.length !== 1) return null;
  try { return JSON.parse(blocks[0][1]); } catch { return null; }
}
export const same = isDeepStrictEqual;
export const questionKinds = new Set(['DECISIONS', 'PLAN', 'DELTA', 'CONFIRM_STEP', 'KNOWN_FACT']);

// modelMessages preserves assistant/tool/user chronology, unlike turn-level conversation.
export function protocolEvents(modelMessages) {
  const results = new Map(modelMessages.filter(m => m.role === 'tool').map(m => [m.tool_call_id, JSON.parse(m.content)]));
  let turn = 0;
  return modelMessages.flatMap((message, index) => {
    if (message.role === 'user') return [{ type: 'user', index, turn: ++turn, content: message.content }];
    if (message.role !== 'assistant') return [];
    const events = [];
    if (message.content?.trim()) {
      const card = protocolCard(message);
      const question = questionKinds.has(card?.kind) || /[?？]/u.test(message.content.replace(/```json[\s\S]*?```/gu, ''));
      const questionCount = !question ? 0 : card?.kind === 'DECISIONS' && Array.isArray(card.decisions)
        ? Math.max(1, card.decisions.length) : card ? 1 : Math.max(1, (message.content.match(/[?？]/gu) ?? []).length);
      events.push({ type: 'visible', index, turn, card, content: message.content,
        question, questionCount });
    }
    for (const call of message.tool_calls ?? []) {
      let args;
      try { args = JSON.parse(call.function.arguments); } catch { args = {}; }
      events.push({ type: 'call', index, tool: call.function.name, arguments: args, result: results.get(call.id) });
    }
    return events;
  });
}
export function operationForCall(scenario, call) {
  return scenario.operations.find(op => call.tool === op.tool && same(call.arguments, { dryRun: true, ...op.match }));
}
export function protocolMetrics({ scenario, modelMessages, readTools }) {
  const events = protocolEvents(modelMessages);
  const firstQuestion = events.find(e => e.question);
  const writes = events.filter(e => e.type === 'call' && (e.tool === 'start_operation' || e.arguments?.dryRun === false));
  const beforeQuestion = events.filter(e => e.type === 'call' && (!firstQuestion || e.index < firstQuestion.index));
  const successfulIndependent = new Set(beforeQuestion.filter(e => {
    const op = operationForCall(scenario, e);
    return op && !(op.requiresApplied?.length) && !(op.requiresDecisions?.length) &&
      e.result?.planId === op.preview.planId && e.result?.status === op.preview.status;
  }).map(e => operationForCall(scenario, e).id));
  return {
    questions_before_first_write: writes.length ? events.filter(e => e.question && e.index < writes[0].index).reduce((count, e) => count + e.questionCount, 0) : null,
    question_turns_count: new Set(events.filter(e => e.question).map(e => e.turn)).size,
    independent_dry_runs_before_first_question: successfulIndependent.size,
    reads_before_first_question: beforeQuestion.filter(e => readTools.has(e.tool) && e.arguments?.dryRun !== true && e.arguments?.dryRun !== false).length,
    no_question: !firstQuestion, no_write: writes.length === 0,
  };
}
