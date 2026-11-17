package ru.rasulov.screens.impl;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.impl.applies.ApplyEvent;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.tools.McpServer;
import ru.rasulov.common.impl.tools.OperationRunner;
import ru.rasulov.common.impl.tools.ToolCall;
import ru.rasulov.common.impl.tools.ToolCatalog;
import ru.rasulov.common.impl.tools.ToolRegistry;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.screens.api.dto.CloneProjectScreensResponse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * clone_project_screens end to end: OperationRunner -> ScreenSchemeServiceImpl -> ScreenCloneWriter -> stateful fake of
 * Jira's screen scheme API -> persisted operation (real AO journal) -> get_operation through MCP -> the Audit event. Only
 * Jira's native objects are replaced.
 */
public class CloneEndToEndTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test public void aWriteFailureAfterTheCopyExistsIsAPartialCloneWithItsIdEverywhereAndNeverARollback() throws Exception {
        try (UndoTestFixture db = UndoTestFixture.create("admin")) {
            ScreenSchemeServiceImplTest.Fixture f = new ScreenSchemeServiceImplTest.Fixture();
            CloneProjectScreensResponse preview = f.preview();
            doThrow(new IllegalStateException("connection reset")).when(f.tops).addSchemeAssociation(any(Project.class), any());

            ApplyJournal audit = mock(ApplyJournal.class);
            Semaphore finished = new Semaphore(0);
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            doAnswer(call -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
            ToolCall clone = args -> f.apply(preview);
            ToolCall read = args -> db.journal.getOperation(args.get("operationId").textValue(), "admin");
            ToolRegistry registry = new ToolRegistry(Collections.singletonList(() -> {
                Map<String, ToolCall> tools = new LinkedHashMap<String, ToolCall>();
                tools.put("clone_project_screens", clone);
                tools.put("get_operation", read);
                return tools;
            }));
            ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
            OperationRunner runner = new OperationRunner(db.journal, db.guard, () -> registry, catalog,
                    mock(JiraAuthenticationContext.class), locals, db.plans, audit);
            try {
                String plan = db.plans.issue("clone_project_screens", "admin", "{\"project\":\"ABC\"}", "fp").getId();
                JsonNode args = MAPPER.readTree("{\"dryRun\":false,\"planId\":\"" + plan + "\"}");
                String id = (String) runner.start("clone_project_screens", args, null, null).get("operationId");
                assertTrue("the operation did not finish", finished.tryAcquire(10, TimeUnit.SECONDS));

                McpServer mcp = new McpServer(db.guard, catalog, registry);
                JsonNode reply = MAPPER.readTree(mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"get_operation\",\"arguments\":{\"operationId\":\"" + id + "\"}}}"));
                assertFalse("a receipt is never an MCP error", reply.path("result").path("isError").asBoolean());
                JsonNode receipt = MAPPER.readTree(reply.path("result").path("content").get(0).path("text").asText());
                assertEquals("COMPLETED", receipt.path("status").asText());
                JsonNode result = receipt.path("result");
                assertEquals("PARTIALLY_CLONED", result.path("status").asText());
                assertEquals("JIRA_ERROR", result.path("errorCode").asText());
                assertEquals("the created copy's id is in the stored result", 101L, result.path("issueTypeScreenScheme").path("newId").asLong());
                assertFalse("no rollback is promised", result.path("message").asText().toLowerCase().contains("rolled back to")
                        || result.path("message").asText().toLowerCase().contains("restored"));
                assertTrue(result.path("message").asText(), result.path("message").asText().contains("not confirmed"));

                ArgumentCaptor<ApplyEvent> event = ArgumentCaptor.forClass(ApplyEvent.class);
                verify(audit, times(1)).record(event.capture());
                assertEquals(id, event.getValue().operationRef);
                assertEquals("COMPLETED", event.getValue().operationState);
                assertEquals("PARTIALLY_CLONED", event.getValue().domainStatus);

                assertEquals("the project was not switched", f.source, f.associations.get(f.alpha));
                assertEquals("the copy really exists in Jira", 2, f.catalog.size());
                Map<String, Object> replay = runner.start("clone_project_screens", args, null, null);
                assertEquals(id, replay.get("operationId"));
                assertEquals(true, replay.get("alreadyStarted"));
                assertEquals("a retry creates no second copy", 2, f.catalog.size());
            } finally {
                runner.destroy();
            }
        }
    }
}
