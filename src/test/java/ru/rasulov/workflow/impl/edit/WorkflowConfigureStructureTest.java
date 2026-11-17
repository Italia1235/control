package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest.Existing;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.api.dto.edit.ScriptRunnerStatus;
import ru.rasulov.workflow.api.dto.edit.WorkflowChange;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.rasulov.workflow.impl.edit.EditFixtures.*;

/** Structural configure regressions against the real planner, writer, preview store and durable journal. */
public class WorkflowConfigureStructureTest {
    private static final String NAME = "Support";
    private WorkflowConfigurationFixture world;

    @Before public void setUp() {
        world = new WorkflowConfigurationFixture();
        world.status("3", "In Progress", "indeterminate");
        world.status("5", "Resolved", "done");
        world.status("6", "Closed", "done");
        world.status("10001", "Review", "indeterminate");
        world.status("10002", "Shipped", "done");
        world.screen(7, "Close form", List.of("description"));
        world.screen(9, "Finish form", List.of("description"));
        world.screen(11, "Start form", List.of("description"));
        Resolution resolution = mock(Resolution.class);
        when(resolution.getId()).thenReturn("2");
        when(resolution.getName()).thenReturn("Won't Fix");
        when(world.constants.getResolution("2")).thenReturn(resolution);
        when(world.occupancy.occupied(anyString(), anySet())).thenReturn(Collections.singletonMap(2, 0L));
        world.putWorkflow(NAME, workflow(), false);
    }

    @After public void close() { world.close(); }

    @Test public void previewNeverWritesOrMutatesTheSourceAndStructuralOnlyNeedsNoScriptRunner() {
        String before = definition();
        when(world.gate.status()).thenReturn(new ScriptRunnerStatus(false, false, null, false, "not installed"));
        ConfigureWorkflowResponse preview = preview("ACTIVE", rename(11, "Begin"));
        assertEquals(preview.getMessage(), "WOULD_CHANGE", preview.getStatus());
        assertNotNull(preview.getPlanId());
        assertTrue(preview.getDiff().toString(), preview.getDiff().stream().anyMatch(d -> d.contains("Begin")));
        assertEquals(before, definition());
        assertNoWrites();
        verifyNoInteractions(world.gate);
    }

    @Test public void inactiveSaveKeepsIdsAndUnnamedThirdPartySettings() {
        String untouched = DescriptorFacts.xml(DescriptorFacts.locate(world.main.get(NAME)).get(13).action());
        ConfigureWorkflowResponse done = world.apply(preview("ACTIVE", rename(11, "Begin"),
                resolution(42, "SET", "2"), screen(11, "SET", 11L)));
        assertEquals(done.getMessage(), "UPDATED_INACTIVE", done.getStatus());
        assertFalse(done.isDryRun());
        WorkflowDescriptor saved = world.main.get(NAME);
        assertEquals("Begin", DescriptorFacts.locate(saved).get(11).action().getName());
        assertEquals("2", DescriptorFacts.rule(DescriptorFacts.locate(saved).get(42).action()).value());
        assertEquals(untouched, DescriptorFacts.xml(DescriptorFacts.locate(saved).get(13).action()));
        assertTrue(DescriptorFacts.xml(saved).contains("com.acme.jira.PolicyValidator"));
        assertTrue(DescriptorFacts.xml(saved).contains("TOP-SECRET"));
        assertEquals(List.of("workflow-save"), world.nativeCalls);
        assertReceipt(done, "workflow-save", "APPLIED", "MATCH");
        verify(world.manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test public void unchangedStructuralRequestReturnsNoPlanAndWritesNothing() {
        ConfigureWorkflowResponse response = preview("ACTIVE", rename(11, "Start"), EditFixtures.description("Support"));
        assertEquals(response.getMessage(), "UNCHANGED", response.getStatus());
        assertNull(response.getPlanId());
        assertNoWrites();
    }

    @Test public void activeSaveCreatesOnlyADraftAndNeverMigratesExistingIssues() throws Exception {
        world.active.add(NAME);
        // A nonempty native issue store. The same manager exposed to the real writer owns its migration API.
        Map<Long, List<Object>> issueRows = new LinkedHashMap<>();
        issueRows.put(101L, new ArrayList<>(List.of(NAME, "1", 1, 1001L)));
        issueRows.put(102L, new ArrayList<>(List.of(NAME, "3", 2, 1002L)));
        List<MutableIssue> issues = new ArrayList<>();
        for (Map.Entry<Long, List<Object>> entry : issueRows.entrySet()) {
            MutableIssue issue = mock(MutableIssue.class);
            when(issue.getId()).thenReturn(entry.getKey());
            when(issue.getStatusId()).thenAnswer(c -> entry.getValue().get(1));
            when(issue.getWorkflowId()).thenAnswer(c -> entry.getValue().get(3));
            issues.add(issue);
        }
        doAnswer(c -> {
            MutableIssue issue = c.getArgument(0);
            JiraWorkflow target = c.getArgument(1);
            Status status = c.getArgument(2);
            List<Object> row = issueRows.get(issue.getId());
            row.set(0, target.getName()); row.set(1, status.getId()); row.set(2, -1);
            return null;
        }).when(world.manager).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        Map<Long, List<Object>> beforeIssues = new LinkedHashMap<>();
        issueRows.forEach((id, row) -> beforeIssues.put(id, List.copyOf(row)));
        String activeBefore = definition();
        ConfigureWorkflowResponse planned = preview("ACTIVE", rename(11, "Begin"),
                resolution(42, "CLEAR", null), retarget(42, 2));
        assertEquals(planned.getMessage(), "WOULD_CHANGE", planned.getStatus());
        assertNull(world.drafts.get(NAME));
        ConfigureWorkflowResponse done = world.apply(planned);
        assertEquals(done.getMessage(), "DRAFT_SAVED", done.getStatus());
        assertEquals(activeBefore, definition());
        assertEquals(beforeIssues, issueRows);
        assertEquals("1", issues.get(0).getStatusId());
        assertEquals("3", issues.get(1).getStatusId());
        assertEquals(Long.valueOf(1001), issues.get(0).getWorkflowId());
        assertEquals(Long.valueOf(1002), issues.get(1).getWorkflowId());
        WorkflowDescriptor saved = world.drafts.get(NAME);
        assertEquals("Begin", DescriptorFacts.locate(saved).get(11).action().getName());
        assertEquals("CLEAR", DescriptorFacts.rule(DescriptorFacts.locate(saved).get(42).action()).action());
        assertTrue(DescriptorFacts.xml(saved).contains("com.acme.jira.WhenCondition"));
        assertEquals(List.of("draft", "workflow-save"), world.nativeCalls);
        assertReceipt(done, "draft", "APPLIED", "MATCH");
        assertReceipt(done, "workflow-save", "APPLIED", "MATCH");
        verify(world.manager, never()).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        verify(world.manager, never()).overwriteActiveWorkflow(any(ApplicationUser.class), anyString());
        verify(world.manager, never()).deleteDraftWorkflow(anyString());
    }

    @Test public void activeRequestNeverOverwritesAnExistingDraft() {
        world.active.add(NAME);
        world.drafts.put(NAME, WorkflowConfigurationFixture.copy(world.main.get(NAME)));
        refused(preview("ACTIVE", rename(11, "Begin")), "DRAFT_EXISTS");
    }

    @Test public void chosenDraftKeepsPriorChangesAndAcceptsReplacingOnlyItsOwnNewSteps() {
        world.active.add(NAME);
        WorkflowDescriptor draft = WorkflowConfigurationFixture.copy(world.main.get(NAME));
        WorkflowDescriptorPatcher.apply(draft, List.of(addStatus("10001", null)), null, Map.of(), catalog());
        world.drafts.put(NAME, draft);
        ConfigureWorkflowResponse result = world.apply(preview("DRAFT", rename(11, "Begin"), replaceStatus(5, "10002")));
        assertEquals(result.getMessage(), "DRAFT_SAVED", result.getStatus());
        assertEquals("10002", DescriptorFacts.statusId(DescriptorFacts.step(world.drafts.get(NAME), 5)));
        assertEquals("Begin", DescriptorFacts.locate(world.drafts.get(NAME)).get(11).action().getName());
        assertEquals("Start", DescriptorFacts.locate(world.main.get(NAME)).get(11).action().getName());
        assertEquals(List.of("workflow-save"), world.nativeCalls);
    }

    @Test public void replacingAnActiveStepRequiresMigrationEvenWhenEditingADraft() {
        world.active.add(NAME);
        refused(preview("ACTIVE", replaceStatus(2, "10001")), "MIGRATION_REQUIRED");
        world.drafts.put(NAME, WorkflowConfigurationFixture.copy(world.main.get(NAME)));
        refused(preview("DRAFT", replaceStatus(2, "10001")), "MIGRATION_REQUIRED");
    }

    @Test public void emptyActiveStepIsRemovedOnlyInDraftWithOccupancyWarning() {
        world.active.add(NAME);
        String before = definition();
        ConfigureWorkflowResponse planned = preview("ACTIVE", removeStepTwo());
        assertEquals(planned.getMessage(), "WOULD_CHANGE", planned.getStatus());
        assertTrue(planned.getWarnings().toString(), planned.getWarnings().stream().anyMatch(w ->
                w.contains("step 2") && w.contains("0 issues") && w.contains("publish_workflow_draft")));
        verify(world.occupancy).occupied(NAME, Set.of(2));
        assertNull(world.drafts.get(NAME));
        ConfigureWorkflowResponse done = world.apply(planned);
        assertEquals(done.getMessage(), "DRAFT_SAVED", done.getStatus());
        assertEquals(before, definition());
        assertNull(DescriptorFacts.step(world.drafts.get(NAME), 2));
    }

    @Test public void occupiedStepRemovalIsRefusedAndOccupancyChangingAfterPreviewIsStale() {
        world.active.add(NAME);
        when(world.occupancy.occupied(NAME, Set.of(2))).thenReturn(Map.of(2, 1L));
        refused(preview("ACTIVE", removeStepTwo()), "MIGRATION_REQUIRED");
        when(world.occupancy.occupied(NAME, Set.of(2))).thenReturn(Map.of(2, 0L));
        ConfigureWorkflowResponse planned = preview("ACTIVE", removeStepTwo());
        assertNotNull(planned.getPlanId());
        when(world.occupancy.occupied(NAME, Set.of(2))).thenReturn(Map.of(2, 1L));
        refused(world.apply(planned), "PREVIEW_STALE");
        assertNull(world.drafts.get(NAME));
    }

    @Test public void unrelatedEditsDoNotQueryOccupancy() {
        world.active.add(NAME);
        assertEquals("WOULD_CHANGE", preview("ACTIVE", rename(11, "Begin")).getStatus());
        verifyNoInteractions(world.occupancy);
    }
    @Test public void referencedStatusCategoryChangeAloneMakesStructuralPreviewStaleBeforeWrites() {
        String before=definition();
        ConfigureWorkflowResponse planned=preview("ACTIVE",rename(11,"Begin"));
        assertNotNull(planned.getMessage(),planned.getPlanId());
        world.status("3","In Progress","done");
        assertEquals(planned.getDiff(),preview("ACTIVE",rename(11,"Begin")).getDiff());
        refused(world.apply(planned),"PREVIEW_STALE");
        assertEquals(before,definition());
    }

    @Test public void referencedResolutionRenameOutsideTheRequestedChangeMakesPreviewStaleBeforeWrites() {
        String before=definition();
        ConfigureWorkflowResponse planned=preview("ACTIVE",rename(11,"Begin"));
        assertNotNull(planned.getMessage(),planned.getPlanId());
        when(world.constants.getResolution("1").getName()).thenReturn("Renamed resolution");
        assertEquals(planned.getDiff(),preview("ACTIVE",rename(11,"Begin")).getDiff());
        refused(world.apply(planned),"PREVIEW_STALE");
        assertEquals(before,definition());
    }

    @Test public void referencedScreenRenameMakesStructuralPreviewStaleBeforeWrites() {
        String before=definition();
        ConfigureWorkflowResponse planned=preview("ACTIVE",rename(11,"Begin"));
        assertNotNull(planned.getMessage(),planned.getPlanId());
        world.screenStore.get(7L).setName("Renamed screen");
        refused(world.apply(planned),"PREVIEW_STALE");
        assertEquals(before,definition());
    }

    @Test public void systemUnknownMissingDraftAndWrongRevisionAreRefusedWithoutWrites() {
        world.system.add(NAME);
        refused(preview("ACTIVE", rename(11, "x")), "SYSTEM_WORKFLOW");
        world.system.remove(NAME);
        refused(world.preview(request("Nope", "ACTIVE", "r", rename(11, "x"))), "UNKNOWN_WORKFLOW");
        refused(world.preview(request(NAME, "ACTIVE", "wrong", rename(11, "x"))), "VERSION_CONFLICT");
        refused(preview("DRAFT", rename(11, "x")), "VERSION_CONFLICT");
        refused(preview("BOTH", rename(11, "x")), "VALIDATION_ERROR");
    }

    @Test public void malformedSourceCannotBeRoundTrippedAndNeverProducesAPlan() {
        world.mutateWorkflowSource(NAME, descriptor -> descriptor.getInitialActions().clear());
        ConfigureWorkflowResponse response = preview("ACTIVE", rename(11, "Begin"));
        assertEquals(response.getMessage(), "FAILED", response.getStatus());
        assertEquals("UNSUPPORTED_DESCRIPTOR", response.getErrorCode());
        assertNull(response.getPlanId());
        assertNoWrites();
    }

    @Test public void applyAcceptsOnlyAPlanAndPreviewCannotCarryAPlan() {
        assertInvalid(new ConfigureWorkflowRequest(false, null, new Existing(NAME, "ACTIVE", "r", null), null, null, "p"));
        assertInvalid(new ConfigureWorkflowRequest(true, null, new Existing(NAME, "ACTIVE", world.revision(NAME),
                List.of(rename(11, "x"))), null, null, "p"));
        assertNoWrites();
    }

    @Test public void consumedPlanCannotWriteAgain() {
        ConfigureWorkflowResponse planned = preview("ACTIVE", rename(11, "Begin"));
        assertEquals("UPDATED_INACTIVE", world.apply(planned).getStatus());
        assertEquals("PREVIEW_STALE", world.apply(planned).getErrorCode());
        assertEquals(List.of("workflow-save"), world.nativeCalls);
    }

    @Test public void changedWorkflowConsumersOrNewDraftInvalidatePreviewBeforeNativeWrites() {
        ConfigureWorkflowResponse planned = preview("ACTIVE", rename(11, "Begin"));
        WorkflowDescriptorPatcher.apply(world.main.get(NAME), List.of(rename(13, "Quick")), null, Map.of(), catalog());
        refused(world.apply(planned), "PREVIEW_STALE");
        assertEquals("Quick", DescriptorFacts.locate(world.main.get(NAME)).get(13).action().getName());
        assertEquals("Start", DescriptorFacts.locate(world.main.get(NAME)).get(11).action().getName());
        world.active.add(NAME);
        planned = preview("ACTIVE", rename(11, "Begin"));
        when(world.inspector.consumers(any())).thenReturn(new WorkflowInspector.Consumers(new TreeSet<>(Set.of(7L)),
                List.of("Shared [7]: Bug"), new TreeMap<>(Map.of("ABC", "Abc"))));
        refused(world.apply(planned), "PREVIEW_STALE");
        planned = preview("ACTIVE", rename(11, "Begin"));
        world.drafts.put(NAME, WorkflowConfigurationFixture.copy(world.main.get(NAME)));
        refused(world.apply(planned), "PREVIEW_STALE");
    }

    @Test public void failureAfterDraftCreationKeepsDraftAndTruthfulPartialReceiptWithoutRetry() {
        world.active.add(NAME);
        ConfigureWorkflowResponse planned = preview("ACTIVE", rename(11, "Begin"));
        doThrow(new IllegalStateException("db down")).when(world.manager).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        ConfigureWorkflowResponse result = world.apply(planned);
        assertEquals(result.getMessage(), "PARTIAL", result.getStatus());
        assertEquals("JIRA_ERROR", result.getErrorCode());
        assertNotNull(world.drafts.get(NAME));
        assertReceipt(result, "draft", "APPLIED", "MATCH");
        assertReceipt(result, "workflow-save", "UNKNOWN", "NOT_CHECKED");
        assertFalse(result.getMessage().toLowerCase(Locale.ROOT).contains("nothing was changed"));
        assertTrue(result.getTransitions().isEmpty());
        assertTrue(result.getDiff().isEmpty());
        verify(world.manager, times(1)).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(world.manager, times(1)).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(world.manager, never()).deleteDraftWorkflow(anyString());
    }

    @Test public void nativeSaveReturningWithoutPersistenceIsNotSuccessAndIsNeverRetried() {
        String before = definition();
        ConfigureWorkflowResponse planned = preview("ACTIVE", rename(11, "Begin"));
        doNothing().when(world.manager).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        ConfigureWorkflowResponse result = world.apply(planned);
        assertEquals(result.getMessage(), "PARTIAL", result.getStatus());
        assertEquals("JIRA_ERROR", result.getErrorCode());
        assertReceipt(result, "workflow-save", "APPLIED", "MISMATCH");
        assertEquals(before, definition());
        assertTrue(result.getTransitions().isEmpty());
        assertTrue(result.getDiff().isEmpty());
        verify(world.manager, times(1)).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test public void timeoutDuringFirstSaveReturnsUnknownReceiptAndNoInventedSuccess() {
        ConfigureWorkflowResponse planned = preview("ACTIVE", rename(11, "Begin"));
        doThrow(new IllegalStateException("timeout")).when(world.manager).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        ConfigureWorkflowResponse result = world.apply(planned);
        assertEquals(result.getMessage(), "UNKNOWN", result.getStatus());
        assertReceipt(result, "workflow-save", "UNKNOWN", "NOT_CHECKED");
        assertTrue(result.getTransitions().isEmpty());
        assertTrue(result.getDiff().isEmpty());
        verify(world.manager, times(1)).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    private String definition() { return DescriptorFacts.definition(world.main.get(NAME)); }
    private ConfigureWorkflowResponse preview(String version, WorkflowChange... changes) {
        return world.preview(request(NAME, version, world.revision(NAME), changes));
    }
    private static ConfigureWorkflowRequest request(String name, String version, String revision, WorkflowChange... changes) {
        return new ConfigureWorkflowRequest(true, null, new Existing(name, version, revision, Arrays.asList(changes)), null, null, null);
    }
    private static WorkflowChange[] removeStepTwo() {
        return new WorkflowChange[]{removeTransition(41), removeTransition(42), removeTransition(43), removeTransition(44),
                removeTransition(45), removeTransition(11), removeStatus(2)};
    }
    private void assertNoWrites() {
        assertTrue(world.nativeCalls.toString(), world.nativeCalls.isEmpty());
        verify(world.manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(world.manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }
    private void refused(ConfigureWorkflowResponse response, String code) {
        assertEquals(response.getMessage(), code, response.getErrorCode());
        assertNull(response.getPlanId());
        assertNoWrites();
    }
    private void assertInvalid(ConfigureWorkflowRequest request) {
        try { world.preview(request); fail("invalid request accepted"); }
        catch (InvalidRequestException expected) { assertNotNull(expected.getMessage()); }
    }
    @SuppressWarnings("unchecked") private static void assertReceipt(ConfigureWorkflowResponse response, String key,
                                                                 String writeStatus, String verification) {
        assertNotNull(response.getMessage(), response.getExecution());
        List<Map<String, Object>> steps = (List<Map<String, Object>>) response.getExecution().get("steps");
        Map<String, Object> step = steps.stream().filter(s -> key.equals(s.get("key"))).findFirst().orElseThrow();
        assertEquals(step.toString(), writeStatus, step.get("writeStatus"));
        assertEquals(step.toString(), verification, step.get("verification"));
    }
}
