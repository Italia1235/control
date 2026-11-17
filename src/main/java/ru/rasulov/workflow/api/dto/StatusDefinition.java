package ru.rasulov.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One status of the workflow. Jira statuses are global: a status with this name that already exists is reused as it is.
 * <p>
 * Getters carry explicit {@code @JsonProperty}: Jira's REST Jackson mapper does not auto-detect {@code getX()}
 * methods, so unannotated getters are silently dropped from the JSON.
 */
public final class StatusDefinition {

    private final String name;
    private final String category;
    private final String description;

    @JsonCreator
    public StatusDefinition(@JsonProperty("name") String name,
                            @JsonProperty("category") String category,
                            @JsonProperty("description") String description) {
        this.name = name;
        this.category = category;
        this.description = description;
    }

    /** Status name as shown in Jira; for an existing status, as it is written in {@code list_statuses}. */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /**
     * {@code TO_DO}, {@code IN_PROGRESS} or {@code DONE}. Required for a status that does not exist yet; ignored for
     * an existing one, which keeps its own category.
     */
    @JsonProperty("category")
    public String getCategory() {
        return category;
    }

    /** Optional description of a new status. */
    @JsonProperty("description")
    public String getDescription() {
        return description;
    }
}
