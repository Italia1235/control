package ru.rasulov.workflow.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.WorkflowUtil;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowStatus;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.WorkflowMocks.fromAny;
import static ru.rasulov.workflow.impl.WorkflowMocks.move;
import static ru.rasulov.workflow.impl.WorkflowMocks.request;
import static ru.rasulov.workflow.impl.WorkflowMocks.simple;
import static ru.rasulov.workflow.impl.WorkflowMocks.status;

/** The descriptor is parsed back by Jira's own {@code WorkflowUtil}: what it accepts and shows is what counts. */
public class WorkflowDescriptorBuilderTest {

    private final WorkflowDescriptorBuilder builder = new WorkflowDescriptorBuilder();
    private final StatusCatalog catalog = mock(StatusCatalog.class);
    private final WorkflowRequestValidator validator = new WorkflowRequestValidator(catalog, null, WorkflowMocks.resolutions());
    private final ApplicationUser user = mock(ApplicationUser.class);

    @Before
    public void jiraHasOpenAndDone() {
        WorkflowMocks.jiraHas(catalog, WorkflowMocks.status("1", "Open", "new"), WorkflowMocks.status("2", "Done", "done"));
    }

    @Test
    public void everyStatusIsAStepLinkedToItsJiraStatusInTheOrderOfTheRequest() throws Exception {
        ResolvedWorkflow workflow = validator.validate(simple(), user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        assertEquals(3, descriptor.getSteps().size());
        assertStep(descriptor, 1, "Open", "1");
        assertStep(descriptor, 2, "In Progress", "10100");
        assertStep(descriptor, 3, "Done", "2");
    }

    @Test
    public void theInitialActionLeadsToTheInitialStatusWhereverItIsListed() throws Exception {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Done", null), status("Open", null)), "Open",
                Arrays.asList(move("Finish", "Open", "Done")));
        ResolvedWorkflow workflow = validator.validate(request, user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        assertEquals(1, descriptor.getInitialActions().size());
        ActionDescriptor create = (ActionDescriptor) descriptor.getInitialActions().get(0);
        assertEquals(WorkflowDescriptorBuilder.INITIAL_ACTION_ID, create.getId());
        assertEquals("Open", ((StepDescriptor) descriptor.getSteps().get(create.getUnconditionalResult().getStep() - 1)).getName());
    }

    @Test
    public void anOrdinaryTransitionIsAnActionOfItsSourceStepLeadingToItsTarget() throws Exception {
        ResolvedWorkflow workflow = validator.validate(simple(), user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        StepDescriptor inProgress = descriptor.getStep(2);
        assertEquals(1, inProgress.getActions().size());
        ActionDescriptor finish = (ActionDescriptor) inProgress.getActions().get(0);
        assertEquals("Finish", finish.getName());
        assertEquals(3, finish.getUnconditionalResult().getStep());
        assertEquals(0, descriptor.getGlobalActions().size());
    }

    @Test
    public void aFromAnyTransitionIsAGlobalActionAndNotAnActionOfAnyStep() throws Exception {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Arrays.asList(move("Finish", "Open", "Done"), fromAny("Reopen", "Open")));
        ResolvedWorkflow workflow = validator.validate(request, user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        assertEquals(1, descriptor.getGlobalActions().size());
        ActionDescriptor reopen = (ActionDescriptor) descriptor.getGlobalActions().get(0);
        assertEquals("Reopen", reopen.getName());
        assertEquals(1, reopen.getUnconditionalResult().getStep());
        for (Object step : descriptor.getSteps()) {
            for (Object action : ((StepDescriptor) step).getActions()) {
                assertTrue("Reopen must not be an action of a step", !"Reopen".equals(((ActionDescriptor) action).getName()));
            }
        }
    }

    @Test
    public void threeAllArrowsRemainThreeGlobalActionsIncludingEachTargetsOwnStatus() throws Exception {
        WorkflowMocks.jiraHas(catalog, WorkflowMocks.status("10801", "В работе", "indeterminate"));
        WorkflowDefinition request = request("Mcp-test workflow",
                Arrays.asList(status("Заказ", "TO_DO"), status("В работе", "IN_PROGRESS"),
                        status("Заморожен", "DONE")), "Заказ",
                Arrays.asList(fromAny("Заказ", "Заказ"), fromAny("В работе", "В работе"),
                        fromAny("Заморожен", "Заморожен")));
        ResolvedWorkflow workflow = validator.validate(request, user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        assertEquals("10801", descriptor.getStep(2).getMetaAttributes().get("jira.status.id"));
        assertEquals(3, descriptor.getGlobalActions().size());
        for (int target = 1; target <= 3; target++) {
            ActionDescriptor action = (ActionDescriptor) descriptor.getGlobalActions().get(target - 1);
            assertEquals(descriptor.getStep(target).getName(), action.getName());
            assertEquals(target, action.getUnconditionalResult().getStep());
            assertTrue("All arrows must not be expanded into local edges",
                    descriptor.getStep(target).getActions().isEmpty());
        }
        assertTrue(workflow.getWarnings().isEmpty());
    }

    @Test
    public void explicitSelfLoopReturnsToItsOwnStep() throws Exception {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Arrays.asList(move("Finish", "Open", "Done"), move("Again", "Done", "Done")));
        ResolvedWorkflow workflow = validator.validate(request, user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        ActionDescriptor again = (ActionDescriptor) descriptor.getStep(2).getActions().get(0);
        assertEquals("Again", again.getName());
        assertEquals(2, again.getUnconditionalResult().getStep());
        assertTrue(descriptor.getGlobalActions().isEmpty());
    }

    @Test
    public void actionIdsAreUniqueAcrossStepsGlobalAndInitialActions() throws Exception {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Arrays.asList(move("Finish", "Open", "Done"), fromAny("Reopen", "Open"), move("Back", "Done", "Open"),
                        move("Again", "Open", "Open")));
        ResolvedWorkflow workflow = validator.validate(request, user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        Set<Integer> ids = new HashSet<Integer>();
        int count = 0;
        for (Object action : descriptor.getInitialActions()) {
            ids.add(((ActionDescriptor) action).getId());
            count++;
        }
        for (Object action : descriptor.getGlobalActions()) {
            ids.add(((ActionDescriptor) action).getId());
            count++;
        }
        for (Object step : descriptor.getSteps()) {
            for (Object action : ((StepDescriptor) step).getActions()) {
                ids.add(((ActionDescriptor) action).getId());
                count++;
            }
        }
        assertEquals(5, count);
        assertEquals(count, ids.size());
    }

    @Test
    public void everyTransitionMovesTheIssueAndIsRecordedInItsHistory() throws Exception {
        ResolvedWorkflow workflow = validator.validate(simple(), user);

        String xml = builder.toXml(workflow, idsOf(workflow));

        // Without UpdateIssueStatusFunction the status would not change; without the history and index functions
        // the change would be invisible. 3 transitions.
        assertEquals(3, count(xml, "workflow.function.issue.UpdateIssueStatusFunction"));
        assertEquals(3, count(xml, "workflow.function.issue.GenerateChangeHistoryFunction"));
        assertEquals(3 + 1, count(xml, "workflow.function.issue.IssueReindexFunction"));
    }

    @Test
    public void namesWithMarkupSurviveTheRoundTrip() throws Exception {
        WorkflowDefinition request = new WorkflowDefinition("R&D <flow> \"x\" 'y'", "a < b & c",
                Arrays.asList(status("Q&A <1>", "TO_DO"), status("It's \"done\"", "DONE")), "Q&A <1>",
                Arrays.asList(new ru.rasulov.workflow.api.dto.TransitionDefinition(
                        "Go & see <it>", "Q&A <1>", false, "It's \"done\"", null, WorkflowMocks.fixed())));
        ResolvedWorkflow workflow = validator.validate(request, user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        assertEquals("Q&A <1>", descriptor.getStep(1).getName());
        assertEquals("It's \"done\"", descriptor.getStep(2).getName());
        assertEquals("Go & see <it>", ((ActionDescriptor) descriptor.getStep(1).getActions().get(0)).getName());
        assertEquals("a < b & c", descriptor.getMetaAttributes().get("jira.description"));
    }

    @Test
    public void xmlLegalMultilineTabAndSupplementaryTextSurviveWorkflowUtilRoundTrip() throws Exception {
        String emoji = "\uD83D\uDE00";
        String description = "First line\nSecond\tline " + emoji + " & <text>";
        WorkflowDefinition request = new WorkflowDefinition("Flow " + emoji, description,
                Arrays.asList(status("Open " + emoji, "TO_DO"), status("Done " + emoji, "DONE")),
                "Open " + emoji, Arrays.asList(new ru.rasulov.workflow.api.dto.TransitionDefinition(
                        "Go " + emoji, "Open " + emoji, false, "Done " + emoji, null, WorkflowMocks.fixed())));
        ResolvedWorkflow workflow = validator.validate(request, user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        assertEquals("Flow " + emoji, workflow.getName());
        assertEquals(description, descriptor.getMetaAttributes().get("jira.description"));
        assertEquals("Open " + emoji, descriptor.getStep(1).getName());
        assertEquals("Done " + emoji, descriptor.getStep(2).getName());
        assertEquals("Go " + emoji, ((ActionDescriptor) descriptor.getStep(1).getActions().get(0)).getName());
    }

    @Test
    public void aNewStatusGetsTheIdOfTheStatusCreatedForIt() throws Exception {
        ResolvedWorkflow workflow = validator.validate(simple(), user);
        Map<WorkflowStatus, String> ids = idsOf(workflow);
        ids.put(workflow.getStatuses().get(1), "77777");

        WorkflowDescriptor descriptor = builder.build(workflow, ids);

        assertStep(descriptor, 2, "In Progress", "77777");
    }

    @Test
    public void transitionScreenIdIsStoredOnTheAction() throws Exception {
        FieldScreen screen = mock(FieldScreen.class);
        when(screen.getId()).thenReturn(12345L);
        TransitionScreenCatalog screens = mock(TransitionScreenCatalog.class);
        when(screens.resolve("Release screen", "transitions[0].screen ('Release screen')")).thenReturn(screen);
        WorkflowRequestValidator withScreens = new WorkflowRequestValidator(catalog, screens, WorkflowMocks.resolutions());
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Arrays.asList(new ru.rasulov.workflow.api.dto.TransitionDefinition(
                        "Finish", "Open", false, "Done", "Release screen", WorkflowMocks.fixed())));
        ResolvedWorkflow workflow = withScreens.validate(request, user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        ActionDescriptor action = (ActionDescriptor) descriptor.getStep(1).getActions().get(0);
        assertEquals("12345", action.getMetaAttributes().get("jira.fieldscreen.id"));
    }

    @Test
    public void aTransitionWithAScreenCarriesTheFieldscreenViewAndSurvivesTheRoundTrip() throws Exception {
        FieldScreen screen = mock(FieldScreen.class);
        when(screen.getId()).thenReturn(12345L);
        TransitionScreenCatalog screens = mock(TransitionScreenCatalog.class);
        when(screens.resolve("Release screen", "transitions[0].screen ('Release screen')")).thenReturn(screen);
        WorkflowRequestValidator withScreens = new WorkflowRequestValidator(catalog, screens, WorkflowMocks.resolutions());
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Arrays.asList(new ru.rasulov.workflow.api.dto.TransitionDefinition(
                        "Finish", "Open", false, "Done", "Release screen", WorkflowMocks.fixed())));
        ResolvedWorkflow workflow = withScreens.validate(request, user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        ActionDescriptor action = (ActionDescriptor) descriptor.getStep(1).getActions().get(0);
        assertEquals("fieldscreen", action.getView());
        assertEquals("12345", action.getMetaAttributes().get("jira.fieldscreen.id"));
        WorkflowDescriptor reloaded = WorkflowUtil.convertXMLtoWorkflowDescriptor(
                WorkflowUtil.convertDescriptorToXML(descriptor));
        ActionDescriptor saved = (ActionDescriptor) reloaded.getStep(1).getActions().get(0);
        assertEquals("fieldscreen", saved.getView());
        assertEquals("12345", saved.getMetaAttributes().get("jira.fieldscreen.id"));
    }

    @Test
    public void aTransitionWithoutAScreenHasNoView() throws Exception {
        ResolvedWorkflow workflow = validator.validate(simple(), user);

        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));

        ActionDescriptor finish = (ActionDescriptor) descriptor.getStep(2).getActions().get(0);
        assertTrue("view is '" + finish.getView() + "'", finish.getView() == null || finish.getView().isEmpty());
        assertFalse(finish.getMetaAttributes().containsKey("jira.fieldscreen.id"));
    }

    @Test
    public void nativeResolutionFunctionsRunBeforeHistoryAndClearUsesLiteralNull() throws Exception {
        ResolvedWorkflow workflow = validator.validate(simple(), user);
        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));
        assertResolutionFunction((ActionDescriptor) descriptor.getStep(2).getActions().get(0), "1");
        assertResolutionFunction((ActionDescriptor) descriptor.getStep(3).getActions().get(0), "null");
        ActionDescriptor start = (ActionDescriptor) descriptor.getStep(1).getActions().get(0);
        assertEquals(5, start.getUnconditionalResult().getPostFunctions().size());
        assertEquals(3, ((ActionDescriptor) descriptor.getInitialActions().get(0))
                .getUnconditionalResult().getPostFunctions().size());
    }

    private static void assertResolutionFunction(ActionDescriptor action, String value) {
        List functions = action.getUnconditionalResult().getPostFunctions();
        assertEquals(6, functions.size());
        Map args = ((com.opensymphony.workflow.loader.FunctionDescriptor) functions.get(2)).getArgs();
        assertEquals("com.atlassian.jira.workflow.function.issue.UpdateIssueFieldFunction", args.get("class.name"));
        assertEquals("resolution", args.get("field.name"));
        assertEquals(value, args.get("field.value"));
        assertEquals("com.atlassian.jira.workflow.function.issue.GenerateChangeHistoryFunction",
                ((com.opensymphony.workflow.loader.FunctionDescriptor) functions.get(3)).getArgs().get("class.name"));
    }

    /** Ids as after creation: the existing statuses keep theirs, the new one gets 10100. */
    private static Map<WorkflowStatus, String> idsOf(ResolvedWorkflow workflow) {
        Map<WorkflowStatus, String> ids = new HashMap<WorkflowStatus, String>();
        for (WorkflowStatus status : workflow.getStatuses()) {
            Status existing = status.getExisting();
            ids.put(status, existing == null ? "10100" : existing.getId());
        }
        return ids;
    }

    private static void assertStep(WorkflowDescriptor descriptor, int id, String name, String statusId) {
        StepDescriptor step = descriptor.getStep(id);
        assertNotNull("no step " + id, step);
        assertEquals(name, step.getName());
        assertEquals(statusId, step.getMetaAttributes().get("jira.status.id"));
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
