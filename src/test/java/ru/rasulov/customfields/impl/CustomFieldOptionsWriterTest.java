package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import ru.rasulov.common.api.dto.field.OptionDefinition;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
public class CustomFieldOptionsWriterTest {
    @org.junit.After
    public void closeUndoDatabases() { JiraMocks.closeJournals(); }

    private OptionsManager optionsManager;
    private FieldConfig config;
    private CustomFieldOptionsWriter writer;

    @Before
    public void setUp() {
        optionsManager = mock(OptionsManager.class);
        com.atlassian.jira.issue.fields.CustomField field = JiraMocks.createdField("customfield_10500");
        config = JiraMocks.withScheme(field);
        com.atlassian.jira.issue.CustomFieldManager manager = mock(com.atlassian.jira.issue.CustomFieldManager.class);
        when(manager.getCustomFieldObject(field.getId())).thenReturn(field);
        ru.rasulov.customfields.impl.undo.CustomFieldUndoHandler undo = FieldJournalTestSupport.fields(manager, optionsManager,
                mock(com.atlassian.jira.issue.fields.config.manager.FieldConfigSchemeManager.class),
                mock(ru.rasulov.customfields.impl.context.ContextSchemeFactory.class));
        ru.rasulov.common.impl.undo.ChangeJournal journal = FieldJournalTestSupport.journal("admin", undo);
        writer = new CustomFieldOptionsWriter(optionsManager,
                new ru.rasulov.customfields.impl.undo.FieldMutationJournal(journal, undo)) {
            @Override public void write(FieldConfig target, java.util.List<OptionDefinition> options) {
                journal.execute("options-test", "admin", () -> { super.write(target, options); return null; });
            }
        };
    }

    @Test
    public void rootOptionsGetIncreasingSequenceAndNoParent() {
        writer.write(config, Arrays.asList(new OptionDefinition("Low", null), new OptionDefinition("High", null)));

        InOrder order = inOrder(optionsManager);
        order.verify(optionsManager).createOption(eq(config), (Long) isNull(), eq(0L), eq("Low"));
        order.verify(optionsManager).createOption(eq(config), (Long) isNull(), eq(1L), eq("High"));
    }

    @Test
    public void childSequenceRestartsForEveryParent() {
        Option eu = mock(Option.class);
        Option us = mock(Option.class);
        when(eu.getOptionId()).thenReturn(7L);
        when(us.getOptionId()).thenReturn(8L);
        when(optionsManager.createOption(eq(config), (Long) isNull(), eq(0L), eq("EU"))).thenReturn(eu);
        when(optionsManager.createOption(eq(config), (Long) isNull(), eq(1L), eq("US"))).thenReturn(us);

        writer.write(config, Arrays.asList(
                new OptionDefinition("EU", Arrays.asList(new OptionDefinition("Berlin", null), new OptionDefinition("Paris", null))),
                new OptionDefinition("US", Collections.singletonList(new OptionDefinition("Boston", null)))));

        verify(optionsManager).createOption(config, 7L, 0L, "Berlin");
        verify(optionsManager).createOption(config, 7L, 1L, "Paris");
        verify(optionsManager).createOption(config, 8L, 0L, "Boston");
    }

    @Test
    public void emptyListWritesNothing() {
        writer.write(config, Collections.<OptionDefinition>emptyList());

        verifyNoInteractions(optionsManager);
    }
}
