package ru.rasulov.workflow.impl;

import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.user.ApplicationUser;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.FunctionDescriptor;
import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.TransitionResolution;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowStatus;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowTransition;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.WorkflowMocks.status;

/** Request-policy regressions and native descriptor evidence; no running Jira is required. */
public class WorkflowParallelTransitionsTest {
    private final StatusCatalog catalog = mock(StatusCatalog.class);
    private final ConstantsManager constants = WorkflowMocks.resolutions();
    private final ApplicationUser user = mock(ApplicationUser.class);
    private final WorkflowRequestValidator validator = new WorkflowRequestValidator(catalog, null, constants);
    private final WorkflowDescriptorBuilder builder = new WorkflowDescriptorBuilder();

    @Before
    public void jiraHasQaStatusesAndTwoResolutions() {
        WorkflowMocks.jiraHas(catalog, status("10", "QA Open", "new"),
                status("20", "QA Working", "indeterminate"), status("30", "QA Done", "done"));
        Resolution duplicate = mock(Resolution.class);
        when(duplicate.getId()).thenReturn("2");
        when(duplicate.getName()).thenReturn("Duplicate");
        when(constants.getResolution("2")).thenReturn(duplicate);
    }

    @Test
    public void differentlyNamedCompletionsRetainTheirDistinctResolutionsInPreview() {
        ResolvedWorkflow preview = validator.validate(request(
                transition("Finish", "QA Working", "QA Done", set("1")),
                transition("Reject", "QA Working", "QA Done", set("2"))), user);

        assertEquals("Finish", preview.getTransitions().get(0).getName());
        assertEquals("Fixed", preview.getTransitions().get(0).getResolution().getName());
        assertEquals("1", preview.getTransitions().get(0).getResolution().getValue());
        assertEquals("Reject", preview.getTransitions().get(1).getName());
        assertEquals("Duplicate", preview.getTransitions().get(1).getResolution().getName());
        assertEquals("2", preview.getTransitions().get(1).getResolution().getValue());
    }

    @Test
    public void differentlyNamedDoneLoopsPreserveOrChangeOutcomeInPreview() {
        // Use the DTO directly: WorkflowMocks.request injects SET for every omitted DONE resolution.
        ResolvedWorkflow preview = validator.validate(request(
                transition("Keep outcome", "QA Done", "QA Done", null),
                transition("Change outcome", "QA Done", "QA Done", set("2"))), user);

        assertEquals("Keep outcome", preview.getTransitions().get(0).getName());
        assertEquals("PRESERVE", preview.getTransitions().get(0).getResolution().getAction());
        assertEquals("Change outcome", preview.getTransitions().get(1).getName());
        assertEquals("SET", preview.getTransitions().get(1).getResolution().getAction());
        assertEquals("2", preview.getTransitions().get(1).getResolution().getValue());
    }

    @Test
    public void parallelCompletionsSurviveRealWorkflowUtilRoundTrip() throws Exception {
        ResolvedWorkflow workflow = descriptorGraph(false);
        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));
        List actions = descriptor.getStep(2).getActions();

        assertEquals(2, actions.size());
        assertAction((ActionDescriptor) actions.get(0), "Finish", 3, "1");
        assertAction((ActionDescriptor) actions.get(1), "Reject", 3, "2");
        assertUniqueActionIds(descriptor, 3);
    }

    @Test
    public void parallelDoneLoopsSurviveRealWorkflowUtilRoundTrip() throws Exception {
        ResolvedWorkflow workflow = descriptorGraph(true);
        WorkflowDescriptor descriptor = builder.build(workflow, idsOf(workflow));
        List actions = descriptor.getStep(3).getActions();

        assertEquals(2, actions.size());
        assertAction((ActionDescriptor) actions.get(0), "Keep outcome", 3, null);
        assertAction((ActionDescriptor) actions.get(1), "Change outcome", 3, "2");
        assertUniqueActionIds(descriptor, 3);
    }

    @Test
    public void globalTargetsCoexistWithExplicitCompletionLoopAndReopen() throws Exception {
        ResolvedWorkflow preview = validator.validate(request(
                transition("Global finish", null, "QA Done", set("1")),
                transition("Finish", "QA Working", "QA Done", set("2")),
                transition("Keep outcome", "QA Done", "QA Done", null),
                transition("Global reopen", null, "QA Open", null),
                transition("Reopen", "QA Done", "QA Open", null)), user);
        WorkflowDescriptor descriptor = builder.build(preview, idsOf(preview));

        assertEquals(2, descriptor.getGlobalActions().size());
        assertAction((ActionDescriptor) descriptor.getGlobalActions().get(0), "Global finish", 3, "1");
        assertAction((ActionDescriptor) descriptor.getGlobalActions().get(1), "Global reopen", 1, "null");
        assertEquals(1, descriptor.getStep(2).getActions().size());
        assertAction((ActionDescriptor) descriptor.getStep(2).getActions().get(0), "Finish", 3, "2");
        assertEquals(2, descriptor.getStep(3).getActions().size());
        assertAction((ActionDescriptor) descriptor.getStep(3).getActions().get(0), "Keep outcome", 3, null);
        assertAction((ActionDescriptor) descriptor.getStep(3).getActions().get(1), "Reopen", 1, "null");
        assertUniqueActionIds(descriptor, 6);
    }

    @Test
    public void sameResolutionAndScreenAreRedundant() {
        String warnings = overlapWarnings(set("1"), "Shared", "Alias", false);
        assertTrue(warnings, warnings.contains("Redundant:"));
        assertTrue(warnings, !warnings.contains("Overlap:"));
    }

    @Test
    public void differentResolutionIdsAreOverlapNotRedundant() {
        String warnings = overlapWarnings(set("2"), null, null, false);
        assertTrue(warnings, warnings.contains("Overlap:"));
        assertTrue(warnings, !warnings.contains("Redundant:"));
    }

    @Test
    public void preserveLoopAndGlobalSetAreOverlapNotRedundant() {
        String warnings = overlapWarnings(null, null, null, true);
        assertTrue(warnings, warnings.contains("Overlap:"));
        assertTrue(warnings, !warnings.contains("Redundant:"));
    }

    @Test
    public void differentScreensAreOverlapNotRedundant() {
        String warnings = overlapWarnings(set("1"), "Shared", "Other", false);
        assertTrue(warnings, warnings.contains("Overlap:"));
        assertTrue(warnings, !warnings.contains("Redundant:"));
    }

    private String overlapWarnings(TransitionResolution localResolution, String localScreen,
                                   String globalScreen, boolean loop) {
        TransitionScreenCatalog screens = mock(TransitionScreenCatalog.class);
        when(screens.resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    com.atlassian.jira.issue.fields.screen.FieldScreen screen =
                            mock(com.atlassian.jira.issue.fields.screen.FieldScreen.class);
                    when(screen.getId()).thenReturn("Other".equals(invocation.getArgument(0)) ? 200L : 100L);
                    return screen;
                });
        WorkflowDefinition input = request(
                new TransitionDefinition("Local", loop ? "QA Done" : "QA Working", false,
                        "QA Done", localScreen, localResolution),
                new TransitionDefinition("Global", null, true, "QA Done", globalScreen, set("1")));
        ResolvedWorkflow preview = new WorkflowRequestValidator(catalog, screens, constants).validate(input, user);
        assertEquals(2, preview.getTransitions().size());
        return String.join(" ", preview.getWarnings());
    }

    private static TransitionResolution set(String id) {
        return new TransitionResolution("SET", id);
    }

    private static TransitionDefinition transition(String name, String from, String to, TransitionResolution resolution) {
        return new TransitionDefinition(name, from, from == null, to, null, resolution);
    }

    private static WorkflowDefinition request(TransitionDefinition... transitions) {
        return new WorkflowDefinition("Manual QA — loops and resolutions", null,
                Arrays.asList(status("QA Open", "TO_DO"), status("QA Working", "IN_PROGRESS"),
                        status("QA Done", "DONE")), "QA Open", Arrays.asList(transitions));
    }

    /** Isolate builder/parser capability from the request-level pair restriction. */
    private static ResolvedWorkflow descriptorGraph(boolean loops) {
        WorkflowStatus open = new WorkflowStatus("QA Open", null, "TO_DO", null);
        WorkflowStatus working = new WorkflowStatus("QA Working", null, "IN_PROGRESS", null);
        WorkflowStatus done = new WorkflowStatus("QA Done", null, "DONE", null);
        List<WorkflowTransition> transitions = loops
                ? Arrays.asList(new WorkflowTransition("Keep outcome", done, done, null,
                                new TransitionResolution("PRESERVE", null)),
                        new WorkflowTransition("Change outcome", done, done, null, set("2")))
                : Arrays.asList(new WorkflowTransition("Finish", working, done, null, set("1")),
                        new WorkflowTransition("Reject", working, done, null, set("2")));
        return new ResolvedWorkflow("Manual QA", "", Arrays.asList(open, working, done), open,
                transitions, Collections.<String>emptyList());
    }

    private static Map<WorkflowStatus, String> idsOf(ResolvedWorkflow workflow) {
        Map<WorkflowStatus, String> ids = new HashMap<WorkflowStatus, String>();
        for (int i = 0; i < workflow.getStatuses().size(); i++) {
            ids.put(workflow.getStatuses().get(i), String.valueOf((i + 1) * 10));
        }
        return ids;
    }

    private static void assertAction(ActionDescriptor action, String name, int target, String resolutionValue) {
        assertEquals(name, action.getName());
        assertEquals(target, action.getUnconditionalResult().getStep());
        int resolutionFunctions = 0;
        for (Object item : action.getUnconditionalResult().getPostFunctions()) {
            FunctionDescriptor function = (FunctionDescriptor) item;
            if ("resolution".equals(function.getArgs().get("field.name"))) {
                resolutionFunctions++;
                assertEquals("com.atlassian.jira.workflow.function.issue.UpdateIssueFieldFunction",
                        function.getArgs().get("class.name"));
                assertEquals(resolutionValue, function.getArgs().get("field.value"));
            }
        }
        assertEquals(resolutionValue == null ? 0 : 1, resolutionFunctions);
    }

    private static void assertUniqueActionIds(WorkflowDescriptor descriptor, int expected) {
        Set<Integer> ids = new HashSet<Integer>();
        for (Object item : descriptor.getInitialActions()) {
            assertTrue(ids.add(((ActionDescriptor) item).getId()));
        }
        for (Object item : descriptor.getGlobalActions()) {
            assertTrue(ids.add(((ActionDescriptor) item).getId()));
        }
        for (Object item : descriptor.getSteps()) {
            for (Object action : ((StepDescriptor) item).getActions()) {
                assertTrue(ids.add(((ActionDescriptor) action).getId()));
            }
        }
        assertEquals(expected, ids.size());
    }
}
