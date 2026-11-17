package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Where a transition starts: one step (id or clientKey), or {@code fromAny=true} for a global transition. */
public final class TransitionSource {
    private final Integer stepId;
    private final String clientKey;
    private final Boolean fromAny;

    @JsonCreator
    public TransitionSource(@JsonProperty("stepId") Integer stepId, @JsonProperty("clientKey") String clientKey,
                            @JsonProperty("fromAny") Boolean fromAny) {
        this.stepId = stepId;
        this.clientKey = clientKey;
        this.fromAny = fromAny;
    }

    @JsonProperty("stepId") public Integer getStepId() { return stepId; }
    @JsonProperty("clientKey") public String getClientKey() { return clientKey; }
    @JsonProperty("fromAny") public Boolean getFromAny() { return fromAny; }
}
