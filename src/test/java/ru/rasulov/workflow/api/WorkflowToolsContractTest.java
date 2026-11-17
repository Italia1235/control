package ru.rasulov.workflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import ru.rasulov.workflow.api.dto.StatusCategoryKey;
import ru.rasulov.workflow.api.dto.StatusResponse;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.WorkflowErrorCode;
import ru.rasulov.workflow.api.dto.WorkflowStatusView;

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

/** Keeps {@code llm/workflow-tools.json} and the JSON shape of the workflow DTOs from drifting apart. */
public class WorkflowToolsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Jira's REST Jackson mapper does not auto-detect {@code getX()}; DTOs must not rely on it. Serializing with this
     * mapper fails when a getter lacks an explicit {@code @JsonProperty}.
     */
    private static final ObjectMapper GETTER_BLIND_MAPPER = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false);


    @Test
    public void everyLimitInTheToolSchemaEqualsTheLimitInCode() throws Exception {
        JsonNode properties = tool("configure_workflow").get("input_schema").get("properties").get("create").get("properties");
        JsonNode status = properties.get("statuses").get("items").get("properties");
        JsonNode transition = properties.get("transitions").get("items").get("properties");

        assertEquals(WorkflowLimits.MAX_NAME_LENGTH, properties.get("name").get("maxLength").intValue());
        assertEquals(WorkflowLimits.MIN_STATUSES, properties.get("statuses").get("minItems").intValue());
        assertEquals(WorkflowLimits.MAX_STATUSES, properties.get("statuses").get("maxItems").intValue());
        assertEquals(WorkflowLimits.MAX_STATUS_NAME_LENGTH, status.get("name").get("maxLength").intValue());
        assertEquals(WorkflowLimits.MAX_TRANSITIONS, properties.get("transitions").get("maxItems").intValue());
        assertEquals(WorkflowLimits.MAX_NAME_LENGTH, transition.get("name").get("maxLength").intValue());
        JsonNode changes = tool("configure_workflow").path("input_schema").path("properties")
                .path("existing").path("properties").path("changes");
        assertEquals(1, changes.path("minItems").intValue());
        assertEquals(WorkflowLimits.MAX_CHANGES, changes.path("maxItems").intValue());
        assertEquals(WorkflowLimits.MAX_NAME_LENGTH,
                changes.path("items").path("properties").path("transitionKey").path("maxLength").intValue());
    }

    @Test
    public void categoryEnumInTheToolSchemaIsTheCategoryEnumInCode() throws Exception {
        JsonNode category = tool("configure_workflow").get("input_schema").get("properties").get("create").get("properties").get("statuses").get("items")
                .get("properties").get("category");

        List<String> inSchema = new ArrayList<String>();
        for (JsonNode value : category.get("enum")) {
            inSchema.add(value.textValue());
        }
        List<String> inCode = new ArrayList<String>();
        for (StatusCategoryKey key : StatusCategoryKey.values()) {
            inCode.add(key.name());
        }
        assertEquals(inCode, inSchema);
    }


    @Test
    public void statusLookupTakesTheQueryAndABoundedLimit() throws Exception {
        JsonNode schema = tool("list_statuses").get("input_schema");
        assertEquals("object", schema.get("type").textValue());
        assertEquals(new TreeSet<String>(Arrays.asList("query", "limit", "names")), names(schema.get("properties")));
        assertEquals(ru.rasulov.common.api.LookupLimits.MAX_REFERENCE_LIST_SIZE,
                schema.get("properties").get("limit").get("maximum").intValue());
    }

    @Test
    public void statusLookupByNamesIsABoundedArrayOfNonEmptyStrings() throws Exception {
        JsonNode schema = tool("list_statuses").get("input_schema");
        JsonNode names = schema.get("properties").get("names");
        assertEquals("array", names.get("type").textValue());
        assertEquals(1, names.get("minItems").intValue());
        assertEquals(30, names.get("maxItems").intValue());
        assertEquals("string", names.get("items").get("type").textValue());
        assertEquals(1, names.get("items").get("minLength").intValue());
        assertFalse(names.get("items").has("maxLength"));
        assertFalse(schema.get("additionalProperties").booleanValue());
    }

    @Test
    public void requestDeserializesFromToolPayloadIncludingFromAny() throws Exception {
        String json = "{\"dryRun\":true,\"create\":{\"name\":\"Bug flow\",\"description\":\"d\","
                + "\"statuses\":[{\"name\":\"Open\",\"category\":\"TO_DO\"},{\"name\":\"Done\",\"category\":\"DONE\","
                + "\"description\":\"finished\"}],\"initialStatus\":\"Open\","
                + "\"transitions\":[{\"key\":\"finish\",\"name\":\"Finish\",\"from\":\"Open\",\"to\":\"Done\",\"screen\":\"12000\"},"
                + "{\"key\":\"reopen\",\"name\":\"Reopen\",\"fromAny\":true,\"to\":\"Open\"}]}}";

        ConfigureWorkflowRequest envelope = MAPPER.readValue(json, ConfigureWorkflowRequest.class);
        WorkflowDefinition request = envelope.create();

        assertTrue(envelope.isDryRun());
        assertEquals("Bug flow", request.getName());
        assertEquals("d", request.getDescription());
        assertEquals("TO_DO", request.getStatuses().get(0).getCategory());
        assertEquals("finished", request.getStatuses().get(1).getDescription());
        assertEquals("Open", request.getInitialStatus());
        TransitionDefinition ordinary = request.getTransitions().get(0);
        assertEquals("finish", ordinary.getKey());
        assertEquals("Open", ordinary.getFrom());
        assertEquals(false, ordinary.isFromAny());
        assertEquals("12000", ordinary.getScreen());
        TransitionDefinition global = request.getTransitions().get(1);
        assertEquals(null, global.getFrom());
        assertEquals(true, global.isFromAny());
        assertEquals("Open", global.getTo());
    }


    @Test
    public void responseSerializesWithAllDocumentedKeysAndOptionalFailureDetails() throws Exception {
        ConfigureWorkflowResponse created = new ConfigureWorkflowResponse(false, "CREATED", "Bug flow", "ACTIVE",
                "ACTIVE", "revision", null, null, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Arrays.asList(new WorkflowStatusView("Open", "1", "TO_DO", false),
                        new WorkflowStatusView("Review", "10100", "IN_PROGRESS", true)),
                Collections.emptyMap(), Collections.singletonMap("steps", Collections.emptyList()), null);
        ConfigureWorkflowResponse failed = ConfigureWorkflowResponse.failed(true, "Bad", "UNKNOWN_STATUS", "msg");
        JsonNode ok = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(created));
        JsonNode bad = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(failed));

        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "status", "outcome", "workflow", "version",
                "targetVersion", "revision", "diff", "warnings", "transitions", "objects", "statuses", "consumers",
                "execution")), names(ok));
        assertEquals("CREATED", ok.get("status").textValue());
        assertEquals("SUCCESS", ok.get("outcome").textValue());
        assertEquals(false, ok.has("errorCode"));
        assertEquals(new TreeSet<String>(Arrays.asList("name", "id", "category", "isNew")), names(ok.get("statuses").get(0)));
        assertEquals(true, ok.get("statuses").get(1).get("isNew").booleanValue());
        JsonNode ordinary = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(
                new TransitionDefinition("finish", "Finish", "Open", false, "Done", null, null)));
        JsonNode global = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(
                new TransitionDefinition("reopen", "Reopen", null, true, "Open", null, null)));
        assertEquals("an absent screen is omitted, so a stored plan stays readable",
                new TreeSet<String>(Arrays.asList("key", "name", "from", "fromAny", "to", "resolution")), names(ordinary));
        assertEquals(new TreeSet<String>(Arrays.asList("key", "name", "from", "fromAny", "to", "screen", "resolution")),
                names(MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(
                        new TransitionDefinition("finish", "Finish", "Open", false, "Done", "12000", null)))));
        assertEquals(true, global.get("fromAny").booleanValue());
        assertTrue(global.get("from").isNull());
        assertEquals("FAILED", bad.get("status").textValue());
        assertEquals(true, bad.get("dryRun").booleanValue());
        assertEquals("UNKNOWN_STATUS", bad.get("errorCode").textValue());
        assertEquals("msg", bad.get("message").textValue());
        assertEquals(0, bad.get("statuses").size());
        assertEquals(0, bad.get("transitions").size());
    }

    @Test
    public void statusListingSerializesWithAllDocumentedKeys() throws Exception {
        JsonNode json = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(
                new StatusResponse("3", "In Progress", "IN_PROGRESS", "d")));

        assertEquals(new TreeSet<String>(Arrays.asList("id", "name", "category", "description")), names(json));
        assertEquals("IN_PROGRESS", json.get("category").textValue());
    }

    private static JsonNode tool(String name) throws Exception {
        InputStream in = WorkflowToolsContractTest.class.getResourceAsStream("/llm/workflow-tools.json");
        assertNotNull("llm/workflow-tools.json is missing from resources", in);
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

}
