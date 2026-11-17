import { applies } from '../../checks.mjs';
import { exportXml } from '../../comment-validators-common.mjs';
import { GET_C, additionChecks, check, configureApplies, configurePreviews, conveysAllConditions, conveysEmptyGroup, exactGroup, firstGoodRead, foreignCalls,
  foreignLabel, isDeepStrictEqual, outcomeOf, previewQuestion, readBeforePreview, replyOf, revisionsFresh, snapshotOf, transitionOf, treeSummary,
  warningsOf } from '../../conditions-common.mjs';

// Migrated to configure_workflow: the dialogue is judged by the
// trace and by the exported descriptor, never by what the assistant says it did. A group request is one
// `transitionRules[{actionId, addConditions:{operator:'AND', rules:[{template:'group', parameters:{group}}]}}]` of an
// `existing` target; the previous warning about a second group in the AND root is gone from the product (the preview shows
// the resulting conditions of the transition instead, which is checked below).
export default async function verify({ prepared, trace, conversation, tool, adminPage }) {
  const { workflow, actionIds, emptyGroup, seedGroup, targetGroup, seedRole } = prepared;
  const finishId = actionIds.Finish, startId = actionIds['Start work'];
  const ci = (a, b) => String(a).toLowerCase() === String(b).toLowerCase();
  const previews = configurePreviews(trace);
  const groupRequest = (p, actionId, group) => p.requests.find((r) => r.actionId === actionId && r.template === 'group' && ci(r.parameters?.group, group));
  const finds = (actionId, group, status) => previews.filter((p) => p.args.existing?.workflow === workflow && groupRequest(p, actionId, group)
    && p.result.status === status);
  const find = (actionId, group, status) => finds(actionId, group, status)[0];
  // The UNCHANGED check needs the other-case variant: prefer an UNCHANGED preview whose argument differs from the canonical name in case.
  const unchanged = finds(startId, targetGroup, 'UNCHANGED');
  const p1 = find(finishId, emptyGroup, 'WOULD_CHANGE'), p2 = find(startId, targetGroup, 'WOULD_CHANGE');
  const p3 = unchanged.find((p) => groupRequest(p, startId, targetGroup).parameters.group !== targetGroup) ?? unchanged[0];
  const p3Group = p3 ? groupRequest(p3, startId, targetGroup).parameters.group : undefined;
  const runs = configureApplies(trace);
  const checks = [];
  const inPlace = (p) => p.args.existing?.version === 'ACTIVE' && p.result.targetVersion === 'ACTIVE'
    && !(Array.isArray(p.result.objects) ? p.result.objects : []).some((o) => o?.kind === 'DRAFT');

  // The conditions are read successfully before the first preview and show the prepared tree.
  const { view: read } = firstGoodRead(trace, workflow);
  const expectedFinish = { kind: 'COMPOUND', operator: 'AND', children: [{ kind: 'GROUP', group: seedGroup },
    { kind: 'PROJECT_ROLE', roleIds: [seedRole.id], roles: [{ id: seedRole.id, name: seedRole.name }], inverted: false }] };
  checks.push(readBeforePreview(trace, workflow));
  checks.push(check('AC-1: первое успешное чтение — Finish: COMPOUND/AND [GROUP, PROJECT_ROLE], Start work: root null',
    Boolean(read)
      && isDeepStrictEqual(snapshotOf(read).find((t) => t.actionId === finishId)?.root, expectedFinish)
      && snapshotOf(read).find((t) => t.actionId === startId)?.root === null,
    read ? JSON.stringify(snapshotOf(read)).slice(0, 500) : 'успешного чтения нет'));
  checks.push(revisionsFresh(trace));

  // The preview shows the conditions of Finish after the write (the previous ones first, the new group
  // last), the new group has no members, and the preview says the condition narrows access for every project.
  const w1 = p1 ? warningsOf(p1.result) : [];
  const finishAfter = { ...expectedFinish, children: [...expectedFinish.children, { kind: 'GROUP', group: emptyGroup }] };
  checks.push(check('AC-2: preview Finish с новой группой — WOULD_CHANGE с planId, запись на месте неактивного workflow (existing.version ACTIVE, targetVersion ACTIVE, draft не создаётся)',
    Boolean(p1) && Boolean(p1.result.planId) && inPlace(p1),
    p1 ? `${p1.args.existing?.version}/${p1.result.targetVersion}; objects: ${JSON.stringify(p1.result.objects ?? [])}` : 'preview нет'));
  checks.push(check('AC-11(а): preview показывает итоговые conditions Finish — прежние группа и роль неизменно первыми, новая группа последней в корне AND',
    Boolean(p1) && isDeepStrictEqual(treeSummary(transitionOf(p1.result, finishId)?.conditions), finishAfter),
    p1 ? JSON.stringify(treeSummary(transitionOf(p1.result, finishId)?.conditions)).slice(0, 500) : 'preview нет'));
  checks.push(check('AC-11(б): предупреждение, что у новой группы наблюдается 0 пользователей и пока группа пуста, условию не удовлетворит никто',
    w1.some((w) => w.includes(emptyGroup) && /observed 0 users/iu.test(w) && /nobody satisfies/iu.test(w)), JSON.stringify(w1)));
  checks.push(check('AC-11: preview говорит, что условия сужают доступ для всех проектов workflow (consumers)',
    w1.some((w) => /narrow access for every project/iu.test(w)), JSON.stringify(w1)));
  if (p1) {
    checks.push(previewQuestion(conversation, p1.entry, { transition: 'Finish', words: [emptyGroup, seedGroup], label: 'Finish' }));
    const reply = replyOf(conversation, p1.entry.turn);
    checks.push(check('AC-11: ассистент донёс, что у перехода несколько условий в корне AND и нужно удовлетворить все',
      conveysAllConditions(reply), `«${reply.slice(0, 200)}»`));
    // The group's own name contains «empty»: cut it out before looking for the statement that the group has no members.
    const prose = reply.replace(new RegExp(emptyGroup.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'), 'giu'), ' ');
    checks.push(check('AC-11(б): ассистент донёс пользователю, что группа пуста (без учёта имени группы)',
      conveysEmptyGroup(prose),
      `«${prose.slice(0, 200)}»`));
  }

  // Start work has no conditions and the group has members: the observation is not an "empty" one.
  const w2 = p2 ? warningsOf(p2.result) : [];
  const startAfter = { kind: 'COMPOUND', operator: 'AND', children: [{ kind: 'GROUP', group: targetGroup }] };
  checks.push(check('AC-4: preview Start work (условий нет) с группой с участниками — WOULD_CHANGE, в итоге новый корень AND с одной группой, наблюдение «observed N users» (N>0) и без предупреждения о пустой группе',
    Boolean(p2) && Boolean(p2.result.planId) && inPlace(p2)
      && isDeepStrictEqual(treeSummary(transitionOf(p2.result, startId)?.conditions), startAfter)
      && w2.some((w) => w.includes(targetGroup) && /observed [1-9]\d* users/iu.test(w)) && !w2.some((w) => /nobody satisfies|remains empty|observed 0 users/iu.test(w)),
    p2 ? JSON.stringify(w2) : 'preview нет'));
  if (p2) checks.push(previewQuestion(conversation, p2.entry, { transition: 'Start work', words: [targetGroup], label: 'Start work' }));

  // Applies: exactly the two shown plans, in this order, written in place.
  const outcomes = runs.map((run) => outcomeOf(trace, run).result);
  checks.push(check('ровно два apply configure_workflow: по плану preview Finish, затем по плану preview Start work; больше ничего не применено',
    runs.length === 2 && applies(trace).length === 2 && Boolean(p1) && Boolean(p2) && runs[0].preview === p1.entry && runs[1].preview === p2.entry,
    `apply: ${applies(trace).map((a) => a.toolName).join(', ') || 'нет'}`));
  checks.push(check('оба результата UPDATED_INACTIVE', outcomes.length === 2 && outcomes.every((o) => o?.status === 'UPDATED_INACTIVE'),
    JSON.stringify(outcomes.map((o) => o?.status ?? o?.errorCode ?? null))));
  checks.push(check('нет publish, edit_workflow, copy/rename_workflow, update_status и configure_workflow с create/screen/validators/newScreens (только conditions существующего workflow)', foreignCalls(trace).length === 0,
    foreignCalls(trace).map(foreignLabel).join(', ') || 'нет'));

  // The same group in another case is UNCHANGED, no plan, after both writes, and nothing applied afterwards.
  checks.push(check('AC-5: повтор Start work с группой в другом регистре — preview UNCHANGED без planId, после обоих apply',
    Boolean(p3) && !p3.result.planId && runs.length === 2 && p3.entry.seq > runs[1].start.seq
      && !applies(trace).some((a) => a.start.seq > p3.entry.seq) && p3Group !== targetGroup,
    p3 ? `group=${p3Group}${p3Group === targetGroup ? ' — вариант регистра не воспроизведён (аргумент совпадает с «' + targetGroup + '»)' : ''}`
      : `UNCHANGED-preview нет; preview: ${previews.map(({ result }) => result.status).join(', ')}`));

  // The result in Jira: the exported descriptor and the conditions as read back.
  const xml = await exportXml(adminPage, workflow);
  checks.push(...additionChecks(prepared.xml, xml, [
    { actionId: finishId, label: 'AC-2/AC-7 Finish', isExpected: (c) => exactGroup(c, emptyGroup) },
    { actionId: startId, label: 'AC-4/AC-7 Start work', isExpected: (c) => exactGroup(c, targetGroup) },
  ]));
  const view = await tool(GET_C, { workflow, version: 'ACTIVE' });
  const state = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  const before = prepared.snapshot;
  const expected = before.map((t) => t.actionId === finishId
    ? { ...t, root: { ...t.root, children: [...t.root.children, { kind: 'GROUP', group: emptyGroup }] } }
    : t.actionId === startId ? { ...t, root: { kind: 'COMPOUND', operator: 'AND', children: [{ kind: 'GROUP', group: targetGroup }] } } : t);
  checks.push(check('AC-1/AC-2/AC-4: get_workflow_conditions читает итог — новые GROUP в конце Finish и в новом корне AND Start work, Reopen прежний',
    isDeepStrictEqual(snapshotOf(view), expected), JSON.stringify(snapshotOf(view)).slice(0, 600)));
  checks.push(check('AC-1: revision get_workflow_conditions равна revision get_workflow', view.revision === state.revision, `${view.revision} / ${state.revision}`));
  checks.push(check('workflow остался неактивным, без draft и потребителей',
    state.active === false && state.hasDraft === false && state.consumers.projectCount === 0 && state.consumers.schemeCount === 0,
    JSON.stringify({ active: state.active, hasDraft: state.hasDraft })));
  return checks;
}
