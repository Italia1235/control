package ru.rasulov.customfields.impl.validation;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldSearcher;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Computes the {@code suggestions} of validation errors: valid values the caller may use instead. */
@Named
public class TypeSuggester {

    public static final int MAX_SUGGESTIONS = 5;
    private static final int MIN_COMMON_PREFIX = 4;

    private final CustomFieldManager customFieldManager;

    @Inject
    public TypeSuggester(@ComponentImport CustomFieldManager customFieldManager) {
        this.customFieldManager = customFieldManager;
    }

    /**
     * Up to {@value #MAX_SUGGESTIONS} installed type keys that resemble what the caller typed, sorted. "Resemble":
     * the input is part of the module key or the type name, or the other way round, or they share a prefix of at
     * least four characters ({@code textfeld} suggests {@code textarea} and {@code textfield}).
     */
    public List<String> similarTypes(String typed) {
        String needle = typed.substring(typed.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
        List<String> keys = new ArrayList<String>();
        for (CustomFieldType<?, ?> candidate : customFieldManager.getCustomFieldTypes()) {
            if (resembles(needle, candidate)) {
                keys.add(candidate.getKey());
            }
        }
        return firstSorted(keys);
    }

    /** Up to {@value #MAX_SUGGESTIONS} installed keys of types that take options, sorted. */
    public List<String> typesWithOptions() {
        List<String> keys = new ArrayList<String>();
        for (CustomFieldType<?, ?> candidate : customFieldManager.getCustomFieldTypes()) {
            if (candidate instanceof MultipleSettableCustomFieldType) {
                keys.add(candidate.getKey());
            }
        }
        return firstSorted(keys);
    }

    /** Keys of all searchers that fit the type. */
    public List<String> compatibleSearchers(CustomFieldType<?, ?> type) {
        List<String> keys = new ArrayList<String>();
        for (CustomFieldSearcher searcher : customFieldManager.getCustomFieldSearchers(type)) {
            keys.add(searcher.getDescriptor().getCompleteKey());
        }
        return keys;
    }

    private static List<String> firstSorted(List<String> keys) {
        Collections.sort(keys);
        return keys.size() > MAX_SUGGESTIONS ? new ArrayList<String>(keys.subList(0, MAX_SUGGESTIONS)) : keys;
    }

    private static boolean resembles(String needle, CustomFieldType<?, ?> candidate) {
        if (needle.isEmpty()) {
            return false;
        }
        String key = candidate.getKey();
        String module = key.substring(key.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
        String name = candidate.getName() == null ? "" : candidate.getName().toLowerCase(Locale.ROOT);
        return module.contains(needle) || name.contains(needle) || needle.contains(module)
                || commonPrefixLength(needle, module) >= MIN_COMMON_PREFIX;
    }

    private static int commonPrefixLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }
}
