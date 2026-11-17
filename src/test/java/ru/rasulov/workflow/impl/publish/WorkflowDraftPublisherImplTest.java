package ru.rasulov.workflow.impl.publish;

import com.atlassian.jira.bc.JiraServiceContext;
import com.atlassian.jira.bc.workflow.WorkflowService;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowScheme;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.jira.workflow.WorkflowUtil;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.InMemoryActionPlans;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.workflow.api.dto.publish.PublishErrorCode;
import ru.rasulov.workflow.api.dto.publish.PublishStatus;
import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftRequest;
import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftResponse;
import ru.rasulov.workflow.impl.edit.StepOccupancy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Preview of a draft publication never writes; apply publishes exactly the confirmed draft through Jira, once. */
public class WorkflowDraftPublisherImplTest {

    private static final String NAME = "Lot Buy";

    private final ApplicationUser user = mock(ApplicationUser.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final WorkflowManager workflowManager = mock(WorkflowManager.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final WorkflowSchemeManager schemeManager = mock(WorkflowSchemeManager.class);
    private final OfBizDelegator delegator = mock(OfBizDelegator.class);
    private final StepOccupancy stepOccupancy = mock(StepOccupancy.class);
    private final Status open = status("1", "Open");
    private final Status progress = status("3", "In Progress");
    private final Status review = status("10001", "Review");
    private final JiraWorkflow active = mock(JiraWorkflow.class);
    private final JiraWorkflow draft = mock(JiraWorkflow.class);
    private final AssignableWorkflowScheme lotScheme = scheme(16100L);
    private final AssignableWorkflowScheme labScheme = scheme(16300L);
    private final List<WorkflowScheme> consumers = new ArrayList<WorkflowScheme>();
    private final JiraWorkflow[] currentDraft = new JiraWorkflow[1];
    private UndoTestFixture journalFixture;
    private WorkflowDraftPublisherImpl publisher;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        journalFixture = UndoTestFixture.create("admin");
        when(user.getKey()).thenReturn("admin");
        when(user.getUsername()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(user);
        when(lock.run(anyString(), anyString(), any())).thenAnswer(call ->
                ((Supplier<Object>) call.getArgument(2)).get());

        WorkflowDescriptor activeDescriptor = descriptor(false);
        WorkflowDescriptor draftDescriptor = descriptor(true);
        when(active.getName()).thenReturn(NAME);
        when(active.getDescriptor()).thenReturn(activeDescriptor);
        when(active.getLinkedStatusObjects()).thenReturn(Arrays.asList(open, progress));
        when(active.hasDraftWorkflow()).thenReturn(true);
        when(draft.getName()).thenReturn(NAME);
        when(draft.isDraftWorkflow()).thenReturn(true);
        when(draft.getDescriptor()).thenReturn(draftDescriptor);
        when(draft.getLinkedStatusObjects()).thenReturn(Arrays.asList(open, progress, review));
        when(draft.getUpdatedDate()).thenReturn(new Date(1000L));
        currentDraft[0] = draft;
        when(workflowManager.getWorkflow(NAME)).thenReturn(active);
        when(workflowManager.getDraftWorkflow(NAME)).thenAnswer(call -> currentDraft[0]);
        when(workflowManager.getWorkflows()).thenReturn(Collections.singletonList(active));

        consumers.add(lotScheme);
        consumers.add(labScheme);
        when(schemeManager.getSchemesForWorkflowIncludingDrafts(active)).thenAnswer(call ->
                new ArrayList<WorkflowScheme>(consumers));
        Project lot = project("LOT", "Lot");
        Project lab = project("LAB", "Lab");
        when(schemeManager.getProjectsUsing(lotScheme)).thenReturn(Collections.singletonList(lot));
        when(schemeManager.getProjectsUsing(labScheme)).thenReturn(Collections.singletonList(lab));

        GenericValue row = mock(GenericValue.class);
        when(row.getLong("id")).thenReturn(10L);
        when(delegator.findByCondition(eq("Workflow"), any(), any(), any())).thenReturn(Collections.singletonList(row));

        // Jira's own publication: the draft becomes the active descriptor and the draft is gone.
        doAnswer(call -> {
            when(active.getDescriptor()).thenReturn(draftDescriptor);
            when(active.getLinkedStatusObjects()).thenReturn(Arrays.asList(open, progress, review));
            when(active.hasDraftWorkflow()).thenReturn(false);
            currentDraft[0] = null;
            return null;
        }).when(workflowService).overwriteActiveWorkflow(any(JiraServiceContext.class), eq(NAME));

        publisher = new WorkflowDraftPublisherImpl(guard, lock, workflowManager, workflowService, schemeManager,
                new ru.rasulov.common.impl.PreviewPlanExecutor(InMemoryActionPlans.create(), new ActionPlanCodec()),
                new ActionPlanCodec(), journalFixture.journal, delegator, stepOccupancy);
    }

    @After
    public void closeJournal() {
        journalFixture.close();
    }

    @Test
    public void previewShowsTheDraftChangesAndEveryAffectedProjectWithoutWriting() {
        PublishWorkflowDraftResponse preview = publisher.publish(preview(NAME));

        assertEquals(PublishStatus.WOULD_PUBLISH, preview.getStatus());
        assertTrue(preview.isDryRun());
        assertEquals(NAME, preview.getWorkflow());
        assertEquals(Collections.singletonList("Review"), preview.getChanges().getAddedStatuses());
        assertTrue(preview.getChanges().getRemovedStatuses().isEmpty());
        assertEquals(Collections.singletonList("Send to review"), preview.getChanges().getAddedTransitions());
        assertTrue(preview.getChanges().getRemovedTransitions().isEmpty());
        assertEquals(Collections.singletonList("Start"), preview.getChanges().getChangedTransitions());
        assertTrue(preview.getChanges().isDescriptorChanged());
        assertEquals(Integer.valueOf(2), preview.getAffectedSchemes());
        assertEquals(Integer.valueOf(2), preview.getAffectedProjects());
        assertEquals(Arrays.asList("Lab", "Lot"), preview.getAffectedProjectNames());
        assertTrue(preview.getMessage(), preview.getMessage().contains("every project"));
        assertTrue(preview.getMessage(), preview.getMessage().contains("clone_project_workflows"));
        assertNotNull(preview.getPlanId());
        verify(workflowService, never()).overwriteActiveWorkflow(any(JiraServiceContext.class), anyString());
        verify(workflowManager, never()).overwriteActiveWorkflow(any(ApplicationUser.class), anyString());
        verify(workflowManager, never()).deleteDraftWorkflow(anyString());
    }

    @Test
    public void aWorkflowWithoutADraftNeedsNoPublicationAndGetsNoPlan() {
        currentDraft[0] = null;

        PublishWorkflowDraftResponse preview = publisher.publish(preview(NAME));

        assertEquals(PublishStatus.NO_DRAFT, preview.getStatus());
        assertNull(preview.getPlanId());
        verify(workflowService, never()).overwriteActiveWorkflow(any(JiraServiceContext.class), anyString());
    }

    @Test
    public void anUnknownWorkflowIsRefusedWithTheWorkflowsThatHaveDrafts() {
        PublishWorkflowDraftResponse refused = publisher.publish(preview("Lot Bye"));

        assertEquals(PublishStatus.FAILED, refused.getStatus());
        assertEquals(PublishErrorCode.UNKNOWN_WORKFLOW, refused.getErrorCode());
        assertEquals(Collections.singletonList(NAME), refused.getSuggestions());
        assertNull(refused.getPlanId());
    }

    @Test
    public void aRefusalByJiraIsShownInThePreviewAndGetsNoPlan() {
        doAnswer(call -> {
            ((JiraServiceContext) call.getArgument(0)).getErrorCollection().addErrorMessage("Draft is invalid.");
            return null;
        }).when(workflowService).validateOverwriteWorkflow(any(JiraServiceContext.class), eq(NAME));

        PublishWorkflowDraftResponse refused = publisher.publish(preview(NAME));

        assertEquals(PublishStatus.FAILED, refused.getStatus());
        assertEquals(PublishErrorCode.PUBLISH_REFUSED, refused.getErrorCode());
        assertTrue(refused.getMessage(), refused.getMessage().contains("Draft is invalid."));
        assertNull(refused.getPlanId());
        verify(workflowService, never()).overwriteActiveWorkflow(any(JiraServiceContext.class), anyString());
    }

    @Test
    public void anOccupiedRemovedStepIsRefusedBeforeNativeValidationAndNothingIsPublished() throws Exception {
        draftRemovesReview();
        when(stepOccupancy.occupied(NAME, Collections.singleton(3))).thenReturn(Collections.singletonMap(3, 2L));

        PublishWorkflowDraftResponse refused = publisher.publish(preview(NAME));

        assertEquals(PublishStatus.FAILED, refused.getStatus());
        assertEquals(PublishErrorCode.PUBLISH_REFUSED, refused.getErrorCode());
        assertTrue(refused.getMessage(), refused.getMessage().contains("3=2"));
        assertTrue(refused.getMessage(), refused.getMessage().contains("Nothing was changed"));
        assertNull(refused.getPlanId());
        verify(stepOccupancy).occupied(NAME, Collections.singleton(3));
        verifyNoInteractions(workflowService);
        verify(workflowManager, never()).overwriteActiveWorkflow(any(ApplicationUser.class), anyString());
        verify(workflowManager, never()).deleteDraftWorkflow(anyString());
    }

    @Test
    public void anEmptyRemovedStepStillRequiresNativeValidation() throws Exception {
        draftRemovesReview();
        when(stepOccupancy.occupied(NAME, Collections.singleton(3))).thenReturn(Collections.singletonMap(3, 0L));

        PublishWorkflowDraftResponse preview = publisher.publish(preview(NAME));

        assertEquals(PublishStatus.WOULD_PUBLISH, preview.getStatus());
        assertNotNull(preview.getPlanId());
        assertEquals(Collections.singletonList("Review"), preview.getChanges().getRemovedStatuses());
        verify(stepOccupancy).occupied(NAME, Collections.singleton(3));
        verify(workflowService).validateOverwriteWorkflow(any(JiraServiceContext.class), eq(NAME));
        verify(workflowService, never()).overwriteActiveWorkflow(any(JiraServiceContext.class), anyString());
    }

    @Test
    public void anIssueEnteringARemovedStepAfterPreviewMakesThePlanStaleWithoutPublishing() throws Exception {
        draftRemovesReview();
        when(stepOccupancy.occupied(NAME, Collections.singleton(3)))
                .thenReturn(Collections.singletonMap(3, 0L), Collections.singletonMap(3, 1L));
        PublishWorkflowDraftResponse preview = publisher.publish(preview(NAME));
        assertEquals(PublishStatus.WOULD_PUBLISH, preview.getStatus());

        PublishWorkflowDraftResponse stale = publisher.publish(apply(preview));

        assertEquals(PublishErrorCode.PREVIEW_STALE, stale.getErrorCode());
        verify(stepOccupancy, times(2)).occupied(NAME, Collections.singleton(3));
        verify(workflowService, times(1)).validateOverwriteWorkflow(any(JiraServiceContext.class), eq(NAME));
        verify(workflowService, never()).overwriteActiveWorkflow(any(JiraServiceContext.class), anyString());
    }

    @Test
    public void aConfirmedPlanPublishesTheDraftOnceThroughJira() {
        PublishWorkflowDraftResponse preview = publisher.publish(preview(NAME));

        PublishWorkflowDraftResponse published = publisher.publish(apply(preview));

        assertEquals(PublishStatus.PUBLISHED, published.getStatus());
        assertFalse(published.isDryRun());
        assertEquals(NAME, published.getWorkflow());
        assertEquals(Integer.valueOf(2), published.getAffectedProjects());
        verify(workflowService, times(1)).overwriteActiveWorkflow(any(JiraServiceContext.class), eq(NAME));
    }

    @Test
    public void aPlanIsSingleUse() {
        PublishWorkflowDraftResponse preview = publisher.publish(preview(NAME));
        publisher.publish(apply(preview));

        assertEquals(PublishErrorCode.PREVIEW_STALE, publisher.publish(apply(preview)).getErrorCode());
        verify(workflowService, times(1)).overwriteActiveWorkflow(any(JiraServiceContext.class), eq(NAME));
    }

    @Test
    public void aDraftEditedAfterThePreviewIsNotPublished() {
        PublishWorkflowDraftResponse preview = publisher.publish(preview(NAME));
        when(draft.getUpdatedDate()).thenReturn(new Date(2000L));

        PublishWorkflowDraftResponse stale = publisher.publish(apply(preview));

        assertEquals(PublishErrorCode.PREVIEW_STALE, stale.getErrorCode());
        verify(workflowService, never()).overwriteActiveWorkflow(any(JiraServiceContext.class), anyString());
    }

    @Test
    public void aNewConsumerAfterThePreviewMakesThePlanStale() {
        PublishWorkflowDraftResponse preview = publisher.publish(preview(NAME));
        AssignableWorkflowScheme another = scheme(16500L);
        Project newcomer = project("NEW", "New");
        when(schemeManager.getProjectsUsing(another)).thenReturn(Collections.singletonList(newcomer));
        consumers.add(another);

        assertEquals(PublishErrorCode.PREVIEW_STALE, publisher.publish(apply(preview)).getErrorCode());
        verify(workflowService, never()).overwriteActiveWorkflow(any(JiraServiceContext.class), anyString());
    }

    @Test
    public void anErrorReportedByJiraWhilePublishingIsNotPublished() {
        PublishWorkflowDraftResponse preview = publisher.publish(preview(NAME));
        doAnswer(call -> {
            ((JiraServiceContext) call.getArgument(0)).getErrorCollection().addErrorMessage("Workflow is locked.");
            return null;
        }).when(workflowService).overwriteActiveWorkflow(any(JiraServiceContext.class), eq(NAME));

        PublishWorkflowDraftResponse failed = publisher.publish(apply(preview));

        assertEquals(PublishStatus.FAILED, failed.getStatus());
        assertEquals(PublishErrorCode.JIRA_ERROR, failed.getErrorCode());
        assertTrue(failed.getMessage(), failed.getMessage().contains("Workflow is locked."));
    }

    @Test
    public void aPublicationJiraDidNotCarryOutIsNotReportedAsPublished() {
        PublishWorkflowDraftResponse preview = publisher.publish(preview(NAME));
        doAnswer(call -> null).when(workflowService).overwriteActiveWorkflow(any(JiraServiceContext.class), eq(NAME));

        PublishWorkflowDraftResponse failed = publisher.publish(apply(preview));

        assertEquals(PublishStatus.FAILED, failed.getStatus());
        assertEquals(PublishErrorCode.JIRA_ERROR, failed.getErrorCode());
        assertTrue(failed.getMessage(), failed.getMessage().contains("do not repeat"));
    }

    @Test
    public void aNonAdministratorIsRefusedBeforeJiraIsRead() {
        when(guard.requireAdministrator()).thenThrow(new AccessException("Only Jira administrators.", false));

        try {
            publisher.publish(preview(NAME));
            fail("expected AccessException");
        } catch (AccessException expected) {
            verifyNoInteractions(workflowManager, workflowService, schemeManager);
        }
    }

    @Test
    public void theModeAndTheApplyShapeAreExplicit() {
        for (PublishWorkflowDraftRequest request : Arrays.asList(
                new PublishWorkflowDraftRequest(null, NAME, null),
                new PublishWorkflowDraftRequest(true, NAME, "plan"),
                new PublishWorkflowDraftRequest(false, NAME, "plan"),
                new PublishWorkflowDraftRequest(false, null, null))) {
            try {
                publisher.publish(request);
                fail("expected InvalidRequestException");
            } catch (InvalidRequestException expected) {
                verifyNoInteractions(workflowService);
            }
        }
        PublishWorkflowDraftResponse blank = publisher.publish(preview("  "));
        assertEquals(PublishErrorCode.VALIDATION_ERROR, blank.getErrorCode());
    }

    private void draftRemovesReview() throws Exception {
        when(active.getDescriptor()).thenReturn(descriptor(true));
        when(active.getLinkedStatusObjects()).thenReturn(Arrays.asList(open, progress, review));
        when(draft.getDescriptor()).thenReturn(descriptor(false));
        when(draft.getLinkedStatusObjects()).thenReturn(Arrays.asList(open, progress));
    }

    private static PublishWorkflowDraftRequest preview(String workflow) {
        return new PublishWorkflowDraftRequest(true, workflow, null);
    }

    private static PublishWorkflowDraftRequest apply(PublishWorkflowDraftResponse preview) {
        return new PublishWorkflowDraftRequest(false, null, preview.getPlanId());
    }

    private static Status status(String id, String name) {
        Status status = mock(Status.class);
        when(status.getId()).thenReturn(id);
        when(status.getName()).thenReturn(name);
        return status;
    }

    private static AssignableWorkflowScheme scheme(long id) {
        AssignableWorkflowScheme scheme = mock(AssignableWorkflowScheme.class);
        when(scheme.getId()).thenReturn(id);
        return scheme;
    }

    private static Project project(String key, String name) {
        Project project = mock(Project.class);
        when(project.getKey()).thenReturn(key);
        when(project.getName()).thenReturn(name);
        return project;
    }

    /** Open -Start-> In Progress; the draft also changes Start and adds Review with Send to review. */
    private static WorkflowDescriptor descriptor(boolean draft) throws Exception {
        String startMeta = draft ? "<meta name=\"jira.description\">Begin work</meta>" : "";
        String reviewStep = draft
                ? "<step id=\"3\" name=\"Review\"><meta name=\"jira.status.id\">10001</meta></step>" : "";
        String reviewAction = draft
                ? "<action id=\"21\" name=\"Send to review\"><results>"
                + "<unconditional-result old-status=\"null\" status=\"null\" step=\"3\"/></results></action>" : "";
        return WorkflowUtil.convertXMLtoWorkflowDescriptor(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE workflow PUBLIC \"-//OpenSymphony Group//DTD OSWorkflow 2.8//EN\" "
                + "\"http://www.opensymphony.com/osworkflow/workflow_2_8.dtd\">"
                + "<workflow><initial-actions><action id=\"1\" name=\"Create\"><results>"
                + "<unconditional-result old-status=\"null\" status=\"open\" step=\"1\"/></results></action>"
                + "</initial-actions><steps>"
                + "<step id=\"1\" name=\"Open\"><meta name=\"jira.status.id\">1</meta><actions>"
                + "<action id=\"11\" name=\"Start\">" + startMeta + "<results>"
                + "<unconditional-result old-status=\"null\" status=\"null\" step=\"2\"/></results></action>"
                + "</actions></step>"
                + "<step id=\"2\" name=\"In Progress\"><meta name=\"jira.status.id\">3</meta>"
                + (draft ? "<actions>" + reviewAction + "</actions>" : "") + "</step>"
                + reviewStep + "</steps></workflow>");
    }
}
