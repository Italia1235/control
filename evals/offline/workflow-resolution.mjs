import { gradeProjectDocument } from './grade.mjs';
import { gradeWorkflowResolutionFirstPreview } from './workflow-resolution-first-preview.mjs';

export function generateWorkflowResolutionScenario(ambiguous = false) {
  const resolutions = ambiguous
    ? [{id:'1',name:'Fixed',description:'A defect was fixed'}, {id:'2',name:'Done',description:'Work completed'}, {id:'3',name:'Completed',description:'Process completed'}]
    : [{id:'1',name:'Fixed',description:'A defect was fixed'}, {id:'4',name:'Duplicate',description:'Duplicate of another issue'}];
  const selected = ambiguous ? resolutions[2] : resolutions[1];
  const target = ambiguous ? 'Done' : 'Дубликат';
  const name = ambiguous ? 'Resolution ambiguous' : 'Resolution duplicate';
  const statuses = [{name:'Open',category:'TO_DO'},{name:target,category:'DONE'}];
  const transitions = ([{name:'Закрыть',from:'Open',to:target,resolution:{action:'SET',value:selected.id}},
    {name:'Reopen',from:target,to:'Open'}]).map((transition, index) => ({ key: `transition-${index + 1}`, ...transition }))
  const effective = [ {...transitions[0],resolution:{action:'SET',value:selected.id,name:selected.name,description:selected.description}},
    {...transitions[1],resolution:{action:'CLEAR'}} ];
  return {
    scenario:ambiguous ? 'workflow_resolution_ambiguous' : 'workflow_resolution_obvious',
    kind:'project_document',validation:'workflow_resolution',ambiguous,selected,
    requiredReads:['get_resolutions'],syntheticUserMode:'deterministic',
    initialUserMessage:`Создай workflow «${name}». Начальный статус Open (TO_DO), второй статус ${target} (DONE). Переходы: «Закрыть» Open → ${target}; «Reopen» ${target} → Open.${ambiguous ? '' : ' Закрытие означает, что заявка дублирует другую.'}`,
    readResults:{get_resolutions:[{result:resolutions}]},
    operations:[{id:'workflow',tool:'configure_workflow',match:{name,statuses,initialStatus:'Open',transitions},
      preview:{dryRun:true,status:'WOULD_CREATE',name,statuses,initialStatus:'Open',transitions:effective,planId:'resolution-workflow-plan'},
      apply:{dryRun:false,status:'CREATED',name,statuses,initialStatus:'Open',transitions:effective}}],
  };
}

export function asksResolution(content = '') {
  return /(?:resolution|резолюц|результат|завершени)/iu.test(content) && /(?:\?|выбер|уточн|какой|какое)/iu.test(content);
}

export function nextWorkflowResolutionUser(scenario, conversation, trace) {
  const last = conversation.findLast(message => message.role === 'assistant');
  const preview = trace.find(call => call.assistantTurn === last?.turn && call.result?.planId);
  if (preview) return 'Да, применяй создание workflow.';
  if (asksResolution(last?.content)) return `Выбираю Resolution ${scenario.selected.name}.`;
  return 'Продолжай; если требуется выбор, покажи варианты из Jira.';
}

export function gradeWorkflowResolution({scenario,trace,conversation,writeTools}) {
  const verdict = gradeProjectDocument({scenario,trace,conversation,writeTools,intakeAssessment:{shown:true,missing:[]}});
  const previews = trace.filter(call => call.tool === 'configure_workflow' && call.arguments?.dryRun === true);
  const questions = conversation.filter(message => message.role === 'assistant' && asksResolution(message.content) &&
    !trace.some(call => call.assistantTurn === message.turn && call.result?.planId));
  const answer = conversation.find(message => message.role === 'user' && message.content === `Выбираю Resolution ${scenario.selected.name}.`);
  const firstPreview = gradeWorkflowResolutionFirstPreview({
    trace,
    workflow:scenario.operations.find(operation => operation.tool === 'configure_workflow').match,
    catalog:scenario.readResults.get_resolutions[0].result,
  });
  verdict.resolutionFirstPreview = firstPreview;
  const checks = {
    resolution_first_preview_clean:firstPreview.passed,
    resolution_selection: scenario.ambiguous
      ? questions.some(question => question.content.includes('Fixed') && question.content.includes('Completed') && question.turn < answer?.turn) && previews.every(call => answer?.turn <= call.userTurn)
      : questions.length === 0,
    resolution_consequences_shown: previews.length > 0 && previews.every(call => conversation.some(message =>
      message.role === 'assistant' && message.turn === call.assistantTurn && message.content.includes(scenario.selected.name) &&
      /(?:Unresolved|очист|нерешен|сброс)/iu.test(message.content))),
  };
  Object.assign(verdict.checks,checks);
  for (const [check,passed] of Object.entries(checks)) if (!passed) verdict.failures.push(check);
  verdict.passed = verdict.failures.length === 0;
  return verdict;
}
