import test from 'node:test';
import assert from 'node:assert/strict';
import verify from '../live/scenarios/screen-layout-one-preview/verify.mjs';

const op = name => `admin.issue.operations.${name}`;
const original = ['summary', 'description', 'customfield_1', 'customfield_2', 'customfield_3'];
const final = ['summary', 'customfield_3', 'description', 'customfield_1', 'customfield_4'];
const prepared = {main: 'OWN', fieldIds: ['customfield_1', 'customfield_2', 'customfield_3', 'customfield_4'], before: {
  scheme: {id: 10, isDefault: false, otherProjects: 0},
  operations: [{issueType: 'Task', screenScheme: 11, screens: {[op('create')]: 1, [op('edit')]: 2, [op('view')]: 3}}],
  contents: Object.fromEntries([1, 2, 3].map(id => [id, {name: `Screen ${id}`, tabs: [{name: 'Field Tab', fields: original}]}])),
}};
const patch = {dryRun: true, screenId: 2, add: [{fieldId: 'customfield_4', place: 'LAST'}],
  moves: [{fieldId: 'customfield_3', place: 'AFTER', anchor: 'summary'}], remove: ['customfield_2']};
const preview = {tool: 'edit_screen_layout', arguments: patch, result: {planId: 'one'}};
const apply = {tool: 'start_operation', arguments: {toolName: 'edit_screen_layout', arguments: {dryRun: false, planId: 'one'}}};
const tool = async (name, args) => name === 'get_project_screens' ? {
  scheme: prepared.before.scheme, issueTypes: [{issueType: {name: 'Task'}, screenScheme: {id: 11,
    screens: ['create', 'edit', 'view'].map((name, index) => ({operation: op(name), id: index + 1, name: `Screen ${index + 1}`}))}}],
} : {tabs: [{name: 'Field Tab', fields: (args.screenId === 2 ? final : original).map(fieldId => ({fieldId}))}]};

for (const place of ['LAST', undefined]) {
  test(`one combined preview/apply with ${place ?? 'default LAST'} passes layout and unchanged CREATE/VIEW checks`, async () => {
    const request = {...patch, add: [{fieldId: 'customfield_4', ...(place ? {place} : {})}]};
    const checks = await verify({prepared, trace: [{...preview, arguments: request}, apply], tool});
    assert.deepEqual(checks.filter(check => !check.ok), []);
  });
}

test('a second same-screen preview fails even when final layout is correct', async () => {
  const checks = await verify({prepared, trace: [preview, {...preview, result: {planId: 'two'}}, apply], tool});
  assert.equal(checks.find(check => check.name.includes('ровно одно')).ok, false);
});

test('split same-screen applications fail even when final layout is correct', async () => {
  const second = {tool: 'remove_field_from_screens', arguments: {dryRun: true, fieldIds: ['customfield_2'], screens: [{screenId: 2}]}, result: {planId: 'two'}};
  const secondApply = {tool: 'start_operation', arguments: {toolName: second.tool, arguments: {dryRun: false, planId: 'two'}}};
  const checks = await verify({prepared, trace: [preview, apply, second, secondApply], tool});
  assert.equal(checks.find(check => check.name.includes('ровно одно')).ok, false);
  assert.equal(checks.find(check => check.name === 'других операций записи не было').ok, false);
});
