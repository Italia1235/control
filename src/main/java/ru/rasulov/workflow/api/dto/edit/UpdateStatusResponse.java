package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Outcome of {@code update_status}. The impact lists are complete in the counts and bounded in the names. */
public final class UpdateStatusResponse {

    public enum Status { WOULD_UPDATE, UPDATED, UNCHANGED, FAILED }

    public enum ErrorCode { VALIDATION_ERROR, UNKNOWN_STATUS, UPDATE_REFUSED, PREVIEW_STALE, JIRA_ERROR }

    private final boolean dryRun;
    private final Status status;
    private final String statusId;
    private final List<String> changes;
    private final int workflowCount;
    private final int projectCount;
    private final List<String> workflows;
    private final List<String> projects;
    private final List<String> warnings;
    private final String message;
    private final ErrorCode errorCode;
    private final String planId;
    private final Long expiresAt;

    private UpdateStatusResponse(boolean dryRun, Status status, String statusId, List<String> changes,
                                 int workflowCount, int projectCount, List<String> workflows, List<String> projects,
                                 List<String> warnings, String message, ErrorCode errorCode, String planId,
                                 Long expiresAt) {
        this.dryRun = dryRun;
        this.status = status;
        this.statusId = statusId;
        this.changes = Immutables.listOf(changes);
        this.workflowCount = workflowCount;
        this.projectCount = projectCount;
        this.workflows = Immutables.listOf(workflows);
        this.projects = Immutables.listOf(projects);
        this.warnings = Immutables.listOf(warnings);
        this.message = message;
        this.errorCode = errorCode;
        this.planId = planId;
        this.expiresAt = expiresAt;
    }

    public static UpdateStatusResponse wouldUpdate(String statusId, List<String> changes, int workflowCount,
                                                   int projectCount, List<String> workflows, List<String> projects,
                                                   List<String> warnings, String message) {
        return new UpdateStatusResponse(true, Status.WOULD_UPDATE, statusId, changes, workflowCount, projectCount,
                workflows, projects, warnings, message, null, null, null);
    }

    public static UpdateStatusResponse updated(String statusId, List<String> changes, int workflowCount,
                                               int projectCount, List<String> workflows, List<String> projects,
                                               List<String> warnings, String message) {
        return new UpdateStatusResponse(false, Status.UPDATED, statusId, changes, workflowCount, projectCount,
                workflows, projects, warnings, message, null, null, null);
    }

    public static UpdateStatusResponse unchanged(String statusId) {
        return new UpdateStatusResponse(true, Status.UNCHANGED, statusId, null, 0, 0, null, null, null,
                "The status already has these values. Nothing was changed.", null, null, null);
    }

    public static UpdateStatusResponse failed(boolean dryRun, String statusId, ErrorCode code, String message) {
        return new UpdateStatusResponse(dryRun, Status.FAILED, statusId, null, 0, 0, null, null, null, message, code,
                null, null);
    }

    public static UpdateStatusResponse stale() {
        return failed(false, null, ErrorCode.PREVIEW_STALE, "Preview is missing, expired, foreign, consumed, or the "
                + "status or the workflows that use it changed. Nothing was updated. Run preview again.");
    }

    public UpdateStatusResponse withPlan(String id, long expiry) {
        return new UpdateStatusResponse(true, status, statusId, changes, workflowCount, projectCount, workflows,
                projects, warnings, message, errorCode, id, expiry);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("undoWarning") public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("statusId") public String getStatusId() { return statusId; }
    @JsonProperty("changes") public List<String> getChanges() { return changes; }
    /** Workflows (active, inactive and drafts) that use the status; the change is global, not per workflow. */
    @JsonProperty("workflowCount") public int getWorkflowCount() { return workflowCount; }
    @JsonProperty("projectCount") public int getProjectCount() { return projectCount; }
    @JsonProperty("workflows") public List<String> getWorkflows() { return workflows; }
    @JsonProperty("projects") public List<String> getProjects() { return projects; }
    @JsonProperty("warnings") public List<String> getWarnings() { return warnings; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
}
