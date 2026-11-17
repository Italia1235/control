package ru.rasulov.common.impl.undo;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Indexes persisted native inverse writes; issue timestamps need not advance during an undo. */
final class UndoReceiptIndex {
    static final int MAX_JSON_BYTES = 4096;
    private Map<Long, TreeMap<Long, UndoReceipt>> byIssue;
    private TreeMap<Long, String> records;

    @SuppressWarnings("unchecked")
    void add(long stepId, String json) {
        if (JournalStore.jsonBytes(json) > MAX_JSON_BYTES) {
            throw new IllegalStateException("Undo receipt exceeds the reserved 4KiB allowance.");
        }
        Map<String, Object> receipt = JournalJson.read(json, Map.class);
        Object rows = receipt.get("issues");
        if (!(rows instanceof List)) throw new IllegalStateException("Invalid undo receipt: missing issue proofs.");
        if (byIssue == null) {
            byIssue = new HashMap<>();
            records = new TreeMap<>();
        }
        for (Object value : (List<?>) rows) {
            if (!(value instanceof List) || ((List<?>) value).size() != 3) {
                throw new IllegalStateException("Invalid undo receipt issue tuple.");
            }
            List<?> issue = (List<?>) value;
            if (!(issue.get(0) instanceof Number) || !(issue.get(1) instanceof Number) || !(issue.get(2) instanceof String)) {
                throw new IllegalStateException("Invalid undo receipt identity, timestamp or hash.");
            }
            long id = ((Number) issue.get(0)).longValue();
            UndoReceipt proof = new UndoReceipt(((Number) issue.get(1)).longValue(), (String) issue.get(2), stepId);
            byIssue.computeIfAbsent(id, key -> new TreeMap<>()).put(stepId, proof);
        }
        records.put(stepId, json);
    }

    Map<Long, String> records() {
        return records == null ? Collections.emptyMap() : records;
    }

    /** The nearest newer inverse is the last one executed before this step in reverse dependency order. */
    Map<Long, UndoReceipt> after(long targetStepId) {
        if (byIssue == null || byIssue.isEmpty()) return Collections.emptyMap();
        return new AbstractMap<Long, UndoReceipt>() {
            @Override public UndoReceipt get(Object key) {
                TreeMap<Long, UndoReceipt> history = byIssue.get(key);
                if (history == null) return null;
                Long origin = history.higherKey(targetStepId);
                return origin == null ? null : history.get(origin);
            }
            @Override public boolean containsKey(Object key) { return get(key) != null; }
            @Override public int size() {
                int count = 0;
                for (TreeMap<Long, UndoReceipt> history : byIssue.values()) {
                    if (history.higherKey(targetStepId) != null) count++;
                }
                return count;
            }
            @Override public boolean isEmpty() {
                for (TreeMap<Long, UndoReceipt> history : byIssue.values()) {
                    if (history.higherKey(targetStepId) != null) return false;
                }
                return true;
            }
            @Override public Set<Entry<Long, UndoReceipt>> entrySet() {
                Set<Entry<Long, UndoReceipt>> entries = new LinkedHashSet<>();
                for (Long id : byIssue.keySet()) {
                    UndoReceipt proof = get(id);
                    if (proof != null) entries.add(new SimpleImmutableEntry<>(id, proof));
                }
                return Collections.unmodifiableSet(entries);
            }
        };
    }
}
