package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.avatar.Avatar;
import com.atlassian.jira.avatar.AvatarManager;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.tools.ChangeTools;
import ru.rasulov.common.impl.tools.McpServer;
import ru.rasulov.common.impl.tools.OperationRunner;
import ru.rasulov.common.impl.tools.ToolCatalog;
import ru.rasulov.common.impl.tools.ToolRegistry;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.issuetypes.api.ProjectIssueTypeService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JSON-RPC -> real tool provider -> real planner/writer/creator -> OperationRunner -> ChangeJournal on AO/HSQL.
 * Only Jira's managers are doubles; the fake catalog is the truth every assertion is compared with.
 */
public class IssueTypeBatchMcpSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void batchIsConfirmedOnceAppliedOnceAndItsIdsSurviveRereadReplayAndAPartialFailure() throws Exception {
        Jira jira = new Jira();
        Semaphore finished = new Semaphore(0);
        int submitted = 0;
        OperationRunner runner = null;
        try (UndoTestFixture f = UndoTestFixture.create("admin", new CreatedIssueTypeUndoHandler(jira.support))) {
            ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
            ToolRegistry[] registry = new ToolRegistry[1];
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            doAnswer(call -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
            ApplyJournal applies = mock(ApplyJournal.class);
            runner = new OperationRunner(f.journal, f.guard, () -> registry[0], catalog,
                    mock(JiraAuthenticationContext.class), locals, f.plans, applies);
            IssueTypeCreatorImpl creator = new IssueTypeCreatorImpl(f.guard, f.locks,
                    new ru.rasulov.common.impl.PreviewPlanExecutor(f.plans, new ActionPlanCodec()), new ActionPlanCodec(),
                    f.journal, new IssueTypeBatchPlanner(jira.types, jira.avatars),
                    new IssueTypeBatchWriter(jira.types, f.journal, jira.support),
                    ru.rasulov.common.impl.TestLinks.links());
            registry[0] = new ToolRegistry(Arrays.asList(new IssueTypeTools(mock(ProjectIssueTypeService.class), creator),
                    new ChangeTools(f.journal, f.guard, runner, f.plans, applies)));
            McpServer server = new McpServer(f.guard, catalog, registry[0]);

            // 1. one preview for four missing types and the existing «Консультация»
            JsonNode preview = call(server, "create_issue_types", request(
                    item("pay", "Оплаты", false), item("consult", "Консультация", false),
                    item("paper", "Бумажные документы", false), item("supply", "Снабжение", false),
                    item("ka", "Работа с КА", false)));
            assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
            assertTrue(preview.toString(), preview.path("planId").isTextual());
            assertEquals("consult", preview.path("results").get(1).path("key").asText());
            assertEquals("EXISTS", preview.path("results").get(1).path("status").asText());
            assertEquals(jira.consultId, preview.path("results").get(1).path("issueType").path("id").asText());
            assertEquals("a preview writes nothing", 2, jira.members.size());

            ObjectNode apply = JSON.createObjectNode();
            apply.put("dryRun", false);
            apply.put("planId", preview.path("planId").asText());
            JsonNode refused = response(server, "create_issue_types", apply);
            assertTrue("never applied directly: " + refused, refused.path("isError").asBoolean());
            assertEquals(2, jira.members.size());

            // 2. one managed operation; the terminal result carries the real ids
            ObjectNode start = JSON.createObjectNode();
            start.put("toolName", "create_issue_types");
            start.put("arguments", apply);
            String operationId = call(server, "start_operation", start).path("operationId").asText();
            submitted++;
            assertFalse(operationId.isEmpty());
            JsonNode terminal = operation(server, operationId);
            assertEquals(terminal.toString(), "COMPLETED", terminal.path("status").asText());
            JsonNode result = terminal.path("result");
            assertEquals(result.toString(), "CREATED", result.path("status").asText());
            assertEquals("exactly four types were created", 6, jira.members.size());
            assertEquals(Arrays.asList("Оплаты", "Бумажные документы", "Снабжение", "Работа с КА"), jira.createCalls);
            for (JsonNode entry : result.path("results")) {
                IssueType real = jira.byId(entry.path("issueType").path("id").asText());
                assertNotNull(entry.toString(), real);
                assertEquals(real.getName(), entry.path("issueType").path("name").asText());
            }
            assertEquals(Arrays.asList("pay", "consult", "paper", "supply", "ka"), keys(result));
            assertEquals(4, result.path("report").path("created").size());
            assertEquals("ISSUE_TYPE", result.path("report").path("created").get(0).path("kind").asText());
            assertEquals(1, result.path("report").path("reused").size());
            assertEquals(jira.consultId, result.path("report").path("reused").get(0).path("id").asText());
            assertEquals("four applied journal steps, one per created type", 4, terminal.path("steps").size());
            for (JsonNode step : terminal.path("steps")) assertEquals("APPLIED", step.path("status").asText());

            // 3. a reconnect reads the persisted, identical result
            McpServer reconnected = new McpServer(f.guard, catalog, registry[0]);
            assertEquals(result, operation(reconnected, operationId).path("result"));

            // 4. replaying the finished plan creates nothing
            JsonNode replayStart = call(reconnected, "start_operation", start);
            assertEquals(operationId, replayStart.path("operationId").asText());
            assertTrue(replayStart.toString(), replayStart.path("alreadyStarted").asBoolean());
            JsonNode replay = operation(reconnected, operationId);
            assertEquals("COMPLETED", replay.path("status").asText());
            assertEquals(result, replay.path("result"));
            assertEquals(terminal.path("steps"), replay.path("steps"));
            assertEquals(6, jira.members.size());
            assertEquals(4, jira.createCalls.size());

            // 5. a native failure in the middle of a second batch: created types stay, ids are kept in the operation
            JsonNode second = call(server, "create_issue_types", request(
                    item("a", "Закупки", false), item("b", "Возвраты", false), item("c", "Склад", true)));
            assertEquals(second.toString(), "WOULD_CREATE", second.path("status").asText());
            jira.failOn = "Возвраты";
            ObjectNode secondApply = JSON.createObjectNode();
            secondApply.put("dryRun", false);
            secondApply.put("planId", second.path("planId").asText());
            ObjectNode secondStart = JSON.createObjectNode();
            secondStart.put("toolName", "create_issue_types");
            secondStart.put("arguments", secondApply);
            String partialId = call(server, "start_operation", secondStart).path("operationId").asText();
            submitted++;
            JsonNode partial = operation(server, partialId);
            assertEquals("the operation itself completed; its domain result says PARTIALLY_CHANGED", "COMPLETED",
                    partial.path("status").asText());
            JsonNode partialResult = partial.path("result");
            assertEquals(partialResult.toString(), "PARTIALLY_CHANGED", partialResult.path("status").asText());
            assertEquals("JIRA_ERROR", partialResult.path("errorCode").asText());
            assertEquals(Arrays.asList("CREATED", "FAILED", "NOT_EXECUTED"), statuses(partialResult));
            String keptId = partialResult.path("results").get(0).path("issueType").path("id").asText();
            assertEquals("Закупки", jira.byId(keptId).getName());
            assertNull(jira.find("Возвраты"));
            assertNull(jira.find("Склад"));
            assertEquals(7, jira.members.size());
            assertEquals(1, partialResult.path("report").path("created").size());
            assertEquals(keptId, partialResult.path("report").path("created").get(0).path("id").asText());
            assertEquals(partialResult, operation(reconnected, partialId).path("result"));

            // 6. after the failure is gone the same list reuses the kept type and creates only the rest
            jira.failOn = null;
            JsonNode again = call(server, "create_issue_types", request(
                    item("a", "Закупки", false), item("b", "Возвраты", false), item("c", "Склад", true)));
            assertEquals(Arrays.asList("EXISTS", "WOULD_CREATE", "WOULD_CREATE"), statuses(again));
            assertEquals(keptId, again.path("results").get(0).path("issueType").path("id").asText());
            ObjectNode finishApply = JSON.createObjectNode();
            finishApply.put("dryRun", false);
            finishApply.put("planId", again.path("planId").asText());
            ObjectNode finishStart = JSON.createObjectNode();
            finishStart.put("toolName", "create_issue_types");
            finishStart.put("arguments", finishApply);
            String finishId = call(server, "start_operation", finishStart).path("operationId").asText();
            submitted++;
            JsonNode finish = operation(server, finishId).path("result");
            assertEquals(finish.toString(), "CREATED", finish.path("status").asText());
            assertEquals(9, jira.members.size());
            List<String> names = new ArrayList<String>();
            for (IssueType type : jira.members) {
                assertFalse("no duplicate " + type.getName(), names.contains(type.getName()));
                names.add(type.getName());
            }
            assertTrue(jira.find("Склад").isSubTask());
        } finally {
            try {
                if (runner != null) runner.destroy();
                assertTrue("Background workers did not finish before closing AO/HSQL",
                        finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            } finally {
                jira.members.clear();
            }
        }
    }

    private static ObjectNode item(String key, String name, boolean subtask) {
        ObjectNode item = JSON.createObjectNode();
        item.put("key", key);
        item.put("name", name);
        item.put("subtask", subtask);
        return item;
    }

    private static ObjectNode request(ObjectNode... items) {
        ObjectNode request = JSON.createObjectNode();
        request.put("dryRun", true);
        ArrayNode array = request.putArray("issueTypes");
        for (ObjectNode item : items) array.add(item);
        return request;
    }

    private static List<String> keys(JsonNode result) {
        List<String> keys = new ArrayList<String>();
        for (JsonNode entry : result.path("results")) keys.add(entry.path("key").asText());
        return keys;
    }

    private static List<String> statuses(JsonNode result) {
        List<String> statuses = new ArrayList<String>();
        for (JsonNode entry : result.path("results")) statuses.add(entry.path("status").asText());
        return statuses;
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
        assertFalse(response.toString(), response.has("error"));
        return response.path("result");
    }

    /** Fake Jira: the catalog is also the default issue type scheme's membership, as in real Jira. */
    private static final class Jira {
        final IssueTypeManager types = mock(IssueTypeManager.class);
        final IssueTypeSchemeManager schemes = mock(IssueTypeSchemeManager.class);
        final AvatarManager avatars = mock(AvatarManager.class);
        final FieldConfigScheme scheme = mock(FieldConfigScheme.class);
        final FieldConfig config = mock(FieldConfig.class);
        final OfBizDelegator delegator = mock(OfBizDelegator.class);
        final WorkflowSchemeManager workflows = mock(WorkflowSchemeManager.class);
        final List<IssueType> members = new ArrayList<IssueType>();
        final List<String> createCalls = new ArrayList<String>();
        final String consultId = "7";
        final IssueTypeUndoSupport support;
        String failOn;
        private int next = 100;

        Jira() {
            IssueType task = type("1", "Task", false);
            members.add(task);
            members.add(type(consultId, "Консультация", false));
            when(delegator.findListIteratorByCondition(anyString(), any(), any(), any(), any(), any()))
                    .thenAnswer(call -> ru.rasulov.common.impl.undo.Cursors.over(Collections.emptyList()));
            when(workflows.getAssignableSchemes()).thenReturn(Collections.emptyList());
            when(scheme.getId()).thenReturn(10L);
            when(scheme.getOneAndOnlyConfig()).thenReturn(config);
            when(schemes.getDefaultIssueTypeSchemeId()).thenReturn(10L);
            when(schemes.getDefaultIssueTypeScheme()).thenReturn(scheme);
            when(schemes.getIssueTypesForScheme(scheme)).thenAnswer(call -> new ArrayList<IssueType>(members));
            when(schemes.getDefaultValue(config)).thenReturn(task);
            when(schemes.isDefaultIssueTypeScheme(scheme)).thenReturn(true);
            when(types.getIssueTypes()).thenAnswer(call -> new ArrayList<IssueType>(members));
            when(types.getIssueType(anyString())).thenAnswer(call -> byId(call.getArgument(0)));
            when(avatars.getDefaultAvatarId(Avatar.Type.ISSUETYPE)).thenReturn(17L);
            when(types.createIssueType(anyString(), anyString(), any(Long.class))).thenAnswer(call -> add(call.getArgument(0), false));
            when(types.createSubTaskIssueType(anyString(), anyString(), any(Long.class))).thenAnswer(call -> add(call.getArgument(0), true));
            support = new IssueTypeUndoSupport(types, schemes, delegator, workflows, mock(IssueTypeScreenSchemeManager.class));
        }

        private IssueType add(String name, boolean subtask) {
            createCalls.add(name);
            if (name.equals(failOn)) throw new IllegalStateException("Jira storage error");
            IssueType created = type(String.valueOf(next++), name, subtask);
            members.add(created);
            return created;
        }

        IssueType byId(String id) {
            for (IssueType type : members) if (type.getId().equals(id)) return type;
            return null;
        }

        IssueType find(String name) {
            for (IssueType type : members) if (type.getName().equals(name)) return type;
            return null;
        }

        private static IssueType type(String id, String name, boolean subtask) {
            IssueType type = mock(IssueType.class);
            when(type.getId()).thenReturn(id);
            when(type.getName()).thenReturn(name);
            when(type.getDescription()).thenReturn("");
            when(type.isSubTask()).thenReturn(subtask);
            return type;
        }
    }
}
