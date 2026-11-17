import { createDelivery, requireNoField } from '../../validators-common.mjs';

// Inactive test workflow; the field id and the transition number of the request must not exist.
export default async function setup(context) {
  await requireNoField(context.rest, 'customfield_99999');
  const delivery = await createDelivery(context, {});
  if (Object.values(delivery.actionIds).includes(999)) throw new Error('в workflow уже есть переход 999');
  return { ...delivery, known: { workflow: delivery.workflow, transitions: Object.keys(delivery.actionIds) } };
}
