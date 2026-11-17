package ru.rasulov.workflow.api.dto;

/** Why a workflow was not created. Stable: the model reads it. */
public enum WorkflowErrorCode {
    /** A value is missing, empty, too long, repeated or otherwise not acceptable. */
    VALIDATION_ERROR,
    /** {@code initialStatus} or a transition names a status that is not in {@code statuses}. */
    UNKNOWN_STATUS,
    /** A transition screen id or exact name does not identify one existing Jira screen. */
    UNKNOWN_SCREEN,
    /** The exact screen name identifies more than one screen; an ID is required. */
    AMBIGUOUS_SCREEN,
    /** An existing.newStatus name identifies multiple global statuses; an ID is required. */
    AMBIGUOUS_STATUS,
    /** A status that does not exist in Jira yet was given without a category. */
    CATEGORY_REQUIRED,
    /** A workflow of this name exists and its statuses or transitions differ. It is not changed. */
    NAME_CONFLICT,
    /** The exact source workflow does not exist. */
    UNKNOWN_WORKFLOW,
    /** The requested copy name is already used, ignoring case. */
    NAME_TAKEN,
    /** Preview token is absent, expired, foreign, consumed, or no longer matches Jira. */
    PREVIEW_STALE,
    /** Jira refused a write; details are in the Jira log. */
    JIRA_ERROR
}
