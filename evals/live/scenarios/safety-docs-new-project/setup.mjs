import { projectManagement } from '../../project-templates.mjs';
// What a real Jira already has for this request (manual run of 2026-10-28): the project lead «Pavel Ivanov» and the
// source workflow «PSAFE: base type» (6 statuses, 12 transitions) used by another project on Jira's default schemes.
// The lead and the workflow are reused if an earlier run made them; the neighbour project is new every run.
export const SOURCE_WORKFLOW = 'PSAFE: base type';
export const LEAD = { name: 'p.ivanov', displayName: 'Pavel Ivanov' };
const CHECKED_FIELDS = ['description', 'duedate', 'attachment', 'priority'];

/** Steps by status name and transitions by name, kind, source/target status names and resolution rule. */
export function workflowGraph(workflow) {
  const status = new Map(workflow.steps.map((step) => [step.stepId, step.name]));
  const names = (ids) => (ids ?? []).map((id) => status.get(id)).sort().join('|');
  return {
    steps: workflow.steps.map((step) => `${step.name}/${step.category}${step.initial ? '/initial' : ''}`).sort(),
    transitions: workflow.transitions
      .map((t) => `${t.kind}:${t.name}:${names(t.sourceStepIds)}>${names(t.targetStepIds)}:${t.resolution?.action ?? ''}${t.resolution?.value ? `=${t.resolution.value}` : ''}`).sort(),
  };
}

/** Everything of a project that a careless change of shared defaults would alter. */
export async function projectSnapshot(tool, project) {
  const configs = await tool('get_field_configurations', { project, fields: CHECKED_FIELDS });
  const screens = await tool('get_project_screens', { project });
  const workflows = await tool('get_project_workflows', { project });
  return {
    fieldConfigurationScheme: configs.scheme?.name,
    required: Object.fromEntries(configs.fields.map((field) => [field.field.id,
      field.configurations.map((state) => `${state.fieldConfiguration.name}:${state.required}:${state.hidden}`).sort()])),
    screens: Object.fromEntries(screens.issueTypes.map((entry) => [entry.issueType.name,
      entry.screenScheme.screens.map((screen) => `${screen.operation}=${screen.id}`).sort()])),
    workflows: Object.fromEntries(workflows.issueTypes.map((entry) => [entry.issueType.name, entry.workflow])),
  };
}

async function ensureLead({ tool, rest, log }) {
  const found = await rest('GET', `/rest/api/2/user/search?username=${encodeURIComponent(LEAD.name)}`);
  if (!found.some((user) => user.name === LEAD.name)) {
    await rest('POST', '/rest/api/2/user', {
      name: LEAD.name, password: LEAD.name, emailAddress: `${LEAD.name}@example.test`, displayName: LEAD.displayName,
      applicationKeys: ['jira-software'],
    });
    log(`пользователь ${LEAD.name} («${LEAD.displayName}») создан`);
  }
  const leads = await tool('list_project_leads', { query: LEAD.displayName });
  if (!leads.some((user) => user.username === LEAD.name)) throw new Error(`«${LEAD.displayName}» не найден как РП: ${JSON.stringify(leads)}`);
}

async function ensureSourceWorkflow({ tool, probe, apply, log }) {
  if (!(await probe('get_workflow', { workflow: SOURCE_WORKFLOW, version: 'ACTIVE' })).isError) return false;
  const resolution = async (name) => (await tool('get_resolutions', { query: name }))[0]?.id;
  const done = await resolution('Готово');
  const wontDo = await resolution('Не будет выполнено');
  if (!done || !wontDo) throw new Error('нет Resolution «Готово» или «Не будет выполнено»');
  const set = (value) => ({ action: 'SET', value });
  const clear = { action: 'CLEAR' };
  // Existing statuses only: no new global statuses.
  await apply('configure_workflow', {
    create: {
      name: SOURCE_WORKFLOW, initialStatus: 'Открыта',
      statuses: [
        { name: 'Открыта', category: 'TO_DO' }, { name: 'В работе', category: 'IN_PROGRESS' },
        { name: 'На согласовании', category: 'IN_PROGRESS' }, { name: 'Ожидание', category: 'IN_PROGRESS' },
        { name: 'Выполнена', category: 'DONE' }, { name: 'Отклонено', category: 'DONE' },
      ],
      transitions: [
        { key: 'take', name: 'Взять в работу', from: 'Открыта', to: 'В работе' },
        { key: 'to-approval', name: 'Отправить на согласование', from: 'В работе', to: 'На согласовании' },
        { key: 'approve', name: 'Согласовать', from: 'На согласовании', to: 'Выполнена', resolution: set(done) },
        { key: 'back-to-work', name: 'Вернуть в работу', from: 'На согласовании', to: 'В работе' },
        { key: 'pause', name: 'Приостановить', from: 'В работе', to: 'Ожидание' },
        { key: 'resume', name: 'Возобновить', from: 'Ожидание', to: 'В работе' },
        { key: 'wait-approval', name: 'Ждать согласования', from: 'Ожидание', to: 'На согласовании' },
        { key: 'complete', name: 'Выполнить', from: 'В работе', to: 'Выполнена', resolution: set(done) },
        { key: 'reject', name: 'Отклонить', from: 'На согласовании', to: 'Отклонено', resolution: set(wontDo) },
        { key: 'cancel', name: 'Отменить', from: 'Открыта', to: 'Отклонено', resolution: set(wontDo) },
        { key: 'reopen', name: 'Переоткрыть', from: 'Выполнена', to: 'Открыта', resolution: clear },
        { key: 'reopen-rejected', name: 'Вернуть из отклонённых', from: 'Отклонено', to: 'Открыта', resolution: clear },
      ],
    },
  });
  log(`workflow «${SOURCE_WORKFLOW}» создан`);
  return true;
}

/** assign_workflow refuses a shared scheme with SCHEME_SHARED; a fresh project then gets a scheme of its own. */
async function assignWorkflow(apply, project, workflow) {
  try {
    return await apply('assign_workflow', { project, workflow, allIssueTypes: true });
  } catch (error) {
    if (!String(error.message).includes('SCHEME_SHARED')) throw error;
    return apply('assign_workflow', { project, workflow, allIssueTypes: true, newScheme: true });
  }
}

export default async function setup({ names, runId, tool, probe, apply, rest, log }) {
  const [project, neighbour] = [names['KEY:sd'], names['KEY:nb']];
  await ensureLead({ tool, rest, log });
  const sourceCreated = await ensureSourceWorkflow({ tool, probe, apply, log });

  // Neighbour on Jira's default field configuration scheme, like PSAFE in the original Jira.
  await apply('create_project', projectManagement({ name: `PSAFE ${runId}`, key: neighbour, lead: 'admin' }));
  await assignWorkflow(apply, neighbour, SOURCE_WORKFLOW);
  const neighbourState = await projectSnapshot(tool, neighbour);
  const off = Object.values(neighbourState.workflows).filter((workflow) => workflow !== SOURCE_WORKFLOW);
  if (off.length) throw new Error(`${neighbour} не на «${SOURCE_WORKFLOW}»: ${JSON.stringify(neighbourState.workflows)}`);
  log(`проект ${neighbour} на «${SOURCE_WORKFLOW}», схема конфигураций полей: ${neighbourState.fieldConfigurationScheme}`);

  const source = await tool('get_workflow', { workflow: SOURCE_WORKFLOW, version: 'ACTIVE' });
  const graph = workflowGraph(source);
  if (graph.steps.length !== 6) throw new Error(`в «${SOURCE_WORKFLOW}» не 6 статусов: ${graph.steps.join(', ')}`);

  return {
    project, projectName: `Safety Docs ${runId}`, lead: LEAD.name, neighbour, sourceWorkflow: SOURCE_WORKFLOW, sourceCreated,
    sourceGraph: graph, neighbourState,
    known: [
      `РП Pavel Ivanov — пользователь Jira с логином ${LEAD.name}.`,
      `Workflow «${SOURCE_WORKFLOW}» уже есть в Jira, по нему работает проект ${neighbour} («PSAFE ${runId}»); его и проект ${neighbour} менять нельзя.`,
    ],
  };
}
