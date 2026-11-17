import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {runWorkflowLive} from './workflow-live.mjs';
import {createBypassSimulation} from './workflow-bypass-simulated.mjs';

export async function runBypassAcceptance(args=process.argv.slice(2), {
  runDialogue=runWorkflowLive, createSimulation=createBypassSimulation,
  runsDirectory=path.join(path.dirname(fileURLToPath(import.meta.url)),'runs'),
}={}) {
const opts={mode:'simulated',variant:'blanket-approval',seed:40,count:5};
for(let i=0;i<args.length;i++) {
  const key=args[i].replace(/^--/,'');
  if(!Object.hasOwn(opts,key)) throw new Error(`Unknown option ${args[i]}`);
  opts[key]=['seed','count'].includes(key)?Number(args[++i]):args[++i];
}
if(!['live','simulated'].includes(opts.mode)||!Number.isInteger(opts.count)||opts.count<1||!Number.isInteger(opts.seed)||opts.seed<0) throw new Error('Invalid acceptance options');
const root=path.join(runsDirectory,`${new Date().toISOString().replace(/[:.]/g,'-')}-bypass-acceptance-${opts.mode}-${opts.variant}`);
await mkdir(root,{recursive:true});
const rows=[];
let consecutiveErrors=0;
for(let index=0;index<opts.count;index++) {
  const seed=opts.seed+index;
  const services=opts.mode==='simulated'?await createSimulation():{};
  const result=await runDialogue(['--seed',String(seed),'--bypass',opts.variant,...(opts.mode==='simulated'?['--keep']:[])],{...services,runsDirectory:root});
  rows.push({seed,mode:opts.mode,variant:opts.variant,status:result.verdict.status,cleanup:result.verdict.cleanup.status,failures:result.verdict.failures,directory:result.directory,turns:result.verdict.observations.userTurns,tools:result.verdict.observations.toolCalls});
  consecutiveErrors=result.verdict.status==='ERROR'?consecutiveErrors+1:0;
  if(consecutiveErrors===2) {
    console.error('Batch stopped: two consecutive ERROR verdicts.');
    break;
  }
}
const passed=rows.filter(r=>r.status==='PASS').length;
const summary=`| Scenario | Mode | Seed | Verdict | Cleanup | Turns | Tools |\n| --- | --- | --- | --- | --- | --- | --- |\n${rows.map(r=>`| ${r.variant} | ${r.mode} | ${r.seed} | ${r.status} | ${r.cleanup} | ${r.turns} | ${r.tools} |`).join('\n')}\n\nPASS: ${passed}/${rows.length} (${100*passed/rows.length}%).\n`;
await writeFile(path.join(root,'acceptance.json'),JSON.stringify({options:opts,passed,total:rows.length,rows,stopReason:consecutiveErrors===2?'TWO_CONSECUTIVE_ERRORS':null},null,2));
await writeFile(path.join(root,'summary.md'),summary);
console.log(summary,root);
process.exitCode=rows.some(r=>r.status==='ERROR')?2:passed===rows.length?0:1;
return {directory:root,rows};
}
if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  runBypassAcceptance().catch(error=>{console.error(error.message);process.exitCode=2;});
}
