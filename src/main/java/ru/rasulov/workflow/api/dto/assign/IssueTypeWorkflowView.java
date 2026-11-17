package ru.rasulov.workflow.api.dto.assign;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.StatusUsage;

import java.util.List;

/** One issue type of a project: the workflow it has now and the issues by status. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public final class IssueTypeWorkflowView {

    private final IssueTypeRef issueType;
    private final String workflow;
    private final Integer issues;
    private final List<StatusUsage> statuses;

    public IssueTypeWorkflowView(IssueTypeRef issueType, String workflow, Integer issues, List<StatusUsage> statuses) {
        this.issueType = issueType;
        this.workflow = workflow;
        this.issues = issues;
        this.statuses = statuses == null ? null : Immutables.listOf(statuses);
    }

    @JsonProperty("issueType")
    public IssueTypeRef getIssueType() {
        return issueType;
    }

    /** Name of the workflow the project's scheme gives this issue type. */
    @JsonProperty("workflow")
    public String getWorkflow() {
        return workflow;
    }

    @JsonProperty("issues")
    public Integer getIssues() {
        return issues;
    }

    /** Occupied statuses, or null when counts were not requested or failed. */
    @JsonProperty("statuses")
    public List<StatusUsage> getStatuses() {
        return statuses;
    }
}
