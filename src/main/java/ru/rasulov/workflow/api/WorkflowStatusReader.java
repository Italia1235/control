package ru.rasulov.workflow.api;

import ru.rasulov.workflow.api.dto.StatusListResponse;
import ru.rasulov.workflow.api.dto.StatusLookupResponse;

import java.util.List;

/** Read-only catalog of reusable global Jira statuses. */
public interface WorkflowStatusReader {
    StatusListResponse listStatuses(String query, Integer limit);

    /**
     * One entry per name, in request order, each matched by trimmed case-insensitive exact name.
     *
     * @param names 1-30 names, each 1-60 characters after trimming
     */
    StatusLookupResponse lookupStatuses(List<String> names);
}
