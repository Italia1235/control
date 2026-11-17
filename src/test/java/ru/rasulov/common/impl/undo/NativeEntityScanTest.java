package ru.rasulov.common.impl.undo;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NativeEntityScanTest {

    private final OfBizDelegator db = mock(OfBizDelegator.class);
    private final OfBizListIterator rows = mock(OfBizListIterator.class);
    private int read;

    private void rows(String... descriptors) {
        Iterator<String> source = Arrays.asList(descriptors).iterator();
        when(db.findListIteratorByCondition(eq("Workflow"), any(), any(), any(), any(), any())).thenReturn(rows);
        when(rows.next()).thenAnswer(call -> {
            if (!source.hasNext()) {
                return null;
            }
            read++;
            GenericValue row = mock(GenericValue.class);
            when(row.getString("descriptor")).thenReturn(source.next());
            return row;
        });
    }

    @Test
    public void stopsReadingAtTheFirstMatchAndClosesTheIterator() {
        rows("a", "needle", "c", "d", "e");

        boolean found = NativeEntityScan.anyMatch(db, "Workflow", null, Collections.singletonList("descriptor"),
                row -> row.getString("descriptor").contains("needle"));

        assertTrue(found);
        assertEquals("rows past the first match must not be read", 2, read);
        verify(rows).close();
    }

    @Test
    public void readsEveryRowWhenNothingMatchesAndClosesTheIterator() {
        rows("a", "b", "c");

        boolean found = NativeEntityScan.anyMatch(db, "Workflow", null, Collections.singletonList("descriptor"),
                row -> row.getString("descriptor").contains("needle"));

        assertFalse(found);
        assertEquals(3, read);
        verify(rows).close();
    }

    @Test
    public void neverMaterializesTheWholeTable() {
        rows("a");

        NativeEntityScan.anyMatch(db, "Workflow", null, Collections.singletonList("descriptor"), row -> false);

        verify(db, never()).findAll(any());
    }
}
