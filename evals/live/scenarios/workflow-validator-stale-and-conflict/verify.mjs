import { GET, changeChecks, check, configureApplies, configurePreviews, existingOf, foreignToolCalls, operationResults, previewQuestion, snapshotOf } from '../../validators-common.mjs';

export default async function verify({ prepared, lifecycle, trace, conversation, tool }) {
  const { workflow, actionIds } = prepared;
  const { first, second } = lifecycle;
  const all = configurePreviews(trace);
  const good = all.filter(({ result }) => result.status === 'WOULD_CHANGE');
  const results = operationResults(trace);
  const stale = results.findIndex((r) => r.errorCode === 'PREVIEW_STALE');
  const staleStart = configureApplies(trace)[0];
  const readsBetween = (from, to) => trace.filter((e) => e.tool === GET && e.seq > from.seq && (!to || e.seq < to.seq));
  const checks = [
    check('hook сработал дважды (после чтения и после preview), revision менялась', Boolean(first && second && first.revisionBefore !== first.revisionAfter && second.revisionBefore !== second.revisionAfter),
      JSON.stringify({ first, second })),
  ];

  // 1. The revision read before the first outside change is old: a fresh read, or VERSION_CONFLICT then a fresh read.
  const firstPreview = all[0];
  const conflicted = firstPreview?.result.errorCode === 'VERSION_CONFLICT';
  const rereadAfterConflict = conflicted && readsBetween(firstPreview.entry, all[1]?.entry).length > 0 && existingOf(all[1]?.entry.arguments).expectedRevision === first?.revisionAfter;
  checks.push(check('после первого изменения workflow: preview либо уже со свежей existing.expectedRevision, либо VERSION_CONFLICT → перечитал → новый preview',
    Boolean(firstPreview) && (existingOf(firstPreview.entry.arguments).expectedRevision === first?.revisionAfter || rereadAfterConflict),
    `первый preview: revision ${existingOf(firstPreview?.entry.arguments).expectedRevision}, результат ${firstPreview?.result.errorCode ?? firstPreview?.result.status}; после hook ${first?.revisionAfter}`));

  // 2. The plan shown before the second change is stale: the apply fails and the assistant reads and previews again.
  checks.push(check('первый apply завершился PREVIEW_STALE', stale >= 0 && Boolean(staleStart), JSON.stringify(results.map((r) => r.errorCode ?? r.status))));
  const next = staleStart && good.find(({ entry }) => entry.seq > staleStart.start.seq);
  checks.push(check('после PREVIEW_STALE: get_workflow_validators → новый preview с новой revision и другим planId',
    Boolean(next) && readsBetween(staleStart.start, next.entry).length > 0 && existingOf(next.entry.arguments).expectedRevision === second?.revisionAfter
    && next.result.planId !== good[0]?.result.planId,
    `revision нового preview ${existingOf(next?.entry.arguments).expectedRevision}, после hook ${second?.revisionAfter}`));
  const applies = configureApplies(trace);
  checks.push(check('второй apply — по новому плану (не по старому planId), результат UPDATED_INACTIVE',
    applies.length === 2 && applies[1].planId === next?.result.planId && applies[1].planId !== applies[0].planId && results.at(-1)?.status === 'UPDATED_INACTIVE',
    `apply: ${applies.length}; результаты: ${JSON.stringify(results.map((r) => r.errorCode ?? r.status))}`));
  if (next) checks.push(previewQuestion(conversation, next.entry, { transition: 'Finish', fieldWords: ['description'] }));
  checks.push(check('нет edit_workflow, configure_workflow с create и других правок', foreignToolCalls(trace).length === 0, `лишних вызовов: ${foreignToolCalls(trace).length}`));

  // Final state: Description added, and the outside changes of the hooks are still in the workflow.
  const after = await tool(GET, { workflow, version: 'ACTIVE' });
  checks.push(...changeChecks({ before: prepared.snapshot, after: snapshotOf(after), actionId: actionIds.Finish, requested: ['description'], label: 'итог' }));
  const graph = JSON.stringify(await tool('get_workflow', { workflow, version: 'ACTIVE' }));
  const lost = [first?.status, second?.status].filter((name) => name && !graph.includes(name));
  checks.push(check('внешние изменения hook (добавленные статусы) на месте', lost.length === 0 && Boolean(first && second), lost.length ? `пропали: ${lost.join(', ')}` : `${first?.status}, ${second?.status}`));
  return checks;
}
