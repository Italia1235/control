import { applies } from '../../checks.mjs';
import { CFG, GET, check, configurePreviews, existingOf, foreignToolCalls, requestedFieldIds, snapshotOf } from '../../validators-common.mjs';

const REQUIRED = 'set_field_required';

export default async function verify({ prepared, trace, conversation, tool }) {
  const { workflow } = prepared;
  // The second request is the first user message that names a transition.
  const users = conversation.filter((c) => c.role === 'user');
  const secondTurn = users.findIndex((c) => /перех/iu.test(c.content)) + 1;
  const before = secondTurn > 0 ? trace.filter((e) => e.turn < secondTurn) : trace;
  const after = secondTurn > 0 ? trace.filter((e) => e.turn >= secondTurn) : [];
  const validatorsTools = (list) => list.filter((e) => e.tool === GET || e.tool === CFG);
  const checks = [
    check('диалог дошёл до второй просьбы (про переход)', secondTurn > 1, `ход второй просьбы: ${secondTurn || 'не найден'}`),
    check('«обязательно при создании»: есть вызов set_field_required', before.some((e) => e.tool === REQUIRED), `вызовов: ${before.filter((e) => e.tool === REQUIRED).length}`),
    check('«обязательно при создании»: инструменты валидаторов не вызваны', validatorsTools(before).length === 0,
      validatorsTools(before).map((e) => `#${e.seq} ${e.tool}`).join(', ') || 'нет'),
    check('«обязательно на переходе»: get_workflow_validators и preview configure_workflow (existing) с правилом required_fields на Finish для description',
      after.some((e) => e.tool === GET && e.arguments?.workflow === workflow)
      && configurePreviews(after).some(({ entry }) => existingOf(entry.arguments).workflow === workflow
        && requestedFieldIds(entry.arguments, prepared.actionIds.Finish).includes('description')),
      `чтений: ${after.filter((e) => e.tool === GET).length}, preview: ${configurePreviews(after).length}`),
    check('«обязательно на переходе»: set_field_required не вызван', !after.some((e) => e.tool === REQUIRED), `вызовов: ${after.filter((e) => e.tool === REQUIRED).length}`),
    check('нет edit_workflow, configure_workflow с create и других правок workflow', foreignToolCalls(trace).length === 0, `лишних вызовов: ${foreignToolCalls(trace).length}`),
    check('ничего не применено (оба плана отклонены)', applies(trace).length === 0, `apply: ${applies(trace).length}`),
  ];
  const view = await tool(GET, { workflow, version: 'ACTIVE' });
  checks.push(check('workflow не изменился', view.revision === prepared.revision && JSON.stringify(snapshotOf(view)) === JSON.stringify(prepared.snapshot),
    `revision ${prepared.revision} → ${view.revision}`));
  return checks;
}
