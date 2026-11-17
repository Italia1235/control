package ru.rasulov.workflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.publish.DraftChanges;
import ru.rasulov.workflow.api.dto.publish.PublishErrorCode;
import ru.rasulov.workflow.api.dto.publish.PublishStatus;
import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftRequest;
import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftResponse;

import java.io.InputStream;
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

/** Keeps {@code publish_workflow_draft} in {@code llm/workflow-tools.json} and its DTOs from drifting apart. */
public class PublishDraftToolContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Jira's REST mapper does not auto-detect getters: every documented key needs {@code @JsonProperty}. */
    private static final ObjectMapper GETTER_BLIND_MAPPER = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false)
            .configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false);

    @Test
    public void theToolIsAPreviewOnlyDestructiveMutationOfOneWorkflow() throws Exception {
        JsonNode tool = tool("publish_workflow_draft");
        JsonNode schema = tool.get("input_schema");

        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "workflow")), names(schema.get("properties")));
        assertEquals("[\"dryRun\",\"workflow\"]", schema.get("required").toString());
        assertEquals("[true]", schema.get("properties").get("dryRun").get("enum").toString());
        assertFalse(schema.get("additionalProperties").booleanValue());
        assertFalse(tool.get("annotations").get("readOnlyHint").booleanValue());
        assertTrue(tool.get("annotations").get("destructiveHint").booleanValue());
    }

    @Test
    public void theModelIsToldEveryOutcomeAndTheContinuation() throws Exception {
        String description = tool("publish_workflow_draft").get("description").textValue();
        for (PublishErrorCode code : PublishErrorCode.values()) {
            if (code != PublishErrorCode.VALIDATION_ERROR) {
                assertTrue(code + " is not in the description", description.contains(code.name()));
            }
        }
        for (PublishStatus status : PublishStatus.values()) {
            assertTrue(status + " is not in the description", description.contains(status.name()));
        }
        assertTrue(description.contains("start_operation"));
        assertTrue("after publishing, the clone needs a new preview", description.contains("clone_project_workflows"));
    }

    @Test
    public void requestDeserializesPreviewAndTokenOnlyApply() throws Exception {
        PublishWorkflowDraftRequest preview = MAPPER.readValue("{\"dryRun\":true,\"workflow\":\"Lot Buy\"}",
                PublishWorkflowDraftRequest.class);
        assertTrue(preview.isDryRun());
        assertEquals("Lot Buy", preview.getWorkflow());
        assertNull(preview.getPlanId());

        PublishWorkflowDraftRequest apply = MAPPER.readValue("{\"dryRun\":false,\"planId\":\"token\"}",
                PublishWorkflowDraftRequest.class);
        assertFalse(apply.isDryRun());
        assertNull(apply.getWorkflow());
        assertEquals("token", apply.getPlanId());
        assertNull(MAPPER.readValue("{\"workflow\":\"Lot Buy\"}", PublishWorkflowDraftRequest.class).isDryRun());
    }

    @Test
    public void responseSerializesWithAllDocumentedKeysIncludingNulls() throws Exception {
        PublishWorkflowDraftResponse would = PublishWorkflowDraftResponse.wouldPublish("Lot Buy",
                new DraftChanges(Collections.singletonList("Review"), Collections.<String>emptyList(),
                        Collections.singletonList("Send to review"), Collections.<String>emptyList(),
                        Collections.singletonList("Start"), true),
                2, 2, Arrays.asList("Lab", "Lot"), "warning").withPlan("token", 123L);
        PublishWorkflowDraftResponse failed = PublishWorkflowDraftResponse.failed(true, "Lot Bye",
                PublishErrorCode.UNKNOWN_WORKFLOW, "msg", Collections.singletonList("Lot Buy"));

        JsonNode ok = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(would));
        JsonNode bad = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(failed));

        Set<String> keys = new TreeSet<String>(Arrays.asList("dryRun", "status", "workflow", "changes",
                "affectedSchemes", "affectedProjects", "affectedProjectNames", "message", "errorCode", "suggestions",
                "planId", "expiresAt", "undoWarning"));
        assertEquals(keys, names(ok));
        assertEquals(keys, names(bad));
        assertEquals(new TreeSet<String>(Arrays.asList("addedStatuses", "removedStatuses", "addedTransitions",
                "removedTransitions", "changedTransitions", "descriptorChanged")), names(ok.get("changes")));
        assertEquals("WOULD_PUBLISH", ok.get("status").textValue());
        assertEquals("token", ok.get("planId").textValue());
        assertEquals(2, ok.get("affectedProjects").intValue());
        assertTrue(ok.get("errorCode").isNull());
        assertEquals("FAILED", bad.get("status").textValue());
        assertEquals("UNKNOWN_WORKFLOW", bad.get("errorCode").textValue());
        assertTrue("unknown consequences are not zero", bad.get("affectedProjects").isNull());
        assertTrue(bad.get("changes").isNull());
        assertTrue(bad.get("planId").isNull());
    }

    private static JsonNode tool(String name) throws Exception {
        InputStream in = PublishDraftToolContractTest.class.getResourceAsStream("/llm/workflow-tools.json");
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
