package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.event.type.EventDispatchOption;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowException;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.opensymphony.workflow.StoreException;
import com.opensymphony.workflow.spi.WorkflowEntry;
import org.ofbiz.core.entity.EntityCondition;
import org.ofbiz.core.entity.EntityConditionList;
import org.ofbiz.core.entity.EntityExpr;
import org.ofbiz.core.entity.EntityFindOptions;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.undo.IssueMutationProof;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/** Exact-ID migration with a fresh issue-state check before every issue is changed. */
@Named
public class IssueTypeMigrator {
    static final int PAGE_SIZE = 50;
    private static final List<String> ID_TYPE = Arrays.asList("id", "type");
    private static final List<String> ASCENDING = Collections.singletonList("id ASC");
    private static final List<String> DESCENDING = Collections.singletonList("id DESC");
    final OfBizDelegator delegator;
    final IssueManager issueManager;
    private final WorkflowManager workflows;
    @Inject public IssueTypeMigrator(@ComponentImport OfBizDelegator delegator,
                                    @ComponentImport IssueManager issueManager,
                                    @ComponentImport WorkflowManager workflows) {
        this.delegator = delegator; this.issueManager = issueManager; this.workflows = workflows;
    }
    int migrate(Project project, List<IssueTypeChangePlan.Mapping> mappings, ApplicationUser user,
                IssueTypeExecution execution) {
        if (mappings.isEmpty()) return 0;
        execution.beginPass();
        Map<String, IssueTypeChangePlan.Mapping> bySource = new LinkedHashMap<String, IssueTypeChangePlan.Mapping>();
        for (IssueTypeChangePlan.Mapping mapping : mappings) bySource.put(mapping.from.getId(), mapping);
        // Freeze the pass high-water mark before writes. Late IDs belong to the fresh second pass.
        List<GenericValue> last = page(project, bySource.keySet(), 0L, null, true);
        if (last.isEmpty()) return 0;
        long upperId = last.get(0).getLong("id");
        long afterId = 0L;
        int count = 0;
        while (true) {
            List<GenericValue> rows = page(project, bySource.keySet(), afterId, upperId, false);
            if (rows.isEmpty()) break;
            List<Map<String, Object>> before = new ArrayList<Map<String, Object>>(rows.size());
            List<Long> batchIds = new ArrayList<Long>(rows.size());
            for (GenericValue row : rows) {
                MutableIssue issue = issueManager.getIssueObject(row.getLong("id"));
                if (issue == null || !Objects.equals(row.getString("type"), issue.getIssueTypeId()))
                    throw new IllegalStateException("An issue changed while preparing its migration.");
                before.add(snapshot(issue));
                batchIds.add(issue.getId());
            }
            execution.beginBatch(batchIds);
            for (Map<String, Object> expected : before) {
                MutableIssue issue = issueManager.getIssueObject(((Number) expected.get("id")).longValue());
                if (!same(expected, snapshot(issue))) throw new IllegalStateException("Issue changed before migration.");
                IssueTypeChangePlan.Mapping mapping = bySource.get(expected.get("type"));
                Status target = mapping.statusMappings.get(issue.getStatusId());
                execution.beginIssue(issue.getId());
                if (!mapping.toWorkflow.getName().equals(workflowOf(issue.getWorkflowId()))
                        || (target != null && !target.getId().equals(issue.getStatusId()))) {
                    moveWorkflow(issue, mapping.toWorkflow, target == null ? issue.getStatus() : target);
                }
                issue.setIssueTypeObject(mapping.to);
                issueManager.updateIssue(user, issue, EventDispatchOption.ISSUE_UPDATED, true);
                count++;
                execution.completedIssue();
            }
            // Never offset into a shrinking source-type result set: advance by the last selected immutable ID.
            afterId = rows.get(rows.size() - 1).getLong("id");
            if (rows.size() < PAGE_SIZE) break;
        }
        return count;
    }
    Map<String,Object> snapshot(MutableIssue issue) {
        if (issue == null) throw new IllegalStateException("Migrated issue no longer exists.");
        Map<String,Object> value = new TreeMap<String,Object>();
        value.put("id", issue.getId()); value.put("project", issue.getProjectId());
        value.put("type", issue.getIssueTypeId()); value.put("status", issue.getStatusId());
        value.put("workflow", workflowOf(issue.getWorkflowId()));
        value.put("updated", issue.getUpdated() == null ? null : issue.getUpdated().getTime());
        value.put("stateHash",IssueMutationProof.hash(delegator,issue.getId()));
        return value;
    }
    private static boolean same(Map<String,Object> first, Map<String,Object> second) {
        for (String key : Arrays.asList("id", "project", "type", "status", "workflow", "updated","stateHash")) {
            Object a = first.get(key), b = second.get(key);
            if (a instanceof Number && b instanceof Number) { if (((Number) a).longValue() != ((Number) b).longValue()) return false; }
            else if (!Objects.equals(a, b)) return false;
        }
        return true;
    }
    private void moveWorkflow(MutableIssue issue, JiraWorkflow workflow, Status status) {
        try { workflows.migrateIssueToWorkflow(issue, workflow, status); }
        catch (WorkflowException e) { throw new IllegalStateException("Jira could not migrate issue " + issue.getKey(), e); }
    }
    private List<GenericValue> page(Project project, Collection<String> types, long afterId, Long upperId,
                                    boolean descending) {
        List<EntityCondition> conditions = new ArrayList<EntityCondition>(4);
        conditions.add(new EntityExpr("project", EntityOperator.EQUALS, project.getId()));
        conditions.add(new EntityExpr("type", EntityOperator.IN, types));
        conditions.add(new EntityExpr("id", EntityOperator.GREATER_THAN, afterId));
        if (upperId != null) conditions.add(new EntityExpr("id", EntityOperator.LESS_THAN_EQUAL_TO, upperId));
        int limit = descending ? 1 : PAGE_SIZE;
        EntityFindOptions options = new EntityFindOptions().maxResults(limit).fetchSize(limit);
        try (OfBizListIterator rows = delegator.findListIteratorByCondition("Issue",
                new EntityConditionList(conditions, EntityOperator.AND), null, ID_TYPE,
                descending ? DESCENDING : ASCENDING, options)) {
            // OFBiz getPartialList(1, ...) fails on an empty result, including the fresh second pass.
            List<GenericValue> page = new ArrayList<GenericValue>(limit);
            GenericValue row;
            while (page.size() < limit && (row = rows.next()) != null) page.add(row);
            return page;
        }
    }
    private String workflowOf(Long id) {
        if (id == null) return null;
        try { WorkflowEntry entry = workflows.getStore().findEntry(id); return entry == null ? null : entry.getWorkflowName(); }
        catch (StoreException e) { throw new IllegalStateException("Cannot read workflow entry " + id, e); }
    }
}
