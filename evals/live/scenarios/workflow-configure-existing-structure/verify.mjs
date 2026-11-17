import { check, contentOf, dialogueChecks, readState, same, transitionOf } from '../../existing-structure-common.mjs';
import { noTrace, screenFields } from '../../configure-common.mjs';

export default async function verify(context) {
  const { prepared: p, tool } = context;
  const { plan, outcome, checks } = dialogueChecks(context, 'UPDATED_INACTIVE');
  const args = plan?.args ?? {};
  const changes = args.existing?.changes ?? [];
  const additions = changes.filter((c) => c.op === 'ADD_TRANSITION');
  const added = additions[0];
  const rule = args.transitionRules?.find((r) => r.transitionKey === added?.transitionKey);
  const screen = args.newScreens?.find((s) => s.key === rule?.screen?.screenKey);
  const startId = p.actions['Start work'], controlId = p.actions['Control return'];
  const source = p.graph.steps.find((s) => s.name === p.statuses[1].name)?.stepId;
  const target = p.graph.steps.find((s) => s.name === p.statuses[2].name)?.stepId;
  // Exact structured request coverage; local keys are model-selected, not hard-coded.
  checks.push(check('One combined existing plan: ADD_TRANSITION/local key + screenKey/newScreens + assignee validator and rename',
    changes.length === 2 && additions.length === 1 && Boolean(added?.transitionKey)
      && same(added, { op: 'ADD_TRANSITION', transitionKey: added.transitionKey, name: 'Complete',
        source: { stepId: source }, targetStepId: target, resolution: { action: 'SET', value: p.resolution.id } })
      && changes.some((c) => same(c, { op: 'UPDATE_TRANSITION', actionId: startId, name: 'Started' }))
      && args.newScreens?.length === 1 && screen?.name === p.newScreen && same(screen?.fieldIds, ['summary', 'assignee'])
      && args.transitionRules?.length === 1 && same(rule, { transitionKey: added.transitionKey,
        screen: { action: 'SET', screenKey: screen?.key },
        addValidators: [{ template: 'required_fields', parameters: { fieldIds: ['assignee'] } }] }),
    JSON.stringify({ changes, newScreens: args.newScreens, transitionRules: args.transitionRules })));
  const state = await readState(tool, p.workflow);
  const newTransitions = state.graph.filter((t) => !p.state.graph.some((old) => old.actionId === t.actionId));
  const actual = newTransitions[0];
  const actualScreenId = state.screens[actual?.actionId];
  const fields = actualScreenId == null ? null : await screenFields(tool, actualScreenId);
  checks.push(check('Inactive workflow saved in place; exactly the requested local Complete transition and Resolution',
    noTrace(state) && newTransitions.length === 1 && actual?.name === 'Complete' && actual.kind === 'LOCAL'
      && same(actual.from, [source]) && same(actual.to, [target])
      && actual.resolution?.action === 'SET' && String(actual.resolution?.value) === String(p.resolution.id), JSON.stringify(actual)));
  checks.push(check('New transition has only the requested screen and assignee required_fields validator, with no conditions', actualScreenId != null
    && fields?.name === p.newScreen && same(fields?.fieldIds, ['summary', 'assignee'])
    && same(state.validators[actual?.actionId], [{ kind: 'REQUIRED_FIELDS', fieldIds: ['assignee'], conditional: false }])
    && state.conditions[actual?.actionId] === null,
  JSON.stringify({ screenId: actualScreenId, fields, validators: state.validators[actual?.actionId], conditions: state.conditions[actual?.actionId] })));
  const expected = structuredClone(contentOf(p.state));
  expected.graph.find((t) => t.actionId === startId).name = 'Started';
  const retained = structuredClone(contentOf(state));
  retained.graph = retained.graph.filter((t) => t.actionId !== actual?.actionId);
  for (const key of ['screens', 'validators', 'conditions']) delete retained[key][actual?.actionId];
  checks.push(check('Rename keeps its actionId; all old graph/rules/screens including control neighbor remain unchanged',
    same(retained, expected) && same(transitionOf(state, controlId), transitionOf(p.state, controlId)), 'Control snapshots of all original transitions'));
  const resultTransition = outcome.result?.transitions?.find((t) => t.transitionKey === added?.transitionKey);
  const resultScreen = outcome.result?.objects?.find((o) => o.kind === 'SCREEN' && o.key === screen?.key);
  checks.push(check('Returned local-key actionId and screenId mappings match actual Jira readers', Boolean(actual)
    && resultTransition?.actionId === actual?.actionId && String(resultTransition?.screenId) === String(actualScreenId)
    && actualScreenId != null && String(resultScreen?.screenId) === String(actualScreenId),
  JSON.stringify({ resultTransition, resultScreen, actualActionId: actual?.actionId, actualScreenId })));
  return checks;
}
