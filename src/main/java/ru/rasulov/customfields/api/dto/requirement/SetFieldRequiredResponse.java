package ru.rasulov.customfields.api.dto.requirement;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.List;

/** Preview or outcome of changing fields' required flags in a project's field configurations. */
public final class SetFieldRequiredResponse {
    public enum Status {
        WOULD_CHANGE_REQUIREMENTS, REQUIREMENTS_CHANGED, ALREADY_AS_REQUESTED, PARTIALLY_CHANGED, FAILED
    }
    public enum ErrorCode {
        VALIDATION_ERROR, UNKNOWN_PROJECT, UNKNOWN_FIELD, AMBIGUOUS_FIELD, UNKNOWN_ISSUE_TYPE,
        FIELD_NOT_APPLICABLE, FIELD_NOT_ON_CREATE_SCREEN, FIELD_HIDDEN, FIELD_REQUIRED_UNSUPPORTED, LOCKED_FIELD,
        ROW_CONFLICT, SHARED_FIELD_CONFIGURATION_SCHEME,
        PREVIEW_STALE, JIRA_ERROR
    }
    public enum TargetStatus {
        WOULD_MAKE_REQUIRED, MADE_REQUIRED, ALREADY_REQUIRED, WOULD_MAKE_OPTIONAL, MADE_OPTIONAL, ALREADY_OPTIONAL;

        public static TargetStatus of(boolean required, boolean changed, boolean dryRun) {
            if (!changed) return required ? ALREADY_REQUIRED : ALREADY_OPTIONAL;
            if (dryRun) return required ? WOULD_MAKE_REQUIRED : WOULD_MAKE_OPTIONAL;
            return required ? MADE_REQUIRED : MADE_OPTIONAL;
        }
    }

    private final boolean dryRun;
    private final Status status;
    private final ProjectRef project;
    private final List<FieldView> fields;
    private final List<FieldView> candidates;
    private final SchemeView fieldConfigurationScheme;
    private final List<TargetView> targets;
    private final ErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;
    private final PartialEffect partialEffect;
    private final List<FieldError> fieldErrors;

    private SetFieldRequiredResponse(boolean dryRun, Status status, ProjectRef project, List<FieldView> fields,
                                     List<FieldView> candidates, SchemeView fieldConfigurationScheme,
                                     List<TargetView> targets, ErrorCode errorCode, String message,
                                     List<String> suggestions, String planId, Long expiresAt,
                                     PartialEffect partialEffect, List<FieldError> fieldErrors) {
        this.dryRun = dryRun;
        this.status = status;
        this.project = project;
        this.fields = Immutables.listOf(fields);
        this.candidates = Immutables.listOf(candidates);
        this.fieldConfigurationScheme = fieldConfigurationScheme;
        this.targets = Immutables.listOf(targets);
        this.errorCode = errorCode;
        this.message = message;
        this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId;
        this.expiresAt = expiresAt;
        this.partialEffect = partialEffect;
        this.fieldErrors = fieldErrors == null ? null : Immutables.listOf(fieldErrors);
    }

    /** Statuses are per issue type and field in {@code targets}. */
    public static SetFieldRequiredResponse success(boolean dryRun, boolean changed, ProjectRef project,
                                                        List<FieldView> fields, SchemeView scheme,
                                                        List<TargetView> targets, String note) {
        Status status = !changed ? Status.ALREADY_AS_REQUESTED
                : dryRun ? Status.WOULD_CHANGE_REQUIREMENTS : Status.REQUIREMENTS_CHANGED;
        String message = !changed ? "Every field already has the requested required flag in every selected issue "
                + "type; nothing was changed."
                : note;
        return new SetFieldRequiredResponse(dryRun, status, project, fields, null, scheme, targets,
                null, message, null, null, null, null, null);
    }

    public static SetFieldRequiredResponse failed(boolean dryRun, ProjectRef project, ErrorCode code, String message,
                                                   List<String> suggestions, List<FieldView> candidates) {
        return new SetFieldRequiredResponse(dryRun, Status.FAILED, project, null, candidates, null, null,
                code, message, suggestions, null, null, null, null);
    }

    /**
     * An apply that failed after it started writing. Never says "nothing was changed": {@code effect} lists what
     * persists, what this operation undid, and what it could not undo.
     */
    public static SetFieldRequiredResponse incomplete(ProjectRef project, List<FieldView> fields, String message,
                                                       PartialEffect effect) {
        boolean persists = !effect.getApplied().isEmpty() || !effect.getLeftBehind().isEmpty();
        return new SetFieldRequiredResponse(false, persists ? Status.PARTIALLY_CHANGED : Status.FAILED, project, fields,
                null, null, null, ErrorCode.JIRA_ERROR, message, null, null, null, effect, null);
    }

    public static SetFieldRequiredResponse stale() {
        return failed(false, null, ErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again; nothing was changed.",
                null, null);
    }

    public SetFieldRequiredResponse withPlan(String id, long expiry) {
        return new SetFieldRequiredResponse(true, status, project, fields, candidates, fieldConfigurationScheme,
                targets, errorCode, message, suggestions, id, expiry, partialEffect, fieldErrors);
    }

    public SetFieldRequiredResponse withFieldErrors(List<FieldError> errors) {
        return new SetFieldRequiredResponse(dryRun, status, project, fields, candidates, fieldConfigurationScheme,
                targets, errorCode, message, suggestions, planId, expiresAt, partialEffect, errors);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("undoWarning") public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("project") public ProjectRef getProject() { return project; }
    @JsonProperty("fields") public List<FieldView> getFields() { return fields; }
    @JsonProperty("candidates") public List<FieldView> getCandidates() { return candidates; }
    @JsonProperty("fieldConfigurationScheme") public SchemeView getFieldConfigurationScheme() { return fieldConfigurationScheme; }
    @JsonProperty("targets") public List<TargetView> getTargets() { return targets; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
    @JsonProperty("partialEffect") @JsonInclude(JsonInclude.Include.NON_NULL)
    public PartialEffect getPartialEffect() { return partialEffect; }
    @JsonProperty("fieldErrors") @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<FieldError> getFieldErrors() { return fieldErrors; }

    public static final class FieldError {
        private final String selector;
        private final ErrorCode errorCode;
        private final String message;
        private final List<FieldView> candidates;

        public FieldError(String selector, ErrorCode errorCode, String message, List<FieldView> candidates) {
            this.selector = selector; this.errorCode = errorCode; this.message = message;
            this.candidates = Immutables.listOf(candidates);
        }
        @JsonProperty("selector") public String getSelector() { return selector; }
        @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
        @JsonProperty("message") public String getMessage() { return message; }
        @JsonProperty("candidates") public List<FieldView> getCandidates() { return candidates; }
    }

    public static final class FieldView {
        private final String id;
        private final String name;
        private final String typeKey;
        private final String typeName;

        public FieldView(String id, String name, String typeKey, String typeName) {
            this.id = id; this.name = name; this.typeKey = typeKey; this.typeName = typeName;
        }
        @JsonProperty("id") public String getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("typeKey") public String getTypeKey() { return typeKey; }
        @JsonProperty("typeName") public String getTypeName() { return typeName; }
    }

    public static final class SchemeView {
        private final Long id;
        private final String name;
        private final boolean defaultScheme;

        public SchemeView(Long id, String name, boolean defaultScheme) {
            this.id = id; this.name = name; this.defaultScheme = defaultScheme;
        }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("defaultScheme") public boolean isDefaultScheme() { return defaultScheme; }
    }

    public static final class FieldStatus {
        private final String id;
        private final TargetStatus status;
        public FieldStatus(String id, TargetStatus status) { this.id = id; this.status = status; }
        @JsonProperty("id") public String getId() { return id; }
        @JsonProperty("status") public TargetStatus getStatus() { return status; }
    }

    /**
     * One selected issue type. fieldConfiguration* is the configuration it uses before the change. When unselected
     * issue types of the project share that configuration, newFieldConfiguration* is the private copy the selected
     * type moves to (id only after apply) and unchangedIssueTypes keep the original; otherwise both are empty.
     */
    public static final class TargetView {
        private final IssueTypeRef issueType;
        private final Long fieldConfigurationId;
        private final String fieldConfigurationName;
        private final TargetStatus status;
        private final int otherProjectsTotal;
        private final List<ProjectRef> otherProjects;
        private final Long newFieldConfigurationId;
        private final String newFieldConfigurationName;
        private final List<IssueTypeRef> unchangedIssueTypes;
        private final List<FieldStatus> fields;

        public TargetView(IssueTypeRef issueType, Long fieldConfigurationId, String fieldConfigurationName,
                          TargetStatus status, int otherProjectsTotal, List<ProjectRef> otherProjects,
                          Long newFieldConfigurationId, String newFieldConfigurationName,
                          List<IssueTypeRef> unchangedIssueTypes, List<FieldStatus> fields) {
            this.issueType = issueType;
            this.fieldConfigurationId = fieldConfigurationId;
            this.fieldConfigurationName = fieldConfigurationName;
            this.status = status;
            this.otherProjectsTotal = otherProjectsTotal;
            this.otherProjects = Immutables.listOf(otherProjects);
            this.newFieldConfigurationId = newFieldConfigurationId;
            this.newFieldConfigurationName = newFieldConfigurationName;
            this.unchangedIssueTypes = Immutables.listOf(unchangedIssueTypes);
            this.fields = Immutables.listOf(fields);
        }
        @JsonProperty("issueType") public IssueTypeRef getIssueType() { return issueType; }
        @JsonProperty("fieldConfigurationId") public Long getFieldConfigurationId() { return fieldConfigurationId; }
        @JsonProperty("fieldConfigurationName") public String getFieldConfigurationName() { return fieldConfigurationName; }
        @JsonProperty("status") @JsonInclude(JsonInclude.Include.NON_NULL)
        public TargetStatus getStatus() { return status; }
        @JsonProperty("otherProjectsTotal") public int getOtherProjectsTotal() { return otherProjectsTotal; }
        @JsonProperty("otherProjects") public List<ProjectRef> getOtherProjects() { return otherProjects; }
        @JsonProperty("newFieldConfigurationId") public Long getNewFieldConfigurationId() { return newFieldConfigurationId; }
        @JsonProperty("newFieldConfigurationName") public String getNewFieldConfigurationName() { return newFieldConfigurationName; }
        @JsonProperty("unchangedIssueTypes") public List<IssueTypeRef> getUnchangedIssueTypes() { return unchangedIssueTypes; }
        @JsonProperty("fields") public List<FieldStatus> getFields() { return fields; }
    }
}
