package ru.rasulov.customfields.impl.undo;

import com.atlassian.jira.issue.fields.CustomField;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.UndoStep;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.function.Supplier;

@Named
public class FieldMutationJournal {
    private final ChangeJournal journal;
    private final CustomFieldUndoHandler undo;
    @Inject
    public FieldMutationJournal(ChangeJournal journal, CustomFieldUndoHandler undo) {
        this.journal = journal; this.undo = undo;
    }
    public <T> T write(CustomField field, java.util.Map<String, Object> action, Supplier<T> write) {
        String name = field.getName();
        UndoStep step = journal.prepare("cf.field", "cf.field:" + name,
                undo.withAction(undo.persistedSnapshot(name, field.getId()), action), null, 4096);
        T result = write.get();
        java.util.Map<String, Object> applied = new java.util.LinkedHashMap<String, Object>(action);
        if (result instanceof com.atlassian.jira.issue.customfields.option.Option)
            applied.put("id", ((com.atlassian.jira.issue.customfields.option.Option) result).getOptionId());
        if (result instanceof com.atlassian.jira.issue.fields.config.FieldConfigScheme)
            applied.put("id", ((com.atlassian.jira.issue.fields.config.FieldConfigScheme) result).getId());
        journal.applied(step, undo.withAction(undo.persistedSnapshot(name, field.getId()), applied));
        journal.checkpoint();
        return result;
    }
}
