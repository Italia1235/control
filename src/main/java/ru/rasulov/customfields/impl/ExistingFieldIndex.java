package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ru.rasulov.common.impl.Normalizer.normalize;

/**
 * Existing custom fields loaded only for names requested by the call. Jira installations can have thousands of
 * custom fields, so building an index from {@code getCustomFieldObjects()} would make every small request O(all fields).
 */
final class ExistingFieldIndex {

    private final CustomFieldManager manager;
    private final Map<String, List<CustomField>> byName = new HashMap<String, List<CustomField>>();

    ExistingFieldIndex(CustomFieldManager manager) {
        this.manager = manager;
    }

    /** Fields with this name (trimmed, case-insensitive); each distinct name is queried at most once. */
    List<CustomField> find(String name) {
        String key = normalize(name);
        List<CustomField> cached = byName.get(key);
        if (cached != null) return cached;

        Collection<CustomField> matches = manager.getCustomFieldObjectsByNameIgnoreCase(name.trim());
        List<CustomField> found = matches == null
                ? new ArrayList<CustomField>() : new ArrayList<CustomField>(matches);
        found.sort(Comparator.comparing(CustomField::getId));
        List<CustomField> result = found.isEmpty()
                ? Collections.<CustomField>emptyList() : Collections.unmodifiableList(found);
        byName.put(key, result);
        return result;
    }
}
