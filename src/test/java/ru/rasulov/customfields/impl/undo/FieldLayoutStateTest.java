package ru.rasulov.customfields.impl.undo;

import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FieldLayoutStateTest {
    @Test
    public void canonicalSnapshotIgnoresItemOrderButOrderedSnapshotDoesNot() {
        FieldLayoutItem a = item("customfield_1", false, false, "text");
        FieldLayoutItem b = item("customfield_2", false, false, "text");
        FieldLayout forward = layout(a, b), reversed = layout(b, a);
        assertEquals(FieldLayoutState.canonicalSnapshot("n", forward), FieldLayoutState.canonicalSnapshot("n", reversed));
        assertFalse(FieldLayoutState.snapshot("n", forward).equals(FieldLayoutState.snapshot("n", reversed)));
    }

    @Test
    public void reorderedReloadMatchesExpectedRequiredStateButOtherChangesDoNot() {
        FieldLayout before = layout(item("customfield_1", false, false, "text"), item("customfield_2", false, false, "text"));
        String expected = FieldLayoutState.canonicalSnapshotWithRequired("n", before, "customfield_1", true);
        FieldLayout reloaded = layout(item("customfield_2", false, false, "text"), item("customfield_1", true, false, "text"));
        assertTrue(FieldLayoutState.sameState(FieldLayoutState.canonicalSnapshot("n", reloaded), expected));
        FieldLayout renderer = layout(item("customfield_2", false, false, "wiki"), item("customfield_1", true, false, "text"));
        assertFalse(FieldLayoutState.sameState(FieldLayoutState.canonicalSnapshot("n", renderer), expected));
        FieldLayout hidden = layout(item("customfield_2", false, true, "text"), item("customfield_1", true, false, "text"));
        assertFalse(FieldLayoutState.sameState(FieldLayoutState.canonicalSnapshot("n", hidden), expected));
        FieldLayout missing = layout(item("customfield_1", true, false, "text"));
        assertFalse(FieldLayoutState.sameState(FieldLayoutState.canonicalSnapshot("n", missing), expected));
        FieldLayout otherRequired = layout(item("customfield_2", true, false, "text"), item("customfield_1", true, false, "text"));
        assertFalse(FieldLayoutState.sameState(FieldLayoutState.canonicalSnapshot("n", otherRequired), expected));
    }

    private static FieldLayoutItem item(String id, boolean required, boolean hidden, String renderer) {
        FieldLayoutItem item = mock(FieldLayoutItem.class);
        OrderableField field = mock(OrderableField.class);
        when(field.getId()).thenReturn(id);
        when(item.getOrderableField()).thenReturn(field);
        when(item.isRequired()).thenReturn(required);
        when(item.isHidden()).thenReturn(hidden);
        when(item.getRendererType()).thenReturn(renderer);
        return item;
    }

    private static FieldLayout layout(FieldLayoutItem... items) {
        FieldLayout layout = mock(FieldLayout.class);
        when(layout.getId()).thenReturn(10L);
        when(layout.getName()).thenReturn("n");
        when(layout.isDefault()).thenReturn(false);
        when(layout.getFieldLayoutItems()).thenReturn(Arrays.asList(items));
        return layout;
    }
}
