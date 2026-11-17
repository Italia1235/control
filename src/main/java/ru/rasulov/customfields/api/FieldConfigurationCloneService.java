package ru.rasulov.customfields.api;

import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsRequest;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse;

/** Gives one project an exact, private copy of its effective field configuration setup. */
public interface FieldConfigurationCloneService {
    CloneProjectFieldConfigurationsResponse cloneProjectFieldConfigurations(
            CloneProjectFieldConfigurationsRequest request);
}
