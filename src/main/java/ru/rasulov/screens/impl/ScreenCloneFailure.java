package ru.rasulov.screens.impl;

import ru.rasulov.screens.api.dto.CloneProjectScreensResponse.ErrorCode;

import java.util.Collections;
import java.util.List;

final class ScreenCloneFailure extends RuntimeException {
    private final ErrorCode code;
    private final List<String> suggestions;

    ScreenCloneFailure(ErrorCode code, String message) { this(code, message, null); }
    ScreenCloneFailure(ErrorCode code, String message, List<String> suggestions) {
        super(message); this.code = code;
        this.suggestions = suggestions == null ? Collections.<String>emptyList() : suggestions;
    }
    ErrorCode getCode() { return code; }
    List<String> getSuggestions() { return suggestions; }
}
