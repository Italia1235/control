# Tool reference

English | [Русский](TOOLS.ru.md)

The contract of each of the 47 MCP tools: inputs, results, error codes and limits. The "preview → confirmation → apply" cycle is described in the [README](README.md#how-changes-work), model behavior rules in [`rules.md`](src/main/resources/llm/rules.md), internals in [howitworks](howitworks/). They are not repeated here.

## General rules

These apply to all writing tools; the sections below name only the differences.

- **Preview.** A domain tool accepts only `dryRun:true` with the full request. A preview writes nothing and on success returns a `planId`. If there is nothing to change (`NO_CHANGE`, `UNCHANGED`, `ALREADY_*`, `EXISTS`) or the request is rejected, there is no plan.
- **Apply.** Only `start_operation` with `toolName` and `arguments:{dryRun:false, planId}`, without preview fields. A direct call of a domain tool with `dryRun:false` is rejected.
- **A plan** is bound to the administrator, the normalized request and a fingerprint of the relevant Jira state; it is valid for a limited time and is applied once. Before the write it is recomputed under a lock; any mismatch gives `PREVIEW_STALE` with no write — a new preview and a new confirmation are needed.
- **Locks** in Control order only Control operations. The Jira UI, REST and other plugins do not respect them.
- **The result** is read back from Jira after the write. `FAILED`, `PARTIAL*`, `UNKNOWN`, `NOT_ATTEMPTED` are not success: Jira may have changed partially. Apply is not atomic; there are no automatic retries and no rollback; a fix is a new preview.
- **Undo.** The MCP catalog has none. The Control journal stores the steps, and for some create operations an internal rollback is available via REST `/rest/control/1.0/changes/rollback` ([Audit](howitworks/audit/Audit-how-it-works.md)); where this is so, the tool's section says it.
- **Reads.** Read-only tools are available to the administrator, write nothing and need no plan.

### Operations and recovery protocol

- Keep the `operationId` from `start_operation`; after reconnecting, read it with `get_operation`. If the start response is lost, repeat the identical `start_operation` with the same `planId`: the existing operation is returned with `alreadyStarted:true`, and no new write begins. Exactly-once is not guaranteed: after a node failure the outcome may stay `UNKNOWN`.
- If the start already returned a final state with a result, no extra read is needed. Otherwise `get_operation` waits up to `waitSeconds` (0–20, default 20). `QUEUED`, `RUNNING`, `CANCEL_REQUESTED` are not an outcome. Do not look up the operation or the created object by name, and do not repeat create to recover.
- `PREVIEW_STALE` with `staleReason:EXPIRED` — the plan expired; with `NOT_FOUND` — the plan is unavailable: a new preview and a new confirmation are needed, even if the request is the same. `CONSUMED` — the plan is already bound to another tool; do not repeat the write (the same tool usually returns the existing operation).
- A rejection for a missing or stale plan happens before the operation is created: the domain tool is not called, and there is no row in the apply log.

## Projects

### `create_project`

Creates a Business or Software project from a supported template, or reads the template catalog. The mode is chosen by the `action` argument; a request without `action` is rejected.

**Catalog: `{action:"list_templates"}`.** A read with no plan. Row: `templateKey`, `projectTypeKey`, `name`, `description`, `supported`; sorted by `projectTypeKey`, then `templateKey`. `supported=true` only if the key and the type reported by Jira match the table; Jira Service Management and third-party templates are `false`. `OK` with empty `templates` — Jira reports no templates; `FAILED`/`JIRA_ERROR` — the catalog was not read, not "there are no templates".

| Type | Template | `templateKey` |
| --- | --- | --- |
| business | Project Management | `com.atlassian.jira-core-project-templates:jira-core-project-management` |
| business | Task Management | `com.atlassian.jira-core-project-templates:jira-core-task-management` |
| business | Process Management | `com.atlassian.jira-core-project-templates:jira-core-process-management` |
| software | Scrum | `com.pyxis.greenhopper.jira:gh-scrum-template` |
| software | Kanban | `com.pyxis.greenhopper.jira:gh-kanban-template` |
| software | Basic | `com.pyxis.greenhopper.jira:basic-software-development-template` |

**Create: `{action:"create", templateKey, dryRun:true, name, key, lead[, issueTypes, newIssueTypes, defaultIssueType]}`.** `templateKey` is a catalog row with `supported=true`; the project type comes from the template. The key is not generated. `lead` is the exact username or userKey of an active user (`list_project_leads`), not the display name. Template missing in Jira — `TEMPLATE_UNAVAILABLE`; key not in the table, or Jira reports another type — `UNSUPPORTED_TEMPLATE`; template disappeared before apply — `PREVIEW_STALE`. The template hook is run by Jira; Control does not model its effects.

Results:
- `CREATED` — the project is created.
- `EXISTS` — a project with the same key, name, lead and type already exists; its template and settings are not checked or changed, `templateKey` is empty.
- `KEY_CONFLICT`, `NAME_CONFLICT`, `LEAD_CONFLICT` — the existing project is not changed; the same key on a project of another type is `KEY_CONFLICT`.

**Issue types.**
- Without `issueTypes` and `newIssueTypes` the project gets the template's own types; they are not known in advance.
- `issueTypes` (existing types, name or id) and `newIssueTypes` (`{key, name, description?, subtask}`) together are the **complete final set**. A definition reuses a type with the same name (case-insensitive) and kind; otherwise a global type is created. A name of a type of another kind, an ambiguous name, duplicate `key`s or names, overlap of `issueTypes` with a definition, a set without a standard type — `FAILED` before the write.
- `defaultIssueType` is a local `key` or a type of the set, standard only. Without it — the first standard type of the set (first `issueTypes`, then `newIssueTypes`); the preview shows the choice in `issueTypeSet.defaultIssueType` and `defaultSource` (`REQUESTED`, `FIRST_STANDARD`).
- An existing project with an explicit set — `KEY_CONFLICT`; use `set_project_issue_types` for it.

The preview shows the project, `issueTypeSet.entries` (`key`, `name`, kind, `WOULD_CREATE` or `EXISTS`, ids of existing ones), the default, `globalImpact` (new types go into Jira's default issue type scheme and are available to all its projects) and `residualRisks`. Which template types will be excluded is known only after the project is created (`assignment.excludedTypes`). Exclusion does not delete a type globally: the project gets its own issue type scheme; shared schemes are not edited.

One operation: project → missing types → set assignment. `CREATED` — the project is created **and** the set is assigned (`issueTypeSet.assignment.state=COMPLETED` with the actual `issueTypes`, `excludedTypes`, `defaultIssueType`, `scheme`). `PARTIALLY_CHANGED` — the project exists (`projectId`, `key`), the rest is not finished:
- `issueTypeSet.entries[].status`: `CREATED`, `EXISTS`, `FAILED`, `UNKNOWN`, `NOT_EXECUTED`; an id with `UNKNOWN` is only observed and must not be used;
- `assignment.state`: `NOT_STARTED` (the project's types untouched), `PARTIALLY_CHANGED` or `UNKNOWN` (details in `assignment.execution`).

The set is not assigned until every type is confirmed. Issues that appeared in an excluded template type are not migrated silently: `MAPPING_REQUIRED`, `set_project_issue_types` with `mappings` is needed. The created project and types stay in Jira; their ids are in `result.report` and `issueTypeSet`.

**Rollback via the log** is possible only for a Business project. For Software it is rejected before deletion: the board and the filter are not owned by the Control log.

**Verification.** Software templates are covered only by unit tests; their keys are taken from Atlassian documentation and are not confirmed on Jira 10.3.24. Task Management and Process Management are verified by preview and unit tests, without real creation.

### `list_project_leads`

Finds active users by a non-empty `query`. Returns `username`, `displayName`, `userKey`, no email. `create_project.lead` takes the exact username or userKey; an ambiguous lead is chosen by the user.

### `list_projects`

Finds projects by key or part of the name: `key`, `name`, at most `limit` (default 20) out of `total`. Issue types are included only if exactly one project is found (`issueTypesIncluded`, `issueTypesTotal`).

## Fields

### `create_custom_fields`

Creates custom fields (text, numbers, dates, lists, users, etc.) with contexts: which projects and issue types. Existence is checked only by the exact names in the request. A field with the same name and type is returned as `EXISTS` and not changed. The field catalog is not dumped in the response; field types are listed by `list_custom_field_types`.

**Context rules** (shared with `create_field_context` and `update_field_contexts`):
- a project (by id; key and name are the same project) belongs to at most one project-scoped context of a field, even with different issue types; at most one global context (`allProjects:true`);
- one project with several types — one context with a common option list; different options require different fields;
- a violation — `FAILED`/`VALIDATION_ERROR` in the preview with the project and the numbers and names of both contexts;
- a global context next to project contexts is allowed but does not apply in their projects, including for unlisted types: Jira picks the project context before the global one. Each such project context gets a warning.

The restriction is a plugin rule: Jira's behavior with several project contexts for one project was not tested.

**Check after the write.** The field is read back; contexts, projects, issue types and options are compared with the plan. `CREATED` — only on a full match. Otherwise `PARTIAL` with `errorCode:CONTEXT_MISMATCH`, the real `customFieldId`, `contexts` as Jira returned them (empty if the read failed), and the list of mismatches in `message`. No cleanup is performed.

Settings of third-party field types are given in the field definition and are the same for all its new contexts. For an existing field (`EXISTS`) they are ignored and listed in the message. All integrations are optional: ordinary fields work without the plugin.

**Assets object** (`com.riadalabs.jira.plugins.insight:rlabs-customfield-default-object`):

```json
"assets": {
  "objectSchemaId": 123,
  "filterScopeAql": "objectType = \"Server\""
}
```

`objectSchemaId` (required) comes from `list_assets_schemas`; `filterScopeAql` is optional, an empty string means no filter. The schema and AQL are checked in the preview and again on apply. `options` are not used for Assets. The settings are written by the Assets service. On compensation the log does not delete a created field with Assets settings; cleanup is manual with Jira's tools. Verified on Assets 8.11.2 / Jira 9.12.2; not tested on Jira 10.3.24.

**Apwide File Field** (`com.apwide.document.file-field:apwide-doc-field`):

```json
"fileField": {
  "allowedFileExtensions": ["pdf", ".docx"],
  "maxFiles": 3,
  "maxFileSizeMb": 5,
  "readonly": false,
  "autoClean": true
}
```

All parameters are optional; without `fileField` the plugin defaults apply (any extensions, 10 files, Jira's upload limit). A dot is added to an extension without one (the plugin itself silently drops such ones); case-insensitive duplicates are rejected. Size is in decimal MB, as in the plugin UI. Verified on File Field 4.0.5 / Jira 10.3.24.

**Table Grid Next Generation** (`tge.cloud:idalko-table-grid-custom-field-key`):

```json
"grid": {
  "columns": [
    {"id": "name", "title": "Name", "type": "string", "required": true},
    {"id": "color", "title": "Color", "type": "list", "options": ["Red", "Green"], "defaultValue": "Green"},
    {"id": "qty", "title": "Qty", "type": "number", "decimals": 2}
  ],
  "searchableColumns": ["name"]
}
```

Column types: `string`, `textarea`, `number`, `integer`, `date`, `datetime`, `checkbox`, `list`, `multiselectlist`, `userlist`, `sequence`. The columns replace the plugin's default columns (Order, Summary, Assignee, Status) in all new contexts; without `grid` the plugin puts them only in the first context. The preview checks id uniqueness, `options` only on lists, `defaultValue` from `options`, existence of `searchableColumns`, then the plugin's own validation. Formulas, attachments, data-source lists, grid behaviors and default rows are not supported. Verified on TGNG 6.5.0 / Jira 10.3.24.

Rollback of File Field and TGNG settings is not implemented; the field itself is logged as usual.

### `list_assets_schemas`

No arguments. Returns `id`, `name`, `description` of the object schemas of the installed Assets. Does not create a schema; if Assets is missing or disabled — an error.

### `list_custom_field_types`

No arguments. Returns the installed custom field types and compatible searchers, including `supportsOptions`.

### `get_field_contexts`

Reads the contexts of one field, without scanning other fields and issues. Input: `field` (exact name, `customfield_N` or `N`), optional `project` (key or exact name; key takes precedence), `contextId` and `includeOptions` (`true` requires `contextId`, otherwise `VALIDATION_ERROR`).

```json
{"field":"customfield_10103","project":"SALES"}
```

- **Response:** `field {id, name, typeKey, typeName, typeClass SYSTEM|KNOWN_PLUGIN|THIRD_PARTY, managed, locked}` and `contexts[]` in ascending id order: scope on both axes (`allProjects`/`projects`, `allIssueTypes`/`issueTypes`), `configs[{configId, issueTypeIds}]` (`null` — all types), `options {count, disabledCount}` (`null` — no options), `defaultValue PRESENT|ABSENT|UNKNOWN`, `pluginSettings NOT_APPLICABLE|PRESENT|ABSENT|UNKNOWN`; for an Assets object field — `assets {objectSchemaId, filterScopeAql}`.
- **Projects:** without `contextId` — up to 50 per context (`projectsComplete:false` when truncated); with `contextId` — the full list. Global scope is shown by flags.
- **`project`** selects global contexts and contexts containing the project; the check uses the full list, not the first 50. This is a filter, not a computation of the effective configuration: counters and projects describe the whole context scope, issue types are not trimmed. No matches — `READ` with `contexts:[]`. A `contextId` that does not belong to the field — `UNKNOWN_CONTEXT`.
- **`includeOptions:true`** adds `optionList {hasOptions, items[{configId, id, value, parentId, disabled, default, position}]}`: a parent, then its children; `position` starts at 0 within its level; `default` is absent if the default value was not read. Only these `id`s are accepted as `optionId` in `update_field_contexts`.
- `managed` — the field is managed by an app; `locked` — the current administrator may not change it (as in the Jira form). A managed field with access level ADMIN: `managed=true`, `locked=false`.
- `defaultValue` of standard types is determined by the stored string: a default referencing a deleted user or version stays `PRESENT`.
- `UNKNOWN` means "could not be read", not "absent". Grid settings are always `UNKNOWN`; Assets and File Field are read.
- **Errors:** `UNKNOWN_FIELD`, `AMBIGUOUS_FIELD` (+`candidates` with id and type), `UNKNOWN_CONTEXT`, `UNKNOWN_PROJECT`, `VALIDATION_ERROR` (archived project, ambiguous project name, `includeOptions` without `contextId`). Project errors contain `suggestions`. Arguments of the wrong type are rejected before Jira is read.

### `update_field_contexts`

Changes, on existing contexts of one field, the scope (projects × issue types), the Assets object settings and/or the options. Input: the exact `fieldId:"customfield_N"` and up to 10 `contexts` from `get_field_contexts`. Does not create new contexts (that is `create_field_context`).

**Scope.** Each `contextId` needs both axes in full: `projects:[keys or names]` or `allProjects:true`, and `issueTypes:[ids or names]` or `allIssueTypes:true`. One axis, empty lists, both forms at once, `false` instead of global — `VALIDATION_ERROR`.
- The whole final set of contexts, including unchanged ones, is checked by the rules from `create_custom_fields`. `FIELD_LOCKED` mirrors Jira's permissions; `MULTIPLE_CONFIGS` — the context has several different FieldConfigs.
- Preview: `WOULD_UPDATE` with a plan or `UNCHANGED` without a plan; `before`/`after`, `preservedConfigIds`, changes of projects, types and combinations, warnings, `unchangedContexts`. In combinations, projects are keys, types are ids, global is `*`; at most 50 rows (`combinationsComplete`). With `countUnit:SYMBOLIC_COMBINATIONS` the counter counts symbolic rows, not projects and issues.
- Preserved: FieldConfigs, options (except those named in `options`), defaults and readable Assets/File Field settings (except those passed in `assets`). For Grid and third-party types — `PLUGIN_SETTINGS_UNVERIFIED`: preservation of their settings cannot be checked.
- Narrowing does not delete issue values but may hide them from the UI and search. Jira gets a reindex-required notification; the reindex itself is not started; `notificationDisplayed:false` — the notification could not be delivered, the scope is changed anyway.
- A change of scope, options, default or readable plugin settings after the preview — `PREVIEW_STALE`.

**Assets.** For an Assets object field you can pass `assets {objectSchemaId, filterScopeAql}` without axes (only settings change) or together with both axes (one plan). `assets` on a field of another type, a nonexistent schema or invalid AQL — `VALIDATION_ERROR`. The preview and the result show `contexts[].assets {before, after}`. The saved values must match the approved ones, and other Assets configuration properties must not change; otherwise `PARTIAL` (or `FAILED` if nothing changed).

**Options** (select, radio buttons, checkboxes, multi-select, cascading select): `contexts[].options` — alone or together with the axes and `assets` in one plan.
- `add [{value, parentOptionId?}]` — a new enabled option at the end of its level; a child only under an existing cascading select parent.
- `rename [{optionId, value}]`, `disable [optionId]`, `enable [optionId]`.
- `order [{parentOptionId?, optionIds}]` — all existing options of the level, including disabled ones, in the new order; new ones go after them in `add` order.
- `optionId` — only from `get_field_contexts` (`contextId` + `includeOptions:true`). Values are trimmed, up to 255 characters.
- There is no deletion: an option is removed from selection by disabling it; issue values are kept. Disabling the default is allowed (with a warning); the default itself is not changed. A cascading select parent can be disabled only together with all its enabled children; under a disabled parent a child cannot be added or enabled.
- Write order: add → rename → enable/disable → order. Issue values are not read or written; no reindex is needed.
- Preview: each context has `options {rows, unchanged, warnings}` — "before → after" (`changes`: `RENAMED`, `DISABLED`, `ENABLED`, `MOVED`, `ADDED`; value, `disabled`, `position`, `default` mark). For a renamed or disabled option — `usage {status COUNT|UNKNOWN, issues|reason}`: the number of issues by option id, preview only, total budget 10 seconds; a failure is `UNKNOWN` with a reason. The number is not part of the plan fingerprint. Operations with no effect are marked `unchanged`; if all are such — `OPTIONS_NO_CHANGE`. Warnings: `RENAME_CHANGES_ALL_ISSUES`, `DISABLE_KEEPS_ISSUE_VALUES`, `DEFAULT_OPTION_DISABLED`.
- Default not read — `JIRA_ERROR` without a plan (on apply — `PREVIEW_STALE`).
- Rejections without a plan: `OPTION_NOT_IN_CONTEXT`, `OPTION_DUPLICATE` (in the final level, case-insensitive, including disabled ones; swapping values and changing case are allowed), `OPTION_VALUE_INVALID`, `OPTION_ORDER_INVALID`, `OPTION_CONFLICT` (one option in both `disable` and `enable`, or a repeated id), `OPTIONS_NOT_SUPPORTED` (field not of an option type, Assets, plugin), `OPTION_PARENT_HAS_ENABLED_CHILDREN`, `OPTION_PARENT_DISABLED`, `OPTIONS_NO_CHANGE`, `OPTIONS_LIMIT`.
- `OPTIONS_LIMIT`: 100 top-level options, 100 children per parent, 500 per context, 2000 named options per request (and in one `order`). The first three are checked only on `add` to the corresponding level or context: a context already over the limit allows `rename`/`enable`/`disable`/`order`.
- After the write the options are compared with the approved outcome (existing ones by id: value, parent, disabled, order; new ones by value, parent and place; the rest unchanged); the default and plugin settings must not change. Nothing written — `FAILED`, written partially — `PARTIAL`. Options are not deleted either on failure or on apply.

There is no undo. The previous state is restored by a new confirmed request based on `before` from the preview.

### `create_field_context`

Creates exactly one **new** context of an existing field. Existing contexts are changed by `update_field_contexts`; `create_custom_fields` returns `EXISTS` for an existing field.

```json
{"dryRun":true,"fieldId":"customfield_10500","name":"QA: QABUG bugs","description":"Bug family",
 "projects":["QABUG"],"issueTypes":["Bug","Sub-bug","Sub-task"]}
```

- Exactly one form per axis: `projects` or `allProjects:true`, `issueTypes` or `allIssueTypes:true`. An empty list, both forms, `false` instead of global, a missing axis — `VALIDATION_ERROR`; there is no implicit global context. Limits: 100 projects, 50 types, name and description up to 255 characters.
- The final set is checked by the rules from `create_custom_fields`: `PROJECT_IN_OTHER_CONTEXT`, `GLOBAL_CONTEXT_EXISTS`; a taken name — `CONTEXT_NAME_EXISTS`. The conflicting context and project are in `conflict`; a conflict is not resolved by widening another context. Other errors: `UNKNOWN_FIELD`, `FIELD_LOCKED`, `UNKNOWN_PROJECT`, `UNKNOWN_ISSUE_TYPE` (with suggestions), `UNSUPPORTED_FIELD_TYPE`, `JIRA_ERROR`.
- **Preview** (`WOULD_CREATE`): field and type, name, description and scope of the new context, `unchangedContexts`, `effectiveChange`, `settings`, `warnings`.
  - A project context overrides the global one for **all** types of its project. `configSwitches` and `OVERRIDES_GLOBAL_CONTEXT` show combinations moving from the global config to the new one; `lostApplicability` and `GLOBAL_APPLICABILITY_REMOVED` — types outside the new context that lose the field; `existingBehaviorChanged` accounts for both effects. Issue values are not deleted, but applicability, visibility and search may change.
  - For a new global context, `keptByExistingContexts` shows combinations that stay with project contexts; `newlyApplicableExact:false` means an upper estimate. Counters with `*` are symbolic.
- **Settings.** The new context gets one new FieldConfig with Jira's stock initial configuration; options, defaults, user filters and other settings of existing contexts are **not copied**. Supported types: text, textarea, date picker, date time picker, number, URL, labels, user/multi user picker, group/multi group picker, project picker, version/multi version picker, and Assets object with its required `assets {objectSchemaId, filterScopeAql}` (checked as in `update_field_contexts`, result in `settings.pluginSettings.assets`). Lists, File, Grid, other third-party and unknown types, Assets without `assets` or with the plugin disabled — `UNSUPPORTED_FIELD_TYPE`.
- **Result.** Existing contexts, screens, field configurations, workflows and issue values are not changed. `CREATED` — after read back: name, description, scope, one new FieldConfig without options, saved defaults or extra settings (for Assets — the approved schema/AQL), existing contexts unchanged. Otherwise `PARTIAL`; an error inside Jira's write also gives `PARTIAL`, not "nothing written". `FAILED` — a rejection before the write with a read-back unchanged state. A known `contextId` is returned in the result and `report` even without confirmation; `actual:null` — the scope is not confirmed.
- There is no undo, internal rollback included: Jira provides no safe context deletion. Delete manually after checking issue values and the return of projects to the global context.
- Jira gets a reindex-required notification (`reindexNotificationDisplayed`); the reindex itself is not started.

### `add_field_to_screens`

Adds **each** of several fields to **each** of several screens: custom and system (`assignee`, `reporter`, `priority`, `duedate`, `labels`, `components`, etc.). Fields with different screen sets — separate calls. For one screen use `edit_screen_layout`: adding, moving and removing on it is one plan. Screens for a custom field are found by `plan_field_screens`; for system fields the `screenId` comes from `get_project_screens` or from the user.

A `screens[]` item: `screenId`, optional `tab`, `place` (`FIRST`, `LAST`, `BEFORE`, `AFTER`) and `anchor`.
- Without `place` the field goes last on the chosen (or first) tab, as with `LAST`.
- `BEFORE`/`AFTER` require `anchor` — the exact `fieldId` on the target tab from `get_screen_contents` (not the name; a number becomes `customfield_N`). `anchor` with `FIRST`/`LAST`, its absence with `BEFORE`/`AFTER`, and also `UP`/`DOWN` — `VALIDATION_ERROR`.
- Several fields with one `AFTER`/`FIRST` go in a row in `fieldIds` order.
- Anchor not on the tab — `FAILED`/`UNKNOWN_ANCHOR` for that screen only. Field already on the screen (on any tab) — `ALREADY_PRESENT`, not moved.
- Tab positions with gaps are first renumbered 0..n-1 without changing the order. After the write the screen is read back: the new field is in place, the others in the previous order with the previous `shownWhenEmpty`. The new row gets `shownWhenEmpty:false`.
- A change of the chosen screens' layout after the preview — `PREVIEW_STALE`.

**Failures.** Screens already processed are not rolled back. An unconfirmed write (including a mismatch on read back) — `UNKNOWN`/`JIRA_ERROR` for the screen: the field may have been added. A failure before the write — `FAILED`/`JIRA_ERROR` for that screen only; the remaining pairs — `NOT_ATTEMPTED`. Overall status in Audit: `ADDED` next to `ALREADY_PRESENT` — `MIXED`; success next to `UNKNOWN`/`FAILED`/`NOT_ATTEMPTED` — `PARTIAL`; no successes — `UNKNOWN` or `FAILED`.

### `set_field_required`

Sets the required flag of fields by `rows[]` in one project's field configuration scheme: one plan for the whole batch.

```json
{"dryRun":true,"project":"PAY","rows":[
 {"fields":["customfield_10100"],"required":true,"issueTypes":["Payment","Procurement"]},
 {"fields":["customfield_10101","duedate"],"required":false,"allIssueTypes":true}]}
```

- `rows` 1–20; a row: `fields` 1–10, an explicit `required`, `issueTypes` or `allIssueTypes:true`. A field of one issue type is in exactly one row, otherwise `ROW_CONFLICT` for the type/field pair.
- A custom field is an exact name, `customfield_N` or a number; a system field is a Jira id (`priority`, `duedate`, `components`, `fixVersions`, `labels`, `assignee`…). A name and an id of the same field are a duplicate.
- Any unknown, ambiguous or locked field rejects the whole batch. `fieldErrors[]` lists every problem (`selector`, `errorCode`, `message`, candidates); the top-level `errorCode` is the first error. Codes: `UNKNOWN_FIELD`, `AMBIGUOUS_FIELD`, `LOCKED_FIELD` (fields required by Jira, e.g. `summary`, `issuetype`, cannot be made optional), `FIELD_REQUIRED_UNSUPPORTED`, `ROW_CONFLICT`.

**The preview** shows the effective scheme, the configuration of each type, the status of each pair in `targets[].fields[]`, types moving to the copy and staying on the source, and other affected projects. Batch statuses: `WOULD_CHANGE_REQUIREMENTS` / `REQUIREMENTS_CHANGED` / `ALREADY_AS_REQUESTED` (no plan), `PARTIALLY_CHANGED`, `FAILED`. Pair statuses: `WOULD_MAKE_REQUIRED` / `MADE_REQUIRED` / `ALREADY_REQUIRED`, `WOULD_MAKE_OPTIONAL` / `MADE_OPTIONAL` / `ALREADY_OPTIONAL`. Works with an explicit project scheme and with Jira's default scheme. Making a field optional does not change issues; workflow validators and app settings are not touched.

**Configuration copies.** The required flag is stored in a Field Configuration, not in the issue type. Final configurations are computed over the whole batch:
- selected types with the same outcome share one copy of the source configuration (`newFieldConfigurationName`, e.g. `PAY: Shared fields (Task)`); different outcomes get one copy per outcome;
- if the source configuration has no unselected types, it is edited in place;
- unchanged and unselected types stay on the source; a source without assignments is not deleted;
- copies require a scheme owned only by this project, otherwise `SHARED_FIELD_CONFIGURATION_SCHEME` — first run `clone_project_field_configurations`.

**Attachment.** The system field `attachment` cannot be made required through a Field Configuration: `required:true` gives `FIELD_REQUIRED_UNSUPPORTED` and rejects the whole batch. This is a Jira limitation; a workflow validator is a separate mechanism. A custom field of the same name and `required:false` are not affected.

**CREATE check (`required:true`).** For each type the effective CREATE screen is taken (the `CREATE` row of the Screen Scheme, otherwise `DEFAULT`) and checked: the field is in the project and type context and in the Field Configuration (`FIELD_NOT_APPLICABLE`), not hidden (`FIELD_HIDDEN`), present on this screen (`FIELD_NOT_ON_CREATE_SCREEN`; EDIT/VIEW do not count, position does not matter). All problem pairs go at once into `fieldErrors[]`. A field that is already required but unavailable on create is also rejected. Screens or scheme not read — `JIRA_ERROR`. Order of work: contexts → screen layout → screen assignment → required flag.

The fingerprint includes the fields, their contexts, configurations, scheme rows, CREATE screens and the presence of fields on them. The limit of 10 fields per row caps the result at 500 field/type pairs with 50 types.

**Failure.** `partialEffect` lists each `REQUIRED_FLAG` (`APPLIED`, `UNKNOWN`, `NOT_STARTED` by `fieldId`), copies and mappings, and separately what was compensated. After a failure a created copy is deleted only if nothing references it, and a scheme row is reverted only while it points to this operation's copy; otherwise they are in `leftBehind`. Others' changes are not overwritten (`leftBehind`, `UNKNOWN`). A failure of the very first write also gives `partialEffect`: the flag is `UNKNOWN`, the rest is `notStarted`.

### `get_field_configurations`

Two mutually exclusive modes.

**Project `{project[, fields]}`.** The project's field configuration scheme (or Jira's default scheme), the `DEFAULT` row (`defaultMapping`), the effective configuration of each project type (`explicit:false` — via the `DEFAULT` row), rows for types outside the project (`otherEntities`), each used configuration with other projects and the number of schemes. `fields` (up to 50) adds the flags `required`, `hidden`, `present` in each configuration and `locked` for fields required by Jira. A scheme is assigned to a project, not to a type: "for Task" means the `Task` row of the project's scheme.

**Search `{kind, id | name[, offset, limit]}`.** Field Configurations (`kind=CONFIGURATION`) or schemes (`kind=SCHEME`), including unused ones.
- `id` — a number or `DEFAULT`; not found — `FAILED` `UNKNOWN_FIELD_CONFIGURATION` / `UNKNOWN_SCHEME`.
- `name` — a case-insensitive substring; `limit` 1–50 (20), `offset` from `nextOffset` (none — last page), `total`. Identical names are separate candidates with ids.
- `usage` on each item. Configuration: `links[]` (scheme and "type → configuration" row; `defaultRow` — the `DEFAULT` row; `fallback` — the scheme has no `DEFAULT` row, and its types without their own row get the Default Field Configuration), `total`, `schemesTotal`. Scheme: `projects[]`, `total`.
- `usage.status=READ` does not yet mean all links are in the response: with `truncated=true` read further with `kind`+`id`+`usageOffset=nextUsageOffset` (`usageLimit` up to 100; in name search — the first 20). `UNAVAILABLE` means "unknown", not "unused". An object is unused only if `READ`, not truncated and `total=0`.

```json
{"project":"PAY","fields":["duedate"]}
{"kind":"CONFIGURATION","id":"10100"}
{"kind":"SCHEME","name":"PAY","limit":20,"offset":20}
```

### `create_field_configuration`

Creates a Field Configuration as an exact copy of an existing one (`copyFrom`: id or `DEFAULT`, default `DEFAULT`): required/hidden flags, descriptions, renderers. The new configuration is used by no scheme, so it affects nothing until a scheme maps a type to it. The name is unique case-insensitively; a taken one — `NAME_TAKEN` with a free variant in `suggestions`.

The id of the created configuration is `result.fieldConfiguration.id` (and `result.report.created`, `kind: FIELD_CONFIGURATION`); `create_field_configuration_scheme` accepts it. If the outcome is unknown, read the operation by `operationId`: a repeated preview with the same name returns `NAME_TAKEN` only if the object is already created.

### `create_field_configuration_scheme`

Creates a Field Configuration Scheme from `issueType → fieldConfiguration` rows. `issueType` is a type name or id, or `DEFAULT` (the required row for all other types); `fieldConfiguration` is an id or `DEFAULT`. The scheme is created without a project (`assigned:false`); `assign_field_configuration_scheme` assigns it. The name is unique, including the built-in "Default Field Configuration Scheme".

The scheme id is `result.scheme.id` (and `result.report.created`, `kind: FIELD_CONFIGURATION_SCHEME`). Configurations used are in `result.report.reused`; what each row references is `result.mappings[].fieldConfiguration.id`.

### `assign_field_configuration_scheme`

Assigns an existing field configuration scheme to a project (`scheme`: id) or returns it to Jira's default scheme (`DEFAULT`). Issue data does not change, but from then on issues are created and edited under the new rules. The preview lists types whose configuration changes and fields with changed required/hidden (custom ones only within context; up to 50 per type, `fieldsTotal`/`truncated`). Warnings: `NEWLY_REQUIRED`, `NEWLY_HIDDEN`, `SHARED_SCHEME` (other projects use the scheme), `SCHEME_ORPHANED` (the old scheme is left without projects and is not deleted). An already assigned scheme — `ALREADY_ASSIGNED`.

### `set_field_configuration_mappings`

A patch of an existing scheme's rows: which configuration an issue type gets. Listed rows are set, the rest are not touched. `fieldConfiguration`: id, `DEFAULT` or `INHERIT` (delete the type's row — it moves to the `DEFAULT` row; that row itself cannot be deleted). Jira's default scheme cannot be changed — `DEFAULT_SCHEME`. The change applies to all projects of the scheme: the preview shows rows before/after, `projectsTotal` and affected projects with types and fields (up to 50). Warnings: `SHARED_SCHEME`, `NEWLY_REQUIRED`, `NEWLY_HIDDEN`. No changes — `NO_CHANGE`.

### `clone_project_field_configurations`

Gives a project an independent copy of its current Field Configuration Scheme and of each Field Configuration it uses: type mappings, field order, visibility, required flags, descriptions, renderers. For Jira's default scheme explicit copies are created. Names contain the project key; on conflict, a `vN` suffix. Copies are created first, then the project is switched to the new scheme; issues do not change. If the scheme and all configurations are already used only by this project — `ALREADY_SEPARATE`.

## Screens

### `get_project_screens`

Reads the complete effective screen hierarchy of one project: Issue Type Screen Scheme → the Screen Scheme of each issue type → CREATE/EDIT/VIEW screens, plus the other projects that use these objects. Screen tabs and fields are read by `get_screen_contents`.

### `get_screen_contents`

Reads one screen: `{"screenId":123}`. Take the id from `get_project_screens`: screen names are not unique. Screen usage by projects is not read here.

- Response: `READ`, `screen {id, name, description}`, full `totalTabs`/`totalFields`, `tabs [{id, name, position, fieldCount, fields}]` in actual `position` order.
- Field: `layoutItemId`, `fieldId`, `name`, `kind` (`SYSTEM`, `CUSTOM`, `UNKNOWN`), `position`, `shownWhenEmpty`. An unresolvable field stays in the response with `UNKNOWN` and `name:null`.
- A page holds up to 200 fields and empty tabs. A split tab repeats its metadata and full `fieldCount`. With `nextCursor`, continue with the same `screenId` until `complete:true`. The cursor is bound to a hash of the whole screen: on "Screen contents changed", start over without a cursor.
- Unknown id — `FAILED`/`SCREEN_NOT_FOUND`.
- A field on a screen does not yet mean it is available to an issue type: contexts, Field Configuration and permissions matter.

### `plan_field_screens`

Finds CREATE/EDIT/VIEW screens from the actual contexts of a custom field (projects and issue types). Shows tabs, field presence, sharing and a rating: `RECOMMENDED`, `CAUTION`, `NOT_RECOMMENDED`, `ALREADY_PRESENT`. Changes nothing. `total` and `summary` of the first page describe all candidates; read further `nextCursor` pages only for the screens of the current `add_field_to_screens` preview.

### `create_screen`

Creates a standalone screen with fields, not attached to a project or scheme. One tab, `Field Tab`. Existing system and custom fields are reused; missing custom fields are created in a global context after an explicit type choice (`MISSING_TYPE` — pick a type from `list_custom_field_types`). If a created field does not match the plan on read back (`CONTEXT_MISMATCH`), the screen is not created, and the error names the field's id, name and mismatch.

### `edit_screen_layout`

One layout edit of **one** screen: add existing fields, move them within a tab and between tabs, remove rows. Fields, tabs and issue data are not created.

```json
{"dryRun":true,"screenId":123,
 "add":[{"fieldId":"customfield_104","place":"LAST"}],
 "moves":[{"fieldId":"customfield_103","place":"AFTER","anchor":"summary"}],
 "remove":["customfield_102"]}
```

- At least one of `add`, `moves`, `remove`; 1–50 actions in total. `fieldId` is a system id or `customfield_N`/`N`, not a name. Before the preview, read all pages of `get_screen_contents`.
- Stages `remove` → `add` → `moves`, each sees the result of the previous one. Duplicates, one field in several lists, an anchor on a field being removed — `VALIDATION_ERROR` with `validationErrors[]`. Set a new field's position in `add`, not in `moves`.
- `add`: `tab` — tab id or name (first tab by default); `place` — `FIRST`, `LAST` (default), `BEFORE`, `AFTER` (the last two with `anchor`). An already placed field — `ALREADY_PRESENT`, not moved. The field's context is not widened (`FIELD_CONTEXT_UNCHANGED`); to pick screens by context, use `plan_field_screens`.
- `moves`: without `tab` the field stays on its tab; `place` also accepts `UP`/`DOWN` with `steps` 1–200 (default 1), without `tab`/`anchor`. Going past the edge — `CLAMPED`.
- `remove`: a missing field — `NOT_PRESENT`; shows the former tab, position, neighbors and warnings `HIDES_VALUES`, `EMPTY_TAB`, `REQUIRED_ON_CREATE` — as in `remove_field_from_screens`. `summary` on a CREATE screen is protected.
- Preview: `WOULD_EDIT`; `add[]`, `moves[]`, `remove[]` with statuses; `tabs[]` with `before`/`after` (±5 windows for long tabs, `truncated:true`), impact and warnings for a shared/default screen. An outcome with no changes yields no plan.
- Fingerprint — the whole screen with positions and the contexts of the fields being added. Write — one pass of the final layout, then a read back of the whole screen.
- Outcome: `EDITED`, `PARTIAL`, `RESTORED`, `FAILED`, `UNKNOWN`, actual layout in `actual[]`. On a failed move, one attempt to put the field back is allowed; `RESTORED` — the whole screen matched `before`. `UNKNOWN` — read back failed.
- Do not build two plans for one screen before applying the first: they make each other stale.

### `remove_field_from_screens`

Removes each named field from each selected screen: up to 50 fields, 100 screens and 500 pairs, `fieldIds` without repeats. For one screen — `edit_screen_layout`. Only the layout row is removed: the field, issue values and search remain. Workflow transition screens are not traversed. "Remove from a project" means that project's CREATE/EDIT/VIEW screens, not all Jira screens.

```json
{"dryRun":true,"fieldIds":["customfield_10100"],"screens":[{"screenId":10200}]}
```

- Preview: `WOULD_REMOVE` (with a plan), `NOT_PRESENT` (the field is nowhere) or `FAILED`. `results[]` per screen × field pair: `status` (`WOULD_REMOVE`, `NOT_PRESENT`, `FAILED`, `NOT_ATTEMPTED`), `tab`/`tabId`, former `position`, neighbors `before`/`after`, `warnings`. `screens[]`: CREATE/EDIT/VIEW operations, projects, `defaultScreen`.
- Warnings: `SHARED_SCREEN` (number of projects, including the selected one), `DEFAULT_SCREEN`, `HIDES_VALUES` (the field disappears from view, values remain and are searchable), `EMPTY_TAB`, `REQUIRED_ON_CREATE`.
- `REQUIRED_ON_CREATE` is a warning, not a refusal: the field is required for project/type pairs whose CREATE screens are affected. `requiredPairs` — number of pairs, `requiredProjects[]` — up to 50 projects with types (`requiredProjectsTruncated`). While the field is required, issue creation in those pairs will stop working; the required flag can be cleared with `set_field_required` and `required:false` before removal.
- `PROTECTED_FIELD` — `summary` on a CREATE screen: that row is `FAILED`, the rest are planned. Other codes: `UNKNOWN_FIELD` (whole call), `UNKNOWN_SCREEN` (that screen's rows), `VALIDATION_ERROR`, `JIRA_ERROR`.
- Screens are independent; the row index is checked against `fieldId` before each removal. Outcome: `REMOVED`, `PARTIAL`, `FAILED`, `UNKNOWN`; per pair — `REMOVED`, `FAILED`, `NOT_ATTEMPTED`, `UNKNOWN`. `actual[]` — the read-back tab order (`layoutItemId`, `fieldId`, `position`, `shownWhenEmpty`).
- To put a field back: `edit_screen_layout.add` on the former tab with `place:AFTER` and `anchor` = the `before` neighbor from the response (or `FIRST`). The returned row gets `shownWhenEmpty:false`.

### `clone_project_screens`

Detaches one project from shared screen configuration. The top Issue Type Screen Scheme is copied only if it is shared or Jira's default; a private non-default one keeps its id and name, and its rows are switched to the copies.

- `scope` of child Screen Schemes: `SCHEME_ONLY`, `ALL`, `SELECTED`; with `SELECTED`, unselected types keep their original schemes, including default.
- `copyScreens` — whether to copy the screens themselves.
- Preview and result show `COPY` or `KEEP` for each level. `otherProjects` excludes the target project and refers to the specific object.
- All selected levels already private and the top scheme non-default — `ALREADY_SEPARATE`.
- A failure after writing started — `PARTIALLY_CLONED`/`JIRA_ERROR`: the created copies (`issueTypeScreenScheme`, `screenSchemes`, `screens` with `newId`), the failed stage and the call with an unconfirmed outcome (the object may have been created). Nothing created — `FAILED`/`JIRA_ERROR`. The project switch is confirmed separately; if it is not confirmed, read the project's scheme.

### `set_project_operation_screens`

Assigns existing screens to the `CREATE`, `EDIT`, `VIEW` operations of one project for any number of issue types in one plan. Screens are not created or copied. Workflow transition screens are assigned by `configure_workflow`.

```json
{"dryRun":true,"project":"<key>","mappings":[
 {"issueTypes":["Task"],"screens":{"CREATE":"<screenId>","EDIT":"<screenId>"}},
 {"issueTypes":["Bug","Story"],"screens":{"CREATE":"<screenId>"}},
 {"allIssueTypes":true,"screens":{"VIEW":"<screenId>"}}]}
```

- `mappings[]` 1–50: a scope (`issueTypes` from the project's current types **or** `allIssueTypes:true`) and a partial `screens` patch; unspecified operations keep their explicit screen or `DEFAULT`.
- `issueTypes` changes only the named types; a type without its own row gets an explicit row. `allIssueTypes:true` changes the `DEFAULT` row and all explicit rows of the project's scheme (including types not in the project), and creates no new rows.
- Contradictions (one type and operation with different screens, including `allIssueTypes` versus a named row), an unknown type or screen — `VALIDATION_ERROR` for the whole batch. Identical assignments are allowed.
- The final matrix is computed from one source state. A new child Screen Scheme is created for each unique pair "source scheme + changed operations"; source child schemes, screens and other projects are not changed, nothing is deleted.
- `topScheme.mode` — by Jira's actual links, not by name:
  - `UPDATE_PRIVATE` — a non-default scheme used only by this project: rows are edited in place, id, name and link are kept;
  - `COPY_SHARED` (other projects use the scheme or exclusivity is not proven) and `COPY_DEFAULT` (Jira's default scheme): one private scheme is created and the project is switched to it; the next batch will go as `UPDATE_PRIVATE`;
  - `NO_CHANGE` — the screens already match: `ALREADY_ASSIGNED`.
  
  In the preview, `topScheme` contains `mode`, `reason`, source, `isDefault`, `otherProjects` (up to 10) and only the changed rows. The mode and all scheme links, including archived projects, are part of the fingerprint.
- Response: `issueTypes[]` — for each project type, `operations[].before/after`, `selected`, `explicitMapping`, `changed`, scheme before/after; `screenSchemes[]` — new child schemes (`appliesTo`, `newId`); `warnings` (impact on `DEFAULT` and on types without their own row); `matrixSource` (`PLANNED` in the preview, `READ_BACK` after the write); `assignedSchemeId`.
- Failure: `PARTIALLY_ASSIGNED` (something was created or changed, or the state is unknown) or `FAILED` (nothing), `errorCode:JIRA_ERROR`. `created` — the objects that actually exist (`complete:false` — the list is incomplete). `assignment`: for a new scheme `NOT_ASSIGNED`, `ASSIGNED` or `UNKNOWN`; for `UPDATE_PRIVATE` the rows are read back — `ASSIGNED`, `NOT_ASSIGNED`, `PARTIAL` (diverging rows "before / plan / actual" in `warnings`) or `UNKNOWN`.

### `set_issue_type_screen_scheme_mappings`

Edits the "issue type → Screen Scheme" rows of an **existing** Issue Type Screen Scheme in place — **for all its projects**. No copies, project links are not changed, issues are not migrated. For one project — `set_project_operation_screens` or `clone_project_screens`.

```json
{"dryRun":true,"issueTypeScreenScheme":"<id | exact name>","rows":[
 {"action":"ADD|REPLACE|REMOVE","issueType":"<id | exact name | DEFAULT>","screenScheme":"<id | exact name>"}]}
```

- 1–50 rows, at most one per type and per `DEFAULT`. `screenScheme` is required for `ADD`/`REPLACE` and forbidden for `REMOVE`.
- `ADD` — a type without its own row; `ADD` of an existing row — warning `ADD_AS_REPLACE` and `REPLACE`. `REPLACE` — the type's own row or `DEFAULT`. `REMOVE` — the type falls back to `DEFAULT`; `DEFAULT` itself can only be replaced.
- Computed on the final table; row order does not matter. `rows[]`: type, `requestedAction`/`action`, scheme before and after (`viaDefault`), `change` (`CHANGE`/`NO_CHANGE`). All `NO_CHANGE` — status `NO_CHANGE`.
- `projects`: `linked` (N, all linked, including archived), `changing` (M, where some type's Screen Scheme changes), `listed` — first 10 by key (`key`, `name`, `archived`, `changedIssueTypes`, `rowTypesMissing`), `truncated`. `scopeText` names N and M; projects without changes still use the changed table.
- Write: `DEFAULT` first, then rows in order, up to the first error. Outcome from the read-back table: `CHANGED`, `PARTIALLY_CHANGED`, `FAILED` (nothing changed), `UNKNOWN`; per row — `APPLIED`, `NO_CHANGE`, `NOT_APPLIED`, `NOT_ATTEMPTED`, `UNKNOWN`.
- Fingerprint: rows, scheme name and description, linked projects and their types, affected Screen Schemes.
- Errors: `ITSS_NOT_FOUND`, `ITSS_AMBIGUOUS`, `SCREEN_SCHEME_NOT_FOUND`, `SCREEN_SCHEME_AMBIGUOUS`, `ISSUE_TYPE_NOT_FOUND`, `DEFAULT_ROW_REQUIRED`, `ROW_NOT_FOUND`, `DUPLICATE_ROW`, `INVALID_ROW`, `VALIDATION_ERROR`; `errorRowIndex` is zero-based.

### `set_screen_scheme_operations`

Edits the "operation → screen" rows of an **existing** Screen Scheme in place — **for all Issue Type Screen Schemes and projects that use it**. The scheme id is kept, no copies, links and issues are not changed. For one project — `set_project_operation_screens` or `clone_project_screens`; to change a type's Screen Scheme — `set_issue_type_screen_scheme_mappings`.

```json
{"dryRun":true,"screenScheme":"<id | exact name>","rows":[
 {"action":"ADD|REPLACE|REMOVE","operation":"DEFAULT|CREATE|EDIT|VIEW","screen":"<id | exact name>"}]}
```

- 1–50 rows, at most one per operation. `screen` is required for `ADD`/`REPLACE` and forbidden for `REMOVE`. `ADD`/`REPLACE`/`REMOVE` — as in `set_issue_type_screen_scheme_mappings`; `REMOVE` moves `CREATE`/`EDIT`/`VIEW` to the `DEFAULT` screen.
- Computed on the final table (`REPLACE DEFAULT` + `REMOVE CREATE` — `CREATE` on the **new** `DEFAULT` screen). `rows[]`: operation, `requestedAction`/`action`, screen before and after (`viaDefault`), `change`.
- `usage`: Issue Type Screen Schemes with the scheme (`linked`, first 10 by id with `isDefault`, `truncated`). `projects`: `linked` N, `changing` M (where the effective screen actually changes), first 10 by key (`key`, `name`, `archived`, `changedIssueTypes`, `issueTypeScreenScheme`), `scopeText`. An unused scheme — N = 0 and `NO_LINKED_PROJECTS`.
- `REQUIRED_FIELDS_MISSING_ON_CREATE` (non-blocking): for project/type pairs whose `CREATE` screen changes, the new screen lacks required fields (orderable; custom ones only within the pair's context). `requiredFields`: `pairs`, up to 10 `projects` with `issueTypes[]`, `truncated`, `fields`.
- Write: explicit `ADD`/`REPLACE` in order, then `DEFAULT`, then `REMOVE` — every operation always points to the old or the new screen; up to the first error. Outcomes — as in `set_issue_type_screen_scheme_mappings`.
- Fingerprint: rows, scheme name and description, linked ITSS and their rows, projects and types, screens before and after, field composition of the effective `CREATE` screens. The required flag in the field configuration and a change of the project's Field Configuration Scheme are not tracked (`limitations[]`).
- Errors: `SCREEN_SCHEME_NOT_FOUND`, `SCREEN_SCHEME_AMBIGUOUS`, `SCREEN_NOT_FOUND`, `SCREEN_AMBIGUOUS`, `INVALID_OPERATION`, `DEFAULT_ROW_REQUIRED`, `ROW_NOT_FOUND`, `DUPLICATE_ROW`, `INVALID_ROW`, `VALIDATION_ERROR`, `JIRA_ERROR` (including a scheme without a `DEFAULT` row); `errorRowIndex` is zero-based.

### `assign_issue_type_screen_scheme`

Switches **one** project to an **existing** Issue Type Screen Scheme. Only the project's link changes: schemes and screens are not edited or copied, issues are not migrated.

```json
{"dryRun":true,"project":"<key | name>","issueTypeScreenScheme":"<id | exact name | DEFAULT>"}
```

- `DEFAULT` (exactly in upper case) — Jira's default scheme, takes precedence over a scheme with the same name. An archived project is rejected.
- Response: `currentIssueTypeScreenScheme`, `newIssueTypeScreenScheme` (`id`, `name`, `isDefault`) and `issueTypes[]` — Screen Scheme and effective `CREATE`/`EDIT`/`VIEW` screens before and after (`viaDefault`), `change`. Project already on the scheme — `NO_CHANGE`.
- Warnings: `ITSS_ORPHANED` (the former scheme is left without projects, not deleted), `SHARED_TARGET` (`count` other projects use the new scheme — its edits will affect this one too), `REQUIRED_FIELDS_MISSING_ON_CREATE` (as in `set_screen_scheme_operations`).
- Outcome from the read-back link: `CHANGED`; `FAILED` — the link stayed the same (write traces are not ruled out); `UNKNOWN` — the state cannot be determined. `observedIssueTypeScreenScheme` — the read-back scheme.
- Fingerprint: the project's link, both schemes with their Screen Schemes and screens, the project's types, the projects of both schemes, fields of the new `CREATE` screens. The required flag and a change of Field Configuration Scheme are not tracked (`limitations[]`).
- Errors: `ITSS_NOT_FOUND`, `ITSS_AMBIGUOUS` (with scheme ids), `UNKNOWN_PROJECT`, `VALIDATION_ERROR`, `JIRA_ERROR`.
- After the switch, the `create_project` internal rollback for this project will end in a conflict.
## Workflows

There is no global workflow list: flows start with `get_project_workflows` of the chosen project or with an exact workflow name.

### `get_project_workflows`

Reads the project's current configuration: workflow scheme, `defaultWorkflow`, the effective workflow of each issue type, drafts and sharing. Does not scan issues.

- `{"project":"LAB"}` — `issueCountsStatus=NOT_REQUESTED`, `issues`/`statuses` are `null`: an unknown count does not mean there are no issues.
- `{"project":"LAB","includeIssueCounts":true}` — full issue count by type and status, with no time limit; `COMPLETE` includes types found only in issues. A count failure gives `ERROR` and `issueCountsError`; the configuration stays in the response.

Neither mode proves a migration is safe: those checks and the number of issues to move come from the `assign_workflow` preview.

### `get_workflow`

Reads one workflow version: `{"workflow":"<exact name>","version":"ACTIVE"|"DRAFT"}`. For an inactive workflow `ACTIVE` is the saved version. A missing draft is an error; no other version is substituted.

Response:
- `revision` — input for `configure_workflow.existing.expectedRevision`; covers the active version, the draft, activity and description;
- steps: `stepId`, `statusId`, status name, category and description, `initial`;
- transitions: `actionId`, `kind` (`INITIAL`, `LOCAL`, `GLOBAL`, `COMMON`), `sourceStepIds`, `targetStepIds`, `screenId`, Resolution rule (`SET`, `CLEAR`, `PRESERVE`, `UNKNOWN` with a reason), `unsupported` — which transition edits will be rejected;
- `capabilities`; `consumers` — schemes (including scheme drafts), projects and issue types, full counts and lists of up to 10; `draftDiff` — active version → draft.

XML and third-party function arguments are not returned. A graph with more than 1000 steps and transitions is rejected as a whole.

### `get_workflow_validators`

Reads the validators of all transitions of one version: `{"workflow":"<exact name>","version":"ACTIVE"|"DRAFT"}`. Response: `revision` (as in `get_workflow`), `scriptRunner {installed, enabled, version, supported, reason}` and `transitions[]` (`actionId`, `name`, `screenId`/`screenName`, `validators[]` in descriptor order). Kinds:
- `REQUIRED_FIELDS` — ScriptRunner "Field(s) required" in its exact form: `fieldIds`, `fields[{id, name}]`, `conditional`;
- `COMMENT_REQUIRED` — "Comment required" in its exact form, no arguments;
- `OTHER` — everything else: only `type`, `className`, `cannedScript`.

Each validator has `problem`: `null` or, for `COMMENT_REQUIRED`, the diagnosis "the transition has no usable screen — ScriptRunner will fail the transition" (no screen assigned or it does not exist, id not numeric, no `view="fieldscreen"`). Reading works without ScriptRunner.

### `get_workflow_conditions`

Reads the conditions of all transitions of one version: same input, `revision`, `scriptRunner` and `transitions[]` (`actionId`, `name`, `root`). `root` is `null` or a tree in descriptor order. Node: `position` (from 1 within the parent) and `kind`:
- `COMPOUND` — `operator` `AND`/`OR`, `children[]`; a root without a type is `AND`;
- `GROUP` — Jira `UserInGroupCondition`: `group`;
- `PROJECT_ROLE` — ScriptRunner "Project role(s) condition": `roleIds[]`, `roles[{id, name}]` (`name:null` — the role does not exist), `inverted`;
- `OTHER` — everything else, including these conditions in another form: `type`, `className`, `cannedScript`.

Keys unrelated to the node kind are `null`. ScriptRunner compatibility for conditions is checked separately from validators: currently only version 9.46.0 is confirmed, and `scriptRunner.supported` here refers to "Project role(s) condition". Reading works without ScriptRunner.

### `configure_workflow`

One edit of **one** workflow: one preview of the full change, one confirmation, one operation. Detailed design: [Configure-workflow-how-it-works](howitworks/mcp/Configure-workflow-how-it-works.md). Exactly one target:

- `create:{name, description?, statuses, initialStatus, transitions}` — a new workflow from text or a graph image; each transition has a unique `key`.
- `existing:{workflow, version:"ACTIVE"|"DRAFT", expectedRevision, changes?}` — an existing workflow; `expectedRevision` comes from `get_workflow`. `changes` is a strict array of 1–100; `null`, `[]`, null elements and unknown fields are rejected.

Shared blocks: `newScreens:[{key, name, description?, fieldIds}]` and `transitionRules:[{transitionKey | actionId, screen?, addValidators?, addConditions?}]`, one block per transition. The schema is closed: unknown properties, `null` instead of blocks, and arbitrary script/class/args are not accepted. Missing or empty `newScreens`/`transitionRules` change nothing; if nothing changes — `UNCHANGED`.

```json
{
  "dryRun": true,
  "create": {
    "name": "Delivery",
    "statuses": [{"name":"To Do"},{"name":"In Progress"}],
    "initialStatus": "To Do",
    "transitions": [{"key":"start","name":"Start","from":"To Do","to":"In Progress"}]
  },
  "newScreens": [{"key":"form","name":"Delivery form","fieldIds":["description"]}],
  "transitionRules": [{
    "transitionKey": "start",
    "screen": {"action":"SET","screenKey":"form"},
    "addValidators": [
      {"template":"comment_required"},
      {"template":"required_fields","parameters":{"fieldIds":["description"]}}
    ],
    "addConditions": {"operator":"AND","rules":[
      {"template":"group","parameters":{"group":"delivery-users"}},
      {"template":"project_role","parameters":{"roles":["Developers"]}}
    ]}
  }]
}
```

**`create` graph.** Statuses with a matching name (case-insensitive) are reused; missing ones are created with a category. `initialStatus` is required. A global transition `{"key":"reopen","name":"Reopen","fromAny":true,"to":"To Do"}` works from all statuses, including the target; loops are supported. "All" on a diagram is the source, not a button name; without a label the transition name is the target's name. An existing screen can be given in `create.transitions[].screen` (id or name). Jira builds the initial Create transition; the request does not address it.

A taken name gives `EXISTS` only for a fully matching graph (including each transition's `view`: a screen needs `fieldscreen`) **without** rules and screens in the request; otherwise `NAME_CONFLICT`. An existing workflow is not configured through `create`.

**`existing.changes` edits:** `ADD_STATUS`, `REPLACE_STATUS`, `REMOVE_STATUS`, `SET_INITIAL_STATUS`, `ADD_TRANSITION`, `UPDATE_TRANSITION`, `REMOVE_TRANSITION`, `UPDATE_METADATA`. All edits, then `transitionRules`, are applied to a copy of the descriptor and the resulting graph is checked — one diff, one fingerprint, one write. Existing ids, unnamed rules, properties and order are kept.

- **Steps** are addressed by `stepId`. `ADD_STATUS` takes exactly one: `statusId` of an existing global status or `newStatus:{name, category, description?}`. A name match (trimmed, case-insensitive) with one status means reuse without changing the status (a mismatched category is a warning); several — `AMBIGUOUS_STATUS` with candidates; none — a global status is created. The preview distinguishes CREATE/REUSE. A status already in the workflow and duplicates are rejected.
- `ADD_STATUS` may set a local `clientKey`. `source:{stepId | clientKey | fromAny:true}`, `targetStepId`/`targetClientKey` and the initial step may refer to a new step in the same request, in any order.
- **Transitions** are addressed by the `actionId` that was read. Only `ADD_TRANSITION` sets `transitionKey` (a non-empty string up to 255 characters without leading/trailing spaces, unique in the request, case-sensitive). In `transitionRules` for `existing` exactly one is needed: `actionId` of an existing transition or `transitionKey` of a new one. Removed, unknown and guessed ids, unknown keys and a repeated target are rejected. Transition keys, step `clientKey` and `newScreens[].key` are independent namespaces.
- **Transition screen** in `changes`: `screen:{action:"SET", screenId}` or `{action:"CLEAR"}` (CLEAR removes the reference and only the `fieldscreen` view). An explicit SET/CLEAR of the same transition in both `changes` and `transitionRules` is a conflict, even with the same value.
- **Resolution:** `resolution:{action:"SET", value}` / `{action:"CLEAR"}` / `{action:"PRESERVE"}` changes only the standard post-function for future transitions; issues are not fixed. Entering DONE requires SET; CLEAR only into non-DONE. A non-standard or conditional writer gives `UNSUPPORTED_DESCRIPTOR`. When the source or target changes, a Resolution contradiction must be resolved in the same request.
- `UPDATE_METADATA` changes only the description.
- **Replacing and removing statuses.** Replacing a step's status in the active version gives `MIGRATION_REQUIRED`, even without issues. Removing a step of the active version requires that no issues are in it across all the workflow's projects (checked in the preview and before publishing). References, including the initial step, are reassigned explicitly, with no cascade; the global status is not deleted. For an occupied step: `copy_workflow` → `configure_workflow` on the copy → `assign_workflow` with `statusMappings`.
- Limits: INITIAL/COMMON/conditional and join/split transitions cannot change source, target or Resolution; COMMON — only name and screen. The built-in `jira` workflow gives `SYSTEM_WORKFLOW`. Structural edits do not require ScriptRunner.

```json
"existing": {"workflow":"Delivery","version":"ACTIVE","expectedRevision":"<from get_workflow>","changes":[
  {"op":"ADD_STATUS","clientKey":"doing","newStatus":{"name":"Doing","category":"IN_PROGRESS"}},
  {"op":"ADD_TRANSITION","transitionKey":"enter","name":"Start","source":{"stepId":1},"targetClientKey":"doing"}]}
```

Along with this you can pass `newScreens` and `transitionRules` with `transitionKey:"enter"`. After a successful read back the result returns `transitions` (`transitionKey → actionId`) and `objects` (`clientKey → stepId`, `newScreens.key → screenId`). Preview ids are planned ids and are not accepted as `actionId` in the same request. On `FAILED`/`PARTIAL`/`UNKNOWN` the actually created ids come from the execution receipt.

**Rules are only added.** Existing validators and conditions, their order and unrelated properties are kept; removal, replacement and reordering are not supported.
- `required_fields`: missing fields are added as a **separate** validator at the end; coverage counts all unconditional recognized validators.
- `comment_required` is skipped only for the exact existing form.
- A repeated `group` (case-insensitive) or the same role set with the same `invert` is recognized only as a direct leaf of the root AND.
- An unrecognized form of a requested rule gives `UNSUPPORTED_DESCRIPTOR`; other unrecognized rules are kept.

**Conditions.** No root — an AND is created; new leaves are appended to an AND. An OR or unknown root is refused. **All** conditions must be met.
- `group` — member of an existing Jira group, no ScriptRunner. A currently empty group is a warning: nobody will satisfy this condition.
- `project_role` — member of **any** of the roles (up to 20 ids or exact names; ambiguous — `AMBIGUOUS_ROLE`) in the issue's project; `invert:true` — none of them.
- Validators and `project_role` require ScriptRunner enabled at the verified version (9.46.0).
- Conditions narrow access for **all** projects of the workflow.

**Screens.** `transitionRules[].screen:{action:"SET", screenId | screenName | screenKey}` sets the reference and the `fieldscreen` view together; `"KEEP"` checks nothing. An ambiguous name gives `AMBIGUOUS_SCREEN`. A new screen is declared in `newScreens` (one `Field Tab` tab with existing orderable fields in order) and used via `screenKey`; a key shared by several transitions is one screen. Duplicates, an unknown field or key, an unused screen — refused. An existing screen with the same name is not reused (warning).

**Final `existing` check** covers added transitions, transitions with an explicit SET/CLEAR and the targets of non-empty `addValidators`/`addConditions`. `comment_required` without a usable screen gives `TRANSITION_SCREEN_MISSING`; a `required_fields` field not on the screen is a warning; unrecognized validators give a "not analyzed" warning. Other transitions are not blocked by old defects.

**Transition kinds.** LOCAL/GLOBAL — rules and screen; COMMON — screen only (the preview shows all steps); INITIAL — rules without a screen.

**Resolution in `create`.** Entering DONE from another status or `fromAny` requires `{"action":"SET","value":"<id from get_resolutions>"}`; a transition between different DONE statuses also needs SET. Leaving DONE and `fromAny` into non-DONE are automatically CLEAR; other non-DONE transitions keep the value. An explicit CLEAR into DONE is forbidden. The preview shows the resulting SET/CLEAR/PRESERVE.

**Versions.** An inactive workflow (`ACTIVE`) is saved in place (`UPDATED_INACTIVE`); an active one without a draft gets a draft (`DRAFT_SAVED`), with a draft — `DRAFT_EXISTS`. `DRAFT` requires an existing draft and saves it. There is no publishing or project assignment (`publish_workflow_draft`, `assign_workflow`). The preview lists the resulting transition restrictions, required fields and comment, the AND tree, screens, global objects to be created, the workflow's projects and shared effects. An error in any rule or lookup means no plan. After a failure a screen may remain incomplete or unassigned; partial/`UNKNOWN` do not trigger rollback or re-creation.

### `copy_workflow`

An exact copy of the main (active or saved) version of one workflow, including the built-in `jira`.

```json
{"dryRun":true,"workflow":"<exact source name>","name":"<new name>","description":"<optional>"}
```

- The name is trimmed, up to 255 characters; taken (case-insensitive) — `NAME_TAKEN`. Without `description` the source's description is inherited.
- The copy is inactive and not assigned to any scheme; the source, projects and issues do not change. The source's draft is not copied: `sourceHasDraft:true` and a warning — publish it beforehand if needed.
- The path for edits unavailable in an active workflow's draft: `copy_workflow` → `configure_workflow` on the copy → `assign_workflow` with `statusMappings`. This is a new workflow, not a rename.
- `CREATED` includes the number of steps and transitions (excluding the initial Create) and `report.created`. If the copy's definition after the write differs from the source (except the description) — `FAILED`/`JIRA_ERROR`: the copy may remain, check Jira and the log.
- Internal rollback is possible while the copy is unchanged, unassigned and not used by issues.
- Errors: `VALIDATION_ERROR`, `UNKNOWN_WORKFLOW`, `NAME_TAKEN`, `PREVIEW_STALE`, `JIRA_ERROR`.

### `assign_workflow`

Connects an existing workflow to a project — to all issue types (`allIssueTypes:true`) or to the named ones (`issueTypes`) — and moves issues to it. An issue keeps its status if the target workflow has it; fields and history are kept.

```json
{"dryRun":true,"project":"ABC","workflow":"Bug flow","issueTypes":["Bug"],
 "statusMappings":[{"from":"In Review","to":"In Progress"}]}
```

- A status occupied by issues that is not in the target workflow requires an explicit `statusMappings`; until every such status is mapped, the whole call is `STATUS_CONFLICT` with no write (blocking statuses and issue counts are in `issueTypes[].statuses` with `inWorkflow=false`).
- Preview per issue type: `ALREADY` (already assigned), `READY` (no issues), `MIGRATE` (issues are moved); the scheme action, issue count, warnings. Everything already assigned — `ALREADY_ASSIGNED`.
- The project's own scheme is changed in place; a project on Jira's default scheme gets a new one. Scheme used by other projects — `SCHEME_SHARED`; `newScheme=true` gives the project its own scheme.
- At most 10,000 issues moved per call (`TOO_MANY_ISSUES` — split by issue type). Runs synchronously; a large project takes minutes. Calls do not overlap: if busy, repeat the same request later.
- Other errors: `DRAFT_EXISTS` (scheme draft; publish or discard it in Jira, or `newScheme=true`), `UNKNOWN_WORKFLOW`, `UNKNOWN_PROJECT`, `UNKNOWN_ISSUE_TYPE`, `ISSUE_TYPE_NOT_IN_PROJECT`, `JIRA_ERROR`.

Outcomes:
- `ASSIGNED` — scheme assigned, migration and the final pass over issues completed;
- `PARTIALLY_ASSIGNED` — the assignment is confirmed by a read, but the final pass did not move or check issues created during the call, or the error came after the assignment;
- `UNKNOWN` — the write may have started, assignment not confirmed;
- `FAILED` — error before the first write.

For `PARTIALLY_ASSIGNED` and `UNKNOWN`, `message` lists what is confirmed (scheme, number of moved issues, assignment) and what is not. Find out the state via `get_project_workflows` and a new preview.

### `clone_project_workflows`

Gives one project its own copy of the shared workflow setup: an exact copy of the scheme and, optionally, of the workflows themselves (steps, transitions, conditions, validators, post-functions and properties, including other apps' settings). The originals do not change, other projects notice nothing; issues move to the copies with the same statuses.

```json
{"dryRun":true,"project":"ABC","scope":"SELECTED","workflows":["Bug flow"]}
```

- `scope`: `SCHEME_ONLY` — only the scheme, workflows stay shared; `ALL_WORKFLOWS` — the scheme and every workflow also used by other projects; `SELECTED` — the scheme and the named `workflows`. Verdicts: `COPY`, `KEEP`, `ALREADY_OWN` (workflow already used only by this project).
- The project's own scheme is changed in place (`scheme.action:UPDATE`), otherwise a new one is created (`CREATE`). Nothing shared left — `ALREADY_SEPARATE`.
- Copy names: another project's key at the start of the name is replaced by this project's key (`BUS: Process scheme` → `SPMT: Process scheme`), otherwise the prefix `KEY: ` is added; ` v2`, ` v3` only when the name is taken. Custom names — `schemeName` and `workflowNames`.
- A copy is built from the workflow's main version; the draft is not copied or changed. The preview (`WOULD_CLONE`) names copied workflows with a draft in one warning; a draft appearing or disappearing before apply gives `PREVIEW_STALE`.
- Errors: `DRAFT_EXISTS` (a draft of the workflow **scheme** — publish or discard it), `WORKFLOW_NOT_IN_SCHEME`, `UNKNOWN_WORKFLOW`, `UNKNOWN_PROJECT`, `NAME_TAKEN`, `TOO_MANY_ISSUES` (up to 10,000 issues per call), `JIRA_ERROR`. The lock is shared with `assign_workflow`.
- Error after a write: `PARTIALLY_CLONED` (a read confirms the project uses the copies) or `UNKNOWN`; `message` lists the created copies, the number of moved issues and what is unconfirmed.

### `publish_workflow_draft`

Publishes the draft of one active workflow with Jira's standard publishing. The draft becomes the working version for **all** projects and schemes of this workflow. The previous active version is not kept.

- Preview `{"dryRun":true,"workflow":"Lot Buy"}`: `changes` (added and removed statuses and transitions, changed transitions, `descriptorChanged`), `affectedProjects`, `affectedProjectNames`, `affectedSchemes` and `PUBLISH_REFUSED` if Jira will not accept the draft. No draft — `NO_DRAFT`.
- For steps absent from the draft, issues in them are counted across all projects: issues present — `PUBLISH_REFUSED` with counts. Zero is part of the fingerprint: a new issue in such a step before apply gives `PREVIEW_STALE`. Jira's standard checks run and are not bypassed.
- After `PUBLISHED` there is no draft and the active version matches the confirmed one. A previous `clone_project_workflows` plan becomes stale. An unconfirmed result is `JIRA_ERROR`; do not repeat the publish.

### `rename_workflow`

```json
{"dryRun":true,"workflow":"<name>","newName":"<new name>"}
```

Jira's standard rename, as in administration: the workflow is stored under the new name, scheme references (including their drafts) are rewritten, the definition does not change. Jira renames only an inactive workflow. Refusals: `ACTIVE_WORKFLOW`, `SYSTEM_WORKFLOW`, `IN_USE` (issues have workflow entries with the old name), `NAME_TAKEN`, `RENAME_REFUSED` (Jira's reason, e.g. a draft). `RENAMED` is confirmed by reading both names, the definition and the schemes. A copy with assignment (`copy_workflow` + `assign_workflow`) is a different operation.

### `update_status`

```json
{"dryRun":true,"statusId":"<id>","name":"…","description":"…","category":"TO_DO|IN_PROGRESS|DONE"}
```

Changes a **global** status: the change is visible in all workflows (active, inactive, drafts), projects and issues that use it. An omitted property is kept; `description:""` clears it. The preview lists those workflows and projects (full counts, names up to 10). Jira validates the name (`UPDATE_REFUSED`). Changing the category does not change issues' Resolution or transition rules; renaming breaks saved JQL using the old name. There is no project-only status and no status deletion.

### `list_statuses`

Finds statuses: `id`, `name`, category (`TO_DO`, `IN_PROGRESS`, `DONE`, `UNDEFINED`), description.
- `query` — up to `limit` records (default 20, max 100) out of `total`, descriptions up to 200 characters.
- `names` — up to 30 exact names in one call: `lookup:[{name, result: FOUND|NOT_FOUND|AMBIGUOUS, statuses}]` in request order.

Not required for `configure_workflow`: its preview reuses existing statuses by name itself.

### `get_resolutions`

Returns existing Resolutions (`id`, `name`, `description`). `query` — exact id or case-insensitive name. The id for `SET` in `configure_workflow` is taken only from here. "Done" does not mean `Fixed` by default.

### `create_resolution`

Creates a global Resolution value for **all of Jira**. Preview: `name`, optional `description`.

- Same name and description — `EXISTS` with the existing id. Ambiguous name or a different description — `NAME_CONFLICT` with no overwrite. `Unresolved`/«Нерешено» are forbidden: they mean no value.
- Resolution and workflow are separate operations: after creation a new workflow preview with the real id is needed; if the workflow fails, the value remains.
- Internal rollback does not delete a value that was changed, is used by issues or workflows, or is set as default.

## Issue types

### `list_issue_types`

Finds issue types by id or name: `id`, `name`, `description`, `subtask`. `query` and `limit`, `total`; default 20 records, max 100, descriptions up to 200 characters.

### `create_issue_types`

Creates or reuses a **batch** of global issue types in one plan; a single type is a batch of one.

```json
{"dryRun":true,"issueTypes":[
 {"key":"pay","name":"Payment","subtask":false},
 {"key":"check","name":"Review","description":"Review sub-task","subtask":true}]}
```

- `issueTypes` 1–20: `key` — local label (1–40 characters `A-Za-z0-9_.-`, not a Jira key), `name`, `description?`, explicit `subtask`.
- A name match (case-insensitive) with one type of the same kind — `EXISTS`, the type is unchanged; no match — `WOULD_CREATE`. Duplicate `key`s or names, an ambiguous name, a name of a type of the other kind, missing fields, more than 20 items — the batch is `FAILED` (`VALIDATION_ERROR` or `NAME_CONFLICT`) with the items marked. Everything already exists — `EXISTS` with no plan.
- The fingerprint (per-item decisions and the whole type catalog) is checked once before the first write. During the batch only the next type's name availability and the integrity of reused types are checked. For several types use one batch: two separate previews are not consistent with each other.
- **Where the ids are:** `result.results[]` in request order: `key`, `status` (`CREATED`, `EXISTS`, `FAILED`, `UNKNOWN`, `NOT_EXECUTED`), `issueType.id`; the same ids are in `result.report.created`/`reused` (`kind: ISSUE_TYPE`). Afterwards use only ids with status `CREATED` or `EXISTS`.
- Outcome: `CREATED` — all missing types created; `PARTIALLY_CHANGED` — some created and remain in Jira and/or one write's outcome is `UNKNOWN`, the rest `NOT_EXECUTED`; `FAILED` — nothing written. A repeat preview of the same list plans only what is missing.
- **Unknown outcome.** If a write threw an exception, the catalog is read back: type not found — `FAILED`; catalog not read — `UNKNOWN` without an id; a type with the same name and kind found — also `UNKNOWN` (someone else may have created it): the id is only observed, does not go into `report.created` and is not deleted automatically.
- Internal rollback touches only types confirmed as created, with usage checks; reused types and types of unknown origin are not deleted.
- Jira adds each new type to the default issue type scheme, so it is visible to all its projects. Project schemes are not reassigned, issues do not change.

### `set_project_issue_types`

Sets the **complete final** set of a project's issue types: `issueTypes` (existing, up to 500), optional `newIssueTypes` (as in `create_project`: reused on matching name and kind, otherwise created in the same operation) and `defaultIssueType` (a local `key` or a standard type in the set). Without `defaultIssueType` the previous default is kept if it stays in the set, otherwise the first standard type.

- Types outside the set are removed from the project, but not from Jira.
- Issues of a removed type require `mappings` (`from` → `to`, the target is a type in the set). Standard issues only to a standard type, sub-tasks only to sub-tasks.
- The target type has a different workflow without an occupied status — `STATUS_CONFLICT`: `statusMappings` is needed for that type.
- Preview: added and removed types, number of issues to move, source and target workflow, status conversions; with `newIssueTypes` — `issueTypeSet` (create/reuse, default, impact).
- A shared or default issue type scheme is not changed: the project gets its own. The project's own scheme is updated in place.
- With `newIssueTypes` a failure is a result, not an exception: `PARTIALLY_CHANGED` (some types created and remain, or outcome `UNKNOWN`), the set is not assigned (`assignment.state NOT_STARTED`) or the assignment was interrupted (`PARTIALLY_CHANGED`/`UNKNOWN` with `execution`). A repeat preview reuses what was created.

## Operations

### `start_operation`

Runs one confirmed plan in the background: `toolName` and `arguments:{dryRun:false, planId}`. `sessionId` and `changeSetId` are not passed: the server manages ownership (an explicit pair must belong to the user and be open). A dropped connection does not stop the work. Recovery — see the [protocol](#operations-and-recovery-protocol).

### `get_operation`

Reads the state and domain result of your operation by `operationId`, including after reconnecting. `waitSeconds` — 0–20 (default 20): the server waits up to this time and responds immediately on a final state; `0` — immediate read. On `QUEUED`, `RUNNING`, `CANCEL_REQUESTED` call again, without pauses or external requests.

## Created-objects report (`report`)

Seven create tools (`create_project`, `create_custom_fields`, `create_screen`, `create_issue_types`, `create_resolution`, `create_field_configuration`, `create_field_configuration_scheme`) return in the apply result a `report` block with links to what was created. The preview does not have it. Created objects are deleted manually via the links. `configure_workflow` returns ids in its result and execution receipt.

```json
"report": {
  "created": [{"kind":"CUSTOM_FIELD","id":"customfield_10201","name":"Severity","url":"http://host/jira/secure/admin/ConfigureCustomField!default.jspa?customFieldId=10201"}],
  "reused":  [{"kind":"STATUS","id":"3","name":"In Progress","url":"http://host/jira/secure/admin/EditStatus!default.jspa?id=3"}]
}
```

- `created` — what the tool created; `reused` — what was found and used (e.g. an existing field in `create_custom_fields` and `create_screen`). What was read but not used is not included. System fields of a screen are not included.
- `kind`: `PROJECT`, `CUSTOM_FIELD`, `FIELD_CONTEXT`, `OPTION`, `SCREEN`, `SCREEN_TAB`, `WORKFLOW`, `STATUS`, `ISSUE_TYPE`, `RESOLUTION`, `FIELD_CONFIGURATION`, `FIELD_CONFIGURATION_SCHEME`.
- Options are not listed: one `OPTION` entry per context with a `count` (including child options) and a link to the context's options.
- `url` is built from Jira's canonical base URL (with the context path, e.g. `/jira`). No reliable page — no `url` (the case for `FIELD_CONFIGURATION*`). For a workflow `id` is empty; the page is found by name. `FIELD_CONTEXT` links to the field's contexts, `SCREEN_TAB` to the screen, `PROJECT` to `/plugins/servlet/project-config/<KEY>`.

## Tool names in the client

Tools pass Russian titles in `annotations.title` (e.g. `list_projects` — «Поиск проектов Jira» (“Search Jira projects”)). The client may show the title or the technical name. The plugin does not hide call cards and does not control the client's permission prompts; `readOnlyHint: true` by itself does not enable auto-approval of reads.

After updating the plugin, reconnect the MCP server so the client fetches the tool catalog again.
