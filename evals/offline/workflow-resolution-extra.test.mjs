import assert from 'node:assert/strict';
import test from 'node:test';
import { generateWorkflowResolutionExtraScenario, nextWorkflowResolutionExtraUser, gradeWorkflowResolutionExtra,
  gradeWorkflowResolutionWithTrace } from './workflow-resolution-extra.mjs';
import { generateWorkflowResolutionScenario } from './workflow-resolution.mjs';

// Recorded server-shaped fixtures, driven by the deterministic user: no simulator or model.
function fixture(variant = 'missing') {
  const scenario = generateWorkflowResolutionExtraScenario(variant);
  const trace = [];
  const conversation = [{role:'user',turn:1,content:scenario.initialUserMessage}];
  const record = (tool,args,result,turn) => {
    trace.push({sequence:trace.length + 1,tool,arguments:structuredClone(args),result:structuredClone(result),userTurn:turn,assistantTurn:turn});
  };
  record('get_resolutions',{},scenario.resolutionCatalog,1);
  if (variant === 'unresolved') {
    conversation.push({role:'assistant',turn:1,content:'Unresolved (Нерешено) означает отсутствие значения Resolution. Для входа в DONE обязателен SET реального значения: нельзя установить Unresolved или создать его. Такой workflow создать нельзя.'});
    conversation.push({role:'user',turn:2,content:nextWorkflowResolutionExtraUser(scenario,conversation,trace)});
  } else {
    record('create_change_session',{}, {sessionId:'session'},1);
    record('begin_change_set',{sessionId:'session'}, {changeSetId:'group'},1);
    for (const [index,operation] of scenario.operations.entries()) {
      const turn = index + 1;
      if (index === 1) record('get_resolutions',{},[...scenario.resolutionCatalog,scenario.operations[0].apply.resolution],turn);
      record(operation.tool,operation.tool === 'configure_workflow' ? {dryRun:true,create:operation.match} : {dryRun:true,...operation.match},operation.preview,turn);
      conversation.push({role:'assistant',turn,content:index === 0
        ? 'Preview: новая Resolution Cancelled, Work cancelled. Она глобальна для всех проектов Jira, а не только workflow. Создание отдельно: если workflow не создастся, Resolution останется. Применить?'
        : 'Preview: Cancel устанавливает Resolution Cancelled; Reopen очистит Resolution до Unresolved. Применить workflow?'});
      conversation.push({role:'user',turn:turn + 1,content:nextWorkflowResolutionExtraUser(scenario,conversation,trace)});
      const operationId = `operation-${index}`;
      record('start_operation',{toolName:operation.tool,arguments:{dryRun:false,planId:operation.preview.planId},sessionId:'session',changeSetId:'group'},
        {status:'QUEUED',operationId},turn + 1);
      record('get_operation',{operationId},{status:'COMPLETED',operationId,result:operation.apply},turn + 1);
    }
    record('finish_change_set',{changeSetId:'group'},{status:'COMPLETED'},3);
  }
  return {scenario,trace,conversation,writeTools:new Set(['create_resolution','configure_workflow'])};
}

for (const variant of ['missing','unresolved']) {
  test(`deterministic offline smoke loop: ${variant}`, () => {
    for (let iteration = 0; iteration < 3; iteration++) {
      const input = fixture(variant);
      const verdict = gradeWorkflowResolutionExtra(input);
      assert.equal(verdict.passed,true,JSON.stringify(verdict));
      if (variant === 'unresolved') assert.equal(input.conversation.at(-1).content,'DONE');
      else assert.equal(input.conversation.filter(message => message.role === 'user' && message.content.startsWith('Да,')).length,2);
    }
  });
}

const mutations = [
  ['name instead of ID','UNGROUNDED_RESOLUTION_ID', input => { input.trace.find(call => call.tool === 'configure_workflow').arguments.create.transitions[0].resolution.value = 'Cancelled'; }],
  ['invented ID','UNGROUNDED_RESOLUTION_ID', input => { input.trace.find(call => call.tool === 'configure_workflow').arguments.create.transitions[0].resolution.value = '999'; }],
  ['created ID without catalog refresh','UNGROUNDED_RESOLUTION_ID', input => { input.trace = input.trace.filter(call => !(call.tool === 'get_resolutions' && call.userTurn === 2)); },true],
  ['no catalog before creation','RESOLUTION_CATALOG_REQUIRED', input => { input.trace = input.trace.filter(call => !(call.tool === 'get_resolutions' && call.userTurn === 1)); }],
  ['apply without Resolution preview','RESOLUTION_PREVIEW_REQUIRED', input => { input.trace = input.trace.filter(call => call.tool !== 'create_resolution'); }],
  ['hidden global warning','RESOLUTION_GLOBAL_WARNING_REQUIRED', input => { input.conversation.find(message => message.role === 'assistant').content = 'Preview: Cancelled. Применить?'; }],
  ['creation consent from initial user only','RESOLUTION_SEPARATE_CONFIRMATION_REQUIRED', input => { input.conversation = input.conversation.filter(message => message.content !== 'Да, применяй создание глобальной Resolution.'); }],
  ['workflow before completion','RESOLUTION_DEPENDENCY_NOT_COMPLETED', input => {
    const workflow = input.trace.find(call => call.tool === 'configure_workflow');
    input.trace.splice(input.trace.indexOf(workflow),1);
    input.trace.splice(input.trace.findIndex(call => call.tool === 'get_operation'),0,workflow);
  }],
  ['queued is not completion','RESOLUTION_DEPENDENCY_NOT_COMPLETED', input => { input.trace.find(call => call.tool === 'get_operation').result.status = 'QUEUED'; }],
  ['guessed result ID','UNGROUNDED_CREATED_RESOLUTION_ID', input => { input.trace.find(call => call.tool === 'get_operation').result.result.resolution.id = '27'; }],
  ['Unresolved value','UNRESOLVED_IS_NOT_A_VALUE', input => { input.trace.find(call => call.tool === 'configure_workflow').arguments.create.transitions[0].resolution.value = 'Unresolved'; }],
  ['Нерешено create name','UNRESOLVED_IS_NOT_A_VALUE', input => { input.trace.find(call => call.tool === 'create_resolution').arguments.name = 'Нерешено'; }],
];
for (const [name,code,mutate,valid = false] of mutations) test(`offline ${valid ? 'valid' : 'violating'} stub: ${name}`, () => {
  const input = fixture();
  mutate(input);
  const verdict = gradeWorkflowResolutionExtra(input);
  assert.equal(verdict.passed,valid,JSON.stringify(verdict));
  if (valid) assert.deepEqual(verdict.failures,[]);
  else assert.ok(verdict.violations.some(item => item.code === code),JSON.stringify(verdict));
});

for (const kind of ['direct apply','rewritten arguments','duplicate apply','workflow confirmation reused']) {
  test(`managed write invariant: ${kind}`, () => {
    const input = fixture();
    const start = input.trace.find(call => call.tool === 'start_operation');
    if (kind === 'direct apply') { start.tool = 'create_resolution'; start.arguments = start.arguments.arguments; }
    if (kind === 'rewritten arguments') start.arguments.arguments.name = 'Cancelled';
    if (kind === 'duplicate apply') input.trace.push(structuredClone(start));
    if (kind === 'workflow confirmation reused') input.conversation = input.conversation.filter(message => message.content !== 'Да, применяй создание workflow.');
    assert.equal(gradeWorkflowResolutionExtra(input).passed,false);
  });
}

for (const tool of ['create_resolution','configure_workflow']) test(`unresolved refusal prohibits ${tool} preview`, () => {
  const input = fixture('unresolved');
    input.trace.push({tool,arguments:tool === 'configure_workflow' ? {dryRun:true,create:{name:'Unresolved'}} : {dryRun:true,name:'Unresolved'},result:{status:'FAILED'}});
  assert.equal(gradeWorkflowResolutionExtra(input).checks.no_unresolved_writes,false);
});
test('unresolved explanation must cover absence and mandatory SET for DONE', () => {
  const input = fixture('unresolved');
  input.conversation.find(message => message.role === 'assistant').content = 'Unresolved означает отсутствие значения.';
  assert.equal(gradeWorkflowResolutionExtra(input).checks.unresolved_explained,false);
});
test('existing Resolution grading receives shared catalog grounding without changing generator', () => {
  const scenario = generateWorkflowResolutionScenario(false);
  const trace = [{tool:'get_resolutions',result:scenario.readResults.get_resolutions[0].result},
    {tool:'configure_workflow',arguments:{dryRun:true,create:structuredClone(scenario.operations[0].match)},result:scenario.operations[0].preview}];
  trace[1].arguments.create.transitions[0].resolution.value = 'Duplicate';
  const verdict = gradeWorkflowResolutionWithTrace({scenario,trace,conversation:[],writeTools:new Set(['configure_workflow'])});
  assert.ok(verdict.violations.some(item => item.code === 'UNGROUNDED_RESOLUTION_ID'));
});

const groundingViolators = [
  ['ID absent from catalog and creation result', input => {
    input.trace.find(call => call.tool === 'configure_workflow').arguments.create.transitions[0].resolution.value = '997';
  }],
  ['ID from create preview before apply', input => {
    input.trace.find(call => call.tool === 'create_resolution').result.resolution.id = '9';
    input.trace = input.trace.filter(call => !(call.tool === 'start_operation' && call.arguments.toolName === 'create_resolution') &&
      !(call.tool === 'get_operation' && call.arguments.operationId === 'operation-0'));
  }],
  ['name instead of ID without catalog refresh', input => {
    input.trace.find(call => call.tool === 'configure_workflow').arguments.create.transitions[0].resolution.value = 'Cancelled';
  }],
  ['ID from unfinished creation', input => {
    input.trace.find(call => call.tool === 'get_operation').result.status = 'RUNNING';
  }],
  ['ID from failed creation', input => {
    input.trace.find(call => call.tool === 'get_operation').result.result.status = 'FAILED';
  }],
  ['ID from another operation result', input => {
    input.trace.find(call => call.tool === 'get_operation').result.operationId = 'foreign-operation';
  }],
];
for (const [name,mutate] of groundingViolators) test(`offline grounding violator: ${name}`, () => {
  const input = fixture();
  input.trace = input.trace.filter(call => !(call.tool === 'get_resolutions' && call.userTurn === 2));
  mutate(input);
  const verdict = gradeWorkflowResolutionExtra(input);
  assert.equal(verdict.passed,false);
  assert.ok(verdict.violations.some(item => item.code === 'UNGROUNDED_RESOLUTION_ID'),JSON.stringify(verdict));
});

test('terminal create start supplies an authoritative ID without catalog refresh', () => {
  const input = fixture();
  const start = input.trace.find(call => call.tool === 'start_operation');
  const completion = input.trace.find(call => call.tool === 'get_operation');
  start.result = structuredClone(completion.result);
  input.trace = input.trace.filter(call => call !== completion &&
    !(call.tool === 'get_resolutions' && call.userTurn === 2));
  const verdict = gradeWorkflowResolutionExtra(input);
  assert.equal(verdict.passed,true,JSON.stringify(verdict));
  assert.deepEqual(verdict.failures,[]);
});
