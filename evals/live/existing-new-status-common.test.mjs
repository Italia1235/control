import test from 'node:test';
import assert from 'node:assert/strict';
import { setupWorkflow, preservesOriginalState } from './existing-new-status-common.mjs';
import { PROJECT_MANAGEMENT_TEMPLATE } from './project-templates.mjs';

// Exercise the real shared setup up to the field boundary, without Jira or model calls.
{
  const runId = 'SCOPE13';
  const project = `L013${runId}`;
  const prefix = `L013-${runId}-`;
  const fieldBoundary = new Error('field boundary reached');
  function context({ rejectProject = false, collision = false } = {}) {
    const writes = [];
    const reads = [];
    return {
      writes, reads, runId, log() {},
      async tool(name, args) {
        reads.push({ name, args });
        if (name === 'list_statuses') return { statuses: [] };
        if (name === 'list_custom_field_types') return [{ typeKey: 'textfield' }];
        if (name === 'list_project_leads') return [{ username: 'admin' }];
        if (name === 'list_projects') return collision ? [{ key: project }] : [];
        throw new Error(`Unexpected read before field creation: ${name}`);
      },
      async apply(name, args) {
        writes.push({ name, args });
        if (name === 'create_project') {
          if (rejectProject) throw new Error('Setup rejected');
          return { status: 'CREATED' };
        }
        assert.equal(name, 'create_custom_fields');
        throw fieldBoundary;
      },
    };
  }
  test('inactive: fresh project precedes field with a project-only context', async () => {
    const c = context();
    await assert.rejects(setupWorkflow(c), (error) => error === fieldBoundary);
    assert.deepEqual(c.writes, [
      { name: 'create_project', args: { action: 'create', templateKey: PROJECT_MANAGEMENT_TEMPLATE, key: project, name: `${prefix}project`, lead: 'admin' } },
      { name: 'create_custom_fields', args: { fields: [{ name: `${prefix}Frosty Dune QG5`, typeKey: 'textfield',
        contexts: [{ name: `${prefix}project-context`, projects: [project], allIssueTypes: true }] }] } },
    ]);
    assert.ok(c.reads.some((r) => r.name === 'list_projects' && r.args.query === project));
  });
  test('inactive: rejection propagates without trying field creation', async () => {
    const c = context({ rejectProject: true });
    await assert.rejects(setupWorkflow(c), /Setup rejected/);
    assert.deepEqual(c.writes.map((r) => r.name), ['create_project']);
  });
  test('inactive: an existing project key stops before any mutation', async () => {
    const c = context({ collision: true });
    await assert.rejects(setupWorkflow(c), /Project key already exists/);
    assert.deepEqual(c.writes, []);
  });
}

const preservationOptions = { finishId: 11, controlId: 21, addedActionIds: [22] };
function preservationFixture() {
  const original = {
    steps: [{ statusId: 'todo', name: 'Todo', initial: true }, { statusId: 'done', name: 'Done', initial: false }],
    graph: [
      { actionId: 1, name: 'Create', kind: 'INITIAL', from: [], to: [1], resolution: { action: 'PRESERVE' } },
      { actionId: 11, name: 'Finish', kind: 'LOCAL', from: [1], to: [2], resolution: { action: 'SET', value: '10000' } },
      { actionId: 21, name: 'Control return', kind: 'LOCAL', from: [2], to: [1], resolution: { action: 'CLEAR' } },
    ],
    screens: { 1: null, 11: 'seed', 21: null },
    validators: { 1: [], 11: [{ kind: 'COMMENT_REQUIRED' }], 21: [
      { kind: 'REQUIRED_FIELDS', fieldIds: ['summary', 'assignee'], conditional: false }, { kind: 'OTHER', className: 'foreign.Validator' },
    ] },
    conditions: { 1: null, 11: null, 21: { operator: 'AND', children: [{ group: 'a' }, { group: 'b' }] } },
  };
  const state = structuredClone(original);
  state.steps.push({ statusId: 'work', name: 'Work', initial: false });
  state.graph[1].from = [3];
  state.graph.push({ actionId: 22, name: 'Enter work', kind: 'LOCAL', from: [1], to: [3], resolution: { action: 'PRESERVE' } });
  state.screens[11] = state.screens[22] = 'completion';
  state.validators[11].push({ kind: 'REQUIRED_FIELDS', fieldIds: ['customfield_1'], conditional: false });
  state.validators[22] = [{ kind: 'REQUIRED_FIELDS', fieldIds: ['priority'], conditional: false }];
  state.conditions[22] = null;
  return { original, state };
}

test('preservation accepts reader transition reordering without mutating snapshots', () => {
  const { original, state } = preservationFixture();
  assert.equal(preservesOriginalState(original, state, preservationOptions), true);
  state.graph = [state.graph[0], state.graph[3], state.graph[2], state.graph[1]];
  const saved = structuredClone({ original, state });
  assert.equal(preservesOriginalState(original, state, preservationOptions), true);
  assert.deepEqual({ original, state }, saved);
});

for (const [label, mutate] of [
  ['neighbor name', s => { s.graph[2].name = 'Changed'; }],
  ['neighbor kind', s => { s.graph[2].kind = 'GLOBAL'; }],
  ['neighbor source', s => { s.graph[2].from = [1]; }],
  ['neighbor target', s => { s.graph[2].to = [2]; }],
  ['neighbor resolution', s => { s.graph[2].resolution.action = 'PRESERVE'; }],
  ['neighbor screen', s => { s.screens[21] = 'foreign'; }],
  ['neighbor validator', s => { s.validators[21][0].conditional = true; }],
  ['neighbor condition', s => { s.conditions[21].operator = 'OR'; }],
  ['finish name', s => { s.graph[1].name = 'Changed'; }],
  ['finish target', s => { s.graph[1].to = [1]; }],
  ['finish resolution', s => { s.graph[1].resolution.value = 'other'; }],
  ['initial transition', s => { s.graph[0].to = [2]; }],
  ['original step', s => { s.steps[0].initial = false; }],
  ['original step order', s => { [s.steps[0], s.steps[1]] = [s.steps[1], s.steps[0]]; }],
  ['validator order', s => { s.validators[21].reverse(); }],
  ['validator field order', s => { s.validators[21][0].fieldIds.reverse(); }],
  ['condition child order', s => { s.conditions[21].children.reverse(); }],
  ['missing neighbor ID', s => { s.graph.splice(2, 1); }],
  ['missing finish ID', s => { s.graph.splice(1, 1); }],
  ['missing added ID', s => { s.graph.splice(3, 1); }],
  ['extra ID', s => { s.graph.push({ ...s.graph[2], actionId: 99 }); }],
  ['replaced ID with unchanged count', s => { s.graph[2].actionId = 99; }],
  ['duplicate original ID', s => { s.graph.push(structuredClone(s.graph[2])); }],
  ['duplicate new ID', s => { s.graph.push(structuredClone(s.graph[3])); }],
]) test(`preservation rejects ${label}`, () => {
  const { original, state } = preservationFixture();
  mutate(state);
  assert.equal(preservesOriginalState(original, state, preservationOptions), false);
});

test('preservation rejects duplicate IDs in the original snapshot or allowed additions', () => {
  const { original, state } = preservationFixture();
  original.graph.push(structuredClone(original.graph[2]));
  state.graph.splice(3, 0, structuredClone(state.graph[2]));
  assert.equal(preservesOriginalState(original, state, preservationOptions), false);
  const fresh = preservationFixture();
  assert.equal(preservesOriginalState(fresh.original, fresh.state, { ...preservationOptions, addedActionIds: [22, 22] }), false);
});

for (const key of ['from', 'to']) test(`preservation keeps nested ${key} array order significant`, () => {
  const { original, state } = preservationFixture();
  original.graph[2][key] = [1, 2];
  state.graph[2][key] = [2, 1];
  assert.equal(preservesOriginalState(original, state, preservationOptions), false);
});
