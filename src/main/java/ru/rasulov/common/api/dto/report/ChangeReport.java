package ru.rasulov.common.api.dto.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * What an applied create tool made ({@code created}) and which existing objects it found and used ({@code reused}).
 * Present only after {@code dryRun:false}. The plugin has no undo: the user deletes the created objects by hand.
 */
public final class ChangeReport {

    private final List<ChangedObject> created;
    private final List<ChangedObject> reused;

    public ChangeReport(List<ChangedObject> created, List<ChangedObject> reused) {
        this.created = Immutables.listOf(created);
        this.reused = Immutables.listOf(reused);
    }

    @JsonProperty("created")
    public List<ChangedObject> getCreated() { return created; }

    @JsonProperty("reused")
    public List<ChangedObject> getReused() { return reused; }
}
