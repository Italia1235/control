import { activate, addStep, createDelivery, GET, snapshotOf } from '../../validators-common.mjs';

// An active test workflow and somebody else's draft of it: one extra status added in Jira's own admin action.
export default async function setup(context) {
  const delivery = await createDelivery(context, { spare: 1 });
  const { project } = await activate(context, delivery.workflow);
  const [foreign] = delivery.statuses.foreign;
  await addStep(context, delivery.workflow, foreign, 'draft');
  const publish = await context.tool('publish_workflow_draft', { dryRun: true, workflow: delivery.workflow });
  if (publish.status !== 'WOULD_PUBLISH' || !publish.changes.addedStatuses.includes(foreign.name)) {
    throw new Error(`draft с чужой правкой не создан: ${JSON.stringify(publish).slice(0, 500)}`);
  }
  const draft = await context.tool(GET, { workflow: delivery.workflow, version: 'DRAFT' });
  context.log(`draft «${delivery.workflow}»: чужая правка — статус ${foreign.name}; revision ${draft.revision}`);
  return { ...delivery, project, foreignStatus: foreign.name, draftRevision: draft.revision, draftSnapshot: snapshotOf(draft),
    known: { workflow: delivery.workflow, project, transitions: Object.keys(delivery.actionIds), field: 'Labels' } };
}
