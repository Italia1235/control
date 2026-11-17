import { GET, addedRequiredFields, changeChecks, check, configureApplies, configurePreviews, existingOf, foreignToolCalls, functionIds,
  operationResults, previewTransition, replyOf, previewQuestion, requestedFieldIds, revisionsFresh, snapshotOf } from '../../validators-common.mjs';

export default async function verify({ prepared, trace, conversation, tool, adminPage }) {
  const { workflow, actionIds } = prepared;
  const finishId = actionIds.Finish;
  const previews = configurePreviews(trace).filter(({ result }) => result.status === 'WOULD_CHANGE');
  const runs = configureApplies(trace);
  const preview = previews[0]?.result;
  const request = previews[0]?.entry.arguments;
  const requested = requestedFieldIds(request, finishId);
  const finishView = previewTransition(preview, finishId);
  const finishValidators = finishView?.validators ?? [];
  const checks = [
    revisionsFresh(trace),
    check('preview: existing — этот workflow, версия ACTIVE; единственное правило — на переходе Finish',
      existingOf(request).workflow === workflow && existingOf(request).version === 'ACTIVE' && request?.transitionRules?.length === 1
      && request.transitionRules[0].actionId === finishId, JSON.stringify(request)),
    check('запрошены оба поля: assignee и description', JSON.stringify([...requested].sort()) === '["assignee","description"]', JSON.stringify(requested)),
    check('preview добавляет ОДИН новый валидатор только с description (assignee уже покрыт), прежний не расширяется',
      JSON.stringify(addedRequiredFields(preview, finishId)) === '[["description"]]',
      `diff: ${JSON.stringify(preview?.diff)}`),
    check('preview показывает итог Finish: прежний валидатор [assignee], затем новый [description] в конце',
      finishValidators.length === 2 && JSON.stringify(finishValidators[0].fieldIds) === '["assignee"]' && JSON.stringify(finishValidators[1].fieldIds) === '["description"]'
      && finishValidators.every((v) => v.kind === 'required_fields' && v.conditional === false),
      JSON.stringify(finishValidators.map((v) => ({ kind: v.kind, fieldIds: v.fieldIds, conditional: v.conditional })))),
    check('один preview и один apply по его плану, результат UPDATED_INACTIVE', previews.length === 1 && runs.length === 1 && runs[0].preview === previews[0].entry
      && operationResults(trace).some((r) => r.status === 'UPDATED_INACTIVE'), `preview ${previews.length}, apply ${runs.length}`),
    check('нет edit_workflow, configure_workflow с create и других правок', foreignToolCalls(trace).length === 0, `лишних вызовов: ${foreignToolCalls(trace).length}`),
  ];
  if (previews[0]) {
    checks.push(previewQuestion(conversation, previews[0].entry, { transition: 'Finish', fieldWords: ['assignee', 'description'] }));
    const text = replyOf(conversation, previews[0].entry.turn);
    const lines = text.split('\n');
    checks.push(check('в ответе с планом Assignee назван уже обязательным, Description — добавляемым',
      lines.some((l) => /assignee/iu.test(l) && /уже|already/iu.test(l)) && lines.some((l) => /description/iu.test(l) && /добав|будет|added/iu.test(l)),
      `«${text.slice(0, 300)}»`));
  }
  const after = await tool(GET, { workflow, version: 'ACTIVE' });
  const snapshot = snapshotOf(after);
  checks.push(...changeChecks({ before: prepared.snapshot, after: snapshot, actionId: actionIds.Finish, requested: ['assignee', 'description'], label: 'итог' }));
  const finish = snapshot.find((t) => t.actionId === actionIds.Finish).validators;
  checks.push(check('на Finish два валидатора: прежний [assignee] без изменений и новый [description] в конце; прежний не расширен до [assignee, description]',
    finish.length === 2 && JSON.stringify(finish[0].fieldIds) === '["assignee"]' && JSON.stringify(finish[1].fieldIds) === '["description"]'
    && finish.every((v) => v.kind === 'REQUIRED_FIELDS' && !v.conditional), JSON.stringify(finish)));
  const functions = await functionIds(adminPage, workflow);
  const xmlCheck = 'XML-экспорт: FIELD_FUNCTION_ID прежних валидаторов на месте, один новый (новый валидатор, а не дополнение прежнего)';
  checks.push(!prepared.functionIds || !functions
    ? check(xmlCheck, true, 'НЕ ПРОВЕРЕНО: страница XML-экспорта недоступна в этой Jira')
    : check(xmlCheck, prepared.functionIds.every((id) => functions.includes(id)) && functions.length === prepared.functionIds.length + 1,
      `было ${prepared.functionIds.length}, стало ${functions.length}`));
  return checks;
}
