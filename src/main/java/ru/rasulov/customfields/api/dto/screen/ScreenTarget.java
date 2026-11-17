package ru.rasulov.customfields.api.dto.screen;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.screen.FieldPlace;

/** One screen the field is to be added to. */
public final class ScreenTarget {

    private final Long screenId;
    private final String tab;
    private final FieldPlace place;
    private final String anchor;

    @JsonCreator
    public ScreenTarget(@JsonProperty("screenId") Long screenId, @JsonProperty("tab") String tab,
                        @JsonProperty("place") FieldPlace place, @JsonProperty("anchor") String anchor) {
        this.screenId = screenId;
        this.tab = tab;
        this.place = place;
        this.anchor = anchor;
    }

    /** Id of the screen from the plan. */
    @JsonProperty("screenId")
    public Long getScreenId() {
        return screenId;
    }

    /** Name or id of an existing tab; {@code null} means the first tab. */
    @JsonProperty("tab")
    public String getTab() {
        return tab;
    }

    /** Optional insertion location; {@code null} means LAST. UP and DOWN are not supported for additions. */
    @JsonProperty("place")
    public FieldPlace getPlace() {
        return place;
    }

    /** Exact field id in the target tab, required only for BEFORE and AFTER. */
    @JsonProperty("anchor")
    public String getAnchor() {
        return anchor;
    }
}
