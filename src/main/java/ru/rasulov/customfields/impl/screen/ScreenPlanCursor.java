package ru.rasulov.customfields.impl.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.customfields.api.dto.screen.Recommendation;
import ru.rasulov.customfields.api.dto.screen.ScreenCandidate;
import ru.rasulov.customfields.api.dto.screen.ScreenPlan;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** Read-only continuation bound to the complete observed selection, not a mutable list offset. */
final class ScreenPlanCursor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ActionPlanCodec CODEC = new ActionPlanCodec();

    private ScreenPlanCursor() { }

    static String fingerprint(ScreenPlan plan, Object membership) {
        com.fasterxml.jackson.databind.node.ObjectNode tree = MAPPER.createObjectNode();
        tree.put("plan", MAPPER.valueToTree(plan));
        tree.put("membership", MAPPER.valueToTree(membership));
        normalize(tree, "");
        return CODEC.fingerprint(tree);
    }

    static String next(String fieldId, Recommendation filter, String fingerprint, long lastId) {
        String payload = "1\n" + fieldId + "\n" + filterName(filter) + "\n" + fingerprint + "\n" + lastId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    static int start(String cursor, String fieldId, Recommendation filter, String fingerprint,
                     List<ScreenCandidate> selected) {
        String[] parts;
        long lastId;
        try {
            if (cursor.length() > 512) throw new IllegalArgumentException();
            parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 5 || !"1".equals(parts[0]) || !parts[3].matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
            lastId = Long.parseLong(parts[4]);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Invalid screen cursor. Restart plan_field_screens without cursor.");
        }
        if (!fieldId.equals(parts[1]) || !filterName(filter).equals(parts[2])) {
            throw new InvalidRequestException("Screen cursor belongs to another field or recommendation filter. Restart without cursor.");
        }
        if (!fingerprint.equals(parts[3])) {
            throw new InvalidRequestException("Screen selection changed. Discard previous pages and restart plan_field_screens without cursor.");
        }
        for (int i = 0; i < selected.size(); i++) {
            if (selected.get(i).getScreenId() == lastId && i + 1 < selected.size()) return i + 1;
        }
        throw new InvalidRequestException("Invalid screen cursor position. Restart plan_field_screens without cursor.");
    }


    private static String filterName(Recommendation filter) {
        return filter == null ? "" : filter.name();
    }

    /** Preserve tab order (first-tab writes depend on it); other DTO arrays are unordered sets. */
    private static void normalize(JsonNode node, String property) {
        if (node.isObject()) {
            for (Iterator<String> names = node.fieldNames(); names.hasNext();) {
                String name = names.next();
                normalize(node.get(name), name);
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            List<JsonNode> values = new ArrayList<JsonNode>(array.size());
            for (JsonNode value : array) {
                normalize(value, property);
                values.add(value);
            }
            if (!"tabs".equals(property) && !"screens".equals(property)) {
                values.sort(Comparator.comparing(JsonNode::toString));
                array.removeAll();
                for (JsonNode value : values) array.add(value);
            }
        }
    }
}
