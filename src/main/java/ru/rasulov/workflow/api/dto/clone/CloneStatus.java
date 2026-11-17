package ru.rasulov.workflow.api.dto.clone;

public enum CloneStatus {
    CLONED,
    /** The scheme is assigned and issues moved, but Jira does not report the result as expected; nothing was repaired. */
    PARTIALLY_CLONED,
    WOULD_CLONE,
    /** Nothing is shared any more (or nothing asked for is): no change is needed. */
    ALREADY_SEPARATE,
    FAILED,
    /**
     * A write may have been made, then an error stopped the call and Jira did not confirm the result on readback (or
     * could not be read). The message says what is confirmed and what is not; nothing was repaired or retried.
     */
    UNKNOWN
}
