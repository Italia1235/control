package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.customfields.impl.context.FieldSelector;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves a field a field configuration can hold: a custom field by exact name or id, or a system field by its exact
 * Jira id (priority, duedate, ...). A system id that is also a custom field's name is ambiguous, never guessed.
 */
@Named
public class ConfigurableFieldResolver {
    public enum ErrorCode { VALIDATION_ERROR, UNKNOWN_FIELD, AMBIGUOUS_FIELD }

    private final FieldSelector customFields;
    private final FieldManager fieldManager;

    @Inject
    public ConfigurableFieldResolver(FieldSelector customFields, @ComponentImport FieldManager fieldManager) {
        this.customFields = customFields;
        this.fieldManager = fieldManager;
    }

    public OrderableField resolve(String nameOrId) {
        String text = nameOrId == null ? "" : nameOrId.trim();
        OrderableField system = systemField(text);
        CustomField custom;
        try {
            custom = customFields.resolve(nameOrId);
        } catch (FieldSelector.Failure e) {
            if (system != null && e.getCode() == FieldSelector.ErrorCode.UNKNOWN_FIELD) return system;
            if (system != null) throw ambiguous(text, system, e.getCandidates());
            if (e.getCode() == FieldSelector.ErrorCode.UNKNOWN_FIELD) {
                throw new Failure(ErrorCode.UNKNOWN_FIELD, "No custom field has the exact name or id '" + text
                        + "', and it is not a system field id.", null);
            }
            throw new Failure(ErrorCode.valueOf(e.getCode().name()), e.getMessage(),
                    new ArrayList<OrderableField>(e.getCandidates()));
        }
        if (system != null) throw ambiguous(text, system, Collections.singletonList(custom));
        return custom;
    }

    /** Jira requires these everywhere (summary, issuetype, ...); they can never become optional. */
    public boolean isMandatory(OrderableField field) {
        return fieldManager.isMandatoryField(field);
    }

    private OrderableField systemField(String id) {
        if (id.isEmpty() || id.startsWith("customfield_") || fieldManager.isCustomFieldId(id)
                || !fieldManager.isOrderableField(id)) return null;
        return fieldManager.getOrderableField(id);
    }

    private static Failure ambiguous(String text, OrderableField system, List<CustomField> custom) {
        List<OrderableField> candidates = new ArrayList<OrderableField>();
        candidates.add(system);
        candidates.addAll(custom);
        return new Failure(ErrorCode.AMBIGUOUS_FIELD, "'" + text + "' is both a system field id and the name of a "
                + "custom field. Show every candidate and ask the user to choose its id.", candidates);
    }

    public static final class Failure extends RuntimeException {
        private final ErrorCode code;
        private final List<OrderableField> candidates;

        private Failure(ErrorCode code, String message, List<OrderableField> candidates) {
            super(message, null, false, false);
            this.code = code;
            this.candidates = Immutables.listOf(candidates);
        }

        public ErrorCode getCode() { return code; }
        public List<OrderableField> getCandidates() { return candidates; }
    }
}
