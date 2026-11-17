package ru.rasulov.issuetypes.api;

import ru.rasulov.issuetypes.api.dto.CreateIssueTypesRequest;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse;

/** Administrator-only preview/apply creation or reuse of a batch of global standard and sub-task issue types. */
public interface IssueTypeCreator {
    CreateIssueTypesResponse create(CreateIssueTypesRequest request);
}
