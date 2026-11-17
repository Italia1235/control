package ru.rasulov.common.impl.tools;

import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.workflow.api.dto.StatusLookupEntry;
import ru.rasulov.workflow.api.dto.StatusLookupResponse;
import ru.rasulov.workflow.api.dto.StatusResponse;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.customfields.api.dto.screen.AddToScreensResponse;
import ru.rasulov.customfields.api.dto.screen.FieldScreensResult;
import ru.rasulov.customfields.api.dto.screen.ScreenErrorCode;
import ru.rasulov.customfields.api.dto.screen.ScreenResult;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;

/** Error signalling is separate from the preserved domain result and journal read state. */
public class McpExecutionResultsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));

    @Test public void validationFailureIsAToolErrorWithoutLosingTheDomainDiagnostic() throws Exception {
        String message = "transitions[2] ('Reopen').resolution.action CLEAR is not allowed for non-DONE -> non-DONE.";
        JsonNode result = call("configure_workflow", ConfigureWorkflowResponse.failed(true, "Delivery",
                "VALIDATION_ERROR", message));
        assertTrue(result.path("isError").asBoolean());
        JsonNode domain = payload(result);
        assertEquals("FAILED", domain.path("status").asText());
        assertEquals("VALIDATION_ERROR", domain.path("errorCode").asText());
        assertEquals(message, domain.path("message").asText());
        assertEquals("Delivery", domain.path("workflow").asText());
    }

    @Test public void successfulPreviewAndIdempotentResultsAreNotErrorsEvenWithWarnings() throws Exception {
        for (String status : Arrays.asList("WOULD_CREATE", "EXISTS")) {
            ConfigureWorkflowResponse response = new ConfigureWorkflowResponse(true, status, "Delivery", "ACTIVE",
                    "ACTIVE", null, null, "Some unreachable statuses", Collections.emptyList(),
                    Collections.singletonList("Requested category ignored"), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), null, null, null);
            JsonNode result = call("configure_workflow", response);
            assertFalse(result.toString(), result.path("isError").asBoolean());
            assertEquals(response.getStatus(), payload(result).path("status").asText());
            assertEquals("Some unreachable statuses", payload(result).path("message").asText());
            assertEquals("Requested category ignored", payload(result).path("warnings").get(0).asText());
        }
        for (String status : Arrays.asList("WOULD_ASSIGN", "ALREADY_ASSIGNED")) {
            assertFalse(status, call("assign_workflow", mapper.readTree("{\"status\":\"" + status
                    + "\",\"message\":\"Some issues may need review\"}")).path("isError").asBoolean());
        }
    }

    @Test public void failedItemsMakeAMixedFieldBatchAnErrorWithoutDiscardingSuccesses() throws Exception {
        assumeTrue(catalog.isPublished("create_custom_fields"));
        CreateCustomFieldsResponse batch = new CreateCustomFieldsResponse(true, Arrays.asList(
                FieldCreationResult.wouldCreate("Good", null, null),
                FieldCreationResult.failed("Bad", CustomFieldErrorCode.UNKNOWN_TYPE, "Type is not installed", null)));
        JsonNode result = call("create_custom_fields", batch);
        assertTrue(result.path("isError").asBoolean());
        assertEquals("WOULD_CREATE", payload(result).path("results").get(0).path("status").asText());
        assertEquals("UNKNOWN_TYPE", payload(result).path("results").get(1).path("errorCode").asText());
    }

    @Test public void partialFieldIsAnErrorButStillCarriesTheCreatedIdActualContextsAndDifferences() throws Exception {
        assumeTrue(catalog.isPublished("create_custom_fields"));
        ru.rasulov.common.api.dto.field.context.ContextSummary actual =
                new ru.rasulov.common.api.dto.field.context.ContextSummary("PAY", false,
                        Collections.singletonList(new ru.rasulov.common.api.dto.ProjectRef("SHOP", "Webshop")), true,
                        Collections.<ru.rasulov.common.api.dto.IssueTypeRef>emptyList());
        CreateCustomFieldsResponse applied = new CreateCustomFieldsResponse(false, Arrays.asList(
                FieldCreationResult.created("Fine", "customfield_1", null, null),
                FieldCreationResult.partial("Field", "customfield_10800", Collections.singletonList(actual),
                        "planned [PAY] but Jira reports [SHOP]")));
        JsonNode result = call("create_custom_fields", applied);
        JsonNode direct = payload(result);
        JsonNode item = direct.path("results").get(1);
        assertTrue(result.path("isError").asBoolean());
        assertEquals("PARTIAL", item.path("status").asText());
        assertEquals("customfield_10800", item.path("customFieldId").asText());
        assertEquals("CONTEXT_MISMATCH", item.path("errorCode").asText());
        assertEquals("SHOP", item.path("contexts").get(0).path("projects").get(0).path("key").asText());
        assertEquals("planned [PAY] but Jira reports [SHOP]", item.path("message").asText());
        assertEquals("customfield_1", direct.path("results").get(0).path("customFieldId").asText());
    }

    @Test public void nestedScreenFailuresAreErrorsButSuccessfulScreenBatchesAreNot() throws Exception {
        assumeTrue(catalog.isPublished("add_field_to_screens"));
        FieldScreensResult good = new FieldScreensResult("customfield_1", "Good",
                Collections.singletonList(ScreenResult.wouldAdd(1, "Edit", "Field Tab")));
        FieldScreensResult bad = new FieldScreensResult("customfield_2", "Bad", Collections.singletonList(
                ScreenResult.failed(2L, null, ScreenErrorCode.UNKNOWN_SCREEN, "Missing screen")));
        JsonNode mixed = call("add_field_to_screens", new AddToScreensResponse(true, Arrays.asList(good, bad)));
        assertTrue(mixed.path("isError").asBoolean());
        assertEquals("WOULD_ADD", payload(mixed).path("fields").get(0).path("results").get(0).path("status").asText());
        assertEquals("UNKNOWN_SCREEN", payload(mixed).path("fields").get(1).path("results").get(0).path("errorCode").asText());
        assertFalse(call("add_field_to_screens", new AddToScreensResponse(true,
                Collections.singletonList(good))).path("isError").asBoolean());
    }

    @Test public void staleBatchPlansWithoutATopLevelStatusAreErrors() throws Exception {
        assumeTrue(catalog.isPublished("create_custom_fields"));
        assumeTrue(catalog.isPublished("add_field_to_screens"));
        for (JsonNode result : Arrays.asList(call("create_custom_fields", CreateCustomFieldsResponse.stale()),
                call("add_field_to_screens", AddToScreensResponse.stale()))) {
            assertTrue(result.path("isError").asBoolean());
            assertEquals("PREVIEW_STALE", payload(result).path("errorCode").asText());
        }
    }

    @Test public void partialDomainOutcomesAreErrorsAndRetainEvidenceOfChanges() throws Exception {
        for (String status : Arrays.asList("PARTIALLY_ASSIGNED", "PARTIALLY_CHANGED")) {
            String tool = "PARTIALLY_ASSIGNED".equals(status) ? "assign_workflow" : "set_project_issue_types";
            assumeTrue(catalog.isPublished(tool));
            JsonNode result = call(tool, mapper.readTree("{\"status\":\"" + status
                    + "\",\"message\":\"Some changes were committed; do not repeat apply\"}"));
            assertTrue(status, result.path("isError").asBoolean());
            assertEquals("Some changes were committed; do not repeat apply", payload(result).path("message").asText());
        }
    }

    @Test public void operationReceiptsAreSuccessfulReadsNotTheExecutionOfTheStoredDomainResult() throws Exception {
        for (String tool : Arrays.asList("start_operation", "get_operation")) {
            for (String state : Arrays.asList("QUEUED", "RUNNING", "CANCEL_REQUESTED", "COMPLETED", "FAILED",
                    "CANCELLED", "BLOCKED", "PARTIAL", "ROLLED_BACK")) {
                String stored = "COMPLETED".equals(state)
                        ? ",\"result\":{\"status\":\"FAILED\",\"errorCode\":\"VALIDATION_ERROR\",\"message\":\"Inspect domain failure\"}"
                        : "FAILED".equals(state) || "BLOCKED".equals(state) || "PARTIAL".equals(state)
                            ? ",\"error\":\"Persisted diagnostic\"" : "";
                JsonNode receipt = mapper.readTree("{\"operationId\":\"owned-operation\",\"status\":\"" + state
                        + "\",\"operation\":\"configure_workflow\"" + stored + "}");
                JsonNode result = call(tool, receipt);
                assertFalse(tool + ": " + state, result.path("isError").asBoolean());
                assertEquals(receipt, payload(result));
            }
        }
    }

    @Test public void operationMcpViewOmitsUndoDetailsWithoutChangingJournalEvidence() throws Exception {
        for (String tool : Arrays.asList("start_operation", "get_operation")) {
            JsonNode receipt = mapper.readTree("{\"operationId\":\"owned-operation\",\"status\":\"PARTIAL\","
                    + "\"undoExpiresAt\":1792500000000,\"rollbackRequested\":true,"
                    + "\"result\":{\"status\":\"FAILED\",\"message\":\"Created status remains\","
                    + "\"warnings\":[\"Inspect remaining status\"]},"
                    + "\"error\":\"Compensation conflict\","
                    + "\"steps\":[{\"kind\":\"status.created\",\"status\":\"BLOCKED\",\"error\":\"Status in use\"}],"
                    + "\"execution\":{\"counts\":{\"completed\":1,\"unknown\":1}}}");
            JsonNode original = mapper.readTree(receipt.toString());
            JsonNode expected = mapper.readTree(receipt.toString());
            ((com.fasterxml.jackson.databind.node.ObjectNode) expected).remove("undoExpiresAt");
            ((com.fasterxml.jackson.databind.node.ObjectNode) expected).remove("rollbackRequested");
            JsonNode result = call(tool, receipt);
            assertFalse(result.path("isError").asBoolean());
            assertEquals(expected, payload(result));
            assertEquals("MCP must not mutate the internal journal result", original, receipt);
        }
    }

    @Test public void successfulBackgroundAppliesRemainSuccessfulMcpResults() throws Exception {
        for (String status : Arrays.asList("CREATED", "ASSIGNED", "EXISTS", "ALREADY_ASSIGNED")) {
            JsonNode receipt = mapper.readTree("{\"operationId\":\"owned-operation\",\"status\":\"COMPLETED\","
                    + "\"result\":{\"status\":\"" + status + "\"}}");
            JsonNode result = call("get_operation", receipt);
            assertFalse(status, result.path("isError").asBoolean());
            assertEquals(status, payload(result).path("result").path("status").asText());
        }
    }

    @Test public void failedOperationLookupIsAnErrorRatherThanASuccessfulReceipt() throws Exception {
        McpServer server = server("get_operation", arguments -> { throw new InvalidRequestException("Unknown owned operation"); });
        JsonNode result = send(server, "get_operation");
        assertTrue(result.path("isError").asBoolean());
        assertEquals("Unknown owned operation", result.path("content").get(0).path("text").asText());
    }

    @Test public void discoveryDataNamedFailedIsNotAnExecutionError() throws Exception {
        JsonNode data = mapper.readTree("{\"statuses\":[{\"name\":\"FAILED\",\"category\":\"DONE\"}],"
                + "\"metadata\":{\"status\":\"FAILED\"}}");
        JsonNode result = call("list_statuses", data);
        assertFalse(result.path("isError").asBoolean());
        assertEquals(data, payload(result));
    }

    @Test public void aNamesLookupIsOnlyTheLookupWithoutTotalOrTopLevelStatuses() throws Exception {
        StatusResponse done = new StatusResponse("10001", "Done", "DONE", "");
        JsonNode result = call("list_statuses", new StatusLookupResponse(Arrays.asList(
                new StatusLookupEntry("done", StatusLookupEntry.Result.FOUND, Collections.singletonList(done)),
                new StatusLookupEntry("Nope", StatusLookupEntry.Result.NOT_FOUND, Collections.<StatusResponse>emptyList()))));
        assertFalse(result.toString(), result.path("isError").asBoolean());
        JsonNode body = payload(result);
        assertEquals("[lookup]", String.valueOf(iterate(body)));
        assertEquals("FOUND", body.path("lookup").get(0).path("result").asText());
        assertEquals("10001", body.path("lookup").get(0).path("statuses").get(0).path("id").asText());
        assertEquals("NOT_FOUND", body.path("lookup").get(1).path("result").asText());
        assertEquals(0, body.path("lookup").get(1).path("statuses").size());
    }
    private static java.util.List<String> iterate(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private JsonNode call(String tool, Object value) throws Exception { return send(server(tool, arguments -> value), tool); }
    private McpServer server(String name, ToolCall call) {
        return new McpServer(mock(AdministratorGuard.class), catalog,
                new ToolRegistry(Collections.singletonList(() -> Collections.singletonMap(name, call))));
    }
    private JsonNode send(McpServer server, String tool) throws Exception {
        JsonNode reply = mapper.readTree(server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":{\"dryRun\":true}}}"));
        assertFalse(reply.toString(), reply.has("error"));
        return reply.path("result");
    }
    private JsonNode payload(JsonNode result) throws Exception { return mapper.readTree(result.path("content").get(0).path("text").asText()); }
}
