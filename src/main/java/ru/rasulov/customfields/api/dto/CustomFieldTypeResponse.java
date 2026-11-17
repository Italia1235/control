package ru.rasulov.customfields.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import ru.rasulov.common.api.dto.Immutables;

/** A custom field type installed in Jira. */
public final class CustomFieldTypeResponse {

    private final String key;
    private final String name;
    private final String description;
    private final boolean supportsOptions;
    private final List<String> searcherKeys;

    public CustomFieldTypeResponse(String key, String name, String description, boolean supportsOptions,
                                   List<String> searcherKeys) {
        this.key = key;
        this.name = name;
        this.description = description;
        this.supportsOptions = supportsOptions;
        this.searcherKeys = Immutables.listOf(searcherKeys);
    }

    /** Full module key, usable as {@code typeKey}. */
    @JsonProperty("key")
    public String getKey() {
        return key;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("description")
    public String getDescription() {
        return description;
    }

    /** {@code true} if values are chosen from options (select, radio, ...). */
    @JsonProperty("supportsOptions")
    public boolean isSupportsOptions() {
        return supportsOptions;
    }

    /** Keys of the searchers compatible with the type. */
    @JsonProperty("searcherKeys")
    public List<String> getSearcherKeys() {
        return searcherKeys;
    }
}
