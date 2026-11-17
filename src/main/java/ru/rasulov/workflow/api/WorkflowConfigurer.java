package ru.rasulov.workflow.api;

import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;

/** Plans and saves one complete workflow descriptor; additions are forward-only. */
public interface WorkflowConfigurer {
    ConfigureWorkflowResponse configure(ConfigureWorkflowRequest request);
}
