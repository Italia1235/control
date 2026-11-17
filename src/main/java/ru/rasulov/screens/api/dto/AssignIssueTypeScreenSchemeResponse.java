package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.List;

/**
 * Preview or outcome of switching ONE project to an existing Issue Type Screen Scheme. Preview: {@code WOULD_CHANGE}
 * (with {@code planId}) or {@code NO_CHANGE} (the project already uses the scheme; no plan). Apply: {@code CHANGED} only
 * when the association read back from Jira is the new scheme and the project is no longer listed under the old one;
 * {@code FAILED} (the project still uses the old scheme) or {@code UNKNOWN} (the state cannot be established).
 */
public final class AssignIssueTypeScreenSchemeResponse {
    public enum Status { WOULD_CHANGE, NO_CHANGE, CHANGED, UNKNOWN, FAILED }
    public enum ErrorCode {
        VALIDATION_ERROR, UNKNOWN_PROJECT, ITSS_NOT_FOUND, ITSS_AMBIGUOUS, PREVIEW_STALE, JIRA_ERROR
    }
    /** What the issue type gets: CHANGE when its effective Screen Scheme or any effective screen differs. */
    public enum Change { CHANGE, NO_CHANGE }

    private final Status status;
    private final boolean dryRun;
    private final ProjectRef project;
    private final SchemeInfo currentIssueTypeScreenScheme;
    private final SchemeInfo newIssueTypeScreenScheme;
    private final List<TypePlan> issueTypes;
    private final List<String> limitations;
    private final List<Warning> warnings;
    private final SchemeInfo observedIssueTypeScreenScheme;
    private final Boolean readBack;
    private final List<String> notes;
    private final String message;
    private final ErrorCode errorCode;
    private final String planId;
    private final Long planExpiresAt;

    public AssignIssueTypeScreenSchemeResponse(Status status, boolean dryRun, ProjectRef project,
            SchemeInfo currentIssueTypeScreenScheme, SchemeInfo newIssueTypeScreenScheme, List<TypePlan> issueTypes,
            List<String> limitations, List<Warning> warnings, SchemeInfo observedIssueTypeScreenScheme, Boolean readBack,
            List<String> notes, String message, ErrorCode errorCode, String planId, Long planExpiresAt) {
        this.status = status; this.dryRun = dryRun; this.project = project;
        this.currentIssueTypeScreenScheme = currentIssueTypeScreenScheme; this.newIssueTypeScreenScheme = newIssueTypeScreenScheme;
        this.issueTypes = Immutables.listOf(issueTypes); this.limitations = Immutables.listOf(limitations);
        this.warnings = Immutables.listOf(warnings); this.observedIssueTypeScreenScheme = observedIssueTypeScreenScheme;
        this.readBack = readBack; this.notes = Immutables.listOf(notes); this.message = message;
        this.errorCode = errorCode; this.planId = planId; this.planExpiresAt = planExpiresAt;
    }

    public AssignIssueTypeScreenSchemeResponse withPlan(String id, long expiresAt) {
        return new AssignIssueTypeScreenSchemeResponse(status, dryRun, project, currentIssueTypeScreenScheme,
                newIssueTypeScreenScheme, issueTypes, limitations, warnings, observedIssueTypeScreenScheme, readBack, notes,
                message, errorCode, id, expiresAt);
    }

    /** The terminal result of an apply; {@code observed} is the scheme read back from Jira (null when it could not be read). */
    public AssignIssueTypeScreenSchemeResponse outcome(Status outcome, SchemeInfo observed, boolean wasReadBack,
            List<String> outcomeNotes, String text) {
        return new AssignIssueTypeScreenSchemeResponse(outcome, false, project, currentIssueTypeScreenScheme,
                newIssueTypeScreenScheme, issueTypes, limitations, warnings, observed, wasReadBack, outcomeNotes, text,
                outcome == Status.CHANGED ? null : ErrorCode.JIRA_ERROR, null, null);
    }

    public static AssignIssueTypeScreenSchemeResponse failed(boolean dryRun, ErrorCode code, String message) {
        return new AssignIssueTypeScreenSchemeResponse(Status.FAILED, dryRun, null, null, null, null, null, null, null, null,
                null, message, code, null, null);
    }

    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("project") public ProjectRef getProject() { return project; }
    @JsonProperty("currentIssueTypeScreenScheme") public SchemeInfo getCurrentIssueTypeScreenScheme() { return currentIssueTypeScreenScheme; }
    @JsonProperty("newIssueTypeScreenScheme") public SchemeInfo getNewIssueTypeScreenScheme() { return newIssueTypeScreenScheme; }
    /** One entry per issue type of the project: Screen Scheme and effective CREATE/EDIT/VIEW screens before and after. */
    @JsonProperty("issueTypes") public List<TypePlan> getIssueTypes() { return issueTypes; }
    @JsonProperty("limitations") public List<String> getLimitations() { return limitations; }
    @JsonProperty("warnings") public List<Warning> getWarnings() { return warnings; }
    /** Apply only: the Issue Type Screen Scheme the project uses after the write, as read back from Jira. */
    @JsonProperty("observedIssueTypeScreenScheme") public SchemeInfo getObservedIssueTypeScreenScheme() { return observedIssueTypeScreenScheme; }
    /** Apply only: true when the association was re-read from Jira after the write. */
    @JsonProperty("readBack") public Boolean getReadBack() { return readBack; }
    @JsonProperty("notes") public List<String> getNotes() { return notes; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("planExpiresAt") public Long getPlanExpiresAt() { return planExpiresAt; }

    /** An Issue Type Screen Scheme. */
    public static final class SchemeInfo {
        private final Long id;
        private final String name;
        private final boolean jiraDefault;
        public SchemeInfo(Long id, String name, boolean jiraDefault) { this.id = id; this.name = name; this.jiraDefault = jiraDefault; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("isDefault") public boolean isJiraDefault() { return jiraDefault; }
    }

    /** A Screen Scheme or a screen as an issue type gets it: an explicit row or through the DEFAULT one ({@code viaDefault}). */
    public static final class Effective {
        private final Long id;
        private final String name;
        private final boolean viaDefault;
        public Effective(Long id, String name, boolean viaDefault) { this.id = id; this.name = name; this.viaDefault = viaDefault; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("viaDefault") public boolean isViaDefault() { return viaDefault; }
    }

    /** The effective screen of one operation (CREATE, EDIT or VIEW) before and after; either side may be null. */
    public static final class OperationChange {
        private final String operation;
        private final Effective before;
        private final Effective after;
        public OperationChange(String operation, Effective before, Effective after) {
            this.operation = operation; this.before = before; this.after = after;
        }
        @JsonProperty("operation") public String getOperation() { return operation; }
        @JsonProperty("before") public Effective getBefore() { return before; }
        @JsonProperty("after") public Effective getAfter() { return after; }
    }

    /** One issue type of the project. */
    public static final class TypePlan {
        private final IssueTypeRef issueType;
        private final Change change;
        private final Effective screenSchemeBefore;
        private final Effective screenSchemeAfter;
        private final List<OperationChange> screens;
        public TypePlan(IssueTypeRef issueType, Change change, Effective screenSchemeBefore, Effective screenSchemeAfter,
                        List<OperationChange> screens) {
            this.issueType = issueType; this.change = change; this.screenSchemeBefore = screenSchemeBefore;
            this.screenSchemeAfter = screenSchemeAfter; this.screens = Immutables.listOf(screens);
        }
        @JsonProperty("issueType") public IssueTypeRef getIssueType() { return issueType; }
        @JsonProperty("change") public Change getChange() { return change; }
        @JsonProperty("screenSchemeBefore") public Effective getScreenSchemeBefore() { return screenSchemeBefore; }
        @JsonProperty("screenSchemeAfter") public Effective getScreenSchemeAfter() { return screenSchemeAfter; }
        @JsonProperty("screens") public List<OperationChange> getScreens() { return screens; }
    }

    /** A field that is required for creating an issue and absent from the new CREATE screen. */
    public static final class FieldRef {
        private final String id;
        private final String name;
        public FieldRef(String id, String name) { this.id = id; this.name = name; }
        @JsonProperty("id") public String getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
    }

    /**
     * A structured preview warning: {@code ITSS_ORPHANED} (the old scheme is left without projects),
     * {@code SHARED_TARGET} ({@code count} other projects use the new scheme, {@code projects} lists the first ones) and
     * {@code REQUIRED_FIELDS_MISSING_ON_CREATE} ({@code issueTypes} get a CREATE screen without the required {@code fields}).
     */
    public static final class Warning {
        private final String code;
        private final String message;
        private final Integer count;
        private final List<ProjectRef> projects;
        private final List<IssueTypeRef> issueTypes;
        private final List<FieldRef> fields;
        public Warning(String code, String message, Integer count, List<ProjectRef> projects, List<IssueTypeRef> issueTypes,
                       List<FieldRef> fields) {
            this.code = code; this.message = message; this.count = count; this.projects = Immutables.listOf(projects);
            this.issueTypes = Immutables.listOf(issueTypes); this.fields = Immutables.listOf(fields);
        }
        @JsonProperty("code") public String getCode() { return code; }
        @JsonProperty("message") public String getMessage() { return message; }
        @JsonProperty("count") public Integer getCount() { return count; }
        @JsonProperty("projects") public List<ProjectRef> getProjects() { return projects; }
        @JsonProperty("issueTypes") public List<IssueTypeRef> getIssueTypes() { return issueTypes; }
        @JsonProperty("fields") public List<FieldRef> getFields() { return fields; }
    }
}
