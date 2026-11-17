package ru.rasulov.customfields.api;

import ru.rasulov.customfields.api.dto.configuration.AssignFieldConfigurationSchemeRequest;
import ru.rasulov.customfields.api.dto.configuration.AssignFieldConfigurationSchemeResponse;
import ru.rasulov.customfields.api.dto.configuration.SetFieldConfigurationMappingsRequest;
import ru.rasulov.customfields.api.dto.configuration.SetFieldConfigurationMappingsResponse;

/**
 * {@code assign_field_configuration_scheme} and {@code set_field_configuration_mappings}: change which field
 * configuration existing issue types use. Each previews every affected project and field, then applies only its planId.
 */
public interface FieldConfigurationSchemeAssigner {
    AssignFieldConfigurationSchemeResponse assign(AssignFieldConfigurationSchemeRequest request);

    SetFieldConfigurationMappingsResponse setMappings(SetFieldConfigurationMappingsRequest request);
}
