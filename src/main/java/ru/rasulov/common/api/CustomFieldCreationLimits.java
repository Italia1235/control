package ru.rasulov.common.api;

/** Limits shared by field creation and features that orchestrate it. */
public final class CustomFieldCreationLimits {
    /** Field definitions or selections in one call. */
    public static final int MAX_FIELDS_PER_CALL = 50;

    private CustomFieldCreationLimits() {
    }
}
