// All fixture writes use the runner's opt-in simulated-user confirmation.
import { actionIdsOf, check, createScreen, doneResolution, noTrace, readState, same, screenFields } from './configure-common.mjs';
import { contentOf, dialogueChecks, transitionOf } from './existing-structure-common.mjs';
import { projectManagement } from './project-templates.mjs';
export { check, readState, same };

export async function setupWorkflow({ runId, tool, apply, log }) {
  const prefix = `L013-${runId}-`;
  const workflow = `${prefix}inactive`;
  const statuses = [{ name: `${prefix}Todo`, category: 'TO_DO' }, { name: `${prefix}Done`, category: 'DONE' }];
  const catalog = await tool('list_statuses', { query: prefix, limit: 100 });
  if (catalog.statuses.some((s) => s.name.startsWith(prefix))) throw new Error('Run prefix already exists');
  const types = await tool('list_custom_field_types', {});
  // Keep field availability inside a fresh project, even for the unassigned inactive workflow.
  if (!JSON.stringify(types).includes('textfield')) throw new Error('Text field type unavailable');
  const leads = await tool('list_project_leads', { query: 'admin' });
  const lead = leads[0];
  if (!lead?.username) throw new Error('No active project lead');
  const project = `L013${runId.toUpperCase()}`;
  const projects = await tool('list_projects', { query: project });
  if (JSON.stringify(projects).includes(`"${project}"`)) throw new Error('Project key already exists');
  await apply('create_project', projectManagement({ key: project, name: `${prefix}project`, lead: lead.username }));
  const fieldName = `${prefix}Frosty Dune QG5`;
  const fields = await apply('create_custom_fields', { fields: [{ name: fieldName, typeKey: 'textfield',
    contexts: [{ name: `${prefix}project-context`, projects: [project], allIssueTypes: true }] }] });
  const resolution = await doneResolution(tool);
  const id = fields.report?.created?.find((row) => row.kind === 'CUSTOM_FIELD' && row.name === fieldName)?.id;
  if (!id) throw new Error('Fresh field was not created');
  const fieldId = String(id).startsWith('customfield_') ? String(id) : `customfield_${id}`;
  const seedScreen = await createScreen(apply, `${prefix}seed-screen`, ['comment']);
  const created = await apply('configure_workflow', {
    create: { name: workflow, statuses, initialStatus: statuses[0].name, transitions: [
      { key: 'finish', name: 'Finish', from: statuses[0].name, to: statuses[1].name, screen: seedScreen.id,
        resolution: { action: 'SET', value: resolution.id } },
      { key: 'control', name: 'Control return', from: statuses[1].name, to: statuses[0].name },
    ] },
    transitionRules: [
      { transitionKey: 'finish', addValidators: [{ template: 'comment_required' }] },
      { transitionKey: 'control', addValidators: [{ template: 'required_fields', parameters: { fieldIds: ['summary'] } }] },
    ],
  });
  if (created.status !== 'CREATED') throw new Error('Baseline workflow not created');
  const graph = await tool('get_workflow', { workflow, version: 'ACTIVE' });
  const state = await readState(tool, workflow);
  if (!state.scriptRunner?.installed || !state.scriptRunner?.enabled) throw new Error('ScriptRunner unavailable');
  if (!noTrace(state)) throw new Error('Unexpected baseline activation');
  const actions = actionIdsOf(state);
  if (!actions.Finish || !actions['Control return'] || !state.validators[actions.Finish]?.length
    || !state.validators[actions['Control return']]?.length) throw new Error('Seed rules missing');
  const newStatus = { name: `${prefix}Делоем`, category: 'IN_PROGRESS' };
  const newScreen = `${prefix}completion-screen`;
  log(`Prepared ${workflow}; no issues, cleanup or rollback`);
  return { prefix, workflow, statuses, fieldName, fieldId, seedScreen, project, graph, state, actions, newStatus, newScreen,
    known: { workflow, project, fieldName, fieldId, newScreen, newStatus, resolution } };
}

const required = (validators, field) => validators?.filter((v) => v.kind === 'REQUIRED_FIELDS'
  && v.conditional === false && same(v.fieldIds, [field])).length === 1;

export function preservesOriginalState(original, state, { finishId, controlId, addedActionIds }) {
  const byActionId = (a, b) => a.actionId - b.actionId;
  const expectedIds = [...original.graph.map(t => t.actionId), ...addedActionIds].sort((a, b) => a - b);
  const actualIds = state.graph.map(t => t.actionId).sort((a, b) => a - b);
  if (new Set(expectedIds).size !== expectedIds.length || new Set(actualIds).size !== actualIds.length
      || !same(expectedIds, actualIds)) return false;
  // Only the outer reader transition order is incidental. Nested arrays and every property remain exact.
  const before = { ...contentOf(original), graph: [...original.graph].sort(byActionId) };
  const retained = structuredClone(contentOf(state));
  retained.steps = retained.steps.filter((s) => original.steps.some((old) => String(old.statusId) === String(s.statusId)));
  retained.graph = retained.graph.filter(t => !addedActionIds.includes(t.actionId)).sort(byActionId);
  const originalFinish = original.graph.find(t => t.actionId === finishId);
  const retainedFinish = retained.graph.find(t => t.actionId === finishId);
  if (!originalFinish || !retainedFinish) return false;
  retainedFinish.from = originalFinish.from;
  retained.screens[finishId] = original.screens[finishId];
  retained.validators[finishId] = retained.validators[finishId].slice(0, original.validators[finishId].length);
  for (const actionId of addedActionIds) for (const key of ['screens', 'validators', 'conditions']) delete retained[key][actionId];
  return same(before, retained) && same(transitionOf(state, controlId), transitionOf(original, controlId));
}

export async function verifyWorkflow(context) {
  const { prepared: p, tool } = context;
  const { plan, outcome, checks } = dialogueChecks(context, 'UPDATED_INACTIVE');
  const args = plan?.args ?? {}, changes = args.existing?.changes ?? [];
  const additions = changes.filter((c) => c.op === 'ADD_STATUS');
  const createdRequest = additions.find((c) => c.newStatus?.name === p.newStatus.name);
  checks.push(check('AC-1/2/5: existing.newStatus is the sole status path, with exact new status intent',
    additions.length === 1 && additions.every((c) => !c.statusId && c.clientKey)
      && createdRequest?.newStatus.category === 'IN_PROGRESS', JSON.stringify(additions)));
  const version = 'ACTIVE';
  const state = await readState(tool, p.workflow, version);
  const graph = await tool('get_workflow', { workflow: p.workflow, version });
  const catalog = await tool('list_statuses', { query: p.prefix, limit: 100 });
  const fresh = catalog.statuses.filter((s) => s.name === p.newStatus.name);
  const status = fresh[0];
  const newStep = graph.steps.find((s) => String(s.statusId) === String(status?.id));
  const todo = p.graph.steps.find((s) => s.name === p.statuses[0].name)?.stepId;
  const done = p.graph.steps.find((s) => s.name === p.statuses[1].name)?.stepId;
  const finishId = p.actions.Finish, controlId = p.actions['Control return'];
  const finish = state.graph.find((t) => t.actionId === finishId);
  const incoming = state.graph.filter((t) => !p.state.graph.some((old) => old.actionId === t.actionId));
  const enter = incoming.find((t) => t.name === 'Enter work');
  checks.push(check('AC-1/3: actual native status/category/step and no TO_DO → Done bypass',
    fresh.length === 1 && status.category === 'IN_PROGRESS' && Boolean(newStep)
      && graph.steps.length === p.graph.steps.length + 1
      && finish?.kind === 'LOCAL' && same(finish.from, [newStep?.stepId]) && same(finish.to, [done])
      && enter?.kind === 'LOCAL' && same(enter.from, [todo]) && same(enter.to, [newStep?.stepId])
      && !state.graph.some((t) => t.from?.includes(todo) && t.to?.includes(done))
      && incoming.length === 1, JSON.stringify({ status, steps: graph.steps, incoming, finish })));
  const fields = state.screens[finishId] == null ? null : await screenFields(tool, state.screens[finishId]);
  checks.push(check('AC-2/3: priority on entry, custom field on exit, shared new screen with priority/custom field/comment',
    required(state.validators[enter?.actionId], 'priority') && required(state.validators[finishId], p.fieldId)
      && same(state.validators[finishId]?.slice(0, p.state.validators[finishId].length), p.state.validators[finishId])
      && state.validators[finishId]?.length === p.state.validators[finishId].length + 1
      && state.validators[enter?.actionId]?.length === 1 && state.conditions[enter?.actionId] === null
      && fields?.name === p.newScreen && same(fields?.fieldIds, ['priority', p.fieldId, 'comment'])
      && state.screens[enter?.actionId] === state.screens[finishId] && state.screens[finishId] !== p.seedScreen.id,
    JSON.stringify({ fields, validators: state.validators, screens: state.screens })));
  checks.push(check('AC-3/8: all original neighbors/rules/steps preserved except requested exit source/screen/validator',
    preservesOriginalState(p.state, state, { finishId, controlId,
      addedActionIds: [enter?.actionId] }), 'Control snapshots'));
  const steps = outcome.result?.execution?.steps ?? [];
  const writes = steps.filter((s) => s.kind === 'STATUS');
  const objects = outcome.result?.objects?.filter((o) => o.kind === 'STATUS') ?? [];
  const native = objects.find((o) => o.name === p.newStatus.name);
  checks.push(check('AC-7: exactly one STATUS APPLIED/MATCH and actual native ID/step returned',
    writes.length === 1 && writes[0].writeStatus === 'APPLIED' && writes[0].verification === 'MATCH'
      && steps.every((s) => s.verification !== 'MISMATCH') && outcome.result?.outcome !== 'PARTIAL'
      && native?.action === 'CREATE' && String(native.id) === String(status?.id) && native.stepId === newStep?.stepId,
    JSON.stringify({ writes, objects })));
  const entryRequest = changes.find((c) => c.op === 'ADD_TRANSITION' && c.name === 'Enter work');
  const entryMapping = outcome.result?.transitions?.find((t) => t.transitionKey === entryRequest?.transitionKey);
  const screenMapping = outcome.result?.objects?.find((o) => o.kind === 'SCREEN' && o.name === p.newScreen);
  checks.push(check('AC-2/7: transitionKey and new screen IDs returned match actual Jira',
    Boolean(entryRequest?.transitionKey) && entryMapping?.actionId === enter?.actionId
      && String(entryMapping?.screenId) === String(state.screens[finishId])
      && args.newScreens?.length === 1 && screenMapping != null
      && String(screenMapping.screenId) === String(state.screens[finishId]),
    JSON.stringify({ entryMapping, screenMapping })));
  checks.push(check('AC-8: workflow remains inactive with no consumers or draft', noTrace(state), JSON.stringify(state.consumers)));
  return checks;
}
