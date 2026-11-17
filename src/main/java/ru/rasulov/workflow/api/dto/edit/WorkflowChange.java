package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.workflow.api.dto.TransitionResolution;
import ru.rasulov.workflow.api.dto.StatusDefinition;

/**
 * One point change of an existing workflow. A field that is absent (null) means "keep"; which fields an
 * {@code op} reads is in the tool description. Transitions are addressed by {@code actionId}, steps by
 * {@code stepId}: names identify nothing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class WorkflowChange {
    private final String op;
    private final Integer actionId;
    private final Integer stepId;
    private final String statusId;
    private final String clientKey;
    private final String name;
    private final String description;
    private final TransitionSource source;
    private final Integer targetStepId;
    private final String targetClientKey;
    private final ScreenPatch screen;
    private final TransitionResolution resolution;
    private final String transitionKey;
    private final StatusDefinition newStatus;

    @JsonCreator
    public WorkflowChange(@JsonProperty("op") String op, @JsonProperty("actionId") Integer actionId,
                          @JsonProperty("stepId") Integer stepId, @JsonProperty("statusId") String statusId,
                          @JsonProperty("clientKey") String clientKey, @JsonProperty("name") String name,
                          @JsonProperty("description") String description,
                          @JsonProperty("source") TransitionSource source,
                          @JsonProperty("targetStepId") Integer targetStepId,
                          @JsonProperty("targetClientKey") String targetClientKey,
                          @JsonProperty("screen") ScreenPatch screen,
                          @JsonProperty("resolution") TransitionResolution resolution,
                          @JsonProperty("transitionKey") String transitionKey,
                          @JsonProperty("newStatus") StatusDefinition newStatus) {
        this.op = op;
        this.actionId = actionId;
        this.stepId = stepId;
        this.statusId = statusId;
        this.clientKey = clientKey;
        this.name = name;
        this.description = description;
        this.source = source;
        this.targetStepId = targetStepId;
        this.targetClientKey = targetClientKey;
        this.screen = screen;
        this.resolution = resolution;
        this.transitionKey = transitionKey;
        // Only existing.newStatus uses strict text-only descriptions on persisted-plan replay.
        // Omission means empty; leave the shared create.statuses DTO/serialization unchanged.
        this.newStatus = newStatus != null && newStatus.getDescription() == null
                ? new StatusDefinition(newStatus.getName(), newStatus.getCategory(), "") : newStatus;
    }

    @JsonProperty("op") public String getOp() { return op; }
    @JsonProperty("actionId") public Integer getActionId() { return actionId; }
    @JsonProperty("stepId") public Integer getStepId() { return stepId; }
    @JsonProperty("statusId") public String getStatusId() { return statusId; }
    @JsonProperty("clientKey") public String getClientKey() { return clientKey; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("source") public TransitionSource getSource() { return source; }
    @JsonProperty("targetStepId") public Integer getTargetStepId() { return targetStepId; }
    @JsonProperty("targetClientKey") public String getTargetClientKey() { return targetClientKey; }
    @JsonProperty("screen") public ScreenPatch getScreen() { return screen; }
    @JsonProperty("resolution") public TransitionResolution getResolution() { return resolution; }
    @JsonProperty("transitionKey") public String getTransitionKey() { return transitionKey; }
    @JsonProperty("newStatus") public StatusDefinition getNewStatus() { return newStatus; }
}
