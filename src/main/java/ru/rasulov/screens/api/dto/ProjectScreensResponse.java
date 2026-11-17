package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.List;

/** Current effective screen setup of a project, one row per issue type. */
public final class ProjectScreensResponse {
    private final ProjectRef project;
    private final SchemeView scheme;
    private final List<IssueTypeScreensView> issueTypes;

    public ProjectScreensResponse(ProjectRef project, SchemeView scheme, List<IssueTypeScreensView> issueTypes) {
        this.project = project;
        this.scheme = scheme;
        this.issueTypes = Immutables.listOf(issueTypes);
    }

    @JsonProperty("project") public ProjectRef getProject() { return project; }
    @JsonProperty("scheme") public SchemeView getScheme() { return scheme; }
    @JsonProperty("issueTypes") public List<IssueTypeScreensView> getIssueTypes() { return issueTypes; }

    public static final class SchemeView {
        private final Long id;
        private final String name;
        private final boolean defaultScheme;
        private final int otherProjects;
        private final List<ProjectRef> otherProjectNames;

        public SchemeView(Long id, String name, boolean defaultScheme, int otherProjects,
                          List<ProjectRef> otherProjectNames) {
            this.id = id; this.name = name; this.defaultScheme = defaultScheme;
            this.otherProjects = otherProjects; this.otherProjectNames = Immutables.listOf(otherProjectNames);
        }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("isDefault") public boolean isDefaultScheme() { return defaultScheme; }
        @JsonProperty("otherProjects") public int getOtherProjects() { return otherProjects; }
        @JsonProperty("otherProjectNames") public List<ProjectRef> getOtherProjectNames() { return otherProjectNames; }
    }

    public static final class IssueTypeScreensView {
        private final IssueTypeRef issueType;
        private final ScreenSchemeView screenScheme;

        public IssueTypeScreensView(IssueTypeRef issueType, ScreenSchemeView screenScheme) {
            this.issueType = issueType; this.screenScheme = screenScheme;
        }
        @JsonProperty("issueType") public IssueTypeRef getIssueType() { return issueType; }
        @JsonProperty("screenScheme") public ScreenSchemeView getScreenScheme() { return screenScheme; }
    }

    public static final class ScreenSchemeView {
        private final Long id;
        private final String name;
        private final int otherProjects;
        private final List<ProjectRef> otherProjectNames;
        private final List<OperationScreenView> screens;

        public ScreenSchemeView(Long id, String name, int otherProjects, List<ProjectRef> otherProjectNames,
                                List<OperationScreenView> screens) {
            this.id = id; this.name = name; this.otherProjects = otherProjects;
            this.otherProjectNames = Immutables.listOf(otherProjectNames); this.screens = Immutables.listOf(screens);
        }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("otherProjects") public int getOtherProjects() { return otherProjects; }
        @JsonProperty("otherProjectNames") public List<ProjectRef> getOtherProjectNames() { return otherProjectNames; }
        @JsonProperty("screens") public List<OperationScreenView> getScreens() { return screens; }
    }

    public static final class OperationScreenView {
        private final String operation;
        private final Long id;
        private final String name;
        private final int otherProjects;
        private final List<ProjectRef> otherProjectNames;

        public OperationScreenView(String operation, Long id, String name, int otherProjects,
                                   List<ProjectRef> otherProjectNames) {
            this.operation = operation; this.id = id; this.name = name; this.otherProjects = otherProjects;
            this.otherProjectNames = Immutables.listOf(otherProjectNames);
        }
        @JsonProperty("operation") public String getOperation() { return operation; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("otherProjects") public int getOtherProjects() { return otherProjects; }
        @JsonProperty("otherProjectNames") public List<ProjectRef> getOtherProjectNames() { return otherProjectNames; }
    }
}
