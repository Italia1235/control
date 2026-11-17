package ru.rasulov.customfields.api;

/**
 * Size limits of the services. The single source of truth: the validation code reads them from here and
 * {@code llm/customfields-tools.json} repeats the ones expressible in JSON Schema ({@code ToolsContractTest} keeps them equal).
 * They bound the work one HTTP request can trigger: every field, context, option and screen change is a separate
 * write to Jira.
 */
public final class Limits {

    /** Characters in a field name or a context name. */
    public static final int MAX_NAME_LENGTH = 255;

    /** Characters in the description of a context; same bound as a name. */
    public static final int MAX_CONTEXT_DESCRIPTION_LENGTH = 255;

    /** Characters in an option value. */
    public static final int MAX_OPTION_LENGTH = 255;

    /** Contexts of one field. */
    public static final int MAX_CONTEXTS_PER_FIELD = 10;

    /** Projects listed in one context. */
    public static final int MAX_PROJECTS_PER_CONTEXT = 100;

    /** Issue types listed in one context. */
    public static final int MAX_ISSUE_TYPES_PER_CONTEXT = 50;

    /** Fields changed by one {@code set_field_required} preview/apply. */
    public static final int MAX_FIELDS_PER_REQUIREMENT_CALL = 10;

    /** Rows of one {@code set_field_required} call in the per-row form. */
    public static final int MAX_REQUIREMENT_ROWS = 20;

    /** Top-level options of one option list (a field's, or one context's own). */
    public static final int MAX_ROOT_OPTIONS = 100;

    /** Child options under one parent option (cascading select). */
    public static final int MAX_CHILD_OPTIONS_PER_PARENT = 100;

    /** All options of one option list, top-level and children together. */
    public static final int MAX_OPTIONS_PER_CONTEXT = 500;

    /**
     * All options one call writes, top-level and children together. A list shared by several contexts is written once
     * per context and counted that many times.
     */
    public static final int MAX_OPTIONS_PER_CALL = 2000;

    /** Screens a screen plan lists; more are counted in {@code total} and the plan says it was cut. */
    public static final int MAX_SCREENS_IN_PLAN = 200;

    /** Screens one {@code add_field_to_screens} call changes. */
    public static final int MAX_SCREENS_PER_CALL = 100;

    /** Field-on-screen additions one {@code add_field_to_screens} call makes: fields times screens. */
    public static final int MAX_SCREEN_ADDITIONS_PER_CALL = 500;

    /** Project names shown per screen in a plan; the rest are only counted. */
    public static final int MAX_PROJECTS_SHOWN = 10;

    private Limits() {
    }
}
