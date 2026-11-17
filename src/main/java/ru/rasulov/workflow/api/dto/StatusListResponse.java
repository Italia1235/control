package ru.rasulov.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Answer of {@code list_statuses}: at most {@code limit} statuses out of {@code total} that matched. */
public final class StatusListResponse {

    private final int total;
    private final List<StatusResponse> statuses;

    public StatusListResponse(int total, List<StatusResponse> statuses) {
        this.total = total;
        this.statuses = Immutables.listOf(statuses);
    }

    /** Number of matches; more than {@code statuses.size()} means the caller should narrow the query. */
    @JsonProperty("total")
    public int getTotal() {
        return total;
    }

    @JsonProperty("statuses")
    public List<StatusResponse> getStatuses() {
        return statuses;
    }
}
