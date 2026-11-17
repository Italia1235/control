package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.bc.ServiceOutcome;
import com.atlassian.jira.bc.workflow.WorkflowSchemeService;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import com.opensymphony.workflow.spi.WorkflowEntry;
import com.opensymphony.workflow.spi.WorkflowStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.StatusUsage;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import net.java.ao.Query;
import ru.rasulov.common.impl.undo.JournalStep;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.workflow.api.dto.assign.*;
import ru.rasulov.workflow.api.dto.clone.*;
import ru.rasulov.workflow.impl.WorkflowWriter;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual forward writers and AO/HSQL; native Jira storage and planning are mutable doubles. */
public class WorkflowNativeForwardTest {
    private final WorkflowManager workflows = mock(WorkflowManager.class);
    private final WorkflowSchemeManager schemes = mock(WorkflowSchemeManager.class);
    private final WorkflowSchemeService schemeService = mock(WorkflowSchemeService.class);
    private final ProjectManager projects = mock(ProjectManager.class);
    private final OfBizDelegator delegator = mock(OfBizDelegator.class);
    private final IssueManager issues = mock(IssueManager.class);
    private final WorkflowStore workflowStore = mock(WorkflowStore.class);
    private final Status open = AssignMocks.status("1", "Open");
    private final Project project = AssignMocks.project(1L, "ABC", "Alpha", "business");
    private final Map<String, JiraWorkflow> nativeWorkflows = new LinkedHashMap<String, JiraWorkflow>();
    private final Map<Long, AssignableWorkflowScheme> nativeSchemes = new LinkedHashMap<Long, AssignableWorkflowScheme>();
    private final Map<Long, NativeIssue> nativeIssues = new LinkedHashMap<Long, NativeIssue>();
    private final Map<Long, String> entries = new HashMap<Long, String>();
    private final AtomicLong ids = new AtomicLong(100);
    private long binding = 10;
    private UndoTestFixture fixture;
    private SchemeWriter schemeWriter;
    private final AssignmentVerifier verifier = mock(AssignmentVerifier.class);
    private WorkflowWriter workflowWriter;
    private IssueMigrator migrator;
    private String operation;
    private int issueRowsRead;

    @Before public void setUp() throws Exception {
        nativeWorkflows.put("jira", workflow("jira"));
        when(nativeWorkflows.get("jira").isSystemWorkflow()).thenReturn(true);
        nativeWorkflows.put("Target", workflow("Target"));
        nativeSchemes.put(10L, scheme(10L, "Original", "jira", Collections.<String, String>emptyMap()));
        nativeSchemes.put(0L, scheme(0L, "System default", "jira", Collections.<String, String>emptyMap()));
        when(projects.getProjectObj(1L)).thenReturn(project);
        when(projects.getProjectByCurrentKeyIgnoreCase("ABC")).thenReturn(project);
        when(projects.getProjects()).thenReturn(Collections.singletonList(project));
        when(workflows.getWorkflow(anyString())).thenAnswer(call -> nativeWorkflows.get(call.getArgument(0)));
        when(workflows.getWorkflows()).thenAnswer(call -> new ArrayList<JiraWorkflow>(nativeWorkflows.values()));
        when(workflows.getWorkflowsIncludingDrafts()).thenAnswer(call -> new ArrayList<JiraWorkflow>(nativeWorkflows.values()));
        when(workflows.getStore()).thenReturn(workflowStore);
        when(workflowStore.findEntry(anyLong())).thenAnswer(call -> {
            WorkflowEntry entry = mock(WorkflowEntry.class);
            when(entry.getWorkflowName()).thenReturn(entries.get(call.getArgument(0))); return entry;
        });
        when(workflows.copyWorkflow(any(ApplicationUser.class), anyString(), nullable(String.class), any(JiraWorkflow.class)))
                .thenAnswer(call -> { String name = call.getArgument(1); JiraWorkflow copy = workflow(name); nativeWorkflows.put(name, copy); return copy; });
        when(schemes.getWorkflowSchemeObj(any(Project.class))).thenAnswer(call -> nativeSchemes.get(binding));
        when(schemes.getWorkflowSchemeObj(anyLong())).thenAnswer(call -> nativeSchemes.get((Long) call.getArgument(0)));
        when(schemes.getWorkflowSchemeObj(anyString())).thenAnswer(call -> {
            for (AssignableWorkflowScheme scheme : nativeSchemes.values()) if (scheme.getName().equals(call.getArgument(0))) return scheme; return null;
        });
        when(schemes.getDefaultWorkflowScheme()).thenAnswer(call -> nativeSchemes.get(0L));
        when(schemes.isUsingDefaultScheme(project)).thenAnswer(call -> binding == 0);
        when(schemes.getSchemeObject(anyLong())).thenAnswer(call ->
                new com.atlassian.jira.scheme.Scheme((Long) call.getArgument(0), null, null, Collections.emptyList()));
        doAnswer(call -> { binding = 0; return null; }).when(schemes).removeSchemesFromProject(project);
        doAnswer(call -> { binding = ((com.atlassian.jira.scheme.Scheme) call.getArgument(1)).getId(); return null; })
                .when(schemes).addSchemeToProject(eq(project), any(com.atlassian.jira.scheme.Scheme.class));
        doAnswer(call -> { AssignableWorkflowScheme value = call.getArgument(0); nativeSchemes.put(value.getId(), value); return null; }).when(schemes).updateWorkflowScheme(any(AssignableWorkflowScheme.class));
        when(schemeService.assignableBuilder()).thenAnswer(call -> builder(null, null, null, Collections.<String, String>emptyMap()));
        when(schemeService.createScheme(any(ApplicationUser.class), any(AssignableWorkflowScheme.class))).thenAnswer(call -> {
            AssignableWorkflowScheme wanted = call.getArgument(1);
            AssignableWorkflowScheme saved = scheme(ids.incrementAndGet(), wanted.getName(), wanted.getConfiguredDefaultWorkflow(), wanted.getMappings());
            nativeSchemes.put(saved.getId(), saved); return outcome(saved);
        });
        when(delegator.findByCondition(eq("Issue"), any(), any(), any())).thenAnswer(call -> issueRows((org.ofbiz.core.entity.EntityFieldMap) call.getArgument(1)));
        when(delegator.findListIteratorByCondition(eq("Issue"), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    org.ofbiz.core.entity.EntityCondition condition = call.getArgument(1);
                    if (condition instanceof org.ofbiz.core.entity.EntityConditionList) {
                        org.ofbiz.core.entity.EntityConditionList list = (org.ofbiz.core.entity.EntityConditionList) condition;
                        Object selectedType = null; long after = 0;
                        for (int i = 0; i < list.getConditionListSize(); i++) {
                            org.ofbiz.core.entity.EntityExpr expression = (org.ofbiz.core.entity.EntityExpr) list.getCondition(i);
                            if ("type".equals(expression.getLhs())) selectedType = expression.getRhs();
                            else if ("id".equals(expression.getLhs())) after = ((Number) expression.getRhs()).longValue();
                        }
                        long boundary = after; Object wantedType = selectedType;
                        List<GenericValue> page = issueRows(new org.ofbiz.core.entity.EntityFieldMap(
                                Collections.<String, Object>emptyMap(), org.ofbiz.core.entity.EntityOperator.AND));
                        page.removeIf(row -> row.getLong("id") <= boundary || !Objects.equals(row.getString("type"), wantedType));
                        page.sort(java.util.Comparator.comparing(row -> row.getLong("id")));
                        int limit = ((org.ofbiz.core.entity.EntityFindOptions) call.getArgument(5)).getMaxResults();
                        Iterator<GenericValue> cursor = new ArrayList<GenericValue>(
                                page.subList(0, Math.min(limit, page.size()))).iterator();
                        com.atlassian.jira.ofbiz.OfBizListIterator keyset = mock(com.atlassian.jira.ofbiz.OfBizListIterator.class);
                        when(keyset.next()).thenAnswer(next -> {
                            if (!cursor.hasNext()) return null;
                            issueRowsRead++;
                            return cursor.next();
                        });
                        return keyset;
                    }
                    org.ofbiz.core.entity.EntityFieldMap projectCondition;
                    Collection<String> selected = null;
                    if (condition instanceof org.ofbiz.core.entity.EntityExpr) {
                        org.ofbiz.core.entity.EntityExpr expression = (org.ofbiz.core.entity.EntityExpr) condition;
                        projectCondition = (org.ofbiz.core.entity.EntityFieldMap) expression.getLhs();
                        selected = (Collection<String>) ((org.ofbiz.core.entity.EntityExpr) expression.getRhs()).getRhs();
                    } else {
                        projectCondition = (org.ofbiz.core.entity.EntityFieldMap) condition;
                    }
                    List<GenericValue> matching = issueRows(projectCondition);
                    if (selected != null) {
                        Collection<String> types = selected;
                        matching.removeIf(row -> !types.contains(row.getString("type")));
                    }
                    Iterator<GenericValue> rows = matching.iterator();
                    com.atlassian.jira.ofbiz.OfBizListIterator iterator = mock(com.atlassian.jira.ofbiz.OfBizListIterator.class);
                    when(iterator.next()).thenAnswer(next -> {
                        if (!rows.hasNext()) return null;
                        issueRowsRead++;
                        return rows.next();
                    });
                    return iterator;
                });
        when(delegator.findByCondition(eq("OSCurrentStep"), any(), any(), any())).thenReturn(Collections.<GenericValue>emptyList());
        when(delegator.getDelegatorInterface()).thenReturn(mock(org.ofbiz.core.entity.DelegatorInterface.class));
        when(delegator.getCountByAnd(anyString(), anyMap())).thenThrow(new AssertionError("Migration must count native entities directly"));
        when(delegator.findByAnd(eq("OSCurrentStep"), anyMap(), anyList())).thenReturn(Collections.<GenericValue>emptyList());
        when(delegator.findById(eq("Issue"), anyLong())).thenAnswer(call -> {
            Long id = call.getArgument(1); NativeIssue state = nativeIssues.get(id);
            if (state == null) return null;
            GenericValue row = mock(GenericValue.class); when(row.getLong("workflowId")).thenReturn(state.entry);
            Map<String, Object> fields = new TreeMap<String, Object>();
            fields.put("id", id); fields.put("project", 1L); fields.put("type", "10001");
            fields.put("status", state.status.getId()); fields.put("workflowId", state.entry); fields.put("updated", 1L);
            when(row.getAllFields()).thenReturn(fields);
            return row;
        });
        doAnswer(call -> {
            MutableIssue issue = call.getArgument(0); JiraWorkflow flow = call.getArgument(1); Status status = call.getArgument(2);
            NativeIssue state = nativeIssues.get(issue.getId()); state.entry = ids.incrementAndGet(); state.status = status;
            entries.put(state.entry, flow.getName()); return null;
        }).when(workflows).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        fixture = UndoTestFixture.create("admin");
        schemeWriter = new SchemeWriter(schemes, schemeService, fixture.journal);
        workflowWriter = new WorkflowWriter(workflows, delegator, fixture.journal);
        migrator = new IssueMigrator(delegator, issues, workflows, fixture.journal);
    }
    @After public void close() { fixture.close(); }

    @Test public void c2ReadyTypeCreatedAfterApplyPlanningMigratesBeforeSchemeWrite() throws Exception {
        IssueStatusCounter counter = spy(new IssueStatusCounter(delegator));
        WorkflowAssignerImpl assigner = emptyBugAssigner(counter);
        AssignWorkflowResponse preview = assigner.assign(
                new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        assertEquals(AssignVerdict.READY, preview.getIssueTypes().get(0).getVerdict());
        doAnswer(call -> {
            IssueStatusCounter.Scan scan = (IssueStatusCounter.Scan) call.callRealMethod();
            assertNull(scan.diagnosis);
            assertTrue(nativeIssues.isEmpty());
            issue(1L, "jira");
            return scan;
        }).when(counter).scan(eq(project), any(), any(), eq(workflows));
        String[] entryAtSchemeWrite = {null};
        doAnswer(call -> {
            entryAtSchemeWrite[0] = entries.get(nativeIssues.get(1L).entry);
            AssignableWorkflowScheme value = call.getArgument(0);
            nativeSchemes.put(value.getId(), value);
            return null;
        }).when(schemes).updateWorkflowScheme(any(AssignableWorkflowScheme.class));

        AssignWorkflowResponse result = assigner.assign(
                new AssignWorkflowRequest(false, null, null, false, null, false, preview.getPlanId()));
        assertEquals(AssignStatus.ASSIGNED, result.getStatus());
        assertEquals(AssignVerdict.READY, result.getIssueTypes().get(0).getVerdict());
        assertEquals("Target", entries.get(nativeIssues.get(1L).entry));
        assertEquals("Target", entryAtSchemeWrite[0]);
        assertEquals("1", nativeIssues.get(1L).status.getId());
        assertEquals("Target", nativeSchemes.get(binding).getActualWorkflow("10001"));
        assertNoUndoRows();
    }

    @Test public void c2ReadyTypeCreatedAtSchemeWriteMigratesInFinalSweep() throws Exception {
        WorkflowAssignerImpl assigner = emptyBugAssigner(new IssueStatusCounter(delegator));
        AssignWorkflowResponse preview = assigner.assign(
                new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        assertEquals(AssignVerdict.READY, preview.getIssueTypes().get(0).getVerdict());
        doAnswer(call -> {
            assertTrue(nativeIssues.isEmpty());
            issue(1L, "jira");
            AssignableWorkflowScheme value = call.getArgument(0);
            nativeSchemes.put(value.getId(), value);
            return null;
        }).when(schemes).updateWorkflowScheme(any(AssignableWorkflowScheme.class));

        AssignWorkflowResponse result = assigner.assign(
                new AssignWorkflowRequest(false, null, null, false, null, false, preview.getPlanId()));
        assertEquals(AssignStatus.ASSIGNED, result.getStatus());
        assertEquals("Target", entries.get(nativeIssues.get(1L).entry));
        assertEquals("1", nativeIssues.get(1L).status.getId());
        assertEquals("Target", nativeSchemes.get(binding).getActualWorkflow("10001"));
        assertNoUndoRows();
    }

    private WorkflowAssignerImpl emptyBugAssigner(IssueStatusCounter counter) {
        com.atlassian.jira.issue.issuetype.IssueType bug = AssignMocks.issueType("10001", "Bug");
        when(project.getIssueTypes()).thenReturn(Collections.singletonList(bug));
        com.atlassian.jira.config.IssueTypeManager types = mock(com.atlassian.jira.config.IssueTypeManager.class);
        com.atlassian.jira.config.StatusManager statuses = mock(com.atlassian.jira.config.StatusManager.class);
        when(types.getIssueType("10001")).thenReturn(bug);
        when(statuses.getStatus("1")).thenReturn(open);
        return new WorkflowAssignerImpl(fixture.guard, fixture.locks, new ProjectResolver(projects),
                new IssueTypeResolver(types), workflows, schemes, counter,
                new AssignmentPlanner(schemes, types, statuses), schemeWriter, verifier, migrator,
                new ru.rasulov.common.impl.PreviewPlanExecutor(fixture.plans, new ActionPlanCodec()), new ActionPlanCodec(), fixture.journal);
    }

    @Test public void t4RepeatAssignmentMustDiagnoseOldNativeEntryUnderTargetScheme() {
        WorkflowAssignerImpl assigner = targetSchemeWithNativeRemainder();
        AssignWorkflowResponse result = assigner.assign(
                new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        assertEquals(AssignStatus.FAILED, result.getStatus());
        assertEquals(AssignErrorCode.JIRA_ERROR, result.getErrorCode());
        assertTrue(result.getMessage().contains("ABC-2"));
        assertNull(result.getPlanId());
        assertEquals("jira", entries.get(nativeIssues.get(2L).entry));
        verify(workflows, never()).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
    }

    @Test public void configurationDiscoveryDoesNotReadIssues() {
        WorkflowAssignerImpl assigner = targetSchemeWithNativeRemainder();
        assigner.projectWorkflows("ABC", false);
        verify(delegator, never()).findListIteratorByCondition(eq("Issue"), any(), any(), any(), any(), any());
        assertEquals(0, issueRowsRead);
    }

    @Test public void requestedCountsReadOnceWithoutInspectingBrokenNativeEntries() throws Exception {
        WorkflowAssignerImpl assigner = targetSchemeWithNativeRemainder();
        ProjectWorkflowsResponse response = assigner.projectWorkflows("ABC", true);
        assertEquals(ProjectWorkflowsResponse.IssueCountsStatus.COMPLETE, response.getIssueCountsStatus());
        assertEquals(Integer.valueOf(2), response.getIssueTypes().get(0).getIssues());
        assertEquals(2, response.getIssueTypes().get(0).getStatuses().get(0).getIssues());
        assertEquals(2, issueRowsRead);
        verify(delegator, times(1)).findListIteratorByCondition(eq("Issue"), any(), any(), any(), any(), any());
        verify(workflowStore, never()).findEntry(anyLong());
    }

    @Test public void nPlusOneSelectedTypesAreRestrictedInTheDatabaseQuery() throws Exception {
        WorkflowAssignerImpl assigner = targetSchemeWithNativeRemainder();
        assigner.assign(new AssignWorkflowRequest(true, "ABC", "Target", false,
                Collections.singletonList("Task"), false));
        org.mockito.ArgumentCaptor<org.ofbiz.core.entity.EntityCondition> condition =
                org.mockito.ArgumentCaptor.forClass(org.ofbiz.core.entity.EntityCondition.class);
        verify(delegator, times(1)).findListIteratorByCondition(eq("Issue"), condition.capture(),
                any(), any(), any(), any());
        for (org.ofbiz.core.entity.EntityCondition query : condition.getAllValues()) {
            assertTrue("Selected type must be in the SQL predicate: " + query,
                    query instanceof org.ofbiz.core.entity.EntityExpr);
            org.ofbiz.core.entity.EntityExpr conjunction = (org.ofbiz.core.entity.EntityExpr) query;
            assertEquals(org.ofbiz.core.entity.EntityOperator.AND, conjunction.getOperator());
            org.ofbiz.core.entity.EntityExpr typeFilter = (org.ofbiz.core.entity.EntityExpr) conjunction.getRhs();
            assertEquals("type", typeFilter.getLhs());
            assertEquals(org.ofbiz.core.entity.EntityOperator.IN, typeFilter.getOperator());
            assertEquals(Collections.singletonList("10002"), typeFilter.getRhs());
        }
        verify(workflowStore, never()).findEntry(anyLong());
    }

    @Test public void nPlusOneReadsEntriesOnlyForSelectedIssues() throws Exception {
        WorkflowAssignerImpl assigner = targetSchemeWithNativeRemainder();
        entries.put(nativeIssues.get(2L).entry, "Target");
        NativeIssue foreign = issue(3L, "jira");
        foreign.type = "10002";
        AssignWorkflowResponse result = assigner.assign(new AssignWorkflowRequest(true, "ABC", "Target", false,
                Collections.singletonList("Bug"), false));
        assertEquals(AssignStatus.ALREADY_ASSIGNED, result.getStatus());
        assertEquals(2, issueRowsRead);
        verify(workflowStore).findEntry(nativeIssues.get(1L).entry);
        verify(workflowStore).findEntry(nativeIssues.get(2L).entry);
        verifyNoMoreInteractions(workflowStore);
    }

    @Test public void configurationDiscoverySurvivesOldNativeEntryUnderTargetScheme() throws Exception {
        WorkflowAssignerImpl assigner = targetSchemeWithNativeRemainder();
        ru.rasulov.workflow.api.dto.assign.ProjectWorkflowsResponse response = assigner.projectWorkflows("ABC", false);
        assertEquals("Target", response.getIssueTypes().get(0).getWorkflow());
        verify(workflowStore, never()).findEntry(anyLong());
        assertEquals("jira", entries.get(nativeIssues.get(2L).entry));
        verify(workflows, never()).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
    }

    @Test public void t4DiagnosisLimitsKeysButReportsTheWholeRemainder() {
        WorkflowAssignerImpl assigner = targetSchemeWithNativeRemainder();
        for (long id = 3; id <= 14; id++) issue(id, "jira");
        AssignWorkflowResponse result = assigner.assign(
                new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        assertEquals(AssignStatus.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("13 issue(s)"));
        assertTrue(result.getMessage().contains("ABC-11"));
        assertFalse(result.getMessage().contains("ABC-12"));
        assertTrue(result.getMessage().contains("first 10 keys shown"));
    }

    @Test public void t4DiagnosisTreatsMissingEntryAsInconsistent() {
        WorkflowAssignerImpl assigner = targetSchemeWithNativeRemainder();
        entries.remove(nativeIssues.get(1L).entry);
        AssignWorkflowResponse result = assigner.assign(
                new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        assertEquals(AssignStatus.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("ABC-1"));
        assertTrue(result.getMessage().contains("2 issue(s)"));
    }

    @Test public void t4SelectedTypeDoesNotDiagnoseUnselectedRemainder() {
        WorkflowAssignerImpl assigner = targetSchemeWithNativeRemainder();
        AssignWorkflowResponse result = assigner.assign(
                new AssignWorkflowRequest(true, "ABC", "Target", false, Collections.singletonList("Task"), false));
        assertEquals(AssignStatus.ALREADY_ASSIGNED, result.getStatus());
        assertEquals("jira", entries.get(nativeIssues.get(2L).entry));
    }

    private WorkflowAssignerImpl targetSchemeWithNativeRemainder() {
        nativeSchemes.put(10L, scheme(10L, "Target scheme", "Target", Collections.emptyMap()));
        com.atlassian.jira.issue.issuetype.IssueType bug = AssignMocks.issueType("10001", "Bug");
        com.atlassian.jira.issue.issuetype.IssueType task = AssignMocks.issueType("10002", "Task");
        when(project.getIssueTypes()).thenReturn(Arrays.asList(bug, task));
        issue(1L, "Target");
        issue(2L, "jira");
        com.atlassian.jira.config.IssueTypeManager types = mock(com.atlassian.jira.config.IssueTypeManager.class);
        com.atlassian.jira.config.StatusManager statuses = mock(com.atlassian.jira.config.StatusManager.class);
        when(types.getIssueType("10001")).thenReturn(bug);
        when(types.getIssueType("10002")).thenReturn(task);
        when(types.getIssueTypes()).thenReturn(Arrays.asList(bug, task));
        when(statuses.getStatus("1")).thenReturn(open);
        return new WorkflowAssignerImpl(fixture.guard, fixture.locks, new ProjectResolver(projects),
                new IssueTypeResolver(types), workflows, schemes, new IssueStatusCounter(delegator),
                new AssignmentPlanner(schemes, types, statuses), schemeWriter, verifier, migrator,
                new ru.rasulov.common.impl.PreviewPlanExecutor(fixture.plans, new ActionPlanCodec()), new ActionPlanCodec(), fixture.journal);
    }

    @Test public void clonePreviewApplyCopiesAndAssignsWithoutUndoRows() throws Exception {
        NativeIssue issue = issue(1L, "jira");
        WorkflowClonerImpl cloner = cloner();
        CloneWorkflowsResponse preview = cloner.clone(new CloneWorkflowsRequest(true, "ABC", CloneScope.ALL_WORKFLOWS, null, null, null));
        assertEquals(CloneStatus.WOULD_CLONE, preview.getStatus());
        CloneWorkflowsResponse result = cloner.clone(new CloneWorkflowsRequest(false, null, null, null, null, null, preview.getPlanId()));
        assertEquals(CloneStatus.CLONED, result.getStatus());
        assertEquals("Copy", entries.get(issue.entry)); assertEquals("Copy scheme", nativeSchemes.get(binding).getName());
        assertNotNull(nativeWorkflows.get("jira"));
        assertNoUndoRows();
        assertTrue(nativeWorkflows.containsKey("Copy")); assertEquals(3, nativeSchemes.size());
        assertNotNull(nativeWorkflows.get("jira")); assertNotNull(nativeWorkflows.get("Target"));
    }
    @Test public void assignmentUsesAuthoritativeDtoWithoutUndoRows() throws Exception {
        NativeIssue issue = issue(1L, "jira"); WorkflowAssignerImpl assigner = assigner();
        AssignWorkflowResponse preview = assigner.assign(new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        assertEquals(AssignStatus.WOULD_ASSIGN, preview.getStatus());
        AssignWorkflowResponse result = assigner.assign(new AssignWorkflowRequest(false, null, null, false, null, false, preview.getPlanId()));
        assertEquals(AssignStatus.ASSIGNED, result.getStatus());
        assertEquals("Target", nativeSchemes.get(10L).getConfiguredDefaultWorkflow()); assertEquals("Target", entries.get(issue.entry));
        assertNoUndoRows();
        assertTrue(nativeWorkflows.containsKey("Target"));
    }
    @Test public void aNativeCopyFailureDoesNotTriggerAdHocDeletion() throws Exception {
        when(workflows.copyWorkflow(any(ApplicationUser.class), eq("Copy"), nullable(String.class), any(JiraWorkflow.class)))
                .thenAnswer(call -> { nativeWorkflows.put("Copy", workflow("Copy")); throw new IllegalStateException("created but response lost"); });
        try { run(null, null, () -> workflowWriter.copy(fixture.currentActor.get(), nativeWorkflows.get("jira"), "Copy")); fail("native failure"); }
        catch (IllegalStateException expected) { }
        assertNoUndoRows();
        assertTrue(nativeWorkflows.containsKey("Copy")); verify(workflows, never()).deleteWorkflow(any(JiraWorkflow.class));
    }
    @Test public void aNativeMigrationFailureEscapesWithoutUndoRows() throws Exception {
        NativeIssue issue = issue(1L, "jira");
        doThrow(new IllegalStateException("native outcome unknown")).when(workflows)
                .migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        try {
            run(null, null, () -> migrator.migrate(project, Collections.singletonMap("10001", "jira"),
                    nativeWorkflows.get("Target"), new MigrationCount()));
            fail("native failure");
        } catch (IllegalStateException expected) { }
        assertEquals("jira", entries.get(issue.entry));
        assertNoUndoRows();
    }

    @Test public void aLaterNativeMigrationFailureRetainsTheForwardPrefix() throws Exception {
        NativeIssue first = issue(1L, "jira"), second = issue(2L, "jira");
        doAnswer(call -> {
            MutableIssue issue = call.getArgument(0);
            if (issue.getId() == 2L) throw new IllegalStateException("native outcome unknown");
            NativeIssue state = nativeIssues.get(issue.getId());
            state.entry = ids.incrementAndGet(); entries.put(state.entry, "Target");
            return null;
        }).when(workflows).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        try {
            run(null, null, () -> migrator.migrate(project, Collections.singletonMap("10001", "jira"),
                    nativeWorkflows.get("Target"), new MigrationCount()));
            fail("native failure");
        } catch (IllegalStateException expected) { }
        assertEquals("Target", entries.get(first.entry)); assertEquals("jira", entries.get(second.entry));
        assertNoUndoRows();
        verify(workflows, times(2)).migrateIssueToWorkflow(any(MutableIssue.class), eq(nativeWorkflows.get("Target")), any(Status.class));
        verify(workflows, never()).migrateIssueToWorkflow(any(MutableIssue.class), eq(nativeWorkflows.get("jira")), any(Status.class));
    }

    @Test public void cancellationAfterAMigrationRetainsTheForwardPrefix() throws Exception {
        NativeIssue first = issue(1L, "jira"), second = issue(2L, "jira");
        doAnswer(call -> {
            MutableIssue issue = call.getArgument(0); NativeIssue state = nativeIssues.get(issue.getId());
            state.entry = ids.incrementAndGet(); entries.put(state.entry, ((JiraWorkflow) call.getArgument(1)).getName());
            if ("Target".equals(((JiraWorkflow) call.getArgument(1)).getName()))
                fixture.journal.cancel(operation, "admin", false);
            return null;
        }).when(workflows).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        try {
            run(null, null, () -> migrator.migrate(project, Collections.singletonMap("10001", "jira"),
                    nativeWorkflows.get("Target"), new MigrationCount()));
            fail("cancelled");
        } catch (ru.rasulov.common.impl.undo.JournalCancelledException expected) { }
        assertEquals("Target", entries.get(first.entry)); assertEquals("jira", entries.get(second.entry));
        assertNoUndoRows();
    }

    @Test public void cancellationFromRemoveCompletesTheTargetBindingBeforeItIsHandled() throws Exception {
        AssignableWorkflowScheme target = scheme(20L, "Target scheme", "Target", Collections.<String, String>emptyMap());
        nativeSchemes.put(20L, target);
        doAnswer(call -> {
            binding = 0;
            fixture.journal.cancel(operation, "admin", false);
            return null;
        }).when(schemes).removeSchemesFromProject(project);
        try {
            run(null, null, () -> { schemeWriter.assign(project, target); return null; });
            fail("cancellation must be handled after assignment");
        } catch (ru.rasulov.common.impl.undo.JournalCancelledException expected) {
            assertEquals(20L, binding);
        }
        assertNoUndoRows();
    }

    @Test public void cancellationBeforeAssignmentRetainsTheOriginalBinding() throws Exception {
        AssignableWorkflowScheme target = scheme(20L, "Target scheme", "Target", Collections.<String, String>emptyMap());
        nativeSchemes.put(20L, target);
        try {
            run(null, null, () -> {
                fixture.journal.cancel(operation, "admin", false);
                schemeWriter.assign(project, target);
                return null;
            });
            fail("cancelled");
        } catch (ru.rasulov.common.impl.undo.JournalCancelledException expected) {
            assertEquals(10L, binding);
        }
        verify(schemes, never()).removeSchemesFromProject(project);
        verify(schemes, never()).addSchemeToProject(eq(project), any(com.atlassian.jira.scheme.Scheme.class));
        assertNoUndoRows();
    }

    @Test public void aNativeAddFailureBeforeBindingIsNeverReportedAsAClonedProject() throws Exception {
        NativeIssue issue = issue(1L, "jira");
        WorkflowClonerImpl cloner = cloner(realVerifier());
        CloneWorkflowsResponse preview = cloner.clone(
                new CloneWorkflowsRequest(true, "ABC", CloneScope.ALL_WORKFLOWS, null, null, null));
        doThrow(new IllegalStateException("add failed before binding")).when(schemes)
                .addSchemeToProject(eq(project), any(com.atlassian.jira.scheme.Scheme.class));
        CloneWorkflowsResponse result = cloner.clone(
                new CloneWorkflowsRequest(false, null, null, null, null, null, preview.getPlanId()));
        assertEquals("the binding never changed, so the result is not confirmed", CloneStatus.UNKNOWN, result.getStatus());
        assertTrue(result.getMessage(), result.getMessage().contains("workflow copies 'Copy' were created"));
        assertTrue(result.getMessage(), result.getMessage().contains("1 issue(s) moved to the copies"));
        assertTrue(result.getMessage(), result.getMessage().contains("Not confirmed: "));
        assertEquals(0L, binding);
        assertEquals("Copy", entries.get(issue.entry));
        assertTrue(nativeWorkflows.containsKey("Copy"));
        verify(workflows, times(1)).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        assertNoUndoRows();
    }

    @Test public void aCloneBindingSavedThenAnErrorIsPartialNotFailed() throws Exception {
        NativeIssue issue = issue(1L, "jira");
        WorkflowClonerImpl cloner = cloner(realVerifier());
        CloneWorkflowsResponse preview = cloner.clone(
                new CloneWorkflowsRequest(true, "ABC", CloneScope.ALL_WORKFLOWS, null, null, null));
        doAnswer(call -> {
            binding = ((com.atlassian.jira.scheme.Scheme) call.getArgument(1)).getId();
            throw new IllegalStateException("assigned but response lost");
        }).when(schemes).addSchemeToProject(eq(project), any(com.atlassian.jira.scheme.Scheme.class));
        CloneWorkflowsResponse result = cloner.clone(
                new CloneWorkflowsRequest(false, null, null, null, null, null, preview.getPlanId()));
        assertEquals(CloneStatus.PARTIALLY_CLONED, result.getStatus());
        assertTrue(result.getMessage(), result.getMessage().contains("reports them active"));
        assertTrue(result.getMessage(), result.getMessage().contains("final pass"));
        assertEquals("Copy scheme", nativeSchemes.get(binding).getName());
        assertEquals("Copy", entries.get(issue.entry));
        verify(workflows, times(1)).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        assertNoUndoRows();
    }

    // --- assign_workflow: an error after a native write (W3) ---------------------------------------------------------

    private static final String INJECTED = "injected after the native save";

    private void schemeSavedThenFails() {
        doAnswer(call -> {
            AssignableWorkflowScheme value = call.getArgument(0); nativeSchemes.put(value.getId(), value);
            throw new IllegalStateException(INJECTED);
        }).when(schemes).updateWorkflowScheme(any(AssignableWorkflowScheme.class));
    }

    private AssignWorkflowResponse applyPreview(WorkflowAssignerImpl assigner, AssignWorkflowResponse preview) {
        return assigner.assign(new AssignWorkflowRequest(false, null, null, false, null, false, preview.getPlanId()));
    }

    @Test public void w3TheSchemeIsSavedThenAnErrorIsPartiallyAssignedWithTheConfirmedStateAndNothingRepeats() throws Exception {
        NativeIssue issue = issue(1L, "jira"); WorkflowAssignerImpl assigner = assigner(realVerifier());
        AssignWorkflowResponse preview = assigner.assign(new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        schemeSavedThenFails();

        AssignWorkflowResponse result = applyPreview(assigner, preview);

        assertEquals(AssignStatus.PARTIALLY_ASSIGNED, result.getStatus());
        String message = result.getMessage();
        assertTrue(message, message.contains("Confirmed: 1 issue(s) moved to 'Target'"));
        assertTrue(message, message.contains("resolves the requested issue type(s) to 'Target' and reports the workflow active"));
        assertTrue(message, message.contains("Not confirmed: the final pass for issues created while the call was running"));
        assertTrue(message, message.contains("Nothing was repaired, retried or rolled back"));
        assertFalse("the raw error text is not the answer", message.contains(INJECTED));
        assertEquals(SchemeAction.UPDATE, result.getScheme().getAction());
        assertEquals(Long.valueOf(10L), result.getScheme().getId());
        assertEquals("Target", nativeSchemes.get(10L).getConfiguredDefaultWorkflow());
        assertEquals("Target", entries.get(issue.entry));
        verify(workflows, times(1)).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        verify(schemes, times(1)).updateWorkflowScheme(any(AssignableWorkflowScheme.class));
        assertNoUndoRows();
    }

    @Test public void anErrorBeforeTheSchemeWasSavedKeepsTheMovedIssuesAndIsUnknownNotPartial() throws Exception {
        NativeIssue issue = issue(1L, "jira"); WorkflowAssignerImpl assigner = assigner(realVerifier());
        AssignWorkflowResponse preview = assigner.assign(new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        doThrow(new IllegalStateException(INJECTED)).when(schemes).updateWorkflowScheme(any(AssignableWorkflowScheme.class));

        AssignWorkflowResponse result = applyPreview(assigner, preview);

        assertEquals(AssignStatus.UNKNOWN, result.getStatus());
        String message = result.getMessage();
        assertTrue(message, message.contains("1 issue(s) moved to 'Target'"));
        assertTrue(message, message.contains("issue type 10001 resolves to 'jira', expected 'Target'"));
        assertFalse(message, message.contains("reports the workflow active"));
        assertEquals("jira", nativeSchemes.get(10L).getConfiguredDefaultWorkflow());
        assertEquals("Target", entries.get(issue.entry));
        verify(workflows, times(1)).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
    }

    @Test public void anUnreadableJiraAfterTheErrorStaysUnknownAndInventsNoOutcome() throws Exception {
        issue(1L, "jira"); WorkflowAssignerImpl assigner = assigner(realVerifier());
        AssignWorkflowResponse preview = assigner.assign(new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        boolean[] unreadable = {false};
        when(schemes.getWorkflowSchemeObj(any(Project.class))).thenAnswer(call -> {
            if (unreadable[0]) throw new IllegalStateException("storage unavailable");
            return nativeSchemes.get(binding);
        });
        doAnswer(call -> {
            AssignableWorkflowScheme value = call.getArgument(0); nativeSchemes.put(value.getId(), value);
            unreadable[0] = true;
            throw new IllegalStateException(INJECTED);
        }).when(schemes).updateWorkflowScheme(any(AssignableWorkflowScheme.class));

        AssignWorkflowResponse result = applyPreview(assigner, preview);

        assertEquals(AssignStatus.UNKNOWN, result.getStatus());
        assertTrue(result.getMessage(), result.getMessage().contains("Jira could not be read"));
        assertFalse(result.getMessage(), result.getMessage().contains("reports the workflow active"));
        assertEquals("Target", nativeSchemes.get(10L).getConfiguredDefaultWorkflow());
    }

    @Test public void anErrorInTheMiddleOfTheMovesNamesTheConfirmedMovesAndTheInFlightOne() throws Exception {
        NativeIssue first = issue(1L, "jira"), second = issue(2L, "jira");
        WorkflowAssignerImpl assigner = assigner(realVerifier());
        AssignWorkflowResponse preview = assigner.assign(new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        doAnswer(call -> {
            MutableIssue issue = call.getArgument(0);
            if (issue.getId() == 2L) throw new IllegalStateException("native outcome unknown");
            NativeIssue state = nativeIssues.get(issue.getId());
            state.entry = ids.incrementAndGet(); entries.put(state.entry, ((JiraWorkflow) call.getArgument(1)).getName());
            return null;
        }).when(workflows).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));

        AssignWorkflowResponse result = applyPreview(assigner, preview);

        assertEquals(AssignStatus.UNKNOWN, result.getStatus());
        assertTrue(result.getMessage(), result.getMessage().contains("1 issue(s) moved to 'Target'"));
        assertTrue(result.getMessage(), result.getMessage().contains("the issue whose move was in progress"));
        assertEquals("Target", entries.get(first.entry)); assertEquals("jira", entries.get(second.entry));
        verify(schemes, never()).updateWorkflowScheme(any(AssignableWorkflowScheme.class));
        assertEquals("jira", nativeSchemes.get(10L).getConfiguredDefaultWorkflow());
    }

    @Test public void anErrorBeforeAnyPossibleWriteStillEscapesAsAFailure() throws Exception {
        issue(1L, "jira");
        WorkflowAssignerImpl assigner = assigner(realVerifier());
        AssignWorkflowResponse preview = assigner.assign(new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        entries.put(nativeIssues.get(1L).entry, "Other");
        try {
            AssignWorkflowResponse result = applyPreview(assigner, preview);
            fail("planning error must escape, not return " + result.getStatus());
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("changed workflow, project or type"));
        }
        verify(workflows, never()).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        verify(schemes, never()).updateWorkflowScheme(any(AssignableWorkflowScheme.class));
    }

    @Test public void w3ThroughTheRunnerIsOneCompletedOperationReadableThroughMcpAuditedAndNotRepeatedOnReplay() throws Exception {
        NativeIssue issue = issue(1L, "jira"); WorkflowAssignerImpl assigner = assigner(realVerifier());
        AssignWorkflowResponse preview = assigner.assign(new AssignWorkflowRequest(true, "ABC", "Target", true, null, false));
        schemeSavedThenFails();
        java.util.concurrent.Semaphore finished = new java.util.concurrent.Semaphore(0);
        com.atlassian.jira.util.thread.JiraThreadLocalUtil locals = mock(com.atlassian.jira.util.thread.JiraThreadLocalUtil.class);
        doAnswer(call -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
        ru.rasulov.common.impl.tools.ToolRegistry registry = new ru.rasulov.common.impl.tools.ToolRegistry(
                Collections.singletonList(() -> {
                    Map<String, ru.rasulov.common.impl.tools.ToolCall> tools = new LinkedHashMap<String, ru.rasulov.common.impl.tools.ToolCall>();
                    tools.put("assign_workflow", args -> assigner.assign(new AssignWorkflowRequest(false, null, null, false,
                            null, false, args.get("planId").textValue())));
                    tools.put("get_operation", args -> fixture.journal.getOperation(args.get("operationId").textValue(), "admin"));
                    return tools;
                }));
        ru.rasulov.common.impl.tools.ToolCatalog catalog = new ru.rasulov.common.impl.tools.ToolCatalog(
                mock(com.atlassian.plugin.PluginAccessor.class));
        ru.rasulov.common.impl.applies.ApplyJournal audit = mock(ru.rasulov.common.impl.applies.ApplyJournal.class);
        ru.rasulov.common.impl.tools.OperationRunner runner = new ru.rasulov.common.impl.tools.OperationRunner(
                fixture.journal, fixture.guard, () -> registry, catalog,
                mock(com.atlassian.jira.security.JiraAuthenticationContext.class), locals, fixture.plans, audit);
        ru.rasulov.common.impl.tools.McpServer mcp = new ru.rasulov.common.impl.tools.McpServer(fixture.guard, catalog, registry);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode args = mapper.readTree("{\"dryRun\":false,\"planId\":\"" + preview.getPlanId() + "\"}");
        try {
            Map<String, Object> started = runner.start("assign_workflow", args, null, null);
            assertTrue("the operation did not finish", finished.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS));
            String id = (String) started.get("operationId");
            com.fasterxml.jackson.databind.JsonNode reply = mapper.readTree(mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"get_operation\",\"arguments\":{\"operationId\":\"" + id + "\"}}}"));
            assertFalse(reply.toString(), reply.has("error"));
            com.fasterxml.jackson.databind.JsonNode receipt = mapper.readTree(reply.path("result").path("content").get(0).path("text").asText());
            assertEquals("the lifecycle is finished, not failed", "COMPLETED", receipt.path("status").asText());
            assertEquals("PARTIALLY_ASSIGNED", receipt.path("result").path("status").asText());
            assertTrue(receipt.toString(), receipt.path("result").path("message").asText().contains("Confirmed: 1 issue(s) moved"));
            assertEquals(10L, receipt.path("result").path("scheme").path("id").asLong());
            org.mockito.ArgumentCaptor<ru.rasulov.common.impl.applies.ApplyEvent> event =
                    org.mockito.ArgumentCaptor.forClass(ru.rasulov.common.impl.applies.ApplyEvent.class);
            verify(audit, times(1)).record(event.capture());
            assertEquals(id, event.getValue().operationRef);
            assertEquals("COMPLETED", event.getValue().operationState);
            assertEquals("PARTIALLY_ASSIGNED", event.getValue().domainStatus);

            Map<String, Object> replay = runner.start("assign_workflow", args, null, null);
            assertEquals(id, replay.get("operationId"));
            assertEquals(true, replay.get("alreadyStarted"));
            verify(workflows, times(1)).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
            verify(schemes, times(1)).updateWorkflowScheme(any(AssignableWorkflowScheme.class));
            verify(audit, times(1)).record(any(ru.rasulov.common.impl.applies.ApplyEvent.class));
            assertEquals("Target", entries.get(issue.entry));
        } finally { runner.destroy(); }
    }

    private AssignmentVerifier realVerifier() {
        when(workflows.isActive(any(JiraWorkflow.class))).thenReturn(true);
        return new AssignmentVerifier(workflows, schemes);
    }

    @Test public void aNativeProjectAssignmentFailureRetainsTheForwardBinding() throws Exception {
        AssignableWorkflowScheme target = scheme(20L, "Target scheme", "Target", Collections.<String, String>emptyMap());
        nativeSchemes.put(20L, target);
        doAnswer(call -> { binding = 20L; throw new IllegalStateException("assigned but response lost"); })
                .when(schemes).addSchemeToProject(eq(project), any(com.atlassian.jira.scheme.Scheme.class));
        try { run(null, null, () -> { schemeWriter.assign(project, target); return null; }); fail("native failure"); }
        catch (IllegalStateException expected) { }
        assertEquals(20L, binding);
        assertNoUndoRows();
    }

    @Test public void assigningASchemeResetsTheActiveWorkflowCacheAfterTheBindingChanged() throws Exception {
        AssignableWorkflowScheme target = scheme(20L, "Target scheme", "Target", Collections.<String, String>emptyMap());
        nativeSchemes.put(20L, target);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(schemes);
        run(null, null, () -> { schemeWriter.assign(project, target); return null; });
        order.verify(schemes).addSchemeToProject(eq(project), any(com.atlassian.jira.scheme.Scheme.class));
        order.verify(schemes).clearWorkflowCache();
        assertEquals(20L, binding);
    }

    @Test public void aHalfDoneAssignmentStillResetsTheCacheAndKeepsTheOriginalFailure() throws Exception {
        AssignableWorkflowScheme target = scheme(20L, "Target scheme", "Target", Collections.<String, String>emptyMap());
        nativeSchemes.put(20L, target);
        IllegalStateException original = new IllegalStateException("add failed");
        IllegalStateException cacheFailure = new IllegalStateException("cache failed");
        doThrow(original).when(schemes).addSchemeToProject(eq(project), any(com.atlassian.jira.scheme.Scheme.class));
        doThrow(cacheFailure).when(schemes).clearWorkflowCache();
        try { run(null, null, () -> { schemeWriter.assign(project, target); return null; }); fail("native failure"); }
        catch (IllegalStateException expected) {
            assertSame(original, expected);
            assertSame(cacheFailure, expected.getSuppressed()[0]);
        }
        verify(schemes).clearWorkflowCache();
    }

    @Test public void cacheInvalidationFailureDoesNotRevertForwardSchemeMappings() throws Exception {
        doThrow(new IllegalStateException("cache failed")).when(schemes).clearWorkflowCache();
        try { run(null, null, () -> { schemeWriter.update(nativeSchemes.get(10L), SchemeEdit.allIssueTypes("Target")); return null; }); fail("cache failure"); }
        catch (IllegalStateException expected) { }
        assertEquals("Target", nativeSchemes.get(10L).getConfiguredDefaultWorkflow());
        assertNoUndoRows();
    }

    private WorkflowAssignerImpl assigner() { return assigner(verifier); }
    private WorkflowAssignerImpl assigner(AssignmentVerifier verifier) {
        AssignmentPlanner planner = mock(AssignmentPlanner.class); IssueStatusCounter counter = mock(IssueStatusCounter.class);
        when(counter.scan(eq(project), any(), any(), eq(workflows)))
                .thenReturn(new IssueStatusCounter.Scan(new IssueUsage(), null));
        IssueTypeAssignment type = new IssueTypeAssignment(new IssueTypeRef("10001", "Bug"), "jira", "Target", AssignVerdict.MIGRATE, nativeIssues.size(), Collections.<StatusUsage>emptyList());
        AssignmentPlan plan = new AssignmentPlan(Collections.singletonList(type), SchemeAction.UPDATE, nativeSchemes.get(10L), false, null, SchemeEdit.allIssueTypes("Target"), Collections.<String>emptyList());
        when(planner.plan(any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(plan);
        return new WorkflowAssignerImpl(fixture.guard, fixture.locks, new ProjectResolver(projects), mock(IssueTypeResolver.class), workflows, schemes, counter, planner, schemeWriter, verifier, migrator, new ru.rasulov.common.impl.PreviewPlanExecutor(fixture.plans, new ActionPlanCodec()), new ActionPlanCodec(), fixture.journal);
    }
    private WorkflowClonerImpl cloner() { return cloner(verifier); }
    private WorkflowClonerImpl cloner(AssignmentVerifier verifier) {
        ClonePlanner planner = mock(ClonePlanner.class); IssueStatusCounter counter = mock(IssueStatusCounter.class);
        when(counter.count(project)).thenReturn(new IssueUsage());
        ClonePlan plan = new ClonePlan(CloneScope.ALL_WORKFLOWS, SchemeAction.CREATE, nativeSchemes.get(10L),
                new SchemeView(10L, "Original", false, true, 1, Collections.singletonList("External")), "Copy scheme", SchemeEdit.allIssueTypes("Copy"),
                Collections.singletonList(new ClonePlan.PlannedWorkflow("jira", nativeWorkflows.get("jira"), "Copy", CloneVerdict.COPY, 1, Collections.singletonList("External"), false)),
                Collections.singletonMap("10001", "jira"), Collections.<String>emptyList());
        when(planner.plan(any(), any(), any())).thenReturn(plan);
        return new WorkflowClonerImpl(fixture.guard, fixture.locks, new ProjectResolver(projects), counter, planner, workflowWriter, schemeWriter, verifier, migrator, new ru.rasulov.common.impl.PreviewPlanExecutor(fixture.plans, new ActionPlanCodec()), new ActionPlanCodec(), fixture.journal, delegator);
    }
    private <T> T run(String session, String set, Supplier<T> work) {
        if (session == null) { session = fixture.journal.createSession("admin"); set = fixture.journal.createChangeSet(session, "admin"); }
        operation = fixture.journal.openOperation("assign_workflow", "admin", session, set); return fixture.journal.runOperation(operation, work);
    }
    private void assertNoUndoRows() throws Exception {
        assertEquals(0, fixture.entityManager.find(JournalStep.class, Query.select()).length);
    }
    private JiraWorkflow workflow(String name) {
        JiraWorkflow value = AssignMocks.workflow(name, open); when(value.getDescriptor()).thenReturn(new WorkflowDescriptor()); return value;
    }
    private AssignableWorkflowScheme scheme(Long id, String name, String defaultFlow, Map<String, String> mappings) {
        AssignableWorkflowScheme value = AssignMocks.scheme(id, name, defaultFlow, new TreeMap<String, String>(mappings));
        when(value.getConfiguredDefaultWorkflow()).thenReturn(defaultFlow);
        when(value.builder()).thenAnswer(call -> builder(id, name, defaultFlow, mappings)); return value;
    }
    private AssignableWorkflowScheme.Builder builder(Long id, String name, String defaultFlow, Map<String, String> mappings) {
        AssignableWorkflowScheme.Builder builder = mock(AssignableWorkflowScheme.Builder.class, RETURNS_SELF);
        String[] text = {name, defaultFlow, null}; Map<String, String> edit = new TreeMap<String, String>(mappings);
        when(builder.setName(anyString())).thenAnswer(call -> { text[0] = call.getArgument(0); return builder; });
        when(builder.setDescription(nullable(String.class))).thenAnswer(call -> { text[2] = call.getArgument(0); return builder; });
        when(builder.setDefaultWorkflow(nullable(String.class))).thenAnswer(call -> { text[1] = call.getArgument(0); return builder; });
        when(builder.setMapping(anyString(), anyString())).thenAnswer(call -> { edit.put(call.getArgument(0), call.getArgument(1)); return builder; });
        when(builder.setMappings(anyMap())).thenAnswer(call -> { edit.clear(); edit.putAll(call.getArgument(0)); return builder; });
        when(builder.clearMappings()).thenAnswer(call -> { edit.clear(); return builder; });
        when(builder.getMappings()).thenAnswer(call -> new TreeMap<String, String>(edit)); when(builder.getDefaultWorkflow()).thenAnswer(call -> text[1]);
        when(builder.build()).thenAnswer(call -> { AssignableWorkflowScheme value = scheme(id, text[0], text[1], edit); when(value.getDescription()).thenReturn(text[2]); return value; });
        return builder;
    }
    @SuppressWarnings("unchecked") private static <T> ServiceOutcome<T> outcome(T value) {
        ServiceOutcome<T> outcome = mock(ServiceOutcome.class); when(outcome.isValid()).thenReturn(true); when(outcome.getReturnedValue()).thenReturn(value); return outcome;
    }
    private NativeIssue issue(long id, String flow) {
        NativeIssue state = new NativeIssue(); state.entry = ids.incrementAndGet(); state.status = open; nativeIssues.put(id, state); entries.put(state.entry, flow);
        MutableIssue issue = mock(MutableIssue.class); when(issue.getId()).thenReturn(id); when(issue.getKey()).thenReturn("ABC-" + id);
        when(issue.getProjectId()).thenReturn(1L); when(issue.getIssueTypeId()).thenReturn("10001");
        when(issue.getWorkflowId()).thenAnswer(call -> state.entry); when(issue.getStatus()).thenAnswer(call -> state.status);
        when(issue.getUpdated()).thenReturn(new Timestamp(1)); when(issues.getIssueObject(id)).thenReturn(issue); return state;
    }
    private List<GenericValue> issueRows(org.ofbiz.core.entity.EntityFieldMap where) {
        List<GenericValue> rows = new ArrayList<GenericValue>();
        for (Map.Entry<Long, NativeIssue> issue : nativeIssues.entrySet()) {
            if (where.containsField("workflowName") && !Objects.equals(where.getField("workflowName"), entries.get(issue.getValue().entry))) continue;
            GenericValue row = mock(GenericValue.class); when(row.getLong("id")).thenReturn(issue.getKey()); when(row.getLong("issueId")).thenReturn(issue.getKey());
            when(row.getLong("number")).thenReturn(issue.getKey());
            when(row.getString("type")).thenReturn(issue.getValue().type);
            String statusId = issue.getValue().status.getId();
            when(row.getString("status")).thenReturn(statusId);
            when(row.getLong("workflowId")).thenReturn(issue.getValue().entry);
            when(row.getString("issueType")).thenReturn("10001"); when(row.getString("workflowName")).thenReturn(entries.get(issue.getValue().entry)); rows.add(row);
        }
        return rows;
    }
    private static final class NativeIssue { Long entry; Status status; String type = "10001"; }
}
