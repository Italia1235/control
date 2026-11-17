package ru.rasulov.common.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** How many issues of one issue type stand in one status. */
public final class StatusUsage {

    private final String name;
    private final int issues;
    private final Boolean inWorkflow;
    private final String mappedTo;

    public StatusUsage(String name, int issues, Boolean inWorkflow) {
        this(name, issues, inWorkflow, null);
    }

    /** @param inWorkflow whether the target workflow has the status; {@code null} when there is no target (reading) */
    public StatusUsage(String name, int issues, Boolean inWorkflow, String mappedTo) {
        this.name = name;
        this.issues = issues;
        this.inWorkflow = inWorkflow;
        this.mappedTo = mappedTo;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("issues")
    public int getIssues() {
        return issues;
    }

    /** Whether the target workflow contains this status; {@code null} when reading without a target. */
    @JsonProperty("inWorkflow")
    public Boolean getInWorkflow() {
        return inWorkflow;
    }

    /** Target status selected explicitly when the workflow does not contain {@link #getName()}. */
    @JsonProperty("mappedTo")
    public String getMappedTo() {
        return mappedTo;
    }
}
