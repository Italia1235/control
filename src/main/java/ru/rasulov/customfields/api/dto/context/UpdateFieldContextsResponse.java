package ru.rasulov.customfields.api.dto.context;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import ru.rasulov.common.api.dto.Immutables;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The requested scope, its preview delta, and (after apply) the independently observed scope. */
public final class UpdateFieldContextsResponse {
    public enum Status { WOULD_UPDATE, UNCHANGED, UPDATED, PARTIAL, FAILED }
    public enum Verdict { WOULD_UPDATE, UNCHANGED, UPDATED, FAILED, NOT_ATTEMPTED }
    public enum ErrorCode {
        VALIDATION_ERROR, UNKNOWN_FIELD, FIELD_LOCKED, UNKNOWN_CONTEXT, MULTIPLE_CONFIGS,
        UNKNOWN_PROJECT, UNKNOWN_ISSUE_TYPE, PROJECT_IN_OTHER_CONTEXT, GLOBAL_CONTEXT_EXISTS,
        PREVIEW_STALE, JIRA_ERROR,
        OPTION_NOT_IN_CONTEXT, OPTION_DUPLICATE, OPTION_VALUE_INVALID, OPTIONS_LIMIT, OPTION_ORDER_INVALID,
        OPTION_CONFLICT, OPTIONS_NOT_SUPPORTED, OPTION_PARENT_HAS_ENABLED_CHILDREN, OPTION_PARENT_DISABLED,
        OPTIONS_NO_CHANGE
    }

    private final boolean dryRun;
    private final Status status;
    private final FieldContextsResponse.FieldView field;
    private final List<ContextView> contexts;
    private final List<ContextRef> unchangedContexts;
    private final boolean reindexRecommended;
    private final Boolean reindexNotificationDisplayed;
    private final ErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;
    private final Long failedContextId;
    private final String planId;
    private final Long expiresAt;

    public UpdateFieldContextsResponse(boolean dryRun, Status status, FieldContextsResponse.FieldView field,
                                       List<ContextView> contexts, List<ContextRef> unchangedContexts,
                                       boolean reindexRecommended, Boolean reindexNotificationDisplayed, ErrorCode errorCode, String message,
                                       List<String> suggestions, Long failedContextId, String planId, Long expiresAt) {
        this.dryRun = dryRun; this.status = status; this.field = field;
        this.contexts = Immutables.listOf(contexts); this.unchangedContexts = Immutables.listOf(unchangedContexts);
        this.reindexRecommended = reindexRecommended; this.errorCode = errorCode; this.message = message;
        this.reindexNotificationDisplayed = reindexNotificationDisplayed;
        this.suggestions = Immutables.listOf(suggestions); this.failedContextId = failedContextId;
        this.planId = planId; this.expiresAt = expiresAt;
    }

    public static UpdateFieldContextsResponse failed(boolean dryRun, ErrorCode code, String message, List<String> suggestions) {
        return new UpdateFieldContextsResponse(dryRun, Status.FAILED, null, null, null, false, null, code, message,
                suggestions, null, null, null);
    }
    public static UpdateFieldContextsResponse stale() {
        return failed(false, ErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed; nothing was changed. "
                        + "If the plan changed options, first re-read get_field_contexts with contextId and "
                        + "includeOptions=true and tell the user what changed in the options since the preview; "
                        + "then run preview again from that fresh read, show its table and ask again.", null);
    }
    public UpdateFieldContextsResponse withPlan(String id, long expiry) {
        return new UpdateFieldContextsResponse(dryRun, status, field, contexts, unchangedContexts, reindexRecommended,
                reindexNotificationDisplayed, errorCode, message, suggestions, failedContextId, id, expiry);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("field") public FieldContextsResponse.FieldView getField() { return field; }
    @JsonProperty("contexts") public List<ContextView> getContexts() { return contexts; }
    @JsonProperty("unchangedContexts") public List<ContextRef> getUnchangedContexts() { return unchangedContexts; }
    @JsonProperty("reindexRecommended") public boolean isReindexRecommended() { return reindexRecommended; }
    @JsonProperty("reindexNotificationDisplayed") public Boolean getReindexNotificationDisplayed() { return reindexNotificationDisplayed; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("failedContextId") public Long getFailedContextId() { return failedContextId; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
    @JsonProperty("undoWarning") public String getUndoWarning() {
        return "This operation cannot be rolled back automatically. Restore the before scopes with a new update_field_contexts preview and confirmation.";
    }
    @JsonProperty("note") public String getNote() {
        if (!reindexRecommended) return null;
        if (!dryRun && Boolean.FALSE.equals(reindexNotificationDisplayed))
            return "Jira не смогла показать администратору сообщение о переиндексации; переиндексация не запускалась";
        return dryRun ? "После изменения Jira покажет администратору сообщение о переиндексации; переиндексация не будет запущена."
                : "Jira показывает администратору сообщение о переиндексации; переиндексация не запускалась";
    }

    public static final class ContextRef {
        private final Long contextId;
        private final String name;
        public ContextRef(Long contextId, String name) { this.contextId = contextId; this.name = name; }
        @JsonProperty("contextId") public Long getContextId() { return contextId; }
        @JsonProperty("name") public String getName() { return name; }
    }

    public static final class ContextView {
        private final Long contextId;
        private final String name;
        private final Verdict verdict;
        private final Map<String, Object> before, after, actual, delta, assets, options;
        private final List<Long> preservedConfigIds;
        private final String message;
        public ContextView(Long contextId, String name, Verdict verdict, Map<String, Object> before,
                           Map<String, Object> after, Map<String, Object> actual, Map<String, Object> delta,
                           List<Long> preservedConfigIds, String message, Map<String, Object> assets,
                           Map<String, Object> options) {
            this.contextId = contextId; this.name = name; this.verdict = verdict;
            this.before = scope(before); this.after = scope(after); this.actual = scope(actual);
            this.delta = scope(delta); this.preservedConfigIds = Immutables.listOf(preservedConfigIds); this.message = message;
            this.assets = scope(assets); this.options = scope(options);
        }
        private static Map<String, Object> scope(Map<String, Object> value) {
            return value == null ? null : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(value));
        }
        @JsonProperty("contextId") public Long getContextId() { return contextId; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("verdict") public Verdict getVerdict() { return verdict; }
        @JsonProperty("before") public Map<String, Object> getBefore() { return before; }
        @JsonProperty("after") public Map<String, Object> getAfter() { return after; }
        @JsonProperty("actual") public Map<String, Object> getActual() { return actual; }
        @JsonProperty("preservedConfigIds") public List<Long> getPreservedConfigIds() { return preservedConfigIds; }
        @JsonProperty("addedProjects") public Object getAddedProjects() { return delta.get("addedProjects"); }
        @JsonProperty("removedProjects") public Object getRemovedProjects() { return delta.get("removedProjects"); }
        @JsonProperty("addedIssueTypes") public Object getAddedIssueTypes() { return delta.get("addedIssueTypes"); }
        @JsonProperty("removedIssueTypes") public Object getRemovedIssueTypes() { return delta.get("removedIssueTypes"); }
        @JsonProperty("addedCombinations") public Object getAddedCombinations() { return delta.get("addedCombinations"); }
        @JsonProperty("removedCombinations") public Object getRemovedCombinations() { return delta.get("removedCombinations"); }
        @JsonProperty("warnings") public Object getWarnings() { return delta.get("warnings"); }
        @JsonProperty("message") public String getMessage() { return message; }
        /** Assets object settings {before, after} when the request carried {@code assets}; absent otherwise. */
        @JsonProperty("assets") public Map<String, Object> getAssets() { return assets; }
        /**
         * Option changes {rows, warnings, unchanged} of this context when the request carried {@code options}. Never
         * serialized (not even as null) otherwise, so responses and fingerprints of requests without options keep
         * their exact content.
         */
        @JsonProperty("options") @JsonInclude(JsonInclude.Include.NON_NULL)
        public Map<String, Object> getOptions() { return options; }
    }
}
