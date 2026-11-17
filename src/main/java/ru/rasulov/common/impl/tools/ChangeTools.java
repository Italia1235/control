package ru.rasulov.common.impl.tools;

import com.atlassian.jira.user.ApplicationUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.applies.ApplyEvent;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.applies.ApplyLink;
import ru.rasulov.common.impl.applies.ApplyTarget;
import ru.rasulov.common.impl.undo.ChangeJournal;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Session identity is explicit, durable and owned by the authenticated administrator. */
@Named
public class ChangeTools implements ToolProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ChangeTools.class);
    private static final String ROLLBACK_TITLE = "Откат изменений";
    private final ChangeJournal journal;
    private final AdministratorGuard guard;
    private final OperationRunner runner;
    private final ActionPlanStore plans;
    private final ApplyJournal applies;

    @Inject
    public ChangeTools(ChangeJournal journal, AdministratorGuard guard, OperationRunner runner, ActionPlanStore plans,
                       ApplyJournal applies) {
        this.journal = journal;
        this.guard = guard;
        this.runner = runner;
        this.plans = plans;
        this.applies = applies;
    }

    @Override
    public Map<String, ToolCall> tools() {
        Map<String, ToolCall> tools = new LinkedHashMap<String, ToolCall>();
        tools.put("create_change_session", args -> {
            ToolArguments.allowOnly(args);
            return Collections.singletonMap("sessionId", journal.createSession(actor()));
        });
        tools.put("list_change_sessions", args -> {
            ToolArguments.allowOnly(args);
            return journal.listSessions(actor());
        });
        tools.put("begin_change_set", args -> {
            ToolArguments.allowOnly(args, "sessionId");
            return Collections.singletonMap("changeSetId",
                    journal.createChangeSet(ToolArguments.text(args, "sessionId"), actor()));
        });
        tools.put("finish_change_set", args -> {
            ToolArguments.allowOnly(args, "changeSetId");
            return journal.finishChangeSet(ToolArguments.text(args, "changeSetId"), actor());
        });
        tools.put("list_session_changes", args -> {
            ToolArguments.allowOnly(args, "sessionId");
            return journal.listChanges(ToolArguments.text(args, "sessionId"), actor());
        });
        tools.put("start_operation", args -> {
            ToolArguments.allowOnly(args, "toolName", "arguments", "sessionId", "changeSetId");
            return runner.start(ToolArguments.text(args, "toolName"), args.get("arguments"),
                    ToolArguments.optionalText(args, "sessionId"), ToolArguments.optionalText(args, "changeSetId"));
        });
        tools.put("get_operation", args -> {
            ToolArguments.allowOnly(args, "operationId", "waitSeconds");
            if (args.has("waitSeconds") && args.get("waitSeconds").isNull()) {
                throw new InvalidRequestException("The argument 'waitSeconds' must be a whole number.");
            }
            Integer waitSeconds = ToolArguments.optionalInt(args, "waitSeconds");
            return journal.getOperation(ToolArguments.text(args, "operationId"), actor(),
                    waitSeconds == null ? 20 : waitSeconds);
        });
        tools.put("cancel_operation", args -> {
            ToolArguments.allowOnly(args, "operationId", "rollback");
            return journal.cancel(ToolArguments.text(args, "operationId"), actor(), booleanArgument(args, "rollback"));
        });
        tools.put("rollback_changes", args -> {
            ToolArguments.allowOnly(args, "operationId", "changeSetId", "sessionId", "dryRun", "planId");
            boolean dryRun = booleanArgument(args, "dryRun");
            String operation = ToolArguments.optionalText(args, "operationId");
            String changeSet = ToolArguments.optionalText(args, "changeSetId");
            String session = ToolArguments.optionalText(args, "sessionId");
            String planId = ToolArguments.optionalText(args, "planId");
            int scopes = (operation == null ? 0 : 1) + (changeSet == null ? 0 : 1) + (session == null ? 0 : 1);
            if ((dryRun && (scopes != 1 || planId != null)) || (!dryRun && (scopes != 0 || planId == null))) {
                throw new InvalidRequestException("Rollback preview takes exactly one scope; apply takes only dryRun=false and planId.");
            }
            return dryRun ? journal.rollback(actor(), operation, changeSet, session, true, planId) : applyRollback(planId);
        });
        return tools;
    }

    /**
     * The confirmed rollback ({@code dryRun=false}) journaled as one ROLLBACK row. The rollback journal is unchanged and
     * its result or exception passes through as is; a missing preview is not an apply, so it writes no row. A failure
     * that leaves the preview open (busy lock, changed state) may be retried with the same planId, so its row gets a key
     * of its own and does not take the key of a later successful attempt.
     */
    private Map<String, Object> applyRollback(String planId) {
        ApplicationUser user = guard.requireAdministrator();
        String request = peekPlan(user.getKey(), planId);
        if (request == null) return journal.rollback(user.getKey(), null, null, null, false, planId);
        Map<String, Object> response;
        try {
            response = journal.rollback(user.getKey(), null, null, null, false, planId);
        } catch (RuntimeException | Error failure) {
            String eventKey = "ROLLBACK:" + planId;
            if (peekPlan(user.getKey(), planId) != null) eventKey += ":" + UUID.randomUUID();
            recordRollback(user, eventKey, request, "FAILED", null, failure.getMessage());
            throw failure;
        }
        Object status = response == null ? null : response.get("status");
        recordRollback(user, "ROLLBACK:" + planId, request, "COMPLETED", status instanceof String ? (String) status : null, null);
        return response;
    }

    private String peekPlan(String actorKey, String planId) {
        try {
            return plans.peekRequest("rollback", actorKey, planId);
        } catch (RuntimeException | Error failure) {
            if (failure instanceof VirtualMachineError) throw failure;
            LOG.error("Apply journal could not read the rollback preview", failure);
            return null;
        }
    }

    /** Never throws (except {@link VirtualMachineError}): the journal must not change what the rollback returns. */
    private void recordRollback(ApplicationUser user, String eventKey, String request, String state, String domainStatus,
                                String errorMessage) {
        try {
            String type = null;
            String scope = null;
            if (request.startsWith("operation:")) { type = "OPERATION"; scope = request.substring("operation:".length()); }
            else if (request.startsWith("changeSet:")) { type = "CHANGE_SET"; scope = request.substring("changeSet:".length()); }
            else if (request.startsWith("session:")) { type = "SESSION"; scope = request.substring("session:".length()); }
            List<ApplyTarget> targets = type == null || scope.trim().isEmpty() ? Collections.<ApplyTarget>emptyList()
                    : Collections.singletonList(new ApplyTarget(type, scope, null));
            applies.record(new ApplyEvent(eventKey, ApplyEvent.ROLLBACK, System.currentTimeMillis(),
                    user.getKey(), user.getDisplayName(), "rollback_changes", ROLLBACK_TITLE,
                    type == null || scope.trim().isEmpty() ? null : scope, state, domainStatus, null, errorMessage, false,
                    targets, 0, Collections.<ApplyLink>emptyList()));
        } catch (RuntimeException | Error failure) {
            if (failure instanceof VirtualMachineError) throw failure;
            LOG.error("Apply journal could not build the rollback event {} (actor {}); no row is written",
                    eventKey, user.getKey(), failure);
        }
    }

    private String actor() {
        return guard.requireAdministrator().getKey();
    }

    static boolean booleanArgument(JsonNode args, String name) {
        JsonNode value = args.get(name);
        if (value == null || !value.isBoolean()) {
            throw new InvalidRequestException("'" + name + "' must be explicitly true or false.");
        }
        return value.booleanValue();
    }
}
