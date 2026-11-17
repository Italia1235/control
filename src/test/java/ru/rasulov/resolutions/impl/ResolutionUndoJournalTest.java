package ru.rasulov.resolutions.impl;

import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.config.ResolutionManager;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.opensymphony.workflow.loader.FunctionDescriptor;
import org.junit.Test;
import org.ofbiz.core.entity.DelegatorInterface;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.resolutions.api.dto.CreateResolutionResponse;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.rasulov.resolutions.impl.ResolutionServiceImplTest.*;

/** Real durable journal with isolated native catalog and workflow managers. */
public class ResolutionUndoJournalTest {
    @Test public void unusedCreationSurvivesRestartAndDeletesOnlyConfirmedCatalogRow() throws Exception {
        Domain domain = new Domain();
        try (UndoTestFixture fixture = UndoTestFixture.create("admin", domain.handler)) {
            String operation = domain.create(fixture);
            ChangeJournal reopened = fixture.reopen();
            Map<String,Object> preview = reopened.rollback("admin", operation, null, null, true, null);
            assertEquals(preview.toString(), "READY", preview.get("status"));
            assertEquals("ROLLED_BACK", reopened.rollback("admin", null, null, null, false, (String) preview.get("planId")).get("status"));
            assertNull(domain.created.get());
            verify(domain.delegator).removeByAnd("Resolution", Collections.singletonMap("id", "9"));
            verify(domain.constants).refreshResolutions();
            verify(domain.resolutions, never()).removeResolution(anyString(), any());
        }
    }

    @Test public void issuesIncludingHiddenOrArchivedBlockDeletion() throws Exception {
        Domain domain = new Domain();
        try (UndoTestFixture fixture = UndoTestFixture.create("admin", domain.handler)) {
            String operation = domain.create(fixture);
            domain.issueReferences = 1L;
            assertBlocked(fixture, operation, domain);
        }
    }

    @Test public void editedOrDefaultResolutionBlocksDeletion() throws Exception {
        for (boolean makeDefault : Arrays.asList(false, true)) {
            Domain domain = new Domain();
            try (UndoTestFixture fixture = UndoTestFixture.create("admin", domain.handler)) {
                String operation = domain.create(fixture);
                if (makeDefault) when(domain.resolutions.getDefaultResolution()).thenReturn(domain.created.get());
                else domain.created.set(resolution("9", "Completed", "Edited"));
                assertBlocked(fixture, operation, domain);
            }
        }
    }

    @Test public void publishedAndDraftWorkflowReferencesBlockDeletion() throws Exception {
        for (String name : Arrays.asList("Published", "Draft")) {
            Domain domain = new Domain();
            try (UndoTestFixture fixture = UndoTestFixture.create("admin", domain.handler)) {
                String operation = domain.create(fixture);
                JiraWorkflow workflow = mock(JiraWorkflow.class);
                when(workflow.getName()).thenReturn(name);
                FunctionDescriptor function = com.opensymphony.workflow.loader.DescriptorFactory.getFactory().createFunctionDescriptor();
                function.getArgs().put("field.name", "resolution");
                function.getArgs().put("field.value", "9");
                when(domain.workflows.getWorkflowsIncludingDrafts()).thenReturn(Collections.singletonList(workflow));
                when(domain.workflows.getPostFunctionsForWorkflow(workflow)).thenReturn(Collections.singletonMap(
                        com.opensymphony.workflow.loader.DescriptorFactory.getFactory().createActionDescriptor(),
                        Collections.<FunctionDescriptor>singletonList(function)));
                assertBlocked(fixture, operation, domain);
            }
        }
    }

    @Test public void referenceAddedAfterRollbackPreviewBlocksApply() throws Exception {
        Domain domain = new Domain();
        try (UndoTestFixture fixture = UndoTestFixture.create("admin", domain.handler)) {
            String operation = domain.create(fixture);
            Map<String,Object> preview = fixture.journal.rollback("admin", operation, null, null, true, null);
            assertEquals(preview.toString(), "READY", preview.get("status"));
            domain.issueReferences = 1L;
            assertEquals("BLOCKED", fixture.journal.rollback("admin", null, null, null, false, (String) preview.get("planId")).get("status"));
            assertNotNull(domain.created.get());
            verify(domain.delegator, never()).removeByAnd(eq("Resolution"), anyMap());
        }
    }

    @Test public void uncertainNativeCreationNeverDeletesByName() throws Exception {
        Domain domain = new Domain(); domain.hideResult = true;
        try (UndoTestFixture fixture = UndoTestFixture.create("admin", domain.handler)) {
            try { domain.create(fixture); fail("Missing native result must fail"); }
            catch (IllegalStateException expected) { }
            assertNotNull(domain.created.get());
            verify(domain.delegator, never()).removeByAnd(eq("Resolution"), anyMap());
            verify(domain.resolutions, never()).removeResolution(anyString(), any());
        }
    }

    @Test public void alreadyRemovedResolutionIsSafeAndDoesNotDeleteAnythingElse() throws Exception {
        Domain domain = new Domain();
        try (UndoTestFixture fixture = UndoTestFixture.create("admin", domain.handler)) {
            String operation = domain.create(fixture); domain.created.set(null);
            Map<String,Object> preview = fixture.journal.rollback("admin", operation, null, null, true, null);
            assertEquals("READY", preview.get("status"));
            assertEquals("ROLLED_BACK", fixture.journal.rollback("admin", null, null, null, false, (String) preview.get("planId")).get("status"));
            verify(domain.delegator, never()).removeByAnd(eq("Resolution"), anyMap());
        }
    }

    private static void assertBlocked(UndoTestFixture fixture, String operation, Domain domain) {
        Map<String,Object> preview = fixture.journal.rollback("admin", operation, null, null, true, null);
        assertEquals("BLOCKED", preview.get("status"));
        assertNotNull(domain.created.get());
        verify(domain.delegator, never()).removeByAnd(eq("Resolution"), anyMap());
        verify(domain.resolutions, never()).removeResolution(anyString(), any());
    }

    private static final class Domain {
        final ResolutionManager resolutions = mock(ResolutionManager.class);
        final OfBizDelegator delegator = mock(OfBizDelegator.class);
        final DelegatorInterface nativeDatabase = mock(DelegatorInterface.class);
        final ConstantsManager constants = mock(ConstantsManager.class);
        final WorkflowManager workflows = mock(WorkflowManager.class);
        final AtomicReference<Resolution> created = new AtomicReference<>();
        final CreatedResolutionUndoHandler handler = new CreatedResolutionUndoHandler(resolutions, delegator, workflows, constants);
        boolean hideResult;
        long issueReferences;
        Domain() throws Exception {
            when(delegator.getCountByAnd(anyString(), anyMap()))
                    .thenThrow(new IllegalArgumentException("IssueCount does not expose resolution"));
            when(delegator.getDelegatorInterface()).thenReturn(nativeDatabase);
            when(nativeDatabase.countByCondition(eq("Issue"), eq("id"), any(), isNull()))
                    .thenAnswer(call -> (int) issueReferences);
            when(resolutions.getResolutions()).thenAnswer(call -> created.get() == null ? Collections.emptyList() : Collections.singletonList(created.get()));
            when(resolutions.getResolution("9")).thenAnswer(call -> created.get());
            when(workflows.getWorkflowsIncludingDrafts()).thenReturn(Collections.emptyList());
            when(resolutions.createResolution(anyString(), anyString())).thenAnswer(call -> {
                Resolution value = resolution("9", call.getArgument(0), call.getArgument(1));
                created.set(value); return hideResult ? null : value;
            });
            when(delegator.removeByAnd(eq("Resolution"), anyMap())).thenAnswer(call -> { created.set(null); return 1; });
        }
        String create(UndoTestFixture fixture) {
            ResolutionServiceImpl service = new ResolutionServiceImpl(resolutions, fixture.guard, fixture.locks,
                    new ru.rasulov.common.impl.PreviewPlanExecutor(fixture.plans, new ActionPlanCodec()), new ActionPlanCodec(), fixture.journal, ru.rasulov.common.impl.TestLinks.links());
            CreateResolutionResponse preview = service.create(preview("Completed", "Finished"));
            String session = fixture.journal.createSession("admin");
            String set = fixture.journal.createChangeSet(session, "admin");
            String operation = fixture.journal.openOperation("create_resolution", "admin", session, set);
            CreateResolutionResponse result = fixture.journal.runOperation(operation, () -> service.create(apply(preview.getPlanId())));
            assertEquals(CreateResolutionResponse.Status.CREATED, result.getStatus()); return operation;
        }
    }
}
