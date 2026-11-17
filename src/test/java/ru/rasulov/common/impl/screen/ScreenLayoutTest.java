package ru.rasulov.common.impl.screen;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ScreenLayoutTest {
    private ScreenLayout layout() {
        return new ScreenLayout(1L, "Screen", null, List.of(
                new ScreenLayout.Tab(10L, "Main", 0, List.of(
                        new ScreenLayout.Item(100L, "summary", 0, true),
                        new ScreenLayout.Item(101L, "labels", 1, false),
                        new ScreenLayout.Item(102L, "assignee", 2, true))),
                new ScreenLayout.Tab(20L, "Details", 1, List.of())));
    }
    private List<String> fields(ScreenLayout model, long tab) {
        return model.tab(tab).items().stream().map(ScreenLayout.Item::fieldId).toList();
    }
    @Test public void insertAndRemovePreserveOrderAndOriginalSnapshot() {
        ScreenLayout original = layout();
        ScreenLayout inserted = original.insert(10L, "priority", 1, true);
        assertEquals(List.of("summary", "priority", "labels", "assignee"), fields(inserted, 10));
        assertEquals(2, inserted.item("labels").position());
        assertEquals(original, inserted.remove("priority"));
        assertEquals(List.of("summary", "labels", "assignee"), fields(original, 10));
        assertSame(original, original.remove("absent"));
    }
    @Test public void sequentialMovesUseUpdatedPositionsAndKeepFlags() {
        ScreenLayout moved = layout().move("assignee", 10L, 0).move("labels", 10L, 1);
        assertEquals(List.of("assignee", "labels", "summary"), fields(moved, 10));
        assertEquals(Long.valueOf(102), moved.item("assignee").layoutItemId());
        ScreenLayout transferred = moved.move("assignee", 20L, 0);
        assertEquals(List.of("labels", "summary"), fields(transferred, 10));
        assertEquals(List.of("assignee"), fields(transferred, 20));
        assertTrue(transferred.item("assignee").shownWhenEmpty());
        assertNull(transferred.item("assignee").layoutItemId());
    }
    @Test public void fingerprintIncludesOrderTabNamesAndFlags() {
        ScreenLayout original = layout();
        assertFalse(original.fingerprint().equals(original.move("labels", 10L, 0).fingerprint()));
        ScreenLayout.Tab tab = original.tab(10L);
        ScreenLayout renamed = new ScreenLayout(1L, "Screen", null, List.of(
                new ScreenLayout.Tab(10L, "Renamed", 0, tab.items()), original.tab(20L)));
        assertFalse(original.fingerprint().equals(renamed.fingerprint()));
        assertFalse(original.fingerprint().equals(original.remove("labels").insert(10L, "labels", 1, true).fingerprint()));
        assertEquals(original.fingerprint(), layout().fingerprint());
    }
    @Test public void readbackAllowsNewIdsOnlyForNewOrTransferredItems() {
        ScreenLayout expected = layout().move("labels", 20L, 0);
        ScreenLayout persisted = new ScreenLayout(1L, "Screen", null, List.of(expected.tab(10L),
                new ScreenLayout.Tab(20L, "Details", 1, List.of(new ScreenLayout.Item(999L, "labels", 0, false)))));
        assertTrue(expected.matches(persisted));
        assertFalse(layout().matches(persisted));
        assertFalse(expected.matches(persisted.remove("labels").insert(20L, "labels", 0, true)));
    }
    @Test public void readbackComparesOrderButFingerprintRetainsRawPositions() {
        ScreenLayout original = layout();
        ScreenLayout gapped = new ScreenLayout(1L, "Screen", null, List.of(
                new ScreenLayout.Tab(10L, "Main", 2, List.of(
                        new ScreenLayout.Item(100L, "summary", 0, true),
                        new ScreenLayout.Item(101L, "labels", 2, false),
                        new ScreenLayout.Item(102L, "assignee", 5, true))),
                new ScreenLayout.Tab(20L, "Details", 7, List.of())));
        assertTrue(original.matches(gapped));
        assertTrue(gapped.matches(original));
        assertEquals(5, gapped.item("assignee").position());
        assertEquals(7, gapped.tab(20L).position());
        assertFalse(original.fingerprint().equals(gapped.fingerprint()));
        assertFalse(original.matches(gapped.move("labels", 10L, 0)));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void itemListsCannotBeMutated() { layout().tab(10L).items().clear(); }
}
