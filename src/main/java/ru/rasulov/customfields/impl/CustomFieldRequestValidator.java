package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldSearcher;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;
import ru.rasulov.customfields.impl.context.ContextValidator;
import ru.rasulov.customfields.impl.context.ResolvedContext;
import ru.rasulov.customfields.impl.validation.FieldFailureException;
import ru.rasulov.customfields.impl.validation.OptionsValidator;
import ru.rasulov.customfields.impl.validation.TypeKeys;
import ru.rasulov.customfields.impl.validation.TypeSuggester;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks one {@link FieldDefinition} and resolves its Jira objects: name, type, contexts, options, searcher. The
 * rules live in {@code validation} and {@code context}; this class only puts them in order. Writes nothing to Jira,
 * so the same code serves real creation and dry runs.
 */
@Named
public class CustomFieldRequestValidator {

    private final CustomFieldManager customFieldManager;
    private final TypeSuggester suggester;
    private final OptionsValidator optionsValidator;
    private final ContextValidator contextValidator;

    @Inject
    public CustomFieldRequestValidator(@ComponentImport CustomFieldManager customFieldManager,
                                       TypeSuggester suggester, OptionsValidator optionsValidator,
                                       ContextValidator contextValidator) {
        this.customFieldManager = customFieldManager;
        this.suggester = suggester;
        this.optionsValidator = optionsValidator;
        this.contextValidator = contextValidator;
    }

    /**
     * @throws FieldFailureException the definition is not acceptable; carries the error code and suggestions
     */
    public ResolvedFieldDefinition validate(FieldDefinition definition) {
        if (definition == null) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    "Field definition must be an object with 'name', 'typeKey' and 'contexts'.");
        }
        String name = validateName(definition.getName());
        CustomFieldType<?, ?> type = resolveType(definition.getTypeKey());
        List<ResolvedContext> contexts = withOptions(type, definition,
                contextValidator.validate(definition.getContexts()));
        String searcherKey = definition.getSearcherKey() == null ? "" : definition.getSearcherKey().trim();
        CustomFieldSearcher searcher = resolveSearcher(type, searcherKey);
        String description = definition.getDescription() == null ? "" : definition.getDescription();
        return new ResolvedFieldDefinition(name, description, type, searcher, !searcherKey.isEmpty(), contexts);
    }

    private static String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    "Field 'name' is required and must not be blank.");
        }
        if (name.length() > Limits.MAX_NAME_LENGTH) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    "Field name is " + name.length() + " characters long; the maximum is "
                            + Limits.MAX_NAME_LENGTH + ".");
        }
        return name;
    }

    private CustomFieldType<?, ?> resolveType(String rawTypeKey) {
        String typeKey = rawTypeKey == null ? "" : rawTypeKey.trim();
        if (typeKey.isEmpty()) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    "Field 'typeKey' is required. Call list_custom_field_types to find a valid key.");
        }
        String fullKey = typeKey.indexOf(':') >= 0 ? typeKey : TypeKeys.SYSTEM_PLUGIN + ':' + typeKey;
        CustomFieldType<?, ?> type = customFieldManager.getCustomFieldType(fullKey);
        if (type == null) {
            throw new FieldFailureException(CustomFieldErrorCode.UNKNOWN_TYPE,
                    "Type '" + fullKey + "' is not installed. Use one of suggestions or call list_custom_field_types.",
                    suggester.similarTypes(typeKey));
        }
        return type;
    }

    /**
     * Only contexts with omitted options inherit the field's options; an explicit empty list stays empty.
     * Options the field lists but no context would use are refused instead of silently dropped.
     */
    private List<ResolvedContext> withOptions(CustomFieldType<?, ?> type, FieldDefinition definition,
                                              List<ResolvedContext> contexts) {
        boolean sharedUsed = false;
        for (ContextDefinition context : definition.getContexts()) {
            sharedUsed |= !context.hasOptions();
        }
        List<OptionDefinition> shared = definition.getOptions();
        if (!shared.isEmpty() && !sharedUsed) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    "The field has 'options', but every context explicitly overrides them (including options:[]). "
                            + "Remove the field's 'options' or omit 'options' in a context that should inherit them.");
        }
        List<OptionDefinition> checkedShared = sharedUsed ? optionsValidator.validate(type, shared) : shared;

        List<ResolvedContext> result = new ArrayList<ResolvedContext>(contexts.size());
        for (int i = 0; i < contexts.size(); i++) {
            ContextDefinition requested = definition.getContexts().get(i);
            result.add(contexts.get(i).withOptions(requested.hasOptions()
                    ? optionsValidator.validate(type, requested.getOptions()) : checkedShared));
        }
        return result;
    }

    private CustomFieldSearcher resolveSearcher(CustomFieldType<?, ?> type, String searcherKey) {
        if (searcherKey.isEmpty()) {
            return customFieldManager.getDefaultSearcher(type);
        }
        CustomFieldSearcher searcher = customFieldManager.getCustomFieldSearcher(searcherKey);
        if (searcher == null || !isCompatible(searcher, type)) {
            String problem = searcher == null ? "is not installed" : "is not compatible with type '" + type.getKey() + "'";
            throw new FieldFailureException(CustomFieldErrorCode.INCOMPATIBLE_SEARCHER,
                    "Searcher '" + searcherKey + "' " + problem
                            + ". Use one of suggestions or omit searcherKey to use the default.",
                    suggester.compatibleSearchers(type));
        }
        return searcher;
    }

    private static boolean isCompatible(CustomFieldSearcher searcher, CustomFieldType<?, ?> type) {
        return searcher.getDescriptor().getValidCustomFieldKeys().contains(type.getKey());
    }
}
