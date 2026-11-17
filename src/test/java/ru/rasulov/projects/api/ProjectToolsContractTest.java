package ru.rasulov.projects.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.projects.api.dto.CreateProjectRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Internal DTOs retain separate preview and operation-runner apply payloads. */
public class ProjectToolsContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();


    @Test
    public void previewAndApplyPayloadsRemainDistinct() throws Exception {
        CreateProjectRequest preview = MAPPER.readValue(
                "{\"action\":\"create\",\"templateKey\":\"com.pyxis.greenhopper.jira:gh-scrum-template\",\"dryRun\":true,"
                        + "\"name\":\"Product Operations\",\"key\":\"POPS\",\"lead\":\"alex\"}",
                CreateProjectRequest.class);
        assertEquals("create", preview.getAction());
        assertEquals("com.pyxis.greenhopper.jira:gh-scrum-template", preview.getTemplateKey());
        assertTrue(preview.isDryRun());
        assertEquals("Product Operations", preview.getName());
        assertEquals("POPS", preview.getKey());
        assertEquals("alex", preview.getLead());
        assertEquals(null, preview.getPlanId());

        CreateProjectRequest apply = MAPPER.readValue(
                "{\"dryRun\":false,\"planId\":\"plan-123\"}", CreateProjectRequest.class);
        assertEquals(Boolean.FALSE, apply.isDryRun());
        assertEquals("plan-123", apply.getPlanId());
        assertEquals(null, apply.getName());
        assertEquals(null, apply.getAction());
        assertEquals(null, apply.getTemplateKey());
    }

    @Test
    public void absentAndExplicitNullDryRunRemainDistinctFromAnExplicitWrite() throws Exception {
        CreateProjectRequest missing = MAPPER.readValue(
                "{\"name\":\"Product Operations\",\"key\":\"POPS\",\"lead\":\"alex\"}", CreateProjectRequest.class);
        CreateProjectRequest explicitNull = MAPPER.readValue(
                "{\"dryRun\":null,\"name\":\"Product Operations\",\"key\":\"POPS\",\"lead\":\"alex\"}",
                CreateProjectRequest.class);
        CreateProjectRequest write = MAPPER.readValue(
                "{\"dryRun\":false,\"planId\":\"plan-123\"}", CreateProjectRequest.class);
        assertEquals(null, missing.isDryRun());
        assertEquals(null, explicitNull.isDryRun());
        assertEquals(Boolean.FALSE, write.isDryRun());
    }

}
