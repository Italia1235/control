package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.fields.screen.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mutable native screen fixture: positions may contain gaps, as in persisted Jira screens. */
final class LayoutFixture {
    final FieldScreenManager manager = mock(FieldScreenManager.class);
    final Map<Long, Screen> screens = new LinkedHashMap<>();
    Consumer<String> beforeRemove = field -> { };
    Consumer<String> afterRemove = field -> { };
    Consumer<String> beforeAdd = field -> { };
    Consumer<String> afterAdd = field -> { };
    Consumer<Long> beforeMove = tabId -> { };
    Consumer<Long> afterMove = tabId -> { };
    int additionCalls, moveCalls;
    long nextItemId = 1_000_000;
    boolean readbackFails;
    int removalCalls;
    LayoutFixture() {
        when(manager.getFieldScreen(anyLong())).thenAnswer(call -> {
            if (readbackFails && removalCalls + additionCalls + moveCalls > 0) throw new IllegalStateException("readback unavailable");
            Screen screen = screens.get(call.getArgument(0));
            return screen == null ? null : screen.screen;
        });
    }
    Screen screen(long id, String... fields) {
        Screen result = new Screen(id, fields); screens.put(id, result); return result;
    }
    final class Screen {
        final FieldScreen screen = mock(FieldScreen.class);
        final FieldScreenTab tab = mock(FieldScreenTab.class);
        final List<FieldScreenLayoutItem> rows = new ArrayList<>();
        final List<FieldScreenTab> tabs = new ArrayList<>();
        Screen(long id, String... fields) {
            when(screen.getId()).thenReturn(id); when(screen.getName()).thenReturn("Screen " + id);
            tabs.add(tab);
            when(screen.getTabs()).thenAnswer(call -> Collections.unmodifiableList(tabs));
            when(screen.containsField(anyString())).thenAnswer(call -> tabs.stream().anyMatch(candidate ->
                    candidate.getFieldScreenLayoutItems().stream().anyMatch(item -> item.getFieldId().equals(call.getArgument(0)))));
            configure(tab, rows, id * 10, "Main", 0, fields);
        }
        List<String> fields() { return rows.stream().map(FieldScreenLayoutItem::getFieldId).toList(); }
        Tab tab(long id, String name, String... fields) {
            Tab result = new Tab();
            configure(result.tab, result.rows, id, name, tabs.size(), fields);
            tabs.add(result.tab);
            return result;
        }
    }
    final class Tab {
        final FieldScreenTab tab = mock(FieldScreenTab.class);
        final List<FieldScreenLayoutItem> rows = new ArrayList<>();
        List<String> fields() { return rows.stream().map(FieldScreenLayoutItem::getFieldId).toList(); }
    }
    private void configure(FieldScreenTab tab, List<FieldScreenLayoutItem> rows, long id,
            String name, int position, String... fields) {
        when(tab.getId()).thenReturn(id); when(tab.getName()).thenReturn(name); when(tab.getPosition()).thenReturn(position);
        for (int i = 0; i < fields.length; i++) rows.add(row(id * 10 + i, fields[i], i, i % 2 == 0));
        when(tab.getFieldScreenLayoutItems()).thenAnswer(call -> Collections.unmodifiableList(rows));
        when(tab.getFieldScreenLayoutItem(anyInt())).thenAnswer(call -> rows.get((Integer) call.getArgument(0)));
        when(tab.getFieldScreenLayoutItem(anyString())).thenAnswer(call -> rows.stream()
                .filter(item -> item.getFieldId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(tab.removeFieldScreenLayoutItem(anyInt())).thenAnswer(call -> {
            int index = call.getArgument(0);
            FieldScreenLayoutItem found = rows.get(index);
            removalCalls++;
            beforeRemove.accept(found.getFieldId());
            rows.remove(index);
            resequence(rows, 0);
            afterRemove.accept(found.getFieldId());
            return found;
        });
        doAnswer(call -> {
            String field = call.getArgument(0);
            int index = call.getArgument(1);
            additionCalls++;
            beforeAdd.accept(field);
            rows.add(index, row(nextItemId++, field, 0, false));
            resequence(rows, index);
            afterAdd.accept(field);
            return null;
        }).when(tab).addFieldScreenLayoutItem(anyString(), anyInt());
        doAnswer(call -> {
            Map<Integer, FieldScreenLayoutItem> moves = call.getArgument(0);
            if (moves.isEmpty()) return null;
            moveCalls++;
            beforeMove.accept(id);
            for (FieldScreenLayoutItem item : moves.values()) rows.remove(item);
            for (Map.Entry<Integer, FieldScreenLayoutItem> entry : moves.entrySet()) rows.add(entry.getKey(), entry.getValue());
            resequence(rows, 0);
            afterMove.accept(id);
            return null;
        }).when(tab).moveFieldScreenLayoutItemToPosition(anyMap());
    }
    private static void resequence(List<FieldScreenLayoutItem> rows, int start) {
        for (int i = start; i < rows.size(); i++) rows.get(i).setPosition(i);
    }
    static FieldScreenLayoutItem row(long id, String field, int position, boolean shown) {
        FieldScreenLayoutItem row = mock(FieldScreenLayoutItem.class);
        when(row.getId()).thenReturn(id); when(row.getFieldId()).thenReturn(field);
        AtomicInteger currentPosition = new AtomicInteger(position);
        when(row.getPosition()).thenAnswer(call -> currentPosition.get());
        doAnswer(call -> { currentPosition.set(call.getArgument(0)); return null; }).when(row).setPosition(anyInt());
        AtomicBoolean currentShown = new AtomicBoolean(shown);
        when(row.isShownWhenEmpty()).thenAnswer(call -> currentShown.get());
        doAnswer(call -> { currentShown.set(call.getArgument(0)); return null; }).when(row).setShowWhenEmpty(anyBoolean());
        return row;
    }
}
