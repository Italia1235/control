package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.workflow.WorkflowManager;
import org.ofbiz.core.entity.EntityFieldMap;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.undo.IssueMutationProof;
import ru.rasulov.common.impl.undo.NativeEntityCount;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Native issue state used to reject concurrent edits before a forward workflow migration. */
public final class IssueMigrationState {
    private IssueMigrationState() { }

    public static Map<String, Object> issue(MutableIssue issue, WorkflowManager workflows, OfBizDelegator delegator) {
        try {
            Map<String, Object> value = new TreeMap<String, Object>();
            value.put("id", issue.getId()); value.put("key", issue.getKey());
            value.put("projectId", issue.getProjectId()); value.put("typeId", issue.getIssueTypeId());
            com.opensymphony.workflow.spi.WorkflowEntry entry = workflows.getStore().findEntry(issue.getWorkflowId());
            if (entry == null) throw new IllegalStateException("Missing workflow entry " + issue.getWorkflowId());
            value.put("workflow", entry.getWorkflowName()); value.put("status", issue.getStatus().getId());
            value.put("entryId", issue.getWorkflowId()); value.put("updated", issue.getUpdated() == null ? null : issue.getUpdated().getTime());
            IssueMutationProof.Proof proof = IssueMutationProof.read(delegator, issue.getId());
            if (java.util.Objects.equals(proof.entryId, issue.getWorkflowId())) {
                // The proof already read this entry's steps and counts: reuse them instead of reading them again.
                value.put("steps", new ArrayList<Long>(proof.stepIds));
                value.put("changeCount", proof.changeCount);
                value.put("historyCount", proof.historyCount);
            } else {
                // The cached issue points at another entry than the database row: record the cached entry's facts.
                List<Long> steps = new ArrayList<Long>();
                for (GenericValue row : delegator.findByCondition("OSCurrentStep",
                        new EntityFieldMap(Collections.<String, Object>singletonMap("entryId", issue.getWorkflowId()), EntityOperator.AND),
                        Arrays.asList("id"), Arrays.asList("id"))) steps.add(row.getLong("id"));
                value.put("steps", steps);
                value.put("changeCount", NativeEntityCount.count(delegator, "ChangeGroup", Collections.<String, Object>singletonMap("issue", issue.getId())));
                value.put("historyCount", NativeEntityCount.count(delegator, "OSHistoryStep", Collections.<String, Object>singletonMap("entryId", issue.getWorkflowId())));
            }
            value.put("stateHash", proof.hash);
            return value;
        } catch (Exception e) { throw new IllegalStateException("Could not read issue migration state " + issue.getKey(), e); }
    }
}
