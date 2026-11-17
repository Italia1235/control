package ru.rasulov.workflow.api.dto.assign;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.List;

/** Outcome of {@code assign_workflow}. Built with the factories: each status has its own set of fields. */
public final class AssignWorkflowResponse {

    private final boolean dryRun;
    private final AssignStatus status;
    private final ProjectRef project;
    private final String workflow;
    private final SchemeChange scheme;
    private final List<IssueTypeAssignment> issueTypes;
    private final String message;
    private final AssignErrorCode errorCode;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;

    private AssignWorkflowResponse(boolean dryRun, AssignStatus status, ProjectRef project, String workflow,
                                   SchemeChange scheme, List<IssueTypeAssignment> issueTypes, String message,
                                   AssignErrorCode errorCode, List<String> suggestions, String planId, Long expiresAt) {
        this.dryRun = dryRun;
        this.status = status;
        this.project = project;
        this.workflow = workflow;
        this.scheme = scheme;
        this.issueTypes = Immutables.listOf(issueTypes);
        this.message = message;
        this.errorCode = errorCode;
        this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId;
        this.expiresAt = expiresAt;
    }

    /** @param message warnings for the user, or {@code null} */
    public static AssignWorkflowResponse assigned(ProjectRef project, String workflow, SchemeChange scheme,
                                                  List<IssueTypeAssignment> issueTypes, String message) {
        return new AssignWorkflowResponse(false, AssignStatus.ASSIGNED, project, workflow, scheme, issueTypes, message,
                null, null, null, null);
    }

    /** The scheme is assigned, but the final sweep did not establish a complete migration. */
    public static AssignWorkflowResponse partiallyAssigned(ProjectRef project, String workflow, SchemeChange scheme,
                                                           List<IssueTypeAssignment> issueTypes, String message) {
        return new AssignWorkflowResponse(false, AssignStatus.PARTIALLY_ASSIGNED, project, workflow, scheme, issueTypes,
                message, null, null, null, null);
    }

    /** An error stopped the call after a write may have been made and the assignment is not confirmed. */
    public static AssignWorkflowResponse unknown(ProjectRef project, String workflow, SchemeChange scheme,
                                                 List<IssueTypeAssignment> issueTypes, String message) {
        return new AssignWorkflowResponse(false, AssignStatus.UNKNOWN, project, workflow, scheme, issueTypes,
                message, null, null, null, null);
    }

    /** @param message warnings for the user, or {@code null} */
    public static AssignWorkflowResponse wouldAssign(ProjectRef project, String workflow, SchemeChange scheme,
                                                     List<IssueTypeAssignment> issueTypes, String message) {
        return new AssignWorkflowResponse(true, AssignStatus.WOULD_ASSIGN, project, workflow, scheme, issueTypes,
                message, null, null, null, null);
    }

    /** @param dryRun whether the call was a dry run; the answer is the same either way */
    public static AssignWorkflowResponse alreadyAssigned(boolean dryRun, ProjectRef project, String workflow,
                                                         List<IssueTypeAssignment> issueTypes) {
        return new AssignWorkflowResponse(dryRun, AssignStatus.ALREADY_ASSIGNED, project, workflow,
                new SchemeChange(SchemeAction.NONE, null, null), issueTypes, null, null, null, null, null);
    }

    /**
     * @param project    {@code null} when the project could not be resolved
     * @param issueTypes what was planned, when the failure came after planning ({@code STATUS_CONFLICT} and later)
     */
    public static AssignWorkflowResponse failed(boolean dryRun, ProjectRef project, String workflow,
                                                AssignErrorCode errorCode, String message, List<String> suggestions,
                                                List<IssueTypeAssignment> issueTypes) {
        return new AssignWorkflowResponse(dryRun, AssignStatus.FAILED, project, workflow, null, issueTypes, message,
                errorCode, suggestions, null, null);
    }

    public AssignWorkflowResponse withPlan(String id, long expiry) {
        return new AssignWorkflowResponse(true, status, project, workflow, scheme, issueTypes, message, errorCode,
                suggestions, id, expiry);
    }

    public static AssignWorkflowResponse stale() {
        return failed(false, null, null, AssignErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again.", null, null);
    }

    @JsonProperty("dryRun")
    public boolean isDryRun() {
        return dryRun;
    }

    @JsonProperty("undoWarning")
    public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }

    @JsonProperty("status")
    public AssignStatus getStatus() {
        return status;
    }

    /** {@code null} when the project could not be resolved. */
    @JsonProperty("project")
    public ProjectRef getProject() {
        return project;
    }

    /** The workflow name as it is in Jira; as the caller wrote it when the workflow was not found. */
    @JsonProperty("workflow")
    public String getWorkflow() {
        return workflow;
    }

    /** {@code null} for {@code FAILED}. */
    @JsonProperty("scheme")
    public SchemeChange getScheme() {
        return scheme;
    }

    /**
     * Per issue type: verdict and issues by status. Also present for the failures that come after planning
     * ({@code STATUS_CONFLICT}, {@code TOO_MANY_ISSUES}, {@code SCHEME_SHARED}, {@code DRAFT_EXISTS}), otherwise empty.
     */
    @JsonProperty("issueTypes")
    public List<IssueTypeAssignment> getIssueTypes() {
        return issueTypes;
    }

    /**
     * For {@code FAILED}: what to fix. Otherwise warnings for the user (Jira Software boards, a scheme left without
     * projects, issues that could not be moved), or {@code null}.
     */
    @JsonProperty("message")
    public String getMessage() {
        return message;
    }

    /** Set only for {@code FAILED}. */
    @JsonProperty("errorCode")
    public AssignErrorCode getErrorCode() {
        return errorCode;
    }

    /** Valid values the caller may use instead, or names to show the user (other projects of a shared scheme). */
    @JsonProperty("suggestions")
    public List<String> getSuggestions() {
        return suggestions;
    }

    @JsonProperty("planId")
    public String getPlanId() { return planId; }
    @JsonProperty("expiresAt")
    public Long getExpiresAt() { return expiresAt; }
}
