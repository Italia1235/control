package ru.rasulov.workflow.impl.edit;

import ru.rasulov.workflow.api.dto.edit.EditErrorCode;

import java.util.Collections;
import java.util.List;

/** Internal signal that an edit is refused before any write; converted to a FAILED result by the editor. */
public final class EditFailure extends RuntimeException {

    private final EditErrorCode code;
    private final List<String> suggestions;

    public EditFailure(EditErrorCode code, String message) {
        this(code, message, null);
    }

    public EditFailure(EditErrorCode code, String message, List<String> suggestions) {
        super(message, null, false, false);
        this.code = code;
        this.suggestions = suggestions == null ? Collections.<String>emptyList() : suggestions;
    }

    public EditErrorCode getCode() { return code; }
    public List<String> getSuggestions() { return suggestions; }
}
