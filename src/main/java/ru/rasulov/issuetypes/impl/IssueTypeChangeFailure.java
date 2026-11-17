package ru.rasulov.issuetypes.impl;

import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.ErrorCode;

import java.util.Collections;
import java.util.List;

final class IssueTypeChangeFailure extends RuntimeException {
    private final ErrorCode code;
    private final List<String> suggestions;

    IssueTypeChangeFailure(ErrorCode code, String message) {
        this(code, message, null);
    }

    IssueTypeChangeFailure(ErrorCode code, String message, List<String> suggestions) {
        super(message, null, false, false);
        this.code = code;
        this.suggestions = suggestions == null ? Collections.<String>emptyList() : suggestions;
    }

    ErrorCode getCode() { return code; }
    List<String> getSuggestions() { return suggestions; }
}
