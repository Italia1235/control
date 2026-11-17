package ru.rasulov.workflow.impl.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import org.junit.Test;

import ru.rasulov.workflow.api.dto.edit.EditErrorCode;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.api.dto.edit.RenameWorkflowResponse;
import ru.rasulov.workflow.api.dto.edit.UpdateStatusResponse;
import ru.rasulov.workflow.api.dto.edit.WorkflowView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Keeps the workflow reader, unified structural configuration and remaining point tools aligned with their DTOs. */
public class EditToolsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Jira's REST mapper does not auto-detect getters: every documented key needs {@code @JsonProperty}. */
    private static final ObjectMapper GETTER_BLIND = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false)
            .configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false);

    @Test
    public void everyMutatingToolIsAPreviewOnlyDestructiveToolAndTheReaderIsReadOnly() throws Exception {
        for (String name : Arrays.asList("configure_workflow", "rename_workflow", "update_status")) {
            JsonNode tool = tool(name);
            assertFalse(name, tool.get("annotations").get("readOnlyHint").booleanValue());
            assertTrue(name, tool.get("annotations").get("destructiveHint").booleanValue());
            assertEquals(name, "[true]", tool.get("input_schema").get("properties").get("dryRun").get("enum").toString());
            assertFalse(name, tool.get("input_schema").get("additionalProperties").booleanValue());
            assertTrue(name, tool.get("description").textValue().contains("start_operation"));
            assertTrue(name, tool.get("description").textValue().toLowerCase(java.util.Locale.ROOT).contains("no undo"));
        }
        assertTrue(tool("get_workflow").get("annotations").get("readOnlyHint").booleanValue());
        assertEquals("[\"workflow\",\"version\"]", tool("get_workflow").get("input_schema").get("required").toString());
    }

    @Test
    public void theSchemaOffersExactlyTheOperationsThePatcherImplements() throws Exception {
        JsonNode ops = changes().get("items")
                .get("properties").get("op").get("enum");
        Set<String> schema = new TreeSet<String>();
        for (JsonNode op : ops) schema.add(op.textValue());
        assertEquals(new TreeSet<String>(WorkflowDescriptorPatcher.OPS), schema);
        String description = tool("configure_workflow").get("description").textValue();
        for (String op : WorkflowDescriptorPatcher.OPS) assertTrue(op, description.contains(op));
    }

    @Test
    public void everyChangeFieldOfTheSchemaIsAFieldOfTheRequestAndTheOtherWayRound() throws Exception {
        JsonNode props = changes().get("items")
                .get("properties");
        Set<String> schema = names(props);
        ConfigureWorkflowRequest request = MAPPER.readValue("{\"dryRun\":true,\"existing\":{\"workflow\":\"W\",\"version\":\"ACTIVE\","
                + "\"expectedRevision\":\"r\",\"changes\":[{\"op\":\"ADD_TRANSITION\",\"transitionKey\":\"new\","
                + "\"actionId\":3,\"stepId\":1,\"statusId\":\"2\",\"clientKey\":\"k\",\"name\":\"n\",\"description\":\"d\","
                + "\"source\":{\"stepId\":1,\"clientKey\":\"k\",\"fromAny\":false},\"targetStepId\":2,"
                + "\"targetClientKey\":\"t\",\"screen\":{\"action\":\"SET\",\"screenId\":4},"
                + "\"resolution\":{\"action\":\"SET\",\"value\":\"1\"}}]}}", ConfigureWorkflowRequest.class);
        JsonNode written = MAPPER.readTree(GETTER_BLIND.writeValueAsString(request.existing().changes().get(0)));
        // newStatus is legal only on ADD_STATUS, so it is proven by a second change of that op.
        ConfigureWorkflowRequest addStatus = MAPPER.readValue("{\"dryRun\":true,\"existing\":{\"workflow\":\"W\",\"version\":\"ACTIVE\","
                + "\"expectedRevision\":\"r\",\"changes\":[{\"op\":\"ADD_STATUS\",\"newStatus\":{\"name\":\"N\",\"category\":\"TO_DO\",\"description\":\"d\"}}]}}",
                ConfigureWorkflowRequest.class);
        Set<String> union = new TreeSet<String>(names(written));
        union.addAll(names(MAPPER.readTree(GETTER_BLIND.writeValueAsString(addStatus.existing().changes().get(0)))));
        assertEquals(schema, union);
        assertEquals("SET", request.existing().changes().get(0).getResolution().getAction());
    }

    @Test
    public void everyOutcomeAndErrorCodeIsExplainedToTheModel() throws Exception {
        String edit = tool("configure_workflow").get("description").textValue();
        for (EditErrorCode code : EditErrorCode.values()) {
            if (code != EditErrorCode.VALIDATION_ERROR) assertTrue(code.name(), edit.contains(code.name()));
        }
        assertTrue(edit.contains("UPDATED_INACTIVE"));
        assertTrue(edit.contains("DRAFT_SAVED"));
        assertTrue(edit.contains("publish_workflow_draft"));
        assertTrue(edit.contains("get_workflow"));
        String rename = tool("rename_workflow").get("description").textValue();
        for (RenameWorkflowResponse.ErrorCode code : RenameWorkflowResponse.ErrorCode.values()) {
            if (code != RenameWorkflowResponse.ErrorCode.VALIDATION_ERROR
                    && code != RenameWorkflowResponse.ErrorCode.PREVIEW_STALE
                    && code != RenameWorkflowResponse.ErrorCode.UNKNOWN_WORKFLOW) {
                assertTrue(code.name(), rename.contains(code.name()));
            }
        }
        String status = tool("update_status").get("description").textValue();
        assertTrue(status.contains("GLOBAL"));
        assertTrue(status.contains("UPDATED"));
    }

    @Test
    public void responsesSerializeWithAllDocumentedKeysIncludingNulls() throws Exception {
        ConfigureWorkflowResponse would = new ConfigureWorkflowResponse(true, "WOULD_CHANGE", "W", "ACTIVE",
                "DRAFT", null, null, "m", Arrays.asList("d"), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), null, null, "token");
        JsonNode configured = MAPPER.readTree(GETTER_BLIND.writeValueAsString(would));
        assertEquals("WOULD_CHANGE", configured.path("status").asText());
        assertEquals("token", configured.path("planId").asText());

        WorkflowView view = new WorkflowView("W", "ACTIVE", "rev", 5L, "d", false, true, false,
                Arrays.asList(new WorkflowView.StepView(1, "1", "Open", "new", "", true)),
                Arrays.asList(new WorkflowView.TransitionView(11, "LOCAL", "Start", Arrays.asList(1), Arrays.asList(2),
                        "7", new WorkflowView.Rule("SET", "1", "Fixed", null), new ArrayList<String>())),
                Arrays.asList("c"), new WorkflowView.Consumers(0, 0, null, null, false), Collections.<String>emptyList());
        JsonNode json = MAPPER.readTree(GETTER_BLIND.writeValueAsString(view));
        assertEquals(new TreeSet<String>(Arrays.asList("workflow", "version", "revision", "nativeId", "description",
                "system", "active", "hasDraft", "steps", "transitions", "capabilities", "consumers", "draftDiff")),
                names(json));
        assertEquals(new TreeSet<String>(Arrays.asList("actionId", "kind", "name", "sourceStepIds", "targetStepIds",
                "screenId", "resolution", "unsupported")), names(json.get("transitions").get(0)));
        assertEquals(new TreeSet<String>(Arrays.asList("stepId", "statusId", "name", "category", "description",
                "initial")), names(json.get("steps").get(0)));
        assertNotNull(MAPPER.readTree(GETTER_BLIND.writeValueAsString(RenameWorkflowResponse.stale())).get("errorCode"));
        assertNotNull(MAPPER.readTree(GETTER_BLIND.writeValueAsString(UpdateStatusResponse.stale())).get("errorCode"));

    }

    private static JsonNode changes() throws Exception {
        return tool("configure_workflow").path("input_schema").path("properties").path("existing")
                .path("properties").path("changes");
    }

    private static JsonNode tool(String name) throws Exception {
        try (InputStream in = EditToolsContractTest.class.getResourceAsStream("/llm/workflow-tools.json")) {
            assertNotNull(in);
            for (JsonNode tool : MAPPER.readTree(in)) {
                if (name.equals(tool.get("name").textValue())) return tool;
            }
        }
        throw new AssertionError("tool not described: " + name);
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new TreeSet<String>();
        for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) names.add(it.next());
        return names;
    }
}
