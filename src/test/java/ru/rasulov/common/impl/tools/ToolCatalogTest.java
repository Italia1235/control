package ru.rasulov.common.impl.tools;

import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class ToolCatalogTest {

    private final PluginAccessor pluginAccessor = mock(PluginAccessor.class);
    private ToolCatalog catalog;

    @Before
    public void setUp() {
        catalog = new ToolCatalog(pluginAccessor);
    }

    @Test
    public void everyToolHasTheMcpShape() {
        for (JsonNode tool : catalog.mcpTools()) {
            String name = tool.get("name").textValue();
            assertNotNull(name + " has no description", tool.get("description"));
            assertEquals("object", tool.get("inputSchema").get("type").textValue());
            assertNull("MCP calls it inputSchema", tool.get("input_schema"));
            JsonNode annotations = tool.get("annotations");
            assertTrue(name + " does not say whether it only reads",
                    annotations.get("readOnlyHint").isBoolean());
            assertNotNull(annotations.get("title"));
        }
    }



    @Test
    public void frozenJournalToolsAreNotPublishedButBackgroundOperationsRemainAvailable() {
        for (String name : Arrays.asList("rollback_changes", "cancel_operation", "create_change_session",
                "list_change_sessions", "begin_change_set", "finish_change_set", "list_session_changes")) {
            assertFalse(name, catalog.isPublished(name));
        }
        assertTrue(catalog.isPublished("start_operation"));
        assertTrue(catalog.isPublished("get_operation"));
        assertFalse(catalog.isDomainMutation("start_operation"));
        assertFalse(catalog.isDomainMutation("get_operation"));
    }

    @Test
    public void itssMappingsToolIsPublishedAsADestructiveDomainMutationWithItsSchema() {
        assertTrue(catalog.isPublished("set_issue_type_screen_scheme_mappings"));
        assertTrue(catalog.isDomainMutation("set_issue_type_screen_scheme_mappings"));
        assertFalse("the tool has a client-facing title, not its bare name",
                "set_issue_type_screen_scheme_mappings".equals(catalog.title("set_issue_type_screen_scheme_mappings")));
    }

    @Test
    public void screenSchemeOperationsToolIsPublishedAsADestructiveDomainMutationWithATitle() {
        assertTrue(catalog.isPublished("set_screen_scheme_operations"));
        assertTrue(catalog.isDomainMutation("set_screen_scheme_operations"));
        assertFalse("the tool has a client-facing title, not its bare name",
                "set_screen_scheme_operations".equals(catalog.title("set_screen_scheme_operations")));
    }

    @Test
    public void issueTypeScreenSchemeAssignmentToolIsPublishedAsADestructiveDomainMutationWithATitle() {
        assertTrue(catalog.isPublished("assign_issue_type_screen_scheme"));
        assertTrue(catalog.isDomainMutation("assign_issue_type_screen_scheme"));
        assertFalse("the tool has a client-facing title, not its bare name",
                "assign_issue_type_screen_scheme".equals(catalog.title("assign_issue_type_screen_scheme")));
    }

    @Test
    public void operationStartDoesNotRequireClientSessionOrGroup() {
        for (JsonNode tool : catalog.mcpTools()) {
            if (!"start_operation".equals(tool.path("name").asText())) continue;
            JsonNode schema = tool.get("inputSchema");
            assertTrue(containsText(schema.get("required"), "toolName"));
            assertTrue(containsText(schema.get("required"), "arguments"));
            assertFalse(containsText(schema.get("required"), "sessionId"));
            assertFalse(containsText(schema.get("required"), "changeSetId"));
            assertTrue(containsText(schema.path("dependencies").get("sessionId"), "changeSetId"));
            assertTrue(containsText(schema.path("dependencies").get("changeSetId"), "sessionId"));
            return;
        }
        throw new AssertionError("start_operation is missing");
    }

    @Test
    public void operationWaitIsOptionalAndBounded() {
        for (JsonNode tool : catalog.mcpTools()) {
            if (!"get_operation".equals(tool.path("name").asText())) continue;
            JsonNode schema = tool.get("inputSchema");
            JsonNode wait = schema.path("properties").path("waitSeconds");
            assertEquals("integer", wait.path("type").asText());
            assertEquals(0, wait.path("minimum").asInt());
            assertEquals(20, wait.path("maximum").asInt());
            assertFalse(containsText(schema.get("required"), "waitSeconds"));
            assertTrue(containsText(schema.get("required"), "operationId"));
            return;
        }
        throw new AssertionError("get_operation is missing");
    }



    private static boolean containsText(JsonNode array, String expected) {
        if (array == null) return false;
        for (JsonNode value : array) if (expected.equals(value.textValue())) return true;
        return false;
    }

}
