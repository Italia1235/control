package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenFactory;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RowAction;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RowPlan;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RowResult;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.ScreenRef;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.Status;
import ru.rasulov.screens.impl.ScreenSchemeOperationPlan.PlannedRow;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

import static ru.rasulov.screens.impl.ScreenSchemeOperationPlan.DEFAULT;

/**
 * Writes the changing rows of one Screen Scheme: the explicit ADD/REPLACE rows first (request order), then the DEFAULT row,
 * then the REMOVE rows (request order), so that at every moment each operation points either to its old or to its new
 * screen. One Jira call per row, stopping at the first failure; rows that need no write are never written. Never rolls back:
 * the outcome is what a fresh read of the scheme shows, row by row and as a whole.
 */
@Named
public class ScreenSchemeOperationWriter {
    private static final Logger log = LoggerFactory.getLogger(ScreenSchemeOperationWriter.class);
    private final FieldScreenFactory factory;
    private final FieldScreenSchemeManager fields;

    @Inject
    public ScreenSchemeOperationWriter(@ComponentImport FieldScreenFactory factory, @ComponentImport FieldScreenSchemeManager fields) {
        this.factory = factory; this.fields = fields;
    }

    /** The per-row results (request order) and the overall status, established from the scheme read back from Jira. */
    static final class Result {
        final Status status;
        final List<RowPlan> rows;
        final boolean readBack;
        final List<String> notes;
        final String message;

        Result(Status status, List<RowPlan> rows, boolean readBack, List<String> notes, String message) {
            this.status = status; this.rows = rows; this.readBack = readBack; this.notes = notes; this.message = message;
        }
    }

    Result write(ScreenSchemeOperationPlan plan) {
        List<PlannedRow> order = new ArrayList<PlannedRow>();
        for (PlannedRow row : plan.rows) if (row.change && row.action != RowAction.REMOVE && !DEFAULT.equals(row.operation)) order.add(row);
        for (PlannedRow row : plan.rows) if (row.change && DEFAULT.equals(row.operation)) order.add(row);
        for (PlannedRow row : plan.rows) if (row.change && row.action == RowAction.REMOVE) order.add(row);
        Set<Integer> attempted = new HashSet<Integer>();
        PlannedRow failed = null;
        String error = null;
        for (PlannedRow row : order) {
            if (error != null) break;
            attempted.add(row.index);
            try { writeRow(plan.scheme, row); }
            catch (RuntimeException e) {
                log.error("Screen Scheme " + plan.scheme.getId() + " row write failed (" + describe(row) + ")", e);
                failed = row;
                error = "Jira failed while " + describe(row) + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        List<String> notes = new ArrayList<String>();
        Map<String, FieldScreen> actual = readTable(plan.scheme.getId(), notes);

        List<RowPlan> views = new ArrayList<RowPlan>();
        boolean problem = false;
        for (PlannedRow row : plan.rows) {
            ScreenRef observed = actual == null || actual.get(row.operation) == null ? null
                    : new ScreenRef(actual.get(row.operation).getId(), actual.get(row.operation).getName());
            RowResult result;
            if (!row.change) {
                result = actual != null && !Objects.equals(id(actual.get(row.operation)), id(plan.after.get(row.operation))) ? RowResult.UNKNOWN : RowResult.NO_CHANGE;
            } else if (!attempted.contains(row.index)) {
                result = RowResult.NOT_ATTEMPTED;
            } else if (actual == null) {
                result = RowResult.UNKNOWN;
            } else {
                Long seen = id(actual.get(row.operation));
                if (Objects.equals(seen, id(row.target))) result = RowResult.APPLIED;
                else if (Objects.equals(seen, id(row.prior))) result = failed == row ? RowResult.NOT_APPLIED : RowResult.UNKNOWN;
                else result = RowResult.UNKNOWN;
            }
            problem |= result == RowResult.UNKNOWN || result == RowResult.NOT_ATTEMPTED || result == RowResult.NOT_APPLIED;
            views.add(row.view.withResult(result, observed));
        }

        Status status;
        if (actual == null) {
            status = Status.UNKNOWN;
        } else {
            Map<String, Long> expected = restricted(plan.after, plan), was = restricted(plan.before, plan), now = restricted(actual, plan);
            if (now.equals(expected) && !problem) status = Status.CHANGED;
            else if (now.equals(was)) status = error != null ? Status.FAILED : Status.UNKNOWN;
            else status = Status.PARTIALLY_CHANGED;
        }
        if (status == Status.CHANGED && error != null) {
            notes.add("Jira reported an error (" + error + ") but the scheme read back equals the planned table.");
        }
        String scheme = "Screen Scheme " + plan.scheme.getId() + " '" + plan.scheme.getName() + "'";
        String message;
        switch (status) {
            case CHANGED:
                message = scheme + " was edited in place; the rows below were read back from Jira.";
                break;
            case PARTIALLY_CHANGED:
                message = (error == null ? "The rows read back from Jira differ from the plan." : error + ".") + " " + scheme
                        + " was changed only partly (see the result of each row). Nothing is rolled back automatically. "
                        + "Run a new preview of the remaining changes and obtain a new confirmation; do not repeat the old request.";
                break;
            case FAILED:
                message = error + ". " + scheme + " was not changed (confirmed by reading the scheme back). "
                        + "Nothing is rolled back because nothing changed. Run a new preview before retrying.";
                break;
            default:
                message = (error == null ? "The writes returned without an error" : error) + ", but the state of " + scheme
                        + (actual == null ? " could not be read back." : " does not show the planned rows.")
                        + " It may be partly changed: check it in Jira. Nothing is rolled back automatically. "
                        + "Run a new preview before any retry.";
        }
        return new Result(status, views, actual != null, notes, message);
    }

    private void writeRow(FieldScreenScheme scheme, PlannedRow row) {
        switch (row.action) {
            case ADD: {
                FieldScreenSchemeItem item = factory.createFieldScreenSchemeItem();
                item.setFieldScreenScheme(scheme); item.setIssueOperation(ScreenSchemeOperationPlan.operation(row.operation));
                item.setFieldScreen(row.target);
                fields.createFieldScreenSchemeItem(item);
                break;
            }
            case REPLACE: {
                FieldScreenSchemeItem item = find(scheme, row);
                item.setFieldScreen(row.target);
                fields.updateFieldScreenSchemeItem(item);
                break;
            }
            default:
                fields.removeFieldScreenSchemeItem(find(scheme, row));
        }
    }

    private static FieldScreenSchemeItem find(FieldScreenScheme scheme, PlannedRow row) {
        for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
            if (ScreenSchemeOperationPlan.keyOf(item).equals(row.operation)) return item;
        }
        throw new IllegalStateException("the row is missing in the scheme");
    }

    /**
     * The table as stored in Jira. Jira keeps scheme objects cached (after a failed store the cached row holds the new
     * value, after a successful one the cache may be stale), so the cache is dropped before every read-back; if that fails
     * the table is not trusted.
     */
    private Map<String, FieldScreen> readTable(Long id, List<String> notes) {
        try {
            fields.refresh();
            FieldScreenScheme fresh = fields.getFieldScreenScheme(id);
            if (fresh == null) throw new IllegalStateException("the scheme is gone");
            return ScreenSchemeOperationPlan.table(fresh);
        } catch (RuntimeException e) {
            log.error("Could not read back Screen Scheme " + id + " after the writes", e);
            notes.add("The scheme could not be read back from Jira; the result of each written row is UNKNOWN.");
            return null;
        }
    }

    /** The requested operations only: a concurrent edit of an unrelated row is not this operation's result. */
    private static Map<String, Long> restricted(Map<String, FieldScreen> table, ScreenSchemeOperationPlan plan) {
        Map<String, Long> ids = new TreeMap<String, Long>();
        for (PlannedRow row : plan.rows) {
            Long id = id(table.get(row.operation));
            if (id != null) ids.put(row.operation, id);
        }
        return ids;
    }

    private static Long id(FieldScreen screen) { return screen == null ? null : screen.getId(); }

    private static String describe(PlannedRow row) {
        switch (row.action) {
            case ADD: return "adding the " + row.operation + " row";
            case REPLACE: return "replacing the " + row.operation + " row";
            default: return "removing the " + row.operation + " row";
        }
    }
}
