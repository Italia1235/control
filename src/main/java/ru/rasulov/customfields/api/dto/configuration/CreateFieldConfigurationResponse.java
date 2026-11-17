package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.report.ChangeReport;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ConfigurationRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;

import java.util.List;

/** Preview or outcome of creating one field configuration as a copy; nothing is assigned to a scheme or project. */
public final class CreateFieldConfigurationResponse {
    public enum Status { WOULD_CREATE, CREATED, FAILED }

    private final boolean dryRun;
    private final Status status;
    private final String name;
    private final String description;
    private final ConfigurationRef copyFrom;
    private final ConfigurationRef fieldConfiguration;
    private final ErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;
    private final ChangeReport report;

    private CreateFieldConfigurationResponse(boolean dryRun, Status status, String name, String description,
                                             ConfigurationRef copyFrom, ConfigurationRef fieldConfiguration,
                                             ErrorCode errorCode, String message, List<String> suggestions,
                                             String planId, Long expiresAt, ChangeReport report) {
        this.dryRun = dryRun;
        this.status = status;
        this.name = name;
        this.description = description;
        this.copyFrom = copyFrom;
        this.fieldConfiguration = fieldConfiguration;
        this.errorCode = errorCode;
        this.message = message;
        this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId;
        this.expiresAt = expiresAt;
        this.report = report;
    }

    public static CreateFieldConfigurationResponse planned(String name, String description, ConfigurationRef copyFrom) {
        return new CreateFieldConfigurationResponse(true, Status.WOULD_CREATE, name, description, copyFrom, null, null,
                "The new field configuration is an exact copy of copyFrom and is not used by any scheme or project "
                        + "until a scheme maps an issue type to it.", null, null, null, null);
    }

    /**
     * The terminal outcome. The new configuration's Jira id is {@code fieldConfiguration.id}; in a managed operation it
     * is at {@code result.fieldConfiguration.id}, and create_field_configuration_scheme takes it as the row's
     * {@code fieldConfiguration}.
     */
    public static CreateFieldConfigurationResponse created(String name, String description, ConfigurationRef copyFrom,
                                                           ConfigurationRef created, ChangeReport report) {
        return new CreateFieldConfigurationResponse(false, Status.CREATED, name, description, copyFrom, created, null,
                "Created; not used by any scheme or project yet. Its id is fieldConfiguration.id; pass it as "
                        + "fieldConfiguration of a create_field_configuration_scheme row. Do not create it again.",
                null, null, null, report);
    }

    public static CreateFieldConfigurationResponse failed(boolean dryRun, ErrorCode code, String message,
                                                          List<String> suggestions) {
        return new CreateFieldConfigurationResponse(dryRun, Status.FAILED, null, null, null, null, code, message,
                suggestions, null, null, null);
    }

    public static CreateFieldConfigurationResponse stale() {
        return failed(false, ErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again; nothing was changed.",
                null);
    }

    public CreateFieldConfigurationResponse withPlan(String id, long expiry) {
        return new CreateFieldConfigurationResponse(true, status, name, description, copyFrom, fieldConfiguration,
                errorCode, message, suggestions, id, expiry, report);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("undoWarning") public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("copyFrom") public ConfigurationRef getCopyFrom() { return copyFrom; }
    @JsonProperty("fieldConfiguration") public ConfigurationRef getFieldConfiguration() { return fieldConfiguration; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
    /** What the apply created, with the canonical id; {@code null} in a preview. */
    @JsonProperty("report") public ChangeReport getReport() { return report; }
}
