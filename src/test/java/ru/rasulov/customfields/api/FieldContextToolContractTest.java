package ru.rasulov.customfields.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.customfields.impl.CustomFieldTools;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class FieldContextToolContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void exposesFieldAndOptionalProjectContextAndOptionsAsReadOnly() throws Exception {
        JsonNode tool = null;
        try (InputStream input = getClass().getResourceAsStream("/llm/customfields-tools.json")) {
            for (JsonNode row : JSON.readTree(input)) {
                if ("get_field_contexts".equals(row.path("name").asText())) {
                    tool = row;
                }
            }
        }
        assertNotNull("get_field_contexts schema", tool);
        assertTrue(tool.path("annotations").path("readOnlyHint").asBoolean());
        JsonNode schema = tool.path("input_schema");
        Set<String> properties = new TreeSet<>();
        schema.path("properties").fieldNames().forEachRemaining(properties::add);
        assertEquals(new TreeSet<>(Arrays.asList("field", "project", "contextId", "includeOptions")), properties);
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals(1, schema.path("required").size());
        assertEquals("field", schema.path("required").get(0).asText());
        assertEquals("string", schema.path("properties").path("field").path("type").asText());
        assertEquals("string", schema.path("properties").path("project").path("type").asText());
        assertEquals("integer", schema.path("properties").path("contextId").path("type").asText());
        assertEquals(1, schema.path("properties").path("contextId").path("minimum").asInt());
        assertEquals("boolean", schema.path("properties").path("includeOptions").path("type").asText());
        // contextId stays optional in the schema: the dependency of includeOptions on it is text plus the service
        assertFalse(schema.has("dependencies") || schema.has("dependentRequired") || schema.has("allOf")
                || schema.has("anyOf") || schema.has("oneOf") || schema.has("if"));
    }

    @Test
    public void refusesMutationArgumentsAndInvalidSelectorsBeforeCallingReader() throws Exception {
        FieldContextReader reader = mock(FieldContextReader.class);
        ToolCall tool = new CustomFieldTools(mock(CustomFieldCreator.class), mock(FieldScreenService.class),
                mock(FieldRequirementService.class), mock(FieldConfigurationCloneService.class),
                mock(AssetsFieldConfiguration.class), mock(AdministratorGuard.class), reader,
                mock(FieldContextScopeService.class), mock(ru.rasulov.customfields.api.FieldContextCreator.class))
                .tools().get("get_field_contexts");
        assertNotNull(tool);
        for (String arguments : Arrays.asList("{}", "{\"field\":123}", "{\"field\":\"x\",\"dryRun\":true}",
                "{\"field\":\"x\",\"contextId\":1.5}", "{\"field\":\"x\",\"contextId\":\"1\"}",
                "{\"field\":\"x\",\"contextId\":0}", "{\"field\":\"x\",\"contextId\":-1}",
                "{\"field\":\"x\",\"contextId\":9223372036854775808}",
                "{\"field\":\"x\",\"project\":123}", "{\"field\":\"x\",\"project\":null}",
                "{\"field\":\"x\",\"project\":[]}")) {
            try {
                tool.call(JSON.readTree(arguments));
                fail("Accepted invalid read request: " + arguments);
            } catch (InvalidRequestException expected) {
                verifyNoInteractions(reader);
            }
        }
    }

    @Test
    public void refusesNonBooleanIncludeOptionsBeforeCallingReader() throws Exception {
        FieldContextReader reader = mock(FieldContextReader.class);
        ToolCall tool = new CustomFieldTools(mock(CustomFieldCreator.class), mock(FieldScreenService.class),
                mock(FieldRequirementService.class), mock(FieldConfigurationCloneService.class),
                mock(AssetsFieldConfiguration.class), mock(AdministratorGuard.class), reader,
                mock(FieldContextScopeService.class), mock(ru.rasulov.customfields.api.FieldContextCreator.class))
                .tools().get("get_field_contexts");
        for (String arguments : Arrays.asList("{\"field\":\"x\",\"contextId\":5,\"includeOptions\":\"yes\"}",
                "{\"field\":\"x\",\"contextId\":5,\"includeOptions\":1}",
                "{\"field\":\"x\",\"contextId\":5,\"includeOptions\":null}")) {
            try {
                tool.call(JSON.readTree(arguments));
                fail("Accepted non-boolean includeOptions: " + arguments);
            } catch (InvalidRequestException expected) {
                verifyNoInteractions(reader);
            }
        }
    }

    @Test
    public void updateFieldContextsExposesOptionChangesWithoutDeletion() throws Exception {
        JsonNode update = catalogTool("update_field_contexts");
        JsonNode options = update.path("input_schema").path("properties").path("contexts").path("items")
                .path("properties").path("options");
        assertEquals("object", options.path("type").asText());
        assertFalse(options.path("additionalProperties").asBoolean(true));
        assertEquals(1, options.path("minProperties").asInt());
        Set<String> properties = new TreeSet<>();
        options.path("properties").fieldNames().forEachRemaining(properties::add);
        assertEquals(new TreeSet<>(Arrays.asList("add", "rename", "disable", "enable", "order")), properties);
        JsonNode add = options.path("properties").path("add");
        assertEquals(Limits.MAX_OPTIONS_PER_CONTEXT, add.path("maxItems").asInt());
        assertEquals(Limits.MAX_OPTION_LENGTH, add.path("items").path("properties").path("value").path("maxLength").asInt());
        assertEquals(1, add.path("items").path("properties").path("value").path("minLength").asInt());
        assertEquals("value", add.path("items").path("required").get(0).asText());
        assertFalse(add.path("items").path("additionalProperties").asBoolean(true));
        assertEquals("integer", add.path("items").path("properties").path("parentOptionId").path("type").asText());
        JsonNode rename = options.path("properties").path("rename").path("items");
        assertEquals(2, rename.path("required").size());
        assertEquals("integer", options.path("properties").path("disable").path("items").path("type").asText());
        assertEquals("integer", options.path("properties").path("enable").path("items").path("type").asText());
        JsonNode order = options.path("properties").path("order").path("items");
        assertEquals("optionIds", order.path("required").get(0).asText());
        // a level above 100 options must be orderable through the schema: order.optionIds takes up to the per-call cap
        assertEquals(Limits.MAX_OPTIONS_PER_CALL, order.path("properties").path("optionIds").path("maxItems").asInt());
    }

    @Test
    public void updateFieldContextsAcceptsOptionsOnlyEntriesAndOptionsWithAxesOrAssets() throws Exception {
        JsonNode item = catalogTool("update_field_contexts").path("input_schema").path("properties").path("contexts")
                .path("items");
        String options = "\"options\":{\"disable\":[7]}";
        String assets = "\"assets\":{\"objectSchemaId\":7}";
        assertTrue(valid(item, JSON.readTree("{\"contextId\":1," + options + "}")));
        assertTrue(valid(item, JSON.readTree("{\"contextId\":1,\"allProjects\":true,\"allIssueTypes\":true," + options + "}")));
        assertTrue(valid(item, JSON.readTree("{\"contextId\":1,\"allProjects\":true,\"issueTypes\":[\"Task\"]," + options + "}")));
        assertTrue(valid(item, JSON.readTree("{\"contextId\":1," + options + "," + assets + "}")));
        assertFalse(valid(item, JSON.readTree("{\"contextId\":1}")));
        assertFalse(valid(item, JSON.readTree("{\"contextId\":1,\"allProjects\":true," + options + "}")));
        assertFalse(valid(item, JSON.readTree("{\"contextId\":1,\"issueTypes\":[\"Task\"]," + options + "}")));
    }


    private static JsonNode catalogTool(String name) throws Exception {
        try (InputStream input = FieldContextToolContractTest.class.getResourceAsStream("/llm/customfields-tools.json")) {
            for (JsonNode row : JSON.readTree(input)) if (name.equals(row.path("name").asText())) return row;
        }
        throw new AssertionError("No tool " + name);
    }

    @Test
    public void createFieldContextRequiresBothAxesKeepsLimitsAndIsPreviewOnly() throws Exception {
        JsonNode tool = catalogTool("create_field_context");
        assertFalse(tool.path("annotations").path("readOnlyHint").asBoolean(true));
        JsonNode schema = tool.path("input_schema");
        Set<String> properties = new TreeSet<>();
        schema.path("properties").fieldNames().forEachRemaining(properties::add);
        assertEquals(new TreeSet<>(Arrays.asList("dryRun", "fieldId", "name", "description", "allProjects", "projects",
                "allIssueTypes", "issueTypes", "options", "assets")), properties);
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        Set<String> required = new TreeSet<>();
        for (JsonNode name : schema.path("required")) required.add(name.asText());
        assertEquals(new TreeSet<>(Arrays.asList("dryRun", "fieldId", "name")), required);
        assertEquals(1, schema.path("properties").path("dryRun").path("enum").size());
        assertTrue(schema.path("properties").path("dryRun").path("enum").get(0).asBoolean());
        assertEquals(Limits.MAX_NAME_LENGTH, schema.path("properties").path("name").path("maxLength").asInt());
        assertEquals(Limits.MAX_CONTEXT_DESCRIPTION_LENGTH,
                schema.path("properties").path("description").path("maxLength").asInt());
        assertEquals(Limits.MAX_PROJECTS_PER_CONTEXT, schema.path("properties").path("projects").path("maxItems").asInt());
        assertEquals(Limits.MAX_ISSUE_TYPES_PER_CONTEXT,
                schema.path("properties").path("issueTypes").path("maxItems").asInt());
        assertEquals(Limits.MAX_ROOT_OPTIONS, schema.path("properties").path("options").path("maxItems").asInt());
        // one of each axis form must be present: neither a missing axis nor an implicit global context validates
        assertEquals(2, schema.path("allOf").size());
        for (JsonNode axis : schema.path("allOf")) assertEquals(2, axis.path("oneOf").size());
    }

    @Test
    public void assetsSettingsHaveTheSameShapeInCreateAndUpdateContextTools() throws Exception {
        JsonNode create = catalogTool("create_field_context").path("input_schema").path("properties").path("assets");
        JsonNode update = catalogTool("update_field_contexts").path("input_schema").path("properties").path("contexts")
                .path("items").path("properties").path("assets");
        for (JsonNode assets : Arrays.asList(create, update)) {
            assertEquals("object", assets.path("type").asText());
            Set<String> properties = new TreeSet<>();
            assets.path("properties").fieldNames().forEachRemaining(properties::add);
            assertEquals(new TreeSet<>(Arrays.asList("objectSchemaId", "filterScopeAql")), properties);
            assertEquals(1, assets.path("required").size());
            assertEquals("objectSchemaId", assets.path("required").get(0).asText());
            assertEquals("integer", assets.path("properties").path("objectSchemaId").path("type").asText());
            assertEquals(1, assets.path("properties").path("objectSchemaId").path("minimum").asInt());
            assertEquals("string", assets.path("properties").path("filterScopeAql").path("type").asText());
            assertFalse(assets.path("additionalProperties").asBoolean(true));
        }
    }

    @Test
    public void updateFieldContextsAcceptsAssetsOnlyEntriesButOtherwiseStillRequiresBothAxes() throws Exception {
        JsonNode item = catalogTool("update_field_contexts").path("input_schema").path("properties").path("contexts")
                .path("items");
        String assets = "\"assets\":{\"objectSchemaId\":7,\"filterScopeAql\":\"objectType = \\\"Host\\\"\"}";
        assertTrue(valid(item, JSON.readTree("{\"contextId\":1," + assets + "}")));
        assertTrue(valid(item, JSON.readTree("{\"contextId\":1,\"allProjects\":true,\"allIssueTypes\":true}")));
        assertTrue(valid(item, JSON.readTree(
                "{\"contextId\":1,\"allProjects\":true,\"issueTypes\":[\"Task\"]," + assets + "}")));
        assertFalse(valid(item, JSON.readTree("{\"contextId\":1}")));
        assertFalse(valid(item, JSON.readTree("{\"contextId\":1,\"allProjects\":true}")));
        // assets never completes a half-given scope: the missing axis must still be named
        assertFalse(valid(item, JSON.readTree("{\"contextId\":1,\"allProjects\":true," + assets + "}")));
        assertFalse(valid(item, JSON.readTree("{\"contextId\":1,\"issueTypes\":[\"Task\"]," + assets + "}")));
    }

    /** The structural keywords the catalog uses to say which properties must be present; nothing else is evaluated. */
    private static boolean valid(JsonNode schema, JsonNode value) {
        for (JsonNode name : schema.path("required")) if (!value.has(name.asText())) return false;
        for (JsonNode sub : schema.path("allOf")) if (!valid(sub, value)) return false;
        if (schema.has("anyOf")) {
            boolean any = false;
            for (JsonNode sub : schema.get("anyOf")) any |= valid(sub, value);
            if (!any) return false;
        }
        if (schema.has("oneOf")) {
            int matching = 0;
            for (JsonNode sub : schema.get("oneOf")) if (valid(sub, value)) matching++;
            if (matching != 1) return false;
        }
        if (schema.has("not") && valid(schema.get("not"), value)) return false;
        if (schema.has("if")) {
            JsonNode branch = valid(schema.get("if"), value) ? schema.path("then") : schema.path("else");
            if (!branch.isMissingNode() && !valid(branch, value)) return false;
        }
        return true;
    }

    @Test
    public void createFieldContextRefusesUnknownArgumentsBeforeTheService() throws Exception {
        FieldContextCreator creator = mock(FieldContextCreator.class);
        ToolCall tool = new CustomFieldTools(mock(CustomFieldCreator.class), mock(FieldScreenService.class),
                mock(FieldRequirementService.class), mock(FieldConfigurationCloneService.class),
                mock(AssetsFieldConfiguration.class), mock(AdministratorGuard.class), mock(FieldContextReader.class),
                mock(FieldContextScopeService.class), creator).tools().get("create_field_context");
        assertNotNull(tool);
        try {
            tool.call(JSON.readTree("{\"dryRun\":true,\"fieldId\":\"customfield_1\",\"name\":\"x\",\"bogus\":1}"));
            fail("Accepted an unknown argument");
        } catch (InvalidRequestException expected) {
            verifyNoInteractions(creator);
        }
    }
}
