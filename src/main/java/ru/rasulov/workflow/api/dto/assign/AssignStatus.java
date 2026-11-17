package ru.rasulov.workflow.api.dto.assign;

/** What happened to one {@code assign_workflow} call. */
public enum AssignStatus {
    /** The workflow is assigned, every issue was migrated, and the final sweep completed. */
    ASSIGNED,
    /** The scheme is assigned, but the final sweep could not migrate or verify every concurrent issue. */
    PARTIALLY_ASSIGNED,
    /** Dry run: this would be assigned. */
    WOULD_ASSIGN,
    /** Every requested issue type uses the workflow already; nothing was written. */
    ALREADY_ASSIGNED,
    /** Nothing was assigned; see the error code and the message. */
    FAILED,
    /**
     * A write may have been made, then an error stopped the call and Jira did not confirm the assignment on readback
     * (or could not be read). The message says what is confirmed and what is not; nothing was repaired or retried.
     */
    UNKNOWN
}
