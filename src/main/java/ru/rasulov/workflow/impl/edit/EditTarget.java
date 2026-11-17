package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowUtil;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import ru.rasulov.workflow.api.dto.edit.EditErrorCode;

import java.util.Set;

/**
 * Which stored version a configure_workflow change lands in: the existence, system and revision checks,
 * the choice between the saved
 * workflow, an existing draft and a draft to be created, a private copy of that version's descriptor, and the proof that
 * Jira's own save reproduces it exactly. Either a refusal (nothing was read for writing) or a target.
 */
final class EditTarget {
    enum Mode { INACTIVE_SAVE, DRAFT_CREATE, DRAFT_SAVE }

    final String refusalWorkflow;
    final EditErrorCode refusalCode;
    final String refusalMessage;

    final WorkflowInspector.Snapshot snapshot;
    final EditTarget.Mode mode;
    /** The private copy that is patched and saved. */
    final JiraWorkflow base;
    final String draftState;
    /** Step ids of the active version while a draft is edited; {@code null} for an inactive workflow. */
    final Set<Integer> protectedSteps;
    final WorkflowDescriptor descriptor;
    /** The definition of {@link #descriptor} before any change. */
    final String before;

    private EditTarget(String refusalWorkflow, EditErrorCode refusalCode, String refusalMessage) {
        this.refusalWorkflow = refusalWorkflow;
        this.refusalCode = refusalCode;
        this.refusalMessage = refusalMessage;
        this.snapshot = null;
        this.mode = null;
        this.base = null;
        this.draftState = null;
        this.protectedSteps = null;
        this.descriptor = null;
        this.before = null;
    }

    private EditTarget(WorkflowInspector.Snapshot snapshot, EditTarget.Mode mode, JiraWorkflow base, String draftState,
                       Set<Integer> protectedSteps, WorkflowDescriptor descriptor, String before) {
        this.refusalWorkflow = null;
        this.refusalCode = null;
        this.refusalMessage = null;
        this.snapshot = snapshot;
        this.mode = mode;
        this.base = base;
        this.draftState = draftState;
        this.protectedSteps = protectedSteps;
        this.descriptor = descriptor;
        this.before = before;
    }

    boolean refused() {
        return refusalCode != null;
    }

    private static EditTarget refuse(String workflow, EditErrorCode code, String message) {
        return new EditTarget(workflow, code, message);
    }

    /** {@code name} is trimmed and not blank, {@code version} is ACTIVE or DRAFT, {@code expectedRevision} is trimmed and not blank. */
    static EditTarget resolve(String name, String version, String expectedRevision, WorkflowInspector inspector,
                              WorkflowManager workflowManager) {
        WorkflowInspector.Snapshot s = inspector.snapshot(name);
        if (s == null) {
            return refuse(name, EditErrorCode.UNKNOWN_WORKFLOW, "No workflow is named '" + name + "'. Names are "
                    + "exact; use get_project_workflows to find them.");
        }
        if (s.system()) {
            return refuse(s.name(), EditErrorCode.SYSTEM_WORKFLOW, "'" + s.name() + "' is Jira's built-in workflow "
                    + "and is not edited in place. Copy it first and edit the copy.");
        }
        if (!s.revision().equals(expectedRevision)) {
            return refuse(s.name(), EditErrorCode.VERSION_CONFLICT, "The workflow or its draft changed after the "
                    + "revision you passed was read. Nothing was written. Read the workflow again (get_workflow) and "
                    + "base the edit on that.");
        }
        EditTarget.Mode mode;
        JiraWorkflow base;
        String draftState;
        Set<Integer> protectedSteps = null;
        if ("DRAFT".equals(version)) {
            if (!s.active() || s.draft() == null) {
                return refuse(s.name(), EditErrorCode.VERSION_CONFLICT, "The workflow '" + s.name()
                        + "' has no draft now. Nothing was written. Read the workflow again.");
            }
            mode = Mode.DRAFT_SAVE;
            base = s.draft();
            draftState = "EXISTS: the existing draft is edited; it is shared by all administrators and its other "
                    + "changes stay";
            protectedSteps = DescriptorDiff.stepIds(s.main().getDescriptor());
        } else if (!s.active()) {
            mode = Mode.INACTIVE_SAVE;
            base = workflowManager.getWorkflowClone(s.name());
            draftState = "NOT_APPLICABLE: the workflow is not active, it is saved in place";
        } else if (s.draft() != null) {
            return refuse(s.name(), EditErrorCode.DRAFT_EXISTS, "The active workflow '" + s.name() + "' already has a "
                    + "draft. It is shared by all administrators and may hold changes of others. Read it with "
                    + "get_workflow version DRAFT and edit that version with its revision.");
        } else {
            mode = Mode.DRAFT_CREATE;
            base = workflowManager.getWorkflowClone(s.name());
            draftState = "NONE: a draft will be created and the edit saved into it; the active version stays";
            protectedSteps = DescriptorDiff.stepIds(s.main().getDescriptor());
        }
        if (base == null) {
            return refuse(s.name(), EditErrorCode.JIRA_ERROR, "Jira did not return a copy of the workflow.");
        }
        WorkflowDescriptor descriptor = base.getDescriptor();
        String before = DescriptorFacts.definition(descriptor);
        try {
            String again = DescriptorFacts.definition(WorkflowUtil.convertXMLtoWorkflowDescriptor(
                    WorkflowUtil.convertDescriptorToXML(descriptor)));
            if (!before.equals(again)) {
                return refuse(s.name(), EditErrorCode.UNSUPPORTED_DESCRIPTOR, "Jira's own save would not reproduce "
                        + "this workflow's definition exactly (a save and reload changes it), so it is not edited.");
            }
        } catch (Exception e) {
            return refuse(s.name(), EditErrorCode.UNSUPPORTED_DESCRIPTOR, "The workflow definition cannot be "
                    + "serialized and read back: " + e.getMessage());
        }
        return new EditTarget(s, mode, base, draftState, protectedSteps, descriptor, before);
    }
}
