package ru.rasulov.customfields.api.dto.screen;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.List;

/**
 * A screen the field could be added to, with everything the user needs to decide: for which operations it is used,
 * which projects it belongs to and which ones would be affected without being in the field's context.
 */
public final class ScreenCandidate {

    private final long screenId;
    private final String name;
    private final List<ScreenOperation> operations;
    private final List<TabRef> tabs;
    private final boolean alreadyHasField;
    private final boolean defaultScreen;
    private final int projectsInContextTotal;
    private final List<ProjectRef> projectsInContext;
    private final int projectsOutsideContextTotal;
    private final List<ProjectRef> projectsOutsideContext;
    private final List<IssueTypeRef> issueTypes;
    private final Recommendation recommendation;
    private final List<String> reasons;

    public ScreenCandidate(long screenId, String name, List<ScreenOperation> operations, List<TabRef> tabs,
                           boolean alreadyHasField, boolean defaultScreen, int projectsInContextTotal,
                           List<ProjectRef> projectsInContext, int projectsOutsideContextTotal,
                           List<ProjectRef> projectsOutsideContext, List<IssueTypeRef> issueTypes,
                           Recommendation recommendation, List<String> reasons) {
        this.screenId = screenId;
        this.name = name;
        this.operations = Immutables.listOf(operations);
        this.tabs = Immutables.listOf(tabs);
        this.alreadyHasField = alreadyHasField;
        this.defaultScreen = defaultScreen;
        this.projectsInContextTotal = projectsInContextTotal;
        this.projectsInContext = Immutables.listOf(projectsInContext);
        this.projectsOutsideContextTotal = projectsOutsideContextTotal;
        this.projectsOutsideContext = Immutables.listOf(projectsOutsideContext);
        this.issueTypes = Immutables.listOf(issueTypes);
        this.recommendation = recommendation;
        this.reasons = Immutables.listOf(reasons);
    }

    @JsonProperty("screenId")
    public long getScreenId() {
        return screenId;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /** For which operations the projects of the context use this screen. */
    @JsonProperty("operations")
    public List<ScreenOperation> getOperations() {
        return operations;
    }

    @JsonProperty("tabs")
    public List<TabRef> getTabs() {
        return tabs;
    }

    @JsonProperty("alreadyHasField")
    public boolean isAlreadyHasField() {
        return alreadyHasField;
    }

    /** {@code true} for Jira's default screen. */
    @JsonProperty("defaultScreen")
    public boolean isDefaultScreen() {
        return defaultScreen;
    }

    /** How many projects of the field's context use the screen (all of them, not only the ones listed). */
    @JsonProperty("projectsInContextTotal")
    public int getProjectsInContextTotal() {
        return projectsInContextTotal;
    }

    /** At most {@code Limits.MAX_PROJECTS_SHOWN} of them. */
    @JsonProperty("projectsInContext")
    public List<ProjectRef> getProjectsInContext() {
        return projectsInContext;
    }

    /** How many projects outside the context also use the screen; adding the field changes it for them too. */
    @JsonProperty("projectsOutsideContextTotal")
    public int getProjectsOutsideContextTotal() {
        return projectsOutsideContextTotal;
    }

    /** At most {@code Limits.MAX_PROJECTS_SHOWN} of them. */
    @JsonProperty("projectsOutsideContext")
    public List<ProjectRef> getProjectsOutsideContext() {
        return projectsOutsideContext;
    }

    /** Issue types for which the projects of the context use this screen. */
    @JsonProperty("issueTypes")
    public List<IssueTypeRef> getIssueTypes() {
        return issueTypes;
    }

    @JsonProperty("recommendation")
    public Recommendation getRecommendation() {
        return recommendation;
    }

    /** Short reasons behind the recommendation, ready to be shown to the user. */
    @JsonProperty("reasons")
    public List<String> getReasons() {
        return reasons;
    }
}
