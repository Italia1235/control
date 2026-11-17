package ru.rasulov.screens.api.dto.layout;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import java.util.List;

/** One screen, one approved final layout. Null action lists remain distinguishable in apply mode. */
public final class EditScreenLayoutRequest {
    private final Boolean dryRun;
    private final Long screenId;
    private final List<ScreenFieldAdd> add;
    private final List<ScreenFieldMove> moves;
    private final List<String> remove;
    private final String planId;

    @JsonCreator
    public EditScreenLayoutRequest(@JsonProperty("dryRun") Boolean dryRun, @JsonProperty("screenId") Long screenId,
            @JsonProperty("add") List<ScreenFieldAdd> add, @JsonProperty("moves") List<ScreenFieldMove> moves,
            @JsonProperty("remove") List<String> remove, @JsonProperty("planId") String planId) {
        this.dryRun = dryRun; this.screenId = screenId;
        this.add = add == null ? null : Immutables.listOf(add);
        this.moves = moves == null ? null : Immutables.listOf(moves);
        this.remove = remove == null ? null : Immutables.listOf(remove); this.planId = planId;
    }
    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("screenId") public Long getScreenId() { return screenId; }
    @JsonProperty("add") public List<ScreenFieldAdd> getAdd() { return add; }
    @JsonProperty("moves") public List<ScreenFieldMove> getMoves() { return moves; }
    @JsonProperty("remove") public List<String> getRemove() { return remove; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
