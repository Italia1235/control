package ru.rasulov.workflow.api.dto.edit;

/** Why a structural configure_workflow change did not produce an applicable plan or did not finish. */
public enum EditErrorCode {
    VALIDATION_ERROR,
    UNKNOWN_WORKFLOW,
    UNKNOWN_STEP,
    UNKNOWN_TRANSITION,
    UNKNOWN_STATUS,
    UNKNOWN_SCREEN,
    UNKNOWN_RESOLUTION,
    /** The built-in Jira workflow cannot be edited in place. */
    SYSTEM_WORKFLOW,
    /** The active workflow already has a draft and version=ACTIVE was asked: read and choose DRAFT explicitly. */
    DRAFT_EXISTS,
    /** The revision of the request is not the revision of the workflow now, or the requested version is missing. */
    VERSION_CONFLICT,
    /** The change touches a structure that cannot be patched exactly (conditional results, common actions, ...). */
    UNSUPPORTED_DESCRIPTOR,
    /** Replacing or removing a status of an active workflow needs issue migration, which is not available here. */
    MIGRATION_REQUIRED,
    /** Preview token is absent, expired, foreign, consumed, or the workflow, draft or its users changed. */
    PREVIEW_STALE,
    /** Jira refused or failed while saving, or the result could not be confirmed. */
    JIRA_ERROR
}
