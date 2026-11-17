package ru.rasulov.workflow.impl.assign;

import com.atlassian.cache.Cache;
import com.atlassian.cache.CacheManager;
import com.atlassian.cache.CacheSettings;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.config.StatusManager;
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
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.assign.SchemeView;
import ru.rasulov.workflow.api.dto.clone.CloneErrorCode;
import ru.rasulov.workflow.api.dto.clone.CloneScope;
import ru.rasulov.workflow.api.dto.clone.CloneStatus;
import ru.rasulov.workflow.api.dto.clone.CloneVerdict;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsRequest;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsResponse;
import ru.rasulov.workflow.impl.WorkflowWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
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

/** Order of the writes, what is undone when one fails, and the shape of the answers. */
public class WorkflowClonerImplTest {

    private final ApplicationUser user = mock(ApplicationUser.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final ProjectManager projectManager = mock(ProjectManager.class);
    private final IssueStatusCounter counter = mock(IssueStatusCounter.class);
    private final ClonePlanner planner = mock(ClonePlanner.class);
    private final WorkflowWriter workflowWriter = mock(WorkflowWriter.class);
    private final SchemeWriter schemeWriter = mock(SchemeWriter.class);
    private final AssignmentVerifier verifier = mock(AssignmentVerifier.class);
    private final IssueMigrator migrator = mock(IssueMigrator.class);

    private final Project alpha = project(1L, "ABC", "Alpha", "business", issueType("10001", "Bug"));
    private final JiraWorkflow flowA = workflow("Flow A", status("1", "Open"));
    private final JiraWorkflow bugFlow = workflow("Bug flow", status("1", "Open"), status("2", "Done"));
    private final JiraWorkflow flowACopy = workflow("Flow A v2", status("1", "Open"));
    private final JiraWorkflow bugFlowCopy = workflow("Bug flow v2", status("1", "Open"), status("2", "Done"));
    private final AssignableWorkflowScheme shared = scheme(10100L, "Shared scheme", "Flow A");
    private final AssignableWorkflowScheme createdScheme = scheme(10500L, "Shared scheme v2", "Flow A v2");
    private WorkflowClonerImpl cloner;
    private WorkflowClonerImpl directCloner;
    private final WorkflowManager workflowManager = mock(WorkflowManager.class);
    private final WorkflowSchemeManager schemeManager = mock(WorkflowSchemeManager.class);
    private final AssignableWorkflowScheme directScheme = scheme(10100L, "Shared scheme", "Flow A",
            Collections.singletonMap("10001", "Bug flow"));
    private final Map<String, ActionPlanStore.Plan> storedPlans = new HashMap<>();
    private final com.atlassian.jira.ofbiz.OfBizDelegator delegator =
            mock(com.atlassian.jira.ofbiz.OfBizDelegator.class);
    private final org.ofbiz.core.entity.GenericValue sourceRow = mock(org.ofbiz.core.entity.GenericValue.class);

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        journalFixture = ru.rasulov.common.impl.undo.UndoTestFixture.create("admin");
        when(user.getKey()).thenReturn("admin");
        when(user.getUsername()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(user);
        when(lock.run(anyString(), anyString(), any())).thenAnswer(invocation ->
                ((Supplier<Object>) invocation.getArgument(2)).get());
        when(projectManager.getProjectByCurrentKeyIgnoreCase("ABC")).thenReturn(alpha);
        when(projectManager.getProjects()).thenReturn(Collections.singletonList(alpha));
        when(counter.count(alpha)).thenReturn(new IssueUsage());
        when(planner.plan(any(), any(), any())).thenReturn(cloning(SchemeAction.CREATE));
        when(workflowWriter.copy(any(), eq(flowA), eq("Flow A v2"))).thenReturn(flowACopy);
        when(workflowWriter.copy(any(), eq(bugFlow), eq("Bug flow v2"))).thenReturn(bugFlowCopy);
        when(schemeWriter.create(any(), anyString(), any(), any())).thenReturn(createdScheme);
        when(migrator.migrateByWorkflow(any(), any(), any(), any())).thenReturn(Collections.<String>emptyList());
        when(flowA.getDescriptor()).thenReturn(descriptor("1"));
        when(bugFlow.getDescriptor()).thenReturn(descriptor("1"));
        when(sourceRow.getLong("id")).thenReturn(10L);
        when(delegator.findByCondition(eq("Workflow"), any(), any(), any()))
                .thenReturn(Collections.singletonList(sourceRow));
        cloner = new WorkflowClonerImpl(guard, lock, new ProjectResolver(projectManager), counter, planner,
                workflowWriter, schemeWriter, verifier, migrator, new ru.rasulov.common.impl.PreviewPlanExecutor(
                        ru.rasulov.common.impl.InMemoryActionPlans.create(), new ru.rasulov.common.impl.ActionPlanCodec()),
                new ru.rasulov.common.impl.ActionPlanCodec(), journalFixture.journal, delegator) {
            @Override
            public CloneWorkflowsResponse clone(CloneWorkflowsRequest request) {
                return ru.rasulov.common.impl.LegacyWriteCalls.clone(request, super::clone);
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
        Project beta = project(2L, "BBB", "Beta", "business", issueType("10001", "Bug"), issueType("10002", "Task"));
        Project gamma = project(3L, "GGG", "Gamma", "business", issueType("10001", "Bug"), issueType("10002", "Task"));
        when(projectManager.getProjects()).thenReturn(Arrays.asList(alpha, beta, gamma));
        when(schemeManager.getWorkflowSchemeObj(any(Project.class))).thenReturn(directScheme);
        when(schemeManager.getProjectsUsing(any(AssignableWorkflowScheme.class))).thenReturn(Arrays.asList(alpha, beta, gamma));
        when(schemeManager.getAssignableSchemes()).thenReturn(Collections.singletonList(directScheme));
        AssignableWorkflowScheme systemDefault = scheme(0L, "Default Workflow Scheme", "jira");
        when(schemeManager.getDefaultWorkflowScheme()).thenReturn(systemDefault);
        when(workflowManager.getWorkflow("Flow A")).thenReturn(flowA);
        when(workflowManager.getWorkflow("Bug flow")).thenReturn(bugFlow);
        when(workflowManager.getWorkflows()).thenReturn(Arrays.asList(flowA, bugFlow));
        when(counter.count(alpha)).thenReturn(AssignMocks.usage("10001", "1", "3"));
        ClonePlanner realPlanner = new ClonePlanner(schemeManager, workflowManager, projectManager,
                new AssignmentPlanner(schemeManager, mock(IssueTypeManager.class), mock(StatusManager.class)));
        directCloner = new WorkflowClonerImpl(guard, lock, new ProjectResolver(projectManager), counter, realPlanner,
                workflowWriter, schemeWriter, verifier, migrator,
                new ru.rasulov.common.impl.PreviewPlanExecutor(new ActionPlanStore(caches), new ActionPlanCodec()), new ActionPlanCodec(),
                journalFixture.journal, delegator);
    }

    private ru.rasulov.common.impl.undo.UndoTestFixture journalFixture;
    @org.junit.After
    public void closeJournal() { journalFixture.close(); }

    @Test
    public void explicitModeAndPreviewTokenAreRequiredByTheService() {
        try {
            cloner.clone(new CloneWorkflowsRequest(null, null, null, null, null, null, null));
            fail("missing dryRun must be rejected");
        } catch (InvalidRequestException expected) {
            assertTrue(expected.getMessage().contains("dryRun"));
        }
        CloneWorkflowsResponse stale = cloner.clone(
                new CloneWorkflowsRequest(false, null, null, null, null, null, "missing"));
        assertEquals(CloneErrorCode.PREVIEW_STALE, stale.getErrorCode());
    }

    /** Both workflows get a copy; Bug's issues leave "Bug flow"; the scheme is copied or (UPDATE) the own one changed. */
    private ClonePlan cloning(SchemeAction action) {
        List<ClonePlan.PlannedWorkflow> workflows = Arrays.asList(
                new ClonePlan.PlannedWorkflow("Flow A", flowA, "Flow A v2", CloneVerdict.COPY, 2,
                        Arrays.asList("Beta", "Gamma"), false),
                new ClonePlan.PlannedWorkflow("Bug flow", bugFlow, "Bug flow v2", CloneVerdict.COPY, 2,
                        Arrays.asList("Beta", "Gamma"), false));
        Map<String, String> replacements = new LinkedHashMap<String, String>();
        replacements.put("Flow A", "Flow A v2");
        replacements.put("Bug flow", "Bug flow v2");
        return new ClonePlan(CloneScope.ALL_WORKFLOWS, action, shared,
                new SchemeView(10100L, "Shared scheme", false, false, 2, Arrays.asList("Beta", "Gamma")),
                action == SchemeAction.CREATE ? "Shared scheme v2" : null,
                SchemeEdit.replacing(replacements, "Flow A"), workflows,
                Collections.singletonMap("10001", "Bug flow"), Collections.singletonList("A warning."));
    }

    private static CloneWorkflowsRequest request(boolean dryRun) {
        return new CloneWorkflowsRequest(dryRun, "ABC", CloneScope.ALL_WORKFLOWS, null, null,
                Collections.<String, String>emptyMap());
    }

    // --- who and what --------------------------------------------------------------------------------------------

    @Test
    public void anAnonymousCallerOrANonAdministratorIsRefusedBeforeAnythingIsRead() {
        when(guard.requireAdministrator()).thenThrow(new AccessException("Authentication is required.", true));

        try {
            cloner.clone(request(false));
            fail("expected AccessException");
        } catch (AccessException expected) {
            // ok
        }
        verifyNoInteractions(planner, workflowWriter, schemeWriter, migrator, lock);
    }

    @Test
    public void anEmptyRequestIsAnInvalidRequest() {
        try {
            cloner.clone(null);
            fail("expected InvalidRequestException");
        } catch (InvalidRequestException expected) {
            // ok
        }
    }

    // --- dry run -------------------------------------------------------------------------------------------------

    @Test
    public void aDryRunReportsThePlanWritesNothingAndDoesNotWaitForTheLock() {
        CloneWorkflowsResponse response = cloner.clone(request(true));

        assertEquals(CloneStatus.WOULD_CLONE, response.getStatus());
        assertTrue(response.isDryRun());
        assertEquals("ABC", response.getProject().getKey());
        assertEquals(SchemeAction.CREATE, response.getScheme().getAction());
        assertNull("a scheme a dry run would create has no id", response.getScheme().getId());
        assertEquals("Shared scheme v2", response.getScheme().getName());
        assertEquals("Shared scheme", response.getCurrentScheme().getName());
        assertEquals(Arrays.asList("Flow A v2", "Bug flow v2"),
                Arrays.asList(response.getWorkflows().get(0).getNewName(), response.getWorkflows().get(1).getNewName()));
        assertEquals("A warning.", response.getMessage());
        verifyNoInteractions(workflowWriter, schemeWriter, migrator, lock);
    }

    @Test
    public void whenNothingIsSharedTheAnswerIsAlreadySeparateAndNothingIsWritten() {
        ClonePlan none = new ClonePlan(CloneScope.SCHEME_ONLY, SchemeAction.NONE, shared,
                new SchemeView(10100L, "Shared scheme", false, false, 0, Collections.<String>emptyList()), null, null,
                Collections.<ClonePlan.PlannedWorkflow>emptyList(), Collections.<String, String>emptyMap(),
                Collections.<String>emptyList());
        when(planner.plan(any(), any(), any())).thenReturn(none);

        CloneWorkflowsResponse response = cloner.clone(request(false));

        assertEquals(CloneStatus.ALREADY_SEPARATE, response.getStatus());
        assertEquals(SchemeAction.NONE, response.getScheme().getAction());
        verifyNoInteractions(workflowWriter, schemeWriter, migrator);
    }

    // --- writing -------------------------------------------------------------------------------------------------

    @Test
    public void aRealCloneCopiesTheWorkflowsThenTheSchemeThenMovesTheIssuesThenAssignsUnderTheLock() {
        CloneWorkflowsResponse response = cloner.clone(request(false));

        assertEquals(response.getMessage(), CloneStatus.CLONED, response.getStatus());
        assertEquals(SchemeAction.CREATE, response.getScheme().getAction());
        assertEquals(Long.valueOf(10500), response.getScheme().getId());
        assertEquals("Shared scheme v2", response.getScheme().getName());
        InOrder order = inOrder(lock, workflowWriter, schemeWriter, migrator);
        order.verify(lock).run(eq(WorkflowAssignerImpl.LOCK_NAME), anyString(), any());
        order.verify(workflowWriter).copy(user, flowA, "Flow A v2");
        order.verify(workflowWriter).copy(user, bugFlow, "Bug flow v2");
        order.verify(schemeWriter).create(eq(user), eq("Shared scheme v2"), eq(shared), any());
        Map<String, JiraWorkflow> copies = new LinkedHashMap<String, JiraWorkflow>();
        copies.put("Flow A", flowACopy);
        copies.put("Bug flow", bugFlowCopy);
        order.verify(migrator).migrateByWorkflow(eq(alpha), eq(Collections.singletonMap("10001", "Bug flow")),
                eq(copies), any());
        order.verify(schemeWriter).assign(alpha, createdScheme);
        verify(schemeWriter, never()).update(any(), any());
    }

    @Test
    public void aCloneJiraDoesNotReportAsExpectedIsPartiallyClonedAndIsNotRepaired() {
        when(verifier.verify(eq(alpha), any())).thenReturn("Jira does not report the assignment as expected: "
                + "workflow 'Bug flow v2' is reported inactive.");

        CloneWorkflowsResponse response = cloner.clone(request(false));

        assertEquals(CloneStatus.PARTIALLY_CLONED, response.getStatus());
        assertTrue(response.getMessage(), response.getMessage().contains("reported inactive"));
        verify(schemeWriter, times(1)).assign(alpha, createdScheme);
    }

    @Test
    public void anOwnSchemeIsChangedInPlaceAndNoSchemeIsCreatedOrAssigned() {
        when(planner.plan(any(), any(), any())).thenReturn(cloning(SchemeAction.UPDATE));

        CloneWorkflowsResponse response = cloner.clone(request(false));

        assertEquals(CloneStatus.CLONED, response.getStatus());
        assertEquals(SchemeAction.UPDATE, response.getScheme().getAction());
        assertEquals(Long.valueOf(10100), response.getScheme().getId());
        InOrder order = inOrder(workflowWriter, migrator, schemeWriter);
        order.verify(workflowWriter).copy(user, bugFlow, "Bug flow v2");
        order.verify(migrator).migrateByWorkflow(any(), any(), any(), any());
        order.verify(schemeWriter).update(eq(shared), any());
        verify(schemeWriter, never()).create(any(), anyString(), any(), any());
        verify(schemeWriter, never()).assign(any(), any());
    }

    @Test
    public void issuesCreatedWhileTheCallRunsAreMovedByTheFinalPassAndReported() {
        when(migrator.migrateByWorkflow(any(), any(), any(), any())).thenReturn(Collections.<String>emptyList())
                .thenAnswer(invocation -> {
                    MigrationCount moved = invocation.getArgument(3);
                    moved.increment();
                    moved.increment();
                    return Collections.<String>emptyList();
                });

        CloneWorkflowsResponse response = cloner.clone(request(false));

        assertEquals(CloneStatus.CLONED, response.getStatus());
        assertTrue(response.getMessage(), response.getMessage().contains("2 issue(s) created while the call was running"));
    }


    private void verifyNoInteractionsWithIssues() {
        verify(migrator, never()).migrateByWorkflow(any(), any(), any(), any());
    }

    // --- the request ---------------------------------------------------------------------------------------------

    @Test
    public void aRequestThatDoesNotMakeSenseIsRefusedBeforeThePlan() {
        List<CloneWorkflowsRequest> bad = Arrays.asList(
                new CloneWorkflowsRequest(false, "ABC", null, null, null, null),
                new CloneWorkflowsRequest(false, "ABC", CloneScope.SELECTED, null, null, null),
                new CloneWorkflowsRequest(false, "ABC", CloneScope.ALL_WORKFLOWS, Arrays.asList("Bug flow"), null, null),
                new CloneWorkflowsRequest(false, "ABC", CloneScope.SELECTED, Arrays.asList("  "), null, null),
                new CloneWorkflowsRequest(false, "ABC", CloneScope.SCHEME_ONLY, null, "   ", null),
                new CloneWorkflowsRequest(false, "ABC", CloneScope.ALL_WORKFLOWS, null, null,
                        Collections.singletonMap("Bug flow", " ")),
                new CloneWorkflowsRequest(false, "ABC", CloneScope.ALL_WORKFLOWS, null, null, twoCopiesOneName()),
                new CloneWorkflowsRequest(false, "ABC", CloneScope.SCHEME_ONLY, null,
                        new String(new char[256]).replace('\0', 'x'), null));
        for (CloneWorkflowsRequest request : bad) {
            CloneWorkflowsResponse response = cloner.clone(request);
            assertEquals(CloneStatus.FAILED, response.getStatus());
            assertEquals(CloneErrorCode.VALIDATION_ERROR, response.getErrorCode());
        }
        verifyNoInteractions(planner, workflowWriter, schemeWriter, migrator);
    }

    private static Map<String, String> twoCopiesOneName() {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("Bug flow", "Same");
        names.put("Flow A", "SAME");
        return names;
    }

    @Test
    public void tooManyWorkflowsInARequestAreRefused() {
        List<String> many = new ArrayList<String>();
        for (int i = 0; i <= ru.rasulov.workflow.api.WorkflowAssignLimits.MAX_WORKFLOWS_TO_CLONE; i++) {
            many.add("W" + i);
        }

        CloneWorkflowsResponse response = cloner.clone(new CloneWorkflowsRequest(false, "ABC", CloneScope.SELECTED,
                many, null, null));

        assertEquals(CloneErrorCode.VALIDATION_ERROR, response.getErrorCode());
    }

    @Test
    public void anUnknownProjectAndARefusalOfThePlanAreFailedResultsWithTheirSuggestions() {
        CloneWorkflowsResponse unknown = cloner.clone(new CloneWorkflowsRequest(true, "NOPE", CloneScope.SCHEME_ONLY,
                null, null, null));
        assertEquals(CloneErrorCode.UNKNOWN_PROJECT, unknown.getErrorCode());
        assertNull(unknown.getProject());

        when(planner.plan(any(), any(), any())).thenThrow(new CloneFailureException(CloneErrorCode.DRAFT_EXISTS,
                "The scheme has a draft.", Arrays.asList("x")));
        CloneWorkflowsResponse refused = cloner.clone(request(true));
        assertEquals(CloneStatus.FAILED, refused.getStatus());
        assertEquals(CloneErrorCode.DRAFT_EXISTS, refused.getErrorCode());
        assertEquals(Arrays.asList("x"), refused.getSuggestions());
        assertEquals("the project is known even though the plan refused", "ABC", refused.getProject().getKey());
        verifyNoInteractions(workflowWriter, schemeWriter, migrator);
    }

    @Test
    public void directPreviewApplyClonesApprovedWorkflowsAndConsumesToken() {
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        assertEquals(CloneStatus.WOULD_CLONE, preview.getStatus());
        assertTrue(preview.getPlanId() != null);
        assertEquals(Arrays.asList("Flow A v2", "Bug flow v2"),
                Arrays.asList(preview.getWorkflows().get(0).getNewName(), preview.getWorkflows().get(1).getNewName()));
        verifyNoInteractions(workflowWriter, schemeWriter, migrator, lock);

        CloneWorkflowsResponse cloned = directCloner.clone(apply(preview));
        assertEquals(CloneStatus.CLONED, cloned.getStatus());
        assertEquals(Long.valueOf(10500), cloned.getScheme().getId());
        InOrder order = inOrder(lock, workflowWriter, schemeWriter, migrator);
        order.verify(lock).run(eq(WorkflowAssignerImpl.LOCK_NAME), anyString(), any());
        order.verify(workflowWriter).copy(user, flowA, "Flow A v2");
        order.verify(workflowWriter).copy(user, bugFlow, "Bug flow v2");
        order.verify(schemeWriter).create(eq(user), eq("Shared scheme v2"), eq(directScheme), any());
        order.verify(migrator).migrateByWorkflow(eq(alpha), eq(Collections.singletonMap("10001", "Bug flow")),
                eq(expectedCopies()), any());
        order.verify(schemeWriter).assign(alpha, createdScheme);
        order.verify(migrator).migrateByWorkflow(eq(alpha), eq(Collections.singletonMap("10001", "Bug flow")),
                eq(expectedCopies()), any());
        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());
        verify(workflowWriter).copy(user, flowA, "Flow A v2");
        verify(workflowWriter).copy(user, bugFlow, "Bug flow v2");
        verify(schemeWriter).create(any(), anyString(), any(), any());
        verify(migrator, times(2)).migrateByWorkflow(any(), any(), any(), any());
    }

    @Test
    public void directForeignTokenDoesNotWriteOrConsumeOwnersPlan() {
        journalFixture.currentActor.set(user);
        when(user.isActive()).thenReturn(true);
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        when(user.getKey()).thenReturn("other-admin");
        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());
        verifyNoInteractions(workflowWriter, schemeWriter, migrator);
        when(user.getKey()).thenReturn("admin");
        assertEquals(CloneStatus.CLONED, directCloner.clone(apply(preview)).getStatus());
    }

    @Test
    public void directExpiredTokenDoesNotWrite() {
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        ActionPlanStore.Plan plan = storedPlans.get(preview.getPlanId());
        storedPlans.put(plan.getId(), new ActionPlanStore.Plan(plan.getId(), "clone_project_workflows", "admin",
                plan.getRequest(), plan.getFingerprint(), 0));
        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());
        verifyNoInteractions(workflowWriter, schemeWriter, migrator);
    }

    @Test
    public void workflowNamesDuplicateSourcesRefuseWithoutCopiesOrPlanToken() {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put("Flow A", "Copy A");
        names.put("flow a", "Copy B");
        CloneWorkflowsResponse response = directCloner.clone(new CloneWorkflowsRequest(true, "ABC",
                CloneScope.ALL_WORKFLOWS, null, null, names));

        assertEquals(CloneStatus.FAILED, response.getStatus());
        assertEquals(CloneErrorCode.VALIDATION_ERROR, response.getErrorCode());
        assertTrue(response.getMessage(), response.getMessage().contains("'Flow A'"));
        assertTrue(response.getMessage(), response.getMessage().contains("'flow a'"));
        assertNull(response.getPlanId());
        verifyNoInteractions(workflowWriter, schemeWriter, migrator);
    }

    @Test
    public void directChangedSchemeRejectsAndConsumesPlanWithoutWrites() {
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        AssignableWorkflowScheme replacement = scheme(99L, "Replacement", "Flow A",
                Collections.singletonMap("10001", "Bug flow"));
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenReturn(replacement);
        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenReturn(directScheme);
        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());
        verifyNoInteractions(workflowWriter, schemeWriter, migrator);
    }

    @Test
    public void directApplyNeverCopiesSchemeFromPlanningAfterFingerprintCheck() {
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        AssignableWorkflowScheme replacement = scheme(99L, "Replacement", "Flow A",
                Collections.singletonMap("10001", "Bug flow"));
        // The checked plan still sees the approved scheme; a later planning pass would see another native identity.
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenReturn(directScheme, replacement);
        CloneWorkflowsResponse cloned = directCloner.clone(apply(preview));
        assertEquals(CloneStatus.CLONED, cloned.getStatus());
        ArgumentCaptor<AssignableWorkflowScheme> copied = ArgumentCaptor.forClass(AssignableWorkflowScheme.class);
        verify(schemeWriter).create(eq(user), eq("Shared scheme v2"), copied.capture(), any());
        assertEquals(preview.getCurrentScheme().getId(), copied.getValue().getId());
        assertEquals(preview.getCurrentScheme().getId(), cloned.getCurrentScheme().getId());
    }

    @Test
    public void directApplyNeverCopiesWorkflowFromResolveAfterFingerprintCheck() {
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        JiraWorkflow replacement = workflow("Flow A", status("99", "Open"));
        JiraWorkflow replacementCopy = workflow("Flow A v2", status("99", "Open"));
        // A subsequent resolve of the same workflow name would now return different linked status IDs.
        when(workflowManager.getWorkflow("Flow A")).thenReturn(flowA, replacement);
        when(workflowWriter.copy(user, replacement, "Flow A v2")).thenReturn(replacementCopy);
        CloneWorkflowsResponse cloned = directCloner.clone(apply(preview));
        assertEquals(CloneStatus.CLONED, cloned.getStatus());
        ArgumentCaptor<JiraWorkflow> copied = ArgumentCaptor.forClass(JiraWorkflow.class);
        verify(workflowWriter).copy(eq(user), copied.capture(), eq("Flow A v2"));
        assertEquals(Collections.singleton("1"), copied.getValue().getLinkedStatusIds());
        verify(migrator, times(2)).migrateByWorkflow(eq(alpha), eq(Collections.singletonMap("10001", "Bug flow")),
                eq(expectedCopies()), any());
    }

    @Test
    public void directChangedPostFunctionRejectsAndConsumesPlanWithoutWrites() throws Exception {
        when(flowA.getDescriptor()).thenReturn(descriptor("1"));
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        when(flowA.getDescriptor()).thenReturn(descriptor("2"));
        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());
        when(flowA.getDescriptor()).thenReturn(descriptor("1"));
        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());
        verifyNoInteractions(workflowWriter, schemeWriter, migrator);
    }

    @Test
    public void directReplacedNativeIdentityRejectsWithoutWrites() {
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        when(sourceRow.getLong("id")).thenReturn(99L);
        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());
        verifyNoInteractions(workflowWriter, schemeWriter, migrator);
    }

    @Test
    public void directUnchangedSystemWorkflowWithoutNativeRowCanBeCopied() throws Exception {
        when(flowA.isSystemWorkflow()).thenReturn(true);
        when(delegator.findByCondition(eq("Workflow"), any(), any(), any())).thenAnswer(call -> {
            org.ofbiz.core.entity.EntityFieldMap where = call.getArgument(1);
            return "Flow A".equals(where.getField("name"))
                    ? Collections.emptyList() : Collections.singletonList(sourceRow);
        });
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        assertEquals(CloneStatus.CLONED, directCloner.clone(apply(preview)).getStatus());
        verify(workflowWriter).copy(user, flowA, "Flow A v2");
    }

    @Test
    public void directChangedPostFunctionAtCopyBoundaryRejectsWithoutWrites() throws Exception {
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        // The apply plan is unchanged, but the source descriptor changes before its copy.
        when(flowA.getDescriptor()).thenReturn(descriptor("1"), descriptor("2"));
        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());
        verifyNoInteractions(workflowWriter, schemeWriter, migrator);
    }

    @Test
    public void directChangedSecondSourceRetainsFirstCopyAndDoesNotMigrate() throws Exception {
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        when(bugFlow.getDescriptor()).thenReturn(descriptor("1"), descriptor("2"));
        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());
        verify(workflowWriter).copy(user, flowA, "Flow A v2");
        verify(workflowWriter, never()).copy(user, bugFlow, "Bug flow v2");
        verifyNoInteractions(schemeWriter, migrator);
    }

    // --- a draft of a workflow to copy is a warning, not a refusal ------------------------------------------------

    @Test
    public void aDraftOfASourceWorkflowGivesAWarningAndTheApplyCopiesTheMainVersionAndLeavesTheDraftAlone() {
        JiraWorkflow draftOfA = workflow("Flow A", status("1", "Open"), status("3", "Draft only"));
        when(workflowManager.getDraftWorkflow("Flow A")).thenReturn(draftOfA);

        CloneWorkflowsResponse preview = directCloner.clone(directRequest());

        assertEquals(preview.getMessage(), CloneStatus.WOULD_CLONE, preview.getStatus());
        assertTrue(preview.getMessage(), preview.getMessage().contains("Flow A"));
        assertTrue(preview.getMessage(), preview.getMessage().contains("publish_workflow_draft"));
        assertTrue(preview.getPlanId() != null);

        CloneWorkflowsResponse cloned = directCloner.clone(apply(preview));

        assertEquals(cloned.getMessage(), CloneStatus.CLONED, cloned.getStatus());
        assertTrue(cloned.getMessage(), cloned.getMessage().contains("publish_workflow_draft"));
        verify(workflowWriter).copy(user, flowA, "Flow A v2");
        verify(workflowWriter, never()).copy(eq(user), eq(draftOfA), anyString());
        for (org.mockito.invocation.Invocation call : org.mockito.Mockito.mockingDetails(workflowManager).getInvocations()) {
            assertTrue("the draft is only looked at, never written: " + call.getMethod().getName(),
                    Arrays.asList("getWorkflow", "getWorkflows", "getDraftWorkflow").contains(call.getMethod().getName()));
        }
    }

    @Test
    public void aDraftThatAppearsAfterThePreviewMakesTheApplyStaleAndWritesNothing() {
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        assertEquals(CloneStatus.WOULD_CLONE, preview.getStatus());
        JiraWorkflow draft = workflow("Flow A", status("1", "Open"));
        when(workflowManager.getDraftWorkflow("Flow A")).thenReturn(draft);

        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());

        verifyNoInteractions(workflowWriter, schemeWriter, migrator);
        CloneWorkflowsResponse again = directCloner.clone(directRequest());
        assertEquals(again.getMessage(), CloneStatus.WOULD_CLONE, again.getStatus());
        assertTrue(again.getMessage(), again.getMessage().contains("Flow A"));
    }

    @Test
    public void aDraftThatDisappearsAfterThePreviewMakesTheApplyStaleAndWritesNothing() {
        JiraWorkflow draft = workflow("Flow A", status("1", "Open"));
        when(workflowManager.getDraftWorkflow("Flow A")).thenReturn(draft);
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        assertEquals(preview.getMessage(), CloneStatus.WOULD_CLONE, preview.getStatus());
        when(workflowManager.getDraftWorkflow("Flow A")).thenReturn(null);

        assertEquals(CloneErrorCode.PREVIEW_STALE, directCloner.clone(apply(preview)).getErrorCode());

        verifyNoInteractions(workflowWriter, schemeWriter, migrator);
    }

    @Test
    public void aDraftThatIsReplacedByAnotherOneAfterThePreviewDoesNotMakeTheApplyStale() {
        JiraWorkflow draft = workflow("Flow A", status("1", "Open"));
        JiraWorkflow replacement = workflow("Flow A", status("1", "Open"), status("2", "Done"));
        when(workflowManager.getDraftWorkflow("Flow A")).thenReturn(draft);
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        assertEquals(preview.getMessage(), CloneStatus.WOULD_CLONE, preview.getStatus());
        when(workflowManager.getDraftWorkflow("Flow A")).thenReturn(replacement);

        CloneWorkflowsResponse cloned = directCloner.clone(apply(preview));

        assertEquals(cloned.getMessage(), CloneStatus.CLONED, cloned.getStatus());
        verify(workflowWriter).copy(user, flowA, "Flow A v2");
        verify(workflowWriter).copy(user, bugFlow, "Bug flow v2");
    }

    private static com.opensymphony.workflow.loader.WorkflowDescriptor descriptor(String eventId) throws Exception {
        return com.atlassian.jira.workflow.WorkflowUtil.convertXMLtoWorkflowDescriptor(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE workflow PUBLIC \"-//OpenSymphony Group//DTD OSWorkflow 2.8//EN\" "
                + "\"http://www.opensymphony.com/osworkflow/workflow_2_8.dtd\">"
                + "<workflow><initial-actions><action id=\"1\" name=\"Create\"><results>"
                + "<unconditional-result old-status=\"null\" status=\"open\" step=\"1\"><post-functions>"
                + "<function type=\"class\"><arg name=\"class.name\">"
                + "com.atlassian.jira.workflow.function.event.FireIssueEventFunction</arg>"
                + "<arg name=\"eventTypeId\">" + eventId + "</arg></function>"
                + "</post-functions></unconditional-result></results></action></initial-actions>"
                + "<steps><step id=\"1\" name=\"Open\"><meta name=\"jira.status.id\">1</meta></step></steps></workflow>");
    }

    private static CloneWorkflowsRequest directRequest() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("Flow A", "Flow A v2");
        names.put("Bug flow", "Bug flow v2");
        return new CloneWorkflowsRequest(true, "ABC", CloneScope.ALL_WORKFLOWS, null, "Shared scheme v2", names);
    }

    private static CloneWorkflowsRequest apply(CloneWorkflowsResponse preview) {
        return new CloneWorkflowsRequest(false, null, null, null, null, null, preview.getPlanId());
    }

    @Test
    public void directApplyPlansInsideTheLockCallback() {
        CloneWorkflowsResponse preview = directCloner.clone(directRequest());
        boolean[] insideLock = {false};
        when(lock.run(eq(WorkflowAssignerImpl.LOCK_NAME), anyString(), any())).thenAnswer(call -> {
            insideLock[0] = true;
            try { return ((Supplier<?>) call.getArgument(2)).get(); }
            finally { insideLock[0] = false; }
        });
        when(schemeManager.getWorkflowSchemeObj(alpha)).thenAnswer(call -> {
            assertTrue("Apply must plan the scheme inside the lock callback", insideLock[0]);
            return directScheme;
        });
        assertEquals(CloneStatus.CLONED, directCloner.clone(apply(preview)).getStatus());
        verify(workflowWriter).copy(user, flowA, "Flow A v2");
    }

    @Test
    public void aPreviewThatFindsNothingToCopyDoesNotCountTheProjectsIssues() {
        when(schemeManager.getProjectsUsing(any(AssignableWorkflowScheme.class))).thenReturn(Collections.singletonList(alpha));

        CloneWorkflowsResponse response = directCloner.clone(new CloneWorkflowsRequest(true, "ABC",
                CloneScope.SCHEME_ONLY, Collections.<String>emptyList(), null, Collections.<String, String>emptyMap()));

        assertEquals(CloneStatus.ALREADY_SEPARATE, response.getStatus());
        org.mockito.Mockito.verify(counter, org.mockito.Mockito.never()).count(any(Project.class));
    }

    private Map<String, JiraWorkflow> expectedCopies() {
        Map<String, JiraWorkflow> copies = new LinkedHashMap<>();
        copies.put("Flow A", flowACopy);
        copies.put("Bug flow", bugFlowCopy);
        return copies;
    }
}
