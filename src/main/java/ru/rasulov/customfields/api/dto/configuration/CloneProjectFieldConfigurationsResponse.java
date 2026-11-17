package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.List;

/** Preview or outcome of detaching one project's complete field configuration setup. */
public final class CloneProjectFieldConfigurationsResponse {
    public enum Status { WOULD_CLONE, CLONED, ALREADY_SEPARATE, FAILED }
    public enum ErrorCode { VALIDATION_ERROR, UNKNOWN_PROJECT, JIRA_ERROR, PREVIEW_STALE }

    private final boolean dryRun;
    private final Status status;
    private final ProjectRef project;
    private final CopyView fieldConfigurationScheme;
    private final List<CopyView> fieldConfigurations;
    private final String message;
    private final ErrorCode errorCode;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;

    private CloneProjectFieldConfigurationsResponse(boolean dryRun, Status status, ProjectRef project,
                                                      CopyView scheme, List<CopyView> configurations,
                                                      String message, ErrorCode errorCode, List<String> suggestions,
                                                      String planId, Long expiresAt) {
        this.dryRun = dryRun;
        this.status = status;
        this.project = project;
        this.fieldConfigurationScheme = scheme;
        this.fieldConfigurations = Immutables.listOf(configurations);
        this.message = message;
        this.errorCode = errorCode;
        this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId;
        this.expiresAt = expiresAt;
    }

    public static CloneProjectFieldConfigurationsResponse success(boolean dryRun, ProjectRef project,
                                                                   CopyView scheme,
                                                                   List<CopyView> configurations) {
        return new CloneProjectFieldConfigurationsResponse(dryRun,
                dryRun ? Status.WOULD_CLONE : Status.CLONED, project, scheme, configurations,
                null, null, null, null, null);
    }

    public static CloneProjectFieldConfigurationsResponse alreadySeparate(boolean dryRun, ProjectRef project,
                                                                           CopyView scheme,
                                                                           List<CopyView> configurations) {
        return new CloneProjectFieldConfigurationsResponse(dryRun, Status.ALREADY_SEPARATE, project, scheme,
                configurations, "The field configuration setup is already used only by this project; nothing was changed.",
                null, null, null, null);
    }

    public static CloneProjectFieldConfigurationsResponse failed(boolean dryRun, ProjectRef project, ErrorCode code,
                                                                  String message, List<String> suggestions) {
        return new CloneProjectFieldConfigurationsResponse(dryRun, Status.FAILED, project, null, null,
                message, code, suggestions, null, null);
    }

    public static CloneProjectFieldConfigurationsResponse stale() {
        return failed(false, null, ErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again.", null);
    }

    public CloneProjectFieldConfigurationsResponse withPlan(String id, long expiry) {
        return new CloneProjectFieldConfigurationsResponse(true, status, project, fieldConfigurationScheme,
                fieldConfigurations, message, errorCode, suggestions, id, expiry);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("undoWarning") public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("project") public ProjectRef getProject() { return project; }
    @JsonProperty("fieldConfigurationScheme") public CopyView getFieldConfigurationScheme() { return fieldConfigurationScheme; }
    @JsonProperty("fieldConfigurations") public List<CopyView> getFieldConfigurations() { return fieldConfigurations; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }

    public static final class CopyView {
        private final Long sourceId;
        private final String sourceName;
        private final Long newId;
        private final String newName;
        private final int otherProjectsTotal;
        private final List<ProjectRef> otherProjects;

        public CopyView(Long sourceId, String sourceName, Long newId, String newName,
                        int otherProjectsTotal, List<ProjectRef> otherProjects) {
            this.sourceId = sourceId;
            this.sourceName = sourceName;
            this.newId = newId;
            this.newName = newName;
            this.otherProjectsTotal = otherProjectsTotal;
            this.otherProjects = Immutables.listOf(otherProjects);
        }

        @JsonProperty("sourceId") public Long getSourceId() { return sourceId; }
        @JsonProperty("sourceName") public String getSourceName() { return sourceName; }
        @JsonProperty("newId") public Long getNewId() { return newId; }
        @JsonProperty("newName") public String getNewName() { return newName; }
        @JsonProperty("otherProjectsTotal") public int getOtherProjectsTotal() { return otherProjectsTotal; }
        @JsonProperty("otherProjects") public List<ProjectRef> getOtherProjects() { return otherProjects; }
    }
}
