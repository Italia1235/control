package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.bc.ServiceOutcome;
import com.atlassian.jira.bc.ServiceResultImpl;
import com.atlassian.jira.bc.config.StatusService;
import com.atlassian.jira.config.StatusCategoryManager;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.issue.status.category.StatusCategory;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.SimpleErrorCollection;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.InMemoryActionPlans;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.workflow.api.dto.edit.UpdateStatusRequest;
import ru.rasulov.workflow.api.dto.edit.UpdateStatusResponse;
import ru.rasulov.workflow.api.dto.edit.UpdateStatusResponse.ErrorCode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A status is global: the preview names every workflow that shares it and the apply writes it once. */
public class StatusUpdaterImplTest {

    private final ApplicationUser user = mock(ApplicationUser.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final StatusService service = mock(StatusService.class);
    private final StatusCategoryManager categories = mock(StatusCategoryManager.class);
    private final WorkflowManager manager = mock(WorkflowManager.class);
    private final WorkflowInspector inspector = mock(WorkflowInspector.class);
    private final StatusCategory todo = category("new");
    private final StatusCategory done = category("done");
    private final Status status = mock(Status.class);
    private final List<JiraWorkflow> workflows = new ArrayList<JiraWorkflow>();
    private String name = "Review";
    private StatusCategory current = todo;
    private UndoTestFixture journalFixture;
    private StatusUpdaterImpl updater;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        journalFixture = UndoTestFixture.create("admin");
        when(user.getKey()).thenReturn("admin");
        when(user.getUsername()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(user);
        when(lock.run(anyString(), anyString(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
        when(status.getId()).thenAnswer(call -> "10001");
        when(status.getName()).thenAnswer(call -> name);
        when(status.getDescription()).thenReturn("");
        when(status.getIconUrl()).thenReturn("/i.gif");
        when(status.getStatusCategory()).thenAnswer(call -> current);
        when(service.getStatusById(user, "10001")).thenReturn(status);
        when(categories.getStatusCategoryByKey("done")).thenReturn(done);
        when(categories.getStatusCategoryByKey("new")).thenReturn(todo);
        when(service.validateEditStatus(any(), any(), anyString(), any(), any(), any()))
                .thenReturn(new ServiceResultImpl(new SimpleErrorCollection()));
        ServiceOutcome<Status> ok = mock(ServiceOutcome.class);
        when(ok.isValid()).thenReturn(true);
        when(service.editStatus(any(), any(), anyString(), any(), any(), any())).thenAnswer(call -> {
            name = call.getArgument(2);
            current = call.getArgument(5);
            return ok;
        });
        workflows.add(workflow("Support", false));
        workflows.add(workflow("Billing", false));
        when(manager.getWorkflowsIncludingDrafts()).thenAnswer(call -> new ArrayList<JiraWorkflow>(workflows));
        when(manager.getWorkflow(anyString())).thenAnswer(call -> workflows.stream()
                .filter(w -> w.getName().equals(call.getArgument(0)) && !w.isDraftWorkflow()).findFirst().orElse(null));
        when(inspector.consumers(any())).thenAnswer(call -> new WorkflowInspector.Consumers(new TreeSet<Long>(),
                Collections.<String>emptyList(), new TreeMap<String, String>(Collections.singletonMap(
                        ((JiraWorkflow) call.getArgument(0)).getName().substring(0, 3).toUpperCase(), "p"))));
        updater = new StatusUpdaterImpl(guard, lock, service, categories, manager, inspector,
                new ru.rasulov.common.impl.PreviewPlanExecutor(InMemoryActionPlans.create(), new ActionPlanCodec()),
                new ActionPlanCodec(), journalFixture.journal);
    }

    @After
    public void closeJournal() {
        journalFixture.close();
    }

    @Test
    public void thePreviewNamesEverySharingWorkflowAndProjectAndWritesNothing() {
        UpdateStatusResponse preview = updater.update(new UpdateStatusRequest(true, "10001", "In review", null,
                "DONE", null));

        assertEquals(UpdateStatusResponse.Status.WOULD_UPDATE, preview.getStatus());
        assertEquals(2, preview.getWorkflowCount());
        assertEquals(Arrays.asList("Billing", "Support"), preview.getWorkflows());
        assertEquals(2, preview.getProjectCount());
        assertEquals(2, preview.getChanges().size());
        assertTrue(preview.getWarnings().toString(), preview.getWarnings().stream()
                .anyMatch(w -> w.contains("does not set or clear the resolution")));
        assertTrue(preview.getWarnings().toString(), preview.getWarnings().stream()
                .anyMatch(w -> w.contains("old name")));
        assertNotNull(preview.getPlanId());
        verify(service, never()).editStatus(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    public void applyUpdatesTheStatusOnceAndProvesItByReadBack() {
        UpdateStatusResponse preview = updater.update(new UpdateStatusRequest(true, "10001", "In review", null,
                "DONE", null));

        UpdateStatusResponse done = updater.update(new UpdateStatusRequest(false, null, null, null, null,
                preview.getPlanId()));

        assertEquals(UpdateStatusResponse.Status.UPDATED, done.getStatus());
        verify(service, times(1)).editStatus(eq(user), eq(status), eq("In review"), eq(""), eq("/i.gif"), eq(this.done));
    }

    @Test
    public void aWorkflowThatStartedUsingTheStatusAfterThePreviewMakesThePlanStale() {
        UpdateStatusResponse preview = updater.update(new UpdateStatusRequest(true, "10001", "In review", null,
                null, null));
        workflows.add(workflow("Draft only", true));

        UpdateStatusResponse result = updater.update(new UpdateStatusRequest(false, null, null, null, null,
                preview.getPlanId()));

        assertEquals(ErrorCode.PREVIEW_STALE, result.getErrorCode());
        verify(service, never()).editStatus(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    public void jirasValidationOfTheNameIsFinalAndGivesNoPlan() {
        SimpleErrorCollection errors = new SimpleErrorCollection();
        errors.addError("name", "A status with this name already exists.");
        when(service.validateEditStatus(any(), any(), eq("Open"), any(), any(), any()))
                .thenReturn(new ServiceResultImpl(errors));

        UpdateStatusResponse refused = updater.update(new UpdateStatusRequest(true, "10001", "Open", null, null, null));

        assertEquals(ErrorCode.UPDATE_REFUSED, refused.getErrorCode());
        assertNull(refused.getPlanId());
        assertTrue(refused.getMessage(), refused.getMessage().contains("already exists"));
    }

    @Test
    public void sameValuesAreUnchangedAndAnUnknownStatusIsNamed() {
        assertEquals(UpdateStatusResponse.Status.UNCHANGED, updater.update(new UpdateStatusRequest(true, "10001",
                "Review", null, "TO_DO", null)).getStatus());
        assertEquals(ErrorCode.UNKNOWN_STATUS, updater.update(new UpdateStatusRequest(true, "999", "x", null, null,
                null)).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, updater.update(new UpdateStatusRequest(true, "10001", null, null,
                null, null)).getErrorCode());
    }

    @Test
    public void aWriteThatDoesNotReadBackIsUncertainAndTheStatusIsNotUpdatedAgain() {
        UpdateStatusResponse preview = updater.update(new UpdateStatusRequest(true, "10001", "In review", null,
                null, null));
        ServiceOutcome<Status> silent = mock(ServiceOutcome.class);
        when(silent.isValid()).thenReturn(true);
        org.mockito.Mockito.doReturn(silent).when(service).editStatus(any(), any(), anyString(), any(), any(), any());

        UpdateStatusResponse result = updater.update(new UpdateStatusRequest(false, null, null, null, null,
                preview.getPlanId()));

        assertEquals(ErrorCode.JIRA_ERROR, result.getErrorCode());
        assertTrue(result.getMessage(), result.getMessage().contains("uncertain"));
        verify(service, times(1)).editStatus(any(), any(), anyString(), any(), any(), any());
    }

    private static StatusCategory category(String key) {
        StatusCategory category = mock(StatusCategory.class);
        when(category.getKey()).thenReturn(key);
        return category;
    }

    private static JiraWorkflow workflow(String name, boolean draft) {
        JiraWorkflow workflow = mock(JiraWorkflow.class);
        when(workflow.getName()).thenReturn(name);
        when(workflow.isDraftWorkflow()).thenReturn(draft);
        when(workflow.getLinkedStatusIds()).thenReturn(Collections.singleton("10001"));
        return workflow;
    }
}
