package ru.rasulov.common.api.dto.issuetype;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One complete workflow+type unit; no workflow/type substep claims. */
public final class UnitDetail {
    public enum State { COMPLETED, UNKNOWN, NOT_STARTED }
    private final long unitIndex;
    private final String issueId;
    private final State state;

    public UnitDetail(long unitIndex, String issueId, State state) {
        this.unitIndex = unitIndex; this.issueId = issueId; this.state = state;
    }
    @JsonProperty("unitIndex") public long getUnitIndex() { return unitIndex; }
    @JsonProperty("issueId") public String getIssueId() { return issueId; }
    @JsonProperty("state") public State getState() { return state; }
}
