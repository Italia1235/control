import assert from 'node:assert/strict';
import test from 'node:test';
import { ROLE_CANNED, ROLE_CLASS, GROUP_CLASS, conveysEmptyGroup, conveysInversion, foreignCalls } from '../live/conditions-common.mjs';
import verifyRoleInactive from '../live/scenarios/workflow-condition-role-inactive/verify.mjs';

// workflow-condition-* live verifiers: the replies that must (not) count as «everyone except the role members» and
// «the group has no members». Synthetic replies only; no Jira, no model.
const check = (fn, passing, failing) => {
  for (const text of passing) assert.equal(fn(text), true, `must PASS: ${text}`);
  for (const text of failing) assert.equal(fn(text), false, `must FAIL: ${text}`);
};

test('migrated condition-only requests still forbid workflow creation, screens and validators', () => {
  const request = (rule = {}, extra = {}) => ({ dryRun: true, existing: { workflow: 'LOCAL-006-test', version: 'ACTIVE', expectedRevision: 'r1' },
    transitionRules: [{ actionId: 12, addConditions: { operator: 'AND', rules: [{ template: 'group', parameters: { group: 'test' } }] }, ...rule }], ...extra });
  const calls = (arguments_) => [{ tool: 'configure_workflow', arguments: arguments_ }];
  assert.deepEqual(foreignCalls(calls(request())), []);
  assert.deepEqual(foreignCalls(calls(request({ screen: 'KEEP' }))), []);
  for (const args of [
    request({}, { create: { name: 'unasked' } }),
    request({}, { existing: { ...request().existing, changes: [{ op: 'UPDATE_METADATA', description: 'unasked' }] } }),
    request({}, { newScreens: [{ key: 'extra', name: 'unasked', fieldIds: ['description'] }] }),
    request({ screen: { action: 'SET', screenId: '101' } }),
    request({ addValidators: [{ template: 'required_fields', parameters: { fieldIds: ['description'] } }] }),
  ]) assert.equal(foreignCalls(calls(args)).length, 1);
});

test('inversion: the sense «members of the roles cannot, the rest can» passes, anything else fails', () => {
  check(conveysInversion, [
    '| Итоговый доступ | Члены любой из двух запрещённых ролей не смогут выполнить переход. Остальные — **только если выполняют оба текущих условия** |',
    '| Новое условие | Пользователь **не входит ни в одну** из ролей проекта |',
    'Доступ получат все, кроме членов ролей Administrators и Service Desk Team.',
    'Все пользователи, кроме участников этих ролей, смогут выполнить переход.',
    'Кроме ролей Administrators и Service Desk Team, переход доступен всем.',
    'Everyone except members of the roles can execute it.',
    'Except the members of those roles.',
    'Запрещено членам любой из этих ролей.',
    'Нельзя выполнять переход участникам этих ролей.',
    // The real reply of LIVE-RUN round 2 (run 2026-11-07T09-08-39-826Z), retold.
    '| Новое условие | В конец корневого **AND** добавится запрет для членов хотя бы одной роли проекта: **Administrators** или **Service Desk Team**. |\n| Итоговый доступ | Новое условие **сужает доступ**: переход смогут выполнить только пользователи, удовлетворяющие обоим прежним условиям и не входящие ни в одну из двух запрещённых ролей. «Всем остальным» — только при выполнении прежних условий. |',
    'Пользователи, не входящие ни в одну из этих ролей, смогут выполнить переход.',
    'Запрет для членов ролей Administrators и Service Desk Team; все остальные смогут выполнить переход.',
    'Запрет для членов ролей X и Y: остальные пользователи допускаются.',
    'Members of the roles are blocked.',
    // Natural wordings without «не смогут» (R15), tied to the roles.
    'Переход станет недоступен членам ролей Administrators и Service Desk Team.',
    'Для членов ролей Administrators и Service Desk Team переход будет закрыт.',
    'Члены ролей Administrators и Service Desk Team не получат доступ.',
    'Для участников этих ролей переход исключён.',
    // R18: membership expressed without «члены» and a future denial of access.
    'Пользователи с ролью X или Y не смогут выполнить переход.',
    'Тем, у кого роль X или Y, переход недоступен.',
    'Переход не будет доступен членам ролей Administrators и Service Desk Team.',
    // The real reply of LIVE-RUN round 1 (run 2026-11-07T08-50-31-319Z), as the assistant wrote it.
    '| Новое условие | Пользователь **не входит ни в одну** из ролей проекта: `Administrators` (10002), `Service Desk Team` (10101) |\n| Итоговый доступ | Члены любой из двух запрещённых ролей не смогут выполнить переход. Остальные — **только если выполняют оба текущих условия** |\n\n**«Всем остальным можно» не отменяет существующие ограничения:** новое условие только сужает доступ.',
    'Запрет для членов ролей X и Y. Остальные пользователи смогут выполнить переход, если выполняют прежние условия.',
  ], [
    // R12: the parameter name alone, the echo of the request alone.
    '| Параметры | invert: true |\nДобавить запрет для членов ролей Administrators и Service Desk Team?',
    '| Параметры | project_role, invert: true |\nДобавить запрет для членов ролей Administrators и Service Desk Team?',
    'Условие работает как invert=true.',
    'Условие инвертировано: инверсия членства.',
    'Предупреждение: invert is true.',
    // R13: «не смогут» about anyone but the roles, the opposite sense.
    'Только члены ролей смогут выполнить переход. Пользователи вне ролей не смогут.',
    'Пользователи вне ролей не смогут.',
    'Пользователи без группы jira-administrators не смогут выполнить переход.',
    'Пользователи не смогут отменить изменение автоматически.',
    'Вы не сможете отменить изменение автоматически.',
    'Члены группы jira-administrators не смогут выполнить переход.',
    'Members of the group cannot undo the change.',
    // R14: «other conditions», not other people, can do it.
    'Добавлю запрет для членов ролей Administrators и Service Desk Team. Остальные условия не меняются, и переход смогут выполнить только те, кто им удовлетворяет.',
    'Запрет для членов ролей X и Y. Все остальные условия остаются, переход смогут выполнить те, кто им удовлетворяет.',
    'Запрет для членов ролей X и Y. Остальные пользователи не смогут выполнить переход.',
    'Кроме того, условие добавляется в конец корня AND.',
    'Остальные условия, кроме добавляемого, остаются на месте.',
    'Все остальные условия, кроме добавляемого, сохраняются.',
    'Всего будет три условия, кроме того, ничего не меняется.',
    'Except that, nothing else changes.',
    'Переход смогут выполнить только члены ролей Administrators и Service Desk Team.',
    'Только члены ролей Administrators и Service Desk Team, остальные не смогут.',
    'Только члены этих ролей — остальные не смогут выполнить переход.',
    'Добавить запрет на переход Finish?',
    'Остальные — только если выполняют оба текущих условия.',
    // The bare echo of the request and other near misses (R9).
    'Добавить запрет для членов ролей Administrators и Service Desk Team?',
    'Запрет на переход для ролей Administrators и Service Desk Team.',
    'Только пользователи с обоими прежними условиями смогут выполнить переход, остальные не смогут.',
    'Члены ролей Administrators и Service Desk Team смогут, остальные не смогут.',
    'Переход смогут выполнить члены роли Administrators, остальные условия не меняются.',
    'Переход смогут выполнить члены группы jira-administrators; остальное не меняется.',
    // R9: the denied action is not access to the transition.
    'Члены ролей Administrators не смогут отменить изменение автоматически.',
    // R16: the opposite polarity and a role mentioned only in another clause.
    'Переход закрыт для всех, кроме членов ролей Administrators и Service Desk Team.',
    'Переход недоступен всем, кроме членов ролей Administrators и Service Desk Team.',
    'Переход закрыт для всех, кроме авторов задач, текущее условие Service Desk Customers сохранится.',
    // R17: other rules/settings are not other people allowed to execute the transition.
    'Запрет для членов ролей X и Y. Все остальные правила могут остаться.',
    'Запрет для членов ролей X и Y. Все остальные настройки могут остаться.',
    'Запрет для членов ролей X и Y. Все остальные условия могут остаться.',
    // Independent review: exception order and denial of nonmembers reverse the intended access.
    'Никто, кроме членов ролей Administrators и Service Desk Team, не сможет выполнить переход.',
    'Для всех, кроме членов ролей Administrators и Service Desk Team, переход закрыт.',
    'Никому, кроме членов ролей X и Y, переход недоступен.',
    'Кроме членов ролей X и Y, всем переход закрыт.',
    'No one except members of the roles can execute the transition.',
    'Пользователи, не входящие ни в одну из ролей Administrators и Service Desk Team, не смогут выполнить переход.',
    'Переход не закрыт для членов ролей X.',
    'Исключением будут члены ролей Administrators и Service Desk Team.',
    'Участники не из роли Service Desk Customers не смогут выполнить переход.',
    'Члены группы Контролёры не смогут выполнить переход.',
    'Доступ получат все, кроме Service Desk Customers.',
    // FIX 2: membership nouns after negation and denials before nonmembership are still reversed access.
    'Пользователи, не являющиеся членами ролей Administrators и Service Desk Team, не смогут выполнить переход.',
    'Пользователи, которые не являются членами ролей X и Y, не смогут выполнить переход.',
    'Кто не является участником ролей X или Y, не сможет выполнить переход.',
    'Переход закрыт для пользователей, не входящих ни в одну из ролей Administrators и Service Desk Team.',
    'Переход недоступен пользователям, не входящим ни в одну из ролей X и Y.',
  ]);
});

test('empty group: a statement that the group has no members passes, other words fail', () => {
  check(conveysEmptyGroup, [
    'У группы нет членов — никто не сможет выполнить переход.',
    'В группе нет пользователей.',
    'Участников в группе нет.',
    'Участников в группе: 0',
    '0 участников',
    '| Участников | 0 |',
    'В группе никого нет.',
    'Группа пуста.',
    'Пустая группа',
    'Пустое множество участников у этой группы',
    'The group is empty.',
    'No members in the group.',
    'нет ни одного участника',
    'Пользователей: 0',
    '| Пользователей в группе | 0 |',
  ], [
    'Переход будет допустим только для членов группы.',
    'Пусть так: запустит проверку.',
    'Группа не содержит ничего особенного.',
    'Условие пропустит 10 участников.',
    '| Затронуто пользователей | 0 |',
    'Добавляем в конец группу.',
    '| Затронуто пользователей: 0 |',
    'Затронутых пользователей: 0',
    // R11: «нет» at the end of a table cell and the next cell starting with a noun is not «нет участников».
    'Условие: нет\nпользователей в списке',
  ]);
});

// The role condition of workflow-condition-role-inactive is written by the assistant's configure_workflow, which
// generates name-based (version 3) function ids; the exact-form check accepts any canonical UUID but still rejects other ids.
test('role-inactive: the added role condition keeps its canonical name-based function id', async () => {
  const arg = (name, value) => `<arg name="${name}">${value}</arg>`;
  const group = `<condition type="class">${arg('class.name', GROUP_CLASS)}${arg('group', 'g')}</condition>`;
  const role = (id) => `<condition type="class">${arg('FIELD_FUNCTION_ID', id)}${arg('FIELD_INVERT_CONDITION', 'on')}${arg('FIELD_PROJECT_ROLE_IDS', '10|||20')}`
    + `${arg('canned-script', ROLE_CANNED)}${arg('class.name', ROLE_CLASS)}</condition>`;
  const workflow = (conditions) => `<?xml version="1.0" encoding="UTF-8"?><workflow><initial-actions><action id="1" name="Create"><results><unconditional-result old-status="Finished" status="Open" step="1"/></results></action></initial-actions>`
    + `<steps><step id="1" name="To Do"><actions><action id="12" name="Finish"><restrict-to><conditions type="AND">${conditions}</conditions></restrict-to>`
    + `<results><unconditional-result old-status="Finished" status="Done" step="2"/></results></action></actions></step></steps></workflow>`;
  const V3 = '3d208127-430e-3229-bb08-ed7173c66a58', targets = [{ id: 10, name: 'Administrators' }, { id: 20, name: 'Service Desk Team' }];
  const entry = { seq: 1, turn: 1, tool: 'configure_workflow', result: { status: 'WOULD_CHANGE', planId: 'p1', targetVersion: 'ACTIVE' },
    arguments: { dryRun: true, existing: { workflow: 'W', version: 'ACTIVE', expectedRevision: 'r1' },
      transitionRules: [{ actionId: 12, addConditions: { operator: 'AND', rules: [{ template: 'project_role', parameters: { roles: ['Administrators', 'Service Desk Team'], invert: true } }] } }] } };
  const run = (id) => verifyRoleInactive({ prepared: { workflow: 'W', actionIds: { Finish: 12 }, seedGroup: 'g', seedRole: { id: 30, name: 'Seed' }, targetRoles: targets, snapshot: [],
    xml: workflow(group) }, trace: [entry], conversation: [], tool: async () => ({ revision: 'r1', active: false, hasDraft: false, consumers: { projectCount: 0, schemeCount: 0 }, transitions: [] }),
    adminPage: async () => ({ status: 200, text: workflow(group + role(id)) }) });
  const added = (checks) => checks.find((c) => c.name.includes('добавленное условие — ровно снятый образец'));
  assert.equal(added(await run(V3)).ok, true);
  assert.equal(added(await run('11111111-2222-4333-8444-555555555555')).ok, true);
  for (const bad of ['not-a-uuid', '3d208127-430e-3229-bb08-ed7173c66a5']) assert.equal(added(await run(bad)).ok, false, bad);
});
