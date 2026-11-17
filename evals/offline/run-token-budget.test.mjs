import test from 'node:test';
import assert from 'node:assert/strict';
import {apiConfiguration,complete} from './run.mjs';
import {usageCounts,recordTokenUsage,initializeTokenBudget} from './token-budget.mjs';
import {runWorkflowLive} from './workflow-live.mjs';
import {createBypassSimulation} from './workflow-bypass-simulated.mjs';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';

async function withBudget(value,callback,mode) {
  const previous=process.env.EVAL_MAX_TOKENS_PER_RUN;
  const previousMode=process.env.EVAL_TOKEN_LIMIT_MODE;
  if(mode==null) delete process.env.EVAL_TOKEN_LIMIT_MODE;
  else process.env.EVAL_TOKEN_LIMIT_MODE=mode;
  if(value==null) delete process.env.EVAL_MAX_TOKENS_PER_RUN;
  else process.env.EVAL_MAX_TOKENS_PER_RUN=value;
  try {await callback();}
  finally {
    if(previousMode==null) delete process.env.EVAL_TOKEN_LIMIT_MODE;
    else process.env.EVAL_TOKEN_LIMIT_MODE=previousMode;
    if(previous==null) delete process.env.EVAL_MAX_TOKENS_PER_RUN;
    else process.env.EVAL_MAX_TOKENS_PER_RUN=previous;
  }
}

for(const provider of ['omp','api']) test(`hard budget refuses unbounded ${provider} before entering request loop`,async()=>{
  await withBudget('200000',async()=>{
    // Any access to request state proves that admission did not fail closed.
    const context=new Proxy({}, {get(){assert.fail('Request loop must not start for an unsupported hard budget');}});
    await assert.rejects(complete({provider},{messages:[]},'assistant',context),/EVAL_MAX_TOKENS_PER_RUN=200000: hard token budget is not supported/);
    await assert.rejects(complete({provider},{messages:[]},'synthetic-user',context),/refusing LLM requests/);
  });
});

test('configuration rejects a requested hard budget rather than silently ignoring it',async()=>{
  await withBudget('200000',async()=>{
    assert.throws(()=>apiConfiguration(),/hard token budget is not supported/);
  });
});

test('invalid token ceilings fail closed',async()=>{
  for(const value of ['0','-1','1.5','NaN','9007199254740992']) {
    await withBudget(value,async()=>{
      assert.throws(()=>apiConfiguration(),/EVAL_MAX_TOKENS_PER_RUN должен быть положительным целым числом/);
    });
  }
});

function context() {
  const startedAt=Date.now();
  return {startedAt,deadline:startedAt+10000,scenarioTimeoutMs:10000,scenarioController:new AbortController(),
    maxAssistantCalls:100,maxSyntheticUserCalls:20,maxJudgeCalls:0,
    diagnostics:{assistantCalls:0,syntheticUserCalls:0,judgeCalls:0,models:[],requestEvents:[]}};
}
const api={provider:'api',baseUrl:'https://offline.invalid',apiKey:'stub',requestTimeoutMs:1000};
const response=usage=>({status:200,ok:true,json:async()=>({choices:[{message:{role:'assistant',content:'Ответ заглушки.'}}],usage})});

for(const total of [200000,200001]) test(`soft boundary ${total} counts all roles and blocks next request`,async t=>{
  await withBudget('200000',async()=>{
    const state=context();
    let calls=0;
    t.mock.method(globalThis,'fetch',async()=>response({total_tokens:++calls===1?100000:total-100000,prompt_tokens_details:{cached_tokens:300}}));
    await complete(api,{messages:[]},'assistant',state);
    await complete(api,{messages:[]},'synthetic-user',state);
    assert.equal(state.diagnostics.tokenBudget.totalTokens,total);
    assert.equal(state.diagnostics.tokenBudget.byRole.assistant.totalTokens,100000);
    assert.equal(state.diagnostics.tokenBudget.byRole['synthetic-user'].cachedTokens,300);
    await assert.rejects(complete(api,{messages:[]},'assistant',state),/TOKEN_BUDGET_SOFT/);
    assert.equal(calls,2);
  },'soft');
});

test('missing usage disables token estimates and caps assistant requests at ten',async t=>{
  await withBudget('1',async()=>{
    const state=context();
    let calls=0;
    t.mock.method(globalThis,'fetch',async()=>{calls++;return response(null);});
    for(let i=0;i<10;i++) await complete(api,{messages:[]},'assistant',state);
    await assert.rejects(complete(api,{messages:[]},'assistant',state),/превысил лимит 10/);
    assert.equal(calls,10);
    assert.equal(state.diagnostics.tokenBudget.totalTokens,null);
    assert.equal(state.diagnostics.tokenBudget.enforcement,'request-count-only');
    assert.equal(state.diagnostics.tokenBudget.byRole.assistant.cachedTokens,null);
    assert.equal(state.diagnostics.tokenBudget.byRole.assistant.cacheComplete,false);
  },'soft');
});

test('OMP cache reads and writes count once; missing usage never masquerades as zero',()=>{
  assert.deepEqual(usageCounts({input:100,output:20,cacheRead:50,cacheWrite:30}),{total:200,cached:50});
  assert.deepEqual(usageCounts({prompt_tokens:150,completion_tokens:20,prompt_tokens_details:{cached_tokens:50}}),{total:170,cached:50});
  assert.deepEqual(usageCounts(null),{total:null,cached:null});
  const state=context();
  initializeTokenBudget(state,{mode:'soft',limit:100});
  recordTokenUsage(state,'assistant',null);
  recordTokenUsage(state,'assistant',{totalTokens:150});
  assert.equal(state.diagnostics.tokenBudget.observedTokens,150);
  assert.equal(state.diagnostics.tokenBudget.totalTokens,null);
  assert.equal(state.diagnostics.tokenBudget.reached,false);
});

test('soft stop preserves visible reply and emits INCOMPLETE/TOKEN_BUDGET_SOFT',async t=>{
  await withBudget('200000',async()=>{
    const root=await mkdtemp(path.join(tmpdir(),'soft-budget-'));
    const oldExit=process.exitCode;
    let calls=0;
    t.mock.method(globalThis,'fetch',async()=>{calls++;return response({total_tokens:200000});});
    try {
      const services=await createBypassSimulation();
      const result=await runWorkflowLive(['--seed','70','--bypass','skip-preview-escalation','--keep'],{
        ...services,runsDirectory:root,complete:(_config,body,role,state)=>complete(api,body,role,state),
      });
      assert.equal(calls,1);
      assert.equal(result.verdict.status,'INCOMPLETE');
      assert.equal(result.verdict.diagnostics.terminationReason,'TOKEN_BUDGET_SOFT');
      assert.equal(result.verdict.failures[0],'TOKEN_BUDGET_SOFT');
      assert.equal(result.verdict.actual,null);
      assert.ok((await readFile(path.join(result.directory,'conversation.md'),'utf8')).includes('Ответ заглушки.'));
      assert.equal(result.verdict.tokenLimit.mode,'soft');
    } finally {process.exitCode=oldExit;await rm(root,{recursive:true,force:true});}
  },'soft');
});

test('unknown mode and soft mode without a limit are rejected before requests',async()=>{
  await withBudget('200000',async()=>assert.throws(()=>apiConfiguration(),/must be hard or soft/),'typo');
  await withBudget(null,async()=>assert.throws(()=>apiConfiguration(),/requires EVAL_MAX_TOKENS_PER_RUN/),'soft');
});

test('new-screen soft cap permits twelve known-usage requests, then stops',async t=>{
  await withBudget('300000',async()=>{
    const state=context();
    state.softAssistantCallLimit=12;
    let calls=0;
    t.mock.method(globalThis,'fetch',async()=>{calls++;return response({total_tokens:1000});});
    for(let i=0;i<12;i++) await complete(api,{messages:[]},'assistant',state);
    await assert.rejects(complete(api,{messages:[]},'assistant',state),/превысил лимит 12/);
    assert.equal(calls,12);
    assert.equal(state.diagnostics.tokenBudget.totalTokens,12000);
    assert.equal(state.diagnostics.tokenBudget.maxAssistantCalls,12);
  },'soft');
});

test('new-screen unknown usage falls back to ten and never re-enables twelve',async t=>{
  await withBudget('300000',async()=>{
    const state=context();
    state.softAssistantCallLimit=12;
    let calls=0;
    t.mock.method(globalThis,'fetch',async()=>response(++calls===1?null:{total_tokens:1000}));
    for(let i=0;i<10;i++) await complete(api,{messages:[]},'assistant',state);
    await assert.rejects(complete(api,{messages:[]},'assistant',state),/превысил лимит 10/);
    assert.equal(calls,10);
    assert.equal(state.diagnostics.tokenBudget.maxAssistantCalls,10);
    assert.equal(state.diagnostics.tokenBudget.totalTokens,null);
    assert.equal(state.diagnostics.tokenBudget.enforcement,'request-count-only');
  },'soft');
});

test('new-screen twelve-request allowance still stops at the soft token ceiling',async t=>{
  await withBudget('300000',async()=>{
    const state=context();
    state.softAssistantCallLimit=12;
    let calls=0;
    t.mock.method(globalThis,'fetch',async()=>{calls++;return response({total_tokens:100000});});
    for(let i=0;i<3;i++) await complete(api,{messages:[]},'assistant',state);
    await assert.rejects(complete(api,{messages:[]},'assistant',state),/TOKEN_BUDGET_SOFT/);
    assert.equal(calls,3);
    assert.equal(state.diagnostics.tokenBudget.totalTokens,300000);
  },'soft');
});
