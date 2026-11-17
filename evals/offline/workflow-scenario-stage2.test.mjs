import assert from 'node:assert/strict';
import test from 'node:test';
import { generateWorkflowScenario } from './workflow-scenario.mjs';

const screens = [{id:'20', name:'Existing review'}, {id:'3', name:'Existing approval'}];
const get = (object, path) => path.split('.').reduce((value, key) => value[key], object);

test('stage one stays cooperative, fully specified and screen-free even with a catalog', () => {
  assert.deepEqual(generateWorkflowScenario(1, {stage:1, screens}), generateWorkflowScenario(1));
  assert.equal(generateWorkflowScenario(1).persona, 'cooperative_admin_ru');
  assert.deepEqual(generateWorkflowScenario(1).omissions, []);
  assert.deepEqual(generateWorkflowScenario(1).transitionScreens, []);
});

test('first five seeds deterministically cover five personas and zero through three omissions', () => {
  const generated = [1, 2, 3, 4, 5].map(seed => generateWorkflowScenario(seed, {stage:2, screens}));
  assert.deepEqual(generated.map(scenario => scenario.persona), [
    'cooperative_admin_ru', 'hasty_admin_ru', 'nontechnical_user_ru', 'vague_user_ru', 'mindchanger_admin_ru',
  ]);
  assert.deepEqual(generated.map(scenario => scenario.omissions.length), [0, 1, 2, 3, 2]);
  for (const scenario of generated) {
    assert.deepEqual(scenario, generateWorkflowScenario(scenario.seed, {stage:2, screens}));
    assert.equal(new Set(scenario.omissions.map(omission => omission.id)).size, scenario.omissions.length);
    for (const omission of scenario.omissions) {
      assert.deepEqual(Object.keys(omission).sort(), ['expected', 'id', 'kind', 'path']);
      assert.equal(get(scenario.workflow, omission.path), omission.expected);
    }
  }
});

test('existing screens are assigned by exact name with deterministic preserved identities', () => {
  const saved = structuredClone(screens);
  for (const seed of [0, 1, 4, 5, 0xffffffff]) {
    const scenario = generateWorkflowScenario(seed, {stage:2, screens});
    assert.deepEqual(scenario, generateWorkflowScenario(seed, {stage:2, screens:[...screens].reverse()}));
    assert.equal(scenario.transitionScreens.length, 2);
    for (const binding of scenario.transitionScreens) {
      const original = screens.find(screen => screen.id === binding.screenId);
      assert.ok(original);
      assert.equal(binding.screenName, original.name);
      const transition = scenario.workflow.transitions[binding.transitionIndex];
      assert.equal(transition.name, binding.transitionName);
      assert.equal(transition.screen, original.name);
    }
  }
  assert.deepEqual(screens, saved);
  const without = generateWorkflowScenario(4, {stage:2});
  assert.deepEqual(without.transitionScreens, []);
  assert.ok(without.workflow.transitions.every(transition => !Object.hasOwn(transition, 'screen')));
  assert.equal(without.omissions.at(-1).kind, 'transitionName');
  assert.throws(() => generateWorkflowScenario(1, {stage:2, screens:[{id:'1', name:''}]}));
});

test('stage-two graphs retain unique endpoints and final-status reachability at seed boundaries', () => {
  for (const seed of [...Array.from({length:100}, (_, index) => index), 0xffffffff]) {
    const {workflow} = generateWorkflowScenario(seed, {stage:2, screens});
    const names = new Set(workflow.statuses.map(status => status.name));
    assert.equal(names.size, workflow.statuses.length);
    assert.ok(names.size >= 5 && names.size <= 10);
    assert.ok(names.has(workflow.initialStatus));
    assert.ok(workflow.statuses.every(status => ['TO_DO', 'IN_PROGRESS', 'DONE'].includes(status.category)));
    assert.equal(new Set(workflow.transitions.map(t => t.key)).size, workflow.transitions.length);
    assert.ok(workflow.transitions.every(t => typeof t.key === 'string' && t.key.length > 0));
    const edges = new Set();
    for (const transition of workflow.transitions) {
      assert.ok(names.has(transition.from) && names.has(transition.to));
      const edge = `${transition.from}\0${transition.to}`;
      assert.ok(!edges.has(edge));
      edges.add(edge);
    }
    for (const name of names) {
      const reachable = new Set([name]);
      for (let pass = 0; pass < names.size; pass++) for (const transition of workflow.transitions) {
        if (reachable.has(transition.from)) reachable.add(transition.to);
      }
      assert.ok(reachable.has(workflow.statuses.at(-1).name));
    }
  }
});

test('mindchanger changes one transition label and no other workflow detail', () => {
  const scenario = generateWorkflowScenario(5, {stage:2, screens});
  const initial = structuredClone(scenario.initialWorkflow);
  assert.equal(get(initial, scenario.revision.path), scenario.revision.initial);
  assert.equal(get(scenario.workflow, scenario.revision.path), scenario.revision.expected);
  initial.transitions[1].name = scenario.revision.expected;
  assert.deepEqual(initial, scenario.workflow);
  assert.equal(scenario.revision.afterFirstPreview, true);
  assert.equal(generateWorkflowScenario(1, {stage:2}).revision, undefined);
});
