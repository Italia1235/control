package ru.rasulov.workflow.impl.edit;

import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.tools.ToolCatalog;
import ru.rasulov.common.impl.tools.ToolRegistry;
import ru.rasulov.common.rest.RequestBodies;
import ru.rasulov.workflow.api.*;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.api.dto.edit.*;
import ru.rasulov.workflow.impl.WorkflowTools;
import java.io.InputStream;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Catalog, closed nested DTO, provider routing and durable-plan contract for validators. */
public class ValidatorToolsContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper GETTER_BLIND = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false)
            .configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false);
    private static final String REQUEST = "{\"dryRun\":true,\"existing\":{\"workflow\":\"W\",\"version\":\"ACTIVE\",\"expectedRevision\":\"r\"},\"transitionRules\":[{\"actionId\":21,\"addValidators\":[{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"assignee\",\"customfield_10118\"]}}]}]}";

    @Test public void catalogKeepsReaderAndPublishesOnlyUnifiedMutation() throws Exception {
        JsonNode read = tool("get_workflow_validators");
        assertTrue(read.path("annotations").path("readOnlyHint").booleanValue());
        assertEquals("[\"workflow\",\"version\"]", read.path("input_schema").path("required").toString());
        assertEquals("[\"ACTIVE\",\"DRAFT\"]", read.path("input_schema").path("properties").path("version").path("enum").toString());
        assertFalse(read.path("input_schema").path("additionalProperties").booleanValue());
        JsonNode configure = tool("configure_workflow"), schema = configure.get("input_schema");
        assertFalse(configure.path("annotations").path("readOnlyHint").booleanValue());
        assertTrue(configure.path("annotations").path("destructiveHint").booleanValue());
        assertEquals("[true]", schema.path("properties").path("dryRun").path("enum").toString());
        assertEquals(set("dryRun"), strings(schema.get("required")));
        assertFalse(schema.get("additionalProperties").booleanValue());
        assertFalse(schema.get("properties").has("planId"));
        ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
        assertTrue(catalog.isDomainMutation("configure_workflow"));
        assertTrue(catalog.isPublished("configure_workflow"));
        assertTrue(catalog.isPublished("get_workflow_validators"));
        assertFalse(catalog.isDomainMutation("get_workflow_validators"));
        assertFalse(catalog.isPublished("configure_workflow_validators"));
    }

    @Test public void nestedSchemaIsClosedAndTemplateSpecific() throws Exception {
        JsonNode transition = tool("configure_workflow").path("input_schema").path("properties")
                .path("transitionRules").path("items");
        assertFalse(transition.get("additionalProperties").booleanValue());
        assertEquals("integer", transition.path("properties").path("actionId").path("type").textValue());
        JsonNode validators = transition.path("properties").path("addValidators");
        assertEquals(10, validators.path("maxItems").intValue());
        JsonNode item = validators.path("items");
        assertFalse(item.get("additionalProperties").booleanValue());
        assertEquals(set("template"), strings(item.get("required")));
        assertEquals("[\"comment_required\",\"required_fields\"]", item.path("properties").path("template").path("enum").toString());
        JsonNode fields = item.path("properties").path("parameters");
        assertFalse(fields.get("additionalProperties").booleanValue());
        assertEquals(set("fieldIds"), names(fields.get("properties")));
        String parametersText = fields.path("description").textValue();
        assertTrue(parametersText, parametersText.contains("required_fields") && parametersText.contains("comment_required"));
        assertEquals(50, fields.path("properties").path("fieldIds").path("maxItems").intValue());
        assertEquals(1, fields.path("properties").path("fieldIds").path("minItems").intValue());
        assertTrue(fields.path("properties").path("fieldIds").path("uniqueItems").booleanValue());
        assertEquals("string", fields.path("properties").path("fieldIds").path("items").path("type").textValue());
    }

    @Test public void providerRoutesReaderAndUnifiedRequestAndRejectsInvalidReaderArguments() throws Exception {
        WorkflowValidatorReader reader = mock(WorkflowValidatorReader.class);
        WorkflowConfigurer configurer = mock(WorkflowConfigurer.class);
        when(configurer.configure(any())).thenReturn(ConfigureWorkflowResponse.stale());
        ToolRegistry registry = new ToolRegistry(Collections.singletonList(new WorkflowTools(configurer,
                mock(WorkflowStatusReader.class), mock(WorkflowAssigner.class), mock(WorkflowCloner.class),
                mock(WorkflowDraftPublisher.class), mock(WorkflowReader.class),
                mock(WorkflowRenamer.class), mock(StatusUpdater.class), mock(WorkflowCopier.class),
                reader, mock(WorkflowConditionReader.class))));
        registry.find("get_workflow_validators").call(MAPPER.readTree("{\"workflow\":\"W\",\"version\":\"DRAFT\"}"));
        verify(reader).read("W", "DRAFT");
        registry.find("configure_workflow").call(MAPPER.readTree(REQUEST));
        org.mockito.ArgumentCaptor<ConfigureWorkflowRequest> sent = org.mockito.ArgumentCaptor.forClass(ConfigureWorkflowRequest.class);
        verify(configurer).configure(sent.capture());
        assertEquals("W", sent.getValue().existing().workflow());
        assertEquals(Integer.valueOf(21), sent.getValue().transitionRules().get(0).actionId());
        assertEquals(Arrays.asList("assignee", "customfield_10118"), sent.getValue().transitionRules().get(0).addValidators().get(0).parameters().fieldIds());
        for (String bad : Arrays.asList("{\"workflow\":\"W\",\"version\":\"ACTIVE\",\"extra\":1}", "{\"workflow\":\"W\"}", "{\"version\":\"ACTIVE\"}")) {
            try { registry.find("get_workflow_validators").call(MAPPER.readTree(bad)); fail(bad); }
            catch (InvalidRequestException expected) { assertNotNull(expected.getMessage()); }
        }
        try { registry.find("configure_workflow").call(MAPPER.readTree(REQUEST.replace("\"actionId\":21", "\"actionId\":21,\"script\":\"x\""))); fail("unknown nested key accepted"); }
        catch (InvalidRequestException expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void storedPreviewAndAnnotatedNestedDtoRoundTripWithoutOldFlatKeys() throws Exception {
        ConfigureWorkflowRequest request = RequestBodies.parse(REQUEST, ConfigureWorkflowRequest.class);
        ActionPlanCodec codec = new ActionPlanCodec();
        ConfigureWorkflowRequest again = codec.mode(codec.request(request), true, ConfigureWorkflowRequest.class);
        assertEquals(codec.request(request), codec.request(again));
        assertNull(again.planId());
        JsonNode written = MAPPER.readTree(GETTER_BLIND.writeValueAsString(request));
        assertEquals(set("dryRun", "existing", "transitionRules"), names(written));
        assertEquals(set("workflow", "version", "expectedRevision"), names(written.get("existing")));
        assertEquals(MAPPER.readTree(REQUEST), written);
    }

    @Test public void storedCreatePreviewOmitsAbsentScreenAndRemainsReadableForApply() throws Exception {
        String input = "{\"dryRun\":true,\"create\":{\"name\":\"W\",\"statuses\":[{\"name\":\"Open\"},{\"name\":\"In Progress\"}],\"initialStatus\":\"Open\",\"transitions\":[{\"key\":\"start\",\"name\":\"Start\",\"from\":\"Open\",\"to\":\"In Progress\"}]}}";
        ConfigureWorkflowRequest request = RequestBodies.parse(input, ConfigureWorkflowRequest.class);
        ActionPlanCodec codec = new ActionPlanCodec();
        String stored = codec.request(request);
        assertFalse(MAPPER.readTree(stored).path("create").path("transitions").get(0).has("screen"));
        ConfigureWorkflowRequest replay = codec.mode(stored, true, ConfigureWorkflowRequest.class);
        assertEquals("start", replay.create().getTransitions().get(0).getKey());
        assertNull(replay.create().getTransitions().get(0).getScreen());
        assertEquals(stored, codec.request(replay));
    }

    @Test public void closedNestedParametersRejectUnknownKeysWrongTypesAndNullBlocks() {
        for (String bad : invalidRequests()) {
            try { RequestBodies.parse(bad, ConfigureWorkflowRequest.class); fail("accepted " + bad); }
            catch (InvalidRequestException expected) { assertNotNull(expected.getMessage()); }
        }
    }

    private static List<String> invalidRequests() {
        List<String> bad = new ArrayList<>();
        for (String key : Arrays.asList("className", "args", "script", "functionId", "FIELD_FUNCTION_ID", "operator"))
            bad.add(REQUEST.replace("\"parameters\":{", "\"parameters\":{\"" + key + "\":{},"));
        bad.add(REQUEST.replace("\"parameters\":{", "\"parameters\":{\"extra\":1,"));
        bad.add(REQUEST.replace("\"actionId\":21", "\"actionId\":21,\"className\":\"x\""));
        bad.add(REQUEST.replace("\"existing\":{", "\"existing\":{\"extra\":1,"));
        bad.add(REQUEST.replace("\"transitionRules\":[", "\"transitionRules\":[null,"));
        bad.add(REQUEST.replace("[\"assignee\",\"customfield_10118\"]", "\"assignee\""));
        bad.add(REQUEST.replace("\"addValidators\":[", "\"addValidators\":[null,"));
        bad.add(REQUEST.replace("{\"fieldIds\":[\"assignee\",\"customfield_10118\"]}", "null"));
        return bad;
    }

    @Test public void descriptionsExplainUnifiedFlowAndPreservedRestrictions() throws Exception {
        String configure = tool("configure_workflow").get("description").textValue();
        for (String word : Arrays.asList("start_operation", "publish_workflow_draft", "required_fields", "comment_required", "screen", "SET", "UNCHANGED", "UPDATED_INACTIVE", "DRAFT_SAVED", "UNKNOWN_FIELD", "9.46.0"))
            assertTrue(word, configure.contains(word));
        for (String word : Arrays.asList("WOULD_CHANGE", "VALIDATION_ERROR", "UNKNOWN_WORKFLOW",
                "UNKNOWN_TRANSITION", "SCRIPTRUNNER_UNAVAILABLE", "SCRIPTRUNNER_VERSION_UNSUPPORTED",
                "UNSUPPORTED_DESCRIPTOR", "TRANSITION_SCREEN_MISSING", "DRAFT_EXISTS", "VERSION_CONFLICT",
                "SYSTEM_WORKFLOW", "PREVIEW_STALE", "JIRA_ERROR", "no Undo", "get_workflow_validators"))
            assertTrue(word, configure.contains(word));
        String read = tool("get_workflow_validators").get("description").textValue();
        for (String word : Arrays.asList("revision", "configure_workflow", "COMMENT_REQUIRED", "problem"))
            assertTrue(word, read.contains(word));
    }


    @Test public void unifiedResponseAnnotationsExposeDocumentedNonNullKeys() throws Exception {
        Map<String,Object> transition = new LinkedHashMap<>();
        transition.put("actionId", 21); transition.put("name", "Done");
        ConfigureWorkflowResponse response = new ConfigureWorkflowResponse(true, "WOULD_CHANGE", "W", "ACTIVE",
                "DRAFT", "rev", null, "message", List.of("diff"), List.of("warning"), List.of(transition),
                List.of(), List.of(), new WorkflowView.Consumers(1, 1, List.of("s"), List.of("P"), false), null, "token");
        JsonNode json = MAPPER.readTree(GETTER_BLIND.writeValueAsString(response));
        assertEquals(set("dryRun", "status", "outcome", "workflow", "version", "targetVersion", "revision", "message",
                "diff", "warnings", "transitions", "objects", "statuses", "consumers", "planId"), names(json));
        assertEquals("DRAFT", json.get("targetVersion").textValue());
        assertEquals(21, json.get("transitions").get(0).get("actionId").intValue());
        assertFalse(json.has("errorCode"));
        JsonNode failed = MAPPER.readTree(GETTER_BLIND.writeValueAsString(ConfigureWorkflowResponse.failed(true, "W", "UNKNOWN_FIELD", "m")));
        assertEquals(set("dryRun", "status", "outcome", "workflow", "errorCode", "message", "diff", "warnings", "transitions", "objects", "statuses"), names(failed));
        assertEquals("UNKNOWN_FIELD", failed.get("errorCode").textValue());
        assertEquals("PREVIEW_STALE", MAPPER.readTree(GETTER_BLIND.writeValueAsString(ConfigureWorkflowResponse.stale())).get("errorCode").textValue());
    }

    @Test public void theReadingAnswerSerializesWithAllDocumentedKeysIncludingNulls() throws Exception {
        WorkflowValidatorsView view = new WorkflowValidatorsView("W", "ACTIVE", "rev",
                new ScriptRunnerStatus(true, true, "9.46.0", true, null),
                Arrays.asList(new WorkflowValidatorsView.TransitionValidators(21, "Done", "7", "Screen", Arrays.asList(
                        new WorkflowValidatorsView.ValidatorView(1, "REQUIRED_FIELDS", Arrays.asList("assignee"),
                                Arrays.asList(new ValidatorFieldRef("assignee", "Assignee")), Boolean.FALSE, null, null, null, null),
                        new WorkflowValidatorsView.ValidatorView(2, "OTHER", null, null, null, "class", "c", "s", null)))));
        JsonNode json = MAPPER.readTree(GETTER_BLIND.writeValueAsString(view));
        assertEquals(set("workflow", "version", "revision", "scriptRunner", "transitions"), names(json));
        assertEquals(set("installed", "enabled", "version", "supported", "reason"), names(json.get("scriptRunner")));
        JsonNode transition = json.get("transitions").get(0);
        assertEquals(set("actionId", "name", "screenId", "screenName", "validators"), names(transition));
        Set<String> keys = set("position", "kind", "fieldIds", "fields", "conditional", "type", "className", "cannedScript", "problem");
        assertEquals(keys, names(transition.get("validators").get(0)));
        assertEquals(keys, names(transition.get("validators").get(1)));
        assertEquals(set("id", "name"), names(transition.get("validators").get(0).get("fields").get(0)));
        assertTrue(transition.get("validators").get(1).get("fieldIds").isNull());
    }
    @Test public void commentRequiredAllowsOmittedOrEmptyParametersButRejectsUnknownParameters() {
        String bare = REQUEST.replace("{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"assignee\",\"customfield_10118\"]}}", "{\"template\":\"comment_required\"}");
        ConfigureWorkflowRequest request = RequestBodies.parse(bare, ConfigureWorkflowRequest.class);
        assertEquals("comment_required", request.transitionRules().get(0).addValidators().get(0).template());
        assertNull(request.transitionRules().get(0).addValidators().get(0).parameters());
        ConfigureWorkflowRequest empty = RequestBodies.parse(bare.replace("\"comment_required\"", "\"comment_required\",\"parameters\":{}"), ConfigureWorkflowRequest.class);
        assertNull(empty.transitionRules().get(0).addValidators().get(0).parameters().fieldIds());
        try { RequestBodies.parse(bare.replace("\"comment_required\"", "\"comment_required\",\"parameters\":{\"comment\":\"x\"}"), ConfigureWorkflowRequest.class); fail("unknown parameter accepted"); }
        catch (InvalidRequestException expected) { assertNotNull(expected.getMessage()); }
    }
    @Test public void descriptionsExplainScreenViewRepairInUnifiedOperation() throws Exception {
        String configure = tool("configure_workflow").get("description").textValue();
        assertTrue(configure.contains("SET") && configure.contains("view=fieldscreen"));
        String reader = tool("get_workflow_validators").get("description").textValue();
        assertTrue(reader.contains("view=\"fieldscreen\""));
        assertTrue(reader.contains("configure_workflow") && reader.contains("SET"));
    }

    private static JsonNode tool(String name) throws Exception {
        try (InputStream in = ValidatorToolsContractTest.class.getResourceAsStream("/llm/workflow-tools.json")) {
            assertNotNull(in);
            for (JsonNode tool : MAPPER.readTree(in)) if (name.equals(tool.get("name").textValue())) return tool;
        }
        throw new AssertionError("tool not described: " + name);
    }
    private static Set<String> set(String... values) { return new TreeSet<>(Arrays.asList(values)); }
    private static Set<String> names(JsonNode object) {
        Set<String> values = new TreeSet<>(); object.fieldNames().forEachRemaining(values::add); return values;
    }
    private static Set<String> strings(JsonNode array) {
        Set<String> values = new TreeSet<>(); for (JsonNode value : array) values.add(value.textValue()); return values;
    }
}
