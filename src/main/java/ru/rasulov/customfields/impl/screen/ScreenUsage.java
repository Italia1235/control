package ru.rasulov.customfields.impl.screen;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import ru.rasulov.customfields.api.dto.screen.ScreenOperation;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** How the projects of a field's context use one screen: for which operations, in which projects, for which types. */
final class ScreenUsage {

    private final FieldScreen screen;
    private final Set<ScreenOperation> operations = EnumSet.noneOf(ScreenOperation.class);
    private final Map<Long, Project> projects = new LinkedHashMap<Long, Project>();
    private final Map<String, IssueType> issueTypes = new LinkedHashMap<String, IssueType>();

    ScreenUsage(FieldScreen screen) {
        this.screen = screen;
    }

    void add(ScreenOperation operation, Project project, IssueType issueType) {
        operations.add(operation);
        projects.put(project.getId(), project);
        issueTypes.put(issueType.getId(), issueType);
    }

    FieldScreen screen() {
        return screen;
    }

    Set<ScreenOperation> operations() {
        return operations;
    }

    /** By project id, in the order found. */
    Map<Long, Project> projects() {
        return projects;
    }

    /** By issue type id, in the order found. */
    Map<String, IssueType> issueTypes() {
        return issueTypes;
    }
}
