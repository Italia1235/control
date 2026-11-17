package ru.rasulov.common.impl.applies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort journal of applies done through the plugin ({@link #record} is the only write point). It never changes the
 * behaviour of the plugin: every failure of its own code is logged and swallowed, except {@link VirtualMachineError}.
 * Rows are written once; the plugin neither updates nor deletes them.
 */
@Named
public class ApplyJournal {
    static final int KEY_LIMIT = 300;
    static final int TYPE_LIMIT = 20;
    static final int ACTOR_KEY_LIMIT = 255;
    static final int ACTOR_NAME_LIMIT = 255;
    static final int TOOL_NAME_LIMIT = 100;
    static final int TOOL_TITLE_LIMIT = 255;
    static final int OPERATION_REF_LIMIT = 255;
    static final int STATE_LIMIT = 30;
    static final int DOMAIN_STATUS_LIMIT = 100;
    static final int ERROR_CODE_LIMIT = 100;
    static final int ERROR_MESSAGE_LIMIT = 1000;
    static final int JSON_LIMIT = 8000;
    static final int MAX_TARGETS = 10;
    static final int TARGET_TYPE_LIMIT = 50;
    static final int TARGET_LABEL_LIMIT = 255;
    static final int TARGET_ID_LIMIT = 100;
    static final int MAX_LINKS = 10;
    static final int LINK_NAME_LIMIT = 255;
    static final int LINK_URL_LIMIT = 2000;
    private static final String ELLIPSIS = "\u2026";
    private static final Set<String> EVENT_TYPES = new HashSet<String>(Arrays.asList(ApplyEvent.APPLY, ApplyEvent.ROLLBACK));
    /**
     * Terminal states of an operation as the rollback journal reports them. COMPLETED, FAILED and CANCELLED are the
     * states of the backlog; after the compensation that follows a failure or a cancel with rollback the journal itself
     * rewrites the state to ROLLED_BACK, PARTIAL or BLOCKED, and the row keeps it as is. QUEUED and RUNNING are not written.
     */
    private static final Set<String> STATES = new HashSet<String>(Arrays.asList("COMPLETED", "FAILED", "CANCELLED",
            "ROLLED_BACK", "PARTIAL", "BLOCKED"));
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApplyDatabase database;
    private final Logger log;

    @Inject
    public ApplyJournal(ApplyDatabase database) {
        this(database, LoggerFactory.getLogger(ApplyJournal.class));
    }

    ApplyJournal(ApplyDatabase database, Logger log) {
        this.database = database;
        this.log = log;
    }

    /** Writes one row in its own transaction. A repeated {@code eventKey} changes nothing. Never throws. */
    public void record(ApplyEvent event) {
        try {
            Map<String, Object> row = row(event);
            if (row == null) return;
            try {
                database.insert(row);
            } catch (RuntimeException | Error failure) {
                if (failure instanceof VirtualMachineError) throw failure;
                // A unique violation of a concurrent or repeated insert is a duplicate, not an error.
                if (existsQuietly(event.eventKey)) {
                    log.debug("Apply journal: event {} is already recorded", event.eventKey);
                    return;
                }
                throw failure;
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException | Error failure) {
            logFailure(event, failure);
        }
    }

    private boolean existsQuietly(String eventKey) {
        try {
            return database.exists(truncate(eventKey, KEY_LIMIT));
        } catch (RuntimeException | Error ignored) {
            if (ignored instanceof VirtualMachineError) throw ignored;
            return false;
        }
    }

    /** The row to insert, or {@code null} (already logged) if a required value is missing or not recognised. */
    private Map<String, Object> row(ApplyEvent event) {
        if (event == null) {
            log.error("Apply journal: cannot record a null event");
            return null;
        }
        String eventKey = required(event.eventKey);
        String eventType = required(event.eventType);
        String actorKey = required(event.actorKey);
        String actorName = required(event.actorName);
        String toolName = required(event.toolName);
        String toolTitle = required(event.toolTitle);
        String state = required(event.operationState);
        if (eventKey == null || eventType == null || actorKey == null || actorName == null || toolName == null
                || toolTitle == null || state == null || !EVENT_TYPES.contains(eventType) || !STATES.contains(state)
                || event.recordedAt <= 0) {
            logFailure(event, new IllegalArgumentException("A required apply journal value is missing or not recognised"));
            return null;
        }
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("EVENT_KEY", truncate(eventKey, KEY_LIMIT));
        row.put("EVENT_TYPE", truncate(eventType, TYPE_LIMIT));
        row.put("RECORDED_AT", event.recordedAt);
        row.put("ACTOR_KEY", truncate(actorKey, ACTOR_KEY_LIMIT));
        row.put("ACTOR_NAME", truncate(actorName, ACTOR_NAME_LIMIT));
        row.put("TOOL_NAME", truncate(toolName, TOOL_NAME_LIMIT));
        row.put("TOOL_TITLE", truncate(toolTitle, TOOL_TITLE_LIMIT));
        row.put("OPERATION_REF", optional(event.operationRef, OPERATION_REF_LIMIT));
        row.put("OPERATION_STATE", truncate(state, STATE_LIMIT));
        row.put("DOMAIN_STATUS", optional(event.domainStatus, DOMAIN_STATUS_LIMIT));
        row.put("ERROR_CODE", optional(event.errorCode, ERROR_CODE_LIMIT));
        row.put("ERROR_MESSAGE", optional(event.errorMessage, ERROR_MESSAGE_LIMIT));
        row.put("ROLLBACK_REQUESTED", event.rollbackRequested);
        row.put("TARGETS_JSON", targetsJson(event.targets, event.moreTargets));
        row.put("LINKS_JSON", linksJson(event.links));
        row.put("FIELD_CHANGES_JSON", ApplyEvent.APPLY.equals(eventType) && "edit_screen_layout".equals(toolName)
                && event.fieldChanges != null ? JSON.valueToTree(event.fieldChanges).toString() : null);
        return row;
    }

    private static String required(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static String optional(String value, int limit) {
        return value == null || value.trim().isEmpty() ? null : truncate(value, limit);
    }

    /** At most {@code limit} chars; longer values end with an ellipsis. A surrogate pair is never split. */
    static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        int end = limit - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + ELLIPSIS;
    }

    static String targetsJson(List<ApplyTarget> targets, int alreadyMore) {
        java.util.ArrayList<ObjectNode> items = new java.util.ArrayList<ObjectNode>();
        int more = alreadyMore;
        for (ApplyTarget target : targets) {
            if (target == null || required(target.type) == null || required(target.label) == null) continue;
            if (items.size() >= MAX_TARGETS) { more++; continue; }
            ObjectNode item = JSON.createObjectNode();
            item.put("type", truncate(target.type, TARGET_TYPE_LIMIT));
            item.put("label", truncate(target.label, TARGET_LABEL_LIMIT));
            String id = optional(target.id, TARGET_ID_LIMIT);
            if (id != null) item.put("id", id);
            items.add(item);
        }
        while (true) {
            ObjectNode root = JSON.createObjectNode();
            ArrayNode array = root.putArray("items");
            for (ObjectNode item : items) array.add(item);
            root.put("more", more);
            String text = root.toString();
            if (text.length() <= JSON_LIMIT || items.isEmpty()) return text;
            items.remove(items.size() - 1);
            more++;
        }
    }

    static String linksJson(List<ApplyLink> links) {
        java.util.ArrayList<ObjectNode> kept = new java.util.ArrayList<ObjectNode>();
        for (ApplyLink link : links) {
            if (link == null || required(link.url) == null || link.url.length() > LINK_URL_LIMIT) continue;
            if (kept.size() >= MAX_LINKS) break;
            ObjectNode item = JSON.createObjectNode();
            item.put("name", optional(link.name, LINK_NAME_LIMIT));
            item.put("url", link.url);
            kept.add(item);
        }
        while (true) {
            ArrayNode array = JSON.createArrayNode();
            for (ObjectNode item : kept) array.add(item);
            String text = array.toString();
            if (text.length() <= JSON_LIMIT || kept.isEmpty()) return text;
            kept.remove(kept.size() - 1);
        }
    }

    private void logFailure(ApplyEvent event, Throwable failure) {
        log.error("Apply journal could not record eventKey={} eventType={} actorKey={} toolName={} operationState={} "
                        + "domainStatus={}; the apply itself is not affected",
                event == null ? null : event.eventKey, event == null ? null : event.eventType,
                event == null ? null : event.actorKey, event == null ? null : event.toolName,
                event == null ? null : event.operationState, event == null ? null : event.domainStatus, failure);
    }
}
