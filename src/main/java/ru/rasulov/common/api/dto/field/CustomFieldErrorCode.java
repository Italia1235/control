package ru.rasulov.common.api.dto.field;

public enum CustomFieldErrorCode {
    /** Empty or too long name, duplicate name in the call, bad options or contexts, a limit exceeded. */
    VALIDATION_ERROR,
    /** The type is not installed in Jira. */
    UNKNOWN_TYPE,
    /** The searcher does not exist or does not fit the type. */
    INCOMPATIBLE_SEARCHER,
    /** A field with this name exists with another type, or several such fields exist. */
    NAME_CONFLICT,
    /** No context, or a context that does not say which projects and which issue types it covers. */
    CONTEXT_REQUIRED,
    /** No project has this key or name. */
    UNKNOWN_PROJECT,
    /** No issue type has this name or id. */
    UNKNOWN_ISSUE_TYPE,
    /** The field exists, but its contexts, projects, issue types or options read back from Jira differ from the plan. */
    CONTEXT_MISMATCH,
    /** Jira failed to create the field. */
    JIRA_ERROR
}
