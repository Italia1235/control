package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import org.ofbiz.core.entity.GenericValue;

import javax.inject.Named;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

/** Isolates Jira's implementation class used by its own CopyFieldLayout action. */
@Named
public class FieldLayoutCopyFactory {
    private final Constructor<?> constructor;

    public FieldLayoutCopyFactory() {
        try {
            constructor = Class.forName("com.atlassian.jira.issue.fields.layout.field.EditableFieldLayoutImpl")
                    .getConstructor(GenericValue.class, List.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Jira's editable field layout implementation is unavailable.", e);
        }
    }

    public EditableFieldLayout copyOf(FieldLayout source, String name) {
        try {
            EditableFieldLayout copy = (EditableFieldLayout) constructor.newInstance(null, source.getFieldLayoutItems());
            copy.setName(name);
            copy.setDescription(source.getDescription());
            return copy;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Jira could not create a field configuration copy.", e);
        }
    }
}
