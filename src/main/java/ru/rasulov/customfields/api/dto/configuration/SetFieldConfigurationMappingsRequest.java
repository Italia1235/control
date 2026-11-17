package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Input of {@code set_field_configuration_mappings}; apply calls contain only {@code dryRun=false} and {@code planId}. */
public final class SetFieldConfigurationMappingsRequest {
    private final Boolean dryRun;
    private final String scheme;
    private final List<SchemeMappingInput> mappings;
    private final String planId;

    @JsonCreator
    public SetFieldConfigurationMappingsRequest(@JsonProperty("dryRun") Boolean dryRun,
                                                @JsonProperty("scheme") String scheme,
                                                @JsonProperty("mappings") List<SchemeMappingInput> mappings,
                                                @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.scheme = scheme;
        this.mappings = Immutables.listOf(mappings);
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("scheme") public String getScheme() { return scheme; }
    @JsonProperty("mappings") public List<SchemeMappingInput> getMappings() { return mappings; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
