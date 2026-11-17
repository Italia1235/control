package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.issue.operation.ScreenableIssueOperation;
import com.atlassian.jira.project.Project;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.ErrorCode;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.SchemeMode;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Internal, short-lived plan of the whole batch against ONE source state. Only the request and fingerprint enter the
 * distributed plan store; apply recomputes this plan and compares fingerprints.
 */
final class OperationScreenPlan {
    static final List<String> OPERATIONS = Arrays.asList("CREATE", "EDIT", "VIEW");

    final Project project;
    final IssueTypeScreenScheme source;
    /** How the top scheme changes: decided from the real associations at plan time and re-decided at apply. */
    final SchemeMode mode;
    /** The source's rows before any write (key: issue type id or DEFAULT), captured because in-place writes mutate entities. */
    final Map<String, Long> sourceRows;
    /** New child Screen Schemes, in creation order; each is shared by every row that needs the same result. */
    final List<Copy> copies;
    /** Every row of the new Issue Type Screen Scheme: the source rows (re-pointed where changed) and new explicit rows. */
    final List<Row> rows;
    final SetProjectOperationScreensResponse response;
    final String fingerprint;

    OperationScreenPlan(Project project, IssueTypeScreenScheme source, SchemeMode mode, Map<String, Long> sourceRows,
            List<Copy> copies, List<Row> rows, SetProjectOperationScreensResponse response, String fingerprint) {
        this.project = project; this.source = source; this.mode = mode; this.sourceRows = sourceRows;
        this.copies = copies; this.rows = rows; this.response = response; this.fingerprint = fingerprint;
    }

    static ScreenableIssueOperation operation(String name) {
        switch (name) {
            case "CREATE": return IssueOperations.CREATE_ISSUE_OPERATION;
            case "EDIT": return IssueOperations.EDIT_ISSUE_OPERATION;
            case "VIEW": return IssueOperations.VIEW_ISSUE_OPERATION;
            default: throw new Failure(ErrorCode.VALIDATION_ERROR, "screens supports only CREATE, EDIT and VIEW.");
        }
    }

    static String operationKey(FieldScreenSchemeItem item) {
        return item.getIssueOperation() == null ? "DEFAULT" : item.getIssueOperation().getId().toString();
    }

    static FieldScreen effective(FieldScreenScheme scheme, String name) {
        Long operationId = operation(name).getId();
        FieldScreen fallback = null;
        for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
            if (item.getIssueOperation() == null) fallback = item.getFieldScreen();
            else if (operationId.equals(item.getIssueOperation().getId())) return item.getFieldScreen();
        }
        return fallback;
    }

    /** A new child Screen Scheme: {@code source} with only the operations of {@code patch} replaced. */
    static final class Copy {
        final FieldScreenScheme source;
        final Map<String, FieldScreen> patch;
        String name;
        /** True when the user dictated {@link #name}; false for a generated one. */
        boolean explicitName;
        final List<String> appliesTo;
        Copy(FieldScreenScheme source, Map<String, FieldScreen> patch, String name, boolean explicitName, List<String> appliesTo) {
            this.source = source; this.patch = patch; this.name = name; this.explicitName = explicitName; this.appliesTo = appliesTo;
        }
        FieldScreen effective(String operation) {
            FieldScreen replaced = patch.get(operation);
            return replaced != null ? replaced : OperationScreenPlan.effective(source, operation);
        }
    }

    /**
     * One row of the top scheme; {@code typeId == null} is the DEFAULT row. Exactly one of existing/copy is set.
     * {@code inSource}: the source scheme already has this explicit row (otherwise the row would be new).
     */
    static final class Row {
        final String typeId;
        final FieldScreenScheme existing;
        final Copy copy;
        final boolean inSource;
        Row(String typeId, FieldScreenScheme existing, Copy copy, boolean inSource) {
            this.typeId = typeId; this.existing = existing; this.copy = copy; this.inSource = inSource;
        }
        String key() { return typeId == null ? "DEFAULT" : typeId; }
        FieldScreen effective(String operation) {
            return copy != null ? copy.effective(operation) : OperationScreenPlan.effective(existing, operation);
        }
    }

    static final class Failure extends RuntimeException {
        final ErrorCode code;
        Failure(ErrorCode code, String message) { super(message); this.code = code; }
        Failure(ErrorCode code, String message, Throwable cause) { super(message, cause); this.code = code; }
    }
}
