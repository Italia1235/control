package ru.rasulov.workflow.api;

import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftRequest;
import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftResponse;

/**
 * Publishes the draft of an active workflow: the draft replaces the active version for every project that uses it.
 * A dry run shows what changes and who is affected and issues a plan; apply takes only that plan.
 */
public interface WorkflowDraftPublisher {

    PublishWorkflowDraftResponse publish(PublishWorkflowDraftRequest request);
}
