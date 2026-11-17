package ru.rasulov.workflow.impl;

import ru.rasulov.workflow.api.dto.WorkflowErrorCode;

import java.util.Collections;
import java.util.List;

/**
 * Internal signal that the workflow cannot be created. Converted to a {@code FAILED} result by the orchestrator; never
 * crosses the service boundary. Carries no stack trace: it is control flow, not a bug.
 */
public final class WorkflowFailureException extends RuntimeException {

    private final WorkflowErrorCode errorCode;
    private final List<String> suggestions;

    WorkflowFailureException(WorkflowErrorCode errorCode, String message, List<String> suggestions) {
        super(message, null, false, false);
        this.errorCode = errorCode;
        this.suggestions = suggestions == null ? Collections.<String>emptyList() : suggestions;
    }

    WorkflowFailureException(WorkflowErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public WorkflowErrorCode getErrorCode() {
        return errorCode;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }
}
