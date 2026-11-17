package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Input of {@code create_field_configuration_scheme}; apply calls contain only {@code dryRun=false} and {@code planId}. */
public final class CreateFieldConfigurationSchemeRequest {
    private final Boolean dryRun;
    private final String name;
    private final String description;
    private final List<SchemeMappingInput> mappings;
    private final String planId;

    @JsonCreator
    public CreateFieldConfigurationSchemeRequest(@JsonProperty("dryRun") Boolean dryRun,
                                                 @JsonProperty("name") String name,
                                                 @JsonProperty("description") String description,
                                                 @JsonProperty("mappings") List<SchemeMappingInput> mappings,
                                                 @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.name = name;
        this.description = description;
        this.mappings = Immutables.listOf(mappings);
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("mappings") public List<SchemeMappingInput> getMappings() { return mappings; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
