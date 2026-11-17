package ru.rasulov.screens.api.dto.layout;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import java.util.List;

public final class RemoveFieldFromScreensRequest {
    private final Boolean dryRun;
    private final List<String> fieldIds;
    private final List<ScreenTarget> screens;
    private final String planId;
    @JsonCreator
    public RemoveFieldFromScreensRequest(@JsonProperty("dryRun") Boolean dryRun,
            @JsonProperty("fieldIds") List<String> fieldIds, @JsonProperty("screens") List<ScreenTarget> screens,
            @JsonProperty("planId") String planId) {
        this.dryRun = dryRun; this.fieldIds = fieldIds == null ? null : Immutables.listOf(fieldIds);
        this.screens = screens == null ? null : Immutables.listOf(screens); this.planId = planId;
    }
    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("fieldIds") public List<String> getFieldIds() { return fieldIds; }
    @JsonProperty("screens") public List<ScreenTarget> getScreens() { return screens; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    public static final class ScreenTarget {
        private final Long screenId;
        @JsonCreator public ScreenTarget(@JsonProperty("screenId") Long screenId) { this.screenId = screenId; }
        @JsonProperty("screenId") public Long getScreenId() { return screenId; }
    }
}
