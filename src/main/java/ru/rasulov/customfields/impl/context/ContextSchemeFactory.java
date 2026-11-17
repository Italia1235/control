package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.fields.config.FieldConfigScheme;

import javax.inject.Named;

/**
 * Builds {@link FieldConfigScheme} objects (a Jira "context"). Isolated because {@code FieldConfigScheme.Builder}
 * needs Jira's implementation class at runtime, which unit tests do not have: they replace this class by a mock.
 * Contexts of {@code create_custom_fields} have no description; {@code create_field_context} sets one.
 */
@Named
public class ContextSchemeFactory {

    /** A new context for the field, not yet stored. */
    public FieldConfigScheme create(String fieldId, String name) {
        return create(fieldId, name, "");
    }

    /** A new context with the requested description, not yet stored. */
    public FieldConfigScheme create(String fieldId, String name, String description) {
        return new FieldConfigScheme.Builder().setName(name).setDescription(description).setFieldId(fieldId)
                .toFieldConfigScheme();
    }

    /** The same context with the requested name and description. */
    public FieldConfigScheme renamed(FieldConfigScheme scheme, String name, String description) {
        return new FieldConfigScheme.Builder(scheme).setName(name).setDescription(description).toFieldConfigScheme();
    }
}
