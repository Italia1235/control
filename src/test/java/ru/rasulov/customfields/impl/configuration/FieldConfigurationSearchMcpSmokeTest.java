package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.Invocation;
import ru.rasulov.common.impl.tools.McpServer;
import ru.rasulov.common.impl.tools.ToolCatalog;
import ru.rasulov.common.impl.tools.ToolRegistry;
import ru.rasulov.customfields.api.FieldConfigurationCreator;
import ru.rasulov.customfields.api.FieldConfigurationSchemeAssigner;
import ru.rasulov.customfields.impl.FieldJournalTestSupport;
import ru.rasulov.customfields.impl.context.FieldSelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * The public path (MCP tools/call -> ToolRegistry -> FieldConfigurationTools -> real reader and searcher) over a fake
 * FieldLayoutManager: both modes of get_field_configurations, the refused mixes, and the removed old tool name.
 */
public class FieldConfigurationSearchMcpSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private FieldLayoutFake jira;
    private McpServer server;

    @Before
    public void setUp() {
        jira = new FieldLayoutFake();
        ProjectManager projectManager = mock(ProjectManager.class);
        when(projectManager.getProjects()).thenAnswer(call -> new ArrayList<Project>(jira.allProjects));
        FieldConfigurationSearcher searcher = new FieldConfigurationSearcher(jira.guard, jira.catalog, jira.manager,
                projectManager);
        FieldConfigurationReaderImpl reader = new FieldConfigurationReaderImpl(jira.guard, jira.projects,
                new ConfigurableFieldResolver(new FieldSelector(mock(com.atlassian.jira.issue.CustomFieldManager.class)),
                        mock(com.atlassian.jira.issue.fields.FieldManager.class)),
                jira.catalog, jira.manager, searcher);
        FieldConfigurationTools tools = new FieldConfigurationTools(reader, mock(FieldConfigurationCreator.class),
                mock(FieldConfigurationSchemeAssigner.class));
        ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
        server = new McpServer(jira.guard, catalog, new ToolRegistry(Collections.singletonList(tools)));
    }

    @After
    public void close() { FieldJournalTestSupport.close(); }

    @Test
    public void bothModesWorkThroughTheServerAndNothingIsWritten() throws Exception {
        IssueType task = jira.issueType("1", "Task");
        IssueType bug = jira.issueType("2", "Bug");
        jira.layout(10L, "PAY Task fields");
        jira.layout(11L, "Left behind");
        jira.scheme(20L, "PAY scheme", null);
        jira.rows.get(20L).put(null, null);
        jira.rows.get(20L).put("1", 10L);
        Project pay = jira.project(1, "PAY", task, bug);
        jira.associations.put(pay.getId(), 20L);

        // mode 1: the project read keeps its shape
        ObjectNode projectArgs = JSON.createObjectNode();
        projectArgs.put("project", "PAY");
        JsonNode project = call("get_field_configurations", projectArgs);
        assertEquals(project.toString(), "OK", project.path("status").asText());
        assertEquals(20, project.path("scheme").path("id").asLong());
        assertEquals(2, project.path("mappings").size());
        assertEquals("Bug", project.path("mappings").get(0).path("issueTypeName").asText());
        assertFalse(project.path("mappings").get(0).path("explicit").asBoolean());
        assertEquals(10, project.path("mappings").get(1).path("fieldConfiguration").path("id").asLong());

        // mode 2: a standalone configuration found by name, its usage verified empty
        ObjectNode byName = JSON.createObjectNode();
        byName.put("kind", "CONFIGURATION");
        byName.put("name", "left");
        JsonNode found = call("get_field_configurations", byName);
        assertEquals(found.toString(), "OK", found.path("status").asText());
        assertEquals(1, found.path("total").asInt());
        assertTrue("no next page", found.path("nextOffset").isMissingNode() || found.path("nextOffset").isNull());
        assertTrue(found.path("usageComplete").asBoolean());
        JsonNode item = found.path("items").get(0);
        assertEquals(11, item.path("fieldConfiguration").path("id").asLong());
        assertEquals("READ", item.path("usage").path("status").asText());
        assertEquals(0, item.path("usage").path("total").asInt());
        assertFalse(item.path("usage").path("truncated").asBoolean());

        // mode 2: a scheme by id with its project
        ObjectNode byId = JSON.createObjectNode();
        byId.put("kind", "SCHEME");
        byId.put("id", "20");
        JsonNode scheme = call("get_field_configurations", byId).path("items").get(0);
        assertEquals("PAY scheme", scheme.path("scheme").path("name").asText());
        assertEquals("PAY", scheme.path("usage").path("projects").get(0).path("key").asText());

        for (Invocation invocation : mockingDetails(jira.manager).getInvocations()) {
            String method = invocation.getMethod().getName();
            assertTrue("read-only calls only, saw " + method, method.startsWith("get") || method.equals("refresh"));
        }
    }

    @Test
    public void mixedAmbiguousAndMalformedRequestsAreRefusedBeforeAnyJiraRead() throws Exception {
        String[] refused = {
                "{}",
                "{\"project\":\"PAY\",\"kind\":\"SCHEME\",\"id\":\"1\"}",
                "{\"project\":\"PAY\",\"name\":\"x\"}",
                "{\"fields\":[\"duedate\"],\"kind\":\"SCHEME\",\"id\":\"1\"}",
                "{\"kind\":\"SCHEME\"}",
                "{\"kind\":\"SCHEME\",\"id\":\"1\",\"name\":\"x\"}",
                "{\"kind\":\"OTHER\",\"id\":\"1\"}",
                "{\"kind\":\"SCHEME\",\"id\":\"abc\"}",
                "{\"kind\":\"SCHEME\",\"id\":\"0\"}",
                "{\"kind\":\"SCHEME\",\"id\":\"1\",\"limit\":5}",
                "{\"kind\":\"SCHEME\",\"name\":\"  \"}",
                "{\"kind\":\"SCHEME\",\"name\":\"x\",\"limit\":51}",
                "{\"kind\":\"SCHEME\",\"name\":\"x\",\"offset\":-1}",
                "{\"kind\":\"SCHEME\",\"name\":\"x\",\"usageOffset\":0}",
                "{\"kind\":\"SCHEME\",\"id\":\"1\",\"usageLimit\":101}",
                "{\"kind\":\"SCHEME\",\"id\":\"1\",\"usageOffset\":-1}",
                "{\"project\":\"PAY\",\"usageLimit\":5}",
                "{\"kind\":\"SCHEME\",\"name\":\"x\",\"unknown\":1}"};
        for (String arguments : refused) {
            JsonNode result = rawCall("get_field_configurations", JSON.readTree(arguments));
            assertTrue(arguments + " -> " + result, result.path("isError").asBoolean());
        }
        assertTrue("nothing was read", mockingDetails(jira.manager).getInvocations().isEmpty());
    }

    @Test
    public void theRemovedToolNameIsNotPublished() throws Exception {
        JsonNode result = rawCall("get_project_field_configurations", JSON.readTree("{\"project\":\"PAY\"}"));
        assertTrue(result.toString(), result.path("isError").asBoolean() || result.has("error"));

        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/list");
        JsonNode tools = JSON.readTree(server.handle(request.toString())).path("result").path("tools");
        boolean published = false;
        for (Iterator<JsonNode> it = tools.elements(); it.hasNext(); ) {
            String name = it.next().path("name").asText();
            assertFalse(name, name.equals("get_project_field_configurations"));
            published |= name.equals("get_field_configurations");
        }
        assertTrue("the replacement is listed", published);
    }

    private JsonNode call(String tool, JsonNode args) throws Exception {
        JsonNode result = rawCall(tool, args);
        assertFalse(result.toString(), result.path("isError").asBoolean());
        return JSON.readTree(result.path("content").get(0).path("text").asText());
    }

    private JsonNode rawCall(String tool, JsonNode args) throws Exception {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.put("arguments", args);
        JsonNode response = JSON.readTree(server.handle(request.toString()));
        return response.has("error") ? response : response.path("result");
    }
}
