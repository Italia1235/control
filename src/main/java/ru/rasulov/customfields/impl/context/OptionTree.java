package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;

import java.util.*;

/** The options of one FieldConfig as plain values: the one way the reader and the context writer see them. */
final class OptionTree {
    private OptionTree() { }

    /** One stored option. Top-level options have no parent. Sequence orders the options of one level. */
    static final class Node {
        final Long id, parentId;
        final long sequence;
        final String value;
        final boolean disabled;

        Node(Long id, Long parentId, long sequence, String value, boolean disabled) {
            this.id = id; this.parentId = parentId; this.sequence = sequence; this.value = value; this.disabled = disabled;
        }
    }

    /** Orders the options of one level: by stored sequence, then by id. */
    static final Comparator<Node> BY_LEVEL_ORDER = Comparator.<Node>comparingLong(node -> node.sequence)
            .thenComparing(node -> node.id, Comparator.nullsLast(Comparator.<Long>naturalOrder()));

    /** The top-level options (in the order Jira lists them) and their children, parents first; each level in level order. */
    static List<Node> read(Iterable<Option> roots) {
        List<Node> result = new ArrayList<Node>();
        Set<Long> seen = new HashSet<Long>();
        List<Node> top = new ArrayList<Node>();
        Map<Long, List<Node>> children = new LinkedHashMap<Long, List<Node>>();
        Deque<Option> pending = new ArrayDeque<Option>();
        if (roots != null) for (Option root : roots) if (root != null && seen.add(root.getOptionId())) {
            top.add(node(root, null)); pending.add(root);
        }
        while (!pending.isEmpty()) {
            Option parent = pending.poll();
            List<Option> kids = parent.getChildOptions();
            if (kids == null) continue;
            List<Node> level = new ArrayList<Node>();
            for (Option child : kids) if (child != null && seen.add(child.getOptionId())) {
                level.add(node(child, parent.getOptionId())); pending.add(child);
            }
            children.put(parent.getOptionId(), level);
        }
        top.sort(BY_LEVEL_ORDER);
        for (Node root : top) {
            result.add(root);
            List<Node> level = children.get(root.id);
            if (level != null) { level.sort(BY_LEVEL_ORDER); result.addAll(level); }
        }
        // Children of children do not exist in Jira (cascading select has two levels); keep any anyway, never drop one.
        for (Map.Entry<Long, List<Node>> entry : children.entrySet()) {
            for (Node orphan : entry.getValue()) if (!result.contains(orphan)) result.add(orphan);
        }
        return result;
    }

    /** The persisted rows {id, parentoptionid, sequence, value, disabled} of {@code FieldContextSnapshots.config}. */
    static List<Node> fromRows(List<?> rows) {
        List<Node> result = new ArrayList<Node>();
        for (Object row : rows) {
            List<?> columns = (List<?>) row;
            Object sequence = columns.get(2);
            result.add(new Node((Long) columns.get(0), (Long) columns.get(1), sequence == null ? 0L : ((Number) sequence).longValue(),
                    (String) columns.get(3), disabled(columns.get(4))));
        }
        return result;
    }

    /** The options of each level in level order, by parent id; the top-level options sit under the {@code null} key. */
    static Map<Long, List<Node>> levels(List<Node> nodes) {
        Map<Long, List<Node>> levels = new LinkedHashMap<Long, List<Node>>();
        for (Node node : nodes) levels.computeIfAbsent(node.parentId, key -> new ArrayList<Node>()).add(node);
        for (List<Node> level : levels.values()) level.sort(BY_LEVEL_ORDER);
        return levels;
    }

    /** Jira stores the flag as text; "Y" and "true" both mean disabled. */
    static boolean disabled(Object stored) {
        return stored != null && ("Y".equalsIgnoreCase(stored.toString()) || "true".equalsIgnoreCase(stored.toString()));
    }

    /** Ids of the options the context's default value consists of (one option, several, or a cascading pair). */
    static Set<Long> defaultIds(CustomField field, FieldConfig config) {
        Set<Long> ids = new HashSet<Long>();
        CustomFieldType<?, ?> type = field.getCustomFieldType();
        if (type != null) collect(type.getDefaultValue(config), ids);
        return ids;
    }

    private static void collect(Object value, Set<Long> ids) {
        if (value instanceof Option) ids.add(((Option) value).getOptionId());
        else if (value instanceof Collection) for (Object item : (Collection<?>) value) collect(item, ids);
        else if (value instanceof Map) for (Object item : ((Map<?, ?>) value).values()) collect(item, ids);
    }

    private static Node node(Option option, Long parentId) {
        Long sequence = option.getSequence();
        return new Node(option.getOptionId(), parentId, sequence == null ? 0L : sequence, option.getValue(),
                Boolean.TRUE.equals(option.getDisabled()));
    }
}
