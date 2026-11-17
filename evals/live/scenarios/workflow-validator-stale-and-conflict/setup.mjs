import { createDelivery } from '../../validators-common.mjs';

// Inactive test workflow; two spare existing statuses are kept for the two outside changes made by hooks.mjs.
export default async function setup(context) {
  const delivery = await createDelivery(context, { spare: 2 });
  const state = await context.tool('get_workflow', { workflow: delivery.workflow, version: 'ACTIVE' });
  if (state.active || state.hasDraft) throw new Error('workflow должен быть неактивным, без draft');
  return { ...delivery, known: { workflow: delivery.workflow, transitions: Object.keys(delivery.actionIds), field: 'Description' } };
}
