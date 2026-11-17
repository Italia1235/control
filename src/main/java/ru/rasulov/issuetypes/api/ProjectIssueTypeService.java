package ru.rasulov.issuetypes.api;

import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse;

/** Replaces the issue types available in one project and converts issues of removed types. */
public interface ProjectIssueTypeService {
    SetProjectIssueTypesResponse set(SetProjectIssueTypesRequest request);
}
