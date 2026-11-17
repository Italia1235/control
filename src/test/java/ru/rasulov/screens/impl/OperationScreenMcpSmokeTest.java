package ru.rasulov.screens.impl;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.tools.ChangeTools;
import ru.rasulov.common.impl.tools.McpServer;
import ru.rasulov.common.impl.tools.OperationRunner;
import ru.rasulov.common.impl.tools.ToolCatalog;
import ru.rasulov.common.impl.tools.ToolRegistry;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.screens.api.ScreenFieldRemover;
import ru.rasulov.screens.api.ScreenLayoutEditor;
import ru.rasulov.screens.api.ScreenSchemeService;
import ru.rasulov.screens.api.StandaloneScreenCreator;
import ru.rasulov.screens.api.ScreenContentsReader;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * JSON-RPC -> real ScreenTools -> real planner/writer/assigner -> OperationRunner -> ChangeJournal on AO/HSQL. Only
 * Jira's scheme managers are doubles; the in-memory catalogs are the truth every assertion is compared with.
 */
public class OperationScreenMcpSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void fiveDifferentScreensAreOnePreviewOneOperationOneTopSchemeAndSurviveRereadReplayAndAFailure() throws Exception {
        OperationScreenWorld world = new OperationScreenWorld();
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
            ProjectOperationScreenAssignerImpl assigner = new ProjectOperationScreenAssignerImpl(f.guard, f.locks,
                    new OperationScreenPlanner(world.projects, world.types, world.screenManager, world.fields, world.tops, world.projectManager, world.codec),
                    new OperationScreenWriter(world.factory, world.fields, world.tops),
                    new ru.rasulov.common.impl.PreviewPlanExecutor(f.plans, world.codec), world.codec);
            registry[0] = new ToolRegistry(Arrays.asList(
                    new ScreenTools(mock(ScreenSchemeService.class), mock(StandaloneScreenCreator.class), assigner,
                            mock(ScreenContentsReader.class), mock(ScreenFieldRemover.class), mock(ScreenLayoutEditor.class),
                            mock(ru.rasulov.screens.api.IssueTypeScreenSchemeMappingEditor.class),
                            mock(ru.rasulov.screens.api.ScreenSchemeOperationEditor.class),
                            mock(ru.rasulov.screens.api.IssueTypeScreenSchemeAssigner.class)),
                    new ChangeTools(f.journal, f.guard, runner, f.plans, applies)));
            McpServer server = new McpServer(f.guard, catalog, registry[0]);

            // 0. the old single-patch shape is no longer a contract
            ObjectNode old = JSON.createObjectNode();
            old.put("dryRun", true); old.put("project", "ABC");
            old.putArray("issueTypes").add("Task");
            old.putObject("screens").put("CREATE", "3");
            assertTrue(response(server, "set_project_operation_screens", old).path("isError").asBoolean());
            assertEquals(0, world.writes.size());

            // 1. one preview for five types with five different Screens
            JsonNode preview = call(server, "set_project_operation_screens", five());
            assertEquals(preview.toString(), "WOULD_ASSIGN", preview.path("status").asText());
            assertEquals("PLANNED", preview.path("matrixSource").asText());
            assertTrue(preview.path("planId").isTextual());
            assertEquals(5, preview.path("issueTypes").size());
            assertEquals(5, preview.path("screenSchemes").size());
            assertEquals("a preview writes nothing", 0, world.writes.size());

            ObjectNode apply = JSON.createObjectNode();
            apply.put("dryRun", false); apply.put("planId", preview.path("planId").asText());
            assertTrue("never applied directly", response(server, "set_project_operation_screens", apply).path("isError").asBoolean());
            assertEquals(0, world.writes.size());

            // 2. one managed operation; its terminal result carries the new scheme id and the read-back matrix
            ObjectNode start = JSON.createObjectNode();
            start.put("toolName", "set_project_operation_screens"); start.put("arguments", apply);
            String operationId = call(server, "start_operation", start).path("operationId").asText();
            submitted++;
            JsonNode terminal = operation(server, operationId);
            assertEquals(terminal.toString(), "COMPLETED", terminal.path("status").asText());
            JsonNode result = terminal.path("result");
            assertEquals(result.toString(), "ASSIGNED", result.path("status").asText());
            assertEquals("READ_BACK", result.path("matrixSource").asText());
            assertEquals(world.associated.get().getId().longValue(), result.path("assignedSchemeId").asLong());
            assertEquals("exactly one new top scheme", 2, world.topCount());
            assertEquals("five child schemes", 7, world.fieldCount());
            assertEquals(1, countWrites(world, "associate"));
            long[][] expected = {{3, 4, 6}, {4, 5, 7}, {6, 7, 8}, {7, 8, 9}, {8, 9, 3}};
            String[] names = {"Task", "Bug", "Story", "Epic", "Other"};
            com.atlassian.jira.issue.issuetype.IssueType[] real = {world.task, world.bug, world.story, world.epic, world.other};
            for (int i = 0; i < 5; i++) {
                JsonNode type = byName(result, names[i]);
                for (int op = 0; op < 3; op++) {
                    String operation = OperationScreenPlan.OPERATIONS.get(op);
                    assertEquals(expected[i][op], world.effective(real[i], operation));
                    assertEquals("reported = what Jira holds", expected[i][op], type.path("operations").get(op).path("after").path("id").asLong());
                }
            }
            assertEquals(5, result.path("created").path("screenSchemes").size());
            assertEquals(result.path("assignedSchemeId").asLong(), result.path("created").path("issueTypeScreenScheme").path("id").asLong());

            // 3. a reconnect reads the persisted identical result; a replay creates nothing
            McpServer reconnected = new McpServer(f.guard, catalog, registry[0]);
            assertEquals(result, operation(reconnected, operationId).path("result"));
            JsonNode replayStart = call(reconnected, "start_operation", start);
            assertEquals(operationId, replayStart.path("operationId").asText());
            assertTrue(replayStart.toString(), replayStart.path("alreadyStarted").asBoolean());
            JsonNode replay = operation(reconnected, operationId);
            assertEquals("COMPLETED", replay.path("status").asText());
            assertEquals(result, replay.path("result"));
            assertEquals(terminal.path("steps"), replay.path("steps"));
            assertEquals(2, world.topCount()); assertEquals(7, world.fieldCount());
            JsonNode noOp = call(server, "set_project_operation_screens", five());
            assertEquals("ALREADY_ASSIGNED", noOp.path("status").asText());
            assertTrue(noOp.path("planId").isMissingNode() || noOp.path("planId").isNull());

            // 4. F08: the project's scheme is now private, so the second batch updates ITS mappings in place; a native failure
            //    on the second mapping write leaves one mapping changed and the result says so from a re-read of Jira
            long privateId = result.path("assignedSchemeId").asLong();
            ObjectNode second = JSON.createObjectNode();
            second.put("dryRun", true); second.put("project", "ABC");
            ArrayNode rows = second.putArray("mappings");
            rows.add(row("Task", "9", "9", "9")); rows.add(row("Bug", "8", "8", "8"));
            JsonNode preview2 = call(server, "set_project_operation_screens", second);
            assertEquals(preview2.toString(), "WOULD_ASSIGN", preview2.path("status").asText());
            assertEquals("UPDATE_PRIVATE", preview2.path("topScheme").path("mode").asText());
            assertEquals(privateId, preview2.path("topScheme").path("sourceId").asLong());
            assertEquals(0, preview2.path("topScheme").path("otherProjects").asInt());
            assertEquals(2, preview2.path("topScheme").path("mappings").size());
            assertTrue("no new top scheme is planned", preview2.path("newSchemeName").isNull() || preview2.path("newSchemeName").isMissingNode());
            world.failUpdateNumber = 2;
            ObjectNode apply2 = JSON.createObjectNode();
            apply2.put("dryRun", false); apply2.put("planId", preview2.path("planId").asText());
            ObjectNode start2 = JSON.createObjectNode();
            start2.put("toolName", "set_project_operation_screens"); start2.put("arguments", apply2);
            String failedId = call(server, "start_operation", start2).path("operationId").asText();
            submitted++;
            JsonNode failed = operation(server, failedId);
            JsonNode partial = failed.path("result");
            assertEquals(partial.toString(), "PARTIALLY_ASSIGNED", partial.path("status").asText());
            assertEquals("PARTIAL", partial.path("assignment").asText());
            assertEquals("READ_BACK", partial.path("matrixSource").asText());
            assertEquals(2, partial.path("created").path("screenSchemes").size());
            assertTrue(partial.path("created").path("issueTypeScreenScheme").isNull() || partial.path("created").path("issueTypeScreenScheme").isMissingNode());
            assertEquals("the project keeps the same private scheme", privateId, world.associated.get().getId().longValue());
            assertEquals("Bug was updated before the failure", 8, world.effective(world.bug, "CREATE"));
            assertEquals("Task is still as before", 3, world.effective(world.task, "CREATE"));
            assertEquals("read back, not planned", 3, byName(partial, "Task").path("operations").get(0).path("after").path("id").asLong());
            assertEquals(2, world.topCount());
            assertEquals(partial, operation(reconnected, failedId).path("result"));

            // 5. a new preview finishes the rest in place, still without a new top scheme
            JsonNode preview3 = call(server, "set_project_operation_screens", second);
            assertEquals(preview3.toString(), "UPDATE_PRIVATE", preview3.path("topScheme").path("mode").asText());
            ObjectNode apply3 = JSON.createObjectNode();
            apply3.put("dryRun", false); apply3.put("planId", preview3.path("planId").asText());
            ObjectNode start3 = JSON.createObjectNode();
            start3.put("toolName", "set_project_operation_screens"); start3.put("arguments", apply3);
            String finishId = call(server, "start_operation", start3).path("operationId").asText();
            submitted++;
            JsonNode finished3 = operation(server, finishId).path("result");
            assertEquals(finished3.toString(), "ASSIGNED", finished3.path("status").asText());
            assertEquals(privateId, finished3.path("assignedSchemeId").asLong());
            assertEquals(9, world.effective(world.task, "CREATE"));
            assertEquals("still two top schemes: the private one was never copied", 2, world.topCount());
            assertEquals("only the first batch switched the project", 1, countWrites(world, "associate"));
            assertEquals(finished3, operation(reconnected, finishId).path("result"));
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

    private static int countWrites(OperationScreenWorld world, String prefix) {
        int found = 0; for (String write : world.writes) if (write.startsWith(prefix)) found++; return found;
    }

    private static ObjectNode five() {
        ObjectNode request = JSON.createObjectNode();
        request.put("dryRun", true); request.put("project", "ABC");
        ArrayNode rows = request.putArray("mappings");
        rows.add(row("Task", "3", "4", "6")); rows.add(row("Bug", "4", "5", "7")); rows.add(row("Story", "6", "7", "8"));
        rows.add(row("Epic", "7", "8", "9")); rows.add(row("Other", "8", "9", "3"));
        return request;
    }

    private static ObjectNode row(String type, String create, String edit, String view) {
        ObjectNode row = JSON.createObjectNode();
        row.putArray("issueTypes").add(type);
        ObjectNode screens = row.putObject("screens");
        screens.put("CREATE", create); screens.put("EDIT", edit); screens.put("VIEW", view);
        return row;
    }

    private static JsonNode byName(JsonNode result, String name) {
        for (JsonNode type : result.path("issueTypes")) if (name.equals(type.path("issueType").path("name").asText())) return type;
        throw new AssertionError(name + " in " + result);
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
