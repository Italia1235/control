package ru.rasulov.resolutions.impl;

import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.config.ResolutionManager;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.opensymphony.workflow.loader.FunctionDescriptor;
import ru.rasulov.common.impl.undo.JournalJson;
import ru.rasulov.common.impl.undo.NativeEntityCount;
import ru.rasulov.common.impl.undo.UndoHandler;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.resolutions.api.dto.ResolutionView;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/** Never migrates issues to another Resolution or deletes a referenced global value. */
@Named
public final class CreatedResolutionUndoHandler implements UndoHandler {
    private final ResolutionManager resolutions;
    private final OfBizDelegator delegator;
    private final WorkflowManager workflows;
    private final ConstantsManager constants;
    @Inject public CreatedResolutionUndoHandler(@ComponentImport ResolutionManager resolutions,
                                               @ComponentImport OfBizDelegator delegator,
                                               @ComponentImport WorkflowManager workflows,
                                               @ComponentImport ConstantsManager constants) {
        this.resolutions = resolutions; this.delegator = delegator; this.workflows = workflows;
        this.constants = constants;
    }
    @Override public String kind() { return "resolution.created"; }
    @Override public List<String> lockNames(UndoStep step) {
        return Arrays.asList(ResolutionServiceImpl.LOCK_NAME, "ru.rasulov.control.create-workflow",
                "ru.rasulov.control.assign-workflow");
    }
    @Override public String conflict(UndoStep step) {
        Map<?, ?> recorded = JournalJson.read(step.getAfterJson(), Map.class);
        String id = (String) recorded.get("id");
        if (id == null || id.trim().isEmpty()) return "Resolution creation has no confirmed ID; its result is uncertain.";
        Resolution current = resolutions.getResolution(id);
        if (current == null) return null;
        if (!JournalJson.write(ResolutionView.of(current)).equals(step.getAfterJson())) return "Created Resolution was edited.";
        Resolution defaultResolution = resolutions.getDefaultResolution();
        if (defaultResolution != null && id.equals(defaultResolution.getId())) return "Created Resolution is now Jira's default.";
        // Database count includes issues hidden by permissions and archived issues, unlike a user-scoped JQL search.
        if (NativeEntityCount.count(delegator, "Issue", Collections.singletonMap("resolution", id)) != 0)
            return "Created Resolution is used by issues; it cannot be automatically deleted.";
        for (JiraWorkflow workflow : workflows.getWorkflowsIncludingDrafts()) {
            for (Collection<FunctionDescriptor> functions : workflows.getPostFunctionsForWorkflow(workflow).values()) {
                for (FunctionDescriptor function : functions) {
                    Map<?, ?> args = function.getArgs();
                    if ("resolution".equals(args.get("field.name")) && id.equals(args.get("field.value")))
                        return "Created Resolution is referenced by workflow '" + workflow.getName()
                                + "'; remove that reference before undoing Resolution creation.";
                }
            }
        }
        return null;
    }
    @Override public void undo(UndoStep step, ApplicationUser actor) {
        String reason = conflict(step);
        if (reason != null) throw new IllegalStateException(reason);
        String id = (String) JournalJson.read(step.getAfterJson(), Map.class).get("id");
        if (resolutions.getResolution(id) != null) {
            // Jira's removeResolution requires a replacement and migrates issues. Only delete the
            // confirmed unused catalog row; never choose a replacement or rewrite issue data.
            try {
                delegator.removeByAnd("Resolution", Collections.singletonMap("id", id));
            } finally {
                constants.refreshResolutions();
            }
        }
        if (resolutions.getResolution(id) != null) throw new IllegalStateException("Jira did not remove the unused Resolution.");
    }
}
