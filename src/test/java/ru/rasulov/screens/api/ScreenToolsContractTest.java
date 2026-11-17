package ru.rasulov.screens.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.screens.api.dto.CloneProjectScreensRequest;
import ru.rasulov.common.api.CustomFieldCreationLimits;
import ru.rasulov.screens.api.dto.CreateScreenFieldRequest;
import ru.rasulov.screens.api.dto.ScreenCloneScope;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ScreenToolsContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();


    @Test
    public void contentsSchemaAndArgumentBoundaryAgree() throws Exception {
        JsonNode tool = tool("get_screen_contents"), schema = tool.get("input_schema");
        assertEquals(new TreeSet<String>(Arrays.asList("screenId", "cursor")), names(schema.get("properties")));
        assertEquals("screenId", schema.get("required").get(0).textValue());
        assertEquals(1, schema.get("required").size());
        assertTrue(!schema.get("additionalProperties").booleanValue());
        assertEquals("integer", schema.get("properties").get("screenId").get("type").textValue());
        assertEquals(1, schema.get("properties").get("screenId").get("minimum").intValue());
        assertEquals(1, schema.get("properties").get("cursor").get("minLength").intValue());
        assertEquals(512, schema.get("properties").get("cursor").get("maxLength").intValue());
        assertTrue(tool.get("annotations").get("readOnlyHint").booleanValue());
        ru.rasulov.screens.api.ScreenContentsReader reader = org.mockito.Mockito.mock(ru.rasulov.screens.api.ScreenContentsReader.class);
        ru.rasulov.common.impl.tools.ToolCall call = new ru.rasulov.screens.impl.ScreenTools(
                org.mockito.Mockito.mock(ScreenSchemeService.class),
                org.mockito.Mockito.mock(StandaloneScreenCreator.class),
                org.mockito.Mockito.mock(ProjectOperationScreenAssigner.class), reader,
                org.mockito.Mockito.mock(ScreenFieldRemover.class),
                org.mockito.Mockito.mock(ScreenLayoutEditor.class),
                org.mockito.Mockito.mock(IssueTypeScreenSchemeMappingEditor.class),
                org.mockito.Mockito.mock(ScreenSchemeOperationEditor.class),
                org.mockito.Mockito.mock(IssueTypeScreenSchemeAssigner.class)).tools().get("get_screen_contents");
        for (String args : Arrays.asList("{\"screenId\":1,\"extra\":true}", "{}", "{\"screenId\":0}",
                "{\"screenId\":1.5}", "{\"screenId\":\"1\"}", "{\"screenId\":1,\"cursor\":1}",
                "{\"screenId\":1,\"cursor\":\"\"}", "{\"screenId\":1,\"cursor\":null}")) {
            try { call.call(MAPPER.readTree(args)); org.junit.Assert.fail(args); }
            catch (ru.rasulov.common.api.exception.InvalidRequestException expected) { }
        }
        org.mockito.Mockito.verifyNoInteractions(reader);
    }
    @Test
    public void cloneSchemaListsSupportedScopes() throws Exception {
        JsonNode schema = tool("clone_project_screens").get("input_schema");
        List<String> scopes = new ArrayList<String>();
        for (JsonNode value : schema.get("properties").get("scope").get("enum")) scopes.add(value.textValue());
        assertEquals(Arrays.asList("SCHEME_ONLY", "ALL", "SELECTED"), scopes);
        assertTrue(tool("clone_project_screens").get("description").textValue().contains("get_project_screens"));
    }

    @Test
    public void createFieldSchemaMatchesRequestAndLimits() throws Exception {
        JsonNode schema = tool("create_screen").get("input_schema");
        JsonNode fields = schema.get("properties").get("fields");
        assertEquals(properties(CreateScreenFieldRequest.class), names(fields.get("items").get("properties")));
        assertEquals(CustomFieldCreationLimits.MAX_FIELDS_PER_CALL, fields.get("maxItems").intValue());
    }

    @Test
    public void payloadKeepsIndependentScopeAndScreenChoice() throws Exception {
        CloneProjectScreensRequest request = MAPPER.readValue("{\"dryRun\":true,\"project\":\"ABC\","
                + "\"scope\":\"SELECTED\",\"issueTypes\":[\"Bug\"],\"copyScreens\":true}",
                CloneProjectScreensRequest.class);
        assertEquals(ScreenCloneScope.SELECTED, request.getScope());
        assertEquals(Arrays.asList("Bug"), request.getIssueTypes());
        assertTrue(request.isCopyScreens());
    }

    @Test
    public void removalPreviewIsDestructiveAndUsesExplicitBoundedTargets() throws Exception {
        JsonNode entry = tool("remove_field_from_screens");
        JsonNode schema = entry.get("input_schema"), props = schema.get("properties");
        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "fieldIds", "screens")), names(props));
        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "fieldIds", "screens")),
                values(schema.get("required")));
        assertTrue(!schema.get("additionalProperties").booleanValue());
        assertEquals("boolean", props.get("dryRun").get("type").textValue());
        assertEquals(1, props.get("dryRun").get("enum").size());
        JsonNode fields = props.get("fieldIds"), screens = props.get("screens");
        assertEquals("array", fields.get("type").textValue());
        assertEquals(1, fields.get("minItems").intValue());
        assertTrue(fields.get("uniqueItems").booleanValue());
        assertEquals("string", fields.get("items").get("type").textValue());
        assertEquals(1, fields.get("items").get("minLength").intValue());
        assertEquals("array", screens.get("type").textValue());
        assertEquals(1, screens.get("minItems").intValue());
        JsonNode target = screens.get("items");
        assertEquals(properties(ru.rasulov.screens.api.dto.layout.RemoveFieldFromScreensRequest.ScreenTarget.class),
                names(target.get("properties")));
        assertEquals("object", target.get("type").textValue());
        assertEquals(new TreeSet<String>(Arrays.asList("screenId")), values(target.get("required")));
        assertTrue(!target.get("additionalProperties").booleanValue());
        assertEquals("integer", target.get("properties").get("screenId").get("type").textValue());
        assertEquals(1, target.get("properties").get("screenId").get("minimum").intValue());
        assertEquals(50, props.get("fieldIds").get("maxItems").intValue());
        assertEquals(100, props.get("screens").get("maxItems").intValue());
        assertTrue(!entry.get("annotations").get("readOnlyHint").booleanValue());
        assertTrue(entry.get("annotations").get("destructiveHint").booleanValue());
        assertTrue(!props.has("planId"));
        assertTrue(props.get("dryRun").get("enum").get(0).booleanValue());
        String description = entry.get("description").textValue();
        for (ru.rasulov.screens.api.dto.layout.LayoutErrorCode code : ru.rasulov.screens.api.dto.layout.LayoutErrorCode.values())
            assertTrue(code.name(), description.contains(code.name()));
    }

    @Test
    public void removalDescriptionPreservesDiscoveryConfirmationAndHonestRecoveryProtocol() throws Exception {
        String description = tool("remove_field_from_screens").get("description").textValue();
        for (String clause : Arrays.asList("get_project_screens", "screenId supplied by the user",
                "never guess a screen", "ask which operations", "get_screen_contents through every nextCursor",
                "fieldIds (not names)", "do not traverse workflow usage", "screen-by-field table",
                "previous tab/position/neighbors", "operations, total project counts",
                "SHARED_SCREEN", "number of other projects affected", "DEFAULT_SCREEN",
                "separate line", "REQUIRED_ON_CREATE", "warning, not a refusal", "requiredProjects",
                "requiredProjectsTruncated", "set_field_required", "required:false",
                "apply every set_field_required plan first, then remove_field_from_screens",
                "Never make a field optional unless the user chose (a)",
                "Only summary on CREATE is PROTECTED_FIELD", "HIDES_VALUES", "EMPTY_TAB",
                "ask ONE confirmation question", "only after the user's yes call start_operation",
                "arguments={dryRun:false,planId:<returned>}", "REMOVED/NOT_PRESENT/FAILED/NOT_ATTEMPTED",
                "actual observed layout", "UNKNOWN", "never present the expected layout as observed",
                "edit_screen_layout preview for the same screen and tab", "place:AFTER",
                "anchor equal to the previous neighbor", "use FIRST if there was no previous neighbor",
                "stored values and search remain available", "Automatic Undo is unavailable",
                "Removal error codes:", "shared layout enum", "removal uses NOT_PRESENT",
                "500 field-screen pairs", "new shown preview and confirmation", "never retry writes blindly"))
            assertTrue(clause, description.contains(clause));
    }

    @Test
    public void movePreviewUsesOneScreenAndFlatBoundedSequentialMoves() throws Exception {
        JsonNode entry = tool("edit_screen_layout");
        JsonNode schema = entry.get("input_schema"), props = schema.get("properties");
        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "screenId", "add", "moves", "remove")), names(props));
        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "screenId")), values(schema.get("required")));
        assertTrue(!schema.get("additionalProperties").booleanValue());
        assertTrue(!props.has("planId"));
        assertEquals("boolean", props.get("dryRun").get("type").textValue());
        assertEquals(1, props.get("dryRun").get("enum").size());
        assertTrue(props.get("dryRun").get("enum").get(0).booleanValue());
        assertEquals("integer", props.get("screenId").get("type").textValue());
        assertEquals(1, props.get("screenId").get("minimum").intValue());
        JsonNode moves = props.get("moves"), move = moves.get("items"), fields = move.get("properties");
        assertEquals("array", moves.get("type").textValue());
        assertEquals(1, moves.get("minItems").intValue());
        assertEquals(50, moves.get("maxItems").intValue());
        assertTrue(!moves.has("uniqueItems"));
        assertEquals("object", move.get("type").textValue());
        assertTrue(!move.get("additionalProperties").booleanValue());
        assertTrue(!move.has("oneOf"));
        assertEquals(properties(ru.rasulov.screens.api.dto.layout.ScreenFieldMove.class), names(fields));
        assertEquals(new TreeSet<String>(Arrays.asList("fieldId", "place")), values(move.get("required")));
        for (String field : Arrays.asList("fieldId", "tab", "anchor")) {
            assertEquals("string", fields.get(field).get("type").textValue());
            assertEquals(1, fields.get(field).get("minLength").intValue());
        }
        assertEquals("string", fields.get("place").get("type").textValue());
        assertEquals(new TreeSet<String>(Arrays.asList("FIRST", "LAST", "BEFORE", "AFTER", "UP", "DOWN")),
                values(fields.get("place").get("enum")));
        assertEquals("integer", fields.get("steps").get("type").textValue());
        assertEquals(1, fields.get("steps").get("minimum").intValue());
        assertEquals(200, fields.get("steps").get("maximum").intValue());
        assertTrue(!entry.get("annotations").get("readOnlyHint").booleanValue());
        assertTrue(entry.get("annotations").get("destructiveHint").booleanValue());
        assertTrue(!entry.get("annotations").get("idempotentHint").booleanValue());
    }

    @Test
    public void itssMappingsSchemaMatchesRequestAndSeparatesSharedEditFromProjectIsolation() throws Exception {
        JsonNode tool = tool("set_issue_type_screen_scheme_mappings");
        JsonNode schema = tool.get("input_schema");
        Set<String> preview = properties(ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsRequest.class);
        assertTrue("planId is applied only through start_operation, not in the public preview schema", preview.remove("planId"));
        assertEquals(preview, names(schema.get("properties")));
        JsonNode rows = schema.get("properties").get("rows");
        assertEquals(50, rows.get("maxItems").intValue());
        assertEquals(1, rows.get("minItems").intValue());
        assertEquals(properties(ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsRequest.Row.class),
                names(rows.get("items").get("properties")));
        assertEquals(new TreeSet<String>(Arrays.asList("ADD", "REPLACE", "REMOVE")),
                values(rows.get("items").get("properties").get("action").get("enum")));
        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "issueTypeScreenScheme", "rows")), values(schema.get("required")));
        assertTrue(schema.get("additionalProperties").isBoolean() && !schema.get("additionalProperties").booleanValue());
        assertFalse(tool.get("annotations").get("readOnlyHint").booleanValue());
        assertTrue(tool.get("annotations").get("destructiveHint").booleanValue());
        String description = tool.get("description").textValue();
        assertTrue(description.contains("set_project_operation_screens") && description.contains("clone_project_screens"));
    }

    @Test
    public void screenSchemeOperationsSchemaMatchesRequestAndTheDescriptionCarriesTheBeforeConfirmationDuties() throws Exception {
        JsonNode tool = tool("set_screen_scheme_operations");
        JsonNode schema = tool.get("input_schema");
        Set<String> preview = properties(ru.rasulov.screens.api.dto.SetScreenSchemeOperationsRequest.class);
        assertTrue("planId is applied only through start_operation, not in the public preview schema", preview.remove("planId"));
        assertEquals(preview, names(schema.get("properties")));
        JsonNode rows = schema.get("properties").get("rows");
        assertEquals(50, rows.get("maxItems").intValue());
        assertEquals(1, rows.get("minItems").intValue());
        assertEquals(properties(ru.rasulov.screens.api.dto.SetScreenSchemeOperationsRequest.Row.class),
                names(rows.get("items").get("properties")));
        assertEquals(new TreeSet<String>(Arrays.asList("ADD", "REPLACE", "REMOVE")),
                values(rows.get("items").get("properties").get("action").get("enum")));
        assertEquals(new TreeSet<String>(Arrays.asList("DEFAULT", "CREATE", "EDIT", "VIEW")),
                values(rows.get("items").get("properties").get("operation").get("enum")));
        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "screenScheme", "rows")), values(schema.get("required")));
        assertTrue(schema.get("additionalProperties").isBoolean() && !schema.get("additionalProperties").booleanValue());
        assertFalse(tool.get("annotations").get("readOnlyHint").booleanValue());
        assertTrue(tool.get("annotations").get("destructiveHint").booleanValue());
        String description = tool.get("description").textValue();
        for (String token : Arrays.asList("set_project_operation_screens", "clone_project_screens", "linked = N", "changing = M",
                "SEPARATELY", "never call a cut list 'the listed projects'", "REQUIRED_FIELDS_MISSING_ON_CREATE", "nothing is rolled back",
                "NEW preview", "required flag", "Field Configuration Scheme", "ADD_AS_REPLACE", "PREVIEW_STALE", "start_operation")) {
            assertTrue(token, description.contains(token));
        }
    }

    @Test
    public void rulesMentionTheScreenSchemeToolInExactlyOneLineOfMultiStepRequests() throws Exception {
        String rules;
        try (InputStream in = ScreenToolsContractTest.class.getResourceAsStream("/llm/rules.md")) {
            assertNotNull(in);
            rules = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int section = rules.indexOf("MULTI-STEP REQUESTS");
        assertTrue(section >= 0);
        int mentions = 0;
        for (String line : rules.split("\\R")) if (line.contains("set_screen_scheme_operations")) mentions++;
        assertEquals(1, mentions);
        assertTrue("the line is inside MULTI-STEP REQUESTS", rules.indexOf("set_screen_scheme_operations") > section);
    }

    @Test
    public void itssAssignmentSchemaMatchesRequestAndTheDescriptionCarriesTheBeforeConfirmationDuties() throws Exception {
        JsonNode tool = tool("assign_issue_type_screen_scheme");
        JsonNode schema = tool.get("input_schema");
        Set<String> preview = properties(ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeRequest.class);
        assertTrue("planId is applied only through start_operation, not in the public preview schema", preview.remove("planId"));
        assertEquals(preview, names(schema.get("properties")));
        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "project", "issueTypeScreenScheme")), values(schema.get("required")));
        assertTrue(schema.get("additionalProperties").isBoolean() && !schema.get("additionalProperties").booleanValue());
        assertFalse(tool.get("annotations").get("readOnlyHint").booleanValue());
        assertTrue(tool.get("annotations").get("destructiveHint").booleanValue());
        String description = tool.get("description").textValue();
        for (String token : Arrays.asList("set_issue_type_screen_scheme_mappings", "set_project_operation_screens",
                "clone_project_screens", "DEFAULT", "ITSS_ORPHANED", "SHARED_TARGET", "REQUIRED_FIELDS_MISSING_ON_CREATE",
                "ITSS_NOT_FOUND", "ITSS_AMBIGUOUS", "UNKNOWN_PROJECT", "NO_CHANGE", "NEW preview", "required flag",
                "Field Configuration Scheme", "PREVIEW_STALE", "start_operation", "create_project", "cannot be undone",
                "is not deleted")) {
            assertTrue(token, description.contains(token));
        }
    }

    @Test
    public void rulesMentionTheAssignmentToolInExactlyOneLineThatAlsoSeparatesItFromTheSharedEdit() throws Exception {
        String rules;
        try (InputStream in = ScreenToolsContractTest.class.getResourceAsStream("/llm/rules.md")) {
            assertNotNull(in);
            rules = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int section = rules.indexOf("MULTI-STEP REQUESTS");
        assertTrue(section >= 0);
        int mentions = 0;
        for (String line : rules.split("\\R")) {
            if (!line.contains("assign_issue_type_screen_scheme")) continue;
            mentions++;
            assertTrue("the same line separates it from the shared edit and from the copy tools",
                    line.contains("set_issue_type_screen_scheme_mappings") && line.contains("clone_project_screens"));
        }
        assertEquals(1, mentions);
        assertTrue("the line is inside MULTI-STEP REQUESTS", rules.indexOf("assign_issue_type_screen_scheme") > section);
    }


    private static Set<String> values(JsonNode array) {
        Set<String> result = new TreeSet<String>();
        for (JsonNode value : array) result.add(value.textValue());
        return result;
    }

    private static JsonNode tool(String name) throws Exception {
        InputStream in = ScreenToolsContractTest.class.getResourceAsStream("/llm/screens-tools.json");
        assertNotNull(in);
        try { for (JsonNode tool : MAPPER.readTree(in)) if (name.equals(tool.get("name").textValue())) return tool; }
        finally { in.close(); }
        throw new AssertionError("missing tool " + name);
    }
    private static Set<String> names(JsonNode object) {
        Set<String> result = new TreeSet<String>();
        for (Iterator<String> it = object.fieldNames(); it.hasNext();) result.add(it.next());
        return result;
    }
    private static Set<String> properties(Class<?> type) {
        for (Constructor<?> constructor : type.getConstructors()) {
            if (!constructor.isAnnotationPresent(JsonCreator.class)) continue;
            Set<String> result = new TreeSet<String>();
            for (Annotation[] annotations : constructor.getParameterAnnotations()) {
                for (Annotation annotation : annotations) if (annotation instanceof JsonProperty)
                    result.add(((JsonProperty) annotation).value());
            }
            return result;
        }
        throw new AssertionError("missing @JsonCreator constructor for " + type.getName());
    }
}
