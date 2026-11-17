package ru.rasulov.common.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** An issue type in the {@code list_issue_types} answer. */
public final class IssueTypeResponse {

    private final String id;
    private final String name;
    private final String description;
    private final boolean subtask;

    public IssueTypeResponse(String id, String name, String description, boolean subtask) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.subtask = subtask;
    }

    @JsonProperty("id")
    public String getId() {
        return id;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("description")
    public String getDescription() {
        return description;
    }

    @JsonProperty("subtask")
    public boolean isSubtask() {
        return subtask;
    }
}
