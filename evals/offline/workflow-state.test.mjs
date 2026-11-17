import assert from 'node:assert/strict';
import test from 'node:test';
import { compareWorkflow, cleanupCreated } from './workflow-state.mjs';

const workflow = { name: 'Eval Flow', statuses: [{name:'New',category:'TO_DO'}, {name:'Done',category:'DONE'}], initialStatus:'New', transitions:[{name:'Finish',from:'New',to:'Done'}] };
const before = { workflows:[{id:'1',name:'Existing'}], statuses:[{id:'1',name:'New',category:'TO_DO'}] };

test('workflow comparison tolerates case/whitespace and order, not category/start/edge drift', () => {
  const actual = structuredClone(workflow);
  actual.name = '  eval   FLOW ';
  actual.statuses.reverse();
  assert.deepEqual(compareWorkflow(workflow, actual).diff, []);
  actual.statuses[0].category = 'IN_PROGRESS';
  actual.initialStatus = 'Done';
  actual.transitions[0].from = 'Done';
  const result = compareWorkflow(workflow, actual);
  assert.equal(result.matches, false);
  assert.deepEqual(result.diff.map(x => x.field).sort(), ['initialStatus','statuses','transitions']);
  assert.equal(compareWorkflow(workflow, null).matches, false);
});

test('cleanup requires new identity, expected name AND creation evidence, workflow before statuses', async () => {
  const after = {workflows:[...before.workflows,{id:'2',name:'Eval Flow'},{id:'3',name:'Other'}], statuses:[...before.statuses,{id:'2',name:'Done',category:'DONE'},{id:'3',name:'Other'}]};
  const deleted = [];
  const api = {snapshot:async () => structuredClone(after), deleteWorkflow:async e => {deleted.push(['workflow',e.id]);after.workflows=after.workflows.filter(x=>x.id!==e.id);}, deleteStatus:async e => {deleted.push(['status',e.id]);after.statuses=after.statuses.filter(x=>x.id!==e.id);}};
  const result = await cleanupCreated({api,before,workflow,created:{workflowNames:['Eval Flow'],statusIds:['1','2']}});
  assert.deepEqual(deleted,[['workflow','2'],['status','2']]);
  assert.equal(result.status,'CLEAN');
  assert.equal(result.previousPreserved,true);
  assert.ok(after.workflows.some(x=>x.id==='3'));
  assert.ok(after.statuses.some(x=>x.id==='1'));
});

test('cleanup does not delete a new matching name without this dialogue creation evidence', async () => {
  const snapshot = { workflows:[...before.workflows,{id:'2',name:'Eval Flow'}], statuses:[...before.statuses,{id:'2',name:'Done'}] };
  const api = {snapshot:async()=>snapshot,deleteWorkflow:async()=>assert.fail('unowned workflow'),deleteStatus:async()=>assert.fail('unowned status')};
  const result = await cleanupCreated({api,before,workflow,created:{workflowNames:[],statusIds:[]}});
  assert.equal(result.status,'WARNING');
  assert.match(result.warnings.join(' '),/reset\.sh/);
});

test('cleanup failure is warning and preserves existing entities', async () => {
  const snapshot = {workflows:[...before.workflows,{id:'2',name:'Eval Flow'}],statuses:before.statuses};
  const api = {snapshot:async()=>snapshot,deleteWorkflow:async()=>{throw new Error('workflow is in use');},deleteStatus:async()=>assert.fail('old status')};
  const result = await cleanupCreated({api,before,workflow,created:{workflowNames:['Eval Flow'],statusIds:[]}});
  assert.equal(result.status,'WARNING');
  assert.equal(result.previousPreserved,true);
  assert.match(result.warnings.join(' '),/in use/);
});
