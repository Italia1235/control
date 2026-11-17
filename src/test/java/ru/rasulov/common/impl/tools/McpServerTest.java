package ru.rasulov.common.impl.tools;

import com.atlassian.plugin.Plugin;
import com.atlassian.plugin.PluginAccessor;
import com.atlassian.plugin.PluginInformation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The MCP protocol a tool server needs, against a registry with tools made for the test. */
public class McpServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final Map<String, JsonNode> received = new LinkedHashMap<String, JsonNode>();
    private McpServer server;

    @Before
    public void setUp() {
        Plugin plugin = mock(Plugin.class);
        PluginInformation information = mock(PluginInformation.class);
        when(information.getVersion()).thenReturn("1.2.3");
        when(plugin.getPluginInformation()).thenReturn(information);
        PluginAccessor accessor = mock(PluginAccessor.class);
        when(accessor.getPlugin(ToolCatalog.PLUGIN_KEY)).thenReturn(plugin);

        ToolProvider provider = () -> {
            Map<String, ToolCall> tools = new LinkedHashMap<String, ToolCall>();
            tools.put("get_operation", arguments -> undoPayload());
            tools.put("assign_workflow", arguments -> undoPayload());
            tools.put("get_resolutions", arguments -> {
                received.put("get_resolutions", arguments);
                char[] text = new char[arguments.get("size").asInt()];
                java.util.Arrays.fill(text, 'x');
                return new String(text);
            });
            tools.put("list_projects", arguments -> {
                received.put("list_projects", arguments);
                return new ProjectRef("ABC", "Alpha");
            });
            tools.put("list_issue_types", arguments -> {
                throw new InvalidRequestException("nope, bad arguments");
            });
            tools.put("list_statuses", arguments -> {
                throw new BusyException("busy");
            });
            tools.put("configure_workflow", arguments -> {
                throw new IllegalStateException("Jira text that must not leak");
            });
            return tools;
        };
        server = new McpServer(guard, new ToolCatalog(accessor), new ToolRegistry(Collections.singletonList(provider)));
    }

    private JsonNode send(String json) throws Exception {
        String answer = server.handle(json);
        return answer == null ? null : MAPPER.readTree(answer);
    }

    private JsonNode call(String tool, String arguments) throws Exception {
        return send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\""
                + (arguments == null ? "" : ",\"arguments\":" + arguments) + "}}");
    }

    // --- handshake -----------------------------------------------------------------------------------------------

    @Test
    public void initializeAnswersWithTheClientsVersionTheToolsCapabilityTheServerVersionAndTheRules() throws Exception {
        JsonNode answer = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"kilo\"}}}");

        assertEquals("2.0", answer.get("jsonrpc").textValue());
        assertEquals(1, answer.get("id").intValue());
        JsonNode result = answer.get("result");
        assertEquals("2025-03-26", result.get("protocolVersion").textValue());
        assertNotNull(result.get("capabilities").get("tools"));
        assertEquals("1.2.3", result.get("serverInfo").get("version").textValue());
        assertFalse(result.get("instructions").textValue().trim().isEmpty());
    }

    @Test
    public void aProtocolVersionThisServerDoesNotKnowGetsTheNewestOne() throws Exception {
        JsonNode answer = send("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2099-01-01\"}}");

        assertEquals(McpServer.NEWEST_PROTOCOL, answer.get("result").get("protocolVersion").textValue());
        assertEquals("a string id is answered as it came", "a", answer.get("id").textValue());
    }

    @Test
    public void notificationsAreNotAnswered() throws Exception {
        assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertNull("an unknown notification is not an error either", send("{\"jsonrpc\":\"2.0\",\"method\":\"whatever\"}"));
        assertNull("an answer of the client is ignored", send("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}"));
    }

    @Test
    public void pingAnswersWithAnEmptyResult() throws Exception {
        JsonNode answer = send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");

        assertEquals(0, answer.get("result").size());
    }

    // --- tools ---------------------------------------------------------------------------------------------------

    @Test
    public void toolsListHasEveryToolWithItsSchemaAndAnnotations() throws Exception {
        JsonNode tools = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").get("result").get("tools");

        assertEquals("list_projects", tools.get(0).get("name").textValue());
        assertNotNull(tools.get(0).get("inputSchema"));
        assertTrue(tools.get(0).get("annotations").get("readOnlyHint").booleanValue());
    }

    @Test
    public void aToolResultIsTheJsonOfTheServiceAnswerAsTextAndNotAnError() throws Exception {
        JsonNode result = call("list_projects", "{\"query\":\"a\"}").get("result");

        assertFalse(result.get("isError").booleanValue());
        assertEquals("text", result.get("content").get(0).get("type").textValue());
        JsonNode payload = MAPPER.readTree(result.get("content").get(0).get("text").textValue());
        assertEquals("ABC", payload.get("key").textValue());
    }

    @Test
    public void aCallWithoutArgumentsGetsAnEmptyObject() throws Exception {
        call("list_projects", null);

        assertEquals(0, received.get("list_projects").size());
    }

    @Test
    public void aRefusedCallIsAnErrorResultWithTheMessageForTheModel() throws Exception {
        JsonNode bad = call("list_issue_types", "{}").get("result");
        JsonNode busy = call("list_statuses", "{}").get("result");

        assertTrue(bad.get("isError").booleanValue());
        assertEquals("nope, bad arguments", bad.get("content").get(0).get("text").textValue());
        assertTrue(busy.get("isError").booleanValue());
        assertEquals("busy", busy.get("content").get(0).get("text").textValue());
    }

    @Test
    public void aBugInAToolIsAnErrorResultWithoutTheTextOfTheException() throws Exception {
        JsonNode result = call("configure_workflow", "{}").get("result");

        assertTrue(result.get("isError").booleanValue());
        assertFalse(result.get("content").get(0).get("text").textValue().contains("must not leak"));
    }

    @Test
    public void anUnknownToolIsAnInvalidParamsError() throws Exception {
        JsonNode answer = call("drop_database", "{}");

        assertEquals(McpServer.INVALID_PARAMS, answer.get("error").get("code").intValue());
        assertEquals(7, answer.get("id").intValue());
        assertNull(answer.get("result"));
    }

    @Test
    public void frozenCommandsCannotBeCalledEvenWhenTheirInternalHandlersExist() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Map<String, ToolCall> internal = new LinkedHashMap<String, ToolCall>();
        for (String name : java.util.Arrays.asList("rollback_changes", "cancel_operation",
                "create_change_session", "list_change_sessions", "begin_change_set",
                "finish_change_set", "list_session_changes")) {
            internal.put(name, arguments -> {
                calls.incrementAndGet();
                return Collections.emptyMap();
            });
        }
        server = new McpServer(guard, new ToolCatalog(mock(PluginAccessor.class)),
                new ToolRegistry(Collections.singletonList(() -> internal)));
        for (String name : internal.keySet()) {
            JsonNode answer = call(name, "{}");
            assertEquals(name, McpServer.INVALID_PARAMS, answer.path("error").path("code").asInt());
            assertNull(answer.get("result"));
        }
        assertEquals(0, calls.get());
    }

    @Test
    public void argumentsThatAreNotAnObjectAreRefused() throws Exception {
        assertEquals(McpServer.INVALID_PARAMS, call("list_projects", "[1]").get("error").get("code").intValue());
        JsonNode noName = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{}}");
        assertEquals(McpServer.INVALID_PARAMS, noName.get("error").get("code").intValue());
    }

    // --- errors of the protocol ----------------------------------------------------------------------------------

    @Test
    public void anUnknownMethodWithAnIdIsMethodNotFound() throws Exception {
        JsonNode answer = send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}");

        assertEquals(McpServer.METHOD_NOT_FOUND, answer.get("error").get("code").intValue());
        assertEquals(5, answer.get("id").intValue());
    }

    @Test
    public void aBodyThatIsNotJsonIsAParseErrorWithANullId() throws Exception {
        JsonNode answer = send("{not json");

        assertEquals(McpServer.PARSE_ERROR, answer.get("error").get("code").intValue());
        assertTrue(answer.get("id").isNull());
        assertEquals(McpServer.PARSE_ERROR, send("").get("error").get("code").intValue());
    }

    @Test
    public void aMessageThatIsNotAnObjectOrHasNoMethodIsAnInvalidRequest() throws Exception {
        assertEquals(McpServer.INVALID_REQUEST, send("42").get("error").get("code").intValue());
        assertEquals(McpServer.INVALID_REQUEST, send("{\"id\":1}").get("error").get("code").intValue());
    }

    @Test
    public void aBatchIsAnsweredMessageByMessageWithoutTheNotifications() throws Exception {
        JsonNode answers = send("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}]");

        assertEquals(2, answers.size());
        assertEquals(1, answers.get(0).get("id").intValue());
        assertEquals(2, answers.get(1).get("id").intValue());
        assertNull("a batch of notifications has no answer", send("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]"));
    }

    @Test
    public void nothingIsServedToAnyoneButAnAdministrator() throws Exception {
        when(guard.requireAdministrator()).thenThrow(new AccessException("no", false));

        try {
            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
            fail("expected AccessException");
        } catch (AccessException expected) {
            assertTrue(received.isEmpty());
        }
    }

    @Test
    public void confirmedPreviewCannotBypassManagedOperationThroughDirectMcpApply() throws Exception {
        for (String name : java.util.Arrays.asList("configure_workflow", "update_field_contexts", "create_field_context")) {
            java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
            ToolProvider domain = () -> Collections.singletonMap(name, args -> {
                writes.incrementAndGet();
                return Collections.singletonMap("status", "UPDATED");
            });
            server = new McpServer(guard, new ToolCatalog(mock(PluginAccessor.class)),
                    new ToolRegistry(Collections.singletonList(domain)));
            JsonNode result = call(name, "{\"dryRun\":false,\"planId\":\"confirmed-plan\"}").get("result");
            assertNotNull(name + " must be published and refuse direct apply", result);
            assertTrue(result.get("isError").booleanValue());
            assertEquals(0, writes.get());
        }
    }

    // --- bounded transport ---------------------------------------------------------------------------------------

    @Test
    public void anOversizeRequestIsRefusedWithoutRunningAnyTool() throws Exception {
        String padding = new String(new char[McpLimits.MAX_REQUEST_CHARS]).replace('\0', ' ');
        JsonNode answer = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":"
                + "\"list_projects\",\"arguments\":{\"query\":\"a\"}}}" + padding);

        assertEquals(McpServer.INVALID_REQUEST, answer.get("error").get("code").intValue());
        assertTrue(answer.get("error").get("message").textValue().contains("too large"));
        assertFalse(received.containsKey("list_projects"));
    }

    @Test
    public void excessiveJsonNestingIsRejectedBeforeRunningAnyTool() throws Exception {
        String nested = "[".repeat(1100) + "0" + "]".repeat(1100);
        JsonNode answer = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"list_projects\",\"arguments\":{\"query\":" + nested + "}}}");

        assertEquals(McpServer.PARSE_ERROR, answer.path("error").path("code").intValue());
        assertTrue(answer.path("id").isNull());
        assertFalse(received.containsKey("list_projects"));
    }

    @Test
    public void aBatchOverTheLimitIsRefusedWhole() throws Exception {
        JsonNode answer = send(pings(McpLimits.MAX_BATCH_MESSAGES + 1));

        assertEquals(McpServer.INVALID_REQUEST, answer.get("error").get("code").intValue());
        assertTrue(answer.get("error").get("message").textValue().contains("batch"));
    }

    @Test
    public void aBatchAtTheLimitIsAnswered() throws Exception {
        assertEquals(McpLimits.MAX_BATCH_MESSAGES, send(pings(McpLimits.MAX_BATCH_MESSAGES)).size());
    }

    private static String pings(int count) {
        StringBuilder batch = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            batch.append(i == 0 ? "" : ",").append("{\"jsonrpc\":\"2.0\",\"id\":").append(i)
                    .append(",\"method\":\"ping\"}");
        }
        return batch.append("]").toString();
    }

    @Test
    public void aReadResultOverTheLimitIsAnExplicitErrorNotATruncatedList() throws Exception {
        JsonNode result = call("get_resolutions", "{\"size\":" + (McpLimits.MAX_READ_RESULT_CHARS + 1) + "}")
                .get("result");

        assertTrue(result.get("isError").booleanValue());
        String text = result.get("content").get(0).get("text").textValue();
        assertTrue(text, text.contains("too large"));
        assertTrue("the oversize payload must not be echoed", text.length() < 2000);
    }

    @Test
    public void aReadResultWithinTheLimitIsReturnedWhole() throws Exception {
        JsonNode result = call("get_resolutions", "{\"size\":1000}").get("result");

        assertFalse(result.get("isError").booleanValue());
    }

    private static Map<String, Object> undoPayload() {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", "a");
        item.put("undoWarning", "This scope cannot be rolled back through Undo");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("results", Collections.singletonList(item));
        result.put("undoWarning", "Cannot be undone");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("operationId", "op");
        payload.put("status", "COMPLETED");
        payload.put("rollbackRequested", false);
        payload.put("undoExpiresAt", 123L);
        payload.put("result", result);
        return payload;
    }

    @Test
    public void undoDetailsOfAnyDepthNeverReachTheModelWhateverTheTool() throws Exception {
        for (String tool : new String[] {"get_operation", "assign_workflow"}) {
            String text = call(tool, "{\"operationId\":\"x\",\"dryRun\":true}").get("result").get("content").get(0)
                    .get("text").textValue();

            assertFalse(tool + ": " + text, text.toLowerCase().contains("undo"));
            assertFalse(tool + ": " + text, text.contains("rollback"));
            assertTrue(tool + ": " + text, text.contains("\"status\":\"COMPLETED\"") && text.contains("\"name\":\"a\""));
        }
    }
}
