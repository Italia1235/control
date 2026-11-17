package ru.rasulov.workflow.api.dto.publish;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Outcome of {@code publish_workflow_draft}. Built with the factories: each status has its own set of fields. */
public final class PublishWorkflowDraftResponse {

    private final boolean dryRun;
    private final PublishStatus status;
    private final String workflow;
    private final DraftChanges changes;
    private final Integer affectedSchemes;
    private final Integer affectedProjects;
    private final List<String> affectedProjectNames;
    private final String message;
    private final PublishErrorCode errorCode;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;

    private PublishWorkflowDraftResponse(boolean dryRun, PublishStatus status, String workflow, DraftChanges changes,
                                         Integer affectedSchemes, Integer affectedProjects,
                                         List<String> affectedProjectNames, String message,
                                         PublishErrorCode errorCode, List<String> suggestions, String planId,
                                         Long expiresAt) {
        this.dryRun = dryRun;
        this.status = status;
        this.workflow = workflow;
        this.changes = changes;
        this.affectedSchemes = affectedSchemes;
        this.affectedProjects = affectedProjects;
        this.affectedProjectNames = Immutables.listOf(affectedProjectNames);
        this.message = message;
        this.errorCode = errorCode;
        this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId;
        this.expiresAt = expiresAt;
    }

    /** @param projectNames at most the shown limit; {@code affectedProjects} is the full count */
    public static PublishWorkflowDraftResponse wouldPublish(String workflow, DraftChanges changes, int schemes,
                                                            int projects, List<String> projectNames, String message) {
        return new PublishWorkflowDraftResponse(true, PublishStatus.WOULD_PUBLISH, workflow, changes, schemes,
                projects, projectNames, message, null, null, null, null);
    }

    public static PublishWorkflowDraftResponse published(String workflow, DraftChanges changes, int schemes,
                                                         int projects, List<String> projectNames, String message) {
        return new PublishWorkflowDraftResponse(false, PublishStatus.PUBLISHED, workflow, changes, schemes, projects,
                projectNames, message, null, null, null, null);
    }

    public static PublishWorkflowDraftResponse noDraft(boolean dryRun, String workflow) {
        return new PublishWorkflowDraftResponse(dryRun, PublishStatus.NO_DRAFT, workflow, null, null, null, null,
                "The workflow '" + workflow + "' has no draft: nothing to publish, nothing was changed.", null, null,
                null, null);
    }

    /** @param workflow {@code null} when the request named none */
    public static PublishWorkflowDraftResponse failed(boolean dryRun, String workflow, PublishErrorCode errorCode,
                                                      String message, List<String> suggestions) {
        return new PublishWorkflowDraftResponse(dryRun, PublishStatus.FAILED, workflow, null, null, null, null,
                message, errorCode, suggestions, null, null);
    }

    public static PublishWorkflowDraftResponse stale() {
        return failed(false, null, PublishErrorCode.PREVIEW_STALE, "Preview is missing, expired, foreign, consumed, "
                + "or the workflow, its draft or its users changed. Nothing was published. Run preview again.", null);
    }

    public PublishWorkflowDraftResponse withPlan(String id, long expiry) {
        return new PublishWorkflowDraftResponse(true, status, workflow, changes, affectedSchemes, affectedProjects,
                affectedProjectNames, message, errorCode, suggestions, id, expiry);
    }

    @JsonProperty("dryRun")
    public boolean isDryRun() { return dryRun; }

    @JsonProperty("undoWarning")
    public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }

    @JsonProperty("status")
    public PublishStatus getStatus() { return status; }

    @JsonProperty("workflow")
    public String getWorkflow() { return workflow; }

    /** {@code null} unless {@code WOULD_PUBLISH} or {@code PUBLISHED}. */
    @JsonProperty("changes")
    public DraftChanges getChanges() { return changes; }

    /** Workflow schemes (including scheme drafts) that use the workflow; {@code null} when unknown. */
    @JsonProperty("affectedSchemes")
    public Integer getAffectedSchemes() { return affectedSchemes; }

    /** Every project whose workflow changes; {@code null} when unknown, never a stand-in zero. */
    @JsonProperty("affectedProjects")
    public Integer getAffectedProjects() { return affectedProjects; }

    @JsonProperty("affectedProjectNames")
    public List<String> getAffectedProjectNames() { return affectedProjectNames; }

    @JsonProperty("message")
    public String getMessage() { return message; }

    @JsonProperty("errorCode")
    public PublishErrorCode getErrorCode() { return errorCode; }

    @JsonProperty("suggestions")
    public List<String> getSuggestions() { return suggestions; }

    @JsonProperty("planId")
    public String getPlanId() { return planId; }

    @JsonProperty("expiresAt")
    public Long getExpiresAt() { return expiresAt; }
}
