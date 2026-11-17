package ru.rasulov.screens.api.dto.layout;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.ProjectRef;
import java.util.List;

public final class ScreenImpact {
    private final Long screenId;
    private final String name;
    private final List<String> operations;
    private final int projects;
    private final List<ProjectRef> projectNames;
    private final boolean defaultScreen;
    private final List<LayoutWarning> warnings;
    public ScreenImpact(Long screenId, String name, List<String> operations, int projects,
                        List<ProjectRef> projectNames, boolean defaultScreen, List<LayoutWarning> warnings) {
        this.screenId = screenId; this.name = name; this.operations = Immutables.listOf(operations);
        this.projects = projects; this.projectNames = Immutables.listOf(projectNames);
        this.defaultScreen = defaultScreen; this.warnings = Immutables.listOf(warnings);
    }
    @JsonProperty("screenId") public Long getScreenId() { return screenId; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("operations") public List<String> getOperations() { return operations; }
    @JsonProperty("projects") public int getProjects() { return projects; }
    @JsonProperty("projectNames") public List<ProjectRef> getProjectNames() { return projectNames; }
    @JsonProperty("defaultScreen") public boolean isDefaultScreen() { return defaultScreen; }
    @JsonProperty("warnings") public List<LayoutWarning> getWarnings() { return warnings; }
}
