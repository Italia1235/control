package ru.rasulov.common.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Answer of {@code list_projects}: at most {@code limit} projects out of {@code total} that matched. */
public final class ProjectListResponse {

    private final int total;
    private final List<ProjectResponse> projects;

    public ProjectListResponse(int total, List<ProjectResponse> projects) {
        this.total = total;
        this.projects = Immutables.listOf(projects);
    }

    /** Number of matching projects; more than {@code projects.size()} means the list was cut, narrow the query. */
    @JsonProperty("total")
    public int getTotal() {
        return total;
    }

    @JsonProperty("projects")
    public List<ProjectResponse> getProjects() {
        return projects;
    }
}
