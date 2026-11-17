import assert from 'node:assert/strict';
import test from 'node:test';
import { JiraWorkflowState, compareWorkflow } from './workflow-state.mjs';

const catalog = [
  {id:1,name:'Default Screen',description:'All system fields'},
  {id:3,name:'Resolve Issue Screen',description:'Resolution fields'},
  {id:2,name:'Workflow Screen',description:'Assignment fields'},
];
const statuses = [
  {id:'1',name:'New',statusCategory:{key:'new'}},
  {id:'2',name:'Done',statusCategory:{key:'done'}},
];

function graphWith(transitions) {
  return {layout:{
    statuses:[
      {id:'I<1>',initial:true},
      {id:'S<1>',statusId:'1',initial:false},
      {id:'S<2>',statusId:'2',initial:false},
    ],
    transitions:[{name:'Create',sourceId:'I<1>',targetId:'S<1>',initial:true},...transitions],
  }};
}

function stateFor(graph, screens = catalog, calls = []) {
  return new JiraWorkflowState(async (path, options = {}) => {
    calls.push(path);
    assert.equal(options.method ?? 'GET','GET');
    if (path === '/rest/api/2/workflow') return [{name:'Eval Flow'}];
    if (path === '/rest/api/2/status' || path === '/rest/workflowDesigner/1.0/statuses') return statuses;
    if (path === '/rest/api/2/statuscategory') return [{id:2,key:'new'},{id:3,key:'done'}];
    if (path === '/rest/api/2/screens') return screens;
    assert.equal(path,'/rest/workflowDesigner/1.0/workflows?name=Eval%20Flow&draft=false');
    return graph;
  });
}

const finish = {name:'Finish',actionId:10,sourceId:'S<1>',targetId:'S<2>',initial:false};

test('screen catalog uses the complete native REST array and preserves exact names and identities', async () => {
  const calls = [];
  const api = stateFor(graphWith([]),catalog,calls);
  assert.deepEqual(await api.listScreens(),catalog.map(screen => ({id:String(screen.id),name:screen.name})));
  assert.deepEqual(calls,['/rest/api/2/screens']);
  assert.deepEqual(await api.snapshot(),{
    workflows:[{name:'Eval Flow'}],
    statuses:[{id:'1',name:'New',category:'TO_DO'},{id:'2',name:'Done',category:'DONE'}],
  });
});

test('screen catalog rejects partial, synthetic, invalid, or duplicate native identities', async () => {
  for (const screens of [{values:catalog,isLast:false},[{id:0,name:'None'}],[{id:1,name:''}],[{name:'Default Screen'}],[{id:1,name:'One'},{id:'1',name:'Other'}]]) {
    await assert.rejects(stateFor(graphWith([]),screens).listScreens(),/Invalid independent Jira screen catalog/);
  }
  assert.deepEqual(await stateFor(graphWith([]),[]).listScreens(),[]);
});

test('assigned transition screen ids resolve to exact independent catalog names, not graph labels', async () => {
  const calls = [];
  const api = stateFor(graphWith([
    {...finish,screenId:3,screenName:'Incorrect designer label'},
    {name:'Reset',actionId:20,sourceId:'S<1>',targetId:'S<1>',globalTransition:true,screenId:'2'},
    {name:'Reset',actionId:20,sourceId:'S<2>',targetId:'S<1>',globalTransition:true,screenId:'2'},
  ]),catalog,calls);
  const observed = await api.readWorkflow('Eval Flow');
  assert.deepEqual(observed.transitions,[
    {name:'Finish',from:'New',to:'Done',screen:'Resolve Issue Screen'},
    {name:'Reset',from:null,to:'New',screen:'Workflow Screen'},
  ]);
  assert.equal(calls.filter(path => path === '/rest/api/2/screens').length,1);
});

test('unassigned transitions omit the screen property for missing, null and native None ids', async () => {
  for (const metadata of [{},{screenId:null},{screenId:0,screenName:'None'},{screenId:'0',screenName:'None'}]) {
    const calls = [];
    const observed = await stateFor(graphWith([{...finish,...metadata}]),catalog,calls).readWorkflow('Eval Flow');
    assert.deepEqual(observed.transitions,[{name:'Finish',from:'New',to:'Done'}]);
    assert.equal(Object.hasOwn(observed.transitions[0],'screen'),false);
    assert.equal(calls.includes('/rest/api/2/screens'),false);
  }
});

test('unresolvable screen bindings fail instead of inventing names or treating them as unassigned', async () => {
  await assert.rejects(stateFor(graphWith([{...finish,screenId:99,screenName:'Invented Screen'}])).readWorkflow('Eval Flow'),/screen absent from Jira REST/);
  await assert.rejects(stateFor(graphWith([{...finish,screenName:'Resolve Issue Screen'}])).readWorkflow('Eval Flow'),/no independently resolvable id/);
  await assert.rejects(stateFor(graphWith([{...finish,screenId:''}])).readWorkflow('Eval Flow'),/screen absent from Jira REST/);
});

test('comparison includes screen bindings, normalizes names, and equates missing and null', () => {
  const workflow = {name:'Eval Flow',statuses,initialStatus:'New',transitions:[{name:'Finish',from:'New',to:'Done'}]};
  const actual = structuredClone(workflow);
  actual.transitions[0].screen = null;
  assert.equal(compareWorkflow(workflow,actual).matches,true);
  actual.transitions[0].screen = 'Resolve Issue Screen';
  assert.equal(compareWorkflow(workflow,actual).matches,false);
  workflow.transitions[0].screen = '  resolve   ISSUE screen ';
  assert.equal(compareWorkflow(workflow,actual).matches,true);
  actual.transitions[0].screen = 'Workflow Screen';
  assert.deepEqual(compareWorkflow(workflow,actual).diff.map(entry => entry.field),['transitions']);
  delete actual.transitions[0].screen;
  assert.equal(compareWorkflow(workflow,actual).matches,false);
});
