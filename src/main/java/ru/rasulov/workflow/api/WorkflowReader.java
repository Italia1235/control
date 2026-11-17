package ru.rasulov.workflow.api;

import ru.rasulov.workflow.api.dto.edit.WorkflowView;

/** Reads one version of an existing workflow with the stable ids that {@code configure_workflow.existing} addresses. */
public interface WorkflowReader {

    /**
     * @param workflow exact workflow name
     * @param version  {@code ACTIVE} (for an inactive workflow: its saved version) or {@code DRAFT}
     * @throws ru.rasulov.common.api.exception.InvalidRequestException unknown workflow, missing draft, too large
     */
    WorkflowView read(String workflow, String version);
}
