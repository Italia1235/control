package ru.rasulov.customfields.impl.configuration;

import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse.ErrorCode;

import java.util.Collections;
import java.util.List;

final class FieldConfigurationCloneFailure extends RuntimeException {
    private final ErrorCode code;
    private final List<String> suggestions;

    FieldConfigurationCloneFailure(ErrorCode code, String message) {
        this(code, message, null);
    }

    FieldConfigurationCloneFailure(ErrorCode code, String message, List<String> suggestions) {
        super(message);
        this.code = code;
        this.suggestions = suggestions == null ? Collections.<String>emptyList() : suggestions;
    }

    ErrorCode getCode() { return code; }
    List<String> getSuggestions() { return suggestions; }
}
