package ru.rasulov.common.api;

/**
 * Size limits of the lookup service ({@link LookupService}). They stay here, not with a feature's limits, because every
 * feature that names projects or issue types uses the same lookups.
 */
public final class LookupLimits {

    /** Projects returned by {@code list_projects} when the caller gives no limit. */
    public static final int DEFAULT_PROJECT_LIST_SIZE = 20;

    /** Upper bound of the {@code limit} of {@code list_projects}. */
    public static final int MAX_PROJECT_LIST_SIZE = 100;

    /** Entries returned by issue-type and status lookups when the caller gives no limit. */
    public static final int DEFAULT_REFERENCE_LIST_SIZE = 20;

    /** Upper bound of an issue-type or status lookup. */
    public static final int MAX_REFERENCE_LIST_SIZE = 100;

    /** Names or keys offered as suggestions when a project or an issue type is not found. */
    public static final int MAX_SUGGESTIONS = 5;

    private LookupLimits() {
    }
}
