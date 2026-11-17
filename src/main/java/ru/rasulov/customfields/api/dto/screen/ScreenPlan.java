package ru.rasulov.customfields.api.dto.screen;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.field.context.ContextSummary;

import java.util.List;

/**
 * The screens a field could be added to, worked out from the contexts the field really has. Read-only: nothing is
 * changed until {@code add_field_to_screens} is called with the screens the user agreed to.
 */
public final class ScreenPlan {

    private final String fieldId;
    private final String fieldName;
    private final List<ContextSummary> contexts;
    private final int total;
    private final boolean truncated;
    private final ScreenSummary summary;
    private final List<ScreenCandidate> screens;
    private final String nextCursor;

    public ScreenPlan(String fieldId, String fieldName, List<ContextSummary> contexts, int total, boolean truncated,
                      ScreenSummary summary, List<ScreenCandidate> screens) {
        this(fieldId, fieldName, contexts, total, truncated, summary, screens, null);
    }

    public ScreenPlan(String fieldId, String fieldName, List<ContextSummary> contexts, int total, boolean truncated,
                      ScreenSummary summary, List<ScreenCandidate> screens, String nextCursor) {
        this.fieldId = fieldId;
        this.fieldName = fieldName;
        this.contexts = Immutables.listOf(contexts);
        this.total = total;
        this.truncated = truncated;
        this.summary = summary;
        this.screens = Immutables.listOf(screens);
        this.nextCursor = nextCursor;
    }

    @JsonProperty("fieldId")
    public String getFieldId() {
        return fieldId;
    }

    @JsonProperty("fieldName")
    public String getFieldName() {
        return fieldName;
    }

    /** The contexts the plan was worked out for. */
    @JsonProperty("contexts")
    public List<ContextSummary> getContexts() {
        return contexts;
    }

    /** All screens found; more than {@code screens.size()} means the list was cut, see {@code truncated}. */
    @JsonProperty("total")
    public int getTotal() {
        return total;
    }

    @JsonProperty("truncated")
    public boolean isTruncated() {
        return truncated;
    }

    /** Counts over all found screens, also the ones cut from the list. */
    @JsonProperty("summary")
    public ScreenSummary getSummary() {
        return summary;
    }

    @JsonProperty("screens")
    public List<ScreenCandidate> getScreens() {
        return screens;
    }

    /** Continuation of this selection; absent on the last page, even when truncated is true. */
    @JsonProperty("nextCursor")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getNextCursor() {
        return nextCursor;
    }
}
