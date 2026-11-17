package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.clone.CloneErrorCode;
import ru.rasulov.workflow.api.dto.clone.CloneScope;
import ru.rasulov.workflow.api.dto.clone.CloneVerdict;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsRequest;
import ru.rasulov.workflow.api.dto.clone.WorkflowCloneView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.assign.AssignMocks.issueType;
import static ru.rasulov.workflow.impl.assign.AssignMocks.mapping;
import static ru.rasulov.workflow.impl.assign.AssignMocks.project;
import static ru.rasulov.workflow.impl.assign.AssignMocks.scheme;
import static ru.rasulov.workflow.impl.assign.AssignMocks.status;
import static ru.rasulov.workflow.impl.assign.AssignMocks.usage;
import static ru.rasulov.workflow.impl.assign.AssignMocks.workflow;

/**
 * What a clone would do: which workflows are worth a copy, what the scheme does, the names of the copies, the issues
 * that move, and everything that makes the plan refuse.
 */
public class ClonePlannerTest {

    private static final String BUG = "10001";
    private static final String TASK = "10002";

    private final WorkflowSchemeManager schemeManager = mock(WorkflowSchemeManager.class);
    private final WorkflowManager workflowManager = mock(WorkflowManager.class);
    private final ProjectManager projectManager = mock(ProjectManager.class);

    private final IssueType bug = issueType(BUG, "Bug");
    private final IssueType task = issueType(TASK, "Task");
    private final Project alpha = project(1L, "ABC", "Alpha", "business", bug, task);
    private final Project beta = project(2L, "BBB", "Beta", "business", bug, task);
    private final Project gamma = project(3L, "GGG", "Gamma", "business", bug, task);
    private final JiraWorkflow flowA = workflow("Flow A", status("1", "Open"));
    private final JiraWorkflow bugFlow = workflow("Bug flow", status("1", "Open"), status("2", "Done"));
    /** Alpha, beta and gamma share this: Bug runs "Bug flow", everything else "Flow A". */
    private final AssignableWorkflowScheme shared = scheme(10100L, "Shared scheme", "Flow A", mapping(BUG, "Bug flow"));

    private final Map<Project, AssignableWorkflowScheme> schemeOf = new LinkedHashMap<Project, AssignableWorkflowScheme>();
    private final List<AssignableWorkflowScheme> allSchemes = new ArrayList<AssignableWorkflowScheme>();
    private final List<JiraWorkflow> allWorkflows = new ArrayList<JiraWorkflow>(Arrays.asList(flowA, bugFlow,
            workflow("jira")));
    private ClonePlanner planner;

    @Before
    public void setUp() {
        for (Project project : Arrays.asList(alpha, beta, gamma)) {
            schemeOf.put(project, shared);
        }
        allSchemes.add(shared);
        when(schemeManager.getWorkflowSchemeObj(any(Project.class))).thenAnswer(invocation ->
                schemeOf.get((Project) invocation.getArgument(0)));
        when(schemeManager.getProjectsUsing(any(AssignableWorkflowScheme.class))).thenAnswer(invocation -> {
            List<Project> users = new ArrayList<Project>();
            for (Map.Entry<Project, AssignableWorkflowScheme> entry : schemeOf.entrySet()) {
                if (entry.getValue() == invocation.getArgument(0)) {
                    users.add(entry.getKey());
                }
            }
            return users;
        });
        when(schemeManager.getAssignableSchemes()).thenAnswer(invocation -> allSchemes);
        AssignableWorkflowScheme systemDefault = scheme(null, "Default Workflow Scheme", "jira");
        when(schemeManager.getDefaultWorkflowScheme()).thenReturn(systemDefault);
        when(projectManager.getProjects()).thenReturn(Arrays.asList(alpha, beta, gamma));
        when(workflowManager.getWorkflows()).thenAnswer(invocation -> allWorkflows);
        when(workflowManager.getWorkflow(anyString())).thenAnswer(invocation -> {
            for (JiraWorkflow workflow : allWorkflows) {
                if (workflow.getName().equals(invocation.getArgument(0))) {
                    return workflow;
                }
            }
            return null;
        });
        planner = new ClonePlanner(schemeManager, workflowManager, projectManager,
                new AssignmentPlanner(schemeManager, mock(IssueTypeManager.class), mock(StatusManager.class)));
    }

    private static CloneWorkflowsRequest request(CloneScope scope, String... workflows) {
        return new CloneWorkflowsRequest(true, "ABC", scope, Arrays.asList(workflows), null,
                Collections.<String, String>emptyMap());
    }

    private static Map<String, WorkflowCloneView> byName(ClonePlan plan) {
        Map<String, WorkflowCloneView> views = new HashMap<String, WorkflowCloneView>();
        for (WorkflowCloneView view : plan.views()) {
            views.put(view.getName(), view);
        }
        return views;
    }

    private CloneFailureException refused(CloneWorkflowsRequest request, IssueUsage usage) {
        try {
            planner.plan(alpha, request, usage);
        } catch (CloneFailureException e) {
            return e;
        }
        throw new AssertionError("expected the plan to be refused");
    }

    /** Alpha gets a scheme of its own (Bug: "Bug flow", the rest "Flow A"); beta and gamma keep the shared one. */
    private AssignableWorkflowScheme alphaHasItsOwnScheme() {
        AssignableWorkflowScheme own = scheme(10200L, "ABC scheme", "Flow A", mapping(BUG, "Bug flow"));
        schemeOf.put(alpha, own);
        allSchemes.add(own);
        return own;
    }

    // --- scopes --------------------------------------------------------------------------------------------------

    @Test
    public void allWorkflowsOnASharedSchemeCopiesTheSchemeAndEveryWorkflowOthersUse() {
        ClonePlan plan = planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());

        assertEquals(SchemeAction.CREATE, plan.getAction());
        assertEquals("ABC: Shared scheme", plan.getNewSchemeName());
        assertEquals(2, plan.getCurrentScheme().getOtherProjects());
        WorkflowCloneView a = byName(plan).get("Flow A");
        WorkflowCloneView b = byName(plan).get("Bug flow");
        assertEquals(CloneVerdict.COPY, a.getVerdict());
        assertEquals("ABC: Flow A", a.getNewName());
        assertEquals(2, a.getOtherProjects());
        assertEquals(Arrays.asList("Beta", "Gamma"), a.getOtherProjectNames());
        assertEquals(CloneVerdict.COPY, b.getVerdict());
        assertEquals("ABC: Bug flow", b.getNewName());
        Map<String, String> leaving = new HashMap<String, String>();
        leaving.put(BUG, "Bug flow");
        leaving.put(TASK, "Flow A");
        assertEquals("every issue type of the project leaves the workflow it has now", leaving,
                plan.workflowsToLeave());
    }

    @Test
    public void schemeOnlyCopiesTheSchemeAndNoWorkflowAndMovesNoIssue() {
        ClonePlan plan = planner.plan(alpha, request(CloneScope.SCHEME_ONLY), usage(BUG, "1", "5"));

        assertEquals(SchemeAction.CREATE, plan.getAction());
        assertTrue(plan.toCopy().isEmpty());
        for (WorkflowCloneView view : plan.views()) {
            assertEquals(view.getName(), CloneVerdict.KEEP, view.getVerdict());
            assertNull(view.getNewName());
            assertEquals("the sharing is still reported", 2, view.getOtherProjects());
        }
        assertTrue(plan.workflowsToLeave().isEmpty());
    }

    @Test
    public void selectedCopiesOnlyTheNamedWorkflowsMatchedWithoutCase() {
        ClonePlan plan = planner.plan(alpha, request(CloneScope.SELECTED, "bug FLOW"), usage());

        assertEquals(SchemeAction.CREATE, plan.getAction());
        assertEquals(CloneVerdict.COPY, byName(plan).get("Bug flow").getVerdict());
        assertEquals(CloneVerdict.KEEP, byName(plan).get("Flow A").getVerdict());
        assertEquals(Collections.singletonMap(BUG, "Bug flow"), plan.workflowsToLeave());
    }

    @Test
    public void aWorkflowNobodyElseUsesGetsNoCopyAndAnOwnSchemeIsChangedInPlace() {
        // beta and gamma run "Flow A" for everything: only alpha runs "Bug flow"
        AssignableWorkflowScheme others = scheme(10300L, "Others scheme", "Flow A");
        schemeOf.put(beta, others);
        schemeOf.put(gamma, others);
        allSchemes.add(others);

        ClonePlan plan = planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());

        assertEquals("the scheme is alpha's alone", SchemeAction.UPDATE, plan.getAction());
        assertNull(plan.getNewSchemeName());
        assertEquals(CloneVerdict.COPY, byName(plan).get("Flow A").getVerdict());
        assertEquals(CloneVerdict.ALREADY_OWN, byName(plan).get("Bug flow").getVerdict());
        assertNull(byName(plan).get("Bug flow").getNewName());
        assertEquals(Collections.singletonMap(TASK, "Flow A"), plan.workflowsToLeave());
    }

    @Test
    public void whenNothingIsSharedThereIsNothingToDo() {
        alphaHasItsOwnScheme();
        schemeOf.put(beta, scheme(10300L, "Beta scheme", "jira"));
        schemeOf.put(gamma, scheme(10301L, "Gamma scheme", "jira"));

        for (CloneScope scope : CloneScope.values()) {
            ClonePlan plan = planner.plan(alpha, request(scope, scope == CloneScope.SELECTED ? new String[]{"Flow A"}
                    : new String[0]), usage());
            assertEquals(scope.name(), SchemeAction.NONE, plan.getAction());
            assertTrue(plan.toCopy().isEmpty());
        }
    }

    @Test
    public void aSchemeOnlyCloneOfAnOwnSchemeChangesNothing() {
        alphaHasItsOwnScheme();

        ClonePlan plan = planner.plan(alpha, request(CloneScope.SCHEME_ONLY), usage());

        assertEquals(SchemeAction.NONE, plan.getAction());
    }

    @Test
    public void theSystemDefaultSchemeIsAlwaysSharedAndGetsACopyWithAWarning() {
        AssignableWorkflowScheme builtIn = scheme(null, "Default Workflow Scheme", "jira");
        schemeOf.put(alpha, builtIn);
        when(schemeManager.isUsingDefaultScheme(alpha)).thenReturn(true);
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenReturn(builtIn);
        JiraWorkflow jira = allWorkflows.get(2);
        // beta and gamma run "jira" too, through the same default scheme
        schemeOf.put(beta, builtIn);
        schemeOf.put(gamma, builtIn);

        ClonePlan plan = planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());

        assertEquals(SchemeAction.CREATE, plan.getAction());
        assertEquals("ABC: Default Workflow Scheme", plan.getNewSchemeName());
        assertEquals("ABC: jira", byName(plan).get("jira").getNewName());
        assertEquals(CloneVerdict.COPY, byName(plan).get(jira.getName()).getVerdict());
        assertTrue(plan.getWarnings().get(0), plan.getWarnings().get(0).contains("system default"));
    }

    // --- refusals ------------------------------------------------------------------------------------------------

    @Test
    public void aWorkflowThatIsNotInTheSchemeIsNamedWithTheOnesThatAre() {
        allWorkflows.add(workflow("Elsewhere flow"));

        CloneFailureException exists = refused(request(CloneScope.SELECTED, "Elsewhere flow"), usage());
        CloneFailureException missing = refused(request(CloneScope.SELECTED, "Nope"), usage());

        assertEquals(CloneErrorCode.WORKFLOW_NOT_IN_SCHEME, exists.getErrorCode());
        assertEquals(Arrays.asList("Flow A", "Bug flow"), exists.getSuggestions());
        assertEquals(CloneErrorCode.UNKNOWN_WORKFLOW, missing.getErrorCode());
        assertEquals(Arrays.asList("Flow A", "Bug flow"), missing.getSuggestions());
    }

    @Test
    public void aWorkflowListedTwiceIsRefused() {
        CloneFailureException e = refused(request(CloneScope.SELECTED, "Bug flow", "bug flow"), usage());

        assertEquals(CloneErrorCode.VALIDATION_ERROR, e.getErrorCode());
    }

    @Test
    public void aDraftOfEveryWorkflowToCopyDoesNotBlockAndOneWarningNamesThemAllByAlphabet() {
        when(workflowManager.getDraftWorkflow("Bug flow")).thenReturn(bugFlow);
        when(workflowManager.getDraftWorkflow("Flow A")).thenReturn(flowA);

        ClonePlan plan = planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());

        assertEquals(SchemeAction.CREATE, plan.getAction());
        assertEquals(CloneVerdict.COPY, byName(plan).get("Flow A").getVerdict());
        assertEquals(CloneVerdict.COPY, byName(plan).get("Bug flow").getVerdict());
        assertEquals("one warning for all drafts", 1, plan.getWarnings().size());
        String warning = plan.getWarnings().get(0);
        assertTrue(warning, warning.contains("Bug flow, Flow A"));
        assertTrue(warning, warning.contains("not copied"));
        assertTrue(warning, warning.contains("publish_workflow_draft first"));
    }

    @Test
    public void draftedWorkflowsAreSortedWithoutCaseAndTheWarningComesAfterTheDefaultSchemeWarning() {
        AssignableWorkflowScheme builtIn = scheme(null, "Default Workflow Scheme", "Zed flow",
                mapping(BUG, "alpha flow"));
        allWorkflows.add(workflow("Zed flow"));
        allWorkflows.add(workflow("alpha flow"));
        for (Project project : Arrays.asList(alpha, beta, gamma)) {
            schemeOf.put(project, builtIn);
        }
        when(schemeManager.isUsingDefaultScheme(alpha)).thenReturn(true);
        when(workflowManager.getDraftWorkflow("Zed flow")).thenReturn(flowA);
        when(workflowManager.getDraftWorkflow("alpha flow")).thenReturn(flowA);

        ClonePlan plan = planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());

        assertEquals(2, plan.getWarnings().size());
        assertTrue(plan.getWarnings().get(0), plan.getWarnings().get(0).contains("system default"));
        assertTrue(plan.getWarnings().get(1), plan.getWarnings().get(1).contains("alpha flow, Zed flow"));
    }

    @Test
    public void aDraftOfAWorkflowThatIsNotCopiedIsNotMentioned() {
        when(workflowManager.getDraftWorkflow("Flow A")).thenReturn(flowA);

        ClonePlan selected = planner.plan(alpha, request(CloneScope.SELECTED, "Bug flow"), usage());
        assertEquals(SchemeAction.CREATE, selected.getAction());
        assertEquals(CloneVerdict.KEEP, byName(selected).get("Flow A").getVerdict());
        assertTrue("Flow A stays shared: " + selected.getWarnings(), selected.getWarnings().isEmpty());
        assertTrue(planner.plan(alpha, request(CloneScope.SCHEME_ONLY), usage()).getWarnings().isEmpty());
    }

    @Test
    public void aDraftOfAWorkflowTheProjectAlreadyOwnsIsNotMentioned() {
        // beta and gamma run "Flow A" for everything: only alpha runs "Bug flow", so it is alpha's own
        AssignableWorkflowScheme others = scheme(10300L, "Others scheme", "Flow A");
        schemeOf.put(beta, others);
        schemeOf.put(gamma, others);
        allSchemes.add(others);
        when(workflowManager.getDraftWorkflow("Bug flow")).thenReturn(bugFlow);

        ClonePlan own = planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());
        assertEquals(CloneVerdict.ALREADY_OWN, byName(own).get("Bug flow").getVerdict());
        assertTrue(own.getWarnings().toString(), own.getWarnings().isEmpty());

        when(workflowManager.getDraftWorkflow("Flow A")).thenReturn(flowA);
        ClonePlan both = planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());
        assertEquals(1, both.getWarnings().size());
        assertTrue(both.getWarnings().get(0), both.getWarnings().get(0).contains("Flow A"));
        assertFalse(both.getWarnings().get(0), both.getWarnings().get(0).contains("Bug flow"));
    }

    @Test
    public void withoutAnyDraftThereIsNoDraftWarning() {
        assertTrue(planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage()).getWarnings().isEmpty());
    }

    @Test
    public void aDraftOfTheSchemeStaysASeparateRefusalThatIsNotPublishedByTheWorkflowTool() {
        when(schemeManager.hasDraft(shared)).thenReturn(true);
        for (CloneScope scope : CloneScope.values()) {
            String[] named = scope == CloneScope.SELECTED ? new String[]{"Flow A"} : new String[0];
            CloneFailureException e = refused(request(scope, named), usage());
            assertEquals(scope.name(), CloneErrorCode.DRAFT_EXISTS, e.getErrorCode());
            assertFalse(e.getMessage(), e.getMessage().contains("publish_workflow_draft"));
        }
    }

    @Test
    public void tooManyIssuesToMoveIsRefusedBeforeAnythingIsWrittenButOnlyCountsWhatMoves() {
        IssueUsage big = new IssueUsage();
        for (int i = 0; i < 10_001; i++) {
            big.add(BUG, "1");
        }

        assertEquals(CloneErrorCode.TOO_MANY_ISSUES, refused(request(CloneScope.ALL_WORKFLOWS), big).getErrorCode());
        assertEquals("Task's workflow is copied, Bug's is not: the bugs stay where they are", SchemeAction.CREATE,
                planner.plan(alpha, request(CloneScope.SELECTED, "Flow A"), big).getAction());
        assertEquals(SchemeAction.CREATE, planner.plan(alpha, request(CloneScope.SCHEME_ONLY), big).getAction());
    }

    // --- names ---------------------------------------------------------------------------------------------------

    @Test
    public void aTakenNameGetsTheNextVersionAndACopyOfACopyKeepsCounting() {
        allWorkflows.add(workflow("ABC: Flow A"));
        allWorkflows.add(workflow("abc: bug flow"));
        allSchemes.add(scheme(10400L, "abc: shared scheme", "jira"));

        ClonePlan plan = planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());

        assertEquals("ABC: Flow A v2", byName(plan).get("Flow A").getNewName());
        assertEquals("ABC: Bug flow v2", byName(plan).get("Bug flow").getNewName());
        assertEquals("ABC: Shared scheme v2", plan.getNewSchemeName());
        assertEquals("Flow A v3", ClonePlanner.nextFreeName("Flow A v2", new HashSet<String>(Arrays.asList("flow a v2"))));
    }

    @Test
    public void aNameOfAnotherProjectIsRetitledToThisProjectAndANameOfThisProjectStaysWithAVersion() {
        allWorkflows.add(workflow("BBB_Flow"));
        allWorkflows.add(workflow("ABC: Own flow"));
        AssignableWorkflowScheme others = scheme(10500L, "BBB: Shared scheme", "BBB_Flow", mapping(BUG, "ABC: Own flow"));
        for (Project project : Arrays.asList(alpha, beta, gamma)) {
            schemeOf.put(project, others);
        }
        allSchemes.add(others);

        ClonePlan plan = planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());

        assertEquals("BBB's key gives way to ABC's", "ABC: Shared scheme", plan.getNewSchemeName());
        assertEquals("ABC_Flow", byName(plan).get("BBB_Flow").getNewName());
        assertEquals("a name that carries ABC already is not prefixed twice", "ABC: Own flow v2",
                byName(plan).get("ABC: Own flow").getNewName());
    }

    @Test
    public void aKeyIsOnlyTakenForAKeyWhenItIsFollowedByASeparator() {
        Project bu = project(9L, "BU", "Bu", "business", bug);
        assertEquals("ABC: BUS flow", ClonePlanner.titled("BUS flow", alpha, Arrays.asList(bu)));
        assertEquals("ABC flow", ClonePlanner.titled("BU flow", alpha, Arrays.asList(bu)));
        assertEquals("ABC-flow", ClonePlanner.titled("bu-flow", alpha, Arrays.asList(bu)));
    }

    @Test
    public void aNameOverLimitIsShortenedSoThatTheVersionFits() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < ClonePlanner.MAX_NAME_LENGTH; i++) {
            longName.append('x');
        }

        String next = ClonePlanner.nextFreeName(longName.toString(), new HashSet<String>());

        assertEquals(ClonePlanner.MAX_NAME_LENGTH, next.length());
        assertTrue(next.endsWith(" v2"));
    }

    @Test
    public void namesTheCallerChoseAreUsedAndCheckedAgainstWhatExists() {
        CloneWorkflowsRequest chosen = new CloneWorkflowsRequest(true, "ABC", CloneScope.ALL_WORKFLOWS, null,
                "  Alpha scheme ", Collections.singletonMap("bug flow", "Alpha bug flow"));

        ClonePlan plan = planner.plan(alpha, chosen, usage());

        assertEquals("Alpha scheme", plan.getNewSchemeName());
        assertEquals("Alpha bug flow", byName(plan).get("Bug flow").getNewName());
        assertEquals("the other one is named by default", "ABC: Flow A", byName(plan).get("Flow A").getNewName());

        CloneFailureException workflowTaken = refused(new CloneWorkflowsRequest(true, "ABC",
                CloneScope.ALL_WORKFLOWS, null, null, Collections.singletonMap("Bug flow", "FLOW a")), usage());
        CloneFailureException schemeTaken = refused(new CloneWorkflowsRequest(true, "ABC",
                CloneScope.SCHEME_ONLY, null, "Shared Scheme", Collections.<String, String>emptyMap()), usage());
        assertEquals(CloneErrorCode.NAME_TAKEN, workflowTaken.getErrorCode());
        assertEquals(CloneErrorCode.NAME_TAKEN, schemeTaken.getErrorCode());
    }

    @Test
    public void workflowNamesCaseInsensitiveDuplicateSourcesAreRejectedWithBothInputs() {
        for (boolean reverse : Arrays.asList(false, true)) {
            Map<String, String> names = new LinkedHashMap<String, String>();
            names.put(reverse ? "flow a" : "Flow A", reverse ? "Copy B" : "Copy A");
            names.put(reverse ? "Flow A" : "flow a", reverse ? "Copy A" : "Copy B");
            CloneFailureException e = refused(new CloneWorkflowsRequest(true, "ABC",
                    CloneScope.ALL_WORKFLOWS, null, null, names), usage());

            assertEquals(CloneErrorCode.VALIDATION_ERROR, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("'Flow A'"));
            assertTrue(e.getMessage(), e.getMessage().contains("'flow a'"));
            assertTrue(e.getMessage(), e.getMessage().contains("'Copy A'"));
            assertTrue(e.getMessage(), e.getMessage().contains("'Copy B'"));
        }
    }

    @Test
    public void aCopyCannotTakeTheNameOfAnotherCopyOfTheSameCall() {
        CloneWorkflowsRequest chosen = new CloneWorkflowsRequest(true, "ABC", CloneScope.ALL_WORKFLOWS, null, null,
                Collections.singletonMap("Bug flow", "ABC: Flow A"));

        assertEquals(CloneErrorCode.NAME_TAKEN, refused(chosen, usage()).getErrorCode());
    }

    @Test
    public void aNameForAWorkflowThatGetsNoCopyOrForASchemeThatIsNotCopiedIsRefusedNotIgnored() {
        CloneFailureException notCopied = refused(new CloneWorkflowsRequest(true, "ABC", CloneScope.SELECTED,
                Collections.singletonList("Flow A"), null, Collections.singletonMap("Bug flow", "X")), usage());
        assertEquals(CloneErrorCode.VALIDATION_ERROR, notCopied.getErrorCode());

        AssignableWorkflowScheme others = scheme(10300L, "Others scheme", "Flow A");
        schemeOf.put(beta, others);
        schemeOf.put(gamma, others);
        allSchemes.add(others);
        CloneFailureException inPlace = refused(new CloneWorkflowsRequest(true, "ABC", CloneScope.ALL_WORKFLOWS, null,
                "Name", Collections.<String, String>emptyMap()), usage());
        assertEquals(CloneErrorCode.VALIDATION_ERROR, inPlace.getErrorCode());
        try {
            planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());
        } catch (CloneFailureException e) {
            fail("without schemeName the same request is fine: " + e.getMessage());
        }
    }

    // --- sharing is read once per plan --------------------------------------------------------------------------

    @Test
    public void theUsersOfTheProjectsSchemeAreReadOncePerPlan() {
        planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());

        org.mockito.Mockito.verify(schemeManager, org.mockito.Mockito.times(1)).getProjectsUsing(shared);
    }

    @Test
    public void whatAnIssueTypeRunsInASharedSchemeIsReadOncePerSchemeNotOncePerProject() {
        planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());
        int withTwoOthers = workflowReads();

        List<Project> everyone = new ArrayList<Project>(Arrays.asList(alpha, beta, gamma));
        for (int i = 0; i < 8; i++) {
            Project extra = project(10L + i, "X" + i, "Extra " + i, "business", bug, task);
            schemeOf.put(extra, shared);
            everyone.add(extra);
        }
        when(projectManager.getProjects()).thenReturn(everyone);
        org.mockito.Mockito.clearInvocations(shared);
        planner.plan(alpha, request(CloneScope.ALL_WORKFLOWS), usage());

        assertEquals(withTwoOthers, workflowReads());
    }

    private int workflowReads() {
        int reads = 0;
        for (org.mockito.invocation.Invocation call : org.mockito.Mockito.mockingDetails(shared).getInvocations()) {
            if (call.getMethod().getName().equals("getActualWorkflow")) reads++;
        }
        return reads;
    }
}
