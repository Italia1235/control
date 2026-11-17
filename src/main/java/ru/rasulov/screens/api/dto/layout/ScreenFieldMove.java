package ru.rasulov.screens.api.dto.layout;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.screen.FieldPlace;

public final class ScreenFieldMove {
    private final String fieldId, tab, anchor;
    private final FieldPlace place;
    private final Integer steps;
    @JsonCreator
    public ScreenFieldMove(@JsonProperty("fieldId") String fieldId, @JsonProperty("tab") String tab,
            @JsonProperty("place") FieldPlace place, @JsonProperty("anchor") String anchor,
            @JsonProperty("steps") Integer steps) {
        this.fieldId = fieldId; this.tab = tab; this.place = place; this.anchor = anchor; this.steps = steps;
    }
    @JsonProperty("fieldId") public String getFieldId() { return fieldId; }
    @JsonProperty("tab") public String getTab() { return tab; }
    @JsonProperty("place") public FieldPlace getPlace() { return place; }
    @JsonProperty("anchor") public String getAnchor() { return anchor; }
    @JsonProperty("steps") public Integer getSteps() { return steps; }
}
