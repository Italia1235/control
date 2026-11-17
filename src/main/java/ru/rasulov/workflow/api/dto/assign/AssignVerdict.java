package ru.rasulov.workflow.api.dto.assign;

/** What the assignment does with one issue type of the project. */
public enum AssignVerdict {
    /** The issue type already uses this workflow; nothing to do. */
    ALREADY,
    /** The project has no issues of this type: only the scheme changes. */
    READY,
    /** There are issues and every occupied status can be kept or has an explicit target mapping. */
    MIGRATE,
    /** Some occupied statuses are absent from the workflow and have no explicit mapping; the whole call is refused. */
    CONFLICT
}
