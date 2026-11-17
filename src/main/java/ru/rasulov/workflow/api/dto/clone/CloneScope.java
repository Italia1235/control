package ru.rasulov.workflow.api.dto.clone;

/** What of the project's workflow setup gets its own copy. */
public enum CloneScope {
    /** Only the workflow scheme; the project keeps using the same workflows. */
    SCHEME_ONLY,
    /** The scheme and every workflow of it that other projects use too. */
    ALL_WORKFLOWS,
    /** The scheme and the workflows named in the request. */
    SELECTED
}
