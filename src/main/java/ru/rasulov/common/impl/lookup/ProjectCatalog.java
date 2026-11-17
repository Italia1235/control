package ru.rasulov.common.impl.lookup;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.LookupLimits;
import ru.rasulov.common.api.dto.ProjectListResponse;
import ru.rasulov.common.api.dto.ProjectResponse;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Lets the caller find a project from the user's words: active projects whose key or name contains the query, sorted
 * by key, cut to a limit so that an instance with thousands of projects cannot flood the caller.
 */
@Named
public class ProjectCatalog {

    private final ProjectManager projectManager;

    @Inject
    public ProjectCatalog(@ComponentImport ProjectManager projectManager) {
        this.projectManager = projectManager;
    }

    /**
     * @param query substring of key or name, ignoring case; {@code null} or blank matches everything
     * @param limit how many projects to return; {@code null} or below 1 means the default, above the maximum is cut
     */
    public ProjectListResponse list(String query, Integer limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int size = limit == null || limit < 1 ? LookupLimits.DEFAULT_PROJECT_LIST_SIZE
                : Math.min(limit, LookupLimits.MAX_PROJECT_LIST_SIZE);

        List<Project> matching = new ArrayList<Project>();
        for (Project project : projectManager.getProjects()) {
            if (!project.isArchived() && matches(project, needle)) {
                matching.add(project);
            }
        }
        Collections.sort(matching, new Comparator<Project>() {
            @Override
            public int compare(Project a, Project b) {
                return a.getKey().compareTo(b.getKey());
            }
        });

        List<ProjectResponse> shown = new ArrayList<ProjectResponse>();
        boolean includeIssueTypes = matching.size() == 1;
        for (Project project : matching.subList(0, Math.min(size, matching.size()))) {
            List<IssueType> projectTypes = new ArrayList<IssueType>(project.getIssueTypes());
            List<String> issueTypes = new ArrayList<String>();
            if (includeIssueTypes) {
                for (IssueType issueType : projectTypes) {
                    issueTypes.add(issueType.getName());
                }
                Collections.sort(issueTypes, String.CASE_INSENSITIVE_ORDER);
            }
            shown.add(new ProjectResponse(project.getKey(), project.getName(), includeIssueTypes,
                    projectTypes.size(), issueTypes));
        }
        return new ProjectListResponse(matching.size(), shown);
    }

    private static boolean matches(Project project, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        String name = project.getName() == null ? "" : project.getName().toLowerCase(Locale.ROOT);
        return project.getKey().toLowerCase(Locale.ROOT).contains(needle) || name.contains(needle);
    }
}
