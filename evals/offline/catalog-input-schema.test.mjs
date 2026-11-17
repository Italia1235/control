import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import Ajv2020 from 'ajv/dist/2020.js';

const catalogs = ['common', 'customfields', 'screens', 'projects', 'issuetypes', 'resolution', 'rollback'];
const tools = (await Promise.all(catalogs.map(async catalog => JSON.parse(await readFile(
  new URL(`../../src/main/resources/llm/${catalog}-tools.json`, import.meta.url), 'utf8'))))).flat();
// Branch requirements may refer to properties declared by the containing object schema.
const ajv = new Ajv2020({strict: true, strictRequired: false, allErrors: true});
const validators = new Map(tools.map(tool => [tool.name, ajv.compile(tool.input_schema)]));
const clone = value => structuredClone(value);
function accepts(name, args) {
  const validate = validators.get(name);
  assert.equal(validate(args), true, `${name}: ${JSON.stringify(validate.errors)}`);
}
function rejects(name, args) {
  assert.equal(validators.get(name)(args), false, `${name} accepted ${JSON.stringify(args)}`);
}

const PROJECT_MANAGEMENT = 'com.atlassian.jira-core-project-templates:jira-core-project-management';
const TASK_MANAGEMENT = 'com.atlassian.jira-core-project-templates:jira-core-task-management';
const PROCESS_MANAGEMENT = 'com.atlassian.jira-core-project-templates:jira-core-process-management';
const SCRUM = 'com.pyxis.greenhopper.jira:gh-scrum-template';
const KANBAN = 'com.pyxis.greenhopper.jira:gh-kanban-template';
const BASIC = 'com.pyxis.greenhopper.jira:basic-software-development-template';

const fixtures = {
  create_custom_fields: {
    dryRun: true, fields: [{name: 'Priority detail', typeKey: 'cascadingselect',
      options: [{value: 'High', children: [{value: 'Urgent'}]}],
      contexts: [{projects: ['ABC'], issueTypes: ['Bug'], options: []}]}],
  },
  add_field_to_screens: {dryRun: true, fieldIds: ['customfield_10100'], screens: [{screenId: 10100, tab: 'Field Tab'}]},
  remove_field_from_screens: {dryRun: true, fieldIds: ['labels'], screens: [{screenId: 10100}]},
  edit_screen_layout: {dryRun: true, screenId: 10100,
    add: [{fieldId: 'customfield_10100', place: 'LAST'}],
    moves: [{fieldId: 'labels', tab: 'Details', place: 'AFTER', anchor: 'assignee'}],
    remove: ['description']},
  update_field_contexts: {dryRun: true, fieldId: 'customfield_10100',
    contexts: [{contextId: 10000, projects: ['ABC'], allIssueTypes: true}]},
  create_field_context: {dryRun: true, fieldId: 'customfield_10100', name: 'QA QABUG', description: 'Bug family',
    projects: ['QABUG'], issueTypes: ['Bug', 'Sub-bug', 'Sub-task']},
  set_field_required: {dryRun: true, project: 'ABC',
    rows: [{fields: ['customfield_10100'], required: true, allIssueTypes: true}]},
  clone_project_field_configurations: {dryRun: true, project: 'ABC'},
  create_field_configuration: {dryRun: true, name: 'ABC: Task fields', description: 'Task only', copyFrom: '10200'},
  create_field_configuration_scheme: {dryRun: true, name: 'ABC fields', description: 'ABC only', mappings: [
    {issueType: 'DEFAULT', fieldConfiguration: 'DEFAULT'}, {issueType: 'Task', fieldConfiguration: '10310'},
  ]},
  assign_field_configuration_scheme: {dryRun: true, project: 'ABC', scheme: '10400'},
  set_field_configuration_mappings: {dryRun: true, scheme: '10400', mappings: [
    {issueType: 'Task', fieldConfiguration: '10310'}, {issueType: 'Bug', fieldConfiguration: 'INHERIT'},
  ]},
  clone_project_screens: {dryRun: true, project: 'ABC', scope: 'SELECTED', issueTypes: ['Bug'], copyScreens: true},
  set_project_operation_screens: {dryRun: true, project: 'ABC', mappings: [{issueTypes: ['Bug'], screens: {CREATE: '10100', VIEW: 'Details'}}, {allIssueTypes: true, screens: {EDIT: 'Details'}}]},
  set_issue_type_screen_scheme_mappings: {dryRun: true, issueTypeScreenScheme: 'Shared ITSS', rows: [
    {action: 'REPLACE', issueType: 'DEFAULT', screenScheme: '10100'}, {action: 'REMOVE', issueType: 'Bug'},
    {action: 'ADD', issueType: 'Vacation', screenScheme: 'HR Vacation Screen Scheme'},
  ]},
  assign_issue_type_screen_scheme: {dryRun: true, project: 'ABC', issueTypeScreenScheme: 'Shared ITSS'},
  set_screen_scheme_operations: {dryRun: true, screenScheme: 'Shared SS', rows: [
    {action: 'REPLACE', operation: 'CREATE', screen: '10100'}, {action: 'REMOVE', operation: 'EDIT'},
    {action: 'ADD', operation: 'VIEW', screen: 'Details screen'},
  ]},
  create_screen: {dryRun: true, name: 'Delivery screen', description: 'Delivery details', fields: [
    {name: 'summary'}, {name: 'Delivery region', typeKey: 'cascadingselect', options: [{value: 'Europe', children: [{value: 'West'}]}]},
  ]},
  create_project: {action: 'create', templateKey: PROJECT_MANAGEMENT, dryRun: true, name: 'Delivery', key: 'DEL', lead: 'alex'},
  set_project_issue_types: {dryRun: true, project: 'ABC', issueTypes: ['Task', 'Bug'], mappings: [
    {from: 'Story', to: 'Task', statusMappings: [{from: 'Review', to: 'Done'}]},
  ]},
  create_issue_types: {dryRun: true, issueTypes: [{key: 'delivery', name: 'Delivery request', subtask: false, description: 'Requested work'}]},
  create_resolution: {dryRun: true, name: 'Delivered', description: 'Delivery completed'},
};
const required = {
  create_custom_fields: ['dryRun', 'fields'],
  add_field_to_screens: ['dryRun', 'fieldIds', 'screens'],
  remove_field_from_screens: ['dryRun', 'fieldIds', 'screens'],
  edit_screen_layout: ['dryRun', 'screenId'],
  update_field_contexts: ['dryRun', 'fieldId', 'contexts'],
  create_field_context: ['dryRun', 'fieldId', 'name', 'projects', 'issueTypes'],
  set_field_required: ['dryRun', 'project', 'rows'],
  clone_project_field_configurations: ['dryRun', 'project'],
  create_field_configuration: ['dryRun', 'name'],
  create_field_configuration_scheme: ['dryRun', 'name', 'mappings'],
  assign_field_configuration_scheme: ['dryRun', 'project', 'scheme'],
  set_field_configuration_mappings: ['dryRun', 'scheme', 'mappings'],
  clone_project_screens: ['dryRun', 'project', 'scope', 'copyScreens'],
  set_project_operation_screens: ['dryRun', 'project', 'mappings'],
  set_issue_type_screen_scheme_mappings: ['dryRun', 'issueTypeScreenScheme', 'rows'],
  assign_issue_type_screen_scheme: ['dryRun', 'project', 'issueTypeScreenScheme'],
  set_screen_scheme_operations: ['dryRun', 'screenScheme', 'rows'],
  create_screen: ['dryRun', 'name', 'fields'],
  create_project: ['action', 'templateKey', 'dryRun', 'name', 'key', 'lead'],
  set_project_issue_types: ['dryRun', 'project', 'issueTypes'],
  create_issue_types: ['dryRun', 'issueTypes'],
  create_resolution: ['dryRun', 'name'],
};

test('fixtures cover every domain mutation in these catalogs', () => {
  const mutations = tools.filter(tool => tool.name !== 'start_operation' && !tool.annotations.readOnlyHint);
  assert.deepEqual(mutations.map(tool => tool.name).sort(), Object.keys(fixtures).sort());
});

for (const [name, args] of Object.entries(fixtures)) {
  test(`${name} accepts its complete public preview and refuses direct apply`, () => {
    accepts(name, clone(args));
    rejects(name, {...clone(args), dryRun: false});
    rejects(name, {...clone(args), planId: 'confirmed-plan'});
    rejects(name, {...clone(args), unexpected: true});
    const apply = {dryRun: false, planId: 'confirmed-plan'};
    rejects(name, apply);
    accepts('start_operation', {toolName: name, arguments: apply});
    for (const property of required[name]) {
      const missing = clone(args);
      delete missing[property];
      rejects(name, missing);
    }
  });
}

const itssRows = rows => ({dryRun: true, issueTypeScreenScheme: 'Shared ITSS', rows});

test('ITSS mapping rows: ADD/REPLACE need screenScheme, REMOVE forbids it, rows are closed, bounded and nonempty', () => {
  const name = 'set_issue_type_screen_scheme_mappings';
  accepts(name, itssRows([{action: 'ADD', issueType: 'Vacation', screenScheme: '10100'}]));
  accepts(name, itssRows([{action: 'REPLACE', issueType: 'DEFAULT', screenScheme: 'HR SS'}]));
  accepts(name, itssRows([{action: 'REMOVE', issueType: '10002'}]));
  accepts(name, {...itssRows([{action: 'REMOVE', issueType: 'Bug'}]), issueTypeScreenScheme: '20'});
  accepts(name, itssRows(Array.from({length: 50}, () => ({action: 'REMOVE', issueType: 'Bug'}))));
  rejects(name, itssRows([]));
  rejects(name, itssRows(Array.from({length: 51}, () => ({action: 'REMOVE', issueType: 'Bug'}))));
  rejects(name, itssRows([{action: 'REMOVE', issueType: 'Bug', screenScheme: '10100'}]));
  rejects(name, itssRows([{action: 'ADD', issueType: 'Bug'}]));
  rejects(name, itssRows([{action: 'REPLACE', issueType: 'Bug'}]));
  rejects(name, itssRows([{action: 'MOVE', issueType: 'Bug', screenScheme: '10100'}]));
  rejects(name, itssRows([{issueType: 'Bug', screenScheme: '10100'}]));
  rejects(name, itssRows([{action: 'ADD', screenScheme: '10100'}]));
  rejects(name, itssRows([{action: 'ADD', issueType: '', screenScheme: '10100'}]));
  rejects(name, itssRows([{action: 'ADD', issueType: 'Bug', screenScheme: ''}]));
  rejects(name, itssRows([{action: 'ADD', issueType: 'Bug', screenScheme: '10100', extra: 1}]));
  rejects(name, {dryRun: true, rows: [{action: 'REMOVE', issueType: 'Bug'}]});
  rejects(name, {dryRun: true, issueTypeScreenScheme: '', rows: [{action: 'REMOVE', issueType: 'Bug'}]});
  rejects(name, {dryRun: true, issueTypeScreenScheme: 'Shared ITSS', mappings: [{action: 'REMOVE', issueType: 'Bug'}]});
});

const ssRows = rows => ({dryRun: true, screenScheme: 'Shared SS', rows});

test('Screen Scheme rows: ADD/REPLACE need screen, REMOVE forbids it, operation is one of four, rows are closed, bounded and nonempty', () => {
  const name = 'set_screen_scheme_operations';
  for (const operation of ['DEFAULT', 'CREATE', 'EDIT', 'VIEW']) {
    accepts(name, ssRows([{action: 'REPLACE', operation, screen: '10100'}]));
  }
  accepts(name, ssRows([{action: 'ADD', operation: 'VIEW', screen: 'Details screen'}]));
  accepts(name, ssRows([{action: 'REMOVE', operation: 'EDIT'}]));
  accepts(name, {...ssRows([{action: 'REMOVE', operation: 'EDIT'}]), screenScheme: '30'});
  accepts(name, ssRows(Array.from({length: 50}, () => ({action: 'REMOVE', operation: 'EDIT'}))));
  rejects(name, ssRows([]));
  rejects(name, ssRows(Array.from({length: 51}, () => ({action: 'REMOVE', operation: 'EDIT'}))));
  rejects(name, ssRows([{action: 'REMOVE', operation: 'EDIT', screen: '10100'}]));
  rejects(name, ssRows([{action: 'ADD', operation: 'EDIT'}]));
  rejects(name, ssRows([{action: 'REPLACE', operation: 'EDIT'}]));
  rejects(name, ssRows([{action: 'MOVE', operation: 'EDIT', screen: '10100'}]));
  rejects(name, ssRows([{action: 'REPLACE', operation: 'TRANSITION', screen: '10100'}]));
  rejects(name, ssRows([{action: 'REPLACE', operation: 'create', screen: '10100'}]));
  rejects(name, ssRows([{operation: 'EDIT', screen: '10100'}]));
  rejects(name, ssRows([{action: 'ADD', screen: '10100'}]));
  rejects(name, ssRows([{action: 'ADD', operation: 'EDIT', screen: ''}]));
  rejects(name, ssRows([{action: 'ADD', operation: 'EDIT', screen: '10100', extra: 1}]));
  rejects(name, ssRows([{action: 'ADD', issueType: 'Bug', screen: '10100'}]));
  rejects(name, {dryRun: true, rows: [{action: 'REMOVE', operation: 'EDIT'}]});
  rejects(name, {dryRun: true, screenScheme: '', rows: [{action: 'REMOVE', operation: 'EDIT'}]});
  rejects(name, {dryRun: true, screenScheme: 'Shared SS', mappings: [{action: 'REMOVE', operation: 'EDIT'}]});
});

const row = (extra = {}) => ({fields: ['Delivery region'], required: true, issueTypes: ['Bug'], ...extra});
const requirement = (...rows) => ({dryRun: true, project: 'ABC', rows});

test('field requirement rows retain exactly one explicit issue-type scope each', () => {
  accepts('set_field_required', requirement(row()));
  rejects('set_field_required', requirement(row({allIssueTypes: true})));
  rejects('set_field_required', requirement(row({issueTypes: undefined})));
  rejects('set_field_required', requirement(row({issueTypes: undefined, allIssueTypes: false})));
  rejects('set_field_required', requirement(row({issueTypes: []})));
  rejects('set_field_required', requirement(row({issueTypes: ['Bug', 'Bug']})));
});

test('field requirement rows accept 1–10 distinct fields, an explicit flag, and several rows', () => {
  const fields = ['duedate', 'priority', 'customfield_10100'];
  accepts('set_field_required', requirement(row({fields})));
  accepts('set_field_required', requirement(row({fields, required: false})));
  accepts('set_field_required', requirement(row({fields: ['duedate']})));
  accepts('set_field_required', requirement(row({fields: Array.from({length: 10}, (_, i) => `customfield_${10100 + i}`)})));
  accepts('set_field_required', requirement(row({issueTypes: ['Payment']}),
    row({fields: ['priority'], issueTypes: undefined, allIssueTypes: true, required: false})));
  accepts('set_field_required', requirement(...Array.from({length: 20}, () => row())));
  rejects('set_field_required', requirement(...Array.from({length: 21}, () => row())));
  rejects('set_field_required', requirement());
  rejects('set_field_required', requirement(row({required: undefined})));
  for (const bad of [[], Array.from({length: 11}, (_, i) => `customfield_${10100 + i}`),
    ['duedate', 'duedate'], [''], [null], [123], 'duedate', null]) {
    rejects('set_field_required', requirement(row({fields: bad})));
  }
});

test('the retired single-scope arguments are rejected, not converted', () => {
  const retired = {fields: ['duedate'], required: true, allIssueTypes: true, issueTypes: ['Bug'], field: 'duedate'};
  for (const [name, value] of Object.entries(retired)) {
    rejects('set_field_required', {...requirement(row()), [name]: value});
  }
  rejects('set_field_required', {dryRun: true, project: 'ABC', fields: ['duedate'], allIssueTypes: true});
});

test('create_project has exclusive modes: list_templates alone, or create with templateKey; the old form is gone', () => {
  const tool = 'create_project';
  const create = fixtures.create_project;
  accepts(tool, {action: 'list_templates'});
  accepts(tool, create);
  for (const templateKey of [TASK_MANAGEMENT, PROCESS_MANAGEMENT, SCRUM, KANBAN, BASIC])
    accepts(tool, {...create, templateKey});
  for (const extra of [{dryRun: true}, {dryRun: false}, {name: 'X'}, {key: 'X'}, {lead: 'alex'},
    {templateKey: PROJECT_MANAGEMENT}, {planId: 'p'}, {issueTypes: ['Task']}, {defaultIssueType: 'Task'}, {unexpected: 1}])
    rejects(tool, {action: 'list_templates', ...extra});
  const {action, ...withoutAction} = create;
  const {templateKey, ...withoutTemplate} = create;
  rejects(tool, withoutAction);
  rejects(tool, withoutTemplate);
  rejects(tool, {dryRun: true, name: 'Delivery', key: 'DEL', lead: 'alex'});
  for (const bad of ['', null, 123, 'delete', 'CREATE', 'list-templates']) rejects(tool, {...create, action: bad});
  for (const bad of ['', null, 123, ['x']]) rejects(tool, {...create, templateKey: bad});
  rejects(tool, {action: 'create', dryRun: false, planId: 'p'});
  rejects(tool, {});
  rejects(tool, {action: 'list_templates', planId: 'p', dryRun: false});
  rejects('start_operation', {toolName: tool, arguments: {action: 'list_templates'}});
  rejects('start_operation', {toolName: tool, arguments: {action: 'create', dryRun: false, planId: 'confirmed-plan'}});
  assert.equal(tools.find(item => item.name === tool).annotations.readOnlyHint, false);
});

test('previews retain nested required fields, closed shapes, enums and bounds', () => {
  rejects('create_project', {...fixtures.create_project, key: ''});
  accepts('create_project', {...fixtures.create_project, issueTypes: ['Task'], newIssueTypes: [{key: 'consult', name: 'Консультация', subtask: false}], defaultIssueType: 'consult'});
  rejects('create_project', {...fixtures.create_project, newIssueTypes: [{name: 'No key', subtask: false}]});
  rejects('create_project', {...fixtures.create_project, newIssueTypes: [{key: 'k', name: 'No kind'}]});
  accepts('set_project_issue_types', {...fixtures.set_project_issue_types, issueTypes: [], newIssueTypes: [{key: 'a', name: 'A', subtask: false}]});
  accepts('create_issue_types', {...fixtures.create_issue_types, issueTypes: [{...fixtures.create_issue_types.issueTypes[0], subtask: true}]});
  rejects('create_issue_types', {...fixtures.create_issue_types, issueTypes: [{...fixtures.create_issue_types.issueTypes[0], subtask: 'false'}]});
  rejects('create_issue_types', {...fixtures.create_issue_types, issueTypes: []});
  rejects('create_issue_types', {...fixtures.create_issue_types, issueTypes: [{name: 'No key', subtask: false}]});
  rejects('create_resolution', {...fixtures.create_resolution, name: ''});
  for (const scope of ['SCHEME_ONLY', 'ALL']) {
    accepts('clone_project_screens', {dryRun: true, project: 'ABC', scope, copyScreens: false});
  }
  rejects('clone_project_screens', {...fixtures.clone_project_screens, scope: 'UNKNOWN'});
  rejects('clone_project_screens', {...fixtures.clone_project_screens, issueTypes: ['Bug', 'Bug']});
  rejects('clone_project_screens', {...fixtures.clone_project_screens, issueTypes: Array.from({length: 51}, (_, i) => `Type ${i}`)});
  const rowOf = (row) => ({...fixtures.set_project_operation_screens, mappings: [row]});
  accepts('set_project_operation_screens', rowOf({allIssueTypes: true, screens: {EDIT: 'Details'}}));
  rejects('set_project_operation_screens', rowOf({issueTypes: ['Bug'], screens: {}}));
  rejects('set_project_operation_screens', rowOf({issueTypes: ['Bug'], screens: {DEFAULT: '10100'}}));
  rejects('set_project_operation_screens', rowOf({issueTypes: ['Bug'], screens: {CREATE: ''}}));
  rejects('set_project_operation_screens', rowOf({screens: {CREATE: '10100'}}));
  rejects('set_project_operation_screens', rowOf({issueTypes: ['Bug'], allIssueTypes: true, screens: {CREATE: '10100'}}));
  rejects('set_project_operation_screens', rowOf({allIssueTypes: false, screens: {CREATE: '10100'}}));
  rejects('set_project_operation_screens', rowOf({issueTypes: [], screens: {CREATE: '10100'}}));
  rejects('set_project_operation_screens', rowOf({issueTypes: ['Bug'], screens: {CREATE: '10100'}, extra: 1}));
  rejects('set_project_operation_screens', {...fixtures.set_project_operation_screens, mappings: []});
  rejects('set_project_operation_screens', {dryRun: true, project: 'ABC', issueTypes: ['Bug'], screens: {CREATE: '10100'}});
  rejects('set_project_issue_types', {...fixtures.set_project_issue_types, issueTypes: ['Bug', 'Bug']});
  rejects('set_project_issue_types', {...fixtures.set_project_issue_types, mappings: [{from: 'Story'}]});
  rejects('set_project_issue_types', {...fixtures.set_project_issue_types, mappings: [{from: 'Story', to: 'Task', extra: true}]});
  rejects('set_project_issue_types', {...fixtures.set_project_issue_types, mappings: [{from: 'Story', to: 'Task', statusMappings: [{from: 'Review'}]}]});
  rejects('set_project_issue_types', {...fixtures.set_project_issue_types, mappings: [{from: 'Story', to: 'Task', statusMappings: [{from: 'Review', to: 'Done', extra: true}]}]});
  rejects('create_screen', {...fixtures.create_screen, fields: []});
  rejects('create_screen', {...fixtures.create_screen, name: 'x'.repeat(256)});
  rejects('create_screen', {...fixtures.create_screen, fields: [{typeKey: 'textfield'}]});
  rejects('create_screen', {...fixtures.create_screen, fields: [{name: 'summary', extra: true}]});
  rejects('create_screen', {...fixtures.create_screen, fields: [{name: 'Region', options: [{value: 'West', extra: true}]}]});
  rejects('create_screen', {...fixtures.create_screen, fields: [{name: 'Region', options: [{value: 'West', children: [{value: 'City', children: []}]}]}]});
  rejects('add_field_to_screens', {...fixtures.add_field_to_screens, screens: [{}]});
  rejects('add_field_to_screens', {...fixtures.add_field_to_screens, fieldIds: []});
  rejects('add_field_to_screens', {...fixtures.add_field_to_screens, screens: Array.from({length: 101}, () => ({screenId: 10100}))});
  rejects('remove_field_from_screens', {...fixtures.remove_field_from_screens, screens: [{screenId: 0}]});
  rejects('remove_field_from_screens', {...fixtures.remove_field_from_screens, screens: [{screenId: 1, tab: 'Main'}]});
  rejects('remove_field_from_screens', {...fixtures.remove_field_from_screens, fieldIds: ['labels', 'labels']});
  rejects('remove_field_from_screens', {...fixtures.remove_field_from_screens, fieldIds: Array.from({length: 51}, (_, i) => `customfield_${i + 1}`)});
  rejects('create_custom_fields', {dryRun: true, fields: []});
  rejects('create_custom_fields', {dryRun: true, fields: [{name: 'Region', typeKey: 'textfield'}]});
  rejects('create_custom_fields', {dryRun: true, fields: [{name: 'Region', contexts: [{}]}]});
  rejects('create_custom_fields', {dryRun: true, fields: [{typeKey: 'textfield', contexts: [{}]}]});
  rejects('create_custom_fields', {dryRun: true, fields: [{name: 'Region', typeKey: 'textfield', contexts: []}]});
  const assetsField = {name: 'Server', typeKey: 'com.riadalabs.jira.plugins.insight:rlabs-customfield-default-object',
    assets: {objectSchemaId: 7, filterScopeAql: 'objectType = Server'}, contexts: [{allProjects: true, allIssueTypes: true}]};
  accepts('create_custom_fields', {dryRun: true, fields: [assetsField]});
  for (const assets of [{}, {objectSchemaId: 0}, {objectSchemaId: 7, extra: true}]) {
    rejects('create_custom_fields', {dryRun: true, fields: [{...assetsField, assets}]});
  }
});

test('addition previews accept positioned and legacy targets with closed flat shapes', () => {
  const preview = screens => ({...fixtures.add_field_to_screens, screens});
  accepts('add_field_to_screens', fixtures.add_field_to_screens);
  accepts('add_field_to_screens', preview([
    {screenId: 10100, tab: 'Details', place: 'AFTER', anchor: 'assignee'},
    {screenId: 10101, place: 'BEFORE', anchor: '10123'},
  ]));
  for (const place of ['FIRST', 'LAST'])
    accepts('add_field_to_screens', preview([{screenId: 10100, place}]));
  for (const place of ['UP', 'DOWN', 'MIDDLE', '', null, 1])
    rejects('add_field_to_screens', preview([{screenId: 10100, place}]));
  for (const anchor of ['', null, 123])
    rejects('add_field_to_screens', preview([{screenId: 10100, place: 'AFTER', anchor}]));
  for (const extra of ['fieldId', 'position', 'steps', 'bogus'])
    rejects('add_field_to_screens', preview([{screenId: 10100, place: 'FIRST', [extra]: 'unexpected'}]));
  rejects('add_field_to_screens', preview([null]));
  rejects('add_field_to_screens', preview([{screenId: 10100, placement: {place: 'FIRST'}}]));
  rejects('add_field_to_screens', {...fixtures.add_field_to_screens, place: 'FIRST'});
});

test('layout previews retain flat closed moves and numeric boundaries', () => {
  const preview = (moves, screenId = 10100) => ({dryRun: true, screenId, moves});
  for (const place of ['FIRST', 'LAST', 'BEFORE', 'AFTER', 'UP', 'DOWN']) {
    const move = {fieldId: 'labels', place};
    if (place === 'BEFORE' || place === 'AFTER') move.anchor = 'assignee';
    accepts('edit_screen_layout', preview([move]));
  }
  accepts('edit_screen_layout', preview([{fieldId: 'labels', place: 'UP', steps: 1}], 1));
  accepts('edit_screen_layout', preview([{fieldId: 'labels', place: 'DOWN', steps: 200}]));
  accepts('edit_screen_layout', preview(Array.from({length: 50}, () => ({fieldId: 'labels', place: 'FIRST'}))));
  for (const screenId of [0, -1, 1.5, '10100', null])
    rejects('edit_screen_layout', preview([{fieldId: 'labels', place: 'FIRST'}], screenId));
  rejects('edit_screen_layout', preview([]));
  rejects('edit_screen_layout', preview(Array.from({length: 51}, () => ({fieldId: 'labels', place: 'FIRST'}))));
  rejects('edit_screen_layout', preview([null]));
  rejects('edit_screen_layout', preview([{place: 'FIRST'}]));
  rejects('edit_screen_layout', preview([{fieldId: 'labels'}]));
  rejects('edit_screen_layout', preview([{fieldId: 'labels', place: 'MIDDLE'}]));
  for (const fieldId of ['', null, 123])
    rejects('edit_screen_layout', preview([{fieldId, place: 'FIRST'}]));
  for (const tab of ['', null, 123])
    rejects('edit_screen_layout', preview([{fieldId: 'labels', tab, place: 'FIRST'}]));
  for (const anchor of ['', null, 123])
    rejects('edit_screen_layout', preview([{fieldId: 'labels', place: 'AFTER', anchor}]));
  for (const steps of [0, -1, 201, 1.5, '1', null])
    rejects('edit_screen_layout', preview([{fieldId: 'labels', place: 'UP', steps}]));
  for (const extra of ['screenId', 'project', 'name', 'position', 'bogus'])
    rejects('edit_screen_layout', preview([{fieldId: 'labels', place: 'FIRST', [extra]: 'unexpected'}]));
});

test('layout previews support add/remove alone and reject empty edits and malformed additions', () => {
  const preview = patch => ({dryRun: true, screenId: 10100, ...patch});
  accepts('edit_screen_layout', preview({add: [{fieldId: 'labels'}]}));
  accepts('edit_screen_layout', preview({remove: ['labels']}));
  accepts('edit_screen_layout', preview({add: [{fieldId: 'labels', tab: 'Details', place: 'AFTER', anchor: 'summary'}]}));
  rejects('edit_screen_layout', preview({}));
  rejects('edit_screen_layout', preview({add: [], moves: [], remove: []}));
  for (const add of [[null], [{fieldId: ''}], [{fieldId: 'labels', place: 'UP'}],
    [{fieldId: 'labels', steps: 1}], [{fieldId: 'labels', place: 'MIDDLE'}]])
    rejects('edit_screen_layout', preview({add}));
  for (const remove of [[null], [''], [123], 'labels'])
    rejects('edit_screen_layout', preview({remove}));
});

test('get_field_configurations has two exclusive modes: project[, fields] or kind with id | name[, offset, limit]', () => {
  const tool = 'get_field_configurations';
  accepts(tool, {project: 'ABC'});
  accepts(tool, {project: 'ABC', fields: ['duedate']});
  accepts(tool, {kind: 'CONFIGURATION', id: '10100'});
  accepts(tool, {kind: 'SCHEME', id: 'DEFAULT'});
  accepts(tool, {kind: 'SCHEME', name: 'ABC'});
  accepts(tool, {kind: 'CONFIGURATION', name: 'ABC', offset: 20, limit: 50});
  accepts(tool, {kind: 'CONFIGURATION', id: '10', usageOffset: 20, usageLimit: 100});
  for (const args of [
    {}, {fields: ['duedate']},
    {project: 'ABC', kind: 'SCHEME', id: '1'}, {project: 'ABC', name: 'x'}, {project: 'ABC', offset: 0},
    {fields: ['duedate'], kind: 'SCHEME', id: '1'},
    {kind: 'SCHEME'}, {kind: 'SCHEME', id: '1', name: 'x'}, {id: '1'}, {name: 'x'},
    {kind: 'OTHER', id: '1'}, {kind: 'SCHEME', id: 'abc'}, {kind: 'SCHEME', id: '0'}, {kind: 'SCHEME', id: 'default'},
    {kind: 'SCHEME', id: '1', limit: 5}, {kind: 'SCHEME', id: '1', offset: 0},
    {kind: 'SCHEME', name: 'x', usageOffset: 0}, {kind: 'SCHEME', name: 'x', usageLimit: 5}, {project: 'ABC', usageLimit: 5},
    {kind: 'SCHEME', id: '1', usageLimit: 101}, {kind: 'SCHEME', id: '1', usageLimit: 0}, {kind: 'SCHEME', id: '1', usageOffset: -1},
    {kind: 'SCHEME', name: '   '}, {kind: 'SCHEME', name: ''}, {kind: 'SCHEME', name: 'x', limit: 51},
    {kind: 'SCHEME', name: 'x', limit: 0}, {kind: 'SCHEME', name: 'x', offset: -1}, {kind: 'SCHEME', name: 'x', unknown: 1},
  ]) rejects(tool, args);
  assert.equal(validators.has('get_project_field_configurations'), false, 'the old name is not published');
});

const reads = {
  list_projects: {query: 'ABC', limit: 20},
  list_issue_types: {query: 'Bug', limit: 20},
  list_custom_field_types: {},
  list_assets_schemas: {},
  plan_field_screens: {fieldId: 'customfield_10100', cursor: 'next-page', recommendation: 'RECOMMENDED'},
  get_project_screens: {project: 'ABC'},
  list_project_leads: {query: 'alex'},
  get_resolutions: {query: 'Delivered'},
  get_operation: {operationId: 'operation-1'},
};
for (const [name, args] of Object.entries(reads)) {
  test(`${name} refuses unexpected root arguments`, () => {
    accepts(name, args);
    rejects(name, {...args, unexpected: true});
  });
}

test('get_operation accepts only optional integer waitSeconds from 0 to 20', () => {
  const args = {operationId: 'operation-1'};
  accepts('get_operation', args);
  for (const waitSeconds of [0, 1, 10, 20])
    accepts('get_operation', {...args, waitSeconds});
  for (const waitSeconds of [-1, 21, 1.5, '20', null, true, {}, []])
    rejects('get_operation', {...args, waitSeconds});
  rejects('get_operation', {waitSeconds: 20});
});

test('start_operation accepts apply-only payloads with an optional complete ownership pair', () => {
  const args = {toolName: 'create_project', arguments: {dryRun: false, planId: 'confirmed-plan'}};
  accepts('start_operation', args);
  accepts('start_operation', {...args, sessionId: 'session-1', changeSetId: 'group-1'});
  rejects('start_operation', {...args, sessionId: 'session-1'});
  rejects('start_operation', {...args, changeSetId: 'group-1'});
  rejects('start_operation', {...args, unexpected: true});
  rejects('start_operation', {...args, arguments: {...args.arguments, name: 'Delivery'}});
  rejects('start_operation', {...args, arguments: {...args.arguments, dryRun: true}});
  rejects('start_operation', {...args, arguments: {dryRun: false}});
  rejects('start_operation', {...args, arguments: {planId: 'confirmed-plan'}});
  for (const blank of ['', ' ', '\t\r\n', '\u0000\u001f ']) {
    rejects('start_operation', {...args, arguments: {...args.arguments, planId: blank}});
    rejects('start_operation', {...args, sessionId: blank, changeSetId: 'group-1'});
    rejects('start_operation', {...args, sessionId: 'session-1', changeSetId: blank});
  }
  // Match Java String.trim(), not a broader Unicode whitespace policy.
  accepts('start_operation', {...args, arguments: {...args.arguments, planId: '\u00a0'}, sessionId: '\u00a0', changeSetId: '\u00a0'});
  accepts('start_operation', {...args, arguments: {...args.arguments, planId: ' confirmed-plan '}, sessionId: ' session-1 ', changeSetId: ' group-1 '});
});
