package ru.rasulov.workflow.impl.assign;

import com.atlassian.cache.Cache;
import com.atlassian.cache.CacheManager;
import com.atlassian.cache.CacheSettings;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.workflow.api.dto.assign.AssignErrorCode;
import ru.rasulov.workflow.api.dto.assign.AssignStatus;
import ru.rasulov.workflow.api.dto.assign.AssignVerdict;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowRequest;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowResponse;
import ru.rasulov.workflow.api.dto.assign.IssueTypeAssignment;
import ru.rasulov.workflow.api.dto.assign.ProjectWorkflowsResponse;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.common.api.dto.StatusUsage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.assign.AssignMocks.issueType;
import static ru.rasulov.workflow.impl.assign.AssignMocks.project;
import static ru.rasulov.workflow.impl.assign.AssignMocks.scheme;
import static ru.rasulov.workflow.impl.assign.AssignMocks.status;
import static ru.rasulov.workflow.impl.assign.AssignMocks.workflow;

public class WorkflowAssignerImplTest {

    private final ApplicationUser user = mock(ApplicationUser.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final ProjectManager projectManager = mock(ProjectManager.class);
    private final IssueTypeManager issueTypeManager = mock(IssueTypeManager.class);
    private final WorkflowManager workflowManager = mock(WorkflowManager.class);
    private final WorkflowSchemeManager schemeManager = mock(WorkflowSchemeManager.class);
    private final IssueStatusCounter counter = mock(IssueStatusCounter.class);
    private final AssignmentPlanner planner = mock(AssignmentPlanner.class);
    private final SchemeWriter schemeWriter = mock(SchemeWriter.class);
    private final AssignmentVerifier verifier = mock(AssignmentVerifier.class);
    private final IssueMigrator migrator = mock(IssueMigrator.class);

    private final IssueType bug = issueType("10001", "Bug");
    private final IssueType task = issueType("10002", "Task");
    private final Project alpha = project(1L, "ABC", "Alpha", "business", bug, task);
    private final JiraWorkflow target = workflow("Bug flow", status("1", "Open"));
    private final AssignableWorkflowScheme own = scheme(10100L, "ABC Workflow Scheme", "jira");
    private final AssignableWorkflowScheme created = scheme(10200L, "ABC: Workflow Scheme", "jira");
    private WorkflowAssignerImpl assigner;
    private ru.rasulov.common.impl.undo.UndoTestFixture journalFixture;
    private WorkflowAssignerImpl directAssigner;
    private final Map<String, ActionPlanStore.Plan> storedPlans = new HashMap<>();

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        journalFixture = ru.rasulov.common.impl.undo.UndoTestFixture.create("admin");
        when(user.getKey()).thenReturn("admin");
        when(user.getUsername()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(user);
        when(lock.run(anyString(), anyString(), any())).thenAnswer(invocation ->
                ((Supplier<Object>) invocation.getArgument(2)).get());
        when(projectManager.getProjectByCurrentKeyIgnoreCase("ABC")).thenReturn(alpha);
        when(projectManager.getProjects()).thenReturn(Collections.singletonList(alpha));
        when(issueTypeManager.getIssueTypes()).thenReturn(Arrays.asList(bug, task));
        when(issueTypeManager.getIssueType("10001")).thenReturn(bug);
        JiraWorkflow builtIn = workflow("jira");
        when(workflowManager.getWorkflows()).thenReturn(Arrays.asList(builtIn, target));
        when(schemeWriter.create(any(), anyString(), any(), any())).thenReturn(created);
        when(planner.plan(any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(migrating(SchemeAction.UPDATE));
        assigner = new WorkflowAssignerImpl(guard, lock, new ProjectResolver(projectManager),
                new IssueTypeResolver(issueTypeManager), workflowManager, schemeManager, counter, planner, schemeWriter,
                verifier, migrator, new ru.rasulov.common.impl.PreviewPlanExecutor(
                        ru.rasulov.common.impl.InMemoryActionPlans.create(), new ru.rasulov.common.impl.ActionPlanCodec()),
                new ru.rasulov.common.impl.ActionPlanCodec(), journalFixture.journal) {
            @Override
            public AssignWorkflowResponse assign(AssignWorkflowRequest request) {
                return ru.rasulov.common.impl.LegacyWriteCalls.assignment(request, super::assign);
            }
        };
        Cache<String, ActionPlanStore.Plan> cache = mock(Cache.class);
        when(cache.get(anyString())).thenAnswer(call -> storedPlans.get(call.getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> {
            storedPlans.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(cache).put(anyString(), any(ActionPlanStore.Plan.class));
        org.mockito.Mockito.doAnswer(call -> {
            storedPlans.remove((String) call.getArgument(0));
            return null;
        }).when(cache).remove(anyString());
        CacheManager caches = mock(CacheManager.class);
        when(caches.<String, ActionPlanStore.Plan>getCache(anyString(), org.mockito.ArgumentMatchers.isNull(),
                any(CacheSettings.class))).thenReturn(cache);
        StatusManager statuses = mock(StatusManager.class);
        com.atlassian.jira.issue.status.Status open = target.getLinkedStatusObjects().get(0);
        when(statuses.getStatus("1")).thenReturn(open);
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenReturn(own);
        when(counter.scan(eq(alpha), any(), any(), eq(workflowManager)))
                .thenReturn(new IssueStatusCounter.Scan(AssignMocks.usage("10001", "1", "3"), null));
        directAssigner = new WorkflowAssignerImpl(guard, lock, new ProjectResolver(projectManager),
                new IssueTypeResolver(issueTypeManager), workflowManager, schemeManager, counter,
                new AssignmentPlanner(schemeManager, issueTypeManager, statuses), schemeWriter, verifier, migrator,
                new ru.rasulov.common.impl.PreviewPlanExecutor(new ActionPlanStore(caches), new ActionPlanCodec()),
                new ActionPlanCodec(), journalFixture.journal);
    }

    @org.junit.After
    public void closeJournal() { journalFixture.close(); }

    @Test
    public void explicitModeAndPreviewTokenAreRequiredByTheService() {
        try {
            assigner.assign(new AssignWorkflowRequest(null, null, null, false, null, false, null));
            fail("missing dryRun must be rejected");
        } catch (InvalidRequestException expected) {
            assertTrue(expected.getMessage().contains("dryRun"));
        }
        AssignWorkflowResponse stale = assigner.assign(
                new AssignWorkflowRequest(false, null, null, false, null, false, "missing"));
        assertEquals(AssignErrorCode.PREVIEW_STALE, stale.getErrorCode());
    }

    /** Bug has 3 issues to move from "jira"; the scheme is the project's own or a new one. */
    private AssignmentPlan migrating(SchemeAction action) {
        IssueTypeAssignment moving = new IssueTypeAssignment(new IssueTypeRef("10001", "Bug"), "jira", "Bug flow",
                AssignVerdict.MIGRATE, 3, Collections.singletonList(new StatusUsage("Open", 3, true)));
        boolean create = action == SchemeAction.CREATE;
        return new AssignmentPlan(Collections.singletonList(moving), action, own, false,
                create ? "ABC: Workflow Scheme" : null, SchemeEdit.issueTypes("Bug flow", Collections.singleton("10001")),
                Collections.singletonList("A warning."));
    }

    private static AssignWorkflowRequest request(boolean dryRun, String workflow, boolean all, String... types) {
        return new AssignWorkflowRequest(dryRun, "ABC", workflow, all, Arrays.asList(types), false);
    }

    private static AssignWorkflowRequest bugsOnly(boolean dryRun) {
        return request(dryRun, "Bug flow", false, "Bug");
    }

    /** Reports these confirmed migrations on the first pass, none on later calls. */
    private void migrateMoves(String... keys) {
        when(migrator.migrate(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            MigrationCount moved = invocation.getArgument(4);
            for (String key : keys) {
                moved.increment();
            }
            return Collections.<String>emptyList();
        }).thenReturn(Collections.<String>emptyList());
    }

    // --- dry run -------------------------------------------------------------------------------------------------

    @Test
    public void aDryRunReportsThePlanWritesNothingAndDoesNotWaitForTheLock() {
        AssignWorkflowResponse response = assigner.assign(bugsOnly(true));

        assertEquals(AssignStatus.WOULD_ASSIGN, response.getStatus());
        assertTrue(response.isDryRun());
        assertEquals("Bug flow", response.getWorkflow());
        assertEquals("ABC", response.getProject().getKey());
        assertEquals(SchemeAction.UPDATE, response.getScheme().getAction());
        assertEquals(Long.valueOf(10100), response.getScheme().getId());
        assertEquals("A warning.", response.getMessage());
        assertEquals(AssignVerdict.MIGRATE, response.getIssueTypes().get(0).getVerdict());
        verifyNoInteractions(schemeWriter, migrator, lock);
    }

    @Test
    public void aDryRunOfANewSchemeHasItsNameButNoIdYet() {
        when(planner.plan(any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(migrating(SchemeAction.CREATE));

        AssignWorkflowResponse response = assigner.assign(bugsOnly(true));

        assertEquals(SchemeAction.CREATE, response.getScheme().getAction());
        assertNull(response.getScheme().getId());
        assertEquals("ABC: Workflow Scheme", response.getScheme().getName());
        verifyNoInteractions(schemeWriter, migrator);
    }

    @Test
    public void whenEveryTypeHasTheWorkflowNothingIsWrittenAndItSaysSo() {
        IssueTypeAssignment has = new IssueTypeAssignment(new IssueTypeRef("10001", "Bug"), "Bug flow", "Bug flow",
                AssignVerdict.ALREADY, 0, Collections.<StatusUsage>emptyList());
        when(planner.plan(any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(new AssignmentPlan(
                Collections.singletonList(has), SchemeAction.NONE, own, false, null, null,
                Collections.<String>emptyList()));

        AssignWorkflowResponse response = assigner.assign(bugsOnly(false));

        assertEquals(AssignStatus.ALREADY_ASSIGNED, response.getStatus());
        assertEquals(SchemeAction.NONE, response.getScheme().getAction());
        verifyNoInteractions(schemeWriter, migrator);
    }

    // --- writing -------------------------------------------------------------------------------------------------

    @Test
    public void anOwnSchemeIsChangedAfterTheIssuesMovedAndAllUnderTheLock() {
        migrateMoves("ABC-1", "ABC-2", "ABC-3");

        AssignWorkflowResponse response = assigner.assign(bugsOnly(false));

        assertEquals(AssignStatus.ASSIGNED, response.getStatus());
        assertEquals(SchemeAction.UPDATE, response.getScheme().getAction());
        assertEquals(Long.valueOf(10100), response.getScheme().getId());
        assertEquals("A warning.", response.getMessage());
        InOrder order = inOrder(lock, migrator, schemeWriter);
        order.verify(lock).run(eq(WorkflowAssignerImpl.LOCK_NAME), anyString(), any());
        order.verify(migrator).migrate(eq(alpha), eq(Collections.singletonMap("10001", "jira")), eq(target),
                eq(Collections.emptyMap()), any());
        order.verify(schemeWriter).update(eq(own), any());
        order.verify(migrator).migrate(eq(alpha), any(), eq(target), eq(Collections.emptyMap()), any());
        verify(schemeWriter, never()).create(any(), any(), any(), any());
    }

    @Test
    public void aNewSchemeIsCreatedFirstThenTheIssuesMoveThenTheProjectGetsIt() {
        when(planner.plan(any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(migrating(SchemeAction.CREATE));
        migrateMoves("ABC-1");

        AssignWorkflowResponse response = assigner.assign(bugsOnly(false));

        assertEquals(AssignStatus.ASSIGNED, response.getStatus());
        assertEquals(SchemeAction.CREATE, response.getScheme().getAction());
        assertEquals(Long.valueOf(10200), response.getScheme().getId());
        InOrder order = inOrder(schemeWriter, migrator);
        order.verify(schemeWriter).create(eq(user), eq("ABC: Workflow Scheme"), eq(own), any());
        order.verify(migrator).migrate(any(), any(), any(), any(), any());
        order.verify(schemeWriter).assign(alpha, created);
    }

    @Test
    public void anAssignmentJiraDoesNotReportAsExpectedIsPartialAndIsNotRepaired() {
        when(planner.plan(any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(migrating(SchemeAction.CREATE));
        when(verifier.verify(eq(alpha), eq(Collections.singletonMap("10001", "Bug flow"))))
                .thenReturn("Jira does not report the assignment as expected: workflow 'Target' is reported inactive.");
        migrateMoves("ABC-1");

        AssignWorkflowResponse response = assigner.assign(bugsOnly(false));

        assertEquals(AssignStatus.PARTIALLY_ASSIGNED, response.getStatus());
        assertTrue(response.getMessage(), response.getMessage().contains("reported inactive"));
        verify(schemeWriter, times(1)).assign(alpha, created);
        verify(schemeWriter, never()).update(any(), any());
    }


    // --- the sweep for issues created meanwhile ------------------------------------------------------------------

    @Test
    public void issuesCreatedWhileTheCallRanAreMovedByTheSecondPassAndReported() {
        when(migrator.migrate(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            MigrationCount moved = invocation.getArgument(4);
            moved.increment();
            return Collections.<String>emptyList();
        });

        AssignWorkflowResponse response = assigner.assign(bugsOnly(false));

        assertEquals(AssignStatus.ASSIGNED, response.getStatus());
        assertTrue(response.getMessage(), response.getMessage().contains("1 issue(s) created while the call was running"));
    }

    @Test
    public void aFailedSecondPassDoesNotUndoTheAssignmentButTellsTheUser() {
        when(migrator.migrate(any(), any(), any(), any(), any())).thenReturn(Collections.<String>emptyList())
                .thenThrow(new IllegalStateException("boom"));

        AssignWorkflowResponse response = assigner.assign(bugsOnly(false));

        assertEquals(AssignStatus.PARTIALLY_ASSIGNED, response.getStatus());
        assertTrue(response.getMessage(), response.getMessage().contains("final check"));
        assertTrue(response.getMessage(), response.getMessage().contains("inspect native workflow entries"));
    }

    @Test
    public void issuesInAStatusTheWorkflowLacksAfterTheSecondPassAreNamed() {
        when(migrator.migrate(any(), any(), any(), any(), any())).thenReturn(Collections.<String>emptyList())
                .thenReturn(Arrays.asList("ABC-9"));

        AssignWorkflowResponse response = assigner.assign(bugsOnly(false));

        assertEquals(AssignStatus.PARTIALLY_ASSIGNED, response.getStatus());
        assertTrue(response.getMessage(), response.getMessage().contains("ABC-9"));
        assertTrue(response.getMessage(), response.getMessage().contains("inspect native workflow entries"));
    }

    // --- the request ---------------------------------------------------------------------------------------------

    @Test
    public void bothAllIssueTypesAndAListIsRefused() {
        AssignWorkflowResponse response = assigner.assign(request(true, "Bug flow", true, "Bug"));

        assertEquals(AssignErrorCode.VALIDATION_ERROR, response.getErrorCode());
        assertNull("nothing was resolved yet", response.getProject());
        verifyNoInteractions(planner);
    }

    @Test
    public void neitherAllIssueTypesNorAListIsRefusedWithAnInstructionToAsk() {
        AssignWorkflowResponse response = assigner.assign(request(true, "Bug flow", false));

        assertEquals(AssignErrorCode.VALIDATION_ERROR, response.getErrorCode());
        assertTrue(response.getMessage(), response.getMessage().contains("Ask the user"));
    }

    @Test
    public void aBlankWorkflowIsRefused() {
        assertEquals(AssignErrorCode.VALIDATION_ERROR, assigner.assign(request(true, "  ", true)).getErrorCode());
        assertEquals(AssignErrorCode.VALIDATION_ERROR, assigner.assign(request(true, null, true)).getErrorCode());
    }

    @Test
    public void tooManyIssueTypesAreRefused() {
        String[] many = new String[51];
        Arrays.fill(many, "Bug");

        assertEquals(AssignErrorCode.VALIDATION_ERROR, assigner.assign(request(true, "Bug flow", false, many)).getErrorCode());
    }

    @Test
    public void anUnknownProjectIsAFailedResultWithSuggestions() {
        AssignWorkflowResponse response = assigner.assign(new AssignWorkflowRequest(true, "ZZZ", "Bug flow", true,
                null, false));

        assertEquals(AssignErrorCode.UNKNOWN_PROJECT, response.getErrorCode());
        assertNull(response.getProject());
    }

    @Test
    public void anUnknownWorkflowIsAFailedResultWithSimilarNamesAndTheProject() {
        AssignWorkflowResponse response = assigner.assign(request(true, "Bug", true));

        assertEquals(AssignErrorCode.UNKNOWN_WORKFLOW, response.getErrorCode());
        assertEquals(Collections.singletonList("Bug flow"), response.getSuggestions());
        assertEquals("ABC", response.getProject().getKey());
        assertEquals("the name as the caller wrote it", "Bug", response.getWorkflow());
    }

    @Test
    public void theWorkflowIsFoundWithoutCaseAndReportedByItsRealName() {
        AssignWorkflowResponse response = assigner.assign(request(true, " BUG FLOW ", false, "Bug"));

        assertEquals(AssignStatus.WOULD_ASSIGN, response.getStatus());
        assertEquals("Bug flow", response.getWorkflow());
    }

    @Test
    public void anUnknownIssueTypeIsAFailedResult() {
        AssignWorkflowResponse response = assigner.assign(request(true, "Bug flow", false, "Nope"));

        assertEquals(AssignErrorCode.UNKNOWN_ISSUE_TYPE, response.getErrorCode());
    }

    @Test
    public void anIssueTypeListedTwiceIsRefused() {
        AssignWorkflowResponse response = assigner.assign(request(true, "Bug flow", false, "Bug", "10001"));

        assertEquals(AssignErrorCode.VALIDATION_ERROR, response.getErrorCode());
        assertTrue(response.getMessage(), response.getMessage().contains("twice"));
    }

    @Test
    public void whenThePlannerRefusesTheResultCarriesWhatWasPlanned() {
        IssueTypeAssignment conflict = new IssueTypeAssignment(new IssueTypeRef("10001", "Bug"), "jira", "Bug flow",
                AssignVerdict.CONFLICT, 4, Collections.singletonList(new StatusUsage("In Review", 4, false)));
        when(planner.plan(any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new AssignFailureException(
                AssignErrorCode.STATUS_CONFLICT, "blocked", Collections.singletonList("Open"),
                Collections.singletonList(conflict)));

        AssignWorkflowResponse response = assigner.assign(bugsOnly(false));

        assertEquals(AssignStatus.FAILED, response.getStatus());
        assertEquals(AssignErrorCode.STATUS_CONFLICT, response.getErrorCode());
        assertEquals(Collections.singletonList("Open"), response.getSuggestions());
        assertEquals(AssignVerdict.CONFLICT, response.getIssueTypes().get(0).getVerdict());
        assertNull(response.getScheme());
        verifyNoInteractions(schemeWriter, migrator);
    }

    @Test
    public void aNullRequestIsAnErrorOfTheCall() {
        try {
            assigner.assign(null);
            fail("expected InvalidRequestException");
        } catch (InvalidRequestException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void nothingHappensForACallerWhoIsNotAnAdministrator() {
        when(guard.requireAdministrator()).thenThrow(new AccessException("no", false));

        try {
            assigner.assign(bugsOnly(false));
            fail("expected AccessException");
        } catch (AccessException expected) {
            verifyNoInteractions(planner, schemeWriter, migrator, lock, counter);
        }
        try {
            assigner.projectWorkflows("ABC", false);
            fail("expected AccessException");
        } catch (AccessException expected) {
            verifyNoInteractions(planner, counter);
        }
    }

    // --- reading -------------------------------------------------------------------------------------------------

    @Test
    public void readingAnswersWithTheSchemeAndTheIssueTypes() {
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenReturn(own);

        ProjectWorkflowsResponse response = assigner.projectWorkflows("ABC", false);

        assertEquals("ABC", response.getProject().getKey());
        verify(planner).schemeView(alpha, own);
        verify(planner).describe(eq(alpha), eq(own), any());
        verifyNoInteractions(schemeWriter, migrator);
    }

    @Test
    public void configurationReadDoesNotAccessIssueStorage() {
        doThrow(new IllegalStateException("Issue storage unavailable"))
                .when(counter).scan(any(), any(), any(), any());

        ProjectWorkflowsResponse response = directAssigner.projectWorkflows("ABC", false);

        assertEquals("ABC", response.getProject().getKey());
        assertEquals(2, response.getIssueTypes().size());
        assertEquals("jira", response.getIssueTypes().get(0).getWorkflow());
        verifyNoInteractions(counter);
    }

    @Test
    public void configurationReadDoesNotReportUnrequestedCountsAsZero() throws Exception {
        ProjectWorkflowsResponse response = directAssigner.projectWorkflows("ABC", false);
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                .valueToTree(response);

        assertEquals("NOT_REQUESTED", json.path("issueCountsStatus").asText());
        assertTrue(json.path("issueTypes").get(0).path("issues").isNull());
        assertTrue(json.path("issueTypes").get(0).path("statuses").isNull());
    }

    @Test
    public void requestedCountsIncludeHistoricalTypesAndExactStatusTotals() {
        when(counter.count(alpha)).thenReturn(AssignMocks.usage("10001", "1", "3", "removed", "1", "2"));

        ProjectWorkflowsResponse response = directAssigner.projectWorkflows("ABC", true);

        assertEquals(ProjectWorkflowsResponse.IssueCountsStatus.COMPLETE, response.getIssueCountsStatus());
        assertEquals(Integer.valueOf(3), response.getIssueTypes().get(0).getIssues());
        assertEquals(3, response.getIssueTypes().get(0).getStatuses().get(0).getIssues());
        assertEquals(Integer.valueOf(0), response.getIssueTypes().get(1).getIssues());
        assertTrue(response.getIssueTypes().get(1).getStatuses().isEmpty());
        assertEquals("removed", response.getIssueTypes().get(2).getIssueType().getId());
        assertEquals(Integer.valueOf(2), response.getIssueTypes().get(2).getIssues());
        verify(counter, never()).scan(any(), any(), any(), any());
    }

    @Test
    public void failedCountsKeepCompleteConfigurationAndUnknownStatistics() {
        when(counter.count(alpha)).thenThrow(new IllegalStateException("storage failure"));

        ProjectWorkflowsResponse response = directAssigner.projectWorkflows("ABC", true);

        assertEquals(ProjectWorkflowsResponse.IssueCountsStatus.ERROR, response.getIssueCountsStatus());
        assertNotNull(response.getIssueCountsError());
        assertEquals("jira", response.getDefaultWorkflow());
        assertEquals(2, response.getIssueTypes().size());
        assertEquals("jira", response.getIssueTypes().get(0).getWorkflow());
        assertNull(response.getIssueTypes().get(0).getIssues());
        assertNull(response.getIssueTypes().get(0).getStatuses());
    }

    @Test
    public void configurationIncludesDefaultExplicitMappingsDraftAndSharing() {
        own.getMappings().put("10001", "Bug flow");
        when(schemeManager.hasDraft(own)).thenReturn(true);
        Project other = project(2L, "OTHER", "Other project", "business");
        when(schemeManager.getProjectsUsing(own)).thenReturn(Arrays.asList(alpha, other));

        ProjectWorkflowsResponse response = directAssigner.projectWorkflows("ABC", false);

        assertEquals("jira", response.getDefaultWorkflow());
        assertEquals("Bug flow", response.getIssueTypes().get(0).getWorkflow());
        assertEquals("jira", response.getIssueTypes().get(1).getWorkflow());
        assertTrue(response.getScheme().hasDraft());
        assertEquals(1, response.getScheme().getOtherProjects());
        assertEquals(Collections.singletonList("Other project"), response.getScheme().getOtherProjectNames());
        verifyNoInteractions(counter);
    }

    @Test
    public void emptyProjectOnDefaultSchemeHasCompleteZeroCountsOnlyWhenRequested() {
        AssignableWorkflowScheme defaultScheme = scheme(null, "Default Workflow Scheme", "jira");
        when(defaultScheme.isDefault()).thenReturn(true);
        when(schemeManager.isUsingDefaultScheme(alpha)).thenReturn(true);
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenReturn(defaultScheme);
        when(counter.count(alpha)).thenReturn(new IssueUsage());

        ProjectWorkflowsResponse configuration = directAssigner.projectWorkflows("ABC", false);
        assertTrue(configuration.getScheme().isDefault());
        assertNull(configuration.getScheme().getId());
        assertEquals("jira", configuration.getDefaultWorkflow());
        assertNull(configuration.getIssueTypes().get(0).getIssues());

        ProjectWorkflowsResponse counted = directAssigner.projectWorkflows("ABC", true);
        assertEquals(ProjectWorkflowsResponse.IssueCountsStatus.COMPLETE, counted.getIssueCountsStatus());
        assertEquals(Integer.valueOf(0), counted.getIssueTypes().get(0).getIssues());
        assertTrue(counted.getIssueTypes().get(0).getStatuses().isEmpty());
    }

    @Test
    public void readingAnUnknownProjectIsAnErrorOfTheCallWithSuggestions() {
        try {
            assigner.projectWorkflows("ZZZ", false);
            fail("expected InvalidRequestException");
        } catch (InvalidRequestException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ZZZ"));
        }
    }

    @Test
    public void directPreviewApplyAssignsApprovedSchemeAndConsumesToken() {
        AssignWorkflowResponse preview = directAssigner.assign(bugsOnly(true));
        assertEquals(AssignStatus.WOULD_ASSIGN, preview.getStatus());
        assertNotNull(preview.getPlanId());
        assertEquals(AssignVerdict.MIGRATE, preview.getIssueTypes().get(0).getVerdict());
        assertEquals(3, preview.getIssueTypes().get(0).getIssues());
        verifyNoInteractions(schemeWriter, migrator, lock);

        AssignWorkflowResponse assigned = directAssigner.assign(apply(preview));
        assertEquals(AssignStatus.ASSIGNED, assigned.getStatus());
        assertEquals(preview.getScheme().getId(), assigned.getScheme().getId());
        InOrder order = inOrder(lock, migrator, schemeWriter);
        order.verify(lock).run(eq(WorkflowAssignerImpl.LOCK_NAME), anyString(), any());
        order.verify(migrator).migrate(eq(alpha), eq(Collections.singletonMap("10001", "jira")), eq(target),
                eq(Collections.emptyMap()), any());
        order.verify(schemeWriter).update(eq(own), any());
        order.verify(migrator).migrate(eq(alpha), eq(Collections.singletonMap("10001", "jira")), eq(target),
                eq(Collections.emptyMap()), any());
        assertEquals(AssignErrorCode.PREVIEW_STALE, directAssigner.assign(apply(preview)).getErrorCode());
        verify(schemeWriter).update(eq(own), any());
        verify(migrator, times(2)).migrate(any(), any(), any(), any(), any());
    }

    @Test
    public void directForeignTokenDoesNotWriteOrConsumeOwnersPlan() {
        journalFixture.currentActor.set(user);
        when(user.isActive()).thenReturn(true);
        AssignWorkflowResponse preview = directAssigner.assign(bugsOnly(true));
        when(user.getKey()).thenReturn("other-admin");
        assertEquals(AssignErrorCode.PREVIEW_STALE, directAssigner.assign(apply(preview)).getErrorCode());
        verifyNoInteractions(schemeWriter, migrator);
        when(user.getKey()).thenReturn("admin");
        assertEquals(AssignStatus.ASSIGNED, directAssigner.assign(apply(preview)).getStatus());
    }

    @Test
    public void directExpiredTokenDoesNotWrite() {
        AssignWorkflowResponse preview = directAssigner.assign(bugsOnly(true));
        ActionPlanStore.Plan plan = storedPlans.get(preview.getPlanId());
        storedPlans.put(plan.getId(), new ActionPlanStore.Plan(plan.getId(), "assign_workflow", "admin",
                plan.getRequest(), plan.getFingerprint(), 0));
        assertEquals(AssignErrorCode.PREVIEW_STALE, directAssigner.assign(apply(preview)).getErrorCode());
        verifyNoInteractions(schemeWriter, migrator);
    }

    @Test
    public void directChangedSchemeRejectsAndConsumesPlanWithoutWrites() {
        AssignWorkflowResponse preview = directAssigner.assign(bugsOnly(true));
        AssignableWorkflowScheme replacement = scheme(99L, "Replacement", "jira");
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenReturn(replacement);
        assertEquals(AssignErrorCode.PREVIEW_STALE, directAssigner.assign(apply(preview)).getErrorCode());
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenReturn(own);
        assertEquals(AssignErrorCode.PREVIEW_STALE, directAssigner.assign(apply(preview)).getErrorCode());
        verifyNoInteractions(schemeWriter, migrator);
    }

    @Test
    public void directApplyNeverWritesSchemeFromPlanningAfterFingerprintCheck() {
        AssignWorkflowResponse preview = directAssigner.assign(bugsOnly(true));
        // The checked plan sees the approved scheme; a later planning pass would see another native identity.
        AssignableWorkflowScheme replacement = scheme(99L, "Replacement", "other-workflow");
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenReturn(own, replacement);
        AssignWorkflowResponse assigned = directAssigner.assign(apply(preview));
        assertEquals(AssignStatus.ASSIGNED, assigned.getStatus());
        ArgumentCaptor<AssignableWorkflowScheme> written = ArgumentCaptor.forClass(AssignableWorkflowScheme.class);
        verify(schemeWriter).update(written.capture(), any());
        assertEquals(preview.getScheme().getId(), written.getValue().getId());
        assertEquals(preview.getScheme().getId(), assigned.getScheme().getId());
        verify(migrator, times(2)).migrate(eq(alpha), eq(Collections.singletonMap("10001", "jira")), eq(target),
                eq(Collections.emptyMap()), any());
    }

    @Test
    public void directApplyPlansInsideTheLockCallback() {
        AssignWorkflowResponse preview = directAssigner.assign(bugsOnly(true));
        boolean[] insideLock = {false};
        when(lock.run(eq(WorkflowAssignerImpl.LOCK_NAME), anyString(), any())).thenAnswer(call -> {
            insideLock[0] = true;
            try { return ((Supplier<?>) call.getArgument(2)).get(); }
            finally { insideLock[0] = false; }
        });
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenAnswer(call -> {
            assertTrue("Apply must plan the scheme inside the lock callback", insideLock[0]);
            return own;
        });
        assertEquals(AssignStatus.ASSIGNED, directAssigner.assign(apply(preview)).getStatus());
        verify(schemeWriter).update(eq(own), any());
    }

    private static AssignWorkflowRequest apply(AssignWorkflowResponse preview) {
        return new AssignWorkflowRequest(false, null, null, false, null, false, preview.getPlanId());
    }

}
