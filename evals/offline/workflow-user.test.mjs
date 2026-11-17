import assert from 'node:assert/strict';
import test from 'node:test';
import { generateWorkflowScenario } from './workflow-scenario.mjs';
import { buildWorkflowUserPrompt, assessWorkflowUserReply, gradeWorkflowOmissions } from './workflow-user.mjs';

const screens = [{id:'17', name:'Existing approval screen'}];
const payload = (message, extra = {}) => ({message, signal:'CONTINUE', previewShown:false, answeredOmissions:[], revisionDisclosed:false, ...extra});
const assistant = (content, turn = 1) => ({role:'assistant', content, turn});
const user = (content, turn = 1) => ({role:'user', content, turn});
const assess = (scenario, conversation, reply) => assessWorkflowUserReply({scenario, conversation, payload:reply});
function categoryAnswer(scenario) {
  return `Для статуса «${scenario.workflow.statuses[1].name}» категория ${scenario.omissions[0].expected}.`;
}
function preview(scenario, workflow = scenario.workflow) {
  return assistant(`Предпросмотр плана ${workflow.name}:\n${workflow.statuses.map(status => `${status.name} (${status.category})`).join('\n')}\nНачальный статус: ${workflow.initialStatus}\n${workflow.transitions.map(transition => `${transition.name}: ${transition.from} → ${transition.to}${transition.screen ? `; экран ${transition.screen}` : ''}`).join('\n')}\nПодтвердите применение.`, 2);
}

test('public initial prompt withholds omitted paths, not the publicly repeated status names', () => {
  const scenario = generateWorkflowScenario(4, {stage:2, screens});
  const prompt = buildWorkflowUserPrompt(scenario);
  const publicSpec = JSON.parse(prompt.split('ПУБЛИЧНАЯ НАЧАЛЬНАЯ СПЕЦИФИКАЦИЯ:\n')[1].split('\nСКРЫТЫЕ ПРОПУСКИ')[0]);
  assert.equal(publicSpec.statuses[1].name, scenario.workflow.statuses[1].name);
  assert.equal(publicSpec.statuses[1].category, undefined);
  assert.equal(publicSpec.initialStatus, undefined);
  assert.equal(publicSpec.transitions[0].screen, undefined);
  assert.ok(prompt.includes('никогда не инструменты'));
  assert.ok(buildWorkflowUserPrompt(generateWorkflowScenario(1)).includes('Кооперативный'));
});

test('hidden category cannot be disclosed on generic requests, even with forged metadata', () => {
  const scenario = generateWorkflowScenario(2, {stage:2});
  const conversation = [user(`Создай ${scenario.workflow.name}`), assistant('Расскажите всю спецификацию workflow.')];
  const reply = payload(categoryAnswer(scenario), {answeredOmissions:['status-category']});
  const assessment = assess(scenario, conversation, reply);
  assert.deepEqual(assessment.answeredOmissions, []);
  assert.ok(assessment.errors.some(error => error.includes('Unasked hidden value')));
  assert.ok(assessment.errors.some(error => error.includes('lacks matching question')));
  assert.ok(assess(scenario, [], payload(scenario.omissions[0].expected)).errors.some(error => error.includes('Unasked hidden value')));
});

test('a matching kind question and exact answer are both required', () => {
  const scenario = generateWorkflowScenario(2, {stage:2});
  const question = assistant(`Какая категория у статуса «${scenario.workflow.statuses[1].name}»?`);
  const reply = payload(categoryAnswer(scenario), {answeredOmissions:['status-category']});
  assert.deepEqual(assess(scenario, [question], reply), {answeredOmissions:['status-category'], revisionDisclosed:false, errors:[]});
  assert.ok(assess(scenario, [question], payload('Как-нибудь потом.', {answeredOmissions:['status-category']})).errors.length);
  assert.ok(assess(scenario, [question], payload(categoryAnswer(scenario))).errors.some(error => error.includes('Missing disclosure metadata')));
  const otherQuestion = assistant(`Какая категория у статуса «${scenario.workflow.statuses[0].name}»?`);
  assert.ok(assess(scenario, [otherQuestion], reply).errors.length);
  assert.ok(assess(scenario, [question, user('Пока не знаю.'), assistant('Тогда подтвердите план.')], reply).errors.length);
});

test('natural nontechnical and hasty replies preserve exact values; all-category question is specific', () => {
  for (const seed of [2, 3, 4]) {
    const scenario = generateWorkflowScenario(seed, {stage:2});
    const question = assistant('Укажите статусы и категорию каждого, пожалуйста.');
    const reply = payload(`Кажется так, тороплюс: ${categoryAnswer(scenario)}`, {answeredOmissions:['status-category']});
    assert.deepEqual(assess(scenario, [question], reply).errors, []);
    const jsonReply = payload(JSON.stringify({statuses:[scenario.workflow.statuses[1]]}), {answeredOmissions:['status-category']});
    assert.deepEqual(assess(scenario, [question], jsonReply).errors, []);
  }
});

test('public occurrence of the initial-status name is not a hidden initial choice', () => {
  const scenario = generateWorkflowScenario(3, {stage:2});
  const reply = payload(`Нужны статусы: ${scenario.workflow.statuses[0].name}; ${scenario.workflow.statuses[1].name}. Переход: ${scenario.workflow.transitions[0].from} → ${scenario.workflow.transitions[0].to}.`);
  assert.deepEqual(assess(scenario, [assistant('Дайте детали.')], reply).errors, []);
  const answer = payload(`Начальный статус: ${scenario.workflow.initialStatus}.`, {answeredOmissions:['initial-status']});
  assert.deepEqual(assess(scenario, [assistant('Какой начальный статус?')], answer).errors, []);
  assert.ok(assess(scenario, [assistant('Дайте детали.')], answer).errors.some(error => error.includes('Unasked hidden value')));
});

test('screen disclosure requires a screen question, not another transition specification request', () => {
  const scenario = generateWorkflowScenario(4, {stage:2, screens});
  const transition = scenario.workflow.transitions[0];
  const reply = payload(`Для перехода «${transition.name}» нужен экран «${transition.screen}».`, {answeredOmissions:['transition-screen']});
  assert.deepEqual(assess(scenario, [assistant('Нужны ли экраны на каких-либо переходах?')], reply).errors, []);
  assert.ok(assess(scenario, [assistant('Какие переходы нужны?')], reply).errors.some(error => error.includes('Unasked hidden value')));
  assert.deepEqual(assess(scenario, [assistant(`Какой экран у перехода ${transition.name}?`)], reply).errors, []);
});

test('transition-name fallback has the same question/evidence gate without a screen catalog', () => {
  const scenario = generateWorkflowScenario(4, {stage:2});
  const omission = scenario.omissions.find(item => item.kind === 'transitionName');
  const reply = payload(`Название перехода: ${omission.expected}.`, {answeredOmissions:[omission.id]});
  assert.deepEqual(assess(scenario, [assistant('Укажите названия переходов.')], reply).errors, []);
  assert.ok(assess(scenario, [assistant('Подтвердите создание.')], reply).errors.length);
});

test('mindchanger discloses exactly one rename after initial preview and requires revised preview', () => {
  const scenario = {...generateWorkflowScenario(5, {stage:2, screens}), omissions:[]};
  const change = payload(`Переименуйте переход «${scenario.revision.initial}» в «${scenario.revision.expected}». Покажите новый preview.`, {previewShown:true, revisionDisclosed:true});
  assert.ok(assess(scenario, [], change).errors.some(error => error.includes('before first visible preview')));
  const initial = [user(`Создай ${scenario.workflow.name}`), preview(scenario, scenario.initialWorkflow)];
  assert.deepEqual(assess(scenario, initial, change), {answeredOmissions:[], revisionDisclosed:true, errors:[]});
  assert.ok(assess(scenario, initial, payload('Да, применяй.', {previewShown:true})).errors.some(error => error.includes('Approval before revision')));
  const changed = [...initial, user(change.message, 2)];
  assert.ok(assess(scenario, [...changed, preview(scenario, scenario.initialWorkflow)], payload('Да, применяй.', {previewShown:true})).errors.some(error => error.includes('revised visible preview')));
  assert.deepEqual(assess(scenario, [...changed, preview(scenario)], payload('Да, применяй.', {previewShown:true})).errors, []);
  assert.ok(assess(scenario, [...changed, preview(scenario)], change).errors.some(error => error.includes('Revision disclosure lacks')));
});

test('metadata alone cannot claim a visible preview or a successful omission answer', () => {
  const scenario = generateWorkflowScenario(2, {stage:2});
  assert.ok(assess(scenario, [assistant('Подтвердите?')], payload('Да, применяй.', {previewShown:true})).errors.length);
  assert.ok(assess(scenario, [], payload('Здравствуйте', {answeredOmissions:['unknown']})).errors.some(error => error.includes('Unknown omission')));
  assert.ok(assess(scenario, [], {message:'hello', signal:'CONTINUE'}).errors.some(error => error.includes('Invalid')));
});

test('grade detects unasked chosen values even if assistant guesses the hidden expected value', () => {
  const scenario = generateWorkflowScenario(2, {stage:2});
  const trace = [{sequence:1, tool:'configure_workflow', arguments:{ dryRun: true, create: scenario.workflow }, userTurn:1}];
  const grade = gradeWorkflowOmissions({scenario, trace, conversation:[user('Создай workflow')], disclosures:[]});
  assert.deepEqual(grade.omissions, [{id:'status-category', asked:false, answered:false, decision:'AUTONOMOUS', actual:scenario.omissions[0].expected}]);
  assert.equal(grade.autonomousDecisions.length, 1);
  assert.equal(grade.autonomousDecisions[0].path, 'statuses.1.category');
});

test('grade separates answered specification from premature decisions and forged disclosure metadata', () => {
  const scenario = generateWorkflowScenario(2, {stage:2});
  const question = assistant(`Какая категория у статуса ${scenario.workflow.statuses[1].name}?`);
  const reply = payload(categoryAnswer(scenario), {answeredOmissions:['status-category']});
  const assessment = assess(scenario, [user('Создай workflow'), question], reply);
  const conversation = [user('Создай workflow'), question, user(reply.message, 2)];
  const disclosures = [{...assessment, userTurn:2}];
  const makePreview = (sequence, userTurn) => ({sequence, userTurn, tool:'configure_workflow', arguments:{ dryRun: true, create: scenario.workflow }});
  const answered = gradeWorkflowOmissions({scenario, trace:[makePreview(1, 2)], conversation, disclosures});
  assert.equal(answered.omissions[0].decision, 'ASKED_AND_ANSWERED');
  assert.deepEqual(answered.autonomousDecisions, []);
  const premature = gradeWorkflowOmissions({scenario, trace:[makePreview(1, 1), makePreview(2, 2)], conversation, disclosures});
  assert.equal(premature.omissions[0].asked, true);
  assert.equal(premature.omissions[0].answered, true);
  assert.equal(premature.omissions[0].decision, 'AUTONOMOUS');
  assert.equal(premature.autonomousDecisions.length, 1);
  const forged = gradeWorkflowOmissions({scenario, trace:[makePreview(1, 2)], conversation:[user('Создай workflow'), user('Ок', 2)], disclosures});
  assert.equal(forged.omissions[0].answered, false);
  assert.equal(forged.omissions[0].decision, 'AUTONOMOUS');
  const askedOnly = gradeWorkflowOmissions({scenario, trace:[], conversation:[question], disclosures:[]});
  assert.equal(askedOnly.omissions[0].decision, 'ASKED_UNANSWERED');
});

test('stage-one cooperative simulator still starts and approves a visible complete plan', () => {
  const scenario = generateWorkflowScenario(1);
  assert.deepEqual(assess(scenario, [], payload(`Создай workflow «${scenario.workflow.name}».`)).errors, []);
  assert.deepEqual(assess(scenario, [preview(scenario)], payload('Да, применяй.', {previewShown:true})).errors, []);
  assert.deepEqual(gradeWorkflowOmissions({scenario, trace:[], conversation:[], disclosures:[]}), {omissions:[], autonomousDecisions:[]});
});

test('actual Russian «Предпросмотр готов» phrasing is recognized without the word план', () => {
  const scenario = generateWorkflowScenario(1);
  const visible = preview(scenario);
  visible.content = visible.content.replace('Предпросмотр плана', 'Предпросмотр готов.');
  assert.deepEqual(assess(scenario, [visible], payload('Да, применяй.', {previewShown:true})).errors, []);
  const mindchanger = {...generateWorkflowScenario(5, {stage:2}), omissions:[]};
  const initial = preview(mindchanger, mindchanger.initialWorkflow);
  initial.content = initial.content.replace('Предпросмотр плана', 'Предпросмотр готов.');
  const reply = payload(`Переименуйте переход в «${mindchanger.revision.expected}». Покажите новый предпросмотр.`, {previewShown:true, revisionDisclosed:true});
  assert.deepEqual(assess(mindchanger, [initial], reply).errors, []);
});

test('confirmation-only preview does not authorize hidden category disclosure or count as asking', () => {
  const scenario = generateWorkflowScenario(2, {stage:2, screens});
  const status = scenario.workflow.statuses[1];
  const conversation = [
    user(`Создай ${scenario.workflow.name}`),
    assistant(`Предпросмотр готов.\nКатегория статуса «${status.name}»: ${status.category}.\nПодтвердите создание workflow?`),
  ];
  const reply = payload(`${status.name} — ${status.category}.`, {answeredOmissions:['status-category']});
  const assessment = assess(scenario, conversation, reply);
  assert.deepEqual(assessment.answeredOmissions, []);
  assert.ok(assessment.errors.some(error => error.includes('Unasked hidden value')));
  const trace = [{sequence:1, tool:'configure_workflow', arguments:{ dryRun: true, create: scenario.workflow }, userTurn:1}];
  const grade = gradeWorkflowOmissions({
    scenario, trace, conversation:[...conversation, user(reply.message, 2)],
    disclosures:[{...assessment, userTurn:2}],
  });
  assert.equal(grade.omissions[0].asked, false);
  assert.equal(grade.omissions[0].answered, false);
  assert.equal(grade.omissions[0].decision, 'AUTONOMOUS');
  assert.equal(grade.autonomousDecisions.length, 1);
});

test('explicit multiline intake request authorizes its numbered category, initial and screen questions', () => {
  const scenario = generateWorkflowScenario(4, {stage:2, screens});
  const transition = scenario.workflow.transitions[0];
  const conversation = [assistant(`Чтобы подготовить предпросмотр, укажите, пожалуйста:\n\n1. Статусы и категорию каждого.\n2. Начальный статус для новых задач.\n3. Экраны переходов по точному имени.`)];
  const reply = payload(`${categoryAnswer(scenario)}\nНачальный статус: ${scenario.workflow.initialStatus}.\nДля перехода «${transition.name}» экран «${transition.screen}».`, {
    answeredOmissions:['status-category', 'initial-status', 'transition-screen'],
  });
  assert.deepEqual(assess(scenario, conversation, reply), {
    answeredOmissions:['status-category', 'initial-status', 'transition-screen'],
    revisionDisclosed:false, errors:[],
  });
});

test('actual nontechnical intake requests category groups for steps and a starting step', () => {
  const scenario = generateWorkflowScenario(3, {stage:2, screens});
  const conversation = [assistant(`Для «${scenario.workflow.name}» мне нужны:\n\n1. **Шаги (статусы)** — например: «Новая», «В работе», «Ожидание клиента», «Решена». Для каждого укажите, к какой группе он относится:\n   - «к выполнению»;\n   - «в работе»;\n   - «готово».\n2. **Начальный шаг** — куда должна попадать новая заявка.\n3. **Кнопки-переходы**: из какого шага → в какой шаг — название кнопки.`)];
  const reply = payload(`Шаг «${scenario.workflow.statuses[1].name}» — ${scenario.omissions[0].expected}.\nНачинаем с «${scenario.workflow.initialStatus}».`, {
    answeredOmissions:['status-category', 'initial-status'],
  });
  assert.deepEqual(assess(scenario, conversation, reply), {
    answeredOmissions:['status-category', 'initial-status'], revisionDisclosed:false, errors:[],
  });
  const english = assistant(`For each step, specify which category bucket it belongs to.`);
  assert.deepEqual(assess(scenario, [english], payload(categoryAnswer(scenario), {answeredOmissions:['status-category']})).errors, []);
  const unrelated = assistant('Для каждого перехода укажите, к какой группе он относится.');
  assert.ok(assess(scenario, [unrelated], payload(categoryAnswer(scenario), {answeredOmissions:['status-category']})).errors.length);
});

test('conversational «Да, вот схема» is specification assent, not authorization to apply', () => {
  const vague = generateWorkflowScenario(4, {stage:2, screens});
  const question = assistant('Укажите категории всех статусов, начальный статус и экраны переходов.');
  const reply = payload(`Да, вот схема: ${JSON.stringify(vague.workflow)}`, {
    answeredOmissions:vague.omissions.map(omission => omission.id),
  });
  assert.deepEqual(assess(vague, [question], reply).errors, []);
  const mindchanger = {...generateWorkflowScenario(5, {stage:2, screens}), omissions:[]};
  const initialDetails = payload(`Да, вот схема: ${JSON.stringify(mindchanger.initialWorkflow)}`);
  assert.deepEqual(assess(mindchanger, [assistant('Расскажите детали процесса.')], initialDetails).errors, []);
  const stageOne = generateWorkflowScenario(1);
  assert.deepEqual(assess(stageOne, [], payload('Yes, here are the details.')).errors, []);
  assert.ok(assess(stageOne, [], payload('Да, применяй.')).errors.some(error => error.includes('Approval without visible preview')));
});
