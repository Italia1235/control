package ru.rasulov.workflow.api;

import ru.rasulov.workflow.api.dto.edit.RenameWorkflowRequest;
import ru.rasulov.workflow.api.dto.edit.RenameWorkflowResponse;

/** Renames a workflow with Jira's own rename, which is available only for a workflow no project uses. */
public interface WorkflowRenamer {

    RenameWorkflowResponse rename(RenameWorkflowRequest request);
}
