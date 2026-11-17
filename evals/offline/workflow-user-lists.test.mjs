import test from 'node:test';
import assert from 'node:assert/strict';
import {generateWorkflowScenario} from './workflow-scenario.mjs';
import {assessWorkflowUserReply, gradeWorkflowOmissions} from './workflow-user.mjs';

const requests = [
  [10, `Да. Для создания процесса с именем «Поддержка L2 EV0000000a» опишите, пожалуйста:

1. Статусы процесса и категории каждого: **к выполнению**, **в работе** или **готово**.
2. Начальный статус для новой задачи.
3. Переходы между статусами (например: «Новая → В работе», «В работе → Решено»), а также нужны ли переходы «из любого статуса».
4. Нужны ли экраны на каких-либо переходах.
5. Нужно ли затем назначить этот процесс проекту; если да — какому проекту и для всех типов задач или только выбранных.

После этого я подготовлю единый предварительный просмотр процесса для подтверждения — без внесения изменений до вашего явного согласия.`],
  [20, `Конечно. Для процесса «Закупка оборудования EV00000014» пришлите, пожалуйста:

1. Список статусов и категорию каждого: **к выполнению**, **в работе** или **готово**.
2. Начальный статус для новой задачи.
3. Переходы в формате «Из → В», при необходимости — название кнопки перехода.
4. Нужны ли переходы «из любого статуса → …».
5. Нужно ли назначить процесс проекту: если да, укажите проект и для всех ли типов задач он должен применяться.
6. Нужны ли экраны на переходах (например, обязательный комментарий при отклонении) — и для каких переходов.

После этого я подготовлю предварительный просмотр всей схемы и покажу последствия перед созданием.`],
  [22, `Для workflow «Поддержка L2 EV00000016» нужны структура и стартовый статус. Пришлите, пожалуйста:

1. Статусы с категориями: \`todo\` / \`in progress\` / \`done\`.
2. Начальный статус.
3. Переходы в формате «Откуда → Куда» (если из любого статуса — укажите «Из любого → …»).
4. При необходимости — названия кнопок переходов и экраны переходов.

Например: \`Новая (todo) → В работе (in progress) → Решено (done); старт: Новая; Новая→В работе, В работе→Решено, Из любого→Новая\`.`],
  [23, `Конечно. Опишите процесс в свободной форме — я помогу превратить его в workflow Jira.

Нужны, по возможности:
1. этапы/статусы договора (например: «Черновик», «На согласовании», «Согласовано», «Отклонено»);
2. какие кнопки-переходы нужны и между какими этапами;
3. с какого статуса начинается новый договор;
4. для каждого нового статуса — категория: «к выполнению», «в работе» или «завершено»;
5. нужно ли показывать при переходе форму с полями или комментарием;
6. требуется ли затем назначить этот процесс конкретному проекту и типам задач.

Можно просто написать наподобие: «из Черновика отправляем на согласование, согласующий либо согласует, либо возвращает на доработку…».`],
];
const scenarioFor = seed => generateWorkflowScenario(seed,{stage:2,screens:[]});
function assessRequest(scenario, content) {
  const omissions=scenario.omissions.filter(o=>['category','initialStatus'].includes(o.kind));
  const message=`${scenario.workflow.statuses[1].name} — ${scenario.workflow.statuses[1].category}.\nНачальный статус: ${scenario.workflow.initialStatus}.`;
  const payload={message,signal:'CONTINUE',previewShown:false,answeredOmissions:omissions.map(o=>o.id),revisionDisclosed:false};
  const conversation=[{role:'assistant',content,turn:1}];
  const assessment=assessWorkflowUserReply({scenario,conversation,payload});
  const grade=gradeWorkflowOmissions({scenario,conversation:[...conversation,{role:'user',content:message,turn:2}],trace:[{sequence:1,tool:'configure_workflow',arguments:{ dryRun: true, create: scenario.workflow },userTurn:2}],disclosures:[{...assessment,userTurn:2}]});
  return {assessment,grade,omissions};
}
for(const [seed,content] of requests) {
  test(`saved seed ${seed} list requests its hidden details`,()=>{
    const {assessment,grade,omissions}=assessRequest(scenarioFor(seed),content);
    assert.deepEqual(assessment.errors,[]);
    assert.deepEqual(assessment.answeredOmissions,omissions.map(o=>o.id));
    assert.ok(grade.omissions.every(o=>o.asked&&o.answered&&o.decision==='ASKED_AND_ANSWERED'));
    assert.deepEqual(grade.autonomousDecisions,[]);
  });
}
const scenario=scenarioFor(23);
const selected=scenario.workflow.statuses[1];
const negatives=[
  ['generic request without items','Опишите процесс.'],
  ['selected values under request heading',`Пришлите, пожалуйста:\n1. Категория статуса «${selected.name}»: ${selected.category}.\n2. Начальный статус: «${scenario.workflow.initialStatus}».`],
  ['single selected category for all statuses',`Опишите, пожалуйста:\n1. Категория каждого статуса: TO_DO.\n2. Начальный статус: «${scenario.workflow.initialStatus}».`],
  ['list after prose paragraph',`Опишите, пожалуйста:\n1. Название процесса.\n\nЗатем я подготовлю план.\n2. Категории всех статусов.\n3. Начальный статус.`],
  ['list after empty paragraph',`Пришлите, пожалуйста:\n1. Название процесса.\n\n2. Категории всех статусов.\n3. Начальный статус.`],
  ['approval request',`Подтвердите применение:\n1. Категории всех статусов.\n2. Начальный статус.`],
  ['approval phrased as polite request',`Укажите, подтверждаете ли применение:\n1. Категории всех статусов.\n2. Начальный статус.`],
];
for(const [name,content] of negatives) {
  test(`does not count as a detail request: ${name}`,()=>{
    const {assessment,grade}=assessRequest(scenario,content);
    assert.deepEqual(assessment.answeredOmissions,[]);
    assert.ok(assessment.errors.some(e=>e.includes('Unasked hidden value')));
    assert.ok(grade.omissions.every(o=>!o.asked&&!o.answered&&o.decision==='AUTONOMOUS'));
    assert.equal(grade.autonomousDecisions.length,2);
  });
}
