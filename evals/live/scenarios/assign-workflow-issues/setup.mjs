import { projectManagement } from '../../project-templates.mjs';
// Project with three issues of the standard type: one per status category (to do, in progress, done), so assigning a
// workflow without those statuses needs explicit status mappings.
export default async function setup({ names, apply, rest, log }) {
  const key = names['KEY:proj'];
  await apply('create_project', projectManagement({ name: names['NAME:project'], key, lead: 'admin' }));
  log(`проект ${key} создан`);

  const issueType = (await rest('GET', `/rest/api/2/project/${key}`)).issueTypes.find((type) => !type.subtask);
  const issues = [];
  for (const category of ['new', 'indeterminate', 'done']) {
    const { key: issueKey } = await rest('POST', '/rest/api/2/issue', {
      fields: { project: { key }, issuetype: { id: issueType.id }, summary: `Setup issue (${category})` },
    });
    if (category !== 'new') {
      const { transitions } = await rest('GET', `/rest/api/2/issue/${issueKey}/transitions`);
      const transition = transitions.find((candidate) => candidate.to.statusCategory.key === category);
      if (!transition) throw new Error(`${issueKey}: нет перехода в категорию ${category}`);
      await rest('POST', `/rest/api/2/issue/${issueKey}/transitions`, { transition: { id: transition.id } });
    }
    const { fields } = await rest('GET', `/rest/api/2/issue/${issueKey}?fields=status`);
    issues.push({ key: issueKey, status: fields.status.name, category: fields.status.statusCategory.key });
  }
  log(`задачи: ${issues.map((issue) => `${issue.key} ${issue.status}`).join(', ')}`);
  return { project: key, issueType: issueType.name, issues };
}
