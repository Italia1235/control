package ru.rasulov.common.impl.tools;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.common.impl.undo.UndoHandler;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.common.impl.undo.UndoTestFixture;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Real AO storage and a real background executor: cancellation must remain actionable during a write. */
public class OperationRunnerDatabaseTest {
    @Test
    public void cancelAndUndoIsPersistedWhileTheWorkerWaitsAtASafeBoundary() throws Exception {
        AtomicInteger value = new AtomicInteger();
        UndoHandler handler = new UndoHandler() {
            public String kind() { return "runner.value"; }
            public java.util.List<String> lockNames(UndoStep step) { return Collections.singletonList("runner-value"); }
            public String conflict(UndoStep step) {
                int current = value.get();
                return current == Integer.parseInt(step.getBeforeJson()) || current == Integer.parseInt(step.getAfterJson())
                        ? null : "The value changed outside this operation";
            }
            public void undo(UndoStep step, ApplicationUser actor) {
                String conflict = conflict(step);
                if (conflict != null) throw new IllegalStateException(conflict);
                value.set(Integer.parseInt(step.getBeforeJson()));
            }
        };
        try (UndoTestFixture db = UndoTestFixture.create("admin", handler)) {
            CountDownLatch written = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch workerFinished = new CountDownLatch(1);
            ToolProvider domain = () -> Collections.singletonMap("create_screen", args -> {
                UndoStep step = db.journal.prepare("runner.value", "value", "0", "1", 1);
                value.set(1);
                db.journal.applied(step, "1");
                written.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out at the safe boundary");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                db.journal.checkpoint();
                return Collections.singletonMap("status", "CREATED");
            });
            ToolRegistry registry = new ToolRegistry(Collections.singletonList(domain));
            JiraThreadLocalUtil threadLocals = mock(JiraThreadLocalUtil.class);
            doAnswer(call -> { workerFinished.countDown(); return null; }).when(threadLocals).postCall(any(org.slf4j.Logger.class));
            OperationRunner runner = new OperationRunner(db.journal, db.guard, () -> registry,
                    new ToolCatalog(mock(PluginAccessor.class)), mock(JiraAuthenticationContext.class), threadLocals, db.plans,
                    mock(ru.rasulov.common.impl.applies.ApplyJournal.class));
            try {
                String session = db.journal.createSession("admin");
                String set = db.journal.createChangeSet(session, "admin");
                String planId = db.plans.issue("create_screen", "admin", "{}", "fp").getId();
                Map<String, Object> started = runner.start("create_screen",
                        new ObjectMapper().readTree("{\"dryRun\":false,\"planId\":\"" + planId + "\"}"), session, set);
                String id = (String) started.get("operationId");
                assertTrue("The background mutation did not start", written.await(10, TimeUnit.SECONDS));
                assertEquals(1, value.get());
                db.journal.cancel(id, "admin", true);
                assertEquals("Cancellation must wait for the safe boundary", 1, value.get());
                release.countDown();
                assertTrue("The operation did not finish", workerFinished.await(10, TimeUnit.SECONDS));
                assertEquals(0, value.get());
                assertEquals("ROLLED_BACK", db.reopen().getOperation(id, "admin").get("status"));
                assertEquals(session, db.reopen().getOperation(id, "admin").get("sessionId"));
            } finally {
                release.countDown();
                runner.destroy();
                workerFinished.await(10, TimeUnit.SECONDS);
            }
        }
    }
}
