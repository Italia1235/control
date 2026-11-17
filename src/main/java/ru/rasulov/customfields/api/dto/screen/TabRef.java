package ru.rasulov.customfields.api.dto.screen;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A tab of a screen. */
public final class TabRef {

    private final long id;
    private final String name;

    public TabRef(long id, String name) {
        this.id = id;
        this.name = name;
    }

    @JsonProperty("id")
    public long getId() {
        return id;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }
}
