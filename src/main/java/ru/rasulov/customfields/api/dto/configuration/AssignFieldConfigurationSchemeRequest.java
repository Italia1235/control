package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Input of {@code assign_field_configuration_scheme}; apply calls contain only {@code dryRun=false} and {@code planId}. */
public final class AssignFieldConfigurationSchemeRequest {
    private final Boolean dryRun;
    private final String project;
    private final String scheme;
    private final String planId;

    @JsonCreator
    public AssignFieldConfigurationSchemeRequest(@JsonProperty("dryRun") Boolean dryRun,
                                                 @JsonProperty("project") String project,
                                                 @JsonProperty("scheme") String scheme,
                                                 @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.project = project;
        this.scheme = scheme;
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("project") public String getProject() { return project; }
    @JsonProperty("scheme") public String getScheme() { return scheme; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
