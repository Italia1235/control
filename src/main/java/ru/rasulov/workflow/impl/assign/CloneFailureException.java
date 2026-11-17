package ru.rasulov.workflow.impl.assign;

import ru.rasulov.workflow.api.dto.clone.CloneErrorCode;

import java.util.Collections;
import java.util.List;

/**
 * Internal signal that the project cannot be cloned. Converted to a {@code FAILED} result by the orchestrator; never
 * crosses the service boundary. Carries no stack trace: it is control flow, not a bug.
 */
final class CloneFailureException extends RuntimeException {

    private final CloneErrorCode errorCode;
    private final List<String> suggestions;

    CloneFailureException(CloneErrorCode errorCode, String message, List<String> suggestions) {
        super(message, null, false, false);
        this.errorCode = errorCode;
        this.suggestions = suggestions == null ? Collections.<String>emptyList() : suggestions;
    }

    CloneFailureException(CloneErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    CloneErrorCode getErrorCode() {
        return errorCode;
    }

    List<String> getSuggestions() {
        return suggestions;
    }
}
