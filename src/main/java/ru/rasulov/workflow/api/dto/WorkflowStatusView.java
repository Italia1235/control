package ru.rasulov.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A status of the workflow as the result shows it: what it is called in Jira, and whether the call creates it. */
public final class WorkflowStatusView {

    private final String name;
    private final String id;
    private final String category;
    private final boolean isNew;

    public WorkflowStatusView(String name, String id, String category, boolean isNew) {
        this.name = name;
        this.id = id;
        this.category = category;
        this.isNew = isNew;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /** Jira status id; {@code null} for a status that does not exist yet (dry run). */
    @JsonProperty("id")
    public String getId() {
        return id;
    }

    /** {@code TO_DO}, {@code IN_PROGRESS} or {@code DONE}; {@code UNDEFINED} for an old status without one. */
    @JsonProperty("category")
    public String getCategory() {
        return category;
    }

    /** {@code true}: the status did not exist and this call creates (or created) it. */
    @JsonProperty("isNew")
    public boolean isNew() {
        return isNew;
    }
}
