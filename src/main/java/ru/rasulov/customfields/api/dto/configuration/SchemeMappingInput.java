package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One requested scheme row: an issue type name/id or {@code DEFAULT}, and a field configuration id or {@code DEFAULT}
 * (Jira's Default Field Configuration); {@code set_field_configuration_mappings} also takes {@code INHERIT}.
 */
public final class SchemeMappingInput {
    private final String issueType;
    private final String fieldConfiguration;

    @JsonCreator
    public SchemeMappingInput(@JsonProperty("issueType") String issueType,
                              @JsonProperty("fieldConfiguration") String fieldConfiguration) {
        this.issueType = issueType;
        this.fieldConfiguration = fieldConfiguration;
    }

    @JsonProperty("issueType") public String getIssueType() { return issueType; }
    @JsonProperty("fieldConfiguration") public String getFieldConfiguration() { return fieldConfiguration; }
}
