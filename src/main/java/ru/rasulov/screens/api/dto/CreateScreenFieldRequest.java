package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.field.OptionDefinition;

import java.util.List;

/** One field to put on a standalone screen; creation details are used only when no exact existing field matches. */
public final class CreateScreenFieldRequest {
    private final String name;
    private final String description;
    private final String typeKey;
    private final String searcherKey;
    private final List<OptionDefinition> options;

    @JsonCreator
    public CreateScreenFieldRequest(@JsonProperty("name") String name,
                                    @JsonProperty("description") String description,
                                    @JsonProperty("typeKey") String typeKey,
                                    @JsonProperty("searcherKey") String searcherKey,
                                    @JsonProperty("options") List<OptionDefinition> options) {
        this.name = name;
        this.description = description;
        this.typeKey = typeKey;
        this.searcherKey = searcherKey;
        this.options = Immutables.listOf(options);
    }

    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("typeKey") public String getTypeKey() { return typeKey; }
    @JsonProperty("searcherKey") public String getSearcherKey() { return searcherKey; }
    @JsonProperty("options") public List<OptionDefinition> getOptions() { return options; }
}
