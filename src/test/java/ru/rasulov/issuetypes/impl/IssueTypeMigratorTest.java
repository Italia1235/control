package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.opensymphony.workflow.spi.WorkflowEntry;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;

import java.sql.Timestamp;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class IssueTypeMigratorTest {
    @Test public void migratesThirtyThreeExactIdsWithoutSelectingIssuesCreatedDuringThePass() throws Exception {
        Domain domain = new Domain(33);
        domain.afterWorkflowWrite = () -> domain.add(99L, "A");
        assertEquals(33, domain.migrate());
        for (long id = 1; id <= 33; id++) {
            Model model = domain.models.get(id);
            assertEquals("B", model.type); assertEquals("B", model.workflow); assertEquals("B", model.status);
        }
        assertEquals("A", domain.models.get(99L).type);
        assertEquals("A", domain.models.get(99L).workflow);
    }

    @Test public void sameMillisecondForeignEditBlocksAnIssueBeforeItIsOverwritten() throws Exception {
        Domain domain = new Domain(2);
        domain.afterWorkflowWrite = () -> domain.models.get(2L).summary = "Foreign edit";
        expectFailure(domain);
        assertEquals("Foreign edit", domain.models.get(2L).summary);
        assertEquals("A", domain.models.get(2L).type);
        verify(domain.models.get(2L).issue, never()).setIssueTypeObject(any());
    }

    @Test public void issueMovedAfterPreparationIsNotMigratedInItsFormerProject() throws Exception {
        Domain domain = new Domain(2);
        domain.afterWorkflowWrite = () -> domain.models.get(2L).projectId = 99L;
        expectFailure(domain);
        assertEquals("A", domain.models.get(2L).type);
        verify(domain.models.get(2L).issue, never()).setIssueTypeObject(any());
    }

    @Test public void changedSourceTypeStopsPreparationBeforeAnyMutation() throws Exception {
        Domain domain = new Domain(2);
        domain.afterSelection = () -> domain.models.get(2L).type = "B";
        expectFailure(domain);
        verify(domain.workflows, never()).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        verify(domain.issues, never()).updateIssue(any(), any(), any(), anyBoolean());
    }

    @Test public void workflowFailurePropagatesAndStopsBeforeTypeUpdate() throws Exception {
        Domain domain = new Domain(2); domain.failWorkflowAfterWrite = true;
        assertSame(domain.nativeFailure, expectFailure(domain));
        assertEquals("B", domain.models.get(1L).workflow);
        assertEquals("A", domain.models.get(1L).type);
        assertEquals("A", domain.models.get(2L).workflow);
        verify(domain.issues, never()).updateIssue(any(), any(), any(), anyBoolean());
    }

    @Test public void issueUpdateFailurePropagatesWithoutStartingTheNextIssue() throws Exception {
        Domain domain = new Domain(2); domain.failUpdateAfterWrite = true;
        assertSame(domain.nativeFailure, expectFailure(domain));
        assertEquals("B", domain.models.get(1L).type);
        assertEquals("B", domain.models.get(1L).workflow);
        assertEquals("A", domain.models.get(2L).type);
        assertEquals("A", domain.models.get(2L).workflow);
    }

    @Test public void tenThousandIssuesUseBoundedDeterministicPagesWithoutLostOrDuplicateWrites() throws Exception {
        IssueTypePagingFixture domain = new IssueTypePagingFixture(10000);
        IssueTypeExecution progress = mock(IssueTypeExecution.class);
        assertEquals(10000, domain.migrate(progress));
        assertEquals(0, domain.migrate(progress));
        for (long id = 1; id <= 10000; id++) {
            IssueTypePagingFixture.State state = domain.states.get(id);
            assertEquals("issue " + id, 1, state.writes);
            assertEquals("B", state.type);
            assertEquals("B", state.workflow);
            assertEquals("B", state.status);
            assertEquals(id, domain.written.get((int) id - 1).longValue());
        }
        assertTrue("The migration must retain at most one 50-row page", domain.maxRows <= 50);
    }

    @Test public void aLateIssueBetweenPassesIsSelectedOnceAfterResettingTheKeyset() throws Exception {
        IssueTypePagingFixture domain = new IssueTypePagingFixture(51);
        IssueTypeExecution progress = mock(IssueTypeExecution.class);
        assertEquals(51, domain.migrate(progress));
        domain.add(10001L);
        assertEquals(1, domain.migrate(progress));
        assertEquals(0, domain.migrate(progress));
        for (IssueTypePagingFixture.State state : domain.states.values()) assertEquals(1, state.writes);
        assertEquals(10001L, domain.written.get(51).longValue());
    }

    @Test public void mixedSourceTypesShareOneIdOrderAcrossPageAndPassBoundaries() throws Exception {
        IssueTypePagingFixture domain = new IssueTypePagingFixture(51);
        IssueType extra = mock(IssueType.class);
        when(extra.getId()).thenReturn("C");
        JiraWorkflow extraWorkflow = mock(JiraWorkflow.class);
        when(extraWorkflow.getName()).thenReturn("C");
        for (long id = 2; id <= 50; id += 2) {
            domain.states.get(id).type = "C";
            domain.states.get(id).workflow = "C";
        }
        List<IssueTypeChangePlan.Mapping> mappings = Arrays.asList(domain.mapping(),
                new IssueTypeChangePlan.Mapping(extra, domain.to, extraWorkflow, domain.target,
                        Collections.singletonMap("A", domain.targetStatus), 25));
        IssueTypeExecution progress = mock(IssueTypeExecution.class);
        assertEquals(51, domain.migrator.migrate(domain.project, mappings, null, progress));
        domain.add(10001L);
        domain.states.get(10001L).type = "C";
        domain.states.get(10001L).workflow = "C";
        assertEquals(1, domain.migrator.migrate(domain.project, mappings, null, progress));
        for (long id = 1; id <= 51; id++) {
            assertEquals(1, domain.states.get(id).writes);
            assertEquals(id, domain.written.get((int) id - 1).longValue());
        }
        assertEquals(1, domain.states.get(10001L).writes);
        assertEquals(10001L, domain.written.get(51).longValue());
    }

    private static IllegalStateException expectFailure(Domain domain) {
        try { domain.migrate(); fail("Migration must refuse the changed issue or propagate the native failure"); }
        catch (IllegalStateException expected) { return expected; }
        throw new AssertionError();
    }

    static final class Model {
        final long id;
        long projectId = 10L, updated = 100L;
        String type, status, workflow, summary = "Original";
        final MutableIssue issue = mock(MutableIssue.class);
        final GenericValue row = mock(GenericValue.class);
        Model(long id, String state) {
            this.id = id; type = status = workflow = state;
            when(issue.getId()).thenReturn(id); when(issue.getWorkflowId()).thenReturn(id);
            when(issue.getProjectId()).thenAnswer(call -> projectId);
            when(issue.getIssueTypeId()).thenAnswer(call -> type);
            when(issue.getStatusId()).thenAnswer(call -> status);
            when(issue.getUpdated()).thenAnswer(call -> new Timestamp(updated));
            when(row.getLong("id")).thenReturn(id); when(row.getLong("workflowId")).thenReturn(id);
            when(row.getAllFields()).thenAnswer(call -> {
                Map<String,Object> fields = new TreeMap<String,Object>();
                fields.put("id", id); fields.put("project", projectId); fields.put("type", type);
                fields.put("status", status); fields.put("workflowId", id); fields.put("summary", summary);
                fields.put("updated", new Timestamp(updated)); return fields;
            });
            doAnswer(call -> { type = ((IssueType) call.getArgument(0)).getId(); return null; })
                    .when(issue).setIssueTypeObject(any());
        }
    }

    static final class Domain {
        final Map<Long,Model> models = new LinkedHashMap<Long,Model>();
        final OfBizDelegator database = mock(OfBizDelegator.class);
        final IssueManager issues = mock(IssueManager.class);
        final WorkflowManager workflows = mock(WorkflowManager.class, RETURNS_DEEP_STUBS);
        final Project project = mock(Project.class);
        final IssueTypeMigrator migrator = new IssueTypeMigrator(database, issues, workflows);
        final IssueType from = mock(IssueType.class), to = mock(IssueType.class);
        final JiraWorkflow original = mock(JiraWorkflow.class), target = mock(JiraWorkflow.class);
        final Status targetStatus = mock(Status.class);
        final List<GenericValue> selected = new ArrayList<GenericValue>();
        final IllegalStateException nativeFailure = new IllegalStateException("Native write failed after mutation");
        boolean failWorkflowAfterWrite, failUpdateAfterWrite;
        Runnable afterWorkflowWrite;
        Runnable afterSelection;
        Domain(int count) throws Exception {
            when(project.getId()).thenReturn(10L);
            when(from.getId()).thenReturn("A"); when(to.getId()).thenReturn("B");
            when(original.getName()).thenReturn("A"); when(target.getName()).thenReturn("B");
            when(targetStatus.getId()).thenReturn("B");
            when(database.getDelegatorInterface()).thenReturn(mock(org.ofbiz.core.entity.DelegatorInterface.class));
            when(database.getCountByAnd(anyString(), anyMap())).thenThrow(new AssertionError("Migration must count native entities directly"));
            IssueTypePagingFixture.installPages(database, () -> models.keySet(), (id, field) -> {
                Model model = models.get(id);
                if ("id".equals(field)) return id;
                if ("project".equals(field)) return model.projectId;
                if ("type".equals(field)) return model.type;
                return null;
            }, size -> {
                if (size > 1 && afterSelection != null) {
                    Runnable callback = afterSelection; afterSelection = null; callback.run();
                }
            });
            when(database.findById(eq("Issue"), anyLong())).thenAnswer(call -> models.get(call.getArgument(1)).row);
            when(issues.getIssueObject(anyLong())).thenAnswer(call -> models.get(call.getArgument(0)).issue);
            when(workflows.getStore().findEntry(anyLong())).thenAnswer(call -> {
                Model model = models.get(call.getArgument(0)); WorkflowEntry entry = mock(WorkflowEntry.class);
                when(entry.getWorkflowName()).thenAnswer(invocation -> model.workflow); return entry;
            });
            doAnswer(call -> {
                Model model = models.get(((MutableIssue) call.getArgument(0)).getId());
                model.workflow = ((JiraWorkflow) call.getArgument(1)).getName();
                model.status = ((Status) call.getArgument(2)).getId(); model.updated++;
                if (afterWorkflowWrite != null) { Runnable callback = afterWorkflowWrite; afterWorkflowWrite = null; callback.run(); }
                if (failWorkflowAfterWrite) throw nativeFailure;
                return null;
            }).when(workflows).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
            when(issues.updateIssue(any(), any(MutableIssue.class), any(), anyBoolean())).thenAnswer(call -> {
                MutableIssue issue = call.getArgument(1); models.get(issue.getId()).updated++;
                if (failUpdateAfterWrite) throw nativeFailure;
                return issue;
            });
            for (long id = 1; id <= count; id++) selected.add(add(id, "A").row);
        }
        Model add(long id, String state) { Model model = new Model(id, state); models.put(id, model); return model; }
        int migrate() {
            IssueTypeChangePlan.Mapping mapping = new IssueTypeChangePlan.Mapping(from, to, original, target,
                    Collections.singletonMap("A", targetStatus), selected.size());
            return migrator.migrate(project, Collections.singletonList(mapping), null, mock(IssueTypeExecution.class));
        }
    }
}
