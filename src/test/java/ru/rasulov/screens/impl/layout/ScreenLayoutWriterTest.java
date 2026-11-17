package ru.rasulov.screens.impl.layout;

import org.junit.Test;
import ru.rasulov.common.impl.screen.ScreenLayout;
import java.util.List;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ScreenLayoutWriterTest {
    private final LayoutFixture jira = new LayoutFixture();
    private final ScreenLayoutWriter writer = new ScreenLayoutWriter(jira.manager);

    @Test public void removesLabelsByListIndexDespiteGappedPositions() {
        LayoutFixture.Screen screen = gappedScreen();
        ScreenLayout before = ScreenLayout.of(screen.screen);
        ScreenLayoutWriter.Result result = writer.remove(before, List.of("labels"));
        assertTrue(result.succeeded());
        assertEquals(List.of("summary", "assignee"), screen.fields());
        assertEquals(Long.valueOf(102), result.actual().item("assignee").layoutItemId());
        assertEquals(1, result.actual().item("assignee").position());
        assertTrue(result.actual().item("assignee").shownWhenEmpty());
        assertTrue(before.remove("labels").matches(result.actual()));
    }
    @Test public void removesAssigneeByListIndexDespiteGappedPositions() {
        LayoutFixture.Screen screen = gappedScreen();
        ScreenLayout before = ScreenLayout.of(screen.screen);
        ScreenLayoutWriter.Result result = writer.remove(before, List.of("assignee"));
        assertTrue(result.succeeded());
        assertEquals(List.of("summary", "labels"), screen.fields());
        assertEquals(Long.valueOf(101), result.actual().item("labels").layoutItemId());
        assertEquals(1, result.actual().item("labels").position());
        assertFalse(result.actual().item("labels").shownWhenEmpty());
        assertTrue(before.remove("assignee").matches(result.actual()));
    }
    @Test public void recomputesIndexAfterEachRemovalResequencesTheTab() {
        LayoutFixture.Screen screen = gappedScreen();
        ScreenLayout before = ScreenLayout.of(screen.screen);
        ScreenLayoutWriter.Result result = writer.remove(before, List.of("labels", "assignee"));
        assertTrue(result.succeeded());
        assertEquals(List.of("summary"), screen.fields());
        assertEquals(Long.valueOf(100), result.actual().item("summary").layoutItemId());
        assertEquals(0, result.actual().item("summary").position());
        assertTrue(result.actual().item("summary").shownWhenEmpty());
        assertEquals(2, jira.removalCalls);
    }
    @Test public void refusesToWriteWhenIndexLookupNoLongerMatchesTheRequestedField() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        when(screen.tab.getFieldScreenLayoutItem(1)).thenReturn(screen.rows.get(0));
        ScreenLayoutWriter.Result result = writer.remove(ScreenLayout.of(screen.screen), List.of("labels"));
        assertFalse(result.succeeded());
        assertTrue(result.attempted().isEmpty());
        assertTrue(result.message().contains("changed before removal"));
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields());
        assertNotNull(result.actual().item("labels"));
        assertEquals(0, jira.removalCalls);
    }
    private LayoutFixture.Screen gappedScreen() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        screen.rows.set(1, LayoutFixture.row(101, "labels", 2, false));
        screen.rows.set(2, LayoutFixture.row(102, "assignee", 5, true));
        return screen;
    }
    @Test public void refusesConcurrentChangeBeforeAnyNativeCall() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        ScreenLayout before = ScreenLayout.of(screen.screen);
        Collections.swap(screen.rows, 0, 1);
        when(screen.rows.get(0).getPosition()).thenReturn(0);
        when(screen.rows.get(1).getPosition()).thenReturn(1);
        ScreenLayoutWriter.Result result = writer.remove(before, List.of("labels"));
        assertFalse(result.succeeded()); assertTrue(result.attempted().isEmpty());
        assertEquals(List.of("labels", "summary", "assignee"), screen.fields());
        assertEquals(0, jira.removalCalls);
    }
    @Test public void nativeFailureStopsRemainingFieldsWithoutRollbackAndReadsActual() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee", "priority");
        jira.beforeRemove = field -> { if (field.equals("assignee")) throw new IllegalStateException("db failure"); };
        ScreenLayoutWriter.Result result = writer.remove(ScreenLayout.of(screen.screen), List.of("labels", "assignee", "priority"));
        assertFalse(result.succeeded()); assertNotNull(result.actual());
        assertEquals(List.of("summary", "assignee", "priority"), screen.fields());
        assertNull(result.actual().item("labels")); assertNotNull(result.actual().item("assignee"));
        assertTrue(result.attempted().contains("assignee")); assertFalse(result.attempted().contains("priority"));
        assertEquals(2, jira.removalCalls);
    }
    @Test public void exceptionAfterPersistenceDoesNotPretendTheFieldRemained() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels");
        jira.afterRemove = field -> { throw new IllegalStateException("failed after write"); };
        ScreenLayoutWriter.Result result = writer.remove(ScreenLayout.of(screen.screen), List.of("labels"));
        assertFalse(result.succeeded()); assertNull(result.actual().item("labels"));
        assertEquals(List.of("summary"), screen.fields()); assertEquals(1, jira.removalCalls);
    }
    @Test public void persistedMismatchIsReportedWithActualAndNoRetry() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        jira.afterRemove = field -> when(screen.rows.get(0).isShownWhenEmpty()).thenReturn(false);
        ScreenLayoutWriter.Result result = writer.remove(ScreenLayout.of(screen.screen), List.of("labels"));
        assertFalse(result.succeeded()); assertTrue(result.mismatch());
        assertFalse(result.actual().item("summary").shownWhenEmpty());
        assertNull(result.actual().item("labels")); assertEquals(1, jira.removalCalls);
    }
    @Test public void unreadablePoststateIsUnknownRatherThanExpectedState() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels");
        jira.readbackFails = true;
        ScreenLayoutWriter.Result result = writer.remove(ScreenLayout.of(screen.screen), List.of("labels"));
        assertFalse(result.succeeded()); assertNull(result.actual());
        assertTrue(result.message().contains("UNKNOWN")); assertEquals(List.of("summary"), screen.fields());
        assertEquals(1, jira.removalCalls);
    }
}
