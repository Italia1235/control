package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Input of {@code rename_workflow}: preview with workflow and newName, apply with the preview's planId only. */
public final class RenameWorkflowRequest {
    private final Boolean dryRun;
    private final String workflow;
    private final String newName;
    private final String planId;

    @JsonCreator
    public RenameWorkflowRequest(@JsonProperty("dryRun") Boolean dryRun, @JsonProperty("workflow") String workflow,
                                 @JsonProperty("newName") String newName, @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.workflow = workflow;
        this.newName = newName;
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("workflow") public String getWorkflow() { return workflow; }
    @JsonProperty("newName") public String getNewName() { return newName; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
