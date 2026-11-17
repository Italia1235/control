package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;

import java.util.List;

/**
 * Preview or outcome of an in-place edit of the rows of an Issue Type Screen Scheme. Preview: {@code WOULD_CHANGE}
 * (with {@code planId}) or {@code NO_CHANGE} (nothing to write, no plan). Apply: {@code CHANGED} only when the table read
 * back from Jira equals the planned final table; otherwise {@code PARTIALLY_CHANGED} (part applied), {@code FAILED}
 * (nothing changed) or {@code UNKNOWN} (the state cannot be established). Each row carries its own {@code result}.
 */
public final class SetIssueTypeScreenSchemeMappingsResponse {
    public enum Status { WOULD_CHANGE, NO_CHANGE, CHANGED, PARTIALLY_CHANGED, UNKNOWN, FAILED }
    public enum ErrorCode {
        VALIDATION_ERROR, ITSS_NOT_FOUND, ITSS_AMBIGUOUS, SCREEN_SCHEME_NOT_FOUND, SCREEN_SCHEME_AMBIGUOUS,
        ISSUE_TYPE_NOT_FOUND, DEFAULT_ROW_REQUIRED, ROW_NOT_FOUND, DUPLICATE_ROW, INVALID_ROW, PREVIEW_STALE, JIRA_ERROR
    }
    public enum RowAction { ADD, REPLACE, REMOVE }
    /** What the row does to the stored table: CHANGE needs a write, NO_CHANGE is skipped at apply. */
    public enum Change { CHANGE, NO_CHANGE }
    /** APPLIED; NO_CHANGE (no write needed, read back equals expected); NOT_APPLIED (write failed, read back shows the old value); NOT_ATTEMPTED; UNKNOWN. */
    public enum RowResult { APPLIED, NO_CHANGE, NOT_APPLIED, NOT_ATTEMPTED, UNKNOWN }

    private final Status status;
    private final boolean dryRun;
    private final SchemeInfo issueTypeScreenScheme;
    private final List<RowPlan> rows;
    private final ProjectImpact projects;
    private final String scopeText;
    private final List<String> limitations;
    private final List<Warning> warnings;
    private final Boolean readBack;
    private final List<String> notes;
    private final String message;
    private final ErrorCode errorCode;
    private final Integer errorRowIndex;
    private final String planId;
    private final Long planExpiresAt;

    public SetIssueTypeScreenSchemeMappingsResponse(Status status, boolean dryRun, SchemeInfo issueTypeScreenScheme,
            List<RowPlan> rows, ProjectImpact projects, String scopeText, List<String> limitations, List<Warning> warnings,
            Boolean readBack, List<String> notes, String message, ErrorCode errorCode, Integer errorRowIndex,
            String planId, Long planExpiresAt) {
        this.status = status; this.dryRun = dryRun; this.issueTypeScreenScheme = issueTypeScreenScheme;
        this.rows = Immutables.listOf(rows); this.projects = projects; this.scopeText = scopeText;
        this.limitations = Immutables.listOf(limitations); this.warnings = Immutables.listOf(warnings);
        this.readBack = readBack; this.notes = Immutables.listOf(notes); this.message = message;
        this.errorCode = errorCode; this.errorRowIndex = errorRowIndex; this.planId = planId; this.planExpiresAt = planExpiresAt;
    }

    public SetIssueTypeScreenSchemeMappingsResponse withPlan(String id, long expiresAt) {
        return new SetIssueTypeScreenSchemeMappingsResponse(status, dryRun, issueTypeScreenScheme, rows, projects, scopeText,
                limitations, warnings, readBack, notes, message, errorCode, errorRowIndex, id, expiresAt);
    }

    /** The terminal result of an apply; {@code rows} carry the per-row results, {@code readBack} says whether Jira was re-read. */
    public SetIssueTypeScreenSchemeMappingsResponse outcome(Status outcome, List<RowPlan> resultRows, boolean wasReadBack,
            List<String> outcomeNotes, String text) {
        return new SetIssueTypeScreenSchemeMappingsResponse(outcome, false, issueTypeScreenScheme, resultRows, projects,
                scopeText, limitations, warnings, wasReadBack, outcomeNotes, text,
                outcome == Status.CHANGED ? null : ErrorCode.JIRA_ERROR, null, null, null);
    }

    public static SetIssueTypeScreenSchemeMappingsResponse failed(boolean dryRun, ErrorCode code, Integer rowIndex, String message) {
        return new SetIssueTypeScreenSchemeMappingsResponse(Status.FAILED, dryRun, null, null, null, null, null, null,
                null, null, message, code, rowIndex, null, null);
    }

    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("issueTypeScreenScheme") public SchemeInfo getIssueTypeScreenScheme() { return issueTypeScreenScheme; }
    @JsonProperty("rows") public List<RowPlan> getRows() { return rows; }
    @JsonProperty("projects") public ProjectImpact getProjects() { return projects; }
    @JsonProperty("scopeText") public String getScopeText() { return scopeText; }
    @JsonProperty("limitations") public List<String> getLimitations() { return limitations; }
    @JsonProperty("warnings") public List<Warning> getWarnings() { return warnings; }
    /** Apply only: true when the table was re-read from Jira after the writes. */
    @JsonProperty("readBack") public Boolean getReadBack() { return readBack; }
    @JsonProperty("notes") public List<String> getNotes() { return notes; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    /** Zero-based index in {@code rows[]} of the request row that caused a validation error. */
    @JsonProperty("errorRowIndex") public Integer getErrorRowIndex() { return errorRowIndex; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("planExpiresAt") public Long getPlanExpiresAt() { return planExpiresAt; }

    /** The Issue Type Screen Scheme being edited. */
    public static final class SchemeInfo {
        private final Long id;
        private final String name;
        private final boolean jiraDefault;
        public SchemeInfo(Long id, String name, boolean jiraDefault) { this.id = id; this.name = name; this.jiraDefault = jiraDefault; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("isDefault") public boolean isJiraDefault() { return jiraDefault; }
    }

    /** A Screen Scheme as an issue type gets it: its own row, or through the DEFAULT row ({@code viaDefault}). */
    public static final class EffectiveScheme {
        private final Long id;
        private final String name;
        private final boolean viaDefault;
        public EffectiveScheme(Long id, String name, boolean viaDefault) { this.id = id; this.name = name; this.viaDefault = viaDefault; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("viaDefault") public boolean isViaDefault() { return viaDefault; }
    }

    /** A Screen Scheme read back from an explicit row. */
    public static final class SchemeRef {
        private final Long id;
        private final String name;
        public SchemeRef(Long id, String name) { this.id = id; this.name = name; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
    }

    /**
     * One request row against the final table. {@code before}/{@code after} are the effective schemes of the type before
     * and after ALL rows of the request; {@code action} is what is done ({@code ADD} of an existing row is a {@code REPLACE}).
     * {@code result} and {@code observed} (the explicit row read back, null when absent or unreadable) exist after an apply.
     */
    public static final class RowPlan {
        private final int rowIndex;
        private final IssueTypeRef issueType;
        private final RowAction requestedAction;
        private final RowAction action;
        private final Change change;
        private final EffectiveScheme before;
        private final EffectiveScheme after;
        private final RowResult result;
        private final SchemeRef observed;

        public RowPlan(int rowIndex, IssueTypeRef issueType, RowAction requestedAction, RowAction action, Change change,
                       EffectiveScheme before, EffectiveScheme after, RowResult result, SchemeRef observed) {
            this.rowIndex = rowIndex; this.issueType = issueType; this.requestedAction = requestedAction; this.action = action;
            this.change = change; this.before = before; this.after = after; this.result = result; this.observed = observed;
        }

        public RowPlan withResult(RowResult newResult, SchemeRef newObserved) {
            return new RowPlan(rowIndex, issueType, requestedAction, action, change, before, after, newResult, newObserved);
        }

        @JsonProperty("rowIndex") public int getRowIndex() { return rowIndex; }
        @JsonProperty("scope") public String getScope() { return issueType == null ? "DEFAULT" : "ISSUE_TYPE"; }
        @JsonProperty("issueType") public IssueTypeRef getIssueType() { return issueType; }
        @JsonProperty("requestedAction") public RowAction getRequestedAction() { return requestedAction; }
        @JsonProperty("action") public RowAction getAction() { return action; }
        @JsonProperty("change") public Change getChange() { return change; }
        @JsonProperty("before") public EffectiveScheme getBefore() { return before; }
        @JsonProperty("after") public EffectiveScheme getAfter() { return after; }
        @JsonProperty("result") public RowResult getResult() { return result; }
        @JsonProperty("observed") public SchemeRef getObserved() { return observed; }
    }

    /** {@code linked} (N) and {@code changing} (M) count ALL projects; only {@code listed} is cut to the first 10 by key. */
    public static final class ProjectImpact {
        private final int linked;
        private final int changing;
        private final boolean truncated;
        private final List<LinkedProject> listed;
        public ProjectImpact(int linked, int changing, boolean truncated, List<LinkedProject> listed) {
            this.linked = linked; this.changing = changing; this.truncated = truncated; this.listed = Immutables.listOf(listed);
        }
        @JsonProperty("linked") public int getLinked() { return linked; }
        @JsonProperty("changing") public int getChanging() { return changing; }
        @JsonProperty("truncated") public boolean isTruncated() { return truncated; }
        @JsonProperty("listed") public List<LinkedProject> getListed() { return listed; }
    }

    /** A project that uses the scheme: how many of its issue types change their effective Screen Scheme (may be 0). */
    public static final class LinkedProject {
        private final String key;
        private final String name;
        private final boolean archived;
        private final int changedIssueTypes;
        private final List<IssueTypeRef> rowTypesMissing;
        public LinkedProject(String key, String name, boolean archived, int changedIssueTypes, List<IssueTypeRef> rowTypesMissing) {
            this.key = key; this.name = name; this.archived = archived; this.changedIssueTypes = changedIssueTypes;
            this.rowTypesMissing = Immutables.listOf(rowTypesMissing);
        }
        @JsonProperty("key") public String getKey() { return key; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("archived") public boolean isArchived() { return archived; }
        @JsonProperty("changedIssueTypes") public int getChangedIssueTypes() { return changedIssueTypes; }
        /** Issue types of the request rows that this project does not have. */
        @JsonProperty("rowTypesMissing") public List<IssueTypeRef> getRowTypesMissing() { return rowTypesMissing; }
    }

    /** A structured preview warning: {@code ADD_AS_REPLACE}, {@code NO_PROJECTS}. */
    public static final class Warning {
        private final String code;
        private final Integer rowIndex;
        private final String message;
        public Warning(String code, Integer rowIndex, String message) { this.code = code; this.rowIndex = rowIndex; this.message = message; }
        @JsonProperty("code") public String getCode() { return code; }
        @JsonProperty("rowIndex") public Integer getRowIndex() { return rowIndex; }
        @JsonProperty("message") public String getMessage() { return message; }
    }
}
