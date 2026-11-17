package ru.rasulov.projects.impl;

import com.atlassian.jira.user.ApplicationUser;
import ru.rasulov.common.impl.undo.UndoHandler;
import ru.rasulov.common.impl.undo.UndoStep;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

@Named
public class CreatedProjectUndoHandler implements UndoHandler {
    private final ProjectUndoSupport support;
    @Inject public CreatedProjectUndoHandler(ProjectUndoSupport support) { this.support = support; }
    public String kind() { return "project.created"; }
    public List<String> lockNames(UndoStep step) {
        return Arrays.asList(ProjectCreatorImpl.LOCK_NAME, "ru.rasulov.control.set-project-issue-types",
                "ru.rasulov.control.assign-workflow", "ru.rasulov.control.create-workflow",
                "ru.rasulov.control.screen-scheme-clone", "ru.rasulov.control.create-standalone-screen",
                "ru.rasulov.control.edit-screens", "ru.rasulov.control.create-custom-fields",
                "ru.rasulov.control.create-issue-type");
    }
    public String conflict(UndoStep step) { return support.conflict(support.read(step.getAfterJson())); }
    public String conflict(UndoStep step, List<UndoStep> selection) {
        return support.conflict(support.read(step.getAfterJson()),step.getId(),selection);
    }
    public void undo(UndoStep step, ApplicationUser actor) { support.undo(support.read(step.getAfterJson()), actor); }
}
