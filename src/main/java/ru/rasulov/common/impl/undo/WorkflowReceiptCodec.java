package ru.rasulov.common.impl.undo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned storage only: callers and get_operation always use the full named receipt fields. */
public final class WorkflowReceiptCodec {
    public static final int ERROR_LENGTH = 160;
    private static final List<String> KINDS = List.of("STATUS", "SCREEN", "WORKFLOW_COPY", "DRAFT", "WORKFLOW_SAVE");
    private static final List<String> WRITES = List.of("NOT_ATTEMPTED", "UNKNOWN", "APPLIED", "FAILED");
    private static final List<String> CHECKS = List.of("NOT_CHECKED", "MATCH", "MISMATCH", "UNAVAILABLE");
    private static final Set<String> FIELDS = Set.of("key", "kind", "writeStatus", "verification", "id", "error");

    private WorkflowReceiptCodec() { }

    public static String write(Object execution) {
        Object encoded = encode(execution);
        return JournalJson.write(encoded);
    }

    public static Object read(String json) {
        Object value = JournalJson.read(json, Object.class);
        if (!(value instanceof Map)) return value;
        Map<?, ?> storage = (Map<?, ?>) value;
        if (!storage.containsKey("workflowReceipt")) return value;
        if (!Integer.valueOf(1).equals(storage.get("workflowReceipt")) || storage.size() != 2)
            throw new IllegalArgumentException("Unsupported workflow receipt storage version");
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object item : (List<?>) storage.get("rows")) {
            List<?> row = (List<?>) item;
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("key", row.get(0));
            step.put("kind", KINDS.get(((Number) row.get(1)).intValue()));
            step.put("writeStatus", WRITES.get(((Number) row.get(2)).intValue()));
            step.put("verification", CHECKS.get(((Number) row.get(3)).intValue()));
            if (row.size() > 4 && row.get(4) != null) step.put("id", row.get(4));
            if (row.size() > 5) step.put("error", row.get(5));
            steps.add(step);
        }
        return Map.of("steps", steps);
    }

    /**
     * Every reachable prefix fits: all steps may acquire a signed-long ID, while only the stopping step can
     * acquire an error. Enum codes all have equal width. NUL reserves the maximum JSON escape cost (six bytes)
     * for each of the writer's bounded error characters, including errors without a returned ID.
     */
    public static int reservedBytes(Object execution) {
        Object encoded = encode(execution);
        if (!(encoded instanceof Map) || !((Map<?, ?>) encoded).containsKey("workflowReceipt"))
            throw new IllegalArgumentException("Workflow receipt has an unsupported step shape");
        List<?> rows = (List<?>) ((Map<?, ?>) encoded).get("rows");
        List<List<Object>> maximum = new ArrayList<>(rows.size());
        for (Object item : rows) {
            List<?> row = (List<?>) item;
            List<Object> full = new ArrayList<>(6);
            full.addAll(row.subList(0, 4));
            full.add("9".repeat(20));
            maximum.add(full);
        }
        if (!maximum.isEmpty()) maximum.get(0).add("\u0000".repeat(ERROR_LENGTH));
        return bytes(JournalJson.write(storage(maximum)));
    }

    public static int bytes(String json) { return json.getBytes(StandardCharsets.UTF_8).length; }

    private static Object encode(Object execution) {
        if (!(execution instanceof Map)) return execution;
        Map<?, ?> root = (Map<?, ?>) execution;
        if (root.size() != 1 || !(root.get("steps") instanceof List)) return execution;
        List<?> steps = (List<?>) root.get("steps");
        List<List<Object>> rows = new ArrayList<>(steps.size());
        for (Object item : steps) {
            if (!(item instanceof Map)) return execution;
            Map<?, ?> step = (Map<?, ?>) item;
            int kind = KINDS.indexOf(step.get("kind"));
            int write = WRITES.indexOf(step.get("writeStatus"));
            int check = CHECKS.indexOf(step.get("verification"));
            if (!FIELDS.containsAll(step.keySet()) || !(step.get("key") instanceof String)
                    || kind < 0 || write < 0 || check < 0
                    || (step.containsKey("id") && !(step.get("id") instanceof String))
                    || (step.containsKey("error") && !(step.get("error") instanceof String))) return execution;
            // Version 1 row positions: key, kind, writeStatus, verification, optional id, optional error.
            List<Object> row = new ArrayList<>(6);
            row.add(step.get("key")); row.add(kind); row.add(write); row.add(check);
            if (step.containsKey("id") || step.containsKey("error")) row.add(step.get("id"));
            if (step.containsKey("error")) row.add(step.get("error"));
            rows.add(row);
        }
        return storage(rows);
    }

    private static Map<String, Object> storage(Object rows) {
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("workflowReceipt", 1);
        storage.put("rows", rows);
        return storage;
    }
}
