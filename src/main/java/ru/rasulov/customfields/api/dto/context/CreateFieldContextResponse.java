package ru.rasulov.customfields.api.dto.context;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.report.ChangeReport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Preview and result of create_field_context: the exact field, the new context with its scope and what it receives,
 * the contexts that stay as they are, the effective change, and (after apply) the independently observed context.
 */
public final class CreateFieldContextResponse {
    public enum Status { WOULD_CREATE, CREATED, PARTIAL, FAILED }
    public enum ErrorCode {
        VALIDATION_ERROR, UNKNOWN_FIELD, FIELD_LOCKED, UNSUPPORTED_FIELD_TYPE, UNKNOWN_PROJECT, UNKNOWN_ISSUE_TYPE,
        CONTEXT_NAME_EXISTS, PROJECT_IN_OTHER_CONTEXT, GLOBAL_CONTEXT_EXISTS, PREVIEW_STALE, JIRA_ERROR
    }

    private final boolean dryRun;
    private final Status status;
    private final FieldContextsResponse.FieldView field;
    private final NewContext context;
    private final List<ExistingContext> unchangedContexts;
    private final Map<String, Object> effectiveChange;
    private final Map<String, Object> settings;
    private final List<Map<String, Object>> warnings;
    private final boolean reindexRecommended;
    private final Boolean reindexNotificationDisplayed;
    private final ErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;
    private final Map<String, Object> conflict;
    private final String planId;
    private final Long expiresAt;
    private final ChangeReport report;

    public CreateFieldContextResponse(boolean dryRun, Status status, FieldContextsResponse.FieldView field,
                                      NewContext context, List<ExistingContext> unchangedContexts,
                                      Map<String, Object> effectiveChange, Map<String, Object> settings,
                                      List<Map<String, Object>> warnings, boolean reindexRecommended,
                                      Boolean reindexNotificationDisplayed, ErrorCode errorCode, String message,
                                      List<String> suggestions, Map<String, Object> conflict, String planId,
                                      Long expiresAt, ChangeReport report) {
        this.dryRun = dryRun; this.status = status; this.field = field; this.context = context;
        this.unchangedContexts = Immutables.listOf(unchangedContexts);
        this.effectiveChange = effectiveChange == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(effectiveChange));
        this.settings = settings == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(settings));
        this.warnings = Immutables.listOf(warnings);
        this.reindexRecommended = reindexRecommended; this.reindexNotificationDisplayed = reindexNotificationDisplayed;
        this.errorCode = errorCode; this.message = message; this.suggestions = Immutables.listOf(suggestions);
        this.conflict = conflict == null ? null : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(conflict));
        this.planId = planId; this.expiresAt = expiresAt; this.report = report;
    }

    public static CreateFieldContextResponse failed(boolean dryRun, ErrorCode code, String message,
                                                    List<String> suggestions, Map<String, Object> conflict) {
        return new CreateFieldContextResponse(dryRun, Status.FAILED, null, null, null, null, null, null, false, null,
                code, message, suggestions, conflict, null, null, null);
    }

    public static CreateFieldContextResponse stale() {
        return failed(false, ErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again; nothing was changed.",
                null, null);
    }

    public CreateFieldContextResponse withPlan(String id, long expiry) {
        return new CreateFieldContextResponse(dryRun, status, field, context, unchangedContexts, effectiveChange,
                settings, warnings, reindexRecommended, reindexNotificationDisplayed, errorCode, message, suggestions,
                conflict, id, expiry, report);
    }

    public CreateFieldContextResponse withReport(ChangeReport changes) {
        return new CreateFieldContextResponse(dryRun, status, field, context, unchangedContexts, effectiveChange,
                settings, warnings, reindexRecommended, reindexNotificationDisplayed, errorCode, message, suggestions,
                conflict, planId, expiresAt, changes);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("field") public FieldContextsResponse.FieldView getField() { return field; }
    @JsonProperty("context") public NewContext getContext() { return context; }
    @JsonProperty("unchangedContexts") public List<ExistingContext> getUnchangedContexts() { return unchangedContexts; }
    @JsonProperty("effectiveChange") public Map<String, Object> getEffectiveChange() { return effectiveChange; }
    @JsonProperty("settings") public Map<String, Object> getSettings() { return settings; }
    @JsonProperty("warnings") public List<Map<String, Object>> getWarnings() { return warnings; }
    @JsonProperty("reindexRecommended") public boolean isReindexRecommended() { return reindexRecommended; }
    @JsonProperty("reindexNotificationDisplayed") public Boolean getReindexNotificationDisplayed() { return reindexNotificationDisplayed; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("conflict") public Map<String, Object> getConflict() { return conflict; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
    @JsonProperty("report") public ChangeReport getReport() { return report; }
    @JsonProperty("undoNote") public String getUndoNote() {
        return "Automatic undo is not supported for create_field_context. The journal retains the operation, but "
                + "does not delete its context. Manual cleanup requires checking issue values, later configuration "
                + "changes and the effective global/project configuration first. Failed or PARTIAL results are not rolled back.";
    }
    @JsonProperty("note") public String getNote() {
        if (!reindexRecommended) return null;
        if (!dryRun && Boolean.FALSE.equals(reindexNotificationDisplayed))
            return "Jira не смогла показать администратору сообщение о переиндексации; переиндексация не запускалась";
        return dryRun ? "После создания Jira покажет администратору сообщение о переиндексации; переиндексация не будет запущена."
                : "Jira показывает администратору сообщение о переиндексации; переиндексация не запускалась";
    }

    /** The context to create (preview) or the created one (contextId, configIds and actual are set after apply). */
    public static final class NewContext {
        private final Long contextId;
        private final String name, description;
        private final Map<String, Object> scope, actual;
        private final List<Long> configIds;
        public NewContext(Long contextId, String name, String description, Map<String, Object> scope,
                          Map<String, Object> actual, List<Long> configIds) {
            this.contextId = contextId; this.name = name; this.description = description;
            this.scope = scope == null ? null : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(scope));
            this.actual = actual == null ? null : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(actual));
            this.configIds = Immutables.listOf(configIds);
        }
        @JsonProperty("contextId") public Long getContextId() { return contextId; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("description") public String getDescription() { return description; }
        @JsonProperty("scope") public Map<String, Object> getScope() { return scope; }
        @JsonProperty("actual") public Map<String, Object> getActual() { return actual; }
        @JsonProperty("configIds") public List<Long> getConfigIds() { return configIds; }
    }

    /** A context that exists already and is not touched. */
    public static final class ExistingContext {
        private final Long contextId;
        private final String name, description;
        private final Map<String, Object> scope;
        private final List<Long> configIds;
        public ExistingContext(Long contextId, String name, String description, Map<String, Object> scope,
                               List<Long> configIds) {
            this.contextId = contextId; this.name = name; this.description = description;
            this.scope = scope == null ? null : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(scope));
            this.configIds = Immutables.listOf(configIds);
        }
        @JsonProperty("contextId") public Long getContextId() { return contextId; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("description") public String getDescription() { return description; }
        @JsonProperty("scope") public Map<String, Object> getScope() { return scope; }
        @JsonProperty("configIds") public List<Long> getConfigIds() { return configIds; }
    }
}
