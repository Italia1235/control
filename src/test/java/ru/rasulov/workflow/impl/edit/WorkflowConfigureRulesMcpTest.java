package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.atlassian.jira.workflow.WorkflowUtil;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.tools.*;
import ru.rasulov.workflow.api.*;
import ru.rasulov.workflow.impl.WorkflowTools;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real MCP dispatch, planner, native descriptor store and operation journal for create/rules/structural configuration. */
public class WorkflowConfigureRulesMcpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "\"create\":{\"name\":\"L010-rules\",\"statuses\":[{\"name\":\"Open\"},{\"name\":\"Done\"}],\"initialStatus\":\"Open\","
            + "\"transitions\":[{\"key\":\"finish\",\"name\":\"Finish\",\"from\":\"Open\",\"to\":\"Done\",\"resolution\":{\"action\":\"SET\",\"value\":\"1\"}}]}";

    private static String request(String rules) { return "{\"dryRun\":true," + CREATE + ",\"transitionRules\":" + rules + "}"; }

    private static ObjectNode envelope(String tool, String arguments) throws Exception {
        ObjectNode request = JSON.createObjectNode(); request.put("jsonrpc", "2.0"); request.put("id", 1); request.put("method", "tools/call");
        ObjectNode params = request.putObject("params"); params.put("name", tool); params.set("arguments", JSON.readTree(arguments));
        return request;
    }

    private interface Check { void run(McpServer server, WorkflowConfigurationFixture world) throws Exception; }

    private static void withServer(Check check) throws Exception {
        try (WorkflowConfigurationFixture world = new WorkflowConfigurationFixture()) {
            ToolRegistry[] registry = new ToolRegistry[1];
            ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
            Semaphore finished = new Semaphore(0);
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            doAnswer(c -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
            ApplyJournal applies = mock(ApplyJournal.class);
            OperationRunner runner = new OperationRunner(world.journalFixture.journal, world.journalFixture.guard, () -> registry[0], catalog,
                    mock(JiraAuthenticationContext.class), locals, world.journalFixture.plans, applies);
            try {
                registry[0] = new ToolRegistry(List.of(new WorkflowTools(world.configurer, mock(WorkflowStatusReader.class),
                        mock(WorkflowAssigner.class), mock(WorkflowCloner.class), mock(WorkflowDraftPublisher.class),
                        mock(WorkflowReader.class), mock(WorkflowRenamer.class),
                        mock(StatusUpdater.class), mock(WorkflowCopier.class), mock(WorkflowValidatorReader.class),
                        mock(WorkflowConditionReader.class)),
                        new ChangeTools(world.journalFixture.journal, world.journalFixture.guard, runner, world.journalFixture.plans, applies)));
                check.run(new McpServer(world.journalFixture.guard, catalog, registry[0]), world);
            } finally {
                runner.destroy();
                int submitted = world.journalFixture.entityManager.find(ru.rasulov.common.impl.undo.JournalOperation.class).length;
                assertTrue("operation workers must finish before closing AO", finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            }
        }
    }

    private static JsonNode result(McpServer server, String arguments) throws Exception {
        JsonNode rpc = JSON.readTree(server.handle(envelope("configure_workflow", arguments).toString()));
        assertFalse(rpc.toString(), rpc.has("error"));
        return rpc.path("result");
    }

    private static JsonNode call(McpServer server, String tool, JsonNode arguments) throws Exception {
        JsonNode rpc = JSON.readTree(server.handle(envelope(tool, arguments.toString()).toString()));
        assertFalse(rpc.toString(), rpc.has("error"));
        JsonNode response = rpc.path("result");
        assertFalse(response.toString(), response.path("isError").asBoolean());
        return JSON.readTree(response.path("content").get(0).path("text").asText());
    }

    private static ObjectNode existing(WorkflowConfigurationFixture world, String name, String changes) throws Exception {
        ObjectNode request = JSON.createObjectNode(); request.put("dryRun", true);
        ObjectNode target = request.putObject("existing");
        target.put("workflow", name).put("version", "ACTIVE").put("expectedRevision", world.revision(name));
        if (changes != null) target.set("changes", JSON.readTree(changes));
        return request;
    }

    private static JsonNode preview(McpServer server, WorkflowConfigurationFixture world, ObjectNode request) throws Exception {
        String name = request.path("existing").path("workflow").asText();
        String before = DescriptorFacts.definition(world.main.get(name));
        JsonNode preview = call(server, "configure_workflow", request);
        assertEquals(preview.toString(), "WOULD_CHANGE", preview.path("status").asText());
        assertFalse(preview.toString(), preview.path("planId").asText().isEmpty());
        assertEquals(before, DescriptorFacts.definition(world.main.get(name)));
        assertTrue(world.nativeCalls.isEmpty());
        assertEquals(0, world.journalFixture.entityManager.find(ru.rasulov.common.impl.undo.JournalOperation.class).length);
        return preview;
    }

    private static JsonNode apply(McpServer server, JsonNode preview) throws Exception {
        ObjectNode start = JSON.createObjectNode(); start.put("toolName", "configure_workflow");
        start.putObject("arguments").put("dryRun", false).put("planId", preview.path("planId").asText());
        JsonNode started = call(server, "start_operation", start);
        assertFalse(started.toString(), started.path("operationId").asText().isEmpty());
        ObjectNode query = JSON.createObjectNode();
        query.put("operationId", started.path("operationId").asText()).put("waitSeconds", 20);
        JsonNode terminal = call(server, "get_operation", query);
        assertEquals(terminal.toString(), "COMPLETED", terminal.path("status").asText());
        JsonNode saved = terminal.path("result");
        assertEquals(saved.toString(), "UPDATED_INACTIVE", saved.path("status").asText());
        assertTrue(terminal.toString(), terminal.path("execution").path("steps").size() > 0);
        for (JsonNode step : terminal.path("execution").path("steps")) {
            assertEquals(step.toString(), "APPLIED", step.path("writeStatus").asText());
            assertEquals(step.toString(), "MATCH", step.path("verification").asText());
        }
        return saved;
    }

    @Test public void actualScreenPreviewsRemainCompatibleWithTheLiveConsumer() throws Exception {
        var cases = JSON.createArrayNode();
        for (String mode : List.of("no-screen", "same-id", "other-view")) {
            withServer((server, world) -> {
                String name = "L012-screen-consumer";
                WorkflowConfigurationNativeTest.existingGraph(world, name, false);
                world.screen(7, "Form one", List.of("comment"));
                world.screen(9, "Form two", List.of("comment"));
                var initial = DescriptorFacts.locate(world.main.get(name)).get(1).action();
                DescriptorFacts.meta(initial).put(DescriptorFacts.SCREEN_META, "7");
                initial.setView("fieldscreen");
                var target = DescriptorFacts.locate(world.main.get(name)).get(11).action();
                if (!mode.equals("no-screen")) DescriptorFacts.meta(target).put(DescriptorFacts.SCREEN_META, "7");
                target.setView(mode.equals("other-view") ? "other-view" : null);
                ObjectNode evidence = cases.addObject();
                evidence.put("mode", mode).put("workflow", name).put("before", DescriptorFacts.xml(world.main.get(name)));
                var trace = evidence.putArray("trace");
                for (int plan = 0; plan < 2; plan++) {
                    ObjectNode request = existing(world, name, null);
                    ObjectNode rule = request.putArray("transitionRules").addObject().put("actionId", 11);
                    rule.putObject("screen").put("action", "SET").put("screenId", plan == 0 ? "7" : "9");
                    if (plan == 0) rule.putArray("addValidators").addObject().put("template", "comment_required");
                    int writes = world.nativeCalls.size();
                    JsonNode preview = call(server, "configure_workflow", request);
                    assertEquals(preview.toString(), "WOULD_CHANGE", preview.path("status").asText());
                    assertEquals(writes, world.nativeCalls.size());
                    ObjectNode previewEvent = trace.addObject().put("seq", trace.size() + 1).put("tool", "configure_workflow");
                    previewEvent.set("arguments", request);
                    previewEvent.set("result", preview);
                    ObjectNode start = JSON.createObjectNode().put("toolName", "configure_workflow");
                    start.putObject("arguments").put("dryRun", false).put("planId", preview.path("planId").asText());
                    JsonNode started = call(server, "start_operation", start);
                    ObjectNode startEvent = trace.addObject().put("seq", trace.size() + 1).put("tool", "start_operation");
                    startEvent.set("arguments", start);
                    startEvent.set("result", started);
                    ObjectNode query = JSON.createObjectNode().put("operationId", started.path("operationId").asText()).put("waitSeconds", 20);
                    JsonNode terminal = call(server, "get_operation", query);
                    assertEquals(terminal.toString(), "COMPLETED", terminal.path("status").asText());
                    assertEquals(terminal.toString(), "UPDATED_INACTIVE", terminal.path("result").path("status").asText());
                    ObjectNode terminalEvent = trace.addObject().put("seq", trace.size() + 1).put("tool", "get_operation");
                    terminalEvent.set("arguments", query);
                    terminalEvent.set("result", terminal);
                }
                evidence.put("after", DescriptorFacts.xml(world.main.get(name)));
                evidence.set("validators", JSON.valueToTree(new WorkflowValidatorReaderImpl(world.journalFixture.guard,
                        world.inspector, world.fields, world.screens, world.gate).read(name, "ACTIVE")));
                evidence.set("state", JSON.valueToTree(java.util.Map.of("active", false, "hasDraft", false,
                        "consumers", java.util.Map.of("projectCount", 0))));
            });
        }
        Path payload = Files.createTempFile("screen-preview-consumer-", ".json");
        Path output = Files.createTempFile("screen-preview-consumer-", ".log");
        try {
            JSON.writeValue(payload.toFile(), cases);
            Process consumer = new ProcessBuilder("node", "evals/offline/screen-preview-consumer.mjs", payload.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            boolean finished = consumer.waitFor(30, TimeUnit.SECONDS);
            if (!finished) consumer.destroyForcibly();
            assertTrue("screen consumer timed out", finished);
            assertEquals(Files.readString(output), 0, consumer.exitValue());
        } finally {
            Files.deleteIfExists(payload);
            Files.deleteIfExists(output);
        }
    }

    @Test public void allEightStructuralOperationsPreviewAndApplyThroughMcp() throws Exception {
        withServer((server, world) -> {
            String name = "L012-eight";
            WorkflowDescriptor original = WorkflowUtil.convertXMLtoWorkflowDescriptor(EditFixtures.DTD
                    + "<workflow><meta name=\"acme.owner\">team-a</meta><initial-actions>"
                    + EditFixtures.action(1, "Create", 1, "", EditFixtures.REINDEX) + "</initial-actions><steps>"
                    + "<step id=\"1\" name=\"Open\"><meta name=\"jira.status.id\">1</meta><actions>"
                    + EditFixtures.action(11, "Start", 2, "", EditFixtures.STATUS + EditFixtures.HISTORY)
                    + EditFixtures.action(12, "Stay open", 1, "", EditFixtures.STATUS + EditFixtures.HISTORY)
                    + "</actions></step><step id=\"2\" name=\"In Progress\"><meta name=\"jira.status.id\">2</meta><actions>"
                    + EditFixtures.action(21, "Return", 1, "", EditFixtures.STATUS + EditFixtures.HISTORY)
                    + "</actions></step><step id=\"3\" name=\"Done\"><meta name=\"jira.status.id\">3</meta></step></steps></workflow>");
            world.putWorkflow(name, original, false);
            world.status("4", "Reviewed", "IN_PROGRESS"); world.status("5", "Ready", "IN_PROGRESS");
            world.screen(7, "Existing form", List.of("description"));
            // The same text is deliberately a step key, transition key and new-screen key.
            ObjectNode request = existing(world, name, "["
                    + "{\"op\":\"REMOVE_STATUS\",\"stepId\":3},"
                    + "{\"op\":\"UPDATE_METADATA\",\"description\":\"Revised through MCP\"},"
                    + "{\"op\":\"SET_INITIAL_STATUS\",\"clientKey\":\"local\"},"
                    + "{\"op\":\"REMOVE_TRANSITION\",\"actionId\":21},"
                    + "{\"op\":\"ADD_TRANSITION\",\"transitionKey\":\"local\",\"name\":\"Back\",\"source\":{\"clientKey\":\"local\"},\"targetStepId\":1,\"resolution\":{\"action\":\"CLEAR\"}},"
                    + "{\"op\":\"UPDATE_TRANSITION\",\"actionId\":11,\"name\":\"Begin\",\"source\":{\"stepId\":2},\"targetClientKey\":\"local\",\"screen\":{\"action\":\"SET\",\"screenId\":\"7\"},\"resolution\":{\"action\":\"CLEAR\"}},"
                    + "{\"op\":\"REPLACE_STATUS\",\"stepId\":2,\"statusId\":\"4\"},"
                    + "{\"op\":\"ADD_STATUS\",\"statusId\":\"5\",\"clientKey\":\"local\"}]");
            request.set("newScreens", JSON.readTree("[{\"key\":\"local\",\"name\":\"L012 return form\",\"fieldIds\":[\"description\"]}]"));
            request.set("transitionRules", JSON.readTree("[{\"transitionKey\":\"local\",\"screen\":{\"action\":\"SET\",\"screenKey\":\"local\"},"
                    + "\"addValidators\":[{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"description\"]}}],"
                    + "\"addConditions\":{\"operator\":\"AND\",\"rules\":[{\"template\":\"group\",\"parameters\":{\"group\":\"admins\"}}]}}]"));
            JsonNode preview = preview(server, world, request);
            for (String op : List.of("ADD_STATUS", "REPLACE_STATUS", "UPDATE_TRANSITION", "ADD_TRANSITION",
                    "REMOVE_TRANSITION", "SET_INITIAL_STATUS", "UPDATE_METADATA", "REMOVE_STATUS"))
                assertTrue(op + ": " + preview, preview.path("diff").toString().contains(op));
            JsonNode saved = apply(server, preview);
            WorkflowDescriptor stored = world.main.get(name);
            assertEquals("Revised through MCP", DescriptorFacts.meta(stored).get("jira.description"));
            assertEquals("team-a", DescriptorFacts.meta(stored).get("acme.owner"));
            assertNull(DescriptorFacts.step(stored, 3));
            assertEquals("4", DescriptorFacts.statusId(DescriptorFacts.step(stored, 2)));
            assertEquals("5", DescriptorFacts.statusId(DescriptorFacts.step(stored, 4)));
            assertTrue("removing a workflow step must not delete its global status", world.statusStore.containsKey("3"));
            assertEquals(4, DescriptorFacts.locate(stored).get(1).action().getUnconditionalResult().getStep());
            assertEquals("keep me 1", DescriptorFacts.meta(DescriptorFacts.locate(stored).get(1).action()).get("jira.description"));
            ActionDescriptor updated = DescriptorFacts.locate(stored).get(11).action();
            assertEquals("Begin", updated.getName());
            assertEquals(List.of(2), DescriptorFacts.locate(stored).get(11).sources());
            assertEquals(4, updated.getUnconditionalResult().getStep());
            assertEquals("7", DescriptorFacts.meta(updated).get(DescriptorFacts.SCREEN_META));
            assertEquals("CLEAR", DescriptorFacts.rule(updated).action());
            assertEquals("keep me 11", DescriptorFacts.meta(updated).get("jira.description"));
            assertFalse(DescriptorFacts.locate(stored).containsKey(21));
            JsonNode mapped = null;
            for (JsonNode transition : saved.path("transitions")) if ("local".equals(transition.path("transitionKey").asText())) mapped = transition;
            assertNotNull(saved.toString(), mapped);
            int actionId = mapped.path("actionId").asInt();
            ActionDescriptor added = DescriptorFacts.locate(stored).get(actionId).action();
            assertEquals("Back", added.getName()); assertEquals(1, added.getUnconditionalResult().getStep());
            assertEquals(List.of(4), DescriptorFacts.locate(stored).get(actionId).sources());
            assertEquals(List.of("description"), RequiredFieldsDescriptor.scan(added).get(0).fieldIds());
            assertEquals(1, added.getRestriction().getConditionsDescriptor().getConditions().size());
            assertEquals("fieldscreen", added.getView());
            assertEquals(mapped.path("screenId").asText(), DescriptorFacts.meta(added).get(DescriptorFacts.SCREEN_META));
            assertNotNull(world.screenStore.get(Long.valueOf(mapped.path("screenId").asText())));
            boolean stepMapped = false, screenMapped = false;
            for (JsonNode object : saved.path("objects")) {
                if ("STEP".equals(object.path("kind").asText()) && "local".equals(object.path("clientKey").asText())) {
                    assertEquals(4, object.path("stepId").asInt()); stepMapped = true;
                }
                if ("SCREEN".equals(object.path("kind").asText()) && "local".equals(object.path("key").asText())) {
                    assertEquals(mapped.path("screenId").asText(), object.path("screenId").asText()); screenMapped = true;
                }
            }
            assertTrue(saved.toString(), stepMapped); assertTrue(saved.toString(), screenMapped);
            assertEquals(List.of("screen", "tab", "field", "workflow-save"), world.nativeCalls);
        });
    }

    @Test public void structuralScreenClearAndRuleKeepApplyWithoutAConflict() throws Exception {
        withServer((server, world) -> {
            String name = "L012-clear";
            WorkflowConfigurationNativeTest.existingGraph(world, name, false);
            world.screen(7, "Original form", List.of("description"));
            ActionDescriptor original = DescriptorFacts.locate(world.main.get(name)).get(11).action();
            DescriptorFacts.meta(original).put(DescriptorFacts.SCREEN_META, "7"); original.setView("fieldscreen");
            ObjectNode request = existing(world, name, "[{\"op\":\"UPDATE_TRANSITION\",\"actionId\":11,\"screen\":{\"action\":\"CLEAR\"},\"resolution\":{\"action\":\"CLEAR\"}}]");
            request.set("transitionRules", JSON.readTree("[{\"actionId\":11,\"screen\":\"KEEP\",\"addValidators\":[{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"description\"]}}]}]"));
            apply(server, preview(server, world, request));
            ActionDescriptor stored = DescriptorFacts.locate(world.main.get(name)).get(11).action();
            assertFalse(DescriptorFacts.meta(stored).containsKey(DescriptorFacts.SCREEN_META));
            assertFalse("fieldscreen".equals(stored.getView()));
            assertEquals("CLEAR", DescriptorFacts.rule(stored).action());
            assertEquals(List.of("description"), RequiredFieldsDescriptor.scan(stored).get(0).fieldIds());
            assertEquals(List.of("workflow-save"), world.nativeCalls);
        });
    }

    @Test public void existingRulesOnlyWithoutChangesStillPreviewAndApplyThroughMcp() throws Exception {
        withServer((server, world) -> {
            WorkflowConfigurationNativeTest.existingGraph(world, "L012-rules-only", false);
            ObjectNode request = existing(world, "L012-rules-only", null);
            request.set("transitionRules", JSON.readTree("[{\"actionId\":11,\"addValidators\":[{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"description\"]}}]}]"));
            apply(server, preview(server, world, request));
            ActionDescriptor stored = DescriptorFacts.locate(world.main.get("L012-rules-only")).get(11).action();
            assertEquals("Start", stored.getName());
            assertEquals(List.of("description"), RequiredFieldsDescriptor.scan(stored).get(0).fieldIds());
            assertEquals(List.of("workflow-save"), world.nativeCalls);
        });
    }

    @Test public void optionalCaseSensitiveAndMaximumLengthTransitionKeysApplyThroughMcp() throws Exception {
        withServer((server, world) -> {
            String name = "L012-keys", longest = "k".repeat(255);
            WorkflowConfigurationNativeTest.existingGraph(world, name, false);
            ObjectNode request = existing(world, name, "["
                    + "{\"op\":\"ADD_TRANSITION\",\"transitionKey\":\"return\",\"name\":\"Lower\",\"source\":{\"stepId\":2},\"targetStepId\":1,\"resolution\":{\"action\":\"PRESERVE\"}},"
                    + "{\"op\":\"ADD_TRANSITION\",\"transitionKey\":\"Return\",\"name\":\"Upper\",\"source\":{\"stepId\":2},\"targetStepId\":1},"
                    + "{\"op\":\"ADD_TRANSITION\",\"transitionKey\":\"" + longest + "\",\"name\":\"Longest\",\"source\":{\"stepId\":2},\"targetStepId\":1},"
                    + "{\"op\":\"ADD_TRANSITION\",\"name\":\"Unkeyed\",\"source\":{\"fromAny\":true},\"targetStepId\":1,\"resolution\":{\"action\":\"CLEAR\"}}]");
            request.set("transitionRules", JSON.readTree("[{\"transitionKey\":\"return\",\"addValidators\":[{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"description\"]}}]},"
                    + "{\"transitionKey\":\"Return\",\"addConditions\":{\"operator\":\"AND\",\"rules\":[{\"template\":\"group\",\"parameters\":{\"group\":\"admins\"}}]}},"
                    + "{\"transitionKey\":\"" + longest + "\",\"addValidators\":[{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"assignee\"]}}]}]"));
            JsonNode saved = apply(server, preview(server, world, request));
            int keyed = 0;
            for (JsonNode transition : saved.path("transitions")) {
                String key = transition.path("transitionKey").asText();
                if (key.isEmpty()) continue;
                ActionDescriptor action = DescriptorFacts.locate(world.main.get(name)).get(transition.path("actionId").asInt()).action();
                if ("return".equals(key)) {
                    assertEquals("Lower", action.getName());
                    assertEquals(List.of("description"), RequiredFieldsDescriptor.scan(action).get(0).fieldIds());
                } else if ("Return".equals(key)) {
                    assertEquals("Upper", action.getName());
                    assertEquals(1, action.getRestriction().getConditionsDescriptor().getConditions().size());
                } else {
                    assertEquals(longest, key); assertEquals("Longest", action.getName());
                    assertEquals(List.of("assignee"), RequiredFieldsDescriptor.scan(action).get(0).fieldIds());
                }
                keyed++;
            }
            assertEquals(3, keyed);
            assertTrue(DescriptorFacts.locate(world.main.get(name)).values().stream()
                    .anyMatch(l -> l.kind() == DescriptorFacts.Kind.GLOBAL && "Unkeyed".equals(l.action().getName())));
            assertEquals(List.of("workflow-save"), world.nativeCalls);
        });
    }

    private static void refused(String rules, String token) throws Exception {
        withServer((server, world) -> {
            JsonNode result = result(server, request(rules));
            String text = result.path("content").get(0).path("text").asText();
            assertTrue(result.toString(), result.path("isError").asBoolean());
            assertTrue(text, text.contains(token));
            assertFalse(text, text.contains("planId"));
            assertTrue(world.nativeCalls.isEmpty());
            assertEquals(0, world.journalFixture.entityManager.find(ru.rasulov.common.impl.undo.JournalOperation.class).length);
        });
    }

    @Test public void existingStructuralChangesReachTheRealMcpPreviewWithoutWrites() throws Exception {
        withServer((server, world) -> {
            world.putWorkflow("L012-existing", EditFixtures.workflow(), false);
            String arguments = "{\"dryRun\":true,\"existing\":{\"workflow\":\"L012-existing\",\"version\":\"ACTIVE\","
                    + "\"expectedRevision\":\"" + world.revision("L012-existing") + "\","
                    + "\"changes\":[{\"op\":\"UPDATE_METADATA\",\"description\":\"LOCAL-012 revised\"}]}}";
            JsonNode response = result(server, arguments);
            assertFalse(response.toString(), response.path("isError").asBoolean());
            JsonNode preview = JSON.readTree(response.path("content").get(0).path("text").asText());
            assertEquals(preview.toString(), "WOULD_CHANGE", preview.path("status").asText());
            assertFalse(preview.toString(), preview.path("planId").asText().isEmpty());
            assertTrue(world.nativeCalls.isEmpty());
            assertEquals(0, world.journalFixture.entityManager.find(ru.rasulov.common.impl.undo.JournalOperation.class).length);
        });
    }

    @Test public void typeInsteadOfTemplateIsRefusedByName() throws Exception {
        refused("[{\"transitionKey\":\"finish\",\"addValidators\":[{\"type\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"description\"]}}]}]", "\"type\"");
    }

    @Test public void extraKeyInAConditionIsRefusedByName() throws Exception {
        refused("[{\"transitionKey\":\"finish\",\"addConditions\":{\"operator\":\"AND\",\"rules\":[{\"template\":\"group\",\"extraKey\":\"x\",\"parameters\":{\"group\":\"admins\"}}]}}]", "\"extraKey\"");
    }

    @Test public void extraKeyInParametersIsRefusedByName() throws Exception {
        refused("[{\"transitionKey\":\"finish\",\"addValidators\":[{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"description\"],\"bogusKey\":1}}]}]", "\"bogusKey\"");
    }

    @Test public void conditionsGivenAsAnArrayAreRefused() throws Exception {
        refused("[{\"transitionKey\":\"finish\",\"addConditions\":[{\"template\":\"group\",\"parameters\":{\"group\":\"admins\"}}]}]", "'addConditions'");
    }

    @Test public void validRulesAndResolutionOnATransitionIntoDoneReachTheCreatePreviewDiff() throws Exception {
        withServer((server, world) -> {
            JsonNode result = result(server, request("[{\"transitionKey\":\"finish\",\"addValidators\":[{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"description\"]}}],"
                    + "\"addConditions\":{\"operator\":\"AND\",\"rules\":[{\"template\":\"project_role\",\"parameters\":{\"roles\":[\"10000\"]}}]}}]"));
            assertFalse(result.toString(), result.path("isError").asBoolean());
            JsonNode preview = JSON.readTree(result.path("content").get(0).path("text").asText());
            assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
            assertFalse(preview.path("planId").asText().isEmpty());
            StringBuilder diff = new StringBuilder();
            for (JsonNode line : preview.path("diff")) diff.append(line.asText()).append('\n');
            assertTrue(diff.toString(), diff.toString().toLowerCase().contains("validator"));
            assertTrue(diff.toString(), diff.toString().toLowerCase().contains("condition"));
            assertTrue(world.nativeCalls.isEmpty());
        });
    }

    @Test public void validConditionTemplatesAndXmlWhitespaceDescriptionReachPreview() throws Exception {
        for (String condition : new String[] {
                "{\"template\":\"group\",\"parameters\":{\"group\":\"admins\"}}",
                "{\"template\":\"project_role\",\"parameters\":{\"roles\":[10000]}}",
                "{\"template\":\"project_role\",\"parameters\":{\"roles\":[10000],\"invert\":false}}"}) {
            withServer((server, world) -> {
                ObjectNode arguments = (ObjectNode) JSON.readTree(request("[{\"transitionKey\":\"finish\",\"addConditions\":{\"operator\":\"AND\",\"rules\":["
                        + condition + "]}}]"));
                ((ObjectNode) arguments.get("create")).put("description", "First\tline\nSecond\rline");
                JsonNode result = result(server, arguments.toString());
                assertFalse(result.toString(), result.path("isError").asBoolean());
                JsonNode preview = JSON.readTree(result.path("content").get(0).path("text").asText());
                assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
                assertFalse(preview.toString(), preview.path("planId").asText().isEmpty());
                assertTrue(world.nativeCalls.isEmpty());
                assertEquals(0, world.journalFixture.entityManager.find(ru.rasulov.common.impl.undo.JournalOperation.class).length);
            });
        }
    }
}
