import { applies } from '../../checks.mjs';
import { GET, PUBLISH, calls, changeChecks, check, configureApplies, configurePreviews, createsDraft, existingOf, foreignToolCalls, functionIds, operationResults,
  previewQuestion, revisionsFresh, snapshotOf } from '../../validators-common.mjs';

export default async function verify({ prepared, trace, conversation, tool, adminPage }) {
  const { workflow, actionIds } = prepared;
  const previews = configurePreviews(trace).filter(({ result }) => result.status === 'WOULD_CHANGE');
  const runs = configureApplies(trace);
  const checks = [
    check('сначала get_workflow_validators', trace.findIndex((e) => e.tool === GET) !== -1
      && trace.findIndex((e) => e.tool === GET) < trace.findIndex((e) => e.arguments?.dryRun === true && e.tool !== GET), `${calls(trace, GET).length} чтений`),
    revisionsFresh(trace),
    check('ровно один preview и один apply, по плану этого preview', previews.length === 1 && runs.length === 1 && runs[0].preview === previews[0].entry,
      `preview: ${previews.length}, apply: ${runs.length}`),
    check('preview с existing на версии ACTIVE пишет на месте неактивного workflow (targetVersion ACTIVE, draft не создаётся)',
      previews[0] && existingOf(previews[0].entry.arguments).version === 'ACTIVE' && previews[0].result.targetVersion === 'ACTIVE' && !createsDraft(previews[0].result),
      `версия ${existingOf(previews[0]?.entry.arguments).version}, targetVersion ${previews[0]?.result.targetVersion}`),
    check('результат операции UPDATED_INACTIVE', operationResults(trace).some((r) => r.status === 'UPDATED_INACTIVE'),
      JSON.stringify(operationResults(trace).map((r) => r.status))),
    check('нет publish, edit_workflow, configure_workflow с create и других правок workflow', foreignToolCalls(trace).length === 0 && applies(trace, PUBLISH).length === 0,
      `лишних вызовов: ${foreignToolCalls(trace).length}`),
  ];
  if (previews[0]) checks.push(previewQuestion(conversation, previews[0].entry, { transition: 'Finish', fieldWords: ['description'] }));

  const after = await tool(GET, { workflow, version: 'ACTIVE' });
  checks.push(...changeChecks({ before: prepared.snapshot, after: snapshotOf(after), actionId: actionIds.Finish, requested: ['description'], label: 'итог' }));
  const state = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  checks.push(check('workflow остался неактивным и без draft', !state.active && !state.hasDraft, `active=${state.active}, hasDraft=${state.hasDraft}`));

  // FIELD_FUNCTION_ID of the untouched Reopen validator stays and one new one appears (needs Jira's XML export page).
  const ids = await functionIds(adminPage, workflow);
  if (!prepared.functionIds || !ids) {
    checks.push(check('XML-экспорт: FIELD_FUNCTION_ID прежних валидаторов на месте, один новый', true, 'НЕ ПРОВЕРЕНО: страница XML-экспорта недоступна в этой Jira'));
  } else {
    const ok = prepared.functionIds.every((id) => ids.includes(id)) && ids.length === prepared.functionIds.length + 1;
    checks.push(check('XML-экспорт: FIELD_FUNCTION_ID прежних валидаторов на месте, один новый', ok, `было ${prepared.functionIds.length}, стало ${ids.length}`));
  }
  return checks;
}
