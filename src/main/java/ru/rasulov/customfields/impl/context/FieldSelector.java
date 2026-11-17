package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.Immutables;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Exact-name/id lookup shared by readers and field mutations; no field catalog scan. */
@Named
public class FieldSelector {
    public enum ErrorCode { VALIDATION_ERROR, UNKNOWN_FIELD, AMBIGUOUS_FIELD }
    private final CustomFieldManager fields;

    @Inject
    public FieldSelector(@ComponentImport CustomFieldManager fields) {
        this.fields = fields;
    }

    public CustomField resolve(String nameOrId) {
        fields.refresh();
        String text = nameOrId == null ? "" : nameOrId.trim();
        if (text.isEmpty()) throw new Failure(ErrorCode.VALIDATION_ERROR,
                "A custom field must be given by its exact name or id, not blank.", null);
        if (digits(text) || text.startsWith("customfield_")) {
            String id = digits(text) ? "customfield_" + text : text;
            CustomField field = fields.getCustomFieldObject(id);
            if (field == null) throw new Failure(ErrorCode.UNKNOWN_FIELD,
                    "No custom field has the id '" + text + "'.", null);
            return field;
        }
        Collection<CustomField> matches = fields.getCustomFieldObjectsByNameIgnoreCase(text);
        List<CustomField> sorted = new ArrayList<CustomField>(matches == null
                ? Collections.<CustomField>emptyList() : matches);
        sorted.sort(Comparator.comparing(CustomField::getId));
        if (sorted.size() == 1) return sorted.get(0);
        if (sorted.size() > 1) throw new Failure(ErrorCode.AMBIGUOUS_FIELD,
                "Several custom fields are named '" + text + "'. Show every candidate and ask the user to choose its id.", sorted);
        throw new Failure(ErrorCode.UNKNOWN_FIELD,
                "No custom field has the exact name or id '" + text + "'.", null);
    }

    private static boolean digits(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) if (!Character.isDigit(text.charAt(i))) return false;
        return true;
    }

    public static final class Failure extends RuntimeException {
        private final ErrorCode code;
        private final List<CustomField> candidates;
        private Failure(ErrorCode code, String message, List<CustomField> candidates) {
            super(message, null, false, false);
            this.code = code;
            this.candidates = Immutables.listOf(candidates);
        }
        public ErrorCode getCode() { return code; }
        public List<CustomField> getCandidates() { return candidates; }
    }
}
