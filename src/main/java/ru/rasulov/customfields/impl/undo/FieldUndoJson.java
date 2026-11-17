package ru.rasulov.customfields.impl.undo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical, small JSON payloads shared by field writers and their native undo handlers. */
public final class FieldUndoJson {
    private static final ObjectMapper JSON = new ObjectMapper();
    private FieldUndoJson() { }
    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode field undo state", e); }
    }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String value) {
        try { return JSON.readValue(value, Map.class); }
        catch (Exception e) { throw new IllegalStateException("Cannot decode field undo state", e); }
    }
    public static Long id(Object value) { return value == null ? null : ((Number) value).longValue(); }
    public static boolean pending(ru.rasulov.common.impl.undo.UndoStep step) {
        return "PREPARED".equals(step.getState()) || "APPLIED".equals(step.getState()) || "BLOCKED".equals(step.getState());
    }
}
