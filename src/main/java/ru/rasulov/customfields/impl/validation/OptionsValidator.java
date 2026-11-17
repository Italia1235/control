package ru.rasulov.customfields.impl.validation;

import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ru.rasulov.common.impl.Normalizer.normalize;

/**
 * Rules for one option list (a field's, or one context's own): only types that take options, {@link Limits} on the
 * counts, non-blank unique values, children only for cascading select and only one level. Writes nothing to Jira.
 */
@Named
public class OptionsValidator {

    private final TypeSuggester suggester;

    @Inject
    public OptionsValidator(TypeSuggester suggester) {
        this.suggester = suggester;
    }

    /**
     * Number of options the definitions would write, top-level and children together, without validating them. A
     * list shared by several contexts is written once per context and counted that many times. Used for the
     * call-wide {@link Limits#MAX_OPTIONS_PER_CALL}.
     */
    public static int countOptions(List<FieldDefinition> definitions) {
        int count = 0;
        for (FieldDefinition definition : definitions) {
            if (definition == null) {
                continue;
            }
            int shared = withChildren(definition.getOptions());
            if (definition.getContexts().isEmpty()) {
                count += shared;
            }
            for (ContextDefinition context : definition.getContexts()) {
                count += context == null || !context.hasOptions() ? shared : withChildren(context.getOptions());
            }
        }
        return count;
    }

    /** Top-level options plus their children. */
    private static int withChildren(Iterable<OptionDefinition> roots) {
        int count = 0;
        for (OptionDefinition root : roots) {
            count++;
            if (root != null) {
                count += root.getChildren().size();
            }
        }
        return count;
    }

    /**
     * @return trimmed copies in request order; empty if there are none
     * @throws FieldFailureException the options are not acceptable for this type
     */
    public List<OptionDefinition> validate(CustomFieldType<?, ?> type, List<OptionDefinition> options) {
        if (options.isEmpty()) {
            return Collections.emptyList();
        }
        if (!(type instanceof MultipleSettableCustomFieldType)) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    "Type '" + type.getKey() + "' does not support options. Remove 'options' or use a type with "
                            + "supportsOptions=true (see suggestions).",
                    suggester.typesWithOptions());
        }
        checkCounts(options);
        boolean cascading = TypeKeys.CASCADING_SELECT.equals(type.getKey());
        List<OptionDefinition> checked = new ArrayList<OptionDefinition>(options.size());
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < options.size(); i++) {
            OptionDefinition option = options.get(i);
            String value = validateValue(option, "options[" + i + "]");
            if (!seen.add(normalize(value))) {
                throw duplicate(value, "");
            }
            checked.add(new OptionDefinition(value, validateChildren(option, value, cascading)));
        }
        return checked;
    }

    private static void checkCounts(List<OptionDefinition> options) {
        if (options.size() > Limits.MAX_ROOT_OPTIONS) {
            throw tooMany("The option list has " + options.size() + " top-level options; the maximum is "
                    + Limits.MAX_ROOT_OPTIONS + ".", "split it into several fields");
        }
        int total = withChildren(options);
        if (total > Limits.MAX_OPTIONS_PER_CONTEXT) {
            throw tooMany("The option list has " + total + " options including children; the maximum is "
                    + Limits.MAX_OPTIONS_PER_CONTEXT + ".", "split it into several fields");
        }
    }

    private static List<OptionDefinition> validateChildren(OptionDefinition option, String parentValue,
                                                           boolean cascading) {
        if (option.getChildren().isEmpty()) {
            return Collections.emptyList();
        }
        if (!cascading) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    "Option '" + parentValue + "' has 'children', but only " + TypeKeys.CASCADING_SELECT
                            + " supports them. Remove 'children' or use that type.",
                    Collections.singletonList(TypeKeys.CASCADING_SELECT));
        }
        if (option.getChildren().size() > Limits.MAX_CHILD_OPTIONS_PER_PARENT) {
            throw tooMany("Option '" + parentValue + "' has " + option.getChildren().size()
                    + " children; the maximum is " + Limits.MAX_CHILD_OPTIONS_PER_PARENT + " per option.",
                    "split it");
        }
        List<OptionDefinition> checked = new ArrayList<OptionDefinition>(option.getChildren().size());
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < option.getChildren().size(); i++) {
            OptionDefinition child = option.getChildren().get(i);
            String value = validateValue(child, "the child #" + i + " of option '" + parentValue + "'");
            if (!seen.add(normalize(value))) {
                throw duplicate(value, " under '" + parentValue + "'");
            }
            if (!child.getChildren().isEmpty()) {
                throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                        "Option '" + value + "' under '" + parentValue + "' has 'children' of its own; cascading "
                                + "select has only two levels. Flatten the options.");
            }
            checked.add(new OptionDefinition(value, null));
        }
        return checked;
    }

    private static String validateValue(OptionDefinition option, String where) {
        String value = option == null || option.getValue() == null ? "" : option.getValue().trim();
        if (value.isEmpty()) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    "Option " + where + " needs a non-blank 'value'.");
        }
        if (value.length() > Limits.MAX_OPTION_LENGTH) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    "Option '" + value.substring(0, 40) + "...' is " + value.length()
                            + " characters long; the maximum is " + Limits.MAX_OPTION_LENGTH + ".");
        }
        return value;
    }

    private static FieldFailureException duplicate(String value, String where) {
        return new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                "Option '" + value + "' is listed more than once" + where
                        + " (values are compared ignoring case and surrounding spaces). Keep one.");
    }

    private static FieldFailureException tooMany(String problem, String remedy) {
        return new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                problem + " Ask the user to shorten the list or " + remedy + ".");
    }
}
