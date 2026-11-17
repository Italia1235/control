package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reverse traversal from a screen object to the active projects affected by changing it. */
public final class ScreenUsage {
    private static final int MAX_NAMES = 10;
    private final ProjectManager projectManager;
    private final FieldScreenSchemeManager fieldScreenSchemeManager;
    private final IssueTypeScreenSchemeManager issueTypeScreenSchemeManager;

    public ScreenUsage(ProjectManager projectManager, FieldScreenSchemeManager fieldScreenSchemeManager,
                       IssueTypeScreenSchemeManager issueTypeScreenSchemeManager) {
        this.projectManager = projectManager;
        this.fieldScreenSchemeManager = fieldScreenSchemeManager;
        this.issueTypeScreenSchemeManager = issueTypeScreenSchemeManager;
    }

    List<Project> projects(IssueTypeScreenScheme scheme) {
        Map<Long, Project> result = new LinkedHashMap<Long, Project>();
        for (GenericValue value : issueTypeScreenSchemeManager.getProjects(scheme)) {
            add(result, projectManager.getProjectObj(value.getLong("id")));
        }
        return sorted(result.values());
    }

    boolean hasOtherAssociation(IssueTypeScreenScheme scheme, Project target) {
        for (GenericValue value : issueTypeScreenSchemeManager.getProjects(scheme)) {
            if (!target.getId().equals(value.getLong("id"))) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    List<Project> projects(FieldScreenScheme scheme) {
        Map<Long, Project> result = new LinkedHashMap<Long, Project>();
        for (Object value : issueTypeScreenSchemeManager.getIssueTypeScreenSchemes(scheme)) {
            for (Project project : projects((IssueTypeScreenScheme) value)) add(result, project);
        }
        return sorted(result.values());
    }

    public List<Project> projects(FieldScreen screen) {
        Map<Long, Project> result = new LinkedHashMap<Long, Project>();
        for (FieldScreenScheme scheme : fieldScreenSchemeManager.getFieldScreenSchemes(screen)) {
            for (Project project : projects(scheme)) add(result, project);
        }
        return sorted(result.values());
    }

    List<Project> others(List<Project> projects, Project target) {
        List<Project> result = new ArrayList<Project>();
        for (Project project : projects) if (!project.getId().equals(target.getId())) result.add(project);
        return result;
    }

    public List<ProjectRef> refs(List<Project> projects) {
        List<ProjectRef> result = new ArrayList<ProjectRef>();
        for (int i = 0; i < projects.size() && i < MAX_NAMES; i++) {
            Project project = projects.get(i);
            result.add(new ProjectRef(project.getKey(), project.getName()));
        }
        return result;
    }

    private static void add(Map<Long, Project> result, Project project) {
        if (project != null && !project.isArchived()) result.put(project.getId(), project);
    }

    private static List<Project> sorted(Collection<Project> projects) {
        List<Project> result = new ArrayList<Project>(projects);
        result.sort(Comparator.comparing(Project::getKey));
        return result;
    }
}
