package ru.rasulov.workflow.api.dto;

/** What happened to a standalone workflow copy. */
public enum WorkflowResultStatus {
    /** Created. */
    CREATED,
    /** Dry run: this would be created. */
    WOULD_CREATE,
    /** A workflow of this name with the same statuses and transitions exists already; nothing was written. */
    EXISTS,
    /** Not created; see the error code and the message. */
    FAILED
}
