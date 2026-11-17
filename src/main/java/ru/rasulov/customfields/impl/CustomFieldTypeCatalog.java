package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldSearcher;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.customfields.api.dto.CustomFieldTypeResponse;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** The reference of installed custom field types for the caller (LLM): compact, sorted by key. */
@Named
public class CustomFieldTypeCatalog {

    static final int MAX_DESCRIPTION_LENGTH = 200;

    private final CustomFieldManager customFieldManager;

    @Inject
    public CustomFieldTypeCatalog(@ComponentImport CustomFieldManager customFieldManager) {
        this.customFieldManager = customFieldManager;
    }

    List<CustomFieldTypeResponse> list() {
        List<CustomFieldTypeResponse> types = new ArrayList<CustomFieldTypeResponse>();
        for (CustomFieldType<?, ?> type : customFieldManager.getCustomFieldTypes()) {
            List<String> searcherKeys = new ArrayList<String>();
            for (CustomFieldSearcher searcher : customFieldManager.getCustomFieldSearchers(type)) {
                searcherKeys.add(searcher.getDescriptor().getCompleteKey());
            }
            types.add(new CustomFieldTypeResponse(type.getKey(), type.getName(), truncate(type.getDescription()),
                    type instanceof MultipleSettableCustomFieldType, searcherKeys));
        }
        Collections.sort(types, new Comparator<CustomFieldTypeResponse>() {
            @Override
            public int compare(CustomFieldTypeResponse a, CustomFieldTypeResponse b) {
                return a.getKey().compareTo(b.getKey());
            }
        });
        return types;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_DESCRIPTION_LENGTH ? text : text.substring(0, MAX_DESCRIPTION_LENGTH);
    }
}
