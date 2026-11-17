package ru.rasulov.customfields.api;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.common.api.CustomFieldCreationLimits;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;
import ru.rasulov.common.api.dto.field.context.ContextSummary;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse;
import ru.rasulov.customfields.api.dto.screen.AddToScreensRequest;
import ru.rasulov.customfields.api.dto.screen.AddToScreensResponse;
import ru.rasulov.customfields.api.dto.screen.ScreenCandidate;
import ru.rasulov.customfields.api.dto.screen.FieldScreensResult;
import ru.rasulov.customfields.api.dto.screen.Recommendation;
import ru.rasulov.customfields.api.dto.screen.ScreenErrorCode;
import ru.rasulov.customfields.api.dto.screen.ScreenOperation;
import ru.rasulov.customfields.api.dto.screen.ScreenPlan;
import ru.rasulov.customfields.api.dto.screen.ScreenResult;
import ru.rasulov.customfields.api.dto.screen.ScreenSummary;
import ru.rasulov.customfields.api.dto.screen.ScreenTarget;
import ru.rasulov.customfields.api.dto.screen.TabRef;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredRequest;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Keeps {@code llm/customfields-tools.json} and the JSON shape of the field and screen DTOs from drifting apart. */
public class ToolsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Jira's REST Jackson mapper does not auto-detect {@code getX()}; DTOs must not rely on it. Serializing with
     * this mapper fails when a getter lacks an explicit {@code @JsonProperty}.
     */
    private static final ObjectMapper GETTER_BLIND_MAPPER = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false);


    @Test
    public void everyRequiredPropertyIsDeclared() throws Exception {
        JsonNode schema = tool("create_custom_fields").get("input_schema");
        JsonNode field = schema.get("properties").get("fields").get("items");
        assertRequiredAreDeclared(schema);
        assertRequiredAreDeclared(field);
        assertRequiredAreDeclared(field.get("properties").get("options").get("items"));
        assertRequiredAreDeclared(field.get("properties").get("options").get("items").get("properties").get("children").get("items"));
    }

    @Test
    public void optionSchemaMatchesOptionDtoAndChildrenAreOneLevelDeep() throws Exception {
        JsonNode option = tool("create_custom_fields").get("input_schema").get("properties").get("fields").get("items")
                .get("properties").get("options").get("items");

        assertEquals(jsonPropertiesOf(OptionDefinition.class), names(option.get("properties")));
        JsonNode child = option.get("properties").get("children").get("items");
        assertEquals(new TreeSet<String>(Collections.singletonList("value")), names(child.get("properties")));
    }

    @Test
    public void everyLimitInTheToolSchemaEqualsTheLimitInCode() throws Exception {
        JsonNode fields = tool("create_custom_fields").get("input_schema").get("properties").get("fields");
        JsonNode field = fields.get("items").get("properties");
        JsonNode options = field.get("options");
        JsonNode option = options.get("items").get("properties");
        JsonNode child = option.get("children");
        JsonNode contexts = field.get("contexts");
        JsonNode context = contexts.get("items").get("properties");

        assertEquals(CustomFieldCreationLimits.MAX_FIELDS_PER_CALL, fields.get("maxItems").intValue());
        assertEquals(Limits.MAX_NAME_LENGTH, field.get("name").get("maxLength").intValue());
        assertEquals(Limits.MAX_ROOT_OPTIONS, options.get("maxItems").intValue());
        assertEquals(Limits.MAX_OPTION_LENGTH, option.get("value").get("maxLength").intValue());
        assertEquals(Limits.MAX_CHILD_OPTIONS_PER_PARENT, child.get("maxItems").intValue());
        assertEquals(Limits.MAX_OPTION_LENGTH,
                child.get("items").get("properties").get("value").get("maxLength").intValue());
        assertEquals(Limits.MAX_CONTEXTS_PER_FIELD, contexts.get("maxItems").intValue());
        assertEquals(Limits.MAX_NAME_LENGTH, context.get("name").get("maxLength").intValue());
        assertEquals(Limits.MAX_PROJECTS_PER_CONTEXT, context.get("projects").get("maxItems").intValue());
        assertEquals(Limits.MAX_ISSUE_TYPES_PER_CONTEXT, context.get("issueTypes").get("maxItems").intValue());
        assertEquals(Limits.MAX_ROOT_OPTIONS, context.get("options").get("maxItems").intValue());
    }

    @Test
    public void requestWithNestedOptionsDeserializes() throws Exception {
        String json = "{\"fields\":[{\"name\":\"Region\",\"typeKey\":\"cascadingselect\",\"options\":["
                + "{\"value\":\"EU\",\"children\":[{\"value\":\"Berlin\"},{\"value\":\"Paris\"}]},{\"value\":\"US\"}]}]}";

        FieldDefinition field = MAPPER.readValue(json, CreateCustomFieldsRequest.class).getFields().get(0);

        assertEquals(2, field.getOptions().size());
        assertEquals("EU", field.getOptions().get(0).getValue());
        assertEquals("Paris", field.getOptions().get(0).getChildren().get(1).getValue());
        assertEquals(0, field.getOptions().get(1).getChildren().size());
    }

    @Test
    public void requestWithAssetsRoundTripsWithExplicitGetters() throws Exception {
        String json = "{\"dryRun\":true,\"fields\":[{\"name\":\"Server\","
                + "\"typeKey\":\"com.riadalabs.jira.plugins.insight:rlabs-customfield-default-object\","
                + "\"assets\":{\"objectSchemaId\":7,\"filterScopeAql\":\"objectType = Server\"},"
                + "\"contexts\":[{\"allProjects\":true,\"allIssueTypes\":true}]}]}";

        CreateCustomFieldsRequest request = MAPPER.readValue(json, CreateCustomFieldsRequest.class);
        AssetsFieldSettings assets = request.getFields().get(0).getAssets();
        assertEquals(Integer.valueOf(7), assets.getObjectSchemaId());
        assertEquals("objectType = Server", assets.getFilterScopeAql());

        CreateCustomFieldsRequest restored = MAPPER.readValue(
                GETTER_BLIND_MAPPER.writeValueAsString(request), CreateCustomFieldsRequest.class);
        AssetsFieldSettings restoredAssets = restored.getFields().get(0).getAssets();
        assertNotNull(restoredAssets);
        assertEquals(assets.getObjectSchemaId(), restoredAssets.getObjectSchemaId());
        assertEquals(assets.getFilterScopeAql(), restoredAssets.getFilterScopeAql());
    }

    @Test
    public void assetsAndScopeAqlRemainOptional() throws Exception {
        FieldDefinition plain = MAPPER.readValue(
                "{\"name\":\"Text\",\"typeKey\":\"textfield\"}", FieldDefinition.class);
        assertNull(plain.getAssets());

        FieldDefinition object = MAPPER.readValue(
                "{\"name\":\"Server\",\"typeKey\":\"com.riadalabs.jira.plugins.insight:rlabs-customfield-default-object\","
                        + "\"assets\":{\"objectSchemaId\":7}}",
                FieldDefinition.class);
        assertEquals(Integer.valueOf(7), object.getAssets().getObjectSchemaId());
        assertNull(object.getAssets().getFilterScopeAql());
    }

    @Test
    public void requestWithContextsDeserializes() throws Exception {
        String json = "{\"fields\":[{\"name\":\"Severity\",\"typeKey\":\"select\",\"contexts\":["
                + "{\"name\":\"Payments\",\"projects\":[\"PAY\",\"Webshop\"],\"issueTypes\":[\"Bug\",\"10002\"],"
                + "\"options\":[{\"value\":\"Card\"}]},"
                + "{\"allProjects\":true,\"allIssueTypes\":true}]}]}";

        FieldDefinition field = MAPPER.readValue(json, CreateCustomFieldsRequest.class).getFields().get(0);

        assertEquals(2, field.getContexts().size());
        ContextDefinition first = field.getContexts().get(0);
        assertEquals("Payments", first.getName());
        assertEquals(Arrays.asList("PAY", "Webshop"), first.getProjects());
        assertEquals(Arrays.asList("Bug", "10002"), first.getIssueTypes());
        assertEquals(false, first.isAllProjects());
        assertEquals(false, first.isAllIssueTypes());
        assertEquals("Card", first.getOptions().get(0).getValue());
        ContextDefinition second = field.getContexts().get(1);
        assertEquals(true, second.isAllProjects());
        assertEquals(true, second.isAllIssueTypes());
        assertEquals(0, second.getProjects().size());
    }

    @Test
    public void contextSchemaMatchesContextDtoAndRequiresContexts() throws Exception {
        JsonNode field = tool("create_custom_fields").get("input_schema").get("properties").get("fields").get("items");
        JsonNode contexts = field.get("properties").get("contexts");

        assertEquals(jsonPropertiesOf(ContextDefinition.class), names(contexts.get("items").get("properties")));
        assertEquals(1, contexts.get("minItems").intValue());
        boolean required = false;
        for (JsonNode name : field.get("required")) {
            required |= "contexts".equals(name.textValue());
        }
        assertTrue("contexts must be required: a context is never assumed", required);
        assertEquals(jsonPropertiesOf(OptionDefinition.class),
                names(contexts.get("items").get("properties").get("options").get("items").get("properties")));
    }

    @Test
    public void screenTargetSchemaMatchesRequestAndLimits() throws Exception {
        JsonNode plan = tool("plan_field_screens").get("input_schema");
        JsonNode add = tool("add_field_to_screens").get("input_schema");
        JsonNode target = add.get("properties").get("screens").get("items");

        assertEquals(new TreeSet<String>(Arrays.asList("fieldId", "cursor", "recommendation")),
                names(plan.get("properties")));
        assertEquals(jsonPropertiesOf(ScreenTarget.class), names(target.get("properties")));
        assertEquals(Limits.MAX_SCREENS_PER_CALL, add.get("properties").get("screens").get("maxItems").intValue());
        assertEquals(CustomFieldCreationLimits.MAX_FIELDS_PER_CALL, add.get("properties").get("fieldIds").get("maxItems").intValue());
        assertEquals(1, add.get("properties").get("screens").get("minItems").intValue());
        assertEquals(1, add.get("properties").get("fieldIds").get("minItems").intValue());
        assertEquals("object", target.get("type").textValue());
        assertFalse(target.get("additionalProperties").booleanValue());
        assertEquals(MAPPER.readTree("[\"screenId\"]"), target.get("required"));
        assertEquals(MAPPER.readTree("[\"FIRST\",\"LAST\",\"BEFORE\",\"AFTER\"]"),
                target.get("properties").get("place").get("enum"));
        assertEquals("string", target.get("properties").get("anchor").get("type").textValue());
        assertEquals(1, target.get("properties").get("anchor").get("minLength").intValue());
        assertRequiredAreDeclared(plan);
        assertRequiredAreDeclared(add);
        assertRequiredAreDeclared(target);
    }

    @Test
    public void screenTargetsParsePlacementAndRetainLegacyOmittedDefaults() throws Exception {
        ScreenTarget legacy = MAPPER.readValue("{\"screenId\":5,\"tab\":\"Details\"}", ScreenTarget.class);
        assertEquals(Long.valueOf(5L), legacy.getScreenId());
        assertEquals("Details", legacy.getTab());
        assertNull(legacy.getPlace());
        assertNull(legacy.getAnchor());
        ScreenTarget positioned = MAPPER.readValue(
                "{\"screenId\":5,\"tab\":\"Details\",\"place\":\"AFTER\",\"anchor\":\"assignee\"}",
                ScreenTarget.class);
        assertEquals(ru.rasulov.common.api.dto.screen.FieldPlace.AFTER, positioned.getPlace());
        assertEquals("assignee", positioned.getAnchor());
        JsonNode serialized = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(positioned));
        assertEquals(jsonPropertiesOf(ScreenTarget.class), names(serialized));
        assertEquals("AFTER", serialized.get("place").textValue());
        assertEquals("assignee", serialized.get("anchor").textValue());
    }


    @Test
    public void screenPlanSerializesWithAllDocumentedKeys() throws Exception {
        ScreenCandidate candidate = new ScreenCandidate(10100L, "A create",
                Arrays.asList(ScreenOperation.CREATE, ScreenOperation.EDIT),
                Collections.singletonList(new TabRef(7L, "Field Tab")), false, false, 1,
                Collections.singletonList(new ProjectRef("A", "Project A")), 2,
                Arrays.asList(new ProjectRef("B", "Project B"), new ProjectRef("C", "Project C")),
                Collections.singletonList(new IssueTypeRef("1", "Bug")), Recommendation.CAUTION,
                Collections.singletonList("shared"));
        ScreenPlan plan = new ScreenPlan("customfield_1", "Severity", Collections.<ContextSummary>emptyList(), 1, false,
                new ScreenSummary(0, 1, 0, 0), Collections.singletonList(candidate));

        JsonNode json = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(plan));

        assertEquals(new TreeSet<String>(Arrays.asList("fieldId", "fieldName", "contexts", "total", "truncated",
                "summary", "screens")), names(json));
        assertEquals(new TreeSet<String>(Arrays.asList("recommended", "caution", "notRecommended", "alreadyPresent")),
                names(json.get("summary")));
        JsonNode screen = json.get("screens").get(0);
        assertEquals(new TreeSet<String>(Arrays.asList("screenId", "name", "operations", "tabs", "alreadyHasField",
                "defaultScreen", "projectsInContextTotal", "projectsInContext", "projectsOutsideContextTotal",
                "projectsOutsideContext", "issueTypes", "recommendation", "reasons")), names(screen));
        assertEquals("CAUTION", screen.get("recommendation").textValue());
        assertEquals("EDIT", screen.get("operations").get(1).textValue());
        assertEquals(2, screen.get("projectsOutsideContextTotal").intValue());
        assertEquals("Field Tab", screen.get("tabs").get(0).get("name").textValue());
    }

    @Test
    public void addToScreensResponseSerializesWithAllDocumentedKeys() throws Exception {
        AddToScreensResponse response = new AddToScreensResponse(false, Collections.singletonList(
                new FieldScreensResult("customfield_1", "Severity", Arrays.asList(
                        ScreenResult.added(1L, "A", "Field Tab"),
                        ScreenResult.failed(2L, null, ScreenErrorCode.UNKNOWN_SCREEN, "no such screen")))));

        JsonNode json = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(response));

        assertEquals(new TreeSet<String>(Arrays.asList(
                "dryRun", "errorCode", "expiresAt", "fields", "message", "planId", "undoWarning")), names(json));
        JsonNode field = json.get("fields").get(0);
        assertEquals(new TreeSet<String>(Arrays.asList("fieldId", "name", "results")), names(field));
        assertEquals("Severity", field.get("name").textValue());
        JsonNode added = field.get("results").get(0);
        assertEquals(new TreeSet<String>(Arrays.asList("screenId", "name", "status", "tab", "errorCode", "message")),
                names(added));
        assertEquals("ADDED", added.get("status").textValue());
        assertTrue(added.get("errorCode").isNull());
        assertEquals("UNKNOWN_SCREEN", field.get("results").get(1).get("errorCode").textValue());
    }



    @Test
    public void responseSerializesWithAllDocumentedKeysIncludingNulls() throws Exception {
        ContextSummary context = new ContextSummary("PAY", false,
                Collections.singletonList(new ProjectRef("PAY", "Payments")), false,
                Collections.singletonList(new IssueTypeRef("10004", "Bug")));
        CreateCustomFieldsResponse response = new CreateCustomFieldsResponse(false, Arrays.asList(
                FieldCreationResult.created("Summary", "customfield_10201", Collections.singletonList(context), null),
                FieldCreationResult.failed("Bad", CustomFieldErrorCode.UNKNOWN_TYPE, "msg", Collections.singletonList("s"))));

        JsonNode json = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(response));

        assertEquals(false, json.get("dryRun").booleanValue());
        JsonNode created = json.get("results").get(0);
        assertEquals(new TreeSet<String>(Arrays.asList("name", "status", "customFieldId", "errorCode", "message",
                "suggestions", "contexts")), names(created));
        assertEquals("CREATED", created.get("status").textValue());
        assertTrue(created.get("errorCode").isNull());
        assertEquals(0, created.get("suggestions").size());
        JsonNode shown = created.get("contexts").get(0);
        assertEquals(new TreeSet<String>(Arrays.asList("name", "allProjects", "projects", "allIssueTypes", "issueTypes")),
                names(shown));
        assertEquals("PAY", shown.get("projects").get(0).get("key").textValue());
        assertEquals("Payments", shown.get("projects").get(0).get("name").textValue());
        assertEquals("10004", shown.get("issueTypes").get(0).get("id").textValue());
        JsonNode failed = json.get("results").get(1);
        assertEquals("UNKNOWN_TYPE", failed.get("errorCode").textValue());
        assertTrue(failed.get("customFieldId").isNull());
        assertEquals("s", failed.get("suggestions").get(0).textValue());
        assertEquals(0, failed.get("contexts").size());
    }


    private static java.util.Set<String> fieldNames(JsonNode node) {
        java.util.Set<String> names = new TreeSet<String>();
        for (java.util.Iterator<String> it = node.fieldNames(); it.hasNext(); ) names.add(it.next());
        return names;
    }

    @Test
    public void fieldRequirementToolSchemaKeepsItsLimits() throws Exception {
        JsonNode schema = tool("set_field_required").get("input_schema");

        JsonNode rows = schema.get("properties").get("rows");
        JsonNode row = rows.get("items").get("properties");

        assertEquals(Limits.MAX_REQUIREMENT_ROWS, rows.get("maxItems").intValue());
        assertEquals(Limits.MAX_ISSUE_TYPES_PER_CONTEXT, row.get("issueTypes").get("maxItems").intValue());
        assertEquals(Limits.MAX_FIELDS_PER_REQUIREMENT_CALL, row.get("fields").get("maxItems").intValue());
        assertEquals(1, row.get("fields").get("minItems").intValue());
        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "project", "rows")),
                fieldNames(schema.get("properties")));
        assertRequiredAreDeclared(schema);
    }


    @Test
    public void fieldConfigurationCloneResponseSerializesTheCompletePlan() throws Exception {
        CloneProjectFieldConfigurationsResponse.CopyView scheme =
                new CloneProjectFieldConfigurationsResponse.CopyView(10L, "Shared", null, "PAY: Shared", 9,
                        Collections.singletonList(new ProjectRef("SHOP", "Shop")));
        CloneProjectFieldConfigurationsResponse response = CloneProjectFieldConfigurationsResponse.success(true,
                new ProjectRef("PAY", "Payments"), scheme, Collections.singletonList(scheme));

        JsonNode json = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(response));

        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "status", "project",
                "fieldConfigurationScheme", "fieldConfigurations", "message", "errorCode", "suggestions",
                "planId", "expiresAt", "undoWarning")), names(json));
        assertEquals(new TreeSet<String>(Arrays.asList("sourceId", "sourceName", "newId", "newName",
                "otherProjectsTotal", "otherProjects")), names(json.get("fieldConfigurations").get(0)));
        assertEquals(9, json.get("fieldConfigurationScheme").get("otherProjectsTotal").intValue());
    }

    @Test
    public void ambiguousFieldRequirementResponseSerializesTypedCandidates() throws Exception {
        SetFieldRequiredResponse.FieldView candidate = new SetFieldRequiredResponse.FieldView(
                "customfield_10100", "Severity", "select", "Select List");
        SetFieldRequiredResponse response = SetFieldRequiredResponse.failed(true, new ProjectRef("PAY", "Payments"),
                SetFieldRequiredResponse.ErrorCode.AMBIGUOUS_FIELD, "choose", null,
                Collections.singletonList(candidate));

        JsonNode json = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(response));

        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "status", "project", "fields", "candidates",
                "fieldConfigurationScheme", "targets", "errorCode", "message", "suggestions", "planId", "expiresAt",
                "undoWarning")),
                names(json));
        JsonNode shown = json.get("candidates").get(0);
        assertEquals(new TreeSet<String>(Arrays.asList("id", "name", "typeKey", "typeName")), names(shown));
        assertEquals("Select List", shown.get("typeName").textValue());
    }


    private static JsonNode tool(String name) throws Exception {
        InputStream in = ToolsContractTest.class.getResourceAsStream("/llm/customfields-tools.json");
        assertNotNull("llm/customfields-tools.json is missing from resources", in);
        try {
            for (JsonNode tool : MAPPER.readTree(in)) {
                if (name.equals(tool.get("name").textValue())) {
                    return tool;
                }
            }
        } finally {
            in.close();
        }
        throw new AssertionError("tool not described: " + name);
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new TreeSet<String>();
        for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    private static void assertRequiredAreDeclared(JsonNode objectSchema) {
        Set<String> declared = names(objectSchema.get("properties"));
        for (JsonNode required : objectSchema.get("required")) {
            assertTrue("required but undeclared: " + required.textValue(), declared.contains(required.textValue()));
        }
    }

    /** JSON property names taken from the {@code @JsonProperty} constructor parameters of the DTO. */
    private static Set<String> jsonPropertiesOf(Class<?> dto) {
        Constructor<?> constructor = dto.getConstructors()[0];
        Set<String> names = new TreeSet<String>();
        for (Annotation[] parameter : constructor.getParameterAnnotations()) {
            for (Annotation annotation : parameter) {
                if (annotation instanceof JsonProperty) {
                    names.add(((JsonProperty) annotation).value());
                }
            }
        }
        return names;
    }
}
