package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreenFactory;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.RowPlan;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.RowResult;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.SchemeRef;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.Status;
import ru.rasulov.screens.impl.IssueTypeScreenSchemeMappingPlan.PlannedRow;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

import static ru.rasulov.screens.impl.IssueTypeScreenSchemeMappingPlan.DEFAULT;

/**
 * Writes the changing rows of one Issue Type Screen Scheme: the DEFAULT row first, then the others in request order, one
 * Jira call per row, stopping at the first failure. Rows that need no write are never written. Never rolls back: the
 * outcome is what a fresh read of the scheme shows, row by row and as a whole.
 */
@Named
public class IssueTypeScreenSchemeMappingWriter {
    private static final Logger log = LoggerFactory.getLogger(IssueTypeScreenSchemeMappingWriter.class);
    private final FieldScreenFactory factory;
    private final IssueTypeScreenSchemeManager tops;

    @Inject
    public IssueTypeScreenSchemeMappingWriter(@ComponentImport FieldScreenFactory factory,
            @ComponentImport IssueTypeScreenSchemeManager tops) {
        this.factory = factory; this.tops = tops;
    }

    /** The per-row results (request order) and the overall status, established from the table read back from Jira. */
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

    Result write(IssueTypeScreenSchemeMappingPlan plan) {
        List<PlannedRow> order = new ArrayList<PlannedRow>();
        for (PlannedRow row : plan.rows) if (row.change && DEFAULT.equals(row.key)) order.add(row);
        for (PlannedRow row : plan.rows) if (row.change && !DEFAULT.equals(row.key)) order.add(row);
        Set<Integer> attempted = new HashSet<Integer>();
        PlannedRow failed = null;
        String error = null;
        for (PlannedRow row : order) {
            if (error != null) break;
            attempted.add(row.index);
            try { writeRow(plan.itss, row); }
            catch (RuntimeException e) {
                log.error("Issue Type Screen Scheme " + plan.itss.getId() + " row write failed (" + describe(row) + ")", e);
                failed = row;
                error = "Jira failed while " + describe(row) + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        List<String> notes = new ArrayList<String>();
        Map<String, FieldScreenScheme> actual = readTable(plan.itss.getId(), notes);

        List<RowPlan> views = new ArrayList<RowPlan>();
        boolean problem = false;
        for (PlannedRow row : plan.rows) {
            SchemeRef observed = actual == null || actual.get(row.key) == null ? null
                    : new SchemeRef(actual.get(row.key).getId(), actual.get(row.key).getName());
            RowResult result;
            if (!row.change) {
                result = actual != null && !Objects.equals(id(actual.get(row.key)), id(plan.after.get(row.key))) ? RowResult.UNKNOWN : RowResult.NO_CHANGE;
            } else if (!attempted.contains(row.index)) {
                result = RowResult.NOT_ATTEMPTED;
            } else if (actual == null) {
                result = RowResult.UNKNOWN;
            } else {
                Long seen = id(actual.get(row.key));
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
            notes.add("Jira reported an error (" + error + ") but the table read back equals the planned table.");
        }
        String scheme = "Issue Type Screen Scheme " + plan.itss.getId() + " '" + plan.itss.getName() + "'";
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
                message = error + ". " + scheme + " was not changed (confirmed by reading the table back). "
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

    private void writeRow(IssueTypeScreenScheme itss, PlannedRow row) {
        switch (row.action) {
            case ADD: {
                IssueTypeScreenSchemeEntity entity = factory.createIssueTypeScreenSchemeEntity();
                entity.setIssueTypeScreenScheme(itss); entity.setIssueTypeId(row.typeId); entity.setFieldScreenScheme(row.target);
                tops.createIssueTypeScreenSchemeEntity(entity);
                break;
            }
            case REPLACE: {
                IssueTypeScreenSchemeEntity entity = find(itss, row);
                entity.setFieldScreenScheme(row.target);
                tops.updateIssueTypeScreenSchemeEntity(entity);
                break;
            }
            default:
                tops.removeIssueTypeScreenSchemeEntity(find(itss, row));
        }
    }

    private static IssueTypeScreenSchemeEntity find(IssueTypeScreenScheme itss, PlannedRow row) {
        for (IssueTypeScreenSchemeEntity entity : itss.getEntities()) {
            if (Objects.equals(entity.getIssueTypeId(), row.typeId)) return entity;
        }
        throw new IllegalStateException("the row is missing in the scheme");
    }

    /**
     * The table as stored in Jira. Jira keeps scheme objects cached (after a failed store the cached row holds the new
     * value, after a successful one the cache may be stale), so the cache is dropped before every read-back; if that fails
     * the table is not trusted.
     */
    private Map<String, FieldScreenScheme> readTable(Long id, List<String> notes) {
        try {
            tops.refresh();
            IssueTypeScreenScheme fresh = tops.getIssueTypeScreenScheme(id);
            if (fresh == null) throw new IllegalStateException("the scheme is gone");
            Map<String, FieldScreenScheme> table = new TreeMap<String, FieldScreenScheme>();
            for (IssueTypeScreenSchemeEntity entity : fresh.getEntities()) {
                table.put(entity.getIssueTypeId() == null ? DEFAULT : entity.getIssueTypeId(), entity.getFieldScreenScheme());
            }
            return table;
        } catch (RuntimeException e) {
            log.error("Could not read back Issue Type Screen Scheme " + id + " after the writes", e);
            notes.add("The table could not be read back from Jira; the result of each written row is UNKNOWN.");
            return null;
        }
    }

    /** The requested keys only: a concurrent edit of an unrelated row is not this operation's result. */
    private static Map<String, Long> restricted(Map<String, FieldScreenScheme> table, IssueTypeScreenSchemeMappingPlan plan) {
        Map<String, Long> ids = new TreeMap<String, Long>();
        for (PlannedRow row : plan.rows) {
            Long id = id(table.get(row.key));
            if (id != null) ids.put(row.key, id);
        }
        return ids;
    }

    private static Long id(FieldScreenScheme scheme) { return scheme == null ? null : scheme.getId(); }

    private static String describe(PlannedRow row) {
        String where = row.typeId == null ? "the DEFAULT row" : "the row of issue type '" + row.typeName + "'";
        switch (row.action) {
            case ADD: return "adding " + where;
            case REPLACE: return "replacing " + where;
            default: return "removing " + where;
        }
    }
}
