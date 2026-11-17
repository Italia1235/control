package ru.rasulov.customfields.impl.context;

import ru.rasulov.customfields.impl.context.OptionTree.Node;

import java.util.*;

/**
 * What one options change does to one context: the writes in their order, the preview rows and the approved final
 * state against which the persisted rows are verified after the write.
 */
final class OptionsPlan {
    /** A new enabled option to create at the given sequence under an existing parent (null: top level). */
    static final class Add {
        final Long parentId;
        final String value;
        final long sequence;
        Add(Long parentId, String value, long sequence) { this.parentId = parentId; this.value = value; this.sequence = sequence; }
    }

    /** One line of the before/after table: an existing option with its changes, or a new option (optionId null). */
    static final class Row {
        final Long optionId, parentId;
        final List<String> changes;
        final Map<String, Object> before, after;
        final boolean defaultValue;
        final String summary;
        Row(Long optionId, Long parentId, List<String> changes, Map<String, Object> before, Map<String, Object> after,
            boolean defaultValue, String summary) {
            this.optionId = optionId; this.parentId = parentId; this.changes = changes; this.before = before;
            this.after = after; this.defaultValue = defaultValue; this.summary = summary;
        }
        boolean wantsUsage() { return changes.contains("RENAMED") || changes.contains("DISABLED"); }
    }

    /** What an existing option must look like after the write (its order is checked per level, below). */
    static final class Expected {
        final Long parentId;
        final String value;
        final boolean disabled;
        Expected(Long parentId, String value, boolean disabled) { this.parentId = parentId; this.value = value; this.disabled = disabled; }
    }

    final List<Add> adds;
    final Map<Long, String> renames;
    final List<Long> disables, enables;
    /** Existing options whose stored sequence must change, by new sequence, in the requested order. */
    final Map<Long, Long> sequences;
    final List<String> unchanged;
    final List<Row> rows;
    final List<Map<String, Object>> warnings;
    final int touched;

    private final List<Node> before;
    private final Map<Long, Expected> expected;
    /** Per level (parent id; null = top level) whose order is part of the change: ids of the existing options in final
     *  order; the new options of that level follow in add order. Other levels must keep their stored sequences. */
    private final Map<Long, List<Long>> touchedLevels;

    OptionsPlan(List<Add> adds, Map<Long, String> renames, List<Long> disables, List<Long> enables,
                Map<Long, Long> sequences, List<String> unchanged, List<Row> rows,
                List<Map<String, Object>> warnings, int touched, List<Node> before, Map<Long, Expected> expected,
                Map<Long, List<Long>> touchedLevels) {
        this.adds = adds; this.renames = renames; this.disables = disables; this.enables = enables;
        this.sequences = sequences; this.unchanged = unchanged; this.rows = rows; this.warnings = warnings;
        this.touched = touched; this.before = before; this.expected = expected; this.touchedLevels = touchedLevels;
    }

    boolean changed() {
        return !adds.isEmpty() || !renames.isEmpty() || !disables.isEmpty() || !enables.isEmpty() || !sequences.isEmpty();
    }

    /** Options whose use is worth showing (renamed or disabled) mapped to their parent id, in row order. */
    Map<Long, Long> usageTargets() {
        Map<Long, Long> targets = new LinkedHashMap<Long, Long>();
        for (Row row : rows) if (row.optionId != null && row.wantsUsage()) targets.put(row.optionId, row.parentId);
        return targets;
    }

    /** The preview/result of this context's options; usage is shown only when a (possibly empty) map is given. */
    Map<String, Object> view(Map<Long, OptionUsage> usage) {
        List<Object> shown = new ArrayList<Object>();
        for (Row row : rows) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("optionId", row.optionId); entry.put("parentOptionId", row.parentId);
            entry.put("changes", row.changes); entry.put("summary", row.summary);
            entry.put("before", row.before); entry.put("after", row.after);
            if (row.defaultValue) entry.put("default", true);
            if (usage != null && row.wantsUsage()) {
                OptionUsage found = usage.get(row.optionId);
                Map<String, Object> shownUsage = new LinkedHashMap<String, Object>();
                if (found != null && found.isKnown()) { shownUsage.put("status", "COUNT"); shownUsage.put("issues", found.getIssues()); }
                else { shownUsage.put("status", "UNKNOWN"); shownUsage.put("reason", found == null ? "Usage was not counted." : found.getReason()); }
                entry.put("usage", shownUsage);
            }
            shown.add(entry);
        }
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("rows", shown); view.put("unchanged", unchanged); view.put("warnings", warnings);
        return view;
    }

    /** Null when the persisted option rows are exactly the approved final state, otherwise what differs. */
    String mismatch(List<?> persistedRows) {
        List<Node> actual = OptionTree.fromRows(persistedRows);
        Map<Long, Node> byId = new HashMap<Long, Node>();
        for (Node node : actual) byId.put(node.id, node);
        Map<Long, Node> previous = new HashMap<Long, Node>();
        for (Node node : before) previous.put(node.id, node);
        for (Node node : before) {
            Node now = byId.get(node.id);
            if (now == null) return "Option " + node.id + " ('" + node.value + "') is gone.";
            Expected want = expected.get(node.id);
            if (!Objects.equals(want.parentId, now.parentId)) return "Option " + node.id + " has another parent than approved.";
            if (!Objects.equals(want.value, now.value)) return "Option " + node.id + " has the value '" + now.value + "' instead of '" + want.value + "'.";
            if (want.disabled != now.disabled) return "Option " + node.id + " is " + (now.disabled ? "disabled" : "enabled") + " contrary to the approval.";
            if (!touchedLevels.containsKey(node.parentId) && node.sequence != now.sequence)
                return "Option " + node.id + " outside the requested order was moved.";
        }
        List<Node> created = new ArrayList<Node>();
        for (Node node : actual) if (!previous.containsKey(node.id)) created.add(node);
        if (created.size() != adds.size()) return "Expected " + adds.size() + " new option(s) but found " + created.size() + ".";
        Map<Add, Long> idOfAdd = new LinkedHashMap<Add, Long>();
        for (Add add : adds) {
            Node match = null;
            for (Node node : created) {
                if (!idOfAdd.containsValue(node.id) && Objects.equals(add.parentId, node.parentId) && add.value.equals(node.value) && !node.disabled) {
                    match = node; break;
                }
            }
            if (match == null) return "The new option '" + add.value + "' is missing or differs from the approval.";
            idOfAdd.put(add, match.id);
        }
        for (Map.Entry<Long, List<Long>> level : touchedLevels.entrySet()) {
            List<Long> wanted = new ArrayList<Long>(level.getValue());
            for (Add add : adds) if (Objects.equals(add.parentId, level.getKey())) wanted.add(idOfAdd.get(add));
            List<Long> found = new ArrayList<Long>();
            for (Node node : OptionTree.levels(actual).getOrDefault(level.getKey(), Collections.<Node>emptyList())) found.add(node.id);
            if (!wanted.equals(found)) return "The order of the options " + (level.getKey() == null ? "of the top level" : "under " + level.getKey())
                    + " is " + found + " instead of " + wanted + ".";
        }
        return null;
    }
}
