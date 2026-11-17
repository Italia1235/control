import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import Ajv2020 from 'ajv/dist/2020.js';
import AjvDraft7 from 'ajv';

const workflowTools = JSON.parse(await readFile(new URL('../../src/main/resources/llm/workflow-tools.json', import.meta.url), 'utf8'));
const operationTools = JSON.parse(await readFile(new URL('../../src/main/resources/llm/rollback-tools.json', import.meta.url), 'utf8'));
// Required fields may be declared in a parent schema; that is valid JSON Schema, not Ajv's strictRequired lint.
const ajv = new Ajv2020({strict: true, strictRequired: false, allErrors: true});
// The flat configure_workflow schema states unions as `type: [..]` (resolution, screen, roles), so its compilers allow union types.
// They stay strict otherwise; no other tool's schema is compiled with this option.
const configureOptions = {strict: true, strictRequired: false, allErrors: true, allowUnionTypes: true};
const unionAjv = new Ajv2020(configureOptions);
// MCP clients do not all enable RegExp's Unicode flag. Exercise both modes and both schema drafts.
const workflowSchema = workflowTools.find(tool => tool.name === 'configure_workflow').input_schema;
const workflowValidators = [
  ['2020-12/u', unionAjv],
  ['2020-12/no-u', new Ajv2020({...configureOptions, unicodeRegExp: false})],
  ['draft-07/u', new AjvDraft7(configureOptions)],
  ['draft-07/no-u', new AjvDraft7({...configureOptions, unicodeRegExp: false})],
].map(([mode, compiler]) => ({mode, validate: compiler.compile(workflowSchema)}));
const validateStart = ajv.compile(operationTools.find(tool => tool.name === 'start_operation').input_schema);

function preview(transition = {name: 'Continue', from: 'Open', to: 'Working'}) {
  return {dryRun: true, create: {name: 'Delivery', statuses: [
    {name: 'Open', category: 'TO_DO'}, {name: 'Working', category: 'IN_PROGRESS'}, {name: 'Done', category: 'DONE'},
  ], initialStatus: 'Open', transitions: [{key: 'continue', ...transition}]}};
}
function accepts(args) {
  for (const {mode, validate} of workflowValidators) {
    assert.equal(validate(args), true, JSON.stringify({mode, args, errors: validate.errors}));
  }
}
function rejects(args) {
  for (const {mode, validate} of workflowValidators) {
    assert.equal(validate(args), false, `${mode}: public schema accepted ${JSON.stringify(args)}`);
  }
}
const finish = resolution => preview({name: 'Finish', from: 'Working', to: 'Done', resolution});
const reopen = resolution => preview({name: 'Reopen', from: 'Done', to: 'Working', resolution});

test('public workflow calls require a complete explicit preview', () => {
  accepts(preview());
  for (const property of ['name', 'statuses', 'initialStatus', 'transitions']) {
    const args = preview();
    delete args.create[property];
    rejects(args);
  }
  rejects({create: preview().create});
});

test('public workflow calls reject apply mode and preview mixed with a planId', () => {
  rejects({...preview(), dryRun: false});
  rejects({...preview(), planId: 'confirmed-plan'});
  const apply = {dryRun: false, planId: 'confirmed-plan'};
  rejects(apply);
  assert.equal(validateStart({toolName: 'configure_workflow', arguments: apply}), true, JSON.stringify(validateStart.errors));
});

test('SET accepts a nonempty string value; empty and non-string values are rejected', () => {
  accepts(finish({action: 'SET', value: '10000'}));
  // A missing value and a padded or control-character value need oneOf/pattern, which the flat schema no longer has:
  // ConfigureWorkflowServerRefusalsMcpTest#resolutionShapesTheSchemaNoLongerStatesAreRefused refuses them on the server.
  for (const resolution of [{action: 'SET', value: ''}, {action: 'SET', value: null}, {action: 'SET', value: 10000}]) {
    rejects(finish(resolution));
  }
});

test('CLEAR accepts no value; a null value is not a string', () => {
  accepts(reopen({action: 'CLEAR'}));
  // CLEAR with a string value is refused by the server (same test as above), not by the flat schema.
  rejects(reopen({action: 'CLEAR', value: null}));
});

test('omission and null still select the effective default; strings and missing actions do not', () => {
  accepts(preview());
  accepts(preview({name: 'Continue', from: 'Open', to: 'Working', resolution: null}));
  rejects(finish('10000'));
  rejects(finish({value: '10000'}));
  rejects(finish({action: 'set', value: '10000'}));
  rejects(finish({action: 'PRESERVE'}));
});

test('a local source may omit fromAny or explicitly set it false; both fields are typed', () => {
  accepts(preview());
  accepts(preview({name: 'Continue', from: 'Open', fromAny: false, to: 'Working'}));
  // "Exactly one of from and fromAny" is a oneOf in the old schema; it is a server rule now
  // (ConfigureWorkflowServerRefusalsMcpTest#aTransitionNeedsExactlyOneOfFromAndFromAny).
  rejects(preview({name: 'Continue', from: 5, to: 'Working'}));
  rejects(preview({name: 'Continue', from: 'Open', fromAny: 'false', to: 'Working'}));
});

test('a global source accepts fromAny true with a blank from; fromAny must be a boolean', () => {
  accepts(preview({name: 'Reopen', fromAny: true, to: 'Working'}));
  accepts(preview({name: 'Reopen', from: '', fromAny: true, to: 'Working'}));
  // The server uses String.trim().isEmpty(), not JavaScript/Unicode whitespace normalization.
  accepts(preview({name: 'Reopen', from: ' \t\r\n\u0000', fromAny: true, to: 'Working'}));
  // A meaningful from next to fromAny: true is refused by the server (same test as above), not by the flat schema.
  rejects(preview({name: 'Reopen', fromAny: 'true', to: 'Working'}));
});

test('required transition name and target and the closed Resolution shape remain enforced', () => {
  rejects(preview({from: 'Open', to: 'Working'}));
  rejects(preview({name: 'Continue', from: 'Open'}));
  rejects(finish({action: 'SET', value: '10000', resolutionId: '10000'}));
});

test('workflow, status and transition names accept any text the server accepts; the empty name is rejected', () => {
  const setters = [
    (args, value) => { args.create.name = value; },
    (args, value) => { args.create.statuses[0].name = value; },
    (args, value) => { args.create.transitions[0].name = value; },
  ];
  for (const set of setters) {
    for (const value of [' \t\r\nИмя 工作 😀\t ', '\u00a0', '\u2028', '\ue000', '\ufffd', '\u{10ffff}']) {
      const args = preview();
      set(args, value);
      accepts(args);
    }
    // Whitespace-only, control and XML-forbidden names need pattern; the server refuses them
    // (ConfigureWorkflowServerRefusalsMcpTest#blankControlAndXmlForbiddenNamesAreRefused).
    for (const value of ['']) {
      const args = preview();
      set(args, value);
      rejects(args);
    }
  }
});

test('existing name length limits and padded boundary names remain enforced', () => {
  for (const [set, maximum] of [
    [(args, value) => { args.create.name = value; }, 255],
    [(args, value) => { args.create.statuses[0].name = value; }, 60],
    [(args, value) => { args.create.transitions[0].name = value; }, 255],
  ]) {
    for (const value of ['x'.repeat(maximum), ` ${'x'.repeat(maximum - 2)} `]) {
      const args = preview();
      set(args, value);
      accepts(args);
    }
    const args = preview();
    set(args, 'x'.repeat(maximum + 1));
    rejects(args);
  }
});

test('workflow descriptions accept XML whitespace and Unicode; a non-string description is rejected', () => {
  for (const description of ['', ' \t\r\nОписание 工作 😀\u007f\u0085', '\u{10ffff}']) {
    accepts({ ...preview(), create: { ...preview().create, description } });
  }
  // Malformed XML characters need pattern; the server refuses them
  // (ConfigureWorkflowServerRefusalsMcpTest#malformedXmlCharactersInADescriptionAreRefused).
  rejects({ ...preview(), create: { ...preview().create, description: 5 } });
});

test('supplementary-plane and boundary XML characters are accepted in all client modes', () => {
  for (const description of ['\ud7ff', '\ue000', '\ufffd', '\u{10000}', '\u{10ffff}',
    'A\u{10000}\u{10ffff}😀Z']) {
    accepts({ ...preview(), create: { ...preview().create, description } });
  }
  // Broken surrogate sequences are refused by the server (same test as for the description above).
});

test('initial and target status references are nonempty strings', () => {
  for (const field of ['initialStatus', 'to']) {
    // Whitespace-only references need pattern; the server refuses them
    // (ConfigureWorkflowServerRefusalsMcpTest#blankStatusReferencesAreRefusedWithThePath).
    for (const value of ['']) {
      const args = preview();
      if (field === 'initialStatus') args.create.initialStatus = value;
      else args.create.transitions[0].to = value;
      rejects(args);
    }
    for (const value of [' Open\t', '\u00a0', '\u2028', '\u0000Open\u0000']) {
      const args = preview();
      if (field === 'initialStatus') args.create.initialStatus = value;
      else args.create.transitions[0].to = value;
      accepts(args);
    }
    for (const value of [null, 12, true, [], {}]) {
      const args = preview();
      if (field === 'initialStatus') args.create.initialStatus = value;
      else args.create.transitions[0].to = value;
      rejects(args);
    }
  }
});

test('status and transition objects are closed while existing status categories remain optional', () => {
  const existing = preview();
  delete existing.create.statuses[0].category;
  accepts(existing);
  // Status descriptions are validated only when Jira resolves the status as new.
  // Applying XML rules unconditionally would reject ignored values for existing statuses.
  existing.create.statuses[0].description = '\u0000';
  accepts(existing);
  const statusExtra = preview();
  statusExtra.create.statuses[0].unknown = true;
  rejects(statusExtra);
  const transitionExtra = preview();
  transitionExtra.create.transitions[0].unknown = true;
  rejects(transitionExtra);
});

const schemas = Object.fromEntries(workflowTools.map(tool => [tool.name, (tool.name === 'configure_workflow' ? unionAjv : ajv).compile(tool.input_schema)]));
function schemaAccepts(name, args) {
  const check = schemas[name];
  assert.equal(check(args), true, JSON.stringify({name, args, errors: check.errors}));
}
function schemaRejects(name, args) {
  assert.equal(schemas[name](args), false, `${name} accepted ${JSON.stringify(args)}`);
}
const assignment = () => ({dryRun: true, project: 'ABC', workflow: 'Delivery', allIssueTypes: true});
const clone = scope => ({dryRun: true, project: 'ABC', scope});

test('assignment and cloning publish only complete previews, with apply routed through start_operation', () => {
  for (const [name, args] of [
    ['assign_workflow', assignment()],
    ['clone_project_workflows', clone('ALL_WORKFLOWS')],
  ]) {
    schemaAccepts(name, args);
    for (const field of ['dryRun', 'project', name === 'assign_workflow' ? 'workflow' : 'scope']) {
      const incomplete = {...args};
      delete incomplete[field];
      schemaRejects(name, incomplete);
    }
    schemaRejects(name, {...args, dryRun: false});
    schemaRejects(name, {...args, planId: 'confirmed-plan'});
    const apply = {dryRun: false, planId: 'confirmed-plan'};
    schemaRejects(name, apply);
    assert.equal(validateStart({toolName: name, arguments: apply}), true, JSON.stringify(validateStart.errors));
  }
});

test('assignment requires exactly one effective issue-type selection', () => {
  schemaAccepts('assign_workflow', assignment());
  const selected = {...assignment(), issueTypes: ['Bug']};
  delete selected.allIssueTypes;
  schemaAccepts('assign_workflow', selected);
  schemaAccepts('assign_workflow', {...selected, allIssueTypes: false});
  schemaRejects('assign_workflow', {...assignment(), issueTypes: ['Bug']});
  schemaRejects('assign_workflow', {...assignment(), allIssueTypes: false});
  schemaRejects('assign_workflow', {...selected, issueTypes: []});
  delete selected.issueTypes;
  schemaRejects('assign_workflow', selected);
});

test('cloning requires selected workflow names only for SELECTED scope', () => {
  schemaAccepts('clone_project_workflows', {...clone('SELECTED'), workflows: ['Delivery']});
  schemaRejects('clone_project_workflows', clone('SELECTED'));
  schemaRejects('clone_project_workflows', {...clone('SELECTED'), workflows: []});
  for (const scope of ['SCHEME_ONLY', 'ALL_WORKFLOWS']) {
    schemaAccepts('clone_project_workflows', clone(scope));
    schemaRejects('clone_project_workflows', {...clone(scope), workflows: ['Delivery']});
  }
});

test('workflow read tools reject unknown root arguments and preserve supported lookups', () => {
  schemaAccepts('list_statuses', {});
  schemaAccepts('list_statuses', {query: 'Работа', limit: 100});
  schemaAccepts('get_project_workflows', {project: 'ABC'});
  schemaRejects('list_statuses', {unknown: true});
  schemaRejects('get_project_workflows', {project: 'ABC', unknown: true});
});

function structuralResolution(op, resolution, expected) {
  const change = op === 'ADD_TRANSITION'
    ? {op, name:'Reopen', source:{stepId:3}, targetStepId:1}
    : {op, actionId:21};
  if (resolution !== undefined) change.resolution = resolution;
  const args = {dryRun:true, existing:{workflow:'Delivery', version:'ACTIVE', expectedRevision:'read-revision', changes:[change]}};
  for (const {mode, validate} of workflowValidators) {
    assert.equal(validate(args), expected, JSON.stringify({mode, args, errors:validate.errors}));
  }
}

for (const op of ['ADD_TRANSITION', 'UPDATE_TRANSITION']) {
  test(`${op} accepts explicit PRESERVE (no resolution writer), omission and null`, () => {
    // PRESERVE explicitly requests no writer; UPDATE omission leaves the existing rule
    // unchanged, while ADD omission selects the category-based default. The schema
    // permits these distinct inputs; graph/category effects are server-side rules.
    for (const resolution of [undefined, null, {action:'PRESERVE'}, {action:'PRESERVE', value:null}]) {
      structuralResolution(op, resolution, true);
    }
  });

  test(`${op} SET values stay typed; canonical IDs are checked by the server`, () => {
    for (const value of ['1', '10000', '\u00a0', '\u2028', 'a b', '', ' 1', '1 ', '\t1', '1\n']) {
      structuralResolution(op, {action:'SET', value}, true);
    }
    for (const value of [1, false, [], {}]) {
      structuralResolution(op, {action:'SET', value}, false);
    }
    // SET requires a nonblank canonical ID at runtime, not in the flat display schema.
    structuralResolution(op, {action:'SET'}, true);
    structuralResolution(op, {action:'SET', value:null}, true);
  });

  test(`${op} CLEAR and PRESERVE values are typed; ignored values are refused by the server`, () => {
    for (const action of ['CLEAR', 'PRESERVE']) {
      structuralResolution(op, {action}, true);
      structuralResolution(op, {action, value:null}, true);
      for (const value of ['1', '']) structuralResolution(op, {action, value}, true);
      for (const value of [1, false, [], {}]) structuralResolution(op, {action, value}, false);
    }
  });

  test(`${op} rejects malformed and open-ended Resolution input`, () => {
    for (const resolution of [
      '1', [], {}, {value:'1'}, {action:'set', value:'1'}, {action:'UNKNOWN'},
      {action:'SET', value:'1', resolutionId:'1'}, {action:'CLEAR', resolutionId:'1'},
    ]) structuralResolution(op, resolution, false);
  });
}

test('existing structural changes are closed, bounded and support all eight operations', () => {
  assert.equal(workflowTools.some(tool => tool.name === 'edit_workflow'), false);
  const request = changes => ({dryRun: true, existing: {...target(), changes}});
  const changes = [
    {op: 'ADD_STATUS', statusId: '1', clientKey: 'step'},
    {op: 'REPLACE_STATUS', stepId: 2, statusId: '2'},
    {op: 'UPDATE_TRANSITION', actionId: 21, name: 'Renamed', screen: {action: 'CLEAR'}},
    {op: 'ADD_TRANSITION', transitionKey: 'return', name: 'Return', source: {stepId: 2},
      targetClientKey: 'step', resolution: {action: 'CLEAR'}},
    {op: 'REMOVE_TRANSITION', actionId: 22},
    {op: 'SET_INITIAL_STATUS', clientKey: 'step'},
    {op: 'UPDATE_METADATA', description: 'Updated'},
    {op: 'REMOVE_STATUS', stepId: 3},
  ];
  accepts(request(changes));
  accepts(request(Array.from({length: 100}, () => ({op: 'UPDATE_METADATA', description: 'Updated'}))));
  for (const invalid of [null, [], [null], Array.from({length: 101}, () => changes[0]),
    [{op: 'UNKNOWN'}], [{op: 'UPDATE_METADATA', extra: true}],
    [{...changes[3], transitionKey: ''}], [{...changes[3], transitionKey: 'x'.repeat(256)}],
    [{...changes[3], source: {stepId: 2, extra: true}}]]) rejects(request(invalid));
  accepts({...request([changes[3]]), newScreens: [{key: 'form', name: 'Return form', fieldIds: ['assignee']}],
    transitionRules: [{transitionKey: 'return', screen: {action: 'SET', screenKey: 'form'},
      addValidators: [{template: 'required_fields', parameters: {fieldIds: ['assignee']}}]}]});
  rejects({...preview(), create: {...preview().create, changes}});
});

function validatorTool(name) {
  const tool = workflowTools.find(entry => entry.name === name);
  assert.ok(tool, `${name} is not in workflow-tools.json`);
  return tool;
}
const target = () => ({workflow: 'Delivery', version: 'DRAFT', expectedRevision: 'read-revision'});
const existingPreview = rule => ({dryRun: true, existing: target(), transitionRules: [{actionId: 21, ...rule}]});
const requiredRule = (fieldIds = ['assignee', 'customfield_10118']) => ({
  template: 'required_fields', parameters: {fieldIds},
});
const validatorPreview = (validator = requiredRule()) => existingPreview({addValidators: [validator]});
const conditionPreview = (condition = {template: 'group', parameters: {group: 'jira-administrators'}}) =>
  existingPreview({addConditions: {operator: 'AND', rules: [condition]}});
const roleRule = (roles = ['Administrators', '10101'], invert = true) => ({
  template: 'project_role', parameters: {roles, invert},
});
const withoutKey = (args, key) => { const copy = {...args}; delete copy[key]; return copy; };

test('one configure_workflow replaces all three obsolete mutation tools without aliases', () => {
  const names = workflowTools.map(tool => tool.name);
  assert.equal(names.filter(name => name === 'configure_workflow').length, 1);
  for (const name of ['create_workflow', 'configure_workflow_validators', 'configure_workflow_conditions']) {
    assert.equal(names.includes(name), false);
  }
  for (const name of ['get_workflow_validators', 'get_workflow_conditions']) {
    assert.equal(validatorTool(name).annotations.readOnlyHint, true);
  }
  const configure = validatorTool('configure_workflow');
  assert.equal(configure.annotations.readOnlyHint, false);
  assert.equal(configure.annotations.destructiveHint, true);
  assert.deepEqual(configure.input_schema.properties.dryRun.enum, [true]);
  assert.ok(configure.description.includes('start_operation'));
});

test('both workflow rule readers compile in strict mode of both drafts and Unicode modes', () => {
  for (const name of ['get_workflow_validators', 'get_workflow_conditions']) {
    for (const Compiler of [Ajv2020, AjvDraft7]) for (const unicodeRegExp of [true, false]) {
      assert.equal(typeof new Compiler({strict: true, strictRequired: false, allErrors: true, unicodeRegExp})
        .compile(validatorTool(name).input_schema), 'function');
    }
  }
});

for (const name of ['get_workflow_validators', 'get_workflow_conditions']) {
  test(`${name} requires workflow and version and rejects unknown root arguments`, () => {
    schemaAccepts(name, {workflow: 'Delivery', version: 'ACTIVE'});
    schemaAccepts(name, {workflow: 'Delivery', version: 'DRAFT'});
    for (const args of [{workflow: 'Delivery'}, {version: 'ACTIVE'}, {workflow: '', version: 'ACTIVE'},
      {workflow: 'Delivery', version: 'BOTH'}, {workflow: 'Delivery', version: 'ACTIVE', unknown: true}]) schemaRejects(name, args);
  });
}

test('configure_workflow closes create and existing, refuses null blocks and any public apply payload', () => {
  accepts(validatorPreview());
  for (const args of [
    // "Exactly one of create and existing" is a server rule now (ConfigureWorkflowServerRefusalsMcpTest#createAndExistingTogetherOrNeitherAreRefused).
    {...preview(), create: null},
    {dryRun: true, existing: null}, {...validatorPreview(), unknown: true},
    {...preview(), create: {...preview().create, unknown: true}},
    {...validatorPreview(), existing: {...target(), unknown: true}},
    {...validatorPreview(), dryRun: false}, {...validatorPreview(), planId: 'p'}, {dryRun: false, planId: 'p'},
  ]) rejects(args);
  for (const key of ['workflow', 'version', 'expectedRevision']) {
    rejects({...validatorPreview(), existing: withoutKey(target(), key)});
  }
  assert.equal(validateStart({toolName: 'configure_workflow', arguments: {dryRun: false, planId: 'p'}}), true);
});

test('transition keys and actionIds are typed; which of them a rule may carry is a server rule', () => {
  const create = preview();
  accepts({...create, transitionRules: [{transitionKey: 'continue', addValidators: [{template: 'comment_required'}]}]});
  // Create keys address create.transitions; existing keys address ADD_TRANSITION changes.
  // Exactly one address, valid references and retained native actionIds are server rules.
  const missingKey = preview(); delete missingKey.create.transitions[0].key; rejects(missingKey);
  for (const key of ['', null, 1]) {
    const invalid = preview(); invalid.create.transitions[0].key = key; rejects(invalid);
  }
  for (const actionId of ['21', 21.5, -1, 0, null, true]) rejects(existingPreview({actionId}));
  for (const version of ['BOTH', 'active', '', null]) rejects({...validatorPreview(), existing: {...target(), version}});
  for (const key of ['workflow', 'expectedRevision']) for (const value of ['', 5, null]) {
    rejects({...validatorPreview(), existing: {...target(), [key]: value}});
  }
});

test('typed required_fields validators preserve complete preview and field-id boundary constraints', () => {
  accepts(validatorPreview());
  accepts({...validatorPreview(requiredRule(['assignee'])), existing: {...target(), version: 'ACTIVE'}});
  accepts(validatorPreview(requiredRule(Array.from({length: 50}, (_, i) => `customfield_${10000 + i}`))));
  for (const fieldIds of [[], ['assignee', 'assignee'], [''], ['assignee', ''], [1], [null],
    ['assignee', null], 'assignee', Array.from({length: 51}, (_, i) => `customfield_${10000 + i}`)]) {
    rejects(validatorPreview(requiredRule(fieldIds)));
  }
  // A validator without parameters or with {} (no fieldIds) is refused by the server (validatorParametersThatDoNotFitTheTemplateAreRefused).
  for (const parameters of [null, ['assignee']]) {
    rejects(validatorPreview({template: 'required_fields', parameters}));
  }
});

test('comment_required accepts omission or empty parameters and refuses every other parameter shape', () => {
  accepts(validatorPreview({template: 'comment_required'}));
  accepts(validatorPreview({template: 'comment_required', parameters: {}}));
  // comment_required with parameters.fieldIds is refused by the server (validatorParametersThatDoNotFitTheTemplateAreRefused).
  for (const parameters of [{comment: 'x'}, null, [], 'x']) {
    rejects(validatorPreview({template: 'comment_required', parameters}));
  }
});

test('validator templates are closed and cannot carry scripts, custom messages or arbitrary arguments', () => {
  for (const [key, value] of [['className', 'com.acme.Validator'], ['args', {a: 'b'}], ['script', 'return true'],
    ['condition', 'x'], ['message', 'custom text'], ['fieldNames', ['Assignee']], ['validator', {}]]) {
    rejects(validatorPreview({...requiredRule(), [key]: value}));
    rejects(validatorPreview({...requiredRule(), parameters: {fieldIds: ['assignee'], [key]: value}}));
    rejects({...validatorPreview(), [key]: value});
  }
  for (const template of ['simple_scripted', 'Required_Fields', 'Comment_Required', '', null, 1]) {
    rejects(validatorPreview({...requiredRule(), template}));
  }
});

test('AND conditions accept group and project-role templates with canonical names or ids', () => {
  accepts(conditionPreview());
  accepts({...conditionPreview(), existing: {...target(), version: 'ACTIVE'}});
  accepts(conditionPreview(roleRule()));
  accepts(conditionPreview(roleRule(['10002'])));
  accepts(conditionPreview(roleRule([10002])));
  accepts(conditionPreview(roleRule(Array.from({length: 20}, (_, i) => `${10000 + i}`), false)));
  accepts(conditionPreview({template: 'project_role', parameters: {roles: ['Administrators']}}));
  for (const roles of [[], ['10002', '10002'], [''], ['10002', ''], [0], [-1], [null], 'Administrators',
    Array.from({length: 21}, (_, i) => `${10000 + i}`)]) rejects(conditionPreview(roleRule(roles)));
  for (const invert of ['true', 1, null, 'on']) rejects(conditionPreview(roleRule(['10002'], invert)));
  for (const group of ['', 5, null, ['g']]) rejects(conditionPreview({template: 'group', parameters: {group}}));
  for (const parameters of [null, ['g'], undefined]) rejects(conditionPreview(
    parameters === undefined ? {template: 'group'} : {template: 'group', parameters}));
});

test('condition rule trees and parameters are closed and only support additive AND', () => {
  for (const [key, value] of [['className', 'com.acme.Condition'], ['args', {a: 'b'}], ['script', 'return true'],
    ['functionId', 'x'], ['FIELD_FUNCTION_ID', 'x'], ['operator', 'OR'], ['type', 'OR'], ['condition', {}]]) {
    rejects(conditionPreview({template: 'group', parameters: {group: 'g'}, [key]: value}));
    rejects(conditionPreview({template: 'group', parameters: {group: 'g', [key]: value}}));
    rejects(conditionPreview({template: 'project_role', parameters: {roles: ['10002'], [key]: value}}));
  }
  for (const template of ['user', 'Group', 'PROJECT_ROLE', 'groups', 'comment_required', '', null, 1]) {
    rejects(conditionPreview({template, parameters: {group: 'g'}}));
  }
  for (const addConditions of [null, [], {operator: 'OR', rules: []}, {rules: []}, {operator: 'AND'},
    {operator: 'AND', rules: [], unknown: true}]) rejects(existingPreview({addConditions}));
  for (const key of ['removeValidators', 'replaceValidators', 'removeConditions', 'replaceConditions']) {
    rejects(existingPreview({[key]: []}));
  }
});

test('one create preview carries both validators, both AND conditions and one new screen', () => {
  const request = {...preview(), newScreens: [{key: 'form', name: 'Workflow form', fieldIds: ['description', 'comment']}],
    transitionRules: [{transitionKey: 'continue', screen: {action: 'SET', screenKey: 'form'},
      addValidators: [{template: 'comment_required'}, requiredRule(['description'])],
      addConditions: {operator: 'AND', rules: [roleRule(), {template: 'group', parameters: {group: 'jira-administrators'}}]}}]};
  accepts(request);
  const existing = {...request, existing: target(), transitionRules: [{...request.transitionRules[0], actionId: 21}]};
  delete existing.create; delete existing.transitionRules[0].transitionKey;
  accepts(existing);
});

test('omitted and empty additive blocks are accepted without inventing removal semantics', () => {
  accepts({dryRun: true, existing: target()});
  accepts({dryRun: true, existing: target(), transitionRules: []});
  accepts(existingPreview({screen: 'KEEP', addValidators: [], addConditions: {operator: 'AND', rules: []}}));
  for (const key of ['transitionRules', 'newScreens']) rejects({...validatorPreview(), [key]: null});
  rejects(existingPreview({addValidators: null}));
});

test('screen SET and CLEAR are typed and closed; reference combinations are server rules', () => {
  for (const key of ['screenId', 'screenName', 'screenKey']) {
    accepts(existingPreview({screen: {action: 'SET', [key]: '10100'}}));
    for (const value of ['', null, 10100]) rejects(existingPreview({screen: {action: 'SET', [key]: value}}));
  }
  accepts(existingPreview({screen: {action: 'CLEAR'}}));
  // Exactly one SET reference, no CLEAR reference and string KEEP semantics are server rules.
  for (const screen of [null, {action: 'KEEP'}, {action: 'SET', screenId: '10100', extra: true}]) {
    rejects(existingPreview({screen}));
  }
  const screen = {key: 'form', name: 'Form', description: '', fieldIds: ['description', 'comment']};
  accepts({...preview(), newScreens: [screen]});
  for (const key of ['key', 'name', 'fieldIds']) rejects({...preview(), newScreens: [withoutKey(screen, key)]});
  for (const fieldIds of [['description', 'description'], [''], [1], null]) {
    rejects({...preview(), newScreens: [{...screen, fieldIds}]});
  }
  rejects({...preview(), newScreens: [{...screen, unknown: true}]});
});

test('schema limits accept each boundary and reject every one-over-limit request', () => {
  const graph = preview();
  graph.create.transitions = Array.from({length: 100}, (_, i) => ({...graph.create.transitions[0], key: `edge-${i}`}));
  accepts(graph);
  rejects({...graph, create: {...graph.create, transitions: [...graph.create.transitions, {...graph.create.transitions[0], key: 'overflow'}]}});
  const rules = Array.from({length: 100}, (_, i) => ({actionId: i + 1}));
  accepts({dryRun: true, existing: target(), transitionRules: rules});
  rejects({dryRun: true, existing: target(), transitionRules: [...rules, {actionId: 101}]});
  const validators = Array.from({length: 10}, () => ({template: 'comment_required'}));
  accepts(existingPreview({addValidators: validators}));
  rejects(existingPreview({addValidators: [...validators, {template: 'comment_required'}]}));
  const conditions = Array.from({length: 10}, (_, i) => ({template: 'group', parameters: {group: `g-${i}`}}));
  accepts(existingPreview({addConditions: {operator: 'AND', rules: conditions}}));
  rejects(existingPreview({addConditions: {operator: 'AND', rules: [...conditions, conditions[0]]}}));
  const screens = Array.from({length: 10}, (_, i) => ({key: `form-${i}`, name: `Form ${i}`,
    fieldIds: Array.from({length: 50}, (_, j) => `customfield_${10000 + j}`)}));
  accepts({...preview(), newScreens: screens});
  rejects({...preview(), newScreens: [...screens, {...screens[0], key: 'overflow'}]});
  rejects({...preview(), newScreens: [{...screens[0], fieldIds: [...screens[0].fieldIds, 'customfield_99999']}]});
});
