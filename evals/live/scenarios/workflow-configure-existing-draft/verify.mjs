import { check, contentOf, dialogueChecks, readState, same } from '../../existing-structure-common.mjs';
import { screenFields } from '../../configure-common.mjs';

export default async function verify(context) {
  const { prepared: p, tool } = context;
  const { plan, checks } = dialogueChecks(context, 'DRAFT_SAVED');
  const changes = plan?.args.existing?.changes ?? [];
  const actionId = p.actions['Start work'];
  const renames = changes.filter((c) => c.op === 'UPDATE_TRANSITION' && c.actionId === actionId && c.name === 'Started');
  const clears = changes.filter((c) => c.op === 'UPDATE_TRANSITION' && c.actionId === actionId && same(c.screen, { action: 'CLEAR' }));
  checks.push(check('Single existing changes request renames Start work and CLEARs its screen',
    renames.length === 1 && clears.length === 1 && changes.length >= 1 && changes.length <= 2
      && changes.every((c) => c.op === 'UPDATE_TRANSITION' && c.actionId === actionId
        && Object.keys(c).every((key) => ['op', 'actionId', 'name', 'screen'].includes(key)))
      && !plan?.args.newScreens?.length && !plan?.args.transitionRules?.length, JSON.stringify(changes)));
  const active = await readState(tool, p.workflow, 'ACTIVE');
  const draft = await readState(tool, p.workflow, 'DRAFT');
  const expected = structuredClone(contentOf(p.state));
  expected.graph.find((t) => t.actionId === actionId).name = 'Started';
  expected.screens[actionId] = null;
  checks.push(check('Draft contains exactly the rename and removed screen, preserving all other reader facts',
    same(contentOf(draft), expected), JSON.stringify({ graph: draft.graph, screens: draft.screens })));
  checks.push(check('Workflow remains active with saved draft; active graph/screens/rules remain unchanged',
    active.active === true && active.hasDraft === true && same(contentOf(active), contentOf(p.state))
      && same(active.consumers, p.state.consumers), 'Compare ACTIVE Control reader snapshots, excluding revision and hasDraft'));
  const assignments = await tool('get_project_workflows', { project: p.project });
  checks.push(check('Fresh project scheme, default and assignments for every issue type are unchanged',
    p.assignments.issueTypes.length > 0 && same(assignments, p.assignments), JSON.stringify(assignments)));
  const seedScreen = await screenFields(tool, p.seedScreen.id);
  checks.push(check('CLEAR removes the workflow reference, not the seed screen or its fields',
    String(p.state.screens[actionId]) === p.seedScreen.id && same(seedScreen.fieldIds, p.seedScreen.fieldIds)
      && seedScreen.name === p.seedScreen.name, JSON.stringify(seedScreen)));
  // No issue-migration claim: setup creates no issues. The nonempty issue-store proof belongs to unit/wired tests.
  return checks;
}
