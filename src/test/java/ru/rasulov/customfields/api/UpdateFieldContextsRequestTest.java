package ru.rasulov.customfields.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import org.junit.Test;
import ru.rasulov.customfields.api.dto.context.OptionsChange;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsRequest;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsRequest.ContextUpdate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class UpdateFieldContextsRequestTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    public void omittedAxesStayAbsentInsteadOfBecomingEmptyOrFalse() throws Exception {
        UpdateFieldContextsRequest request = json.readValue("{\"contexts\":[{\"contextId\":12}]}", UpdateFieldContextsRequest.class);
        ContextUpdate update = request.getContexts().get(0);
        assertNull(request.isDryRun());
        assertNull(request.getFieldId());
        assertNull(request.getPlanId());
        assertEquals(Long.valueOf(12), update.getContextId());
        assertNull(update.isAllProjects());
        assertNull(update.getProjects());
        assertNull(update.isAllIssueTypes());
        assertNull(update.getIssueTypes());
    }

    @Test
    public void explicitEmptyAndDoubleFormsSurviveDeserializationForServiceValidation() throws Exception {
        ContextUpdate update = json.readValue("{\"contexts\":[{\"contextId\":12,\"allProjects\":true,"
                + "\"projects\":[],\"allIssueTypes\":true,\"issueTypes\":[]}]}", UpdateFieldContextsRequest.class)
                .getContexts().get(0);
        assertEquals(Boolean.TRUE, update.isAllProjects());
        assertNotNull(update.getProjects());
        assertTrue(update.getProjects().isEmpty());
        assertEquals(Boolean.TRUE, update.isAllIssueTypes());
        assertNotNull(update.getIssueTypes());
        assertTrue(update.getIssueTypes().isEmpty());
    }

    @Test
    public void explicitFalseIsNotConfusedWithAnAbsentAllFlag() throws Exception {
        ContextUpdate update = json.readValue("{\"contexts\":[{\"contextId\":12,\"allProjects\":false,"
                + "\"allIssueTypes\":false}]}", UpdateFieldContextsRequest.class).getContexts().get(0);
        assertEquals(Boolean.FALSE, update.isAllProjects());
        assertEquals(Boolean.FALSE, update.isAllIssueTypes());
        assertNull(update.getProjects());
        assertNull(update.getIssueTypes());
    }

    @Test
    public void requestCopiesAndFreezesCollectionsWithoutNormalizingTheirContents() {
        List<String> projects = new ArrayList<String>(Arrays.asList("LOT", "LOT", null));
        List<String> types = new ArrayList<String>(Arrays.asList("14900"));
        ContextUpdate update = new ContextUpdate(12L, null, projects, null, types);
        List<ContextUpdate> contexts = new ArrayList<ContextUpdate>(Arrays.asList(update, null));
        UpdateFieldContextsRequest request = new UpdateFieldContextsRequest(true, null, "customfield_10000", contexts);
        projects.clear(); types.clear(); contexts.clear();
        assertEquals(2, request.getContexts().size());
        assertNull(request.getContexts().get(1));
        assertEquals(Arrays.asList("LOT", "LOT", null), update.getProjects());
        assertEquals(Arrays.asList("14900"), update.getIssueTypes());
        try { request.getContexts().clear(); fail("contexts must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        try { update.getProjects().clear(); fail("projects must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        try { update.getIssueTypes().clear(); fail("issue types must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void applyOnlyPayloadDoesNotInventPreviewFields() throws Exception {
        UpdateFieldContextsRequest request = json.readValue("{\"dryRun\":false,\"planId\":\"saved-preview\"}",
                UpdateFieldContextsRequest.class);
        assertEquals(Boolean.FALSE, request.isDryRun());
        assertEquals("saved-preview", request.getPlanId());
        assertNull(request.getFieldId());
        assertNull(request.getContexts());
    }

    @Test
    public void allPropertiesSerializeWithGetterAutodetectionDisabled() throws Exception {
        ObjectMapper explicit = new ObjectMapper().configure(MapperFeature.AUTO_DETECT_GETTERS, false)
                .configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false);
        UpdateFieldContextsRequest request = new UpdateFieldContextsRequest(true, null, "customfield_10000",
                Collections.singletonList(new ContextUpdate(12L, false, Arrays.asList("LOT"), true, null)));
        JsonNode tree = explicit.readTree(explicit.writeValueAsString(request));
        assertTrue(tree.get("dryRun").asBoolean());
        assertEquals("customfield_10000", tree.get("fieldId").asText());
        JsonNode context = tree.get("contexts").get(0);
        assertEquals(12L, context.get("contextId").asLong());
        assertFalse(context.get("allProjects").asBoolean());
        assertEquals("LOT", context.get("projects").get(0).asText());
        assertTrue(context.get("allIssueTypes").asBoolean());
        UpdateFieldContextsRequest restored = json.readValue(explicit.writeValueAsString(request), UpdateFieldContextsRequest.class);
        assertNull(restored.getContexts().get(0).getIssueTypes());
    }

    @Test
    public void optionsBlockKeepsEveryOperationInOrderAndAnAbsentBlockStaysNull() throws Exception {
        UpdateFieldContextsRequest request = json.readValue("{\"dryRun\":true,\"fieldId\":\"customfield_1\",\"contexts\":["
                + "{\"contextId\":5,\"options\":{\"add\":[{\"value\":\"Urgent\"},{\"value\":\"IT\",\"parentOptionId\":10}],"
                + "\"rename\":[{\"optionId\":3,\"value\":\"High\"}],\"disable\":[4,5],\"enable\":[6],"
                + "\"order\":[{\"optionIds\":[1,2,3]},{\"parentOptionId\":10,\"optionIds\":[11,12]}]}},"
                + "{\"contextId\":6,\"allProjects\":true,\"allIssueTypes\":true}]}", UpdateFieldContextsRequest.class);
        ContextUpdate onlyOptions = request.getContexts().get(0);
        assertNull(onlyOptions.isAllProjects());
        assertNull(onlyOptions.getProjects());
        assertNull(onlyOptions.getAssets());
        OptionsChange options = onlyOptions.getOptions();
        assertTrue(options.hasOperations());
        assertEquals(2, options.getAdd().size());
        assertEquals("Urgent", options.getAdd().get(0).getValue());
        assertNull(options.getAdd().get(0).getParentOptionId());
        assertEquals(Long.valueOf(10), options.getAdd().get(1).getParentOptionId());
        assertEquals(Long.valueOf(3), options.getRename().get(0).getOptionId());
        assertEquals("High", options.getRename().get(0).getValue());
        assertEquals(Arrays.asList(4L, 5L), options.getDisable());
        assertEquals(Collections.singletonList(6L), options.getEnable());
        assertNull(options.getOrder().get(0).getParentOptionId());
        assertEquals(Arrays.asList(1L, 2L, 3L), options.getOrder().get(0).getOptionIds());
        assertEquals(Long.valueOf(10), options.getOrder().get(1).getParentOptionId());
        assertNull(request.getContexts().get(1).getOptions());
        try { options.getDisable().clear(); fail("lists must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void optionsSurviveTheSavedPlanRoundTripAndAnEmptyBlockHasNoOperations() throws Exception {
        String body = "{\"dryRun\":true,\"fieldId\":\"customfield_1\",\"contexts\":[{\"contextId\":5,\"allProjects\":true,"
                + "\"allIssueTypes\":true,\"options\":{\"add\":[{\"value\":\"A\"}],\"disable\":[7]}},"
                + "{\"contextId\":6,\"options\":{}}]}";
        UpdateFieldContextsRequest request = json.readValue(body, UpdateFieldContextsRequest.class);
        UpdateFieldContextsRequest restored = json.readValue(json.writeValueAsString(request), UpdateFieldContextsRequest.class);
        OptionsChange options = restored.getContexts().get(0).getOptions();
        assertEquals("A", options.getAdd().get(0).getValue());
        assertEquals(Collections.singletonList(7L), options.getDisable());
        assertTrue(options.getRename().isEmpty());
        assertTrue(options.getOrder().isEmpty());
        assertFalse(restored.getContexts().get(1).getOptions().hasOperations());
        assertTrue(restored.getContexts().get(0).isAllProjects());
    }
}
