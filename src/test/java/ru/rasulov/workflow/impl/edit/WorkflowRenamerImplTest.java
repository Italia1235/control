package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.bc.JiraServiceContext;
import com.atlassian.jira.bc.workflow.WorkflowService;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.InMemoryActionPlans;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.workflow.api.dto.edit.RenameWorkflowRequest;
import ru.rasulov.workflow.api.dto.edit.RenameWorkflowResponse;
import ru.rasulov.workflow.api.dto.edit.RenameWorkflowResponse.ErrorCode;
import ru.rasulov.workflow.api.dto.edit.RenameWorkflowResponse.Status;

import java.util.Arrays;
import java.util.Collections;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rename goes through Jira's own rename, only for an unused workflow, and is proven by reading both names back. */
public class WorkflowRenamerImplTest {

    private final ApplicationUser user = mock(ApplicationUser.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final WorkflowManager manager = mock(WorkflowManager.class);
    private final WorkflowService service = mock(WorkflowService.class);
    private final OfBizDelegator delegator = mock(OfBizDelegator.class);
    private final WorkflowInspector inspector = mock(WorkflowInspector.class);
    private final JiraWorkflow stored = mock(JiraWorkflow.class);
    private WorkflowDescriptor descriptor;
    private boolean active;
    private boolean renamed;
    private long schemeId = 7;
    private int revision;
    private UndoTestFixture journalFixture;
    private WorkflowRenamerImpl renamer;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        journalFixture = UndoTestFixture.create("admin");
        when(user.getKey()).thenReturn("admin");
        when(user.getUsername()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(user);
        when(lock.run(anyString(), anyString(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
        descriptor = EditFixtures.workflow();
        when(stored.getName()).thenReturn("Old");
        when(stored.getDescription()).thenReturn("Support");
        when(stored.getDescriptor()).thenReturn(descriptor);
        when(inspector.snapshot("Old")).thenAnswer(call -> new WorkflowInspector.Snapshot("Old", stored, null, active,
                false, "rev-" + revision));
        when(inspector.consumers(any())).thenAnswer(call -> new WorkflowInspector.Consumers(
                new TreeSet<Long>(Collections.singleton(schemeId)), Arrays.asList("Scheme [" + schemeId + "]"),
                new TreeMap<String, String>()));
        when(manager.getWorkflow("Old")).thenAnswer(call -> renamed ? null : stored);
        when(manager.getWorkflow("New")).thenAnswer(call -> renamed ? stored : null);
        when(manager.getWorkflowClone("Old")).thenReturn(stored);
        when(delegator.findByCondition(eq("OSWorkflowEntry"), any(), any(), any()))
                .thenReturn(Collections.<GenericValue>emptyList());
        doAnswer(call -> {
            renamed = true;
            return null;
        }).when(manager).updateWorkflowNameAndDescription(any(ApplicationUser.class), any(JiraWorkflow.class),
                anyString(), anyString());
        renamer = new WorkflowRenamerImpl(guard, lock, manager, service, delegator, inspector,
                new ru.rasulov.common.impl.PreviewPlanExecutor(InMemoryActionPlans.create(), new ActionPlanCodec()),
                new ActionPlanCodec(), journalFixture.journal);
    }

    @After
    public void closeJournal() {
        journalFixture.close();
    }

    @Test
    public void previewWritesNothingAndApplyRenamesOnceAndProvesItByReadBack() {
        RenameWorkflowResponse preview = renamer.rename(new RenameWorkflowRequest(true, "Old", "New", null));
        assertEquals(Status.WOULD_RENAME, preview.getStatus());
        assertNotNull(preview.getPlanId());
        assertEquals(Collections.singletonList("Scheme [7]"), preview.getSchemes());
        verify(manager, never()).updateWorkflowNameAndDescription(any(ApplicationUser.class), any(JiraWorkflow.class),
                anyString(), anyString());

        RenameWorkflowResponse done = renamer.rename(new RenameWorkflowRequest(false, null, null, preview.getPlanId()));

        assertEquals(Status.RENAMED, done.getStatus());
        assertEquals("New", done.getNewName());
        verify(manager, times(1)).updateWorkflowNameAndDescription(any(ApplicationUser.class), any(JiraWorkflow.class),
                eq("New"), eq("Support"));
    }

    @Test
    public void anActiveWorkflowIsNotRenamedAndNoCopyIsOffered() {
        active = true;

        RenameWorkflowResponse refused = renamer.rename(new RenameWorkflowRequest(true, "Old", "New", null));

        assertEquals(ErrorCode.ACTIVE_WORKFLOW, refused.getErrorCode());
        assertNull(refused.getPlanId());
        assertTrue(refused.getMessage(), refused.getMessage().contains("not a rename"));
    }

    @Test
    public void issuesWithEntriesUnderTheOldNameBlockTheRename() {
        GenericValue entry = mock(GenericValue.class);
        when(delegator.findByCondition(eq("OSWorkflowEntry"), any(), any(), any()))
                .thenReturn(Collections.singletonList(entry));

        assertEquals(ErrorCode.IN_USE, renamer.rename(new RenameWorkflowRequest(true, "Old", "New", null)).getErrorCode());
    }

    @Test
    public void jirasOwnNameValidationDecidesAboutATakenName() {
        doAnswer(call -> {
            ((JiraServiceContext) call.getArgument(0)).getErrorCollection().addError("newWorkflowName", "exists");
            return null;
        }).when(service).validateUpdateWorkflowNameAndDescription(any(JiraServiceContext.class),
                any(JiraWorkflow.class), anyString());

        RenameWorkflowResponse refused = renamer.rename(new RenameWorkflowRequest(true, "Old", "Taken", null));

        assertEquals(ErrorCode.NAME_TAKEN, refused.getErrorCode());
        assertNull(refused.getPlanId());
    }

    @Test
    public void aSchemeThatChangedAfterThePreviewMakesThePlanStale() {
        RenameWorkflowResponse preview = renamer.rename(new RenameWorkflowRequest(true, "Old", "New", null));
        schemeId = 8;

        RenameWorkflowResponse result = renamer.rename(new RenameWorkflowRequest(false, null, null, preview.getPlanId()));

        assertEquals(ErrorCode.PREVIEW_STALE, result.getErrorCode());
        verify(manager, never()).updateWorkflowNameAndDescription(any(ApplicationUser.class), any(JiraWorkflow.class),
                anyString(), anyString());
    }

    @Test
    public void aRenameThatDoesNotReadBackIsUncertainAndNeverRepeated() {
        doAnswer(call -> null).when(manager).updateWorkflowNameAndDescription(any(ApplicationUser.class),
                any(JiraWorkflow.class), anyString(), anyString());
        RenameWorkflowResponse preview = renamer.rename(new RenameWorkflowRequest(true, "Old", "New", null));

        RenameWorkflowResponse result = renamer.rename(new RenameWorkflowRequest(false, null, null, preview.getPlanId()));

        assertEquals(ErrorCode.JIRA_ERROR, result.getErrorCode());
        assertTrue(result.getMessage(), result.getMessage().contains("uncertain"));
        assertEquals(ErrorCode.PREVIEW_STALE, renamer.rename(new RenameWorkflowRequest(false, null, null,
                preview.getPlanId())).getErrorCode());
        verify(manager, times(1)).updateWorkflowNameAndDescription(any(ApplicationUser.class), any(JiraWorkflow.class),
                anyString(), anyString());
    }

    @Test
    public void theSameNameIsUnchanged() {
        assertEquals(Status.UNCHANGED, renamer.rename(new RenameWorkflowRequest(true, "Old", "Old", null)).getStatus());
    }
}
