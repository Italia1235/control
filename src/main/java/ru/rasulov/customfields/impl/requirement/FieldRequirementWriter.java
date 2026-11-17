package ru.rasulov.customfields.impl.requirement;

import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.JournalCancelledException;
import ru.rasulov.customfields.api.dto.requirement.PartialEffect;
import ru.rasulov.customfields.api.dto.requirement.PartialEffect.Item;
import ru.rasulov.customfields.api.dto.requirement.PartialEffect.Kind;
import ru.rasulov.customfields.api.dto.requirement.PartialEffect.State;
import ru.rasulov.customfields.impl.configuration.FieldLayoutCopyFactory;
import ru.rasulov.customfields.impl.requirement.RequirementPlan.LayoutChange;
import ru.rasulov.customfields.impl.requirement.RequirementPlan.Wanted;
import ru.rasulov.customfields.impl.requirement.RequirementPlan.Plan;
import ru.rasulov.customfields.impl.requirement.RequirementPlan.Split;
import ru.rasulov.customfields.impl.undo.FieldLayoutState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Write half of {@code set_field_required}: mutates a layout or copies it, maps the selected types to the copy,
 * verifies by reading back, and undoes what this operation created when a split fails. It deletes only a copy it
 * created itself and only after it has confirmed that no scheme row refers to it.
 */
final class FieldRequirementWriter {
    private static final Logger LOG = LoggerFactory.getLogger(FieldRequirementWriter.class);
    private static final int MAX_DIFFERENCES = 10;

    private final FieldLayoutManager fieldLayoutManager;
    private final FieldLayoutCopyFactory copies;
    private final ChangeJournal journal;

    FieldRequirementWriter(FieldLayoutManager fieldLayoutManager, FieldLayoutCopyFactory copies, ChangeJournal journal) {
        this.fieldLayoutManager = fieldLayoutManager;
        this.copies = copies;
        this.journal = journal;
    }

    /** A failed apply together with exactly what it left in Jira. */
    static final class WriteFailure extends RuntimeException {
        final PartialEffect effect;

        private WriteFailure(String message, PartialEffect effect, Throwable cause) {
            super(message, cause);
            this.effect = effect;
        }
    }

    /**
     * Applies the plan. A failure surfaces as a {@link WriteFailure} carrying what persists or could not be confirmed,
     * including the first write of a plan without copies (its flag may be stored even though the call failed). Jira's own
     * exception propagates only when nothing was attempted, so there is nothing to report.
     * Cancellation always passes through unchanged.
     */
    void write(Plan plan) {
        Effect effect = new Effect(plan);
        try {
            for (LayoutChange change : plan.changes) {
                for (Wanted wanted : change.fields) writeChange(change, wanted.field, wanted.required, effect);
            }
            for (Split split : plan.splits) writeSplit(plan.project, split, effect);
        } catch (JournalCancelledException e) {
            // A cancel after confirmed or unconfirmed writes must not discard what is known about them.
            if (effect.applied.isEmpty() && effect.leftBehind.isEmpty()) throw e;
            throw new WriteFailure(failureMessage(e, effect), effect.build(), e);
        } catch (RuntimeException e) {
            if (plan.splits.isEmpty() && effect.applied.isEmpty() && effect.leftBehind.isEmpty()) throw e;
            throw new WriteFailure(failureMessage(e, effect), effect.build(), e);
        }
    }

    private void writeChange(LayoutChange change, OrderableField field, boolean required, Effect effect) {
        journal.checkpoint();
        Long id = change.layout.getId();
        Flag key = new Flag(change, field.getId());
        Item pending = effect.pending.get(key);
        boolean[] stored = new boolean[1];
        try {
            EditableFieldLayout editable = change.layout.isDefault() ? fieldLayoutManager.getEditableDefaultFieldLayout()
                    : fieldLayoutManager.getEditableFieldLayout(id);
            setRequired(editable, field, id, required, stored);
        } catch (RuntimeException e) {
            // Before the store call nothing was written for this flag: it stays "not started", never "unknown".
            if (stored[0]) {
                effect.pending.remove(key);
                effect.leftBehind.add(flagItem(pending, pending.getId(), State.UNKNOWN,
                        "The write or its readback failed; the stored required flag was not confirmed: " + e.getMessage()));
            }
            throw e;
        }
        effect.pending.remove(key);
        effect.applied.add(flagItem(pending, pending.getId(), State.APPLIED, null));
        journal.checkpoint();
    }

    /** Copies the shared layout, sets the flag in the copy, then maps only the selected types to it. */
    private void writeSplit(Project project, Split split, Effect effect) {
        journal.checkpoint();
        Set<Long> existingLayouts = layoutIds();
        List<Mapping> mappings = new ArrayList<Mapping>();
        try {
            EditableFieldLayout copy = fieldLayoutManager.storeAndReturnEditableFieldLayout(
                    copies.copyOf(split.source, split.newName));
            if (copy == null || copy.getId() == null) throw new IllegalStateException("A copied field configuration has no id.");
            split.createdId = copy.getId();
            for (Wanted wanted : split.fields) {
                Flag key = new Flag(split, wanted.field.getId());
                Item item = effect.pending.get(key);
                effect.pending.put(key, flagItem(item, String.valueOf(split.createdId), State.NOT_STARTED, null));
            }
            writeSplitCopy(project, split, mappings, effect);
        } catch (RuntimeException e) {
            compensate(project, split, existingLayouts, mappings, effect);
            if (e instanceof JournalCancelledException && !effect.leftBehind.isEmpty()) {
                LOG.warn("Cancelled set_field_required left objects behind: {}", describe(effect.leftBehind));
                e.addSuppressed(new IllegalStateException("Left behind: " + describe(effect.leftBehind)));
            }
            throw e;
        }
        effect.pending.remove(split);
        effect.applied.add(copyItem(split, State.APPLIED, null));
        for (Mapping mapping : mappings) effect.applied.add(mapping.item(split, State.APPLIED, null));
    }

    private void writeSplitCopy(Project project, Split split,
                                List<Mapping> mappings, Effect effect) {
        fieldLayoutManager.refresh();
        journal.checkpoint();
        for (Wanted wanted : split.fields) {
            OrderableField field = wanted.field;
            Item pending = effect.pending.remove(new Flag(split, field.getId()));
            try {
                setRequired(fieldLayoutManager.getEditableFieldLayout(split.createdId), field, split.createdId, wanted.required,
                        new boolean[1]);
                effect.applied.add(flagItem(pending, String.valueOf(split.createdId), State.APPLIED, null));
            } catch (RuntimeException e) {
                effect.leftBehind.add(flagItem(pending, String.valueOf(split.createdId), State.UNKNOWN,
                        "The write or readback failed: " + e.getMessage()));
                throw e;
            }
            journal.checkpoint();
        }
        journal.checkpoint();
        FieldConfigurationScheme assigned = fieldLayoutManager.getFieldConfigurationScheme(project);
        if (assigned == null) throw new IllegalStateException("The project lost its field configuration scheme.");
        for (IssueType type : split.issueTypes) {
            fieldLayoutManager.refresh();
            FieldLayoutScheme scheme = fieldLayoutManager.getMutableFieldLayoutScheme(assigned.getId());
            FieldLayoutSchemeEntity existing = scheme.getEntity(type.getId());
            mappings.add(new Mapping(type, assigned.getId(), existing != null,
                    existing == null ? null : existing.getFieldLayoutId()));
            if (existing == null) {
                fieldLayoutManager.createFieldLayoutSchemeEntity(scheme, type.getId(), split.createdId);
            } else {
                existing.setFieldLayoutId(split.createdId);
                fieldLayoutManager.updateFieldLayoutSchemeEntity(existing);
            }
            journal.checkpoint();
        }
        fieldLayoutManager.refresh();
        for (IssueType type : split.issueTypes) {
            FieldLayout effective = fieldLayoutManager.getFieldLayout(project, type.getId());
            if (effective == null || !split.createdId.equals(effective.getId())) {
                throw new IllegalStateException("Jira did not persist the field configuration of issue type " + type.getId() + ".");
            }
        }
    }

    // ---- compensation: never calls journal.checkpoint(), so a cancellation cannot interrupt the cleanup ----

    private void compensate(Project project, Split split, Set<Long> existingLayouts, List<Mapping> mappings,
                            Effect effect) {
        for (int i = mappings.size() - 1; i >= 0; i--) {
            Mapping mapping = mappings.get(i);
            try {
                Restored restored = restore(project, split, mapping);
                if (restored == Restored.CHANGED) {
                    effect.compensated.add(mapping.item(split, State.COMPENSATED, "Restored to the original configuration."));
                } else if (restored == Restored.FOREIGN) {
                    effect.leftBehind.add(mapping.item(split, State.UNKNOWN,
                            "changed by someone else during the operation; not restored"));
                }
            } catch (RuntimeException e) {
                LOG.error("Could not restore the field configuration of issue type {}", mapping.type.getId(), e);
                effect.leftBehind.add(mapping.item(split, State.UNKNOWN,
                        "Restoring the original configuration failed or was not confirmed: " + e.getMessage()));
            }
        }
        if (split.createdId == null) {
            try {
                reportUnconfirmedCopy(split, existingLayouts, effect);
            } catch (RuntimeException e) {
                LOG.error("Could not check for a copy of field configuration '{}'", split.newName, e);
                effect.pending.remove(split);
                effect.leftBehind.add(new Item(Kind.FIELD_CONFIGURATION_COPY, null, split.newName, State.UNKNOWN,
                        "Creating the copy failed and Jira could not be asked whether a copy with this name exists: "
                                + e.getMessage()));
            }
            return;
        }
        effect.pending.remove(split);
        removeCopy(split, effect);
    }

    /**
     * The factory returned no id, so ownership of a same-named configuration cannot be proven (it may have been created
     * concurrently by someone else). It is reported, never deleted.
     */
    private void reportUnconfirmedCopy(Split split, Set<Long> existingLayouts, Effect effect) {
        fieldLayoutManager.refresh();
        for (FieldLayout layout : fieldLayoutManager.getEditableFieldLayouts()) {
            if (layout.getId() != null && !existingLayouts.contains(layout.getId()) && split.newName.equals(layout.getName())) {
                effect.pending.remove(split);
                effect.leftBehind.add(new Item(Kind.FIELD_CONFIGURATION_COPY, String.valueOf(layout.getId()),
                        layout.getName(), State.UNKNOWN,
                        "found by name after an unconfirmed create; verify and delete manually"));
                return;
            }
        }
        // Nothing found: the unit stays notStarted.
    }

    private enum Restored { CHANGED, ALREADY_ORIGINAL, FOREIGN }

    /**
     * Changes the scheme row back only while it still points at this operation's copy; any other state is someone
     * else's change and is left untouched.
     */
    private Restored restore(Project project, Split split, Mapping mapping) {
        fieldLayoutManager.refresh();
        String typeId = mapping.type.getId();
        FieldLayoutScheme scheme = fieldLayoutManager.getMutableFieldLayoutScheme(mapping.schemeId);
        FieldLayoutSchemeEntity entity = scheme.getEntity(typeId);
        Restored result;
        if (entity == null) {
            result = mapping.hadEntity ? Restored.FOREIGN : Restored.ALREADY_ORIGINAL;
        } else if (split.createdId.equals(entity.getFieldLayoutId())) {
            if (mapping.hadEntity) {
                entity.setFieldLayoutId(mapping.originalLayoutId);
                fieldLayoutManager.updateFieldLayoutSchemeEntity(entity);
            } else {
                fieldLayoutManager.removeFieldLayoutSchemeEntity(entity);
            }
            result = Restored.CHANGED;
        } else if (mapping.hadEntity && Objects.equals(entity.getFieldLayoutId(), mapping.originalLayoutId)) {
            result = Restored.ALREADY_ORIGINAL;
        } else {
            result = Restored.FOREIGN;
        }
        if (result == Restored.FOREIGN) return result;
        fieldLayoutManager.refresh();
        FieldLayout effective = fieldLayoutManager.getFieldLayout(project, typeId);
        if (effective == null || split.createdId.equals(effective.getId())) {
            throw new IllegalStateException("issue type " + typeId + " still uses the copy after the restore");
        }
        return result;
    }

    private void removeCopy(Split split, Effect effect) {
        Long id = split.createdId;
        try {
            fieldLayoutManager.refresh();
            String user = referencingScheme(id);
            if (user != null) {
                effect.leftBehind.add(copyItem(split, State.LEFT_BEHIND, "Not deleted: still assigned in field "
                        + "configuration scheme '" + user + "'."));
                return;
            }
            EditableFieldLayout layout = fieldLayoutManager.getEditableFieldLayout(id);
            if (layout != null) fieldLayoutManager.deleteFieldLayout(layout);
            fieldLayoutManager.refresh();
            if (layoutIds().contains(id)) {
                effect.leftBehind.add(copyItem(split, State.LEFT_BEHIND, "Jira still lists the copy after the delete."));
            } else {
                effect.compensated.add(copyItem(split, State.COMPENSATED, "Created by this operation and deleted."));
                effect.compensateFlags(split);
            }
        } catch (RuntimeException e) {
            LOG.error("Could not delete field configuration copy {}", id, e);
            effect.leftBehind.add(copyItem(split, State.UNKNOWN,
                    "The delete failed or was not confirmed: " + e.getMessage()));
        }
    }

    private String referencingScheme(Long layoutId) {
        for (FieldLayoutScheme scheme : fieldLayoutManager.getFieldLayoutSchemes()) {
            for (FieldLayoutSchemeEntity entity : scheme.getEntities()) {
                if (layoutId.equals(entity.getFieldLayoutId())) return scheme.getName();
            }
        }
        return null;
    }

    private Set<Long> layoutIds() {
        Set<Long> ids = new HashSet<Long>();
        for (FieldLayout layout : fieldLayoutManager.getEditableFieldLayouts()) ids.add(layout.getId());
        return ids;
    }

    // ---- required flag ----

    /** {@code stored[0]} turns true immediately before the native store call: a failure before it wrote nothing. */
    private void setRequired(EditableFieldLayout editable, OrderableField field, Long layoutId, boolean required,
                             boolean[] stored) {
        FieldLayoutItem item = editable.getFieldLayoutItem(field.getId());
        if (item == null) throw new IllegalStateException("Field disappeared from layout " + layoutId);
        if (item.isRequired() == required) return;
        String name = editable.getName();
        String before = FieldLayoutState.canonicalSnapshot(name, editable);
        String expected = FieldLayoutState.canonicalSnapshotWithRequired(name, editable, field.getId(), required);
        Map<String, Map<String, Object>> expectedImage = image(editable, field.getId(), required);
        if (required) editable.makeRequired(item);
        else editable.makeOptional(item);
        stored[0] = true;
        FieldLayoutState.store(fieldLayoutManager, editable);
        verifyRequired(name, editable, field.getId(), before, expected, expectedImage, required);
    }

    private void verifyRequired(String name, EditableFieldLayout editable, String fieldId, String before, String expected,
                                Map<String, Map<String, Object>> expectedImage, boolean required) {
        fieldLayoutManager.refresh();
        EditableFieldLayout persisted = editable.isDefault() ? fieldLayoutManager.getEditableDefaultFieldLayout()
                : fieldLayoutManager.getEditableFieldLayout(editable.getId());
        FieldLayoutItem item = persisted.getFieldLayoutItem(fieldId);
        if (item == null) throw new IllegalStateException("Field disappeared from persisted layout " + editable.getId());
        String actual = FieldLayoutState.canonicalSnapshot(name, persisted);
        if (!FieldLayoutState.sameState(actual, expected) && !FieldLayoutState.sameState(actual, before)) {
            throw new IllegalStateException("Field configuration changed beyond the requested required flag ("
                    + String.join("; ", differences(expectedImage, image(persisted, null, false))) + ").");
        }
        if (item.isRequired() != required) {
            throw new IllegalStateException("Jira did not persist the requested required flag.");
        }
    }

    /** Layout attributes and every item by field id, so comparison does not depend on enumeration order. */
    private static Map<String, Map<String, Object>> image(FieldLayout layout, String changedField, boolean required) {
        Map<String, Map<String, Object>> image = new TreeMap<String, Map<String, Object>>();
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("id", layout.getId());
        header.put("name", layout.getName());
        header.put("description", layout.getDescription());
        header.put("default", layout.isDefault());
        image.put("(layout)", header);
        for (FieldLayoutItem item : layout.getFieldLayoutItems()) {
            String id = item.getOrderableField().getId();
            Map<String, Object> attributes = new LinkedHashMap<String, Object>();
            attributes.put("required", id.equals(changedField) ? required : item.isRequired());
            attributes.put("hidden", item.isHidden());
            attributes.put("description", item.getRawFieldDescription());
            attributes.put("renderer", item.getRendererType());
            image.put(id, attributes);
        }
        return image;
    }

    /** Field ids and attribute names only: values may be admin-written text. */
    private static List<String> differences(Map<String, Map<String, Object>> expected,
                                            Map<String, Map<String, Object>> actual) {
        Set<String> keys = new TreeSet<String>(expected.keySet());
        keys.addAll(actual.keySet());
        List<String> result = new ArrayList<String>();
        for (String key : keys) {
            Map<String, Object> want = expected.get(key), got = actual.get(key);
            if (got == null) result.add(key + " missing");
            else if (want == null) result.add(key + " unexpected");
            else {
                List<String> attributes = new ArrayList<String>();
                for (Map.Entry<String, Object> entry : want.entrySet()) {
                    if (!Objects.equals(entry.getValue(), got.get(entry.getKey()))) attributes.add(entry.getKey());
                }
                if (!attributes.isEmpty()) result.add(key + " " + String.join(",", attributes));
            }
            if (result.size() > MAX_DIFFERENCES) {
                result.set(MAX_DIFFERENCES, "more differences omitted");
                break;
            }
        }
        return result.isEmpty() ? Collections.singletonList("no per-field difference found") : result;
    }

    // ---- result bookkeeping ----

    private static Item copyItem(Split split, State state, String detail) {
        return new Item(Kind.FIELD_CONFIGURATION_COPY, split.createdId == null ? null : String.valueOf(split.createdId),
                split.newName, state, detail);
    }

    private static String describe(List<Item> items) {
        List<String> parts = new ArrayList<String>();
        for (Item item : items) {
            parts.add(item.getKind() + " '" + item.getName() + "' (id " + item.getId() + ", " + item.getState() + ")");
        }
        return String.join("; ", parts);
    }

    private static String failureMessage(RuntimeException cause, Effect effect) {
        StringBuilder message = new StringBuilder("The change failed after writing started: ").append(cause.getMessage());
        if (message.charAt(message.length() - 1) != '.') message.append('.');
        message.append(" partialEffect lists what persists (applied), what this operation reverted (compensated),"
                + " what it could not revert (leftBehind) and what was not started.");
        if (!effect.leftBehind.isEmpty()) message.append(" Left behind: ").append(describe(effect.leftBehind)).append('.');
        return message.toString();
    }

    private record Flag(Object owner, String fieldId) { }

    private static Item flagItem(Item item, String layoutId, State state, String detail) {
        return new Item(Kind.REQUIRED_FLAG, layoutId, item.getName(), state, detail,
                item.getFieldId(), item.getFieldName());
    }

    private static final class Mapping {
        private final IssueType type;
        private final Long schemeId;
        private final boolean hadEntity;
        private final Long originalLayoutId;

        private Mapping(IssueType type, Long schemeId, boolean hadEntity, Long originalLayoutId) {
            this.type = type; this.schemeId = schemeId; this.hadEntity = hadEntity; this.originalLayoutId = originalLayoutId;
        }

        private Item item(Split split, State state, String detail) {
            return new Item(Kind.ISSUE_TYPE_MAPPING, type.getId(), type.getName(), state,
                    detail != null ? detail : "Maps to field configuration " + split.createdId + ".");
        }
    }

    /** Units are keyed so a unit moves from notStarted to exactly one of applied / compensated / leftBehind. */
    private static final class Effect {
        private final Map<Object, Item> pending = new LinkedHashMap<Object, Item>();
        private final List<Item> applied = new ArrayList<Item>();
        private final List<Item> compensated = new ArrayList<Item>();
        private final List<Item> leftBehind = new ArrayList<Item>();

        private Effect(Plan plan) {
            for (LayoutChange change : plan.changes) {
                for (Wanted wanted : change.fields) pending.put(new Flag(change, wanted.field.getId()),
                        new Item(Kind.REQUIRED_FLAG, String.valueOf(change.layout.getId()), change.layout.getName(),
                                State.NOT_STARTED, null, wanted.field.getId(), wanted.field.getName()));
            }
            for (Split split : plan.splits) {
                List<String> types = new ArrayList<String>();
                for (IssueType type : split.issueTypes) types.add(type.getName());
                pending.put(split, new Item(Kind.FIELD_CONFIGURATION_COPY, null, split.newName, State.NOT_STARTED,
                        "Copy of '" + split.source.getName() + "' for issue types: " + String.join(", ", types) + "."));
                for (Wanted wanted : split.fields) pending.put(new Flag(split, wanted.field.getId()),
                        new Item(Kind.REQUIRED_FLAG, null, split.newName, State.NOT_STARTED, null,
                                wanted.field.getId(), wanted.field.getName()));
            }
        }

        private void compensateFlags(Split split) {
            String id = String.valueOf(split.createdId);
            for (List<Item> items : java.util.Arrays.asList(applied, leftBehind)) {
                java.util.Iterator<Item> iterator = items.iterator();
                while (iterator.hasNext()) {
                    Item item = iterator.next();
                    if (item.getKind() == Kind.REQUIRED_FLAG && id.equals(item.getId())) {
                        compensated.add(flagItem(item, id, State.COMPENSATED, "The operation's copy was deleted."));
                        iterator.remove();
                    }
                }
            }
        }

        private PartialEffect build() {
            return new PartialEffect(applied, compensated, leftBehind, new ArrayList<Item>(pending.values()));
        }
    }
}
