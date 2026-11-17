import { applies } from '../../checks.mjs';
import { exportXml, xmlEquivalent } from '../../comment-validators-common.mjs';
import { GET_C, check, configurePreviews, foreignCalls, foreignLabel, isDeepStrictEqual, readsOf, requestedRoleIds, revisionsFresh, snapshotOf } from '../../conditions-common.mjs';

// The rules.md routing line, migrated to configure_workflow: "who can execute the
// transition" goes to get_workflow_conditions and ONE configure_workflow preview of an existing workflow with
// `transitionRules[].addConditions` (templates group / project_role), not to edit_workflow, not to validators (also not
// through configure_workflow), not to a `create`; the plans are declined, so the workflow must be exactly as prepared.
// (A root joined by OR cannot be prepared without bypassing Control, so its refusal is covered by unit tests only.)
export default async function verify({ prepared, trace, conversation, tool, adminPage }) {
  const { workflow, actionIds, group, role } = prepared;
  const finishId = actionIds.Finish;
  // The second request is the first user message that names a project role.
  const users = conversation.filter((c) => c.role === 'user');
  const secondTurn = users.findIndex((c) => /роль/iu.test(c.content)) + 1;
  const first = secondTurn > 0 ? trace.filter((e) => e.turn < secondTurn) : trace;
  const second = secondTurn > 0 ? trace.filter((e) => e.turn >= secondTurn) : [];
  const onFinish = (list) => configurePreviews(list).filter(({ args, requests }) => args.existing?.workflow === workflow && requests.some((r) => r.actionId === finishId));
  const isGroup = (r) => r.actionId === finishId && r.template === 'group' && String(r.parameters?.group).toLowerCase() === group.toLowerCase();
  const isRole = (r) => r.actionId === finishId && r.template === 'project_role' && requestedRoleIds(r.parameters?.roles ?? [], [role]).includes(role.id);
  const groupPreviews = onFinish(first).filter(({ requests }) => requests.some(isGroup));
  const rolePreviews = onFinish(second).filter(({ requests }) => requests.some(isRole));
  const checks = [
    check('диалог дошёл до второй просьбы (про роль проекта)', secondTurn > 1, `ход второй просьбы: ${secondTurn || 'не найден'}`),
    check('«только члены группы»: get_workflow_conditions и preview configure_workflow (existing + transitionRules[].addConditions), template group, Finish, jira-administrators',
      readsOf(first, workflow).length > 0 && groupPreviews.length > 0 && groupPreviews.every(({ requests }) => !requests.some((r) => r.template === 'project_role')),
      `чтений: ${readsOf(first, workflow).length}, preview group: ${groupPreviews.length}, всего preview в первой просьбе: ${configurePreviews(first).length}`),
    check('«только пользователи с ролью»: preview configure_workflow, addConditions template project_role, Finish, роль Administrators',
      rolePreviews.length > 0, `preview project_role: ${rolePreviews.length}, всего preview во второй просьбе: ${configurePreviews(second).length}`),
    check('оба preview приняты (WOULD_CHANGE с планом), а не отвергнуты как неверный вызов',
      groupPreviews.some(({ result }) => result.status === 'WOULD_CHANGE' && result.planId) && rolePreviews.some(({ result }) => result.status === 'WOULD_CHANGE' && result.planId),
      `group: ${groupPreviews.map(({ result }) => result.status ?? result.errorCode).join(',') || '—'}; role: ${rolePreviews.map(({ result }) => result.status ?? result.errorCode).join(',') || '—'}`),
    revisionsFresh(trace),
    check('ни edit_workflow, ни configure_workflow с create/screen/validators/newScreens, ни других правок workflow ни в одной просьбе', foreignCalls(trace).length === 0,
      foreignCalls(trace).map(foreignLabel).join(', ') || 'нет'),
    check('ничего не применено (оба плана отклонены)', applies(trace).length === 0, `apply: ${applies(trace).length}`),
  ];
  const view = await tool(GET_C, { workflow, version: 'ACTIVE' });
  checks.push(check('workflow не изменился: conditions, revision и дескриптор как после подготовки',
    view.revision === prepared.revision && isDeepStrictEqual(snapshotOf(view), prepared.snapshot) && xmlEquivalent(prepared.xml, await exportXml(adminPage, workflow)),
    `revision ${prepared.revision} → ${view.revision}`));
  return checks;
}
