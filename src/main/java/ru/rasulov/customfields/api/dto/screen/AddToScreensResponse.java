package ru.rasulov.customfields.api.dto.screen;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Output of {@code add_field_to_screens}; {@code fields} follow the order of the request's {@code fieldIds}. */
public final class AddToScreensResponse {

    private final boolean dryRun;
    private final List<FieldScreensResult> fields;
    private final String planId;
    private final Long expiresAt;
    private final String errorCode;
    private final String message;

    public AddToScreensResponse(boolean dryRun, List<FieldScreensResult> fields) {
        this(dryRun, fields, null, null, null, null);
    }

    private AddToScreensResponse(boolean dryRun, List<FieldScreensResult> fields, String planId, Long expiresAt,
                                 String errorCode, String message) {
        this.dryRun = dryRun;
        this.fields = Immutables.listOf(fields);
        this.planId = planId;
        this.expiresAt = expiresAt;
        this.errorCode = errorCode;
        this.message = message;
    }

    @JsonProperty("dryRun")
    public boolean isDryRun() {
        return dryRun;
    }

    @JsonProperty("undoWarning")
    public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }

    @JsonProperty("fields")
    public List<FieldScreensResult> getFields() {
        return fields;
    }

    public AddToScreensResponse withPlan(String id, long expiry) {
        return new AddToScreensResponse(true, fields, id, expiry, null, null);
    }

    public static AddToScreensResponse stale() {
        return new AddToScreensResponse(false, null, null, null, "PREVIEW_STALE",
                "The preview is missing, expired, belongs to another actor, was applied, or Jira state changed. "
                        + "Run preview again; nothing was changed.");
    }

    @JsonProperty("planId")
    public String getPlanId() { return planId; }
    @JsonProperty("expiresAt")
    public Long getExpiresAt() { return expiresAt; }
    @JsonProperty("errorCode")
    public String getErrorCode() { return errorCode; }
    @JsonProperty("message")
    public String getMessage() { return message; }
}
