package ru.rasulov.common.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** An issue type as shown to the caller. */
public final class IssueTypeRef {

    private final String id;
    private final String name;

    public IssueTypeRef(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @JsonProperty("id")
    public String getId() {
        return id;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }
}
