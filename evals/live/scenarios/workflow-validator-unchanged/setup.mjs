import { createDelivery } from '../../validators-common.mjs';

// Inactive test workflow whose Finish transition already requires [assignee, description] unconditionally.
export default async function setup(context) {
  const delivery = await createDelivery(context, { seed: { Finish: ['assignee', 'description'] } });
  const finish = delivery.snapshot.find((t) => t.actionId === delivery.actionIds.Finish).validators;
  if (!finish.some((v) => v.kind === 'REQUIRED_FIELDS' && !v.conditional && JSON.stringify(v.fieldIds) === '["assignee","description"]')) {
    throw new Error(`на Finish ожидался безусловный валидатор [assignee, description]: ${JSON.stringify(finish)}`);
  }
  const state = await context.tool('get_workflow', { workflow: delivery.workflow, version: 'ACTIVE' });
  if (state.active || state.hasDraft) throw new Error('workflow должен быть неактивным, без draft');
  return { ...delivery, known: { workflow: delivery.workflow, transitions: Object.keys(delivery.actionIds), fields: ['Assignee', 'Description'] } };
}
