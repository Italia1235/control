package ru.rasulov.workflow.api;

import ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView;

/**
 * Reads the conditions of every transition of one version of an existing workflow as a tree, with the revision that
 * {@code configure_workflow} takes as {@code existing.expectedRevision}. Works without ScriptRunner.
 */
public interface WorkflowConditionReader {

    WorkflowConditionsView read(String workflow, String version);
}
