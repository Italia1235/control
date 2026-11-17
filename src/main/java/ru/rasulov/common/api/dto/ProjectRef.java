package ru.rasulov.common.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A project as shown to the caller. */
public final class ProjectRef {

    private final String key;
    private final String name;

    public ProjectRef(String key, String name) {
        this.key = key;
        this.name = name;
    }

    @JsonProperty("key")
    public String getKey() {
        return key;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }
}
