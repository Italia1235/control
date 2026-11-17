package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.security.JiraAuthenticationContext;
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
import ru.rasulov.customfields.api.dto.screen.AddToScreensRequest;
import ru.rasulov.customfields.api.dto.screen.AddToScreensResponse;
import ru.rasulov.customfields.api.dto.screen.ScreenTarget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * End to end over the real components: OperationRunner (dispatcher, executor, plan check) -> FieldScreenServiceImpl ->
 * ScreenWriter -> a stateful fake of Jira's screen API -> persisted operation (real AO journal) -> get_operation through
 * the MCP server -> the Audit event the runner builds. Only Jira's native objects are replaced; this is not a check of the
 * native Jira API.
 */
public class ForwardResultsEndToEndTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class Pipeline implements AutoCloseable {
        final NativeFieldUndoTest.Model model;
        final OperationRunner runner;
        final McpServer mcp;
        final ApplyJournal audit = mock(ApplyJournal.class);
        final Semaphore finished = new Semaphore(0);

        Pipeline(NativeFieldUndoTest.Model model) {
            this.model = model;
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            doAnswer(call -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
            ToolCall add = args -> model.screenService.addToScreens(
                    new AddToScreensRequest(null, false, null, args.get("planId").textValue()));
            ToolCall read = args -> model.fixture.journal.getOperation(args.get("operationId").textValue(), "admin");
            ToolRegistry registry = new ToolRegistry(Collections.singletonList(() -> {
                Map<String, ToolCall> tools = new LinkedHashMap<String, ToolCall>();
                tools.put("add_field_to_screens", add);
                tools.put("create_custom_fields", args -> model.creator.createAll(
                        new ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest(false, null, args.get("planId").textValue())));
                tools.put("get_operation", read);
                return tools;
            }));
            ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
            runner = new OperationRunner(model.fixture.journal, model.fixture.guard, () -> registry, catalog,
                    mock(JiraAuthenticationContext.class), locals, model.fixture.plans, audit);
            mcp = new McpServer(model.fixture.guard, catalog, registry);
        }

        AddToScreensResponse preview(Long... screens) {
            List<ScreenTarget> targets = new ArrayList<ScreenTarget>();
            for (Long id : screens) targets.add(new ScreenTarget(id, null, null, null));
            AddToScreensResponse preview = model.screenService.addToScreens(
                    new AddToScreensRequest(Collections.singletonList(NativeFieldUndoTest.Model.FIELD), true, targets));
            assertNotNull(preview.getPlanId());
            return preview;
        }

        JsonNode args(String planId) throws Exception {
            return MAPPER.readTree("{\"dryRun\":false,\"planId\":\"" + planId + "\"}");
        }

        Map<String, Object> start(String planId) throws Exception {
            Map<String, Object> started = runner.start("add_field_to_screens", args(planId), null, null);
            assertTrue("the operation did not finish", finished.tryAcquire(10, TimeUnit.SECONDS));
            return started;
        }

        ApplyEvent auditEvent() {
            ArgumentCaptor<ApplyEvent> event = ArgumentCaptor.forClass(ApplyEvent.class);
            verify(audit, times(1)).record(event.capture());
            return event.getValue();
        }

        /** The MCP get_operation answer: isError and the parsed payload. */
        JsonNode receipt(String operationId) throws Exception {
            JsonNode reply = MAPPER.readTree(mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"get_operation\",\"arguments\":{\"operationId\":\"" + operationId + "\"}}}"));
            assertFalse(reply.toString(), reply.has("error"));
            JsonNode result = reply.path("result");
            assertFalse("a receipt is never an MCP error", result.path("isError").asBoolean());
            return MAPPER.readTree(result.path("content").get(0).path("text").asText());
        }

        @Override public void close() { runner.destroy(); }
    }

    private static List<String> statuses(JsonNode receipt) {
        List<String> statuses = new ArrayList<String>();
        for (JsonNode result : receipt.path("result").path("fields").get(0).path("results")) statuses.add(result.path("status").asText());
        return statuses;
    }

    /** A second screen with one tab; the write persists and then fails when {@code failAfterStoring}. */
    private static FieldScreenTab secondScreen(NativeFieldUndoTest.Model model, boolean failAfterStoring, AtomicInteger writes) {
        List<FieldScreenLayoutItem> items = new ArrayList<FieldScreenLayoutItem>();
        items.add(NativeFieldUndoTest.Model.screenItem(70L, "summary", 0));
        FieldScreenTab tab = mock(FieldScreenTab.class);
        FieldScreen screen = mock(FieldScreen.class);
        when(tab.getId()).thenReturn(61L); when(tab.getName()).thenReturn("General"); when(tab.getFieldScreen()).thenReturn(screen);
        when(tab.getFieldScreenLayoutItems()).thenAnswer(call -> new ArrayList<FieldScreenLayoutItem>(items));
        when(tab.getFieldScreenLayoutItem(anyInt())).thenAnswer(call -> {
            int index = call.getArgument(0);
            return index >= 0 && index < items.size() ? items.get(index) : null;
        });
        doAnswer(call -> {
            writes.incrementAndGet();
            int index = call.getArgument(1);
            items.add(index, NativeFieldUndoTest.Model.screenItem(71L, call.getArgument(0), index));
            if (failAfterStoring) throw new IllegalStateException("connection reset after the store");
            return null;
        }).when(tab).addFieldScreenLayoutItem(anyString(), anyInt());
        when(screen.getId()).thenReturn(51L); when(screen.getName()).thenReturn("Second");
        when(screen.getTabs()).thenReturn(Collections.singletonList(tab));
        when(screen.containsField(anyString())).thenAnswer(call ->
                items.stream().anyMatch(item -> item.getFieldId().equals(call.getArgument(0))));
        when(model.screens.getFieldScreen(51L)).thenReturn(screen);
        return tab;
    }

    @Test public void aSuccessfulApplyIsOneCompletedOperationReadableThroughMcpAndRecordedInAudit() throws Exception {
        try (NativeFieldUndoTest.Model model = new NativeFieldUndoTest.Model(); Pipeline pipeline = new Pipeline(model)) {
            model.exists.set(true);
            Map<String, Object> started = pipeline.start(pipeline.preview(50L).getPlanId());
            String id = (String) started.get("operationId");
            JsonNode receipt = pipeline.receipt(id);
            assertEquals("COMPLETED", receipt.path("status").asText());
            assertEquals(Collections.singletonList("ADDED"), statuses(receipt));
            ApplyEvent event = pipeline.auditEvent();
            assertEquals(id, event.operationRef);
            assertEquals("COMPLETED", event.operationState);
            assertEquals("ADDED", event.domainStatus);
            assertEquals(Arrays.asList("summary", NativeFieldUndoTest.Model.FIELD), model.screenFields());
        }
    }

    @Test public void aConfirmedPrefixAndALaterUnconfirmedWriteAreNeverASuccessAnywhereOnThePath() throws Exception {
        try (NativeFieldUndoTest.Model model = new NativeFieldUndoTest.Model(); Pipeline pipeline = new Pipeline(model)) {
            model.exists.set(true);
            AtomicInteger secondScreenWrites = new AtomicInteger();
            FieldScreenTab second = secondScreen(model, true, secondScreenWrites);
            String plan = pipeline.preview(50L, 51L).getPlanId();
            Map<String, Object> started = pipeline.start(plan);
            String id = (String) started.get("operationId");
            JsonNode receipt = pipeline.receipt(id);
            assertEquals("the lifecycle is finished", "COMPLETED", receipt.path("status").asText());
            assertEquals("the domain result keeps the prefix and the unknown write apart",
                    Arrays.asList("ADDED", "UNKNOWN"), statuses(receipt));
            assertEquals("UNKNOWN", receipt.path("result").path("fields").get(0).path("results").get(1).path("status").asText());
            ApplyEvent event = pipeline.auditEvent();
            assertEquals("Audit must not show a clean success for a prefix plus an unknown write", "PARTIAL", event.domainStatus);
            assertEquals(Arrays.asList("summary", NativeFieldUndoTest.Model.FIELD), model.screenFields());
            assertTrue("the unconfirmed write really reached Jira", second.getFieldScreenLayoutItems().stream()
                    .anyMatch(item -> item.getFieldId().equals(NativeFieldUndoTest.Model.FIELD)));
            // a retry of the identical start returns the same operation and does not write again
            Map<String, Object> replay = pipeline.runner.start("add_field_to_screens", pipeline.args(plan), null, null);
            assertEquals(id, replay.get("operationId"));
            assertEquals(true, replay.get("alreadyStarted"));
            assertEquals(1, secondScreenWrites.get());
        }
    }

    @Test public void aCancelRequestedAfterTheLastWriteLeavesACompletedOperationWithItsResultAndTheFlag() throws Exception {
        try (NativeFieldUndoTest.Model model = new NativeFieldUndoTest.Model(); Pipeline pipeline = new Pipeline(model)) {
            model.exists.set(true);
            org.mockito.stubbing.Answer<Object> store = call -> {
                int index = call.getArgument(1);
                model.screenItems.add(index, NativeFieldUndoTest.Model.screenItem(53L, call.getArgument(0), index));
                String running = model.fixture.journal.currentOperationId();
                if (running != null) model.fixture.journal.cancel(running, "admin", false);
                return null;
            };
            String plan = pipeline.preview(50L).getPlanId();
            doAnswer(store).when(model.tab).addFieldScreenLayoutItem(anyString(), anyInt());
            String id = (String) pipeline.start(plan).get("operationId");
            JsonNode receipt = pipeline.receipt(id);
            assertEquals("COMPLETED", receipt.path("status").asText());
            assertTrue("the request to cancel stays visible", receipt.path("cancelRequested").asBoolean());
            assertEquals(Collections.singletonList("ADDED"), statuses(receipt));
            ApplyEvent event = pipeline.auditEvent();
            assertEquals("COMPLETED", event.operationState);
            assertEquals("ADDED", event.domainStatus);
        }
    }

    @Test public void aBatchStoppedByTheSecondCreateStillNamesTheFirstFieldAndNeverAnInventedSecondOne() throws Exception {
        try (NativeFieldUndoTest.Model model = new NativeFieldUndoTest.Model(); Pipeline pipeline = new Pipeline(model)) {
            String plan = model.creator.createAll(new ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest(true, Arrays.asList(
                    new ru.rasulov.common.api.dto.field.FieldDefinition("Field", null, "textfield", null, null, JiraMocks.GLOBAL, null),
                    new ru.rasulov.common.api.dto.field.FieldDefinition("Field B", null, "textfield", null, null, JiraMocks.GLOBAL, null)))).getPlanId();
            Map<String, Object> started = pipeline.runner.start("create_custom_fields", pipeline.args(plan), null, null);
            assertTrue("the operation did not finish", pipeline.finished.tryAcquire(10, TimeUnit.SECONDS));
            String id = (String) started.get("operationId");
            JsonNode receipt = pipeline.receipt(id);
            assertEquals("an unproven create blocks the rollback", "BLOCKED", receipt.path("status").asText());
            assertNull("no domain result is made up", model.fixture.journal.getOperation(id, "admin").get("result"));
            assertFalse("MCP omits the absent domain result", receipt.has("result"));
            Map<String, String> fieldIdByResource = new LinkedHashMap<String, String>();
            for (JsonNode step : receipt.path("steps")) fieldIdByResource.put(step.path("resource").asText(), step.has("fieldId") ? step.path("fieldId").asText() : null);
            assertEquals(NativeFieldUndoTest.Model.FIELD, fieldIdByResource.get("cf.field:Field"));
            assertNull("the second field is unidentified although Jira has it", fieldIdByResource.get("cf.field:Field B"));
            assertTrue(fieldIdByResource.containsKey("cf.field:Field B"));
            assertTrue(model.secondCreated.get());
            assertEquals(0, model.deletions.get());
            ApplyEvent event = pipeline.auditEvent();
            assertEquals(id, event.operationRef);
            assertEquals("BLOCKED", event.operationState);
            assertNull("Audit shows no domain success", event.domainStatus);
            Map<String, Object> replay = pipeline.runner.start("create_custom_fields", pipeline.args(plan), null, null);
            assertEquals(id, replay.get("operationId"));
            assertEquals(true, replay.get("alreadyStarted"));
            verify(model.fields, times(2)).createCustomField(anyString(), any(), any(), any(), anyList(), anyList());
        }
    }
}
