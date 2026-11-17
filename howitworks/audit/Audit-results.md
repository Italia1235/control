# Audit: what Result means

Reference for administrators and developers: what each badge in the **Result** column of the Audit page means and which tool produces it. Source of truth: the tools' enum statuses in `src/main/java/**/api/dto`; labels: `src/main/resources/control.properties` (`control.applies.*`); badge selection: `outcome()` in `src/main/resources/js/control.js`. How the journal works overall: [Audit-how-it-works](Audit-how-it-works.md); database tables: [journal-schema](journal-schema.md).

## How the badge is chosen

For each row the page checks, in order:

1. **Rollback row** (`ROLLBACK`): badge from the "Rollback" section.
2. **Operation did not finish normally** (state ≠ `COMPLETED`): badge from the "Operation state" section.
3. **Operation finished**: badge from the tool's own status (`status` in the response). If the tool responds with a list (`results[].status`, e.g. `create_custom_fields`), the common status of the items is used, and `MIXED` if they differ.
4. No status at all: `Done`.
5. A status exists but `control.properties` has no label for it (new tool or status): the code in words (`SOMETHING_NEW` → "Something new") in **yellow** with the hint "check the details and Jira", so that an undocumented result does not look like success. All current statuses have labels; the `AuditResultLabelsTest` test prevents adding a tool status without a label.

The page shows only the final operation state and the final tool status ("Operation state" and "Tool status" in the row details are raw codes). Statuses of individual items (each field, each screen) exist only in the tool's response in the chat; in Audit they show up only through the `MIXED` aggregate.

Important: `Done` and green/blue badges mean that **the operation ran to the end**, not that Jira necessarily changed: the result may be `Already existed` or `No changes`. Conversely, `Failed` does not always mean "nothing changed": see `Partly done`/`Unknown` and the row details.

Colors: green: created; blue: changed; orange: required flag; purple: cloned; gray: nothing changed; yellow: partial/uncertain, check Jira; red: refusal or error.

## Operation state (when it did not finish normally)

| Code | Badge | Meaning |
|---|---|---|
| `FAILED` | Failed | Apply failed. The reason is in the error message. |
| `CANCELLED` | Cancelled | Cancelled before completion. |
| `ROLLED_BACK` | Failed, undone | Failed, and the changes made were rolled back. |
| `PARTIAL` | Partly undone | Failed, and not everything could be rolled back. Check Jira. |
| `BLOCKED` | Not undone | Failed, and nothing could be rolled back (the objects had already changed or are in use). Check Jira. |

## Rollback ("Roll back changes" rows)

| Code | Badge | Meaning |
|---|---|---|
| `ROLLED_BACK` | Rolled back | Rollback completed fully. |
| `PARTIAL` | Partly rolled back | Some changes were rolled back, some were not. |
| `BLOCKED` | Rollback blocked | Nothing was rolled back: the objects were changed after the apply or are in use. |
| `FAILED` | Rollback failed | The rollback itself failed. |

## Tool result: change made

| Code | Badge | Meaning | Tools |
|---|---|---|---|
| `CREATED` | Created | A new object was created. | `create_custom_fields`, `create_field_context`, `configure_workflow` (historical records: `create_workflow`), `copy_workflow`, `create_screen`, `create_issue_types` (rows before 1.1.6: `create_issue_type`), `create_resolution`, `create_project`, `create_field_configuration`, `create_field_configuration_scheme` |
| `ADDED` | Added | Field added to a screen. | `add_field_to_screens` (per item) |
| `ASSIGNED` | Assigned | Scheme/workflow/screens assigned; for `assign_workflow`, also all issues migrated and the final check passed. | `assign_workflow`, `assign_field_configuration_scheme`, `set_project_operation_screens` |
| `CHANGED` | Changed | The set or the scheme rows were changed. | `set_project_issue_types`, `set_field_configuration_mappings` |
| `UPDATED` | Updated | An existing object was updated. | `update_field_contexts`, `update_status` |
| `CLONED` | Cloned | The project got its own copy of the shared schemes. | `clone_project_workflows`, `clone_project_screens`, `clone_project_field_configurations` |
| `MADE_REQUIRED` | Made required | The field became required. | `set_field_required` |
| `MADE_OPTIONAL` | Made optional | The required flag was removed. | `set_field_required` |
| `REQUIREMENTS_CHANGED` | Required flags changed | Required flags set per `rows[]`; the result per type and field is in the response. | `set_field_required` |
| `MOVED` | Moved | Fields on the screen were reordered. | `move_screen_fields` (historical rows before 1.1.4 only; the tool was removed) |
| `EDITED` | Edited | The layout of one screen was changed (add, remove, move in one plan). | `edit_screen_layout` |
| `REMOVED` | Removed | Field removed from a screen (the layout row; values in issues remain). | `remove_field_from_screens` |
| `PUBLISHED` | Published | The workflow draft became the active version, for **all** projects that use it. | `publish_workflow_draft` |
| `RESTORED` | Restored | Moving a field between tabs failed, and the screen was returned to its original state. | `edit_screen_layout`; in rows before 1.1.4: `move_screen_fields` (per item) |
| `DRAFT_SAVED` | Draft saved | The edit was saved to the **draft** of the active workflow. Projects and issues are not affected yet; it takes effect only after `publish_workflow_draft`. | `configure_workflow` |
| `UPDATED_INACTIVE` | Updated (inactive) | The edit was saved directly to a workflow that no project uses. It takes effect after assignment. | `configure_workflow` |
| `RENAMED` | Renamed | Workflow renamed; schemes that referenced it were rewritten to the new name. The content did not change. | `rename_workflow` |

## Tool result: nothing changed

An Audit row is created only by an apply through `start_operation`, and a plan (`planId`) is issued only by a preview that has something to do. If the whole preview answers "nothing needs to be done" (`NOT_PRESENT`, `ALREADY_*`, `UNCHANGED`, `NO_DRAFT`…), there is no plan and no Audit record. If Jira changed between the preview and the apply, the apply returns `PREVIEW_STALE`, not "nothing changed". Therefore the codes below occur mostly **on individual items** in the tool's response (e.g. the field was removed from screen A, and was not on screen B at all), and in Audit only through the `MIXED` aggregate or when all items of the list ended the same way. For `remove_field_from_screens` this was verified in the code: the final apply status is only `REMOVED`, `PARTIAL`, `FAILED`, `UNKNOWN` or `PREVIEW_STALE` (`ScreenFieldRemoverImpl.java:63`, `:73-74`, `:210`); other tools were not checked. Labels for these codes exist anyway, so the page stays consistent if a code ever reaches a row.

| Code | Badge | Meaning |
|---|---|---|
| `EXISTS`, `EXISTING` | Already existed | Such an object (same name and type/content) already existed; nothing was created. |
| `UNCHANGED`, `NO_CHANGE` | No changes | Jira already matched the request. |
| `ALREADY_ASSIGNED` | Already assigned | Already assigned. |
| `ALREADY_CONFIGURED` | Already configured | The issue type set is already as requested. |
| `ALREADY_IN_PLACE` | Already in place | The field is already in the requested position. |
| `ALREADY_REQUIRED` / `ALREADY_OPTIONAL` / `ALREADY_AS_REQUESTED` | Already required / optional | The flag was already set that way. |
| `ALREADY_SEPARATE` | Already separate | The project already has its own schemes; nothing to clone. |
| `ALREADY_PRESENT` | Already present | The field was already on the screen (`add_field_to_screens`). |
| `NOT_PRESENT` | Not present | The field was not on this screen anyway; nothing to remove (`remove_field_from_screens`). Not an error. Occurs on an item or in a preview; never a final apply status. |
| `NO_DRAFT` | No draft | The workflow has no draft; nothing to publish (`publish_workflow_draft`). No plan, does not reach Audit. |
| `MIXED` | Mixed | Items ended differently (e.g. some fields were created, some already existed). Per item: in the tool's response in the chat; Audit does not show them. |

## Tool result: partial or unknown, check Jira

| Code | Badge | Meaning |
|---|---|---|
| `PARTIAL` | Partly done | Some changes were made, some were not. There was no automatic rollback. |
| `PARTIALLY_CHANGED` | Partly done | Same, for `set_project_issue_types` and `set_field_required`. For `set_field_required` the response has `partialEffect`: what was applied, what was rolled back (`compensated`), what remained in Jira (`leftBehind`), what was not started. |
| `PARTIALLY_ASSIGNED` | Partly assigned | The workflow was assigned, but the final check could not migrate or confirm all issues (e.g. they were being changed concurrently). |
| `UNKNOWN` | Unknown | A write happened, but reading the result back from Jira afterwards failed: it is **unknown** whether it was saved. This is not "nothing changed". Open the screen in Jira and check manually (`edit_screen_layout`, `remove_field_from_screens`; in rows before 1.1.4: `move_screen_fields`). |
| `NOT_ATTEMPTED` | Not applied | This item was not executed: the work stopped earlier. |
| `PREVIEW_STALE` | Preview expired | Not applied: the preview expired or Jira changed after it. A new preview and confirmation are needed. |

## Refusal (red badges)

Nothing was applied unless explicitly stated otherwise.

| Code | Badge | Meaning |
|---|---|---|
| `FAILED` | Failed | The tool refused; the reason is in the error code and message. |
| `VALIDATION_ERROR` | Invalid request | Invalid request. |
| `KEY_CONFLICT` | Key conflict | The key is taken by another object. |
| `NAME_CONFLICT` | Name conflict | The name is taken by another object. |
| `NAME_TAKEN` | Name taken | The name is taken. |
| `LEAD_CONFLICT` | Lead conflict | The existing project has a different lead. |
| `STATUS_CONFLICT` | Status conflict | Status conflict when changing issue types: explicit `statusMappings` are needed. |
| `FIELD_LOCKED` | Field locked | Jira blocks changes to this field. |
| `TEMPLATE_UNAVAILABLE` | No template | The project template is unavailable. |
| `UNSUPPORTED_TEMPLATE` | Template not supported | Control does not create projects from this template. |
| `TOO_MANY_ISSUES` | Too many issues | Too many issues affected (limit exceeded). |
| `JIRA_ERROR` | Jira error | Jira returned an error. |
| `ERROR` | Error | Other error. |

## Known discrepancies

- The `COMPLETE` label exists in `control.properties`, but tools do not produce this status in Audit (it is the issue-count status in the read-only `get_project_workflows`).
- `NO_DRAFT` (`publish_workflow_draft`) and `UNCHANGED` from `configure_workflow` are returned without a plan, there is no apply, so they do not reach Audit. Historical rows of the removed editor keep the old tool name and result; the cutover does not rewrite the journal.
