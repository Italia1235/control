package ru.rasulov.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Copy the main version of an existing workflow to a new, unassigned workflow. */
public final class CopyWorkflowRequest {
    private final Boolean dryRun;
    private final String workflow;
    private final String name;
    private final String description;
    private final String planId;

    public CopyWorkflowRequest(boolean dryRun, String workflow, String name, String description) {
        this(dryRun, workflow, name, description, null);
    }

    @JsonCreator
    public CopyWorkflowRequest(@JsonProperty("dryRun") Boolean dryRun,
                               @JsonProperty("workflow") String workflow,
                               @JsonProperty("name") String name,
                               @JsonProperty("description") String description,
                               @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.workflow = workflow;
        this.name = name;
        this.description = description;
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("workflow") public String getWorkflow() { return workflow; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
