import { createDelivery, functionIds } from '../../validators-common.mjs';

// Inactive test workflow whose Finish transition already has one unconditional required-fields validator: [assignee].
export default async function setup(context) {
  const delivery = await createDelivery(context, { seed: { Finish: ['assignee'] } });
  const finish = delivery.snapshot.find((t) => t.actionId === delivery.actionIds.Finish).validators;
  if (finish.length !== 1 || JSON.stringify(finish[0].fieldIds) !== '["assignee"]' || finish[0].conditional) {
    throw new Error(`на Finish ожидался один безусловный валидатор [assignee]: ${JSON.stringify(finish)}`);
  }
  const state = await context.tool('get_workflow', { workflow: delivery.workflow, version: 'ACTIVE' });
  if (state.active || state.hasDraft) throw new Error('workflow должен быть неактивным, без draft');
  return { ...delivery, functionIds: await functionIds(context.adminPage, delivery.workflow),
    known: { workflow: delivery.workflow, transitions: Object.keys(delivery.actionIds), fields: ['Assignee', 'Description'] } };
}
