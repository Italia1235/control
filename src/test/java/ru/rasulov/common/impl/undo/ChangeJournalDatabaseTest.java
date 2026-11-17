package ru.rasulov.common.impl.undo;

import com.atlassian.jira.user.ApplicationUser;
import net.java.ao.DBParam;
import net.java.ao.Query;
import org.junit.Test;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Storage is a real AO EntityManager over HSQLDB, never mocked persistence. */
public class ChangeJournalDatabaseTest {
    @Test public void planIdentitySurvivesReopeningAndIsActorScoped() throws Exception {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice")) {
            String session = fixture.journal.createSession("alice");
            String set = fixture.journal.createChangeSet(session, "alice");
            String legacy = fixture.journal.openOperation("create_screen", "alice", session, set);
            String id = fixture.journal.openOperation("create_screen", "alice", session, set, "secret-preview");
            JournalOperation[] rows = fixture.entityManager.find(JournalOperation.class,
                    Query.select().where("OPERATION_KEY = ?", id));
            assertEquals(64, rows[0].getPlanHash().length());
            assertFalse("secret-preview".equals(rows[0].getPlanHash()));
            assertEquals(id, fixture.reopen().findOperationByPlan("secret-preview", "alice").get("operationId"));
            assertNull(fixture.reopen().findOperationByPlan("unknown", "alice"));
            fixture.currentActor.set(UndoTestFixture.user("bob"));
            assertNull(fixture.reopen().findOperationByPlan("secret-preview", "bob"));
            fixture.currentActor.set(UndoTestFixture.user("alice"));
            assertNull(fixture.entityManager.find(JournalOperation.class,
                    Query.select().where("OPERATION_KEY = ?", legacy))[0].getPlanHash());
        }
    }

    @Test public void expiredUnsettledProgressIsFreedByNextJournalWrite() throws Exception {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice")) {
            String old = unsettledProgress(fixture, 2);
            JournalProgress progress = fixture.entityManager.find(JournalProgress.class)[0];
            long allocation = progress.getAllocatedBytes() + 2L * ChangeJournal.PROGRESS_RECORD_BYTES;
            long before = fixture.store.usedBytes();
            fixture.clock.addAndGet(TimeUnit.DAYS.toMillis(30) + 1);
            String session = fixture.journal.createSession("alice");
            assertEquals(0, fixture.entityManager.count(JournalProgressBatch.class));
            assertEquals(0, fixture.entityManager.count(JournalProgress.class));
            assertEquals(before - allocation + JournalStore.bytes(session, "alice"), fixture.store.usedBytes());
            assertEquals("FAILED", fixture.journal.getOperation(old, "alice").get("status"));
        }
    }

    @Test public void freshUnsettledProgressAndUnknownReceiptRemainAvailable() throws Exception {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice")) {
            String old = unsettledProgress(fixture, 1);
            fixture.clock.addAndGet(TimeUnit.DAYS.toMillis(30) - 1);
            fixture.journal.createSession("alice");
            assertEquals(1, fixture.entityManager.count(JournalProgress.class));
            assertEquals(1, fixture.entityManager.count(JournalProgressBatch.class));
            Map<?, ?> execution = (Map<?, ?>) fixture.reopen().getOperation(old, "alice").get("execution");
            assertEquals("UNKNOWN", execution.get("outcome"));
            assertEquals(1, ((Number) ((Map<?, ?>) execution.get("counts")).get("unknown")).intValue());
            assertNull(fixture.entityManager.find(JournalProgressBatch.class)[0].getAfterJson());
        }
    }

    @Test public void capacityRefusalNamesUnfinishedProgressBeforeAnyNativeWrite() {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", ChangeJournal.RESULT_JSON_LIMIT + 100000L)) {
            unsettledProgress(fixture, 1);
            String session = fixture.journal.createSession("alice");
            String set = fixture.journal.createChangeSet(session, "alice");
            String next = fixture.journal.openOperation("set_project_issue_types", "alice", session, set);
            AtomicInteger writes = new AtomicInteger();
            try {
                fixture.journal.runOperation(next, () -> {
                    fixture.journal.startIssueTypeProgress(unknownExecution(), 204);
                    writes.incrementAndGet();
                    return null;
                });
                fail("Progress admission must refuse before native writes");
            } catch (JournalCapacityException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("unfinished progress records: 1"));
                assertTrue(expected.getMessage(), expected.getMessage().contains("30 days"));
                assertTrue(expected.getMessage(), expected.getMessage().contains("No new mutation was started"));
            }
            assertEquals(0, writes.get());
        }
    }

    @Test public void expiredProgressCleanupPreservesLegacyUndoAndOtherLiveReservations() throws Exception {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            unsettledProgress(fixture, 1);
            String other = operation(fixture.journal, "alice");
            fixture.journal.runOperation(other, () -> {
                change(fixture.journal, handler, "screen:legacy", "0", "1");
                fixture.journal.reserve(4096);
                fixture.clock.addAndGet(TimeUnit.DAYS.toMillis(31));
                fixture.journal.createSession("alice");
                try {
                    assertEquals(0, fixture.entityManager.count(JournalProgress.class));
                    assertEquals(1, fixture.entityManager.count(JournalStep.class));
                    assertEquals(4096L, fixture.entityManager.find(JournalOperation.class,
                            Query.select().where("OPERATION_KEY = ?", other))[0].getReservedBytes());
                } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
                assertEquals("1", handler.values.get("screen:legacy"));
                return null;
            });
        }
    }

    @Test public void boundedExpiryCleanupCommitsEvenWhenNextAdmissionStillFails() throws Exception {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", ChangeJournal.RESULT_JSON_LIMIT + 300000L)) {
            unsettledProgress(fixture, 60);
            String session = fixture.journal.createSession("alice");
            String set = fixture.journal.createChangeSet(session, "alice");
            String next = fixture.journal.openOperation("set_project_issue_types", "alice", session, set);
            fixture.clock.addAndGet(TimeUnit.DAYS.toMillis(31));
            expect(JournalCapacityException.class, () -> fixture.journal.runOperation(next, () -> {
                fixture.journal.startIssueTypeProgress(unknownExecution(), 204);
                fail("No native work may start");
                return null;
            }));
            assertEquals(10, fixture.entityManager.count(JournalProgressBatch.class));
            assertEquals(1, fixture.entityManager.count(JournalProgress.class));
            fixture.journal.createSession("alice");
            assertEquals(0, fixture.entityManager.count(JournalProgressBatch.class));
            assertEquals(0, fixture.entityManager.count(JournalProgress.class));
        }
    }

    private static Map<String, Object> unknownExecution() {
        Map<String, Object> counts = new java.util.LinkedHashMap<>();
        counts.put("completed", 0); counts.put("unknown", 1); counts.put("notStarted", null);
        Map<String, Object> execution = new java.util.LinkedHashMap<>();
        execution.put("outcome", "UNKNOWN"); execution.put("counts", counts);
        return execution;
    }

    private static String unsettledProgress(UndoTestFixture fixture, int batches) {
        String session = fixture.journal.createSession("alice");
        String set = fixture.journal.createChangeSet(session, "alice");
        String operation = fixture.journal.openOperation("set_project_issue_types", "alice", session, set);
        expect(IllegalStateException.class, () -> fixture.journal.runOperation(operation, () -> {
            fixture.journal.startIssueTypeProgress(unknownExecution(), batches);
            for (int index = 0; index < batches; index++)
                fixture.journal.beginIssueTypeBatch("ISSUES", Collections.singletonMap("issueIds",
                        Collections.singletonList((long) index + 1)), unknownExecution());
            throw new IllegalStateException("Unknown native outcome");
        }));
        return operation;
    }

    @Test public void persistsOperationPoststateAndResultAcrossFreshStoreInstances() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            fixture.journal.runOperation(operation, () -> {
                change(fixture.journal, handler, "screen:1", "0", "1");
                return Collections.singletonMap("status", "CREATED");
            });
            ChangeJournal restarted = fixture.reopen();
            Map<String, Object> row = restarted.getOperation(operation, "alice");
            assertEquals("COMPLETED", row.get("status"));
            assertEquals("CREATED", ((Map<?, ?>) row.get("result")).get("status"));
            Map<String, Object> preview = restarted.rollback("alice", operation, null, null, true, null);
            assertEquals("READY", preview.get("status"));
            assertEquals("ROLLED_BACK", confirm(restarted, "alice", preview).get("status"));
            assertEquals("0", handler.values.get("screen:1"));
            assertNull(restarted.currentOperationId());
        }
    }

    @Test public void mixedGroupReportsNonUndoableOperationsAndPreservesTheirCompletion() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String session = fixture.journal.createSession("alice");
            String set = fixture.journal.createChangeSet(session, "alice");
            String creation = fixture.journal.openOperation("create_screen", "alice", session, set);
            fixture.journal.runOperation(creation, () -> {
                change(fixture.journal, handler, "screen:1", "absent", "created");
                return "created";
            });
            Map<String, String> forward = new java.util.LinkedHashMap<>();
            for (String tool : new String[] {"add_field_to_screens", "set_field_required",
                    "clone_project_field_configurations", "assign_workflow", "clone_project_workflows",
                    "create_field_configuration", "create_field_configuration_scheme",
                    "assign_field_configuration_scheme", "set_field_configuration_mappings",
                    "clone_project_screens", "set_project_operation_screens", "set_issue_type_screen_scheme_mappings",
                    "set_screen_scheme_operations", "assign_issue_type_screen_scheme", "set_project_issue_types",
                    "publish_workflow_draft", "update_field_contexts", "remove_field_from_screens", "move_screen_fields", "edit_screen_layout",
                    "edit_workflow", "rename_workflow", "update_status", "configure_workflow_validators",
                    "configure_workflow_conditions"}) {
                String id = fixture.journal.openOperation(tool, "alice", session, set);
                fixture.journal.runOperation(id, () -> "changed");
                forward.put(id, tool);
            }
            Map<String, Object> preview = fixture.reopen().rollback("alice", null, set, null, true, null);
            Map<String, String> reported = new java.util.LinkedHashMap<>();
            for (Object item : (List<?>) preview.get("nonUndoableOperations")) {
                Map<?, ?> row = (Map<?, ?>) item;
                reported.put((String) row.get("operationId"), (String) row.get("toolName"));
            }
            assertEquals(forward, reported);
            Map<String, Object> result = confirm(fixture.journal, "alice", preview);
            assertEquals("PARTIAL", result.get("status"));
            assertEquals(preview.get("nonUndoableOperations"), result.get("nonUndoableOperations"));
            assertEquals("absent", handler.values.get("screen:1"));
            assertEquals("ROLLED_BACK", fixture.journal.getOperation(creation, "alice").get("status"));
            for (String id : forward.keySet()) {
                assertEquals("COMPLETED", fixture.journal.getOperation(id, "alice").get("status"));
            }
        }
    }

    @Test public void forwardOperationAddedAfterPreviewInvalidatesConfirmation() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String session = fixture.journal.createSession("alice");
            String set = fixture.journal.createChangeSet(session, "alice");
            String creation = fixture.journal.openOperation("create_screen", "alice", session, set);
            fixture.journal.runOperation(creation, () -> {
                change(fixture.journal, handler, "screen:1", "absent", "created");
                return "created";
            });
            Map<String, Object> preview = fixture.journal.rollback("alice", null, null, session, true, null);
            String assignment = fixture.journal.openOperation("assign_workflow", "alice", session, set);
            fixture.journal.runOperation(assignment, () -> "assigned");
            expect(InvalidRequestException.class, () -> confirm(fixture.journal, "alice", preview));
            assertEquals("created", handler.values.get("screen:1"));
            assertEquals(0, handler.undoCalls.get());
        }
    }

    @Test public void ownershipAndSingleUsePlanAreEnforcedAndScopeComesFromPlan() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            fixture.journal.runOperation(operation, () -> { change(fixture.journal, handler, "field:1", "0", "1"); return "ok"; });
            Map<String, Object> preview = fixture.journal.rollback("alice", operation, null, null, true, null);
            fixture.currentActor.set(UndoTestFixture.user("bob"));
            expect(AccessException.class, () -> fixture.journal.getOperation(operation, "alice"));
            expect(AccessException.class, () -> fixture.journal.getOperation(operation, "bob"));
            expect(InvalidRequestException.class, () -> confirm(fixture.journal, "bob", preview));
            fixture.currentActor.set(UndoTestFixture.user("alice"));
            confirm(fixture.journal, "alice", preview);
            expect(InvalidRequestException.class, () -> confirm(fixture.journal, "alice", preview));
        }
    }

    @Test public void foreignConflictRefusesEntireSelectionBeforeAnyUndo() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            fixture.journal.runOperation(operation, () -> {
                change(fixture.journal, handler, "field:1", "0", "1");
                change(fixture.journal, handler, "screen:1", "0", "1");
                return "ok";
            });
            Map<String, Object> preview = fixture.journal.rollback("alice", operation, null, null, true, null);
            assertEquals("READY", preview.get("status"));
            handler.values.put("field:1", "foreign");
            assertEquals("BLOCKED", confirm(fixture.journal, "alice", preview).get("status"));
            assertEquals("foreign", handler.values.get("field:1"));
            assertEquals("1", handler.values.get("screen:1"));
            assertEquals(0, handler.undoCalls.get());
        }
    }

    @Test public void coherentSuccessiveChangesUndoInReverseAcrossOperationsAndChangesets() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String session = fixture.journal.createSession("alice");
            String firstSet = fixture.journal.createChangeSet(session, "alice");
            String first = fixture.journal.openOperation("change", "alice", session, firstSet);
            fixture.journal.runOperation(first, () -> { change(fixture.journal, handler, "field:1", "0", "1"); return "first"; });
            fixture.journal.finishChangeSet(firstSet, "alice");
            String secondSet = fixture.journal.createChangeSet(session, "alice");
            String second = fixture.journal.openOperation("change", "alice", session, secondSet);
            fixture.journal.runOperation(second, () -> { change(fixture.journal, handler, "field:1", "1", "2"); return "second"; });
            fixture.journal.finishChangeSet(secondSet, "alice");
            Map<String, Object> preview = fixture.journal.rollback("alice", null, null, session, true, null);
            assertEquals("READY", preview.get("status"));
            confirm(fixture.journal, "alice", preview);
            assertEquals("0", handler.values.get("field:1"));
            assertEquals(2, handler.undoCalls.get());
            assertEquals("ROLLED_BACK", fixture.journal.getOperation(first, "alice").get("status"));
            assertEquals("ROLLED_BACK", fixture.journal.getOperation(second, "alice").get("status"));
        }
    }

    @Test public void partialUndoRetriesAlreadyRestoredStateWithoutApplyingTwice() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            fixture.journal.runOperation(operation, () -> {
                change(fixture.journal, handler, "older", "0", "1");
                change(fixture.journal, handler, "middle", "0", "1");
                change(fixture.journal, handler, "newer", "0", "1");
                return "ok";
            });
            handler.failAfterUndo = "middle";
            Map<String, Object> preview = fixture.journal.rollback("alice", operation, null, null, true, null);
            Map<String, Object> partial = confirm(fixture.journal, "alice", preview);
            assertEquals("PARTIAL", partial.get("status"));
            List<?> steps = (List<?>) partial.get("steps");
            assertEquals("newer", ((Map<?, ?>) steps.get(0)).get("resource"));
            assertEquals("UNDONE", ((Map<?, ?>) steps.get(0)).get("status"));
            assertEquals("middle", ((Map<?, ?>) steps.get(1)).get("resource"));
            assertEquals("BLOCKED", ((Map<?, ?>) steps.get(1)).get("status"));
            assertEquals("older", ((Map<?, ?>) steps.get(2)).get("resource"));
            assertEquals("APPLIED", ((Map<?, ?>) steps.get(2)).get("status"));
            assertEquals("PARTIAL", fixture.journal.getOperation(operation, "alice").get("status"));
            assertEquals("1", handler.values.get("older"));
            assertEquals("0", handler.values.get("middle"));
            assertEquals("0", handler.values.get("newer"));
            assertEquals(2, handler.changedByUndo.get());
            Map<String, Object> retry = fixture.journal.rollback("alice", operation, null, null, true, null);
            assertEquals("ROLLED_BACK", confirm(fixture.journal, "alice", retry).get("status"));
            assertEquals("0", handler.values.get("older"));
            assertEquals("0", handler.values.get("middle"));
            assertEquals("0", handler.values.get("newer"));
            assertEquals(3, handler.changedByUndo.get());
            assertTrue(handler.sawPreviouslyUndone);
        }
    }

    @Test public void preparedCrashIsBlockedEvenWhenExpectedPoststateLooksPlausible() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            expect(IllegalStateException.class, () -> fixture.journal.runOperation(operation, () -> {
                fixture.journal.prepare("state", "created:1", "absent", "created");
                handler.values.put("created:1", "created");
                throw new IllegalStateException("simulated crash after Jira create, before applied");
            }));
            assertEquals("BLOCKED", fixture.reopen().getOperation(operation, "alice").get("status"));
            Map<String, Object> preview = fixture.journal.rollback("alice", operation, null, null, true, null);
            assertEquals("BLOCKED", preview.get("status"));
            assertEquals("created", handler.values.get("created:1"));
            assertEquals(0, handler.undoCalls.get());
            assertNull(fixture.journal.currentOperationId());
        }
    }

    @Test public void appliedChunkUpdatesPersistExactPartialStateAndCancellationUnwinds() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            expect(JournalCancelledException.class, () -> fixture.journal.runOperation(operation, () -> {
                handler.values.put("chunk", "0");
                UndoStep chunk = fixture.journal.prepare("state", "chunk", "0", "2", 100);
                handler.values.put("chunk", "1");
                fixture.journal.applied(chunk, "1");
                fixture.journal.checkpoint();
                fixture.journal.prepareUpdate(chunk);
                handler.values.put("chunk", "2");
                fixture.journal.cancel(operation, "alice", true);
                fixture.journal.applied(chunk, "2");
                fixture.journal.checkpoint();
                return "unreachable";
            }));
            assertEquals("0", handler.values.get("chunk"));
            assertEquals("ROLLED_BACK", fixture.journal.getOperation(operation, "alice").get("status"));
            assertEquals(true, fixture.journal.getOperation(operation, "alice").get("cancelRequested"));
        }
    }

    @Test public void repeatedWriteCrashBlocksUndoEvenWhenLastProvenPoststateStillMatches() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            expect(IllegalStateException.class, () -> fixture.journal.runOperation(operation, () -> {
                handler.values.put("chunk", "0");
                UndoStep chunk = fixture.journal.prepare("state", "chunk", "0", "2", 100);
                handler.values.put("chunk", "1");
                fixture.journal.applied(chunk, "1");
                fixture.journal.prepareUpdate(chunk);
                handler.values.put("unconfirmed-member", "changed");
                throw new IllegalStateException("crash during the next native write");
            }));
            ChangeJournal restarted = fixture.reopen();
            assertEquals("BLOCKED", restarted.getOperation(operation, "alice").get("status"));
            assertEquals("BLOCKED", restarted.rollback("alice", operation, null, null, true, null).get("status"));
            assertEquals("1", handler.values.get("chunk"));
            assertEquals("changed", handler.values.get("unconfirmed-member"));
            assertEquals(0, handler.undoCalls.get());
        }
    }

    @Test public void cancellationWithoutRollbackRetainsExactCommittedPartialWork() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            expect(JournalCancelledException.class, () -> fixture.journal.runOperation(operation, () -> {
                change(fixture.journal, handler, "field", "0", "1");
                fixture.journal.cancel(operation, "alice", false);
                fixture.journal.checkpoint();
                return null;
            }));
            assertEquals("CANCELLED", fixture.journal.getOperation(operation, "alice").get("status"));
            assertEquals("1", handler.values.get("field"));
            assertEquals(0, handler.undoCalls.get());
        }
    }

    @Test public void cancellationAfterCompletionRequiresPreviewAndDoesNotWrite() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            fixture.journal.runOperation(operation, () -> { change(fixture.journal, handler, "field", "0", "1"); return "ok"; });
            Map<String, Object> result = fixture.journal.cancel(operation, "alice", true);
            assertEquals(true, result.get("requiresRollbackPreview"));
            assertEquals("COMPLETED", result.get("status"));
            assertEquals("1", handler.values.get("field"));
            expect(InvalidRequestException.class, () -> fixture.journal.rollback("alice", operation, null, null, false, null));
        }
    }

    @Test public void aForwardOnlyToolKeepsItsFinishedResultWhenCancelArrivesAfterItsLastWrite() {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice")) {
            String session = fixture.journal.createSession("alice");
            String set = fixture.journal.createChangeSet(session, "alice");
            String operation = fixture.journal.openOperation("add_field_to_screens", "alice", session, set);
            Map<String, Object> finished = Collections.singletonMap("status", "PARTIAL");
            fixture.journal.runOperation(operation, () -> { fixture.journal.cancel(operation, "alice", false); return finished; });
            Map<String, Object> stored = fixture.journal.getOperation(operation, "alice");
            assertEquals("COMPLETED", stored.get("status"));
            assertEquals(true, stored.get("cancelRequested"));
            assertEquals("PARTIAL", ((Map<?, ?>) stored.get("result")).get("status"));
        }
    }

    @Test public void aForwardOnlyOperationCancelledBeforeItStartedNeverRunsItsWork() {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice")) {
            String session = fixture.journal.createSession("alice");
            String set = fixture.journal.createChangeSet(session, "alice");
            String operation = fixture.journal.openOperation("add_field_to_screens", "alice", session, set);
            fixture.journal.cancel(operation, "alice", false);
            java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
            expect(InvalidRequestException.class, () -> fixture.journal.runOperation(operation, () -> { writes.incrementAndGet(); return "x"; }));
            assertEquals(0, writes.get());
            assertEquals("CANCELLED", fixture.journal.getOperation(operation, "alice").get("status"));
        }
    }

    @Test public void aForwardOnlyPartialResultAfterACancelRequestStaysPartialAndAnExceptionStaysFailed() {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice")) {
            String session = fixture.journal.createSession("alice");
            String set = fixture.journal.createChangeSet(session, "alice");
            String partial = fixture.journal.openOperation("set_field_required", "alice", session, set);
            fixture.journal.runOperation(partial, () -> {
                fixture.journal.cancel(partial, "alice", false);
                return Collections.singletonMap("status", "PARTIALLY_CHANGED");
            });
            Map<String, Object> stored = fixture.journal.getOperation(partial, "alice");
            assertEquals("COMPLETED", stored.get("status"));
            assertEquals("a partial domain result is never turned into success", "PARTIALLY_CHANGED",
                    ((Map<?, ?>) stored.get("result")).get("status"));
            String failing = fixture.journal.openOperation("set_field_required", "alice", session, set);
            expect(IllegalStateException.class, () -> fixture.journal.runOperation(failing, () -> {
                fixture.journal.cancel(failing, "alice", false);
                throw new IllegalStateException("native failure");
            }));
            Map<String, Object> failed = fixture.journal.getOperation(failing, "alice");
            assertEquals("an execution failure is not turned into a completed operation", "FAILED", failed.get("status"));
            assertNull(failed.get("result"));
        }
    }

    @Test public void anUndoableToolStillHonoursACancelRequestedBeforeItCompletes() {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice")) {
            String operation = operation(fixture.journal, "alice");
            expect(JournalCancelledException.class, () ->
                    fixture.journal.runOperation(operation, () -> { fixture.journal.cancel(operation, "alice", false); return "ok"; }));
            assertEquals("CANCELLED", fixture.journal.getOperation(operation, "alice").get("status"));
        }
    }

    @Test public void anOldStoredScreenResultAndANewOneWithUnknownAndNotAttemptedAreBothReadBackThroughTheOperation() throws Exception {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice")) {
            String session = fixture.journal.createSession("alice");
            String set = fixture.journal.createChangeSet(session, "alice");
            // 1.1.8 shape: no UNKNOWN/NOT_ATTEMPTED, no per-pair message.
            Object old = new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"dryRun\":false,\"errorCode\":null,"
                    + "\"fields\":[{\"fieldId\":\"customfield_10500\",\"fieldName\":\"One\",\"results\":["
                    + "{\"screenId\":5,\"name\":\"A\",\"status\":\"ADDED\",\"tab\":\"General\"},"
                    + "{\"screenId\":6,\"name\":null,\"status\":\"FAILED\",\"errorCode\":\"UNKNOWN_SCREEN\",\"message\":\"No screen\"}]}]}",
                    Object.class);
            String first = fixture.journal.openOperation("add_field_to_screens", "alice", session, set);
            fixture.journal.runOperation(first, () -> old);
            ru.rasulov.customfields.api.dto.screen.AddToScreensResponse current =
                    new ru.rasulov.customfields.api.dto.screen.AddToScreensResponse(false, java.util.Collections.singletonList(
                            new ru.rasulov.customfields.api.dto.screen.FieldScreensResult("customfield_10500", "One", java.util.Arrays.asList(
                                    ru.rasulov.customfields.api.dto.screen.ScreenResult.added(5, "A", "General"),
                                    ru.rasulov.customfields.api.dto.screen.ScreenResult.unknown(6L, "B", "not confirmed"),
                                    ru.rasulov.customfields.api.dto.screen.ScreenResult.notAttempted(7L, "stopped")))));
            String second = fixture.journal.openOperation("add_field_to_screens", "alice", session, set);
            fixture.journal.runOperation(second, () -> current);
            ChangeJournal reopened = fixture.reopen();
            for (String[] expected : new String[][] {{first, "ADDED", "FAILED"}, {second, "ADDED", "UNKNOWN", "NOT_ATTEMPTED"}}) {
                Map<String, Object> stored = reopened.getOperation(expected[0], "alice");
                assertEquals("COMPLETED", stored.get("status"));
                java.util.List<?> fields = (java.util.List<?>) ((Map<?, ?>) stored.get("result")).get("fields");
                java.util.List<?> results = (java.util.List<?>) ((Map<?, ?>) fields.get(0)).get("results");
                for (int i = 1; i < expected.length; i++) {
                    assertEquals(expected[i], ((Map<?, ?>) results.get(i - 1)).get("status"));
                }
            }
        }
    }

    @Test public void expiryCascadesInBatchesButNeverDeletesUnresolvedRecords() throws Exception {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String complete = operation(fixture.journal, "alice");
            fixture.journal.runOperation(complete, () -> { change(fixture.journal, handler, "complete", "0", "1"); return "ok"; });
            String completeSet = (String) fixture.journal.getOperation(complete, "alice").get("changeSetId");
            fixture.journal.finishChangeSet(completeSet, "alice");
            String unresolved = operation(fixture.journal, "alice");
            expect(IllegalStateException.class, () -> fixture.journal.runOperation(unresolved, () -> {
                fixture.journal.prepare("state", "uncertain", "0", "1");
                throw new IllegalStateException("unproven native write");
            }));
            String unresolvedSet = (String) fixture.journal.getOperation(unresolved, "alice").get("changeSetId");
            fixture.journal.finishChangeSet(unresolvedSet, "alice");
            fixture.clock.addAndGet(JournalStore.UNDO_WINDOW_MILLIS + 1);
            expect(InvalidRequestException.class, () -> fixture.journal.rollback("alice", complete, null, null, true, null));
            for (int pass = 0; pass < 10; pass++) fixture.journal.cleanupBatch(2);
            expect(AccessException.class, () -> fixture.journal.getOperation(complete, "alice"));
            assertEquals("BLOCKED", fixture.journal.getOperation(unresolved, "alice").get("status"));
            assertEquals(1, fixture.entityManager.count(JournalStep.class));
            assertEquals(1, fixture.entityManager.count(JournalOperation.class));
            assertEquals(1, fixture.entityManager.count(JournalChangeSet.class));
            assertEquals(1, fixture.entityManager.count(JournalSession.class));
            assertTrue(fixture.store.usedBytes() > 0);
        }
    }

    @Test public void utf8PayloadLimitIsBytesNotCharacters() {
        String exact = "\"" + repeat('\u00e9', 32767) + "\"";
        assertEquals(65536, JournalStore.jsonBytes(exact));
        JournalStore.checkJson(exact, null);
        expect(JournalCapacityException.class, () -> JournalStore.checkJson(exact, "x"));
        assertEquals(4, JournalStore.jsonBytes("\ud83d\ude00"));
    }

    @Test public void globalCapacityTransactionsAreAtomicAcrossConcurrentStoreInstances() throws Exception {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", 100)) {
            JournalStore other = fixture.reopenStore();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> first = executor.submit(() -> chargeAtBarrier(fixture.store, ready, go));
                Future<Boolean> second = executor.submit(() -> chargeAtBarrier(other, ready, go));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                go.countDown();
                assertTrue(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS));
                assertEquals(60, fixture.store.usedBytes());
            } finally { executor.shutdownNow(); }
        }
    }

    @Test public void reserveFailsBeforeMutationAndNestedExecuteSharesOneOperation() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            AtomicInteger writes = new AtomicInteger();
            String operation = operation(fixture.journal, "alice");
            expect(JournalCapacityException.class, () -> fixture.journal.runOperation(operation, () -> {
                fixture.journal.reserve(JournalStore.DEFAULT_CAPACITY);
                writes.incrementAndGet();
                return null;
            }));
            assertEquals(0, writes.get());
            assertNull(fixture.journal.currentOperationId());
            fixture.journal.execute("outer", "alice", () -> fixture.journal.execute("inner", "alice", () -> {
                change(fixture.journal, handler, "field", "0", "1");
                return "nested";
            }));
            assertEquals(2, fixture.journal.listSessions("alice").size());
        }
    }

    @Test public void largeValidResultIsPersistedWithoutUnintendedCompensation() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            String large = repeat('x', 100000);
            fixture.journal.runOperation(operation, () -> {
                change(fixture.journal, handler, "field", "0", "1");
                return Collections.singletonMap("message", large);
            });
            assertEquals("COMPLETED", fixture.journal.getOperation(operation, "alice").get("status"));
            assertEquals(large, ((Map<?, ?>) fixture.reopen().getOperation(operation, "alice").get("result")).get("message"));
            assertEquals("1", handler.values.get("field"));
            assertEquals(0, handler.undoCalls.get());
        }
    }

    private static Map<String, Object> oversizedResult() {
        return Collections.<String, Object>singletonMap("blob", repeat('x', ChangeJournal.RESULT_JSON_LIMIT + 1));
    }

    /** The write was proven by two steps; the result then does not fit. The reaction is compensation of those steps only. */
    @Test public void anOversizedResultOfAnUndoableOperationCompensatesOnlyItsOwnStepsAndReplayDoesNotRunItAgain() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            handler.values.put("bystander", "7");
            String operation = operation(fixture.journal, "alice");
            AtomicInteger runs = new AtomicInteger();
            try {
                fixture.journal.runOperation(operation, () -> {
                    runs.incrementAndGet();
                    change(fixture.journal, handler, "field:1", "0", "1");
                    change(fixture.journal, handler, "screen:1", "0", "1");
                    return oversizedResult();
                });
                fail("the result does not fit");
            } catch (JournalCapacityException expected) { }
            Map<String, Object> stored = fixture.reopen().getOperation(operation, "alice");
            assertEquals("ROLLED_BACK", stored.get("status"));
            assertNull("no part of the oversized result is stored", stored.get("result"));
            assertTrue(String.valueOf(stored.get("error")), String.valueOf(stored.get("error")).contains("result could not be stored"));
            assertEquals("0", handler.values.get("field:1"));
            assertEquals("0", handler.values.get("screen:1"));
            assertEquals("a resource of no step of this operation is never touched", "7", handler.values.get("bystander"));
            assertEquals(2, handler.undoCalls.get());
            expect(ru.rasulov.common.api.exception.InvalidRequestException.class, () -> fixture.journal.runOperation(operation, () -> { runs.incrementAndGet(); return "again"; }));
            assertEquals("a replay of a finished operation never runs the work again", 1, runs.get());
            assertEquals(2, handler.undoCalls.get());
            assertEquals("ROLLED_BACK", fixture.reopen().getOperation(operation, "alice").get("status"));
        }
    }

    /** The first compensation step succeeds, the second fails: nothing may read as fully restored. */
    @Test public void aCompensationThatCannotFinishLeavesAPartialOperationWithTheUnrestoredStepNamed() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            handler.failAfterUndo = "field:1";
            try {
                fixture.journal.runOperation(operation, () -> {
                    change(fixture.journal, handler, "field:1", "0", "1");
                    change(fixture.journal, handler, "screen:1", "0", "1");
                    return oversizedResult();
                });
                fail("the result does not fit");
            } catch (JournalCapacityException expected) { }
            Map<String, Object> stored = fixture.reopen().getOperation(operation, "alice");
            assertEquals("PARTIAL", stored.get("status"));
            assertNull(stored.get("result"));
            Map<String, String> states = new java.util.LinkedHashMap<>();
            for (Object step : (List<?>) stored.get("steps")) states.put(String.valueOf(((Map<?, ?>) step).get("resource")), String.valueOf(((Map<?, ?>) step).get("status")));
            assertEquals("UNDONE", states.get("screen:1"));
            assertEquals("the step whose compensation failed is not reported as restored", "BLOCKED", states.get("field:1"));
            int undoCalls = handler.undoCalls.get();
            expect(ru.rasulov.common.api.exception.InvalidRequestException.class, () -> fixture.journal.runOperation(operation, () -> "again"));
            assertEquals(undoCalls, handler.undoCalls.get());
            assertEquals("PARTIAL", fixture.reopen().getOperation(operation, "alice").get("status"));
        }
    }

    /** Another administrator changed a resource of this operation before compensation: nothing is written back. */
    @Test public void aForeignEditBeforeCompensationBlocksItAndIsNeverOverwritten() {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            try {
                fixture.journal.runOperation(operation, () -> {
                    change(fixture.journal, handler, "field:1", "0", "1");
                    change(fixture.journal, handler, "screen:1", "0", "1");
                    handler.values.put("field:1", "foreign");
                    return oversizedResult();
                });
                fail("the result does not fit");
            } catch (JournalCapacityException expected) { }
            Map<String, Object> stored = fixture.reopen().getOperation(operation, "alice");
            assertEquals("BLOCKED", stored.get("status"));
            assertTrue(String.valueOf(stored.get("error")), String.valueOf(stored.get("error")).contains("Foreign value"));
            assertEquals("foreign", handler.values.get("field:1"));
            assertEquals("a proven write of this operation stays and is not claimed as restored", "1", handler.values.get("screen:1"));
            assertEquals("nothing is written back when any step conflicts", 0, handler.undoCalls.get());
            for (Object step : (List<?>) stored.get("steps")) assertEquals("APPLIED", ((Map<?, ?>) step).get("status"));
            expect(ru.rasulov.common.api.exception.InvalidRequestException.class, () -> fixture.journal.runOperation(operation, () -> "again"));
            assertEquals(0, handler.undoCalls.get());
        }
    }

    @Test public void abandonedWorkerRecoveryRetainsIntentAndBecomesActionable() throws Exception {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            fixture.store.locked(db -> {
                JournalOperation row = JournalStore.one(db, JournalOperation.class, "OPERATION_KEY", operation);
                row.setState("RUNNING");
                row.save();
                db.create(JournalStep.class, new DBParam("OPERATION_KEY", operation), new DBParam("KIND", "state"),
                        new DBParam("RESOURCE", "crash"), new DBParam("BEFORE_JSON", "0"), new DBParam("AFTER_JSON", "1"),
                        new DBParam("STATE", "PREPARED"), new DBParam("CREATED_AT", fixture.clock.get()),
                        new DBParam("ALLOCATED_BYTES", 0L), new DBParam("MAX_AFTER_BYTES", 100));
                return null;
            });
            fixture.clock.addAndGet(TimeUnit.HOURS.toMillis(2));
            fixture.reopen().cleanupBatch(100);
            assertEquals("BLOCKED", fixture.journal.getOperation(operation, "alice").get("status"));
            assertEquals("BLOCKED", fixture.journal.rollback("alice", operation, null, null, true, null).get("status"));
            assertEquals(1, fixture.entityManager.count(JournalStep.class));
        }
    }

    @Test public void executionLocksUseABoundedSetOfNamesAndRecordTheSlot() {
        try (UndoTestFixture fixture = UndoTestFixture.create("alice")) {
            java.util.Set<String> operations = new java.util.HashSet<>();
            for (int i = 0; i < ChangeJournal.EXECUTION_SLOTS + 50; i++) {
                String operation = operation(fixture.journal, "alice");
                operations.add(operation);
                fixture.journal.runOperation(operation, () -> "done");
                int slot = fixture.store.locked(db -> JournalStore.one(db, JournalOperation.class, "OPERATION_KEY", operation).getExecutionSlot());
                assertTrue("slot " + slot, slot >= 0 && slot < ChangeJournal.EXECUTION_SLOTS);
            }
            int executionNames = 0;
            for (String name : fixture.lockNames()) {
                for (String operation : operations) assertFalse(name, name.contains(operation));
                if (name.startsWith(ChangeJournal.EXECUTION_LOCK_PREFIX)) executionNames++;
            }
            assertTrue("execution lock names: " + executionNames, executionNames <= ChangeJournal.EXECUTION_SLOTS);
        }
    }

    @Test public void completedUndoProofSurvivesRestartAndAuthorizesOnlyTheSelectedRetry() {
        Map<String, String> values = new ConcurrentHashMap<>();
        AtomicInteger attempts = new AtomicInteger();
        String proofHash = repeat('a', 64);
        UndoHandler handler = new UndoHandler() {
            public String kind() { return "receipt"; }
            public List<String> lockNames(UndoStep step) { return Collections.singletonList("receipt.native"); }
            public String conflict(UndoStep step) { return null; }
            public String conflict(UndoStep step, List<UndoStep> selection, Map<Long, UndoReceipt> receipts) {
                if ("older".equals(step.getResource()) && "0".equals(values.get("newer"))) {
                    UndoReceipt proof = receipts.get(42L);
                    return proof != null && proof.getUpdated() == 900L && proofHash.equals(proof.getStateHash())
                            ? null : "The later native restore has no durable proof";
                }
                return null;
            }
            public void undo(UndoStep step, ApplicationUser actor) { throw new AssertionError("Receipt context is required"); }
            public void undo(UndoStep step, ApplicationUser actor, List<UndoStep> selection, Map<Long, UndoReceipt> receipts) {
                String conflict = conflict(step, selection, receipts);
                if (conflict != null) throw new IllegalStateException(conflict);
                if ("older".equals(step.getResource()) && attempts.getAndIncrement() == 0) {
                    throw new IllegalStateException("Native write unavailable");
                }
                values.put(step.getResource(), step.getBeforeJson());
            }
            public String undoReceipt(UndoStep step) {
                return "newer".equals(step.getResource())
                        ? JournalJson.write(Collections.singletonMap("issues", Collections.singletonList(java.util.Arrays.asList(42L, 900L, proofHash))))
                        : null;
            }
        };
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            fixture.journal.runOperation(operation, () -> {
                for (String resource : java.util.Arrays.asList("older", "newer")) {
                    UndoStep step = fixture.journal.prepare("receipt", resource, "0", "1", 1);
                    values.put(resource, "1");
                    fixture.journal.applied(step, "1");
                }
                return "ok";
            });
            Map<String, Object> preview = fixture.journal.rollback("alice", operation, null, null, true, null);
            assertEquals("PARTIAL", confirm(fixture.journal, "alice", preview).get("status"));
            assertEquals("1", values.get("older"));
            assertEquals("0", values.get("newer"));
            ChangeJournal restarted = fixture.reopen();
            Map<String, Object> retry = restarted.rollback("alice", operation, null, null, true, null);
            assertEquals("READY", retry.get("status"));
            assertEquals("ROLLED_BACK", confirm(restarted, "alice", retry).get("status"));
            assertEquals("0", values.get("older"));
        }
    }

    @Test public void unresolvedExpiredUndoRemainsRecoverableAndIsPurgedAfterResolution() throws Exception {
        StateHandler handler = new StateHandler();
        try (UndoTestFixture fixture = UndoTestFixture.create("alice", handler)) {
            String operation = operation(fixture.journal, "alice");
            fixture.journal.runOperation(operation, () -> {
                change(fixture.journal, handler, "older", "0", "1");
                change(fixture.journal, handler, "newer", "0", "1");
                return "ok";
            });
            String set = (String) fixture.journal.getOperation(operation, "alice").get("changeSetId");
            fixture.journal.finishChangeSet(set, "alice");
            handler.failAfterUndo = "older";
            confirm(fixture.journal, "alice", fixture.journal.rollback("alice", operation, null, null, true, null));
            fixture.clock.addAndGet(JournalStore.UNDO_WINDOW_MILLIS + 1);
            fixture.journal.cleanupBatch(100);
            assertEquals(2, fixture.entityManager.count(JournalStep.class));
            ChangeJournal restarted = fixture.reopen();
            Map<String, Object> retry = restarted.rollback("alice", null, set, null, true, null);
            assertEquals("READY", retry.get("status"));
            assertEquals("ROLLED_BACK", confirm(restarted, "alice", retry).get("status"));
            restarted.cleanupBatch(100);
            assertEquals(0, fixture.entityManager.count(JournalStep.class));
            assertEquals(0, fixture.entityManager.count(JournalOperation.class));
            assertEquals(0L, fixture.store.usedBytes());
        }
    }

    private static boolean chargeAtBarrier(JournalStore store, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("capacity test barrier");
        try { store.locked(db -> { store.charge(db, 60); return null; }); return true; }
        catch (JournalCapacityException full) { return false; }
    }

    static String operation(ChangeJournal journal, String actor) {
        String session = journal.createSession(actor);
        String set = journal.createChangeSet(session, actor);
        return journal.openOperation("test.change", actor, session, set);
    }

    static Map<String, Object> confirm(ChangeJournal journal, String actor, Map<String, Object> preview) {
        return journal.rollback(actor, null, null, null, false, (String) preview.get("planId"));
    }

    static void change(ChangeJournal journal, StateHandler handler, String resource, String before, String after) {
        handler.values.putIfAbsent(resource, before);
        UndoStep step = journal.prepare("state", resource, before, after, 100);
        handler.values.put(resource, after);
        journal.applied(step, after);
    }

    static String repeat(char value, int count) { char[] chars = new char[count]; java.util.Arrays.fill(chars, value); return new String(chars); }

    private static void expect(Class<? extends Throwable> type, Runnable work) {
        try { work.run(); fail("Expected " + type.getSimpleName()); }
        catch (Throwable failure) { if (!type.isInstance(failure)) throw failure; }
    }

    static final class StateHandler implements UndoHandler {
        final Map<String, String> values = new ConcurrentHashMap<>();
        final AtomicInteger undoCalls = new AtomicInteger();
        final AtomicInteger changedByUndo = new AtomicInteger();
        String failAfterUndo;
        boolean sawPreviouslyUndone;
        public String kind() { return "state"; }
        public List<String> lockNames(UndoStep step) { return Collections.singletonList("test.native.state"); }
        public String conflict(UndoStep step) {
            String value = values.get(step.getResource());
            return java.util.Objects.equals(value, step.getBeforeJson()) || java.util.Objects.equals(value, step.getAfterJson()) ? null : "Foreign value: " + value;
        }
        public void undo(UndoStep step, ApplicationUser actor) { undo(step, actor, Collections.emptyList()); }
        public void undo(UndoStep step, ApplicationUser actor, List<UndoStep> selection) {
            undoCalls.incrementAndGet();
            for (UndoStep selected : selection) if ("UNDONE".equals(selected.getState())) sawPreviouslyUndone = true;
            if (java.util.Objects.equals(values.get(step.getResource()), step.getBeforeJson())) return;
            String conflict = conflict(step);
            if (conflict != null) throw new IllegalStateException(conflict);
            values.put(step.getResource(), step.getBeforeJson());
            changedByUndo.incrementAndGet();
            if (step.getResource().equals(failAfterUndo)) {
                failAfterUndo = null;
                throw new IllegalStateException("simulated process failure after compensation write");
            }
        }
    }
}
