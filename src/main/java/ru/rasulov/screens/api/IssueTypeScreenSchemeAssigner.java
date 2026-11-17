package ru.rasulov.screens.api;

import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeRequest;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse;

/** Switches ONE project to an existing Issue Type Screen Scheme; no scheme, row or screen is changed or copied. */
public interface IssueTypeScreenSchemeAssigner {
    AssignIssueTypeScreenSchemeResponse assign(AssignIssueTypeScreenSchemeRequest request);
}
