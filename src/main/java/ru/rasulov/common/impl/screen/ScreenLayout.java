package ru.rasulov.common.impl.screen;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable ordered snapshot. Null item IDs represent newly inserted or transferred items. */
public record ScreenLayout(Long id, String name, String description, List<Tab> tabs) {
    public ScreenLayout { tabs = List.copyOf(tabs); }

    public record Item(Long layoutItemId, String fieldId, int position, boolean shownWhenEmpty) { }
    public record Tab(Long id, String name, int position, List<Item> items) {
        public Tab { items = List.copyOf(items); }
    }

    public static ScreenLayout of(FieldScreen screen) {
        List<Tab> tabs = new ArrayList<>();
        for (FieldScreenTab tab : screen.getTabs()) {
            List<Item> items = new ArrayList<>();
            for (FieldScreenLayoutItem item : tab.getFieldScreenLayoutItems()) {
                items.add(new Item(item.getId(), item.getFieldId(), item.getPosition(), item.isShownWhenEmpty()));
            }
            items.sort(Comparator.comparingInt(Item::position));
            tabs.add(new Tab(tab.getId(), tab.getName(), tab.getPosition(), items));
        }
        tabs.sort(Comparator.comparingInt(Tab::position));
        return new ScreenLayout(screen.getId(), screen.getName(), screen.getDescription(), tabs);
    }

    public Tab tab(Long tabId) {
        return tabs.stream().filter(tab -> Objects.equals(tab.id(), tabId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tab: " + tabId));
    }

    public Tab containing(String fieldId) {
        return tabs.stream().filter(tab -> tab.items().stream().anyMatch(item -> item.fieldId().equals(fieldId)))
                .findFirst().orElse(null);
    }

    public Item item(String fieldId) {
        Tab tab = containing(fieldId);
        return tab == null ? null : tab.items().stream().filter(item -> item.fieldId().equals(fieldId)).findFirst().orElseThrow();
    }

    public ScreenLayout insert(Long tabId, String fieldId, int position, boolean shownWhenEmpty) {
        if (containing(fieldId) != null) throw new IllegalArgumentException("Field already on screen: " + fieldId);
        List<Item> items = new ArrayList<>(tab(tabId).items());
        items.add(position, new Item(null, fieldId, position, shownWhenEmpty));
        return replace(tabId, items);
    }

    public ScreenLayout remove(String fieldId) {
        Tab tab = containing(fieldId);
        if (tab == null) return this;
        List<Item> items = new ArrayList<>(tab.items());
        items.removeIf(item -> item.fieldId().equals(fieldId));
        return replace(tab.id(), items);
    }

    /** Position is in the target tab after removing the source item. */
    public ScreenLayout move(String fieldId, Long targetTabId, int position) {
        Tab source = containing(fieldId);
        if (source == null) throw new IllegalArgumentException("Field not on screen: " + fieldId);
        Item item = item(fieldId);
        ScreenLayout removed = remove(fieldId);
        List<Item> items = new ArrayList<>(removed.tab(targetTabId).items());
        items.add(position, new Item(Objects.equals(source.id(), targetTabId) ? item.layoutItemId() : null,
                fieldId, position, item.shownWhenEmpty()));
        return removed.replace(targetTabId, items);
    }

    private ScreenLayout replace(Long tabId, List<Item> items) {
        List<Item> positioned = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            positioned.add(new Item(item.layoutItemId(), item.fieldId(), i, item.shownWhenEmpty()));
        }
        List<Tab> result = new ArrayList<>(tabs.size());
        for (Tab tab : tabs) result.add(Objects.equals(tab.id(), tabId)
                ? new Tab(tab.id(), tab.name(), tab.position(), positioned) : tab);
        return new ScreenLayout(id, name, description, result);
    }

    /** Compare tab/item order by index, not raw Jira positions (which can have gaps).
     * Only new/transferred item IDs are allowed to change; fingerprints retain raw positions. */
    public boolean matches(ScreenLayout actual) {
        if (actual == null || !Objects.equals(id, actual.id) || !Objects.equals(name, actual.name)
                || !Objects.equals(description, actual.description) || tabs.size() != actual.tabs.size()) return false;
        for (int t = 0; t < tabs.size(); t++) {
            Tab expected = tabs.get(t), found = actual.tabs.get(t);
            if (!Objects.equals(expected.id(), found.id()) || !Objects.equals(expected.name(), found.name())
                    || expected.items().size() != found.items().size()) return false;
            for (int i = 0; i < expected.items().size(); i++) {
                Item e = expected.items().get(i), a = found.items().get(i);
                if (a.layoutItemId() == null || (e.layoutItemId() != null && !e.layoutItemId().equals(a.layoutItemId()))
                        || !e.fieldId().equals(a.fieldId())
                        || e.shownWhenEmpty() != a.shownWhenEmpty()) return false;
            }
        }
        return true;
    }

    public String fingerprint() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            text(out, String.valueOf(id)); text(out, name); text(out, description);
            out.writeInt(tabs.size());
            for (Tab tab : tabs) {
                text(out, String.valueOf(tab.id())); text(out, tab.name()); out.writeInt(tab.position());
                out.writeInt(tab.items().size());
                for (Item item : tab.items()) {
                    text(out, item.layoutItemId() == null ? null : item.layoutItemId().toString());
                    text(out, item.fieldId()); out.writeInt(item.position()); out.writeBoolean(item.shownWhenEmpty());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint screen layout", e);
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }
}
