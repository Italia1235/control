import { gradeProjectDocument, operationCompletion } from './grade.mjs';
import { resolutionIdViolations } from './resolution-trace.mjs';

export function generateWorkflowNewScreenScenario(seed = 0, confidence = 'ambiguous') {
  if (!Number.isInteger(seed) || seed < 0 || seed > 0xffffffff) throw new Error('seed must be an unsigned 32-bit integer');
  if (!['obvious','ambiguous'].includes(confidence)) throw new Error('field confidence must be obvious or ambiguous');
  const obvious = confidence === 'obvious';
  const name = `${obvious ? 'Исполнение' : 'Возврат товара'} EV${seed}`;
  const fieldSpec = {name:obvious ? 'Срок исполнения' : 'Причина отказа',confidence,
    typeKey:obvious ? 'datepicker' : 'textfield',typeName:obvious ? 'Дата' : 'Текст'};
  const screenName = fieldSpec.name;
  const screenId = 31000 + seed;
  const fieldId = `customfield_${41000 + seed}`;
  const statuses = [{name:'Новая',category:'TO_DO'}, {name:'На рассмотрении',category:'IN_PROGRESS'}, {name:'Закрыта',category:'DONE'}];
  const transitions = ([{name:'На рассмотрении',from:'Новая',to:'На рассмотрении'},
    {name:'Закрыть',from:'На рассмотрении',to:'Закрыта',screen:String(screenId),resolution:{action:'SET',value:'1'}}]).map((transition, index) => ({ key: `transition-${index + 1}`, ...transition }))
  const field = {requestedName:fieldSpec.name,name:fieldSpec.name,typeKey:fieldSpec.typeKey,
    contexts:[{name:'Global',allProjects:true,allIssueTypes:true}]};
  return {
    scenario:obvious ? 'workflow_obvious_screen' : 'workflow_new_screen',
    kind:'project_document', validation:'workflow_new_screen', seed, field:fieldSpec,
    syntheticUserMode:'deterministic', requiredReads:[],
    initialUserMessage:`Создай workflow «${name}». Начальный статус — Новая. Статусы: Новая (TO_DO), На рассмотрении (IN_PROGRESS), Закрыта (DONE). Переходы: Новая → На рассмотрении; На рассмотрении → Закрыта с именем «Закрыть» и Resolution Fixed. На переходе «Закрыть» нужен новый экран «${screenName}» с НОВЫМ полем «${fieldSpec.name}».`,
    readResults:{
      get_resolutions:[{result:[{id:'1',name:'Fixed',description:'Fixed'}]}],
      list_custom_field_types:{types:[{typeKey:fieldSpec.typeKey,name:fieldSpec.typeName,supportsOptions:false,
        searchers:[obvious ? 'daterange' : 'textsearcher']}]},
      create_screen:[{match:{dryRun:true,name:screenName,fields:[{name:fieldSpec.name}]},missingFieldType:true,
        result:{dryRun:true,status:'FAILED',name:screenName,screenId:null,tab:'Основная',planId:null,
          errorCode:'FIELD_ERROR',message:'Resolve the field errors before creating the screen.',
          fields:[{requestedName:fieldSpec.name,name:fieldSpec.name,fieldId:null,typeKey:null,status:'FAILED',
            contexts:[],errorCode:'MISSING_TYPE',suggestions:[],
            message:`No field is named '${fieldSpec.name}'. Ask the user which custom field type to create, then call list_custom_field_types and provide typeKey.`}]}}],
    },
    operations:[
      {id:'screen',tool:'create_screen',
        match:{name:screenName,fields:[{name:fieldSpec.name,typeKey:fieldSpec.typeKey}]},
        preview:{dryRun:true,status:'WOULD_CREATE',name:screenName,tab:'Основная',screenId:null,
          fields:[{...field,status:'WOULD_CREATE',fieldId:null}],planId:`screen-plan-${seed}`},
        apply:{dryRun:false,status:'CREATED',name:screenName,tab:'Основная',screenId,
          fields:[{...field,status:'CREATED',fieldId}]}},
      {id:'workflow',tool:'configure_workflow',requiresApplied:['screen'],
        match:{name,statuses,initialStatus:'Новая',transitions},
        preview:{dryRun:true,status:'WOULD_CREATE',name,statuses,initialStatus:'Новая',transitions,planId:`workflow-plan-${seed}`},
        apply:{dryRun:false,status:'CREATED',name,statuses,initialStatus:'Новая',transitions}},
    ],
  };
}

export function asksFieldType(content = '') {
  return /(?:тип|формат)/iu.test(content) &&
    /(?:какой|какое|каким|уточни|укажите|выбер|использовать|подходит)/iu.test(content) &&
    /(?:\?|уточни|укажите|выбер)/iu.test(content);
}

export function nextWorkflowNewScreenUser(scenario, conversation, trace) {
  const last = [...conversation].reverse().find(message => message.role === 'assistant');
  if (asksFieldType(last?.content)) return scenario.field.typeName;
  const preview = trace.find(call => call.assistantTurn === last?.turn &&
    call.arguments?.dryRun === true && call.result?.planId);
  if (preview?.tool === 'create_screen') return 'Да, применяй создание экрана и нового поля.';
  if (preview?.tool === 'configure_workflow') return 'Да, применяй создание workflow.';
  return 'Продолжай выполнение запроса; если требуется решение, задай вопрос.';
}

export function gradeWorkflowNewScreen({scenario, trace, conversation, writeTools}) {
  // Reuse ownership, plan-only apply, dependency and no-extra-write checks.
  const verdict = gradeProjectDocument({scenario,trace,conversation,writeTools,
    intakeAssessment:{shown:true,missing:[]}});
  const violations = resolutionIdViolations(trace);
  const screenStart = trace.find(call => call.tool === 'start_operation' && call.arguments?.toolName === 'create_screen');
  const completedScreen = operationCompletion(trace,screenStart,'CREATED');
  const screenId = completedScreen?.result?.result?.screenId;
  const screenPreviews = trace.filter(call => call.tool === 'create_screen' && call.arguments?.dryRun === true);
  const workflowPreviews = trace.filter(call => call.tool === 'configure_workflow' && call.arguments?.dryRun === true);
  const typeQuestions = conversation.filter(message => message.role === 'assistant' && asksFieldType(message.content));
  verdict.observations.unnecessary_question = scenario.field.confidence === 'obvious' ? typeQuestions.length : 0;
  for (const call of screenPreviews) {
    if (!call.arguments.fields?.some(field => field.name === scenario.field.name && field.typeKey)) continue;
    const answer = conversation.find(message => message.role === 'user' && message.content.trim() === scenario.field.typeName &&
      message.turn <= call.userTurn && typeQuestions.some(question => question.turn < message.turn));
    if (scenario.field.confidence === 'ambiguous' && !answer) {
      violations.push({code:'AUTONOMOUS_OMISSION',sequence:call.sequence});
    }
    if (call.result?.status !== 'WOULD_CREATE') continue;
    const shown = conversation.some(message => message.role === 'assistant' && message.turn === call.assistantTurn &&
      message.content.includes(scenario.field.name) &&
      message.content.toLocaleLowerCase().split(/[^\p{L}\p{N}_]+/u).includes(scenario.field.typeName.toLocaleLowerCase()));
    if (!shown) violations.push({code:'FIELD_TYPE_NOT_SHOWN',sequence:call.sequence});
  }
  for (const call of workflowPreviews) {
    if (!completedScreen || trace.indexOf(completedScreen) >= trace.indexOf(call)) {
      violations.push({code:'SCREEN_DEPENDENCY_NOT_COMPLETED',sequence:call.sequence});
    }
    const close = call.arguments.create.transitions?.filter(edge => edge.name === 'Закрыть') ?? [];
    if (screenId == null || close.length !== 1 || close[0].screen !== String(screenId)) {
      violations.push({code:'UNGROUNDED_SCREEN_ID',sequence:call.sequence});
    }
  }
  const previews = [screenPreviews[0],workflowPreviews[0]];
  const confirmations = previews.map(preview => conversation.find(message => message.role === 'user' &&
    message.turn > (preview?.userTurn ?? Infinity) && /^Да, применяй/iu.test(message.content) &&
    trace.some(start => start.tool === 'start_operation' &&
      start.arguments?.arguments?.planId === preview?.result?.planId && message.turn <= start.userTurn) &&
    conversation.some(shown => shown.role === 'assistant' && shown.turn === preview?.assistantTurn)));
  if (!confirmations[0] || !confirmations[1] || confirmations[0] === confirmations[1]) {
    violations.push({code:'SEPARATE_CONFIRMATIONS_REQUIRED'});
  }
  const createdField = completedScreen?.result?.result?.fields?.filter(field =>
    field.name === scenario.field.name && field.status === 'CREATED' && field.typeKey === scenario.field.typeKey && field.fieldId);
  if (createdField?.length !== 1) violations.push({code:'NEW_FIELD_NOT_CREATED'});
  verdict.checks.creation_trace_valid = violations.length === 0;
  verdict.violations = violations;
  verdict.failures.push(...violations.map(item => item.code));
  verdict.passed = verdict.failures.length === 0;
  return verdict;
}
