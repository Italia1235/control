package ru.rasulov.workflow.api.dto.assign;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.StatusMapping;

import java.util.List;

/**
 * Input of {@code assign_workflow}: give a workflow to a project, for all its issue types or for the named ones.
 * Exactly one of {@code allIssueTypes} and a non-empty {@code issueTypes}.
 */
public final class AssignWorkflowRequest {

    private final Boolean dryRun;
    private final String project;
    private final String workflow;
    private final boolean allIssueTypes;
    private final List<String> issueTypes;
    private final boolean newScheme;
    private final List<StatusMapping> statusMappings;
    private final String planId;

    public AssignWorkflowRequest(boolean dryRun, String project, String workflow, boolean allIssueTypes,
                                 List<String> issueTypes, boolean newScheme) {
        this(dryRun, project, workflow, allIssueTypes, issueTypes, newScheme, null, null);
    }

    public AssignWorkflowRequest(Boolean dryRun, String project, String workflow, boolean allIssueTypes,
                                 List<String> issueTypes, boolean newScheme, String planId) {
        this(dryRun, project, workflow, allIssueTypes, issueTypes, newScheme, null, planId);
    }

    @JsonCreator
    public AssignWorkflowRequest(@JsonProperty("dryRun") Boolean dryRun,
                                 @JsonProperty("project") String project,
                                 @JsonProperty("workflow") String workflow,
                                 @JsonProperty("allIssueTypes") boolean allIssueTypes,
                                 @JsonProperty("issueTypes") List<String> issueTypes,
                                 @JsonProperty("newScheme") boolean newScheme,
                                 @JsonProperty("statusMappings") List<StatusMapping> statusMappings,
                                 @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.project = project;
        this.workflow = workflow;
        this.allIssueTypes = allIssueTypes;
        this.issueTypes = Immutables.listOf(issueTypes);
        this.newScheme = newScheme;
        this.statusMappings = Immutables.listOf(statusMappings);
        this.planId = planId;
    }

    @JsonProperty("dryRun")
    public Boolean isDryRun() {
        return dryRun;
    }

    /** Key or name of the project. */
    @JsonProperty("project")
    public String getProject() {
        return project;
    }

    /** Name of an existing workflow. */
    @JsonProperty("workflow")
    public String getWorkflow() {
        return workflow;
    }

    @JsonProperty("allIssueTypes")
    public boolean isAllIssueTypes() {
        return allIssueTypes;
    }

    /** Names or ids of issue types of the project. */
    @JsonProperty("issueTypes")
    public List<String> getIssueTypes() {
        return issueTypes;
    }

    /** {@code true}: give the project a scheme of its own instead of changing the current one. Default {@code false}. */
    @JsonProperty("newScheme")
    public boolean isNewScheme() {
        return newScheme;
    }
    /** Explicit conversions for occupied statuses absent from the target workflow. */
    @JsonProperty("statusMappings")
    public List<StatusMapping> getStatusMappings() {
        return statusMappings;
    }


    @JsonProperty("planId")
    public String getPlanId() { return planId; }
}
