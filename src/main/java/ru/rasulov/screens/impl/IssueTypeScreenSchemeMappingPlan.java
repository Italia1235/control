package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.ErrorCode;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.RowAction;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.RowPlan;

import java.util.List;
import java.util.Map;

/**
 * One planned edit of an Issue Type Screen Scheme: the scheme, the request rows resolved in request order, the stored
 * table before and the final table after ALL rows (keys: issue type id or {@code DEFAULT}), the preview and its fingerprint.
 */
final class IssueTypeScreenSchemeMappingPlan {
    static final String DEFAULT = "DEFAULT";

    final IssueTypeScreenScheme itss;
    final List<PlannedRow> rows;
    final Map<String, FieldScreenScheme> before;
    final Map<String, FieldScreenScheme> after;
    final SetIssueTypeScreenSchemeMappingsResponse response;
    final String fingerprint;

    IssueTypeScreenSchemeMappingPlan(IssueTypeScreenScheme itss, List<PlannedRow> rows, Map<String, FieldScreenScheme> before,
            Map<String, FieldScreenScheme> after, SetIssueTypeScreenSchemeMappingsResponse response, String fingerprint) {
        this.itss = itss; this.rows = rows; this.before = before; this.after = after;
        this.response = response; this.fingerprint = fingerprint;
    }

    /**
     * A request row after validation. {@code action} is the effective one (ADD of an existing row is REPLACE); {@code prior}
     * is the explicit row before (null when absent) and {@code target} the Screen Scheme after (null for REMOVE).
     */
    static final class PlannedRow {
        final int index;
        final String key;
        final String typeId;
        final String typeName;
        final RowAction action;
        final boolean change;
        final FieldScreenScheme prior;
        final FieldScreenScheme target;
        final RowPlan view;

        PlannedRow(int index, String key, String typeId, String typeName, RowAction action, boolean change,
                   FieldScreenScheme prior, FieldScreenScheme target, RowPlan view) {
            this.index = index; this.key = key; this.typeId = typeId; this.typeName = typeName; this.action = action;
            this.change = change; this.prior = prior; this.target = target; this.view = view;
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
