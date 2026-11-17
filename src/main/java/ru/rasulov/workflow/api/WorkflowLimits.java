package ru.rasulov.workflow.api;

/**
 * Size limits of the workflow service. The single source of truth: the validation code reads them from here and
 * {@code llm/workflow-tools.json} repeats them ({@code WorkflowToolsContractTest} keeps them equal). Every new status and
 * every transition is a separate write to Jira, so they bound the work one HTTP request can trigger.
 */
public final class WorkflowLimits {

    /** Characters in a workflow name or a transition name. */
    public static final int MAX_NAME_LENGTH = 255;

    /** Characters in a status name; Jira's own limit for statuses. */
    public static final int MAX_STATUS_NAME_LENGTH = 60;

    /** A workflow needs somewhere to go: an initial status and at least one other. */
    public static final int MIN_STATUSES = 2;

    /** Statuses in one workflow, existing and new together. */
    public static final int MAX_STATUSES = 30;

    /** Transitions in one workflow, ordinary and "from any status" together. */
    public static final int MAX_TRANSITIONS = 100;

    /** Structural edits in one existing-workflow configuration request. */
    public static final int MAX_CHANGES = 100;

    private WorkflowLimits() {
    }
}
