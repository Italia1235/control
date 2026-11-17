package ru.rasulov.workflow.api.dto.clone;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Input of {@code clone_project_workflows}: give one project its own copy of its workflow scheme and, by
 * {@code scope}, of its workflows. {@code workflows} only with {@code SELECTED}. Names of the copies are made up
 * after the project ("SPMT: Flow" for "BUS: Flow") unless {@code schemeName} and {@code workflowNames} say otherwise.
 */
public final class CloneWorkflowsRequest {

    private final Boolean dryRun;
    private final String project;
    private final CloneScope scope;
    private final List<String> workflows;
    private final String schemeName;
    private final Map<String, String> workflowNames;
    private final String planId;

    public CloneWorkflowsRequest(boolean dryRun, String project, CloneScope scope, List<String> workflows,
                                 String schemeName, Map<String, String> workflowNames) {
        this(dryRun, project, scope, workflows, schemeName, workflowNames, null);
    }

    @JsonCreator
    public CloneWorkflowsRequest(@JsonProperty("dryRun") Boolean dryRun,
                                 @JsonProperty("project") String project,
                                 @JsonProperty("scope") CloneScope scope,
                                 @JsonProperty("workflows") List<String> workflows,
                                 @JsonProperty("schemeName") String schemeName,
                                 @JsonProperty("workflowNames") Map<String, String> workflowNames,
                                 @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.project = project;
        this.scope = scope;
        this.workflows = Immutables.listOf(workflows);
        this.schemeName = schemeName;
        this.workflowNames = workflowNames == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(workflowNames));
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

    @JsonProperty("scope")
    public CloneScope getScope() {
        return scope;
    }

    /** Names of workflows of the project's scheme to copy; only for {@code SELECTED}. */
    @JsonProperty("workflows")
    public List<String> getWorkflows() {
        return workflows;
    }

    /** Name of the new scheme; {@code null}: the old name under this project's key, " v2" and up only if taken. */
    @JsonProperty("schemeName")
    public String getSchemeName() {
        return schemeName;
    }

    /** Old workflow name to the name of its copy; a workflow that is not in the map is named after the project. */
    @JsonProperty("workflowNames")
    public Map<String, String> getWorkflowNames() {
        return workflowNames;
    }

    @JsonProperty("planId")
    public String getPlanId() { return planId; }
}
