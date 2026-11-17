package ru.rasulov.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Answer of {@code list_statuses} when called with {@code names}: one entry per requested name, in request order. */
public final class StatusLookupResponse {

    private final List<StatusLookupEntry> lookup;

    public StatusLookupResponse(List<StatusLookupEntry> lookup) {
        this.lookup = Immutables.listOf(lookup);
    }

    @JsonProperty("lookup")
    public List<StatusLookupEntry> getLookup() {
        return lookup;
    }
}
