package ru.rasulov.screens.api;

import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsRequest;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse;

/** Edits the rows of an existing Issue Type Screen Scheme in place, for every project that uses it. */
public interface IssueTypeScreenSchemeMappingEditor {
    SetIssueTypeScreenSchemeMappingsResponse edit(SetIssueTypeScreenSchemeMappingsRequest request);
}
