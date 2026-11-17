package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.tools.ChangeTools;
import ru.rasulov.common.impl.tools.McpServer;
import ru.rasulov.common.impl.tools.OperationRunner;
import ru.rasulov.common.impl.tools.ToolCatalog;
import ru.rasulov.common.impl.tools.ToolRegistry;
import ru.rasulov.customfields.api.CustomFieldCreator;
import ru.rasulov.customfields.api.FieldConfigurationCloneService;
import ru.rasulov.customfields.api.FieldContextReader;
import ru.rasulov.customfields.api.FieldContextScopeService;
import ru.rasulov.customfields.api.FieldRequirementService;
import ru.rasulov.customfields.api.FieldScreenService;
import ru.rasulov.customfields.impl.CustomFieldTools;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;
import ru.rasulov.customfields.impl.filefield.FileFieldConfiguration;
import ru.rasulov.customfields.impl.undo.CustomFieldUndoHandler;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.impl.JiraMocks;
import com.atlassian.beehive.ClusterLock;
import com.atlassian.jira.issue.customfields.manager.GenericConfigManager;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local JSON-RPC to real field service and AO/HSQL journal; no Jira server or network is used. */
public class FieldContextManagedMcpSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test public void managedCreatePersistsSerializedDomainResultAndConsumesItsPlanOnlyOnce() throws Exception {
        FieldContextCreatorImplTest owner = new FieldContextCreatorImplTest();
        owner.initializeComponents();
        OperationRunner runner = null;
        Semaphore finished = new Semaphore(0);
        int submitted = 0;
        try {
            FieldContextCreatorImplTest.Fixture f = owner.new Fixture();
            ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
            ToolRegistry[] registry = new ToolRegistry[1];
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            doAnswer(call -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
            ApplyJournal applies = mock(ApplyJournal.class);
            runner = new OperationRunner(f.journal, f.guard, () -> registry[0], catalog,
                    mock(JiraAuthenticationContext.class), locals, f.plans, applies);
            CustomFieldTools fields = new CustomFieldTools(mock(CustomFieldCreator.class), mock(FieldScreenService.class),
                    mock(FieldRequirementService.class), mock(FieldConfigurationCloneService.class),
                    mock(AssetsFieldConfiguration.class), f.guard, mock(FieldContextReader.class),
                    mock(FieldContextScopeService.class), f.service);
            ChangeTools changes = new ChangeTools(f.journal, f.guard, runner, f.plans, applies);
            registry[0] = new ToolRegistry(Arrays.asList(fields, changes));
            McpServer server = new McpServer(f.guard, catalog, registry[0]);

            JsonNode preview = call(server, "create_field_context",
                    JSON.valueToTree(f.request("QA managed MCP", "QABUG", "Bug", "Sub-bug", "Sub-task")));
            assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
            assertEquals("customfield_123", preview.path("field").path("id").asText());
            JsonNode issueTypes = preview.path("context").path("scope").path("issueTypes");
            assertEquals(3, issueTypes.size());
            assertEquals("1", issueTypes.get(0).path("id").asText());
            assertEquals("2", issueTypes.get(1).path("id").asText());
            assertEquals("3", issueTypes.get(2).path("id").asText());
            assertTrue(preview.toString(), preview.path("planId").isTextual());
            verify(f.fields, atLeastOnce()).getCustomFieldObject("customfield_123");
            verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
            assertEquals(0, f.creates.get());

            ObjectNode apply = JSON.createObjectNode();
            apply.put("dryRun", false);
            apply.put("planId", preview.path("planId").asText());
            JsonNode refused = response(server, "create_field_context", apply);
            assertTrue(refused.toString(), refused.path("isError").asBoolean());
            assertEquals(0, f.creates.get());

            ObjectNode start = JSON.createObjectNode();
            start.put("toolName", "create_field_context");
            start.put("arguments", apply);
            JsonNode started = call(server, "start_operation", start);
            submitted++;
            String operationId = started.path("operationId").asText();
            assertFalse(started.toString(), operationId.isEmpty());
            JsonNode terminal = operation(server, operationId);
            assertEquals(terminal.toString(), "COMPLETED", terminal.path("status").asText());
            JsonNode created = terminal.path("result");
            assertEquals(terminal.toString(), "CREATED", created.path("status").asText());
            assertEquals("customfield_123", created.path("field").path("id").asText());
            assertEquals(3L, created.path("context").path("contextId").asLong());
            assertEquals(93L, created.path("context").path("configIds").get(0).asLong());
            assertEquals("FIELD_CONTEXT", created.path("report").path("created").get(0).path("kind").asText());
            assertEquals("3", created.path("report").path("created").get(0).path("id").asText());
            assertEquals("create_field_context", terminal.path("operation").asText());
            assertFalse(terminal.toString(), terminal.path("sessionId").asText().isEmpty());
            assertFalse(terminal.toString(), terminal.path("changeSetId").asText().isEmpty());
            assertEquals(1, terminal.path("steps").size());
            assertEquals("cf.field", terminal.path("steps").get(0).path("kind").asText());
            assertEquals("APPLIED", terminal.path("steps").get(0).path("status").asText());
            assertEquals(1, f.creates.get());
            verify(f.factory).create("customfield_123", "QA managed MCP", "");

            // A fresh public get_operation call must return the persisted result, not the service's live DTO.
            assertEquals(created, operation(server, operationId).path("result"));
            JsonNode replayStarted = call(server, "start_operation", start);
            String replayId = replayStarted.path("operationId").asText();
            assertEquals(replayStarted.toString(), operationId, replayId);
            assertTrue(replayStarted.toString(), replayStarted.path("alreadyStarted").asBoolean());
            JsonNode replay = operation(server, replayId);
            assertEquals(replay.toString(), "COMPLETED", replay.path("status").asText());
            assertEquals(created, replay.path("result"));
            assertEquals(terminal.path("steps"), replay.path("steps"));
            assertEquals(1, f.creates.get());

            assertTrue("Managed groups did not finish", finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            submitted = 0;
            // Undo is deliberately internal: its real consumer must not delete a non-undoable context,
            // even though the durable journal retains the actual APPLIED cf.field intent.
            JsonNode rollbackPreview = JSON.valueToTree(f.journal.rollback("admin", operationId, null, null, true, null));
            assertEquals(rollbackPreview.toString(), "READY", rollbackPreview.path("status").asText());
            assertEquals(0, rollbackPreview.path("steps").size());
            assertEquals(1, rollbackPreview.path("nonUndoableOperations").size());
            assertEquals("create_field_context",
                    rollbackPreview.path("nonUndoableOperations").get(0).path("toolName").asText());
            assertEquals(operationId,
                    rollbackPreview.path("nonUndoableOperations").get(0).path("operationId").asText());
            JsonNode rollback = JSON.valueToTree(f.journal.rollback("admin", null, null, null, false,
                    rollbackPreview.path("planId").asText()));
            assertEquals(rollback.toString(), "PARTIAL", rollback.path("status").asText());
            assertEquals(0, rollback.path("undoneSteps").asInt());
            assertEquals(rollbackPreview.path("nonUndoableOperations"), rollback.path("nonUndoableOperations"));
            JsonNode retained = operation(server, operationId);
            assertEquals(retained.toString(), "COMPLETED", retained.path("status").asText());
            assertEquals(created, retained.path("result"));
            assertEquals("APPLIED", retained.path("steps").get(0).path("status").asText());
            assertEquals(2, f.contexts.size());
            verify(f.schemes, times(1)).createFieldConfigScheme(any(), anyList(), anyList(), eq(f.field));
            verify(f.schemes, never()).updateFieldConfigScheme(any());
            verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
            verify(f.schemes, never()).removeFieldConfigScheme(anyLong());
        } finally {
            try {
                if (runner != null) runner.destroy();
                assertTrue("Background workers did not finish before closing AO/HSQL",
                        finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            } finally {
                owner.closeJournals();
            }
        }
    }

    @Test public void managedOptionsUpdatePreviewsAppliesOnlyThroughStartOperationReadsBackAndConsumesItsPlan() throws Exception {
        FieldContextCreatorImplTest owner = new FieldContextCreatorImplTest();
        owner.initializeComponents();
        OperationRunner runner = null;
        Semaphore finished = new Semaphore(0);
        int submitted = 0;
        try {
            FieldContextCreatorImplTest.Fixture f = owner.new Fixture();
            f.select("select");
            OptionsManager manager = mock(OptionsManager.class);
            OptionStoreFake store = new OptionStoreFake();
            store.option(1, 91, null, 0, "High", false).option(2, 91, null, 1, "Medium", false)
                    .option(3, 91, null, 2, "Low", true);
            store.install(manager, f.db);
            CustomFieldUndoHandler undo = mock(CustomFieldUndoHandler.class);
            when(undo.persistedSnapshot(anyString(), anyString())).thenReturn("persisted-field");
            ActionPlanCodec codec = new ActionPlanCodec();
            OptionUsageCounter usage = (user, field, options) -> {
                Map<Long, OptionUsage> counted = new LinkedHashMap<>();
                for (Long id : options.keySet()) counted.put(id, OptionUsage.count(4));
                return counted;
            };
            FieldContextScopeServiceImpl scopes = new FieldContextScopeServiceImpl(f.guard,
                    new NamedLock(JiraMocks.lockService(mock(ClusterLock.class))), new FieldSelector(f.fields), f.managed,
                    new ProjectResolver(f.projectManager), new IssueTypeResolver(f.typeManager), f.fields, f.fieldManager,
                    f.schemes, f.projectManager, f.reindex, new FieldContextSnapshots(undo, f.db, f.assets),
                    new PreviewPlanExecutor(f.plans, codec), codec, f.journal, f.assets, manager, usage);
            FieldContextReaderImpl reader = new FieldContextReaderImpl(f.guard, new FieldSelector(f.fields),
                    new ProjectResolver(f.projectManager), f.managed,
                    manager, mock(GenericConfigManager.class), f.assets, mock(FileFieldConfiguration.class));
            ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
            ToolRegistry[] registry = new ToolRegistry[1];
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            doAnswer(call -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
            ApplyJournal applies = mock(ApplyJournal.class);
            runner = new OperationRunner(f.journal, f.guard, () -> registry[0], catalog,
                    mock(JiraAuthenticationContext.class), locals, f.plans, applies);
            CustomFieldTools fields = new CustomFieldTools(mock(CustomFieldCreator.class), mock(FieldScreenService.class),
                    mock(FieldRequirementService.class), mock(FieldConfigurationCloneService.class),
                    mock(AssetsFieldConfiguration.class), f.guard, reader, scopes, f.service);
            ChangeTools changes = new ChangeTools(f.journal, f.guard, runner, f.plans, applies);
            registry[0] = new ToolRegistry(Arrays.asList(fields, changes));
            McpServer server = new McpServer(f.guard, catalog, registry[0]);

            ObjectNode read = JSON.createObjectNode();
            read.put("field", "customfield_123"); read.put("contextId", 1); read.put("includeOptions", true);
            JsonNode before = call(server, "get_field_contexts", read).path("contexts").get(0).path("optionList");
            assertTrue(before.toString(), before.path("hasOptions").asBoolean());
            assertEquals(3, before.path("items").size());
            assertEquals("High", before.path("items").get(0).path("value").asText());
            assertTrue(before.path("items").get(2).path("disabled").asBoolean());

            JsonNode arguments = JSON.readTree("{\"dryRun\":true,\"fieldId\":\"customfield_123\",\"contexts\":[{\"contextId\":1,"
                    + "\"options\":{\"add\":[{\"value\":\"Urgent\"}],\"rename\":[{\"optionId\":2,\"value\":\"Normal\"}],"
                    + "\"disable\":[1],\"order\":[{\"optionIds\":[2,1,3]}]}}]}");
            JsonNode preview = call(server, "update_field_contexts", arguments);
            assertEquals(preview.toString(), "WOULD_UPDATE", preview.path("status").asText());
            assertTrue(preview.toString(), preview.path("planId").isTextual());
            JsonNode rows = preview.path("contexts").get(0).path("options").path("rows");
            assertEquals(preview.toString(), 3, rows.size());
            assertEquals(4, rows.get(0).path("usage").path("issues").asInt());
            assertEquals(0, store.writes);

            ObjectNode apply = JSON.createObjectNode();
            apply.put("dryRun", false);
            apply.put("planId", preview.path("planId").asText());
            JsonNode refused = response(server, "update_field_contexts", apply);
            assertTrue(refused.toString(), refused.path("isError").asBoolean());
            assertEquals(0, store.writes);

            ObjectNode start = JSON.createObjectNode();
            start.put("toolName", "update_field_contexts");
            start.put("arguments", apply);
            JsonNode started = call(server, "start_operation", start);
            submitted++;
            String operationId = started.path("operationId").asText();
            assertFalse(started.toString(), operationId.isEmpty());
            JsonNode terminal = operation(server, operationId);
            assertEquals(terminal.toString(), "COMPLETED", terminal.path("status").asText());
            assertEquals(terminal.toString(), "UPDATED", terminal.path("result").path("status").asText());
            assertEquals("update_field_contexts", terminal.path("operation").asText());
            assertFalse(terminal.path("result").path("reindexRecommended").asBoolean());
            int writes = store.writes;
            assertTrue(writes >= 4);
            verifyNoInteractions(f.reindex);
            verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());

            JsonNode after = call(server, "get_field_contexts", read).path("contexts").get(0).path("optionList");
            JsonNode items = after.path("items");
            assertEquals(after.toString(), 4, items.size());
            String[] values = {"Normal", "High", "Low", "Urgent"};
            boolean[] disabled = {false, true, true, false};
            for (int i = 0; i < values.length; i++) {
                assertEquals(after.toString(), values[i], items.get(i).path("value").asText());
                assertEquals(after.toString(), disabled[i], items.get(i).path("disabled").asBoolean());
                assertEquals(after.toString(), i, items.get(i).path("position").asInt());
            }

            JsonNode replayStarted = call(server, "start_operation", start);
            assertEquals(replayStarted.toString(), operationId, replayStarted.path("operationId").asText());
            assertTrue(replayStarted.toString(), replayStarted.path("alreadyStarted").asBoolean());
            JsonNode replayRefused = response(server, "update_field_contexts", apply);
            assertTrue(replayRefused.toString(), replayRefused.path("isError").asBoolean());
            assertEquals("The consumed plan must not write again", writes, store.writes);
            assertTrue("Managed groups did not finish", finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            submitted = 0;
        } finally {
            try {
                if (runner != null) runner.destroy();
                assertTrue("Background workers did not finish before closing AO/HSQL",
                        finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            } finally {
                owner.closeJournals();
            }
        }
    }

    private static JsonNode operation(McpServer server, String operationId) throws Exception {
        ObjectNode args = JSON.createObjectNode();
        args.put("operationId", operationId);
        args.put("waitSeconds", 20);
        return call(server, "get_operation", args);
    }

    private static JsonNode call(McpServer server, String tool, JsonNode args) throws Exception {
        JsonNode result = response(server, tool, args);
        assertFalse(result.toString(), result.path("isError").asBoolean());
        return JSON.readTree(result.path("content").get(0).path("text").asText());
    }

    private static JsonNode response(McpServer server, String tool, JsonNode args) throws Exception {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.put("arguments", args);
        JsonNode response = JSON.readTree(server.handle(request.toString()));
        assertEquals(response.toString(), "2.0", response.path("jsonrpc").asText());
        assertEquals(response.toString(), 1, response.path("id").asInt());
        assertFalse(response.toString(), response.has("error"));
        return response.path("result");
    }
}
