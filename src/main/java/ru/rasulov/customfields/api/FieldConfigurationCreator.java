package ru.rasulov.customfields.api;

import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationRequest;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationResponse;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationSchemeRequest;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationSchemeResponse;

/**
 * {@code create_field_configuration} and {@code create_field_configuration_scheme}: create new objects only, never
 * assign them. Each previews with full details, then applies only the preview's planId.
 */
public interface FieldConfigurationCreator {
    CreateFieldConfigurationResponse createConfiguration(CreateFieldConfigurationRequest request);

    CreateFieldConfigurationSchemeResponse createScheme(CreateFieldConfigurationSchemeRequest request);
}
