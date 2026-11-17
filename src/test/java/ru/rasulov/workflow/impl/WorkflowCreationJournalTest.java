package ru.rasulov.workflow.impl;

import com.atlassian.jira.bc.ServiceOutcome;
import com.atlassian.jira.bc.ServiceResult;
import com.atlassian.jira.bc.config.StatusService;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowScheme;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.workflow.api.dto.*;
import ru.rasulov.workflow.api.dto.configure.*;
import ru.rasulov.workflow.impl.edit.WorkflowConfigurationFixture;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.workflow.impl.undo.WorkflowSnapshots;
import ru.rasulov.workflow.impl.undo.CreatedStatusUndoHandler;
import ru.rasulov.workflow.impl.undo.CreatedWorkflowUndoHandler;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class WorkflowCreationJournalTest {
    private final WorkflowManager workflows = mock(WorkflowManager.class);
    private final WorkflowSchemeManager schemes = mock(WorkflowSchemeManager.class);
    private final StatusService statusService = mock(StatusService.class);
    private final ConstantsManager constants = mock(ConstantsManager.class);
    private final OfBizDelegator delegator = mock(OfBizDelegator.class);
    private final StatusCatalog catalog = mock(StatusCatalog.class);
    private final Map<String, JiraWorkflow> stored = new LinkedHashMap<String, JiraWorkflow>();
    private final Map<String, Status> statuses = new LinkedHashMap<String, Status>();
    private UndoTestFixture fixture;
    private WorkflowConfigurationFixture configure;

    @Before public void setUp() {
        when(delegator.findListIteratorByCondition(anyString(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> ru.rasulov.common.impl.undo.Cursors.over(Collections.emptyList()));
        statuses.put("1", WorkflowMocks.status("1", "Open", "new")); statuses.put("2", WorkflowMocks.status("2", "Done", "done"));
        JiraWorkflow template = nativeWorkflow("jira", new WorkflowDescriptor()); stored.put("jira", template);
        when(workflows.getWorkflow(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        when(workflows.getWorkflows()).thenAnswer(call -> new ArrayList<JiraWorkflow>(stored.values()));
        when(workflows.getWorkflowsIncludingDrafts()).thenAnswer(call -> new ArrayList<JiraWorkflow>(stored.values()));
        when(workflows.copyWorkflow(any(ApplicationUser.class), anyString(), nullable(String.class), any(JiraWorkflow.class))).thenAnswer(call -> {
            String name = call.getArgument(1); JiraWorkflow copy = nativeWorkflow(name, new WorkflowDescriptor()); stored.put(name, copy); return copy;
        });
        doAnswer(call -> { stored.remove(((JiraWorkflow) call.getArgument(0)).getName()); return null; }).when(workflows).deleteWorkflow(any(JiraWorkflow.class));
        when(schemes.getSchemesForWorkflowIncludingDrafts(any(JiraWorkflow.class))).thenReturn(Collections.<WorkflowScheme>emptyList());
        when(delegator.findByCondition(anyString(), any(), any(), any())).thenAnswer(call -> {
            if (!"Workflow".equals(call.getArgument(0))) return Collections.<GenericValue>emptyList();
            GenericValue row = mock(GenericValue.class); when(row.getLong("id")).thenReturn(100L); return Collections.singletonList(row);
        });
        when(catalog.find(anyString())).thenAnswer(call -> {
            for (Status status : statuses.values()) if (status.getName().equalsIgnoreCase(call.getArgument(0))) return status; return null;
        });
        when(constants.getStatusObject(anyString())).thenAnswer(call -> statuses.get(call.getArgument(0)));
        when(constants.getStatusByNameIgnoreCase(anyString())).thenAnswer(call -> {
            for (Status status : statuses.values()) if (status.getName().equalsIgnoreCase(call.getArgument(0))) return status; return null;
        });
        when(statusService.createStatus(any(ApplicationUser.class), anyString(), nullable(String.class), anyString(), any())).thenAnswer(call -> {
            Status created = WorkflowMocks.status("3", call.getArgument(1), "indeterminate"); statuses.put("3", created); return outcome(created);
        });
        when(statusService.removeStatus(any(ApplicationUser.class), any(Status.class))).thenAnswer(call -> {
            statuses.remove(((Status) call.getArgument(1)).getId()); ServiceResult result = mock(ServiceResult.class); when(result.isValid()).thenReturn(true); return result;
        });
        fixture = UndoTestFixture.create("admin", new CreatedWorkflowUndoHandler(workflows, schemes, delegator),
                new CreatedStatusUndoHandler(constants, statusService, workflows, delegator));
        configure = new WorkflowConfigurationFixture();
    }
    @After public void close() { configure.close(); fixture.close(); }

    @Test public void xmlControlCharacterIsRejectedBeforeNativeCreation() {
        assertXmlTextRejected("\u0001");
    }
    @Test public void xmlNoncharacterIsRejectedBeforeNativeCreation() {
        assertXmlTextRejected("\uFFFF");
    }
    @Test public void xmlUnpairedSurrogateIsRejectedBeforeNativeCreation() {
        assertXmlTextRejected("\uD83D");
        assertXmlTextRejected("\uDE00");
    }
    private void assertXmlTextRejected(String bad) {
        WorkflowDefinition base = WorkflowCreatorImplTest.keyed(WorkflowMocks.simple());
        for (String text : Arrays.asList("a" + bad + "b", bad + "text", "text" + bad)) {
            List<StatusDefinition> statuses = Arrays.asList(WorkflowMocks.status("Open", null),
                    WorkflowMocks.status(text, "IN_PROGRESS"), WorkflowMocks.status("Done", null));
            List<WorkflowDefinition> requests = Arrays.asList(
                    new WorkflowDefinition(base.getName(), text, base.getStatuses(),
                            base.getInitialStatus(), base.getTransitions()),
                    new WorkflowDefinition(text, null, base.getStatuses(),
                            base.getInitialStatus(), base.getTransitions()),
                    new WorkflowDefinition("W", null, statuses, "Open",
                            Arrays.asList(WorkflowMocks.move("Go", "Open", text))),
                    new WorkflowDefinition("W", null, base.getStatuses(), "Open",
                            Arrays.asList(WorkflowMocks.move(text, "Open", "Done"))),
                    new WorkflowDefinition("W", null,
                            Arrays.asList(WorkflowMocks.status("Open", null),
                                    new StatusDefinition("Review", "IN_PROGRESS", text)), "Open",
                            Arrays.asList(WorkflowMocks.move("Go", "Open", "Review"))));
            for (WorkflowDefinition request : requests) {
                ConfigureWorkflowResponse rejected = configure.preview(WorkflowCreatorImplTest.request(request));
                assertEquals("FAILED", rejected.getStatus());
                assertEquals("VALIDATION_ERROR", rejected.getErrorCode());
                assertNull(rejected.getPlanId());
                assertTrue(configure.nativeCalls.isEmpty());
                assertFalse(configure.main.containsKey("W"));
                assertEquals(new LinkedHashSet<String>(Arrays.asList("1", "2", "3")), configure.statusStore.keySet());
            }
        }
    }

    /** Old durable create_workflow entries remain undoable after the API cutover. */
    @Test public void historicalCreationRecordsNativeIdentityAndRollbackDeletesTheWorkflowBeforeItsNewStatus() {
        create();
        assertEquals(3, stored.get("Bug flow").getDescriptor().getSteps().size());
        ChangeJournal reopened = fixture.reopen();
        Map<String, Object> rollback = reopened.rollback("admin", null, null, session(), true, null);
        assertEquals(rollback.toString(), "READY", rollback.get("status"));
        Map<String, Object> undone = reopened.rollback("admin", null, null, null, false, (String) rollback.get("planId"));
        assertEquals(undone.toString(), "ROLLED_BACK", undone.get("status"));
        assertFalse(stored.containsKey("Bug flow")); assertFalse(statuses.containsKey("3"));
        assertTrue(stored.containsKey("jira")); assertTrue(statuses.containsKey("1")); assertTrue(statuses.containsKey("2"));
    }
    @Test public void aRepeatExistsDoesNotCreateANewUndoOwnerOrMutateTheExistingWorkflow() {
        create();
        ConfigureWorkflowResponse first = configure.apply(configure.preview(WorkflowCreatorImplTest.request(WorkflowMocks.simple())));
        assertEquals(first.getMessage(), "CREATED", first.getStatus());
        ConfigureWorkflowResponse repeat = configure.preview(WorkflowCreatorImplTest.request(WorkflowMocks.simple()));
        assertEquals("EXISTS", repeat.getStatus());
        assertNull(repeat.getPlanId());
        assertEquals(1, fixture.journal.listChanges(session(), "admin").size());
        verify(workflows, never()).deleteWorkflow(any(JiraWorkflow.class));
    }
    @Test public void aForeignWorkflowUsingTheNewStatusBlocksTheEntireCreationRollback() {
        create(); JiraWorkflow foreign = nativeWorkflow("Foreign", new WorkflowDescriptor());
        when(foreign.getLinkedStatusIds()).thenReturn(Collections.singleton("3")); stored.put("Foreign", foreign);
        Map<String, Object> preview = fixture.reopen().rollback("admin", null, null, session(), true, null);
        assertEquals(preview.toString(), "BLOCKED", preview.get("status"));
        assertTrue(stored.containsKey("Bug flow")); assertTrue(statuses.containsKey("3"));
        verify(statusService, never()).removeStatus(any(ApplicationUser.class), any(Status.class));
    }
    @Test public void anAssignedSchemeBlocksDeletionOfTheCreatedWorkflowAndStatus() {
        create();
        WorkflowScheme assigned = mock(WorkflowScheme.class); when(assigned.getId()).thenReturn(20L);
        when(schemes.getSchemesForWorkflowIncludingDrafts(stored.get("Bug flow"))).thenReturn(Collections.singletonList(assigned));
        assertCreationBlocked("referenced by scheme 20");
    }
    @Test public void forwardAssignmentInTheSameGroupBlocksCreationRollback() throws Exception {
        String session = fixture.journal.createSession("admin");
        String set = fixture.journal.createChangeSet(session, "admin");
        String creation = fixture.journal.openOperation("create_workflow", "admin", session, set);
        fixture.journal.runOperation(creation, this::create);

        AssignableWorkflowScheme assigned = mock(AssignableWorkflowScheme.class);
        when(assigned.getId()).thenReturn(20L);
        when(assigned.getConfiguredDefaultWorkflow()).thenReturn("Bug flow");
        List<WorkflowScheme> references = new ArrayList<>();
        when(schemes.getSchemesForWorkflowIncludingDrafts(stored.get("Bug flow"))).thenReturn(references);
        doAnswer(call -> { references.add(call.getArgument(0)); return null; })
                .when(schemes).updateWorkflowScheme(any(AssignableWorkflowScheme.class));
        String assignment = fixture.journal.openOperation("assign_workflow", "admin", session, set);
        fixture.journal.runOperation(assignment, () -> {
            schemes.updateWorkflowScheme(assigned);
            fixture.journal.checkpoint();
            return Collections.singletonMap("status", "ASSIGNED");
        });
        assertEquals("Bug flow", ((AssignableWorkflowScheme) references.get(0)).getConfiguredDefaultWorkflow());
        assertTrue(((List<?>) fixture.journal.getOperation(assignment, "admin").get("steps")).isEmpty());

        Map<String, Object> rollback = fixture.reopen().rollback("admin", null, set, null, true, null);
        assertEquals(rollback.toString(), "BLOCKED", rollback.get("status"));
        assertTrue(rollback.toString(), rollback.get("conflicts").toString().contains("referenced by scheme 20"));
        Map<?, ?> nonUndoable = (Map<?, ?>) ((List<?>) rollback.get("nonUndoableOperations")).get(0);
        assertEquals("assign_workflow", nonUndoable.get("toolName"));
        assertEquals(assignment, nonUndoable.get("operationId"));
        assertEquals("BLOCKED", fixture.journal.rollback("admin", null, null, null, false,
                (String) rollback.get("planId")).get("status"));
        assertTrue(stored.containsKey("Bug flow")); assertTrue(statuses.containsKey("3"));
        verify(workflows, never()).deleteWorkflow(any(JiraWorkflow.class));
        verify(statusService, never()).removeStatus(any(ApplicationUser.class), any(Status.class));
    }
    @Test public void anIssueUsingTheCreatedWorkflowBlocksDeletion() {
        create();
        GenericValue issue = mock(GenericValue.class); when(issue.getLong("issueId")).thenReturn(11L);
        when(delegator.findListIteratorByCondition(eq("IssueWorkflowEntryView"), any(), any(), any(), any(), any()))
                .thenAnswer(call -> ru.rasulov.common.impl.undo.Cursors.over(Collections.singletonList(issue)));
        assertCreationBlocked("Issue 11 now uses workflow");
    }
    @Test public void anIssueUsingTheCreatedStatusBlocksDeletionEvenWithItsOwnedWorkflowSelected() {
        create();
        GenericValue issue = mock(GenericValue.class); when(issue.getLong("id")).thenReturn(11L);
        when(delegator.findListIteratorByCondition(eq("Issue"), any(), any(), any(), any(), any()))
                .thenAnswer(call -> ru.rasulov.common.impl.undo.Cursors.over(Collections.singletonList(issue)));
        assertCreationBlocked("Issue 11 now uses status");
    }
    @Test public void changedResolutionPostFunctionBlocksRollbackOfTheWholeCreation() {
        create();
        com.opensymphony.workflow.loader.ActionDescriptor finish =
                (com.opensymphony.workflow.loader.ActionDescriptor) stored.get("Bug flow").getDescriptor()
                        .getStep(2).getActions().get(0);
        com.opensymphony.workflow.loader.FunctionDescriptor resolution =
                (com.opensymphony.workflow.loader.FunctionDescriptor) finish.getUnconditionalResult()
                        .getPostFunctions().get(2);
        resolution.getArgs().put("field.value", "another-resolution");
        assertCreationBlocked("was edited or replaced");
    }
    private void assertCreationBlocked(String reason) {
        Map<String, Object> preview = fixture.reopen().rollback("admin", null, null, session(), true, null);
        assertEquals(preview.toString(), "BLOCKED", preview.get("status"));
        assertTrue(preview.toString(), preview.get("conflicts").toString().contains(reason));
        assertTrue(stored.containsKey("Bug flow")); assertTrue(statuses.containsKey("3"));
        verify(workflows, never()).deleteWorkflow(any(JiraWorkflow.class));
        verify(statusService, never()).removeStatus(any(ApplicationUser.class), any(Status.class));
    }
    @Test public void aNativeUpdateFailureCannotTriggerASecondAdHocCompensation() {
        configure.statusStore.remove("2");
        ConfigureWorkflowResponse preview = configure.preview(WorkflowCreatorImplTest.request(WorkflowMocks.simple()));
        configure.beforeNative = key -> { if ("workflow-save".equals(key)) throw new IllegalStateException("native update failed"); };
        ConfigureWorkflowResponse result = configure.apply(preview);
        assertEquals(result.getMessage(), "PARTIAL", result.getStatus());
        assertTrue(configure.main.containsKey("Bug flow"));
        assertEquals(3, configure.statusStore.size());
        verify(configure.manager, never()).deleteWorkflow(any(JiraWorkflow.class));
        verify(configure.statusService, never()).removeStatus(any(ApplicationUser.class), any(Status.class));
        assertEquals("UNKNOWN", WorkflowCreatorImplTest.steps(result).get(2).get("writeStatus"));
        assertEquals("APPLIED", WorkflowCreatorImplTest.steps(result).get(0).get("writeStatus"));
    }
    @Test public void configuredCreationHasDurableForwardOnlyReceiptAndNoUndoOwnership() {
        ConfigureWorkflowResponse result = configure.apply(configure.preview(WorkflowCreatorImplTest.request(WorkflowMocks.simple())));
        assertEquals(result.getMessage(), "CREATED", result.getStatus());
        String session = (String) configure.journalFixture.journal.listSessions("admin").get(0).get("sessionId");
        Map<String,Object> rollback = configure.journalFixture.reopen().rollback("admin", null, null, session, true, null);
        List<?> excluded = (List<?>) rollback.get("nonUndoableOperations");
        assertEquals(1, excluded.size());
        assertEquals("configure_workflow", ((Map<?,?>) excluded.get(0)).get("toolName"));
        String operation = (String) ((Map<?,?>) excluded.get(0)).get("operationId");
        Map<String,Object> reopened = configure.journalFixture.reopen().getOperation(operation, "admin");
        assertTrue(((List<?>) reopened.get("steps")).isEmpty());
        assertNotNull(reopened.get("execution"));
        assertEquals(result.getExecution(), reopened.get("execution"));
        assertTrue(configure.main.containsKey("Bug flow"));
        verify(configure.manager, never()).deleteWorkflow(any(JiraWorkflow.class));
    }
    /** Seed the persisted historical contract, not an alias for a removed creation service. */
    private Map<String,Object> create() {
        if (stored.containsKey("Bug flow")) return Collections.singletonMap("status", "EXISTS");
        if (fixture.journal.currentOperationId() == null) {
            String session = fixture.journal.createSession("admin");
            String changeSet = fixture.journal.createChangeSet(session, "admin");
            String operation = fixture.journal.openOperation("create_workflow", "admin", session, changeSet);
            return fixture.journal.runOperation(operation, this::create);
        }
            ApplicationUser user = fixture.currentActor.get();
            ResolvedWorkflow graph = new WorkflowRequestValidator(catalog, null, WorkflowMocks.resolutions())
                    .validate(WorkflowMocks.simple(), user);
            Map<ResolvedWorkflow.WorkflowStatus,String> ids = new IdentityHashMap<>();
            for (ResolvedWorkflow.WorkflowStatus status : graph.getStatuses()) {
                Status nativeStatus = status.getExisting();
                if (status.isNew()) {
                    String absent = WorkflowSnapshots.json(WorkflowSnapshots.absent(status.getName()));
                    UndoStep statusStep = fixture.journal.prepare("workflow.status-created", "status:" + status.getName(), absent, absent);
                    nativeStatus = WorkflowMocks.status("3", status.getName(), "indeterminate");
                    statuses.put("3", nativeStatus);
                    fixture.journal.applied(statusStep, WorkflowSnapshots.json(WorkflowSnapshots.status(nativeStatus)));
                    fixture.journal.checkpoint();
                }
                ids.put(status, nativeStatus.getId());
            }
            WorkflowDescriptor descriptor;
            try { descriptor = new WorkflowDescriptorBuilder().build(graph, ids); }
            catch (com.opensymphony.workflow.FactoryException e) { throw new IllegalStateException(e); }
            UndoStep step = fixture.journal.prepare("workflow.created", "workflow:Bug flow",
                    WorkflowSnapshots.json(WorkflowSnapshots.absent("Bug flow")),
                    WorkflowSnapshots.json(WorkflowSnapshots.absent("Bug flow")));
            JiraWorkflow workflow = nativeWorkflow("Bug flow", descriptor);
            stored.put("Bug flow", workflow);
            fixture.journal.applied(step, WorkflowSnapshots.json(WorkflowSnapshots.workflow(workflow, delegator)));
            fixture.journal.checkpoint();
            return Collections.singletonMap("status", "CREATED");
    }
    private String session() { return (String) fixture.journal.listSessions("admin").get(0).get("sessionId"); }
    private JiraWorkflow nativeWorkflow(String name, WorkflowDescriptor descriptor) {
        JiraWorkflow value = mock(JiraWorkflow.class); when(value.getName()).thenReturn(name); when(value.getDescriptor()).thenReturn(descriptor);
        when(value.getLinkedStatusIds()).thenAnswer(call -> {
            Set<String> linked = new HashSet<String>();
            for (Object raw : descriptor.getSteps()) linked.add(((com.opensymphony.workflow.loader.StepDescriptor) raw).getMetaAttributes().get("jira.status.id").toString());
            return linked;
        });
        return value;
    }
    @SuppressWarnings("unchecked") private static <T> ServiceOutcome<T> outcome(T value) {
        ServiceOutcome<T> outcome = mock(ServiceOutcome.class); when(outcome.isValid()).thenReturn(true); when(outcome.getReturnedValue()).thenReturn(value); return outcome;
    }
}
