package ru.rasulov.workflow.api.dto.clone;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** One workflow of the project's scheme, who else uses it and what happens to it. */
public final class WorkflowCloneView {

    private final String name;
    private final String newName;
    private final CloneVerdict verdict;
    private final int otherProjects;
    private final List<String> otherProjectNames;

    public WorkflowCloneView(String name, String newName, CloneVerdict verdict, int otherProjects,
                             List<String> otherProjectNames) {
        this.name = name;
        this.newName = newName;
        this.verdict = verdict;
        this.otherProjects = otherProjects;
        this.otherProjectNames = Immutables.listOf(otherProjectNames);
    }

    /** The workflow as it is now. */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /** Name of the copy; {@code null} unless the verdict is {@code COPY}. */
    @JsonProperty("newName")
    public String getNewName() {
        return newName;
    }

    @JsonProperty("verdict")
    public CloneVerdict getVerdict() {
        return verdict;
    }

    /** How many other projects use the workflow. */
    @JsonProperty("otherProjects")
    public int getOtherProjects() {
        return otherProjects;
    }

    /** Names of some of those projects. */
    @JsonProperty("otherProjectNames")
    public List<String> getOtherProjectNames() {
        return otherProjectNames;
    }
}
