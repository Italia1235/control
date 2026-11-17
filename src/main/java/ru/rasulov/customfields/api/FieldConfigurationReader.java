package ru.rasulov.customfields.api;

import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse;
import ru.rasulov.customfields.api.dto.configuration.ProjectFieldConfigurationsResponse;

import java.util.List;

/** {@code get_field_configurations}: read-only view of one project's setup, or a search of configurations/schemes. */
public interface FieldConfigurationReader {
    /** Fields are optional (at most 50): custom field names/ids or system field ids whose flags to report. */
    ProjectFieldConfigurationsResponse read(String project, List<String> fields);

    /**
     * Finds Field Configurations or Field Configuration Schemes regardless of any project. Exactly one of
     * {@code id} (positive id or DEFAULT) and {@code name} (case-insensitive part of the name, paged by
     * {@code offset}/{@code limit}) is set. {@code usageOffset}/{@code usageLimit} page the usage links of each
     * object (with an exact id: all of that object's links).
     */
    FieldConfigurationSearchResponse search(FieldConfigurationSearchResponse.Kind kind, String id, String name,
                                            int offset, int limit, int usageOffset, int usageLimit);
}
