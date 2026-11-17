package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Input of {@code create_screen}. Apply calls contain only {@code dryRun=false} and {@code planId}. */
public final class CreateScreenRequest {
    private final Boolean dryRun;
    private final String name;
    private final String description;
    private final List<CreateScreenFieldRequest> fields;
    private final String planId;

    @JsonCreator
    public CreateScreenRequest(@JsonProperty("dryRun") Boolean dryRun,
                               @JsonProperty("name") String name,
                               @JsonProperty("description") String description,
                               @JsonProperty("fields") List<CreateScreenFieldRequest> fields,
                               @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.name = name;
        this.description = description;
        this.fields = Immutables.listOf(fields);
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("fields") public List<CreateScreenFieldRequest> getFields() { return fields; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
