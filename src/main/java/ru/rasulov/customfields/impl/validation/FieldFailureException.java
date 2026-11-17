package ru.rasulov.customfields.impl.validation;

import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;

import java.util.Collections;
import java.util.List;

/**
 * Internal signal that a single field definition cannot be created. Converted to a {@code FAILED} result by the
 * orchestrator; never crosses the service boundary. Carries no stack trace: it is control flow, not a bug.
 */
public final class FieldFailureException extends RuntimeException {

    private final CustomFieldErrorCode errorCode;
    private final List<String> suggestions;

    public FieldFailureException(CustomFieldErrorCode errorCode, String message, List<String> suggestions) {
        super(message, null, false, false);
        this.errorCode = errorCode;
        this.suggestions = suggestions == null ? Collections.<String>emptyList() : suggestions;
    }

    public FieldFailureException(CustomFieldErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public CustomFieldErrorCode getErrorCode() {
        return errorCode;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }
}
