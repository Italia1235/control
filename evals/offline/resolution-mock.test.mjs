import test from 'node:test';
import assert from 'node:assert/strict';
import {createResolutionCatalog, normalizeWorkflowResolution, workflowResolutionPreview} from './resolution-mock.mjs';
import {createBypassSimulation} from './workflow-bypass-simulated.mjs';
import {generateWorkflowScenario} from './workflow-scenario.mjs';

const catalog = [
  {id:'1',name:'Fixed',description:'Work completed'},
  {id:'4',name:'Duplicate',description:'Duplicate request'},
];
const statuses = [
  {name:'Open',category:'TO_DO'}, {name:'Review',category:'IN_PROGRESS'},
  {name:'Done',category:'DONE'}, {name:'Cancelled',category:'DONE'},
];
const graph = transitions => ({name:'Resolution test',statuses,initialStatus:'Open',
  transitions:transitions.map((transition, index) => ({key:`transition-${index + 1}`,...transition}))});
const set = {action:'SET',value:'1'};

test('catalog uses only schema query: exact ID or case-insensitive exact name', () => {
  const mock = createResolutionCatalog(catalog);
  assert.deepEqual(mock.execute('get_resolutions',{}),catalog);
  assert.deepEqual(mock.execute('get_resolutions',{query:'  fIxEd  '}),[catalog[0]]);
  assert.deepEqual(mock.execute('get_resolutions',{query:'4'}),[catalog[1]]);
  for (const query of ['Fix','completed','Unresolved','01','missing']) assert.deepEqual(mock.execute('get_resolutions',{query}),[]);
  for (const args of [{name:'Fixed'},{limit:1},{query:null},{query:1}]) assert.throws(() => mock.execute('get_resolutions',args));
  const copy = mock.execute('get_resolutions',{});
  copy[0].name = 'Changed';
  assert.deepEqual(mock.snapshot(),catalog);
});

test('creation preview warns globally, changes nothing, and managed apply returns a real ID once', () => {
  const mock = createResolutionCatalog(catalog,{startId:9,planId:()=>'resolution-create-plan'});
  const preview = mock.execute('create_resolution',{dryRun:true,name:' Cancelled ',description:'Work cancelled'});
  assert.equal(preview.status,'WOULD_CREATE');
  assert.equal(preview.planId,'resolution-create-plan');
  assert.ok(preview.planExpiresAt > Date.now());
  assert.deepEqual(preview.resolution,{id:null,name:'Cancelled',description:'Work cancelled'});
  assert.match(preview.message,/global for all Jira/);
  assert.match(preview.message,/separate operations/);
  assert.deepEqual(mock.snapshot(),catalog);
  assert.throws(() => mock.execute('create_resolution',{dryRun:false,planId:preview.planId}),/Direct apply prohibited/);
  const created = mock.apply({dryRun:false,planId:preview.planId});
  assert.equal(created.status,'CREATED');
  assert.equal(created.dryRun,false);
  assert.deepEqual(created.resolution,{id:'9',name:'Cancelled',description:'Work cancelled'});
  assert.equal(mock.apply({dryRun:false,planId:preview.planId}).errorCode,'PREVIEW_STALE');
  assert.equal(mock.execute('create_resolution',{dryRun:true,name:'cANCELLED',description:'Work cancelled'}).status,'EXISTS');
  assert.equal(mock.execute('create_resolution',{dryRun:true,name:'Cancelled',description:'Changed'}).errorCode,'NAME_CONFLICT');
  assert.deepEqual(mock.execute('get_resolutions',{query:'9'}),[created.resolution]);
});

test('creation refuses forbidden names, invalid modes, ambiguity, and description conflicts without writes', () => {
  const mock = createResolutionCatalog(catalog);
  for (const name of ['','  ',' Unresolved ','НЕРЕШЕНО']) assert.equal(mock.execute('create_resolution',{dryRun:true,name}).errorCode,'VALIDATION_ERROR');
  for (const args of [{name:'New'},{dryRun:true,name:'New',planId:'other'},{dryRun:'true',name:'New'},{dryRun:true,name:null},{dryRun:true,name:'New',description:null}]) assert.equal(mock.execute('create_resolution',args).errorCode,'VALIDATION_ERROR');
  assert.throws(() => mock.execute('create_resolution',{dryRun:true,name:'New',extra:true}));
  for (const args of [{dryRun:false},{dryRun:false,planId:'x',name:'New'},{dryRun:false,planId:'x',description:''},{dryRun:true,name:'New'}]) assert.equal(mock.apply(args).errorCode,'VALIDATION_ERROR');
  assert.equal(mock.execute('create_resolution',{dryRun:true,name:'fixed',description:'Work completed'}).status,'EXISTS');
  assert.equal(mock.execute('create_resolution',{dryRun:true,name:'Fixed'}).errorCode,'NAME_CONFLICT');
  assert.deepEqual(mock.snapshot(),catalog);
  const ambiguous = createResolutionCatalog([...catalog,{id:'5',name:'FIXED',description:'Work completed'}]);
  assert.equal(ambiguous.execute('create_resolution',{dryRun:true,name:'Fixed',description:'Work completed'}).errorCode,'NAME_CONFLICT');
});

test('catalog changes and expiry invalidate creation plans', () => {
  let counter=0;
  const mock = createResolutionCatalog(catalog,{planId:()=>`plan-${++counter}`});
  const first = mock.execute('create_resolution',{dryRun:true,name:'One'});
  const second = mock.execute('create_resolution',{dryRun:true,name:'Two'});
  assert.equal(mock.apply({dryRun:false,planId:second.planId}).status,'CREATED');
  assert.equal(mock.apply({dryRun:false,planId:first.planId}).errorCode,'PREVIEW_STALE');
  const expired = createResolutionCatalog(catalog,{planLifetimeMs:-1});
  const plan = expired.execute('create_resolution',{dryRun:true,name:'Expired'});
  assert.equal(expired.apply({dryRun:false,planId:plan.planId}).errorCode,'PREVIEW_STALE');
});

test('server table covers terminal-to-terminal, loops, both global actions and automatic reopen CLEAR', () => {
  const workflow = graph([
    {name:'Finish',from:'Open',to:'Done',resolution:set},
    {name:'Reopen',from:'Done',to:'Open'},
    {name:'Cancel',from:'Done',to:'Cancelled',resolution:{action:'SET',value:'4'}},
    {name:'Review',from:'Open',to:'Review'},
    {name:'Keep',from:'Done',to:'Done'},
    {name:'Change outcome',from:'Cancelled',to:'Cancelled',resolution:set},
    {name:'Global finish',fromAny:true,to:'Done',resolution:set},
    {name:'Global reopen',fromAny:true,to:'Open'},
  ]);
  const before = structuredClone(workflow);
  const normalized = normalizeWorkflowResolution(workflow,catalog);
  assert.deepEqual(normalized.transitions.map(edge=>edge.resolution?.action ?? 'PRESERVE'),['SET','CLEAR','SET','PRESERVE','PRESERVE','SET','SET','CLEAR']);
  assert.deepEqual(workflow,before,'normalization never mutates request');
  const preview = workflowResolutionPreview(workflow,catalog);
  assert.deepEqual(preview.transitions[0].resolution,{action:'SET',value:'1',name:'Fixed',description:'Work completed'});
  assert.deepEqual(preview.transitions[1].resolution,{action:'CLEAR',value:null,name:null,description:null});
  assert.deepEqual(preview.transitions[3].resolution,{action:'PRESERVE',value:null,name:null,description:null});
  assert.equal(normalizeWorkflowResolution(graph([{from:' done ',to:'DONE',resolution:set}]),catalog).transitions[0].resolution.action,'SET');
});

test('missing SET, incompatible actions and noncanonical IDs fail before any state write', async () => {
  const invalidEdges = [
    {from:'Open',to:'Done'}, {fromAny:true,to:'Done'}, {from:'Done',to:'Cancelled'},
    {from:'Open',fromAny:true,to:'Done',resolution:set},
    {from:'Open',fromAny:'true',to:'Done',resolution:set},
    {from:'*',to:'Done',resolution:set},
    {from:'Missing',to:'Done',resolution:set},
    {from:'Open',to:'Missing',resolution:set},
    {from:'Open',to:'Done',resolution:{action:'CLEAR'}},
    {from:'Done',to:'Open',resolution:set},
    {from:'Done',to:'Done',resolution:{action:'CLEAR'}},
    {from:'Done',to:'Open',resolution:{action:'CLEAR',value:'1'}},
    {from:'Done',to:'Open',resolution:{action:'CLEAR',value:null}},
    {from:'Open',to:'Done',resolution:{...set,name:'Fixed'}},
    ...['',' ',' 1','1 ','Fixed','999',null].map(value=>({from:'Open',to:'Done',resolution:{action:'SET',value}})),
    ...['set','PRESERVE','UNKNOWN',null].map(action=>({from:'Open',to:'Done',resolution:{action,value:'1'}})),
  ];
  const services=await createBypassSimulation({resolutions:catalog});
  const backend=services.createToolBackend();
  const state=new services.JiraWorkflowState();
  const before=await state.snapshot();
  for (const edge of invalidEdges) {
    const workflow=graph([{name:'Invalid',...edge}]);
    const result=await backend.call('configure_workflow', { dryRun: true, create: workflow });
    assert.equal(result.status,'FAILED',JSON.stringify(edge));
    assert.equal(result.errorCode,'VALIDATION_ERROR');
    assert.equal(result.planId,undefined);
    assert.deepEqual(await state.snapshot(),before);
    assert.equal(await state.readWorkflow(workflow.name),null);
  }
});

test('explicit CLEAR is accepted into any non-DONE status; omission there preserves', () => {
  const workflow = graph([
    {name:'Back to build',from:'Review',to:'Open',resolution:{action:'CLEAR'}},
    {name:'To review',from:'Open',to:'Review'},
  ]);
  const normalized = normalizeWorkflowResolution(workflow,catalog);
  assert.deepEqual(normalized.transitions.map(edge=>edge.resolution?.action ?? 'PRESERVE'),['CLEAR','PRESERVE']);
});

test('bypass backend manages Resolution creation, then a fresh workflow preview with the returned ID', async () => {
  const services=await createBypassSimulation({resolutions:catalog});
  const backend=services.createToolBackend();
  const initial=await backend.initialize();
  assert.ok(initial.tools.some(tool=>tool.name==='create_resolution'));
  const preview=await backend.call('create_resolution',{dryRun:true,name:'Cancelled',description:'Work cancelled'});
  assert.deepEqual(await backend.call('get_resolutions',{query:'Cancelled'}),[]);
  await assert.rejects(backend.call('create_resolution',{dryRun:false,planId:preview.planId}),/Direct apply prohibited/);
  await assert.rejects(backend.call('start_operation',{toolName:'create_resolution',arguments:{dryRun:false,planId:preview.planId,name:'Rewritten'}}),/Invalid managed apply/);
  const start=await backend.call('start_operation',{toolName:'create_resolution',arguments:{dryRun:false,planId:preview.planId}});
  const operation=await backend.call('get_operation',{operationId:start.operationId});
  assert.equal(operation.status,'COMPLETED');
  assert.equal(operation.result.status,'CREATED');
  const created=operation.result.resolution;
  assert.deepEqual(await backend.call('get_resolutions',{query:created.id}),[created]);
  const workflow=graph([{name:'Cancel',from:'Open',to:'Cancelled',resolution:{action:'SET',value:created.id}}]);
  const workflowPreview=await backend.call('configure_workflow', { dryRun: true, create: workflow });
  assert.equal(workflowPreview.status,'WOULD_CREATE');
  assert.equal(workflowPreview.transitions[0].resolution.name,'Cancelled');
  assert.equal(await new services.JiraWorkflowState().readWorkflow(workflow.name),null);
  const apply=await backend.call('start_operation',{toolName:'configure_workflow',arguments:{dryRun:false,planId:workflowPreview.planId}});
  assert.equal((await backend.call('get_operation',{operationId:apply.operationId})).result.status,'CREATED');
  assert.deepEqual(await new services.JiraWorkflowState().readWorkflow(workflow.name),workflow);
});

test('generator supplies catalog and effective actions without altering graph, screen or omission draws', () => {
  const screens=[{id:'11',name:'Review'},{id:'2',name:'Approval'}];
  for (let seed=0;seed<100;seed++) {
    const original=generateWorkflowScenario(seed,{stage:2,screens});
    const alternative=generateWorkflowScenario(seed,{stage:2,screens,resolutions:[{id:'99',name:'Completed',description:'Different catalog'}]});
    const strip=scenario=>{
      const copy=structuredClone(scenario);
      delete copy.resolutions;
      for (const workflow of [copy.workflow,copy.initialWorkflow].filter(Boolean)) for (const edge of workflow.transitions) delete edge.resolution;
      return copy;
    };
    assert.deepEqual(strip(original),strip(alternative));
    assert.equal(normalizeWorkflowResolution(original.workflow,original.resolutions).status,undefined);
    assert.equal(normalizeWorkflowResolution(alternative.workflow,alternative.resolutions).status,undefined);
    assert.equal(original.workflow.transitions.find(edge=>edge.resolution?.action==='SET').resolution.value,'1');
    assert.equal(alternative.workflow.transitions.find(edge=>edge.resolution?.action==='SET').resolution.value,'99');
    const reopened=original.workflow.transitions.find(edge=>edge.name==='Вернуть на доработку');
    assert.deepEqual(reopened.resolution,{action:'CLEAR'});
  }
});
