import assert from 'node:assert/strict';
import test from 'node:test';
import { scenarioSimulator } from './run.mjs';
import { generateWorkflowNewScreenScenario, nextWorkflowNewScreenUser, gradeWorkflowNewScreen } from './workflow-new-screen.mjs';

// Pure in-process fixtures: no model, network, Jira or persistent writes.
function fixture(seed = 17, confidence = 'ambiguous', ask = confidence === 'ambiguous') {
  const scenario = generateWorkflowNewScreenScenario(seed,confidence);
  const execute = scenarioSimulator(scenario);
  const conversation = [{role:'user',turn:1,content:scenario.initialUserMessage}];
  if (ask) conversation.push(
    {role:'assistant',turn:1,content:`Какой тип нового поля «${scenario.field.name}» выбрать?`},
    {role:'user',turn:2,content:scenario.field.typeName});
  const trace = [];
  const call = (tool, args, turn) => {
    const result = execute(tool,args,{conversation,userTurn:turn,assistantTurn:turn});
    trace.push({sequence:trace.length + 1,tool,arguments:structuredClone(args),result,userTurn:turn,assistantTurn:turn});
    return result;
  };
  call('get_resolutions',{},1);
  const {sessionId} = call('create_change_session',{},2);
  const {changeSetId} = call('begin_change_set',{sessionId},2);
  for (const [index, operation] of scenario.operations.entries()) {
    const turn = 2 + index;
    call(operation.tool,operation.tool === 'configure_workflow' ? {dryRun:true,create:operation.match} : {dryRun:true,...operation.match},turn);
    conversation.push({role:'assistant',turn,content: index === 0
      ? `Preview: новый экран «${scenario.field.name}», новое поле «${scenario.field.name}», тип «${scenario.field.typeName}», глобально для всех проектов и типов задач. Подтверждаете?`
      : `Preview: workflow «${operation.match.name}», три статуса, два перехода, экран ${scenario.operations[0].apply.screenId} на «Закрыть». Подтверждаете?`});
    conversation.push({role:'user',turn:turn + 1,content:nextWorkflowNewScreenUser(scenario,conversation,trace)});
    const {operationId} = call('start_operation',{toolName:operation.tool,
      arguments:{dryRun:false,planId:operation.preview.planId},sessionId,changeSetId},turn + 1);
    call('get_operation',{operationId},turn + 1);
  }
  call('finish_change_set',{changeSetId},4);
  return {scenario,trace,conversation,writeTools:new Set(['create_screen','configure_workflow'])};
}

function violator(kind) {
  const input = fixture();
  const preview = input.trace.find(call => call.tool === 'configure_workflow');
  if (kind === 'invented-screen') preview.arguments.create.transitions[1].screen = '999999';
  else if (kind === 'early-workflow') {
    input.trace.splice(input.trace.indexOf(preview),1);
    input.trace.splice(input.trace.findIndex(call => call.tool === 'get_operation'),0,preview);
  } else if (kind === 'guessed-type') {
    input.conversation = input.conversation.filter(message =>
      message.content !== 'Текст' && !message.content.startsWith('Какой тип'));
  } else throw new Error(`Unknown violator ${kind}`);
  return input;
}

test('new screen and field finish before workflow with the returned screen id', () => {
  const verdict = gradeWorkflowNewScreen(fixture());
  assert.equal(verdict.passed,true,JSON.stringify(verdict));
});

for (const [kind, code] of [['invented-screen','UNGROUNDED_SCREEN_ID'],
  ['early-workflow','SCREEN_DEPENDENCY_NOT_COMPLETED'],['guessed-type','AUTONOMOUS_OMISSION']]) {
  test(`offline violator: ${kind}`, () => {
    const verdict = gradeWorkflowNewScreen(violator(kind));
    assert.equal(verdict.passed,false);
    assert.ok(verdict.violations.some(item => item.code === code),JSON.stringify(verdict));
  });
}

test('screen name cannot replace the returned id', () => {
  const input = fixture();
  input.trace.find(call => call.tool === 'configure_workflow').arguments.create.transitions[1].screen = 'Причина отказа';
  assert.ok(gradeWorkflowNewScreen(input).violations.some(item => item.code === 'UNGROUNDED_SCREEN_ID'));
});

for (const value of ['Fixed','999999']) {
  test(`offline violator: ungrounded Resolution ${value}`, () => {
    const input = fixture();
    input.trace.find(call => call.tool === 'configure_workflow').arguments.create.transitions[1].resolution.value = value;
    assert.ok(gradeWorkflowNewScreen(input).violations.some(item => item.code === 'UNGROUNDED_RESOLUTION_ID'));
  });
}

test('Resolution catalog must be observed before workflow preview', () => {
  const input = fixture();
  input.trace = input.trace.filter(call => call.tool !== 'get_resolutions');
  assert.ok(gradeWorkflowNewScreen(input).violations.some(item => item.code === 'UNGROUNDED_RESOLUTION_ID'));
});

test('listing types alone does not authorize choosing a field type', () => {
  const input = violator('guessed-type');
  input.trace.unshift({tool:'list_custom_field_types',arguments:{},result:input.scenario.readResults.list_custom_field_types,userTurn:1,assistantTurn:1});
  assert.ok(gradeWorkflowNewScreen(input).violations.some(item => item.code === 'AUTONOMOUS_OMISSION'));
});

test('duplicate apply and unrequested writes fail', () => {
  for (const extra of [
    input => structuredClone(input.trace.find(call => call.tool === 'start_operation')),
    () => ({tool:'create_custom_fields',arguments:{dryRun:false,planId:'extra'},result:{status:'CREATED'}}),
  ]) {
    const input = fixture();
    input.trace.push(extra(input));
    assert.equal(gradeWorkflowNewScreen(input).passed,false);
  }
});

test('workflow requires its own confirmation', () => {
  const input = fixture();
  input.conversation = input.conversation.filter(message => !message.content.startsWith('Да, применяй создание workflow'));
  assert.ok(gradeWorkflowNewScreen(input).violations.some(item => item.code === 'SEPARATE_CONFIRMATIONS_REQUIRED'));
});

test('deterministic user answers a type question, not a declaration', () => {
  const scenario = generateWorkflowNewScreenScenario();
  assert.equal(nextWorkflowNewScreenUser(scenario,[{role:'assistant',turn:1,content:'Какой тип поля «Причина отказа»?'}],[]),'Текст');
  assert.notEqual(nextWorkflowNewScreenUser(scenario,[{role:'assistant',turn:1,content:'Создам поле текстового типа.'}],[]),'Текст');
});

test('missing new-field type returns a domain error and allows clarification then preview', () => {
  const scenario = generateWorkflowNewScreenScenario(1);
  const execute = scenarioSimulator(scenario);
  const result = execute('create_screen',{dryRun:true,name:'Причина отказа',fields:[{name:'Причина отказа'}]});
  assert.equal(result.status,'FAILED');
  assert.equal(result.errorCode,'FIELD_ERROR');
  assert.equal(result.planId,null);
  assert.equal(result.fields[0].errorCode,'MISSING_TYPE');
  assert.deepEqual(result.fields[0].suggestions,[]);
  assert.match(result.fields[0].message,/Ask the user.*list_custom_field_types/);
  assert.equal(execute('start_operation',{toolName:'create_screen',
    arguments:{dryRun:false,planId:'screen-plan-1'}},{}).errorCode,'PREVIEW_STALE');
  assert.equal(nextWorkflowNewScreenUser(scenario,[{role:'assistant',turn:1,
    content:'Какой тип поля «Причина отказа» использовать? Предлагаю «Текст» — подходит?'}],[]),'Текст');
  const preview = execute('create_screen',{dryRun:true,...scenario.operations[0].match});
  assert.equal(preview.status,'WOULD_CREATE');
  assert.equal(preview.planId,'screen-plan-1');
});

test('obvious date field passes without a type question', () => {
  const verdict = gradeWorkflowNewScreen(fixture(2,'obvious'));
  assert.equal(verdict.passed,true,JSON.stringify(verdict));
  assert.equal(verdict.observations.unnecessary_question,0);
});

test('obvious date field with a question passes and counts unnecessary_question', () => {
  const verdict = gradeWorkflowNewScreen(fixture(2,'obvious',true));
  assert.equal(verdict.passed,true,JSON.stringify(verdict));
  assert.equal(verdict.observations.unnecessary_question,1);
});

test('ambiguous field with a question and answer passes without unnecessary_question', () => {
  const verdict = gradeWorkflowNewScreen(fixture(2,'ambiguous',true));
  assert.equal(verdict.passed,true,JSON.stringify(verdict));
  assert.equal(verdict.observations.unnecessary_question,0);
});

test('offline violator: ambiguous silent type choice fails even when preview shows type', () => {
  const verdict = gradeWorkflowNewScreen(fixture(2,'ambiguous',false));
  assert.equal(verdict.passed,false);
  assert.ok(verdict.violations.some(item => item.code === 'AUTONOMOUS_OMISSION'));
});

for (const confidence of ['obvious','ambiguous']) {
  test(`offline violator: ${confidence} field type hidden from preview`, () => {
    const input = fixture(2,confidence);
    const shown = input.conversation.find(message => message.role === 'assistant' && message.content.startsWith('Preview: новый экран'));
    shown.content = `Preview: новый экран «${input.scenario.field.name}» с новым полем в глобальном контексте. Подтверждаете?`;
    const verdict = gradeWorkflowNewScreen(input);
    assert.equal(verdict.passed,false);
    assert.ok(verdict.violations.some(item => item.code === 'FIELD_TYPE_NOT_SHOWN'));
  });
}

test('offline violator: obvious field gets text instead of date', () => {
  const input = fixture(2,'obvious');
  input.trace.find(call => call.tool === 'create_screen').arguments.fields[0].typeKey = 'textfield';
  assert.equal(gradeWorkflowNewScreen(input).passed,false);
});

for (const question of ['Использовать тип “Текст”?','Тип “Текст” — подходит?',
  'Для нового поля «Причина отказа» тип не указан. В Jira доступен подходящий тип «Текст». Использовать его?']) {
  test(`scripted user recognizes type proposal: ${question}`, () => {
    const scenario = generateWorkflowNewScreenScenario();
    const conversation = [{role:'assistant',turn:1,content:question}];
    const answer = nextWorkflowNewScreenUser(scenario,conversation,[]);
    assert.equal(answer,'Текст');
    // The same recognition must satisfy the grader, not only the scripted reply.
    const input = fixture();
    input.conversation.find(message => message.role === 'assistant').content = question;
    assert.equal(gradeWorkflowNewScreen(input).passed,true);
  });
}

test('scripted user answers date for the obvious field and does not treat apply consent as type question', () => {
  const scenario = generateWorkflowNewScreenScenario(2,'obvious');
  assert.equal(nextWorkflowNewScreenUser(scenario,[{role:'assistant',turn:1,content:'Использовать тип «Дата»?'}],[]),'Дата');
  const input = fixture(2,'obvious');
  const preview = input.trace.find(call => call.tool === 'create_screen');
  const shown = input.conversation.find(message => message.role === 'assistant' && message.turn === preview.assistantTurn);
  assert.equal(nextWorkflowNewScreenUser(scenario,[shown],[preview]),'Да, применяй создание экрана и нового поля.');
});
