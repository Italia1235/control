package ru.rasulov.screens.impl;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.applies.ApplyEvent;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.tools.ChangeTools;
import ru.rasulov.common.impl.tools.McpServer;
import ru.rasulov.common.impl.tools.OperationRunner;
import ru.rasulov.common.impl.tools.ToolCatalog;
import ru.rasulov.common.impl.tools.ToolRegistry;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.screens.api.ProjectOperationScreenAssigner;
import ru.rasulov.screens.api.ScreenContentsReader;
import ru.rasulov.screens.api.ScreenFieldRemover;
import ru.rasulov.screens.api.ScreenLayoutEditor;
import ru.rasulov.screens.api.ScreenSchemeService;
import ru.rasulov.screens.api.StandaloneScreenCreator;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * JSON-RPC -> real ScreenTools -> real editor/planner/writer -> OperationRunner -> ChangeJournal on AO/HSQL for
 * {@code set_issue_type_screen_scheme_mappings}. Only Jira's managers are doubles; {@link ItssMappingWorld} is the truth.
 */
public class IssueTypeScreenSchemeMappingMcpSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL = "set_issue_type_screen_scheme_mappings";

    @Test
    public void previewOneConfirmationOneOperationEditsTheSameItssInPlaceAndRecordsTheApply() throws Exception {
        ItssMappingWorld world = new ItssMappingWorld();
        Semaphore finished = new Semaphore(0);
        int submitted = 0;
        OperationRunner runner = null;
        try (UndoTestFixture f = UndoTestFixture.create("admin")) {
            ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
            ToolRegistry[] registry = new ToolRegistry[1];
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            doAnswer(call -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
            ApplyJournal applies = mock(ApplyJournal.class);
            runner = new OperationRunner(f.journal, f.guard, () -> registry[0], catalog,
                    mock(JiraAuthenticationContext.class), locals, f.plans, applies);
            IssueTypeScreenSchemeMappingEditorImpl editor = new IssueTypeScreenSchemeMappingEditorImpl(f.guard, f.locks,
                    new IssueTypeScreenSchemeMappingPlanner(world.types, world.fields, world.tops, world.projectManager, world.codec),
                    new IssueTypeScreenSchemeMappingWriter(world.factory, world.tops),
                    new PreviewPlanExecutor(f.plans, world.codec));
            registry[0] = new ToolRegistry(Arrays.asList(
                    new ScreenTools(mock(ScreenSchemeService.class), mock(StandaloneScreenCreator.class),
                            mock(ProjectOperationScreenAssigner.class), mock(ScreenContentsReader.class),
                            mock(ScreenFieldRemover.class), mock(ScreenLayoutEditor.class), editor,
                            mock(ru.rasulov.screens.api.ScreenSchemeOperationEditor.class),
                            mock(ru.rasulov.screens.api.IssueTypeScreenSchemeAssigner.class)),
                    new ChangeTools(f.journal, f.guard, runner, f.plans, applies)));
            McpServer server = new McpServer(f.guard, catalog, registry[0]);
            int tops = world.topCatalog.size(), fields = world.fieldCatalog.size();
            Map<Long, Object> usersBefore = new TreeMap<Long, Object>();
            for (Map.Entry<Long, java.util.Set<Long>> entry : world.usersByTop.entrySet()) usersBefore.put(entry.getKey(), new java.util.TreeSet<Long>(entry.getValue()));

            // 0. a validation failure is an MCP error result with the code and row, and no plan
            JsonNode bad = response(server, TOOL, preview("Shared ITSS", row("REPLACE", "Bug", "No such SS")));
            assertTrue(bad.path("isError").asBoolean());
            JsonNode badBody = JSON.readTree(bad.path("content").get(0).path("text").asText());
            assertEquals("SCREEN_SCHEME_NOT_FOUND", badBody.path("errorCode").asText());
            assertEquals(0, badBody.path("errorRowIndex").asInt());
            assertTrue(world.writes.isEmpty());

            // 1. ONE preview for REPLACE DEFAULT + REMOVE + ADD + a NO_CHANGE row
            JsonNode preview = call(server, TOOL, preview("shared itss", row("REPLACE", "DEFAULT", "Alt SS"),
                    row("REMOVE", "Vacation", null), row("ADD", "Story", "HR SS"), row("REPLACE", "Bug", "32")));
            assertEquals(preview.toString(), "WOULD_CHANGE", preview.path("status").asText());
            assertTrue(preview.path("planId").isTextual());
            assertEquals(3, preview.path("projects").path("linked").asInt());
            assertEquals(3, preview.path("projects").path("changing").asInt());
            assertEquals("NO_CHANGE", preview.path("rows").get(3).path("change").asText());
            assertTrue("a preview writes nothing", world.writes.isEmpty());

            ObjectNode apply = JSON.createObjectNode();
            apply.put("dryRun", false); apply.put("planId", preview.path("planId").asText());
            assertTrue("never applied directly", response(server, TOOL, apply).path("isError").asBoolean());
            assertTrue(world.writes.isEmpty());

            // 2. ONE managed operation; the terminal result is the read-back
            ObjectNode start = JSON.createObjectNode();
            start.put("toolName", TOOL); start.put("arguments", apply);
            String operationId = call(server, "start_operation", start).path("operationId").asText();
            submitted++;
            JsonNode terminal = operation(server, operationId);
            assertEquals(terminal.toString(), "COMPLETED", terminal.path("status").asText());
            JsonNode result = terminal.path("result");
            assertEquals(result.toString(), "CHANGED", result.path("status").asText());
            assertTrue(result.path("readBack").asBoolean());
            assertEquals(20, result.path("issueTypeScreenScheme").path("id").asInt());
            assertEquals("APPLIED", result.path("rows").get(0).path("result").asText());
            assertEquals("NO_CHANGE", result.path("rows").get(3).path("result").asText());
            Map<String, Long> expected = new TreeMap<String, Long>();
            expected.put("DEFAULT", 33L); expected.put("2", 32L); expected.put("3", 31L);
            assertEquals(expected, world.table(world.shared));
            assertEquals(Arrays.asList("update 20 DEFAULT", "remove 20 6", "create 20 3"), world.writes);
            assertEquals("no new ITSS, no new Screen Scheme", tops, world.topCatalog.size());
            assertEquals(fields, world.fieldCatalog.size());
            verify(world.tops, never()).createIssueTypeScreenScheme(any());
            verify(world.fields, never()).createFieldScreenScheme(any());
            verify(world.tops, never()).addSchemeAssociation(any(com.atlassian.jira.project.Project.class), any());
            Map<Long, Object> usersAfter = new TreeMap<Long, Object>();
            for (Map.Entry<Long, java.util.Set<Long>> entry : world.usersByTop.entrySet()) usersAfter.put(entry.getKey(), new java.util.TreeSet<Long>(entry.getValue()));
            assertEquals("project links untouched", usersBefore, usersAfter);

            // 3. the ApplyRecord is written by OperationRunner
            ArgumentCaptor<ApplyEvent> event = ArgumentCaptor.forClass(ApplyEvent.class);
            verify(applies, atLeastOnce()).record(event.capture());
            ApplyEvent recorded = event.getValue();
            assertEquals(TOOL, recorded.toolName); assertEquals(operationId, recorded.operationRef);
            assertEquals("COMPLETED", recorded.operationState); assertEquals("CHANGED", recorded.domainStatus);

            // 4. a reconnect reads the persisted identical result; a replay writes nothing
            McpServer reconnected = new McpServer(f.guard, catalog, registry[0]);
            assertEquals(result, operation(reconnected, operationId).path("result"));
            JsonNode replay = call(reconnected, "start_operation", start);
            assertTrue(replay.toString(), replay.path("alreadyStarted").asBoolean());
            assertEquals(3, world.writes.size());

            // 5. a failing row: a new preview, PARTIALLY_CHANGED from the read-back, then a fresh preview finishes the rest
            world.failUpdateNumber = 3;
            JsonNode second = call(server, TOOL, preview("Shared ITSS", row("REPLACE", "Bug", "Alt SS"), row("REPLACE", "Story", "Alt SS"),
                    row("REPLACE", "DEFAULT", "HR SS")));
            ObjectNode apply2 = JSON.createObjectNode();
            apply2.put("dryRun", false); apply2.put("planId", second.path("planId").asText());
            ObjectNode start2 = JSON.createObjectNode();
            start2.put("toolName", TOOL); start2.put("arguments", apply2);
            String failedId = call(server, "start_operation", start2).path("operationId").asText();
            submitted++;
            JsonNode partial = operation(server, failedId).path("result");
            assertEquals(partial.toString(), "PARTIALLY_CHANGED", partial.path("status").asText());
            assertEquals("APPLIED", partial.path("rows").get(2).path("result").asText());
            assertEquals("NOT_APPLIED", partial.path("rows").get(0).path("result").asText());
            assertEquals("NOT_ATTEMPTED", partial.path("rows").get(1).path("result").asText());
            assertEquals("not rolled back", Long.valueOf(31), world.table(world.shared).get("DEFAULT"));
            JsonNode finish = call(server, TOOL, preview("Shared ITSS", row("REPLACE", "Bug", "Alt SS"), row("REPLACE", "Story", "Alt SS"),
                    row("REPLACE", "DEFAULT", "HR SS")));
            assertEquals("the unfinished rows only", "NO_CHANGE", finish.path("rows").get(2).path("change").asText());
            assertEquals("CHANGE", finish.path("rows").get(0).path("change").asText());
        } finally {
            try {
                if (runner != null) runner.destroy();
                assertTrue("Background workers did not finish before closing AO/HSQL",
                        finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            } finally {
                world.writes.clear();
            }
        }
    }

    private static ObjectNode preview(String itss, ObjectNode... rows) {
        ObjectNode request = JSON.createObjectNode();
        request.put("dryRun", true); request.put("issueTypeScreenScheme", itss);
        ArrayNode list = request.putArray("rows");
        for (ObjectNode row : rows) list.add(row);
        return request;
    }

    private static ObjectNode row(String action, String type, String scheme) {
        ObjectNode row = JSON.createObjectNode();
        row.put("action", action); row.put("issueType", type);
        if (scheme != null) row.put("screenScheme", scheme);
        return row;
    }

    private static JsonNode operation(McpServer server, String operationId) throws Exception {
        ObjectNode args = JSON.createObjectNode();
        args.put("operationId", operationId); args.put("waitSeconds", 20);
        return call(server, "get_operation", args);
    }

    private static JsonNode call(McpServer server, String tool, JsonNode args) throws Exception {
        JsonNode result = response(server, tool, args);
        assertFalse(result.toString(), result.path("isError").asBoolean());
        return JSON.readTree(result.path("content").get(0).path("text").asText());
    }

    private static JsonNode response(McpServer server, String tool, JsonNode args) throws Exception {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0"); request.put("id", 1); request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool); params.put("arguments", args);
        JsonNode response = JSON.readTree(server.handle(request.toString()));
        assertFalse(response.toString(), response.has("error"));
        return response.path("result");
    }
}
