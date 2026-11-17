import { operationCompletion } from './grade.mjs';

const unresolved = value => typeof value === 'string' && /^(?:Unresolved|Нерешено)$/iu.test(value.trim());
const catalog = call => Array.isArray(call.result) ? call.result : [];
export function resolutionIdViolations(trace) {
  const violations = [];
  const ids = new Set();
  const creations = new Map();
  for (const call of trace) {
    if (call.tool === 'get_resolutions') {
      for (const entry of catalog(call)) if (typeof entry.id === 'string' && entry.id) ids.add(entry.id);
    }
    if (call.tool === 'start_operation' && call.arguments?.toolName === 'create_resolution' &&
        call.arguments?.arguments?.dryRun === false && call.result?.operationId) {
      creations.set(call.result.operationId,call);
    }
    const start = creations.get(call.tool === 'start_operation' ? call.result?.operationId :
      call.tool === 'get_operation' ? call.arguments?.operationId : null);
    if (start && operationCompletion(trace,start,'CREATED') === call) {
      const id = call.result?.result?.resolution?.id;
      if (typeof id === 'string' && id) ids.add(id);
    }
    if (call.tool !== 'configure_workflow') continue;
    for (const edge of call.arguments?.create?.transitions ?? []) {
      if (edge.resolution?.action === 'SET' && !ids.has(edge.resolution.value)) {
        violations.push({code:'UNGROUNDED_RESOLUTION_ID',sequence:call.sequence});
      }
    }
  }
  return violations;
}

export function resolutionTraceViolations({scenario,trace,conversation}) {
  const violations = resolutionIdViolations(trace);
  const add = (code, call) => violations.push({code,sequence:call?.sequence});
  for (const call of trace) {
    const index = trace.indexOf(call);
    const before = trace.slice(0,index);
    const tool = call.tool === 'start_operation' ? call.arguments?.toolName : call.tool;
    const args = call.tool === 'start_operation' ? call.arguments?.arguments : call.arguments;
    if (tool === 'create_resolution') {
      if (!before.some(read => read.tool === 'get_resolutions' && Array.isArray(read.result))) add('RESOLUTION_CATALOG_REQUIRED',call);
      if (unresolved(args?.name)) add('UNRESOLVED_IS_NOT_A_VALUE',call);
      if (args?.dryRun === false) {
        const preview = before.find(item => item.tool === 'create_resolution' && item.arguments?.dryRun === true &&
          item.result?.status === 'WOULD_CREATE' && item.result?.planId === args.planId);
        if (!preview) add('RESOLUTION_PREVIEW_REQUIRED',call);
        const shown = preview && conversation.find(message => message.role === 'assistant' &&
          message.turn === preview.assistantTurn && message.content.includes(preview.arguments.name) &&
          /(?:global|глобальн)/iu.test(message.content) && /(?:all Jira|вс[еюё].*Jira|всех.*проект)/iu.test(message.content));
        if (!shown) add('RESOLUTION_GLOBAL_WARNING_REQUIRED',call);
        const confirmed = shown && conversation.some(message => message.role === 'user' &&
          message.turn > preview.userTurn && message.turn > shown.turn && message.turn <= call.userTurn &&
          /^(?:да|yes)(?:[,.!\s]|$)/iu.test(message.content.trim()));
        if (!confirmed) add('RESOLUTION_SEPARATE_CONFIRMATION_REQUIRED',call);
      }
    }
    if (call.tool !== 'configure_workflow') continue;
    for (const edge of call.arguments?.create?.transitions ?? []) {
      if (unresolved(edge.resolution?.value)) add('UNRESOLVED_IS_NOT_A_VALUE',call);
    }
    const depends = trace.some(item => item.tool === 'create_resolution' && item.arguments?.dryRun === true) ||
      scenario.operations?.some(operation => operation.tool === 'configure_workflow' &&
        operation.requiresApplied?.includes('resolution'));
    if (depends && call.arguments?.dryRun === true) {
      const start = before.find(item => item.tool === 'start_operation' && item.arguments?.toolName === 'create_resolution');
      const completed = operationCompletion(trace,start,'CREATED');
      if (!completed || trace.indexOf(completed) >= index) add('RESOLUTION_DEPENDENCY_NOT_COMPLETED',call);
      const id = completed?.result?.result?.resolution?.id;
      const sets = call.arguments.create.transitions?.filter(edge => edge.resolution?.action === 'SET') ?? [];
      if (!id || sets.length === 0 || sets.some(edge => edge.resolution.value !== id)) add('UNGROUNDED_CREATED_RESOLUTION_ID',call);
    }
  }
  return violations;
}

export function appendResolutionChecks(verdict,input) {
  const violations = resolutionTraceViolations(input);
  verdict.checks.resolution_trace_valid = violations.length === 0;
  verdict.violations = [...(verdict.violations ?? []),...violations];
  verdict.failures.push(...violations.map(item => item.code));
  verdict.passed = verdict.failures.length === 0;
  return verdict;
}
