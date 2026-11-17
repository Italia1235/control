package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import org.junit.Test;
import org.ofbiz.core.entity.DelegatorInterface;
import org.ofbiz.core.entity.EntityConditionList;
import org.ofbiz.core.entity.EntityExpr;
import org.ofbiz.core.entity.EntityFindOptions;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.undo.Cursors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class StepOccupancyTest {
    private final OfBizDelegator database = mock(OfBizDelegator.class);
    private final DelegatorInterface nativeDatabase = mock(DelegatorInterface.class);

    @Test
    public void countsNativeCurrentStepsAcrossBoundedEntryPagesIncludingZeros() throws Exception {
        when(database.getDelegatorInterface()).thenReturn(nativeDatabase);
        List<OfBizListIterator> cursors = new ArrayList<OfBizListIterator>();
        List<Long> afterIds = new ArrayList<Long>();
        when(database.findListIteratorByCondition(eq("OSWorkflowEntry"), any(), isNull(), any(), any(), any()))
                .thenAnswer(call -> {
                    EntityConditionList condition = call.getArgument(1);
                    EntityExpr name = (EntityExpr) condition.getCondition(0);
                    EntityExpr id = (EntityExpr) condition.getCondition(1);
                    assertEquals("name", name.getLhs());
                    assertEquals("Shared", name.getRhs());
                    assertEquals(EntityOperator.EQUALS, name.getOperator());
                    assertEquals("id", id.getLhs());
                    assertEquals(EntityOperator.GREATER_THAN, id.getOperator());
                    long after = (Long) id.getRhs();
                    afterIds.add(after);
                    EntityFindOptions options = call.getArgument(5);
                    assertTrue(options.getMaxResults() > 0 && options.getMaxResults() <= 500);
                    assertEquals(Collections.singletonList("id"), call.getArgument(3));
                    assertEquals(Collections.singletonList("id ASC"), call.getArgument(4));
                    List<GenericValue> page = new ArrayList<GenericValue>();
                    for (long entry = after + 1; entry <= 1001 && page.size() < options.getMaxResults(); entry++) {
                        GenericValue row = mock(GenericValue.class);
                        when(row.getLong("id")).thenReturn(entry);
                        page.add(row);
                    }
                    OfBizListIterator cursor = Cursors.over(page);
                    cursors.add(cursor);
                    return cursor;
                });
        when(nativeDatabase.countByCondition(eq("OSCurrentStep"), eq("id"), any(), isNull())).thenAnswer(call -> {
            // No cursor may be held open while a second native query is executed.
            verify(cursors.get(cursors.size() - 1)).close();
            EntityConditionList condition = call.getArgument(2);
            EntityExpr entries = (EntityExpr) condition.getCondition(0);
            EntityExpr step = (EntityExpr) condition.getCondition(1);
            assertEquals("entryId", entries.getLhs());
            assertEquals(EntityOperator.IN, entries.getOperator());
            List<?> ids = (List<?>) entries.getRhs();
            assertTrue(!ids.isEmpty() && ids.size() <= 500);
            assertEquals("stepId", step.getLhs());
            assertEquals(EntityOperator.EQUALS, step.getOperator());
            assertTrue("Native stepId is Integer, not Long", step.getRhs() instanceof Integer);
            int stepId = (Integer) step.getRhs();
            return stepId == 2 ? ids.size() : stepId == 4 && ids.contains(1001L) ? 1 : 0;
        });

        Map<Integer, Long> result = new StepOccupancy(database).occupied("Shared",
                new TreeSet<Integer>(Arrays.asList(2, 4, 9)));

        Map<Integer, Long> expected = new TreeMap<Integer, Long>();
        expected.put(2, 1001L);
        expected.put(4, 1L);
        expected.put(9, 0L);
        assertEquals(expected, result);
        assertEquals(Arrays.asList(0L, 500L, 1000L), afterIds);
        verify(nativeDatabase, times(9)).countByCondition(eq("OSCurrentStep"), eq("id"), any(), isNull());
        verify(database, never()).findByCondition(any(), any(), any(), any());
    }

    @Test
    public void emptyWorkflowRetainsRequestedZeroCountsWithoutCurrentStepQueries() {
        OfBizListIterator cursor = Cursors.over(Collections.<GenericValue>emptyList());
        when(database.findListIteratorByCondition(eq("OSWorkflowEntry"), any(), isNull(), any(), any(), any()))
                .thenReturn(cursor);

        assertEquals(Collections.singletonMap(7, 0L),
                new StepOccupancy(database).occupied("Empty", Collections.singleton(7)));
        verify(cursor).close();
        verify(database, never()).getDelegatorInterface();
    }

    @Test
    public void noRequestedStepsDoesNotQueryJira() {
        assertTrue(new StepOccupancy(database).occupied("Shared", Collections.emptySet()).isEmpty());
        verifyNoInteractions(database);
    }
}
