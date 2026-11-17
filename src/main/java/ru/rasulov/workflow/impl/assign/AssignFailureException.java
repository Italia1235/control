package ru.rasulov.workflow.impl.assign;

import ru.rasulov.workflow.api.dto.assign.AssignErrorCode;
import ru.rasulov.workflow.api.dto.assign.IssueTypeAssignment;

import java.util.Collections;
import java.util.List;

/**
 * Internal signal that the workflow cannot be assigned. Converted to a {@code FAILED} result by the orchestrator; never
 * crosses the service boundary. Carries no stack trace: it is control flow, not a bug.
 */
final class AssignFailureException extends RuntimeException {

    private final AssignErrorCode errorCode;
    private final List<String> suggestions;
    private final List<IssueTypeAssignment> issueTypes;

    AssignFailureException(AssignErrorCode errorCode, String message, List<String> suggestions,
                           List<IssueTypeAssignment> issueTypes) {
        super(message, null, false, false);
        this.errorCode = errorCode;
        this.suggestions = suggestions == null ? Collections.<String>emptyList() : suggestions;
        this.issueTypes = issueTypes == null ? Collections.<IssueTypeAssignment>emptyList() : issueTypes;
    }

    AssignFailureException(AssignErrorCode errorCode, String message, List<String> suggestions) {
        this(errorCode, message, suggestions, null);
    }

    AssignFailureException(AssignErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    AssignErrorCode getErrorCode() {
        return errorCode;
    }

    List<String> getSuggestions() {
        return suggestions;
    }

    /** What was planned when the failure came after planning; empty before. */
    List<IssueTypeAssignment> getIssueTypes() {
        return issueTypes;
    }
}
