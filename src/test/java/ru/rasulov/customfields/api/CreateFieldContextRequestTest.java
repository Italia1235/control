package ru.rasulov.customfields.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.customfields.api.dto.context.CreateFieldContextRequest;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** The service validates axes itself, so absent versus empty versus false must reach it unchanged. */
public class CreateFieldContextRequestTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    public void publicPreviewKeepsBothAxesAndLeavesOmittedFieldsAbsent() throws Exception {
        CreateFieldContextRequest request = json.readValue("{\"dryRun\":true,\"fieldId\":\"customfield_10500\","
                + "\"name\":\"QA QABUG\",\"projects\":[\"QABUG\"],\"issueTypes\":[\"Bug\",\"Sub-bug\",\"Sub-task\"]}",
                CreateFieldContextRequest.class);
        assertEquals(Boolean.TRUE, request.isDryRun());
        assertEquals(Arrays.asList("QABUG"), request.getProjects());
        assertEquals(Arrays.asList("Bug", "Sub-bug", "Sub-task"), request.getIssueTypes());
        assertNull(request.isAllProjects());
        assertNull(request.isAllIssueTypes());
        assertNull(request.getDescription());
        assertNull(request.getPlanId());
    }

    @Test
    public void explicitFlagsAndEmptyListsAreNotNormalisedAway() throws Exception {
        CreateFieldContextRequest request = json.readValue("{\"allProjects\":false,\"projects\":[],"
                + "\"allIssueTypes\":true}", CreateFieldContextRequest.class);
        assertEquals(Boolean.FALSE, request.isAllProjects());
        assertNotNull(request.getProjects());
        assertEquals(0, request.getProjects().size());
        assertEquals(Boolean.TRUE, request.isAllIssueTypes());
        assertNull(request.getIssueTypes());
    }

    @Test
    public void applyPayloadCarriesOnlyModeAndPlan() throws Exception {
        CreateFieldContextRequest request = json.readValue("{\"dryRun\":false,\"planId\":\"saved-preview\"}",
                CreateFieldContextRequest.class);
        assertEquals(Boolean.FALSE, request.isDryRun());
        assertEquals("saved-preview", request.getPlanId());
        assertNull(request.getFieldId());
        assertNull(request.getName());
        assertNull(request.isAllProjects());
        assertNull(request.getProjects());
        assertNull(request.isAllIssueTypes());
        assertNull(request.getIssueTypes());
    }
}
