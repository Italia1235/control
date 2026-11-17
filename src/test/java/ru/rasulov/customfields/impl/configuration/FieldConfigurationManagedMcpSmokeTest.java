package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Test;
import ru.rasulov.common.impl.TestLinks;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.tools.ChangeTools;
import ru.rasulov.common.impl.tools.McpServer;
import ru.rasulov.common.impl.tools.OperationRunner;
import ru.rasulov.common.impl.tools.ToolCatalog;
import ru.rasulov.common.impl.tools.ToolRegistry;
import ru.rasulov.customfields.api.FieldConfigurationReader;
import ru.rasulov.customfields.api.FieldConfigurationSchemeAssigner;
import ru.rasulov.customfields.impl.FieldJournalTestSupport;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Real MCP -> OperationRunner -> ChangeJournal (AO/HSQL) -> FieldConfigurationCreatorImpl chain; only Jira's
 * FieldLayoutManager is a fake. Follows one id from create_field_configuration into create_field_configuration_scheme.
 */
public class FieldConfigurationManagedMcpSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @After
    public void close() { FieldJournalTestSupport.close(); }

    @Test
    public void createdConfigurationIdSurvivesRereadAndIsTheOneTheSchemeReferences() throws Exception {
        FieldLayoutFake jira = new FieldLayoutFake();
        jira.layout(10L, "Shared fields");
        jira.issueType("1", "Task");
        FieldConfigurationCreatorImpl creator = new FieldConfigurationCreatorImpl(jira.guard, jira.lock, jira.catalog,
                new SchemeRows(jira.issueTypes, jira.catalog), jira.manager, jira.copies,
                new ru.rasulov.common.impl.PreviewPlanExecutor(jira.plans, jira.codec), jira.codec,
                jira.journal, TestLinks.links());
        Semaphore finished = new Semaphore(0);
        int submitted = 0;
        OperationRunner runner = null;
        try {
            ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
            ToolRegistry[] registry = new ToolRegistry[1];
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            doAnswer(call -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
            ApplyJournal applies = mock(ApplyJournal.class);
            runner = new OperationRunner(jira.journal, jira.guard, () -> registry[0], catalog,
                    mock(JiraAuthenticationContext.class), locals, jira.plans, applies);
            FieldConfigurationTools tools = new FieldConfigurationTools(mock(FieldConfigurationReader.class), creator,
                    mock(FieldConfigurationSchemeAssigner.class));
            ChangeTools changes = new ChangeTools(jira.journal, jira.guard, runner, jira.plans, applies);
            registry[0] = new ToolRegistry(Arrays.asList(tools, changes));
            McpServer server = new McpServer(jira.guard, catalog, registry[0]);

            // 1. preview, then apply through a managed operation
            ObjectNode previewArgs = JSON.createObjectNode();
            previewArgs.put("dryRun", true);
            previewArgs.put("name", "SD: Оплаты");
            previewArgs.put("copyFrom", "10");
            JsonNode preview = call(server, "create_field_configuration", previewArgs);
            assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
            assertTrue("a preview has no report", absent(preview.path("report")));
            assertEquals(1, jira.layouts.size());

            ObjectNode apply = JSON.createObjectNode();
            apply.put("dryRun", false);
            apply.put("planId", preview.path("planId").asText());
            ObjectNode start = JSON.createObjectNode();
            start.put("toolName", "create_field_configuration");
            start.put("arguments", apply);
            JsonNode started = call(server, "start_operation", start);
            submitted++;
            String operationId = started.path("operationId").asText();
            assertFalse(started.toString(), operationId.isEmpty());

            // 2. the terminal result carries the id the fake Jira really assigned
            JsonNode terminal = operation(server, operationId);
            assertEquals(terminal.toString(), "COMPLETED", terminal.path("status").asText());
            JsonNode created = terminal.path("result");
            assertEquals(terminal.toString(), "CREATED", created.path("status").asText());
            assertEquals(2, jira.layouts.size());
            long configurationId = created.path("fieldConfiguration").path("id").asLong();
            assertEquals("SD: Оплаты", jira.layouts.get(configurationId).getName());
            assertEquals(jira.layouts.get(configurationId).getName(), created.path("fieldConfiguration").path("name").asText());
            JsonNode createdEntry = created.path("report").path("created").get(0);
            assertEquals("FIELD_CONFIGURATION", createdEntry.path("kind").asText());
            assertEquals(String.valueOf(configurationId), createdEntry.path("id").asText());
            assertEquals("SD: Оплаты", createdEntry.path("name").asText());
            assertTrue("no admin route is confirmed, so no url", absent(createdEntry.path("url")));
            assertEquals(1, created.path("report").path("created").size());
            assertEquals(0, created.path("report").path("reused").size());

            // 3. after a reconnect (a new server over the same journal) the known operation returns the same result
            McpServer reconnected = new McpServer(jira.guard, catalog, registry[0]);
            assertEquals(created, operation(reconnected, operationId).path("result"));

            // 4. a lost start-response recovers the original operation without writing again
            JsonNode replayStarted = call(reconnected, "start_operation", start);
            assertEquals(replayStarted.toString(), operationId, replayStarted.path("operationId").asText());
            assertTrue(replayStarted.toString(), replayStarted.path("alreadyStarted").asBoolean());
            JsonNode replay = operation(reconnected, replayStarted.path("operationId").asText());
            assertEquals(replay.toString(), "COMPLETED", replay.path("status").asText());
            assertEquals(created, replay.path("result"));
            assertEquals(terminal.path("steps"), replay.path("steps"));
            assertEquals(2, jira.layouts.size());

            // 5. the id from the terminal result is what the scheme preview accepts and what it stores
            ObjectNode schemeArgs = JSON.createObjectNode();
            schemeArgs.put("dryRun", true);
            schemeArgs.put("name", "SD: схема");
            ArrayNode mappings = schemeArgs.putArray("mappings");
            ObjectNode defaultRow = mappings.addObject();
            defaultRow.put("issueType", "DEFAULT");
            defaultRow.put("fieldConfiguration", "DEFAULT");
            ObjectNode taskRow = mappings.addObject();
            taskRow.put("issueType", "Task");
            taskRow.put("fieldConfiguration", String.valueOf(configurationId));
            JsonNode schemePreview = call(server, "create_field_configuration_scheme", schemeArgs);
            assertEquals(schemePreview.toString(), "WOULD_CREATE", schemePreview.path("status").asText());
            assertEquals(String.valueOf(configurationId),
                    schemePreview.path("mappings").get(1).path("fieldConfiguration").path("id").asText());

            ObjectNode wrong = (ObjectNode) JSON.readTree(schemeArgs.toString());
            ((ObjectNode) wrong.path("mappings").get(1)).put("fieldConfiguration", String.valueOf(configurationId + 1000));
            JsonNode wrongPreview = failure(server, "create_field_configuration_scheme", wrong);
            assertEquals(wrongPreview.toString(), "UNKNOWN_FIELD_CONFIGURATION", wrongPreview.path("errorCode").asText());
            assertTrue(absent(wrongPreview.path("planId")));

            ObjectNode schemeApply = JSON.createObjectNode();
            schemeApply.put("dryRun", false);
            schemeApply.put("planId", schemePreview.path("planId").asText());
            ObjectNode schemeStart = JSON.createObjectNode();
            schemeStart.put("toolName", "create_field_configuration_scheme");
            schemeStart.put("arguments", schemeApply);
            String schemeOperation = call(server, "start_operation", schemeStart).path("operationId").asText();
            submitted++;
            JsonNode schemeTerminal = operation(server, schemeOperation);
            assertEquals(schemeTerminal.toString(), "COMPLETED", schemeTerminal.path("status").asText());
            JsonNode scheme = schemeTerminal.path("result");
            assertEquals(scheme.toString(), "CREATED", scheme.path("status").asText());

            long schemeId = scheme.path("scheme").path("id").asLong();
            assertEquals("SD: схема", jira.schemes.get(schemeId).getName());
            assertEquals("the stored Task row points at the created configuration",
                    Long.valueOf(configurationId), jira.rows.get(schemeId).get("1"));
            assertEquals(null, jira.rows.get(schemeId).get(null));
            assertEquals(String.valueOf(configurationId),
                    scheme.path("mappings").get(1).path("fieldConfiguration").path("id").asText());
            JsonNode schemeCreated = scheme.path("report").path("created").get(0);
            assertEquals("FIELD_CONFIGURATION_SCHEME", schemeCreated.path("kind").asText());
            assertEquals(String.valueOf(schemeId), schemeCreated.path("id").asText());
            assertEquals("SD: схема", schemeCreated.path("name").asText());
            JsonNode reused = scheme.path("report").path("reused");
            assertEquals("only the created configuration is reused; DEFAULT has no id", 1, reused.size());
            assertEquals("FIELD_CONFIGURATION", reused.get(0).path("kind").asText());
            assertEquals(String.valueOf(configurationId), reused.get(0).path("id").asText());
            assertEquals("SD: Оплаты", reused.get(0).path("name").asText());
            assertEquals(scheme, operation(reconnected, schemeOperation).path("result"));
            assertEquals("each object is created exactly once", 2, jira.layouts.size());
            assertEquals(1, jira.schemes.size());
            assertTrue(jira.associations.isEmpty());
        } finally {
            try {
                if (runner != null) runner.destroy();
                assertTrue("Background workers did not finish before closing AO/HSQL",
                        finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            } finally {
                FieldJournalTestSupport.close();
            }
        }
    }

    private static JsonNode failure(McpServer server, String tool, JsonNode args) throws Exception {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.put("arguments", args);
        JsonNode result = JSON.readTree(server.handle(request.toString())).path("result");
        assertTrue(result.toString(), result.path("isError").asBoolean());
        return JSON.readTree(result.path("content").get(0).path("text").asText());
    }

    private static boolean absent(JsonNode node) { return node.isMissingNode() || node.isNull(); }

    private static JsonNode operation(McpServer server, String operationId) throws Exception {
        ObjectNode args = JSON.createObjectNode();
        args.put("operationId", operationId);
        args.put("waitSeconds", 20);
        return call(server, "get_operation", args);
    }

    private static JsonNode call(McpServer server, String tool, JsonNode args) throws Exception {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.put("arguments", args);
        JsonNode response = JSON.readTree(server.handle(request.toString()));
        assertFalse(response.toString(), response.has("error"));
        JsonNode result = response.path("result");
        assertFalse(result.toString(), result.path("isError").asBoolean());
        return JSON.readTree(result.path("content").get(0).path("text").asText());
    }
}
