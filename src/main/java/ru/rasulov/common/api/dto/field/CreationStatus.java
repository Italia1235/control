package ru.rasulov.common.api.dto.field;

public enum CreationStatus {
    /** The field was created. */
    CREATED,
    /** Dry run: the field would be created. */
    WOULD_CREATE,
    /** A field with the same name and type already exists; nothing was changed. */
    EXISTS,
    /**
     * The field was created, but Jira's re-read state differs from the plan; {@code contexts} is what Jira reports,
     * not what was requested. Nothing was undone or repaired.
     */
    PARTIAL,
    /** The field was not created; see {@link FieldCreationResult#getErrorCode()}. */
    FAILED
}
