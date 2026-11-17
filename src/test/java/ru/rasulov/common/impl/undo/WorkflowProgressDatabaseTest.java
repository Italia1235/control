package ru.rasulov.common.impl.undo;

import net.java.ao.DBParam;
import net.java.ao.Query;
import org.junit.Test;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Workflow receipts use real AO persistence, including independently reopened readers and crash recovery. */
public class WorkflowProgressDatabaseTest {
    @Test public void nativeAcknowledgementIsDurableBeforeReadbackAndTerminalResultSurvivesReopening() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = operation(db, "configure_workflow");
            Map<String, Object> execution = execution("NOT_ATTEMPTED", "NOT_CHECKED", null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "PARTIAL");
            result.put("targetVersion", "DRAFT");
            db.journal.runOperation(id, () -> {
                db.journal.startWorkflowProgress(execution);
                long allocated = db.store.usedBytes();
                assertEquals(execution, db.reopen().getOperation(id, "admin").get("execution"));
                execution.put("steps", steps("UNKNOWN", "NOT_CHECKED", null));
                db.journal.workflowProgress(execution, false);
                assertEquals(execution, db.reopen().getOperation(id, "admin").get("execution"));
                execution.put("steps", steps("APPLIED", "NOT_CHECKED", "10001"));
                db.journal.workflowProgress(execution, false);
                // A read-back failure cannot erase the preceding native acknowledgement and known ID.
                assertEquals(execution, db.reopen().getOperation(id, "admin").get("execution"));
                execution.put("steps", steps("APPLIED", "UNAVAILABLE", "10001"));
                db.journal.workflowProgress(execution, false);
                assertEquals(allocated, db.store.usedBytes());
                return result;
            });
            Map<String, Object> saved = db.reopen().getOperation(id, "admin");
            assertEquals("COMPLETED", saved.get("status"));
            assertEquals(result, saved.get("result"));
            assertEquals(execution, saved.get("execution"));
            assertTrue(((List<?>) saved.get("steps")).isEmpty());
            assertEquals(0, db.entityManager.count(JournalStep.class));
            assertEquals(0, db.entityManager.count(JournalProgressBatch.class));
            JournalProgress receipt = db.entityManager.find(JournalProgress.class)[0];
            assertEquals(JournalStore.bytes(id) + ChangeJournal.PROGRESS_JSON_LIMIT, receipt.getAllocatedBytes());
            assertEquals(0L, receipt.getReservedBytes());
            assertFalse(receipt.isSettled());
            db.currentActor.set(UndoTestFixture.user("other"));
            expect(AccessException.class, () -> db.reopen().getOperation(id, "other"));
        }
    }

    @Test public void receiptAcknowledgementIsSavedEvenWhenCancellationArrivesAfterTheNativeCall() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = operation(db, "configure_workflow");
            Map<String, Object> applied = execution("APPLIED", "MATCH", "10001");
            db.journal.runOperation(id, () -> {
                db.journal.startWorkflowProgress(execution("UNKNOWN", "NOT_CHECKED", null));
                db.journal.cancel(id, "admin", true);
                db.journal.workflowProgress(applied, true);
                return Collections.singletonMap("status", "CREATED");
            });
            Map<String, Object> saved = db.reopen().getOperation(id, "admin");
            assertEquals("COMPLETED", saved.get("status"));
            assertEquals(applied, saved.get("execution"));
            assertEquals("CREATED", ((Map<?, ?>) saved.get("result")).get("status"));
            assertFalse(db.lockNames().contains("ru.rasulov.control.undo.rollback"));
        }
    }

    @Test public void exceptionPreservesUnknownIntentWithoutRollbackOrInventingAnObjectId() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = operation(db, "configure_workflow");
            Map<String, Object> unknown = execution("UNKNOWN", "NOT_CHECKED", null);
            AtomicInteger nativeCalls = new AtomicInteger();
            expect(IllegalStateException.class, () -> db.journal.runOperation(id, () -> {
                db.journal.startWorkflowProgress(execution("NOT_ATTEMPTED", "NOT_CHECKED", null));
                db.journal.workflowProgress(unknown, false);
                nativeCalls.incrementAndGet();
                throw new IllegalStateException("Lost native response; effect is possible");
            }));
            Map<String, Object> saved = db.reopen().getOperation(id, "admin");
            assertEquals("FAILED", saved.get("status"));
            assertEquals(unknown, saved.get("execution"));
            assertEquals(1, nativeCalls.get());
            assertFalse(db.lockNames().contains("ru.rasulov.control.undo.rollback"));
            expect(InvalidRequestException.class, () -> db.journal.runOperation(id, () -> nativeCalls.incrementAndGet()));
            assertEquals(1, nativeCalls.get());
        }
    }

    @Test public void stoppedWorkerBecomesBlockedAndLeavesEveryDurableStepExactlyAsSaved() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = operation(db, "configure_workflow");
            Map<String, Object> receipt = execution("UNKNOWN", "NOT_CHECKED", null);
            // Reconstruct the exact committed state of a process that stopped after persisting its native-call intent.
            db.store.locked(database -> {
                JournalOperation row = JournalStore.one(database, JournalOperation.class, "OPERATION_KEY", id);
                row.setState("RUNNING");
                row.setStartedAt(db.clock.get());
                row.setExecutionSlot(0);
                row.save();
                long allocation = JournalStore.bytes(id) + ChangeJournal.PROGRESS_JSON_LIMIT;
                db.store.charge(database, allocation);
                database.create(JournalProgress.class, new DBParam("OPERATION_KEY", id),
                        new DBParam("EXECUTION_JSON", JournalJson.write(receipt)), new DBParam("SETTLED", false),
                        new DBParam("ALLOCATED_BYTES", allocation), new DBParam("RESERVED_BYTES", 0L));
                return null;
            });
            db.clock.addAndGet(TimeUnit.HOURS.toMillis(2));
            ChangeJournal restarted = db.reopen();
            restarted.cleanupBatch(100);
            Map<String, Object> saved = db.reopen().getOperation(id, "admin");
            assertEquals("BLOCKED", saved.get("status"));
            assertEquals(receipt, saved.get("execution"));
            assertNull(saved.get("result"));
            assertTrue(((String) saved.get("error")).contains("worker stopped"));
            restarted.cleanupBatch(100);
            assertEquals(saved, db.reopen().getOperation(id, "admin"));
            expect(InvalidRequestException.class, () -> restarted.runOperation(id, () -> failNativeCall()));
            assertEquals(1, db.store.locked(database -> database.count(JournalProgress.class, Query.select())).intValue());
        }
    }

    @Test public void fullUtf8BudgetIsAcceptedButOversizedUpdatesCannotReplaceTheLastReceipt() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = operation(db, "configure_workflow");
            int overhead = JournalStore.jsonBytes(JournalJson.write(Collections.singletonMap("payload", "")));
            String payload = ChangeJournalDatabaseTest.repeat('x', ChangeJournal.PROGRESS_JSON_LIMIT - overhead);
            Map<String, String> boundary = Collections.singletonMap("payload", payload);
            assertEquals(ChangeJournal.PROGRESS_JSON_LIMIT, JournalStore.jsonBytes(JournalJson.write(boundary)));
            db.journal.runOperation(id, () -> {
                db.journal.startWorkflowProgress(boundary);
                long allocated = db.store.usedBytes();
                expect(JournalCapacityException.class, () -> db.journal.workflowProgress(
                        Collections.singletonMap("payload", payload + "λ"), true));
                assertEquals(boundary, db.reopen().getOperation(id, "admin").get("execution"));
                assertEquals(allocated, db.store.usedBytes());
                db.journal.workflowProgress(boundary, true);
                return Collections.singletonMap("status", "CREATED");
            });
        }
    }

    @Test public void allocationAndOversizedInitialReceiptRefuseBeforeNativeWork() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin", ChangeJournal.RESULT_JSON_LIMIT + 16384L)) {
            String id = operation(db, "configure_workflow");
            AtomicInteger nativeCalls = new AtomicInteger();
            expect(JournalCapacityException.class, () -> db.journal.runOperation(id, () -> {
                db.journal.startWorkflowProgress(execution("NOT_ATTEMPTED", "NOT_CHECKED", null));
                return nativeCalls.incrementAndGet();
            }));
            assertEquals(0, nativeCalls.get());
            assertEquals(0, db.entityManager.count(JournalProgress.class));
        }
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = operation(db, "configure_workflow");
            AtomicInteger nativeCalls = new AtomicInteger();
            expect(JournalCapacityException.class, () -> db.journal.runOperation(id, () -> {
                db.journal.startWorkflowProgress(Collections.singletonMap("payload",
                        ChangeJournalDatabaseTest.repeat('λ', ChangeJournal.PROGRESS_JSON_LIMIT / 2)));
                return nativeCalls.incrementAndGet();
            }));
            assertEquals(0, nativeCalls.get());
            assertEquals(0, db.entityManager.count(JournalProgress.class));
        }
    }

    @Test public void receiptMustBeAllocatedOnceAndOnlyInsideItsOwningWorkflowOperation() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            expect(IllegalStateException.class, () -> db.journal.startWorkflowProgress(Collections.emptyMap()));
            String workflow = operation(db, "configure_workflow");
            db.journal.runOperation(workflow, () -> {
                expect(InvalidRequestException.class, () -> db.journal.workflowProgress(Collections.emptyMap(), true));
                db.journal.startWorkflowProgress(Collections.emptyMap());
                expect(InvalidRequestException.class, () -> db.journal.startWorkflowProgress(Collections.emptyMap()));
                expect(InvalidRequestException.class, () -> db.journal.issueTypeProgress(Collections.emptyMap(), true));
                return null;
            });
            String screen = operation(db, "create_screen");
            db.journal.runOperation(screen, () -> {
                expect(InvalidRequestException.class, () -> db.journal.startWorkflowProgress(Collections.emptyMap()));
                return null;
            });
            assertEquals(1, db.entityManager.count(JournalProgress.class));
        }
    }

    @Test public void unfinishedWorkflowReceiptKeepsThirtyDayRetentionAndReleasesExactlyItsAllocation() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = operation(db, "configure_workflow");
            Map<String, Object> receipt = execution("UNKNOWN", "NOT_CHECKED", null);
            db.journal.runOperation(id, () -> {
                db.journal.startWorkflowProgress(receipt);
                return Collections.singletonMap("status", "UNKNOWN");
            });
            long allocation = db.entityManager.find(JournalProgress.class)[0].getAllocatedBytes();
            db.clock.addAndGet(TimeUnit.DAYS.toMillis(30) - 1);
            db.journal.createSession("admin");
            assertEquals(receipt, db.reopen().getOperation(id, "admin").get("execution"));
            long before = db.store.usedBytes();
            db.clock.addAndGet(2);
            String session = db.journal.createSession("admin");
            assertEquals(0, db.entityManager.count(JournalProgress.class));
            assertEquals(before - allocation + JournalStore.bytes(session, "admin"), db.store.usedBytes());
            assertEquals("COMPLETED", db.reopen().getOperation(id, "admin").get("status"));
        }
    }

    @Test public void settledReceiptIsCleanedWithItsOperationWithoutLeakingBudget() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = operation(db, "configure_workflow");
            db.journal.runOperation(id, () -> {
                Map<String, Object> receipt = execution("APPLIED", "MATCH", "10001");
                db.journal.startWorkflowProgress(receipt);
                db.journal.workflowProgress(receipt, true);
                return Collections.singletonMap("status", "CREATED");
            });
            db.journal.finishChangeSet((String) db.journal.getOperation(id, "admin").get("changeSetId"), "admin");
            db.clock.addAndGet(JournalStore.UNDO_WINDOW_MILLIS + 1);
            db.reopen().cleanupBatch(100);
            assertEquals(0, db.entityManager.count(JournalProgress.class));
            assertEquals(0, db.entityManager.count(JournalOperation.class));
            assertEquals(0L, db.store.usedBytes());
        }
    }

    @Test public void auditKeepsHistoricalWorkflowCreationUndoableButUnifiedConfigurationForwardOnly() {
        ChangeJournalDatabaseTest.StateHandler handler = new ChangeJournalDatabaseTest.StateHandler();
        try (UndoTestFixture db = UndoTestFixture.create("admin", handler)) {
            String historical = operation(db, "create_workflow");
            db.journal.runOperation(historical, () -> {
                ChangeJournalDatabaseTest.change(db.journal, handler, "workflow:old", "absent", "created");
                return "created";
            });
            Map<String, Object> preview = db.journal.rollback("admin", historical, null, null, true, null);
            assertTrue(((List<?>) preview.get("nonUndoableOperations")).isEmpty());
            ChangeJournalDatabaseTest.confirm(db.journal, "admin", preview);
            assertEquals("absent", handler.values.get("workflow:old"));
            for (String tool : Arrays.asList("configure_workflow", "configure_workflow_validators", "configure_workflow_conditions")) {
                String id = operation(db, tool);
                db.journal.runOperation(id, () -> Collections.singletonMap("status", "CREATED"));
                Map<String, Object> forward = db.journal.rollback("admin", id, null, null, true, null);
                List<?> operations = (List<?>) forward.get("nonUndoableOperations");
                assertEquals(1, operations.size());
                assertEquals(tool, ((Map<?, ?>) operations.get(0)).get("toolName"));
                ChangeJournalDatabaseTest.confirm(db.journal, "admin", forward);
                assertEquals("COMPLETED", db.reopen().getOperation(id, "admin").get("status"));
            }
            assertEquals(1, handler.undoCalls.get());
        }
    }

    @Test public void compactBoundaryReceiptReopensLosslesslyAndKeepsTheFullTerminalExecution() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = operation(db, "configure_workflow");
            Map<String, Object> intent = WorkflowReceiptCodecTest.boundary("NOT_ATTEMPTED", "NOT_CHECKED", null);
            Map<String, Object> completed = WorkflowReceiptCodecTest.boundary("APPLIED", "MATCH", "9223372036854775807");
            Map<String, Object> result = Map.of("status", "CREATED", "execution", completed);
            db.journal.runOperation(id, () -> {
                db.journal.startWorkflowProgress(intent);
                long allocated = db.store.usedBytes();
                assertEquals(intent, db.reopen().getOperation(id, "admin").get("execution"));
                db.journal.workflowProgress(completed, true);
                assertEquals(completed, db.reopen().getOperation(id, "admin").get("execution"));
                assertEquals(allocated, db.store.usedBytes());
                return result;
            });
            JournalProgress row = db.entityManager.find(JournalProgress.class)[0];
            assertTrue(row.getExecutionJson().contains("\"workflowReceipt\":1"));
            assertTrue(JournalStore.jsonBytes(row.getExecutionJson()) <= ChangeJournal.PROGRESS_JSON_LIMIT);
            assertTrue(JournalStore.jsonBytes(JournalJson.write(completed)) > ChangeJournal.PROGRESS_JSON_LIMIT);
            assertTrue(row.isSettled());
            Map<String, Object> saved = db.reopen().getOperation(id, "admin");
            assertEquals("COMPLETED", saved.get("status"));
            assertEquals(completed, saved.get("execution"));
            assertEquals(result, saved.get("result"));
            assertEquals(saved, db.reopen().getOperation(id, "admin"));
        }
    }

    @Test public void compactCrashRecoveryRetainsAppliedUnknownAndUnattemptedNativeBoundaries() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = operation(db, "configure_workflow");
            Map<String, Object> receipt = WorkflowReceiptCodecTest.boundary("NOT_ATTEMPTED", "NOT_CHECKED", null);
            List<Map<String, Object>> nativeSteps = WorkflowReceiptCodecTest.steps(receipt);
            for (int index = 0; index < 519; index++) {
                Map<String, Object> step = nativeSteps.get(index);
                step.put("writeStatus", "APPLIED");
                step.put("verification", "MATCH");
                step.put("id", "9223372036854775807");
            }
            nativeSteps.get(519).put("writeStatus", "UNKNOWN");
            nativeSteps.get(519).put("error", "\u0000".repeat(WorkflowReceiptCodec.ERROR_LENGTH));
            db.store.locked(database -> {
                JournalOperation row = JournalStore.one(database, JournalOperation.class, "OPERATION_KEY", id);
                row.setState("RUNNING");
                row.setStartedAt(db.clock.get());
                row.setExecutionSlot(0);
                row.save();
                long allocation = JournalStore.bytes(id) + ChangeJournal.PROGRESS_JSON_LIMIT;
                db.store.charge(database, allocation);
                database.create(JournalProgress.class, new DBParam("OPERATION_KEY", id),
                        new DBParam("EXECUTION_JSON", WorkflowReceiptCodec.write(receipt)), new DBParam("SETTLED", false),
                        new DBParam("ALLOCATED_BYTES", allocation), new DBParam("RESERVED_BYTES", 0L));
                return null;
            });
            db.clock.addAndGet(TimeUnit.HOURS.toMillis(2));
            db.reopen().cleanupBatch(100);
            Map<String, Object> saved = db.reopen().getOperation(id, "admin");
            assertEquals("BLOCKED", saved.get("status"));
            assertEquals(receipt, saved.get("execution"));
            assertNull(saved.get("result"));
            assertFalse(nativeSteps.get(519).containsKey("id"));
            assertEquals("NOT_ATTEMPTED", nativeSteps.get(520).get("writeStatus"));
            assertEquals("NOT_ATTEMPTED", nativeSteps.get(521).get("writeStatus"));
            expect(InvalidRequestException.class, () -> db.reopen().runOperation(id, WorkflowProgressDatabaseTest::failNativeCall));
            assertEquals(saved, db.reopen().getOperation(id, "admin"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test public void receiptWrittenBeforeFieldDiagnosticsIsStillReadUnchanged() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = operation(db, "configure_workflow");
            Map<String, Object> old = execution("APPLIED", "MISMATCH", "10001");
            ((Map<String, Object>) ((List<?>) old.get("steps")).get(0)).put("error", "Native write read-back differs from the confirmed state.");
            db.journal.runOperation(id, () -> {
                db.journal.startWorkflowProgress(old);
                db.journal.workflowProgress(old, true);
                return Collections.singletonMap("status", "PARTIAL");
            });
            assertEquals(old, db.reopen().getOperation(id, "admin").get("execution"));
        }
    }

    private static String operation(UndoTestFixture db, String tool) {
        String session = db.journal.createSession("admin");
        return db.journal.openOperation(tool, "admin", session, db.journal.createChangeSet(session, "admin"));
    }

    private static Map<String, Object> execution(String status, String verification, String id) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("steps", steps(status, verification, id));
        return execution;
    }

    private static List<Map<String, Object>> steps(String status, String verification, String id) {
        Map<String, Object> screen = new LinkedHashMap<>();
        screen.put("key", "screen:form");
        screen.put("kind", "SCREEN");
        screen.put("writeStatus", status);
        screen.put("verification", verification);
        if (id != null) screen.put("id", id);
        Map<String, Object> save = new LinkedHashMap<>();
        save.put("key", "workflow:save");
        save.put("kind", "WORKFLOW_SAVE");
        save.put("writeStatus", "NOT_ATTEMPTED");
        save.put("verification", "NOT_CHECKED");
        return Arrays.asList(screen, save);
    }

    private static Object failNativeCall() { throw new AssertionError("A blocked operation must never resume native work"); }

    private static void expect(Class<? extends Throwable> type, Runnable work) {
        try { work.run(); fail("Expected " + type.getSimpleName()); }
        catch (RuntimeException | Error failure) { if (!type.isInstance(failure)) throw failure; }
    }
}
