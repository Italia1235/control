package ru.rasulov.workflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import org.junit.Test;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.workflow.api.dto.assign.AssignErrorCode;
import ru.rasulov.workflow.api.dto.assign.AssignVerdict;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowRequest;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowResponse;
import ru.rasulov.workflow.api.dto.assign.IssueTypeAssignment;
import ru.rasulov.workflow.api.dto.assign.IssueTypeWorkflowView;
import ru.rasulov.workflow.api.dto.assign.ProjectWorkflowsResponse;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.assign.SchemeChange;
import ru.rasulov.workflow.api.dto.assign.SchemeView;
import ru.rasulov.common.api.dto.StatusUsage;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
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

/** Keeps the assignment tools in {@code llm/workflow-tools.json} and the JSON shape of their DTOs from drifting apart. */
public class WorkflowAssignToolsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Jira's REST mapper does not auto-detect {@code getX()}: serializing fails to show a getter without {@code @JsonProperty}. */
    private static final ObjectMapper GETTER_BLIND_MAPPER = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false)
            .configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false);

    @Test
    public void publicPreviewPropertiesMatchTheRequestDtoExceptTheInternalApplyToken() throws Exception {
        JsonNode schema = tool("assign_workflow").get("input_schema");

        assertEquals("object", schema.get("type").textValue());
        Set<String> previewProperties = jsonPropertiesOf(AssignWorkflowRequest.class);
        previewProperties.remove("planId");
        assertEquals(previewProperties, names(schema.get("properties")));
        assertEquals(Arrays.asList("dryRun", "project", "workflow"), requiredOf(schema));
    }

    @Test
    public void readToolOffersOptionalIssueCounts() throws Exception {
        JsonNode schema = tool("get_project_workflows").get("input_schema");

        assertEquals(new TreeSet<String>(Arrays.asList("project", "includeIssueCounts")), names(schema.get("properties")));
        assertEquals(Arrays.asList("project"), requiredOf(schema));
    }

    @Test
    public void everyLimitInTheToolIsTheLimitInCode() throws Exception {
        JsonNode assign = tool("assign_workflow");

        assertEquals(WorkflowAssignLimits.MAX_ISSUE_TYPES,
                assign.get("input_schema").get("properties").get("issueTypes").get("maxItems").intValue());
        assertTrue("the description must state the issue limit the code enforces",
                assign.get("description").textValue().contains(WorkflowAssignLimits.MAX_ISSUES_TO_MIGRATE + " issues"));
    }

    @Test
    public void everyErrorCodeThatNeedsAnActionIsExplainedToTheModel() throws Exception {
        String description = tool("assign_workflow").get("description").textValue();
        for (AssignErrorCode code : AssignErrorCode.values()) {
            if (code != AssignErrorCode.VALIDATION_ERROR) {
                assertTrue(code + " is not in the description", description.contains(code.name()));
            }
        }
    }

    @Test
    public void requestDeserializesFromToolPayloadAndNewSchemeDefaultsToFalse() throws Exception {
        AssignWorkflowRequest request = MAPPER.readValue("{\"dryRun\":true,\"project\":\"ABC\",\"workflow\":\"Bug flow\","
                + "\"issueTypes\":[\"Bug\",\"10002\"],\"statusMappings\":[{\"from\":\"Review\",\"to\":\"Done\"}]}",
                AssignWorkflowRequest.class);

        assertTrue(request.isDryRun());
        assertEquals("ABC", request.getProject());
        assertEquals("Bug flow", request.getWorkflow());
        assertEquals(Arrays.asList("Bug", "10002"), request.getIssueTypes());
        assertFalse(request.isAllIssueTypes());
        assertFalse(request.isNewScheme());
        assertEquals("Review", request.getStatusMappings().get(0).getFrom());
        assertEquals("Done", request.getStatusMappings().get(0).getTo());

        AssignWorkflowRequest all = MAPPER.readValue("{\"project\":\"ABC\",\"workflow\":\"W\",\"allIssueTypes\":true,"
                + "\"newScheme\":true}", AssignWorkflowRequest.class);
        assertTrue(all.isAllIssueTypes());
        assertTrue(all.isNewScheme());
        assertTrue(all.getIssueTypes().isEmpty());
    }

    @Test
    public void assignResponseSerializesWithAllDocumentedKeysIncludingNulls() throws Exception {
        IssueTypeAssignment bug = new IssueTypeAssignment(new IssueTypeRef("10001", "Bug"), "jira", "Bug flow",
                AssignVerdict.MIGRATE, 12, Arrays.asList(new StatusUsage("Open", 7, true), new StatusUsage("Done", 5, true)));
        AssignWorkflowResponse would = AssignWorkflowResponse.wouldAssign(new ProjectRef("ABC", "Alpha"), "Bug flow",
                new SchemeChange(SchemeAction.CREATE, null, "ABC: Workflow Scheme"), Arrays.asList(bug), null);
        AssignWorkflowResponse failed = AssignWorkflowResponse.failed(false, null, "Nope", AssignErrorCode.UNKNOWN_WORKFLOW,
                "msg", Collections.singletonList("Bug flow"), null);
        AssignWorkflowResponse partial = AssignWorkflowResponse.partiallyAssigned(new ProjectRef("ABC", "Alpha"),
                "Bug flow", new SchemeChange(SchemeAction.UPDATE, 10100L, "ABC Workflow Scheme"),
                Arrays.asList(bug), "Final sweep failed.");

        JsonNode ok = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(would));
        JsonNode bad = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(failed));
        JsonNode partialJson = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(partial));

        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "status", "project", "workflow", "scheme", "issueTypes",
                "message", "errorCode", "suggestions", "planId", "expiresAt", "undoWarning")), names(ok));
        assertEquals("WOULD_ASSIGN", ok.get("status").textValue());
        assertTrue(ok.get("errorCode").isNull());
        assertTrue(ok.get("message").isNull());
        assertEquals(new TreeSet<String>(Arrays.asList("action", "id", "name")), names(ok.get("scheme")));
        assertTrue("a scheme a dry run would create has no id yet", ok.get("scheme").get("id").isNull());
        JsonNode type = ok.get("issueTypes").get(0);
        assertEquals(new TreeSet<String>(Arrays.asList("issueType", "from", "to", "verdict", "issues", "statuses")),
                names(type));
        assertEquals("MIGRATE", type.get("verdict").textValue());
        assertEquals(new TreeSet<String>(Arrays.asList("name", "issues", "inWorkflow", "mappedTo")),
                names(type.get("statuses").get(0)));
        assertEquals("PARTIALLY_ASSIGNED", partialJson.get("status").textValue());
        assertEquals("Final sweep failed.", partialJson.get("message").textValue());


        assertEquals("FAILED", bad.get("status").textValue());
        assertTrue("the project is unknown", bad.get("project").isNull());
        assertTrue(bad.get("scheme").isNull());
        assertEquals("UNKNOWN_WORKFLOW", bad.get("errorCode").textValue());
        assertEquals("Bug flow", bad.get("suggestions").get(0).textValue());
        assertEquals(0, bad.get("issueTypes").size());
    }

    @Test
    public void readResponseSerializesWithAllDocumentedKeys() throws Exception {
        ProjectWorkflowsResponse response = new ProjectWorkflowsResponse(new ProjectRef("ABC", "Alpha"),
                new SchemeView(10100L, "ABC Workflow Scheme", false, false, 1, Arrays.asList("Beta")),
                Arrays.asList(new IssueTypeWorkflowView(new IssueTypeRef("10001", "Bug"), "jira", 5,
                        Arrays.asList(new StatusUsage("Open", 5, null)))), "jira",
                ProjectWorkflowsResponse.IssueCountsStatus.COMPLETE, null);

        JsonNode json = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(response));

        assertEquals(new TreeSet<String>(Arrays.asList("project", "scheme", "issueTypes", "defaultWorkflow",
                "issueCountsStatus", "issueCountsError")), names(json));
        assertEquals(new TreeSet<String>(Arrays.asList("id", "name", "isDefault", "hasDraft", "otherProjects",
                "otherProjectNames")), names(json.get("scheme")));
        JsonNode type = json.get("issueTypes").get(0);
        assertEquals(new TreeSet<String>(Arrays.asList("issueType", "workflow", "issues", "statuses")), names(type));
        assertTrue("without a target workflow there is nothing to say about inWorkflow",
                type.get("statuses").get(0).get("inWorkflow").isNull());
    }

    private static JsonNode tool(String name) throws Exception {
        InputStream in = WorkflowAssignToolsContractTest.class.getResourceAsStream("/llm/workflow-tools.json");
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

    private static List<String> requiredOf(JsonNode objectSchema) {
        List<String> required = new ArrayList<String>();
        for (JsonNode name : objectSchema.get("required")) {
            required.add(name.textValue());
        }
        return required;
    }

    /** JSON property names taken from the {@code @JsonProperty} constructor parameters of the DTO. */
    private static Set<String> jsonPropertiesOf(Class<?> dto) {
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : dto.getConstructors()) {
            if (candidate.isAnnotationPresent(JsonCreator.class)) {
                assertTrue("DTO must have only one JSON creator constructor: " + dto.getName(), constructor == null);
                constructor = candidate;
            }
        }
        assertNotNull("DTO must have a JSON creator constructor: " + dto.getName(), constructor);
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
