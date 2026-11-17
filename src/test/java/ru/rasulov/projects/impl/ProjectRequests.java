package ru.rasulov.projects.impl;

import ru.rasulov.common.api.dto.issuetype.IssueTypeDefinition;
import ru.rasulov.projects.api.dto.CreateProjectRequest;

import java.util.List;

/** Requests in the current create_project contract: action=create with a templateKey; apply carries only the plan. */
public final class ProjectRequests {
    private ProjectRequests() {
    }

    public static CreateProjectRequest preview(String name, String key, String lead) {
        return preview(ProjectTemplates.PROJECT_MANAGEMENT, name, key, lead);
    }

    public static CreateProjectRequest preview(String templateKey, String name, String key, String lead) {
        return new CreateProjectRequest("create", templateKey, true, name, key, lead, null, null, null, null);
    }

    public static CreateProjectRequest preview(String name, String key, String lead, List<String> issueTypes,
                                               List<IssueTypeDefinition> newIssueTypes, String defaultIssueType) {
        return new CreateProjectRequest("create", ProjectTemplates.PROJECT_MANAGEMENT, true, name, key, lead, null,
                issueTypes, newIssueTypes, defaultIssueType);
    }

    public static CreateProjectRequest apply(String planId) {
        return new CreateProjectRequest(null, null, false, null, null, null, planId, null, null, null);
    }
}
