package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import com.atlassian.jira.project.Project;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class IssueStatusCounterTest {

    private final OfBizDelegator delegator = mock(OfBizDelegator.class);
    private final OfBizListIterator rows = mock(OfBizListIterator.class);
    private final Project project = AssignMocks.project(1L, "ABC", "Alpha", "business");

    private static GenericValue row(String type, String status) {
        GenericValue row = mock(GenericValue.class);
        when(row.getString("type")).thenReturn(type);
        when(row.getString("status")).thenReturn(status);
        return row;
    }

    @Test
    public void countsIssuesByTypeAndStatusInOnePassAndClosesTheCursor() {
        when(delegator.findListIteratorByCondition(eq("Issue"), any(), any(), any(), any(), any())).thenReturn(rows);
        GenericValue firstBug = row("10001", "1");
        GenericValue secondBug = row("10001", "1");
        GenericValue doneBug = row("10001", "3");
        GenericValue task = row("10002", "1");
        when(rows.next()).thenReturn(firstBug, secondBug, doneBug, task, null);

        IssueUsage usage = new IssueStatusCounter(delegator).count(project);

        assertEquals(3, usage.issues("10001"));
        assertEquals(2, (int) usage.statuses("10001").get("1"));
        assertEquals(1, (int) usage.statuses("10001").get("3"));
        assertEquals(1, usage.issues("10002"));
        assertEquals(Arrays.asList("10001", "10002"), Arrays.asList(usage.issueTypeIds().toArray()));
        verify(rows).close();
    }

    @Test
    public void aProjectWithoutIssuesHasNoTypes() {
        when(delegator.findListIteratorByCondition(eq("Issue"), any(), any(), any(), any(), any())).thenReturn(rows);
        when(rows.next()).thenReturn(null);

        IssueUsage usage = new IssueStatusCounter(delegator).count(project);

        assertTrue(usage.issueTypeIds().isEmpty());
        assertEquals(0, usage.issues("10001"));
        assertEquals(Collections.emptyMap(), usage.statuses("10001"));
    }

    @Test public void deadlineStopsEntryReadsButCountsEveryRemainingIssueAndFailsClosed() throws Exception {
        com.atlassian.jira.workflow.WorkflowManager workflows = mock(com.atlassian.jira.workflow.WorkflowManager.class);
        com.opensymphony.workflow.spi.WorkflowStore store = mock(com.opensymphony.workflow.spi.WorkflowStore.class);
        when(workflows.getStore()).thenReturn(store);
        com.atlassian.jira.workflow.AssignableWorkflowScheme scheme =
                mock(com.atlassian.jira.workflow.AssignableWorkflowScheme.class);
        when(scheme.getActualWorkflow("10001")).thenReturn("Target");
        com.opensymphony.workflow.spi.WorkflowEntry entry = mock(com.opensymphony.workflow.spi.WorkflowEntry.class);
        when(entry.getWorkflowName()).thenReturn("Old");
        when(store.findEntry(1L)).thenReturn(entry);
        when(delegator.findListIteratorByCondition(eq("Issue"), any(), any(), any(), any(), any())).thenReturn(rows);
        GenericValue first = row("10001", "1");
        when(first.getLong("workflowId")).thenReturn(1L);
        when(first.getLong("number")).thenReturn(1L);
        GenericValue second = row("10001", "3");
        GenericValue third = row("10001", "3");
        when(rows.next()).thenReturn(first, second, third, null);
        long budget = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                ru.rasulov.workflow.api.WorkflowAssignLimits.NATIVE_DIAGNOSIS_BUDGET_MILLIS);
        long[] ticks = {0L, 0L, budget, budget};
        java.util.concurrent.atomic.AtomicInteger tick = new java.util.concurrent.atomic.AtomicInteger();
        IssueStatusCounter.Scan scan = new IssueStatusCounter(delegator, () -> ticks[tick.getAndIncrement()])
                .scan(project, scheme, null, workflows);
        assertEquals(3, scan.usage.issues("10001"));
        assertEquals(2, (int) scan.usage.statuses("10001").get("3"));
        assertTrue(scan.diagnosis.contains("проверено 1 из 3 задач"));
        assertTrue(scan.diagnosis.contains("at least 1 issue(s)"));
        assertTrue(scan.diagnosis.contains("ABC-1"));
        assertTrue(scan.diagnosis.contains("total remainder is unknown"));
        verify(store).findEntry(1L);
        org.mockito.Mockito.verifyNoMoreInteractions(store);
        verify(rows).close();
    }

    @Test public void expirationWithoutObservedMismatchStillRefusesDiscovery() {
        when(delegator.findListIteratorByCondition(eq("Issue"), any(), any(), any(), any(), any())).thenReturn(rows);
        GenericValue first = row("10001", "1");
        when(rows.next()).thenReturn(first, null);
        java.util.concurrent.atomic.AtomicLong time = new java.util.concurrent.atomic.AtomicLong();
        long budget = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                ru.rasulov.workflow.api.WorkflowAssignLimits.NATIVE_DIAGNOSIS_BUDGET_MILLIS);
        IssueStatusCounter.Scan scan = new IssueStatusCounter(delegator, () -> time.getAndAdd(budget))
                .scan(project, mock(com.atlassian.jira.workflow.AssignableWorkflowScheme.class), null,
                        mock(com.atlassian.jira.workflow.WorkflowManager.class));
        assertTrue(scan.diagnosis.contains("проверено 0 из 1 задач"));
        assertTrue(scan.diagnosis.contains("inspection was not performed"));
        assertTrue(scan.diagnosis.contains("inspection result and total remainder are unknown"));
        assertEquals(1, scan.usage.issues("10001"));
    }
}
