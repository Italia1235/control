package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.issue.operation.ScreenableIssueOperation;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.ErrorCode;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RowAction;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RowPlan;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One planned edit of a Screen Scheme: the scheme, the request rows resolved in request order, the stored table before and
 * the final table after ALL rows (keys: {@code DEFAULT}, {@code CREATE}, {@code EDIT}, {@code VIEW}, or the id of an
 * operation Control does not edit), the preview and its fingerprint.
 */
final class ScreenSchemeOperationPlan {
    static final String DEFAULT = "DEFAULT";
    /** The operations a request row can address, and the ones whose effective screen is measured. */
    static final List<String> OPERATIONS = Arrays.asList(DEFAULT, "CREATE", "EDIT", "VIEW");
    static final List<String> SCREENED = Arrays.asList("CREATE", "EDIT", "VIEW");

    /** The key of a row: {@code DEFAULT} for the item without an operation, the operation's name, else its id. */
    static String keyOf(FieldScreenSchemeItem item) {
        ScreenableIssueOperation operation = item.getIssueOperation();
        if (operation == null) return DEFAULT;
        Long id = operation.getId();
        if (IssueOperations.CREATE_ISSUE_OPERATION.getId().equals(id)) return "CREATE";
        if (IssueOperations.EDIT_ISSUE_OPERATION.getId().equals(id)) return "EDIT";
        if (IssueOperations.VIEW_ISSUE_OPERATION.getId().equals(id)) return "VIEW";
        return String.valueOf(id);
    }

    /** The operation of a key; null for {@code DEFAULT} (the item without an operation). */
    static ScreenableIssueOperation operation(String key) {
        switch (key) {
            case "CREATE": return IssueOperations.CREATE_ISSUE_OPERATION;
            case "EDIT": return IssueOperations.EDIT_ISSUE_OPERATION;
            case "VIEW": return IssueOperations.VIEW_ISSUE_OPERATION;
            default: return null;
        }
    }

    /** The stored rows of a scheme, by key; a duplicate operation or a row without a screen is broken data. */
    static Map<String, FieldScreen> table(FieldScreenScheme scheme) {
        Map<String, FieldScreen> table = new TreeMap<String, FieldScreen>();
        for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
            String key = keyOf(item);
            if (item.getFieldScreen() == null) throw new Failure(ErrorCode.JIRA_ERROR, null,
                    "The " + key + " row of Screen Scheme " + scheme.getId() + " has no screen.");
            if (table.put(key, item.getFieldScreen()) != null) throw new Failure(ErrorCode.JIRA_ERROR, null,
                    "Screen Scheme " + scheme.getId() + " has duplicate " + key + " rows.");
        }
        return table;
    }

    final FieldScreenScheme scheme;
    final List<PlannedRow> rows;
    final Map<String, FieldScreen> before;
    final Map<String, FieldScreen> after;
    final SetScreenSchemeOperationsResponse response;
    final String fingerprint;

    ScreenSchemeOperationPlan(FieldScreenScheme scheme, List<PlannedRow> rows, Map<String, FieldScreen> before,
            Map<String, FieldScreen> after, SetScreenSchemeOperationsResponse response, String fingerprint) {
        this.scheme = scheme; this.rows = rows; this.before = before; this.after = after;
        this.response = response; this.fingerprint = fingerprint;
    }

    /**
     * A request row after validation. {@code action} is the effective one (ADD of an existing row is REPLACE); {@code prior}
     * is the explicit row before (null when absent) and {@code target} the screen after (null for REMOVE).
     */
    static final class PlannedRow {
        final int index;
        final String operation;
        final RowAction action;
        final boolean change;
        final FieldScreen prior;
        final FieldScreen target;
        final RowPlan view;

        PlannedRow(int index, String operation, RowAction action, boolean change, FieldScreen prior, FieldScreen target, RowPlan view) {
            this.index = index; this.operation = operation; this.action = action; this.change = change;
            this.prior = prior; this.target = target; this.view = view;
        }
    }

    /** A request that cannot be planned: the code and the zero-based row index it concerns (null for the request as a whole). */
    static final class Failure extends RuntimeException {
        final ErrorCode code;
        final Integer rowIndex;

        Failure(ErrorCode code, Integer rowIndex, String message) {
            super(message, null, false, false);
            this.code = code; this.rowIndex = rowIndex;
        }
    }
}
