package ru.rasulov.workflow.api.dto.clone;

/** Why a {@code clone_project_workflows} call did nothing. */
public enum CloneErrorCode {
    /** The request is malformed: no scope, workflows with a scope that takes none, a blank or repeated name, a limit. */
    VALIDATION_ERROR,
    UNKNOWN_PROJECT,
    /** A named workflow does not exist at all. */
    UNKNOWN_WORKFLOW,
    /** A named workflow exists but is not in the project's workflow scheme. */
    WORKFLOW_NOT_IN_SCHEME,
    /** A name the caller chose for a copy is used already. */
    NAME_TAKEN,
    /** The scheme has a draft; copying would silently leave the draft out. */
    DRAFT_EXISTS,
    /** More issues would have to move to the copies than one call may move. */
    TOO_MANY_ISSUES,
    /** Preview token is absent, expired, foreign, consumed, or no longer matches Jira. */
    PREVIEW_STALE,
    /** Jira failed while writing; what could be undone was undone. */
    JIRA_ERROR
}
