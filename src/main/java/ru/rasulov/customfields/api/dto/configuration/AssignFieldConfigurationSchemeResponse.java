package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.IssueTypeChange;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.SchemeRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.Usage;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.Warning;

import java.util.List;

/**
 * Preview or outcome of assigning a field configuration scheme to one project: the scheme before and after, every
 * issue type whose effective configuration changes with its required/hidden field differences, and warnings.
 */
public final class AssignFieldConfigurationSchemeResponse {
    public enum Status { WOULD_ASSIGN, ASSIGNED, ALREADY_ASSIGNED, FAILED }

    private final boolean dryRun;
    private final Status status;
    private final ProjectRef project;
    private final SchemeRef schemeBefore;
    private final SchemeRef schemeAfter;
    private final Usage schemeAfterOtherProjects;
    private final List<IssueTypeChange> changes;
    private final List<Warning> warnings;
    private final ErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;

    private AssignFieldConfigurationSchemeResponse(boolean dryRun, Status status, ProjectRef project,
                                                   SchemeRef schemeBefore, SchemeRef schemeAfter,
                                                   Usage schemeAfterOtherProjects, List<IssueTypeChange> changes,
                                                   List<Warning> warnings, ErrorCode errorCode, String message,
                                                   List<String> suggestions, String planId, Long expiresAt) {
        this.dryRun = dryRun;
        this.status = status;
        this.project = project;
        this.schemeBefore = schemeBefore;
        this.schemeAfter = schemeAfter;
        this.schemeAfterOtherProjects = schemeAfterOtherProjects;
        this.changes = Immutables.listOf(changes);
        this.warnings = Immutables.listOf(warnings);
        this.errorCode = errorCode;
        this.message = message;
        this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId;
        this.expiresAt = expiresAt;
    }

    public static AssignFieldConfigurationSchemeResponse success(boolean dryRun, boolean changed, ProjectRef project,
                                                                 SchemeRef before, SchemeRef after, Usage afterOthers,
                                                                 List<IssueTypeChange> changes, List<Warning> warnings) {
        Status status = !changed ? Status.ALREADY_ASSIGNED : dryRun ? Status.WOULD_ASSIGN : Status.ASSIGNED;
        String message = !changed ? "The project already uses this scheme; nothing was changed."
                : "Issue data is not changed; only the field rules of the listed issue types change.";
        return new AssignFieldConfigurationSchemeResponse(dryRun, status, project, before, after, afterOthers, changes,
                warnings, null, message, null, null, null);
    }

    public static AssignFieldConfigurationSchemeResponse failed(boolean dryRun, ProjectRef project, ErrorCode code,
                                                                String message, List<String> suggestions) {
        return new AssignFieldConfigurationSchemeResponse(dryRun, Status.FAILED, project, null, null, null, null, null,
                code, message, suggestions, null, null);
    }

    public static AssignFieldConfigurationSchemeResponse stale() {
        return failed(false, null, ErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again; nothing was changed.",
                null);
    }

    public AssignFieldConfigurationSchemeResponse withPlan(String id, long expiry) {
        return new AssignFieldConfigurationSchemeResponse(true, status, project, schemeBefore, schemeAfter,
                schemeAfterOtherProjects, changes, warnings, errorCode, message, suggestions, id, expiry);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("undoWarning") public String getUndoWarning() {
        return "This operation cannot be rolled back through Undo; assign schemeBefore again with a new preview.";
    }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("project") public ProjectRef getProject() { return project; }
    @JsonProperty("schemeBefore") public SchemeRef getSchemeBefore() { return schemeBefore; }
    @JsonProperty("schemeAfter") public SchemeRef getSchemeAfter() { return schemeAfter; }
    @JsonProperty("schemeAfterOtherProjects") public Usage getSchemeAfterOtherProjects() { return schemeAfterOtherProjects; }
    @JsonProperty("changes") public List<IssueTypeChange> getChanges() { return changes; }
    @JsonProperty("warnings") public List<Warning> getWarnings() { return warnings; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
}
