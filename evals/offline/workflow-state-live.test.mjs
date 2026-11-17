import assert from 'node:assert/strict';
import test from 'node:test';
import { JiraWorkflowState, cleanupCreated, compareWorkflow } from './workflow-state.mjs';

const restStatuses = [
  {id:'1',name:'New',statusCategory:{key:'new'}},
  {id:'2',name:'Done',statusCategory:{key:'done'}},
];

const designer = {layout:{
  statuses:[
    {id:'I<1>',name:'Create',initial:true},
    {id:'S<1>',statusId:'1',name:'Misleading designer name',initial:false,statusCategory:{id:4}},
    {id:'S<2>',statusId:'2',name:'Done',initial:false},
  ],
  transitions:[
    {name:'Create',sourceId:'I<1>',targetId:'S<1>',initial:true},
    {name:'Finish',sourceId:'S<1>',targetId:'S<2>',initial:false},
    {name:'Reset',actionId:20,sourceId:'S<1>',targetId:'S<1>',globalTransition:true},
    {name:'Reset',actionId:20,sourceId:'S<2>',targetId:'S<1>',globalTransition:true},
  ],
}};

test('independent state uses name identity, REST categories, initial target, and global edges', async () => {
  const calls = [];
  const request = async (path, options) => {
    calls.push([path,options]);
    if (path === '/rest/api/2/workflow') return [{name:'  EVAL Flow  '}];
    if (path === '/rest/api/2/status') return restStatuses;
    if (path === '/rest/workflowDesigner/1.0/statuses') return restStatuses;
    if (path === '/rest/api/2/statuscategory') return [{id:2,key:'new'},{id:3,key:'done'}];
    assert.equal(path,'/rest/workflowDesigner/1.0/workflows?name=%20%20EVAL%20Flow%20%20&draft=false');
    assert.equal(options.headers['X-Atlassian-Token'],'no-check');
    return designer;
  };
  const api = new JiraWorkflowState(request);
  assert.deepEqual(await api.snapshot(),{workflows:[{name:'  EVAL Flow  '}],statuses:[{id:'1',name:'New',category:'TO_DO'},{id:'2',name:'Done',category:'DONE'}]});
  const observed = await api.readWorkflow('eval   flow');
  assert.equal(observed.initialStatus,'New');
  assert.deepEqual(observed.transitions,[{name:'Finish',from:'New',to:'Done'},{name:'Reset',from:null,to:'New'}]);
  assert.equal(compareWorkflow({name:'Eval Flow',statuses:[{name:'New',category:'TO_DO'},{name:'Done',category:'DONE'}],initialStatus:'New',transitions:[{name:'Reset',from:'*',to:'New'},{name:'Finish',from:'New',to:'Done'}]},observed).matches,true);
  assert.equal(await api.readWorkflow('missing'),null);
  assert.ok(calls.every(([path]) => path.startsWith('/rest/')));
});

test('cleanup preserves existing name-only workflow identities and unrelated creations', async () => {
  const before = {workflows:[{name:'Existing'}],statuses:[{id:'1',name:'New'}]};
  const live = {workflows:[...before.workflows,{name:'Eval Flow'},{name:'Unrelated'}],statuses:[...before.statuses,{id:'2',name:'Done'},{id:'3',name:'Unrelated'}]};
  const deleted = [];
  const api = {
    snapshot:async () => structuredClone(live),
    deleteWorkflow:async entity => {deleted.push(entity.name);live.workflows=live.workflows.filter(x=>x.name!==entity.name);},
    deleteStatus:async entity => {deleted.push(entity.id);live.statuses=live.statuses.filter(x=>x.id!==entity.id);},
  };
  const result = await cleanupCreated({api,before,workflow:{name:'Eval Flow',statuses:[{name:'New'},{name:'Done'}]},created:{workflowNames:['Existing','Eval Flow','Unrelated'],statusIds:['1','2','3']}});
  assert.deepEqual(deleted,['Eval Flow','2']);
  assert.equal(result.previousPreserved,true);
  assert.equal(result.status,'CLEAN');
  assert.deepEqual(live,{workflows:[{name:'Existing'},{name:'Unrelated'}],statuses:[{id:'1',name:'New'},{id:'3',name:'Unrelated'}]});
});

test('cleanup verifies REST removal rather than trusting a successful deletion call', async () => {
  const before = {workflows:[],statuses:[]};
  const live = {workflows:[{name:'Eval Flow'}],statuses:[{id:'2',name:'Done'}]};
  const result = await cleanupCreated({api:{snapshot:async()=>structuredClone(live),deleteWorkflow:async()=>{},deleteStatus:async()=>assert.fail('workflow still exists')},before,workflow:{name:'Eval Flow',statuses:[{name:'Done'}]},created:{workflowNames:['Eval Flow'],statusIds:['2']}});
  assert.equal(result.status,'WARNING');
  assert.equal(result.previousPreserved,true);
  assert.match(result.warnings.join(' '),/still present/);
  assert.match(result.warnings.join(' '),/No reset\.sh exists.*hypothetical/);
});

function deleteForm(kind, value) {
  return kind === 'workflow'
    ? `<form action="DeleteWorkflow.jspa" method="post"><input type="hidden" name="workflowName" value="${value}"><input type="hidden" name="workflowMode"><input type="hidden" name="confirmedDelete" value="true"><input type="hidden" name="atl_token" value="csrf"><input type="submit" name="Delete" value="Delete"></form>`
    : `<form action="DeleteStatus.jspa" method="post"><input type="hidden" name="id" value="${value}"><input type="hidden" name="confirm" value="true"><input type="hidden" name="atl_token" value="csrf"><input type="submit" name="Delete" value="Delete"></form>`;
}

test('native cleanup authenticates WebSudo then posts Jira form CSRF without bypasses', async () => {
  const workflowName = 'Eval & Flow';
  const path = '/secure/admin/workflows/DeleteWorkflow!default.jspa?workflowName=Eval%20%26%20Flow';
  let authenticated = false;
  let deleted = false;
  const posts = [];
  const request = async (resource, options = {}) => {
    if (resource === '/rest/api/2/workflow') return deleted ? [] : [{name:workflowName}];
    if (resource === '/rest/api/2/status') return restStatuses;
    if (resource === '/rest/workflowDesigner/1.0/statuses') return restStatuses;
    if (options.method === 'POST') {
      const fields = new URLSearchParams(options.body);
      posts.push(resource);
      assert.equal(options.headers['content-type'],'application/x-www-form-urlencoded');
      assert.equal(options.headers['X-Atlassian-Token'],undefined);
      assert.equal(fields.get('atl_token'),'csrf');
      assert.equal(fields.has('force'),false);
      if (resource === '/secure/admin/WebSudoAuthenticate.jspa') {
        assert.equal(fields.get('webSudoPassword'),'test-password');
        authenticated = true;
        return {redirect:path};
      }
      assert.equal(resource,'/secure/admin/workflows/DeleteWorkflow.jspa');
      assert.equal(fields.get('workflowName'),workflowName);
      assert.equal(fields.get('confirmedDelete'),'true');
      assert.equal(fields.get('workflowMode'),'');
      assert.equal(fields.get('Delete'),'Delete');
      deleted = true;
      return {redirect:'/secure/admin/workflows/ListWorkflows.jspa'};
    }
    if (resource === '/secure/admin/workflows/ListWorkflows.jspa') return '<html>Workflows</html>';
    assert.equal(resource,path);
    if (authenticated) return deleteForm('workflow','Eval &amp; Flow');
    return `<meta name="ajs-remote-user" content="test-user"><form action="/secure/admin/WebSudoAuthenticate.jspa" method="post"><input name="webSudoPassword" type="password"><input name="webSudoDestination" type="hidden" value="${path}"><input name="webSudoIsPost" type="hidden" value="false"><input name="atl_token" type="hidden" value="csrf"></form>`;
  };
  await new JiraWorkflowState(request,{username:'test-user',password:'test-password'}).deleteWorkflow({name:workflowName});
  assert.deepEqual(posts,['/secure/admin/WebSudoAuthenticate.jspa','/secure/admin/workflows/DeleteWorkflow.jspa']);
});

test('native status deletion uses its offered form and proves disappearance through REST', async () => {
  let deleted = false;
  const request = async (path,options = {}) => {
    if (path === '/rest/api/2/workflow') return [];
    if (path === '/rest/api/2/status') return deleted ? [] : [restStatuses[1]];
    if (path === '/rest/workflowDesigner/1.0/statuses') return deleted ? [] : [restStatuses[1]];
    if (path === '/secure/admin/DeleteStatus!default.jspa?id=2') return deleteForm('status','2');
    assert.equal(path,'/secure/admin/DeleteStatus.jspa');
    assert.equal(options.method,'POST');
    const fields = new URLSearchParams(options.body);
    assert.equal(fields.get('id'),'2');
    assert.equal(fields.get('confirm'),'true');
    assert.equal(fields.get('atl_token'),'csrf');
    assert.equal(options.headers['X-Atlassian-Token'],undefined);
    deleted = true;
    return '<html>Deleted</html>';
  };
  await new JiraWorkflowState(request).deleteStatus({id:'2',name:'Done'});
});

test('native deletion refuses unavailable, wrong-identity, or off-origin forms without POST', async () => {
  for (const html of ['<html>Workflow is in use</html>',deleteForm('workflow','Other'),deleteForm('workflow','Eval Flow').replace('DeleteWorkflow.jspa','http://external.test/DeleteWorkflow.jspa')]) {
    const request = async (_path,options = {}) => {
      assert.notEqual(options.method,'POST');
      return html;
    };
    await assert.rejects(new JiraWorkflowState(request).deleteWorkflow({name:'Eval Flow'}),/supported native|Unsupported native/);
  }
});

test('missing prior identities and unavailable snapshots become cleanup warnings', async () => {
  const before = {workflows:[{name:'Existing'}],statuses:[{id:'1',name:'New'}]};
  const workflow = {name:'Eval Flow',statuses:[]};
  const missing = await cleanupCreated({api:{snapshot:async()=>({workflows:[],statuses:[]})},before,workflow,created:{}});
  assert.equal(missing.previousPreserved,false);
  assert.equal(missing.status,'WARNING');
  const unavailable = await cleanupCreated({api:{snapshot:async()=>{throw new Error('HTTP 503');}},before,workflow,created:{}});
  assert.equal(unavailable.status,'WARNING');
  assert.equal(unavailable.previousPreserved,null);
  assert.match(unavailable.warnings.join(' '),/REST verification/);
});

test('global REST snapshot and graph categories include statuses hidden by project permissions', async () => {
  const graph = structuredClone(designer);
  graph.layout.statuses[1].statusCategory = {id:2};
  graph.layout.statuses[2].statusCategory = {id:3};
  const api = new JiraWorkflowState(async path => {
    if (path === '/rest/api/2/workflow') return [{name:'Eval Flow'}];
    if (path === '/rest/api/2/status') return [];
    if (path === '/rest/workflowDesigner/1.0/statuses') return [{id:'1',name:'New'},{id:'2',name:'Done'},{id:'3',name:'Unused pre-existing'}];
    if (path === '/rest/api/2/statuscategory') return [{id:2,key:'new'},{id:3,key:'done'}];
    return graph;
  });
  const snapshot = await api.snapshot();
  assert.deepEqual(snapshot.statuses,[{id:'1',name:'New',category:null},{id:'2',name:'Done',category:null},{id:'3',name:'Unused pre-existing',category:null}]);
  const workflow = await api.readWorkflow('Eval Flow');
  assert.deepEqual(workflow.statuses,[{id:'1',name:'New',category:'TO_DO'},{id:'2',name:'Done',category:'DONE'}]);
});
