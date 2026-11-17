package ru.rasulov.common.impl.applies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What an apply touched, found by one rule for every tool: first the objects the result reports as created, then the
 * well-known top-level keys of the preview request. No code per tool.
 */
public final class ApplyTargets {
    /** At most this many targets are kept; the rest are counted in {@link Result#more}. */
    public static final int MAX = 10;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] LABEL_KEYS = {"name", "key", "project", "field", "fieldId", "screenId"};

    private static final String[][] KEYS = {
            {"PROJECT", "project", "projects"},
            {"FIELD", "field", "fieldId", "fieldIds", "fields"},
            {"SCREEN", "screenId", "screens"},
            {"SCHEME", "scheme", "schemeName", "newScheme"},
            {"WORKFLOW", "workflow", "workflows", "workflowNames"},
            {"ISSUE_TYPE", "issueTypes"},
            {"CONTEXT", "contextId", "contexts"},
            {"OBJECT", "key", "name"}};

    public static final class Result {
        public final List<ApplyTarget> items;
        public final int more;
        public final List<ApplyLink> links;

        Result(List<ApplyTarget> items, int more, List<ApplyLink> links) {
            this.items = items;
            this.more = more;
            this.links = links;
        }
    }

    private ApplyTargets() {
    }

    /**
     * A request target is dropped when the report already names the same object: the same label or the same id under
     * any type (the report says {@code CUSTOM_FIELD "X" #10500}, the request only {@code FIELD "X"}).
     *
     * @param result      the operation's top-level {@code result} (a JSON-like map) or {@code null}
     * @param planRequest the preview request text, or {@code null} when the preview no longer exists
     */
    public static Result collect(Object result, String planRequest) {
        Set<Key> found = new LinkedHashSet<Key>();
        List<ApplyLink> links = new ArrayList<ApplyLink>();
        fromReport(result, found, links);
        List<Key> reported = new ArrayList<Key>(found);
        Set<Key> requested = new LinkedHashSet<Key>();
        fromRequest(planRequest, requested);
        for (Key key : requested) {
            if (!reportedAlready(reported, key)) found.add(key);
        }
        List<ApplyTarget> items = new ArrayList<ApplyTarget>();
        int more = 0;
        for (Key key : found) {
            if (items.size() < MAX) items.add(new ApplyTarget(key.type, key.label, key.id)); else more++;
        }
        return new Result(Collections.unmodifiableList(items), more, Collections.unmodifiableList(links));
    }

    private static boolean reportedAlready(List<Key> reported, Key key) {
        for (Key other : reported) {
            if (other.label.equals(key.label) || (key.id != null && key.id.equals(other.id))) return true;
        }
        return false;
    }

    private static void fromReport(Object result, Set<Key> found, List<ApplyLink> links) {
        if (!(result instanceof Map)) return;
        Object report = ((Map<?, ?>) result).get("report");
        if (!(report instanceof Map)) return;
        Object created = ((Map<?, ?>) report).get("created");
        if (!(created instanceof Iterable)) return;
        for (Object entry : (Iterable<?>) created) {
            if (!(entry instanceof Map)) continue;
            Map<?, ?> object = (Map<?, ?>) entry;
            String type = text(object.get("kind"));
            String name = text(object.get("name"));
            String id = text(object.get("id"));
            String url = text(object.get("url"));
            if (type != null && name != null) found.add(new Key(type, name, id));
            if (url != null) {
                ApplyLink link = new ApplyLink(name, url);
                boolean known = false;
                for (ApplyLink other : links) {
                    if (Objects.equals(other.name, link.name) && other.url.equals(link.url)) known = true;
                }
                if (!known) links.add(link);
            }
        }
    }

    private static void fromRequest(String planRequest, Set<Key> found) {
        if (planRequest == null) return;
        JsonNode root;
        try {
            root = JSON.readTree(planRequest);
        } catch (IOException | RuntimeException notJson) {
            return;
        }
        if (root == null || !root.isObject()) return;
        for (String[] row : KEYS) {
            for (int i = 1; i < row.length; i++) {
                JsonNode value = root.get(row[i]);
                if (value != null) add(row[0], value, true, found);
            }
        }
        if (root.path("allProjects").isBoolean() && root.get("allProjects").booleanValue()) {
            found.add(new Key("PROJECT", "All projects", null));
        }
        if (root.path("allIssueTypes").isBoolean() && root.get("allIssueTypes").booleanValue()) {
            found.add(new Key("ISSUE_TYPE", "All issue types", null));
        }
    }

    private static void add(String type, JsonNode value, boolean arraysAllowed, Set<Key> found) {
        if (value.isArray()) {
            if (!arraysAllowed) return;
            for (JsonNode element : value) add(type, element, false, found);
        } else if (value.isTextual()) {
            String label = nonBlank(value.textValue());
            if (label != null) found.add(new Key(type, label, null));
        } else if (value.isNumber()) {
            String id = value.asText();
            found.add(new Key(type, id, id));
        } else if (value.isObject()) {
            String label = null;
            for (String name : LABEL_KEYS) {
                label = scalar(value.get(name));
                if (label != null) break;
            }
            String id = scalar(value.get("id"));
            if (label == null) label = id;
            if (label != null) found.add(new Key(type, label, id));
        }
    }

    /** Text of a string or number node; anything else is absent. */
    private static String scalar(JsonNode node) {
        if (node == null) return null;
        if (node.isTextual()) return nonBlank(node.textValue());
        if (node.isNumber()) return node.asText();
        return null;
    }

    private static String text(Object value) {
        return value instanceof String ? nonBlank((String) value) : value instanceof Number ? value.toString() : null;
    }

    private static String nonBlank(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static final class Key {
        final String type;
        final String label;
        final String id;

        Key(String type, String label, String id) {
            this.type = type;
            this.label = label;
            this.id = id;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return type.equals(key.type) && label.equals(key.label) && Objects.equals(id, key.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, label, id);
        }
    }
}
