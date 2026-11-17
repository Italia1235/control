package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.report.ChangeReport;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.field.context.ContextSummary;

import java.util.List;

/** Preview or outcome of creating one unassigned screen and provisioning its missing custom fields. */
public final class CreateScreenResponse {
    public enum Status { WOULD_CREATE, CREATED, FAILED }
    public enum ErrorCode { VALIDATION_ERROR, NAME_TAKEN, FIELD_ERROR, JIRA_ERROR, PREVIEW_STALE }

    private final boolean dryRun;
    private final Status status;
    private final Long screenId;
    private final String name;
    private final String description;
    private final String tab;
    private final List<FieldResult> fields;
    private final ErrorCode errorCode;
    private final String message;
    private final String planId;
    private final Long expiresAt;
    private final ChangeReport report;

    private CreateScreenResponse(boolean dryRun, Status status, Long screenId, String name, String description,
                                 String tab, List<FieldResult> fields, ErrorCode errorCode, String message,
                                 String planId, Long expiresAt) {
        this(dryRun, status, screenId, name, description, tab, fields, errorCode, message, planId, expiresAt, null);
    }

    private CreateScreenResponse(boolean dryRun, Status status, Long screenId, String name, String description,
                                 String tab, List<FieldResult> fields, ErrorCode errorCode, String message,
                                 String planId, Long expiresAt, ChangeReport report) {
        this.dryRun = dryRun; this.status = status; this.screenId = screenId; this.name = name;
        this.description = description; this.tab = tab; this.fields = Immutables.listOf(fields);
        this.errorCode = errorCode; this.message = message; this.planId = planId; this.expiresAt = expiresAt;
        this.report = report;
    }

    public static CreateScreenResponse success(boolean dryRun, Long screenId, String name, String description,
                                               String tab, List<FieldResult> fields) {
        return new CreateScreenResponse(dryRun, dryRun ? Status.WOULD_CREATE : Status.CREATED, screenId, name,
                description, tab, fields, null, null, null, null);
    }

    public static CreateScreenResponse failed(boolean dryRun, String name, String description, String tab,
                                              List<FieldResult> fields, ErrorCode code, String message) {
        return new CreateScreenResponse(dryRun, Status.FAILED, null, name, description, tab, fields, code, message,
                null, null);
    }

    public static CreateScreenResponse stale() {
        return failed(false, null, null, null, null, ErrorCode.PREVIEW_STALE,
                "The preview is missing, expired, belongs to another actor, was applied, or Jira state changed. "
                        + "Run preview again; nothing was changed.");
    }

    public CreateScreenResponse withPlan(String id, long expiry) {
        return new CreateScreenResponse(true, status, screenId, name, description, tab, fields, errorCode, message,
                id, expiry);
    }

    public CreateScreenResponse withReport(ChangeReport changes) {
        return new CreateScreenResponse(dryRun, status, screenId, name, description, tab, fields, errorCode, message,
                planId, expiresAt, changes);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("screenId") public Long getScreenId() { return screenId; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("tab") public String getTab() { return tab; }
    @JsonProperty("fields") public List<FieldResult> getFields() { return fields; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
    /** What the apply created and reused, with admin links; {@code null} in a preview. */
    @JsonProperty("report") public ChangeReport getReport() { return report; }

    public static final class FieldResult {
        /** PARTIAL: the field exists (fieldId) but Jira's stored contexts/options differ from the plan; see message. */
        public enum Status { EXISTING, WOULD_CREATE, CREATED, PARTIAL, FAILED }
        private final String requestedName;
        private final String fieldId;
        private final String name;
        private final Status status;
        private final String typeKey;
        private final List<ContextSummary> contexts;
        private final String errorCode;
        private final String message;
        private final List<String> suggestions;

        public FieldResult(String requestedName, String fieldId, String name, Status status, String typeKey,
                           List<ContextSummary> contexts, String errorCode, String message, List<String> suggestions) {
            this.requestedName = requestedName; this.fieldId = fieldId; this.name = name; this.status = status;
            this.typeKey = typeKey; this.contexts = Immutables.listOf(contexts); this.errorCode = errorCode;
            this.message = message; this.suggestions = Immutables.listOf(suggestions);
        }

        @JsonProperty("requestedName") public String getRequestedName() { return requestedName; }
        @JsonProperty("fieldId") public String getFieldId() { return fieldId; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("status") public Status getStatus() { return status; }
        @JsonProperty("typeKey") public String getTypeKey() { return typeKey; }
        @JsonProperty("errorCode") public String getErrorCode() { return errorCode; }
        @JsonProperty("contexts") public List<ContextSummary> getContexts() { return contexts; }
        @JsonProperty("message") public String getMessage() { return message; }
        @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    }
}
