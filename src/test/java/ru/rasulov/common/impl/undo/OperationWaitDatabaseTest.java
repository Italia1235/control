package ru.rasulov.common.impl.undo;

import org.junit.Test;
import ru.rasulov.common.api.exception.AccessException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OperationWaitDatabaseTest {
    private static <T extends Throwable> T assertThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) throw new AssertionError("Unexpected failure", failure);
            return type.cast(failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static String open(UndoTestFixture db) {
        String session = db.journal.createSession("admin");
        return db.journal.openOperation("create_screen", "admin", session,
                db.journal.createChangeSet(session, "admin"));
    }

    private static ChangeJournal polling(UndoTestFixture db, ChangeJournal.Sleeper sleeper) {
        return spy(new ChangeJournal(db.store, db.locks, db.plans, db.guard, db.users,
                Collections.emptyList(), sleeper));
    }

    @Test public void terminalOnAnotherJournalAfterThreeSecondsReadsResultOnce() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = open(db);
            long start = db.clock.get();
            ChangeJournal poll = polling(db, millis -> {
                long wakeAt = db.clock.get() + millis;
                if (db.clock.get() - start < 3000 && wakeAt - start >= 3000) {
                    db.clock.set(start + 3000);
                    db.reopen().runOperation(id, () -> Collections.singletonMap("screenId", "123"));
                }
                db.clock.set(wakeAt);
            });
            Map<String, Object> result = poll.getOperation(id, "admin", 20);
            assertEquals("COMPLETED", result.get("status"));
            assertEquals(Collections.singletonMap("screenId", "123"), result.get("result"));
            assertTrue("Must not return before completion", db.clock.get() - start >= 3000);
            assertTrue("Must observe completion within 2000 ms", db.clock.get() - start <= 5000);
            verify(poll, times(4)).operationState(id, "admin");
            verify(poll, times(1)).getOperation(id, "admin");
        }
    }

    @Test public void runningReturnsOnlyAtDeadline() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = open(db);
            ChangeJournal poll = polling(db, db.clock::addAndGet);
            long start = db.clock.get();
            db.journal.runOperation(id, () -> {
                assertEquals("RUNNING", poll.getOperation(id, "admin", 4).get("status"));
                assertEquals(4000, db.clock.get() - start);
                return null;
            });
            verify(poll, times(4)).operationState(id, "admin");
            verify(poll, times(1)).getOperation(id, "admin");
        }
    }

    @Test public void runningTwentySecondsBacksOffAndClipsLastPause() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = open(db);
            List<Long> pauses = new ArrayList<>();
            ChangeJournal poll = polling(db, millis -> {
                pauses.add(millis);
                db.clock.addAndGet(millis);
            });
            long start = db.clock.get();
            db.journal.runOperation(id, () -> {
                assertEquals("RUNNING", poll.getOperation(id, "admin", 20).get("status"));
                assertEquals(20000, db.clock.get() - start);
                return null;
            });
            assertEquals(Arrays.asList(500L, 1000L, 2000L, 2000L, 2000L, 2000L,
                    2000L, 2000L, 2000L, 2000L, 2000L, 500L), pauses);
            verify(poll, times(12)).operationState(id, "admin");
            verify(poll, times(1)).getOperation(id, "admin");
        }
    }

    @Test public void zeroWaitDoesOnlyFullRead() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = open(db);
            ChangeJournal poll = polling(db, millis -> fail("must not sleep"));
            assertEquals("QUEUED", poll.getOperation(id, "admin", 0).get("status"));
            verify(poll, never()).operationState(anyString(), anyString());
            verify(poll, times(1)).getOperation(id, "admin");
        }
    }

    @Test public void terminalDoesNotSleep() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = open(db);
            db.journal.runOperation(id, () -> "done");
            ChangeJournal poll = polling(db, millis -> fail("must not sleep"));
            assertEquals("done", poll.getOperation(id, "admin", 20).get("result"));
        }
    }

    @Test public void foreignAndMissingHaveSameErrorWithoutWaiting() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = open(db);
            ChangeJournal poll = polling(db, millis -> fail("must not sleep"));
            db.currentActor.set(UndoTestFixture.user("other"));
            String expected = assertThrows(AccessException.class,
                    () -> db.journal.getOperation(id, "other")).getMessage();
            assertEquals(expected, assertThrows(AccessException.class,
                    () -> poll.getOperation(id, "other", 20)).getMessage());
            assertEquals(expected, assertThrows(AccessException.class,
                    () -> poll.getOperation("missing", "other", 20)).getMessage());
        }
    }

    @Test public void ninthWaitFallsBackAndSlotsAreReleased() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = open(db);
            CountDownLatch entered = new CountDownLatch(8);
            CountDownLatch release = new CountDownLatch(1);
            ChangeJournal poll = polling(db, millis -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                throw new InterruptedException("release waiters");
            });
            ExecutorService workers = Executors.newFixedThreadPool(8);
            java.util.List<Future<Map<String, Object>>> futures = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < 8; i++) futures.add(workers.submit(() -> poll.getOperation(id, "admin", 20)));
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals("QUEUED", poll.getOperation(id, "admin", 20).get("status"));
                verify(poll, times(9)).operationState(id, "admin");
                release.countDown();
                for (Future<Map<String, Object>> future : futures) assertEquals("QUEUED", future.get(10, TimeUnit.SECONDS).get("status"));
                reset(poll);
                try {
                    assertEquals("QUEUED", poll.getOperation(id, "admin", 20).get("status"));
                    assertTrue(Thread.currentThread().isInterrupted());
                } finally { Thread.interrupted(); }
            } finally {
                release.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void interruptionRestoresFlagAndReturnsPersistedState() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = open(db);
            ChangeJournal poll = polling(db, millis -> { throw new InterruptedException("stop"); });
            try {
                assertEquals("QUEUED", poll.getOperation(id, "admin", 20).get("status"));
                assertTrue(Thread.currentThread().isInterrupted());
                verify(poll, times(1)).getOperation(id, "admin");
            } finally { Thread.interrupted(); }
        }
    }

    @Test public void pluginStopEndsWaitAndSubsequentCallsDoNotSleep() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = open(db);
            AtomicReference<ChangeJournal> ref = new AtomicReference<>();
            AtomicInteger sleeps = new AtomicInteger();
            ChangeJournal poll = polling(db, millis -> { sleeps.incrementAndGet(); ref.get().destroy(); });
            ref.set(poll);
            assertEquals("QUEUED", poll.getOperation(id, "admin", 20).get("status"));
            assertEquals("QUEUED", poll.getOperation(id, "admin", 20).get("status"));
            assertEquals(1, sleeps.get());
        }
    }

    @Test public void pollReadErrorPropagatesUnchangedAndReleasesSlot() {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            String id = open(db);
            ChangeJournal poll = polling(db, db.clock::addAndGet);
            IllegalStateException failure = new IllegalStateException("database unavailable");
            doCallRealMethod().doThrow(failure).when(poll).operationState(id, "admin");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> poll.getOperation(id, "admin", 20)));
            doCallRealMethod().when(poll).operationState(id, "admin");
            assertEquals("QUEUED", poll.getOperation(id, "admin", 1).get("status"));
        }
    }
}
