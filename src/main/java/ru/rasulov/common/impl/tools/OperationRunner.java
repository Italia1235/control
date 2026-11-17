package ru.rasulov.common.impl.tools;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.applies.ApplyEvent;
import ru.rasulov.common.impl.applies.ApplyFieldChanges;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.applies.ApplyTargets;
import ru.rasulov.common.impl.undo.ChangeJournal;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded off-request execution; disconnecting a client does not cancel a Jira write. */
@Named
public class OperationRunner implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(OperationRunner.class);
    private final ChangeJournal journal;
    private final AdministratorGuard guard;
    private final Provider<ToolRegistry> registry;
    private final ToolCatalog catalog;
    private final JiraAuthenticationContext authentication;
    private final JiraThreadLocalUtil threadLocals;
    private final ActionPlanStore plans;
    private final ApplyJournal applies;
    private final ThreadPoolExecutor executor;
    private final Semaphore slots = new Semaphore(34);
    private boolean stopping;

    @Inject
    public OperationRunner(ChangeJournal journal, AdministratorGuard guard, Provider<ToolRegistry> registry,
                           ToolCatalog catalog, @ComponentImport JiraAuthenticationContext authentication,
                           @ComponentImport JiraThreadLocalUtil threadLocals, ActionPlanStore plans, ApplyJournal applies) {
        this.journal = journal;
        this.guard = guard;
        this.registry = registry;
        this.catalog = catalog;
        this.authentication = authentication;
        this.threadLocals = threadLocals;
        this.plans = plans;
        this.applies = applies;
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(32), task -> {
            Thread thread = new Thread(task, "jira-control-operation");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized Map<String, Object> start(String toolName, JsonNode arguments, String sessionId,
                                                 String changeSetId) {
        ApplicationUser actor = guard.requireAdministrator();
        if (!catalog.isDomainMutation(toolName)) {
            throw new InvalidRequestException("start_operation takes an existing Jira mutation tool, not a read or journal tool.");
        }
        ToolCall tool = registry.get().find(toolName);
        if (tool == null) throw new InvalidRequestException("Unknown tool: " + toolName);
        if (arguments == null || !arguments.isObject()) {
            throw new InvalidRequestException("'arguments' must contain dryRun=false and planId from a confirmed preview.");
        }
        ToolArguments.allowOnly(arguments, "dryRun", "planId");
        JsonNode dryRun = arguments.get("dryRun");
        if (dryRun == null || !dryRun.isBoolean() || dryRun.booleanValue()
                || ToolArguments.text(arguments, "planId").trim().isEmpty()) {
            throw new InvalidRequestException("Apply takes dryRun=false and a nonempty planId only.");
        }
        boolean managedGroup = sessionId == null && changeSetId == null;
        if (!managedGroup && (sessionId == null || sessionId.trim().isEmpty()
                || changeSetId == null || changeSetId.trim().isEmpty())) {
            throw new InvalidRequestException("Supply both sessionId and changeSetId, or omit both.");
        }
        String planId = ToolArguments.text(arguments, "planId");
        return journal.withPlanStart(planId,
                () -> startConfirmed(toolName, arguments, sessionId, changeSetId, actor, tool, planId));
    }

    private Map<String, Object> startConfirmed(String toolName, JsonNode arguments, String sessionId,
                                              String changeSetId, ApplicationUser actor, ToolCall tool, String planId) {
        Map<String, Object> existing = journal.findOperationByPlan(planId, actor.getKey());
        if (existing != null) {
            if (!toolName.equals(existing.get("operation"))) return stale("CONSUMED");
            Map<String, Object> response = journal.getOperation((String) existing.get("operationId"), actor.getKey());
            response.put("alreadyStarted", true);
            return response;
        }
        String planRequest = plans.peekRequest(toolName, actor.getKey(), planId);
        if (planRequest == null) {
            return stale(plans.status(toolName, actor.getKey(), planId) == ActionPlanStore.Status.EXPIRED
                    ? "EXPIRED" : "NOT_FOUND");
        }
        boolean managedGroup = sessionId == null && changeSetId == null;
        if (stopping || !slots.tryAcquire()) {
            throw new BusyException("The operation queue is full or the plugin is stopping. Nothing was started.");
        }
        String id;
        try {
            if (managedGroup) {
                sessionId = journal.createSession(actor.getKey());
                changeSetId = journal.createRunnerChangeSet(sessionId, actor.getKey());
            }
            id = journal.openOperation(toolName, actor.getKey(), sessionId, changeSetId, planId);
        } catch (RuntimeException | Error failure) {
            slots.release();
            if (managedGroup && changeSetId != null) finishManagedGroup(changeSetId, actor.getKey());
            throw failure;
        }
        final String operationGroup = changeSetId;
        try {
            executor.execute(() -> {
                boolean prepared = false;
                try {
                    threadLocals.preCall();
                    prepared = true;
                    authentication.setLoggedInUser(actor);
                    journal.runOperation(id, () -> {
                        guard.requireAdministrator();
                        return tool.call(arguments);
                    });
                } catch (RuntimeException | Error failure) {
                    LOG.error("Journal operation {} failed; inspect its persisted result before retrying", id, failure);
                    try {
                        authentication.setLoggedInUser(actor);
                        journal.failOperation(id, actor.getKey(), "The operation could not start or complete; inspect its recorded changes.");
                    } catch (RuntimeException | Error recordingFailure) {
                        LOG.error("Cannot persist startup failure for operation {}", id, recordingFailure);
                    }
                } finally {
                    try {
                        recordApply(id, actor, toolName, planRequest);
                        if (managedGroup) finishManagedGroup(operationGroup, actor.getKey());
                        authentication.clearLoggedInUser();
                        if (prepared) threadLocals.postCall(LOG);
                    } finally {
                        slots.release();
                    }
                }
            });
        } catch (RuntimeException | Error failure) {
            slots.release();
            try {
                journal.failOperation(id, actor.getKey(), "The background executor could not start this operation; no Jira write was invoked.");
            } finally {
                try {
                    recordApply(id, actor, toolName, planRequest);
                } finally {
                    if (managedGroup) finishManagedGroup(operationGroup, actor.getKey());
                }
            }
            throw failure;
        }
        return journal.getOperation(id, actor.getKey());
    }

    private static Map<String, Object> stale(String reason) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("errorCode", "PREVIEW_STALE");
        response.put("staleReason", reason);
        response.put("message", "This preview cannot be started. Request a new preview and obtain new confirmation before applying.");
        return response;
    }

    /**
     * Journals the finished apply from the persisted operation. Never throws (except {@link VirtualMachineError}): a
     * journal failure must not change the operation, its result or the release of its slot.
     */
    private void recordApply(String operationId, ApplicationUser actor, String toolName, String planRequest) {
        try {
            Map<String, Object> operation = journal.getOperation(operationId, actor.getKey());
            Object result = operation.get("result");
            Map<?, ?> top = result instanceof Map ? (Map<?, ?>) result : null;
            ApplyTargets.Result targets = ApplyTargets.collect(result, planRequest);
            long finishedAt = operation.get("finishedAt") instanceof Number ? ((Number) operation.get("finishedAt")).longValue() : 0L;
            applies.record(new ApplyEvent("APPLY:" + operationId, ApplyEvent.APPLY,
                    finishedAt > 0 ? finishedAt : System.currentTimeMillis(), actor.getKey(), actor.getDisplayName(),
                    toolName, catalog.title(toolName), operationId, text(operation.get("status")),
                    domainStatus(top), top == null ? null : text(top.get("errorCode")),
                    text(operation.get("error")), Boolean.TRUE.equals(operation.get("rollbackRequested")),
                    targets.items, targets.more, targets.links,
                    "edit_screen_layout".equals(toolName)
                            ? top == null || top.get("fieldChanges") == null ? ApplyFieldChanges.unavailable()
                                    : ApplyFieldChanges.from(top.get("fieldChanges"))
                            : null));
        } catch (RuntimeException | Error failure) {
            if (failure instanceof VirtualMachineError) throw failure;
            LOG.error("Apply journal could not build the event for operation {} (tool {}, actor {}); no row is written",
                    operationId, toolName, actor.getKey(), failure);
        }
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }

    /**
     * The tool's own status; a tool that reports one status per item ({@code results[].status}, as create_custom_fields,
     * or {@code fields[].results[].status}, as add_field_to_screens) gets that status when all items agree, MIXED when
     * they differ.
     */
    static String domainStatus(Map<?, ?> top) {
        if (top == null) return null;
        String status = text(top.get("status"));
        if (status != null) return status;
        java.util.List<Object> items = new java.util.ArrayList<Object>();
        if (top.get("results") instanceof Iterable) {
            for (Object entry : (Iterable<?>) top.get("results")) items.add(entry);
        }
        if (top.get("fields") instanceof Iterable) {
            for (Object field : (Iterable<?>) top.get("fields")) {
                if (field instanceof Map && ((Map<?, ?>) field).get("results") instanceof Iterable) {
                    for (Object entry : (Iterable<?>) ((Map<?, ?>) field).get("results")) items.add(entry);
                }
            }
        }
        String common = null;
        boolean mixed = false, problem = false, ok = false, partial = false, unknown = false;
        for (Object entry : items) {
            String item = entry instanceof Map ? text(((Map<?, ?>) entry).get("status")) : null;
            if (item == null) continue;
            boolean bad = item.equals("FAILED") || item.equals("UNKNOWN") || item.startsWith("PARTIAL") || item.startsWith("NOT_");
            problem |= bad;
            ok |= !bad;
            partial |= item.startsWith("PARTIAL");
            unknown |= item.equals("UNKNOWN");
            if (common == null) common = item;
            else if (!common.equals(item)) mixed = true;
        }
        if (!mixed) return common;
        // Different but benign outcomes (created and already existing) are MIXED. A failed, unknown, partial or
        // not-attempted item next to successes is PARTIAL, never a neutral "mixed"; with no success at all it is UNKNOWN when
        // any item is, otherwise FAILED.
        if (!problem) return "MIXED";
        if (ok || partial) return "PARTIAL";
        return unknown ? "UNKNOWN" : "FAILED";
    }

    private void finishManagedGroup(String changeSetId, String actorKey) {
        try {
            journal.finishChangeSet(changeSetId, actorKey);
        } catch (RuntimeException | Error failure) {
            LOG.error("Cannot finish internal change set {}", changeSetId, failure);
        }
    }

    @Override
    public synchronized void destroy() {
        stopping = true;
        // Do not interrupt a thread inside a Jira write. Queued work drains with its persisted identity.
        executor.shutdown();
    }
}
