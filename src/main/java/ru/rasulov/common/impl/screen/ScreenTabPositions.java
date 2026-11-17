package ru.rasulov.common.impl.screen;

import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;

import java.util.List;
import java.util.TreeMap;

/**
 * Jira 10.3.24 {@code LayoutItems.add(int, item)} renumbers positions only from the insertion index; the full
 * renumbering exists only in {@code remove(int)} and {@code moveToPosition(Map)}. A tab whose positions are not
 * 0..n-1 (gaps are normal in persisted screens) therefore gets duplicate or out-of-order positions from an indexed
 * add, and the saved order differs from the in-memory list. Renumber such a tab once before inserting.
 */
public final class ScreenTabPositions {
    private ScreenTabPositions() { }

    /** Whether the native list's positions are exactly its indices. */
    public static boolean isSequential(List<FieldScreenLayoutItem> items) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).getPosition() != i) return false;
        return true;
    }

    /** Whether a snapshot tab's positions are exactly its indices. */
    public static boolean isSequential(ScreenLayout.Tab tab) {
        for (int i = 0; i < tab.items().size(); i++) if (tab.items().get(i).position() != i) return false;
        return true;
    }

    /**
     * Renumbers the tab to 0..n-1 keeping its current list order, with one native
     * {@code moveFieldScreenLayoutItemToPosition(TreeMap)} call (Jira inserts in iteration order, so the map must be
     * ordered). Does nothing for a sequential tab. Returns whether a write was made.
     */
    public static boolean normalize(FieldScreenTab tab) {
        List<FieldScreenLayoutItem> items = tab.getFieldScreenLayoutItems();
        if (isSequential(items)) return false;
        TreeMap<Integer, FieldScreenLayoutItem> order = new TreeMap<>();
        for (int i = 0; i < items.size(); i++) order.put(i, items.get(i));
        // SPIKE §9 п.1: bytecode resequences the whole tab and stores; live persistence remains unverified.
        tab.moveFieldScreenLayoutItemToPosition(order);
        return true;
    }
}
