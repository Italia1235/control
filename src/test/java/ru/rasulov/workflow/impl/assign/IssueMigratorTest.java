package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowException;
import com.atlassian.jira.workflow.WorkflowManager;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import com.opensymphony.workflow.spi.WorkflowEntry;
import com.opensymphony.workflow.spi.WorkflowStore;
import net.java.ao.Query;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import org.ofbiz.core.entity.DelegatorInterface;
import org.ofbiz.core.entity.EntityFieldMap;
import ru.rasulov.common.impl.undo.JournalCancelledException;
import ru.rasulov.common.impl.undo.JournalStep;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.rasulov.workflow.impl.assign.AssignMocks.status;
import static ru.rasulov.workflow.impl.assign.AssignMocks.workflow;

/** Stateful native Jira doubles exercise forward migration guards and managed-operation boundaries. */
public class IssueMigratorTest {
    private final OfBizDelegator delegator = mock(OfBizDelegator.class);
    private final IssueManager issues = mock(IssueManager.class);
    private final WorkflowManager workflows = mock(WorkflowManager.class);
    private final WorkflowStore store = mock(WorkflowStore.class);
    private final Project project = AssignMocks.project(1L, "ABC", "Alpha", "business");
    private final Status open = status("1", "Open"), review = status("2", "Review"), done = status("3", "Done");
    private final JiraWorkflow original = workflow("jira", open, review, done), target = workflow("Bug flow", open, done);
    private final Map<Long, State> states = new HashMap<Long, State>();
    private final Map<Long, String> entries = new HashMap<Long, String>();
    private final List<GenericValue> rows = new ArrayList<GenericValue>();
    private final AtomicLong sequence = new AtomicLong(1000);
    private UndoTestFixture fixture;
    private IssueMigrator migrator;
    private String operation;
    private Runnable afterMove;

    @Before public void setUp() throws Exception {
        when(original.getDescriptor()).thenReturn(new WorkflowDescriptor());
        when(target.getDescriptor()).thenReturn(new WorkflowDescriptor());
        when(workflows.getWorkflow("jira")).thenReturn(original);
        when(workflows.getWorkflow("Bug flow")).thenReturn(target);
        when(workflows.getStore()).thenReturn(store);
        when(store.findEntry(anyLong())).thenAnswer(call -> {
            String name = entries.get((Long) call.getArgument(0));
            if (name == null) return null;
            WorkflowEntry entry = mock(WorkflowEntry.class); when(entry.getWorkflowName()).thenReturn(name); return entry;
        });
        when(delegator.findListIteratorByCondition(eq("Issue"), any(), isNull(), any(), any(), any())).thenAnswer(call -> {
            org.ofbiz.core.entity.EntityConditionList list = call.getArgument(1);
            org.ofbiz.core.entity.EntityFindOptions options = call.getArgument(5);
            if (options == null || options.getMaxResults() < 1 || options.getMaxResults() > 50)
                throw new AssertionError("Native selection must request at most 50 rows");
            Object selectedProject = null, selectedType = null; long after = 0;
            for (int i = 0; i < list.getConditionListSize(); i++) {
                org.ofbiz.core.entity.EntityExpr expression = (org.ofbiz.core.entity.EntityExpr) list.getCondition(i);
                String field = (String) expression.getLhs();
                if ("project".equals(field)) selectedProject = expression.getRhs();
                else if ("type".equals(field)) selectedType = expression.getRhs();
                else if ("id".equals(field)) after = ((Number) expression.getRhs()).longValue();
            }
            List<Long> ids = new ArrayList<Long>(states.keySet()); Collections.sort(ids);
            List<GenericValue> page = new ArrayList<GenericValue>();
            for (Long id : ids) {
                State state = states.get(id);
                if (id <= after) continue;
                GenericValue row = mock(GenericValue.class); when(row.getLong("id")).thenReturn(id); page.add(row);
                if (page.size() == options.getMaxResults()) break;
            }
            pageSizes.add(page.size());
            return ru.rasulov.common.impl.undo.Cursors.over(page);
        });
        when(delegator.findByCondition(eq("OSCurrentStep"), any(), any(), any())).thenReturn(Collections.<GenericValue>emptyList());
        when(delegator.findByAnd(eq("OSCurrentStep"), anyMap(), anyList())).thenReturn(Collections.<GenericValue>emptyList());
        when(delegator.findById(eq("Issue"), anyLong())).thenAnswer(call -> {
            Long id = call.getArgument(1); State state = states.get(id);
            if (state == null) return null;
            GenericValue row = mock(GenericValue.class);
            when(row.getLong("workflowId")).thenReturn(state.entryId);
            Map<String, Object> fields = new TreeMap<String, Object>();
            fields.put("id", id); fields.put("project", state.projectId); fields.put("type", state.type);
            fields.put("status", state.status.getId()); fields.put("workflowId", state.entryId); fields.put("updated", state.updated);
            fields.put("summary", state.summary);
            when(row.getAllFields()).thenReturn(fields);
            return row;
        });
        DelegatorInterface nativeDatabase = mock(DelegatorInterface.class);
        when(delegator.getDelegatorInterface()).thenReturn(nativeDatabase);
        when(nativeDatabase.countByCondition(eq("ChangeGroup"), eq("id"), any(), isNull())).thenAnswer(call ->
                Math.toIntExact(states.get(((EntityFieldMap) call.getArgument(2)).getField("issue")).changes));
        when(nativeDatabase.countByCondition(eq("OSHistoryStep"), eq("id"), any(), isNull())).thenAnswer(call -> {
            Long entryId = (Long) ((EntityFieldMap) call.getArgument(2)).getField("entryId");
            for (State state : states.values()) if (entryId.equals(state.entryId)) return Math.toIntExact(state.transitions);
            return 0;
        });
        when(delegator.getCountByAnd(anyString(), anyMap())).thenThrow(new AssertionError("Migration must count native entities directly"));
        doAnswer(call -> {
            MutableIssue issue = call.getArgument(0); JiraWorkflow workflow = call.getArgument(1); Status status = call.getArgument(2);
            State state = states.get(issue.getId());
            if (state.fail) throw new WorkflowException("native failure");
            if (!state.status.getId().equals(status.getId())) state.updated++;
            state.status = status; state.entryId = sequence.incrementAndGet(); state.transitions = 0;
            entries.put(state.entryId, workflow.getName());
            if (afterMove != null) afterMove.run();
            if (state.cancel) { state.cancel = false; fixture.journal.cancel(operation, "admin", state.cancelRollback); }
            return null;
        }).when(workflows).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        fixture = UndoTestFixture.create("admin");
        migrator = new IssueMigrator(delegator, issues, workflows, fixture.journal);
    }
    @After public void close() { fixture.close(); }

    @Test public void manyToOneStatusMappingsMigrateEveryOriginalStatusWithoutUndoRows() throws Exception {
        State first = issue(11, review), second = issue(12, done);
        MigrationCount moved = new MigrationCount();
        run(() -> migrator.migrate(project, Collections.singletonMap("10001", "jira"), target,
                Collections.singletonMap("2", done), moved));
        assertEquals("Bug flow", entries.get(first.entryId)); assertEquals("3", first.status.getId());
        assertEquals("Bug flow", entries.get(second.entryId)); assertEquals("3", second.status.getId());
        assertEquals(2, moved.get());
        assertNoUndoRows();
    }
    @Test public void aNativeFailureRetainsTheForwardPrefixAndEscapes() throws Exception {
        State first = issue(11, open), failing = issue(12, open); failing.fail = true;
        try { migrate(); fail("native failure must escape"); } catch (IllegalStateException expected) { }
        assertEquals("Bug flow", entries.get(first.entryId)); assertEquals("jira", entries.get(failing.entryId));
        assertNoUndoRows();
    }
    @Test public void cancellationAtTheNextIssueBoundaryRetainsTheForwardPrefix() throws Exception {
        State first = issue(11, open), second = issue(12, open); first.cancel = true;
        try { migrate(); fail("cancellation must escape"); } catch (JournalCancelledException expected) { }
        assertEquals("Bug flow", entries.get(first.entryId)); assertEquals("jira", entries.get(second.entryId));
        assertNoUndoRows();
    }
    @Test public void unsupportedStatusesAreReportedWithoutMovingTheirIssues() {
        State state = issue(11, review); MigrationCount moved = new MigrationCount();
        List<String> stuck = run(() -> migrator.migrate(project, Collections.singletonMap("10001", "jira"), target, moved));
        assertEquals(Collections.singletonList("ABC-11"), stuck);
        assertEquals("jira", entries.get(state.entryId)); assertEquals(0, moved.get());
    }
    @Test public void alreadyMigratedIssuesAreSkipped() {
        State state = issue(11, open); entries.put(state.entryId, "Bug flow");
        migrate();
        verify(workflows, never()).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
    }
    @Test public void foreignProjectOrTypeChangesAreRejectedWhilePlanning() {
        State state = issue(11, open); state.type = "10002";
        assertMigrationRejected("changed workflow, project or type");
        state.type = "10001"; state.projectId = 2L;
        assertMigrationRejected("changed workflow, project or type");
        verify(workflows, never()).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
    }
    @Test public void aSameMillisecondSummaryEditIsRejectedBeforeTheNextMigration() {
        State first = issue(11, open), second = issue(12, open);
        afterMove = () -> second.summary = "Foreign edit";
        assertMigrationRejected("changed before its migration");
        assertEquals("Bug flow", entries.get(first.entryId)); assertEquals("jira", entries.get(second.entryId));
    }
    @Test public void aTransitionReturningToTheSameStatusIsRejectedBeforeTheNextMigration() {
        State first = issue(11, open), second = issue(12, open);
        afterMove = () -> second.transitions++;
        assertMigrationRejected("changed before its migration");
        assertEquals("Bug flow", entries.get(first.entryId)); assertEquals("jira", entries.get(second.entryId));
    }
    @Test public void aNativeWriteThatDoesNotReachTheTargetIsRejected() {
        State state = issue(11, open);
        doNothing().when(workflows).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        assertMigrationRejected("changed during migration");
        assertEquals("jira", entries.get(state.entryId));
    }
    private void assertMigrationRejected(String reason) {
        try { migrate(); fail("migration must reject foreign or ambiguous state"); }
        catch (IllegalStateException expected) { assertTrue(expected.toString(), expected.getMessage().contains(reason)); }
    }
    private void migrate() { run(() -> migrator.migrate(project, Collections.singletonMap("10001", "jira"), target, new MigrationCount())); }
    private <T> T run(Supplier<T> work) {
        String session = fixture.journal.createSession("admin");
        String set = fixture.journal.createChangeSet(session, "admin");
        operation = fixture.journal.openOperation("assign_workflow", "admin", session, set);
        return fixture.journal.runOperation(operation, work);
    }
    private void assertNoUndoRows() throws Exception {
        assertEquals(0, fixture.entityManager.find(JournalStep.class,
                Query.select().where("OPERATION_KEY = ?", operation)).length);
    }
    private State issue(long id, Status status) {
        State state = new State(); state.status = status; state.entryId = sequence.incrementAndGet();
        entries.put(state.entryId, "jira"); states.put(id, state);
        MutableIssue issue = mock(MutableIssue.class);
        when(issue.getId()).thenReturn(id); when(issue.getKey()).thenReturn("ABC-" + id);
        when(issue.getProjectId()).thenAnswer(call -> state.projectId); when(issue.getIssueTypeId()).thenAnswer(call -> state.type);
        when(issue.getStatus()).thenAnswer(call -> state.status); when(issue.getWorkflowId()).thenAnswer(call -> state.entryId);
        when(issue.getUpdated()).thenAnswer(call -> new Timestamp(state.updated)); when(issues.getIssueObject(id)).thenReturn(issue);
        GenericValue row = mock(GenericValue.class); when(row.getLong("id")).thenReturn(id); rows.add(row);
        return state;
    }
    private final List<Integer> pageSizes = new ArrayList<Integer>();
    @Test public void aLargeTypeIsReadInBoundedPagesAndEveryIssueIsMigrated() throws Exception {
        for (long id = 1; id <= 120; id++) issue(id, open);
        MigrationCount moved = new MigrationCount();
        run(() -> migrator.migrate(project, Collections.singletonMap("10001", "jira"), target, moved));
        assertEquals(120, moved.get());
        for (long id = 1; id <= 120; id++) assertEquals("Bug flow", entries.get(states.get(id).entryId));
        assertTrue(pageSizes.toString(), pageSizes.size() >= 3);
        for (int size : pageSizes) assertTrue(size <= 50);
    }
    @Test public void oneSnapshotReadsEachNativeFactOnceAndKeepsEveryRecordedField() throws Exception {
        State state = issue(11, open); state.changes = 3; state.transitions = 2;
        DelegatorInterface nativeDatabase = delegator.getDelegatorInterface();
        clearInvocations(nativeDatabase, delegator);

        Map<String, Object> snapshot = IssueMigrationState.issue(issues.getIssueObject(11L), workflows, delegator);

        verify(nativeDatabase, times(1)).countByCondition(eq("ChangeGroup"), eq("id"), any(), isNull());
        verify(nativeDatabase, times(1)).countByCondition(eq("OSHistoryStep"), eq("id"), any(), isNull());
        verify(delegator, never()).findByCondition(eq("OSCurrentStep"), any(), any(), any());
        verify(delegator, times(1)).findByAnd(eq("OSCurrentStep"), anyMap(), anyList());
        assertEquals(3L, ((Number) snapshot.get("changeCount")).longValue());
        assertEquals(2L, ((Number) snapshot.get("historyCount")).longValue());
        for (String key : Arrays.asList("id", "key", "projectId", "typeId", "workflow", "status", "entryId", "updated",
                "steps", "stateHash")) assertTrue(key, snapshot.containsKey(key));
    }
    private static final class State {
        Long projectId = 1L, entryId; String type = "10001"; Status status; long updated = 100, changes, transitions;
        String summary = "Original summary";
        boolean fail, cancel, cancelRollback = true;
    }
}
