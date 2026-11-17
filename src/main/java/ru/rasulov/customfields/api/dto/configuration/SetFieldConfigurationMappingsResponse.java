package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ConfigurationRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ProjectImpact;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.SchemeRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.Warning;

import java.util.List;

/**
 * Preview or outcome of changing rows of one field configuration scheme: each requested row before and after, and
 * every project of the scheme whose issue types change configuration, with required/hidden field differences.
 */
public final class SetFieldConfigurationMappingsResponse {
    public enum Status { WOULD_CHANGE, CHANGED, NO_CHANGE, FAILED }

    private final boolean dryRun;
    private final Status status;
    private final SchemeRef scheme;
    private final List<RowChange> rows;
    private final int projectsTotal;
    private final List<ProjectImpact> affected;
    private final List<Warning> warnings;
    private final ErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;

    private SetFieldConfigurationMappingsResponse(boolean dryRun, Status status, SchemeRef scheme, List<RowChange> rows,
                                                  int projectsTotal, List<ProjectImpact> affected,
                                                  List<Warning> warnings, ErrorCode errorCode, String message,
                                                  List<String> suggestions, String planId, Long expiresAt) {
        this.dryRun = dryRun;
        this.status = status;
        this.scheme = scheme;
        this.rows = Immutables.listOf(rows);
        this.projectsTotal = projectsTotal;
        this.affected = Immutables.listOf(affected);
        this.warnings = Immutables.listOf(warnings);
        this.errorCode = errorCode;
        this.message = message;
        this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId;
        this.expiresAt = expiresAt;
    }

    public static SetFieldConfigurationMappingsResponse success(boolean dryRun, boolean changed, SchemeRef scheme,
                                                                List<RowChange> rows, int projectsTotal,
                                                                List<ProjectImpact> affected, List<Warning> warnings) {
        Status status = !changed ? Status.NO_CHANGE : dryRun ? Status.WOULD_CHANGE : Status.CHANGED;
        String message = !changed ? "Every requested row already has this field configuration; nothing was changed."
                : "Issue data is not changed; only the field rules of the affected issue types change.";
        return new SetFieldConfigurationMappingsResponse(dryRun, status, scheme, rows, projectsTotal, affected,
                warnings, null, message, null, null, null);
    }

    public static SetFieldConfigurationMappingsResponse failed(boolean dryRun, ErrorCode code, String message,
                                                               List<String> suggestions) {
        return new SetFieldConfigurationMappingsResponse(dryRun, Status.FAILED, null, null, 0, null, null, code,
                message, suggestions, null, null);
    }

    public static SetFieldConfigurationMappingsResponse stale() {
        return failed(false, ErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again; nothing was changed.",
                null);
    }

    public SetFieldConfigurationMappingsResponse withPlan(String id, long expiry) {
        return new SetFieldConfigurationMappingsResponse(true, status, scheme, rows, projectsTotal, affected, warnings,
                errorCode, message, suggestions, id, expiry);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("undoWarning") public String getUndoWarning() {
        return "This operation cannot be rolled back through Undo; restore each row's before value with a new preview.";
    }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("scheme") public SchemeRef getScheme() { return scheme; }
    @JsonProperty("rows") public List<RowChange> getRows() { return rows; }
    @JsonProperty("projectsTotal") public int getProjectsTotal() { return projectsTotal; }
    @JsonProperty("affected") public List<ProjectImpact> getAffected() { return affected; }
    @JsonProperty("warnings") public List<Warning> getWarnings() { return warnings; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }

    /**
     * One requested row. {@code before}/{@code after} null means no explicit row (the issue type falls to DEFAULT);
     * the DEFAULT row has issueTypeId=null.
     */
    public static final class RowChange {
        private final String issueTypeId;
        private final String issueTypeName;
        private final ConfigurationRef before;
        private final ConfigurationRef after;
        private final boolean changed;

        public RowChange(String issueTypeId, String issueTypeName, ConfigurationRef before, ConfigurationRef after,
                         boolean changed) {
            this.issueTypeId = issueTypeId; this.issueTypeName = issueTypeName; this.before = before;
            this.after = after; this.changed = changed;
        }
        @JsonProperty("issueTypeId") public String getIssueTypeId() { return issueTypeId; }
        @JsonProperty("issueTypeName") public String getIssueTypeName() { return issueTypeName; }
        @JsonProperty("before") public ConfigurationRef getBefore() { return before; }
        @JsonProperty("after") public ConfigurationRef getAfter() { return after; }
        @JsonProperty("changed") public boolean isChanged() { return changed; }
    }
}
