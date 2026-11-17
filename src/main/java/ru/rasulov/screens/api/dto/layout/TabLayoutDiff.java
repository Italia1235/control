package ru.rasulov.screens.api.dto.layout;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import java.util.List;

/** Preview windows retain absolute positions; actual layouts are independently observed and never truncated. */
public final class TabLayoutDiff {
    private final Long tabId;
    private final String name;
    private final List<FieldRef> before, after;
    private final boolean truncated;
    public TabLayoutDiff(Long tabId, String name, List<FieldRef> before, List<FieldRef> after, boolean truncated) {
        this.tabId = tabId; this.name = name; this.before = Immutables.listOf(before);
        this.after = Immutables.listOf(after); this.truncated = truncated;
    }
    @JsonProperty("tabId") public Long getTabId() { return tabId; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("before") public List<FieldRef> getBefore() { return before; }
    @JsonProperty("after") public List<FieldRef> getAfter() { return after; }
    @JsonProperty("truncated") public boolean isTruncated() { return truncated; }

    public static final class FieldRef {
        private final String fieldId, name;
        private final int index, position;
        private final Long layoutItemId;
        private final boolean shownWhenEmpty;
        public FieldRef(String fieldId, String name, int index, int position, Long layoutItemId, boolean shownWhenEmpty) {
            this.fieldId = fieldId; this.name = name; this.index = index; this.position = position;
            this.layoutItemId = layoutItemId; this.shownWhenEmpty = shownWhenEmpty;
        }
        @JsonProperty("fieldId") public String getFieldId() { return fieldId; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("index") public int getIndex() { return index; }
        @JsonProperty("position") public int getPosition() { return position; }
        @JsonProperty("layoutItemId") public Long getLayoutItemId() { return layoutItemId; }
        @JsonProperty("shownWhenEmpty") public boolean isShownWhenEmpty() { return shownWhenEmpty; }
    }
    public static final class TabLayout {
        private final Long tabId;
        private final String name;
        private final List<FieldRef> fields;
        public TabLayout(Long tabId, String name, List<FieldRef> fields) {
            this.tabId = tabId; this.name = name; this.fields = Immutables.listOf(fields);
        }
        @JsonProperty("tabId") public Long getTabId() { return tabId; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("fields") public List<FieldRef> getFields() { return fields; }
    }
}
