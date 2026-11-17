package ru.rasulov.workflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import org.junit.Test;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.assign.SchemeChange;
import ru.rasulov.workflow.api.dto.assign.SchemeView;
import ru.rasulov.workflow.api.dto.clone.CloneErrorCode;
import ru.rasulov.workflow.api.dto.clone.CloneScope;
import ru.rasulov.workflow.api.dto.clone.CloneVerdict;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsRequest;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsResponse;
import ru.rasulov.workflow.api.dto.clone.WorkflowCloneView;

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Keeps {@code clone_project_workflows} in {@code llm/workflow-tools.json} and the JSON shape of its DTOs from drifting apart. */
public class CloneToolContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Jira's REST mapper does not auto-detect {@code getX()}: serializing fails to show a getter without {@code @JsonProperty}. */
    private static final ObjectMapper GETTER_BLIND_MAPPER = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false)
            .configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false);

    @Test
    public void publicPreviewPropertiesMatchTheRequestDtoExceptTheInternalApplyToken() throws Exception {
        JsonNode schema = tool().get("input_schema");

        assertEquals("object", schema.get("type").textValue());
        Set<String> previewProperties = jsonPropertiesOf(CloneWorkflowsRequest.class);
        previewProperties.remove("planId");
        assertEquals(previewProperties, names(schema.get("properties")));
        assertEquals(Arrays.asList("dryRun", "project", "scope"), requiredOf(schema));
        List<String> scopes = new ArrayList<String>();
        for (JsonNode scope : schema.get("properties").get("scope").get("enum")) {
            scopes.add(scope.textValue());
        }
        List<String> inCode = new ArrayList<String>();
        for (CloneScope scope : CloneScope.values()) {
            inCode.add(scope.name());
        }
        assertEquals(inCode, scopes);
    }

    @Test
    public void everyLimitInTheToolIsTheLimitInCode() throws Exception {
        JsonNode tool = tool();

        assertEquals(WorkflowAssignLimits.MAX_WORKFLOWS_TO_CLONE,
                tool.get("input_schema").get("properties").get("workflows").get("maxItems").intValue());
        assertTrue("the description must state the issue limit the code enforces",
                tool.get("description").textValue().contains(WorkflowAssignLimits.MAX_ISSUES_TO_MIGRATE + " issues"));
    }

    @Test
    public void everyErrorCodeThatNeedsAnActionAndEveryScopeAndVerdictIsExplainedToTheModel() throws Exception {
        String description = tool().get("description").textValue();
        for (CloneErrorCode code : CloneErrorCode.values()) {
            if (code != CloneErrorCode.VALIDATION_ERROR) {
                assertTrue(code + " is not in the description", description.contains(code.name()));
            }
        }
        for (CloneScope scope : CloneScope.values()) {
            assertTrue(scope + " is not in the description", description.contains(scope.name()));
        }
        for (CloneVerdict verdict : CloneVerdict.values()) {
            assertTrue(verdict + " is not in the description", description.contains(verdict.name()));
        }
    }

    @Test
    public void requestDeserializesPreviewAndTokenOnlyApplyWithoutImplicitWriteMode() throws Exception {
        CloneWorkflowsRequest request = MAPPER.readValue("{\"dryRun\":true,\"project\":\"ABC\",\"scope\":\"SELECTED\","
                + "\"workflows\":[\"Bug flow\"],\"schemeName\":\"S\",\"workflowNames\":{\"Bug flow\":\"Bug flow 2\"}}",
                CloneWorkflowsRequest.class);

        assertTrue(request.isDryRun());
        assertEquals("ABC", request.getProject());
        assertEquals(CloneScope.SELECTED, request.getScope());
        assertEquals(Arrays.asList("Bug flow"), request.getWorkflows());
        assertEquals("S", request.getSchemeName());
        assertEquals("Bug flow 2", request.getWorkflowNames().get("Bug flow"));

        CloneWorkflowsRequest plain = MAPPER.readValue("{\"project\":\"ABC\",\"scope\":\"ALL_WORKFLOWS\"}",
                CloneWorkflowsRequest.class);
        assertNull(plain.isDryRun());
        assertTrue(plain.getWorkflows().isEmpty());
        assertNull(plain.getSchemeName());
        assertTrue(plain.getWorkflowNames().isEmpty());
        assertNull(plain.getPlanId());

        CloneWorkflowsRequest apply = MAPPER.readValue("{\"dryRun\":false,\"planId\":\"preview-token\"}",
                CloneWorkflowsRequest.class);
        assertFalse(apply.isDryRun());
        assertEquals("preview-token", apply.getPlanId());
        assertNull(apply.getProject());
        assertNull(apply.getScope());
    }

    @Test
    public void responseSerializesWithAllDocumentedKeysIncludingNulls() throws Exception {
        CloneWorkflowsResponse would = CloneWorkflowsResponse.wouldClone(new ProjectRef("ABC", "Alpha"),
                CloneScope.ALL_WORKFLOWS,
                new SchemeView(10100L, "Shared scheme", false, false, 2, Arrays.asList("Beta", "Gamma")),
                new SchemeChange(SchemeAction.CREATE, null, "Shared scheme v2"),
                Arrays.asList(new WorkflowCloneView("Bug flow", "Bug flow v2", CloneVerdict.COPY, 2,
                                Arrays.asList("Beta", "Gamma")),
                        new WorkflowCloneView("Flow A", null, CloneVerdict.ALREADY_OWN, 0,
                                Collections.<String>emptyList())), null).withPlan("preview-token", 123456789L);
        CloneWorkflowsResponse failed = CloneWorkflowsResponse.failed(false, null, null, CloneErrorCode.UNKNOWN_PROJECT,
                "msg", Collections.singletonList("ABC"));

        JsonNode ok = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(would));
        JsonNode bad = MAPPER.readTree(GETTER_BLIND_MAPPER.writeValueAsString(failed));

        assertEquals(new TreeSet<String>(Arrays.asList("dryRun", "status", "project", "scope", "currentScheme", "scheme",
                "workflows", "message", "errorCode", "suggestions", "planId", "expiresAt", "undoWarning")), names(ok));
        assertEquals("WOULD_CLONE", ok.get("status").textValue());
        assertEquals("ALL_WORKFLOWS", ok.get("scope").textValue());
        assertTrue(ok.get("errorCode").isNull());
        assertEquals("preview-token", ok.get("planId").textValue());
        assertEquals(123456789L, ok.get("expiresAt").longValue());
        assertTrue("a scheme a dry run would create has no id yet", ok.get("scheme").get("id").isNull());
        assertEquals(new TreeSet<String>(Arrays.asList("id", "name", "isDefault", "hasDraft", "otherProjects",
                "otherProjectNames")), names(ok.get("currentScheme")));
        JsonNode copy = ok.get("workflows").get(0);
        assertEquals(new TreeSet<String>(Arrays.asList("name", "newName", "verdict", "otherProjects",
                "otherProjectNames")), names(copy));
        assertEquals("COPY", copy.get("verdict").textValue());
        assertTrue("a workflow that gets no copy has no new name", ok.get("workflows").get(1).get("newName").isNull());

        assertEquals("FAILED", bad.get("status").textValue());
        assertTrue("the project is unknown", bad.get("project").isNull());
        assertTrue(bad.get("scheme").isNull());
        assertTrue(bad.get("currentScheme").isNull());
        assertEquals("UNKNOWN_PROJECT", bad.get("errorCode").textValue());
        assertEquals("ABC", bad.get("suggestions").get(0).textValue());
        assertTrue(bad.get("planId").isNull());
        assertTrue(bad.get("expiresAt").isNull());
        assertEquals(0, bad.get("workflows").size());
    }

    private static JsonNode tool() throws Exception {
        InputStream in = CloneToolContractTest.class.getResourceAsStream("/llm/workflow-tools.json");
        assertNotNull("llm/workflow-tools.json is missing from resources", in);
        try {
            for (JsonNode tool : MAPPER.readTree(in)) {
                if ("clone_project_workflows".equals(tool.get("name").textValue())) {
                    return tool;
                }
            }
        } finally {
            in.close();
        }
        throw new AssertionError("tool not described: clone_project_workflows");
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
                assertNull("only one JSON creator is allowed", constructor);
                constructor = candidate;
            }
        }
        assertNotNull("the request must declare a JSON creator", constructor);
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
