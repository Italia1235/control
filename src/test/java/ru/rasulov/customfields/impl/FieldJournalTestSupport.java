package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.fields.config.manager.FieldConfigSchemeManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import ru.rasulov.common.impl.undo.*;
import ru.rasulov.customfields.impl.context.ContextSchemeFactory;
import ru.rasulov.customfields.impl.undo.*;
import java.util.*;
import static org.mockito.Mockito.mock;

/** Owns real AO databases used by field-service unit fixtures; each test closes its own thread's databases. */
public final class FieldJournalTestSupport {
    private static final ThreadLocal<List<UndoTestFixture>> OPEN = ThreadLocal.withInitial(ArrayList::new);
    private FieldJournalTestSupport() { }
    public static ChangeJournal journal(String actor, UndoHandler... handlers) {
        UndoTestFixture fixture = UndoTestFixture.create(actor, handlers);
        OPEN.get().add(fixture);
        return fixture.journal;
    }
    public static void close() {
        List<UndoTestFixture> fixtures = OPEN.get();
        OPEN.remove();
        RuntimeException failure = null;
        for (UndoTestFixture fixture : fixtures) {
            try { fixture.close(); }
            catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
    public static CustomFieldUndoHandler fields(CustomFieldManager manager, OptionsManager options,
            FieldConfigSchemeManager schemes, ContextSchemeFactory factory) {
        return new CustomFieldUndoHandler(manager, schemes, options, mock(FieldScreenManager.class),
                mock(FieldLayoutManager.class), mock(OfBizDelegator.class), factory);
    }
}
