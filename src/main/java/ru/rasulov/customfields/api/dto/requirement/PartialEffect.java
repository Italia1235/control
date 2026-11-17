package ru.rasulov.customfields.api.dto.requirement;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * What a failed {@code set_field_required} apply left in Jira. Follows the progress vocabulary of the issue-type
 * execution summary (completed / unknown / not started) and adds what this operation undid itself.
 * {@code applied} persists; {@code compensated} was created or changed by this operation and then reverted;
 * {@code leftBehind} still exists or is in an unknown state and needs an administrator; {@code notStarted} was not touched.
 */
public final class PartialEffect {
    public enum Kind { REQUIRED_FLAG, FIELD_CONFIGURATION_COPY, ISSUE_TYPE_MAPPING }
    /** UNKNOWN: a write or its readback failed, the real state was not confirmed. LEFT_BEHIND: confirmed still present. */
    public enum State { APPLIED, COMPENSATED, LEFT_BEHIND, UNKNOWN, NOT_STARTED }

    private final List<Item> applied;
    private final List<Item> compensated;
    private final List<Item> leftBehind;
    private final List<Item> notStarted;

    public PartialEffect(List<Item> applied, List<Item> compensated, List<Item> leftBehind, List<Item> notStarted) {
        this.applied = Immutables.listOf(applied);
        this.compensated = Immutables.listOf(compensated);
        this.leftBehind = Immutables.listOf(leftBehind);
        this.notStarted = Immutables.listOf(notStarted);
    }

    @JsonProperty("applied") public List<Item> getApplied() { return applied; }
    @JsonProperty("compensated") public List<Item> getCompensated() { return compensated; }
    @JsonProperty("leftBehind") public List<Item> getLeftBehind() { return leftBehind; }
    @JsonProperty("notStarted") public List<Item> getNotStarted() { return notStarted; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Item {
        private final Kind kind;
        private final String id;
        private final String name;
        private final State state;
        private final String detail;
        private final String fieldId;
        private final String fieldName;

        public Item(Kind kind, String id, String name, State state, String detail) {
            this(kind, id, name, state, detail, null, null);
        }
        public Item(Kind kind, String id, String name, State state, String detail, String fieldId, String fieldName) {
            this.kind = kind; this.id = id; this.name = name; this.state = state; this.detail = detail;
            this.fieldId = fieldId; this.fieldName = fieldName;
        }

        @JsonProperty("kind") public Kind getKind() { return kind; }
        @JsonProperty("id") public String getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("state") public State getState() { return state; }
        @JsonProperty("detail") public String getDetail() { return detail; }
        @JsonProperty("fieldId") public String getFieldId() { return fieldId; }
        @JsonProperty("fieldName") public String getFieldName() { return fieldName; }
    }
}
