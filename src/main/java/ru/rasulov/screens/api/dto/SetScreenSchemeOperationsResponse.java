package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;

import java.util.List;

/**
 * Preview or outcome of an in-place edit of the rows (operation to screen) of a Screen Scheme. Preview:
 * {@code WOULD_CHANGE} (with {@code planId}) or {@code NO_CHANGE} (nothing to write, no plan). Apply: {@code CHANGED} only
 * when the scheme read back from Jira equals the planned final table; otherwise {@code PARTIALLY_CHANGED} (part applied),
 * {@code FAILED} (nothing changed) or {@code UNKNOWN} (the state cannot be established). Each row carries its own {@code result}.
 */
public final class SetScreenSchemeOperationsResponse {
    public enum Status { WOULD_CHANGE, NO_CHANGE, CHANGED, PARTIALLY_CHANGED, UNKNOWN, FAILED }
    public enum ErrorCode {
        VALIDATION_ERROR, SCREEN_SCHEME_NOT_FOUND, SCREEN_SCHEME_AMBIGUOUS, SCREEN_NOT_FOUND, SCREEN_AMBIGUOUS,
        INVALID_OPERATION, DEFAULT_ROW_REQUIRED, ROW_NOT_FOUND, DUPLICATE_ROW, INVALID_ROW, PREVIEW_STALE, JIRA_ERROR
    }
    public enum RowAction { ADD, REPLACE, REMOVE }
    /** What the row does to the stored table: CHANGE needs a write, NO_CHANGE is skipped at apply. */
    public enum Change { CHANGE, NO_CHANGE }
    /** APPLIED; NO_CHANGE (no write needed, read back equals expected); NOT_APPLIED (write failed, read back shows the old value); NOT_ATTEMPTED; UNKNOWN. */
    public enum RowResult { APPLIED, NO_CHANGE, NOT_APPLIED, NOT_ATTEMPTED, UNKNOWN }

    private final Status status;
    private final boolean dryRun;
    private final SchemeInfo screenScheme;
    private final List<RowPlan> rows;
    private final ItssUsage usage;
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

    public SetScreenSchemeOperationsResponse(Status status, boolean dryRun, SchemeInfo screenScheme, List<RowPlan> rows,
            ItssUsage usage, ProjectImpact projects, String scopeText, List<String> limitations, List<Warning> warnings,
            Boolean readBack, List<String> notes, String message, ErrorCode errorCode, Integer errorRowIndex,
            String planId, Long planExpiresAt) {
        this.status = status; this.dryRun = dryRun; this.screenScheme = screenScheme; this.rows = Immutables.listOf(rows);
        this.usage = usage; this.projects = projects; this.scopeText = scopeText;
        this.limitations = Immutables.listOf(limitations); this.warnings = Immutables.listOf(warnings);
        this.readBack = readBack; this.notes = Immutables.listOf(notes); this.message = message;
        this.errorCode = errorCode; this.errorRowIndex = errorRowIndex; this.planId = planId; this.planExpiresAt = planExpiresAt;
    }

    public SetScreenSchemeOperationsResponse withPlan(String id, long expiresAt) {
        return new SetScreenSchemeOperationsResponse(status, dryRun, screenScheme, rows, usage, projects, scopeText,
                limitations, warnings, readBack, notes, message, errorCode, errorRowIndex, id, expiresAt);
    }

    /** The terminal result of an apply; {@code rows} carry the per-row results, {@code readBack} says whether Jira was re-read. */
    public SetScreenSchemeOperationsResponse outcome(Status outcome, List<RowPlan> resultRows, boolean wasReadBack,
            List<String> outcomeNotes, String text) {
        return new SetScreenSchemeOperationsResponse(outcome, false, screenScheme, resultRows, usage, projects,
                scopeText, limitations, warnings, wasReadBack, outcomeNotes, text,
                outcome == Status.CHANGED ? null : ErrorCode.JIRA_ERROR, null, null, null);
    }

    public static SetScreenSchemeOperationsResponse failed(boolean dryRun, ErrorCode code, Integer rowIndex, String message) {
        return new SetScreenSchemeOperationsResponse(Status.FAILED, dryRun, null, null, null, null, null, null, null,
                null, null, message, code, rowIndex, null, null);
    }

    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("screenScheme") public SchemeInfo getScreenScheme() { return screenScheme; }
    @JsonProperty("rows") public List<RowPlan> getRows() { return rows; }
    /** Issue Type Screen Schemes that contain the scheme (explicit row or DEFAULT row). */
    @JsonProperty("usage") public ItssUsage getUsage() { return usage; }
    @JsonProperty("projects") public ProjectImpact getProjects() { return projects; }
    @JsonProperty("scopeText") public String getScopeText() { return scopeText; }
    @JsonProperty("limitations") public List<String> getLimitations() { return limitations; }
    @JsonProperty("warnings") public List<Warning> getWarnings() { return warnings; }
    /** Apply only: true when the scheme was re-read from Jira after the writes. */
    @JsonProperty("readBack") public Boolean getReadBack() { return readBack; }
    @JsonProperty("notes") public List<String> getNotes() { return notes; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    /** Zero-based index in {@code rows[]} of the request row that caused a validation error. */
    @JsonProperty("errorRowIndex") public Integer getErrorRowIndex() { return errorRowIndex; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("planExpiresAt") public Long getPlanExpiresAt() { return planExpiresAt; }

    /** The Screen Scheme being edited. */
    public static final class SchemeInfo {
        private final Long id;
        private final String name;
        private final String description;
        public SchemeInfo(Long id, String name, String description) { this.id = id; this.name = name; this.description = description; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("description") public String getDescription() { return description; }
    }

    /** A Screen as an operation gets it: its own row, or through the DEFAULT row ({@code viaDefault}). */
    public static final class EffectiveScreen {
        private final Long id;
        private final String name;
        private final boolean viaDefault;
        public EffectiveScreen(Long id, String name, boolean viaDefault) { this.id = id; this.name = name; this.viaDefault = viaDefault; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("viaDefault") public boolean isViaDefault() { return viaDefault; }
    }

    /** A Screen read back from an explicit row. */
    public static final class ScreenRef {
        private final Long id;
        private final String name;
        public ScreenRef(Long id, String name) { this.id = id; this.name = name; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
    }

    /**
     * One request row against the final table. {@code before}/{@code after} are the effective screens of the operation
     * before and after ALL rows of the request; {@code action} is what is done ({@code ADD} of an existing row is a
     * {@code REPLACE}). {@code result} and {@code observed} (the explicit row read back, null when absent or unreadable)
     * exist after an apply.
     */
    public static final class RowPlan {
        private final int rowIndex;
        private final String operation;
        private final RowAction requestedAction;
        private final RowAction action;
        private final Change change;
        private final EffectiveScreen before;
        private final EffectiveScreen after;
        private final RowResult result;
        private final ScreenRef observed;

        public RowPlan(int rowIndex, String operation, RowAction requestedAction, RowAction action, Change change,
                       EffectiveScreen before, EffectiveScreen after, RowResult result, ScreenRef observed) {
            this.rowIndex = rowIndex; this.operation = operation; this.requestedAction = requestedAction; this.action = action;
            this.change = change; this.before = before; this.after = after; this.result = result; this.observed = observed;
        }

        public RowPlan withResult(RowResult newResult, ScreenRef newObserved) {
            return new RowPlan(rowIndex, operation, requestedAction, action, change, before, after, newResult, newObserved);
        }

        @JsonProperty("rowIndex") public int getRowIndex() { return rowIndex; }
        @JsonProperty("operation") public String getOperation() { return operation; }
        @JsonProperty("requestedAction") public RowAction getRequestedAction() { return requestedAction; }
        @JsonProperty("action") public RowAction getAction() { return action; }
        @JsonProperty("change") public Change getChange() { return change; }
        @JsonProperty("before") public EffectiveScreen getBefore() { return before; }
        @JsonProperty("after") public EffectiveScreen getAfter() { return after; }
        @JsonProperty("result") public RowResult getResult() { return result; }
        @JsonProperty("observed") public ScreenRef getObserved() { return observed; }
    }

    /** An Issue Type Screen Scheme that contains the Screen Scheme. */
    public static final class ItssRef {
        private final Long id;
        private final String name;
        private final boolean jiraDefault;
        public ItssRef(Long id, String name, boolean jiraDefault) { this.id = id; this.name = name; this.jiraDefault = jiraDefault; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("isDefault") public boolean isJiraDefault() { return jiraDefault; }
    }

    /** {@code linked} counts ALL Issue Type Screen Schemes that contain the scheme; only {@code listed} is cut to the first 10 by id. */
    public static final class ItssUsage {
        private final int linked;
        private final boolean truncated;
        private final List<ItssRef> listed;
        public ItssUsage(int linked, boolean truncated, List<ItssRef> listed) {
            this.linked = linked; this.truncated = truncated; this.listed = Immutables.listOf(listed);
        }
        @JsonProperty("linked") public int getLinked() { return linked; }
        @JsonProperty("truncated") public boolean isTruncated() { return truncated; }
        @JsonProperty("listed") public List<ItssRef> getListed() { return listed; }
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

    /** A project that uses the scheme: how many of its issue types change an effective screen (may be 0), and its Issue Type Screen Scheme. */
    public static final class LinkedProject {
        private final String key;
        private final String name;
        private final boolean archived;
        private final int changedIssueTypes;
        private final ItssRef issueTypeScreenScheme;
        public LinkedProject(String key, String name, boolean archived, int changedIssueTypes, ItssRef issueTypeScreenScheme) {
            this.key = key; this.name = name; this.archived = archived; this.changedIssueTypes = changedIssueTypes;
            this.issueTypeScreenScheme = issueTypeScreenScheme;
        }
        @JsonProperty("key") public String getKey() { return key; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("archived") public boolean isArchived() { return archived; }
        @JsonProperty("changedIssueTypes") public int getChangedIssueTypes() { return changedIssueTypes; }
        @JsonProperty("issueTypeScreenScheme") public ItssRef getIssueTypeScreenScheme() { return issueTypeScreenScheme; }
    }

    /** A field by Jira id and name. */
    public static final class FieldRef {
        private final String id;
        private final String name;
        public FieldRef(String id, String name) { this.id = id; this.name = name; }
        @JsonProperty("id") public String getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
    }

    /** A project with the issue types whose new CREATE screen lacks a required field. */
    public static final class RequiredProject {
        private final String key;
        private final String name;
        private final List<IssueTypeRef> issueTypes;
        public RequiredProject(String key, String name, List<IssueTypeRef> issueTypes) {
            this.key = key; this.name = name; this.issueTypes = Immutables.listOf(issueTypes);
        }
        @JsonProperty("key") public String getKey() { return key; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("issueTypes") public List<IssueTypeRef> getIssueTypes() { return issueTypes; }
    }

    /**
     * The details of {@code REQUIRED_FIELDS_MISSING_ON_CREATE}: {@code pairs} (project, issue type) pairs whose new CREATE
     * screen lacks a field that is required in the field configuration of the pair, the first 10 projects by key
     * ({@code truncated} when more) and the missing fields of ALL pairs.
     */
    public static final class RequiredFields {
        private final int pairs;
        private final boolean truncated;
        private final List<RequiredProject> projects;
        private final List<FieldRef> fields;
        public RequiredFields(int pairs, boolean truncated, List<RequiredProject> projects, List<FieldRef> fields) {
            this.pairs = pairs; this.truncated = truncated; this.projects = Immutables.listOf(projects); this.fields = Immutables.listOf(fields);
        }
        @JsonProperty("pairs") public int getPairs() { return pairs; }
        @JsonProperty("truncated") public boolean isTruncated() { return truncated; }
        @JsonProperty("projects") public List<RequiredProject> getProjects() { return projects; }
        @JsonProperty("fields") public List<FieldRef> getFields() { return fields; }
    }

    /** A structured preview warning: {@code ADD_AS_REPLACE}, {@code REQUIRED_FIELDS_MISSING_ON_CREATE}, {@code NO_LINKED_PROJECTS}. */
    public static final class Warning {
        private final String code;
        private final Integer rowIndex;
        private final String message;
        private final RequiredFields requiredFields;
        public Warning(String code, Integer rowIndex, String message) { this(code, rowIndex, message, null); }
        public Warning(String code, Integer rowIndex, String message, RequiredFields requiredFields) {
            this.code = code; this.rowIndex = rowIndex; this.message = message; this.requiredFields = requiredFields;
        }
        @JsonProperty("code") public String getCode() { return code; }
        @JsonProperty("rowIndex") public Integer getRowIndex() { return rowIndex; }
        @JsonProperty("message") public String getMessage() { return message; }
        @JsonProperty("requiredFields") @JsonInclude(JsonInclude.Include.NON_NULL) public RequiredFields getRequiredFields() { return requiredFields; }
    }
}
