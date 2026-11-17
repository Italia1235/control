package ru.rasulov.workflow.api.dto.assign;

/** What the assignment does with the project's workflow scheme. */
public enum SchemeAction {
    /** Nothing: every issue type already has the workflow. */
    NONE,
    /** The project's own scheme is changed in place. */
    UPDATE,
    /** A new scheme is created and given to the project. */
    CREATE
}
