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

/** Catalog, closed nested DTO, provider routing and durable-plan contract for conditions. */
public class ConditionToolsContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper GETTER_BLIND = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false)
            .configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false);
    private static final String REQUEST = "{\"dryRun\":true,\"existing\":{\"workflow\":\"W\",\"version\":\"ACTIVE\",\"expectedRevision\":\"r\"},\"transitionRules\":[{\"actionId\":21,\"addConditions\":{\"operator\":\"AND\",\"rules\":[{\"template\":\"project_role\",\"parameters\":{\"roles\":[\"Administrators\",\"10101\"],\"invert\":true}}]}}]}";

    @Test public void catalogKeepsReaderAndPublishesOnlyUnifiedMutation() throws Exception {
        JsonNode read = tool("get_workflow_conditions");
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
        assertTrue(catalog.isPublished("get_workflow_conditions"));
        assertFalse(catalog.isDomainMutation("get_workflow_conditions"));
        assertFalse(catalog.isPublished("configure_workflow_conditions"));
    }

    @Test public void nestedSchemaIsClosedAndTemplateSpecific() throws Exception {
        JsonNode transition = tool("configure_workflow").path("input_schema").path("properties")
                .path("transitionRules").path("items");
        assertFalse(transition.get("additionalProperties").booleanValue());
        assertEquals("integer", transition.path("properties").path("actionId").path("type").textValue());
        JsonNode conditions = transition.path("properties").path("addConditions");
        assertFalse(conditions.get("additionalProperties").booleanValue());
        assertEquals("[\"AND\"]", conditions.path("properties").path("operator").path("enum").toString());
        assertEquals(set("operator", "rules"), strings(conditions.get("required")));
        assertEquals(10, conditions.path("properties").path("rules").path("maxItems").intValue());
        JsonNode item = conditions.path("properties").path("rules").path("items");
        assertFalse(item.get("additionalProperties").booleanValue());
        assertEquals(set("template", "parameters"), strings(item.get("required")));
        assertEquals("[\"group\",\"project_role\"]", item.path("properties").path("template").path("enum").toString());
        JsonNode parameters = item.path("properties").path("parameters");
        assertFalse(parameters.get("additionalProperties").booleanValue());
        assertEquals(set("group", "roles", "invert"), names(parameters.get("properties")));
        String conditionText = parameters.path("description").textValue();
        assertTrue(conditionText, conditionText.contains("group") && conditionText.contains("project_role"));
        assertEquals(1, parameters.path("properties").path("group").path("minLength").intValue());
        JsonNode roles = parameters.path("properties").path("roles");
        assertEquals(20, roles.path("maxItems").intValue());
        assertEquals(1, roles.path("minItems").intValue());
        assertTrue(roles.path("uniqueItems").booleanValue());
        assertEquals("[\"string\",\"integer\"]", roles.path("items").path("type").toString());
        assertEquals(1, roles.path("items").path("minLength").intValue());
        assertEquals(1, roles.path("items").path("minimum").intValue());
        assertEquals("boolean", parameters.path("properties").path("invert").path("type").textValue());
    }

    @Test public void providerRoutesReaderAndUnifiedRequestAndRejectsInvalidReaderArguments() throws Exception {
        WorkflowConditionReader reader = mock(WorkflowConditionReader.class);
        WorkflowConfigurer configurer = mock(WorkflowConfigurer.class);
        when(configurer.configure(any())).thenReturn(ConfigureWorkflowResponse.stale());
        ToolRegistry registry = new ToolRegistry(Collections.singletonList(new WorkflowTools(configurer,
                mock(WorkflowStatusReader.class), mock(WorkflowAssigner.class), mock(WorkflowCloner.class),
                mock(WorkflowDraftPublisher.class), mock(WorkflowReader.class),
                mock(WorkflowRenamer.class), mock(StatusUpdater.class), mock(WorkflowCopier.class),
                mock(WorkflowValidatorReader.class), reader)));
        registry.find("get_workflow_conditions").call(MAPPER.readTree("{\"workflow\":\"W\",\"version\":\"DRAFT\"}"));
        verify(reader).read("W", "DRAFT");
        registry.find("configure_workflow").call(MAPPER.readTree(REQUEST));
        org.mockito.ArgumentCaptor<ConfigureWorkflowRequest> sent = org.mockito.ArgumentCaptor.forClass(ConfigureWorkflowRequest.class);
        verify(configurer).configure(sent.capture());
        assertEquals("W", sent.getValue().existing().workflow());
        assertEquals(Integer.valueOf(21), sent.getValue().transitionRules().get(0).actionId());
        assertEquals(Arrays.asList("Administrators", "10101"), sent.getValue().transitionRules().get(0).addConditions().rules().get(0).parameters().roles());
        assertEquals(Boolean.TRUE, sent.getValue().transitionRules().get(0).addConditions().rules().get(0).parameters().invert());
        for (String bad : Arrays.asList("{\"workflow\":\"W\",\"version\":\"ACTIVE\",\"extra\":1}", "{\"workflow\":\"W\"}", "{\"version\":\"ACTIVE\"}")) {
            try { registry.find("get_workflow_conditions").call(MAPPER.readTree(bad)); fail(bad); }
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
        bad.add(REQUEST.replace("[\"Administrators\",\"10101\"]", "{}"));
        bad.add(REQUEST.replace("\"invert\":true", "\"invert\":{}"));
        bad.add(REQUEST.replace("\"addConditions\":{", "\"addConditions\":{\"script\":\"x\","));
        bad.add(REQUEST.replace("{\"roles\":[\"Administrators\",\"10101\"],\"invert\":true}", "null"));
        bad.add(REQUEST.replace("\"rules\":[", "\"rules\":[null,"));
        return bad;
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

    @Test public void readerTreeAnnotationsPreserveAllKeysIncludingNulls() throws Exception {
        WorkflowConditionsView.ConditionNode root = WorkflowConditionsView.ConditionNode.compound(1, "AND", List.of(
                WorkflowConditionsView.ConditionNode.group(1, "g"),
                WorkflowConditionsView.ConditionNode.projectRole(2, List.of(10101L), List.of(new WorkflowConditionsView.RoleRef(10101L, "Administrators")), true),
                WorkflowConditionsView.ConditionNode.other(3, "class", "c", "s")));
        WorkflowConditionsView view = new WorkflowConditionsView("W", "ACTIVE", "rev", new ScriptRunnerStatus(true, true, "9.46.0", true, null),
                List.of(new WorkflowConditionsView.TransitionConditions(21, "Done", root), new WorkflowConditionsView.TransitionConditions(22, "Empty", null)));
        JsonNode json = MAPPER.readTree(GETTER_BLIND.writeValueAsString(view));
        assertEquals(set("workflow", "version", "revision", "scriptRunner", "transitions"), names(json));
        assertEquals(set("installed", "enabled", "version", "supported", "reason"), names(json.get("scriptRunner")));
        assertEquals(set("actionId", "name", "root"), names(json.get("transitions").get(0)));
        Set<String> keys = set("position", "kind", "operator", "children", "group", "roleIds", "roles", "inverted", "type", "className", "cannedScript");
        JsonNode tree = json.get("transitions").get(0).get("root");
        assertEquals(keys, names(tree));
        for (JsonNode child : tree.get("children")) assertEquals(keys, names(child));
        assertEquals(set("id", "name"), names(tree.get("children").get(1).get("roles").get(0)));
        assertTrue(tree.get("children").get(0).get("roles").isNull());
        assertTrue(json.get("transitions").get(1).get("root").isNull());
    }
    @Test public void groupParametersRemainTypedAndOptionalRolePropertiesRemainAbsent() {
        String group = REQUEST.replace("\"project_role\"", "\"group\"").replace("\"roles\":[\"Administrators\",\"10101\"],\"invert\":true", "\"group\":\"g\"");
        ConfigureWorkflowRequest.Parameters parameters = RequestBodies.parse(group, ConfigureWorkflowRequest.class).transitionRules().get(0).addConditions().rules().get(0).parameters();
        assertEquals("g", parameters.group()); assertNull(parameters.roles()); assertNull(parameters.invert());
        try { RequestBodies.parse(group.replace("\"group\":\"g\"", "\"group\":[\"g\"]"), ConfigureWorkflowRequest.class); fail("array group accepted"); }
        catch (InvalidRequestException expected) { assertNotNull(expected.getMessage()); }
    }

    private static JsonNode tool(String name) throws Exception {
        try (InputStream in = ConditionToolsContractTest.class.getResourceAsStream("/llm/workflow-tools.json")) {
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
