package ru.rasulov.resolutions.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class CreateResolutionRequest {
    private final Boolean dryRun;
    private final String name;
    private final String description;
    private final String planId;

    @JsonCreator
    public CreateResolutionRequest(@JsonProperty("dryRun") Boolean dryRun,
                                   @JsonProperty("name") String name,
                                   @JsonProperty("description") String description,
                                   @JsonProperty("planId") String planId) {
        this.dryRun = dryRun; this.name = name; this.description = description; this.planId = planId;
    }
    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
