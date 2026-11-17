package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowException;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import org.ofbiz.core.entity.EntityCondition;
import org.ofbiz.core.entity.EntityConditionList;
import org.ofbiz.core.entity.EntityExpr;
import org.ofbiz.core.entity.EntityFindOptions;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.workflow.impl.undo.WorkflowSnapshots;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves issues to another workflow and keeps their status. An issue stores the workflow it runs as an osworkflow
 * entry (workflow name and step id), and step ids differ between workflows, so changing the scheme alone would put an
 * issue on the wrong step. {@code migrateIssueToWorkflow} rewrites the entry onto the step of the same status.
 */
@Named
public class IssueMigrator {
    static final int PAGE_SIZE = 50;

    private final OfBizDelegator delegator;
    private final IssueManager issueManager;
    private final WorkflowManager workflowManager;
    private final ChangeJournal journal;

    @Inject
    public IssueMigrator(@ComponentImport OfBizDelegator delegator, @ComponentImport IssueManager issueManager,
                         @ComponentImport WorkflowManager workflowManager, ChangeJournal journal) {
        this.delegator = delegator;
        this.issueManager = issueManager;
        this.workflowManager = workflowManager;
        this.journal = journal;
    }

    /**
     * Selected issues are checked again immediately before migration; {@code moved} holds the progress count.
     * Running it again picks up what is left, including issues created while the call was running.
     *
     * @param leaving issue type id to the name of the workflow its issues leave
     * @return keys of the issues that could not be moved because their status is not in {@code target}
     * @throws IllegalStateException Jira failed on an issue or its native state changed concurrently
     */
    List<String> migrate(Project project, Map<String, String> leaving, JiraWorkflow target, MigrationCount moved) {
        return migrate(project, leaving, target, Collections.<String, Status>emptyMap(), moved);
    }

    List<String> migrate(Project project, Map<String, String> leaving, JiraWorkflow target,
                         Map<String, Status> statusMappings, MigrationCount moved) {
        Map<String, JiraWorkflow> targets = new HashMap<String, JiraWorkflow>();
        for (String left : leaving.values()) {
            targets.put(left, target);
        }
        return migrateByWorkflow(project, leaving, targets, statusMappings, moved);
    }

    /**
     * Like {@link #migrate}, but each issue type goes to its own target: {@code targets} maps the name of the
     * workflow an issue type leaves to the workflow that replaces it (a clone has one copy per workflow).
     */
    List<String> migrateByWorkflow(Project project, Map<String, String> leaving, Map<String, JiraWorkflow> targets,
                                   MigrationCount moved) {
        return migrateByWorkflow(project, leaving, targets, Collections.<String, Status>emptyMap(), moved);
    }

    private List<String> migrateByWorkflow(Project project, Map<String, String> leaving,
                                           Map<String, JiraWorkflow> targets, Map<String, Status> statusMappings,
                                           MigrationCount moved) {
        List<String> stuck = new ArrayList<String>();
        List<PlannedMigration> planned = new ArrayList<PlannedMigration>();
        for (Map.Entry<String, String> type : leaving.entrySet()) {
            journal.checkpoint();
            JiraWorkflow target = targets.get(type.getValue());
            long after = 0;
            List<GenericValue> page;
            while (!(page = issuePage(project, type.getKey(), after)).isEmpty()) {
                for (GenericValue row : page) {
                    journal.checkpoint();
                    after = row.getLong("id");
                    MutableIssue issue = issueManager.getIssueObject(after);
                    Map<String, Object> state = IssueMigrationState.issue(issue, workflowManager, delegator);
                    String actualWorkflow = (String) state.get("workflow");
                    if (target.getName().equals(actualWorkflow)) continue;
                    if (!type.getValue().equals(actualWorkflow) || !project.getId().equals(state.get("projectId"))
                            || !type.getKey().equals(state.get("typeId")))
                        throw new IllegalStateException("Issue " + issue.getKey() + " changed workflow, project or type while planning migration.");
                    Status mapped = statusMappings.get(state.get("status"));
                    if (mapped == null && !target.getLinkedStatusIds().contains(state.get("status"))) {
                        stuck.add(issue.getKey()); continue;
                    }
                    planned.add(new PlannedMigration(state, target, mapped));
                }
            }
        }
        for (PlannedMigration migration : planned) {
            journal.checkpoint();
            Map<String, Object> state = migration.state;
            MutableIssue issue = issueManager.getIssueObject(WorkflowSnapshots.number(state.get("id")));
            if (!WorkflowSnapshots.json(state).equals(WorkflowSnapshots.json(
                    IssueMigrationState.issue(issue, workflowManager, delegator))))
                throw new IllegalStateException("Issue " + issue.getKey() + " changed before its migration.");
            moved.attempting();
            moveTo(issue.getId(), migration.target, migration.status);
            Map<String, Object> after = IssueMigrationState.issue(issueManager.getIssueObject(issue.getId()), workflowManager, delegator);
            String expectedStatus = migration.status == null ? (String) state.get("status") : migration.status.getId();
            if (!migration.target.getName().equals(after.get("workflow")) || !expectedStatus.equals(after.get("status"))
                    || !state.get("projectId").equals(after.get("projectId")) || !state.get("typeId").equals(after.get("typeId"))
                    || !state.get("key").equals(after.get("key")))
                throw new IllegalStateException("Issue " + issue.getKey() + " changed during migration; its state is ambiguous.");
            moved.increment();
            journal.checkpoint();
        }
        return stuck;
    }

    private static final class PlannedMigration {
        final Map<String, Object> state;
        final JiraWorkflow target;
        final Status status;
        PlannedMigration(Map<String, Object> state, JiraWorkflow target, Status status) {
            this.state = state; this.target = target; this.status = status;
        }
    }

    private void moveTo(Long issueId, JiraWorkflow workflow, Status mappedStatus) {
        MutableIssue issue = issueManager.getIssueObject(issueId);
        Status previousStatus = issue.getStatus();
        Status targetStatus = mappedStatus == null ? previousStatus : mappedStatus;
        try {
            workflowManager.migrateIssueToWorkflow(issue, workflow, targetStatus);
        } catch (WorkflowException e) {
            throw new IllegalStateException("Jira could not move issue " + issue.getKey() + " to workflow '"
                    + workflow.getName() + "'", e);
        }
    }

    /** At most {@link #PAGE_SIZE} issues of the type with an id above {@code afterId}, lowest id first. */
    private List<GenericValue> issuePage(Project project, String issueTypeId, long afterId) {
        List<EntityCondition> conditions = new ArrayList<EntityCondition>(3);
        conditions.add(new EntityExpr("project", EntityOperator.EQUALS, project.getId()));
        conditions.add(new EntityExpr("type", EntityOperator.EQUALS, issueTypeId));
        conditions.add(new EntityExpr("id", EntityOperator.GREATER_THAN, afterId));
        EntityFindOptions options = new EntityFindOptions().maxResults(PAGE_SIZE).fetchSize(PAGE_SIZE);
        try (OfBizListIterator rows = delegator.findListIteratorByCondition("Issue",
                new EntityConditionList(conditions, EntityOperator.AND), null, Collections.singletonList("id"),
                Collections.singletonList("id ASC"), options)) {
            // OFBiz getPartialList(1, ...) fails on an empty result, so read the cursor row by row.
            List<GenericValue> page = new ArrayList<GenericValue>(PAGE_SIZE);
            GenericValue row;
            while (page.size() < PAGE_SIZE && (row = rows.next()) != null) page.add(row);
            return page;
        }
    }

}
