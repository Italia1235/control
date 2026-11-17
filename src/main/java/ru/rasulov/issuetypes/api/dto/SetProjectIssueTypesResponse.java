package ru.rasulov.issuetypes.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.api.dto.StatusUsage;
import ru.rasulov.common.api.dto.report.ChangeReport;
import ru.rasulov.common.api.dto.issuetype.ExecutionSummary;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView;
import ru.rasulov.common.api.dto.issuetype.SchemeView;

import java.util.List;

/** Preview or result of replacing one project's issue-type set. */
public final class SetProjectIssueTypesResponse {
    public enum Status { WOULD_CHANGE, CHANGED, PARTIALLY_CHANGED, ALREADY_CONFIGURED, FAILED }
    public enum ErrorCode {
        VALIDATION_ERROR, UNKNOWN_PROJECT, UNKNOWN_ISSUE_TYPE, MAPPING_REQUIRED, INVALID_MAPPING,
        STATUS_CONFLICT, TOO_MANY_ISSUES, PREVIEW_STALE, JIRA_ERROR
    }

    private final boolean dryRun;
    private final Status status;
    private final ProjectRef project;
    private final SchemeView scheme;
    private final List<IssueTypeRef> issueTypes;
    private final List<IssueTypeRef> added;
    private final List<IssueTypeRef> removed;
    private final List<MappingView> mappings;
    private final ErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;
    private final ExecutionSummary execution;
    private final IssueTypeSetView issueTypeSet;
    private final ChangeReport report;

    private SetProjectIssueTypesResponse(boolean dryRun, Status status, ProjectRef project, SchemeView scheme,
                                         List<IssueTypeRef> issueTypes, List<IssueTypeRef> added,
                                         List<IssueTypeRef> removed, List<MappingView> mappings, ErrorCode errorCode,
                                         String message, List<String> suggestions, String planId, Long expiresAt,
                                         ExecutionSummary execution) {
        this(dryRun, status, project, scheme, issueTypes, added, removed, mappings, errorCode, message, suggestions,
                planId, expiresAt, execution, null, null);
    }

    private SetProjectIssueTypesResponse(boolean dryRun, Status status, ProjectRef project, SchemeView scheme,
                                         List<IssueTypeRef> issueTypes, List<IssueTypeRef> added,
                                         List<IssueTypeRef> removed, List<MappingView> mappings, ErrorCode errorCode,
                                         String message, List<String> suggestions, String planId, Long expiresAt,
                                         ExecutionSummary execution, IssueTypeSetView issueTypeSet, ChangeReport report) {
        this.issueTypeSet = issueTypeSet; this.report = report;
        this.dryRun = dryRun; this.status = status; this.project = project; this.scheme = scheme;
        this.issueTypes = Immutables.listOf(issueTypes); this.added = Immutables.listOf(added);
        this.removed = Immutables.listOf(removed); this.mappings = Immutables.listOf(mappings);
        this.errorCode = errorCode; this.message = message; this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId; this.expiresAt = expiresAt;
        this.execution = execution;
    }

    public static SetProjectIssueTypesResponse wouldChange(ProjectRef project, SchemeView scheme,
                                                            List<IssueTypeRef> issueTypes, List<IssueTypeRef> added,
                                                            List<IssueTypeRef> removed, List<MappingView> mappings,
                                                            String message) {
        return new SetProjectIssueTypesResponse(true, Status.WOULD_CHANGE, project, scheme, issueTypes, added, removed,
                mappings, null, message, null, null, null, null);
    }

    public static SetProjectIssueTypesResponse changed(ProjectRef project, SchemeView scheme,
                                                        List<IssueTypeRef> issueTypes, List<IssueTypeRef> added,
                                                        List<IssueTypeRef> removed, List<MappingView> mappings,
                                                        String message, boolean complete) {
        return new SetProjectIssueTypesResponse(false, complete ? Status.CHANGED : Status.PARTIALLY_CHANGED, project,
                scheme, issueTypes, added, removed, mappings, null, message, null, null, null, null);
    }

    public static SetProjectIssueTypesResponse already(boolean dryRun, ProjectRef project, SchemeView scheme,
                                                        List<IssueTypeRef> issueTypes) {
        return new SetProjectIssueTypesResponse(dryRun, Status.ALREADY_CONFIGURED, project, scheme, issueTypes, null,
                null, null, null, "The project already has exactly these issue types; nothing was changed.", null,
                null, null, null);
    }

    public static SetProjectIssueTypesResponse failed(boolean dryRun, ProjectRef project, ErrorCode code,
                                                       String message, List<String> suggestions) {
        return new SetProjectIssueTypesResponse(dryRun, Status.FAILED, project, null, null, null, null, null, code,
                message, suggestions, null, null, null);
    }

    public SetProjectIssueTypesResponse withPlan(String id, long expiry) {
        return new SetProjectIssueTypesResponse(true, status, project, scheme, issueTypes, added, removed, mappings,
                errorCode, message, suggestions, id, expiry, null, issueTypeSet, null);
    }

    /** The same response with the resolved set (new definitions, default, per-type results) and, after apply, the report. */
    public SetProjectIssueTypesResponse withSet(IssueTypeSetView set, ChangeReport changes) {
        return new SetProjectIssueTypesResponse(dryRun, status, project, scheme, issueTypes, added, removed, mappings,
                errorCode, message, suggestions, planId, expiresAt, execution, set, changes);
    }

    /**
     * After apply: some types were written but the final set is not (completely) assigned. {@code message} says what
     * is and is not done; {@code set.assignment} has the real state.
     */
    public static SetProjectIssueTypesResponse incomplete(ProjectRef project, ErrorCode code, String message,
                                                          IssueTypeSetView set, ChangeReport changes) {
        return new SetProjectIssueTypesResponse(false, Status.PARTIALLY_CHANGED, project, null, null, null, null, null,
                code, message, null, null, null, null, set, changes);
    }

    public SetProjectIssueTypesResponse withExecution(ExecutionSummary summary) {
        return new SetProjectIssueTypesResponse(dryRun, status, project, scheme, issueTypes, added, removed, mappings,
                errorCode, message, suggestions, planId, expiresAt, summary, issueTypeSet, report);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("undoWarning") public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("project") public ProjectRef getProject() { return project; }
    @JsonProperty("scheme") public SchemeView getScheme() { return scheme; }
    @JsonProperty("issueTypes") public List<IssueTypeRef> getIssueTypes() { return issueTypes; }
    @JsonProperty("added") public List<IssueTypeRef> getAdded() { return added; }
    @JsonProperty("removed") public List<IssueTypeRef> getRemoved() { return removed; }
    @JsonProperty("mappings") public List<MappingView> getMappings() { return mappings; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
    @JsonProperty("execution") @JsonInclude(JsonInclude.Include.NON_NULL)
    public ExecutionSummary getExecution() { return execution; }
    /** The resolved final set: per-type key/status/id, default, excluded types and, after apply, the real assignment. */
    @JsonProperty("issueTypeSet") @JsonInclude(JsonInclude.Include.NON_NULL)
    public IssueTypeSetView getIssueTypeSet() { return issueTypeSet; }
    /** After apply with newIssueTypes: created and reused types with canonical ids. */
    @JsonProperty("report") @JsonInclude(JsonInclude.Include.NON_NULL)
    public ChangeReport getReport() { return report; }

    public static final class MappingView {
        private final IssueTypeRef from;
        private final IssueTypeRef to;
        private final int issues;
        private final String fromWorkflow;
        private final String toWorkflow;
        private final List<StatusUsage> statuses;

        public MappingView(IssueTypeRef from, IssueTypeRef to, int issues, String fromWorkflow, String toWorkflow,
                           List<StatusUsage> statuses) {
            this.from = from; this.to = to; this.issues = issues; this.fromWorkflow = fromWorkflow;
            this.toWorkflow = toWorkflow; this.statuses = Immutables.listOf(statuses);
        }
        @JsonProperty("from") public IssueTypeRef getFrom() { return from; }
        @JsonProperty("to") public IssueTypeRef getTo() { return to; }
        @JsonProperty("issues") public int getIssues() { return issues; }
        @JsonProperty("fromWorkflow") public String getFromWorkflow() { return fromWorkflow; }
        @JsonProperty("toWorkflow") public String getToWorkflow() { return toWorkflow; }
        @JsonProperty("statuses") public List<StatusUsage> getStatuses() { return statuses; }
    }
}
