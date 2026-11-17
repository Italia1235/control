import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {runWorkflowLive} from './workflow-live.mjs';
import {runBypassAcceptance} from './workflow-bypass-run.mjs';

for(const statuses of [['ERROR','ERROR','PASS'],['ERROR','FAIL','ERROR','ERROR','PASS'],['ERROR','FAIL','ERROR','INCOMPLETE','ERROR']]) {
  const expected=statuses.slice(0,statuses.findIndex((s,i)=>s==='ERROR' && statuses[i-1]==='ERROR')+1 || statuses.length);
  for(const runner of ['workflow','acceptance']) test(`${runner} cutoff: ${statuses.join(',')}`,async()=>{
    const root=await mkdtemp(path.join(tmpdir(),'batch-stop-'));
    const old=process.exitCode;
    let calls=0;
    try {
      let observed,directory;
      if(runner==='acceptance') {
        const result=await runBypassAcceptance(['--count',String(statuses.length)],{
          runsDirectory:root,createSimulation:async()=>({}),
          async runDialogue(){
            const status=statuses[calls++];
            return {directory:root,verdict:{status,cleanup:{status:'KEPT'},failures:[],observations:{userTurns:0,toolCalls:0}}};
          },
        });
        observed=result.rows.map(row=>row.status);directory=result.directory;
        const saved=JSON.parse(await readFile(path.join(directory,'acceptance.json'),'utf8'));
        assert.equal(saved.total,expected.length);
        assert.equal(saved.stopReason,expected.at(-1)==='ERROR' && expected.at(-2)==='ERROR'?'TWO_CONSECUTIVE_ERRORS':null);
      } else {
        const result=await runWorkflowLive(['--n',String(statuses.length),'--keep'],{
          runsDirectory:root,
          createToolBackend(){
            const status=statuses[calls++];
            return {mode:'live',async initialize(){
              if(status==='ERROR') throw new Error('offline infrastructure failure');
              return {instructions:'fixture',tools:[],pluginVersion:'fixture'};
            },request:async()=>null};
          },
          JiraWorkflowState:class {async snapshot(){return {workflows:[],statuses:[]};} async listScreens(){return [];} async readWorkflow(){return null;}},
          async complete(){return {role:'assistant',content:JSON.stringify({message:'',signal:'DONE',previewShown:false,answeredOmissions:[],revisionDisclosed:false})};},
        });
        // Non-errors become FAIL (missing workflow), which must also reset the streak.
        observed=result.results.map(row=>row.verdict.status);directory=result.directory;
      }
      assert.equal(calls,expected.length);
      assert.deepEqual(observed,runner==='acceptance'?expected:expected.map(s=>s==='ERROR'?s:'FAIL'));
      assert.equal(process.exitCode,2);
      const summary=await readFile(path.join(directory,'summary.md'),'utf8');
      assert.ok(summary.includes('ERROR'));
    } finally {process.exitCode=old;await rm(root,{recursive:true,force:true});}
  });
}
