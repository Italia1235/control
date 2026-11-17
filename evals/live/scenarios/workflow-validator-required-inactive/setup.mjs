import { createDelivery, functionIds } from '../../validators-common.mjs';

// Only an inactive test workflow with a known set of transitions; Reopen already carries a required-fields validator.
export default async function setup(context) {
  const delivery = await createDelivery(context, {});
  const state = await context.tool('get_workflow', { workflow: delivery.workflow, version: 'ACTIVE' });
  if (state.active || state.hasDraft || state.consumers.projectCount !== 0) throw new Error('workflow должен быть неактивным, без draft и потребителей');
  const ids = await functionIds(context.adminPage, delivery.workflow);
  return { ...delivery, functionIds: ids,
    known: { workflow: delivery.workflow, transitions: Object.keys(delivery.actionIds), field: 'Description' } };
}
