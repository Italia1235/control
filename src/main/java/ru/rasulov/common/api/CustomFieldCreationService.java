package ru.rasulov.common.api;

import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;

/**
 * Shared, transport-independent contract for previewing and applying custom field creation.
 * Implementations own validation, existing-field matching, contexts, options and rollback;
 * callers in other features use this contract rather than depending on the implementing feature.
 */
public interface CustomFieldCreationService {

    /**
     * Previews definitions with {@code dryRun=true}; applies the issued plan with
     * {@code dryRun=false} and {@code planId} only. Results follow request order, and a
     * failure of one field does not affect the others. Apply calls use a cluster-wide lock.
     * Every definition must explicitly name its contexts; none are assumed.
     *
     * @throws InvalidRequestException the request mode or call-wide limits are invalid
     * @throws AccessException the current user is anonymous or not a Jira administrator
     * @throws BusyException another apply call held the lock for too long; nothing was changed
     */
    CreateCustomFieldsResponse createAll(CreateCustomFieldsRequest request);
}
