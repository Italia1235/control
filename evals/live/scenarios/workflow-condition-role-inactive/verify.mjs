import { applies } from '../../checks.mjs';
import { CANONICAL_UUID, exportXml } from '../../comment-validators-common.mjs';
import { GET_C, additionChecks, check, configureApplies, configurePreviews, conveysAllConditions, conveysInversion, exactRole, firstGoodRead, foreignCalls,
  foreignLabel, functionIdsOf, isDeepStrictEqual, outcomeOf, previewQuestion, readBeforePreview, replyOf, requestedRoleIds, revisionsFresh, snapshotOf,
  transitionOf, treeSummary, warningsOf } from '../../conditions-common.mjs';

// Migrated to configure_workflow: two roles with invert:true are written in
// exactly the captured format; the same roles in another order are UNCHANGED; the same roles with another invert are a new
// plan that is not applied. A role request is `transitionRules[{actionId, addConditions:{operator:'AND', rules:[{template:
// 'project_role', parameters:{roles, invert}}]}}]` of an `existing` target. The product no longer puts an «invert»
// warning into the preview; the inversion is checked in the preview's own view of the transition (`transitions[].conditions`)
// and in the assistant's reply.
export default async function verify({ prepared, trace, conversation, tool, adminPage }) {
  const { workflow, actionIds, seedGroup, seedRole, targetRoles } = prepared;
  const finishId = actionIds.Finish;
  const known = [...targetRoles, seedRole];
  const targetIds = targetRoles.map((r) => r.id);
  const previews = configurePreviews(trace).map((p) => {
    const role = p.requests.find((r) => r.actionId === finishId && r.template === 'project_role');
    return { ...p, role, ids: role ? requestedRoleIds(role.parameters?.roles ?? [], known) : [] };
  });
  const sameRoles = (p) => p.args.existing?.workflow === workflow && Boolean(p.role) && isDeepStrictEqual([...p.ids].sort(), [...targetIds].sort());
  const inverted = (p) => p.role?.parameters?.invert === true;
  const inPlace = (p) => p.args.existing?.version === 'ACTIVE' && p.result.targetVersion === 'ACTIVE'
    && !(Array.isArray(p.result.objects) ? p.result.objects : []).some((o) => o?.kind === 'DRAFT');
  const q1 = previews.find((p) => sameRoles(p) && inverted(p) && p.result.status === 'WOULD_CHANGE');
  // The UNCHANGED check needs the reordered variant: prefer an UNCHANGED preview whose role sequence differs from the first (q1) one.
  const unchanged = previews.filter((p) => sameRoles(p) && inverted(p) && p.result.status === 'UNCHANGED');
  const q2 = unchanged.find((p) => q1 && !isDeepStrictEqual(p.ids, q1.ids)) ?? unchanged[0];
  const q3 = previews.find((p) => sameRoles(p) && !inverted(p) && p.result.status === 'WOULD_CHANGE');
  const runs = configureApplies(trace);
  const checks = [];

  // The conditions are read before anything is previewed.
  const { view: read } = firstGoodRead(trace, workflow);
  const expectedFinish = { kind: 'COMPOUND', operator: 'AND', children: [{ kind: 'GROUP', group: seedGroup },
    { kind: 'PROJECT_ROLE', roleIds: [seedRole.id], roles: [{ id: seedRole.id, name: seedRole.name }], inverted: false }] };
  checks.push(readBeforePreview(trace, workflow));
  checks.push(check('AC-1: первое успешное чтение — Finish: COMPOUND/AND [GROUP, PROJECT_ROLE другой роли]',
    Boolean(read) && isDeepStrictEqual(snapshotOf(read).find((t) => t.actionId === finishId)?.root, expectedFinish),
    read ? JSON.stringify(snapshotOf(read)).slice(0, 500) : 'успешного чтения нет'));
  checks.push(revisionsFresh(trace));

  // invert:true plan. The preview shows the resulting conditions of Finish (the previous ones first, the new
  // inverted role condition last) and says that the conditions narrow access for every project.
  const w1 = q1 ? warningsOf(q1.result) : [];
  const roleNode = (ids, invert) => ({ kind: 'PROJECT_ROLE', roleIds: ids, roles: ids.map((id) => ({ id, name: known.find((r) => r.id === id)?.name ?? null })), inverted: invert });
  const finishWith = (added) => ({ ...expectedFinish, children: [...expectedFinish.children, added] });
  checks.push(check('AC-3: preview двух ролей с invert:true — WOULD_CHANGE с planId, запись на месте неактивного workflow (existing.version ACTIVE, targetVersion ACTIVE, draft не создаётся)',
    Boolean(q1) && Boolean(q1.result.planId) && inPlace(q1),
    q1 ? `${q1.args.existing?.version}/${q1.result.targetVersion}` : `preview: ${previews.map(({ result, role }) => `${result.status}/${role?.template}`).join(', ') || 'нет'}`));
  checks.push(check('AC-11(в): preview показывает итоговые conditions Finish — прежние группа и роль неизменно первыми, последним PROJECT_ROLE с обеими ролями (id и имена) и inverted=true',
    Boolean(q1) && isDeepStrictEqual(treeSummary(transitionOf(q1.result, finishId)?.conditions), finishWith(roleNode(q1.ids, true))),
    q1 ? JSON.stringify(treeSummary(transitionOf(q1.result, finishId)?.conditions)).slice(0, 500) : 'preview нет'));
  checks.push(check('AC-11: preview говорит, что условия сужают доступ для всех проектов workflow (consumers)',
    w1.some((w) => /narrow access for every project/iu.test(w)), JSON.stringify(w1)));
  if (q1) {
    checks.push(previewQuestion(conversation, q1.entry, { transition: 'Finish', words: ['Administrators', 'Service Desk Team'], label: 'invert:true' }));
    const reply = replyOf(conversation, q1.entry.turn);
    checks.push(check('AC-11(в): ассистент донёс инверсию — члены ролей не смогут выполнить переход / не член ни одной из ролей / все, кроме членов ролей',
      conveysInversion(reply), `«${reply.slice(0, 200)}»`));
    checks.push(check('AC-11: ассистент донёс, что у перехода несколько условий в корне AND и нужно удовлетворить все',
      conveysAllConditions(reply), `«${reply.slice(0, 200)}»`));
  }

  // The one apply: exactly the shown invert:true plan.
  const outcome = runs.length ? outcomeOf(trace, runs[0]).result : null;
  checks.push(check('ровно один apply — по плану preview с invert:true; больше ничего не применено (UNCHANGED и план без инверсии не применялись)',
    runs.length === 1 && applies(trace).length === 1 && Boolean(q1) && runs[0].preview === q1.entry,
    `apply: ${applies(trace).map((a) => a.toolName).join(', ') || 'нет'}`));
  checks.push(check('результат операции UPDATED_INACTIVE', outcome?.status === 'UPDATED_INACTIVE', JSON.stringify(outcome?.status ?? outcome?.errorCode ?? null)));
  checks.push(check('нет publish, edit_workflow, copy/rename_workflow, update_status и configure_workflow с create/screen/validators/newScreens (только conditions существующего workflow)', foreignCalls(trace).length === 0,
    foreignCalls(trace).map(foreignLabel).join(', ') || 'нет'));

  // The same roles in another order are UNCHANGED.
  checks.push(check('AC-5: те же роли в другом порядке, тот же invert, после apply — preview UNCHANGED без planId',
    Boolean(q2) && !q2.result.planId && runs.length === 1 && q2.entry.seq > runs[0].start.seq && Boolean(q1) && !isDeepStrictEqual(q2.ids, q1.ids),
    q2 ? `roles=${JSON.stringify(q2.role.parameters.roles)}${q1 && isDeepStrictEqual(q2.ids, q1.ids) ? ' — вариант порядка не воспроизведён (последовательность ids как в первом запросе)' : ''}` : 'UNCHANGED-preview нет'));
  checks.push(check('AC-5/AC-11(в): те же роли без invert — WOULD_CHANGE с планом, preview показывает после прежних условий и уже применённого запрета последним PROJECT_ROLE с inverted=false, не применён',
    Boolean(q3) && Boolean(q3.result.planId) && Boolean(q1)
      && isDeepStrictEqual(treeSummary(transitionOf(q3.result, finishId)?.conditions),
        { ...expectedFinish, children: [...expectedFinish.children, roleNode(q1.ids, true), roleNode(q3.ids, false)] })
      && !applies(trace).some((a) => a.preview === q3.entry) && q3.entry.seq > (runs[0]?.start.seq ?? Infinity),
    q3 ? JSON.stringify(treeSummary(transitionOf(q3.result, finishId)?.conditions)).slice(0, 500) : 'preview без invert нет'));

  // The result in Jira: the exact captured format, appended last, everything else untouched.
  const xml = await exportXml(adminPage, workflow);
  const ids = q1 ? q1.ids : [];
  checks.push(...additionChecks(prepared.xml, xml, [{
    actionId: finishId, label: 'AC-3/AC-7 Finish',
    isExpected: (c) => exactRole(c, { ids, invert: true }, functionIdsOf(prepared.xml), CANONICAL_UUID),
  }]));
  const view = await tool(GET_C, { workflow, version: 'ACTIVE' });
  const state = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  const added = { kind: 'PROJECT_ROLE', roleIds: ids, roles: ids.map((id) => ({ id, name: known.find((r) => r.id === id)?.name ?? null })), inverted: true };
  const expected = prepared.snapshot.map((t) => t.actionId === finishId
    ? { ...t, root: { ...t.root, children: [...t.root.children, added] } } : t);
  checks.push(check('AC-1/AC-3: get_workflow_conditions читает итог — PROJECT_ROLE с обеими ролями (id и имена) и inverted=true последним в корне Finish',
    Boolean(q1) && isDeepStrictEqual(snapshotOf(view), expected), JSON.stringify(snapshotOf(view)).slice(0, 600)));
  checks.push(check('AC-1: revision get_workflow_conditions равна revision get_workflow', view.revision === state.revision, `${view.revision} / ${state.revision}`));
  checks.push(check('workflow остался неактивным, без draft и потребителей',
    state.active === false && state.hasDraft === false && state.consumers.projectCount === 0 && state.consumers.schemeCount === 0,
    JSON.stringify({ active: state.active, hasDraft: state.hasDraft })));
  return checks;
}
