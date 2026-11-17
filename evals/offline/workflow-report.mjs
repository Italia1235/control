import path from 'node:path';
import { usageCounts, SOFT_LIMIT_NOTICE } from './token-budget.mjs';

const cell = value => String(value ?? '').replace(/\r?\n/gu, ' ').replace(/\|/gu, '\\|');
export function renderWorkflowBatchSummary(results, {rootDirectory} = {}) {
  const rows=results.map(({directory,verdict},index)=>{
    const events=verdict.diagnostics?.requestEvents ?? [];
    const successful=events.filter(event=>event.outcome==='ok');
    const usageKnown=successful.length>0 && successful.every(event=>usageCounts(event.usage).total!=null);
    const tokens=usageKnown?successful.reduce((sum,event)=>sum+usageCounts(event.usage).total,0):'n/a';
    const costs=events.filter(event=>event.outcome==='ok').map(event=>event.usage?.cost?.total);
    const cost=costs.length && costs.every(value=>typeof value==='number')?costs.reduce((sum,value)=>sum+value,0).toFixed(6):'n/a';
    const firstFailure=verdict.failures?.[0];
    const reason=typeof firstFailure==='string'?firstFailure:firstFailure?.field?`state: ${firstFailure.field}`:verdict.passed?'—':verdict.diagnostics?.terminationReason ?? verdict.status;
    const artifact=path.relative(rootDirectory ?? path.dirname(directory),path.join(directory,'verdict.json')).replace(/\\/gu,'/');
    return [index+1,verdict.seed,verdict.persona,verdict.expected?.statuses?.length ?? 0,verdict.expected?.transitions?.length ?? 0,
      verdict.observations?.assistantTurns ?? 0,verdict.observations?.toolCalls ?? 0,tokens,cost,
      `${verdict.omissionChecks?.omissions?.length ?? 0}/${verdict.omissionChecks?.omissions?.filter(item=>item.asked).length ?? 0}`,verdict.omissionChecks?.autonomousDecisions?.length ?? 0,
      verdict.status,verdict.cleanup?.status ?? 'NOT_STARTED',reason,`[artifacts](${artifact})`];
  });
  const header=['№','Seed','Persona','Statuses','Transitions','Turns','Tools','Tokens','Cost (provider)','Omitted/Asked','Autonomous','Verdict','Cleanup','Reason','Artifacts'];
  const statuses=['PASS','FAIL','SAFETY_VIOLATION','INCOMPLETE','ERROR'].map(status=>`${status}: ${results.filter(result=>result.verdict.status===status).length}`).join('; ');
  return ['# Workflow live run','',`Dialogs: ${results.length}. ${statuses}.`,'','ERROR is an infrastructure failure, not an assistant failure. Autonomous omitted decisions are listed separately in each verdict. Cost uses provider-reported accounting, not an estimated bill.','',
    ...(results.some(result=>result.verdict.tokenLimit?.mode==='soft')?[SOFT_LIMIT_NOTICE,'']:[]),
    `| ${header.join(' | ')} |`,`| ${header.map(()=> '---').join(' | ')} |`,
    ...rows.map(row=>`| ${row.map(cell).join(' | ')} |`),''].join('\n');
}
