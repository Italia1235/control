package ru.rasulov.workflow.api.dto.assign;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.StatusUsage;

import java.util.List;

/** What the assignment does (or did) with one issue type. */
public final class IssueTypeAssignment {

    private final IssueTypeRef issueType;
    private final String from;
    private final String to;
    private final AssignVerdict verdict;
    private final int issues;
    private final List<StatusUsage> statuses;

    public IssueTypeAssignment(IssueTypeRef issueType, String from, String to, AssignVerdict verdict, int issues,
                               List<StatusUsage> statuses) {
        this.issueType = issueType;
        this.from = from;
        this.to = to;
        this.verdict = verdict;
        this.issues = issues;
        this.statuses = Immutables.listOf(statuses);
    }

    @JsonProperty("issueType")
    public IssueTypeRef getIssueType() {
        return issueType;
    }

    /** The workflow the issue type has now. */
    @JsonProperty("from")
    public String getFrom() {
        return from;
    }

    /** The workflow it gets. */
    @JsonProperty("to")
    public String getTo() {
        return to;
    }

    @JsonProperty("verdict")
    public AssignVerdict getVerdict() {
        return verdict;
    }

    @JsonProperty("issues")
    public int getIssues() {
        return issues;
    }

    /** Statuses that have issues, each with whether the target workflow has it. */
    @JsonProperty("statuses")
    public List<StatusUsage> getStatuses() {
        return statuses;
    }
}
