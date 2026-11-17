package ru.rasulov.common.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Answer of {@code list_issue_types}: at most {@code limit} issue types out of {@code total} that matched. */
public final class IssueTypeListResponse {

    private final int total;
    private final List<IssueTypeResponse> issueTypes;

    public IssueTypeListResponse(int total, List<IssueTypeResponse> issueTypes) {
        this.total = total;
        this.issueTypes = Immutables.listOf(issueTypes);
    }

    /** Number of matches; more than {@code issueTypes.size()} means the caller should narrow the query. */
    @JsonProperty("total")
    public int getTotal() {
        return total;
    }

    @JsonProperty("issueTypes")
    public List<IssueTypeResponse> getIssueTypes() {
        return issueTypes;
    }
}
