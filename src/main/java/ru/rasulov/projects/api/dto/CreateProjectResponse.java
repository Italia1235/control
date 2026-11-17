package ru.rasulov.projects.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.report.ChangeReport;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Result of a preview, creation, idempotent retry, or failed project request. */
public final class CreateProjectResponse {
    public enum Status { WOULD_CREATE, CREATED, PARTIALLY_CHANGED, EXISTS, FAILED }
    public enum ErrorCode {
        KEY_CONFLICT, NAME_CONFLICT, LEAD_CONFLICT, UNKNOWN_LEAD, AMBIGUOUS_LEAD,
        VALIDATION_ERROR, TEMPLATE_UNAVAILABLE, UNSUPPORTED_TEMPLATE, UNKNOWN_ISSUE_TYPE, MAPPING_REQUIRED, INVALID_MAPPING,
        STATUS_CONFLICT, TOO_MANY_ISSUES, PREVIEW_STALE, JIRA_ERROR
    }

    private final boolean dryRun;
    private final Status status;
    private final String name;
    private final String key;
    private final ProjectLeadView lead;
    private final Long projectId;
    private final ErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;
    private final ChangeReport report;
    private final IssueTypeSetView issueTypeSet;
    private final String projectType;
    private final String templateKey;

    private CreateProjectResponse(boolean dryRun, Status status, String name, String key, ProjectLeadView lead,
                                  Long projectId, ErrorCode errorCode, String message, List<String> suggestions,
                                  String planId, Long expiresAt) {
        this(dryRun, status, name, key, lead, projectId, errorCode, message, suggestions, planId, expiresAt, null, null,
                null, null);
    }

    private CreateProjectResponse(boolean dryRun, Status status, String name, String key, ProjectLeadView lead,
                                  Long projectId, ErrorCode errorCode, String message, List<String> suggestions,
                                  String planId, Long expiresAt, ChangeReport report, IssueTypeSetView issueTypeSet,
                                  String projectType, String templateKey) {
        this.issueTypeSet = issueTypeSet;
        this.dryRun = dryRun;
        this.status = status;
        this.name = name;
        this.key = key;
        this.lead = lead;
        this.projectId = projectId;
        this.errorCode = errorCode;
        this.message = message;
        this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId;
        this.expiresAt = expiresAt;
        this.report = report;
        this.projectType = projectType;
        this.templateKey = templateKey;
    }

    public CreateProjectResponse withReport(ChangeReport changes) {
        return new CreateProjectResponse(dryRun, status, name, key, lead, projectId, errorCode, message, suggestions,
                planId, expiresAt, changes, issueTypeSet, projectType, templateKey);
    }

    /** The same response with the final issue-type set: the plan in a preview, the real assignment after apply. */
    public CreateProjectResponse withIssueTypeSet(IssueTypeSetView set) {
        return new CreateProjectResponse(dryRun, status, name, key, lead, projectId, errorCode, message, suggestions,
                planId, expiresAt, report, set, projectType, templateKey);
    }

    /** The same response carrying the project type and template of the plan (the selected template's own type). */
    public CreateProjectResponse withTemplate(String projectType, String templateKey) {
        return new CreateProjectResponse(dryRun, status, name, key, lead, projectId, errorCode, message, suggestions,
                planId, expiresAt, report, issueTypeSet, projectType, templateKey);
    }

    /**
     * The project exists (id and key are real) but the requested final issue-type set is not completely in place, or
     * an outcome is UNKNOWN. {@code message} says what is done and what is not; the set view has the real state.
     */
    public static CreateProjectResponse incomplete(String name, String key, ProjectLeadView lead, Long projectId,
                                                   ErrorCode code, String message) {
        return new CreateProjectResponse(false, Status.PARTIALLY_CHANGED, name, key, lead, projectId, code, message,
                null, null, null);
    }

    public static CreateProjectResponse wouldCreate(String name, String key, ProjectLeadView lead,
                                                    String planId, long expiresAt) {
        return new CreateProjectResponse(true, Status.WOULD_CREATE, name, key, lead, null, null,
                "Project can be created. Show this plan to the user and apply its planId only after confirmation.",
                null, planId, expiresAt);
    }

    public static CreateProjectResponse created(String name, String key, ProjectLeadView lead, Long id) {
        return new CreateProjectResponse(false, Status.CREATED, name, key, lead, id, null, null, null, null, null);
    }

    public static CreateProjectResponse exists(boolean dryRun, String name, String key, ProjectLeadView lead, Long id) {
        return new CreateProjectResponse(dryRun, Status.EXISTS, name, key, lead, dryRun ? null : id, null,
                "A project with this name, key and lead already exists. Its original template and configuration "
                        + "were not checked; nothing was changed.", null, null, null);
    }

    public static CreateProjectResponse failed(boolean dryRun, String name, String key, ProjectLeadView lead,
                                               ErrorCode code, String message, List<String> suggestions) {
        return new CreateProjectResponse(dryRun, Status.FAILED, name, key, lead, null, code, message, suggestions,
                null, null);
    }

    @JsonProperty("dryRun")
    public boolean isDryRun() { return dryRun; }

    @JsonProperty("status")
    public Status getStatus() { return status; }

    @JsonProperty("name")
    public String getName() { return name; }

    @JsonProperty("key")
    public String getKey() { return key; }

    @JsonProperty("lead")
    public ProjectLeadView getLead() { return lead; }

    @JsonProperty("projectId")
    public Long getProjectId() { return projectId; }

    /** The type of the selected template; only for a preview and a created (or partly created) project. */
    @JsonProperty("projectType")
    public String getProjectType() { return projectType; }

    /** The selected template; an existing project's template is not known, so EXISTS has none. */
    @JsonProperty("templateKey")
    public String getTemplateKey() { return status == Status.EXISTS ? null : templateKey; }

    @JsonProperty("errorCode")
    public ErrorCode getErrorCode() { return errorCode; }

    @JsonProperty("message")
    public String getMessage() { return message; }

    @JsonProperty("suggestions")
    public List<String> getSuggestions() { return suggestions; }

    @JsonProperty("planId")
    public String getPlanId() { return planId; }

    /** Expiry of planId as Unix epoch milliseconds. */
    @JsonProperty("expiresAt")
    public Long getExpiresAt() { return expiresAt; }

    /** What the apply created, with its admin link; {@code null} in a preview. */
    @JsonProperty("report")
    public ChangeReport getReport() { return report; }

    /**
     * Without it the project was created in template mode: it keeps the selected template's own issue types, which
     * are not listed here. With it, the complete final set requested by the caller.
     */
    @JsonProperty("issueTypeSet") @JsonInclude(JsonInclude.Include.NON_NULL)
    public IssueTypeSetView getIssueTypeSet() { return issueTypeSet; }
}
