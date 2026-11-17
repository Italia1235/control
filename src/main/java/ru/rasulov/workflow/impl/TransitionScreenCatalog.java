package ru.rasulov.workflow.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.workflow.api.dto.WorkflowErrorCode;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static ru.rasulov.common.impl.Normalizer.normalize;

/** Resolves the exact existing Jira screen that a workflow transition opens. */
@Named
public class TransitionScreenCatalog {
    private final FieldScreenManager screenManager;

    @Inject
    public TransitionScreenCatalog(@ComponentImport FieldScreenManager screenManager) {
        this.screenManager = screenManager;
    }

    public FieldScreen resolve(String reference, String where) {
        String wanted = reference == null ? "" : reference.trim();
        if (wanted.isEmpty()) return null;

        try {
            FieldScreen byId = screenManager.getFieldScreen(Long.valueOf(wanted));
            if (byId != null) return byId;
        } catch (NumberFormatException ignored) {
            // A non-numeric reference is a screen name.
        }

        List<FieldScreen> matches = new ArrayList<FieldScreen>();
        for (FieldScreen screen : screenManager.getFieldScreens()) {
            if (normalize(screen.getName()).equals(normalize(wanted))) matches.add(screen);
        }
        matches.sort(Comparator.comparing(FieldScreen::getId));
        if (matches.size() == 1) return matches.get(0);
        if (matches.size() > 1) {
            List<String> suggestions = new ArrayList<String>(matches.size());
            for (FieldScreen screen : matches) suggestions.add(screen.getId() + " (" + screen.getName() + ")");
            throw new WorkflowFailureException(WorkflowErrorCode.AMBIGUOUS_SCREEN,
                    where + " names several Jira screens. Use one exact screen id.", suggestions);
        }
        throw new WorkflowFailureException(WorkflowErrorCode.UNKNOWN_SCREEN,
                "No Jira screen has the id or exact name '" + wanted + "'. Create it first with create_screen, or use "
                        + "an existing screen's exact id or name.", Collections.<String>emptyList());
    }
}
