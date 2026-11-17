package ru.rasulov.common.api.dto.field.context;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.field.OptionDefinition;

import java.util.List;

/**
 * Where a field applies: which projects and which issue types. Each axis is given one way: {@code allProjects: true}
 * <b>or</b> a non-empty {@code projects}; {@code allIssueTypes: true} <b>or</b> a non-empty {@code issueTypes}.
 * Nothing is assumed when an axis is missing: the field is not created.
 */
public final class ContextDefinition {

    private final String name;
    private final boolean allProjects;
    private final List<String> projects;
    private final boolean allIssueTypes;
    private final List<String> issueTypes;
    private final List<OptionDefinition> options;

    @JsonCreator
    public ContextDefinition(@JsonProperty("name") String name,
                             @JsonProperty("allProjects") boolean allProjects,
                             @JsonProperty("projects") List<String> projects,
                             @JsonProperty("allIssueTypes") boolean allIssueTypes,
                             @JsonProperty("issueTypes") List<String> issueTypes,
                             @JsonProperty("options") List<OptionDefinition> options) {
        this.name = name;
        this.allProjects = allProjects;
        this.projects = Immutables.listOf(projects);
        this.allIssueTypes = allIssueTypes;
        this.issueTypes = Immutables.listOf(issueTypes);
        this.options = options == null ? null : Immutables.listOf(options);
    }

    /** Optional context name; by default the keys of the first five projects, or "Global". */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("allProjects")
    public boolean isAllProjects() {
        return allProjects;
    }

    /** Project keys or names. */
    @JsonProperty("projects")
    public List<String> getProjects() {
        return projects;
    }

    @JsonProperty("allIssueTypes")
    public boolean isAllIssueTypes() {
        return allIssueTypes;
    }

    /** Issue type names or ids. */
    @JsonProperty("issueTypes")
    public List<String> getIssueTypes() {
        return issueTypes;
    }

    /** Null means inherit field options; an empty list explicitly overrides them with no options. */
    @JsonProperty("options")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<OptionDefinition> getOptions() {
        return options;
    }

    /** Whether a context-specific list was supplied, including an explicitly empty list. */
    @JsonIgnore
    public boolean hasOptions() {
        return options != null;
    }
}
