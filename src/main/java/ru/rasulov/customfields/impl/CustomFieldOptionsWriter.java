package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.field.OptionDefinition;

import ru.rasulov.customfields.impl.undo.FieldMutationJournal;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;

/** Writes the options of one context into the field configuration of that context. */
@Named
public class CustomFieldOptionsWriter {

    private final OptionsManager optionsManager;
    private final FieldMutationJournal mutations;

    @Inject
    public CustomFieldOptionsWriter(@ComponentImport OptionsManager optionsManager, FieldMutationJournal mutations) {
        this.optionsManager = optionsManager;
        this.mutations = mutations;
    }

    /**
     * Creates the options in request order; children get the id of their parent. A no-op for an empty list.
     *
     * @throws RuntimeException when Jira rejects an option; earlier options stay, the caller must roll the field back
     */
    public void write(FieldConfig config, List<OptionDefinition> options) {
        long sequence = 0;
        for (OptionDefinition option : options) {
            final long parentSequence = sequence++;
            Option parent = mutations.write(config.getCustomField(), ru.rasulov.customfields.impl.undo.FieldUndoJson.map(
                    "type", "option", "config", config.getId(), "parent", null, "sequence", parentSequence, "value", option.getValue()),
                    () -> optionsManager.createOption(config, null, parentSequence, option.getValue()));
            long childSequence = 0;
            for (OptionDefinition child : option.getChildren()) {
                final long nextChildSequence = childSequence++;
                mutations.write(config.getCustomField(), ru.rasulov.customfields.impl.undo.FieldUndoJson.map(
                        "type", "option", "config", config.getId(), "parent", parent.getOptionId(),
                        "sequence", nextChildSequence, "value", child.getValue()), () -> optionsManager.createOption(config,
                        parent.getOptionId(), nextChildSequence, child.getValue()));
            }
        }
    }
}
