package ru.rasulov.common.impl.applies;

import net.java.ao.Query;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Read side of the apply journal for the administrators' page. Read only. */
@Named
public class ApplyReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Comparator<String> BY_TEXT = Comparator.comparing((String value) -> value.toLowerCase(java.util.Locale.ROOT))
            .thenComparing(Comparator.<String>naturalOrder());

    private final ApplyDatabase database;

    @Inject
    ApplyReader(ApplyDatabase database) {
        this.database = database;
    }

    /**
     * Newest rows first (by AO id), at most {@code limit}; {@code nextBefore} continues when the page is full.
     * {@code total} counts every row that matches the filters, whatever the cursor.
     */
    public Map<String, Object> list(ApplyQuery query) {
        List<String> where = new ArrayList<String>();
        List<Object> params = new ArrayList<Object>();
        add(where, params, "ACTOR_KEY = ?", query.actor);
        add(where, params, "TOOL_NAME = ?", query.tool);
        add(where, params, "OPERATION_STATE = ?", query.state);
        add(where, params, "DOMAIN_STATUS = ?", query.status);
        add(where, params, "RECORDED_AT >= ?", query.from);
        add(where, params, "RECORDED_AT < ?", query.to);
        int total = database.count(where.isEmpty() ? Query.select()
                : Query.select().where(String.join(" AND ", where), params.toArray()));
        add(where, params, "ID < ?", query.before);
        Query select = Query.select();
        if (!where.isEmpty()) select = select.where(String.join(" AND ", where), params.toArray());
        ApplyRecord[] rows = database.find(select.order("\"ID\" DESC").limit(query.limit));
        List<Object> items = new ArrayList<Object>();
        for (ApplyRecord row : rows) items.add(item(row));
        Map<String, Object> page = new LinkedHashMap<String, Object>();
        page.put("items", items);
        page.put("nextBefore", rows.length == query.limit && rows.length > 0 ? (Object) rows[rows.length - 1].getID() : null);
        page.put("total", total);
        return page;
    }

    /**
     * Distinct values present in the table; names and titles come from the newest row of each key. {@code mostActive}
     * is the actor with the most rows in the whole journal (a tie goes to the one who acted most recently), or null.
     */
    public Map<String, Object> facets() {
        Map<String, String> actors = new LinkedHashMap<String, String>();
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        Map<String, String> tools = new LinkedHashMap<String, String>();
        TreeSet<String> states = new TreeSet<String>(BY_TEXT);
        TreeSet<String> statuses = new TreeSet<String>(BY_TEXT);
        database.stream(Query.select("ID, ACTOR_KEY, ACTOR_NAME, TOOL_NAME, TOOL_TITLE, OPERATION_STATE, DOMAIN_STATUS")
                .order("\"ID\" DESC"), row -> {
            if (row.getActorKey() != null) {
                actors.putIfAbsent(row.getActorKey(), row.getActorName());
                counts.merge(row.getActorKey(), 1, Integer::sum);
            }
            if (row.getToolName() != null) tools.putIfAbsent(row.getToolName(), row.getToolTitle());
            if (row.getOperationState() != null) states.add(row.getOperationState());
            if (row.getDomainStatus() != null) statuses.add(row.getDomainStatus());
        });
        Map<String, Object> facets = new LinkedHashMap<String, Object>();
        facets.put("actors", sorted(actors, "key", "displayName"));
        facets.put("tools", sorted(tools, "name", "title"));
        facets.put("states", new ArrayList<String>(states));
        facets.put("statuses", new ArrayList<String>(statuses));
        String top = null;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (top == null || entry.getValue() > counts.get(top)) top = entry.getKey();
        }
        Map<String, Object> mostActive = null;
        if (top != null) {
            mostActive = new LinkedHashMap<String, Object>();
            mostActive.put("key", top);
            mostActive.put("displayName", actors.get(top));
            mostActive.put("count", counts.get(top));
        }
        facets.put("mostActive", mostActive);
        return facets;
    }

    private static List<Object> sorted(Map<String, String> byKey, String keyName, String labelName) {
        List<Map.Entry<String, String>> entries = new ArrayList<Map.Entry<String, String>>(byKey.entrySet());
        Collections.sort(entries, (a, b) -> {
            int byLabel = BY_TEXT.compare(a.getValue() == null ? a.getKey() : a.getValue(),
                    b.getValue() == null ? b.getKey() : b.getValue());
            return byLabel != 0 ? byLabel : a.getKey().compareTo(b.getKey());
        });
        List<Object> out = new ArrayList<Object>();
        for (Map.Entry<String, String> entry : entries) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put(keyName, entry.getKey());
            value.put(labelName, entry.getValue());
            out.add(value);
        }
        return out;
    }

    private static void add(List<String> where, List<Object> params, String condition, Object value) {
        if (value == null) return;
        where.add(condition);
        params.add(value);
    }

    private static Map<String, Object> item(ApplyRecord row) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", row.getID());
        item.put("eventType", row.getEventType());
        item.put("recordedAt", row.getRecordedAt());
        item.put("actor", pair("key", row.getActorKey(), "displayName", row.getActorName()));
        item.put("tool", pair("name", row.getToolName(), "title", row.getToolTitle()));
        item.put("operationRef", row.getOperationRef());
        item.put("operationState", row.getOperationState());
        item.put("domainStatus", row.getDomainStatus());
        item.put("errorCode", row.getErrorCode());
        item.put("errorMessage", row.getErrorMessage());
        item.put("rollbackRequested", row.isRollbackRequested());
        item.put("targets", targets(row.getTargetsJson()));
        item.put("links", links(row.getLinksJson()));
        if (ApplyEvent.APPLY.equals(row.getEventType()) && "edit_screen_layout".equals(row.getToolName())) {
            Map<String, Object> changes = fieldChanges(row.getFieldChangesJson());
            if (changes != null) item.put("fieldChanges", changes);
        }
        return item;
    }

    private static Map<String, Object> pair(String firstName, String first, String secondName, String second) {
        Map<String, Object> pair = new LinkedHashMap<String, Object>();
        pair.put(firstName, first);
        pair.put(secondName, second);
        return pair;
    }

    /** Legacy rows have no section; corrupt data is explicitly unavailable rather than known empty. */
    static Map<String, Object> fieldChanges(String json) {
        if (json == null) return null;
        ApplyFieldChanges changes;
        try {
            changes = ApplyFieldChanges.from(JSON.readValue(json, Object.class));
            if (changes == null) changes = ApplyFieldChanges.unavailable();
        } catch (Exception broken) {
            changes = ApplyFieldChanges.unavailable();
        }
        List<Object> items = new ArrayList<Object>(changes.items.size());
        for (ApplyFieldChanges.Item change : changes.items)
            items.add(pair("fieldId", change.fieldId, "action", change.action));
        Map<String, Object> section = new LinkedHashMap<String, Object>();
        section.put("available", changes.available);
        section.put("items", items);
        return section;
    }

    /** Parsed {@code TARGETS_JSON}; a broken value is an empty target list, never an error. */
    static Map<String, Object> targets(String json) {
        List<Object> items = new ArrayList<Object>();
        int more = 0;
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode array = root.get("items");
            if (!root.isObject() || array == null || !array.isArray() || !root.path("more").isInt()) throw new IllegalStateException();
            more = root.get("more").intValue();
            for (JsonNode node : array) {
                if (!node.isObject() || !node.path("type").isTextual() || !node.path("label").isTextual()) throw new IllegalStateException();
                Map<String, Object> target = new LinkedHashMap<String, Object>();
                target.put("type", node.get("type").textValue());
                target.put("label", node.get("label").textValue());
                target.put("id", node.path("id").isTextual() ? node.get("id").textValue() : null);
                items.add(target);
            }
        } catch (Exception broken) {
            items = new ArrayList<Object>();
            more = 0;
        }
        Map<String, Object> targets = new LinkedHashMap<String, Object>();
        targets.put("items", items);
        targets.put("more", more);
        return targets;
    }

    /** Parsed {@code LINKS_JSON}; a broken value is an empty list. */
    static List<Object> links(String json) {
        List<Object> links = new ArrayList<Object>();
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isArray()) throw new IllegalStateException();
            for (JsonNode node : root) {
                if (!node.isObject() || !node.path("url").isTextual()) throw new IllegalStateException();
                links.add(pair("name", node.path("name").isTextual() ? node.get("name").textValue() : null,
                        "url", node.get("url").textValue()));
            }
        } catch (Exception broken) {
            links = new ArrayList<Object>();
        }
        return links;
    }
}
