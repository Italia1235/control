package ru.rasulov.workflow.impl.undo;

import com.atlassian.jira.bc.ServiceResult;
import com.atlassian.jira.bc.config.StatusService;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
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
public final class CreatedStatusUndoHandler implements UndoHandler {
    private final ConstantsManager constants;
    private final StatusService service;
    private final WorkflowManager workflows;
    private final OfBizDelegator delegator;
    @Inject public CreatedStatusUndoHandler(@ComponentImport ConstantsManager constants,
            @ComponentImport StatusService service, @ComponentImport WorkflowManager workflows,
            @ComponentImport OfBizDelegator delegator) {
        this.constants = constants; this.service = service; this.workflows = workflows; this.delegator = delegator;
    }
    @Override public String kind() { return "workflow.status-created"; }
    @Override public List<String> lockNames(UndoStep step) {
        return Arrays.asList("ru.rasulov.control.assign-workflow", "ru.rasulov.control.create-workflow");
    }
    @Override public String conflict(UndoStep step) { return conflict(step, Collections.<UndoStep>emptyList()); }
    @Override public String conflict(UndoStep step, List<UndoStep> selection) {
        Map<String, Object> before = WorkflowSnapshots.parse(step.getBeforeJson());
        Map<String, Object> after = WorkflowSnapshots.parse(step.getAfterJson());
        Status live = after.containsKey("id") ? constants.getStatusObject((String) after.get("id"))
                : constants.getStatusByNameIgnoreCase((String) before.get("name"));
        if (live == null) return null;
        if (!after.containsKey("id")) return "Status '" + before.get("name") + "' has no confirmed creation identity.";
        if (!WorkflowSnapshots.json(WorkflowSnapshots.status(live)).equals(step.getAfterJson()))
            return "Status " + live.getId() + " was edited or replaced.";
        for (JiraWorkflow workflow : workflows.getWorkflowsIncludingDrafts()) {
            if (!workflow.getLinkedStatusIds().contains(live.getId())) continue;
            boolean owned = false;
            if (!workflow.isDraftWorkflow()) for (UndoStep candidate : selection) {
                Map<String, Object> created = WorkflowSnapshots.parse(candidate.getAfterJson());
                if (candidate.getKind().equals("workflow.created") && !"DISCARDED".equals(candidate.getState())
                        && created.containsKey("id") && workflow.getName().equals(created.get("name"))) owned = true;
            }
            if (!owned) return "Status " + live.getId() + " is referenced by workflow '" + workflow.getName() + "'.";
        }
        GenericValue issue = ru.rasulov.common.impl.undo.NativeEntityScan.first(delegator, "Issue",
                new EntityFieldMap(Collections.<String, Object>singletonMap("status", live.getId()), EntityOperator.AND),
                Arrays.asList("id"));
        if (issue != null) return "Issue " + issue.getLong("id") + " now uses status " + live.getId() + ".";
        return null;
    }
    @Override public void undo(UndoStep step, ApplicationUser actor) {
        String problem = conflict(step);
        if (problem != null) throw new IllegalStateException(problem);
        Map<String, Object> after = WorkflowSnapshots.parse(step.getAfterJson());
        if (!after.containsKey("id")) return;
        Status live = constants.getStatusObject((String) after.get("id"));
        if (live == null) return;
        ServiceResult result = service.removeStatus(actor, live);
        if (!result.isValid()) throw new IllegalStateException("Jira refused to remove status " + live.getId() + ": " + result.getErrorCollection());
    }
}
