package ru.rasulov.workflow.api;

import ru.rasulov.workflow.api.dto.CopyWorkflowRequest;
import ru.rasulov.workflow.api.dto.CopyWorkflowResponse;

/** Preview and apply an exact copy of a workflow's main version. */
public interface WorkflowCopier {
    CopyWorkflowResponse copy(CopyWorkflowRequest request);
}
