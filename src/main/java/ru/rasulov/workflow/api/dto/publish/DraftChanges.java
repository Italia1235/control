package ru.rasulov.workflow.api.dto.publish;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * What the draft changes against the active workflow: statuses and transitions by name, and transitions whose
 * definition (conditions, validators, post-functions, screen, properties) differs. {@code descriptorChanged} is true
 * when anything in the workflow definition differs, including what the lists do not name.
 */
public final class DraftChanges {

    private final List<String> addedStatuses;
    private final List<String> removedStatuses;
    private final List<String> addedTransitions;
    private final List<String> removedTransitions;
    private final List<String> changedTransitions;
    private final boolean descriptorChanged;

    public DraftChanges(List<String> addedStatuses, List<String> removedStatuses, List<String> addedTransitions,
                        List<String> removedTransitions, List<String> changedTransitions, boolean descriptorChanged) {
        this.addedStatuses = Immutables.listOf(addedStatuses);
        this.removedStatuses = Immutables.listOf(removedStatuses);
        this.addedTransitions = Immutables.listOf(addedTransitions);
        this.removedTransitions = Immutables.listOf(removedTransitions);
        this.changedTransitions = Immutables.listOf(changedTransitions);
        this.descriptorChanged = descriptorChanged;
    }

    @JsonProperty("addedStatuses")
    public List<String> getAddedStatuses() { return addedStatuses; }

    @JsonProperty("removedStatuses")
    public List<String> getRemovedStatuses() { return removedStatuses; }

    @JsonProperty("addedTransitions")
    public List<String> getAddedTransitions() { return addedTransitions; }

    @JsonProperty("removedTransitions")
    public List<String> getRemovedTransitions() { return removedTransitions; }

    @JsonProperty("changedTransitions")
    public List<String> getChangedTransitions() { return changedTransitions; }

    @JsonProperty("descriptorChanged")
    public boolean isDescriptorChanged() { return descriptorChanged; }
}
