package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.opensymphony.workflow.spi.WorkflowEntry;
import org.ofbiz.core.entity.*;

import java.sql.Timestamp;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Compact native state for large migrations; issue/entry doubles are reused, never cached by production. */
final class IssueTypePagingFixture {
    final TreeMap<Long, State> states = new TreeMap<Long, State>();
    final OfBizDelegator database = mock(OfBizDelegator.class, withSettings().stubOnly());
    final IssueManager issues = mock(IssueManager.class, withSettings().stubOnly());
    final WorkflowManager workflows = mock(WorkflowManager.class, withSettings().stubOnly().defaultAnswer(RETURNS_DEEP_STUBS));
    final Project project = mock(Project.class);
    final IssueType from = mock(IssueType.class), to = mock(IssueType.class);
    final JiraWorkflow original = mock(JiraWorkflow.class), target = mock(JiraWorkflow.class);
    final Status targetStatus = mock(Status.class);
    final IssueTypeMigrator migrator = new IssueTypeMigrator(database, issues, workflows);
    final List<Long> written = new ArrayList<Long>();
    final List<Integer> pageSizes = new ArrayList<Integer>();
    Runnable afterWorkflow;
    Runnable afterUpdate;
    int maxRows;
    private long issueId, entryId, nativeRowId;

    IssueTypePagingFixture(int count) throws Exception {
        when(project.getId()).thenReturn(10L);
        when(project.getKey()).thenReturn("ALPHA");
        when(project.getName()).thenReturn("Alpha");
        when(from.getId()).thenReturn("A"); when(to.getId()).thenReturn("B");
        when(original.getName()).thenReturn("A"); when(target.getName()).thenReturn("B");
        when(targetStatus.getId()).thenReturn("B");
        for (long id = 1; id <= count; id++) add(id);
        when(database.getDelegatorInterface()).thenReturn(mock(DelegatorInterface.class, withSettings().stubOnly()));
        when(database.findByCondition(eq("Issue"), any(), any(), any()))
                .thenThrow(new AssertionError("Unbounded issue-list selection must not be used"));
        installPages(database, () -> states.keySet(), (id, field) -> {
            State state = states.get(id);
            if ("id".equals(field)) return id;
            if ("project".equals(field)) return 10L;
            if ("type".equals(field)) return state.type;
            return null;
        }, size -> { maxRows = Math.max(maxRows, size); pageSizes.add(size); });
        GenericValue nativeRow = mock(GenericValue.class, withSettings().stubOnly());
        when(database.findById(eq("Issue"), anyLong())).thenAnswer(call -> {
            nativeRowId = call.getArgument(1); return nativeRow;
        });
        when(nativeRow.getAllFields()).thenAnswer(call -> {
            State state = states.get(nativeRowId);
            Map<String, Object> fields = new TreeMap<String, Object>();
            fields.put("id", nativeRowId); fields.put("project", 10L);
            fields.put("type", state.type); fields.put("workflow", state.workflow);
            fields.put("status", state.status); fields.put("updated", state.updated);
            return fields;
        });
        MutableIssue issue = mock(MutableIssue.class, withSettings().stubOnly());
        when(issues.getIssueObject(anyLong())).thenAnswer(call -> { issueId = call.getArgument(0); return issue; });
        when(issue.getId()).thenAnswer(call -> issueId);
        when(issue.getWorkflowId()).thenAnswer(call -> issueId);
        when(issue.getProjectId()).thenReturn(10L);
        when(issue.getIssueTypeId()).thenAnswer(call -> states.get(issueId).type);
        when(issue.getStatusId()).thenAnswer(call -> states.get(issueId).status);
        when(issue.getUpdated()).thenAnswer(call -> new Timestamp(states.get(issueId).updated));
        doAnswer(call -> { states.get(issueId).type = ((IssueType) call.getArgument(0)).getId(); return null; })
                .when(issue).setIssueTypeObject(any());
        WorkflowEntry entry = mock(WorkflowEntry.class, withSettings().stubOnly());
        when(workflows.getStore().findEntry(anyLong())).thenAnswer(call -> { entryId = call.getArgument(0); return entry; });
        when(entry.getWorkflowName()).thenAnswer(call -> states.get(entryId).workflow);
        doAnswer(call -> {
            State state = states.get(issueId); state.workflow = "B"; state.status = "B"; state.updated++;
            if (afterWorkflow != null) afterWorkflow.run();
            return null;
        }).when(workflows).migrateIssueToWorkflow(any(MutableIssue.class), any(JiraWorkflow.class), any(Status.class));
        when(issues.updateIssue(any(), any(), any(), anyBoolean())).thenAnswer(call -> {
            State state = states.get(issueId); state.updated++; state.writes++; written.add(issueId);
            if (afterUpdate != null) afterUpdate.run();
            return issue;
        });
    }

    void add(long id) { states.put(id, new State()); }
    IssueTypeChangePlan.Mapping mapping() {
        return new IssueTypeChangePlan.Mapping(from, to, original, target,
                Collections.singletonMap("A", targetStatus), states.size());
    }
    int migrate(IssueTypeExecution progress) {
        return migrator.migrate(project, Collections.singletonList(mapping()), null, progress);
    }
    static final class State {
        String type = "A", workflow = "A", status = "A";
        long updated = 100L;
        int writes;
    }

    static void installPages(OfBizDelegator database, Supplier<Collection<Long>> ids,
                             BiFunction<Long, String, Object> value, java.util.function.IntConsumer pageObserved) {
        when(database.findListIteratorByCondition(eq("Issue"), any(), isNull(), any(), any(), any()))
                .thenAnswer(call -> {
                    EntityCondition condition = call.getArgument(1);
                    List<String> order = call.getArgument(4);
                    EntityFindOptions options = call.getArgument(5);
                    if (options == null || options.getMaxResults() < 1 || options.getMaxResults() > 50)
                        throw new AssertionError("Native selection must request at most 50 rows");
                    List<Long> sorted = new ArrayList<Long>(ids.get());
                    Collections.sort(sorted);
                    if (order.equals(Collections.singletonList("id DESC"))) Collections.reverse(sorted);
                    else if (!order.equals(Collections.singletonList("id ASC"))) throw new AssertionError("Missing deterministic ID order");
                    List<GenericValue> page = new ArrayList<GenericValue>();
                    for (Long id : sorted) {
                        if (!matches(condition, id, value)) continue;
                        GenericValue row = mock(GenericValue.class, withSettings().stubOnly());
                        when(row.getLong("id")).thenReturn(id);
                        String selectedType = (String) value.apply(id, "type");
                        when(row.getString("type")).thenReturn(selectedType);
                        page.add(row);
                        if (page.size() == options.getMaxResults()) break;
                    }
                    pageObserved.accept(page.size());
                    OfBizListIterator iterator = mock(OfBizListIterator.class, withSettings().stubOnly());
                    Iterator<GenericValue> cursor = page.iterator();
                    when(iterator.next()).thenAnswer(invocation -> cursor.hasNext() ? cursor.next() : null);
                    when(iterator.getPartialList(eq(1), anyInt())).thenAnswer(invocation -> {
                        int limit = invocation.getArgument(1);
                        if (limit > 50) throw new AssertionError("Unbounded partial list");
                        if (page.isEmpty())
                            throw new IllegalStateException("Could not move to the start position of 1, there are probably not that many results for this find.");
                        return new ArrayList<GenericValue>(page.subList(0, Math.min(limit, page.size())));
                    });
                    return iterator;
                });
    }

    private static boolean matches(EntityCondition condition, Long id, BiFunction<Long, String, Object> value) {
        if (condition instanceof EntityFieldMap) {
            EntityFieldMap fields = (EntityFieldMap) condition;
            Iterator<String> keys = fields.getFieldKeyIterator();
            while (keys.hasNext()) { String key = keys.next(); if (!Objects.equals(fields.getField(key), value.apply(id, key))) return false; }
            return true;
        }
        if (condition instanceof EntityConditionList) {
            EntityConditionList list = (EntityConditionList) condition;
            if (!EntityOperator.AND.equals(list.getOperator())) throw new AssertionError("Unsupported boolean operator");
            for (int i = 0; i < list.getConditionListSize(); i++) if (!matches(list.getCondition(i), id, value)) return false;
            return true;
        }
        EntityExpr expression = (EntityExpr) condition;
        Object left = value.apply(id, (String) expression.getLhs()), right = expression.getRhs();
        EntityOperator operator = expression.getOperator();
        if (EntityOperator.EQUALS.equals(operator)) return Objects.equals(left, right);
        if (EntityOperator.IN.equals(operator)) return ((Collection<?>) right).contains(left);
        long comparison = Long.compare(((Number) left).longValue(), ((Number) right).longValue());
        if (EntityOperator.GREATER_THAN.equals(operator)) return comparison > 0;
        if (EntityOperator.LESS_THAN_EQUAL_TO.equals(operator)) return comparison <= 0;
        throw new AssertionError("Unsupported condition " + expression);
    }
}
