package ru.rasulov.common.impl.tools;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.undo.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OperationRunnerReplayTest {
    private static JsonNode arguments(String id) throws Exception {
        return new ObjectMapper().readTree("{\"dryRun\":false,\"planId\":\"" + id + "\"}");
    }

    private static OperationRunner runner(UndoTestFixture db, ChangeJournal journal, ActionPlanStore plans,
                                          ToolCall call, Semaphore finished) {
        ToolProvider provider = () -> {
            Map<String, ToolCall> tools = new HashMap<>();
            tools.put("create_screen", call);
            tools.put("create_project", call);
            tools.put("configure_workflow", call);
            return tools;
        };
        ToolRegistry registry = new ToolRegistry(Collections.singletonList(provider));
        JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
        doAnswer(invocation -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
        return new OperationRunner(journal, db.guard, () -> registry, new ToolCatalog(mock(PluginAccessor.class)),
                mock(JiraAuthenticationContext.class), locals, plans, mock(ApplyJournal.class));
    }

    @Test public void concurrentRunnersReplayRunningAndCompletedWithoutAnotherWrite() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            AtomicInteger writes = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Semaphore finished = new Semaphore(0);
            ToolCall tool = args -> {
                writes.incrementAndGet();
                db.plans.remove(ToolArguments.text(args, "planId"));
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Worker not released");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return Collections.singletonMap("status", "CREATED");
            };
            OperationRunner first = runner(db, db.journal, db.plans, tool, finished);
            OperationRunner second = runner(db, db.reopen(), db.plans, tool, finished);
            ExecutorService clients = Executors.newFixedThreadPool(2);
            try {
                String plan = db.plans.issue("create_screen", "admin", "{}", "fp").getId();
                JsonNode args = arguments(plan);
                CountDownLatch go = new CountDownLatch(1);
                Future<Map<String, Object>> a = clients.submit(() -> { go.await(); return first.start("create_screen", args, null, null); });
                Future<Map<String, Object>> b = clients.submit(() -> { go.await(); return second.start("create_screen", args, null, null); });
                go.countDown();
                Map<String, Object> one = a.get(10, TimeUnit.SECONDS);
                Map<String, Object> two = b.get(10, TimeUnit.SECONDS);
                assertEquals(one.get("operationId"), two.get("operationId"));
                assertTrue(Boolean.TRUE.equals(one.get("alreadyStarted")) ^ Boolean.TRUE.equals(two.get("alreadyStarted")));
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                Map<String, Object> running = second.start("create_screen", args, null, null);
                assertEquals(one.get("operationId"), running.get("operationId"));
                assertEquals("RUNNING", running.get("status"));
                assertEquals(true, running.get("alreadyStarted"));
                release.countDown();
                assertTrue(finished.tryAcquire(10, TimeUnit.SECONDS));
                Map<String, Object> completed = first.start("create_screen", args, null, null);
                assertEquals(one.get("operationId"), completed.get("operationId"));
                assertEquals("COMPLETED", completed.get("status"));
                assertEquals(true, completed.get("alreadyStarted"));
                assertEquals("CONSUMED", first.start("create_project", args, null, null).get("staleReason"));
                db.currentActor.set(UndoTestFixture.user("other"));
                Map<String, Object> foreign = second.start("create_screen", args, null, null);
                assertEquals("NOT_FOUND", foreign.get("staleReason"));
                assertFalse(foreign.containsKey("operationId"));
                assertEquals(1, writes.get());
                assertEquals(1, db.entityManager.count(JournalOperation.class));
                assertEquals(1, db.entityManager.count(JournalSession.class));
            } finally {
                release.countDown();
                first.destroy(); second.destroy(); clients.shutdownNow();
                clients.awaitTermination(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test public void unknownForeignWrongToolAndExpiredPlansNeverCreateDurableWork() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            ActionPlanStore plans = mock(ActionPlanStore.class);
            when(plans.status("create_screen", "admin", "expired")).thenReturn(ActionPlanStore.Status.EXPIRED);
            Semaphore finished = new Semaphore(0);
            ToolCall forbidden = args -> { throw new AssertionError("Stale submission invoked a tool"); };
            OperationRunner runner = runner(db, db.journal, db.plans, forbidden, finished);
            OperationRunner expired = runner(db, db.journal, plans, forbidden, finished);
            try {
                String foreign = db.plans.issue("create_screen", "other", "{}", "fp").getId();
                String wrongTool = db.plans.issue("create_project", "admin", "{}", "fp").getId();
                for (String id : Arrays.asList("invented", foreign, wrongTool)) {
                    Map<String, Object> rejected = runner.start("create_screen", arguments(id), null, null);
                    assertEquals("PREVIEW_STALE", rejected.get("errorCode"));
                    assertEquals("NOT_FOUND", rejected.get("staleReason"));
                    assertFalse(rejected.containsKey("operationId"));
                }
                Map<String, Object> rejected = expired.start("create_screen", arguments("expired"), null, null);
                assertEquals("PREVIEW_STALE", rejected.get("errorCode"));
                assertEquals("EXPIRED", rejected.get("staleReason"));
                assertFalse(rejected.containsKey("operationId"));
                assertEquals(0, db.entityManager.count(JournalOperation.class));
                assertEquals(0, db.entityManager.count(JournalSession.class));
                assertEquals(0, db.entityManager.count(JournalChangeSet.class));
            } finally { runner.destroy(); expired.destroy(); }
        }
    }

    /** Composition of restart recovery and replay: an accepted-then-orphaned operation is answered, never re-run. */
    @Test public void anOperationRecoveredAfterARestartIsReplayedAsTheSameBlockedOperationAndNeverRunsAgain() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            AtomicInteger writes = new AtomicInteger();
            ToolCall tool = args -> { writes.incrementAndGet(); return Collections.singletonMap("status", "CREATED"); };
            String plan = db.plans.issue("create_screen", "admin", "{}", "fp").getId();
            String session = db.journal.createSession("admin");
            String set = db.journal.createRunnerChangeSet(session, "admin");
            String orphan = db.journal.openOperation("create_screen", "admin", session, set, plan);
            db.clock.addAndGet(java.util.concurrent.TimeUnit.HOURS.toMillis(2));
            ChangeJournal restarted = db.reopen();
            restarted.cleanupBatch(100);
            assertEquals("BLOCKED", restarted.getOperation(orphan, "admin").get("status"));
            OperationRunner runner = runner(db, restarted, db.plans, tool, new Semaphore(0));
            try {
                Map<String, Object> replay = runner.start("create_screen", arguments(plan), null, null);
                assertEquals(orphan, replay.get("operationId"));
                assertEquals("BLOCKED", replay.get("status"));
                assertEquals(true, replay.get("alreadyStarted"));
                assertEquals(0, writes.get());
                assertEquals(1, db.entityManager.count(JournalOperation.class));
            } finally { runner.destroy(); }
        }
    }

    /** The worker died after it had started (native write possible): recovery blocks it, replay never re-runs it. */
    @Test public void aStartedOperationWhoseWorkerDiedIsBlockedAndNeverReplayedIntoASecondWrite() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            AtomicInteger writes = new AtomicInteger();
            ToolCall tool = args -> { writes.incrementAndGet(); return Collections.singletonMap("status", "CREATED"); };
            String plan = db.plans.issue("create_screen", "admin", "{}", "fp").getId();
            String session = db.journal.createSession("admin");
            String set = db.journal.createRunnerChangeSet(session, "admin");
            String started = db.journal.openOperation("create_screen", "admin", session, set, plan);
            JournalOperation row = db.entityManager.find(JournalOperation.class)[0];
            row.setState("RUNNING");
            row.setExecutionSlot(0);
            row.setStartedAt(db.clock.get());
            row.save();
            db.clock.addAndGet(java.util.concurrent.TimeUnit.HOURS.toMillis(2));
            ChangeJournal restarted = db.reopen();
            restarted.cleanupBatch(100);
            Map<String, Object> recovered = restarted.getOperation(started, "admin");
            assertEquals("BLOCKED", recovered.get("status"));
            assertFalse(String.valueOf(recovered.get("error")).toLowerCase().contains("nothing"));
            OperationRunner runner = runner(db, restarted, db.plans, tool, new Semaphore(0));
            try {
                Map<String, Object> replay = runner.start("create_screen", arguments(plan), null, null);
                assertEquals(started, replay.get("operationId"));
                assertEquals("BLOCKED", replay.get("status"));
                assertEquals(true, replay.get("alreadyStarted"));
                assertEquals(0, writes.get());
                assertEquals(1, db.entityManager.count(JournalOperation.class));
            } finally { runner.destroy(); }
        }
    }

    /** A slow but live worker is never declared dead by lease age: a restarted node's cleanup keeps it RUNNING. */
    @Test public void aLiveSlowWorkerIsNotRecoveredByCleanupAndFinishesExactlyOnce() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            AtomicInteger writes = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Semaphore finished = new Semaphore(0);
            ToolCall tool = args -> {
                writes.incrementAndGet();
                db.plans.remove(ToolArguments.text(args, "planId"));
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Worker not released");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return Collections.singletonMap("status", "CREATED");
            };
            OperationRunner runner = runner(db, db.journal, db.plans, tool, finished);
            try {
                String plan = db.plans.issue("create_screen", "admin", "{}", "fp").getId();
                Map<String, Object> accepted = runner.start("create_screen", arguments(plan), null, null);
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                db.clock.addAndGet(TimeUnit.HOURS.toMillis(2));
                ChangeJournal other = db.reopen();
                // The restarted node's cleanup sees an expired lease but the execution slot is still held: it must wait for
                // the slot (bounded by NamedLock) and re-check, never block the live worker's operation.
                ExecutorService cleanup = Executors.newSingleThreadExecutor();
                try {
                    Future<Integer> sweep = cleanup.submit(() -> other.cleanupBatch(100));
                    Thread.sleep(300);
                    assertFalse("cleanup must still be waiting for the live slot", sweep.isDone());
                    Map<String, Object> seen = other.getOperation((String) accepted.get("operationId"), "admin");
                    assertEquals(String.valueOf(seen), "RUNNING", seen.get("status"));
                    Map<String, Object> replay = runner.start("create_screen", arguments(plan), null, null);
                    assertEquals(accepted.get("operationId"), replay.get("operationId"));
                    assertEquals(true, replay.get("alreadyStarted"));
                    release.countDown();
                    assertTrue(finished.tryAcquire(10, TimeUnit.SECONDS));
                    sweep.get(10, TimeUnit.SECONDS);
                } finally { cleanup.shutdownNow(); }
                Map<String, Object> done = other.getOperation((String) accepted.get("operationId"), "admin");
                assertEquals(String.valueOf(done), "COMPLETED", done.get("status"));
                assertEquals("CREATED", ((Map<?, ?>) done.get("result")).get("status"));
                assertEquals(1, writes.get());
            } finally { release.countDown(); runner.destroy(); }
        }
    }

    @Test public void workflowReceiptAndLostTerminalResponseReplayAcrossRunnersWithoutAnotherNativeCall() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            AtomicInteger nativeCalls = new AtomicInteger();
            CountDownLatch acknowledged = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            Semaphore finished = new Semaphore(0);
            Map<String, Object> receipt = WorkflowReceiptCodecTest.boundary("APPLIED", "MATCH", "10001");
            WorkflowReceiptCodecTest.steps(receipt).get(521).put("verification", "NOT_CHECKED");
            Map<String, Object> unavailable = WorkflowReceiptCodecTest.boundary("APPLIED", "MATCH", "10001");
            WorkflowReceiptCodecTest.steps(unavailable).get(521).put("verification", "UNAVAILABLE");
            WorkflowReceiptCodecTest.steps(unavailable).get(521).put("error", "Read-back failed: \u0000\"\\\nλ");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "PARTIAL");
            result.put("targetVersion", "DRAFT");
            result.put("screenId", "10001");
            ToolCall tool = args -> {
                db.plans.remove(ToolArguments.text(args, "planId"));
                db.journal.startWorkflowProgress(WorkflowReceiptCodecTest.boundary("NOT_ATTEMPTED", "NOT_CHECKED", null));
                nativeCalls.incrementAndGet();
                db.journal.workflowProgress(receipt, false);
                acknowledged.countDown();
                try {
                    if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Worker not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                db.journal.workflowProgress(unavailable, false);
                return result;
            };
            OperationRunner first = runner(db, db.journal, db.plans, tool, finished);
            OperationRunner reopened = runner(db, db.reopen(), db.plans, tool, finished);
            try {
                String plan = db.plans.issue("configure_workflow", "admin", "{}", "fp").getId();
                Map<String, Object> accepted = first.start("configure_workflow", arguments(plan), null, null);
                assertTrue(acknowledged.await(10, TimeUnit.SECONDS));
                Map<String, Object> running = reopened.start("configure_workflow", arguments(plan), null, null);
                assertEquals(accepted.get("operationId"), running.get("operationId"));
                assertEquals("RUNNING", running.get("status"));
                assertEquals(true, running.get("alreadyStarted"));
                assertEquals(receipt, running.get("execution"));
                finish.countDown();
                assertTrue(finished.tryAcquire(10, TimeUnit.SECONDS));
                // No terminal tool response is used: both recovery surfaces must use the persisted result and receipt.
                Map<String, Object> recovered = db.reopen().getOperation((String) accepted.get("operationId"), "admin");
                Map<String, Object> replay = reopened.start("configure_workflow", arguments(plan), null, null);
                assertEquals("COMPLETED", recovered.get("status"));
                assertEquals(result, recovered.get("result"));
                assertEquals(unavailable, recovered.get("execution"));
                assertEquals(recovered.get("execution"), replay.get("execution"));
                assertEquals(recovered.get("result"), replay.get("result"));
                assertEquals(recovered.get("operationId"), replay.get("operationId"));
                assertEquals(true, replay.get("alreadyStarted"));
                assertEquals(1, nativeCalls.get());
                assertEquals(1, db.entityManager.count(JournalOperation.class));
                assertEquals(1, db.entityManager.count(JournalProgress.class));
                assertEquals(0, db.entityManager.count(JournalStep.class));
                JournalProgress stored = db.entityManager.find(JournalProgress.class)[0];
                assertTrue(stored.getExecutionJson().contains("\"workflowReceipt\":1"));
                assertTrue(WorkflowReceiptCodec.bytes(stored.getExecutionJson()) <= ChangeJournal.PROGRESS_JSON_LIMIT);
                assertTrue(WorkflowReceiptCodec.bytes(JournalJson.write(recovered.get("execution"))) > ChangeJournal.PROGRESS_JSON_LIMIT);
            } finally {
                finish.countDown();
                first.destroy();
                reopened.destroy();
            }
        }
    }

    @Test public void interruptedWorkflowReplayKeepsUnknownIntentAndNeverResumesOrAdoptsAnId() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String plan = db.plans.issue("configure_workflow", "admin", "{}", "fp").getId();
            String session = db.journal.createSession("admin");
            String set = db.journal.createRunnerChangeSet(session, "admin");
            String id = db.journal.openOperation("configure_workflow", "admin", session, set, plan);
            JournalOperation row = db.entityManager.find(JournalOperation.class)[0];
            row.setState("RUNNING");
            row.setExecutionSlot(0);
            row.setStartedAt(db.clock.get());
            row.save();
            Map<String, Object> receipt = workflowReceipt("UNKNOWN", "NOT_CHECKED", null);
            // Committed crash snapshot: a native call may have happened, but neither ID nor acknowledgement is known.
            db.entityManager.create(JournalProgress.class, new net.java.ao.DBParam("OPERATION_KEY", id),
                    new net.java.ao.DBParam("EXECUTION_JSON", JournalJson.write(receipt)),
                    new net.java.ao.DBParam("SETTLED", false), new net.java.ao.DBParam("ALLOCATED_BYTES", 0L),
                    new net.java.ao.DBParam("RESERVED_BYTES", 0L));
            db.plans.remove(plan);
            db.clock.addAndGet(TimeUnit.HOURS.toMillis(2));
            ChangeJournal restarted = db.reopen();
            restarted.cleanupBatch(100);
            AtomicInteger nativeCalls = new AtomicInteger();
            OperationRunner runner = runner(db, restarted, db.plans, args -> {
                nativeCalls.incrementAndGet();
                throw new AssertionError("An accepted workflow must never be resumed");
            }, new Semaphore(0));
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    Map<String, Object> replay = runner.start("configure_workflow", arguments(plan), null, null);
                    assertEquals(id, replay.get("operationId"));
                    assertEquals("BLOCKED", replay.get("status"));
                    assertEquals(true, replay.get("alreadyStarted"));
                    assertEquals(receipt, replay.get("execution"));
                    assertNull(replay.get("result"));
                }
                assertEquals(0, nativeCalls.get());
                assertEquals(1, db.entityManager.count(JournalOperation.class));
                assertEquals(receipt, db.reopen().getOperation(id, "admin").get("execution"));
            } finally { runner.destroy(); }
        }
    }

    private static Map<String, Object> workflowReceipt(String writeStatus, String verification, String id) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("key", "screen:form");
        step.put("kind", "SCREEN");
        step.put("writeStatus", writeStatus);
        step.put("verification", verification);
        if (id != null) step.put("id", id);
        return Collections.singletonMap("steps", Collections.singletonList(step));
    }
}
