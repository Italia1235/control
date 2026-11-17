package ru.rasulov.common.api.dto.field.context;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.List;

/**
 * A context as it was understood or as it exists: names instead of keys and ids, so that a caller can show it to the
 * user before anything is created.
 */
public final class ContextSummary {

    private final String name;
    private final boolean allProjects;
    private final List<ProjectRef> projects;
    private final boolean allIssueTypes;
    private final List<IssueTypeRef> issueTypes;

    public ContextSummary(String name, boolean allProjects, List<ProjectRef> projects, boolean allIssueTypes,
                          List<IssueTypeRef> issueTypes) {
        this.name = name;
        this.allProjects = allProjects;
        this.projects = Immutables.listOf(projects);
        this.allIssueTypes = allIssueTypes;
        this.issueTypes = Immutables.listOf(issueTypes);
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("allProjects")
    public boolean isAllProjects() {
        return allProjects;
    }

    /** Empty when {@code allProjects}. */
    @JsonProperty("projects")
    public List<ProjectRef> getProjects() {
        return projects;
    }

    @JsonProperty("allIssueTypes")
    public boolean isAllIssueTypes() {
        return allIssueTypes;
    }

    /** Empty when {@code allIssueTypes}. */
    @JsonProperty("issueTypes")
    public List<IssueTypeRef> getIssueTypes() {
        return issueTypes;
    }
}
