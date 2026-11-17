package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.workflow.api.WorkflowAssignLimits;
import ru.rasulov.workflow.api.dto.assign.AssignErrorCode;
import ru.rasulov.workflow.api.dto.assign.AssignVerdict;
import ru.rasulov.workflow.api.dto.assign.IssueTypeAssignment;
import ru.rasulov.workflow.api.dto.assign.IssueTypeWorkflowView;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.assign.SchemeView;
import ru.rasulov.common.api.dto.StatusMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.assign.AssignMocks.issueType;
import static ru.rasulov.workflow.impl.assign.AssignMocks.mapping;
import static ru.rasulov.workflow.impl.assign.AssignMocks.project;
import static ru.rasulov.workflow.impl.assign.AssignMocks.scheme;
import static ru.rasulov.workflow.impl.assign.AssignMocks.status;
import static ru.rasulov.workflow.impl.assign.AssignMocks.usage;
import static ru.rasulov.workflow.impl.assign.AssignMocks.workflow;

public class AssignmentPlannerTest {

    private final WorkflowSchemeManager schemeManager = mock(WorkflowSchemeManager.class);
    private final IssueTypeManager issueTypeManager = mock(IssueTypeManager.class);
    private final StatusManager statusManager = mock(StatusManager.class);
    private final IssueType bug = issueType("10001", "Bug");
    private final IssueType task = issueType("10002", "Task");
    private final IssueType epic = issueType("10003", "Epic");
    private final Status open = status("1", "Open");
    private final Status review = status("2", "In Review");
    private final Status done = status("3", "Done");
    private final Project project = project(1L, "ABC", "Alpha", "business", bug, task);
    /** Open and Done only: an issue in "In Review" has nowhere to stay. */
    private final JiraWorkflow target = workflow("Bug flow", open, done);
    private AssignableWorkflowScheme own = scheme(10100L, "ABC Workflow Scheme", "jira");
    private AssignmentPlanner planner;

    @Before
    public void setUp() {
        for (Status status : Arrays.asList(open, review, done)) {
            when(statusManager.getStatus(status.getId())).thenReturn(status);
        }
        when(statusManager.getStatuses()).thenReturn(Arrays.asList(open, review, done));
        for (IssueType type : Arrays.asList(bug, task, epic)) {
            when(issueTypeManager.getIssueType(type.getId())).thenReturn(type);
        }
        useScheme(own, false);
        when(schemeManager.getAssignableSchemes()).thenReturn(Collections.singletonList(own));
        planner = new AssignmentPlanner(schemeManager, issueTypeManager, statusManager);
    }

    private void useScheme(AssignableWorkflowScheme scheme, boolean isDefault) {
        own = scheme;
        when(schemeManager.getWorkflowSchemeObj(project)).thenReturn(scheme);
        when(schemeManager.isUsingDefaultScheme(project)).thenReturn(isDefault);
        when(schemeManager.getProjectsUsing(scheme)).thenReturn(Collections.singletonList(project));
        when(schemeManager.hasDraft(scheme)).thenReturn(false);
    }

    private static IssueTypeAssignment of(AssignmentPlan plan, String issueTypeName) {
        for (IssueTypeAssignment assignment : plan.getAssignments()) {
            if (issueTypeName.equals(assignment.getIssueType().getName())) {
                return assignment;
            }
        }
        throw new AssertionError("no assignment for " + issueTypeName + " in " + plan.getAssignments());
    }

    private AssignFailureException refused(List<IssueType> requested, boolean newScheme, IssueUsage usage) {
        try {
            planner.plan(project, target, requested, newScheme, usage);
        } catch (AssignFailureException e) {
            return e;
        }
        throw new AssertionError("the plan was expected to be refused");
    }

    // --- verdicts ------------------------------------------------------------------------------------------------

    @Test
    public void aTypeWithoutIssuesIsReadyAndOnlyTheSchemeChanges() {
        AssignmentPlan plan = planner.plan(project, target, Arrays.asList(bug), false, usage());

        assertEquals(AssignVerdict.READY, of(plan, "Bug").getVerdict());
        assertEquals("jira", of(plan, "Bug").getFrom());
        assertEquals("Bug flow", of(plan, "Bug").getTo());
        assertEquals(SchemeAction.UPDATE, plan.getAction());
    }

    @Test
    public void issuesInStatusesTheWorkflowHasAreMigratedAndKeepTheirStatus() {
        AssignmentPlan plan = planner.plan(project, target, Arrays.asList(bug), false,
                usage("10001", "1", "7", "10001", "3", "5"));

        IssueTypeAssignment bugs = of(plan, "Bug");
        assertEquals(AssignVerdict.MIGRATE, bugs.getVerdict());
        assertEquals(12, bugs.getIssues());
        assertEquals("Done", bugs.getStatuses().get(0).getName());
        assertEquals(5, bugs.getStatuses().get(0).getIssues());
        assertEquals(Boolean.TRUE, bugs.getStatuses().get(0).getInWorkflow());
        assertEquals(Collections.singletonMap("10001", "jira"), plan.workflowsToLeave());
    }

    @Test
    public void oneStatusTheWorkflowLacksRefusesTheWholeCallEvenIfAnotherTypeIsReady() {
        // Bug is fine, Task has an issue in "In Review": Q2, all or nothing
        AssignFailureException failure = refused(Arrays.asList(bug, task), false,
                usage("10001", "1", "2", "10002", "2", "4", "10002", "1", "1"));

        assertEquals(AssignErrorCode.STATUS_CONFLICT, failure.getErrorCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("Task: 'In Review' (4 issue(s))"));
        assertFalse("a status the workflow has is not the problem", failure.getMessage().contains("'Open'"));
        assertEquals("the statuses the workflow has are offered", Arrays.asList("Open", "Done"), failure.getSuggestions());
        assertEquals(AssignVerdict.MIGRATE, failure.getIssueTypes().get(0).getVerdict());
        assertEquals(AssignVerdict.CONFLICT, failure.getIssueTypes().get(1).getVerdict());
        assertEquals(Boolean.FALSE, failure.getIssueTypes().get(1).getStatuses().get(0).getInWorkflow());
    }

    @Test
    public void anExplicitMappingTurnsAStatusConflictIntoAMigrationPlan() {
        AssignmentPlan plan = planner.plan(project, target, Arrays.asList(task), false,
                usage("10002", "2", "4"), Collections.singletonList(new StatusMapping("In Review", "Done")));

        IssueTypeAssignment tasks = of(plan, "Task");
        assertEquals(AssignVerdict.MIGRATE, tasks.getVerdict());
        assertEquals(Boolean.FALSE, tasks.getStatuses().get(0).getInWorkflow());
        assertEquals("Done", tasks.getStatuses().get(0).getMappedTo());
        assertEquals(done, plan.getStatusMappings().get("2"));
    }

    @Test
    public void everyBlockingStatusStillNeedsAnExplicitMapping() {
        AssignFailureException failure;
        try {
            planner.plan(project, target, Arrays.asList(task), false,
                    usage("10002", "2", "4"), Collections.<StatusMapping>emptyList());
            throw new AssertionError("the plan was expected to be refused");
        } catch (AssignFailureException expected) {
            failure = expected;
        }

        assertEquals(AssignErrorCode.STATUS_CONFLICT, failure.getErrorCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("Ask the user"));
    }

    @Test
    public void aMappingTargetMustBelongToTheTargetWorkflow() {
        try {
            planner.plan(project, target, Arrays.asList(task), false, usage("10002", "2", "4"),
                    Collections.singletonList(new StatusMapping("In Review", "Not a target")));
            throw new AssertionError("the plan was expected to be refused");
        } catch (AssignFailureException failure) {
            assertEquals(AssignErrorCode.VALIDATION_ERROR, failure.getErrorCode());
            assertEquals(Arrays.asList("Open", "Done"), failure.getSuggestions());
        }
    }

    @Test
    public void aTypeThatHasTheWorkflowAlreadyChangesNothing() {
        useScheme(scheme(10100L, "ABC Workflow Scheme", "jira", mapping("10001", "Bug flow")), false);

        AssignmentPlan plan = planner.plan(project, target, Arrays.asList(bug), false, usage("10001", "2", "3"));

        assertEquals("its issues are not looked at: they are on the workflow already", AssignVerdict.ALREADY,
                of(plan, "Bug").getVerdict());
        assertEquals(SchemeAction.NONE, plan.getAction());
    }

    @Test
    public void aTypeThatHasItAlreadyDoesNotStopTheOthers() {
        useScheme(scheme(10100L, "ABC Workflow Scheme", "jira", mapping("10001", "Bug flow")), false);

        AssignmentPlan plan = planner.plan(project, target, Arrays.asList(bug, task), false, usage());

        assertEquals(AssignVerdict.ALREADY, of(plan, "Bug").getVerdict());
        assertEquals(AssignVerdict.READY, of(plan, "Task").getVerdict());
        assertEquals(SchemeAction.UPDATE, plan.getAction());
    }

    @Test
    public void theWholeProjectIncludesTypesThatHaveIssuesButAreNotInTheProjectAnyMore() {
        // Epic was in the issue type scheme once; its issues stay and run on the scheme's default workflow
        AssignmentPlan plan = planner.plan(project, target, null, false, usage("10003", "1", "2"));

        assertEquals(Arrays.asList("Bug", "Task", "Epic"), names(plan.getAssignments()));
        assertEquals(AssignVerdict.MIGRATE, of(plan, "Epic").getVerdict());
    }

    @Test
    public void anOrphanedTypeWithAForeignStatusConflictsToo() {
        AssignFailureException failure = refused(null, false, usage("10003", "2", "1"));

        assertEquals(AssignErrorCode.STATUS_CONFLICT, failure.getErrorCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("Epic"));
    }

    @Test
    public void aNamedTypeThatIsNotInTheProjectIsRefusedWithTheProjectsTypes() {
        AssignFailureException failure = refused(Arrays.asList(epic), false, usage());

        assertEquals(AssignErrorCode.ISSUE_TYPE_NOT_IN_PROJECT, failure.getErrorCode());
        assertEquals(Arrays.asList("Bug", "Task"), failure.getSuggestions());
    }

    @Test
    public void moreIssuesThanACallMayMoveAreRefused() {
        IssueUsage big = new IssueUsage();
        for (int i = 0; i <= WorkflowAssignLimits.MAX_ISSUES_TO_MIGRATE; i++) {
            big.add("10001", "1");
        }

        AssignFailureException failure = refused(Arrays.asList(bug), false, big);

        assertEquals(AssignErrorCode.TOO_MANY_ISSUES, failure.getErrorCode());
        assertEquals("what was planned is shown", 1, failure.getIssueTypes().size());
    }

    @Test
    public void exactlyTheLimitIsAllowed() {
        IssueUsage edge = new IssueUsage();
        for (int i = 0; i < WorkflowAssignLimits.MAX_ISSUES_TO_MIGRATE; i++) {
            edge.add("10001", "1");
        }

        assertEquals(SchemeAction.UPDATE, planner.plan(project, target, Arrays.asList(bug), false, edge).getAction());
    }

    // --- what happens to the scheme ------------------------------------------------------------------------------

    @Test
    public void aSchemeOfOnlyThisProjectIsChangedInPlace() {
        AssignmentPlan plan = planner.plan(project, target, Arrays.asList(bug), false, usage());

        assertEquals(SchemeAction.UPDATE, plan.getAction());
        assertNull(plan.getNewSchemeName());
        assertTrue(plan.getWarnings().isEmpty());
    }

    @Test
    public void theSystemDefaultSchemeIsNeverChangedAProjectOnItGetsANewOne() {
        useScheme(scheme(null, "Default", "jira"), true);

        AssignmentPlan plan = planner.plan(project, target, Arrays.asList(bug), false, usage());

        assertEquals(SchemeAction.CREATE, plan.getAction());
        assertEquals("ABC: Workflow Scheme", plan.getNewSchemeName());
        assertTrue(plan.getWarnings().toString(), plan.getWarnings().get(0).contains("system default"));
    }

    @Test
    public void aSchemeSharedWithOtherProjectsIsRefusedNamingThemUnlessANewSchemeWasAskedFor() {
        Project beta = project(2L, "BET", "Beta", "business");
        when(schemeManager.getProjectsUsing(own)).thenReturn(Arrays.asList(project, beta));

        AssignFailureException failure = refused(Arrays.asList(bug), false, usage());

        assertEquals(AssignErrorCode.SCHEME_SHARED, failure.getErrorCode());
        assertEquals(Collections.singletonList("Beta"), failure.getSuggestions());
        assertTrue(failure.getMessage(), failure.getMessage().contains("newScheme: true"));
        assertEquals(1, failure.getIssueTypes().size());

        AssignmentPlan plan = planner.plan(project, target, Arrays.asList(bug), true, usage());
        assertEquals("the other project keeps the scheme, so it is not left without projects", SchemeAction.CREATE,
                plan.getAction());
        assertTrue(plan.getWarnings().isEmpty());
    }

    @Test
    public void aSchemeWithADraftIsRefusedUnlessANewSchemeWasAskedFor() {
        when(schemeManager.hasDraft(own)).thenReturn(true);

        assertEquals(AssignErrorCode.DRAFT_EXISTS, refused(Arrays.asList(bug), false, usage()).getErrorCode());
        assertEquals(SchemeAction.CREATE, planner.plan(project, target, Arrays.asList(bug), true, usage()).getAction());
    }

    @Test
    public void aNewSchemeForAnOwnSchemeWarnsThatTheOldOneIsLeftWithoutProjects() {
        AssignmentPlan plan = planner.plan(project, target, Arrays.asList(bug), true, usage());

        assertEquals(SchemeAction.CREATE, plan.getAction());
        assertTrue(plan.getWarnings().toString(), plan.getWarnings().get(0).contains("left without projects"));
    }

    @Test
    public void aTakenSchemeNameGetsANumber() {
        AssignableWorkflowScheme first = scheme(1L, "abc: workflow scheme", "jira");
        AssignableWorkflowScheme second = scheme(2L, "ABC: Workflow Scheme (2)", "jira");
        when(schemeManager.getAssignableSchemes()).thenReturn(Arrays.asList(first, second));

        assertEquals("ABC: Workflow Scheme (3)",
                planner.plan(project, target, Arrays.asList(bug), true, usage()).getNewSchemeName());
    }

    @Test
    public void aJiraSoftwareProjectGetsTheBoardWarning() {
        Project software = project(1L, "ABC", "Alpha", "software", bug, task);
        when(schemeManager.getWorkflowSchemeObj(software)).thenReturn(own);
        when(schemeManager.getProjectsUsing(own)).thenReturn(Collections.singletonList(software));

        AssignmentPlan plan = planner.plan(software, target, Arrays.asList(bug), false, usage());

        assertTrue(plan.getWarnings().toString(), plan.getWarnings().get(0).contains("boards"));
    }

    // --- reading -------------------------------------------------------------------------------------------------

    @Test
    public void describeListsTheProjectsTypesThenOrphansWithStatusesByName() {
        List<IssueTypeWorkflowView> views = planner.describe(project, own,
                usage("10001", "3", "5", "10001", "1", "7", "10003", "1", "2"));

        assertEquals(3, views.size());
        assertEquals("Bug", views.get(0).getIssueType().getName());
        assertEquals(Integer.valueOf(12), views.get(0).getIssues());
        assertEquals("Done", views.get(0).getStatuses().get(0).getName());
        assertEquals("Open", views.get(0).getStatuses().get(1).getName());
        assertNull("nothing to compare the status with", views.get(0).getStatuses().get(0).getInWorkflow());
        assertEquals(Integer.valueOf(0), views.get(1).getIssues());
        assertEquals("the orphan comes last", "Epic", views.get(2).getIssueType().getName());
        assertEquals("jira", views.get(2).getWorkflow());
    }

    @Test
    public void schemeViewCountsOtherProjectsAndShowsAtMostTen() {
        List<Project> users = new ArrayList<Project>();
        users.add(project);
        for (int i = 0; i < 12; i++) {
            users.add(project(100 + i, "P" + i, String.format("Proj %02d", i), "business"));
        }
        when(schemeManager.getProjectsUsing(own)).thenReturn(users);
        when(schemeManager.hasDraft(own)).thenReturn(true);

        SchemeView view = planner.schemeView(project, own);

        assertEquals(12, view.getOtherProjects());
        assertEquals(WorkflowAssignLimits.MAX_PROJECTS_SHOWN, view.getOtherProjectNames().size());
        assertEquals("Proj 00", view.getOtherProjectNames().get(0));
        assertTrue(view.hasDraft());
        assertFalse(view.isDefault());
    }

    @Test
    public void schemeViewOfTheSystemDefaultSchemeHasNoId() {
        AssignableWorkflowScheme system = scheme(null, "Default", "jira");
        when(system.isDefault()).thenReturn(true);
        useScheme(system, true);

        SchemeView view = planner.schemeView(project, system);

        assertNull(view.getId());
        assertTrue(view.isDefault());
        assertFalse(view.hasDraft());
    }

    private static List<String> names(List<IssueTypeAssignment> assignments) {
        List<String> names = new ArrayList<String>();
        for (IssueTypeAssignment assignment : assignments) {
            names.add(assignment.getIssueType().getName());
        }
        return names;
    }
}
