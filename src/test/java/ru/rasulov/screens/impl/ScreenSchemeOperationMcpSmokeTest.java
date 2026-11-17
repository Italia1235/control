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
import ru.rasulov.screens.api.IssueTypeScreenSchemeMappingEditor;
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
 * {@code set_screen_scheme_operations}. Only Jira's managers are doubles; {@link ScreenSchemeOperationWorld} is the truth.
 */
public class ScreenSchemeOperationMcpSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL = "set_screen_scheme_operations";

    @Test
    public void previewOneConfirmationOneOperationEditsTheSameSchemeInPlaceAndRecordsTheApply() throws Exception {
        ScreenSchemeOperationWorld world = new ScreenSchemeOperationWorld();
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
            ScreenSchemeOperationEditorImpl editor = new ScreenSchemeOperationEditorImpl(f.guard, f.locks,
                    new ScreenSchemeOperationPlanner(world.fields, world.screenManager, world.tops, world.projectManager,
                            new ScreenSchemeCreateRequirements(world.layouts, world.fieldManager), world.codec),
                    new ScreenSchemeOperationWriter(world.factory, world.fields),
                    new PreviewPlanExecutor(f.plans, world.codec));
            registry[0] = new ToolRegistry(Arrays.asList(
                    new ScreenTools(mock(ScreenSchemeService.class), mock(StandaloneScreenCreator.class),
                            mock(ProjectOperationScreenAssigner.class), mock(ScreenContentsReader.class),
                            mock(ScreenFieldRemover.class), mock(ScreenLayoutEditor.class),
                            mock(IssueTypeScreenSchemeMappingEditor.class), editor,
                            mock(ru.rasulov.screens.api.IssueTypeScreenSchemeAssigner.class)),
                    new ChangeTools(f.journal, f.guard, runner, f.plans, applies)));
            McpServer server = new McpServer(f.guard, catalog, registry[0]);
            int tops = world.topCatalog.size(), fields = world.fieldCatalog.size(), screens = world.screens.size();
            Map<Long, Object> usersBefore = new TreeMap<Long, Object>();
            for (Map.Entry<Long, java.util.Set<Long>> entry : world.usersByTop.entrySet()) usersBefore.put(entry.getKey(), new java.util.TreeSet<Long>(entry.getValue()));

            // 0. a validation failure is an MCP error result with the code and row, and no plan
            JsonNode bad = response(server, TOOL, preview("Shared SS", row("REPLACE", "CREATE", "No such screen")));
            assertTrue(bad.path("isError").asBoolean());
            JsonNode badBody = JSON.readTree(bad.path("content").get(0).path("text").asText());
            assertEquals("SCREEN_NOT_FOUND", badBody.path("errorCode").asText());
            assertEquals(0, badBody.path("errorRowIndex").asInt());
            assertTrue(world.writes.isEmpty());

            // 1. ONE preview: a changing REPLACE, a NO_CHANGE row and an ADD; the required-field warning is in the answer
            JsonNode preview = call(server, TOOL, preview("shared ss", row("REPLACE", "CREATE", "Screen 5"),
                    row("REPLACE", "EDIT", "3"), row("ADD", "VIEW", "Screen 9")));
            assertEquals(preview.toString(), "WOULD_CHANGE", preview.path("status").asText());
            assertTrue(preview.path("planId").isTextual());
            assertEquals(30, preview.path("screenScheme").path("id").asInt());
            assertEquals(5, preview.path("projects").path("linked").asInt());
            assertEquals(4, preview.path("projects").path("changing").asInt());
            assertEquals(3, preview.path("usage").path("linked").asInt());
            assertEquals("NO_CHANGE", preview.path("rows").get(1).path("change").asText());
            boolean required = false;
            for (JsonNode warning : preview.path("warnings")) {
                if ("REQUIRED_FIELDS_MISSING_ON_CREATE".equals(warning.path("code").asText())) {
                    required = true;
                    assertEquals(6, warning.path("requiredFields").path("pairs").asInt());
                    assertEquals("summary", warning.path("requiredFields").path("fields").get(1).path("id").asText());
                }
            }
            assertTrue(preview.toString(), required);
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
            assertEquals(30, result.path("screenScheme").path("id").asInt());
            assertEquals("APPLIED", result.path("rows").get(0).path("result").asText());
            assertEquals("NO_CHANGE", result.path("rows").get(1).path("result").asText());
            assertEquals("APPLIED", result.path("rows").get(2).path("result").asText());
            Map<String, Long> expected = new TreeMap<String, Long>();
            expected.put("DEFAULT", 1L); expected.put("CREATE", 5L); expected.put("EDIT", 3L); expected.put("VIEW", 9L);
            assertEquals(expected, world.table(30));
            assertEquals(Arrays.asList("update 30 CREATE", "create 30 VIEW"), world.writes);
            assertEquals("no new ITSS, Screen Scheme or Screen", tops, world.topCatalog.size());
            assertEquals(fields, world.fieldCatalog.size());
            assertEquals(screens, world.screens.size());
            verify(world.fields, never()).createFieldScreenScheme(any());
            verify(world.tops, never()).createIssueTypeScreenScheme(any());
            verify(world.tops, never()).addSchemeAssociation(any(com.atlassian.jira.project.Project.class), any());
            verify(world.tops, never()).updateIssueTypeScreenSchemeEntity(any());
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
            assertEquals(2, world.writes.size());

            // 5. the edit is forward-only: the journal refuses to undo it and keeps it completed
            assertTrue("Background workers did not finish", finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            submitted = 0;
            JsonNode rollbackPreview = JSON.valueToTree(f.journal.rollback("admin", operationId, null, null, true, null));
            assertEquals(rollbackPreview.toString(), 0, rollbackPreview.path("steps").size());
            assertEquals(1, rollbackPreview.path("nonUndoableOperations").size());
            assertEquals(TOOL, rollbackPreview.path("nonUndoableOperations").get(0).path("toolName").asText());
            JsonNode rollback = JSON.valueToTree(f.journal.rollback("admin", null, null, null, false, rollbackPreview.path("planId").asText()));
            assertEquals(rollback.toString(), "PARTIAL", rollback.path("status").asText());
            assertEquals(0, rollback.path("undoneSteps").asInt());
            assertEquals(expected, world.table(30));
            assertEquals("COMPLETED", operation(server, operationId).path("status").asText());

            // 6. a failing row: PARTIALLY_CHANGED from the read-back, then a fresh preview finishes the rest
            world.failUpdateNumber = 4; // updates so far: CREATE (1); now CREATE (2), EDIT (3), DEFAULT (4) fails
            JsonNode second = call(server, TOOL, preview("Shared SS", row("REPLACE", "CREATE", "Screen 8"), row("REPLACE", "EDIT", "Screen 8"),
                    row("REPLACE", "DEFAULT", "Screen 7")));
            ObjectNode apply2 = JSON.createObjectNode();
            apply2.put("dryRun", false); apply2.put("planId", second.path("planId").asText());
            ObjectNode start2 = JSON.createObjectNode();
            start2.put("toolName", TOOL); start2.put("arguments", apply2);
            String failedId = call(server, "start_operation", start2).path("operationId").asText();
            submitted++;
            JsonNode partial = operation(server, failedId).path("result");
            assertEquals(partial.toString(), "PARTIALLY_CHANGED", partial.path("status").asText());
            assertEquals("APPLIED", partial.path("rows").get(0).path("result").asText());
            assertEquals("APPLIED", partial.path("rows").get(1).path("result").asText());
            assertEquals("NOT_APPLIED", partial.path("rows").get(2).path("result").asText());
            assertEquals("not rolled back", Long.valueOf(8), world.table(30).get("CREATE"));
            assertEquals(Long.valueOf(1), world.table(30).get("DEFAULT"));
            JsonNode finish = call(server, TOOL, preview("Shared SS", row("REPLACE", "CREATE", "Screen 8"), row("REPLACE", "EDIT", "Screen 8"),
                    row("REPLACE", "DEFAULT", "Screen 7")));
            assertEquals("the unfinished row only", "NO_CHANGE", finish.path("rows").get(0).path("change").asText());
            assertEquals("CHANGE", finish.path("rows").get(2).path("change").asText());
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

    private static ObjectNode preview(String scheme, ObjectNode... rows) {
        ObjectNode request = JSON.createObjectNode();
        request.put("dryRun", true); request.put("screenScheme", scheme);
        ArrayNode list = request.putArray("rows");
        for (ObjectNode row : rows) list.add(row);
        return request;
    }

    private static ObjectNode row(String action, String operation, String screen) {
        ObjectNode row = JSON.createObjectNode();
        row.put("action", action); row.put("operation", operation);
        if (screen != null) row.put("screen", screen);
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
