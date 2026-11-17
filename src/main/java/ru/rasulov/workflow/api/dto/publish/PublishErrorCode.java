package ru.rasulov.workflow.api.dto.publish;

/** Why a {@code publish_workflow_draft} call did not publish. */
public enum PublishErrorCode {
    /** The request is malformed: a blank workflow name. */
    VALIDATION_ERROR,
    /** No active workflow has this name; suggestions are the workflows that have drafts. */
    UNKNOWN_WORKFLOW,
    /** Jira's own validation refuses to publish this draft; the message carries Jira's reasons. */
    PUBLISH_REFUSED,
    /** Preview token is absent, expired, foreign, consumed, or the workflow, draft or its users changed. */
    PREVIEW_STALE,
    /** Jira failed while publishing, or the result could not be confirmed. */
    JIRA_ERROR
}
