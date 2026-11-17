package ru.rasulov.customfields.api.dto.screen;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Input of {@code add_field_to_screens}: every field of {@code fieldIds} goes to every screen of {@code screens}. */
public final class AddToScreensRequest {

    private final List<String> fieldIds;
    private final Boolean dryRun;
    private final List<ScreenTarget> screens;
    private final String planId;

    public AddToScreensRequest(List<String> fieldIds, boolean dryRun, List<ScreenTarget> screens) {
        this(fieldIds, dryRun, screens, null);
    }

    @JsonCreator
    public AddToScreensRequest(@JsonProperty("fieldIds") List<String> fieldIds,
                               @JsonProperty("dryRun") Boolean dryRun,
                               @JsonProperty("screens") List<ScreenTarget> screens,
                               @JsonProperty("planId") String planId) {
        this.fieldIds = Immutables.listOf(fieldIds);
        this.dryRun = dryRun;
        this.screens = Immutables.listOf(screens);
        this.planId = planId;
    }

    /** {@code customfield_10123}, or the bare number; each field once. */
    @JsonProperty("fieldIds")
    public List<String> getFieldIds() {
        return fieldIds;
    }

    @JsonProperty("dryRun")
    public Boolean isDryRun() {
        return dryRun;
    }

    @JsonProperty("screens")
    public List<ScreenTarget> getScreens() {
        return screens;
    }

    @JsonProperty("planId")
    public String getPlanId() { return planId; }
}
