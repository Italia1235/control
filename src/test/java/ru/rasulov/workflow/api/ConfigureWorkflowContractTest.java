package ru.rasulov.workflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.*;

/** Public contract of the single workflow configuration operation. */
public class ConfigureWorkflowContractTest {
    @Test
    public void oneClosedPreviewContractReplacesThreeMutationTools() throws Exception {
        Set<String> names = new HashSet<>();
        JsonNode configure = null;
        try (InputStream input = getClass().getResourceAsStream("/llm/workflow-tools.json")) {
            assertNotNull(input);
            for (JsonNode tool : new ObjectMapper().readTree(input)) {
                String name = tool.path("name").asText();
                names.add(name);
                if ("configure_workflow".equals(name)) configure = tool;
            }
        }
        assertNotNull("one configure_workflow preview must support the complete domain change", configure);
        assertFalse(names.contains("create_workflow"));
        assertFalse(names.contains("configure_workflow_validators"));
        assertFalse(names.contains("configure_workflow_conditions"));
        assertFalse(names.contains("edit_workflow"));
        JsonNode schema = configure.path("input_schema");
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(true, schema.path("properties").path("dryRun").path("enum").get(0).asBoolean());
        assertEquals(1, schema.path("properties").path("dryRun").path("enum").size());
        assertTrue(schema.path("properties").has("create"));
        assertTrue(schema.path("properties").has("existing"));
        assertFalse(schema.path("properties").has("planId"));
        assertEquals("object", schema.path("type").asText());
        assertEquals(strings("dryRun"), strings(schema.path("required")));
        assertEquals(strings("dryRun", "create", "existing", "newScreens", "transitionRules"), names(schema.path("properties")));
        assertTrue("the target choice is stated in words, not by a combinator",
                schema.path("properties").path("create").path("description").asText().contains("existing"));
        assertTrue(schema.path("properties").path("existing").path("description").asText().contains("create"));
    }

    /** The whole input schema is flat for the client: no references and no combinators. */
    @Test
    public void inputSchemaHasNoReferencesCombinatorsOrPatterns() throws Exception {
        List<String> violations = new ArrayList<>();
        collectForbidden(schema(), "$", violations);
        assertTrue("forbidden schema keywords: " + violations, violations.isEmpty());
    }

    /** Every object in the schema, at any depth, rejects unknown keys. */
    @Test
    public void everyObjectInTheInputSchemaIsClosed() throws Exception {
        List<String> open = new ArrayList<>();
        collectOpenObjects(schema(), "$", open);
        assertTrue("objects without additionalProperties:false: " + open, open.isEmpty());
    }

    /** Flattening keeps the simple limits and the required lists of the former objects. */
    @Test
    public void simpleLimitsAndRequiredListsAreKept() throws Exception {
        JsonNode properties = schema().path("properties");
        JsonNode create = properties.path("create");
        assertEquals(strings("name", "statuses", "initialStatus", "transitions"), strings(create.path("required")));
        assertEquals(255, create.path("properties").path("name").path("maxLength").asInt());
        assertEquals(1, create.path("properties").path("name").path("minLength").asInt());
        JsonNode statuses = create.path("properties").path("statuses");
        assertEquals(2, statuses.path("minItems").asInt());
        assertEquals(30, statuses.path("maxItems").asInt());
        assertEquals(strings("name"), strings(statuses.path("items").path("required")));
        assertEquals(strings("TO_DO", "IN_PROGRESS", "DONE"),
                strings(statuses.path("items").path("properties").path("category").path("enum")));
        JsonNode transitions = create.path("properties").path("transitions");
        assertEquals(1, transitions.path("minItems").asInt());
        assertEquals(100, transitions.path("maxItems").asInt());
        assertEquals(strings("name", "to", "key"), strings(transitions.path("items").path("required")));
        JsonNode existing = properties.path("existing");
        assertEquals(strings("workflow", "version", "expectedRevision"), strings(existing.path("required")));
        assertEquals(strings("ACTIVE", "DRAFT"), strings(existing.path("properties").path("version").path("enum")));
        assertEquals(strings("workflow", "version", "expectedRevision", "changes"), names(existing.path("properties")));
        JsonNode changes = existing.path("properties").path("changes");
        assertEquals(1, changes.path("minItems").asInt());
        assertEquals(100, changes.path("maxItems").asInt());
        assertEquals(strings("op"), strings(changes.path("items").path("required")));
        JsonNode change = changes.path("items").path("properties");
        assertEquals(strings("ADD_STATUS", "REPLACE_STATUS", "UPDATE_TRANSITION", "ADD_TRANSITION",
                "REMOVE_TRANSITION", "SET_INITIAL_STATUS", "UPDATE_METADATA", "REMOVE_STATUS"),
                strings(change.path("op").path("enum")));
        assertEquals(1, change.path("transitionKey").path("minLength").asInt());
        assertEquals(255, change.path("transitionKey").path("maxLength").asInt());
        assertTrue(change.path("transitionKey").path("description").asText().contains("ADD_TRANSITION"));
        assertEquals(strings("SET", "CLEAR", "PRESERVE"),
                strings(change.path("resolution").path("properties").path("action").path("enum")));
        assertFalse(create.path("properties").has("changes"));
        JsonNode newScreens = properties.path("newScreens");
        assertEquals(10, newScreens.path("maxItems").asInt());
        assertEquals(strings("key", "name", "fieldIds"), strings(newScreens.path("items").path("required")));
        JsonNode fieldIds = newScreens.path("items").path("properties").path("fieldIds");
        assertEquals(50, fieldIds.path("maxItems").asInt());
        assertTrue(fieldIds.path("uniqueItems").asBoolean());
        assertEquals(100, properties.path("transitionRules").path("maxItems").asInt());
    }

    /** Transition source and resolution are properties whose descriptions carry the old oneOf rules. */
    @Test
    public void transitionSourceAndResolutionAreDescribedByProperties() throws Exception {
        JsonNode transition = schema().path("properties").path("create").path("properties").path("transitions")
                .path("items").path("properties");
        assertEquals("string", transition.path("from").path("type").asText());
        assertEquals("boolean", transition.path("fromAny").path("type").asText());
        assertTrue(transition.path("from").path("description").asText().contains("fromAny"));
        assertTrue(transition.path("fromAny").path("description").asText().contains("from"));
        JsonNode resolution = transition.path("resolution");
        assertEquals(strings("object", "null"), strings(resolution.path("type")));
        assertEquals(strings("action", "value"), names(resolution.path("properties")));
        assertEquals(strings("action"), strings(resolution.path("required")));
        assertEquals(strings("SET", "CLEAR"), strings(resolution.path("properties").path("action").path("enum")));
        String value = resolution.path("properties").path("value").path("description").asText();
        assertTrue(value, value.contains("SET") && value.contains("CLEAR"));
    }

    /** One rule addresses one transition; the key kind depends on the target branch. */
    @Test
    public void transitionRuleAddressingAndScreenAreDescribedByProperties() throws Exception {
        JsonNode rule = schema().path("properties").path("transitionRules").path("items");
        assertEquals(strings("transitionKey", "actionId", "screen", "addValidators", "addConditions"),
                names(rule.path("properties")));
        String key = rule.path("properties").path("transitionKey").path("description").asText();
        assertTrue(key, key.contains("create") && key.contains("actionId") && key.contains("ADD_TRANSITION"));
        String action = rule.path("properties").path("actionId").path("description").asText();
        assertTrue(action, action.contains("existing") && action.contains("transitionKey"));
        assertEquals(1, rule.path("properties").path("actionId").path("minimum").asInt());
        JsonNode screen = rule.path("properties").path("screen");
        assertEquals(strings("string", "object"), strings(screen.path("type")));
        assertEquals(strings("action", "screenId", "screenName", "screenKey"), names(screen.path("properties")));
        assertEquals(strings("SET", "CLEAR"), strings(screen.path("properties").path("action").path("enum")));
        assertEquals(strings("action"), strings(screen.path("required")));
        String screenText = screen.path("description").asText();
        for (String word : new String[]{"KEEP", "SET", "screenId", "screenName", "screenKey"}) {
            assertTrue(word + " in " + screenText, screenText.contains(word));
        }
        assertTrue(screenText, screenText.contains("exactly one"));
    }

    /** Validators are one object type; the template decides which parameters are allowed. */
    @Test
    public void validatorFormsAreDescribedByTemplateAndParameters() throws Exception {
        JsonNode validators = schema().path("properties").path("transitionRules").path("items").path("properties")
                .path("addValidators");
        JsonNode item = validators.path("items");
        assertEquals("object", item.path("type").asText());
        assertEquals(strings("template", "parameters"), names(item.path("properties")));
        assertEquals(strings("template"), strings(item.path("required")));
        assertEquals(strings("comment_required", "required_fields"),
                strings(item.path("properties").path("template").path("enum")));
        JsonNode parameters = item.path("properties").path("parameters");
        assertEquals(strings("fieldIds"), names(parameters.path("properties")));
        String text = parameters.path("description").asText();
        assertTrue(text, text.contains("required_fields") && text.contains("comment_required")
                && text.contains("fieldIds"));
        String template = item.path("properties").path("template").path("description").asText();
        assertTrue(template, template.contains("required_fields") && template.contains("parameters"));
    }

    /** Condition rules are one object type; the template decides which parameters are allowed. */
    @Test
    public void conditionFormsAreDescribedByOperatorTemplateAndParameters() throws Exception {
        JsonNode conditions = schema().path("properties").path("transitionRules").path("items").path("properties")
                .path("addConditions");
        assertEquals(strings("AND"), strings(conditions.path("properties").path("operator").path("enum")));
        JsonNode item = conditions.path("properties").path("rules").path("items");
        assertEquals(strings("template", "parameters"), strings(item.path("required")));
        assertEquals(strings("group", "project_role"), strings(item.path("properties").path("template").path("enum")));
        JsonNode parameters = item.path("properties").path("parameters");
        assertEquals(strings("group", "roles", "invert"), names(parameters.path("properties")));
        String text = parameters.path("description").asText();
        assertTrue(text, text.contains("group") && text.contains("project_role") && text.contains("roles"));
        assertEquals(strings("string", "integer"),
                strings(parameters.path("properties").path("roles").path("items").path("type")));
    }

    @Test
    public void structuralChangesParserDistinguishesOmissionFromInvalidBlocks() throws Exception {
        ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest absent = parseExisting("");
        assertNull(absent.existing().changes());
        for (String changes : new String[]{"null", "[]", "{}", "\"wrong\"", "[null]",
                "[{\"op\":\"UPDATE_METADATA\"},null]"}) {
            assertInvalidExisting("\"changes\":" + changes, "existing.changes");
        }
        String oversized = "[" + String.join(",", java.util.Collections.nCopies(101,
                "{\"op\":\"UPDATE_METADATA\",\"description\":\"d\"}")) + "]";
        assertInvalidExisting("\"changes\":" + oversized, "existing.changes");
        assertInvalidExisting("\"changes\":[null]", "existing.changes[0]");
        assertInvalidExisting("\"changes\":[{\"op\":\"ADD_TRANSITION\",\"transitionKey\":42}]",
                "existing.changes[0].transitionKey");
        assertInvalidExisting("\"changes\":[{\"op\":\"UPDATE_METADATA\",\"unknown\":true}]",
                "unknown");
        assertInvalidExisting("\"unknown\":true", "unknown");
    }

    @Test
    public void structuralParserKeepsStepTransitionAndScreenKeyNamespacesSeparate() throws Exception {
        String json = "{\"dryRun\":true,\"existing\":{\"workflow\":\"Flow\",\"version\":\"ACTIVE\","
                + "\"expectedRevision\":\"rev\",\"changes\":["
                + "{\"op\":\"ADD_STATUS\",\"statusId\":\"10\",\"clientKey\":\"same\"},"
                + "{\"op\":\"ADD_TRANSITION\",\"transitionKey\":\"same\",\"name\":\"Return\","
                + "\"source\":{\"clientKey\":\"same\"},\"targetStepId\":1,\"resolution\":{\"action\":\"CLEAR\"}}]},"
                + "\"newScreens\":[{\"key\":\"same\",\"name\":\"Return form\",\"fieldIds\":[\"assignee\"]}],"
                + "\"transitionRules\":[{\"transitionKey\":\"same\",\"screen\":{\"action\":\"SET\",\"screenKey\":\"same\"},"
                + "\"addValidators\":[{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"assignee\"]}}]}]}";
        ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest request =
                ru.rasulov.common.impl.tools.ToolArguments.parse(new ObjectMapper().readTree(json),
                        ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest.class);
        assertEquals("same", request.existing().changes().get(0).getClientKey());
        assertEquals("same", request.existing().changes().get(1).getTransitionKey());
        assertEquals("same", request.existing().changes().get(1).getSource().getClientKey());
        assertEquals("same", request.transitionRules().get(0).transitionKey());
        assertNull(request.transitionRules().get(0).actionId());
        assertEquals("same", request.transitionRules().get(0).screen().screenKey());
        assertEquals("same", request.newScreens().get(0).key());
        assertEquals("required_fields", request.transitionRules().get(0).addValidators().get(0).template());
    }

    @Test
    public void createParserStillRejectsStructuralChangesAndScreenDeclarationsRejectScreenKey() throws Exception {
        for (String json : new String[]{
                "{\"dryRun\":true,\"create\":{\"name\":\"Flow\",\"changes\":[{\"op\":\"UPDATE_METADATA\"}]}}",
                "{\"dryRun\":true,\"newScreens\":[{\"key\":\"form\",\"screenKey\":\"form\",\"name\":\"Form\",\"fieldIds\":[]}]}"}) {
            try {
                ru.rasulov.common.impl.tools.ToolArguments.parse(new ObjectMapper().readTree(json),
                        ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest.class);
                fail("unknown fields must not be silently ignored: " + json);
            } catch (ru.rasulov.common.api.exception.InvalidRequestException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(
                        json.contains("\"create\"") ? "changes" : "screenKey"));
            }
        }
    }

    private static ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest parseExisting(String properties)
            throws Exception {
        String json = "{\"dryRun\":true,\"existing\":{\"workflow\":\"Flow\",\"version\":\"ACTIVE\","
                + "\"expectedRevision\":\"rev\"" + (properties.isEmpty() ? "" : "," + properties) + "}}";
        return ru.rasulov.common.impl.tools.ToolArguments.parse(new ObjectMapper().readTree(json),
                ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest.class);
    }

    private static void assertInvalidExisting(String properties, String path) throws Exception {
        try {
            parseExisting(properties);
            fail("invalid existing properties accepted: " + properties);
        } catch (ru.rasulov.common.api.exception.InvalidRequestException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(path));
        }
    }

    private JsonNode schema() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/llm/workflow-tools.json")) {
            assertNotNull(input);
            for (JsonNode tool : new ObjectMapper().readTree(input)) {
                if ("configure_workflow".equals(tool.path("name").asText())) return tool.path("input_schema");
            }
        }
        throw new AssertionError("configure_workflow is not described");
    }

    private static final Set<String> FORBIDDEN = new HashSet<>(Arrays.asList(
            "$defs", "$ref", "oneOf", "anyOf", "allOf", "not", "if", "then", "else", "pattern"));

    /** Walks schemas only: names under {@code properties} are property names, not keywords. */
    private static void collectForbidden(JsonNode schema, String path, List<String> violations) {
        for (Iterator<String> it = schema.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            JsonNode value = schema.get(key);
            if ("properties".equals(key)) {
                for (Iterator<String> names = value.fieldNames(); names.hasNext(); ) {
                    String name = names.next();
                    collectForbidden(value.get(name), path + ".properties." + name, violations);
                }
                continue;
            }
            if (FORBIDDEN.contains(key)) violations.add(path + "." + key);
            if (value.isObject()) collectForbidden(value, path + "." + key, violations);
            else if (value.isArray()) {
                for (int index = 0; index < value.size(); index++) {
                    if (value.get(index).isObject()) collectForbidden(value.get(index), path + "." + key + "[" + index + "]", violations);
                }
            }
        }
    }

    private static void collectOpenObjects(JsonNode schema, String path, List<String> open) {
        boolean object = schema.has("properties") || "object".equals(schema.path("type").asText())
                || strings(schema.path("type")).contains("object");
        if (object && !(schema.has("additionalProperties") && !schema.path("additionalProperties").asBoolean(true))) {
            open.add(path);
        }
        for (Iterator<String> it = schema.path("properties").fieldNames(); it.hasNext(); ) {
            String name = it.next();
            collectOpenObjects(schema.path("properties").get(name), path + ".properties." + name, open);
        }
        if (schema.path("items").isObject()) collectOpenObjects(schema.path("items"), path + ".items", open);
    }

    private static Set<String> strings(String... values) {
        return new TreeSet<>(Arrays.asList(values));
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new TreeSet<>();
        if (array.isTextual()) values.add(array.asText());
        else for (JsonNode value : array) values.add(value.asText());
        return values;
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) names.add(it.next());
        return names;
    }

}
