package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Input of {@code create_field_configuration}; apply calls contain only {@code dryRun=false} and {@code planId}. */
public final class CreateFieldConfigurationRequest {
    private final Boolean dryRun;
    private final String name;
    private final String description;
    private final String copyFrom;
    private final String planId;

    @JsonCreator
    public CreateFieldConfigurationRequest(@JsonProperty("dryRun") Boolean dryRun,
                                           @JsonProperty("name") String name,
                                           @JsonProperty("description") String description,
                                           @JsonProperty("copyFrom") String copyFrom,
                                           @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.name = name;
        this.description = description;
        this.copyFrom = copyFrom;
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("copyFrom") public String getCopyFrom() { return copyFrom; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
