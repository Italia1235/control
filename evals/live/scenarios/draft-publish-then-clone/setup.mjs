import { projectManagement } from '../../project-templates.mjs';
// Two projects whose own schemes both use one new workflow, and an unpublished draft of that workflow with one added
// status. The draft must not block the clone and must not be copied or published by it (see verify.mjs). Jira has no
// REST for drafts, so the draft is made through its admin actions; the publication preview is only a check that it exists.
const DRAFT_STATUS = 'In Progress';

export default async function setup({ names, tool, apply, rest, adminPage, log }) {
  const [projectA, projectB, workflow] = [names['KEY:a'], names['KEY:b'], names['NAME:wf']];
  await apply('create_project', projectManagement({ name: names['NAME:projectA'], key: projectA, lead: 'admin' }));
  await apply('create_project', projectManagement({ name: names['NAME:projectB'], key: projectB, lead: 'admin' }));
  log(`проекты ${projectA}, ${projectB} созданы`);

  const [fixed] = await tool('get_resolutions', { query: 'Fixed' });
  if (!fixed) throw new Error('нет Resolution Fixed');
  await apply('configure_workflow', {
    create: {
      name: workflow, initialStatus: 'Open',
      statuses: [{ name: 'Open', category: 'TO_DO' }, { name: 'In Work', category: 'IN_PROGRESS' }, { name: 'Closed', category: 'DONE' }],
      transitions: [
        { key: 'start', name: 'Start', from: 'Open', to: 'In Work' },
        { key: 'close', name: 'Close', from: 'In Work', to: 'Closed', resolution: { action: 'SET', value: fixed.id } },
        { key: 'reopen', name: 'Reopen', fromAny: true, to: 'Open', resolution: { action: 'CLEAR' } },
      ],
    },
  });
  for (const project of [projectA, projectB]) await apply('assign_workflow', { project, workflow, allIssueTypes: true });
  log(`workflow «${workflow}» назначен обоим проектам`);

  const status = (await rest('GET', '/rest/api/2/status')).find((candidate) => candidate.name === DRAFT_STATUS);
  if (!status) throw new Error(`нет статуса ${DRAFT_STATUS}`);
  await adminPage(`/secure/admin/workflows/EditWorkflowDispatcher.jspa?wfName=${encodeURIComponent(workflow)}`);
  await adminPage('/secure/admin/workflows/AddWorkflowStep.jspa', {
    workflowName: workflow, workflowMode: 'draft', stepName: DRAFT_STATUS, stepStatus: status.id,
  });
  const draft = await tool('publish_workflow_draft', { dryRun: true, workflow });
  if (draft.status !== 'WOULD_PUBLISH' || !draft.changes.addedStatuses.includes(DRAFT_STATUS)) {
    throw new Error(`черновик не создан: ${JSON.stringify(draft).slice(0, 500)}`);
  }
  log(`черновик «${workflow}»: добавлен статус ${DRAFT_STATUS}, затронуты ${draft.affectedProjectNames.join(', ')}`);
  return { projects: [projectA, projectB], workflow, draftChanges: draft.changes, affectedProjects: draft.affectedProjectNames };
}
