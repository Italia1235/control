package ru.rasulov.common.impl.undo;

import com.atlassian.jira.ofbiz.OfBizListIterator;
import org.ofbiz.core.entity.GenericValue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A cursor over fixed rows, read one at a time like Jira's. */
public final class Cursors {
    private Cursors() { }

    public static OfBizListIterator over(List<GenericValue> rows) {
        Iterator<GenericValue> source = new ArrayList<GenericValue>(rows).iterator();
        OfBizListIterator cursor = mock(OfBizListIterator.class);
        when(cursor.next()).thenAnswer(call -> source.hasNext() ? source.next() : null);
        return cursor;
    }
}
