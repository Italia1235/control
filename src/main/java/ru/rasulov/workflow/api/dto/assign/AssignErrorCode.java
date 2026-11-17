package ru.rasulov.workflow.api.dto.assign;

/** Why an {@code assign_workflow} call did nothing. */
public enum AssignErrorCode {
    /** The request is malformed: blank workflow, both or neither of allIssueTypes and issueTypes, a limit, a duplicate. */
    VALIDATION_ERROR,
    UNKNOWN_PROJECT,
    UNKNOWN_ISSUE_TYPE,
    /** A named issue type is not one of the project's issue types. */
    ISSUE_TYPE_NOT_IN_PROJECT,
    UNKNOWN_WORKFLOW,
    /** Occupied statuses absent from the target workflow have no explicit mappings yet. */
    STATUS_CONFLICT,
    /** More issues would move than one call may move. */
    TOO_MANY_ISSUES,
    /** The project's scheme is shared with other projects and the caller did not ask for a new one. */
    SCHEME_SHARED,
    /** The project's scheme has a draft, which would overwrite the change when published. */
    DRAFT_EXISTS,
    /** Preview token is absent, expired, foreign, consumed, or no longer matches Jira. */
    PREVIEW_STALE,
    /** Jira failed while writing; what could be undone was undone. */
    JIRA_ERROR
}
