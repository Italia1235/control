package ru.rasulov.common.impl.undo;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import net.java.ao.DBParam;
import net.java.ao.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;

import javax.inject.Inject;
import javax.inject.Named;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;

/** Durable coordinator. Jira domain state and compensation belong exclusively to feature handlers. */
@Named
public class ChangeJournal implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(ChangeJournal.class);
    private static final String ROLLBACK_LOCK = "ru.rasulov.control.undo.rollback";
    private static final long STRANDED_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final long CLEANUP_RECHECK_MILLIS = 60L * 60 * 1000;
    private static final int STRANDED_CANDIDATES = 16;
    private static final long ERROR_ALLOWANCE = 4096;
    public static final int RESULT_JSON_LIMIT = 4 * 1024 * 1024;
    public static final int PROGRESS_JSON_LIMIT = 32 * 1024;
    public static final int PROGRESS_RECORD_BYTES = 2816;
    private static final int PROGRESS_INTENT_LIMIT = 1536;
    private static final int PROGRESS_RECEIPT_LIMIT = 512;
    private static final long ABANDONED_MILLIS = 60L * 60 * 1000;
    /**
     * A live worker holds one of these constant-name cluster locks for its whole call and records the slot in its row;
     * recovery and retention test that slot. Constant names: Beehive stores every lock name forever, so a name per
     * operation would leak a database row per apply. Covers {@code OperationRunner}'s 34 workers on 7 nodes.
     */
    static final String EXECUTION_LOCK_PREFIX = "ru.rasulov.control.undo.execution.";
    static final int EXECUTION_SLOTS = 256;
    private static final Set<String> NON_UNDOABLE_TOOLS = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
            "add_field_to_screens", "set_field_required", "clone_project_field_configurations", "assign_workflow",
            "create_field_configuration", "create_field_configuration_scheme", "assign_field_configuration_scheme",
            "set_field_configuration_mappings", "create_field_context",
            "clone_project_workflows", "clone_project_screens", "set_project_operation_screens", "set_issue_type_screen_scheme_mappings",
            "set_screen_scheme_operations", "assign_issue_type_screen_scheme", "set_project_issue_types",
            "publish_workflow_draft", "update_field_contexts", "remove_field_from_screens", "move_screen_fields", "edit_screen_layout",
            "edit_workflow", "rename_workflow", "update_status", "configure_workflow_validators",
            "configure_workflow_conditions", "configure_workflow")));
    private final JournalStore store;
    private final NamedLock locks;
    private final ActionPlanStore plans;
    private final AdministratorGuard guard;
    private final UserManager users;
    private final Map<String, UndoHandler> handlers;
    private final ThreadLocal<String> context = new ThreadLocal<>();
    private final Semaphore waitSlots = new Semaphore(8);
    private final Sleeper sleeper;
    private volatile boolean stopping;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Inject
    public ChangeJournal(JournalStore store, NamedLock locks, ActionPlanStore plans, AdministratorGuard guard,
                         @ComponentImport UserManager users, List<UndoHandler> handlers) {
        this(store, locks, plans, guard, users, handlers, Thread::sleep);
    }

    ChangeJournal(JournalStore store, NamedLock locks, ActionPlanStore plans, AdministratorGuard guard,
                  UserManager users, List<UndoHandler> handlers, Sleeper sleeper) {
        this.store = store;
        this.locks = locks;
        this.plans = plans;
        this.guard = guard;
        this.users = users;
        this.sleeper = sleeper;
        Map<String, UndoHandler> registry = new HashMap<>();
        for (UndoHandler handler : handlers) {
            if (registry.put(handler.kind(), handler) != null) throw new IllegalArgumentException("Duplicate undo handler: " + handler.kind());
        }
        this.handlers = Collections.unmodifiableMap(registry);
    }

    public String currentOperationId() { return context.get(); }

    public String createSession(String actorKey) {
        actor(actorKey);
        return admitting(db -> {
            String id = UUID.randomUUID().toString();
            long bytes = JournalStore.bytes(id, actorKey);
            store.charge(db, bytes);
            db.create(JournalSession.class, new DBParam("SESSION_KEY", id), new DBParam("ACTOR_KEY", actorKey),
                    new DBParam("CREATED_AT", store.now()), new DBParam("BYTES", bytes));
            return id;
        });
    }

    /** Explicit group: the client owns closing it. */
    public String createChangeSet(String sessionId, String actorKey) {
        return createChangeSet(sessionId, actorKey, false);
    }

    /** Runner-owned group: single operation, closed by the runner, reclaimed by fallback retention if that fails. */
    public String createRunnerChangeSet(String sessionId, String actorKey) {
        return createChangeSet(sessionId, actorKey, true);
    }

    private String createChangeSet(String sessionId, String actorKey, boolean runnerOwned) {
        actor(actorKey);
        return admitting(db -> {
            ownedSession(db, sessionId, actorKey);
            String id = UUID.randomUUID().toString();
            long bytes = JournalStore.bytes(id, sessionId, actorKey, "OPEN");
            store.charge(db, bytes);
            db.create(JournalChangeSet.class, new DBParam("CHANGE_SET_KEY", id), new DBParam("SESSION_KEY", sessionId),
                    new DBParam("ACTOR_KEY", actorKey), new DBParam("STATE", "OPEN"), new DBParam("CREATED_AT", store.now()),
                    new DBParam("FINISHED_AT", 0L), new DBParam("EXPIRES_AT", 0L), new DBParam("BYTES", bytes),
                    new DBParam("RUNNER_OWNED", runnerOwned),
                    new DBParam("NEXT_CLEANUP_AT", runnerOwned ? store.now() + STRANDED_RETENTION_MILLIS : 0L));
            return id;
        });
    }

    private <T> T admitting(Function<JournalDatabase, T> work) {
        reapStrandedRunnerGroups();
        return store.admitting(work);
    }

    public String openOperation(String operation, String actorKey, String sessionId, String changeSetId) {
        return openOperation(operation, actorKey, sessionId, changeSetId, null);
    }

    public String openOperation(String operation, String actorKey, String sessionId, String changeSetId, String planId) {
        actor(actorKey);
        text(operation, "operation name", 200);
        return admitting(db -> {
            ownedSession(db, sessionId, actorKey);
            JournalChangeSet set = ownedSet(db, changeSetId, actorKey);
            if (!Objects.equals(sessionId, set.getSessionKey())) throw invalid("The change set belongs to a different session.");
            if (!"OPEN".equals(set.getState())) throw invalid("The change set is already finished.");
            if (set.isRunnerOwned() && db.find(JournalOperation.class,
                    Query.select().where("CHANGE_SET_KEY = ?", changeSetId).limit(1)).length != 0) {
                throw invalid("A runner-owned change set accepts only its single operation.");
            }
            String id = UUID.randomUUID().toString();
            // The result/error allowance is acquired before work, not after an irreversible Jira write.
            String planHash = planId == null ? null : planHash(planId);
            long bytes = JournalStore.bytes(id, sessionId, changeSetId, actorKey, operation, planHash)
                    + RESULT_JSON_LIMIT + ERROR_ALLOWANCE;
            store.charge(db, bytes);
            db.create(JournalOperation.class, new DBParam("OPERATION_KEY", id), new DBParam("SESSION_KEY", sessionId),
                    new DBParam("CHANGE_SET_KEY", changeSetId), new DBParam("ACTOR_KEY", actorKey), new DBParam("NAME", operation),
                    new DBParam("PLAN_HASH", planHash), new DBParam("EXECUTION_SLOT", -1),
                    new DBParam("STATE", "QUEUED"), new DBParam("CANCEL_REQUESTED", false), new DBParam("ROLLBACK_REQUESTED", false),
                    new DBParam("CREATED_AT", store.now()), new DBParam("STARTED_AT", 0L), new DBParam("FINISHED_AT", 0L),
                    new DBParam("HEARTBEAT_AT", store.now()), new DBParam("RESERVED_BYTES", 0L), new DBParam("BYTES", bytes));
            return id;
        });
    }

    /**
     * Serialize lookup, admission and submission across DC nodes, not the background Jira write. One constant lock name:
     * Beehive stores every lock name in the database permanently, so a per-plan name would leak a row per apply. The
     * caller holds the node-local {@code OperationRunner} monitor while waiting; the guarded work is short.
     */
    public <T> T withPlanStart(String planId, Supplier<T> work) {
        return locks.run("ru.rasulov.control.operation-start", "operation start", work);
    }

    /** Actor-scoped lookup; deliberately includes other tools so a consumed plan cannot be reused. */
    public Map<String, Object> findOperationByPlan(String planId, String actorKey) {
        actor(actorKey);
        return store.locked(db -> {
            JournalOperation[] rows = db.find(JournalOperation.class,
                    Query.select().where("PLAN_HASH = ? AND ACTOR_KEY = ?", planHash(planId), actorKey).limit(1));
            return rows.length == 0 ? null : operationMap(db, rows[0]);
        });
    }

    private static String planHash(String planId) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(planId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                hex[2 * i] = digits[(digest[i] & 255) >>> 4];
                hex[2 * i + 1] = digits[digest[i] & 15];
            }
            return new String(hex);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }

    public <T> T execute(String operation, String actorKey, Supplier<T> work) {
        if (context.get() != null) {
            actor(actorKey);
            store.locked(db -> { ownedOperation(db, context.get(), actorKey); return null; });
            checkpoint();
            return work.get();
        }
        String session = createSession(actorKey);
        String set = createChangeSet(session, actorKey);
        String id = openOperation(operation, actorKey, session, set);
        try { return runOperation(id, work); }
        finally { finishChangeSet(set, actorKey); }
    }

    public <T> T runOperation(String operationId, Supplier<T> work) {
        if (context.get() != null) throw invalid("An operation cannot be started inside another operation.");
        String actorKey = guard.requireAdministrator().getKey();
        return locks.runOnFreeSlot(EXECUTION_LOCK_PREFIX, EXECUTION_SLOTS, operationId.hashCode(), "operation execution", slot -> {
            String name = store.locked(db -> {
                JournalOperation operation = ownedOperation(db, operationId, actorKey);
                if (!"QUEUED".equals(operation.getState())) throw invalid("The operation has already started or completed.");
                operation.setState("RUNNING");
                operation.setStartedAt(store.now());
                operation.setExecutionSlot(slot);
                operation.setHeartbeatAt(store.now());
                operation.save();
                return operation.getName();
            });
            context.set(operationId);
            boolean workReturned = false;
            try {
                checkpoint();
                T result = work.get();
                workReturned = true;
                // A forward-only tool has nothing to compensate: a cancel that arrives after its last write must not
                // replace its finished result (created ids, PARTIAL/UNKNOWN items) with CANCELLED.
                boolean forwardOnly = NON_UNDOABLE_TOOLS.contains(name);
                if (!forwardOnly) checkpoint();
                String json = JournalJson.write(result);
                if (JournalStore.jsonBytes(json) > RESULT_JSON_LIMIT) {
                    throw new JournalCapacityException("The operation result exceeds the 4MiB preallocated durable result limit.");
                }
                complete(operationId, "COMPLETED", json, null, !forwardOnly);
                return result;
            } catch (RuntimeException | Error failure) {
                boolean cancelled = failure instanceof JournalCancelledException;
                boolean compensate = store.locked(db -> {
                    JournalOperation operation = ownedOperation(db, operationId, actorKey);
                    return !NON_UNDOABLE_TOOLS.contains(name) && (!cancelled || operation.isRollbackRequested());
                });
                // Once the tool has returned, Jira may have been changed: a failure to persist its result must not read as
                // "nothing happened".
                String reason = workReturned && !cancelled
                        ? "The tool finished and Jira may have been changed, but its result could not be stored: " + message(failure)
                        : message(failure);
                complete(operationId, cancelled ? "CANCELLED" : "FAILED", null, reason);
                if (compensate) {
                    try { compensate(operationId, actorKey); }
                    catch (RuntimeException | Error undoFailure) { failure.addSuppressed(undoFailure); }
                }
                throw failure; // Preserve the service's original domain exception.
            } finally {
                context.remove();
            }
        });
    }

    /** Operations that write issue-type scheme steps with durable progress: the tool itself and project creation with a final type set. */
    static boolean tracksIssueTypeProgress(String operationName) {
        return "set_project_issue_types".equals(operationName) || "create_project".equals(operationName);
    }

    private static boolean tracksProgress(String operationName) {
        return tracksIssueTypeProgress(operationName) || "configure_workflow".equals(operationName);
    }

    public void reserve(long bytes) {
        if (bytes < 0) throw invalid("The reservation must not be negative.");
        String id = requiredContext();
        admitting(db -> {
            JournalOperation operation = running(db, id);
            checkCancelled(operation);
            store.charge(db, bytes);
            operation.setReservedBytes(Math.addExact(operation.getReservedBytes(), bytes));
            operation.setHeartbeatAt(store.now());
            operation.save();
            return null;
        });
    }

    /** Allocates the entire forward-progress receipt before any domain mutation. Not an undo reservation. */
    public void startIssueTypeProgress(Object execution, int recordCount) {
        startProgress(execution, recordCount, false);
    }

    /** Preallocates the complete workflow receipt before the first native write. No undo or batch records are created. */
    public void startWorkflowProgress(Object execution) {
        startProgress(execution, 0, true);
    }

    private void startProgress(Object execution, int recordCount, boolean workflow) {
        String id = requiredContext();
        String json = workflow ? workflowProgressJson(execution) : progressJson(execution);
        if (recordCount < 0) throw invalid("Progress record reservation must not be negative.");
        long reserved = Math.multiplyExact((long) recordCount, PROGRESS_RECORD_BYTES);
        admitting(db -> {
            JournalOperation operation = running(db, id);
            checkCancelled(operation);
            requireProgressTool(operation, workflow);
            if (JournalStore.one(db, JournalProgress.class, "OPERATION_KEY", id) != null)
                throw invalid("Execution progress has already started.");
            long allocation = JournalStore.bytes(id) + PROGRESS_JSON_LIMIT;
            store.charge(db, Math.addExact(allocation, reserved));
            db.create(JournalProgress.class, new DBParam("OPERATION_KEY", id),
                    new DBParam("EXECUTION_JSON", json), new DBParam("SETTLED", false),
                    new DBParam("ALLOCATED_BYTES", allocation), new DBParam("RESERVED_BYTES", reserved));
            return null;
        });
    }

    /** Never checks cancellation: a successful native pair must be receipted before cancellation is honored. */
    public void issueTypeProgress(Object execution, boolean settled) {
        saveProgress(execution, settled, false);
    }

    /** Persists intent, write acknowledgement, or read-back independently, including after a cancellation request. */
    public void workflowProgress(Object execution, boolean settled) {
        saveProgress(execution, settled, true);
    }

    private void saveProgress(Object execution, boolean settled, boolean workflow) {
        String id = requiredContext();
        String json = workflow ? workflowProgressJson(execution) : progressJson(execution);
        store.locked(db -> {
            JournalOperation operation = running(db, id);
            requireProgressTool(operation, workflow);
            JournalProgress progress = JournalStore.one(db, JournalProgress.class, "OPERATION_KEY", id);
            if (progress == null) throw invalid("Execution progress was not allocated before mutation.");
            progress.setExecutionJson(json);
            progress.setSettled(settled);
            progress.save();
            operation.setHeartbeatAt(store.now());
            operation.save();
            return null;
        });
    }

    private static void requireProgressTool(JournalOperation operation, boolean workflow) {
        if (workflow ? !"configure_workflow".equals(operation.getName()) : !tracksIssueTypeProgress(operation.getName()))
            throw invalid(workflow ? "Workflow execution progress is only supported for configure_workflow."
                    : "Issue-type execution progress is only supported for set_project_issue_types and create_project.");
    }

    /** Atomically publishes the bounded intent and its conservative UNKNOWN execution before native writes. */
    public int beginIssueTypeBatch(String kind, Object intent, Object execution) {
        String id = requiredContext();
        String before = boundedProgressJson(intent, PROGRESS_INTENT_LIMIT);
        String json = progressJson(execution);
        if (!"ISSUES".equals(kind) && !"SCHEME".equals(kind)) throw invalid("Unknown progress record kind.");
        return store.locked(db -> {
            JournalOperation operation = running(db, id);
            checkCancelled(operation);
            JournalProgress progress = JournalStore.one(db, JournalProgress.class, "OPERATION_KEY", id);
            if (progress == null || progress.getReservedBytes() < PROGRESS_RECORD_BYTES)
                throw new JournalCapacityException("The preallocated progress budget is exhausted; no new batch was started.");
            JournalProgressBatch batch = db.create(JournalProgressBatch.class, new DBParam("OPERATION_KEY", id),
                    new DBParam("KIND", kind), new DBParam("BEFORE_JSON", before),
                    new DBParam("ALLOCATED_BYTES", (long) PROGRESS_RECORD_BYTES));
            progress.setReservedBytes(progress.getReservedBytes() - PROGRESS_RECORD_BYTES);
            progress.setExecutionJson(json);
            progress.save();
            operation.setHeartbeatAt(store.now());
            operation.save();
            return batch.getID();
        });
    }

    /** A receipt and its public projection commit together; cancellation must not hide a completed native pair. */
    public void finishIssueTypeBatch(int batchId, Object receipt, Object execution) {
        String id = requiredContext();
        String after = boundedProgressJson(receipt, PROGRESS_RECEIPT_LIMIT);
        String json = progressJson(execution);
        store.locked(db -> {
            JournalOperation operation = running(db, id);
            JournalProgressBatch batch = JournalStore.one(db, JournalProgressBatch.class, "ID", batchId);
            if (batch == null || !id.equals(batch.getOperationKey()) || batch.getAfterJson() != null)
                throw invalid("The progress batch is unavailable or already receipted.");
            JournalStore.checkJson(batch.getBeforeJson(), after);
            JournalProgress progress = JournalStore.one(db, JournalProgress.class, "OPERATION_KEY", id);
            batch.setAfterJson(after);
            batch.save();
            progress.setExecutionJson(json);
            progress.save();
            operation.setHeartbeatAt(store.now());
            operation.save();
            return null;
        });
    }

    /** Immutable receipts, not native-state caching; both the candidate IDs and each journal page are bounded. */
    public boolean hasCompletedIssueTypeIds(List<Long> sortedIssueIds) {
        String id = requiredContext();
        return store.locked(db -> {
            int afterId = 0;
            while (true) {
                JournalProgressBatch[] page = db.find(JournalProgressBatch.class, Query.select()
                        .where("OPERATION_KEY = ? AND KIND = ? AND ID > ?", id, "ISSUES", afterId)
                        .order("\"ID\" ASC").limit(50));
                for (JournalProgressBatch batch : page) {
                    afterId = batch.getID();
                    if (batch.getAfterJson() == null) continue;
                    Map<?, ?> receipt = JournalJson.read(batch.getAfterJson(), Map.class);
                    int prefix = ((Number) receipt.get("completedPrefix")).intValue();
                    Map<?, ?> intent = JournalJson.read(batch.getBeforeJson(), Map.class);
                    List<?> recordedIds = (List<?>) intent.get("issueIds");
                    for (int index = 0; index < prefix; index++) {
                        long issueId = ((Number) recordedIds.get(index)).longValue();
                        if (Collections.binarySearch(sortedIssueIds, issueId) >= 0) return true;
                    }
                }
                if (page.length < 50) return false;
            }
        });
    }

    private static String boundedProgressJson(Object value, int limit) {
        String json = JournalJson.write(value);
        if (JournalStore.jsonBytes(json) > limit)
            throw new JournalCapacityException("A progress intent/receipt exceeds its preallocated UTF-8 byte limit.");
        return json;
    }

    private static String progressJson(Object execution) {
        String json = JournalJson.write(execution);
        if (JournalStore.jsonBytes(json) > PROGRESS_JSON_LIMIT)
            throw new JournalCapacityException("Execution progress exceeds its preallocated 32KiB UTF-8 limit.");
        return json;
    }

    private static String workflowProgressJson(Object execution) {
        String json = WorkflowReceiptCodec.write(execution);
        if (JournalStore.jsonBytes(json) > PROGRESS_JSON_LIMIT)
            throw new JournalCapacityException("Workflow execution exceeds its preallocated 32KiB encoded storage limit.");
        return json;
    }

    public UndoStep prepare(String kind, String resource, String beforeJson, String expectedAfterJson) {
        return prepare(kind, resource, beforeJson, expectedAfterJson,
                JournalStore.STEP_JSON_LIMIT - JournalStore.jsonBytes(beforeJson));
    }

    /** A proven poststate upper bound avoids reserving 64KiB for compact fingerprint steps. */
    public UndoStep prepare(String kind, String resource, String beforeJson, String expectedAfterJson, int maxAfterBytes) {
        String id = requiredContext();
        text(kind, "undo handler kind", 200);
        text(resource, "resource identity", 450);
        if (!handlers.containsKey(kind)) throw invalid("No compensation handler is registered for " + kind + ". Nothing was written.");
        if (beforeJson == null) throw invalid("An undo intent requires the original state or explicit absence.");
        JournalStore.checkJson(beforeJson, expectedAfterJson);
        if (maxAfterBytes < JournalStore.jsonBytes(expectedAfterJson) || maxAfterBytes < 0
                || (long) JournalStore.jsonBytes(beforeJson) + maxAfterBytes > JournalStore.STEP_JSON_LIMIT) {
            throw new JournalCapacityException("The declared undo poststate bound exceeds 64KiB or is smaller than the expected JSON.");
        }
        return admitting(db -> {
            JournalOperation operation = running(db, id);
            checkCancelled(operation);
            long allocation = JournalStore.bytes(id, kind, resource, beforeJson) + maxAfterBytes + ERROR_ALLOWANCE;
            long reserved = Math.min(operation.getReservedBytes(), allocation);
            store.charge(db, allocation - reserved);
            operation.setReservedBytes(operation.getReservedBytes() - reserved);
            operation.setHeartbeatAt(store.now());
            operation.save();
            JournalStep step = db.create(JournalStep.class, new DBParam("OPERATION_KEY", id), new DBParam("KIND", kind),
                    new DBParam("RESOURCE", resource), new DBParam("BEFORE_JSON", beforeJson), new DBParam("AFTER_JSON", expectedAfterJson),
                    new DBParam("STATE", "PREPARED"), new DBParam("CREATED_AT", store.now()),
                    new DBParam("ALLOCATED_BYTES", allocation), new DBParam("MAX_AFTER_BYTES", maxAfterBytes));
            return JournalStore.snapshot(step);
        });
    }

    public void applied(UndoStep intent, String actualAfterJson) {
        if (actualAfterJson == null) throw invalid("An applied step requires the observed poststate.");
        String id = requiredContext();
        store.locked(db -> {
            JournalOperation operation = running(db, id);
            JournalStep step = ownStep(db, intent.getId(), id);
            if (!Arrays.asList("PREPARED", "APPLIED").contains(step.getState())) throw invalid("The step is no longer writable.");
            JournalStore.checkJson(step.getBeforeJson(), actualAfterJson);
            if (JournalStore.jsonBytes(actualAfterJson) > step.getMaxAfterBytes()) {
                throw new JournalCapacityException("The observed poststate exceeded its pre-write allocation; this intent needs reconciliation.");
            }
            step.setAfterJson(actualAfterJson);
            step.setState("APPLIED");
            step.save();
            operation.setHeartbeatAt(store.now());
            operation.save();
            return null; // Do not checkpoint here: a cancellation must never hide a successful write.
        });
    }

    /** Reuse a bounded step only after durably marking its next native write as uncertain. */
    public void prepareUpdate(UndoStep intent) {
        String id = requiredContext();
        store.locked(db -> {
            JournalOperation operation = running(db, id);
            checkCancelled(operation);
            JournalStep step = ownStep(db, intent.getId(), id);
            if (!"APPLIED".equals(step.getState())) throw invalid("Only a proven applied step can begin another write.");
            step.setState("PREPARED");
            step.save();
            operation.setHeartbeatAt(store.now());
            operation.save();
            return null;
        });
    }

    public void discard(UndoStep intent) {
        String id = requiredContext();
        store.locked(db -> {
            JournalStep step = ownStep(db, intent.getId(), id);
            step.setState("DISCARDED");
            step.setError(null);
            step.save();
            return null;
        });
    }

    public void checkpoint() {
        String id = requiredContext();
        store.locked(db -> {
            JournalOperation operation = running(db, id);
            checkCancelled(operation);
            operation.setHeartbeatAt(store.now());
            operation.save();
            return null;
        });
    }

    /** Fresh cooperative cancellation read, without a per-issue heartbeat/progress write. */
    public void checkIssueTypeCancellation() {
        String id = requiredContext();
        store.locked(db -> { checkCancelled(running(db, id)); return null; });
    }

    public Map<String, Object> getOperation(String id, String actorKey) {
        actor(actorKey);
        return store.locked(db -> operationMap(db, ownedOperation(db, id, actorKey)));
    }

    /** Poll only state/ownership; materialize the potentially large result exactly once, at the end. */
    public Map<String, Object> getOperation(String id, String actorKey, int waitSeconds) {
        if (waitSeconds < 0 || waitSeconds > 20) throw invalid("'waitSeconds' must be between 0 and 20.");
        if (waitSeconds == 0) return getOperation(id, actorKey);
        actor(actorKey);
        String state = operationState(id, actorKey);
        if (!waiting(state) || stopping) return getOperation(id, actorKey);
        if (!waitSlots.tryAcquire()) {
            LOG.debug("get_operation wait capacity reached; returning immediately");
            return getOperation(id, actorKey);
        }
        boolean interrupted = false;
        try {
            long deadline = store.now() + waitSeconds * 1000L;
            long interval = 500L;
            while (waiting(state) && !stopping && !Thread.currentThread().isInterrupted()) {
                long remaining = deadline - store.now();
                if (remaining <= 0) break;
                try {
                    sleeper.sleep(Math.min(interval, remaining));
                    interval = Math.min(interval * 2, 2000L);
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
                if (stopping || store.now() >= deadline) break;
                state = operationState(id, actorKey);
            }
            // NamedLock uses interruptible acquisition. Restore the flag after the final DB read.
            interrupted |= Thread.interrupted();
            return getOperation(id, actorKey);
        } finally {
            waitSlots.release();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    String operationState(String id, String actorKey) {
        return store.locked(db -> {
            JournalOperation[] rows = db.find(JournalOperation.class,
                    Query.select("ACTOR_KEY, STATE").where("OPERATION_KEY = ?", id).limit(1));
            JournalOperation row = rows.length == 0 ? null : rows[0];
            requireOperationOwner(row, actorKey);
            return row.getState();
        });
    }

    private static boolean waiting(String state) {
        return "QUEUED".equals(state) || "RUNNING".equals(state) || "CANCEL_REQUESTED".equals(state);
    }

    @Override
    public void destroy() {
        stopping = true;
    }

    public List<Map<String, Object>> listChanges(String sessionId, String actorKey) {
        actor(actorKey);
        return store.locked(db -> {
            ownedSession(db, sessionId, actorKey);
            List<Map<String, Object>> result = new ArrayList<>();
            for (JournalOperation operation : db.find(JournalOperation.class,
                    Query.select().where("SESSION_KEY = ?", sessionId).order("\"ID\" DESC"))) result.add(operationMap(db, operation));
            return result;
        });
    }

    public List<Map<String, Object>> listSessions(String actorKey) {
        actor(actorKey);
        return store.locked(db -> {
            List<Map<String, Object>> result = new ArrayList<>();
            for (JournalSession session : db.find(JournalSession.class,
                    Query.select().where("ACTOR_KEY = ?", actorKey).order("\"ID\" DESC"))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sessionId", session.getSessionKey());
                row.put("createdAt", session.getCreatedAt());
                List<Map<String, Object>> sets = new ArrayList<>();
                for (JournalChangeSet set : db.find(JournalChangeSet.class, Query.select().where("SESSION_KEY = ?", session.getSessionKey()).order("\"ID\" DESC"))) {
                    sets.add(setMap(set));
                }
                row.put("changeSets", sets);
                result.add(row);
            }
            return result;
        });
    }

    public Map<String, Object> finishChangeSet(String changeSetId, String actorKey) {
        actor(actorKey);
        return store.locked(db -> {
            JournalChangeSet set = ownedSet(db, changeSetId, actorKey);
            if ("OPEN".equals(set.getState())) {
                for (JournalOperation operation : db.find(JournalOperation.class, Query.select().where("CHANGE_SET_KEY = ?", changeSetId))) {
                    if (active(operation)) throw invalid("A change set cannot finish while an operation is queued or running.");
                }
                set.setState("FINISHED");
                set.setFinishedAt(store.now());
                set.setExpiresAt(store.now() + JournalStore.UNDO_WINDOW_MILLIS);
                set.save();
            }
            return setMap(set);
        });
    }

    public Map<String, Object> failOperation(String operationId, String actorKey, String message) {
        actor(actorKey);
        return store.locked(db -> {
            JournalOperation operation = ownedOperation(db, operationId, actorKey);
            if ("QUEUED".equals(operation.getState())) {
                operation.setState("FAILED");
                operation.setFinishedAt(store.now());
                operation.setError(limit(message));
                releaseReservation(db, operation);
                operation.save();
            }
            return operationMap(db, operation);
        });
    }

    public Map<String, Object> cancel(String operationId, String actorKey, boolean rollback) {
        actor(actorKey);
        return store.locked(db -> {
            JournalOperation operation = ownedOperation(db, operationId, actorKey);
            boolean wasActive = active(operation);
            if (wasActive) {
                operation.setCancelRequested(true);
                operation.setRollbackRequested(operation.isRollbackRequested() || rollback);
                if ("QUEUED".equals(operation.getState())) {
                    operation.setState("CANCELLED");
                    operation.setFinishedAt(store.now());
                    releaseReservation(db, operation);
                }
                operation.save();
            }
            Map<String, Object> result = operationMap(db, operation);
            if (!wasActive && rollback) result.put("requiresRollbackPreview", true);
            return result;
        });
    }

    public Map<String, Object> rollback(String actorKey, String operationId, String changeSetId, String sessionId,
                                       boolean dryRun, String planId) {
        ApplicationUser actor = actor(actorKey);
        if (!dryRun) {
            ActionPlanStore.Plan plan = plans.find("rollback", actorKey, planId);
            if (plan == null) throw invalid("Preview the rollback and supply its unexpired single-use planId.");
            if (operationId != null || changeSetId != null || sessionId != null) throw invalid("A confirmed rollback accepts only planId, not another scope.");
            String request = plan.getRequest();
            if (request.startsWith("operation:")) operationId = request.substring("operation:".length());
            else if (request.startsWith("changeSet:")) changeSetId = request.substring("changeSet:".length());
            else if (request.startsWith("session:")) sessionId = request.substring("session:".length());
            else throw invalid("Invalid rollback preview selection.");
        }
        final String selectedOperation = operationId;
        final String selectedSet = changeSetId;
        final String selectedSession = sessionId;
        Selection selection = select(actorKey, selectedOperation, selectedSet, selectedSession);
        return withSelectionLocks(selection, () -> {
            Selection current = select(actorKey, selectedOperation, selectedSet, selectedSession);
            List<Map<String, Object>> conflicts = preflight(current);
            String fingerprint = fingerprint(current);
            Map<String, Object> response = previewMap(current, conflicts, -1);
            if (dryRun) {
                ActionPlanStore.Plan plan = plans.issue("rollback", actorKey, current.request, fingerprint);
                response.put("planId", plan.getId());
                response.put("planExpiresAt", plan.getExpiresAt());
                return response;
            }
            ActionPlanStore.Plan plan = plans.find("rollback", actorKey, planId);
            if (plan == null || !current.request.equals(plan.getRequest()) || !fingerprint.equals(plan.getFingerprint())) {
                throw invalid("The rollback preview expired, was consumed, or its selection/state changed. Preview again.");
            }
            plans.remove(planId);
            if (!conflicts.isEmpty()) {
                response.put("status", "BLOCKED");
                return response;
            }
            actor(actorKey); // Recheck ownership's resolved principal and current permission at the write boundary.
            return apply(current, actor);
        });
    }

    private void compensate(String operationId, String actorKey) {
        Selection selection = select(actorKey, operationId, null, null);
        withSelectionLocks(selection, () -> {
            Selection current = select(actorKey, operationId, null, null);
            List<Map<String, Object>> conflicts = preflight(current);
            if (!conflicts.isEmpty()) {
                store.locked(db -> {
                    JournalOperation operation = ownedOperation(db, operationId, actorKey);
                    operation.setState("BLOCKED");
                    operation.setError(limit("Compensation blocked: " + JournalJson.write(conflicts)));
                    operation.save();
                    return null;
                });
                return null;
            }
            apply(current, actor(actorKey));
            return null;
        });
    }

    private Map<String, Object> apply(Selection selection, ApplicationUser actor) {
        int undone = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        HistoryState history = new HistoryState(selection);
        for (UndoStep step : selection.steps) {
            try {
                UndoHandler handler = handlers.get(step.getKind());
                actor(actor.getKey());
                handler.undo(step, actor, history.steps, history.receipts.after(step.getId()));
                String receipt = handler.undoReceipt(step);
                if (receipt != null) history.receipts.add(step.getId(), receipt);
                store.locked(db -> {
                    JournalStep row = JournalStore.one(db, JournalStep.class, "ID", (int) step.getId());
                    if (row == null) throw new IllegalStateException("Undo step disappeared during compensation");
                    row.setState("UNDONE");
                    row.setError(null);
                    row.setUndoReceiptJson(receipt);
                    row.save();
                    return null;
                });
                history.undone(step);
                undone++;
            } catch (RuntimeException | Error failure) {
                failures.add(conflictMap(step, message(failure)));
                store.locked(db -> {
                    JournalStep row = JournalStore.one(db, JournalStep.class, "ID", (int) step.getId());
                    row.setState("BLOCKED");
                    row.setError(limit(message(failure)));
                    row.save();
                    return null;
                });
                break; // A prerequisite failed: never continue into a dependent deletion/restoration.
            }
        }
        final int completed = undone;
        store.locked(db -> {
            Set<String> affectedSets = new TreeSet<>();
            for (String id : selection.operationIds) {
                JournalOperation operation = ownedOperation(db, id, actor.getKey());
                if (NON_UNDOABLE_TOOLS.contains(operation.getName())) continue;
                boolean remains = false;
                for (JournalStep row : db.find(JournalStep.class, Query.select().where("OPERATION_KEY = ?", id))) {
                    if (pending(row.getState())) { remains = true; break; }
                }
                operation.setState(remains ? (completed > 0 ? "PARTIAL" : "BLOCKED") : "ROLLED_BACK");
                operation.save();
                affectedSets.add(operation.getChangeSetKey());
            }
            for (String setId : affectedSets) {
                JournalChangeSet set = ownedSet(db, setId, actor.getKey());
                if ("RETAINED".equals(set.getState()) && !unresolvedSet(db, set)) {
                    set.setState("FINISHED");
                    set.save();
                }
            }
            return null;
        });
        Map<String, Object> response = previewMap(selection, failures, undone);
        response.put("status", failures.isEmpty()
                ? (selection.nonUndoableOperations.isEmpty() ? "ROLLED_BACK" : "PARTIAL")
                : undone > 0 ? "PARTIAL" : "BLOCKED");
        response.put("undoneSteps", undone);
        return response;
    }


    private List<Map<String, Object>> preflight(Selection selection) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, UndoStep> next = new HashMap<>();
        Map<String, UndoStep> live = new HashMap<>();
        for (UndoStep step : selection.steps) {
            live.putIfAbsent(step.getResource(), step);
            UndoHandler handler = handlers.get(step.getKind());
            String reason = null;
            if ("PREPARED".equals(step.getState())) reason = "The write intent has no proven committed poststate; inspect/reconcile the Jira object before undo.";
            else if (handler == null) reason = "No handler is registered for " + step.getKind();
            else {
                UndoStep newer = next.get(step.getResource());
                if (newer != null) {
                    if (!handler.sameState(step, newer, selection.history)) {
                        reason = "The selected history does not form a coherent state chain for " + step.getResource();
                    }
                    if (reason == null) {
                        UndoStep latest = live.get(step.getResource());
                        if ("PREPARED".equals(latest.getState())) reason = "A newer write intent has no proven committed state.";
                        else {
                            try {
                                UndoHandler liveHandler = handlers.get(latest.getKind());
                                UndoStep beforeProbe = new UndoStep(latest.getId(), latest.getKind(), latest.getResource(),
                                        latest.getBeforeJson(), latest.getBeforeJson(), latest.getState());
                                String currentJson = liveHandler != null && liveHandler.conflict(beforeProbe, selection.history, selection.receipts.after(latest.getId())) == null
                                        ? latest.getBeforeJson() : latest.getAfterJson();
                                UndoStep projected = new UndoStep(step.getId(), step.getKind(), step.getResource(),
                                        step.getBeforeJson(), currentJson, step.getState());
                                reason = handler.conflict(projected, selection.history, selection.receipts.after(step.getId()));
                            } catch (RuntimeException failure) { reason = "Cannot inspect dependent state: " + message(failure); }
                        }
                    }
                } else {
                    try { reason = handler.conflict(step, selection.history, selection.receipts.after(step.getId())); }
                    catch (RuntimeException failure) { reason = "Cannot inspect current state: " + message(failure); }
                }
            }
            if (reason != null) conflicts.add(conflictMap(step, reason));
            next.put(step.getResource(), step);
        }
        return conflicts;
    }

    private <T> T withSelectionLocks(Selection selection, Supplier<T> work) {
        Set<String> names = new TreeSet<>();
        for (UndoStep step : selection.steps) {
            UndoHandler handler = handlers.get(step.getKind());
            if (handler != null) names.addAll(handler.lockNames(step));
        }
        return locks.run(ROLLBACK_LOCK, "rollback", () -> withLocks(new ArrayList<>(names), 0, work));
    }

    private <T> T withLocks(List<String> names, int index, Supplier<T> work) {
        if (index == names.size()) return work.get();
        return locks.run(names.get(index), "rollback resources", () -> withLocks(names, index + 1, work));
    }

    private Selection select(String actorKey, String operationId, String changeSetId, String sessionId) {
        int scopes = (operationId == null ? 0 : 1) + (changeSetId == null ? 0 : 1) + (sessionId == null ? 0 : 1);
        if (scopes != 1) throw invalid("Select exactly one operationId, changeSetId, or sessionId.");
        return store.locked(db -> {
            List<JournalOperation> operations = new ArrayList<>();
            String request;
            if (operationId != null) {
                operations.add(ownedOperation(db, operationId, actorKey));
                request = "operation:" + operationId;
            } else if (changeSetId != null) {
                ownedSet(db, changeSetId, actorKey);
                operations.addAll(Arrays.asList(db.find(JournalOperation.class, Query.select().where("CHANGE_SET_KEY = ?", changeSetId).order("\"ID\" DESC"))));
                request = "changeSet:" + changeSetId;
            } else {
                ownedSession(db, sessionId, actorKey);
                operations.addAll(Arrays.asList(db.find(JournalOperation.class, Query.select().where("SESSION_KEY = ?", sessionId).order("\"ID\" DESC"))));
                request = "session:" + sessionId;
            }
            List<String> ids = new ArrayList<>();
            List<Map<String, Object>> nonUndoableOperations = new ArrayList<>();
            List<UndoStep> steps = new ArrayList<>();
            List<UndoStep> history = new ArrayList<>();
            UndoReceiptIndex receipts = new UndoReceiptIndex();
            Map<String, Boolean> recoverableSets = new HashMap<>();
            for (JournalOperation operation : operations) {
                if (active(operation)) throw invalid("Cancel running operations and wait for a safe boundary before rolling back this scope.");
                JournalChangeSet set = ownedSet(db, operation.getChangeSetKey(), actorKey);
                if (set.getExpiresAt() > 0 && set.getExpiresAt() <= store.now()
                        && !recoverableSets.computeIfAbsent(set.getChangeSetKey(), ignored -> unresolvedSet(db, set))) {
                    throw invalid("This change set's 24-hour undo window expired.");
                }
                ids.add(operation.getOperationKey());
                if (NON_UNDOABLE_TOOLS.contains(operation.getName())) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("toolName", operation.getName());
                    item.put("operationId", operation.getOperationKey());
                    nonUndoableOperations.add(Collections.unmodifiableMap(item));
                }
                for (JournalStep row : db.find(JournalStep.class, Query.select().where("OPERATION_KEY = ?", operation.getOperationKey()))) {
                    UndoStep snapshot = JournalStore.snapshot(row);
                    history.add(snapshot);
                    if (pending(row.getState()) && !NON_UNDOABLE_TOOLS.contains(operation.getName())) steps.add(snapshot);
                    if ("UNDONE".equals(row.getState()) && row.getUndoReceiptJson() != null) {
                        receipts.add(row.getID(), row.getUndoReceiptJson());
                    }
                }
            }
            steps.sort((left, right) -> Long.compare(right.getId(), left.getId()));
            history.sort((left, right) -> Long.compare(right.getId(), left.getId()));
            return new Selection(request, ids, Collections.unmodifiableList(steps), Collections.unmodifiableList(history),
                    receipts, Collections.unmodifiableList(nonUndoableOperations));
        });
    }

    private String fingerprint(Selection selection) {
        StringBuilder value = new StringBuilder(selection.request);
        for (String id : selection.operationIds) value.append('\n').append("operation:").append(id);
        for (UndoStep step : selection.history) {
            value.append('\n').append(step.getId()).append(':').append(step.getState()).append(':')
                    .append(step.getKind()).append(':').append(step.getResource()).append(':')
                    .append(step.getBeforeJson()).append(':').append(step.getAfterJson());
        }
        for (Map.Entry<Long, String> receipt : selection.receipts.records().entrySet()) {
            value.append('\n').append(receipt.getKey()).append(':').append(receipt.getValue());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void complete(String id, String state, String result, String error) {
        complete(id, state, result, error, true);
    }

    private void complete(String id, String state, String result, String error, boolean honorCancel) {
        store.locked(db -> {
            JournalOperation operation = JournalStore.one(db, JournalOperation.class, "OPERATION_KEY", id);
            if ("COMPLETED".equals(state) && honorCancel) checkCancelled(operation);
            operation.setState(state);
            operation.setResultJson(result);
            operation.setError(limit(error));
            operation.setFinishedAt(store.now());
            releaseReservation(db, operation);
            releaseProgressReservation(db, operation);
            for (JournalStep step : db.find(JournalStep.class, Query.select().where("OPERATION_KEY = ?", id))) {
                long exact = JournalStore.bytes(id, step.getKind(), step.getResource(), step.getBeforeJson(), step.getAfterJson()) + ERROR_ALLOWANCE;
                store.charge(db, exact - step.getAllocatedBytes());
                step.setAllocatedBytes(exact);
                step.save();
            }
            long exactOperation = JournalStore.bytes(operation.getOperationKey(), operation.getSessionKey(),
                    operation.getChangeSetKey(), operation.getActorKey(), operation.getName(), result) + ERROR_ALLOWANCE;
            store.charge(db, exactOperation - operation.getBytes());
            operation.setBytes(exactOperation);
            operation.save();
            return null;
        });
    }

    private void releaseReservation(JournalDatabase db, JournalOperation operation) {
        store.charge(db, -operation.getReservedBytes());
        operation.setReservedBytes(0L);
    }

    private void releaseProgressReservation(JournalDatabase db, JournalOperation operation) {
        if (!tracksProgress(operation.getName())) return;
        JournalProgress progress = JournalStore.one(db, JournalProgress.class, "OPERATION_KEY", operation.getOperationKey());
        if (progress != null && progress.getReservedBytes() != 0) {
            store.charge(db, -progress.getReservedBytes());
            progress.setReservedBytes(0L);
            progress.save();
        }
    }

    /** Invoked hourly by the cluster-singleton scheduler; each transaction deletes at most one small batch. */
    public int cleanupBatch(int batchSize) {
        if (batchSize < 1 || batchSize > 500) throw invalid("Cleanup batch size must be 1..500.");
        reapStrandedRunnerGroups();
        recoverAbandoned(batchSize);
        return store.locked(db -> {
            long now = store.now();
            for (JournalChangeSet empty : db.find(JournalChangeSet.class,
                    Query.select().where("STATE = ? AND CREATED_AT < ?", "OPEN", now - JournalStore.UNDO_WINDOW_MILLIS).limit(batchSize))) {
                if (db.find(JournalOperation.class, Query.select().where("CHANGE_SET_KEY = ?", empty.getChangeSetKey()).limit(1)).length == 0) {
                    empty.setState("FINISHED");
                    empty.setFinishedAt(empty.getCreatedAt());
                    empty.setExpiresAt(empty.getCreatedAt() + JournalStore.UNDO_WINDOW_MILLIS);
                    empty.save();
                }
            }
            int deleted = 0;
            for (JournalChangeSet set : db.find(JournalChangeSet.class,
                    Query.select().where("STATE = ? AND EXPIRES_AT > ? AND EXPIRES_AT <= ?", "FINISHED", 0L, now).order("\"ID\" ASC").limit(batchSize))) {
                JournalOperation[] operations = db.find(JournalOperation.class, Query.select().where("CHANGE_SET_KEY = ?", set.getChangeSetKey()));
                boolean unresolved = false;
                for (JournalOperation operation : operations) {
                    if (unresolved(db, operation)) { unresolved = true; break; }
                }
                if (unresolved) {
                    // Expired unresolved sets remain durable, but must not starve later cleanup batches.
                    set.setState("RETAINED");
                    set.save();
                    continue;
                }
                // Delete at most batchSize rows, keeping parent rows until every child is gone.
                for (JournalOperation operation : operations) {
                    for (JournalProgressBatch batch : db.find(JournalProgressBatch.class,
                            Query.select().where("OPERATION_KEY = ?", operation.getOperationKey()).limit(batchSize - deleted))) {
                        store.charge(db, -batch.getAllocatedBytes());
                        db.delete(batch);
                        if (++deleted == batchSize) return deleted;
                    }
                    for (JournalProgress progress : db.find(JournalProgress.class,
                            Query.select().where("OPERATION_KEY = ?", operation.getOperationKey()))) {
                        store.charge(db, -progress.getAllocatedBytes() - progress.getReservedBytes());
                        db.delete(progress);
                        if (++deleted == batchSize) return deleted;
                    }
                    for (JournalStep step : db.find(JournalStep.class, Query.select().where("OPERATION_KEY = ?", operation.getOperationKey()).limit(batchSize - deleted))) {
                        store.charge(db, -step.getAllocatedBytes());
                        db.delete(step);
                        if (++deleted == batchSize) return deleted;
                    }
                    if (db.find(JournalStep.class, Query.select().where("OPERATION_KEY = ?", operation.getOperationKey()).limit(1)).length == 0) {
                        store.charge(db, -operation.getBytes() - operation.getReservedBytes());
                        db.delete(operation);
                        if (++deleted == batchSize) return deleted;
                    }
                }
                if (db.find(JournalOperation.class, Query.select().where("CHANGE_SET_KEY = ?", set.getChangeSetKey()).limit(1)).length == 0) {
                    String session = set.getSessionKey();
                    store.charge(db, -set.getBytes());
                    db.delete(set);
                    if (++deleted == batchSize) return deleted;
                    if (db.find(JournalChangeSet.class, Query.select().where("SESSION_KEY = ?", session).limit(1)).length == 0) {
                        JournalSession row = JournalStore.one(db, JournalSession.class, "SESSION_KEY", session);
                        store.charge(db, -row.getBytes());
                        db.delete(row);
                        if (++deleted == batchSize) return deleted;
                    }
                }
            }
            // Empty abandoned sessions have no changes and no unresolved records.
            for (JournalSession session : db.find(JournalSession.class,
                    Query.select().where("CREATED_AT < ?", now - JournalStore.UNDO_WINDOW_MILLIS).limit(batchSize - deleted))) {
                if (db.find(JournalChangeSet.class, Query.select().where("SESSION_KEY = ?", session.getSessionKey()).limit(1)).length == 0) {
                    store.charge(db, -session.getBytes());
                    db.delete(session);
                    if (++deleted == batchSize) break;
                }
            }
            return deleted;
        });
    }

    private void recoverAbandoned(int batchSize) {
        List<Object[]> candidates = store.locked(db -> {
            List<Object[]> ids = new ArrayList<>();
            for (JournalOperation operation : db.find(JournalOperation.class,
                    Query.select().where("HEARTBEAT_AT < ? AND (STATE = ? OR STATE = ?)",
                            store.now() - ABANDONED_MILLIS, "RUNNING", "QUEUED").limit(batchSize))) {
                ids.add(new Object[] {operation.getOperationKey(), operation.getExecutionSlot()});
            }
            return ids;
        });
        for (Object[] candidate : candidates) {
            String id = (String) candidate[0];
            try {
                // A slow but live executor holds its recorded slot for its entire call. Lease age alone never
                // authorizes a transition that could race a Jira write on another node. A slot that another operation
                // now holds reads as busy: the candidate is kept and retried, never wrongly blocked. Without a slot the
                // worker has not started; the state check under the journal lock decides alone.
                Supplier<Object> recover = () -> store.locked(db -> {
                    JournalOperation operation = JournalStore.one(db, JournalOperation.class, "OPERATION_KEY", id);
                    if (operation != null && active(operation) && operation.getHeartbeatAt() < store.now() - ABANDONED_MILLIS) {
                        operation.setState("BLOCKED");
                        operation.setError("The worker stopped without completing its operation; prepared intents require reconciliation.");
                        operation.setFinishedAt(store.now());
                        releaseReservation(db, operation);
                        releaseProgressReservation(db, operation);
                        operation.save();
                    }
                    return null;
                });
                int slot = (Integer) candidate[1];
                if (slot < 0) recover.get();
                else locks.run(EXECUTION_LOCK_PREFIX + slot, "abandoned journal operation", recover);
            } catch (ru.rasulov.common.api.exception.BusyException liveExecutor) {
                // The cluster still owns this executor: retain RUNNING and try next hourly pass.
            }
        }
    }

    /** Bounded fallback for runner-owned groups whose close failed; never touches evidence or Jira. */
    private void reapStrandedRunnerGroups() {
        List<String[]> candidates = store.locked(db -> {
            List<String[]> result = new ArrayList<>();
            long now = store.now();
            for (JournalChangeSet set : db.find(JournalChangeSet.class, Query.select()
                    .where("RUNNER_OWNED = ? AND STATE = ? AND NEXT_CLEANUP_AT <= ?", true, "OPEN", now)
                    .order("NEXT_CLEANUP_AT ASC, \"ID\" ASC").limit(STRANDED_CANDIDATES))) {
                // Rotate busy or protected candidates too, so they cannot starve later ones.
                set.setNextCleanupAt(now + CLEANUP_RECHECK_MILLIS);
                set.save();
                JournalOperation[] rows = db.find(JournalOperation.class,
                        Query.select().where("CHANGE_SET_KEY = ?", set.getChangeSetKey()).limit(2));
                if (rows.length <= 1) result.add(new String[] {set.getChangeSetKey(),
                        rows.length == 0 ? "" : rows[0].getOperationKey(),
                        rows.length == 0 ? "-1" : Integer.toString(rows[0].getExecutionSlot())});
            }
            return result;
        });
        for (String[] candidate : candidates) {
            // Order: execution -> rollback -> journal. Never take the execution lock inside store.locked.
            Supplier<Integer> reap = () -> locks.run(ROLLBACK_LOCK, "rollback",
                    () -> store.locked(db -> reapEvidenceFreeRunnerGroup(db, candidate[0], candidate[1])));
            try {
                if (candidate[1].isEmpty() || Integer.parseInt(candidate[2]) < 0) reap.get();
                else locks.run(EXECUTION_LOCK_PREFIX + candidate[2], "runner retention", reap);
            } catch (ru.rasulov.common.api.exception.BusyException busy) {
                // No destructive work was done; the persisted candidate is retried in a later pass.
            }
        }
    }

    private int reapEvidenceFreeRunnerGroup(JournalDatabase db, String setId, String operationId) {
        JournalChangeSet set = JournalStore.one(db, JournalChangeSet.class, "CHANGE_SET_KEY", setId);
        if (set == null || !set.isRunnerOwned() || !"OPEN".equals(set.getState())) return 0;
        JournalOperation[] rows = db.find(JournalOperation.class,
                Query.select().where("CHANGE_SET_KEY = ?", setId).limit(2));
        if (rows.length > 1 || (rows.length == 0) != operationId.isEmpty()) return 0;
        long finishedAt = set.getCreatedAt();
        if (rows.length == 1) {
            JournalOperation operation = rows[0];
            if (!operationId.equals(operation.getOperationKey())) return 0;
            String state = operation.getState();
            if (!("COMPLETED".equals(state) || "FAILED".equals(state)
                    || "CANCELLED".equals(state) || "ROLLED_BACK".equals(state))) return 0;
            if (operation.getReservedBytes() != 0 || unresolved(db, operation)) return 0;
            if (db.find(JournalStep.class, Query.select().where("OPERATION_KEY = ?", operationId).limit(1)).length != 0
                    || db.find(JournalProgress.class, Query.select().where("OPERATION_KEY = ?", operationId).limit(1)).length != 0
                    || db.find(JournalProgressBatch.class, Query.select().where("OPERATION_KEY = ?", operationId).limit(1)).length != 0) return 0;
            finishedAt = operation.getFinishedAt();
        }
        if (finishedAt <= 0 || finishedAt > store.now() - STRANDED_RETENTION_MILLIS) return 0;
        int deleted = 0;
        if (rows.length == 1) {
            store.charge(db, -rows[0].getBytes());
            db.delete(rows[0]);
            deleted++;
        }
        String sessionId = set.getSessionKey();
        store.charge(db, -set.getBytes());
        db.delete(set);
        deleted++;
        if (db.find(JournalChangeSet.class,
                Query.select().where("SESSION_KEY = ?", sessionId).limit(1)).length == 0) {
            JournalSession session = JournalStore.one(db, JournalSession.class, "SESSION_KEY", sessionId);
            if (session != null) {
                store.charge(db, -session.getBytes());
                db.delete(session);
                deleted++;
            }
        }
        return deleted;
    }

    private ApplicationUser actor(String key) {
        ApplicationUser current = guard.requireAdministrator();
        if (key == null || !key.equals(current.getKey())) throw new AccessException("The authenticated user does not own this journal request.", false);
        ApplicationUser resolved = users.getUserByKey(key);
        if (resolved == null || !resolved.isActive()) throw new AccessException("The journal owner is no longer an active Jira user.", false);
        return resolved;
    }

    private JournalSession ownedSession(JournalDatabase db, String id, String actor) {
        JournalSession row = JournalStore.one(db, JournalSession.class, "SESSION_KEY", id);
        if (row == null || !actor.equals(row.getActorKey())) throw new AccessException("The session is unavailable to this user.", false);
        return row;
    }

    private JournalChangeSet ownedSet(JournalDatabase db, String id, String actor) {
        JournalChangeSet row = JournalStore.one(db, JournalChangeSet.class, "CHANGE_SET_KEY", id);
        if (row == null || !actor.equals(row.getActorKey())) throw new AccessException("The change set is unavailable to this user.", false);
        return row;
    }

    private JournalOperation ownedOperation(JournalDatabase db, String id, String actor) {
        JournalOperation row = JournalStore.one(db, JournalOperation.class, "OPERATION_KEY", id);
        requireOperationOwner(row, actor);
        return row;
    }

    private static void requireOperationOwner(JournalOperation row, String actor) {
        if (row == null || !actor.equals(row.getActorKey())) throw new AccessException("The operation is unavailable to this user.", false);
    }

    private JournalOperation running(JournalDatabase db, String id) {
        JournalOperation row = JournalStore.one(db, JournalOperation.class, "OPERATION_KEY", id);
        if (row == null || !"RUNNING".equals(row.getState())) throw invalid("There is no running journal operation.");
        return row;
    }

    private JournalStep ownStep(JournalDatabase db, long stepId, String operationId) {
        if (stepId <= 0 || stepId > Integer.MAX_VALUE) throw invalid("Invalid journal step.");
        JournalStep row = JournalStore.one(db, JournalStep.class, "ID", (int) stepId);
        if (row == null || !operationId.equals(row.getOperationKey())) throw invalid("The step belongs to a different operation.");
        return row;
    }

    private String requiredContext() {
        String id = context.get();
        if (id == null) throw new IllegalStateException("Mutating Jira work must be wrapped by ChangeJournal.execute/runOperation");
        return id;
    }

    private static void checkCancelled(JournalOperation operation) {
        if (operation.isCancelRequested()) throw new JournalCancelledException("Operation " + operation.getOperationKey() + " was cancelled at a safe write boundary.");
    }

    private Map<String, Object> operationMap(JournalDatabase db, JournalOperation operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operationId", operation.getOperationKey());
        result.put("sessionId", operation.getSessionKey());
        result.put("changeSetId", operation.getChangeSetKey());
        result.put("operation", operation.getName());
        result.put("status", operation.getState());
        result.put("createdAt", operation.getCreatedAt());
        result.put("startedAt", operation.getStartedAt());
        result.put("finishedAt", operation.getFinishedAt());
        result.put("cancelRequested", operation.isCancelRequested());
        result.put("rollbackRequested", operation.isRollbackRequested());
        result.put("result", operation.getResultJson() == null ? null : JournalJson.read(operation.getResultJson(), Object.class));
        if (tracksProgress(operation.getName())) {
            JournalProgress progress = JournalStore.one(db, JournalProgress.class, "OPERATION_KEY", operation.getOperationKey());
            if (progress != null) result.put("execution", "configure_workflow".equals(operation.getName())
                    ? WorkflowReceiptCodec.read(progress.getExecutionJson())
                    : JournalJson.read(progress.getExecutionJson(), Object.class));
        }
        result.put("error", operation.getError());
        JournalChangeSet set = JournalStore.one(db, JournalChangeSet.class, "CHANGE_SET_KEY", operation.getChangeSetKey());
        result.put("undoExpiresAt", set == null ? null : set.getExpiresAt());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (JournalStep row : db.find(JournalStep.class, Query.select().where("OPERATION_KEY = ?", operation.getOperationKey()).order("\"ID\" ASC"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getID());
            item.put("kind", row.getKind());
            item.put("resource", row.getResource());
            item.put("status", row.getState());
            item.put("error", row.getError());
            UndoHandler handler = handlers.get(row.getKind());
            if (handler != null && row.getAfterJson() != null) {
                try { item.putAll(handler.publicIdentity(JournalStore.snapshot(row))); }
                catch (RuntimeException unreadable) { LOG.warn("Step {} identity not readable: {}", row.getID(), unreadable.toString()); }
            }
            steps.add(item);
        }
        result.put("steps", steps);
        return result;
    }

    private static Map<String, Object> setMap(JournalChangeSet set) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changeSetId", set.getChangeSetKey());
        result.put("sessionId", set.getSessionKey());
        result.put("status", set.getState());
        result.put("createdAt", set.getCreatedAt());
        result.put("finishedAt", set.getFinishedAt());
        result.put("undoExpiresAt", set.getExpiresAt());
        return result;
    }

    private static Map<String, Object> previewMap(Selection selection, List<Map<String, Object>> conflicts, int undone) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selection", selection.request);
        result.put("operationIds", selection.operationIds);
        result.put("nonUndoableOperations", selection.nonUndoableOperations);
        if (!selection.nonUndoableOperations.isEmpty()) {
            result.put("undoWarning", "This scope contains operations that cannot be rolled back through Undo. "
                    + "Their changes remain; only recorded creations can be undone.");
        }
        result.put("status", conflicts.isEmpty() ? "READY" : "BLOCKED");
        List<Map<String, Object>> changes = new ArrayList<>();
        int index = 0;
        for (UndoStep step : selection.steps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", step.getId());
            row.put("kind", step.getKind());
            row.put("resource", step.getResource());
            // Apply completes a reverse-order prefix and stops at the first failed step.
            String state = step.getState();
            if (undone >= 0) {
                if (index < undone) state = "UNDONE";
                else if (index == undone && !conflicts.isEmpty()) state = "BLOCKED";
            }
            row.put("status", state);
            index++;
            changes.add(row);
        }
        result.put("steps", changes);
        result.put("conflicts", conflicts);
        return result;
    }

    private static Map<String, Object> conflictMap(UndoStep step, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stepId", step.getId());
        result.put("resource", step.getResource());
        result.put("reason", reason);
        return result;
    }

    private static boolean active(JournalOperation row) { return "RUNNING".equals(row.getState()) || "QUEUED".equals(row.getState()); }
    private static boolean pending(String state) { return !"UNDONE".equals(state) && !"DISCARDED".equals(state); }
    private static String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static String limit(String value) { return value == null || value.length() <= 512 ? value : value.substring(0, 512); }
    private static InvalidRequestException invalid(String message) { return new InvalidRequestException(message); }
    private static void text(String value, String name, int max) { if (value == null || value.isEmpty() || value.length() > max) throw invalid("Invalid " + name + "."); }



    private static boolean unresolved(JournalDatabase db, JournalOperation operation) {
        if (active(operation)) return true;
        String id = operation.getOperationKey();
        JournalProgress progress = JournalStore.one(db, JournalProgress.class, "OPERATION_KEY", id);
        if (progress != null && !progress.isSettled()) return true;
        if (db.find(JournalStep.class, Query.select().where("OPERATION_KEY = ? AND (STATE = ? OR STATE = ?)",
                id, "PREPARED", "BLOCKED").limit(1)).length != 0) return true;
        return !"COMPLETED".equals(operation.getState()) && db.find(JournalStep.class,
                Query.select().where("OPERATION_KEY = ? AND STATE = ?", id, "APPLIED").limit(1)).length != 0;
    }

    private static boolean unresolvedSet(JournalDatabase db, JournalChangeSet set) {
        for (JournalOperation operation : db.find(JournalOperation.class,
                Query.select().where("CHANGE_SET_KEY = ?", set.getChangeSetKey()))) {
            if (unresolved(db, operation)) return true;
        }
        return false;
    }

    private static final class HistoryState {
        private final List<UndoStep> mutable;
        private final Map<Long, Integer> positions = new HashMap<>();
        final List<UndoStep> steps;
        final UndoReceiptIndex receipts;

        HistoryState(Selection selection) {
            mutable = new ArrayList<>(selection.history);
            steps = Collections.unmodifiableList(mutable);
            receipts = selection.receipts;
            for (int index = 0; index < mutable.size(); index++) positions.put(mutable.get(index).getId(), index);
        }

        void undone(UndoStep step) {
            mutable.set(positions.get(step.getId()), new UndoStep(step.getId(), step.getKind(), step.getResource(),
                    step.getBeforeJson(), step.getAfterJson(), "UNDONE"));
        }
    }
    private static final class Selection {
        final String request;
        final List<String> operationIds;
        final List<UndoStep> steps;
        final List<UndoStep> history;
        final UndoReceiptIndex receipts;
        final List<Map<String, Object>> nonUndoableOperations;
        Selection(String request, List<String> operationIds, List<UndoStep> steps, List<UndoStep> history,
                  UndoReceiptIndex receipts, List<Map<String, Object>> nonUndoableOperations) {
            this.request = request;
            this.operationIds = operationIds;
            this.steps = steps;
            this.history = history;
            this.receipts = receipts;
            this.nonUndoableOperations = nonUndoableOperations;
        }
    }
}
