package ru.rasulov.customfields.api.dto.screen;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** What happened to one field of an {@code add_field_to_screens} call: a {@link ScreenResult} per requested screen. */
public final class FieldScreensResult {

    private final String fieldId;
    private final String name;
    private final List<ScreenResult> results;

    public FieldScreensResult(String fieldId, String name, List<ScreenResult> results) {
        this.fieldId = fieldId;
        this.name = name;
        this.results = Immutables.listOf(results);
    }

    @JsonProperty("fieldId")
    public String getFieldId() {
        return fieldId;
    }

    /** Name of the field, to talk to the user in names rather than ids. */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /** In the order of the request's {@code screens}. */
    @JsonProperty("results")
    public List<ScreenResult> getResults() {
        return results;
    }
}
