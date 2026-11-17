// Setup writes only fresh L012 objects through Control. Verification reads Control, never REST/XML/UI.
import {
  actionIdsOf, check, configureApplies, createScreen, doneResolution, noTrace, oneOfEach, outcomeOf,
  plannedOf, previewTable, readState, revisionFresh, same,
} from './configure-common.mjs';
import { parseResult, PLUGIN_TOOLS } from './checks.mjs';
import { projectManagement } from './project-templates.mjs';

export { check, readState, same };

export async function setupWorkflow({ runId, tool, apply, log }, mode) {
  const prefix = `L012-${runId}`;
  const workflow = `${prefix}-${mode}`;
  const statuses = [
    { name: `${prefix}-Todo`, category: 'TO_DO' },
    { name: `${prefix}-Working`, category: 'IN_PROGRESS' },
    { name: `${prefix}-Done`, category: 'DONE' },
  ];
  const catalog = await tool('list_statuses', { query: prefix, limit: 100 });
  if (catalog.statuses.some((s) => s.name.startsWith(prefix))) throw new Error('Run prefix already exists');
  const resolution = await doneResolution(tool);
  const seedScreen = mode === 'draft' ? await createScreen(apply, `${prefix}-seed-screen`, ['summary', 'assignee']) : null;
  const transitions = [
    { key: 'start', name: 'Start work', from: statuses[0].name, to: statuses[1].name,
      ...(seedScreen ? { screen: seedScreen.id } : {}) },
    { key: 'control', name: 'Control return', from: statuses[1].name, to: statuses[0].name },
    { key: 'finish', name: 'Seed finish', from: statuses[1].name, to: statuses[2].name,
      resolution: { action: 'SET', value: resolution.id } },
  ];
  const created = await apply('configure_workflow', {
    create: { name: workflow, statuses, initialStatus: statuses[0].name, transitions },
  });
  if (created.status !== 'CREATED') throw new Error(`Workflow setup failed: ${JSON.stringify(created)}`);
  let project = null, assignments = null;
  if (mode === 'draft') {
    // Catalog selection is authorized only for the new test project's lead; no global user is changed.
    const leads = await tool('list_project_leads', { query: 'admin' });
    const lead = leads[0];
    if (!lead?.username) throw new Error('No active project lead returned by Control');
    project = `L012${runId.toUpperCase()}`;
    await tool('list_projects', { query: project });
    await apply('create_project', projectManagement({ key: project, name: `${prefix}-project`, lead: lead.username }));
    await tool('get_project_workflows', { project });
    const assigned = await apply('assign_workflow', { project, workflow, allIssueTypes: true, newScheme: true });
    if (assigned.status !== 'ASSIGNED') throw new Error(`Assignment failed: ${JSON.stringify(assigned)}`);
    assignments = await tool('get_project_workflows', { project });
    if (!assignments.issueTypes?.length || assignments.issueTypes.some((row) => row.workflow !== workflow)) {
      throw new Error('Fresh workflow is not assigned to every project issue type');
    }
  }
  const graph = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  const state = await readState(tool, workflow);
  const actions = actionIdsOf(state);
  if (!state.scriptRunner?.installed || !state.scriptRunner?.enabled) throw new Error('ScriptRunner unavailable');
  if (mode === 'draft' ? !state.active || state.hasDraft : !noTrace(state)) throw new Error('Unexpected setup activation/draft/consumer state');
  if (!actions['Start work'] || !actions['Control return']) throw new Error('Missing seed transitions');
  log(`Prepared fresh ${workflow}${project ? ` / ${project}` : ''}; no issues created`);
  return { prefix, workflow, statuses, resolution, seedScreen, project, assignments, graph, state, actions,
    newScreen: `${prefix}-completion-screen`,
    known: { workflow, project, statuses, resolution, originalTransition: 'Start work', renamedTransition: 'Started',
      newTransition: 'Complete', controlTransition: 'Control return', newScreen: `${prefix}-completion-screen` } };
}

// Descriptor facts exposed by all three Control readers; revision/hasDraft are intentionally excluded because a draft changes them.
export const contentOf = (state) => Object.fromEntries(['steps', 'graph', 'screens', 'validators', 'conditions'].map((key) => [key, state[key]]));
export const transitionOf = (state, actionId) => ({
  graph: state.graph.find((t) => t.actionId === actionId), screen: state.screens[actionId],
  validators: state.validators[actionId], conditions: state.conditions[actionId],
});

export function dialogueChecks({ prepared, trace, conversation }, status) {
  const plan = plannedOf(trace)[0];
  const run = configureApplies(trace)[0];
  const outcome = outcomeOf(trace, run);
  // A whitelist rejects every other mutation, including newly added catalog tools. Generic traceChecks verifies catalog membership.
  const reads = new Set([...PLUGIN_TOOLS].filter((name) => /^(get_|list_|plan_)/u.test(name)));
  const foreign = trace.filter((e) => !reads.has(e.tool) && !['configure_workflow', 'start_operation'].includes(e.tool));
  const target = plan?.args.existing;
  const activeRead = trace.find((e) => e.tool === 'get_workflow' && e.arguments?.workflow === prepared.workflow
    && e.arguments?.version === 'ACTIVE' && e.seq < (plan?.entry.seq ?? -1) && parseResult(e.result)?.revision === target?.expectedRevision);
  return {
    plan, run, outcome,
    checks: [
      oneOfEach(trace, 'LOCAL-012'), revisionFresh(trace, prepared.workflow),
      check('Read ACTIVE graph/revision before the single existing preview', Boolean(activeRead)
        && target?.workflow === prepared.workflow && target?.version === 'ACTIVE' && !plan?.args.create, 'get_workflow ACTIVE → existing expectedRevision'),
      check('No other mutations, removed edit_workflow, publication or assignment in dialogue', foreign.length === 0
        && trace.filter((e) => e.tool === 'start_operation').every((e) => e.arguments?.toolName === 'configure_workflow'), foreign.map((e) => e.tool).join(', ') || 'none'),
      check(`Terminal COMPLETED / ${status}`, outcome.envelope?.status === 'COMPLETED' && outcome.result?.status === status,
        JSON.stringify({ operation: outcome.envelope?.status, domain: outcome.result?.status })),
      // Catalog tables used to clarify Resolution are not extra consequences previews.
      ...(plan ? [previewTable(conversation, plan.entry, { words: [prepared.workflow], label: 'Consequences presentation (advisory)', allowEarlier: true })] : []),
    ],
  };
}
