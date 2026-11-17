package ru.rasulov.issuetypes.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IssueTypeToolsContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void requestDeserializesATypeAndStatusMapping() throws Exception {
        SetProjectIssueTypesRequest request = MAPPER.readValue("{\"dryRun\":true,\"project\":\"ABC\","
                + "\"issueTypes\":[\"Task\",\"Bug\"],\"mappings\":[{\"from\":\"Story\",\"to\":\"Task\","
                + "\"statusMappings\":[{\"from\":\"Review\",\"to\":\"Done\"}]}]}",
                SetProjectIssueTypesRequest.class);

        assertTrue(request.isDryRun());
        assertEquals(Arrays.asList("Task", "Bug"), request.getIssueTypes());
        assertEquals("Story", request.getMappings().get(0).getFrom());
        assertEquals("Done", request.getMappings().get(0).getStatusMappings().get(0).getTo());
    }

}
