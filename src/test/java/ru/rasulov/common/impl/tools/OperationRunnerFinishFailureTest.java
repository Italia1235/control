package ru.rasulov.common.impl.tools;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import net.java.ao.Query;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.JournalChangeSet;
import ru.rasulov.common.impl.undo.UndoTestFixture;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Regression for stranded runner-owned groups after a finish failure; was the RED -Dtest=OperationRunnerFinishFailureTest. */
public class OperationRunnerFinishFailureTest {
    @Test
    public void completedRunnerOwnedGroupMustNotRemainOpenForeverAfterFinishFailure() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            // Real persistence and lifecycle except the one injected journal boundary failure.
            ChangeJournal journal = mock(ChangeJournal.class, delegatesTo(db.journal));
            doThrow(new IllegalStateException("injected finish transaction failure"))
                    .when(journal).finishChangeSet(anyString(), eq("admin"));
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch workerFinished = new CountDownLatch(1);
            ToolProvider domain = () -> Collections.singletonMap("create_screen", arguments -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Worker was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return Collections.singletonMap("status", "CREATED");
            });
            ToolRegistry registry = new ToolRegistry(Collections.singletonList(domain));
            JiraThreadLocalUtil threadLocals = mock(JiraThreadLocalUtil.class);
            doAnswer(call -> { workerFinished.countDown(); return null; })
                    .when(threadLocals).postCall(any(org.slf4j.Logger.class));
            OperationRunner runner = new OperationRunner(journal, db.guard, () -> registry,
                    new ToolCatalog(mock(PluginAccessor.class)), mock(JiraAuthenticationContext.class), threadLocals, db.plans,
                    mock(ru.rasulov.common.impl.applies.ApplyJournal.class));
            try {
                String planId = db.plans.issue("create_screen", "admin", "{}", "fp").getId();
                Map<String, Object> started = runner.start("create_screen", new ObjectMapper().readTree(
                        "{\"dryRun\":false,\"planId\":\"" + planId + "\"}"), null, null);
                String id = (String) started.get("operationId");
                String setId = (String) started.get("changeSetId");
                assertTrue("The real background worker did not enter the tool", entered.await(10, TimeUnit.SECONDS));
                release.countDown();
                assertTrue("Finish failure prevented worker teardown", workerFinished.await(10, TimeUnit.SECONDS));
                verify(journal).finishChangeSet(setId, "admin");
                assertEquals("COMPLETED", db.reopen().getOperation(id, "admin").get("status"));

                // Exercise both actual admission cleanup and scheduled cleanup after the proposed horizon.
                db.clock.addAndGet(TimeUnit.DAYS.toMillis(31));
                db.journal.createSession("admin");
                for (int pass = 0; pass < 4; pass++) db.journal.cleanupBatch(2);
                JournalChangeSet[] remaining = db.entityManager.find(JournalChangeSet.class,
                        Query.select().where("CHANGE_SET_KEY = ?", setId));
                String diagnostic = remaining.length == 0 ? "deleted" : remaining[0].getState()
                        + ", finishedAt=" + remaining[0].getFinishedAt() + ", expiresAt=" + remaining[0].getExpiresAt();
                // Desired invariant, deliberately not an assertion that the broken OPEN state is correct.
                assertEquals("Completed runner-owned group must expire after finish failure; actual: " + diagnostic,
                        0, remaining.length);
            } finally {
                release.countDown();
                runner.destroy();
                workerFinished.await(10, TimeUnit.SECONDS);
            }
        }
    }
}
