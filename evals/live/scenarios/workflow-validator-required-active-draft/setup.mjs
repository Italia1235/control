import { activate, createDelivery } from '../../validators-common.mjs';

// A test project that uses the new workflow: the workflow is active and has no draft. Setup writes validators
// (Reopen) before the workflow is assigned; after that nothing touches it.
export default async function setup(context) {
  const delivery = await createDelivery(context, {});
  const { project } = await activate(context, delivery.workflow);
  return { ...delivery, project,
    known: { workflow: delivery.workflow, project, transitions: Object.keys(delivery.actionIds), field: 'Description' } };
}
