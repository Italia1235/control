package ru.rasulov.common.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Explicit conversion of an occupied status that the target workflow does not have. */
public final class StatusMapping {

    private final String from;
    private final String to;

    @JsonCreator
    public StatusMapping(@JsonProperty("from") String from, @JsonProperty("to") String to) {
        this.from = from;
        this.to = to;
    }

    /** Name of the current status. */
    @JsonProperty("from")
    public String getFrom() {
        return from;
    }

    /** Name of a status in the target workflow. */
    @JsonProperty("to")
    public String getTo() {
        return to;
    }
}
