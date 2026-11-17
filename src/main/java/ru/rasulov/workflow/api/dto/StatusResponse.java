package ru.rasulov.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One element of the {@code list_statuses} answer. */
public final class StatusResponse {

    private final String id;
    private final String name;
    private final String category;
    private final String description;

    public StatusResponse(String id, String name, String category, String description) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
    }

    @JsonProperty("id")
    public String getId() {
        return id;
    }

    /** Write the name exactly like this when a workflow should reuse the status. */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /** {@code TO_DO}, {@code IN_PROGRESS}, {@code DONE}, or {@code UNDEFINED} for an old status without a category. */
    @JsonProperty("category")
    public String getCategory() {
        return category;
    }

    @JsonProperty("description")
    public String getDescription() {
        return description;
    }
}
