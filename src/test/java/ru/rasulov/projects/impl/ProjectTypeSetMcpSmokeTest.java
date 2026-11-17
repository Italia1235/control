package ru.rasulov.projects.impl;

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
import ru.rasulov.issuetypes.api.IssueTypeCreator;
import ru.rasulov.issuetypes.impl.IssueTypeTools;
import ru.rasulov.issuetypes.impl.ProjectTypeWorld;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * JSON-RPC -> ProjectTools/IssueTypeTools -> real domain -> OperationRunner -> ChangeJournal (AO/HSQL): the project
 * with its type set is confirmed once and applied once, and the saved results survive a reconnect. Only Jira's
 * managers are doubles.
 */
public class ProjectTypeSetMcpSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class Rig implements AutoCloseable {
        final ProjectFlowEnv env = new ProjectFlowEnv();
        final Semaphore finished = new Semaphore(0);
        final ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
        final ToolRegistry[] registry = new ToolRegistry[1];
        final OperationRunner runner;
        final McpServer server;
        int submitted;

        Rig() {
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            doAnswer(call -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
            ApplyJournal applies = mock(ApplyJournal.class);
            runner = new OperationRunner(env.fixture.journal, env.fixture.guard, () -> registry[0], catalog,
                    mock(JiraAuthenticationContext.class), locals, env.fixture.plans, applies);
            registry[0] = new ToolRegistry(Arrays.asList(new ProjectTools(env.creator),
                    new IssueTypeTools(env.service, mock(IssueTypeCreator.class)),
                    new ChangeTools(env.fixture.journal, env.fixture.guard, runner, env.fixture.plans, applies)));
            server = new McpServer(env.fixture.guard, catalog, registry[0]);
        }

        McpServer reconnect() { return new McpServer(env.fixture.guard, catalog, registry[0]); }

        /** start_operation of a preview's plan, then the terminal state through get_operation. */
        JsonNode apply(McpServer on, String tool, String planId) throws Exception {
            return apply(on, tool, planId, false);
        }

        JsonNode apply(McpServer on, String tool, String planId, boolean alreadyStarted) throws Exception {
            ObjectNode args = JSON.createObjectNode();
            args.put("dryRun", false);
            args.put("planId", planId);
            ObjectNode start = JSON.createObjectNode();
            start.put("toolName", tool);
            start.put("arguments", args);
            JsonNode started = call(on, "start_operation", start);
            String id = started.path("operationId").asText();
            assertEquals(started.toString(), alreadyStarted, started.path("alreadyStarted").asBoolean());
            if (!alreadyStarted) submitted++;
            assertFalse(id.isEmpty());
            return operation(on, id);
        }

        @Override public void close() throws Exception {
            try {
                runner.destroy();
                assertTrue("Background workers did not finish before closing AO/HSQL",
                        finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            } finally {
                env.close();
            }
        }
    }

    @Test
    public void projectWithItsFiveTypesIsConfirmedOnceAppliedOnceAndItsResultSurvivesReconnectAndReplay() throws Exception {
        try (Rig rig = new Rig()) {
            ProjectTypeWorld w = rig.env.world;
            w.add("7", "Консультация", false);
            w.schemeStates.get(ProjectTypeWorld.DEFAULT_SCHEME).typeIds.add("7");

            JsonNode preview = call(rig.server, "create_project", previewArgs(fiveTypes(), "supply"));
            assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
            assertEquals(5, preview.path("issueTypeSet").path("entries").size());
            assertEquals("Снабжение", preview.path("issueTypeSet").path("defaultIssueType").path("name").asText());
            assertEquals("REQUESTED", preview.path("issueTypeSet").path("defaultSource").asText());
            assertEquals("a preview creates neither project nor types", 0, rig.env.projectCreations);

            ObjectNode direct = JSON.createObjectNode();
            direct.put("dryRun", false);
            direct.put("planId", preview.path("planId").asText());
            assertTrue("never applied directly", response(rig.server, "create_project", direct).path("isError").asBoolean());
            assertEquals(0, rig.env.projectCreations);

            JsonNode terminal = rig.apply(rig.server, "create_project", preview.path("planId").asText());
            assertEquals(terminal.toString(), "COMPLETED", terminal.path("status").asText());
            JsonNode result = terminal.path("result");
            assertEquals(result.toString(), "CREATED", result.path("status").asText());
            assertEquals(1, rig.env.projectCreations);
            assertEquals(w.projectByKey("ALPHA").getId().longValue(), result.path("projectId").asLong());
            assertEquals("COMPLETED", result.path("issueTypeSet").path("assignment").path("state").asText());
            assertEquals(Arrays.asList("Оплаты", "Консультация", "Бумажные документы", "Снабжение", "Работа с КА"),
                    w.typeNamesOf(w.projectByKey("ALPHA")));
            assertEquals(w.type("Снабжение").getId(), w.schemeOf(w.projectByKey("ALPHA")).defaultTypeId);
            for (JsonNode entry : result.path("issueTypeSet").path("entries")) {
                assertEquals(entry.toString(), w.type(entry.path("name").asText()).getId(), entry.path("id").asText());
            }
            assertEquals(5, result.path("report").path("created").size());
            assertEquals("7", result.path("report").path("reused").get(0).path("id").asText());
            assertEquals("COMPLETED", terminal.path("execution").path("outcome").asText());
            List<String> steps = new ArrayList<String>();
            for (JsonNode step : terminal.path("steps")) steps.add(step.path("kind").asText() + ":" + step.path("status").asText());
            assertEquals(Arrays.asList("project.created:APPLIED", "issuetype.created:APPLIED", "issuetype.created:APPLIED",
                    "issuetype.created:APPLIED", "issuetype.created:APPLIED"), steps);

            McpServer reconnected = rig.reconnect();
            assertEquals(result, operation(reconnected, terminal.path("operationId").asText()).path("result"));

            JsonNode replay = rig.apply(reconnected, "create_project", preview.path("planId").asText(), true);
            assertEquals(terminal.path("operationId"), replay.path("operationId"));
            assertEquals("COMPLETED", replay.path("status").asText());
            assertEquals(result, replay.path("result"));
            assertEquals(terminal.path("steps"), replay.path("steps"));
            assertEquals("no second project and no duplicate type", 1, rig.env.projectCreations);
            assertEquals(4, w.createCalls.size());
            assertEquals(1, w.projects.stream().filter(p -> "ALPHA".equals(p.getKey())).count());
        }
    }

    @Test
    public void partialOutcomeIsSavedWithTheProjectIdAndTypeIdsAndReconnectDoesNotCreateAgain() throws Exception {
        try (Rig rig = new Rig()) {
            ProjectTypeWorld w = rig.env.world;
            JsonNode preview = call(rig.server, "create_project", previewArgs(fiveTypes(), null));
            w.failCreationOf = "Снабжение";

            JsonNode terminal = rig.apply(rig.server, "create_project", preview.path("planId").asText());

            assertEquals("the operation finished; its domain result says what is incomplete", "COMPLETED",
                    terminal.path("status").asText());
            JsonNode result = terminal.path("result");
            assertEquals(result.toString(), "PARTIALLY_CHANGED", result.path("status").asText());
            assertTrue(result.path("projectId").isNumber());
            List<String> statuses = new ArrayList<String>();
            for (JsonNode entry : result.path("issueTypeSet").path("entries")) statuses.add(entry.path("status").asText());
            assertEquals(Arrays.asList("CREATED", "CREATED", "CREATED", "FAILED", "NOT_EXECUTED"), statuses);
            assertEquals("NOT_STARTED", result.path("issueTypeSet").path("assignment").path("state").asText());
            assertEquals(Long.valueOf(ProjectTypeWorld.DEFAULT_SCHEME), w.projectScheme.get(w.projectByKey("ALPHA").getId()));
            assertEquals(4, result.path("report").path("created").size());
            assertNotNull(w.type("Бумажные документы"));

            McpServer reconnected = rig.reconnect();
            JsonNode again = operation(reconnected, terminal.path("operationId").asText());
            assertEquals(result, again.path("result"));
            assertEquals(1, rig.env.projectCreations);
            assertEquals(Arrays.asList("Оплаты", "Консультация", "Бумажные документы", "Снабжение"), w.createCalls.subList(0, 4));
            assertEquals("reading never writes", 4, w.createCalls.size());
        }
    }

    private static com.atlassian.jira.project.template.ProjectTemplate row(String key, String type, String name) {
        com.atlassian.jira.project.template.ProjectTemplate template =
                mock(com.atlassian.jira.project.template.ProjectTemplate.class);
        org.mockito.Mockito.when(template.getKey()).thenReturn(new com.atlassian.jira.project.template.ProjectTemplateKey(key));
        org.mockito.Mockito.when(template.getProjectTypeKey())
                .thenReturn(new com.atlassian.jira.project.type.ProjectTypeKey(type));
        org.mockito.Mockito.when(template.getName()).thenReturn(name);
        org.mockito.Mockito.when(template.getDescription()).thenReturn(name + " description");
        return template;
    }

    @Test
    public void catalogIsReadThroughMcpWithoutPlanOperationOrWriteAndIsRefusedByStartOperation() throws Exception {
        try (Rig rig = new Rig()) {
            String jsm = "com.atlassian.servicedesk:itil-service-desk-project";
            List<com.atlassian.jira.project.template.ProjectTemplate> rows = Arrays.asList(
                    row(ProjectTemplates.SCRUM, "software", "Scrum"), row(jsm, "service_desk", "ITSM"),
                    row(ProjectTemplates.PROJECT_MANAGEMENT, "business", "Project management"));
            org.mockito.Mockito.when(rig.env.templates.getProjectTemplates()).thenReturn(rows);
            ObjectNode args = JSON.createObjectNode();
            args.put("action", "list_templates");

            JsonNode catalog = call(rig.server, "create_project", args);

            assertEquals(catalog.toString(), "OK", catalog.path("status").asText());
            assertEquals(3, catalog.path("templates").size());
            assertEquals("business", catalog.path("templates").get(0).path("projectTypeKey").asText());
            assertTrue(catalog.path("templates").get(0).path("supported").asBoolean());
            assertEquals("service_desk", catalog.path("templates").get(1).path("projectTypeKey").asText());
            assertFalse("JSM is listed but not supported", catalog.path("templates").get(1).path("supported").asBoolean());
            assertEquals("software", catalog.path("templates").get(2).path("projectTypeKey").asText());
            assertTrue(catalog.path("planId").isMissingNode());
            assertEquals(0, rig.env.projectCreations);

            ObjectNode start = JSON.createObjectNode();
            start.put("toolName", "create_project");
            start.set("arguments", args);
            assertTrue("a catalog read is not an applicable operation",
                    response(rig.server, "start_operation", start).path("isError").asBoolean());
            ObjectNode old = previewArgs(fiveTypes(), null);
            old.remove("action");
            old.remove("templateKey");
            assertTrue("the old create form is refused", response(rig.server, "create_project", old).path("isError").asBoolean());
            assertEquals(0, rig.env.projectCreations);
        }
    }

    @Test
    public void jsmTemplateFromTheCatalogIsRefusedByPreviewAsUnsupportedWithoutAPlanOrWrite() throws Exception {
        try (Rig rig = new Rig()) {
            String jsm = "com.atlassian.servicedesk:itil-service-desk-project";
            java.util.Optional<com.atlassian.jira.project.template.ProjectTemplate> found =
                    java.util.Optional.of(row(jsm, "service_desk", "ITSM"));
            org.mockito.Mockito.when(rig.env.templates.getProjectTemplate(
                    new com.atlassian.jira.project.template.ProjectTemplateKey(jsm))).thenReturn(found);
            ObjectNode args = previewArgs(fiveTypes(), null);
            args.put("templateKey", jsm);

            JsonNode refused = response(rig.server, "create_project", args);

            assertTrue(refused.toString(), refused.path("isError").asBoolean());
            JsonNode body = JSON.readTree(refused.path("content").get(0).path("text").asText());
            assertEquals("UNSUPPORTED_TEMPLATE", body.path("errorCode").asText());
            assertTrue(body.path("planId").isMissingNode() || body.path("planId").isNull());
            assertEquals(0, rig.env.projectCreations);
        }
    }

    private static ArrayNode fiveTypes() {
        ArrayNode types = JSON.createArrayNode();
        for (String[] item : new String[][] {{"pay", "Оплаты"}, {"consult", "Консультация"}, {"paper", "Бумажные документы"},
                {"supply", "Снабжение"}, {"ka", "Работа с КА"}}) {
            ObjectNode definition = types.addObject();
            definition.put("key", item[0]);
            definition.put("name", item[1]);
            definition.put("subtask", false);
        }
        return types;
    }

    private static ObjectNode previewArgs(ArrayNode definitions, String defaultKey) {
        ObjectNode args = JSON.createObjectNode();
        args.put("action", "create");
        args.put("templateKey", ProjectTemplates.PROJECT_MANAGEMENT);
        args.put("dryRun", true);
        args.put("name", "Alpha");
        args.put("key", "ALPHA");
        args.put("lead", "admin");
        args.put("newIssueTypes", definitions);
        if (defaultKey != null) args.put("defaultIssueType", defaultKey);
        return args;
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
}
