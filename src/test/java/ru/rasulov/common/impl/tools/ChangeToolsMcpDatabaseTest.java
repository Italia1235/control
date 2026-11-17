package ru.rasulov.common.impl.tools;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.java.ao.DBParam;
import net.java.ao.Query;
import org.junit.Test;
import ru.rasulov.common.api.exception.ServiceException;
import ru.rasulov.common.impl.undo.UndoHandler;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.workflow.api.*;
import ru.rasulov.workflow.impl.WorkflowTools;
import ru.rasulov.common.impl.undo.JournalOperation;
import ru.rasulov.common.impl.undo.JournalStep;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Public operations use MCP; frozen journal commands use internal handlers with the same AO/HSQL scenarios. */
public class ChangeToolsMcpDatabaseTest {
    @Test
    public void lastRequestUndoesBothDependentOperationsButNotAnEarlierRequest() throws Exception {
        try (Scenario s = new Scenario()) {
            String session = s.session();
            String earlier = s.group(session);
            s.execute(session, earlier, "earlier");
            s.call("finish_change_set", s.args("changeSetId", earlier));
            String group = s.group(session);
            String first = s.execute(session, group, "parent");
            s.waitForChild = true;
            String child = s.start(session, group, "child");
            assertTrue(s.childWritten.await(10, TimeUnit.SECONDS));
            try {
                s.error("finish_change_set", s.args("changeSetId", group));
                ObjectNode poll = s.args("operationId", child);
                poll.put("waitSeconds", 0);
                assertEquals("RUNNING", s.call("get_operation", poll).path("status").asText());
            } finally {
                s.releaseChild.countDown();
            }
            s.awaitCompletion(child, "child");
            s.call("finish_change_set", s.args("changeSetId", group));
            JsonNode changes = s.call("list_session_changes", s.args("sessionId", session));
            assertEquals(3, changes.size());
            assertEquals(child, changes.get(0).path("operationId").asText());
            assertEquals(first, changes.get(1).path("operationId").asText());
            assertEquals(group, changes.get(0).path("changeSetId").asText());
            assertEquals(group, changes.get(1).path("changeSetId").asText());
            assertEquals(earlier, changes.get(2).path("changeSetId").asText());

            JsonNode preview = s.preview("changeSetId", group);
            assertEquals("READY", preview.path("status").asText());
            assertEquals(2, preview.path("steps").size());
            assertEquals(child, preview.path("operationIds").get(0).asText());
            assertEquals(first, preview.path("operationIds").get(1).asText());
            assertEquals(Integer.valueOf(1), s.values.get("parent"));
            assertEquals(Integer.valueOf(1), s.values.get("child"));
            ObjectNode invalidApply = s.args("planId", preview.path("planId").asText());
            invalidApply.put("dryRun", false);
            invalidApply.put("changeSetId", group);
            s.error("rollback_changes", invalidApply);
            JsonNode applied = s.apply(preview);
            assertEquals("ROLLED_BACK", applied.path("status").asText());
            assertEquals(Integer.valueOf(0), s.values.get("parent"));
            assertEquals(Integer.valueOf(0), s.values.get("child"));
            assertEquals(Integer.valueOf(1), s.values.get("earlier"));
            s.error("rollback_changes", s.applyArgs(preview));
            assertEquals("ROLLED_BACK", s.call("get_operation", s.args("operationId", first)).path("status").asText());
        }
    }

    @Test
    public void operationAndSessionScopesAreIsolatedAndOwnedByTheirAdministrator() throws Exception {
        try (Scenario s = new Scenario()) {
            String session = s.session();
            String group = s.group(session);
            String operation = s.execute(session, group, "parent");
            String otherGroup = s.group(session);
            String other = s.execute(session, otherGroup, "earlier");
            s.call("finish_change_set", s.args("changeSetId", group));
            s.call("finish_change_set", s.args("changeSetId", otherGroup));
            JsonNode operationPreview = s.preview("operationId", operation);
            JsonNode groupPreview = s.preview("changeSetId", group);
            JsonNode sessionPreview = s.preview("sessionId", session);
            s.db.currentActor.set(UndoTestFixture.user("other-admin"));
            try {
                s.error("get_operation", s.args("operationId", operation));
                s.error("list_session_changes", s.args("sessionId", session));
                s.error("begin_change_set", s.args("sessionId", session));
                s.error("finish_change_set", s.args("changeSetId", group));
                for (String[] scope : new String[][] {{"operationId", operation}, {"changeSetId", group}, {"sessionId", session}}) {
                    ObjectNode args = s.args(scope[0], scope[1]);
                    args.put("dryRun", true);
                    s.error("rollback_changes", args);
                }
                for (JsonNode preview : Arrays.asList(operationPreview, groupPreview, sessionPreview)) {
                    s.error("rollback_changes", s.applyArgs(preview));
                }
            } finally {
                s.db.currentActor.set(UndoTestFixture.user("admin"));
            }
            assertEquals(Integer.valueOf(1), s.values.get("parent"));
            assertEquals(Integer.valueOf(1), s.values.get("earlier"));
            assertEquals("ROLLED_BACK", s.apply(operationPreview).path("status").asText());
            assertEquals(Integer.valueOf(0), s.values.get("parent"));
            assertEquals(Integer.valueOf(1), s.values.get("earlier"));
            assertEquals("COMPLETED", s.call("get_operation", s.args("operationId", other)).path("status").asText());
            assertEquals("ROLLED_BACK", s.apply(s.preview("sessionId", session)).path("status").asText());
            assertEquals(Integer.valueOf(0), s.values.get("earlier"));
        }
    }

    @Test
    public void operationWaitRejectsInvalidArguments() throws Exception {
        try (Scenario s = new Scenario()) {
            String session = s.session();
            String id = s.execute(session, s.group(session), "parent");
            for (String value : Arrays.asList("-1", "21", "1.5", "\"3\"", "true", "null", "2147483648")) {
                ObjectNode args = s.args("operationId", id);
                args.put("waitSeconds", s.mapper.readTree(value));
                JsonNode response = s.response("get_operation", args);
                assertTrue(response.toString(), response.path("isError").asBoolean());
                assertTrue(response.toString(), response.path("content").get(0).path("text").asText().contains("waitSeconds"));
            }
        }
    }
    @Test
    public void removedWorkflowToolHasNoListCallOrStartRouteAndItsPreviewCannotBecomeConfigure() throws Exception {
        try (Scenario s = new Scenario()) {
            JsonNode listed = s.mapper.readTree(s.server.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
            boolean configureListed = false;
            for (JsonNode tool : listed.path("result").path("tools")) {
                assertFalse("edit_workflow".equals(tool.path("name").asText()));
                configureListed |= "configure_workflow".equals(tool.path("name").asText());
            }
            assertTrue(configureListed);
            assertNull(s.registry.find("edit_workflow"));
            JsonNode direct = s.mapper.readTree(s.server.handle("{\"jsonrpc\":\"2.0\",\"id\":2,"
                    + "\"method\":\"tools/call\",\"params\":{\"name\":\"edit_workflow\",\"arguments\":{\"dryRun\":true}}}"));
            assertEquals(direct.toString(), McpServer.INVALID_PARAMS, direct.path("error").path("code").asInt());
            assertEquals("Unknown tool: edit_workflow", direct.path("error").path("message").asText());

            String plan = s.db.plans.issue("edit_workflow", "admin", "{}", "legacy-fingerprint").getId();
            s.assertLegacyPlanCannotStart(plan, "NOT_FOUND");
            assertEquals("{}", s.db.plans.peekRequest("edit_workflow", "admin", plan));
            assertEquals(0, s.db.entityManager.count(JournalOperation.class));
            verifyNoInteractions(s.configurer);
        }
    }

    @Test
    public void historicalWorkflowTerminalResultRemainsReadableWithoutRenamingOrRestarting() throws Exception {
        try (Scenario s = new Scenario()) {
            for (String status : Arrays.asList("UPDATED_INACTIVE", "DRAFT_SAVED")) {
                String plan = s.db.plans.issue("edit_workflow", "admin", "{}", "legacy-fingerprint").getId();
                String session = s.db.journal.createSession("admin");
                String set = s.db.journal.createRunnerChangeSet(session, "admin");
                String operation = s.db.journal.openOperation("edit_workflow", "admin", session, set, plan);
                Map<String, Object> legacyResult = new java.util.LinkedHashMap<>();
                legacyResult.put("status", status);
                legacyResult.put("workflow", "Historical flow");
                legacyResult.put("revision", "saved-revision");
                legacyResult.put("warnings", Collections.singletonList("Historical warning"));
                legacyResult.put("objects", Collections.singletonMap("transition", Collections.singletonMap("actionId", 41)));
                s.db.journal.runOperation(operation, () -> legacyResult);
                Map<String, Object> stored = s.db.reopen().getOperation(operation, "admin");

                JsonNode result = s.call("get_operation", s.args("operationId", operation));
                assertEquals(operation, result.path("operationId").asText());
                assertEquals("edit_workflow", result.path("operation").asText());
                assertEquals("COMPLETED", result.path("status").asText());
                assertEquals(s.mapper.valueToTree(legacyResult), result.path("result"));
                s.assertLegacyPlanCannotStart(plan, "CONSUMED");
                assertEquals(result, s.call("get_operation", s.args("operationId", operation)));
                assertEquals(stored, s.db.reopen().getOperation(operation, "admin"));
            }
            assertEquals(2, s.db.entityManager.count(JournalOperation.class));
            verifyNoInteractions(s.configurer);
        }
    }

    @Test
    public void historicalWorkflowFailureRemainsTerminalAndPreservesItsOriginalError() throws Exception {
        try (Scenario s = new Scenario()) {
            String plan = s.db.plans.issue("edit_workflow", "admin", "{}", "legacy-fingerprint").getId();
            String session = s.db.journal.createSession("admin");
            String set = s.db.journal.createRunnerChangeSet(session, "admin");
            String operation = s.db.journal.openOperation("edit_workflow", "admin", session, set, plan);
            IllegalStateException failure = new IllegalStateException("Historical native workflow save failed");
            try {
                s.db.journal.runOperation(operation, () -> { throw failure; });
                fail("The historical operation must fail");
            } catch (IllegalStateException expected) {
                assertSame(failure, expected);
            }
            Map<String, Object> stored = s.db.reopen().getOperation(operation, "admin");
            JsonNode result = s.call("get_operation", s.args("operationId", operation));
            assertEquals("edit_workflow", result.path("operation").asText());
            assertEquals("FAILED", result.path("status").asText());
            assertEquals(failure.getMessage(), result.path("error").asText());
            assertFalse(result.hasNonNull("result"));
            s.assertLegacyPlanCannotStart(plan, "CONSUMED");
            s.db.clock.addAndGet(TimeUnit.HOURS.toMillis(2));
            s.db.reopen().cleanupBatch(100);
            assertEquals(result, s.call("get_operation", s.args("operationId", operation)));
            assertEquals(stored, s.db.reopen().getOperation(operation, "admin"));
            assertEquals(1, s.db.entityManager.count(JournalOperation.class));
            verifyNoInteractions(s.configurer);
        }
    }

    @Test(timeout = 30000)
    public void acceptedLegacyOperationUsesExistingAbandonedRecoveryAndIsNeverReplayed() throws Exception {
        try (Scenario s = new Scenario()) {
            for (String state : Arrays.asList("QUEUED", "RUNNING")) {
                String plan = s.db.plans.issue("edit_workflow", "admin", "{}", "legacy-fingerprint").getId();
                String session = s.db.journal.createSession("admin");
                String set = s.db.journal.createRunnerChangeSet(session, "admin");
                String operation = s.db.journal.openOperation("edit_workflow", "admin", session, set, plan);
                if ("RUNNING".equals(state)) {
                    // Model a stopped pre-cutover worker using the same AO fixture as journal recovery tests.
                    JournalOperation row = s.db.entityManager.find(JournalOperation.class,
                            Query.select().where("OPERATION_KEY = ?", operation))[0];
                    row.setState("RUNNING");
                    row.setStartedAt(s.db.clock.get());
                    row.setExecutionSlot(0);
                    row.save();
                    s.db.entityManager.create(JournalStep.class, new DBParam("OPERATION_KEY", operation),
                            new DBParam("KIND", "mcp.value"), new DBParam("RESOURCE", "legacy-transition"),
                            new DBParam("BEFORE_JSON", "0"), new DBParam("AFTER_JSON", "1"),
                            new DBParam("STATE", "PREPARED"), new DBParam("CREATED_AT", s.db.clock.get()),
                            new DBParam("ALLOCATED_BYTES", 0L), new DBParam("MAX_AFTER_BYTES", 100));
                    s.values.put("legacy-transition", 1);
                }
                ObjectNode poll = s.args("operationId", operation);
                poll.put("waitSeconds", 0); // The fixture clock is frozen until the recovery boundary below.
                JsonNode accepted = s.call("get_operation", poll);
                assertEquals(state, accepted.path("status").asText());
                assertEquals("edit_workflow", accepted.path("operation").asText());
                Map<String, Object> acceptedStored = s.db.reopen().getOperation(operation, "admin");
                s.assertLegacyPlanCannotStart(plan, "CONSUMED");
                assertEquals(accepted, s.call("get_operation", poll));
                assertEquals(acceptedStored, s.db.reopen().getOperation(operation, "admin"));

                s.db.clock.addAndGet(TimeUnit.HOURS.toMillis(2));
                s.db.reopen().cleanupBatch(100);
                JsonNode recovered = s.call("get_operation", s.args("operationId", operation));
                assertEquals("BLOCKED", recovered.path("status").asText());
                assertEquals("edit_workflow", recovered.path("operation").asText());
                assertEquals(operation, recovered.path("operationId").asText());
                assertEquals(session, recovered.path("sessionId").asText());
                assertEquals(set, recovered.path("changeSetId").asText());
                assertEquals(accepted.path("createdAt"), recovered.path("createdAt"));
                assertEquals(accepted.path("startedAt"), recovered.path("startedAt"));
                assertTrue(recovered.path("finishedAt").asLong() > 0);
                assertTrue(recovered.path("result").isMissingNode() || recovered.path("result").isNull());
                assertTrue(recovered.toString(), recovered.path("error").asText().contains("require reconciliation"));
                Map<String, Object> recoveredStored = s.db.reopen().getOperation(operation, "admin");
                assertEquals(acceptedStored.get("steps"), recoveredStored.get("steps"));
                s.assertLegacyPlanCannotStart(plan, "CONSUMED");
                s.db.reopen().cleanupBatch(100);
                assertEquals(recovered, s.call("get_operation", s.args("operationId", operation)));
                assertEquals(recoveredStored, s.db.reopen().getOperation(operation, "admin"));
            }
            assertEquals(2, s.db.entityManager.count(JournalOperation.class));
            assertEquals(1, s.db.entityManager.count(JournalStep.class));
            assertEquals(Integer.valueOf(1), s.values.get("legacy-transition"));
            verifyNoInteractions(s.configurer);
        }
    }


    private static final class Scenario implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper();
        final Map<String, Integer> values = new ConcurrentHashMap<>();
        final Map<String, CountDownLatch> finished = new ConcurrentHashMap<>();
        final CountDownLatch childWritten = new CountDownLatch(1);
        final CountDownLatch releaseChild = new CountDownLatch(1);
        final UndoTestFixture db;
        final OperationRunner runner;
        final McpServer server;
        final Map<String, ToolCall> internalTools;
        final ToolCatalog catalog;
        final WorkflowConfigurer configurer = mock(WorkflowConfigurer.class);
        final ToolRegistry registry;
        volatile boolean waitForChild;

        Scenario() {
            UndoHandler handler = new UndoHandler() {
                public String kind() { return "mcp.value"; }
                public java.util.List<String> lockNames(UndoStep step) { return Collections.singletonList("mcp-values"); }
                public String conflict(UndoStep step) {
                    return values.getOrDefault(step.getResource(), 0) == Integer.parseInt(step.getAfterJson())
                            ? null : "Resource changed outside the operation";
                }
                public void undo(UndoStep step, ApplicationUser actor) {
                    if ("parent".equals(step.getResource()) && values.getOrDefault("child", 0) != 0) {
                        throw new IllegalStateException("Dependent child must be undone first");
                    }
                    values.put(step.getResource(), Integer.parseInt(step.getBeforeJson()));
                }
            };
            db = UndoTestFixture.create("admin", handler);
            ToolProvider domain = () -> Collections.singletonMap("create_screen", args -> {
                String resource = db.plans.peekRequest("create_screen", "admin", args.path("planId").asText());
                if ("child".equals(resource) && values.getOrDefault("parent", 0) != 1) {
                    throw new IllegalStateException("Parent must exist before the dependent write");
                }
                UndoStep step = db.journal.prepare("mcp.value", resource, "0", "1", 1);
                values.put(resource, 1);
                db.journal.applied(step, "1");
                if ("child".equals(resource) && waitForChild) {
                    childWritten.countDown();
                    try {
                        if (!releaseChild.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Child release timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return Collections.singletonMap("resource", resource);
            });
            ToolRegistry[] registry = new ToolRegistry[1];
            catalog = new ToolCatalog(mock(PluginAccessor.class));
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            ThreadLocal<String> active = new ThreadLocal<>();
            ToolProvider trackedDomain = () -> Collections.singletonMap("create_screen", args -> {
                active.set(db.plans.peekRequest("create_screen", "admin", args.path("planId").asText()));
                return domain.tools().get("create_screen").call(args);
            });
            doAnswer(call -> {
                String resource = active.get();
                if (resource != null) finished.get(resource).countDown();
                active.remove();
                return null;
            }).when(locals).postCall(any(org.slf4j.Logger.class));
            runner = new OperationRunner(db.journal, db.guard, () -> registry[0], catalog,
                    mock(JiraAuthenticationContext.class), locals, db.plans, mock(ru.rasulov.common.impl.applies.ApplyJournal.class));
            ChangeTools changes = new ChangeTools(db.journal, db.guard, runner, db.plans,
                    mock(ru.rasulov.common.impl.applies.ApplyJournal.class));
            internalTools = changes.tools();
            WorkflowTools workflows = new WorkflowTools(configurer, mock(WorkflowStatusReader.class),
                    mock(WorkflowAssigner.class), mock(WorkflowCloner.class), mock(WorkflowDraftPublisher.class),
                    mock(WorkflowReader.class), mock(WorkflowRenamer.class), mock(StatusUpdater.class),
                    mock(WorkflowCopier.class), mock(WorkflowValidatorReader.class), mock(WorkflowConditionReader.class));
            registry[0] = new ToolRegistry(Arrays.asList(trackedDomain, workflows, changes));
            this.registry = registry[0];
            server = new McpServer(db.guard, catalog, registry[0]);
        }

        ObjectNode args(String key, String value) {
            ObjectNode args = mapper.createObjectNode();
            args.put(key, value);
            return args;
        }
        JsonNode response(String tool, ObjectNode args) throws Exception {
            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", 1);
            request.put("method", "tools/call");
            ObjectNode params = request.putObject("params");
            params.put("name", tool);
            params.put("arguments", args);
            JsonNode response;
            if (catalog.isPublished(tool)) {
                response = mapper.readTree(server.handle(request.toString()));
            } else {
                // Keep the existing result/error assertions while invoking the real internal handler.
                ObjectNode envelope = mapper.createObjectNode();
                ObjectNode result = envelope.putObject("result");
                try {
                    Object value = internalTools.get(tool).call(args);
                    result.put("isError", false);
                    result.putArray("content").addObject().put("text", mapper.writeValueAsString(value));
                } catch (ServiceException failure) {
                    result.put("isError", true);
                    result.putArray("content").addObject().put("text", failure.getMessage());
                }
                response = envelope;
            }
            assertFalse(response.toString(), response.has("error"));
            return response.path("result");
        }
        JsonNode call(String tool, ObjectNode args) throws Exception {
            JsonNode result = response(tool, args);
            assertFalse(result.toString(), result.path("isError").asBoolean());
            return mapper.readTree(result.path("content").get(0).path("text").asText());
        }
        void error(String tool, ObjectNode args) throws Exception { assertTrue(response(tool, args).path("isError").asBoolean()); }
        void assertLegacyPlanCannotStart(String plan, String staleReason) throws Exception {
            ObjectNode start = args("toolName", "edit_workflow");
            start.putObject("arguments").put("dryRun", false).put("planId", plan);
            error("start_operation", start);
            start.put("toolName", "configure_workflow");
            JsonNode rejected = call("start_operation", start);
            assertEquals("PREVIEW_STALE", rejected.path("errorCode").asText());
            assertEquals(staleReason, rejected.path("staleReason").asText());
            assertFalse(rejected.toString(), rejected.has("operationId"));
            assertFalse(rejected.toString(), rejected.path("alreadyStarted").asBoolean());
        }
        String session() throws Exception { return call("create_change_session", mapper.createObjectNode()).path("sessionId").asText(); }
        String group(String session) throws Exception { return call("begin_change_set", args("sessionId", session)).path("changeSetId").asText(); }
        String start(String session, String group, String resource) throws Exception {
            finished.put(resource, new CountDownLatch(1));
            ObjectNode args = args("toolName", "create_screen");
            args.put("sessionId", session);
            args.put("changeSetId", group);
            ObjectNode apply = args.putObject("arguments");
            apply.put("dryRun", false);
            apply.put("planId", db.plans.issue("create_screen", "admin", resource, "fp").getId());
            return call("start_operation", args).path("operationId").asText();
        }
        void awaitCompletion(String operation, String resource) throws Exception {
            assertTrue("Worker did not finish", finished.get(resource).await(10, TimeUnit.SECONDS));
            JsonNode result = call("get_operation", args("operationId", operation));
            assertEquals("COMPLETED", result.path("status").asText());
            assertEquals(resource, result.path("result").path("resource").asText());
        }
        String execute(String session, String group, String resource) throws Exception {
            String id = start(session, group, resource);
            awaitCompletion(id, resource);
            return id;
        }
        JsonNode preview(String scope, String id) throws Exception {
            ObjectNode args = args(scope, id);
            args.put("dryRun", true);
            return call("rollback_changes", args);
        }
        ObjectNode applyArgs(JsonNode preview) {
            ObjectNode args = args("planId", preview.path("planId").asText());
            args.put("dryRun", false);
            return args;
        }
        JsonNode apply(JsonNode preview) throws Exception { return call("rollback_changes", applyArgs(preview)); }
        public void close() throws Exception {
            releaseChild.countDown();
            runner.destroy();
            for (CountDownLatch worker : finished.values()) assertTrue(worker.await(10, TimeUnit.SECONDS));
            db.close();
        }
    }
}
