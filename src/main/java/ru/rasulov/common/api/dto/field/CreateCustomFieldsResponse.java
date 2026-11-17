package ru.rasulov.common.api.dto.field;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.report.ChangeReport;

/** Output of the {@code create_custom_fields} operation; {@code results} follow the order of the request. */
public final class CreateCustomFieldsResponse {

    private final boolean dryRun;
    private final List<FieldCreationResult> results;
    private final String planId;
    private final Long expiresAt;
    private final String errorCode;
    private final String message;
    private final ChangeReport report;

    public CreateCustomFieldsResponse(boolean dryRun, List<FieldCreationResult> results) {
        this(dryRun, results, null, null, null, null, null);
    }

    private CreateCustomFieldsResponse(boolean dryRun, List<FieldCreationResult> results, String planId,
                                       Long expiresAt, String errorCode, String message, ChangeReport report) {
        this.dryRun = dryRun;
        this.results = Immutables.listOf(results);
        this.planId = planId;
        this.expiresAt = expiresAt;
        this.errorCode = errorCode;
        this.message = message;
        this.report = report;
    }

    @JsonProperty("dryRun")
    public boolean isDryRun() {
        return dryRun;
    }

    @JsonProperty("results")
    public List<FieldCreationResult> getResults() {
        return results;
    }

    public CreateCustomFieldsResponse withPlan(String id, long expiry) {
        return new CreateCustomFieldsResponse(true, results, id, expiry, null, null, null);
    }

    public CreateCustomFieldsResponse withReport(ChangeReport changes) {
        return new CreateCustomFieldsResponse(dryRun, results, planId, expiresAt, errorCode, message, changes);
    }

    public static CreateCustomFieldsResponse stale() {
        return new CreateCustomFieldsResponse(false, null, null, null, "PREVIEW_STALE",
                "The preview is missing, expired, belongs to another actor, was applied, or Jira state changed. "
                        + "Run preview again; nothing was changed.", null);
    }

    @JsonProperty("planId")
    public String getPlanId() { return planId; }
    @JsonProperty("expiresAt")
    public Long getExpiresAt() { return expiresAt; }
    @JsonProperty("errorCode")
    public String getErrorCode() { return errorCode; }
    @JsonProperty("message")
    public String getMessage() { return message; }
    /** What the apply created and reused, with admin links; {@code null} in a preview. */
    @JsonProperty("report")
    public ChangeReport getReport() { return report; }
}
