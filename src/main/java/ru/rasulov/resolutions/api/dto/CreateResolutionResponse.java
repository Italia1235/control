package ru.rasulov.resolutions.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.report.ChangeReport;

public final class CreateResolutionResponse {
    public enum Status { WOULD_CREATE, CREATED, EXISTS, FAILED }
    public enum ErrorCode { VALIDATION_ERROR, NAME_CONFLICT, PREVIEW_STALE, JIRA_ERROR }
    public static final String GLOBAL_WARNING = "Resolution is global for all Jira, not just this workflow. "
            + "Resolution creation and workflow creation are separate operations: if workflow creation fails, "
            + "the global Resolution remains. Build a fresh workflow preview using its real ID.";
    private final Status status;
    private final boolean dryRun;
    private final ResolutionView resolution;
    private final String message;
    private final ErrorCode errorCode;
    private final String planId;
    private final Long planExpiresAt;
    private final ChangeReport report;

    private CreateResolutionResponse(Status status, boolean dryRun, ResolutionView resolution, String message,
                                     ErrorCode errorCode, String planId, Long planExpiresAt) {
        this(status, dryRun, resolution, message, errorCode, planId, planExpiresAt, null);
    }

    private CreateResolutionResponse(Status status, boolean dryRun, ResolutionView resolution, String message,
                                     ErrorCode errorCode, String planId, Long planExpiresAt, ChangeReport report) {
        this.status = status; this.dryRun = dryRun; this.resolution = resolution; this.message = message;
        this.errorCode = errorCode; this.planId = planId; this.planExpiresAt = planExpiresAt;
        this.report = report;
    }
    public static CreateResolutionResponse wouldCreate(ResolutionView resolution) {
        return new CreateResolutionResponse(Status.WOULD_CREATE, true, resolution,
                GLOBAL_WARNING + " Show this preview and obtain confirmation before applying planId.", null, null, null);
    }
    public static CreateResolutionResponse created(ResolutionView resolution) {
        return new CreateResolutionResponse(Status.CREATED, false, resolution, GLOBAL_WARNING, null, null, null);
    }
    public static CreateResolutionResponse exists(ResolutionView resolution) {
        return new CreateResolutionResponse(Status.EXISTS, true, resolution,
                "An identical Resolution already exists; nothing was changed.", null, null, null);
    }
    public static CreateResolutionResponse failed(boolean dryRun, ErrorCode error, String message) {
        return new CreateResolutionResponse(Status.FAILED, dryRun, null, message, error, null, null);
    }
    public CreateResolutionResponse withPlan(String id, long expiresAt) {
        return new CreateResolutionResponse(status, dryRun, resolution, message, errorCode, id, expiresAt);
    }
    public CreateResolutionResponse withReport(ChangeReport changes) {
        return new CreateResolutionResponse(status, dryRun, resolution, message, errorCode, planId, planExpiresAt,
                changes);
    }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("resolution") public ResolutionView getResolution() { return resolution; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    /** What the apply created, with its admin link; {@code null} in a preview. */
    @JsonProperty("report") public ChangeReport getReport() { return report; }
    @JsonProperty("planExpiresAt") public Long getPlanExpiresAt() { return planExpiresAt; }
}
