package ru.rasulov.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** What one requested name found: no status, exactly one, or several that differ only in case or surrounding blanks. */
public final class StatusLookupEntry {

    /** {@code AMBIGUOUS}: more than one status has this name; a workflow must then refer to one of them by id. */
    public enum Result { FOUND, NOT_FOUND, AMBIGUOUS }

    private final String name;
    private final Result result;
    private final List<StatusResponse> statuses;

    public StatusLookupEntry(String name, Result result, List<StatusResponse> statuses) {
        this.name = name;
        this.result = result;
        this.statuses = Immutables.listOf(statuses);
    }

    /** The name as the caller passed it. */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("result")
    public Result getResult() {
        return result;
    }

    /** Matching statuses ordered by id; empty for {@code NOT_FOUND}. */
    @JsonProperty("statuses")
    public List<StatusResponse> getStatuses() {
        return statuses;
    }
}
