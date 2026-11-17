package ru.rasulov.common.impl.undo;

import net.java.ao.DBParam;
import net.java.ao.Query;
import org.junit.Test;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.NamedLock;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Fallback reclamation of runner-owned groups whose close failed; real AO/HSQL storage, controlled clock. */
public class RunnerGroupRetentionTest {
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final long RETENTION = 30 * DAY;

    private static String[] runner(UndoTestFixture f, ChangeJournalDatabaseTest.StateHandler handler, boolean step) {
        String session = f.journal.createSession("alice");
        String set = f.journal.createRunnerChangeSet(session, "alice");
        String op = f.journal.openOperation("test.change", "alice", session, set);
        f.journal.runOperation(op, () -> {
            if (step) ChangeJournalDatabaseTest.change(f.journal, handler, "r-" + op, "0", "1");
            return Collections.singletonMap("status", "CREATED");
        });
        return new String[] {session, set, op};
    }

    private static JournalChangeSet[] sets(UndoTestFixture f, String set) throws Exception {
        return f.entityManager.find(JournalChangeSet.class, Query.select().where("CHANGE_SET_KEY = ?", set));
    }

    private static JournalOperation operation(UndoTestFixture f, String op) throws Exception {
        return f.entityManager.find(JournalOperation.class, Query.select().where("OPERATION_KEY = ?", op))[0];
    }

    @Test public void ageBoundaryIsThirtyDaysFromOperationFinish() throws Exception {
        try (UndoTestFixture f = UndoTestFixture.create("alice")) {
            String[] g = runner(f, null, false);
            f.clock.addAndGet(RETENTION - 1);
            f.journal.cleanupBatch(100);
            assertEquals(1, sets(f, g[1]).length);
            f.clock.addAndGet(1);
            f.journal.cleanupBatch(100);
            assertEquals(0, sets(f, g[1]).length);
            assertEquals(0, f.entityManager.count(JournalOperation.class));
            assertEquals(0, f.entityManager.count(JournalSession.class));
            assertEquals(0, f.store.usedBytes());
        }
    }

    @Test public void explicitOpenGroupIsNeverReclaimed() throws Exception {
        try (UndoTestFixture f = UndoTestFixture.create("alice")) {
            String session = f.journal.createSession("alice");
            String set = f.journal.createChangeSet(session, "alice");
            String op = f.journal.openOperation("test.change", "alice", session, set);
            f.journal.runOperation(op, () -> Collections.singletonMap("status", "CREATED"));
            f.clock.addAndGet(31 * DAY);
            f.journal.cleanupBatch(100);
            f.journal.cleanupBatch(100);
            JournalChangeSet row = sets(f, set)[0];
            assertEquals("OPEN", row.getState());
            assertFalse(row.isRunnerOwned());
            assertEquals(0, row.getNextCleanupAt());
        }
    }

    @Test public void legacyGroupWithoutMarkerKeepsDatabaseDefaultsAndIsNeverReclaimed() throws Exception {
        try (UndoTestFixture f = UndoTestFixture.create("alice")) {
            String session = f.journal.createSession("alice");
            f.entityManager.create(JournalChangeSet.class, new DBParam("CHANGE_SET_KEY", "legacy"),
                    new DBParam("SESSION_KEY", session), new DBParam("ACTOR_KEY", "alice"), new DBParam("STATE", "OPEN"),
                    new DBParam("CREATED_AT", f.clock.get()), new DBParam("FINISHED_AT", 0L),
                    new DBParam("EXPIRES_AT", 0L), new DBParam("BYTES", 0L));
            JournalChangeSet row = sets(f, "legacy")[0];
            // Schema: the new columns exist, and a row written without them reads their declared defaults.
            assertFalse(row.isRunnerOwned());
            assertEquals(0L, row.getNextCleanupAt());
            assertEquals(1, f.entityManager.find(JournalChangeSet.class,
                    Query.select().where("RUNNER_OWNED = ? AND NEXT_CLEANUP_AT = ?", false, 0L)).length);
            String op = f.journal.openOperation("test.change", "alice", session, "legacy");
            f.journal.runOperation(op, () -> Collections.singletonMap("status", "CREATED"));
            f.clock.addAndGet(31 * DAY);
            f.journal.cleanupBatch(100);
            assertEquals("OPEN", sets(f, "legacy")[0].getState());
            assertEquals(1, f.entityManager.count(JournalOperation.class));
        }
    }

    @Test public void emptyRunnerGroupIsReclaimedAtThirtyDaysAndByOrdinaryEmptyCleanupAfterOneDay() throws Exception {
        try (UndoTestFixture f = UndoTestFixture.create("alice")) {
            String session = f.journal.createSession("alice");
            String set = f.journal.createRunnerChangeSet(session, "alice");
            f.clock.addAndGet(RETENTION - 1);
            f.journal.createSession("alice");
            assertEquals(1, sets(f, set).length);
            f.clock.addAndGet(1);
            f.journal.createSession("alice");
            assertEquals("fallback reaps on admission", 0, sets(f, set).length);
            assertEquals(0, f.entityManager.count(JournalChangeSet.class));
        }
        try (UndoTestFixture f = UndoTestFixture.create("alice")) {
            String set = f.journal.createRunnerChangeSet(f.journal.createSession("alice"), "alice");
            f.clock.addAndGet(DAY + 1);
            f.journal.cleanupBatch(100);
            // The pre-existing 24h empty-OPEN cleanup closes and deletes it long before the fallback horizon.
            assertEquals(0, sets(f, set).length);
        }
    }

    @Test public void normalFinishKeepsTwentyFourHourWindowAndOrdinaryDeletion() throws Exception {
        try (UndoTestFixture f = UndoTestFixture.create("alice")) {
            String[] g = runner(f, null, false);
            f.journal.finishChangeSet(g[1], "alice");
            JournalChangeSet row = sets(f, g[1])[0];
            assertEquals("FINISHED", row.getState());
            assertEquals(row.getFinishedAt() + JournalStore.UNDO_WINDOW_MILLIS, row.getExpiresAt());
            f.clock.addAndGet(DAY - 1);
            f.journal.cleanupBatch(100);
            assertEquals(1, sets(f, g[1]).length);
            f.clock.addAndGet(1);
            f.journal.cleanupBatch(100);
            assertEquals(0, sets(f, g[1]).length);
        }
    }

    @Test public void anyUndoOrProgressEvidenceProtectsTheGroup() throws Exception {
        ChangeJournalDatabaseTest.StateHandler handler = new ChangeJournalDatabaseTest.StateHandler();
        try (UndoTestFixture f = UndoTestFixture.create("alice", handler)) {
            String[] applied = runner(f, handler, true);
            String[] undone = runner(f, handler, true);
            JournalStep step = f.entityManager.find(JournalStep.class,
                    Query.select().where("OPERATION_KEY = ?", undone[2]))[0];
            step.setState("UNDONE");
            step.save();
            String[] prepared = new String[3];
            prepared[0] = f.journal.createSession("alice");
            prepared[1] = f.journal.createRunnerChangeSet(prepared[0], "alice");
            prepared[2] = f.journal.openOperation("test.change", "alice", prepared[0], prepared[1]);
            try {
                f.journal.runOperation(prepared[2], () -> {
                    f.journal.prepare("state", "uncertain", "0", "1");
                    throw new IllegalStateException("unproven native write");
                });
                fail();
            } catch (IllegalStateException expected) { }
            String[] progress = new String[3];
            progress[0] = f.journal.createSession("alice");
            progress[1] = f.journal.createRunnerChangeSet(progress[0], "alice");
            progress[2] = f.journal.openOperation("set_project_issue_types", "alice", progress[0], progress[1]);
            try {
                f.journal.runOperation(progress[2], () -> {
                    f.journal.startIssueTypeProgress(unknownExecution(), 1);
                    f.journal.beginIssueTypeBatch("ISSUES", Collections.singletonMap("issueIds",
                            Collections.singletonList(1L)), unknownExecution());
                    throw new IllegalStateException("Unknown native outcome");
                });
                fail();
            } catch (IllegalStateException expected) { }
            f.clock.addAndGet(RETENTION);
            f.journal.cleanupBatch(100);
            for (String[] g : Arrays.asList(applied, undone, prepared, progress))
                assertEquals("evidence group must stay: " + g[1], 1, sets(f, g[1]).length);
        }
    }

    private static java.util.Map<String, Object> unknownExecution() {
        java.util.Map<String, Object> counts = new java.util.LinkedHashMap<>();
        counts.put("completed", 0); counts.put("unknown", 1); counts.put("notStarted", null);
        java.util.Map<String, Object> execution = new java.util.LinkedHashMap<>();
        execution.put("outcome", "UNKNOWN"); execution.put("counts", counts);
        return execution;
    }

    @Test public void reservedBytesAndActiveOperationsProtectTheGroup() throws Exception {
        try (UndoTestFixture f = UndoTestFixture.create("alice")) {
            String[] reserved = runner(f, null, false);
            JournalOperation row = operation(f, reserved[2]);
            row.setReservedBytes(10);
            row.save();
            String[] running = runner(f, null, false);
            row = operation(f, running[2]);
            row.setState("RUNNING");
            row.save();
            String session = f.journal.createSession("alice");
            String set = f.journal.createRunnerChangeSet(session, "alice");
            String queued = f.journal.openOperation("test.change", "alice", session, set);
            f.clock.addAndGet(RETENTION);
            f.journal.cleanupBatch(100);
            assertEquals(1, sets(f, reserved[1]).length);
            assertEquals(1, sets(f, running[1]).length);
            assertEquals(1, sets(f, set).length);
            assertNotNull(queued);
        }
    }

    @Test public void busyExecutionLockSkipsCandidateWithoutErrorAndNextPassReclaims() throws Exception {
        try (UndoTestFixture f = UndoTestFixture.create("alice")) {
            String[] g = runner(f, null, false);
            boolean[] busy = {true};
            NamedLock gate = new NamedLock(null) {
                @Override public <T> T run(String name, String what, Supplier<T> work) {
                    if (busy[0] && name.contains(".undo.execution.")) throw new BusyException("compensation is active");
                    return f.locks.run(name, what, work);
                }
            };
            ChangeJournal journal = new ChangeJournal(f.store, gate, f.plans, f.guard, f.users, Collections.emptyList());
            f.clock.addAndGet(RETENTION);
            journal.cleanupBatch(100);
            assertEquals(1, sets(f, g[1]).length);
            busy[0] = false;
            // The skipped candidate was rotated by one hour; it is retried after that.
            journal.cleanupBatch(100);
            assertEquals(1, sets(f, g[1]).length);
            f.clock.addAndGet(TimeUnit.HOURS.toMillis(1));
            journal.cleanupBatch(100);
            assertEquals(0, sets(f, g[1]).length);
        }
    }

    @Test public void runnerGroupAcceptsExactlyOneOperationButExplicitGroupsAcceptMany() throws Exception {
        try (UndoTestFixture f = UndoTestFixture.create("alice")) {
            String session = f.journal.createSession("alice");
            String owned = f.journal.createRunnerChangeSet(session, "alice");
            f.journal.openOperation("test.change", "alice", session, owned);
            try {
                f.journal.openOperation("test.change", "alice", session, owned);
                fail("second operation must be rejected");
            } catch (InvalidRequestException expected) { }
            assertEquals(1, f.entityManager.count(JournalOperation.class));
            String explicit = f.journal.createChangeSet(session, "alice");
            f.journal.openOperation("test.change", "alice", session, explicit);
            f.journal.openOperation("test.change", "alice", session, explicit);
            assertEquals(3, f.entityManager.count(JournalOperation.class));
        }
    }

    @Test public void reclamationReleasesExactlyTheDeletedRowsBytesAndKeepsSharedSession() throws Exception {
        try (UndoTestFixture f = UndoTestFixture.create("alice")) {
            String[] g = runner(f, null, false);
            String sibling = f.journal.createChangeSet(g[0], "alice");
            long before = f.store.usedBytes();
            long released = operation(f, g[2]).getBytes() + sets(f, g[1])[0].getBytes();
            f.clock.addAndGet(RETENTION);
            String fresh = f.journal.createSession("alice");
            assertEquals(before - released + JournalStore.bytes(fresh, "alice"), f.store.usedBytes());
            assertEquals(0, sets(f, g[1]).length);
            assertEquals(1, sets(f, sibling).length);
            assertEquals("session survives while a sibling group exists", 1, f.entityManager.find(
                    JournalSession.class, Query.select().where("SESSION_KEY = ?", g[0])).length);
        }
        try (UndoTestFixture f = UndoTestFixture.create("alice")) {
            String[] g = runner(f, null, false);
            long released = operation(f, g[2]).getBytes() + sets(f, g[1])[0].getBytes()
                    + f.entityManager.find(JournalSession.class)[0].getBytes();
            long before = f.store.usedBytes();
            f.clock.addAndGet(RETENTION);
            f.journal.cleanupBatch(100);
            assertEquals(before - released, f.store.usedBytes());
            assertEquals(0, f.entityManager.count(JournalSession.class));
        }
    }

    @Test public void protectedCandidatesRotateSoLaterReclaimableGroupsAreNotStarved() throws Exception {
        ChangeJournalDatabaseTest.StateHandler handler = new ChangeJournalDatabaseTest.StateHandler();
        try (UndoTestFixture f = UndoTestFixture.create("alice", handler)) {
            String[][] groups = new String[20][];
            for (int index = 0; index < 20; index++) groups[index] = runner(f, handler, index < 16);
            f.clock.addAndGet(RETENTION);
            f.journal.cleanupBatch(100);
            for (int index = 16; index < 20; index++) assertEquals(1, sets(f, groups[index][1]).length);
            f.journal.cleanupBatch(100);
            for (int index = 0; index < 16; index++) assertEquals(1, sets(f, groups[index][1]).length);
            for (int index = 16; index < 20; index++) assertEquals(0, sets(f, groups[index][1]).length);
        }
    }

    @Test public void reclamationStaysCommittedWhenTheNextAdmissionIsRefused() throws Exception {
        long capacity = ChangeJournal.RESULT_JSON_LIMIT + 4096L + 100000L;
        try (UndoTestFixture f = UndoTestFixture.create("alice", capacity)) {
            String[] g = runner(f, null, false);
            String session = f.journal.createSession("alice");
            String set = f.journal.createChangeSet(session, "alice");
            f.store.locked(db -> { f.store.charge(db, 200000); return null; });
            long released = operation(f, g[2]).getBytes() + sets(f, g[1])[0].getBytes()
                    + f.entityManager.find(JournalSession.class, Query.select().where("SESSION_KEY = ?", g[0]))[0].getBytes();
            long before = f.store.usedBytes();
            f.clock.addAndGet(RETENTION);
            try {
                f.journal.openOperation("test.change", "alice", session, set);
                fail("admission must be refused by capacity");
            } catch (JournalCapacityException expected) { }
            assertEquals(0, sets(f, g[1]).length);
            assertEquals(before - released, f.store.usedBytes());
        }
    }
}
