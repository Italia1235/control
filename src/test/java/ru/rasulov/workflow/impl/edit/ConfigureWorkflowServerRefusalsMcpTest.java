package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.tools.*;
import ru.rasulov.workflow.api.*;
import ru.rasulov.workflow.impl.WorkflowTools;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Flat-schema and structural-input constraints are enforced by the
 * server through the real tools/call path: FAILED/isError, no planId, no native call, no journal row, and the message
 * names the field (or its request path).
 */
public class ConfigureWorkflowServerRefusalsMcpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"name\":\"L011-refusals\",\"statuses\":[{\"name\":\"Open\"},{\"name\":\"Done\"}],\"initialStatus\":\"Open\","
            + "\"transitions\":[{\"key\":\"finish\",\"name\":\"Finish\",\"from\":\"Open\",\"to\":\"Done\",\"resolution\":{\"action\":\"SET\",\"value\":\"1\"}}]}";
    private static final String VALIDATOR = "{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"description\"]}}";

    private static ObjectNode createRequest() throws Exception {
        ObjectNode request = JSON.createObjectNode(); request.put("dryRun", true); request.set("create", JSON.readTree(CREATE));
        return request;
    }

    private static ObjectNode createRequest(String rules) throws Exception {
        ObjectNode request = createRequest(); request.set("transitionRules", JSON.readTree(rules));
        return request;
    }

    private static ObjectNode existingRequest(String workflow, String version, String revision, String rules) throws Exception {
        ObjectNode request = JSON.createObjectNode(); request.put("dryRun", true);
        ObjectNode existing = request.putObject("existing");
        if (workflow != null) existing.put("workflow", workflow);
        if (version != null) existing.put("version", version);
        if (revision != null) existing.put("expectedRevision", revision);
        if (rules != null) request.set("transitionRules", JSON.readTree(rules));
        return request;
    }

    private static String repeat(String item, int times) { return "[" + String.join(",", java.util.Collections.nCopies(times, item)) + "]"; }

    private static ObjectNode envelope(String arguments) throws Exception {
        ObjectNode request = JSON.createObjectNode(); request.put("jsonrpc", "2.0"); request.put("id", 1); request.put("method", "tools/call");
        ObjectNode params = request.putObject("params"); params.put("name", "configure_workflow"); params.set("arguments", JSON.readTree(arguments));
        return request;
    }

    private interface Prepared { ObjectNode request(WorkflowConfigurationFixture world) throws Exception; }

    private static void refused(Prepared prepared, String... tokens) throws Exception {
        try (WorkflowConfigurationFixture world = new WorkflowConfigurationFixture()) {
            ToolRegistry[] registry = new ToolRegistry[1];
            ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
            ApplyJournal applies = mock(ApplyJournal.class);
            OperationRunner runner = new OperationRunner(world.journalFixture.journal, world.journalFixture.guard, () -> registry[0], catalog,
                    mock(JiraAuthenticationContext.class), mock(JiraThreadLocalUtil.class), world.journalFixture.plans, applies);
            try {
                registry[0] = new ToolRegistry(List.of(new WorkflowTools(world.configurer, mock(WorkflowStatusReader.class),
                        mock(WorkflowAssigner.class), mock(WorkflowCloner.class), mock(WorkflowDraftPublisher.class),
                        mock(WorkflowReader.class), mock(WorkflowRenamer.class),
                        mock(StatusUpdater.class), mock(WorkflowCopier.class), mock(WorkflowValidatorReader.class),
                        mock(WorkflowConditionReader.class)),
                        new ChangeTools(world.journalFixture.journal, world.journalFixture.guard, runner, world.journalFixture.plans, applies)));
                String arguments = prepared.request(world).toString();
                java.util.Map<String, String> before = new java.util.LinkedHashMap<>();
                world.main.forEach((name, descriptor) -> before.put(name, DescriptorFacts.definition(descriptor)));
                JsonNode rpc = JSON.readTree(new McpServer(world.journalFixture.guard, catalog, registry[0]).handle(envelope(arguments).toString()));
                assertFalse(rpc.toString(), rpc.has("error"));
                JsonNode result = rpc.path("result");
                String text = result.path("content").get(0).path("text").asText();
                String context = arguments.length() > 400 ? arguments.substring(0, 400) + "..." : arguments;
                assertTrue(context + " -> " + result, result.path("isError").asBoolean());
                for (String token : tokens) assertTrue(context + " -> " + text + " lacks " + token, text.contains(token));
                assertFalse(context + " -> " + text, text.contains("planId"));
                assertTrue(context, world.nativeCalls.isEmpty());
                assertEquals(context, 0, world.journalFixture.entityManager.find(ru.rasulov.common.impl.undo.JournalOperation.class).length);
                java.util.Map<String, String> after = new java.util.LinkedHashMap<>();
                world.main.forEach((name, descriptor) -> after.put(name, DescriptorFacts.definition(descriptor)));
                assertEquals(context, before, after);
            } finally {
                runner.destroy();
            }
        }
    }

    private static void refused(ObjectNode request, String... tokens) throws Exception { refused(world -> request, tokens); }

    private static void refusedExisting(String rules, String... tokens) throws Exception {
        refused(world -> {
            WorkflowConfigurationNativeTest.existingGraph(world, "Existing", false);
            return existingRequest("Existing", "ACTIVE", world.revision("Existing"), rules);
        }, tokens);
    }

    private static final String ADD = "{\"op\":\"ADD_TRANSITION\",\"transitionKey\":\"back\",\"name\":\"Back\","
            + "\"source\":{\"stepId\":2},\"targetStepId\":1}";

    private static void refusedChanges(String changes, String rules, String... tokens) throws Exception {
        refused(world -> {
            WorkflowConfigurationNativeTest.existingGraph(world, "Existing", false);
            world.screen(7, "Existing form", List.of("description"));
            ObjectNode request = existingRequest("Existing", "ACTIVE", world.revision("Existing"), rules);
            ((ObjectNode) request.get("existing")).set("changes", JSON.readTree(changes));
            return request;
        }, tokens);
    }

    @Test public void existingNewStatusShapeRefusalsHaveNoPlanJournalOrNativeWrites() throws Exception {
        for(String value:List.of("null","[]","1","\"Review\""))
            refusedChanges("[{\"op\":\"ADD_STATUS\",\"newStatus\":"+value+"}]",null,"newStatus");
        refusedChanges("[{\"op\":\"ADD_STATUS\",\"statusId\":\"3\",\"newStatus\":{\"name\":\"Review\",\"category\":\"IN_PROGRESS\"}}]",null,"exactly one");
        refusedChanges("[{\"op\":\"UPDATE_METADATA\",\"description\":\"x\",\"newStatus\":{\"name\":\"Review\",\"category\":\"IN_PROGRESS\"}}]",null,"newStatus");
        refusedChanges("[{\"op\":\"ADD_STATUS\",\"newStatus\":{\"name\":\"Review\"}}]",null,"category");
        refusedChanges("[{\"op\":\"ADD_STATUS\",\"newStatus\":{\"name\":\"Review\",\"category\":\"invalid\"}}]",null,"category");
        refusedChanges("[{\"op\":\"ADD_STATUS\",\"newStatus\":{\"name\":\"Review\",\"category\":\"IN_PROGRESS\",\"extra\":true}}]",null,"extra");
        refusedChanges("[{\"op\":\"ADD_STATUS\",\"newStatus\":{\"name\":\"Review\",\"category\":\"IN_PROGRESS\",\"description\":null}}]",null,"description");
    }
    @Test public void nullEmptyNullItemAndOversizedChangesAreRefusedWithTheirPaths() throws Exception {
        refusedChanges("null", null, "existing.changes");
        refusedChanges("[]", null, "existing.changes");
        refusedChanges("[{\"op\":\"UPDATE_METADATA\",\"description\":\"valid\"},null]", null, "existing.changes[1]");
        refusedChanges(repeat("{\"op\":\"UPDATE_METADATA\",\"description\":\"valid\"}", 101), null, "existing.changes", "100");
        refusedChanges("{}", null, "existing.changes");
    }

    @Test public void structuralUnknownPropertiesAreRefusedAtEveryNestedBoundary() throws Exception {
        refusedChanges("[{\"op\":\"UPDATE_METADATA\",\"description\":\"valid\",\"unexpected\":true}]", null, "existing.changes[0].unexpected");
        refusedChanges("[" + ADD.replace("\"stepId\":2", "\"stepId\":2,\"unexpected\":true") + "]", null, "existing.changes[0].source.unexpected");
        refusedChanges("[" + ADD.replace("\"targetStepId\":1", "\"targetStepId\":1,\"screen\":{\"action\":\"SET\",\"screenId\":\"7\",\"unexpected\":true}") + "]",
                null, "existing.changes[0].screen.unexpected");
        refusedChanges("[" + ADD.replace("\"targetStepId\":1", "\"targetStepId\":1,\"resolution\":{\"action\":\"CLEAR\",\"unexpected\":true}") + "]",
                null, "existing.changes[0].resolution.unexpected");
        refused(world -> {
            ObjectNode request = existingRequest("Existing", "ACTIVE", "revision", null);
            ((ObjectNode) request.get("existing")).put("unexpected", true);
            return request;
        }, "existing.unexpected");
        ObjectNode create = createRequest();
        ((ObjectNode) create.get("create")).set("changes", JSON.readTree("[{\"op\":\"UPDATE_METADATA\",\"description\":\"not allowed\"}]"));
        refused(create, "create.changes");
        ObjectNode root = createRequest(); root.set("changes", JSON.readTree("[" + ADD + "]"));
        refused(root, "changes");
    }

    @Test public void malformedTransitionKeysAndKeysOnOtherOperationsAreRefused() throws Exception {
        for (String key : new String[] {"", " ", " back", "back ", "\tback", "back\n", "k".repeat(256)}) {
            ObjectNode change = (ObjectNode) JSON.readTree(ADD); change.put("transitionKey", key);
            refusedChanges("[" + change + "]", null, "changes[0]", "transitionKey");
        }
        for (String value : new String[] {"null", "1", "true", "{}", "[]"}) {
            refusedChanges("[" + ADD.replace("\"transitionKey\":\"back\"", "\"transitionKey\":" + value) + "]",
                    null, "existing.changes[0].transitionKey");
        }
        refusedChanges("[{\"op\":\"UPDATE_METADATA\",\"description\":\"changed\",\"transitionKey\":\"not-an-add\"}]",
                null, "changes[0]", "transitionKey");
        // Duplicates are invalid even when transitionRules never references them.
        refusedChanges("[" + ADD + "," + ADD.replace("\"Back\"", "\"Another back\"") + "]", null, "changes[1]", "transitionKey");
    }

    @Test public void unknownLocalReferencesAndNumericNewOrRemovedActionsAreRefused() throws Exception {
        refusedChanges("[" + ADD + "]", "[{\"transitionKey\":\"Back\",\"addValidators\":[" + VALIDATOR + "]}]", "UNKNOWN_TRANSITION", "transitionKey");
        refusedChanges("[" + ADD + "]", "[{\"actionId\":12,\"addValidators\":[" + VALIDATOR + "]}]", "UNKNOWN_TRANSITION", "actionId");
        refusedChanges("[{\"op\":\"REMOVE_TRANSITION\",\"actionId\":11}," + ADD.replace("\"stepId\":2", "\"stepId\":1") + "]", "[{\"actionId\":11,\"addValidators\":[" + VALIDATOR + "]}]",
                "UNKNOWN_TRANSITION", "actionId");
        // Step keys do not become transition keys, even if an unkeyed transition uses that step.
        refusedChanges("[{\"op\":\"ADD_STATUS\",\"statusId\":\"3\",\"clientKey\":\"step-only\"},"
                        + "{\"op\":\"ADD_TRANSITION\",\"name\":\"From new step\",\"source\":{\"clientKey\":\"step-only\"},\"targetStepId\":1}]",
                "[{\"transitionKey\":\"step-only\",\"addValidators\":[" + VALIDATOR + "]}]", "UNKNOWN_TRANSITION", "transitionKey");
    }

    @Test public void duplicateExistingAndLocalRuleTargetsAreRefused() throws Exception {
        refusedExisting("[{\"actionId\":11,\"addValidators\":[" + VALIDATOR + "]},{\"actionId\":11,\"addValidators\":[" + VALIDATOR + "]}]",
                "one transitionRules", "transition");
        refusedChanges("[" + ADD + "]", "[{\"transitionKey\":\"back\",\"addValidators\":[" + VALIDATOR + "]},"
                + "{\"transitionKey\":\"back\",\"addConditions\":{\"operator\":\"AND\",\"rules\":[{\"template\":\"group\",\"parameters\":{\"group\":\"admins\"}}]}}]",
                "one transitionRules", "transition");
        refusedChanges("[" + ADD + "]", "[{\"actionId\":11,\"transitionKey\":\"back\",\"addValidators\":[" + VALIDATOR + "]}]",
                "actionId", "transitionKey");
        refusedChanges("[" + ADD + "]", "[{\"addValidators\":[" + VALIDATOR + "]}]", "actionId", "transitionKey");
    }

    @Test public void structuralAndRuleScreenChoicesConflictEvenWhenEqual() throws Exception {
        for (String screen : new String[] {"{\"action\":\"SET\",\"screenId\":\"7\"}", "{\"action\":\"CLEAR\"}"}) {
            refusedChanges("[{\"op\":\"UPDATE_TRANSITION\",\"actionId\":11,\"screen\":" + screen + "}]",
                    "[{\"actionId\":11,\"screen\":" + screen + "}]", "existing.changes", "transitionRules", "screen");
            refusedChanges("[" + ADD.replace("\"targetStepId\":1", "\"targetStepId\":1,\"screen\":" + screen) + "]",
                    "[{\"transitionKey\":\"back\",\"screen\":" + screen + "}]", "existing.changes", "transitionRules", "screen");
        }
    }

    @Test public void existingLocalScreenReferencesMustBeDeclaredAndUsed() throws Exception {
        refusedChanges("[" + ADD + "]", "[{\"transitionKey\":\"back\",\"screen\":{\"action\":\"SET\",\"screenKey\":\"missing\"}}]",
                "UNKNOWN_SCREEN", "missing");
        refused(world -> {
            WorkflowConfigurationNativeTest.existingGraph(world, "Existing", false);
            ObjectNode request = existingRequest("Existing", "ACTIVE", world.revision("Existing"), null);
            ((ObjectNode) request.get("existing")).set("changes", JSON.readTree("[" + ADD + "]"));
            request.set("newScreens", JSON.readTree("[{\"key\":\"unused\",\"name\":\"Unused form\",\"fieldIds\":[]}]"));
            return request;
        }, "newScreens", "screenKey");
        ObjectNode misspelled = createRequest();
        misspelled.set("newScreens", JSON.readTree("[{\"screenKey\":\"form\",\"name\":\"Wrong key\"}]"));
        refused(misspelled, "newScreens[0].screenKey");
    }

    // (а) create and existing together / neither
    @Test public void createAndExistingTogetherOrNeitherAreRefused() throws Exception {
        ObjectNode both = createRequest(); both.set("existing", existingRequest("X", "ACTIVE", "r", null).get("existing"));
        refused(both, "create", "existing");
        ObjectNode neither = JSON.createObjectNode(); neither.put("dryRun", true);
        refused(neither, "create", "existing");
    }

    // An existing transitionKey must name an ADD_TRANSITION in this same request.
    @Test public void undeclaredTransitionKeyWithExistingIsRefused() throws Exception {
        refusedExisting("[{\"transitionKey\":\"start\",\"addValidators\":[" + VALIDATOR + "]}]", "UNKNOWN_TRANSITION", "transitionKey");
    }

    // (в) actionId with create
    @Test public void actionIdWithCreateIsRefused() throws Exception {
        refused(createRequest("[{\"actionId\":11,\"addValidators\":[" + VALIDATOR + "]}]"), "actionId", "transitionKey");
    }

    // (г) a rule with both keys / with none
    @Test public void aRuleWithBothKeysOrNoKeyIsRefused() throws Exception {
        refused(createRequest("[{\"transitionKey\":\"finish\",\"actionId\":11,\"addValidators\":[" + VALIDATOR + "]}]"), "actionId", "transitionKey");
        refused(createRequest("[{\"addValidators\":[" + VALIDATOR + "]}]"), "transitionKey");
        refusedExisting("[{\"transitionKey\":\"start\",\"actionId\":11,\"addValidators\":[" + VALIDATOR + "]}]", "actionId", "transitionKey");
        refusedExisting("[{\"addValidators\":[" + VALIDATOR + "]}]", "actionId");
    }

    // (д) screen without a reference / with two references / KEEP with a reference
    @Test public void screenWithoutOrWithSeveralReferencesOrKeepWithAReferenceIsRefused() throws Exception {
        refused(createRequest("[{\"transitionKey\":\"finish\",\"screen\":{\"action\":\"SET\"}}]"), "screenId", "screenName", "screenKey");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"screen\":{\"action\":\"SET\",\"screenId\":\"1\",\"screenName\":\"Form\"}}]"), "screenId", "screenName", "screenKey");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"screen\":{\"action\":\"KEEP\",\"screenId\":\"1\"}}]"), "screenId", "screenName", "screenKey");
    }

    // (д, продолжение) a string other than KEEP, a non-object, null
    @Test public void screenAnythingButKeepOrASetObjectIsRefused() throws Exception {
        refused(createRequest("[{\"transitionKey\":\"finish\",\"screen\":\"CLEAR\"}]"), "screen", "KEEP");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"screen\":5}]"), "screen", "KEEP");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"screen\":{\"action\":\"CLEAR\",\"screenId\":\"1\"}}]"), "screenId", "screenName", "screenKey");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"screen\":null}]"), "screen");
    }

    // (е) comment_required with parameters.fieldIds; required_fields without parameters / with empty fieldIds
    @Test public void validatorParametersThatDoNotFitTheTemplateAreRefused() throws Exception {
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addValidators\":[{\"template\":\"comment_required\",\"parameters\":{\"fieldIds\":[\"description\"]}}]}]"), "comment_required");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addValidators\":[{\"template\":\"required_fields\"}]}]"), "required_fields", "fieldIds");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addValidators\":[{\"template\":\"required_fields\",\"parameters\":{\"fieldIds\":[]}}]}]"), "fieldIds");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addValidators\":[{\"template\":\"required_fields\",\"parameters\":{}}]}]"), "fieldIds");
    }

    // (ж) addConditions without operator or rules / operator OR / unknown template
    @Test public void conditionBlocksWithoutOperatorOrRulesOrWithOrOrUnknownTemplateAreRefused() throws Exception {
        String rule = "{\"template\":\"group\",\"parameters\":{\"group\":\"admins\"}}";
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addConditions\":{\"rules\":[" + rule + "]}}]"), "operator");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addConditions\":{\"operator\":\"AND\"}}]"), "rules");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addConditions\":{\"operator\":\"OR\",\"rules\":[" + rule + "]}}]"), "operator");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addConditions\":{\"operator\":\"AND\",\"rules\":[{\"template\":\"user\",\"parameters\":{\"group\":\"admins\"}}]}}]"), "template");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addValidators\":[{\"template\":\"script\",\"parameters\":{\"fieldIds\":[\"description\"]}}]}]"), "template");
    }

    @Test public void groupConditionRequiresItsGroupParameter() throws Exception {
        refused(conditionRequest("group", "{}"), "parameters.group");
    }

    @Test public void projectRoleConditionRequiresItsRolesParameter() throws Exception {
        refused(conditionRequest("project_role", "{}"), "project_role", "roles");
    }

    @Test public void groupConditionRejectsRolesParameter() throws Exception {
        refused(conditionRequest("group", "{\"group\":\"admins\",\"roles\":[10000]}"), "group", "parameters.group");
    }

    @Test public void groupConditionRejectsEvenFalseInvertParameter() throws Exception {
        refused(conditionRequest("group", "{\"group\":\"admins\",\"invert\":false}"), "group", "parameters.group");
    }

    @Test public void projectRoleConditionRejectsGroupParameter() throws Exception {
        refused(conditionRequest("project_role", "{\"roles\":[10000],\"group\":\"admins\"}"), "parameters");
    }

    private static ObjectNode conditionRequest(String template, String parameters) throws Exception {
        return createRequest("[{\"transitionKey\":\"finish\",\"addConditions\":{\"operator\":\"AND\",\"rules\":[{\"template\":\""
                + template + "\",\"parameters\":" + parameters + "}]}}]");
    }

    // (з) limits: 100 rules, 10 validators, 10 conditions, 10 newScreens
    @Test public void limitsOnRulesValidatorsConditionsAndNewScreensAreRefused() throws Exception {
        refused(createRequest(repeat("{\"transitionKey\":\"finish\"}", 101)), "transitionRules", "100");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addValidators\":" + repeat("{\"template\":\"comment_required\"}", 11) + "}]"), "addValidators", "10");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addConditions\":{\"operator\":\"AND\",\"rules\":"
                + repeat("{\"template\":\"group\",\"parameters\":{\"group\":\"admins\"}}", 11) + "}}]"), "addConditions.rules", "10");
        ArrayNode screens = JSON.createArrayNode();
        for (int i = 0; i < 11; i++) screens.addObject().put("key", "s" + i).put("name", "Screen " + i);
        ObjectNode request = createRequest(); request.set("newScreens", screens);
        refused(request, "newScreens", "10");
    }

    // (и) actionId 0 and negative
    @Test public void zeroAndNegativeActionIdsAreRefused() throws Exception {
        refusedExisting("[{\"actionId\":0,\"addValidators\":[" + VALIDATOR + "]}]", "actionId");
        refusedExisting("[{\"actionId\":-1,\"addValidators\":[" + VALIDATOR + "]}]", "actionId");
    }

    // (к) existing.version outside ACTIVE/DRAFT, blank workflow and expectedRevision
    @Test public void existingWithABadVersionOrBlankWorkflowOrRevisionIsRefused() throws Exception {
        for (String version : new String[] {"DONE", "active", "", null})
            refused(existingRequest("Existing", version, "r", null), "existing.version");
        for (String workflow : new String[] {"", "   ", null})
            refused(existingRequest(workflow, "ACTIVE", "r", null), "existing.workflow");
        for (String revision : new String[] {"", "   ", null})
            refused(existingRequest("Existing", "ACTIVE", revision, null), "existing.expectedRevision");
    }

    // (л) blank / whitespace-only / control / XML-forbidden names of the workflow, a status and a transition
    @Test public void blankControlAndXmlForbiddenNamesAreRefused() throws Exception {
        for (String bad : new String[] {"", "   ", " \t\r\n", "a\nb", "a\u0001b", "\u0000Name", "Name\u000b", "A\tB", "A\rB", "A\u007fB", "A\u0085B",
                "\ud800", "\udfff", "\ufffe", "\uffff"}) {
            ObjectNode workflow = createRequest(); ((ObjectNode) workflow.get("create")).put("name", bad);
            refused(workflow, "'name'");
            ObjectNode status = createRequest(); ((ObjectNode) status.get("create").get("statuses").get(1)).put("name", bad);
            refused(status, "statuses[1].name");
            ObjectNode transition = createRequest(); ((ObjectNode) transition.get("create").get("transitions").get(0)).put("name", bad);
            refused(transition, "transitions[0].name");
        }
    }

    // Constraints of the former schema that (а)–(р) do not list: "exactly one of from and fromAny"
    @Test public void aTransitionNeedsExactlyOneOfFromAndFromAny() throws Exception {
        ObjectNode neither = createRequest(); ((ObjectNode) neither.get("create").get("transitions").get(0)).remove("from");
        refused(neither, "transitions[0]", "fromAny");
        ObjectNode localWithoutFrom = createRequest();
        ObjectNode local = (ObjectNode) localWithoutFrom.get("create").get("transitions").get(0);
        local.remove("from"); local.put("fromAny", false);
        refused(localWithoutFrom, "transitions[0]", "fromAny");
        ObjectNode blank = createRequest(); ((ObjectNode) blank.get("create").get("transitions").get(0)).put("from", " \t\r\n");
        refused(blank, "transitions[0]", "fromAny");
        for (String from : new String[] {"Open", "\u00a0", "\u2028"}) {
            ObjectNode both = createRequest(); ObjectNode transition = (ObjectNode) both.get("create").get("transitions").get(0);
            transition.put("from", from); transition.put("fromAny", true);
            refused(both, "transitions[0]", "fromAny");
        }
    }

    // Resolution: SET needs a canonical value without boundary characters, CLEAR takes no value, only SET and CLEAR exist
    @Test public void resolutionShapesTheSchemaNoLongerStatesAreRefused() throws Exception {
        for (String resolution : new String[] {"{\"action\":\"SET\"}", "{\"action\":\"SET\",\"value\":\" 1\"}", "{\"action\":\"SET\",\"value\":\"1\\n\"}",
                "{\"action\":\"CLEAR\",\"value\":\"1\"}", "{\"action\":\"PRESERVE\"}", "{\"action\":\"set\",\"value\":\"1\"}", "{\"value\":\"1\"}"}) {
            ObjectNode request = createRequest();
            ((ObjectNode) request.get("create").get("transitions").get(0)).set("resolution", JSON.readTree(resolution));
            refused(request, "transitions[0].resolution");
        }
    }

    // Description of the workflow: XML 1.0 characters, including broken surrogate sequences
    @Test public void malformedXmlCharactersInADescriptionAreRefused() throws Exception {
        for (String bad : new String[] {"\u0000", "\u000b", "\u001f", "\ud800", "\udfff", "\ufffe", "\uffff", "\ud800\ud800", "\udc00\udc00", "\udc00\ud800",
                "\ud800A\udc00", "\ud800\udc00\ud800", "\udfff\udbff\udfff", "\ud83d\ude00\ufffe\n"}) {
            ObjectNode request = createRequest(); ((ObjectNode) request.get("create")).put("description", bad);
            refused(request, "'description'");
        }
        // The description of a status that Jira does not know yet is checked too.
        ObjectNode status = createRequest(); ObjectNode fresh = (ObjectNode) status.get("create").get("statuses").get(1);
        fresh.put("name", "Brand New"); fresh.put("category", "TO_DO"); fresh.put("description", "\u0000");
        refused(status, "description of status");
    }

    // initialStatus / to must name a declared status: blank references are UNKNOWN_STATUS-like refusals that carry the path
    @Test public void blankStatusReferencesAreRefusedWithThePath() throws Exception {
        for (String blank : new String[] {"", " \t\r\n\u0000"}) {
            ObjectNode initial = createRequest(); ((ObjectNode) initial.get("create")).put("initialStatus", blank);
            refused(initial, "initialStatus");
            ObjectNode target = createRequest(); ((ObjectNode) target.get("create").get("transitions").get(0)).put("to", blank);
            refused(target, "transitions[0].to");
        }
    }

    // (м) type instead of template, in a validator and in a condition rule: the path names type
    @Test public void typeInsteadOfTemplateIsRefusedWithTheRequestPath() throws Exception {
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addValidators\":[{\"type\":\"required_fields\",\"parameters\":{\"fieldIds\":[\"description\"]}}]}]"),
                "transitionRules[0].addValidators[0].type");
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addConditions\":{\"operator\":\"AND\",\"rules\":[{\"type\":\"group\",\"parameters\":{\"group\":\"admins\"}}]}}]"),
                "transitionRules[0].addConditions.rules[0].type");
    }

    // (н) fields instead of parameters.fieldIds in a validator
    @Test public void fieldsInsteadOfParametersFieldIdsIsRefusedWithTheRequestPath() throws Exception {
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addValidators\":[{\"template\":\"required_fields\",\"fields\":[\"description\"]}]}]"),
                "transitionRules[0].addValidators[0].fields");
    }

    // (о) addConditions as an array instead of an object
    @Test public void conditionsGivenAsAnArrayAreRefusedWithTheRequestPath() throws Exception {
        refused(createRequest("[{\"transitionKey\":\"finish\",\"addConditions\":[{\"template\":\"group\",\"parameters\":{\"group\":\"admins\"}}]}]"),
                "transitionRules[0].addConditions");
    }

    // (п) transitions[].from "INITIAL" that is not a declared status (dialog 1)
    @Test public void initialAsATransitionSourceIsRefusedAsAnUnknownStatusWithThePath() throws Exception {
        ObjectNode request = createRequest(); ((ObjectNode) request.get("create").get("transitions").get(0)).put("from", "INITIAL");
        refused(request, "UNKNOWN_STATUS", "transitions[0].from");
    }

    // (р) initialStatus given as a key instead of a status name (dialog 1)
    @Test public void initialStatusGivenAsAKeyIsRefusedAsAnUnknownStatusWithThePath() throws Exception {
        ObjectNode request = createRequest(); ((ObjectNode) request.get("create")).put("initialStatus", "backlog");
        refused(request, "UNKNOWN_STATUS", "initialStatus");
    }
}
