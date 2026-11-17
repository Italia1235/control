package ru.rasulov.workflow.api.dto.configure;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import java.util.List;

/** The complete graph of a new workflow, never an operation or a patch. */
public final class WorkflowDefinition {
    @JsonProperty private final String name, description, initialStatus;
    @JsonProperty private final List<StatusDefinition> statuses;
    @JsonProperty private final List<TransitionDefinition> transitions;
    @JsonCreator
    public WorkflowDefinition(@JsonProperty("name") String name,
                              @JsonProperty("description") String description,
                              @JsonProperty("statuses") List<StatusDefinition> statuses,
                              @JsonProperty("initialStatus") String initialStatus,
                              @JsonProperty("transitions") List<TransitionDefinition> transitions) {
        this.name = name; this.description = description; this.initialStatus = initialStatus;
        this.statuses = Immutables.listOf(statuses); this.transitions = Immutables.listOf(transitions);
    }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<StatusDefinition> getStatuses() { return statuses; }
    public String getInitialStatus() { return initialStatus; }
    public List<TransitionDefinition> getTransitions() { return transitions; }
}
