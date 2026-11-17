import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import verify from '../live/scenarios/comment-required-screen-write/verify.mjs';

// Invoked by WorkflowConfigureRulesMcpTest with actual MCP responses and persisted XML.
const cases = JSON.parse(await readFile(process.argv[2], 'utf8'));
for (const c of cases) {
  const context = {
    prepared: { workflow: c.workflow, actionId: 1, noScreenActionId: 11,
      screens: [{ id: 7, name: 'Form one' }, { id: 9, name: 'Form two' }], xml: c.before },
    trace: c.trace,
    adminPage: async () => ({ status: 200, text: c.after }),
    tool: async (name) => name === 'get_workflow_validators' ? c.validators : c.state,
  };
  assert.deepEqual((await verify(context)).filter((check) => !check.ok), [], c.mode);
  // A producer that conceals the screen/view change must not pass this consumer.
  const bad = structuredClone(c.trace);
  bad[0].result.diff = bad[0].result.diff.filter((line) => line.startsWith('ADD_VALIDATOR:'));
  assert.ok((await verify({ ...context, trace: bad })).some((check) => !check.ok), c.mode);
  console.log(`PASS screen preview consumer: ${c.mode}`);
}
