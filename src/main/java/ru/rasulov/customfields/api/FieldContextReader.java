package ru.rasulov.customfields.api;

import ru.rasulov.customfields.api.dto.context.FieldContextsResponse;

/** Reads one field's actual Jira contexts, without changing applicability or configuration. */
public interface FieldContextReader {
    /**
     * @param project optional project key or exact name; selects global and matching project-scoped contexts
     * @param includeOptions also list the options of the one context {@code contextId}; refused without a contextId
     */
    FieldContextsResponse read(String field, String project, Long contextId, boolean includeOptions);
}
