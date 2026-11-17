package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Input of {@code clone_project_field_configurations}; apply calls contain only dryRun=false and planId. */
public final class CloneProjectFieldConfigurationsRequest {
    private final Boolean dryRun;
    private final String project;
    private final String planId;

    public CloneProjectFieldConfigurationsRequest(boolean dryRun, String project) {
        this(dryRun, project, null);
    }

    @JsonCreator
    public CloneProjectFieldConfigurationsRequest(@JsonProperty("dryRun") Boolean dryRun,
                                                   @JsonProperty("project") String project,
                                                   @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.project = project;
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("project") public String getProject() { return project; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
