package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.report.ChangeReport;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.MappingView;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.SchemeRef;

import java.util.List;

/**
 * Preview or outcome of creating one field configuration scheme. The DEFAULT row has issueTypeId=null. The scheme is
 * never assigned to a project here: {@code assigned} is always false.
 */
public final class CreateFieldConfigurationSchemeResponse {
    public enum Status { WOULD_CREATE, CREATED, FAILED }

    private final boolean dryRun;
    private final Status status;
    private final SchemeRef scheme;
    private final List<MappingView> mappings;
    private final ErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;
    private final ChangeReport report;

    private CreateFieldConfigurationSchemeResponse(boolean dryRun, Status status, SchemeRef scheme,
                                                   List<MappingView> mappings, ErrorCode errorCode, String message,
                                                   List<String> suggestions, String planId, Long expiresAt,
                                                   ChangeReport report) {
        this.dryRun = dryRun;
        this.status = status;
        this.scheme = scheme;
        this.mappings = Immutables.listOf(mappings);
        this.errorCode = errorCode;
        this.message = message;
        this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId;
        this.expiresAt = expiresAt;
        this.report = report;
    }

    public static CreateFieldConfigurationSchemeResponse planned(SchemeRef scheme, List<MappingView> mappings) {
        return new CreateFieldConfigurationSchemeResponse(true, Status.WOULD_CREATE, scheme, mappings, null,
                "The scheme is created without any project. Assign it with assign_field_configuration_scheme.",
                null, null, null, null);
    }

    /**
     * The terminal outcome. The new scheme's Jira id is {@code scheme.id}; in a managed operation it is at
     * {@code result.scheme.id}, and assign_field_configuration_scheme takes it as {@code scheme}.
     */
    public static CreateFieldConfigurationSchemeResponse created(SchemeRef scheme, List<MappingView> mappings,
                                                                 ChangeReport report) {
        return new CreateFieldConfigurationSchemeResponse(false, Status.CREATED, scheme, mappings, null,
                "Created; no project uses it yet. Its id is scheme.id; pass it to "
                        + "assign_field_configuration_scheme. Do not create it again.", null, null, null, report);
    }

    public static CreateFieldConfigurationSchemeResponse failed(boolean dryRun, ErrorCode code, String message,
                                                                List<String> suggestions) {
        return new CreateFieldConfigurationSchemeResponse(dryRun, Status.FAILED, null, null, code, message,
                suggestions, null, null, null);
    }

    public static CreateFieldConfigurationSchemeResponse stale() {
        return failed(false, ErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again; nothing was changed.",
                null);
    }

    public CreateFieldConfigurationSchemeResponse withPlan(String id, long expiry) {
        return new CreateFieldConfigurationSchemeResponse(true, status, scheme, mappings, errorCode, message,
                suggestions, id, expiry, report);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("undoWarning") public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("scheme") public SchemeRef getScheme() { return scheme; }
    @JsonProperty("mappings") public List<MappingView> getMappings() { return mappings; }
    @JsonProperty("assigned") public boolean isAssigned() { return false; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
    /** What the apply created and reused, with canonical ids; {@code null} in a preview. */
    @JsonProperty("report") public ChangeReport getReport() { return report; }
}
