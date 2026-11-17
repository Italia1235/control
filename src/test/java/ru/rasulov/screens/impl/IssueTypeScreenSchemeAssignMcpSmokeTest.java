package ru.rasulov.screens.impl;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.rasulov.screens.api.ScreenSchemeOperationEditor;
import ru.rasulov.screens.api.ScreenSchemeService;
import ru.rasulov.screens.api.StandaloneScreenCreator;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * JSON-RPC -> real ScreenTools -> real assigner/planner/writer -> OperationRunner -> ChangeJournal on AO/HSQL for
 * {@code assign_issue_type_screen_scheme}. Only Jira's managers are doubles; {@link ItssAssignWorld} is the truth.
 */
public class IssueTypeScreenSchemeAssignMcpSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL = "assign_issue_type_screen_scheme";

    @Test
    public void previewOneConfirmationOneOperationSwitchesOnlyTheProjectAndRecordsTheApply() throws Exception {
        ItssAssignWorld world = new ItssAssignWorld();
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
            IssueTypeScreenSchemeAssignerImpl assigner = new IssueTypeScreenSchemeAssignerImpl(f.guard, f.locks,
                    new IssueTypeScreenSchemeAssignmentPlanner(world.projects, world.tops, world.projectManager, world.requirements, world.codec),
                    new IssueTypeScreenSchemeAssignmentWriter(world.tops),
                    new PreviewPlanExecutor(f.plans, world.codec));
            registry[0] = new ToolRegistry(Arrays.asList(
                    new ScreenTools(mock(ScreenSchemeService.class), mock(StandaloneScreenCreator.class),
                            mock(ProjectOperationScreenAssigner.class), mock(ScreenContentsReader.class),
                            mock(ScreenFieldRemover.class), mock(ScreenLayoutEditor.class),
                            mock(IssueTypeScreenSchemeMappingEditor.class), mock(ScreenSchemeOperationEditor.class), assigner),
                    new ChangeTools(f.journal, f.guard, runner, f.plans, applies)));
            McpServer server = new McpServer(f.guard, catalog, registry[0]);
            int tops = world.topCatalog.size(), fields = world.fieldCatalog.size();
            Map<String, Long> sharedRows = world.table(world.shared), ownRows = world.table(world.own);

            // 0. a validation failure is an MCP error result with the code, and no plan
            JsonNode bad = response(server, TOOL, preview("OWN", "No such ITSS"));
            assertTrue(bad.path("isError").asBoolean());
            assertEquals("ITSS_NOT_FOUND", JSON.readTree(bad.path("content").get(0).path("text").asText()).path("errorCode").asText());
            assertTrue(world.writes.isEmpty());

            // 1. ONE preview: the project, the current and the new scheme, per type before/after, the warnings
            JsonNode preview = call(server, TOOL, preview("own", "shared itss"));
            assertEquals(preview.toString(), "WOULD_CHANGE", preview.path("status").asText());
            assertTrue(preview.path("planId").isTextual());
            assertEquals(21, preview.path("currentIssueTypeScreenScheme").path("id").asInt());
            assertEquals(20, preview.path("newIssueTypeScreenScheme").path("id").asInt());
            assertEquals(2, preview.path("issueTypes").size());
            assertEquals("CREATE", preview.path("issueTypes").get(0).path("screens").get(0).path("operation").asText());
            assertTrue(hasWarning(preview, "ITSS_ORPHANED"));
            assertTrue(hasWarning(preview, "SHARED_TARGET"));
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
            assertEquals(20, result.path("observedIssueTypeScreenScheme").path("id").asInt());
            assertEquals(Collections.singletonList("associate OWN 20"), world.writes);
            assertEquals(sharedRows, world.table(world.shared));
            assertEquals(ownRows, world.table(world.own));
            assertEquals("no new ITSS, no new Screen Scheme", tops, world.topCatalog.size());
            assertEquals(fields, world.fieldCatalog.size());
            verify(world.tops, never()).createIssueTypeScreenScheme(any());
            verify(world.tops, never()).updateIssueTypeScreenSchemeEntity(any());

            // 3. the ApplyRecord is written by OperationRunner; the project is its audit target
            ArgumentCaptor<ApplyEvent> event = ArgumentCaptor.forClass(ApplyEvent.class);
            verify(applies, atLeastOnce()).record(event.capture());
            ApplyEvent recorded = event.getValue();
            assertEquals(TOOL, recorded.toolName); assertEquals(operationId, recorded.operationRef);
            assertEquals("COMPLETED", recorded.operationState); assertEquals("CHANGED", recorded.domainStatus);
            assertFalse("the audit page shows the tool by its title, not the bare name", TOOL.equals(recorded.toolTitle));

            // 4. a reconnect reads the persisted identical result; a replay writes nothing
            McpServer reconnected = new McpServer(f.guard, catalog, registry[0]);
            assertEquals(result, operation(reconnected, operationId).path("result"));
            JsonNode replay = call(reconnected, "start_operation", start);
            assertTrue(replay.toString(), replay.path("alreadyStarted").asBoolean());
            assertEquals(1, world.writes.size());

            // 5. the switch cannot be undone: the journal reports it as forward-only and rolls nothing back
            Map<String, Object> undo = f.journal.rollback("admin", operationId, null, null, true, null);
            List<?> forwardOnly = (List<?>) undo.get("nonUndoableOperations");
            assertNotNull(undo.toString(), forwardOnly);
            assertEquals(1, forwardOnly.size());
            assertEquals(TOOL, ((Map<?, ?>) forwardOnly.get(0)).get("toolName"));
            assertEquals(Collections.singletonList("associate OWN 20"), world.writes);
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

    private static boolean hasWarning(JsonNode preview, String code) {
        for (JsonNode warning : preview.path("warnings")) if (code.equals(warning.path("code").asText())) return true;
        return false;
    }

    private static ObjectNode preview(String project, String itss) {
        ObjectNode request = JSON.createObjectNode();
        request.put("dryRun", true); request.put("project", project); request.put("issueTypeScreenScheme", itss);
        return request;
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
