package ru.rasulov.customfields.impl.undo;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.FieldConfigSchemeManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.impl.undo.NativeEntityCount;
import ru.rasulov.common.impl.undo.UndoHandler;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.customfields.impl.context.ContextSchemeFactory;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;
import static ru.rasulov.customfields.impl.undo.FieldUndoJson.*;

/** Field creation consists only of new contexts/options and a context name change. Never recreates user data. */
@Named
public class CustomFieldUndoHandler implements UndoHandler {
    private final CustomFieldManager fields;
    private final FieldConfigSchemeManager schemes;
    private final OptionsManager options;
    private final FieldScreenManager screens;
    private final FieldLayoutManager layouts;
    private final OfBizDelegator db;
    private final ContextSchemeFactory factory;
    @Inject
    public CustomFieldUndoHandler(@ComponentImport CustomFieldManager fields,
            @ComponentImport FieldConfigSchemeManager schemes, @ComponentImport OptionsManager options,
            @ComponentImport FieldScreenManager screens, @ComponentImport FieldLayoutManager layouts,
            @ComponentImport OfBizDelegator db, ContextSchemeFactory factory) {
        this.fields = fields; this.schemes = schemes; this.options = options;
        this.screens = screens; this.layouts = layouts; this.db = db; this.factory = factory;
    }
    public String kind() { return "cf.field"; }
    public List<String> lockNames(UndoStep step) {
        return Arrays.asList("ru.rasulov.control.create-custom-fields", "ru.rasulov.control.edit-screens",
                "ru.rasulov.control.field-requirements");
    }
    /** The field id recorded after the write was confirmed; a step without a stored post-state names none. */
    @Override public Map<String, Object> publicIdentity(UndoStep step) {
        Object field = parse(step.getAfterJson()).get("field");
        return field instanceof String ? Collections.<String, Object>singletonMap("fieldId", field) : Collections.<String, Object>emptyMap();
    }
    public String snapshot(String name, CustomField field) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(
                    fullSnapshot(name, field).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return json(map("name", name, "field", field == null ? null : field.getId(),
                    "fingerprint", java.util.Base64.getEncoder().encodeToString(hash)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public String persistedSnapshot(String name, String fieldId) {
        return snapshot(name, current(map("field", fieldId)));
    }
    public String withAction(String snapshot, Map<String, Object> action) {
        Map<String, Object> state = parse(snapshot);
        state.put("action", action);
        return json(state);
    }
    public boolean sameState(String left, String right) {
        if (left == null || right == null) return Objects.equals(left, right);
        Map<String, Object> a = parse(left), b = parse(right);
        a.remove("action"); b.remove("action");
        return a.equals(b);
    }
    private String fullSnapshot(String name, CustomField field) {
        if (field == null) return json(map("name", name, "field", null));
        List<Object> contexts = new ArrayList<Object>();
        List<FieldConfigScheme> sorted = new ArrayList<FieldConfigScheme>(field.getConfigurationSchemes());
        sorted.sort(Comparator.comparing(FieldConfigScheme::getId));
        for (FieldConfigScheme scheme : sorted) {
            List<Object> configs = new ArrayList<Object>();
            Map<String, FieldConfig> configMap = new TreeMap<String, FieldConfig>(Comparator.nullsFirst(Comparator.naturalOrder()));
            configMap.putAll(scheme.getConfigs());
            for (Map.Entry<String, FieldConfig> entry : configMap.entrySet()) {
                List<Object> values = new ArrayList<Object>();
                for (org.ofbiz.core.entity.GenericValue option : db.findByAnd("CustomFieldOption",
                        Collections.singletonMap("customfieldconfig", entry.getValue().getId()),
                        Collections.singletonList("id ASC")))
                    values.add(Arrays.asList(option.getLong("id"), option.getLong("parentoptionid"),
                            option.getLong("sequence"), option.getString("value"), option.getString("disabled")));
                configs.add(map("type", entry.getKey(), "id", entry.getValue().getId(), "options", values));
            }
            contexts.add(map("id", scheme.getId(), "name", scheme.getName(), "description", scheme.getDescription(),
                    "global", scheme.isAllProjects(), "allTypes", scheme.isAllIssueTypes(),
                    "projects", sortedIds(scheme.getAssociatedProjectIds()),
                    "types", sortedStrings(scheme.getAssociatedIssueTypeIds()), "configs", configs));
        }
        return json(map("name", name, "field", map("id", field.getId(), "name", field.getName(),
                "description", field.getDescription(), "type", field.getCustomFieldType().getKey(),
                "searcher", field.getCustomFieldSearcher() == null ? null : field.getCustomFieldSearcher().getDescriptor().getCompleteKey(),
                "contexts", contexts)));
    }
    private static List<Long> sortedIds(Collection<Long> values) {
        List<Long> result = new ArrayList<Long>(values == null ? Collections.<Long>emptyList() : values);
        result.sort(Comparator.nullsFirst(Comparator.naturalOrder())); return result;
    }
    private static List<String> sortedStrings(Collection<String> values) {
        List<String> result = new ArrayList<String>(values == null ? Collections.<String>emptyList() : values);
        result.sort(Comparator.nullsFirst(Comparator.naturalOrder())); return result;
    }
    private CustomField current(Map<String, Object> state) {
        fields.refresh();
        if (state.get("field") == null) return null;
        String fieldId = (String) state.get("field");
        schemes.init();
        fields.refreshConfigurationSchemes(Long.valueOf(fieldId.substring("customfield_".length())));
        return fields.getCustomFieldObject(fieldId);
    }
    public String conflict(UndoStep step) { return conflict(step, Collections.<UndoStep>emptyList()); }
    public String conflict(UndoStep step, List<UndoStep> selection) {
        Map<String, Object> before = parse(step.getBeforeJson());
        Map<String, Object> after = step.getAfterJson() == null ? null : parse(step.getAfterJson());
        String name = (String) before.get("name");
        if (after == null) {
            CustomField previous = current(before);
            if (before.get("field") != null && sameState(snapshot(name, previous), step.getBeforeJson())) return null;
            return before.get("field") == null && fields.getCustomFieldObjectsByNameIgnoreCase(name).isEmpty()
                    ? null : "Uncertain field mutation: successful poststate was not persisted";
        }
        CustomField field = current(after);
        String live = snapshot(name, field);
        if (sameState(live, step.getBeforeJson())) return null;
        if (!sameState(live, step.getAfterJson())) return "Field metadata, contexts or options changed after this operation";
        boolean deletingField = before.get("field") == null;
        for (UndoStep selected : selection) if ("cf.field".equals(selected.getKind())
                && step.getResource().equals(selected.getResource())
                && parse(selected.getBeforeJson()).get("field") == null) deletingField = true;
        Map<?, ?> action = (Map<?, ?>) after.get("action");
        if (action != null && "rename".equals(action.get("type")) && !action.containsKey("oldDescription"))
            return "Recorded context rename lacks its original description; cannot prove safe restoration";
        boolean deletingConfiguration = action != null
                && ("option".equals(action.get("type")) || "context".equals(action.get("type")));
        return deletionConflict(field, deletingField, deletingConfiguration, selection);
    }
    private String deletionConflict(CustomField field, boolean deletingField, boolean deletingConfiguration,
                                    List<UndoStep> selection) {
        if (field == null) return null;
        String typeKey = field.getCustomFieldType() == null ? null : field.getCustomFieldType().getKey();
        if ((deletingField || deletingConfiguration)
                && !ru.rasulov.customfields.impl.validation.TypeKeys.hasNativeValueStorage(typeKey))
            return "Cannot prove field/context/option deletion is safe for type '" + typeKey
                    + "': its issue values may use external storage; the field and dependent data are retained";
        Long numeric = Long.valueOf(field.getId().substring("customfield_".length()));
        if (NativeEntityCount.count(db, "CustomFieldValue", Collections.singletonMap("customfield", numeric)) != 0)
            return "Field has issue data; deleting its field, context or options is unsafe";
        if (NativeEntityCount.count(db, "Label", Collections.singletonMap("fieldid", numeric)) != 0)
            return "Field has native label issue data; deleting its field, context or options is unsafe";
        if (deletingField) {
            screens.refresh();
            layouts.refresh();
            for (com.atlassian.jira.issue.fields.screen.FieldScreenTab tab : screens.getFieldScreenTabs(field.getId())) {
                if (!ownScreenAddition(tab, field.getId(), selection)) return "Field is referenced by a foreign screen addition";
            }
            for (EditableFieldLayout layout : layouts.getEditableFieldLayouts()) {
                FieldLayoutItem item = layout.getFieldLayoutItem(field.getId());
                String description = item == null ? null : item.getRawFieldDescription();
                if (item != null && (item.isRequired() || item.isHidden()
                        || (description != null && !description.isEmpty() && !Objects.equals(description, field.getDescription())))
                        && !ownLayoutChange(layout, field.getId(), selection))
                    return "Field configuration contains foreign changes";
            }
            if (ru.rasulov.common.impl.undo.NativeEntityScan.anyMatch(db, "Workflow", null,
                    Collections.singletonList("descriptor"), workflow -> {
                        String descriptor = workflow.getString("descriptor");
                        return descriptor != null && descriptor.contains(field.getId());
                    })) return "Field is referenced by a workflow";
            if (ru.rasulov.common.impl.undo.NativeEntityScan.anyMatch(db, "SearchRequest", null,
                    Collections.singletonList("request"), filter -> {
                        String query = filter.getString("request");
                        return query != null && (query.contains(field.getId()) || query.contains("cf[" + numeric + "]")
                                || (field.getName() != null && query.contains(field.getName())));
                    })) return "Field is referenced by a saved filter";
        }
        return null;
    }
    @SuppressWarnings("unchecked")
    private boolean ownScreenAddition(com.atlassian.jira.issue.fields.screen.FieldScreenTab tab,
                                      String fieldId, List<UndoStep> selection) {
        for (UndoStep step : selection) if (pending(step)
                && ("cf.screen".equals(step.getKind()) || "screen.change".equals(step.getKind()))
                && step.getAfterJson() != null
                && Objects.equals(id(parse(step.getAfterJson()).get("id")), tab.getFieldScreen().getId())) {
            Map<String, Object> beforeState = parse(step.getBeforeJson()), afterState = parse(step.getAfterJson());
            if (!"screen".equals(afterState.get("type"))) continue;
            boolean before = screenContains(beforeState, fieldId);
            boolean after = screenContains(afterState, fieldId);
            if (!before && after) return true;
        }
        return false;
    }
    @SuppressWarnings("unchecked")
    private boolean screenContains(Map<String, Object> screen, String field) {
        if (screen.get("children") == null) return false;
        for (Map<String, Object> tab : (List<Map<String, Object>>) screen.get("children"))
            for (Map<String, Object> item : (List<Map<String, Object>>) tab.get("children"))
                if (field.equals(item.get("field"))) return true;
        return false;
    }
    @SuppressWarnings("unchecked")
    private boolean ownLayoutChange(EditableFieldLayout layout, String field, List<UndoStep> selection) {
        for (UndoStep step : selection) if (pending(step) && step.getAfterJson() != null
                && ("cf.required".equals(step.getKind()) || "cf.layout".equals(step.getKind()))) {
            Map<String, Object> after = parse(step.getAfterJson());
            Map<String, Object> data = (Map<String, Object>) after.get("layout");
            if (data == null || !Objects.equals(id(data.get("id")), layout.getId())) continue;
            if ("cf.layout".equals(step.getKind()) && parse(step.getBeforeJson()).get("layout") == null) return true;
            Map<String, Object> action = (Map<String, Object>) parse(step.getBeforeJson()).get("action");
            if (action != null && field.equals(action.get("field")) && Boolean.FALSE.equals(action.get("required"))
                    && !layout.getFieldLayoutItem(field).isHidden()) return true;
        }
        return false;
    }
    public void undo(UndoStep step, ApplicationUser actor) {
        undo(step, actor, Collections.<UndoStep>emptyList());
    }
    @SuppressWarnings("unchecked")
    public void undo(UndoStep step, ApplicationUser actor, List<UndoStep> selection) {
        String conflict = conflict(step, selection);
        if (conflict != null) throw new IllegalStateException(conflict);
        Map<String, Object> before = parse(step.getBeforeJson());
        if (step.getAfterJson() == null) return;
        CustomField field = current(parse(step.getAfterJson()));
        if (sameState(snapshot((String) before.get("name"), field), step.getBeforeJson())) return;
        if (before.get("field") == null) {
            try { fields.removeCustomField(field); }
            catch (Exception e) { throw new IllegalStateException("Cannot remove created field", e); }
        } else {
            Map<String, Object> action = (Map<String, Object>) parse(step.getAfterJson()).get("action");
            String type = (String) action.get("type");
            Long target = id(action.get("id"));
            if ("option".equals(type)) options.deleteOptionAndChildren(options.findByOptionId(target));
            else if ("context".equals(type)) schemes.removeFieldConfigScheme(target);
            else if ("rename".equals(type)) schemes.updateFieldConfigScheme(
                    factory.renamed(schemes.getFieldConfigScheme(target), (String) action.get("oldName"),
                            (String) action.get("oldDescription")));
            else throw new IllegalStateException("Unknown field undo mutation " + type);
        }
        fields.refreshConfigurationSchemes(Long.valueOf(field.getId().substring("customfield_".length())));
        CustomField remaining = current(parse(step.getAfterJson()));
        if (!sameState(snapshot((String) before.get("name"), remaining), step.getBeforeJson()))
            throw new IllegalStateException("Field compensation did not restore recorded state");
    }
}
