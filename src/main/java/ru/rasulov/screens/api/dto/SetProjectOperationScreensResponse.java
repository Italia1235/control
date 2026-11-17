package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.List;

/**
 * Preview or outcome of the batch assignment of operation screens. {@code issueTypes} is the final CREATE/EDIT/VIEW
 * matrix of EVERY current project type; {@code matrixSource} says whether it is the plan ({@code PLANNED}) or what
 * Jira returned after the assignment ({@code READ_BACK}). Without an assignment the matrix is empty.
 */
public final class SetProjectOperationScreensResponse {
    public enum Status { WOULD_ASSIGN, ASSIGNED, ALREADY_ASSIGNED, PARTIALLY_ASSIGNED, FAILED }
    public enum ErrorCode { VALIDATION_ERROR, UNKNOWN_PROJECT, UNKNOWN_ISSUE_TYPE, UNKNOWN_SCREEN, PREVIEW_STALE, JIRA_ERROR }
    /**
     * Copy mode: NOT_ASSIGNED - the project still uses the source scheme; UNKNOWN - the switch threw and could not be read
     * back. In-place update of a private scheme (no switch): ASSIGNED - every planned mapping is in place; NOT_ASSIGNED -
     * read back from Jira, the mappings are unchanged; PARTIAL - read back from Jira, only some mappings changed;
     * UNKNOWN - the mappings could not be read back.
     */
    public enum AssignmentState { NOT_ASSIGNED, ASSIGNED, PARTIAL, UNKNOWN }
    public enum MatrixSource { PLANNED, READ_BACK }
    /**
     * How the top (Issue Type Screen Scheme) level changes: NO_CHANGE; UPDATE_PRIVATE - mappings of the existing
     * non-default scheme that only this project uses, ID/name kept; COPY_SHARED - one new private scheme, because other
     * projects use the source or exclusive use is not proven; COPY_DEFAULT - one new private scheme, the source is Jira's default.
     */
    public enum SchemeMode { NO_CHANGE, UPDATE_PRIVATE, COPY_SHARED, COPY_DEFAULT }
    /** CHANGE: an explicit row of the source scheme gets another Screen Scheme. ADD: the type inherited DEFAULT and gets its own row. */
    public enum MappingAction { CHANGE, ADD }

    private final Status status;
    private final boolean dryRun;
    private final ProjectRef project;
    private final Long sourceSchemeId;
    private final String sourceSchemeName;
    private final String newSchemeName;
    private final TopSchemePlan topScheme;
    private final Long assignedSchemeId;
    private final MatrixSource matrixSource;
    private final List<TypeAssignment> issueTypes;
    private final List<SchemeChange> screenSchemes;
    private final Created created;
    private final AssignmentState assignment;
    private final List<String> warnings;
    private final String message;
    private final ErrorCode errorCode;
    private final String planId;
    private final Long planExpiresAt;

    public SetProjectOperationScreensResponse(Status status, boolean dryRun, ProjectRef project, Long sourceSchemeId,
            String sourceSchemeName, String newSchemeName, TopSchemePlan topScheme, Long assignedSchemeId,
            MatrixSource matrixSource, List<TypeAssignment> issueTypes, List<SchemeChange> screenSchemes, Created created,
            AssignmentState assignment, List<String> warnings, String message, ErrorCode errorCode,
            String planId, Long planExpiresAt) {
        this.status = status; this.dryRun = dryRun; this.project = project; this.sourceSchemeId = sourceSchemeId;
        this.sourceSchemeName = sourceSchemeName; this.newSchemeName = newSchemeName; this.topScheme = topScheme;
        this.assignedSchemeId = assignedSchemeId; this.matrixSource = matrixSource;
        this.issueTypes = Immutables.listOf(issueTypes); this.screenSchemes = Immutables.listOf(screenSchemes);
        this.created = created; this.assignment = assignment; this.warnings = Immutables.listOf(warnings);
        this.message = message; this.errorCode = errorCode; this.planId = planId; this.planExpiresAt = planExpiresAt;
    }

    public SetProjectOperationScreensResponse withPlan(String id, long expiresAt) {
        return new SetProjectOperationScreensResponse(status, dryRun, project, sourceSchemeId, sourceSchemeName,
                newSchemeName, topScheme, assignedSchemeId, matrixSource, issueTypes, screenSchemes, created, assignment,
                warnings, message, errorCode, id, expiresAt);
    }

    private boolean inPlace() { return topScheme != null && topScheme.getMode() == SchemeMode.UPDATE_PRIVATE; }

    /** The terminal outcome of a successful apply; the matrix is what Jira returned, not what was asked. */
    public SetProjectOperationScreensResponse assigned(Long topId, List<TypeAssignment> readBack, List<SchemeChange> schemes,
            Created createdObjects, List<String> notes) {
        return new SetProjectOperationScreensResponse(Status.ASSIGNED, false, project, sourceSchemeId, sourceSchemeName,
                newSchemeName, topScheme, topId, MatrixSource.READ_BACK, readBack, schemes, createdObjects,
                AssignmentState.ASSIGNED, notes, doneText(topId) + " The matrix below was read back from Jira.",
                null, null, null);
    }

    /** Assigned, but Jira could not be read back: the matrix stays the plan and is labelled as such. */
    public SetProjectOperationScreensResponse assignedUnverified(Long topId, List<SchemeChange> schemes,
            Created createdObjects, List<String> notes) {
        return new SetProjectOperationScreensResponse(Status.ASSIGNED, false, project, sourceSchemeId, sourceSchemeName,
                newSchemeName, topScheme, topId, MatrixSource.PLANNED, issueTypes, schemes, createdObjects,
                AssignmentState.ASSIGNED, notes, doneText(topId) + " The matrix is the plan: Jira could not be read back.",
                null, null, null);
    }

    private String doneText(Long topId) {
        return inPlace()
                ? "Issue Type Screen Scheme " + topId + " was updated in place; the project keeps it."
                : "The project now uses Issue Type Screen Scheme " + topId + ".";
    }

    /**
     * An apply that did not complete: created objects are listed. {@code readBack} is the matrix Jira returned after the
     * failure (null when it was not read); the plan is never presented as the outcome.
     */
    public SetProjectOperationScreensResponse incomplete(Status outcome, AssignmentState state, List<TypeAssignment> readBack,
            Created createdObjects, List<SchemeChange> schemes, List<String> notes, String text) {
        return new SetProjectOperationScreensResponse(outcome, false, project, sourceSchemeId, sourceSchemeName,
                newSchemeName, topScheme, null, readBack == null ? null : MatrixSource.READ_BACK, readBack, schemes,
                createdObjects, state, notes, text, ErrorCode.JIRA_ERROR, null, null);
    }

    public static SetProjectOperationScreensResponse failed(boolean dryRun, ErrorCode code, String message) {
        return new SetProjectOperationScreensResponse(Status.FAILED, dryRun, null, null, null, null, null, null, null,
                null, null, null, null, null, message, code, null, null);
    }

    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("undoWarning") public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }
    @JsonProperty("project") public ProjectRef getProject() { return project; }
    @JsonProperty("sourceSchemeId") public Long getSourceSchemeId() { return sourceSchemeId; }
    @JsonProperty("sourceSchemeName") public String getSourceSchemeName() { return sourceSchemeName; }
    @JsonProperty("newSchemeName") public String getNewSchemeName() { return newSchemeName; }
    @JsonProperty("topScheme") public TopSchemePlan getTopScheme() { return topScheme; }
    @JsonProperty("assignedSchemeId") public Long getAssignedSchemeId() { return assignedSchemeId; }
    @JsonProperty("matrixSource") public MatrixSource getMatrixSource() { return matrixSource; }
    @JsonProperty("issueTypes") public List<TypeAssignment> getIssueTypes() { return issueTypes; }
    @JsonProperty("screenSchemes") public List<SchemeChange> getScreenSchemes() { return screenSchemes; }
    @JsonProperty("created") public Created getCreated() { return created; }
    @JsonProperty("assignment") public AssignmentState getAssignment() { return assignment; }
    @JsonProperty("warnings") public List<String> getWarnings() { return warnings; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("planExpiresAt") public Long getPlanExpiresAt() { return planExpiresAt; }

    /**
     * How the Issue Type Screen Scheme level changes and why. {@code otherProjects} counts every other project Jira
     * reports as using the source (names: at most 10). {@code mappings} lists only the rows that change.
     */
    public static final class TopSchemePlan {
        private final SchemeMode mode;
        private final String reason;
        private final Long sourceId;
        private final String sourceName;
        private final boolean defaultScheme;
        private final int otherProjects;
        private final List<ProjectRef> otherProjectNames;
        private final List<MappingChange> mappings;
        public TopSchemePlan(SchemeMode mode, String reason, Long sourceId, String sourceName, boolean defaultScheme,
                int otherProjects, List<ProjectRef> otherProjectNames, List<MappingChange> mappings) {
            this.mode = mode; this.reason = reason; this.sourceId = sourceId; this.sourceName = sourceName;
            this.defaultScheme = defaultScheme; this.otherProjects = otherProjects;
            this.otherProjectNames = Immutables.listOf(otherProjectNames); this.mappings = Immutables.listOf(mappings);
        }
        @JsonProperty("mode") public SchemeMode getMode() { return mode; }
        @JsonProperty("reason") public String getReason() { return reason; }
        @JsonProperty("sourceId") public Long getSourceId() { return sourceId; }
        @JsonProperty("sourceName") public String getSourceName() { return sourceName; }
        @JsonProperty("isDefault") public boolean isDefaultScheme() { return defaultScheme; }
        @JsonProperty("otherProjects") public int getOtherProjects() { return otherProjects; }
        @JsonProperty("otherProjectNames") public List<ProjectRef> getOtherProjectNames() { return otherProjectNames; }
        @JsonProperty("mappings") public List<MappingChange> getMappings() { return mappings; }
    }

    /**
     * One changing row of the top scheme: {@code issueType == null} is the DEFAULT row. {@code before} is the Screen
     * Scheme the type used until now (the inherited one for ADD); {@code after} is a new Screen Scheme ({@code id == null}).
     */
    public static final class MappingChange {
        private final IssueTypeRef issueType;
        private final MappingAction action;
        private final SchemeRef before;
        private final SchemeRef after;
        public MappingChange(IssueTypeRef issueType, MappingAction action, SchemeRef before, SchemeRef after) {
            this.issueType = issueType; this.action = action; this.before = before; this.after = after;
        }
        @JsonProperty("scope") public String getScope() { return issueType == null ? "DEFAULT" : "ISSUE_TYPE"; }
        @JsonProperty("issueType") public IssueTypeRef getIssueType() { return issueType; }
        @JsonProperty("action") public MappingAction getAction() { return action; }
        @JsonProperty("before") public SchemeRef getBefore() { return before; }
        @JsonProperty("after") public SchemeRef getAfter() { return after; }
    }

    public static final class ScreenRef {
        private final Long id;
        private final String name;
        public ScreenRef(Long id, String name) { this.id = id; this.name = name; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
    }

    public static final class OperationChange {
        private final String operation;
        private final ScreenRef before;
        private final ScreenRef after;
        public OperationChange(String operation, ScreenRef before, ScreenRef after) {
            this.operation = operation; this.before = before; this.after = after;
        }
        @JsonProperty("operation") public String getOperation() { return operation; }
        @JsonProperty("before") public ScreenRef getBefore() { return before; }
        @JsonProperty("after") public ScreenRef getAfter() { return after; }
    }

    /** A Screen Scheme of one issue type: {@code id} is null for a copy that does not exist yet ({@code created=true}). */
    public static final class SchemeRef {
        private final Long id;
        private final String name;
        private final boolean created;
        public SchemeRef(Long id, String name, boolean created) { this.id = id; this.name = name; this.created = created; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("created") public boolean isCreated() { return created; }
    }

    /**
     * Final CREATE/EDIT/VIEW of one project issue type. {@code selected}: some row names the type (or an all-types row
     * applies); {@code explicitMapping}: the new Issue Type Screen Scheme has its own row for the type, otherwise the
     * type follows the DEFAULT row; {@code changed}: some operation or its Screen Scheme differs from before.
     */
    public static final class TypeAssignment {
        private final IssueTypeRef issueType;
        private final boolean selected;
        private final boolean explicitMapping;
        private final boolean changed;
        private final SchemeRef screenSchemeBefore;
        private final SchemeRef screenSchemeAfter;
        private final List<OperationChange> operations;
        public TypeAssignment(IssueTypeRef issueType, boolean selected, boolean explicitMapping, boolean changed,
                SchemeRef screenSchemeBefore, SchemeRef screenSchemeAfter, List<OperationChange> operations) {
            this.issueType = issueType; this.selected = selected; this.explicitMapping = explicitMapping;
            this.changed = changed; this.screenSchemeBefore = screenSchemeBefore;
            this.screenSchemeAfter = screenSchemeAfter; this.operations = Immutables.listOf(operations);
        }
        @JsonProperty("issueType") public IssueTypeRef getIssueType() { return issueType; }
        @JsonProperty("selected") public boolean isSelected() { return selected; }
        @JsonProperty("explicitMapping") public boolean isExplicitMapping() { return explicitMapping; }
        @JsonProperty("changed") public boolean isChanged() { return changed; }
        @JsonProperty("screenSchemeBefore") public SchemeRef getScreenSchemeBefore() { return screenSchemeBefore; }
        @JsonProperty("screenSchemeAfter") public SchemeRef getScreenSchemeAfter() { return screenSchemeAfter; }
        @JsonProperty("operations") public List<OperationChange> getOperations() { return operations; }
    }

    /**
     * One new child Screen Scheme: a copy of {@code sourceId} with the changed operations. {@code appliesTo} names the
     * rows of the new Issue Type Screen Scheme that use it ({@code DEFAULT} or issue-type names).
     */
    public static final class SchemeChange {
        private final Long sourceId;
        private final String sourceName;
        private final String newName;
        private final Long newId;
        private final List<String> appliesTo;
        private final List<OperationChange> operations;
        public SchemeChange(Long sourceId, String sourceName, String newName, Long newId, List<String> appliesTo,
                List<OperationChange> operations) {
            this.sourceId = sourceId; this.sourceName = sourceName; this.newName = newName; this.newId = newId;
            this.appliesTo = Immutables.listOf(appliesTo); this.operations = Immutables.listOf(operations);
        }
        @JsonProperty("sourceId") public Long getSourceId() { return sourceId; }
        @JsonProperty("sourceName") public String getSourceName() { return sourceName; }
        @JsonProperty("newName") public String getNewName() { return newName; }
        @JsonProperty("newId") public Long getNewId() { return newId; }
        @JsonProperty("appliesTo") public List<String> getAppliesTo() { return appliesTo; }
        @JsonProperty("operations") public List<OperationChange> getOperations() { return operations; }
    }

    /** Objects that really exist after an apply, successful or not. {@code complete=false}: filling was interrupted. */
    public static final class Created {
        private final CreatedObject issueTypeScreenScheme;
        private final List<CreatedObject> screenSchemes;
        public Created(CreatedObject issueTypeScreenScheme, List<CreatedObject> screenSchemes) {
            this.issueTypeScreenScheme = issueTypeScreenScheme; this.screenSchemes = Immutables.listOf(screenSchemes);
        }
        @JsonProperty("issueTypeScreenScheme") public CreatedObject getIssueTypeScreenScheme() { return issueTypeScreenScheme; }
        @JsonProperty("screenSchemes") public List<CreatedObject> getScreenSchemes() { return screenSchemes; }
    }

    public static final class CreatedObject {
        private final Long id;
        private final String name;
        private final boolean complete;
        public CreatedObject(Long id, String name, boolean complete) { this.id = id; this.name = name; this.complete = complete; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("complete") public boolean isComplete() { return complete; }
    }
}
