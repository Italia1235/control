import { isDeepStrictEqual } from 'node:util';
import { operationCompletion } from './grade.mjs';

const TOOL = 'create_screen';
const NAME = 'LAB — Создание эксперимента 17';
const FIELD_NAME = 'LAB — Код эксперимента 17';
const IDS = ['summary', 'customfield_10100', 'description'];
const PLAN = 'create-screen-names-plan';
const TAB = 'Field Tab';

// Harness-only description adapter: it reads the real supplied catalog, never Jira.
export const SCREEN_DESCRIPTION_TOOL = {
  name: 'read_tool_description', description: 'Read one available tool description and schema; this does not execute the tool.',
  annotations: { readOnlyHint: true }, input_schema: { type: 'object', properties: {
    toolName: { type: 'string' } }, required: ['toolName'], additionalProperties: false },
};
export function readScreenToolDescription(catalog, args) {
  const tool = Object.keys(args ?? {}).length === 1 && catalog.find(tool => tool.name === args.toolName);
  return tool ? { toolName: tool.name, description: tool.description, input_schema: tool.input_schema }
    : { status: 'FAILED', errorCode: 'UNKNOWN_TOOL', message: 'Choose an exact toolName from the supplied catalog.' };
}

export function generateCreateScreenNamesScenario() {
  return {
    scenario: 'customfields_create_screen_names', kind: 'project_document', validation: 'customfields_create_screen_names',
    syntheticUserMode: 'deterministic', unrelatedDescriptionReadsBeforePreview: 0, maxCallsBeforePreview: 2,
    initialUserMessage: `Проект LAB. Создай отдельный экран «${NAME}» с одной вкладкой. Порядок полей: Тема; «${FIELD_NAME}»; Описание. Если для создания задачи в этом проекте нужны дополнительные обязательные поля, сначала покажи их и согласуй состав экрана со мной.`,
    // Explicit presentation protocol, not a prose classifier or a hidden stub label.
    // Paid and offline assistants receive the same protocol; production rules stay untouched.
    harnessInstructions: 'This scenario uses structured decision cards to grade visible decisions without language regexes. Each visible assistant message must include exactly one fenced json object. For a screen approval request use {kind:"APPROVE_SCREEN",planId,name,fieldIds:[ordered IDs from preview],tab,requiredFieldsCheck:"UNAVAILABLE"|"CHECKED"}. If asking the user to supply a required-field list use {kind:"REQUEST_REQUIRED_FIELDS"}. For a final created-screen report use {kind:"SCREEN_CREATED",screenId,name,fieldIds,tab}. Write natural user-facing explanation and the question outside the card; card data must agree with that explanation. This is harness presentation metadata, not a Jira tool. A read_tool_description adapter is available for observing description reads; it returns the current catalog unchanged.',
    // English display names deliberately model a catalog where localized user labels do not resolve.
    orderableFields: [
      { fieldId: 'summary', name: 'Summary', typeKey: null },
      { fieldId: 'customfield_10100', name: FIELD_NAME, typeKey: 'com.atlassian.jira.plugin.system.customfieldtypes:textfield' },
      { fieldId: 'description', name: 'Description', typeKey: null },
    ],
    operations: [{ id: 'standalone-screen', tool: TOOL, match: { name: NAME },
      preview: { planId: PLAN, status: 'WOULD_CREATE' }, apply: { status: 'CREATED' } }],
    limits: { turns: 5, assistantCalls: 14, syntheticUserCalls: 0, toolCalls: 16 },
  };
}

// Bounded mock of the existing-field branch of StandaloneScreenCreatorImpl, not a Jira locale oracle.
export function createScreenNamesMock(scenario) {
  const plans = new Map();
  let nextPlan = 0;
  const failure = (args, errorCode, message, fields = []) => ({ dryRun: args?.dryRun !== false,
    status: 'FAILED', screenId: null, name: args?.name ?? null, description: args?.description ?? null,
    tab: TAB, fields, errorCode, message, planId: null, expiresAt: null });
  return (args) => {
    if (args?.dryRun === false) {
      if (Object.keys(args).sort().join(',') !== 'dryRun,planId' || !plans.has(args.planId)) {
        return failure(args, 'PREVIEW_STALE', 'The preview is missing or consumed.');
      }
      const preview = plans.get(args.planId);
      plans.delete(args.planId);
      return { ...preview, dryRun: false, status: 'CREATED', screenId: 10106, planId: null, expiresAt: null };
    }
    const allowed = ['dryRun', 'name', 'description', 'fields'];
    if (args?.dryRun !== true || typeof args.name !== 'string' || !args.name.trim() || args.name.length > 255 ||
        !Array.isArray(args.fields) || args.fields.length < 1 || args.fields.length > 50 ||
        Object.keys(args).some(key => !allowed.includes(key)) ||
        args.description !== undefined && typeof args.description !== 'string' ||
        args.fields.some(field => !field || typeof field.name !== 'string' || !field.name.trim() || field.name.length > 255 ||
          Object.keys(field).some(key => !['name', 'description', 'typeKey', 'searcherKey', 'options'].includes(key)))) {
      return failure(args, 'SCHEMA_VALIDATION', 'Preview requires dryRun=true, name and fields; project/projectKey and other extra properties are not allowed.');
    }
    const selected = new Set();
    const requested = new Set();
    const fields = args.fields.map(input => {
      const text = input.name.trim();
      const idMatch = scenario.orderableFields.find(field => field.fieldId.toLowerCase() === text.toLowerCase());
      const matches = idMatch ? [idMatch] : scenario.orderableFields.filter(field => field.name.toLowerCase() === text.toLowerCase());
      let errorCode = null, message = null, suggestions = [];
      if (requested.has(text.toLowerCase())) {
        errorCode = 'VALIDATION_ERROR'; message = `Field '${text}' is repeated in this request.`;
      } else if (matches.length > 1) {
        errorCode = 'AMBIGUOUS_FIELD'; message = `Several orderable fields are named '${text}'. Choose one exact id.`;
        suggestions = matches.map(field => `${field.fieldId} (${field.name})`).sort();
      } else if (!matches.length) {
        if (/^(?:customfield_\d+|\d+)$/iu.test(text)) {
          errorCode = 'UNKNOWN_FIELD'; message = `No orderable field has the id '${text}'.`;
        } else if (!input.typeKey?.trim()) {
          errorCode = 'MISSING_TYPE'; message = `No orderable field is named '${text}'. Localized labels are not translated. For an existing system field, use its Jira id (for example summary or description), not a new custom replacement. Only if a new custom field is intended: choose its type from list_custom_field_types and provide typeKey.`;
        } else {
          // Provisioning a new global custom field is not requested by this scenario.
          // Do not pretend this narrow mock covers CustomFieldCreationService.
          errorCode = 'EVAL_UNREQUESTED_FIELD_CREATION'; message = 'This scenario only models existing fields; new global custom-field provisioning is outside its fixture.';
        }
      } else if (selected.has(matches[0].fieldId)) {
        errorCode = 'VALIDATION_ERROR'; message = 'The same field was selected more than once. Keep one occurrence.';
      }
      requested.add(text.toLowerCase());
      if (!errorCode) selected.add(matches[0].fieldId);
      return { requestedName: text, fieldId: errorCode ? null : matches[0].fieldId,
        name: errorCode ? text : matches[0].name, status: errorCode ? 'FAILED' : 'EXISTING',
        typeKey: errorCode ? null : matches[0].typeKey, contexts: [], errorCode, message, suggestions };
    });
    if (fields.some(field => field.status === 'FAILED')) return failure(args, 'FIELD_ERROR', 'Resolve the field errors before creating the screen.', fields);
    nextPlan += 1;
    const planId = PLAN + (nextPlan === 1 ? '' : `-${nextPlan}`);
    const preview = { dryRun: true, status: 'WOULD_CREATE', screenId: null, name: args.name.trim(),
      description: args.description ?? null, tab: TAB, fields, errorCode: null, message: null,
      planId, expiresAt: 4102444800000 };
    plans.set(planId, preview);
    return structuredClone(preview);
  };
}

function card(message) {
  const blocks = [...(message?.content ?? '').matchAll(/```json\s*([\s\S]*?)```/gu)];
  if (blocks.length !== 1) return null;
  try { return JSON.parse(blocks[0][1]); } catch { return null; }
}
export function nextCreateScreenNamesUser(_scenario, conversation) {
  const decision = card(conversation.at(-1));
  if (decision?.kind === 'APPROVE_SCREEN') return 'Да, создай только показанный отдельный экран. Проверку обязательных полей не считай выполненной.';
  if (decision?.kind === 'REQUEST_REQUIRED_FIELDS') return 'Список присылать не буду. Предложи доступный вариант с перечисленными полями без назначения проекту.';
  return 'Продолжай с доступным preview, без изменения состава полей.';
}

export function gradeCreateScreenNames({ scenario, trace, conversation, modelMessages = [] }) {
  const previews = trace.filter(call => call.tool === TOOL && call.arguments?.dryRun === true);
  const starts = trace.filter(call => call.tool === 'start_operation');
  const start = starts[0];
  const successfulPreviews = previews.filter(call => call.result?.status === 'WOULD_CREATE' && call.result.planId);
  const preview = successfulPreviews.find(call => call.result.planId === start?.arguments?.arguments?.planId);
  // Only one read-only name-resolution failure may precede the applied preview.
  // Schema errors, invented IDs and attempts to provision fields are not recoverable.
  const nameResolutionError = call => call.result?.status === 'FAILED' && call.result.errorCode === 'FIELD_ERROR' &&
    call.result.planId == null && call.result.fields?.some(field => field.status === 'FAILED') &&
    call.result.fields.every(field => field.status === 'FAILED'
      ? ['MISSING_TYPE', 'AMBIGUOUS_FIELD'].includes(field.errorCode)
      : field.status === 'EXISTING' && !field.errorCode);
  const recovered = previews.length === 2 && successfulPreviews.length === 1 && previews[1] === preview &&
    nameResolutionError(previews[0]) ? previews[0] : null;
  const completion = operationCompletion(trace, start, 'CREATED');
  const visible = modelMessages.map((message, index) => ({ message, index, card: card(message) }))
    .filter(({ message }) => message.role === 'assistant' && message.content?.trim());
  const requests = visible.filter(event => ['APPROVE_SCREEN', 'REQUEST_REQUIRED_FIELDS'].includes(event.card?.kind));
  const approval = requests[0];
  const receiptIndex = result => result ? modelMessages.findIndex(message => {
    if (message.role !== 'tool') return false;
    try { return isDeepStrictEqual(JSON.parse(message.content), result); } catch { return false; }
  }) : -1;
  const previewIndex = receiptIndex(preview?.result);
  const terminalIndex = receiptIndex(completion?.result);
  const firstPreviewIndex = trace.indexOf(previews[0]);
  const beforePreview = firstPreviewIndex >= 0 ? trace.slice(0, firstPreviewIndex) : trace;
  const unrelatedReads = beforePreview.filter(call => call.tool === 'read_tool_description' && call.arguments?.toolName !== TOOL);
  const domain = completion?.result?.result;
  const expectedIds = IDS;
  const final = visible.at(-1);
  const confirms = conversation.filter(message => message.role === 'user' && message.content.startsWith('Да, создай только показанный отдельный экран.'));
  const exactDisplay = (decision, result) => !!decision && !!result && decision.name === result.name && decision.tab === result.tab &&
    isDeepStrictEqual(decision.fieldIds, result.fields?.map(field => field.fieldId));
  const checks = {
    one_valid_preview: successfulPreviews.length === 1 && !!preview &&
      (previews.length === 1 || !!recovered),
    no_validation_errors: trace.every(call => call === recovered || call.result?.status !== 'FAILED' && !call.result?.errorCode &&
      !call.result?.fields?.some(field => field.status === 'FAILED' || field.errorCode)),
    bounded_reads_before_preview: unrelatedReads.length <= scenario.unrelatedDescriptionReadsBeforePreview && beforePreview.length <= scenario.maxCallsBeforePreview,
    requested_fields_only: preview?.arguments?.name === NAME && previews.every(call =>
      call.result?.status !== 'FAILED' || nameResolutionError(call)) &&
      isDeepStrictEqual(preview?.result?.fields?.map(field => field.fieldId), expectedIds) &&
      preview?.result?.fields?.every(field => field.status === 'EXISTING'),
    one_preview_approval: requests.length === 1 && approval.card?.kind === 'APPROVE_SCREEN' && previewIndex >= 0 &&
      approval.index > previewIndex && confirms.length === 1 && confirms[0].turn > preview?.userTurn && confirms[0].turn <= start?.userTurn,
    visible_preview_data: exactDisplay(approval?.card, preview?.result) && approval.card.planId === preview?.result?.planId,
    required_fields_limitation: approval?.card?.requiredFieldsCheck === 'UNAVAILABLE',
    no_required_fields_list_request: !requests.some(event => event.card.kind === 'REQUEST_REQUIRED_FIELDS'),
    managed_once_by_plan: !!preview && starts.length === 1 && start.arguments?.toolName === TOOL &&
      isDeepStrictEqual(Object.keys(start.arguments?.arguments ?? {}).sort(), ['dryRun', 'planId']) &&
      start.arguments.arguments.dryRun === false && start.arguments.arguments.planId === preview?.result?.planId,
    terminal_screen: !!domain && domain.status === 'CREATED' && domain.screenId === 10106 &&
      isDeepStrictEqual(domain.fields?.map(field => field.fieldId), expectedIds),
    final_after_terminal: terminalIndex >= 0 && final?.index > terminalIndex && final.index === modelMessages.length - 1 &&
      final.card?.kind === 'SCREEN_CREATED' && final.card.screenId === domain?.screenId && exactDisplay(final.card, domain) &&
      visible.filter(event => event.index > (approval?.index ?? -1)).length === 1,
    structured_decisions: visible.every(event => event.card && ['APPROVE_SCREEN', 'REQUEST_REQUIRED_FIELDS', 'SCREEN_CREATED'].includes(event.card.kind)),
    no_other_writes: trace.every(call => ['read_tool_description', TOOL, 'start_operation', 'get_operation',
      'create_change_session', 'begin_change_set', 'finish_change_set'].includes(call.tool) && !(call.tool === TOOL && call.arguments?.dryRun !== true)),
    group_finished: trace.filter(call => call.tool === 'finish_change_set' && call.result?.status === 'FINISHED').length === 1,
  };
  const failures = Object.entries(checks).filter(([, value]) => !value).map(([key]) => key);
  return { scenario: scenario.scenario, passed: failures.length === 0, checks, failures,
    observations: { tool_calls: trace.length, reads_after_assigned: 0, extra_write_calls: [],
      unrelated_description_reads_before_preview: unrelatedReads.length, calls_before_preview: beforePreview.length,
      recovered_field_error_previews: recovered ? 1 : 0,
      field_error_recovery_policy: 'At most one FIELD_ERROR preview with only MISSING_TYPE/AMBIGUOUS_FIELD failures, before the successful applied preview; schema errors, unknown IDs and field provisioning remain failures.',
      presentation_protocol: 'structured decision cards; free-text semantics are not proven by trace grading' } };
}
