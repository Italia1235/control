import { createDelivery } from '../../validators-common.mjs';

// Inactive test workflow whose Finish transition already requires [assignee]; the three requests must not change it.
export default async function setup(context) {
  const delivery = await createDelivery(context, { seed: { Finish: ['assignee'] } });
  return { ...delivery, known: { workflow: delivery.workflow, transitions: Object.keys(delivery.actionIds) } };
}
