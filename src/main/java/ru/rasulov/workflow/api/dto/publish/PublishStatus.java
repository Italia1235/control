package ru.rasulov.workflow.api.dto.publish;

/** Outcome of {@code publish_workflow_draft}. */
public enum PublishStatus {
    WOULD_PUBLISH,
    PUBLISHED,
    /** The workflow has no draft: nothing to publish, no plan. */
    NO_DRAFT,
    FAILED
}
