package ru.rasulov.workflow.api.dto.clone;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.assign.SchemeChange;
import ru.rasulov.workflow.api.dto.assign.SchemeView;

import java.util.List;

/** Outcome of {@code clone_project_workflows}. Built with the factories: each status has its own set of fields. */
public final class CloneWorkflowsResponse {

    private final boolean dryRun;
    private final CloneStatus status;
    private final ProjectRef project;
    private final CloneScope scope;
    private final SchemeView currentScheme;
    private final SchemeChange scheme;
    private final List<WorkflowCloneView> workflows;
    private final String message;
    private final CloneErrorCode errorCode;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;

    private CloneWorkflowsResponse(boolean dryRun, CloneStatus status, ProjectRef project, CloneScope scope,
                                   SchemeView currentScheme, SchemeChange scheme, List<WorkflowCloneView> workflows,
                                   String message, CloneErrorCode errorCode, List<String> suggestions,
                                   String planId, Long expiresAt) {
        this.dryRun = dryRun;
        this.status = status;
        this.project = project;
        this.scope = scope;
        this.currentScheme = currentScheme;
        this.scheme = scheme;
        this.workflows = Immutables.listOf(workflows);
        this.message = message;
        this.errorCode = errorCode;
        this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId;
        this.expiresAt = expiresAt;
    }

    /** @param message warnings for the user, or {@code null} */
    public static CloneWorkflowsResponse cloned(ProjectRef project, CloneScope scope, SchemeView currentScheme,
                                                SchemeChange scheme, List<WorkflowCloneView> workflows,
                                                String message) {
        return new CloneWorkflowsResponse(false, CloneStatus.CLONED, project, scope, currentScheme, scheme, workflows,
                message, null, null, null, null);
    }

    /** @param message what Jira reports differently from the plan */
    public static CloneWorkflowsResponse partiallyCloned(ProjectRef project, CloneScope scope,
                                                         SchemeView currentScheme, SchemeChange scheme,
                                                         List<WorkflowCloneView> workflows, String message) {
        return new CloneWorkflowsResponse(false, CloneStatus.PARTIALLY_CLONED, project, scope, currentScheme, scheme,
                workflows, message, null, null, null, null);
    }

    /** An error stopped the call after a write may have been made and the result is not confirmed. */
    public static CloneWorkflowsResponse unknown(ProjectRef project, CloneScope scope, SchemeView currentScheme,
                                                 SchemeChange scheme, List<WorkflowCloneView> workflows,
                                                 String message) {
        return new CloneWorkflowsResponse(false, CloneStatus.UNKNOWN, project, scope, currentScheme, scheme,
                workflows, message, null, null, null, null);
    }

    /** @param message warnings for the user, or {@code null} */
    public static CloneWorkflowsResponse wouldClone(ProjectRef project, CloneScope scope, SchemeView currentScheme,
                                                    SchemeChange scheme, List<WorkflowCloneView> workflows,
                                                    String message) {
        return new CloneWorkflowsResponse(true, CloneStatus.WOULD_CLONE, project, scope, currentScheme, scheme,
                workflows, message, null, null, null, null);
    }

    /** @param dryRun whether the call was a dry run; the answer is the same either way */
    public static CloneWorkflowsResponse alreadySeparate(boolean dryRun, ProjectRef project, CloneScope scope,
                                                         SchemeView currentScheme, List<WorkflowCloneView> workflows) {
        return new CloneWorkflowsResponse(dryRun, CloneStatus.ALREADY_SEPARATE, project, scope, currentScheme,
                new SchemeChange(SchemeAction.NONE, null, null), workflows,
                "For a change that cannot be made in a draft, use copy_workflow, edit the copy, then assign_workflow with statusMappings.",
                null, null, null, null);
    }

    /**
     * @param project {@code null} when the project could not be resolved
     * @param scope   {@code null} when the request had none
     */
    public static CloneWorkflowsResponse failed(boolean dryRun, ProjectRef project, CloneScope scope,
                                                CloneErrorCode errorCode, String message, List<String> suggestions) {
        return new CloneWorkflowsResponse(dryRun, CloneStatus.FAILED, project, scope, null, null, null, message,
                errorCode, suggestions, null, null);
    }

    public CloneWorkflowsResponse withPlan(String id, long expiry) {
        return new CloneWorkflowsResponse(true, status, project, scope, currentScheme, scheme, workflows, message,
                errorCode, suggestions, id, expiry);
    }

    public static CloneWorkflowsResponse stale() {
        return failed(false, null, null, CloneErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again.", null);
    }

    @JsonProperty("dryRun")
    public boolean isDryRun() {
        return dryRun;
    }

    @JsonProperty("undoWarning")
    public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }

    @JsonProperty("status")
    public CloneStatus getStatus() {
        return status;
    }

    /** {@code null} when the project could not be resolved. */
    @JsonProperty("project")
    public ProjectRef getProject() {
        return project;
    }

    @JsonProperty("scope")
    public CloneScope getScope() {
        return scope;
    }

    /** The project's scheme before the call and who shares it; {@code null} for {@code FAILED}. */
    @JsonProperty("currentScheme")
    public SchemeView getCurrentScheme() {
        return currentScheme;
    }

    /**
     * What happens to the scheme: {@code CREATE} (a copy, under the name shown; no id in a dry run), {@code UPDATE}
     * (the project's own scheme now points at the copies) or {@code NONE}; {@code null} for {@code FAILED}.
     */
    @JsonProperty("scheme")
    public SchemeChange getScheme() {
        return scheme;
    }

    /** Every workflow of the scheme with its verdict; empty for {@code FAILED}. */
    @JsonProperty("workflows")
    public List<WorkflowCloneView> getWorkflows() {
        return workflows;
    }

    /** Warnings for the user; for {@code FAILED} what to fix. */
    @JsonProperty("message")
    public String getMessage() {
        return message;
    }

    /** Set only for {@code FAILED}. */
    @JsonProperty("errorCode")
    public CloneErrorCode getErrorCode() {
        return errorCode;
    }

    /** Set only for {@code FAILED}: valid values to choose from, when there are any. */
    @JsonProperty("suggestions")
    public List<String> getSuggestions() {
        return suggestions;
    }

    @JsonProperty("planId")
    public String getPlanId() { return planId; }
    @JsonProperty("expiresAt")
    public Long getExpiresAt() { return expiresAt; }
}
