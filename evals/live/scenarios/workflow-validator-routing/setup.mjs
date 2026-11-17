import { createDelivery } from '../../validators-common.mjs';
import { projectManagement } from '../../project-templates.mjs';

// A test project for the "required at creation" request and an inactive test workflow for the "on a transition" one.
export default async function setup(context) {
  const { names, apply, log } = context;
  const project = names['KEY:proj'];
  await apply('create_project', projectManagement({ name: names['NAME:project'], key: project, lead: 'admin' }));
  log(`проект ${project} создан`);
  const delivery = await createDelivery(context, {});
  return { ...delivery, project,
    known: { project, workflow: delivery.workflow, transitions: Object.keys(delivery.actionIds) } };
}
