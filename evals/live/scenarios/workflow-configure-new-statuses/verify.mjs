import {
  GET_W, advisoryCheck, check, configureApplies, oneOfEach, outcomeOf, plannedOf, previewTable, readState, replyOf, same, toolChecks,
} from '../../configure-common.mjs';

// Three NEW global statuses and a workflow with a validator and a condition come from ONE
// configure_workflow create plan. Hard checks read only the final Jira state, the call trace and tool-response fields
// (preview diff, operation receipt); the table and the named global effect are advisory observations of the reply.
const nodesOf = (node) => node ? [node, ...(node.children ?? []).flatMap(nodesOf)] : [];

export default async function verify({ prepared, trace, conversation, tool }) {
  const { prefix, workflow, statuses: wanted, transitions, role } = prepared;
  const [first] = plannedOf(trace);
  const [apply] = configureApplies(trace);
  const { result: outcome, envelope } = outcomeOf(trace, apply);
  const checks = [...toolChecks(trace, { target: 'create' }), oneOfEach(trace, 'AC-6')];

  // --- the preview: the rules reach the diff as tool-response lines ------------------------------------------------------
  const diff = Array.isArray(first?.result.diff) ? first.result.diff.map(String) : [];
  const validatorLine = diff.find((line) => line.startsWith('ADD_VALIDATOR:') && line.includes('required_fields') && line.includes('assignee'));
  const conditionLine = diff.find((line) => line.startsWith('ADD_CONDITION:'));
  checks.push(check('AC-5/AC-6: preview WOULD_CREATE; diff содержит ADD_VALIDATOR required_fields [assignee] и ADD_CONDITION (поля ответа tool)',
    first?.result.status === 'WOULD_CREATE' && Boolean(validatorLine) && Boolean(conditionLine),
    JSON.stringify({ status: first?.result.status ?? first?.result.errorCode ?? null, diff: diff.slice(0, 8) })));

  // --- the operation: one apply, COMPLETED, every STATUS step APPLIED/MATCH -----------------------------------------------
  const steps = outcome?.execution?.steps ?? [];
  const statusSteps = steps.filter((step) => step.kind === 'STATUS');
  checks.push(check('AC-6/AC-3: get_operation COMPLETED, результат CREATED, три шага STATUS (все APPLIED/MATCH), ни одного MISMATCH/PARTIAL',
    envelope?.status === 'COMPLETED' && outcome?.status === 'CREATED' && outcome?.outcome !== 'PARTIAL'
      && statusSteps.length === 3 && statusSteps.every((step) => step.writeStatus === 'APPLIED' && step.verification === 'MATCH')
      && steps.every((step) => step.verification !== 'MISMATCH'),
    JSON.stringify({ operation: envelope?.status ?? null, result: outcome?.status ?? outcome?.errorCode ?? null, steps: steps.map((s) => `${s.key}:${s.writeStatus}/${s.verification}`) })));

  // --- Jira: the three statuses ------------------------------------------------------------------------------------------
  const { statuses: found } = await tool('list_statuses', { query: prefix, limit: 100 });
  const mine = found.filter((status) => status.name.startsWith(prefix));
  const byName = new Map(mine.map((status) => [status.name, status]));
  checks.push(check('AC-6/AC-3: в Jira ровно три статуса с префиксом прогона; имена и категории (TO_DO / IN_PROGRESS / DONE) как в запросе',
    mine.length === 3 && wanted.every((w) => byName.get(w.name)?.category === w.category),
    JSON.stringify(mine.map((s) => `${s.name}:${s.category}`))));

  // --- Jira: the workflow ------------------------------------------------------------------------------------------------
  const [bek, rabim, done] = wanted.map((w) => byName.get(w.name));
  const state = await readState(tool, workflow);
  const view = await tool(GET_W, { workflow, version: 'ACTIVE' });
  const stepIdOf = (status) => view.steps.find((s) => String(s.statusId) === String(status?.id))?.stepId;
  const stepIds = [bek, rabim, done].map(stepIdOf);
  const initialStep = view.steps.find((s) => s.initial);
  const moves = state.graph.filter((t) => t.kind !== 'INITIAL');
  const move = (name) => moves.find((t) => t.name === name);
  const [one, two] = [move(transitions.first), move(transitions.second)];
  checks.push(check('AC-6: workflow существует (прочитан из Jira): три шага на созданных статусах, начальный — первый статус, ровно два перехода «В работу» (1→2) и «Завершить» (2→3)',
    view.steps?.length === 3 && stepIds.every((id) => id !== undefined) && initialStep?.stepId === stepIds[0] && moves.length === 2
      && Boolean(one) && Boolean(two) && same(one.from, [stepIds[0]]) && same(one.to, [stepIds[1]]) && same(two.from, [stepIds[1]]) && same(two.to, [stepIds[2]]),
    JSON.stringify({ steps: view.steps?.map((s) => `${s.stepId}:${s.statusId}${s.initial ? '*' : ''}`), moves: moves.map((t) => `${t.name} ${t.from}->${t.to}`) })));

  const validators = state.validators[one?.actionId] ?? [];
  const requiredAssignee = validators.filter((v) => v.kind === 'REQUIRED_FIELDS' && !v.conditional && Array.isArray(v.fieldIds) && v.fieldIds.includes('assignee'));
  checks.push(check('AC-6: на «В работу» validator REQUIRED_FIELDS с assignee (из Jira)',
    requiredAssignee.length === 1, JSON.stringify(validators)));

  const rootTwo = state.conditions[two?.actionId] ?? null;
  const roleNodes = nodesOf(rootTwo).filter((n) => n.kind === 'PROJECT_ROLE');
  const operators = nodesOf(rootTwo).filter((n) => n.kind === 'COMPOUND').map((n) => n.operator);
  checks.push(check('AC-6: на «Завершить» condition project_role Administrators (не invert, без OR), на «В работу» условий нет (из Jira)',
    roleNodes.length === 1 && same(roleNodes[0].roleIds?.map(String), [String(role.id)]) && roleNodes[0].inverted === false
      && operators.every((op) => op === 'AND') && (state.conditions[one?.actionId] ?? null) === null,
    JSON.stringify({ second: rootTwo, first: state.conditions[one?.actionId] ?? null })));

  const resolutions = await tool('get_resolutions', {});
  const resolution = two?.resolution;
  checks.push(check('AC-6: на «Завершить» resolution SET существующим значением справочника (из Jira)',
    resolution?.action === 'SET' && resolutions.some((r) => String(r.id) === String(resolution.value)), JSON.stringify(resolution ?? null)));

  // --- advisory: the shape of the answer -------------------------------------------------------------------------------
  if (first) {
    const reply = replyOf(conversation, first.entry.turn);
    checks.push(previewTable(conversation, first.entry, { label: 'AC-6', words: [workflow, ...wanted.map((w) => w.name), transitions.first, transitions.second] }));
    checks.push(advisoryCheck('AC-6: в той же таблице названо, что статусы новые и глобальные (действуют во всей Jira)',
      /глобальн|global/iu.test(reply) && /нов|new|созда|creat/iu.test(reply), `«${reply.slice(0, 260)}»`));
  } else {
    checks.push(advisoryCheck('AC-6: preview показан таблицей', false, 'нет preview для показа'));
  }
  return checks;
}
