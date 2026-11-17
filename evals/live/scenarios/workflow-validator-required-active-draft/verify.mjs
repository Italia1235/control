import { applies, parseResult } from '../../checks.mjs';
import { CFG, GET, PUBLISH, calls, changeChecks, check, configureApplies, configurePreviews, createsDraft, existingOf, foreignToolCalls, operationResults,
  replyOf, previewQuestion, revisionsFresh, snapshotOf } from '../../validators-common.mjs';

const PUBLISH_REQUEST = /опублик|publish/iu;

export default async function verify({ prepared, lifecycle, trace, conversation, tool }) {
  const { workflow, actionIds } = prepared;
  const previews = configurePreviews(trace).filter(({ result }) => result.status === 'WOULD_CHANGE');
  const runs = configureApplies(trace);
  const requestTurn = conversation.filter((c) => c.role === 'user').findIndex((c) => PUBLISH_REQUEST.test(c.content)) + 1;
  const publishCalls = calls(trace, PUBLISH);
  const publishRuns = applies(trace, PUBLISH);
  const saveTurn = lifecycle.afterSave?.turn;
  const checks = [
    revisionsFresh(trace),
    check('один preview configure с existing на версии ACTIVE: будет создан новый draft (targetVersion DRAFT, объект DRAFT в плане)',
      previews.length === 1 && existingOf(previews[0].entry.arguments).version === 'ACTIVE'
      && previews[0].result.targetVersion === 'DRAFT' && createsDraft(previews[0].result),
      `версия ${existingOf(previews[0]?.entry.arguments).version}, targetVersion ${previews[0]?.result.targetVersion}, draft создаётся: ${previews[0] ? createsDraft(previews[0].result) : '—'}`),
    check('один apply configure по плану этого preview, результат DRAFT_SAVED', runs.length === 1 && runs[0].preview === previews[0]?.entry
      && operationResults(trace).some((r) => r.toolName === CFG && r.status === 'DRAFT_SAVED'),
      JSON.stringify(operationResults(trace).map((r) => `${r.toolName}:${r.status}`))),
    check('hook наблюдал состояние после DRAFT_SAVED до публикации', Boolean(lifecycle.afterSave), JSON.stringify(Object.keys(lifecycle))),
    check('до публикации активная версия не изменилась (валидаторы как после подготовки; revision общая с draft и меняется)',
      JSON.stringify(lifecycle.afterSave?.active) === JSON.stringify(prepared.snapshot),
      `revision ${prepared.revision} → ${lifecycle.afterSave?.activeRevision} (учитывает draft)`),
    ...(lifecycle.afterSave ? changeChecks({ before: prepared.snapshot, after: lifecycle.afterSave.draft, actionId: actionIds.Finish,
      requested: ['description'], label: 'draft' }) : []),
    check('ассистент сам не публиковал: любой publish_workflow_draft — только после просьбы пользователя',
      requestTurn > 0 && publishCalls.length > 0 && publishCalls.every((e) => e.turn >= requestTurn),
      `просьба в ходе ${requestTurn || 'не было'}; вызовы publish в ходах: ${publishCalls.map((e) => e.turn).join(', ') || '—'}`),
    check('в ответе о draft сказано, что активная версия не меняется и публикация отдельная',
      saveTurn !== undefined && /отдельн[^.]{0,100}публикац|публикац[^.]{0,100}отдельн|не\s+опубликова|пока\s+не\s+опубликова|только\s+в\s+черновик|в\s+черновике/iu.test(replyOf(conversation, saveTurn)),
      `«${replyOf(conversation, saveTurn ?? 1).slice(0, 240)}»`),
    check('публикация: свой preview WOULD_PUBLISH и свой apply по нему, один раз',
      publishRuns.length === 1 && publishRuns[0].preview && parseResult(publishRuns[0].preview.result)?.status === 'WOULD_PUBLISH'
      && operationResults(trace).some((r) => r.toolName === PUBLISH && r.status === 'PUBLISHED'), `apply публикации: ${publishRuns.length}`),
    check('нет edit_workflow, configure_workflow с create и других правок', foreignToolCalls(trace).length === 0, `лишних вызовов: ${foreignToolCalls(trace).length}`),
  ];
  if (previews[0]) checks.push(previewQuestion(conversation, previews[0].entry, { transition: 'Finish', fieldWords: ['description'] }));
  const after = await tool(GET, { workflow, version: 'ACTIVE' });
  checks.push(...changeChecks({ before: prepared.snapshot, after: snapshotOf(after), actionId: actionIds.Finish, requested: ['description'], label: 'итог ACTIVE' }));
  return checks;
}
