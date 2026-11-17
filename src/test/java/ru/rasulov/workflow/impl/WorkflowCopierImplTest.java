package ru.rasulov.workflow.impl;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.opensymphony.workflow.loader.DescriptorFactory;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.InMemoryActionPlans;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.TestLinks;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.workflow.api.dto.CopyWorkflowRequest;
import ru.rasulov.workflow.api.dto.CopyWorkflowResponse;
import ru.rasulov.workflow.api.dto.WorkflowResultStatus;

import java.util.Arrays;
import java.util.Collections;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class WorkflowCopierImplTest {
    private final WorkflowManager workflows = mock(WorkflowManager.class);
    private final OfBizDelegator delegator = mock(OfBizDelegator.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final WorkflowWriter writer = mock(WorkflowWriter.class);
    private final ApplicationUser user = mock(ApplicationUser.class);
    private final JiraWorkflow source = mock(JiraWorkflow.class);
    private final JiraWorkflow copy = mock(JiraWorkflow.class);
    private WorkflowDescriptor descriptor;
    private UndoTestFixture journal;
    private WorkflowCopierImpl copier;

    @Before
    public void setUp() throws Exception {
        journal = UndoTestFixture.create("admin");
        when(user.getKey()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(user);
        when(lock.run(anyString(), anyString(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(2)).get());
        descriptor = com.atlassian.jira.workflow.WorkflowUtil.convertXMLtoWorkflowDescriptor(
                WorkflowDescriptor.XML_HEADER + WorkflowDescriptor.DOCTYPE_DECL
                + "<workflow><meta name=\"jira.description\">Original description</meta>"
                + "<initial-actions><action id=\"1\" name=\"Create\"><results>"
                + "<unconditional-result old-status=\"Not Done\" status=\"Done\" step=\"1\"/>"
                + "</results></action></initial-actions><steps><step id=\"1\" name=\"Open\"/></steps></workflow>");
        when(source.getName()).thenReturn("Source");
        when(source.getDescription()).thenReturn("Original description");
        when(source.getDescriptor()).thenReturn(descriptor);
        when(workflows.getWorkflow("Source")).thenReturn(source);
        when(workflows.getWorkflows()).thenReturn(Collections.singletonList(source));
        GenericValue identity = mock(GenericValue.class);
        when(identity.getLong("id")).thenReturn(10L);
        when(delegator.findByCondition(eq("Workflow"), any(), any(), any())).thenReturn(Collections.singletonList(identity));
        when(copy.getName()).thenReturn("Copy");
        when(copy.getDescriptor()).thenReturn(com.atlassian.jira.workflow.WorkflowUtil.convertXMLtoWorkflowDescriptor(
                com.atlassian.jira.workflow.WorkflowUtil.convertDescriptorToXML(descriptor)));
        when(writer.copyCreated(eq(user), eq(source), eq("Copy"), nullable(String.class))).thenAnswer(call -> {
            String description = call.getArgument(3);
            if (description != null) copy.getDescriptor().getMetaAttributes().put("jira.description", description);
            return copy;
        });
        copier = new WorkflowCopierImpl(workflows, delegator, guard, lock, writer,
                new ru.rasulov.common.impl.PreviewPlanExecutor(InMemoryActionPlans.create(), new ActionPlanCodec()),
                new ActionPlanCodec(), journal.journal, TestLinks.links());
    }

    @After
    public void closeJournal() { journal.close(); }

    private CopyWorkflowRequest preview() { return new CopyWorkflowRequest(true, "Source", " Copy ", null, null); }
    private CopyWorkflowResponse apply(CopyWorkflowResponse preview) {
        return copier.copy(new CopyWorkflowRequest(false, null, null, null, preview.getPlanId()));
    }

    @Test
    public void previewDoesNotWriteAndInheritsDescription() {
        CopyWorkflowResponse result = copier.copy(preview());
        assertEquals(WorkflowResultStatus.WOULD_CREATE, result.getStatus());
        assertEquals("Copy", result.getName());
        assertEquals("Original description", result.getDescription());
        assertEquals(1, result.getSteps());
        assertNotNull(result.getPlanId());
        verifyNoInteractions(writer, lock);
    }

    @Test
    public void occupiedNameIsRejectedWithoutCaseDistinction() {
        JiraWorkflow occupied = mock(JiraWorkflow.class);
        when(occupied.getName()).thenReturn("COPY");
        when(workflows.getWorkflows()).thenReturn(Arrays.asList(source, occupied));
        CopyWorkflowResponse result = copier.copy(preview());
        assertEquals("NAME_TAKEN", result.getErrorCode().name());
        assertTrue(result.getSuggestions().contains("COPY"));
        assertNull(result.getPlanId());
        verifyNoInteractions(writer);
    }

    @Test
    public void unknownSourceDoesNotProducePlan() {
        when(workflows.getWorkflow("Source")).thenReturn(null);
        when(workflows.getWorkflows()).thenReturn(Collections.emptyList());
        CopyWorkflowResponse result = copier.copy(preview());
        assertEquals("UNKNOWN_WORKFLOW", result.getErrorCode().name());
        assertNull(result.getPlanId());
        verifyNoInteractions(writer);
    }

    @Test
    public void sourceDriftConsumesPlanWithoutWriting() {
        CopyWorkflowResponse preview = copier.copy(preview());
        descriptor.getSteps().clear();
        assertEquals("PREVIEW_STALE", apply(preview).getErrorCode().name());
        assertEquals("PREVIEW_STALE", apply(preview).getErrorCode().name());
        verifyNoInteractions(writer);
    }

    @Test(expected = InvalidRequestException.class)
    public void applyRejectsExtraDetails() {
        copier.copy(new CopyWorkflowRequest(false, "Source", null, null, "plan"));
    }

    @Test
    public void sourceDraftIsWarnedAboutButMainVersionIsCopied() {
        JiraWorkflow draft = mock(JiraWorkflow.class);
        when(workflows.getDraftWorkflow("Source")).thenReturn(draft);
        CopyWorkflowResponse preview = copier.copy(preview());
        assertTrue(preview.isSourceHasDraft());
        assertFalse(preview.getWarnings().isEmpty());
        assertEquals(WorkflowResultStatus.CREATED, apply(preview).getStatus());
        verify(writer).copyCreated(user, source, "Copy", "Original description");
        verifyNoInteractions(draft);
    }

    @Test
    public void newlyAppearedDraftInvalidatesPreview() {
        CopyWorkflowResponse preview = copier.copy(preview());
        when(workflows.getDraftWorkflow("Source")).thenReturn(mock(JiraWorkflow.class));
        assertEquals("PREVIEW_STALE", apply(preview).getErrorCode().name());
        verifyNoInteractions(writer);
    }

    @Test
    public void differentCopiedDefinitionReportsErrorWithoutRetryingWrite() {
        when(copy.getDescriptor()).thenReturn(DescriptorFactory.getFactory().createWorkflowDescriptor());
        CopyWorkflowResponse result = apply(copier.copy(preview()));
        assertEquals(WorkflowResultStatus.FAILED, result.getStatus());
        assertEquals("JIRA_ERROR", result.getErrorCode().name());
        assertNotNull(result.getReport());
        verify(writer, times(1)).copyCreated(user, source, "Copy", "Original description");
    }

    @Test
    public void explicitDescriptionAndSharedCreationLockAreUsed() {
        CopyWorkflowResponse result = apply(copier.copy(new CopyWorkflowRequest(true, "Source", "Copy", "Custom", null)));
        assertEquals(WorkflowResultStatus.CREATED, result.getStatus());
        verify(writer).copyCreated(user, source, "Copy", "Custom");
        verify(lock).run(eq(ru.rasulov.workflow.impl.edit.WorkflowConfigurerImpl.CREATE_LOCK), eq("workflows"), any());
        assertEquals("Custom", copy.getDescriptor().getMetaAttributes().get("jira.description"));
        assertEquals("Original description", descriptor.getMetaAttributes().get("jira.description"));
    }

    @Test
    public void absentDescriptionIsCopiedWithoutIntroducingNullMetadata() {
        when(source.getDescription()).thenReturn(null);
        descriptor.getMetaAttributes().remove("jira.description");
        copy.getDescriptor().getMetaAttributes().remove("jira.description");

        CopyWorkflowResponse result = apply(copier.copy(preview()));

        assertEquals(WorkflowResultStatus.CREATED, result.getStatus());
        assertNull(result.getDescription());
        assertFalse(descriptor.getMetaAttributes().containsKey("jira.description"));
        assertFalse(copy.getDescriptor().getMetaAttributes().containsKey("jira.description"));
    }

    @Test
    public void transitionCountExcludesCreateButIncludesLocalGlobalAndCommonActions() throws Exception {
        WorkflowDescriptor graph = com.atlassian.jira.workflow.WorkflowUtil.convertXMLtoWorkflowDescriptor(
                WorkflowDescriptor.XML_HEADER + WorkflowDescriptor.DOCTYPE_DECL
                + "<workflow><initial-actions><action id=\"1\" name=\"Create\"><results>"
                + "<unconditional-result old-status=\"Not Done\" status=\"Done\" step=\"1\"/></results>"
                + "</action></initial-actions><global-actions><action id=\"21\" name=\"Global\"><results>"
                + "<unconditional-result old-status=\"Not Done\" status=\"Done\" step=\"1\"/></results>"
                + "</action></global-actions><common-actions><action id=\"31\" name=\"Common\"><results>"
                + "<unconditional-result old-status=\"Not Done\" status=\"Done\" step=\"1\"/></results>"
                + "</action></common-actions><steps><step id=\"1\" name=\"Open\"><actions>"
                + "<action id=\"11\" name=\"Local\"><results><unconditional-result old-status=\"Not Done\" "
                + "status=\"Done\" step=\"1\"/></results></action></actions></step></steps></workflow>");
        when(source.getDescriptor()).thenReturn(graph);

        assertEquals(3, copier.copy(preview()).getTransitions());
    }
}
