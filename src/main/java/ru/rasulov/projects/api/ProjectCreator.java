package ru.rasulov.projects.api;

import ru.rasulov.projects.api.dto.CreateProjectRequest;
import ru.rasulov.projects.api.dto.CreateProjectResponse;
import ru.rasulov.projects.api.dto.ProjectLeadView;
import ru.rasulov.projects.api.dto.ProjectTemplatesResponse;

import java.util.List;

/** Administrator-only creation of Jira Business/Software projects from a catalog template and lookup of potential project leads. */
public interface ProjectCreator {
    /**
     * Previews project details when dryRun=true, or applies the returned planId when dryRun=false.
     * Both the explicit mode and preview/apply request shape are enforced by the service implementation.
     */
    CreateProjectResponse create(CreateProjectRequest request);

    /** Read-only catalog of the project templates Jira reports, each flagged supported or not. Administrator only. */
    ProjectTemplatesResponse listTemplates();

    List<ProjectLeadView> listLeads(String query);
}
