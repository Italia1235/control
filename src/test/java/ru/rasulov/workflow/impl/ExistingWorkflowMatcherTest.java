package ru.rasulov.workflow.impl;

import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.TransitionResolution;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.WorkflowMocks.fromAny;
import static ru.rasulov.workflow.impl.WorkflowMocks.move;
import static ru.rasulov.workflow.impl.WorkflowMocks.request;
import static ru.rasulov.workflow.impl.WorkflowMocks.simple;
import static ru.rasulov.workflow.impl.WorkflowMocks.status;

/**
 * The "stored" workflow is what the builder writes, read back through Jira's parser, so the matcher is tested against
 * the same shape it meets in Jira.
 */
public class ExistingWorkflowMatcherTest {

    private final ExistingWorkflowMatcher matcher = new ExistingWorkflowMatcher();
    private final WorkflowDescriptorBuilder builder = new WorkflowDescriptorBuilder();
    private final StatusCatalog catalog = mock(StatusCatalog.class);
    private final TransitionScreenCatalog screens = mock(TransitionScreenCatalog.class);
    private final ConstantsManager resolutions = WorkflowMocks.resolutions();
    private final WorkflowRequestValidator validator = new WorkflowRequestValidator(catalog, screens, resolutions);
    private final ApplicationUser user = mock(ApplicationUser.class);

    @Before
    public void jiraHasOpenInProgressAndDone() {
        WorkflowMocks.jiraHas(catalog, WorkflowMocks.status("1", "Open", "new"),
                WorkflowMocks.status("3", "In Progress", "indeterminate"), WorkflowMocks.status("2", "Done", "done"));
    }

    @Test
    public void theSameRequestMatchesWhatItCreated() throws Exception {
        ResolvedWorkflow wanted = validator.validate(simple(), user);

        assertEquals(0, matcher.differences(wanted, stored(wanted)).size());
    }

    @Test
    public void namesAreComparedWithoutCase() throws Exception {
        ResolvedWorkflow stored = validator.validate(simple(), user);
        WorkflowDefinition upper = request("Bug flow",
                Arrays.asList(status("OPEN", null), status("in progress", null), status("Done", null)), "open",
                Arrays.asList(move("START", "Open", "In Progress"), move("finish", "In Progress", "DONE"),
                        move("Reopen", "Done", "Open")));

        assertEquals(0, matcher.differences(validator.validate(upper, user), stored(stored)).size());
    }

    @Test
    public void aRenamedTransitionIsADifferenceOnBothSides() throws Exception {
        ResolvedWorkflow stored = validator.validate(simple(), user);
        WorkflowDefinition renamed = request("Bug flow", simple().getStatuses(), "Open",
                Arrays.asList(move("Begin", "Open", "In Progress"), move("Finish", "In Progress", "Done"),
                        move("Reopen", "Done", "Open")));

        List<String> differences = matcher.differences(validator.validate(renamed, user), stored(stored));

        assertEquals(2, differences.size());
        assertTrue(differences.toString(), differences.get(0).contains("only in the requested workflow: 'Begin'"));
        assertTrue(differences.toString(), differences.get(1).contains("only in the existing workflow: 'Start'"));
    }

    @Test
    public void aDifferentTargetIsADifference() throws Exception {
        ResolvedWorkflow stored = validator.validate(simple(), user);
        WorkflowDefinition changed = request("Bug flow", simple().getStatuses(), "Open",
                Arrays.asList(move("Start", "Open", "In Progress"), move("Finish", "In Progress", "Done"),
                        move("Reopen", "Done", "In Progress")));

        List<String> differences = matcher.differences(validator.validate(changed, user), stored(stored));

        assertEquals(2, differences.size());
        assertTrue(differences.toString(), differences.get(0).contains("'Reopen' ('Done' to 'In Progress')"));
    }

    @Test
    public void aDifferentInitialStatusIsADifference() throws Exception {
        ResolvedWorkflow stored = validator.validate(simple(), user);
        WorkflowDefinition changed = request("Bug flow", simple().getStatuses(), "In Progress",
                simple().getTransitions());

        List<String> differences = matcher.differences(validator.validate(changed, user), stored(stored));

        assertEquals(1, differences.size());
        assertTrue(differences.get(0), differences.get(0).contains("initial status is 'Open' in Jira and 'In Progress' in the request"));
    }

    @Test
    public void anExtraStatusOnEitherSideIsADifference() throws Exception {
        ResolvedWorkflow stored = validator.validate(simple(), user);
        List<StatusDefinition> fewer = Arrays.asList(status("Open", null), status("Done", null));
        WorkflowDefinition changed = request("Bug flow", fewer, "Open",
                Arrays.asList(move("Finish", "Open", "Done"), move("Reopen", "Done", "Open")));

        List<String> differences = matcher.differences(validator.validate(changed, user), stored(stored));

        String all = String.join(" | ", differences);
        assertTrue(all, all.contains("statuses only in the existing workflow: In Progress"));
        assertTrue(all, all.contains("transitions only in the existing workflow: 'Start'"));
    }

    @Test
    public void aFromAnyTransitionIsNotTheSameAsOneFromASingleStatus() throws Exception {
        WorkflowDefinition global = request("W", simple().getStatuses(), "Open",
                Arrays.asList(move("Start", "Open", "In Progress"), move("Finish", "In Progress", "Done"),
                        fromAny("Reopen", "Open")));
        ResolvedWorkflow stored = validator.validate(simple(), user);

        List<String> differences = matcher.differences(validator.validate(global, user), stored(stored));

        String all = String.join(" | ", differences);
        assertTrue(all, all.contains("only in the requested workflow: 'Reopen' (from any status to 'Open')"));
        assertTrue(all, all.contains("only in the existing workflow: 'Reopen' ('Done' to 'Open')"));
    }

    @Test
    public void aStoredFromAnyTransitionMatchesTheSameRequest() throws Exception {
        WorkflowDefinition global = request("W", simple().getStatuses(), "Open",
                Arrays.asList(move("Start", "Open", "In Progress"), move("Finish", "In Progress", "Done"),
                        fromAny("Reopen", "Open")));
        ResolvedWorkflow wanted = validator.validate(global, user);

        assertEquals(0, matcher.differences(wanted, stored(wanted)).size());
    }

    @Test
    public void aTransitionScreenIsPartOfWorkflowIdentity() throws Exception {
        FieldScreen screen = mock(FieldScreen.class);
        when(screen.getId()).thenReturn(12000L);
        when(screens.resolve("Release", "transitions[1].screen ('Release')")).thenReturn(screen);
        WorkflowDefinition withScreen = request("W", simple().getStatuses(), "Open",
                Arrays.asList(move("Start", "Open", "In Progress"),
                        new ru.rasulov.workflow.api.dto.TransitionDefinition(
                                "Finish", "In Progress", false, "Done", "Release", WorkflowMocks.fixed()),
                        move("Reopen", "Done", "Open")));
        ResolvedWorkflow stored = validator.validate(withScreen, user);

        assertEquals(0, matcher.differences(stored, stored(stored)).size());
        List<String> differences = matcher.differences(validator.validate(simple(), user), stored(stored));
        assertEquals(2, differences.size());
        assertTrue(differences.toString(), differences.toString().contains("screen id 12000"));
    }

    @Test
    public void transitionViewMustMatchForLocalAndGlobalActions() throws Exception {
        FieldScreen screen = mock(FieldScreen.class);
        when(screen.getId()).thenReturn(12000L);
        when(screens.resolve("Release", "transitions[0].screen ('Release')")).thenReturn(screen);
        for (boolean global : Arrays.asList(false, true)) {
            for (boolean withScreen : Arrays.asList(false, true)) {
                WorkflowDefinition graph = request("W", simple().getStatuses(), "Open",
                        Arrays.asList(new TransitionDefinition("Finish", global ? null : "Open", global,
                                "Done", withScreen ? "Release" : null, WorkflowMocks.fixed())));
                ResolvedWorkflow wanted = validator.validate(graph, user);
                JiraWorkflow actual = stored(wanted);
                ActionDescriptor action = (ActionDescriptor) (global
                        ? actual.getDescriptor().getGlobalActions().get(0)
                        : actual.getDescriptor().getStep(1).getActions().get(0));
                assertTrue(matcher.differences(wanted, actual).isEmpty());
                for (String view : Arrays.asList(null, "", "other", "fieldscreen")) {
                    action.setView(view);
                    boolean matches = withScreen ? "fieldscreen".equals(view) : view == null || view.isEmpty();
                    assertEquals("global=" + global + ", screen=" + withScreen + ", view=" + view,
                            matches, matcher.differences(wanted, actual).isEmpty());
                }
            }
        }
    }

    @Test
    public void manyDifferencesAreCutAndCounted() throws Exception {
        ResolvedWorkflow stored = validator.validate(simple(), user);
        List<StatusDefinition> statuses = new ArrayList<StatusDefinition>(simple().getStatuses());
        for (int i = 0; i < 8; i++) {
            statuses.add(status("New " + i, "TO_DO"));
        }
        WorkflowDefinition changed = request("Bug flow", statuses, "Open", simple().getTransitions());

        List<String> differences = matcher.differences(validator.validate(changed, user), stored(stored));

        assertEquals(1, differences.size());
        assertTrue(differences.get(0), differences.get(0).contains("and 3 more"));
    }

    @Test
    public void graphKeysDoNotHidePipeDelimitedFieldDifferences() throws Exception {
        List<StatusDefinition> statuses = Arrays.asList(status("z", "TO_DO"),
                status("y|z", "TO_DO"), status("Done", null));
        ResolvedWorkflow actual = validator.validate(request("W", statuses, "z",
                Arrays.asList(move("x|y", "z", "Done"))), user);
        ResolvedWorkflow wanted = validator.validate(request("W", statuses, "z",
                Arrays.asList(move("x", "y|z", "Done"))), user);

        List<String> differences = matcher.differences(wanted, stored(actual));
        assertEquals(2, differences.size());
        assertTrue(differences.toString(), differences.get(0).contains("'x' ('y|z' to 'Done')"));
        assertTrue(differences.toString(), differences.get(1).contains("'x|y' ('z' to 'Done')"));
        assertTrue(matcher.differences(actual, stored(actual)).isEmpty());
    }

    @Test
    public void graphKeysDoNotConfuseLiteralStarWithGlobalSource() throws Exception {
        List<StatusDefinition> statuses = Arrays.asList(status("*", "TO_DO"), status("Done", null));
        ResolvedWorkflow local = validator.validate(request("W", statuses, "*",
                Arrays.asList(move("Go", "*", "Done"))), user);
        ResolvedWorkflow global = validator.validate(request("W", statuses, "*",
                Arrays.asList(fromAny("Go", "Done"))), user);

        List<String> differences = matcher.differences(global, stored(local));
        assertEquals(2, differences.size());
        assertTrue(differences.toString(), differences.get(0).contains("from any status"));
        assertTrue(differences.toString(), differences.get(1).contains("'*' to 'Done'"));
        assertTrue(matcher.differences(local, stored(local)).isEmpty());
        assertTrue(matcher.differences(global, stored(global)).isEmpty());
    }

    @Test
    public void differentResolutionIdsAndActionsNeverMatchTheSameGraph() throws Exception {
        ResolvedWorkflow wanted = validator.validate(simple(), user);
        JiraWorkflow actual = stored(wanted);
        ActionDescriptor finish = (ActionDescriptor) actual.getDescriptor().getStep(2).getActions().get(0);
        com.opensymphony.workflow.loader.FunctionDescriptor resolution =
                (com.opensymphony.workflow.loader.FunctionDescriptor) finish.getUnconditionalResult().getPostFunctions().get(2);
        resolution.getArgs().put("field.value", "2");
        assertTrue(matcher.differences(wanted, actual).toString().contains("SET:2"));
        resolution.getArgs().put("field.value", "null");
        assertTrue(matcher.differences(wanted, actual).toString().contains("CLEAR"));
        finish.getUnconditionalResult().getPostFunctions().remove(2);
        assertTrue(matcher.differences(wanted, actual).toString().contains("PRESERVE"));
    }

    @Test
    public void conditionsValidatorsAndConditionalResultsOnExistingActionsNeverMatch() throws Exception {
        ResolvedWorkflow wanted = validator.validate(simple(), user);
        assertTrue(matcher.differences(wanted, stored(wanted)).isEmpty());

        JiraWorkflow withValidator = stored(wanted);
        ((ActionDescriptor) withValidator.getDescriptor().getStep(2).getActions().get(0)).getValidators()
                .add(((ActionDescriptor) withValidator.getDescriptor().getInitialActions().get(0)).getValidators().get(0));
        assertUnconfirmed(wanted, withValidator, "'Finish' in 'In Progress'");

        JiraWorkflow withCondition = stored(wanted);
        ((ActionDescriptor) withCondition.getDescriptor().getStep(2).getActions().get(0))
                .setRestriction(mock(com.opensymphony.workflow.loader.RestrictionDescriptor.class));
        assertUnconfirmed(wanted, withCondition, "'Finish' in 'In Progress'");

        JiraWorkflow withConditionalResult = stored(wanted);
        ((ActionDescriptor) withConditionalResult.getDescriptor().getStep(2).getActions().get(0))
                .getConditionalResults().add(mock(com.opensymphony.workflow.loader.ConditionalResultDescriptor.class));
        assertUnconfirmed(wanted, withConditionalResult, "'Finish' in 'In Progress'");
    }

    @Test
    public void anExtraInitialActionOrAChangedCreateValidatorNeverMatches() throws Exception {
        ResolvedWorkflow wanted = validator.validate(simple(), user);

        JiraWorkflow extra = stored(wanted);
        extra.getDescriptor().getInitialActions().add(extra.getDescriptor().getInitialActions().get(0));
        assertUnconfirmed(wanted, extra, "initial action 'Create'");

        JiraWorkflow changed = stored(wanted);
        ((ActionDescriptor) changed.getDescriptor().getInitialActions().get(0)).getValidators().clear();
        assertUnconfirmed(wanted, changed, "initial action 'Create'");
    }

    private void assertUnconfirmed(ResolvedWorkflow wanted, JiraWorkflow actual, String expected) {
        List<String> differences = matcher.differences(wanted, actual);
        boolean reported = false;
        for (String difference : differences) {
            reported |= difference.contains("cannot confirm") && difference.contains(expected);
        }
        assertTrue(differences.toString(), reported);
    }

    @Test
    public void misplacedOrMalformedResolutionFunctionsNeverMatch() throws Exception {
        ResolvedWorkflow wanted = validator.validate(simple(), user);
        for (String malformed : Arrays.asList("", " 1", "1 ", "missing", "order")) {
            JiraWorkflow actual = stored(wanted);
            ActionDescriptor finish = (ActionDescriptor) actual.getDescriptor().getStep(2).getActions().get(0);
            List functions = finish.getUnconditionalResult().getPostFunctions();
            com.opensymphony.workflow.loader.FunctionDescriptor resolution =
                    (com.opensymphony.workflow.loader.FunctionDescriptor) functions.get(2);
            if ("missing".equals(malformed)) resolution.getArgs().remove("field.value");
            else if ("order".equals(malformed)) java.util.Collections.swap(functions, 2, 3);
            else resolution.getArgs().put("field.value", malformed);
            assertTrue(matcher.differences(wanted, actual).toString().contains("UNSUPPORTED"));
        }
    }

    @Test
    public void parallelNamesAndResolutionsMatchTheGraphWrittenByTheBuilder() throws Exception {
        ResolvedWorkflow wanted = validator.validate(parallelTransitions(), user);

        assertTrue(matcher.differences(wanted, stored(wanted)).isEmpty());
    }

    @Test
    public void renamingAnyParallelTransitionMakesTheExistingGraphConflict() throws Exception {
        WorkflowDefinition original = parallelTransitions();
        JiraWorkflow actual = stored(validator.validate(original, user));
        for (int i = 0; i < original.getTransitions().size(); i++) {
            List<TransitionDefinition> transitions = new ArrayList<TransitionDefinition>(original.getTransitions());
            TransitionDefinition previous = transitions.get(i);
            transitions.set(i, new TransitionDefinition("Renamed", previous.getFrom(), previous.isFromAny(),
                    previous.getTo(), previous.getScreen(), previous.getResolution()));
            ResolvedWorkflow wanted = validator.validate(request("W", original.getStatuses(), "Open", transitions), user);

            List<String> differences = matcher.differences(wanted, actual);
            assertEquals("transition " + i + ": " + differences, 2, differences.size());
            assertTrue(differences.toString(), differences.get(0).contains("'Renamed'"));
            assertTrue(differences.toString(), differences.get(1).contains("'" + previous.getName() + "'"));
        }
    }

    @Test
    public void changingAnyParallelTransitionsResolutionMakesTheExistingGraphConflict() throws Exception {
        WorkflowDefinition original = parallelTransitions();
        JiraWorkflow actual = stored(validator.validate(original, user));
        for (int i = 0; i < original.getTransitions().size(); i++) {
            List<TransitionDefinition> transitions = new ArrayList<TransitionDefinition>(original.getTransitions());
            TransitionDefinition previous = transitions.get(i);
            String changedId = "1".equals(previous.getResolution().getValue()) ? "2" : "1";
            transitions.set(i, new TransitionDefinition(previous.getName(), previous.getFrom(), previous.isFromAny(),
                    previous.getTo(), previous.getScreen(), new TransitionResolution("SET", changedId)));
            ResolvedWorkflow wanted = validator.validate(request("W", original.getStatuses(), "Open", transitions), user);

            List<String> differences = matcher.differences(wanted, actual);
            assertEquals("transition " + i + ": " + differences, 2, differences.size());
            assertTrue(differences.toString(), differences.get(0).contains("resolution SET:" + changedId));
            assertTrue(differences.toString(), differences.get(1).contains("resolution SET:" + previous.getResolution().getValue()));
        }
    }

    private WorkflowDefinition parallelTransitions() {
        Resolution duplicate = mock(Resolution.class);
        when(duplicate.getId()).thenReturn("2");
        when(duplicate.getName()).thenReturn("Duplicate");
        when(resolutions.getResolution("2")).thenReturn(duplicate);
        return request("W", Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Arrays.asList(move("Finish", "Open", "Done"),
                        new TransitionDefinition("Close", "Open", false, "Done", null,
                                new TransitionResolution("SET", "2")),
                        fromAny("Finish", "Done"),
                        new TransitionDefinition("Close", null, true, "Done", null,
                                new TransitionResolution("SET", "2"))));
    }

    /** What Jira returns for a workflow that was created from {@code workflow}. */
    private JiraWorkflow stored(ResolvedWorkflow workflow) throws Exception {
        Map<WorkflowStatus, String> ids = new HashMap<WorkflowStatus, String>();
        Map<String, Status> byId = new HashMap<String, Status>();
        int next = 10100;
        for (WorkflowStatus status : workflow.getStatuses()) {
            Status jira = status.getExisting();
            if (jira == null) {
                jira = WorkflowMocks.status(String.valueOf(next++), status.getName(), "new");
            }
            ids.put(status, jira.getId());
            byId.put(jira.getId(), jira);
        }
        final WorkflowDescriptor descriptor = builder.build(workflow, ids);
        JiraWorkflow jiraWorkflow = mock(JiraWorkflow.class);
        when(jiraWorkflow.getDescriptor()).thenReturn(descriptor);
        when(jiraWorkflow.getLinkedStatusObject(any(StepDescriptor.class))).thenAnswer(invocation ->
                byId.get(((StepDescriptor) invocation.getArgument(0)).getMetaAttributes().get("jira.status.id")));
        return jiraWorkflow;
    }
}
