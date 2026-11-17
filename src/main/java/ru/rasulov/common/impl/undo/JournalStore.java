package ru.rasulov.common.impl.undo;

import net.java.ao.DBParam;
import net.java.ao.Query;
import net.java.ao.RawEntity;
import ru.rasulov.common.impl.NamedLock;

import javax.inject.Inject;
import javax.inject.Named;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** All persisted transitions and budget changes share one cluster lock and one DB transaction. */
@Named
public final class JournalStore {
    public static final long DEFAULT_CAPACITY = 64L * 1024 * 1024;
    public static final int STEP_JSON_LIMIT = 64 * 1024;
    public static final long UNDO_WINDOW_MILLIS = 24L * 60 * 60 * 1000;
    static final long UNFINISHED_PROGRESS_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final int PROGRESS_CLEANUP_BATCH = 50;
    static final String LOCK = "ru.rasulov.control.undo.journal";
    static final long RECORD_OVERHEAD = 256;
    private final JournalDatabase database;
    private final NamedLock locks;
    private final long capacity;
    private final LongSupplier clock;

    @Inject
    public JournalStore(AoJournalDatabase database, NamedLock locks) {
        this(database, locks, DEFAULT_CAPACITY, System::currentTimeMillis);
    }

    JournalStore(JournalDatabase database, NamedLock locks, long capacity, LongSupplier clock) {
        this.database = database;
        this.locks = locks;
        this.capacity = capacity;
        this.clock = clock;
    }

    <T> T locked(Function<JournalDatabase, T> work) {
        return locked(work, false);
    }

    <T> T admitting(Function<JournalDatabase, T> work) {
        return locked(work, true);
    }

    private <T> T locked(Function<JournalDatabase, T> work, boolean admission) {
        return locks.run(LOCK, "the durable rollback journal", () -> {
            database.flushAll(); // AO entity caches are node-local: never read a cached transition.
            // Commit bounded expiry separately: a refused admission must not roll reclamation back.
            if (admission) database.transaction(() -> { expireUnfinishedProgress(database); return null; });
            return database.transaction(() -> work.apply(database));
        });
    }

    long now() { return clock.getAsLong(); }

    void charge(JournalDatabase db, long delta) {
        JournalBudget[] rows = db.find(JournalBudget.class, Query.select().where("BUDGET_KEY = ?", "global"));
        JournalBudget budget = rows.length == 0
                ? db.create(JournalBudget.class, new DBParam("BUDGET_KEY", "global"), new DBParam("USED_BYTES", 0L))
                : rows[0];
        long used = budget.getUsedBytes();
        if (delta > 0 && (delta > capacity || used > capacity - delta)) {
            int unfinished = db.count(JournalProgress.class, Query.select().where("SETTLED = ?", false));
            throw new JournalCapacityException("The durable journal has insufficient space (used=" + used
                    + ", requested=" + delta + ", capacity=" + capacity + " UTF-8 bytes); unfinished progress records: "
                    + unfinished + ". Unfinished progress is retained for 30 days after operation completion. "
                    + "No new mutation was started; undo evidence and reservations are preserved.");
        }
        if (delta < 0 && used < -delta) throw new IllegalStateException("Journal byte accounting underflow");
        budget.setUsedBytes(used + delta);
        budget.save();
    }

    private void expireUnfinishedProgress(JournalDatabase db) {
        JournalProgress[] candidates = db.find(JournalProgress.class, Query.select()
                .alias(JournalProgress.class, "P").alias(JournalOperation.class, "O")
                .join(JournalOperation.class, "P.OPERATION_KEY = O.OPERATION_KEY")
                .where("P.SETTLED = ? AND (O.NAME = ? OR O.NAME = ? OR O.NAME = ?) AND O.FINISHED_AT > ? AND O.FINISHED_AT <= ?"
                        + " AND O.STATE <> ? AND O.STATE <> ?", false, "set_project_issue_types", "create_project",
                        "configure_workflow", 0L, now() - UNFINISHED_PROGRESS_RETENTION_MILLIS, "RUNNING", "QUEUED")
                .order("P.\"ID\" ASC").limit(PROGRESS_CLEANUP_BATCH));
        int deleted = 0;
        long released = 0;
        for (JournalProgress progress : candidates) {
            for (JournalProgressBatch batch : db.find(JournalProgressBatch.class, Query.select()
                    .where("OPERATION_KEY = ?", progress.getOperationKey()).order("\"ID\" ASC")
                    .limit(PROGRESS_CLEANUP_BATCH - deleted))) {
                released += batch.getAllocatedBytes();
                db.delete(batch);
                if (++deleted == PROGRESS_CLEANUP_BATCH) {
                    charge(db, -released);
                    return;
                }
            }
            released += progress.getAllocatedBytes() + progress.getReservedBytes();
            db.delete(progress);
            if (++deleted == PROGRESS_CLEANUP_BATCH) break;
        }
        if (released != 0) charge(db, -released);
    }

    public long usedBytes() {
        return locked(db -> {
            JournalBudget[] rows = db.find(JournalBudget.class, Query.select().where("BUDGET_KEY = ?", "global"));
            return rows.length == 0 ? 0L : rows[0].getUsedBytes();
        });
    }

    static long bytes(String... values) {
        long result = RECORD_OVERHEAD;
        for (String value : values) if (value != null) result += value.getBytes(StandardCharsets.UTF_8).length;
        return result;
    }

    static int jsonBytes(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }

    static void checkJson(String before, String after) {
        if ((long) jsonBytes(before) + jsonBytes(after) > STEP_JSON_LIMIT) {
            throw new JournalCapacityException("An undo step exceeds the 64KiB UTF-8 JSON limit; split the migration into bounded chunks before writing.");
        }
    }

    static <T extends RawEntity<Integer>> T one(JournalDatabase db, Class<T> type, String field, Object key) {
        T[] rows = db.find(type, Query.select().where(field + " = ?", key).limit(1));
        return rows.length == 0 ? null : rows[0];
    }

    static UndoStep snapshot(JournalStep step) {
        return new UndoStep(step.getID(), step.getKind(), step.getResource(), step.getBeforeJson(), step.getAfterJson(), step.getState());
    }
}
