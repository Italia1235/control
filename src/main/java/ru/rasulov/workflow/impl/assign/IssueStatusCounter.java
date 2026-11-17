package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.WorkflowManager;
import com.opensymphony.workflow.StoreException;
import com.opensymphony.workflow.spi.WorkflowEntry;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.ofbiz.core.entity.EntityFieldMap;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.EntityCondition;
import org.ofbiz.core.entity.EntityExpr;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.workflow.api.WorkflowAssignLimits;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Counts the issues of a project by issue type and status and diagnoses native workflow entries against the scheme.
 * Uses one database pass, not the search index: an index can lag and hide issues left on an old workflow.
 */
@Named
public class IssueStatusCounter {

    private final OfBizDelegator delegator;
    private final LongSupplier nanoTime;

    @Inject
    public IssueStatusCounter(@ComponentImport OfBizDelegator delegator) {
        this(delegator, System::nanoTime);
    }

    IssueStatusCounter(OfBizDelegator delegator, LongSupplier nanoTime) {
        this.delegator = delegator;
        this.nanoTime = nanoTime;
    }

    IssueUsage count(Project project) {
        return scan(project, null, null, null).usage;
    }

    /** Count statuses and inspect native entries together; expiration fails closed, never reports success. */
    Scan scan(Project project, AssignableWorkflowScheme scheme, Set<String> selectedTypes,
              WorkflowManager workflowManager) {
        IssueUsage usage = new IssueUsage();
        if (selectedTypes != null && selectedTypes.isEmpty()) return new Scan(usage, null);
        List<String> keys = new ArrayList<String>();
        int mismatches = 0;
        int checked = 0;
        int total = 0;
        boolean expired = false;
        long started = nanoTime.getAsLong();
        long budget = TimeUnit.MILLISECONDS.toNanos(WorkflowAssignLimits.NATIVE_DIAGNOSIS_BUDGET_MILLIS);
        EntityCondition condition = new EntityFieldMap(Collections.singletonMap("project", project.getId()),
                EntityOperator.AND);
        if (selectedTypes != null) {
            condition = new EntityExpr(condition, EntityOperator.AND,
                    new EntityExpr("type", EntityOperator.IN, new ArrayList<String>(selectedTypes)));
        }
        try (OfBizListIterator rows = delegator.findListIteratorByCondition(
                "Issue", condition, null, scheme == null ? Arrays.asList("type", "status")
                        : Arrays.asList("id", "number", "type", "status", "workflowId"), Arrays.asList("id"), null)) {
            GenericValue row;
            while ((row = rows.next()) != null) {
                String type = row.getString("type");
                usage.add(type, row.getString("status"));
                total++;
                if (scheme == null || expired) continue;
                expired = nanoTime.getAsLong() - started >= budget;
                if (expired) continue;
                Long entryId = row.getLong("workflowId");
                WorkflowEntry entry = entryId == null ? null : workflowManager.getStore().findEntry(entryId);
                checked++;
                if (entry != null && scheme.getActualWorkflow(type).equals(entry.getWorkflowName())) continue;
                mismatches++;
                if (keys.size() < WorkflowAssignLimits.MAX_ISSUE_KEYS_SHOWN) {
                    keys.add(project.getKey() + "-" + row.getLong("number"));
                }
            }
        } catch (StoreException e) {
            throw new IllegalStateException("Could not inspect native workflow entries in project " + project.getKey(), e);
        }
        String diagnosis = null;
        if (mismatches != 0) {
            diagnosis = "Native workflow entries differ from the workflow scheme (or are missing) for "
                    + (expired ? "at least " : "") + mismatches + " issue(s) in project " + project.getKey()
                    + ": " + String.join(", ", keys)
                    + (mismatches > keys.size() ? " (first " + keys.size() + " keys shown)" : "")
                    + ". The scheme does not prove migration is complete. No repair was attempted. "
                    + "Inspect these issues' native workflow entries; ask a Jira administrator to inspect all entries "
                    + "of the affected issue types in this project, including any remainder beyond the displayed keys.";
        }
        if (expired && checked == 0) {
            diagnosis = "Native workflow inspection was not performed: checked 0 of " + total
                    + " issues (проверено 0 из " + total + " задач) in project " + project.getKey()
                    + " because the diagnosis time budget expired. "
                    + "The inspection result and total remainder are unknown. No repair was attempted.";
        } else if (expired) {
            diagnosis = (diagnosis == null ? "" : diagnosis + " ")
                    + "Native workflow diagnosis time budget reached: checked " + checked + " of " + total
                    + " issues (проверено " + checked + " из " + total + " задач) in project " + project.getKey()
                    + ". Unchecked entries may differ; the total remainder is unknown. No repair was attempted.";
        }
        return new Scan(usage, diagnosis);
    }

    static final class Scan {
        final IssueUsage usage;
        final String diagnosis;

        Scan(IssueUsage usage, String diagnosis) {
            this.usage = usage;
            this.diagnosis = diagnosis;
        }
    }

}
