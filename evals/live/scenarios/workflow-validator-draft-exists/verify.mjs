import { applies, parseResult } from '../../checks.mjs';
import { GET, PUBLISH, changeChecks, check, configureApplies, configurePreviews, createsDraft, existingOf, foreignToolCalls, operationResults, replyOf,
  previewQuestion, revisionsFresh, snapshotOf } from '../../validators-common.mjs';

export default async function verify({ prepared, trace, conversation, tool, probe }) {
  const { workflow, actionIds } = prepared;
  const all = configurePreviews(trace);
  const onActive = all.filter(({ entry }) => existingOf(entry.arguments).version === 'ACTIVE');
  const good = all.filter(({ result }) => result.status === 'WOULD_CHANGE');
  const runs = configureApplies(trace);
  const draftReads = trace.filter((e) => e.tool === GET && e.arguments?.version === 'DRAFT');
  const checks = [
    revisionsFresh(trace),
    check('правка ACTIVE (если пробовали) отвечает DRAFT_EXISTS и не повторяется', onActive.length <= 1 && onActive.every(({ result }) => result.errorCode === 'DRAFT_EXISTS'),
      JSON.stringify(onActive.map(({ result }) => result.errorCode ?? result.status))),
    check('прочитана версия DRAFT', draftReads.length > 0 && parseResult(draftReads.at(-1).result)?.revision === prepared.draftRevision,
      `чтений DRAFT: ${draftReads.length}`),
    check('успешный preview — existing на версии DRAFT с revision draft; targetVersion DRAFT, существующий draft (новый не создаётся)',
      good.length === 1 && existingOf(good[0].entry.arguments).version === 'DRAFT'
      && existingOf(good[0].entry.arguments).expectedRevision === prepared.draftRevision
      && good[0].result.targetVersion === 'DRAFT' && !createsDraft(good[0].result),
      `версия ${existingOf(good[0]?.entry.arguments).version}, revision ${existingOf(good[0]?.entry.arguments).expectedRevision}, targetVersion ${good[0]?.result.targetVersion}, создаёт draft: ${good[0] ? createsDraft(good[0].result) : '—'}`),
    check('один apply по этому плану, результат DRAFT_SAVED', runs.length === 1 && runs[0].preview === good[0]?.entry
      && operationResults(trace).some((r) => r.status === 'DRAFT_SAVED'), `apply: ${runs.length}`),
    check('draft не публикуется', applies(trace, PUBLISH).length === 0 && trace.every((e) => e.tool !== PUBLISH || e.arguments?.dryRun === true), `публикаций: ${applies(trace, PUBLISH).length}`),
    check('нет edit_workflow, configure_workflow с create и других правок', foreignToolCalls(trace).length === 0, `лишних вызовов: ${foreignToolCalls(trace).length}`),
  ];
  if (good[0]) {
    checks.push(previewQuestion(conversation, good[0].entry, { transition: 'Finish', fieldWords: ['labels'] }));
    const text = replyOf(conversation, good[0].entry.turn);
    checks.push(check('ассистент объясняет, что правка идёт в существующий общий draft и чужие изменения остаются',
      /(?:существующ|общ|чуж|други[хм])[^.]{0,120}(?:черновик|draft)|(?:черновик|draft)[^.]{0,120}(?:чуж|други[хм]|общ|сохран)/iu.test(text), `«${text.slice(0, 260)}»`));
  }
  const draft = await tool(GET, { workflow, version: 'DRAFT' });
  checks.push(...changeChecks({ before: prepared.draftSnapshot, after: snapshotOf(draft), actionId: actionIds.Finish, requested: ['labels'], label: 'draft' }));
  const active = await tool(GET, { workflow, version: 'ACTIVE' });
  checks.push(check('активная версия не изменилась (валидаторы; revision общая с draft и меняется)',
    JSON.stringify(snapshotOf(active)) === JSON.stringify(prepared.snapshot), `revision ${prepared.revision} → ${active.revision} (учитывает draft)`));
  // Read-only preview of the publication shows what the draft would change: the foreign status must still be in it.
  const publish = await probe(PUBLISH, { dryRun: true, workflow });
  checks.push(check(`чужая правка draft (статус ${prepared.foreignStatus}) сохранилась`,
    publish.data?.status === 'WOULD_PUBLISH' && publish.data.changes.addedStatuses.includes(prepared.foreignStatus),
    `статус ${publish.data?.status ?? publish.text.slice(0, 120)}; добавлены: ${JSON.stringify(publish.data?.changes?.addedStatuses)}`));
  return checks;
}
