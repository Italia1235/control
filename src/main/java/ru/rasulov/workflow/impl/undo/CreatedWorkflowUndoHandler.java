package ru.rasulov.workflow.impl.undo;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowScheme;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.ofbiz.core.entity.EntityFieldMap;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.undo.UndoHandler;
import ru.rasulov.common.impl.undo.UndoStep;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Named
public final class CreatedWorkflowUndoHandler implements UndoHandler {
    private final WorkflowManager workflows;
    private final WorkflowSchemeManager schemes;
    private final OfBizDelegator delegator;
    @Inject public CreatedWorkflowUndoHandler(@ComponentImport WorkflowManager workflows,
            @ComponentImport WorkflowSchemeManager schemes, @ComponentImport OfBizDelegator delegator) {
        this.workflows = workflows; this.schemes = schemes; this.delegator = delegator;
    }
    @Override public String kind() { return "workflow.created"; }
    @Override public List<String> lockNames(UndoStep step) {
        return Arrays.asList("ru.rasulov.control.assign-workflow", "ru.rasulov.control.create-workflow");
    }
    @Override public String conflict(UndoStep step) {
        String name = (String) WorkflowSnapshots.parse(step.getBeforeJson()).get("name");
        JiraWorkflow live = workflows.getWorkflow(name);
        if (live == null) return null;
        Map<String, Object> after = WorkflowSnapshots.parse(step.getAfterJson());
        if (!after.containsKey("id")) return "Creation of workflow '" + name + "' has no confirmed identity.";
        if (!WorkflowSnapshots.json(WorkflowSnapshots.workflow(live, delegator)).equals(step.getAfterJson()))
            return "Workflow '" + name + "' was edited or replaced.";
        if (live.hasDraftWorkflow()) return "Workflow '" + name + "' has a new draft.";
        for (WorkflowScheme scheme : schemes.getSchemesForWorkflowIncludingDrafts(live)) {
            return "Workflow '" + name + "' is referenced by scheme " + scheme.getId() + ".";
        }
        GenericValue issue = ru.rasulov.common.impl.undo.NativeEntityScan.first(delegator, "IssueWorkflowEntryView",
                new EntityFieldMap(Collections.<String, Object>singletonMap("workflowName", name), EntityOperator.AND),
                Arrays.asList("issueId"));
        if (issue != null) return "Issue " + issue.getLong("issueId") + " now uses workflow '" + name + "'.";
        return null;
    }
    @Override public void undo(UndoStep step, ApplicationUser actor) {
        String problem = conflict(step);
        if (problem != null) throw new IllegalStateException(problem);
        JiraWorkflow live = workflows.getWorkflow((String) WorkflowSnapshots.parse(step.getBeforeJson()).get("name"));
        if (live != null) workflows.deleteWorkflow(live);
    }
}
