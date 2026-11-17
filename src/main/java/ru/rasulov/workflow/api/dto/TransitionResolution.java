package ru.rasulov.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Create input: SET/CLEAR; edit input and effective responses also support PRESERVE (no resolution writer). */
public final class TransitionResolution {
    private final String action;
    private final String value;
    private final String name;
    private final String description;

    public TransitionResolution(String action, String value) {
        this(action, value, null, null);
    }

    @JsonCreator
    public TransitionResolution(@JsonProperty("action") String action, @JsonProperty("value") String value,
                                @JsonProperty("name") String name, @JsonProperty("description") String description) {
        this.action = action;
        this.value = value;
        this.name = name;
        this.description = description;
    }

    @JsonProperty("action") public String getAction() { return action; }
    @JsonProperty("value") public String getValue() { return value; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
}
