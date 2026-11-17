import { gradeProjectDocument } from './grade.mjs';
import { gradeWorkflowResolution } from './workflow-resolution.mjs';
import { appendResolutionChecks } from './resolution-trace.mjs';

export function generateWorkflowResolutionExtraScenario(variant = 'missing') {
  if (!['missing','unresolved'].includes(variant)) throw new Error('Unknown Resolution scenario');
  const resolutions = [{id:'1',name:'Fixed',description:'A defect was fixed'},
    {id:'4',name:'Duplicate',description:'Duplicate of another issue'}];
  const name = variant === 'missing' ? 'Resolution cancelled' : 'Resolution unresolved';
  const statuses = [{name:'Open',category:'TO_DO'},{name:'Cancelled',category:'DONE'}];
  const resolution = {id:'9',name:'Cancelled',description:'Work cancelled'};
  const transitions = ([{name:'Cancel',from:'Open',to:'Cancelled',resolution:{action:'SET',value:resolution.id}},
    {name:'Reopen',from:'Cancelled',to:'Open'}]).map((transition, index) => ({ key: `transition-${index + 1}`, ...transition }))
  const effective = [{...transitions[0],resolution:{action:'SET',value:resolution.id,name:resolution.name,description:resolution.description}},
    {...transitions[1],resolution:{action:'CLEAR'}}];
  return {
    scenario:`workflow_resolution_${variant}`,kind:'project_document',validation:'workflow_resolution_extra',variant,
    syntheticUserMode:'deterministic',requiredReads:['get_resolutions'],
    limits:{assistantCalls:12,syntheticUserCalls:0},
    initialUserMessage: variant === 'missing'
      ? `Создай workflow «${name}». Начальный Open (TO_DO), Cancelled (DONE). Переходы Cancel: Open → Cancelled, Reopen: Cancelled → Open. При отмене нужна Resolution Cancelled с описанием Work cancelled; если такого значения нет, предложи создать его.`
      : `Создай workflow «${name}»: Open (TO_DO), Cancelled (DONE), переход Cancel: Open → Cancelled. На Cancel установи Resolution Unresolved (Нерешено).`,
    readResults:{get_resolutions:[{result:resolutions}]},
    resolutionCatalog:resolutions,resolutionMockOptions:{startingId:9,planId:'resolution-create-plan'},
    operations: variant === 'unresolved' ? [] : [
      {id:'resolution',tool:'create_resolution',match:{name:resolution.name,description:resolution.description},
        preview:{dryRun:true,status:'WOULD_CREATE',resolution:{...resolution,id:null},planId:'resolution-create-plan'},
        apply:{dryRun:false,status:'CREATED',resolution}},
      {id:'workflow',tool:'configure_workflow',requiresApplied:['resolution'],match:{name,statuses,initialStatus:'Open',transitions},
        preview:{dryRun:true,status:'WOULD_CREATE',name,statuses,initialStatus:'Open',transitions:effective,planId:'resolution-workflow-plan'},
        apply:{dryRun:false,status:'CREATED',name,statuses,initialStatus:'Open',transitions:effective}},
    ],
  };
}

export function explainsUnresolved(content = '') {
  return /(?:Unresolved|Нерешено)/iu.test(content) && /(?:отсутств|нет значения|absence|no value)/iu.test(content) &&
    /(?:DONE|заверш)/iu.test(content) && /(?:SET|установ|обязател)/iu.test(content) &&
    /(?:нельзя|невозмож|cannot|не могу)/iu.test(content);
}

export function nextWorkflowResolutionExtraUser(scenario,conversation,trace) {
  const last = conversation.findLast(message => message.role === 'assistant');
  if (scenario.variant === 'unresolved' && explainsUnresolved(last?.content)) return 'DONE';
  const preview = trace.find(call => call.assistantTurn === last?.turn && call.arguments?.dryRun === true && call.result?.planId);
  if (preview?.tool === 'create_resolution') return 'Да, применяй создание глобальной Resolution.';
  if (preview?.tool === 'configure_workflow') return 'Да, применяй создание workflow.';
  return 'Продолжай; сначала объясни ограничения и покажи preview, если создание возможно.';
}

export function gradeWorkflowResolutionWithTrace(input) {
  return appendResolutionChecks(gradeWorkflowResolution(input),input);
}

export function gradeWorkflowResolutionExtra(input) {
  const {scenario,trace,conversation,writeTools} = input;
  if (scenario.variant === 'unresolved') {
    const checks = {
      unresolved_explained:conversation.some(message => message.role === 'assistant' && explainsUnresolved(message.content)),
      resolution_catalog_observed:trace.some(call => call.tool === 'get_resolutions' && Array.isArray(call.result)),
      no_unresolved_writes:!trace.some(call => call.tool === 'start_operation' || writeTools.has(call.tool)),
    };
    const failures = Object.entries(checks).filter(([,passed]) => !passed).map(([check]) => check);
    return appendResolutionChecks({scenario:scenario.scenario,checks,failures,passed:failures.length === 0,
      observations:{tool_calls:trace.length,reads_after_assigned:0,extra_write_calls:[]}},input);
  }
  const verdict = gradeProjectDocument({...input,intakeAssessment:{shown:true,missing:[]}});
  const workflowPreviews = trace.filter(call => call.tool === 'configure_workflow' && call.arguments?.dryRun === true);
  const shown = workflowPreviews.length > 0 && workflowPreviews.every(call => conversation.some(message =>
    message.role === 'assistant' && message.turn === call.assistantTurn && message.content.includes('Cancelled') &&
    /(?:Unresolved|очист|нерешен|сброс)/iu.test(message.content)));
  verdict.checks.resolution_consequences_shown = shown;
  if (!shown) verdict.failures.push('resolution_consequences_shown');
  return appendResolutionChecks(verdict,input);
}
