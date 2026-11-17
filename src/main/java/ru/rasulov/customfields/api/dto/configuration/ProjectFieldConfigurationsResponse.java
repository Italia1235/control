package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ConfigurationRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.FieldRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.MappingView;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.SchemeRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.Usage;

import java.util.List;

/**
 * Result of {@code get_field_configurations}: the project's field configuration scheme, the effective field
 * configuration of every issue type of the project, who else uses them, and optionally the state of named fields.
 */
public final class ProjectFieldConfigurationsResponse {
    public enum Status { OK, FAILED }

    private final Status status;
    private final ProjectRef project;
    private final SchemeRef scheme;
    private final Usage schemeOtherProjects;
    private final ConfigurationRef defaultMapping;
    private final List<MappingView> mappings;
    private final List<MappingView> otherEntities;
    private final List<ConfigurationUse> fieldConfigurations;
    private final List<FieldState> fields;
    private final ErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;
    private final List<FieldRef> candidates;

    private ProjectFieldConfigurationsResponse(Status status, ProjectRef project, SchemeRef scheme,
                                               Usage schemeOtherProjects, ConfigurationRef defaultMapping,
                                               List<MappingView> mappings, List<MappingView> otherEntities,
                                               List<ConfigurationUse> fieldConfigurations, List<FieldState> fields,
                                               ErrorCode errorCode, String message, List<String> suggestions,
                                               List<FieldRef> candidates) {
        this.status = status;
        this.project = project;
        this.scheme = scheme;
        this.schemeOtherProjects = schemeOtherProjects;
        this.defaultMapping = defaultMapping;
        this.mappings = Immutables.listOf(mappings);
        this.otherEntities = Immutables.listOf(otherEntities);
        this.fieldConfigurations = Immutables.listOf(fieldConfigurations);
        this.fields = Immutables.listOf(fields);
        this.errorCode = errorCode;
        this.message = message;
        this.suggestions = Immutables.listOf(suggestions);
        this.candidates = Immutables.listOf(candidates);
    }

    public static ProjectFieldConfigurationsResponse ok(ProjectRef project, SchemeRef scheme, Usage schemeOtherProjects,
                                                        ConfigurationRef defaultMapping, List<MappingView> mappings,
                                                        List<MappingView> otherEntities,
                                                        List<ConfigurationUse> fieldConfigurations,
                                                        List<FieldState> fields) {
        return new ProjectFieldConfigurationsResponse(Status.OK, project, scheme, schemeOtherProjects, defaultMapping,
                mappings, otherEntities, fieldConfigurations, fields, null, null, null, null);
    }

    public static ProjectFieldConfigurationsResponse failed(ProjectRef project, ErrorCode code, String message,
                                                            List<String> suggestions, List<FieldRef> candidates) {
        return new ProjectFieldConfigurationsResponse(Status.FAILED, project, null, null, null, null, null, null, null,
                code, message, suggestions, candidates);
    }

    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("project") public ProjectRef getProject() { return project; }
    @JsonProperty("scheme") public SchemeRef getScheme() { return scheme; }
    @JsonProperty("schemeOtherProjects") public Usage getSchemeOtherProjects() { return schemeOtherProjects; }
    @JsonProperty("defaultMapping") public ConfigurationRef getDefaultMapping() { return defaultMapping; }
    @JsonProperty("mappings") public List<MappingView> getMappings() { return mappings; }
    @JsonProperty("otherEntities") public List<MappingView> getOtherEntities() { return otherEntities; }
    @JsonProperty("fieldConfigurations") public List<ConfigurationUse> getFieldConfigurations() { return fieldConfigurations; }
    @JsonProperty("fields") public List<FieldState> getFields() { return fields; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("candidates") public List<FieldRef> getCandidates() { return candidates; }

    /** A field configuration the project uses, with other projects and schemes that use it too. */
    public static final class ConfigurationUse {
        private final ConfigurationRef fieldConfiguration;
        private final Usage otherProjects;
        private final int schemesTotal;

        public ConfigurationUse(ConfigurationRef fieldConfiguration, Usage otherProjects, int schemesTotal) {
            this.fieldConfiguration = fieldConfiguration; this.otherProjects = otherProjects;
            this.schemesTotal = schemesTotal;
        }
        @JsonProperty("fieldConfiguration") public ConfigurationRef getFieldConfiguration() { return fieldConfiguration; }
        @JsonProperty("otherProjects") public Usage getOtherProjects() { return otherProjects; }
        @JsonProperty("schemesTotal") public int getSchemesTotal() { return schemesTotal; }
    }

    /** One requested field in every field configuration the project uses. */
    public static final class FieldState {
        private final FieldRef field;
        private final boolean locked;
        private final List<ConfigurationState> configurations;

        public FieldState(FieldRef field, boolean locked, List<ConfigurationState> configurations) {
            this.field = field; this.locked = locked; this.configurations = Immutables.listOf(configurations);
        }
        @JsonProperty("field") public FieldRef getField() { return field; }
        @JsonProperty("locked") public boolean isLocked() { return locked; }
        @JsonProperty("configurations") public List<ConfigurationState> getConfigurations() { return configurations; }
    }

    /** {@code present=false}: the configuration has no item for the field; required and hidden are then false. */
    public static final class ConfigurationState {
        private final ConfigurationRef fieldConfiguration;
        private final boolean present;
        private final boolean required;
        private final boolean hidden;

        public ConfigurationState(ConfigurationRef fieldConfiguration, boolean present, boolean required,
                                  boolean hidden) {
            this.fieldConfiguration = fieldConfiguration; this.present = present; this.required = required;
            this.hidden = hidden;
        }
        @JsonProperty("fieldConfiguration") public ConfigurationRef getFieldConfiguration() { return fieldConfiguration; }
        @JsonProperty("present") public boolean isPresent() { return present; }
        @JsonProperty("required") public boolean isRequired() { return required; }
        @JsonProperty("hidden") public boolean isHidden() { return hidden; }
    }
}
