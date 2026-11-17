package ru.rasulov.projects.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.projects.api.ProjectCreator;
import ru.rasulov.projects.api.dto.CreateProjectRequest;
import ru.rasulov.projects.api.dto.ProjectTemplatesResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** The create_project tool is one mutually exclusive contract: list_templates, create (preview) or the plan apply. */
public class ProjectToolsDispatchTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProjectCreator creator = mock(ProjectCreator.class);
    private final ToolCall tool = new ProjectTools(creator).tools().get("create_project");

    private static JsonNode args(String json) throws Exception {
        return JSON.readTree(json);
    }

    private void refused(String json) throws Exception {
        try {
            tool.call(args(json));
            fail("Refused: " + json);
        } catch (InvalidRequestException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoMoreInteractions(creator);
    }

    @Test
    public void listTemplatesReadsTheCatalogOnly() throws Exception {
        ProjectTemplatesResponse catalog = ProjectTemplatesResponse.ok(java.util.Collections.<ProjectTemplatesResponse.Template>emptyList());
        when(creator.listTemplates()).thenReturn(catalog);

        assertSame(catalog, tool.call(args("{\"action\":\"list_templates\"}")));

        verify(creator).listTemplates();
        verify(creator, never()).create(any(CreateProjectRequest.class));
    }

    @Test
    public void listTemplatesAcceptsNoOtherArgument() throws Exception {
        for (String extra : new String[] {"\"dryRun\":true", "\"dryRun\":false", "\"name\":\"X\"", "\"key\":\"X\"",
                "\"lead\":\"admin\"", "\"templateKey\":\"t\"", "\"planId\":\"p\"", "\"issueTypes\":[\"Task\"]"}) {
            refused("{\"action\":\"list_templates\"," + extra + "}");
        }
    }

    @Test
    public void theOldCreateFormWithoutActionIsRefused() throws Exception {
        refused("{\"dryRun\":true,\"name\":\"Delivery\",\"key\":\"DEL\",\"lead\":\"alex\"}");
        refused("{\"templateKey\":\"" + ProjectTemplates.PROJECT_MANAGEMENT + "\",\"dryRun\":true,\"name\":\"D\",\"key\":\"DEL\",\"lead\":\"alex\"}");
        refused("{}");
        refused("{\"action\":\"delete\",\"dryRun\":true}");
        refused("{\"action\":null,\"dryRun\":true,\"name\":\"D\",\"key\":\"DEL\",\"lead\":\"alex\"}");
        refused("{\"dryRun\":false,\"planId\":\"p\",\"name\":\"D\"}");
    }

    @Test
    public void createPreviewAndThePlanOnlyApplyReachTheServiceWithTheirShape() throws Exception {
        tool.call(args("{\"action\":\"create\",\"templateKey\":\"" + ProjectTemplates.SCRUM
                + "\",\"dryRun\":true,\"name\":\"D\",\"key\":\"DEL\",\"lead\":\"alex\"}"));
        tool.call(args("{\"dryRun\":false,\"planId\":\"plan-1\"}"));

        ArgumentCaptor<CreateProjectRequest> sent = ArgumentCaptor.forClass(CreateProjectRequest.class);
        verify(creator, org.mockito.Mockito.times(2)).create(sent.capture());
        CreateProjectRequest preview = sent.getAllValues().get(0);
        assertEquals("create", preview.getAction());
        assertEquals(ProjectTemplates.SCRUM, preview.getTemplateKey());
        CreateProjectRequest apply = sent.getAllValues().get(1);
        assertNull(apply.getAction());
        assertEquals("plan-1", apply.getPlanId());
        verify(creator, never()).listTemplates();
    }
}
