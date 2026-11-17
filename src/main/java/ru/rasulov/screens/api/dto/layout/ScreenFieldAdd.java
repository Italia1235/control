package ru.rasulov.screens.api.dto.layout;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.screen.FieldPlace;

/** Add an orderable field to an existing tab; omitted tab/place mean first tab/LAST. */
public final class ScreenFieldAdd {
    private final String fieldId, tab, anchor;
    private final FieldPlace place;
    @JsonCreator public ScreenFieldAdd(@JsonProperty("fieldId") String fieldId, @JsonProperty("tab") String tab,
            @JsonProperty("place") FieldPlace place, @JsonProperty("anchor") String anchor) {
        this.fieldId = fieldId; this.tab = tab; this.place = place; this.anchor = anchor;
    }
    @JsonProperty("fieldId") public String getFieldId() { return fieldId; }
    @JsonProperty("tab") public String getTab() { return tab; }
    @JsonProperty("place") public FieldPlace getPlace() { return place; }
    @JsonProperty("anchor") public String getAnchor() { return anchor; }
}
