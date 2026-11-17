import test from 'node:test';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import verifyCreate from '../live/scenarios/workflow-configure-create/verify.mjs';
import verifyExisting from '../live/scenarios/workflow-configure-existing/verify.mjs';
import verifyStale from '../live/scenarios/workflow-configure-stale/verify.mjs';
import { conveysAnd, conveysAnyRole, conveysNarrowing } from '../live/configure-common.mjs';
import { exactComment, parseXml, nodes } from '../live/comment-validators-common.mjs';
import { exactRole } from '../live/conditions-common.mjs';
import setupStale from '../live/scenarios/workflow-configure-stale/setup.mjs';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { traceChecks } from '../live/checks.mjs';

// workflow-configure-* live verifiers on synthetic Jira state and trace (no Jira, no model). Contract defects must fail the
// scenario; the same defective presentation replies remain covered as persisted advisory observations with overall PASS.
const COMMENT = 'com.onresolve.scriptrunner.canned.jira.workflow.validators.CommentRequiredValidator';
const GROOVY_VALIDATOR = 'com.onresolve.jira.groovy.GroovyValidator';
const GROUP_CLASS = 'com.atlassian.jira.workflow.condition.UserInGroupCondition';
const ROLE_CANNED = 'com.onresolve.scriptrunner.canned.jira.workflow.conditions.ProjectRoleCondition';
const ROLE_CLASS = 'com.onresolve.jira.groovy.GroovyCondition';
const uuid = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const arg = (name, value) => `<arg name="${name}">${value}</arg>`;
const commentXml = (n) => `<validator type="class">${arg('FIELD_FUNCTION_ID', uuid(n))}${arg('canned-script', COMMENT)}${arg('class.name', GROOVY_VALIDATOR)}</validator>`;
const requiredXml = (fields) => `<validator type="class">${arg('class.name', 'RequiredFields')}${arg('fields', fields.join(','))}</validator>`;
const groupXml = (group) => `<condition type="class">${arg('class.name', GROUP_CLASS)}${arg('group', group)}</condition>`;
const roleXml = (n, ids, invert = false) => `<condition type="class">${arg('FIELD_FUNCTION_ID', uuid(n))}${arg('FIELD_INVERT_CONDITION', invert ? 'on' : '')}`
  + `${arg('FIELD_PROJECT_ROLE_IDS', ids.join('|||'))}${arg('canned-script', ROLE_CANNED)}${arg('class.name', ROLE_CLASS)}</condition>`;
const actionXml = ({ id, name, screen, view, validators = [], conditions = [] }) => `<action id="${id}" name="${name}"${view ? ` view="${view}"` : ''}>`
  + (screen ? `<meta name="jira.fieldscreen.id">${screen}</meta>` : '')
  + (conditions.length ? `<restrict-to><conditions type="AND">${conditions.join('')}</conditions></restrict-to>` : '')
  + (validators.length ? `<validators>${validators.join('')}</validators>` : '') + '<results><unconditional-result old-status="Finished" status="Finished" step="2"/></results></action>';
const workflowXml = (actions, extra = '') => `<?xml version="1.0" encoding="UTF-8"?><workflow><meta name="jira.updated.date">1</meta><initial-actions><action id="1" name="Create"><results><unconditional-result old-status="Finished" status="Open" step="1"/></results></action></initial-actions>`
  + `<steps><step id="1" name="To Do"><actions>${actions.join('')}</actions></step>${extra}</steps></workflow>`;

const entry = (trace, turn, tool, args, result, isError = false) => { trace.push({ seq: trace.length + 1, turn, tool, arguments: args, result, isError }); return trace.at(-1); };
const operation = (trace, turn, planId, status, extra = {}) => {
  entry(trace, turn, 'start_operation', { toolName: 'configure_workflow', arguments: { dryRun: false, planId } }, { operationId: `op-${planId}`, status: 'QUEUED' });
  entry(trace, turn, 'get_operation', { operationId: `op-${planId}` }, { status: 'COMPLETED', operation: 'configure_workflow', result: { status, ...extra } });
};
const failing = (checks) => checks.filter((c) => !c.ok).map((c) => c.name);
const run = (verify, world) => verify({ trace: [], conversation: [], lifecycle: {}, ...world });
const mentions = (checks, fragment) => failing(checks).some((name) => name.includes(fragment));

async function report(checks) {
  const directory = await mkdtemp(path.join(tmpdir(), 'configure-verdict-'));
  const lines = [];
  const original = console.log;
  try {
    const { reportChecks } = await import('../live/check-results.mjs');
    console.log = (...args) => lines.push(args.join(' '));
    const passed = await reportChecks(directory, checks);
    return { passed, lines, verdict: JSON.parse(await readFile(path.join(directory, 'verdict.json'), 'utf8')) };
  } finally {
    console.log = original;
    await rm(directory, { recursive: true, force: true });
  }
}

async function advisory(checks, fragment) {
  const found = checks.find((c) => !c.ok && c.name.includes(fragment));
  assert.ok(found, `expected observation «${fragment}»: ${JSON.stringify(checks)}`);
  assert.equal(found.class, 'advisory', found.name);
  assert.ok(found.detail, 'the observation explains the presentation defect');
  assert.deepEqual(checks.filter((c) => !c.ok && c.class !== 'advisory'), []);
  const result = await report(checks);
  assert.equal(result.passed, true);
  assert.equal(result.verdict.passed, true);
  assert.ok(result.verdict.observations.some((c) => c.name === found.name && c.class === 'advisory' && c.detail === found.detail));
  assert.ok(result.lines.includes('ИТОГ: PASS'));
  assert.ok(result.lines.some((line) => line.includes(`OBSERVATION  ${found.name}`) && line.includes(found.detail)));
  return result;
}

// --------------------------------------------------------------------------------------------------------------------
// create
// --------------------------------------------------------------------------------------------------------------------
const C = { workflow: 'LOCAL-008-create-RUN', group: 'LOCAL-008-group-RUN', screenName: 'LOCAL-008-screen-RUN',
  roles: [{ id: 10, name: 'Administrators' }, { id: 20, name: 'Service Desk Team' }], statuses: { todo: { id: '1', name: 'To Do' }, progress: { id: '3', name: 'In Progress' } } };

function createWorld(o = {}) {
  const rule = { transitionKey: 'start', screen: { action: 'SET', screenKey: 'form' },
    addValidators: [{ template: 'comment_required' }, { template: 'required_fields', parameters: { fieldIds: ['description'] } }],
    addConditions: { operator: 'AND', rules: [{ template: 'group', parameters: { group: C.group } }, { template: 'project_role', parameters: { roles: ['Administrators', 'Service Desk Team'] } }] }, ...o.rule };
  const request = { dryRun: true, create: { name: C.workflow, statuses: [{ name: 'To Do' }, { name: 'In Progress' }], initialStatus: 'To Do',
    transitions: [{ key: 'start', name: 'Start', from: 'To Do', to: 'In Progress' }] },
  newScreens: [{ key: 'form', name: C.screenName, fieldIds: ['summary', 'description'] }], transitionRules: [rule], ...o.request };
  const table = '| Объект | Что |\n|---|---|\n| Workflow | LOCAL-008-create-RUN |\n| Переход | Start: To Do → In Progress |\n| Правила | комментарий обязателен; Description обязательно |\n'
    + `| Условия | нужно удовлетворить все условия: группа ${C.group} и член любой из ролей Administrators, Service Desk Team |\n| Экран | ${C.screenName} (Summary, Description) |`;
  const ask = o.ask ?? `${table}\n\nУсловия сужают доступ для всех проектов этого workflow, в том числе будущих. Группа пока пуста: это наблюдение на момент проверки, состав может измениться. Применить?`;
  const conversation = [{ role: 'user', content: 'Создай workflow' }, { role: 'assistant', content: ask }, { role: 'user', content: 'Да, применяй' },
    { role: 'assistant', content: `Готово. Фактически на Start: комментарий и Description обязательны; группа ${C.group}, роли Administrators и Service Desk Team; экран ${C.screenName}.` }];
  const trace = [];
  entry(trace, 1, 'list_statuses', { query: 'To Do' }, { statuses: [] });
  for (let i = 0; i < (o.previews ?? 1); i++) {
    entry(trace, 1, 'configure_workflow', request, { dryRun: true, status: 'WOULD_CREATE', planId: `p${i + 1}`, targetVersion: 'ACTIVE',
      statuses: [{ name: 'To Do', id: '1', isNew: false }, { name: 'In Progress', id: '3', isNew: false }], objects: [{ kind: 'WORKFLOW' }, { kind: 'SCREEN' }], warnings: [] });
  }
  if (o.extraTool) entry(trace, 1, o.extraTool, { dryRun: true }, { status: 'X' });
  operation(trace, 2, 'p1', o.status ?? 'CREATED');
  if (!o.noReadback) entry(trace, 2, 'get_workflow_validators', { workflow: C.workflow, version: 'ACTIVE' }, { revision: 'r2', transitions: [] });

  const screenId = o.screenId ?? '777';
  const validators = o.validators ?? [{ kind: 'COMMENT_REQUIRED', type: 'class', className: GROOVY_VALIDATOR, cannedScript: COMMENT }, { kind: 'REQUIRED_FIELDS', fieldIds: ['description'], conditional: false }];
  const root = o.root ?? { kind: 'COMPOUND', operator: 'AND', children: [{ kind: 'GROUP', group: C.group },
    { kind: 'PROJECT_ROLE', roleIds: [10, 20], roles: [{ id: 10, name: 'Administrators' }, { id: 20, name: 'Service Desk Team' }], inverted: false }] };
  const xml = o.xml ?? workflowXml([actionXml({ id: 5, name: 'Start', screen: screenId, view: o.view ?? 'fieldscreen', validators: [commentXml(1), requiredXml(['description'])],
    conditions: [groupXml(C.group), roleXml(2, [10, 20])] })]);
  const screens = o.screens ?? [{ id: 777, name: C.screenName }];
  const tool = async (name) => ({
    get_workflow: { revision: 'r2', active: false, hasDraft: false, consumers: { projectCount: 0, schemeCount: 0 },
      steps: [{ name: 'To Do', statusId: '1', initial: true }, { name: 'In Progress', statusId: '3', initial: false }],
      transitions: [{ actionId: 1, name: 'Create', kind: 'INITIAL', sourceStepIds: [], targetStepIds: [1], screenId: null }, ...(o.noStart ? [] : [{ actionId: 5, name: 'Start', kind: 'LOCAL', sourceStepIds: [1], targetStepIds: [2], screenId }])] },
    get_workflow_validators: { transitions: [{ actionId: 5, name: 'Start', validators }], scriptRunner: { supported: true } },
    get_workflow_conditions: { transitions: [{ actionId: 5, name: 'Start', root }] },
    get_screen_contents: { screen: { id: 777, name: C.screenName }, tabs: [{ name: 'Field Tab', fields: (o.screenFields ?? ['summary', 'description']).map((fieldId) => ({ fieldId })) }] },
  })[name];
  return { prepared: { ...C, screenFieldIds: ['summary', 'description'] }, trace, conversation, tool,
    rest: async () => ({ screens, total: screens.length }), adminPage: async () => ({ status: 200, text: xml }) };
}

test('workflow-configure-create: a complete one-plan dialogue passes every check', async () => {
  const checks = await run(verifyCreate, createWorld());
  assert.deepEqual(failing(checks), []);
  assert.ok(checks.length >= 18);
  assert.ok(checks.every((c) => ['contract', 'advisory'].includes(c.class)));
});

test('workflow-configure-create: each missing behaviour fails the check that guards it', async () => {
  const cases = [
    ['second preview', { previews: 2 }, 'один preview'],
    ['edit_workflow used', { extraTool: 'edit_workflow' }, 'одна операция'],
    ['removed tool called', { extraTool: 'create_workflow' }, 'удалённых'],
    ['workflow not CREATED', { status: 'PARTIAL' }, 'CREATED'],
    ['no read-back after apply', { noReadback: true }, 'перечитал'],
    ['no table', { ask: 'Создать workflow с правилами и экраном?' }, 'таблица', true],
    ['two questions', { ask: '| a | b |\n|---|---|\n| Start | LOCAL-008-create-RUN LOCAL-008-group-RUN LOCAL-008-screen-RUN Description Administrators Service Desk Team |\nВсе верно? Применить?' }, 'один вопрос', true],
    ['no AND wording', { ask: '| Start | LOCAL-008-create-RUN LOCAL-008-group-RUN LOCAL-008-screen-RUN Description Administrators Service Desk Team |\n|---|---|\n| a | b |\nОграничения сужают доступ, комментарий обязателен. Применить?' }, 'AND', true],
    ['one validator missing', { validators: [{ kind: 'COMMENT_REQUIRED', type: 'class', className: GROOVY_VALIDATOR, cannedScript: COMMENT }] }, 'два validators'],
    ['OR root', { root: { kind: 'COMPOUND', operator: 'OR', children: [{ kind: 'GROUP', group: C.group }, { kind: 'PROJECT_ROLE', roleIds: [10, 20], roles: [], inverted: false }] } }, 'корень AND'],
    ['screen assigned without view', { view: '' }, 'view="fieldscreen"'],
    ['screen duplicated', { screens: [{ id: 777, name: C.screenName }, { id: 778, name: C.screenName }] }, 'ровно один экран'],
    ['wrong screen fields', { screenFields: ['description', 'summary'] }, 'заданном порядке'],
    ['rules in another request', { rule: { addValidators: [{ template: 'comment_required' }] } }, 'один блок правил'],
    ['request without a new screen', { request: { newScreens: [] }, rule: { screen: undefined } }, 'новый экран'],
  ];
  const misses = [];
  for (const [label, options, fragment, presentation] of cases) {
    const world = createWorld(options);
    const checks = [...traceChecks(world.trace, world.conversation, { classifyPresentation: true }), ...await run(verifyCreate, world)];
    if (!mentions(checks, fragment)) misses.push(`${label}: expected «${fragment}», failing: ${JSON.stringify(failing(checks))}`);
    if (presentation) await advisory(checks, fragment);
    else {
      assert.ok(checks.some((c) => !c.ok && c.class === 'contract' && c.name.includes(fragment)), label);
      assert.equal((await report(checks)).passed, false, label);
    }
  }
  assert.deepEqual(misses, []);
});

test('LOCAL-008 captured forms accept canonical UUID identities without requiring random version four', async () => {
  const base = workflowXml([actionXml({ id: 5, name: 'Start', screen: '777', view: 'fieldscreen',
    validators: [commentXml(1), requiredXml(['description'])], conditions: [groupXml(C.group), roleXml(2, [10, 20])] })]);
  for (const version of ['1', '3', '4', '5', '6', '7', '8']) {
    const xml = base.replaceAll('-4000-8000-', `-${version}000-8000-`);
    assert.deepEqual(failing(await run(verifyCreate, createWorld({ xml }))), [], `valid UUID version ${version}`);
  }
  for (const value of ['', 'not-a-uuid', '9820371e-9625-38f0-934d-1296b7c9c4d', '9820371e-9625-38f0-934d-1296b7c9c4d5-extra']) {
    const xml = base.replaceAll(/00000000-0000-4000-8000-\d{12}/gu, value);
    const checks = await run(verifyCreate, createWorld({ xml }));
    assert.ok(mentions(checks, 'captured-форме с UUID'), `invalid comment id ${value}`);
    assert.ok(mentions(checks, 'оба условия') || mentions(checks, 'ровно два условия'), `invalid role id ${value}`);
  }
  for (const [from, to, check] of [
    [COMMENT, 'different.CommentValidator', 'captured-форме с UUID'],
    [ROLE_CANNED, 'different.RoleCondition', 'ровно два условия'],
    [arg('FIELD_INVERT_CONDITION', ''), arg('FIELD_INVERT_CONDITION', 'on'), 'ровно два условия'],
    ['10|||20', '10|||99', 'ровно два условия'],
    [arg('class.name', GROOVY_VALIDATOR), arg('class.name', GROOVY_VALIDATOR) + arg('extra', 'value'), 'captured-форме с UUID'],
    [arg('class.name', ROLE_CLASS), arg('class.name', ROLE_CLASS) + arg('extra', 'value'), 'ровно два условия'],
  ]) assert.ok(mentions(await run(verifyCreate, createWorld({ xml: base.replace(from, to) })), check), `${from} remains protected`);
  // Migrated comment-required and workflow-condition callers keep their original version-four checks by default.
  assert.equal(exactComment(parseXml(`<workflow>${commentXml(1).replace('-4000-', '-3000-')}</workflow>`).children[0], true), false);
  assert.equal(exactRole(nodes(parseXml(`<workflow>${roleXml(2, [10, 20]).replace('-4000-', '-3000-')}</workflow>`))[0], { ids: [10, 20], invert: false }), false);
});

test('LOCAL-008 access semantics accept explicit conjunction and any-role membership, not OR or negation', async () => {
  const base = createWorld().conversation[1].content;
  const observed = base.replace('нужно удовлетворить все условия: группа LOCAL-008-group-RUN и член любой из ролей',
    'членство в группе LOCAL-008-group-RUN **И** хотя бы в одной роли')
    .replace('Условия сужают доступ для всех проектов этого workflow, в том числе будущих.', 'Ограничения будут действовать во всех проектах, которым назначат workflow.');
  assert.deepEqual(failing(await run(verifyCreate, createWorld({ ask: observed }))), []);
  for (const phrase of ['Условия: группа G И роль R', 'Требуются все условия', 'Условия доступа: AND группы и роли']) assert.equal(conveysAnd(phrase), true, phrase);
  for (const phrase of ['Условия: группа G ИЛИ роль R', 'Не нужно удовлетворять все условия', 'AND не требуется для условий', 'Все условия необязательны', 'Все условия не применяются', 'Экран Summary и Description', 'Команды AND сохраняются в журнале']) assert.equal(conveysAnd(phrase), false, phrase);
  for (const phrase of ['Член хотя бы одной роли', 'Членство хотя бы в одной роли', 'Член любой из ролей', 'Для доступа достаточно одной из ролей']) assert.equal(conveysAnyRole(phrase), true, phrase);
  for (const phrase of ['Требуются все роли', 'Не достаточно одной из ролей', 'Не требуется членство хотя бы в одной роли', 'Членство в группе или переход', 'Любой экран роли не касается']) assert.equal(conveysAnyRole(phrase), false, phrase);
  for (const phrase of ['Доступ не ограничивается', 'Правила не ограничивают доступ', 'Правила не сужают доступ', 'Ограничения экрана действуют дополнительно', 'Новые правила действуют дополнительно', 'Ограничения доступа не действуют во всех проектах']) {
    assert.equal(conveysNarrowing(phrase), false, phrase);
  }
  assert.equal(conveysNarrowing('Условия: группа G И роль R; новые ограничения действуют дополнительно во всех проектах workflow'), true);
  assert.equal(conveysNarrowing('Условия: группа G ИЛИ роль R; новые ограничения действуют дополнительно во всех проектах workflow'), false);
  // Word boundaries, membership-bound conjunction, OR in any order and place of the access part.
  for (const phrase of ['Будет добавлено условие роли R', 'Условие группы G добавляется на Start work и Finish, роль — на Pause', 'Оба условия объединены через OR',
    'роль R или группа G; оба условия проверяются', 'Условия: роль R либо группа G. Оба условия проверяются.', 'Достаточно одного из условий: группа G или роль R',
    'Достаточно любого из условий; группа G и роль R перечислены']) assert.equal(conveysAnd(phrase), false, phrase);
  for (const phrase of ['Нужно состоять в группе G и быть членом роли R', 'Требуется членство в группе G и в роли R', 'Условия объединены через AND, а не через OR: группа G и роль R',
    'Нужно быть в группе G и хотя бы в одной из ролей Administrators, Service Desk Team']) assert.equal(conveysAnd(phrase), true, phrase);
  for (const phrase of ['Пользователь не член ни одной из ролей Administrators, Service Desk Team', 'Не состоит ни в одной из ролей Administrators, Service Desk Team']) {
    assert.equal(conveysAnyRole(phrase), false, phrase);
  }
  for (const phrase of ['Сужения доступа нет — workflow пока не назначен проектам', 'Сужения доступа не будет', 'Ограничений доступа нет, пока workflow не назначен проектам',
    'Доступ не будет сужен: проектов нет', 'Нет сужения доступа']) assert.equal(conveysNarrowing(phrase), false, phrase);
  for (const phrase of ['Условия сужают доступ, даже когда проектов нет', 'Других ограничений нет. Условия сужают доступ для всех проектов этого workflow']) {
    assert.equal(conveysNarrowing(phrase), true, phrase);
  }
});

test('LOCAL-008 any-role disjunction inside a role list is not an OR between the group and the role', () => {
  for (const phrase of ['Доступ: член группы G AND хотя бы одной роли: Administrators OR Service Desk Team',
    'Нужно быть членом любой из ролей Administrators или Service Desk Team и группы G',
    'член группы G, а также любой из ролей Administrators или Service Desk Team (роль проверяется в проекте задачи)',
    '| Доступ к `Start` | Членство в `LOCAL-008-group-HTPVC` **И** хотя бы в одной роли проекта: `Administrators` **ИЛИ** `Service Desk Team` |',
    'Членство в LOCAL-008-group-HTPVC И хотя бы в одной роли проекта: Administrators ИЛИ Service Desk Team']) assert.equal(conveysAnd(phrase), true, phrase);
  // The OR between the group and a role, or between the conditions, is still rejected, also next to a role list.
  for (const phrase of ['Доступ: группа G OR роль R', 'Доступ: группа G OR роли: Administrators OR Service Desk Team', 'Доступ: группа G или роли: Administrators, Service Desk Team',
    'Доступ: группа G или в роли R', 'Доступ: роль Administrators или группа G', 'Доступ: роли Administrators или Service Desk Team или группа G',
    'Условия: роль R или группа G; оба условия проверяются', 'Доступ: группа G, а также роль R, достаточно любого из условий',
    'Условия: группа G, роль на Pause и группа на Finish']) assert.equal(conveysAnd(phrase), false, phrase);
});

// The first preview replies of the real LIVE-RUN dialogues (create HTPVC, existing HWM88), verbatim: the run folders are ignored by git.
const captured = (name) => readFileSync(new URL(`./fixtures/configure-preview-${name}.md`, import.meta.url), 'utf8');
const AND_SENTENCE = 'Новые условия объединены через AND';

test('LOCAL-008 OR is classified per operand: a disjunction of roles never cuts AND, group-vs-role and condition OR always do', async () => {
  // Verbatim real previews: «ИЛИ» between the role names; the Latin form is the same text with OR.
  const htpvc = captured('htpvc');
  const hwm88 = captured('hwm88');
  assert.ok(htpvc.includes('Administrators` **ИЛИ** `Service Desk Team'), 'fixture is the captured HTPVC preview');
  assert.ok(hwm88.includes('Administrators ИЛИ Service Desk Team**'), 'fixture is the captured HWM88 preview');
  for (const [name, reply] of [['HTPVC', htpvc], ['HWM88', hwm88]]) {
    assert.equal(conveysAnd(reply), true, `${name} verbatim`);
    assert.equal(conveysAnd(reply.replaceAll('ИЛИ', 'OR')), true, `${name} with Latin OR`);
    assert.equal(conveysAnd(reply.replaceAll('ИЛИ', 'или')), true, `${name} with lower-case или`);
  }
  assert.equal(conveysNarrowing(htpvc.replaceAll('ИЛИ', 'OR')), true);
  assert.equal(conveysAnyRole(hwm88.replaceAll('ИЛИ', 'OR')), true);

  const G = 'член группы G';
  const roleDisjunctions = ['хотя бы в одной роли проекта Administrators OR Service Desk Team', 'любой из ролей Administrators OR Service Desk Team',
    'любой из ролей Administrators или Service Desk Team', 'хотя бы одной роли: Administrators OR Service Desk Team', 'роли проекта: A OR B OR C',
    'роли проекта: A или B или C', 'роли: A либо B', 'любой из ролей Administrators OR Service Desk Team OR Service Desk Customers'];
  for (const roles of roleDisjunctions) {
    for (const phrase of [`${G} AND ${roles}`, `${G} И ${roles}`, `Нужно быть ${roles} и группы G`]) assert.equal(conveysAnd(phrase), true, phrase);
    // Without an AND between the group and the role the disjunction of roles alone conveys nothing about AND.
    assert.equal(conveysAnd(roles), false, roles);
  }
  for (const phrase of ['Доступ: член группы G AND хотя бы одной роли: Administrators OR Service Desk Team',
    'Нужно быть членом любой из ролей Administrators или Service Desk Team и группы G',
    'член группы G, а также любой из ролей Administrators или Service Desk Team (роль проверяется в проекте задачи)',
    '| Pause | Добавить одно условие: членство в Administrators OR Service Desk Team в проекте задачи | Остальное не меняется; все прежние условия через AND |',
    '| Pause | Добавить одно условие: членство в Administrators ИЛИ Service Desk Team в проекте задачи | Новые условия добавить через AND |',
    'Доступ: член группы G и роли проекта: A OR B OR C']) assert.equal(conveysAnd(phrase), true, phrase);

  // OR between the group and a role (any order and wording), between conditions or as the combining operator: rejected on its own
  // and next to an explicit AND sentence, so that the OR operand classification, not the missing AND, is what decides.
  for (const phrase of ['Условия: группа G ИЛИ роль R', 'роль R или группа G; оба условия проверяются', 'Условия объединяются через OR',
    'Достаточно одного из условий: группа G и роль R', 'Достаточно любого из условий; группа G и роль R перечислены',
    'Группа G OR роли: A OR B', 'Доступ: группа G или в роли R', 'роли проекта: A или участники группы G; оба условия проверяются',
    'роли: A OR член группы G. Все условия обязательны', 'роли: R или любой член группы G', 'роли: R или пользователь из группы G',
    'член группы G или пользователь с ролью R; новые условия через AND', 'группа G или проектная роль R; оба условия',
    'Оба условия объединены через OR', 'Условия соединены OR, группа G и роль R', 'Доступ: роли Administrators или Service Desk Team или группа G',
    'Доступ: условие 1 OR условие 2', 'Доступ: группа G, либо роль R', 'Доступ: группа G (или роль R)', 'Доступ: роль R, или группа G',
    'Условия: роли: A OR B OR C OR член группы G']) {
    assert.equal(conveysAnd(phrase), false, phrase);
    assert.equal(conveysAnd(`${phrase}. ${AND_SENTENCE}`), false, `${phrase} + AND`);
  }
  // Phrases with no OR at all stay unconvincing without a conjunction of the predicates.
  for (const phrase of ['Будет добавлено условие роли R', 'Условие группы G добавляется на Start work и Finish, роль — на Pause']) assert.equal(conveysAnd(phrase), false, phrase);
  // A negated OR is the AND statement, in either alphabet.
  for (const phrase of ['Условия объединены через AND, а не через OR: группа G и роль R', 'Условия: группа G и роль R, не OR', 'Условия объединены через AND, а не через ИЛИ: группа G и роль R'])
    assert.equal(conveysAnd(phrase), true, phrase);
  // The real replies turn into a rejection as soon as the role disjunction is replaced by the group-vs-role OR.
  const swap = (text, from, to) => { assert.ok(text.includes(from), from); return text.replace(from, to); };
  assert.equal(conveysAnd(swap(htpvc, 'Членство в `LOCAL-008-group-HTPVC` **И** хотя бы в одной роли проекта: `Administrators` **ИЛИ** `Service Desk Team`',
    'Членство в `LOCAL-008-group-HTPVC` **ИЛИ** хотя бы в одной роли проекта: `Administrators` **И** `Service Desk Team`')), false);
  assert.equal(conveysAnd(swap(hwm88, 'новые условия добавить через **AND**', 'новые условия добавить через **OR**')), false);
});

// --------------------------------------------------------------------------------------------------------------------
// existing
// --------------------------------------------------------------------------------------------------------------------
const E = { workflow: 'LOCAL-008-existing-RUN', group: 'LOCAL-008-group-RUN', formName: 'LOCAL-008-form-RUN', seedGroup: 'jira-administrators',
  seedRole: { id: 30, name: 'Service Desk Customers' }, targetRoles: [{ id: 10, name: 'Administrators' }, { id: 20, name: 'Service Desk Team' }],
  existingScreen: { id: '500', name: 'LOCAL-008-form-RUN' }, actions: { 'Start work': 11, Finish: 12, Pause: 13 } };
const seedRoleNode = { kind: 'PROJECT_ROLE', roleIds: [30], roles: [{ id: 30, name: 'Service Desk Customers' }], inverted: false };
const seedTree = () => ({ kind: 'COMPOUND', operator: 'AND', children: [{ kind: 'GROUP', group: 'jira-administrators' }, seedRoleNode] });
const graph = [
  { actionId: 11, name: 'Start work', kind: 'LOCAL', from: [1], to: [2], resolution: { action: 'PRESERVE' } },
  { actionId: 12, name: 'Finish', kind: 'GLOBAL', from: [1, 2], to: [3], resolution: { action: 'SET', value: '1' } },
  { actionId: 13, name: 'Pause', kind: 'LOCAL', from: [2], to: [1], resolution: { action: 'PRESERVE' } },
];
const steps = [{ name: 'To Do', statusId: '1', initial: true }, { name: 'In Progress', statusId: '3', initial: false }, { name: 'Done', statusId: '5', initial: false }];
const seedConditionsXml = () => [groupXml('jira-administrators'), roleXml(40, [30])];
const beforeValidators = { 11: [{ kind: 'REQUIRED_FIELDS', fieldIds: ['assignee'], conditional: false }], 12: [{ kind: 'REQUIRED_FIELDS', fieldIds: ['assignee', 'description'], conditional: false }], 13: [] };
const commentShape = { kind: 'COMMENT_REQUIRED', type: 'class', className: GROOVY_VALIDATOR, cannedScript: COMMENT };
const beforeXml = workflowXml([
  actionXml({ id: 11, name: 'Start work', validators: [requiredXml(['assignee'])], conditions: seedConditionsXml() }),
  actionXml({ id: 12, name: 'Finish', validators: [requiredXml(['assignee', 'description'])], conditions: seedConditionsXml() }),
  actionXml({ id: 13, name: 'Pause', screen: '500', view: 'fieldscreen', conditions: seedConditionsXml() }),
]);
const beforeState = { revision: 'r1', active: false, hasDraft: false, consumers: { projectCount: 0, schemeCount: 0 }, steps, graph,
  screens: { 11: null, 12: null, 13: '500' }, validators: beforeValidators,
  conditions: { 11: seedTree(), 12: seedTree(), 13: seedTree() }, scriptRunner: { supported: true } };

function existingWorld(o = {}) {
  const block = { screen: { action: 'SET', screenKey: 'form' }, addValidators: [{ template: 'comment_required' }, { template: 'required_fields', parameters: { fieldIds: ['assignee', 'description'] } }],
    addConditions: { operator: 'AND', rules: [{ template: 'group', parameters: { group: E.group } }] } };
  const rules = o.rules ?? [{ actionId: 11, ...block }, { actionId: 12, ...block },
    { actionId: 13, addConditions: { operator: 'AND', rules: [{ template: 'project_role', parameters: { roles: ['Administrators', 'Service Desk Team'] } }] } }];
  const request = { dryRun: true, existing: { workflow: E.workflow, version: 'ACTIVE', expectedRevision: 'r1' },
    newScreens: [{ key: 'form', name: E.formName, fieldIds: ['summary', 'assignee'] }], transitionRules: rules, ...o.request };
  const table = '| Переход | Что добавляется |\n|---|---|\n| Start work | комментарий обязателен, Assignee и Description обязательны; условия: уже стоящие jira-administrators и Service Desk Customers, плюс группа LOCAL-008-group-RUN |\n'
    + '| Finish | то же |\n| Pause | любой из ролей Administrators, Service Desk Team (к уже стоящим) |\n'
    + `| Экран | новый ${E.formName} для Start work и Finish, только Summary и Assignee |\n`
    + (o.noPlacementReply ? '' : '| Предупреждение | Обязательного Description нет на экране; пользователь может не суметь заполнить поле. |');
  const ask = o.ask ?? `LOCAL-008-existing-RUN:\n${table}\n\nНужно удовлетворить все условия. Экран с таким именем уже существует: создам отдельный новый, существующий не переиспользуется. Условия сужают доступ для проектов workflow. Группа пока пуста — наблюдение на момент проверки, состав может измениться. Применить?`;
  const conversation = [{ role: 'user', content: 'Настрой' }, { role: 'assistant', content: ask }, { role: 'user', content: 'Да, применяй' },
    { role: 'assistant', content: `Готово: LOCAL-008-group-RUN, ${E.formName}; Start work, Finish, Pause настроены.` }];
  const trace = [];
  entry(trace, 1, 'get_workflow', { workflow: E.workflow, version: 'ACTIVE' }, { revision: 'r1' });
  for (let i = 0; i < (o.previews ?? 1); i++) {
    entry(trace, 1, 'configure_workflow', request, { dryRun: true, status: 'WOULD_CHANGE', planId: `p${i + 1}`, targetVersion: 'ACTIVE', objects: [{ kind: 'SCREEN' }],
      warnings: [
        ...(o.noDupWarning ? [] : [`A screen named '${E.formName}' already exists. A separate new screen will be created; an identical name does not mean reuse.`]),
        ...(o.noPlacementWarning ? [] : [o.placementWarning ?? "The workflow is not assigned to any project and issue type, so only the transition screen was checked: Field 'Description' (description) is not on it, and a user may be unable to fill it in."]),
      ] });
  }
  if (o.extraTool) entry(trace, 1, o.extraTool, {}, {});
  operation(trace, 2, 'p1', o.status ?? 'UPDATED_INACTIVE');
  entry(trace, 2, 'get_workflow_conditions', { workflow: E.workflow, version: 'ACTIVE' }, { revision: 'r2' });

  const newId = o.newId ?? '501';
  const startValidators = o.startValidators ?? [...beforeValidators[11], commentShape, { kind: 'REQUIRED_FIELDS', fieldIds: ['description'], conditional: false }];
  const finishValidators = o.finishValidators ?? [...beforeValidators[12], commentShape];
  const groupNode = { kind: 'GROUP', group: E.group };
  const append = (node) => ({ ...seedTree(), children: [...seedTree().children, node] });
  const roleNode = { kind: 'PROJECT_ROLE', roleIds: [10, 20], roles: [{ id: 10, name: 'Administrators' }, { id: 20, name: 'Service Desk Team' }], inverted: false };
  const after = { revision: 'r2', active: false, hasDraft: false, consumers: { projectCount: 0, schemeCount: 0 }, steps, graph: o.graph ?? graph,
    screens: { 11: newId, 12: o.finishScreen ?? newId, 13: '500' },
    validators: { 11: startValidators, 12: finishValidators, 13: [] },
    conditions: { 11: o.startConditions ?? append(groupNode), 12: append(groupNode), 13: append(roleNode) } };
  const afterXml = o.xml ?? workflowXml([
    actionXml({ id: 11, name: 'Start work', screen: newId, view: 'fieldscreen', validators: [requiredXml(['assignee']), commentXml(50), requiredXml(['description'])], conditions: [...seedConditionsXml(), groupXml(E.group)] }),
    actionXml({ id: 12, name: 'Finish', screen: newId, view: 'fieldscreen', validators: [requiredXml(['assignee', 'description']), commentXml(51)], conditions: [...seedConditionsXml(), groupXml(E.group)] }),
    actionXml({ id: 13, name: 'Pause', screen: '500', view: 'fieldscreen', conditions: [...seedConditionsXml(), roleXml(52, [10, 20])] }),
  ]);
  const named = o.named ?? [{ id: 500, name: E.formName }, { id: Number(newId), name: E.formName }];
  const tool = async (name, args) => {
    if (name === 'get_screen_contents') {
      const fields = String(args.screenId) === '500' ? (o.oldFields ?? ['summary']) : (o.newFields ?? ['summary', 'assignee']);
      return { screen: { id: args.screenId, name: E.formName }, tabs: [{ name: 'Field Tab', fields: fields.map((fieldId) => ({ fieldId })) }] };
    }
    return ({
      get_workflow: { revision: after.revision, active: false, hasDraft: false, consumers: after.consumers, steps, transitions: after.graph.map((t) => ({ actionId: t.actionId, name: t.name, kind: t.kind, sourceStepIds: t.from, targetStepIds: t.to, resolution: t.resolution, screenId: after.screens[t.actionId] })) },
      get_workflow_validators: { transitions: Object.entries(after.validators).map(([actionId, v]) => ({ actionId: Number(actionId), validators: v })), scriptRunner: { supported: true } },
      get_workflow_conditions: { transitions: Object.entries(after.conditions).map(([actionId, root]) => ({ actionId: Number(actionId), root })) },
    })[name];
  };
  return { prepared: { ...E, state: beforeState, xml: beforeXml, formContents: { name: E.formName, tabs: ['Field Tab'], fieldIds: ['summary'] } }, trace, conversation, tool,
    rest: async () => ({ screens: named, total: named.length }), adminPage: async () => ({ status: 200, text: afterXml }) };
}

test('workflow-configure-existing: a complete one-plan dialogue passes every check', async () => {
  const checks = await run(verifyExisting, existingWorld());
  assert.deepEqual(failing(checks), []);
  assert.ok(checks.length >= 25);
  assert.ok(checks.every((c) => ['contract', 'advisory'].includes(c.class)));
});

test('workflow-configure-existing: each missing behaviour fails the check that guards it', async () => {
  const block = { screen: { action: 'SET', screenKey: 'form' }, addValidators: [{ template: 'comment_required' }, { template: 'required_fields', parameters: { fieldIds: ['assignee', 'description'] } }],
    addConditions: { operator: 'AND', rules: [{ template: 'group', parameters: { group: E.group } }] } };
  const pauseRule = { actionId: 13, addConditions: { operator: 'AND', rules: [{ template: 'project_role', parameters: { roles: ['Administrators', 'Service Desk Team'] } }] } };
  const cases = [
    ['second preview', { previews: 2 }, 'один preview'],
    ['edit_workflow used', { extraTool: 'edit_workflow' }, 'одна операция'],
    ['no duplicate-name warning', { noDupWarning: true }, 'предупреждает о совпадении'],
    ['no missing-field warning', { noPlacementWarning: true }, 'AC-10: preview предупреждает'],
    ['warning names a different field', { placementWarning: "The workflow is not assigned to any project and issue type, so only the transition screen was checked: Field 'Assignee' (assignee) is not on it, and a user may be unable to fill it in." }, 'AC-10: preview предупреждает'],
    ['warning omits fill-in risk', { placementWarning: "The workflow is not assigned to any project and issue type, so only the transition screen was checked: Field 'Description' (description) is not on it." }, 'AC-10: preview предупреждает'],
    ['assistant hides missing-field warning', { noPlacementReply: true }, 'AC-10/AC-11: до подтверждения', true],
    ['required field silently put on screen', { newFields: ['summary', 'assignee', 'description'] }, 'AC-10: обязательное Description'],
    ['missing-screen field no longer required', { startValidators: [...beforeValidators[11], commentShape] }, 'AC-10: обязательное Description'],
    ['reply does not mention the duplicate', { ask: '| Переход | x |\n|---|---|\n| LOCAL-008-existing-RUN Start work Finish Pause LOCAL-008-form-RUN LOCAL-008-group-RUN jira-administrators Administrators Service Desk Team Service Desk Customers | комментарий Assignee Description все условия любой из ролей |\nСужает доступ. Группа пуста на момент проверки, состав может измениться. Применить?' }, 'существующий экран не переиспользуется', true],
    ['existing screen reused by id', { rules: [{ actionId: 11, ...block, screen: { action: 'SET', screenId: '500' } }, { actionId: 12, ...block, screen: { action: 'SET', screenId: '500' } }, pauseRule] }, 'ровно один новый экран'],
    ['pause gets a screen', { rules: [{ actionId: 11, ...block }, { actionId: 12, ...block }, { ...pauseRule, screen: { action: 'SET', screenKey: 'form' } }] }, 'Pause — одно условие'],
    ['duplicate required_fields added on Finish', { finishValidators: [...beforeValidators[12], commentShape, { kind: 'REQUIRED_FIELDS', fieldIds: ['description'], conditional: false }] }, 'дубль не добавлен'],
    ['existing validator rewritten', { startValidators: [{ kind: 'REQUIRED_FIELDS', fieldIds: ['assignee', 'description'], conditional: false }, commentShape] }, 'отдельный REQUIRED_FIELDS'],
    ['foreign conditions replaced', { startConditions: { kind: 'COMPOUND', operator: 'AND', children: [{ kind: 'GROUP', group: E.group }] } }, 'посторонние условия'],
    ['native API refused the duplicate name', { named: [{ id: 500, name: E.formName }], newId: '500' }, 'принял дубль'],
    ['transitions share different screens', { finishScreen: '502' }, 'ОДИН новый экран'],
    ['graph changed', { graph: graph.map((t) => t.actionId === 13 ? { ...t, name: 'Stop' } : t) }, 'граф не изменён'],
    ['existing screen edited', { oldFields: ['summary', 'assignee'] }, 'существующий экран не изменён'],
    ['not UPDATED_INACTIVE', { status: 'PARTIAL' }, 'UPDATED_INACTIVE'],
    ['descriptor changed elsewhere', { xml: workflowXml([
      actionXml({ id: 11, name: 'Start work', screen: '501', view: 'fieldscreen', validators: [requiredXml(['assignee']), commentXml(50), requiredXml(['description'])], conditions: [...seedConditionsXml(), groupXml(E.group)] }),
      actionXml({ id: 12, name: 'Finish', screen: '501', view: 'fieldscreen', validators: [requiredXml(['assignee', 'description']), commentXml(51)], conditions: [...seedConditionsXml(), groupXml(E.group)] }),
      actionXml({ id: 13, name: 'Pause', screen: '500', view: 'fieldscreen', conditions: [groupXml('jira-administrators'), roleXml(52, [10, 20])] }),
    ]) }, 'прежние условия корня'],
  ];
  const misses = [];
  for (const [label, options, fragment, presentation] of cases) {
    const world = existingWorld(options);
    const checks = [...traceChecks(world.trace, world.conversation, { classifyPresentation: true }), ...await run(verifyExisting, world)];
    if (!mentions(checks, fragment)) misses.push(`${label}: expected «${fragment}», failing: ${JSON.stringify(failing(checks))}`);
    if (presentation) await advisory(checks, fragment);
    else {
      assert.ok(checks.some((c) => !c.ok && c.class === 'contract' && c.name.includes(fragment)), label);
      assert.equal((await report(checks)).passed, false, label);
    }
  }
  assert.deepEqual(misses, []);
});

// --------------------------------------------------------------------------------------------------------------------
// stale
// --------------------------------------------------------------------------------------------------------------------
const S = { workflow: 'LOCAL-008-stale-RUN', screenName: 'LOCAL-008-screen-RUN', actions: { 'Start work': 21, Finish: 22 }, spare: { id: '5', name: 'Done' } };
const sSteps = [{ name: 'To Do', statusId: '1', initial: true }, { name: 'In Progress', statusId: '3', initial: false }];
const PERMISSION = { kind: 'OTHER', type: 'class', className: 'com.atlassian.jira.workflow.validator.PermissionValidator' };
const sGraph = [{ actionId: 1, name: 'Create', kind: 'INITIAL', from: [], to: [1], resolution: undefined },
  { actionId: 21, name: 'Start work', kind: 'LOCAL', from: [1], to: [2], resolution: { action: 'PRESERVE' } }, { actionId: 22, name: 'Finish', kind: 'LOCAL', from: [2], to: [1], resolution: { action: 'PRESERVE' } }];
const sBefore = { revision: 'r1', active: false, hasDraft: false, consumers: { projectCount: 0, schemeCount: 0 }, steps: sSteps, graph: sGraph,
  screens: { 1: null, 21: null, 22: null }, validators: { 1: [PERMISSION], 21: [], 22: [] }, conditions: { 1: null, 21: null, 22: null }, scriptRunner: { supported: true } };
const sXml = workflowXml([actionXml({ id: 21, name: 'Start work' }), actionXml({ id: 22, name: 'Finish' })], '<step id="9" name="Done"/>');

function staleWorld(o = {}) {
  const request = (rev) => ({ dryRun: true, existing: { workflow: S.workflow, version: 'ACTIVE', expectedRevision: rev },
    newScreens: [{ key: 'form', name: S.screenName, fieldIds: ['summary', 'description'] }],
    transitionRules: [{ actionId: 21, screen: { action: 'SET', screenKey: 'form' }, addValidators: [{ template: 'comment_required' }, { template: 'required_fields', parameters: { fieldIds: ['description'] } }] }] });
  const table = `| Переход | Что |\n|---|---|\n| ${S.workflow} Start work | комментарий и Description обязательны |\n| Экран | ${S.screenName} |`;
  const conversation = [{ role: 'user', content: 'Настрой' }, { role: 'assistant', content: `${table}\nПрименить?` }, { role: 'user', content: 'Да, применяй' },
    { role: 'assistant', content: o.explain ?? `План устарел: workflow изменили, ничего не записано. Новый план:\n${table}\nПрименить?` }, { role: 'user', content: 'Нет, пока ничего не применяй.' },
    { role: 'assistant', content: 'Хорошо, ничего не применяю.' }];
  const trace = [];
  entry(trace, 1, 'get_workflow', { workflow: S.workflow, version: 'ACTIVE' }, { revision: 'r1' });
  entry(trace, 1, 'configure_workflow', request('r1'), { dryRun: true, status: 'WOULD_CHANGE', planId: 'p1', targetVersion: 'ACTIVE', objects: [{ kind: 'SCREEN' }], warnings: [] });
  entry(trace, 2, 'start_operation', { toolName: 'configure_workflow', arguments: { dryRun: false, planId: 'p1' } }, { operationId: 'op1', status: 'QUEUED' });
  entry(trace, 2, 'get_operation', { operationId: 'op1' }, { status: 'COMPLETED', operation: 'configure_workflow', result: { status: o.status ?? 'FAILED', errorCode: o.code ?? 'PREVIEW_STALE' } });
  if (!o.noReread) entry(trace, 2, 'get_workflow', { workflow: S.workflow, version: 'ACTIVE' }, { revision: 'r2' });
  if (!o.noNewPlan) entry(trace, 2, 'configure_workflow', request(o.newRevision ?? 'r2'), { dryRun: true, status: 'WOULD_CHANGE', planId: o.samePlan ? 'p1' : 'p2', targetVersion: 'ACTIVE', objects: [{ kind: 'SCREEN' }], warnings: [] });
  if (o.thirdApply) {
    entry(trace, 3, 'start_operation', { toolName: 'configure_workflow', arguments: { dryRun: false, planId: 'p2' } }, { operationId: 'op2', status: 'QUEUED' });
  }
  const written = o.written ?? false;
  const after = { ...sBefore, revision: 'r2', steps: [...sSteps, { name: 'Done', statusId: o.injectedStatusId ?? '5', initial: false }],
    validators: { 1: o.initialValidators ?? [PERMISSION], 21: written ? [{ kind: 'COMMENT_REQUIRED' }] : [], 22: [] }, screens: written ? { 1: null, 21: '800', 22: null } : { 1: null, 21: null, 22: null } };
  const tool = async (name) => ({
    get_workflow: { revision: after.revision, active: false, hasDraft: false, consumers: after.consumers, steps: after.steps, transitions: after.graph.map((t) => ({ actionId: t.actionId, name: t.name, kind: t.kind, sourceStepIds: t.from, targetStepIds: t.to, resolution: t.resolution, screenId: after.screens[t.actionId] })) },
    get_workflow_validators: { transitions: Object.entries(after.validators).map(([actionId, validators]) => ({ actionId: Number(actionId), validators })), scriptRunner: { supported: true } },
    get_workflow_conditions: { transitions: Object.entries(after.conditions).map(([actionId, root]) => ({ actionId: Number(actionId), root })) },
  })[name];
  const named = o.screenCreated ? [{ id: 800, name: S.screenName }] : [];
  // setup.json stores the prepared state as JSON: keys with an undefined value are gone, the live reader state must still equal it.
  return { prepared: { ...S, state: JSON.parse(JSON.stringify(sBefore)), xml: sXml }, trace, conversation, tool, rest: async () => ({ screens: named, total: named.length }),
    adminPage: async () => ({ status: 200, text: sXml }),
    lifecycle: { injection: o.noHook ? undefined : { turn: 1, status: 'Done', statusId: '5', revisionBefore: 'r1', revisionAfter: 'r2' } } };
}

test('workflow-configure-stale: a complete stale dialogue passes every check', async () => {
  const checks = await run(verifyStale, staleWorld());
  assert.deepEqual(failing(checks), []);
  assert.ok(checks.length >= 14);
  assert.ok(checks.every((c) => ['contract', 'advisory'].includes(c.class)));
});

test('workflow-configure-stale: each missing behaviour fails the check that guards it', async () => {
  const cases = [
    ['no outside change', { noHook: true }, 'внешняя правка'],
    ['outside step uses an unapproved status', { injectedStatusId: '7' }, 'workflow равен внешнему изменению'],
    ['apply was not stale', { status: 'UPDATED_INACTIVE', code: undefined }, 'PREVIEW_STALE'],
    ['no new preview', { noNewPlan: true }, 'новый preview'],
    ['no re-read', { noReread: true }, 'перечитан'],
    ['old plan repeated', { samePlan: true }, 'другим planId'],
    ['new preview with the old revision', { newRevision: 'r1' }, 'expectedRevision'],
    ['new plan applied without confirmation', { thirdApply: true }, 'один start_operation'],
    ['no explanation', { explain: '| a | b |\n|---|---|\n| LOCAL-008-stale-RUN Start work | LOCAL-008-screen-RUN |\nПрименить?' }, 'объяснил', true],
    ['stale plan wrote something', { written: true, screenCreated: true }, 'правил нет нигде'],
    ['INITIAL permission validator removed', { initialValidators: [] }, 'правил нет нигде'],
    ['INITIAL permission validator replaced', { initialValidators: [{ ...PERMISSION, className: 'other.Validator' }] }, 'правил нет нигде'],
    ['stale plan added a rule to INITIAL', { initialValidators: [PERMISSION, { kind: 'COMMENT_REQUIRED' }] }, 'правил нет нигде'],
    ['screen created before the stale apply', { screenCreated: true }, 'не создан'],
  ];
  const misses = [];
  for (const [label, options, fragment, presentation] of cases) {
    const world = staleWorld(options);
    const checks = [...traceChecks(world.trace, world.conversation, { classifyPresentation: true }), ...await run(verifyStale, world)];
    if (!mentions(checks, fragment)) misses.push(`${label}: expected «${fragment}», failing: ${JSON.stringify(failing(checks))}`);
    if (presentation) await advisory(checks, fragment);
    else {
      assert.ok(checks.some((c) => !c.ok && c.class === 'contract' && c.name.includes(fragment)), label);
      assert.equal((await report(checks)).passed, false, label);
    }
  }
  assert.deepEqual(misses, []);
});

test('LOCAL-008 missing-field warning accepts either grammatical order and rejects unrelated or reassuring claims', async () => {
  const base = existingWorld().conversation[1].content;
  const old = 'Обязательного Description нет на экране; пользователь может не суметь заполнить поле.';
  for (const warning of [
    'Description останется обязательным, но отсутствующим на новом экране. При пустом Description переход не пройдёт; заполнить его на этом экране нельзя.',
    'На новом экране нет обязательного Description; его невозможно заполнить на этом экране.',
    'Нельзя заполнить обязательное Description на новом экране: Description на нём отсутствует.',
  ]) assert.equal(mentions(await run(verifyExisting, existingWorld({ ask: base.replace(old, warning) })), 'до подтверждения ассистент объясняет'), false, warning);
  for (const warning of [
    'На новом экране нет Assignee; Assignee невозможно заполнить на этом экране.',
    'На экране LOCAL-008-other нет Description; Description невозможно заполнить на экране LOCAL-008-other.',
    'Description отсутствует на новом экране, но его можно заполнить на этом экране.',
    'Description необязательно, поэтому на новом экране его нет; заполнить его нельзя.',
    'Description уже заполнено; на новом экране его нет, заполнить его нельзя.',
    'Description отсутствует на новом экране; заполнить его на этом экране нельзя. Но Description доступен для заполнения.',
    'Обратите внимание: некоторые поля могут быть недоступны.',
    'Description отсутствует на новом экране, но его можно будет заполнить на этом экране.',
    'На новом экране Description отсутствует. Ничего другого на этом экране заполнить нельзя.',
  ]) await advisory(await run(verifyExisting, existingWorld({ ask: base.replace(old, warning) })), 'до подтверждения ассистент объясняет');
});

test('LOCAL-008 stale setup preserves native INITIAL permission validation and validates only requested transitions as rule-free', async () => {
  const initial = { kind: 'OTHER', type: 'class', className: 'com.atlassian.jira.workflow.validator.PermissionValidator' };
  async function prepare({ localValidators = [], localScreen = null, initialValidators = [initial] } = {}) {
    const transitions = [
      { actionId: 1, name: 'Create', kind: 'INITIAL', sourceStepIds: [], targetStepIds: [1] },
      { actionId: 11, name: 'Start work', kind: 'LOCAL', sourceStepIds: [1], targetStepIds: [2], screenId: localScreen },
      { actionId: 21, name: 'Finish', kind: 'LOCAL', sourceStepIds: [2], targetStepIds: [1] },
    ];
    return setupStale({ runId: 'SETUP', log() {}, apply: async (name, args) => {
      assert.equal(name, 'configure_workflow'); assert.equal(args.create.name, 'LOCAL-008-stale-SETUP');
      assert.deepEqual(args.create.statuses.map((s) => s.name), ['To Do', 'In Progress']);
      return { status: 'CREATED' };
    }, adminPage: async () => ({ status: 200, text: sXml }), tool: async (name, args) => ({
      list_statuses: { statuses: [{ id: args.query === 'To Do' ? '1' : args.query === 'Done' ? '5' : '3', name: args.query, category: 'TO_DO' }] },
      get_workflow: { revision: 'r1', active: false, hasDraft: false, consumers: { projectCount: 0, schemeCount: 0 }, steps: sSteps, transitions },
      get_workflow_validators: { transitions: [{ actionId: 1, validators: initialValidators }, { actionId: 11, validators: localValidators }, { actionId: 21, validators: [] }], scriptRunner: { supported: true } },
      get_workflow_conditions: { transitions: transitions.map((t) => ({ actionId: t.actionId, root: null })) },
    })[name] });
  }
  const prepared = await prepare();
  assert.deepEqual(prepared.state.validators[1], [initial]); assert.deepEqual(prepared.actions, { 'Start work': 11, Finish: 21 });
  await assert.rejects(prepare({ localValidators: [{ kind: 'REQUIRED_FIELDS', fieldIds: ['description'] }] }), /Исходное состояние/);
  await assert.rejects(prepare({ localScreen: '999' }), /Исходное состояние/);
  await assert.rejects(prepare({ initialValidators: [] }), /Исходное состояние/);
  await assert.rejects(prepare({ initialValidators: [{ ...initial, className: 'different.Validator' }] }), /Исходное состояние/);
});

test('configure readback separates a missing Jira read from missing or empty final wording', async () => {
  for (const [verify, makeWorld] of [[verifyCreate, createWorld], [verifyExisting, existingWorld]]) {
    for (const answer of ['', 'Готово.']) {
      const world = makeWorld();
      world.conversation.at(-1).content = answer;
      await advisory(await run(verify, world), 'показал фактическую конфигурацию');
    }
    const world = makeWorld();
    world.trace = world.trace.filter((e) => !(e.turn === 2 && ['get_workflow', 'get_workflow_validators', 'get_workflow_conditions'].includes(e.tool)));
    const checks = await run(verify, world);
    const read = checks.find((c) => c.name.endsWith('перечитал workflow после apply'));
    assert.equal(read.class, 'contract');
    assert.equal(read.ok, false);
    assert.ok(checks.find((c) => c.name.endsWith('показал фактическую конфигурацию')).ok);
    assert.equal((await report(checks)).passed, false);
  }
});

test('configure missing preview fallbacks classify receipt as contract and presentation as advisory', async () => {
  for (const [verify, world] of [
    [verifyCreate, createWorld({ previews: 0 })],
    [verifyExisting, existingWorld({ previews: 0 })],
    [verifyStale, staleWorld({ noNewPlan: true })],
  ]) {
    const checks = await run(verify, world);
    assert.ok(checks.every((c) => ['contract', 'advisory'].includes(c.class)));
    assert.ok(checks.some((c) => !c.ok && c.class === 'contract' && /preview.*получен/u.test(c.name)));
    assert.ok(checks.some((c) => !c.ok && c.class === 'advisory' && /preview показан таблицей/u.test(c.name)));
    assert.equal((await report(checks)).passed, false);
  }
});

test('configure scenario remains FAIL when a presentation observation accompanies a protocol failure', async () => {
  const world = createWorld({ ask: 'Создать workflow с правилами и экраном?' });
  world.conversation[2].content = 'Нет, не применяй.';
  const checks = [...traceChecks(world.trace, world.conversation, { classifyPresentation: true }), ...await run(verifyCreate, world)];
  assert.ok(checks.some((c) => !c.ok && c.class === 'contract' && c.name.startsWith('apply подтверждён')));
  assert.ok(checks.some((c) => !c.ok && c.class === 'advisory'));
  const { passed, verdict, lines } = await report(checks);
  assert.equal(passed, false);
  assert.equal(verdict.passed, false);
  assert.ok(verdict.observations.length > 0);
  assert.ok(lines.includes('ИТОГ: FAIL'));
});

test('stale re-preview missing object name is an advisory observation, not a stale-protocol failure', async () => {
  const world = staleWorld();
  world.conversation[3].content = world.conversation[3].content.replaceAll(S.screenName, 'новый экран');
  const checks = [...traceChecks(world.trace, world.conversation, { classifyPresentation: true }), ...await run(verifyStale, world)];
  const { verdict } = await advisory(checks, 'AC-13 новый план');
  assert.ok(verdict.observations.some((c) => c.detail.includes(`нет слов: ${S.screenName}`)));
});
