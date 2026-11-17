package ru.rasulov.customfields.impl.undo;

import com.atlassian.jira.issue.fields.layout.field.*;
import java.util.*;
import static ru.rasulov.customfields.impl.undo.FieldUndoJson.*;

public final class FieldLayoutState {
    private FieldLayoutState() { }
    public static String snapshot(String name, FieldLayout layout) {
        return snapshot(name, layout, null, false, false);
    }
    /** The snapshot the layout would have if only {@code fieldId}'s required flag were {@code required}. */
    public static String snapshotWithRequired(String name, FieldLayout layout, String fieldId, boolean required) {
        return snapshot(name, layout, fieldId, required, false);
    }
    /**
     * Like {@link #snapshot} but independent of item enumeration order (items keyed by field id). For verifying a
     * forward write only: Jira does not order the rows it reloads, so order is not a meaningful change there.
     * Persisted undo fingerprints keep using the ordered form.
     */
    public static String canonicalSnapshot(String name, FieldLayout layout) {
        return snapshot(name, layout, null, false, true);
    }
    public static String canonicalSnapshotWithRequired(String name, FieldLayout layout, String fieldId, boolean required) {
        return snapshot(name, layout, fieldId, required, true);
    }
    private static String snapshot(String name, FieldLayout layout, String changedField, boolean required, boolean canonical) {
        if (layout == null) return json(map("name", name, "layout", null));
        List<Object> items = new ArrayList<Object>();
        for (FieldLayoutItem item : layout.getFieldLayoutItems()) {
            String id = item.getOrderableField().getId();
            items.add(map("field", id,
                    "required", changedField != null && changedField.equals(id) ? required : item.isRequired(),
                    "hidden", item.isHidden(), "description", item.getRawFieldDescription(),
                    "renderer", item.getRendererType()));
        }
        if (canonical) items.sort(Comparator.comparing(o -> String.valueOf(((Map<?, ?>) o).get("field"))));
        return snapshot(name, layout, items);
    }
    private static String snapshot(String name, FieldLayout layout, List<Object> items) {
        String full = json(map("id", layout.getId(), "name", layout.getName(), "description", layout.getDescription(),
                "default", layout.isDefault(), "items", items));
        try {
            String fingerprint = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(full.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return json(map("name", name, "layout", map("id", layout.getId(), "default", layout.isDefault()),
                    "fingerprint", fingerprint));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static boolean sameState(String left, String right) {
        if (left == null || right == null) return Objects.equals(left, right);
        Map<String, Object> a = parse(left), b = parse(right);
        a.remove("action"); b.remove("action");
        return a.equals(b);
    }
    public static void store(FieldLayoutManager manager, EditableFieldLayout layout) {
        if (layout instanceof EditableDefaultFieldLayout) manager.storeEditableDefaultFieldLayout((EditableDefaultFieldLayout) layout);
        else manager.storeEditableFieldLayout(layout);
    }
}
