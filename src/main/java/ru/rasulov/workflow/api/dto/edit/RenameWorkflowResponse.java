package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Outcome of {@code rename_workflow}. */
public final class RenameWorkflowResponse {

    public enum Status { WOULD_RENAME, RENAMED, UNCHANGED, FAILED }

    public enum ErrorCode {
        VALIDATION_ERROR, UNKNOWN_WORKFLOW, SYSTEM_WORKFLOW,
        /** The workflow is used by a project: Jira renames only a workflow that is not used. */
        ACTIVE_WORKFLOW,
        /** Issues have workflow entries under the current name; renaming would orphan them. */
        IN_USE,
        NAME_TAKEN,
        /** Jira's own validation refuses the rename; the message carries Jira's reasons. */
        RENAME_REFUSED,
        PREVIEW_STALE,
        JIRA_ERROR
    }

    private final boolean dryRun;
    private final Status status;
    private final String workflow;
    private final String newName;
    private final List<String> schemes;
    private final String message;
    private final ErrorCode errorCode;
    private final String planId;
    private final Long expiresAt;

    private RenameWorkflowResponse(boolean dryRun, Status status, String workflow, String newName,
                                   List<String> schemes, String message, ErrorCode errorCode, String planId,
                                   Long expiresAt) {
        this.dryRun = dryRun;
        this.status = status;
        this.workflow = workflow;
        this.newName = newName;
        this.schemes = Immutables.listOf(schemes);
        this.message = message;
        this.errorCode = errorCode;
        this.planId = planId;
        this.expiresAt = expiresAt;
    }

    public static RenameWorkflowResponse wouldRename(String workflow, String newName, List<String> schemes,
                                                     String message) {
        return new RenameWorkflowResponse(true, Status.WOULD_RENAME, workflow, newName, schemes, message, null, null,
                null);
    }

    public static RenameWorkflowResponse renamed(String workflow, String newName, List<String> schemes,
                                                 String message) {
        return new RenameWorkflowResponse(false, Status.RENAMED, workflow, newName, schemes, message, null, null,
                null);
    }

    public static RenameWorkflowResponse unchanged(String workflow) {
        return new RenameWorkflowResponse(true, Status.UNCHANGED, workflow, workflow, null,
                "The workflow already has this name. Nothing was changed.", null, null, null);
    }

    public static RenameWorkflowResponse failed(boolean dryRun, String workflow, ErrorCode code, String message) {
        return new RenameWorkflowResponse(dryRun, Status.FAILED, workflow, null, null, message, code, null, null);
    }

    public static RenameWorkflowResponse stale() {
        return failed(false, null, ErrorCode.PREVIEW_STALE, "Preview is missing, expired, foreign, consumed, or the "
                + "workflow, its schemes or the target name changed. Nothing was renamed. Run preview again.");
    }

    public RenameWorkflowResponse withPlan(String id, long expiry) {
        return new RenameWorkflowResponse(true, status, workflow, newName, schemes, message, errorCode, id, expiry);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("undoWarning") public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("workflow") public String getWorkflow() { return workflow; }
    @JsonProperty("newName") public String getNewName() { return newName; }
    /** Schemes (including scheme drafts) whose mapping to the workflow Jira rewrites to the new name. */
    @JsonProperty("schemes") public List<String> getSchemes() { return schemes; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
}
