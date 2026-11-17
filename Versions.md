# Release history

Before upgrading, wait until running operations finish. After upgrading, reconnect your MCP client so it reloads the tool catalog.

## 1.2.4 — 2026-11-15

Includes the changes of 1.2.3 (2026-11-13).

### Screen schemes

- New `set_issue_type_screen_scheme_mappings`: edits the rows (issue type → Screen Scheme) of an existing Issue Type Screen Scheme in place, for every project that uses it. Rows are `ADD` / `REPLACE` / `REMOVE`; adding a row that already exists is treated as a replace with an `ADD_AS_REPLACE` warning.
- New `set_screen_scheme_operations`: edits the rows (operation → screen) of an existing Screen Scheme in place, for every Issue Type Screen Scheme and project that uses it.
- New `assign_issue_type_screen_scheme`: switches one project to an existing Issue Type Screen Scheme (or back to the default one). Only the project link changes; schemes and screens are neither edited nor copied, and issues are not migrated.
- The previews of all three tools show the effective CREATE/EDIT/VIEW screens before → after per issue type, how many projects are linked and how many actually change, and warnings for an orphaned or shared scheme and for required fields missing from the new create screen.
- The outcome is read back from Jira: `CHANGED` only when Jira matches the plan, otherwise `PARTIALLY_CHANGED`, `FAILED` or `UNKNOWN`. These tools are forward-only: there is no rollback.

### Projects

- `create_project` creates Business projects (Project Management, Task Management, Process Management) and Software projects (Scrum, Kanban, Basic) from supported templates. `action:"list_templates"` reads the template catalog; `action:"create"` requires a `templateKey`. Jira Service Management and third-party templates are not supported.
- **Breaking:** the old `create_project` request without `action`/`templateKey` is rejected.
- Rollback of a Software project created through Control is refused: its board and filter are not tracked by Control.
- Software templates have only been verified by unit tests, not on a live Jira.

### Workflows

- **Breaking:** `edit_workflow` is removed without an alias. Its structural edits now go to `configure_workflow` under `existing.changes`, in the same plan as screens and rules: rename and add transitions, change sources, targets and resolution, add and remove steps.
- New statuses can be added to an existing workflow by name and category (`ADD_STATUS.newStatus`); a status with the same name is reused, an ambiguous name is refused.

### Fields

- `get_field_contexts` accepts an optional `project` and returns the global contexts plus the contexts that include that project.

### Fixes

- A failed read of option defaults while changing options is no longer masked as an empty set.
- Matching an existing workflow graph now takes the transition view into account, not only screen metadata.
- Cancelling `configure_workflow` between native steps returns `CANCELLED` instead of `JIRA_ERROR`.

### Security

- **Breaking:** every `/rest/control/1.0/changes/*` endpoint now requires `Authorization: Bearer <PAT>` and rejects a foreign `Origin`, like `/mcp`. Requests with only a session cookie or Basic auth get 401, a foreign `Origin` gets 403.

## 1.2.2 — 2026-11-08

### Workflows

- **Breaking:** one `configure_workflow` tool replaces `create_workflow`, `configure_workflow_validators` and `configure_workflow_conditions`. A new workflow, or additions to an existing one, are a single plan: graph, transition screens (existing or new), validators, conditions and resolution. One preview, one confirmation, one operation.
- An active workflow is changed through a draft and is not published; publishing stays a separate `publish_workflow_draft`.
- Validators: `Field(s) required` and `Comment required` (ScriptRunner). Missing required fields are added as a separate validator; existing validators are never rewritten.
- Conditions: `User in group` (built into Jira) and `Project role(s) condition` (ScriptRunner, with `invert`), combined with AND. Conditions narrow access for every project that uses the workflow.
- New read tools `get_workflow_validators` and `get_workflow_conditions`. Reading works without ScriptRunner; writing ScriptRunner rules requires a verified ScriptRunner version (9.46.0).
- The whole operation is forward-only: no rollback and no automatic retries. `get_operation` shows a step-by-step execution record, with honest `PARTIAL` / `UNKNOWN` outcomes and a separate read-back check.
- The `configure_workflow` input schema is flat (no `$ref` or `oneOf`), so clients can display its arguments. Parse errors name the field path, for example `transitionRules[0].addValidators[0].type`.

### License

- Licensed under the Business Source License 1.1. `LICENSE` and `THIRD-PARTY-NOTICES.md` are included in the JAR.

### Security

- `POST /rest/control/1.0/mcp` accepts only `Authorization: Bearer <PAT>`. Requests with only a session cookie or Basic auth get 401 before anything is parsed; a foreign `Origin` gets 403. Previously a cookie request without `Content-Type` could pass Jira's XSRF filter and run `start_operation`.

## 1.2.0 — 2026-11-03

### Reliable outcomes

- A failure in the middle of a write no longer loses what was already done. `add_field_to_screens`, `clone_project_screens`, `set_field_required`, `assign_workflow` and `clone_project_workflows` return the partial result (`PARTIAL*`, `UNKNOWN`, `NOT_ATTEMPTED`) with what was confirmed and what was not, instead of a bare `FAILED`.
- `get_operation` returns the id of each custom field already created, even when a later field in the batch fails.
- A cancel requested after the last write of a forward-only tool no longer replaces its result with `CANCELLED`.
- Audit shows a mix of successful and failed items as `PARTIAL`, not as a neutral `MIXED`.
- The most important model rules are at the start of `rules.md`, because some clients pass only the first 4,000 characters of server instructions.

### Fields

- Assets object fields: `get_field_contexts`, `create_field_context` and `update_field_contexts` read and set the object schema and the filter scope AQL per context.
- Options of an existing select, radio, checkbox, multi-select or cascading select context can be added, renamed, disabled, enabled and reordered through `update_field_contexts`. The preview shows before → after and how many issues use a renamed or disabled option. Options are never deleted.

### Workflows

- `clone_project_workflows` no longer refuses when a workflow has a draft: the main version is copied and the preview warns that the draft is not.

### Security and build

- The SDK no longer opens a remote debugger on `*:5005` by default.
- Jackson 1.9 is replaced with Jackson 2.22, bundled privately inside the plugin.

## 1.1.0 — 2026-10-21 … 2026-10-30

The first release line. Main features:

- **Operations.** Every write is preview (`dryRun:true`) → confirmation → `start_operation` → `get_operation`. A plan is bound to the administrator and to the relevant Jira state; a changed state or an expired plan gives `PREVIEW_STALE`. A repeated identical `start_operation` returns the operation already started (`alreadyStarted:true`). `get_operation` can wait for the final state for up to 20 seconds.
- **Audit.** A log of applies made through the plugin: Manage apps → Control → Audit (administrators only), plus a read-only REST `GET /rest/control/1.0/applies`. Direct per-tool REST endpoints were removed, so every apply goes through `start_operation` and reaches the log.
- **Custom fields and contexts.** `create_custom_fields` (including Apwide File Field and Table Grid NG settings), `get_field_contexts`, `update_field_contexts`, `create_field_context` with its own option list.
- **Screens.** `get_screen_contents`, `add_field_to_screens` with positions (`place`, `anchor`), `remove_field_from_screens`, `edit_screen_layout` (add, move and remove on one screen in one plan), `create_screen`, `clone_project_screens`, `set_project_operation_screens` for any number of issue types in one plan.
- **Required fields and field configurations.** `set_field_required` for several fields and issue types in one plan, giving the selected types their own copy of a shared Field Configuration; `get_field_configurations`, `create_field_configuration`, `create_field_configuration_scheme`, `assign_field_configuration_scheme`, `set_field_configuration_mappings`, `clone_project_field_configurations`.
- **Workflows.** `assign_workflow` with issue migration and status mappings, `clone_project_workflows`, `copy_workflow`, `publish_workflow_draft`, `rename_workflow`, `update_status`, resolution on transitions.
- **Issue types, projects and resolutions.** `create_issue_types` in batches, `create_project` and `set_project_issue_types` with the final set of issue types in one plan, `create_resolution`.
- **Reports.** Create tools return a `report` of created and reused objects with links to the Jira admin pages.
- Target platform: Jira 10.3, Java 17.
