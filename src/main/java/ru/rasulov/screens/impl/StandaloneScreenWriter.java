package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenFactory;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;

/** Writes a standalone screen and journals every persisted child independently. */
@Named
public class StandaloneScreenWriter {
    public static final String TAB_NAME = "Field Tab";
    private final FieldScreenFactory factory;
    private final FieldScreenManager manager;
    private final ScreenJournal journal;

    @Inject
    public StandaloneScreenWriter(@ComponentImport FieldScreenFactory factory,
                                  @ComponentImport FieldScreenManager manager, ScreenJournal journal) {
        this.factory = factory;
        this.manager = manager;
        this.journal = journal;
    }
    void reserve(String name, String description, int fieldCount) {
        long size = 512L + 6L * name.length() + (description == null ? 0L : 6L * description.length())
                + 200L * fieldCount;
        journal.reserve(ScreenJournal.budget(size, 2L + fieldCount));
    }

    public FieldScreen write(String name, String description, List<String> fieldIds) {
        FieldScreen screen = factory.createScreen();
        screen.setName(name);
        screen.setDescription(description);
        journal.create(screen, () -> manager.createFieldScreen(screen));
        final FieldScreenTab[] tab = new FieldScreenTab[1];
        journal.change(screen, () -> tab[0] = screen.addTab(TAB_NAME));
        for (String fieldId : fieldIds) {
            journal.change(screen, () -> tab[0].addFieldScreenLayoutItem(fieldId));
        }
        return screen;
    }
}
