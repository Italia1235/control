package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import ru.rasulov.common.api.dto.Immutables;

import java.util.Collections;
import java.util.List;

/** One snapshot-bound page of a screen's layout, not field applicability to an issue. */
public final class ScreenContentsResponse {
    public enum Status { READ, FAILED }
    public enum ErrorCode { SCREEN_NOT_FOUND }
    public enum Kind { SYSTEM, CUSTOM, UNKNOWN }

    private final Status status;
    private final ErrorCode errorCode;
    private final String message;
    private final ScreenView screen;
    private final int totalTabs;
    private final int totalFields;
    private final boolean complete;
    private final String nextCursor;
    private final List<TabView> tabs;

    private ScreenContentsResponse(Status status, ErrorCode errorCode, String message, ScreenView screen,
                                   int totalTabs, int totalFields, boolean complete, String nextCursor, List<TabView> tabs) {
        this.status = status;
        this.errorCode = errorCode;
        this.message = message;
        this.screen = screen;
        this.totalTabs = totalTabs;
        this.totalFields = totalFields;
        this.complete = complete;
        this.nextCursor = nextCursor;
        this.tabs = Immutables.listOf(tabs);
    }

    public static ScreenContentsResponse read(ScreenView screen, int totalTabs, int totalFields,
                                              String nextCursor, List<TabView> tabs) {
        return new ScreenContentsResponse(Status.READ, null, "Screen contents read.", screen,
                totalTabs, totalFields, nextCursor == null, nextCursor, tabs);
    }

    public static ScreenContentsResponse failed(ErrorCode code, String message) {
        return new ScreenContentsResponse(Status.FAILED, code, message, null, 0, 0, false, null,
                Collections.<TabView>emptyList());
    }

    @JsonProperty("status") public Status getStatus() { return status; }

    @JsonProperty("errorCode")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ErrorCode getErrorCode() { return errorCode; }

    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("screen") public ScreenView getScreen() { return screen; }
    @JsonProperty("totalTabs") public int getTotalTabs() { return totalTabs; }
    @JsonProperty("totalFields") public int getTotalFields() { return totalFields; }
    @JsonProperty("complete") public boolean isComplete() { return complete; }

    @JsonProperty("nextCursor")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getNextCursor() { return nextCursor; }

    @JsonProperty("tabs") public List<TabView> getTabs() { return tabs; }

    public static final class ScreenView {
        private final Long id;
        private final String name;
        private final String description;

        public ScreenView(Long id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }

        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }

        @JsonProperty("description")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        public String getDescription() { return description; }
    }

    public static final class TabView {
        private final Long id;
        private final String name;
        private final int position;
        private final int fieldCount;
        private final List<FieldView> fields;

        public TabView(Long id, String name, int position, int fieldCount, List<FieldView> fields) {
            this.id = id;
            this.name = name;
            this.position = position;
            this.fieldCount = fieldCount;
            this.fields = Immutables.listOf(fields);
        }

        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("position") public int getPosition() { return position; }
        @JsonProperty("fieldCount") public int getFieldCount() { return fieldCount; }
        @JsonProperty("fields") public List<FieldView> getFields() { return fields; }
    }

    public static final class FieldView {
        private final Long layoutItemId;
        private final String fieldId;
        private final String name;
        private final Kind kind;
        private final int position;
        private final boolean shownWhenEmpty;

        public FieldView(Long layoutItemId, String fieldId, String name, Kind kind, int position, boolean shownWhenEmpty) {
            this.layoutItemId = layoutItemId;
            this.fieldId = fieldId;
            this.name = name;
            this.kind = kind;
            this.position = position;
            this.shownWhenEmpty = shownWhenEmpty;
        }

        @JsonProperty("layoutItemId") public Long getLayoutItemId() { return layoutItemId; }
        @JsonProperty("fieldId") public String getFieldId() { return fieldId; }

        @JsonProperty("name")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        public String getName() { return name; }

        @JsonProperty("kind") public Kind getKind() { return kind; }
        @JsonProperty("position") public int getPosition() { return position; }
        @JsonProperty("shownWhenEmpty") public boolean isShownWhenEmpty() { return shownWhenEmpty; }
    }
}
