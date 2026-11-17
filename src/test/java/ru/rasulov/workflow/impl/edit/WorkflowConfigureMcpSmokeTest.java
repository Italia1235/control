package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.tools.*;
import ru.rasulov.workflow.api.*;
import ru.rasulov.workflow.impl.WorkflowTools;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Executable JSON-RPC smoke through real tools, planner/writer, durable operation and AO/HSQL.
 * Jira managers are doubles: this does not prove Jira UI/ScriptRunner runtime or live installation. */
public class WorkflowConfigureMcpSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test public void onePreviewOperationAndReconnectExposeCompleteVerifiedExecutionWithoutRepeatingWrites() throws Exception {
        try (WorkflowConfigurationFixture world = new WorkflowConfigurationFixture()) {
            Semaphore finished = new Semaphore(0);
            ToolRegistry[] registry = new ToolRegistry[1];
            ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            doAnswer(c -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
            ApplyJournal applies = mock(ApplyJournal.class);
            OperationRunner runner = new OperationRunner(world.journalFixture.journal, world.journalFixture.guard,
                    () -> registry[0], catalog, mock(JiraAuthenticationContext.class), locals,
                    world.journalFixture.plans, applies);
            int submitted = 0;
            try {
                registry[0] = new ToolRegistry(List.of(new WorkflowTools(world.configurer, mock(WorkflowStatusReader.class),
                        mock(WorkflowAssigner.class), mock(WorkflowCloner.class), mock(WorkflowDraftPublisher.class),
                        mock(WorkflowReader.class), mock(WorkflowRenamer.class),
                        mock(StatusUpdater.class), mock(WorkflowCopier.class), mock(WorkflowValidatorReader.class),
                        mock(WorkflowConditionReader.class)),
                        new ChangeTools(world.journalFixture.journal, world.journalFixture.guard, runner,
                                world.journalFixture.plans, applies)));
                McpServer server = new McpServer(world.journalFixture.guard, catalog, registry[0]);
                JsonNode request = JSON.readTree("{\"dryRun\":true,\"create\":{\"name\":\"LOCAL-008-smoke\",\"statuses\":[{\"name\":\"Open\"},{\"name\":\"In Progress\"}],\"initialStatus\":\"Open\",\"transitions\":[{\"key\":\"start\",\"name\":\"Start\",\"from\":\"Open\",\"to\":\"In Progress\"}]},\"newScreens\":[{\"key\":\"form\",\"name\":\"LOCAL-008-screen-smoke\",\"fieldIds\":[\"description\"]}],\"transitionRules\":[{\"transitionKey\":\"start\",\"screen\":{\"action\":\"SET\",\"screenKey\":\"form\"},\"addValidators\":[{\"template\":\"comment_required\"},{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"description\"]}}],\"addConditions\":{\"operator\":\"AND\",\"rules\":[{\"template\":\"group\",\"parameters\":{\"group\":\"admins\"}},{\"template\":\"project_role\",\"parameters\":{\"roles\":[\"10000\"]}}]}}]}");
                JsonNode preview = call(server, "configure_workflow", request);
                assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
                assertTrue(world.nativeCalls.isEmpty());
                ObjectNode start = JSON.createObjectNode(); start.put("toolName", "configure_workflow");
                start.putObject("arguments").put("dryRun", false).put("planId", preview.path("planId").asText());
                JsonNode started = call(server, "start_operation", start); submitted++;
                String id = started.path("operationId").asText(); assertFalse(id.isEmpty());
                ObjectNode query = JSON.createObjectNode(); query.put("operationId", id); query.put("waitSeconds", 20);
                JsonNode terminal = call(server, "get_operation", query);
                assertEquals(terminal.toString(), "COMPLETED", terminal.path("status").asText());
                assertEquals(terminal.toString(), "CREATED", terminal.path("result").path("status").asText());
                JsonNode execution = terminal.path("execution"); assertEquals(execution.toString(), 5, execution.path("steps").size());
                for (JsonNode step : execution.path("steps")) {
                    assertEquals(step.toString(), "APPLIED", step.path("writeStatus").asText());
                    assertEquals(step.toString(), "MATCH", step.path("verification").asText());
                }
                String stored = DescriptorFacts.definition(world.main.get("LOCAL-008-smoke"));
                assertTrue(stored.contains("jira.fieldscreen.id")); assertTrue(stored.contains("fieldscreen"));
                assertTrue(stored.contains("UserInGroupCondition"));
                int writes = world.nativeCalls.size();
                McpServer reconnected = new McpServer(world.journalFixture.guard, catalog, registry[0]);
                assertEquals(terminal.path("result"), call(reconnected, "get_operation", query).path("result"));
                assertEquals(execution, call(reconnected, "get_operation", query).path("execution"));
                assertEquals(id, call(reconnected, "start_operation", start).path("operationId").asText());
                assertEquals(writes, world.nativeCalls.size());
            } finally {
                runner.destroy();
                assertTrue("operation workers must finish before closing AO", finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            }
        }
    }
    private static JsonNode call(McpServer server, String tool, JsonNode args) throws Exception {
        ObjectNode request = JSON.createObjectNode(); request.put("jsonrpc", "2.0"); request.put("id", 1); request.put("method", "tools/call");
        ObjectNode params = request.putObject("params"); params.put("name", tool); params.set("arguments", args);
        JsonNode rpc = JSON.readTree(server.handle(request.toString())); assertFalse(rpc.toString(), rpc.has("error"));
        JsonNode result = rpc.path("result"); assertFalse(result.toString(), result.path("isError").asBoolean());
        return JSON.readTree(result.path("content").get(0).path("text").asText());
    }
}
