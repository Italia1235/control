package it.ru.rasulov.workflow;

import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import ru.rasulov.workflow.api.WorkflowDraftPublisher;
import ru.rasulov.workflow.api.WorkflowConfigurer;
import ru.rasulov.workflow.api.WorkflowReader;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.api.dto.edit.WorkflowChange;
import ru.rasulov.workflow.api.dto.edit.WorkflowView;
import ru.rasulov.workflow.api.dto.publish.PublishStatus;
import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftRequest;
import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftResponse;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What must hold for a workflow that a project uses right after an assignment: Jira reports it active, the tools read
 * it as active, {@code configure_workflow} saves into a draft and leaves the live version alone, and the draft can be
 * published. A stale active-workflow cache breaks every step of it.
 */
final class WorkflowDraftLifecycle {
    private WorkflowDraftLifecycle() {
    }

    static void assertActiveEditedThroughDraftAndPublished(WorkflowManager workflowManager, WorkflowReader reader,
                                                           WorkflowConfigurer configurer, WorkflowDraftPublisher publisher,
                                                           String name) {
        try {
            JiraWorkflow main = workflowManager.getWorkflow(name);
            assertNotNull(main);
            assertTrue("Jira reports the assigned workflow active", workflowManager.isActive(main));
            assertTrue("the workflow is active", main.isActive());
            WorkflowView before = reader.read(name, "ACTIVE");
            assertTrue("get_workflow reads the assigned workflow as active", before.isActive());
            assertFalse(before.isHasDraft());

            String marker = "assigned, then edited through a draft";
            WorkflowChange change = new WorkflowChange("UPDATE_METADATA", null, null, null, null, null, marker, null,
                    null, null, null, null, null, null);
            ConfigureWorkflowResponse preview = configurer.configure(new ConfigureWorkflowRequest(true, null,
                    new ConfigureWorkflowRequest.Existing(name, "ACTIVE", before.getRevision(),
                            Collections.singletonList(change)), null, null, null));
            assertEquals(preview.getMessage(), "WOULD_CHANGE", preview.getStatus());
            ConfigureWorkflowResponse saved = configurer.configure(new ConfigureWorkflowRequest(false, null, null,
                    null, null, preview.getPlanId()));
            assertEquals(saved.getMessage(), "DRAFT_SAVED", saved.getStatus());

            assertNotNull("the change went to a draft", workflowManager.getDraftWorkflow(name));
            WorkflowView live = reader.read(name, "ACTIVE");
            assertTrue("the draft is part of the state the revision covers", live.isHasDraft());
            assertEquals("the live version keeps its description before publication", before.getDescription(),
                    live.getDescription());
            assertEquals("the live version keeps its steps", before.getSteps().size(), live.getSteps().size());
            assertEquals("the live version keeps its transitions", before.getTransitions().size(),
                    live.getTransitions().size());
            assertTrue(live.isActive());
            assertEquals(marker, reader.read(name, "DRAFT").getDescription());

            PublishWorkflowDraftResponse publishPreview = publisher.publish(
                    new PublishWorkflowDraftRequest(true, name, null));
            assertEquals(publishPreview.getMessage(), PublishStatus.WOULD_PUBLISH, publishPreview.getStatus());
            PublishWorkflowDraftResponse published = publisher.publish(
                    new PublishWorkflowDraftRequest(false, null, publishPreview.getPlanId()));
            assertEquals(published.getMessage(), PublishStatus.PUBLISHED, published.getStatus());
            assertNull("the draft is gone", workflowManager.getDraftWorkflow(name));
            assertEquals(marker, reader.read(name, "ACTIVE").getDescription());
            assertTrue(workflowManager.isActive(workflowManager.getWorkflow(name)));
        } finally {
            if (workflowManager.getDraftWorkflow(name) != null) {
                workflowManager.deleteDraftWorkflow(name);
            }
        }
    }
}
