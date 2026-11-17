package ru.rasulov.screens.impl.layout;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.impl.screen.ScreenLayout;
import ru.rasulov.screens.api.dto.layout.LayoutStatus;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ScreenLayoutMoverWriterTest {
    private final LayoutFixture jira = new LayoutFixture();
    private final ScreenLayoutWriter writer = new ScreenLayoutWriter(jira.manager);

    private LayoutFixture.Screen gapped() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        screen.rows.get(1).setPosition(2); screen.rows.get(2).setPosition(5);
        return screen;
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"}) public void sortsNonAscendingTargetIndicesAndRetainsIdsAndFlags() {
        LayoutFixture.Screen screen = gapped();
        ScreenLayout before = ScreenLayout.of(screen.screen), after = before.move("assignee", 10L, 0);
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, after);
        assertEquals(LayoutStatus.EDITED, result.status()); assertTrue(after.matches(result.actual()));
        assertEquals(List.of("assignee", "summary", "labels"), screen.fields());
        assertEquals(before.item("assignee").layoutItemId(), result.actual().item("assignee").layoutItemId());
        assertTrue(result.actual().item("assignee").shownWhenEmpty());
        assertFalse(result.actual().item("labels").shownWhenEmpty());
        ArgumentCaptor<Map> map = ArgumentCaptor.forClass(Map.class);
        verify(screen.tab, times(1)).moveFieldScreenLayoutItemToPosition(map.capture());
        // Source order yields target keys 1,2,0. Jira inserts in iteration order, not numeric order.
        assertEquals(List.of(0, 1, 2), List.copyOf(map.getValue().keySet()));
        assertEquals(1, jira.moveCalls); assertEquals(0, jira.removalCalls); assertEquals(0, jira.additionCalls);
    }

    @Test public void transfersByCurrentIndexDespiteGapsPreservingBothShownValues() {
        LayoutFixture.Screen screen = gapped(); LayoutFixture.Tab details = screen.tab(11, "Details", "priority");
        ScreenLayout before = ScreenLayout.of(screen.screen);
        ScreenLayout after = before.move("assignee", 11L, 1).move("labels", 11L, 0);
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, after);
        assertEquals(LayoutStatus.EDITED, result.status()); assertTrue(after.matches(result.actual()));
        assertEquals(List.of("summary"), screen.fields()); assertEquals(List.of("labels", "priority", "assignee"), details.fields());
        assertFalse(result.actual().item("labels").shownWhenEmpty()); assertTrue(result.actual().item("assignee").shownWhenEmpty());
        assertFalse(before.item("labels").layoutItemId().equals(result.actual().item("labels").layoutItemId()));
        assertEquals(before.item("priority").layoutItemId(), result.actual().item("priority").layoutItemId());
        assertEquals(2, jira.removalCalls); assertEquals(2, jira.additionCalls);
        verify(screen.tab, times(2)).removeFieldScreenLayoutItem(1);
    }

    @Test public void exchangesTabsThenReordersEachTabAtMostOnce() {
        LayoutFixture.Screen screen = gapped(); LayoutFixture.Tab details = screen.tab(11, "Details", "priority", "duedate");
        ScreenLayout before = ScreenLayout.of(screen.screen);
        ScreenLayout after = before.move("labels", 11L, 2).move("priority", 10L, 0)
                .move("duedate", 10L, 1).move("summary", 11L, 0);
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, after);
        assertEquals(LayoutStatus.EDITED, result.status()); assertTrue(after.matches(result.actual()));
        assertEquals(List.of("priority", "duedate", "assignee"), screen.fields());
        assertEquals(List.of("summary", "labels"), details.fields());
        verify(screen.tab, atMostOnce()).moveFieldScreenLayoutItemToPosition(anyMap());
        verify(details.tab, atMostOnce()).moveFieldScreenLayoutItemToPosition(anyMap());
    }

    @Test public void checksDestinationIndexBeforeRemovingAnythingFromSource() {
        LayoutFixture.Screen screen = gapped(); LayoutFixture.Tab details = screen.tab(11, "Details", "priority", "duedate");
        ScreenLayout before = ScreenLayout.of(screen.screen);
        when(details.tab.getFieldScreenLayoutItem(1)).thenReturn(details.rows.get(0));
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("labels", 11L, 0));
        assertEquals(LayoutStatus.FAILED, result.status()); assertFalse(result.attempted());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields());
        assertEquals(List.of("priority", "duedate"), details.fields());
        assertEquals(0, jira.removalCalls + jira.additionCalls + jira.moveCalls);
    }

    @Test public void rawPositionChangeRejectsWriteEvenWhenOrderDidNotChange() {
        LayoutFixture.Screen screen = gapped(); ScreenLayout before = ScreenLayout.of(screen.screen);
        screen.rows.get(2).setPosition(6);
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("labels", 10L, 0));
        assertEquals(LayoutStatus.FAILED, result.status()); assertFalse(result.attempted());
        assertEquals(6, result.actual().item("assignee").position()); assertEquals(0, jira.moveCalls);
    }

    @Test public void failureOnSecondTabReportsPartialAndReadsFirstTabActuallyChanged() {
        LayoutFixture.Screen screen = gapped(); screen.tab(11, "Details", "priority", "duedate");
        ScreenLayout before = ScreenLayout.of(screen.screen);
        jira.beforeMove = tab -> { if (tab == 11) throw new IllegalStateException("second tab failed"); };
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("assignee", 10L, 0).move("duedate", 11L, 0));
        assertEquals(LayoutStatus.PARTIAL, result.status()); assertEquals("assignee", result.actual().tab(10L).items().get(0).fieldId());
        assertEquals("priority", result.actual().tab(11L).items().get(0).fieldId()); assertEquals(2, jira.moveCalls);
    }

    @Test public void exceptionAfterMapPersistenceStillReportsObservedOrderAndError() {
        LayoutFixture.Screen screen = gapped(); ScreenLayout before = ScreenLayout.of(screen.screen);
        jira.afterMove = tab -> { throw new IllegalStateException("after write"); };
        ScreenLayout expected = before.move("assignee", 10L, 0);
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, expected);
        assertEquals(LayoutStatus.PARTIAL, result.status()); assertTrue(expected.matches(result.actual())); assertEquals(1, jira.moveCalls);
    }

    @Test public void readbackMismatchReportsActualRatherThanClaimingSuccess() {
        LayoutFixture.Screen screen = gapped(); ScreenLayout before = ScreenLayout.of(screen.screen);
        jira.afterMove = tab -> screen.rows.get(0).setShowWhenEmpty(false);
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("assignee", 10L, 0));
        assertEquals(LayoutStatus.FAILED, result.status()); assertFalse(result.actual().item("assignee").shownWhenEmpty());
        assertEquals(List.of("assignee", "summary", "labels"), screen.fields()); assertEquals(1, jira.moveCalls);
    }

    @Test public void unreadablePoststateIsUnknownNotExpectedLayout() {
        LayoutFixture.Screen screen = gapped(); ScreenLayout before = ScreenLayout.of(screen.screen); jira.readbackFails = true;
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("assignee", 10L, 0));
        assertEquals(LayoutStatus.UNKNOWN, result.status()); assertNull(result.actual()); assertEquals(1, jira.moveCalls);
    }

    @Test public void failedAddRestoresOnceToOriginalIndexWithFlagAndNewId() {
        LayoutFixture.Screen screen = gapped(); LayoutFixture.Tab details = screen.tab(11, "Details", "priority");
        ScreenLayout before = ScreenLayout.of(screen.screen); AtomicInteger calls = new AtomicInteger();
        jira.beforeAdd = field -> { if (calls.getAndIncrement() == 0) throw new IllegalStateException("target add failed"); };
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("assignee", 11L, 0));
        assertEquals(LayoutStatus.PARTIAL, result.status()); assertEquals("assignee", result.restoredField());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields()); assertEquals(List.of("priority"), details.fields());
        assertTrue(result.actual().item("assignee").shownWhenEmpty()); assertEquals(2, jira.additionCalls);
        assertFalse(before.item("assignee").layoutItemId().equals(result.actual().item("assignee").layoutItemId()));
        verify(screen.tab).addFieldScreenLayoutItem("assignee", 2);
    }

    @Test public void failedRestorationReportsPartialWithObservedMissingFieldAndNoRetry() {
        LayoutFixture.Screen screen = gapped(); screen.tab(11, "Details", "priority"); ScreenLayout before = ScreenLayout.of(screen.screen);
        jira.beforeAdd = field -> { throw new IllegalStateException("all adds fail"); };
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("assignee", 11L, 0));
        assertEquals(LayoutStatus.PARTIAL, result.status()); assertNull(result.actual().item("assignee")); assertNull(result.restoredField());
        assertEquals(List.of("summary", "labels"), screen.fields()); assertEquals(2, jira.additionCalls); assertEquals(1, jira.removalCalls);
    }

    @Test public void addThatPersistedThenThrewIsRemovedBeforeSingleRestorationAttempt() {
        LayoutFixture.Screen screen = gapped(); LayoutFixture.Tab details = screen.tab(11, "Details", "priority");
        ScreenLayout before = ScreenLayout.of(screen.screen); AtomicInteger calls = new AtomicInteger();
        jira.afterAdd = field -> { if (calls.getAndIncrement() == 0) throw new IllegalStateException("target persisted then failed"); };
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("assignee", 11L, 0));
        assertEquals(LayoutStatus.PARTIAL, result.status()); assertEquals("assignee", result.restoredField()); assertTrue(result.actual().item("assignee").shownWhenEmpty());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields()); assertEquals(List.of("priority"), details.fields());
        assertEquals(2, jira.additionCalls); assertEquals(2, jira.removalCalls);
    }

    @Test public void restorationThatPersistedThenThrewIsProvenRestoredByReadback() {
        LayoutFixture.Screen screen = gapped(); screen.tab(11, "Details", "priority"); ScreenLayout before = ScreenLayout.of(screen.screen);
        AtomicInteger calls = new AtomicInteger();
        jira.beforeAdd = field -> { if (calls.getAndIncrement() == 0) throw new IllegalStateException("target failure"); };
        jira.afterAdd = field -> { throw new IllegalStateException("restoration persisted then failed"); };
        // labels' original false flag matches the native new-row default, so readback proves the return.
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("labels", 11L, 0));
        assertEquals(LayoutStatus.PARTIAL, result.status()); assertEquals("labels", result.restoredField()); assertFalse(result.actual().item("labels").shownWhenEmpty());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields()); assertEquals(2, jira.additionCalls);
    }

    @Test public void restorationWithoutOriginalShownFlagCannotBeCalledRestored() {
        LayoutFixture.Screen screen = gapped(); screen.tab(11, "Details", "priority"); ScreenLayout before = ScreenLayout.of(screen.screen);
        AtomicInteger calls = new AtomicInteger();
        jira.beforeAdd = field -> { if (calls.getAndIncrement() == 0) throw new IllegalStateException("target failure"); };
        jira.afterAdd = field -> { throw new IllegalStateException("restoration persisted before flag copy"); };
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("assignee", 11L, 0));
        assertEquals(LayoutStatus.PARTIAL, result.status()); assertNull(result.restoredField()); assertNotNull(result.actual().item("assignee"));
        assertFalse(result.actual().item("assignee").shownWhenEmpty()); assertEquals(2, jira.additionCalls);
    }

    @Test public void restoredFailedTransferDoesNotHidePreviouslyCompletedTransfer() {
        LayoutFixture.Screen screen = gapped(); LayoutFixture.Tab details = screen.tab(11, "Details", "priority");
        ScreenLayout before = ScreenLayout.of(screen.screen); AtomicInteger calls = new AtomicInteger();
        jira.beforeAdd = field -> { if (calls.incrementAndGet() == 2) throw new IllegalStateException("second transfer fails"); };
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("labels", 11L, 0).move("assignee", 11L, 1));
        assertEquals(LayoutStatus.PARTIAL, result.status()); assertEquals("assignee", result.restoredField());
        assertEquals(List.of("summary", "assignee"), screen.fields()); assertEquals(List.of("labels", "priority"), details.fields());
        assertTrue(result.message().contains("earlier completed moves may remain")); assertEquals(3, jira.additionCalls);
    }

    private LayoutFixture.Tab gappedDetails(LayoutFixture.Screen screen) {
        LayoutFixture.Tab details = screen.tab(11, "Details", "priority", "duedate", "description");
        details.rows.get(1).setPosition(2); details.rows.get(2).setPosition(5);
        return details;
    }

    @Test public void transferToTheEndOfAGappedTabPersistsOrderAndContiguousPositions() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee"); gappedDetails(screen);
        ScreenLayout before = ScreenLayout.of(screen.screen);
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("labels", 11L, 3));
        assertEquals(LayoutStatus.EDITED, result.status());
        ScreenLayout saved = ScreenLayout.of(screen.screen);
        assertEquals(List.of("priority", "duedate", "description", "labels"), saved.tab(11L).items().stream().map(ScreenLayout.Item::fieldId).toList());
        assertEquals(List.of(0, 1, 2, 3), saved.tab(11L).items().stream().map(ScreenLayout.Item::position).toList());
        assertEquals(List.of(0, 1), saved.tab(10L).items().stream().map(ScreenLayout.Item::position).toList());
    }

    @Test public void transferIntoTheMiddleOfAGappedTabNeverLeavesDuplicatePositions() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee"); LayoutFixture.Tab details = gappedDetails(screen);
        ScreenLayout before = ScreenLayout.of(screen.screen);
        ScreenLayoutWriter.MoveWriteResult result = writer.edit(before, before, before.move("assignee", 11L, 2));
        assertEquals(LayoutStatus.EDITED, result.status());
        ScreenLayout saved = ScreenLayout.of(screen.screen);
        assertEquals(List.of("priority", "duedate", "assignee", "description"), saved.tab(11L).items().stream().map(ScreenLayout.Item::fieldId).toList());
        assertEquals(List.of(0, 1, 2, 3), saved.tab(11L).items().stream().map(ScreenLayout.Item::position).toList());
        assertTrue(saved.item("assignee").shownWhenEmpty());
        assertEquals(List.of("priority", "duedate", "assignee", "description"), details.fields());
    }

    @Test public void contiguousTargetTabIsNotRenumberedBeforeATransfer() {
        LayoutFixture.Screen screen = gapped(); screen.tab(11, "Details", "priority");
        ScreenLayout before = ScreenLayout.of(screen.screen);
        assertEquals(LayoutStatus.EDITED, writer.edit(before, before, before.move("labels", 11L, 1)).status());
        assertEquals(0, jira.moveCalls);
    }
}
