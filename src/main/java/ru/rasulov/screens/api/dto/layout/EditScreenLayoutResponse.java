package ru.rasulov.screens.api.dto.layout;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import java.util.List;

public final class EditScreenLayoutResponse {
    private final boolean dryRun;
    private final Long screenId;
    private final String name;
    private final LayoutStatus status;
    private final List<MoveResult> add, moves;
    private final List<RemoveFieldFromScreensResponse.FieldResult> remove;
    private final List<TabLayoutDiff> tabs;
    private final List<TabLayoutDiff.TabLayout> actual;
    private final ScreenImpact impact;
    private final List<LayoutWarning> warnings;
    private final List<String> validationErrors;
    private final LayoutErrorCode errorCode;
    private final String message, planId;
    private final Long expiresAt;
    private final FieldChanges fieldChanges;
    public EditScreenLayoutResponse(boolean dryRun, Long screenId, String name, LayoutStatus status,
            List<MoveResult> add, List<MoveResult> moves, List<RemoveFieldFromScreensResponse.FieldResult> remove,
            List<TabLayoutDiff> tabs, List<TabLayoutDiff.TabLayout> actual, ScreenImpact impact,
            List<LayoutWarning> warnings, List<String> validationErrors, LayoutErrorCode errorCode, String message,
            String planId, Long expiresAt, FieldChanges fieldChanges) {
        this.dryRun = dryRun; this.screenId = screenId; this.name = name; this.status = status;
        this.add = Immutables.listOf(add); this.moves = Immutables.listOf(moves); this.remove = Immutables.listOf(remove);
        this.tabs = Immutables.listOf(tabs); this.actual = actual == null ? null : Immutables.listOf(actual);
        this.impact = impact; this.warnings = Immutables.listOf(warnings);
        this.validationErrors = Immutables.listOf(validationErrors); this.errorCode = errorCode; this.message = message;
        this.planId = planId; this.expiresAt = expiresAt;
        this.fieldChanges = fieldChanges;
    }
    public static EditScreenLayoutResponse failed(boolean dryRun, Long screenId, LayoutErrorCode code, String message) {
        return new EditScreenLayoutResponse(dryRun, screenId, null, LayoutStatus.FAILED, null, null, null, null, null,
                null, null, code == LayoutErrorCode.VALIDATION_ERROR ? List.of(message) : null, code, message, null, null, null);
    }
    public static EditScreenLayoutResponse stale() {
        return failed(false, null, LayoutErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again; nothing was changed.");
    }
    public EditScreenLayoutResponse withPlan(String id, long expiry) {
        return new EditScreenLayoutResponse(dryRun, screenId, name, status, add, moves, remove, tabs, actual, impact,
                warnings, validationErrors, errorCode, message, id, expiry, fieldChanges);
    }
    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("screenId") public Long getScreenId() { return screenId; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("status") public LayoutStatus getStatus() { return status; }
    @JsonProperty("add") public List<MoveResult> getAdd() { return add; }
    @JsonProperty("moves") public List<MoveResult> getMoves() { return moves; }
    @JsonProperty("remove") public List<RemoveFieldFromScreensResponse.FieldResult> getRemove() { return remove; }
    @JsonProperty("tabs") public List<TabLayoutDiff> getTabs() { return tabs; }
    @JsonProperty("actual") public List<TabLayoutDiff.TabLayout> getActual() { return actual; }
    @JsonProperty("impact") public ScreenImpact getImpact() { return impact; }
    @JsonProperty("warnings") public List<LayoutWarning> getWarnings() { return warnings; }
    @JsonProperty("validationErrors") public List<String> getValidationErrors() { return validationErrors; }
    @JsonProperty("errorCode") public LayoutErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
    @JsonProperty("fieldChanges") public FieldChanges getFieldChanges() { return fieldChanges; }

    /** Observed screen-wide membership delta; unavailable is distinct from an observed empty delta. */
    public static final class FieldChanges {
        private final boolean available;
        private final List<FieldChange> items;
        public FieldChanges(boolean available, List<FieldChange> items) {
            this.available = available;
            this.items = Immutables.listOf(items);
        }
        @JsonProperty("available") public boolean isAvailable() { return available; }
        @JsonProperty("items") public List<FieldChange> getItems() { return items; }
    }

    public static final class FieldChange {
        private final String fieldId, action;
        public FieldChange(String fieldId, String action) { this.fieldId = fieldId; this.action = action; }
        @JsonProperty("fieldId") public String getFieldId() { return fieldId; }
        @JsonProperty("action") public String getAction() { return action; }
    }
    @JsonProperty("undoWarning") public String getUndoWarning() {
        return "This operation cannot be rolled back automatically. Restore the before layout with a new edit_screen_layout preview and confirmation. Stored issue values are not deleted.";
    }
    public static final class MoveResult {
        private final int index;
        private final String fieldId;
        private final LayoutStatus status;
        private final LayoutErrorCode errorCode;
        private final String message;
        private final List<LayoutWarning> warnings;
        public MoveResult(int index, String fieldId, LayoutStatus status, LayoutErrorCode errorCode,
                String message, List<LayoutWarning> warnings) {
            this.index = index; this.fieldId = fieldId; this.status = status; this.errorCode = errorCode;
            this.message = message; this.warnings = Immutables.listOf(warnings);
        }
        @JsonProperty("index") public int getIndex() { return index; }
        @JsonProperty("fieldId") public String getFieldId() { return fieldId; }
        @JsonProperty("status") public LayoutStatus getStatus() { return status; }
        @JsonProperty("errorCode") public LayoutErrorCode getErrorCode() { return errorCode; }
        @JsonProperty("message") public String getMessage() { return message; }
        @JsonProperty("warnings") public List<LayoutWarning> getWarnings() { return warnings; }
    }
}
