package ru.rasulov.customfields.api;

import ru.rasulov.common.api.CustomFieldCreationService;
import ru.rasulov.customfields.api.dto.CustomFieldTypeResponse;
import ru.rasulov.common.api.exception.AccessException;

import java.util.List;

/**
 * Transport-independent service that creates Jira custom fields in the contexts (projects and issue types) the caller
 * names. Turning the user's words into projects and issue types is {@link ru.rasulov.common.api.LookupService}.
 * <p>
 * All operations require the global {@code ADMINISTER} permission of the current user. Field-count limits are in
 * {@link ru.rasulov.common.api.CustomFieldCreationLimits}; feature-specific limits are in {@link Limits}.
 */
public interface CustomFieldCreator extends CustomFieldCreationService {

    /**
     * Lists custom field types installed in Jira.
     *
     * @throws AccessException the current user is anonymous or not a Jira administrator
     */
    List<CustomFieldTypeResponse> listAvailableTypes();
}
