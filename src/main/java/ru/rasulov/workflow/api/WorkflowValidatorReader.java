package ru.rasulov.workflow.api;

import ru.rasulov.workflow.api.dto.edit.WorkflowValidatorsView;

/**
 * Reads the validators of every transition of one version of an existing workflow, with the revision that
 * {@code configure_workflow} takes as {@code existing.expectedRevision}. Works without ScriptRunner.
 */
public interface WorkflowValidatorReader {

    WorkflowValidatorsView read(String workflow, String version);
}
