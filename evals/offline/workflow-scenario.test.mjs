import assert from 'node:assert/strict';
import test from 'node:test';
import { generateWorkflowScenario } from './workflow-scenario.mjs';

test('seed reproduces the complete scenario without clock or Jira inputs', () => {
  assert.equal(JSON.stringify(generateWorkflowScenario(1)), JSON.stringify(generateWorkflowScenario(1)));
  assert.notDeepEqual(generateWorkflowScenario(1), generateWorkflowScenario(2));
});

test('generated graphs use 5–10 unique statuses and every status reaches the final one', () => {
  for (let seed = 0; seed < 100; seed++) {
    const { workflow, omissions, transitionScreens } = generateWorkflowScenario(seed);
    const names = new Set(workflow.statuses.map(s => s.name));
    assert.ok(names.size >= 5 && names.size <= 10);
    assert.equal(names.size, workflow.statuses.length);
    assert.ok(names.has(workflow.initialStatus));
    assert.ok(workflow.statuses.every(s => ['TO_DO', 'IN_PROGRESS', 'DONE'].includes(s.category)));
    const final = workflow.statuses.at(-1).name;
    assert.deepEqual(workflow.transitions.map(t => t.key),
      workflow.transitions.map((_, index) => `transition-${index + 1}`));
    assert.equal(new Set(workflow.transitions.map(t => t.key)).size, workflow.transitions.length);
    const edges = new Set();
    for (const t of workflow.transitions) {
      assert.ok(names.has(t.from) && names.has(t.to));
      assert.ok(!edges.has(`${t.from}\0${t.to}`));
      edges.add(`${t.from}\0${t.to}`);
    }
    for (const name of names) {
      const reachable = new Set([name]);
      for (let i = 0; i < names.size; i++) for (const t of workflow.transitions) {
        if (reachable.has(t.from)) reachable.add(t.to);
      }
      assert.ok(reachable.has(final), `${seed}: ${name} cannot reach final`);
    }
    assert.deepEqual(omissions, []);
    assert.deepEqual(transitionScreens, []);
  }
});

test('invalid seeds are rejected rather than silently aliased', () => {
  for (const seed of [-1, 1.5, NaN, Infinity, '1', 2 ** 32]) assert.throws(() => generateWorkflowScenario(seed));
});
