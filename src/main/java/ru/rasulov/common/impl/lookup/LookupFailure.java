package ru.rasulov.common.impl.lookup;

import java.util.Collections;
import java.util.List;

/**
 * A project or an issue type named by a caller cannot be used: blank, unknown, archived or ambiguous. The feature that
 * asked decides how to report it (a field turns it into a {@code FAILED} result), so this carries only the kind, the
 * message for the caller and suggestions. Internal: never crosses the service boundary and has no stack trace, it is
 * control flow, not a bug.
 */
public final class LookupFailure extends RuntimeException {

    /** What is wrong; features map it to their own error codes. */
    public enum Kind {
        /** Blank, archived project, or a name several issue types share: fix the request. */
        INVALID,
        /** No project has this key or name. */
        UNKNOWN_PROJECT,
        /** No issue type has this name or id. */
        UNKNOWN_ISSUE_TYPE
    }

    private final Kind kind;
    private final List<String> suggestions;

    LookupFailure(Kind kind, String message, List<String> suggestions) {
        super(message, null, false, false);
        this.kind = kind;
        this.suggestions = suggestions == null ? Collections.<String>emptyList() : suggestions;
    }

    LookupFailure(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind getKind() {
        return kind;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }
}
