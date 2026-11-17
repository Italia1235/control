package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** SET with an existing screen id, or CLEAR without one. */
public final class ScreenPatch {
    private final String action;
    private final Long screenId;

    @JsonCreator
    public ScreenPatch(@JsonProperty("action") String action, @JsonProperty("screenId") Long screenId) {
        this.action = action;
        this.screenId = screenId;
    }

    @JsonProperty("action") public String getAction() { return action; }
    @JsonProperty("screenId") public Long getScreenId() { return screenId; }
}
