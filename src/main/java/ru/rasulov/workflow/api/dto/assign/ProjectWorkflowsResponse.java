package ru.rasulov.workflow.api.dto.assign;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.List;

/** Current configuration and explicitly requested issue statistics, never native-entry diagnosis. */
public final class ProjectWorkflowsResponse {

    public enum IssueCountsStatus { NOT_REQUESTED, COMPLETE, ERROR }

    private final ProjectRef project;
    private final SchemeView scheme;
    private final List<IssueTypeWorkflowView> issueTypes;
    private final String defaultWorkflow;
    private final IssueCountsStatus issueCountsStatus;
    private final String issueCountsError;

    public ProjectWorkflowsResponse(ProjectRef project, SchemeView scheme, List<IssueTypeWorkflowView> issueTypes,
                                    String defaultWorkflow, IssueCountsStatus issueCountsStatus, String issueCountsError) {
        this.project = project;
        this.scheme = scheme;
        this.issueTypes = Immutables.listOf(issueTypes);
        this.defaultWorkflow = defaultWorkflow;
        this.issueCountsStatus = issueCountsStatus;
        this.issueCountsError = issueCountsError;
    }

    @JsonProperty("project")
    public ProjectRef getProject() {
        return project;
    }

    @JsonProperty("scheme")
    public SchemeView getScheme() {
        return scheme;
    }

    @JsonProperty("defaultWorkflow")
    public String getDefaultWorkflow() { return defaultWorkflow; }

    @JsonProperty("issueCountsStatus")
    public IssueCountsStatus getIssueCountsStatus() { return issueCountsStatus; }

    @JsonProperty("issueCountsError")
    public String getIssueCountsError() { return issueCountsError; }

    /** All current project types; additional types found in issues only when counts are COMPLETE. */
    @JsonProperty("issueTypes")
    public List<IssueTypeWorkflowView> getIssueTypes() {
        return issueTypes;
    }
}
