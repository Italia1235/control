package ru.rasulov.screens.api.dto.layout;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import java.util.List;

/** Preview positions and independently observed poststate; no internal layout records escape through Jackson 1. */
public final class RemoveFieldFromScreensResponse {
    private final boolean dryRun;
    private final LayoutStatus status;
    private final LayoutErrorCode errorCode;
    private final String message, planId;
    private final Long expiresAt;
    private final List<FieldResult> results;
    private final List<ScreenImpact> screens;
    private final List<ActualScreen> actual;

    public RemoveFieldFromScreensResponse(boolean dryRun, LayoutStatus status, LayoutErrorCode errorCode,
            String message, List<FieldResult> results, List<ScreenImpact> screens, List<ActualScreen> actual,
            String planId, Long expiresAt) {
        this.dryRun = dryRun; this.status = status; this.errorCode = errorCode; this.message = message;
        this.results = Immutables.listOf(results); this.screens = Immutables.listOf(screens);
        this.actual = Immutables.listOf(actual); this.planId = planId; this.expiresAt = expiresAt;
    }
    public static RemoveFieldFromScreensResponse failed(boolean dryRun, LayoutErrorCode code, String message) {
        return new RemoveFieldFromScreensResponse(dryRun, LayoutStatus.FAILED, code, message, null, null, null, null, null);
    }
    public static RemoveFieldFromScreensResponse stale() {
        return failed(false, LayoutErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again; nothing was changed.");
    }
    public RemoveFieldFromScreensResponse withPlan(String id, long expiry) {
        return new RemoveFieldFromScreensResponse(dryRun, status, errorCode, message, results, screens, actual, id, expiry);
    }
    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("status") public LayoutStatus getStatus() { return status; }
    @JsonProperty("errorCode") public LayoutErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("results") public List<FieldResult> getResults() { return results; }
    @JsonProperty("screens") public List<ScreenImpact> getScreens() { return screens; }
    @JsonProperty("actual") public List<ActualScreen> getActual() { return actual; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
    @JsonProperty("undoWarning") public String getUndoWarning() {
        return "This operation cannot be rolled back automatically. Restore removed fields with a new add_field_to_screens preview and confirmation, using the previous tab and place:AFTER with anchor=before, or FIRST when before is absent. If a neighbor was also removed, restore it first. Issue data was not deleted.";
    }

    public static final class FieldResult {
        private final Long screenId, tabId;
        private final String fieldId, name, tab, before, after, message;
        private final Integer position;
        private final LayoutStatus status;
        private final LayoutErrorCode errorCode;
        private final int requiredPairs;
        private final List<RequiredProject> requiredProjects;
        private final boolean requiredProjectsTruncated;
        private final List<LayoutWarning> warnings;
        public FieldResult(Long screenId, String fieldId, String name, LayoutStatus status, LayoutErrorCode errorCode,
                Long tabId, String tab, Integer position, String before, String after, int requiredPairs,
                List<RequiredProject> requiredProjects, boolean requiredProjectsTruncated, List<LayoutWarning> warnings,
                String message) {
            this.screenId = screenId; this.fieldId = fieldId; this.name = name; this.status = status; this.errorCode = errorCode;
            this.tabId = tabId; this.tab = tab; this.position = position; this.before = before; this.after = after;
            this.requiredPairs = requiredPairs; this.requiredProjects = Immutables.listOf(requiredProjects);
            this.requiredProjectsTruncated = requiredProjectsTruncated;
            this.warnings = Immutables.listOf(warnings); this.message = message;
        }
        public FieldResult outcome(LayoutStatus status, LayoutErrorCode code, String message) {
            return new FieldResult(screenId, fieldId, name, status, code, tabId, tab, position, before, after,
                    requiredPairs, requiredProjects, requiredProjectsTruncated, warnings, message);
        }
        @JsonProperty("screenId") public Long getScreenId() { return screenId; }
        @JsonProperty("fieldId") public String getFieldId() { return fieldId; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("status") public LayoutStatus getStatus() { return status; }
        @JsonProperty("errorCode") public LayoutErrorCode getErrorCode() { return errorCode; }
        @JsonProperty("tabId") public Long getTabId() { return tabId; }
        @JsonProperty("tab") public String getTab() { return tab; }
        @JsonProperty("position") public Integer getPosition() { return position; }
        @JsonProperty("before") public String getBefore() { return before; }
        @JsonProperty("after") public String getAfter() { return after; }
        @JsonProperty("requiredPairs") public int getRequiredPairs() { return requiredPairs; }
        /** Projects and issue types where the field is required for CREATE through this screen: input for set_field_required. */
        @JsonProperty("requiredProjects") public List<RequiredProject> getRequiredProjects() { return requiredProjects; }
        @JsonProperty("requiredProjectsTruncated") public boolean isRequiredProjectsTruncated() { return requiredProjectsTruncated; }
        @JsonProperty("warnings") public List<LayoutWarning> getWarnings() { return warnings; }
        @JsonProperty("message") public String getMessage() { return message; }
    }
    public static final class RequiredProject {
        private final String projectKey, projectName;
        private final List<IssueTypeRef> issueTypes;
        public RequiredProject(String projectKey, String projectName, List<IssueTypeRef> issueTypes) {
            this.projectKey = projectKey; this.projectName = projectName; this.issueTypes = Immutables.listOf(issueTypes);
        }
        @JsonProperty("projectKey") public String getProjectKey() { return projectKey; }
        @JsonProperty("projectName") public String getProjectName() { return projectName; }
        @JsonProperty("issueTypes") public List<IssueTypeRef> getIssueTypes() { return issueTypes; }
    }
    public static final class ActualScreen {
        private final Long screenId;
        private final LayoutStatus status;
        private final List<ActualTab> tabs;
        private final String message;
        public ActualScreen(Long screenId, LayoutStatus status, List<ActualTab> tabs, String message) {
            this.screenId = screenId; this.status = status; this.tabs = tabs == null ? null : Immutables.listOf(tabs); this.message = message;
        }
        @JsonProperty("screenId") public Long getScreenId() { return screenId; }
        @JsonProperty("status") public LayoutStatus getStatus() { return status; }
        @JsonProperty("tabs") public List<ActualTab> getTabs() { return tabs; }
        @JsonProperty("message") public String getMessage() { return message; }
    }
    public static final class ActualTab {
        private final Long id;
        private final String name;
        private final int position;
        private final List<ActualField> fields;
        public ActualTab(Long id, String name, int position, List<ActualField> fields) {
            this.id = id; this.name = name; this.position = position; this.fields = Immutables.listOf(fields);
        }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("position") public int getPosition() { return position; }
        @JsonProperty("fields") public List<ActualField> getFields() { return fields; }
    }
    public static final class ActualField {
        private final Long layoutItemId;
        private final String fieldId;
        private final int position;
        private final boolean shownWhenEmpty;
        public ActualField(Long layoutItemId, String fieldId, int position, boolean shownWhenEmpty) {
            this.layoutItemId = layoutItemId; this.fieldId = fieldId; this.position = position; this.shownWhenEmpty = shownWhenEmpty;
        }
        @JsonProperty("layoutItemId") public Long getLayoutItemId() { return layoutItemId; }
        @JsonProperty("fieldId") public String getFieldId() { return fieldId; }
        @JsonProperty("position") public int getPosition() { return position; }
        @JsonProperty("shownWhenEmpty") public boolean isShownWhenEmpty() { return shownWhenEmpty; }
    }
}
