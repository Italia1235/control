package ru.rasulov.workflow.api;

/**
 * Size limits of the workflow assignment. The single source of truth: the code reads them from here and
 * {@code llm/workflow-tools.json} repeats them ({@code WorkflowToolsContractTest} keeps them equal). The transfer of
 * issues is synchronous, so the number of issues bounds how long one HTTP request can run.
 */
public final class WorkflowAssignLimits {

    /** Issue types named in one request. */
    public static final int MAX_ISSUE_TYPES = 50;

    /** Issues moved to the new workflow in one call. */
    public static final int MAX_ISSUES_TO_MIGRATE = 10_000;

    /** Names of other projects (of a shared scheme) shown to the caller. */
    public static final int MAX_PROJECTS_SHOWN = 10;

    /** Keys of issues named in a message when something could not be moved back. */
    public static final int MAX_ISSUE_KEYS_SHOWN = 10;

    /** Provisional cooperative budget for native-entry diagnosis; excludes the remaining status-count scan. */
    public static final long NATIVE_DIAGNOSIS_BUDGET_MILLIS = 10_000L;

    /** Workflows named in one {@code clone_project_workflows} request. */
    public static final int MAX_WORKFLOWS_TO_CLONE = 50;

    private WorkflowAssignLimits() {
    }
}
