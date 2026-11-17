package ru.rasulov.workflow.api.dto.publish;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Input of {@code publish_workflow_draft}: preview with the workflow name, apply with the preview's planId only. */
public final class PublishWorkflowDraftRequest {

    private final Boolean dryRun;
    private final String workflow;
    private final String planId;

    @JsonCreator
    public PublishWorkflowDraftRequest(@JsonProperty("dryRun") Boolean dryRun,
                                       @JsonProperty("workflow") String workflow,
                                       @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.workflow = workflow;
        this.planId = planId;
    }

    @JsonProperty("dryRun")
    public Boolean isDryRun() { return dryRun; }

    /** Exact name of the active workflow whose draft is published. */
    @JsonProperty("workflow")
    public String getWorkflow() { return workflow; }

    @JsonProperty("planId")
    public String getPlanId() { return planId; }
}
