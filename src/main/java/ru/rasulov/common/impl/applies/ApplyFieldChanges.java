package ru.rasulov.common.impl.applies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Actual field membership changes, separate from the bounded summary targets. */
public final class ApplyFieldChanges {
    public final boolean available;
    public final List<Item> items;

    private ApplyFieldChanges(boolean available, List<Item> items) {
        this.available = available;
        this.items = Collections.unmodifiableList(items);
    }

    public static final class Item {
        public final String fieldId;
        public final String action;

        private Item(String fieldId, String action) {
            this.fieldId = fieldId;
            this.action = action;
        }
    }

    public static ApplyFieldChanges unavailable() {
        return new ApplyFieldChanges(false, Collections.<Item>emptyList());
    }

    /** Missing sections belong to old results; malformed sections must never claim known empty changes. */
    public static ApplyFieldChanges from(Object value) {
        if (value == null) return null;
        if (!(value instanceof Map)) return unavailable();
        Map<?, ?> section = (Map<?, ?>) value;
        if (!Boolean.TRUE.equals(section.get("available"))) return unavailable();
        Object entries = section.get("items");
        if (!(entries instanceof List)) return unavailable();
        List<Item> items = new ArrayList<Item>(((List<?>) entries).size());
        for (Object entry : (List<?>) entries) {
            if (!(entry instanceof Map)) return unavailable();
            Map<?, ?> item = (Map<?, ?>) entry;
            Object fieldId = item.get("fieldId");
            Object action = item.get("action");
            if (!(fieldId instanceof String) || ((String) fieldId).trim().isEmpty()
                    || !("added".equals(action) || "deleted".equals(action))) return unavailable();
            items.add(new Item((String) fieldId, (String) action));
        }
        return new ApplyFieldChanges(true, items);
    }
}
