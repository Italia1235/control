package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import ru.rasulov.common.api.dto.field.OptionDefinition;

import java.util.List;

/**
 * A {@code ContextDefinition} whose projects and issue types are resolved to Jira objects. Immutable; the
 * {@code with...} methods return changed copies.
 */
public final class ResolvedContext {

    private final String name;
    private final boolean allProjects;
    private final List<Project> projects;
    private final boolean allIssueTypes;
    private final List<IssueType> issueTypes;
    private final List<OptionDefinition> options;
    private final List<String> warnings;

    ResolvedContext(String name, boolean allProjects, List<Project> projects, boolean allIssueTypes,
                    List<IssueType> issueTypes, List<OptionDefinition> options, List<String> warnings) {
        this.name = name;
        this.allProjects = allProjects;
        this.projects = projects;
        this.allIssueTypes = allIssueTypes;
        this.issueTypes = issueTypes;
        this.options = options;
        this.warnings = warnings;
    }

    /** Name of the context in Jira; unique within the field. */
    public String getName() {
        return name;
    }

    public boolean isAllProjects() {
        return allProjects;
    }

    /** Empty when {@link #isAllProjects()}. */
    public List<Project> getProjects() {
        return projects;
    }

    public boolean isAllIssueTypes() {
        return allIssueTypes;
    }

    /** Empty when {@link #isAllIssueTypes()}. */
    public List<IssueType> getIssueTypes() {
        return issueTypes;
    }

    /**
     * The context's own options as requested (unchecked), then, after {@link #withOptions}, the options that are
     * written into this context. Empty means none.
     */
    public List<OptionDefinition> getOptions() {
        return options;
    }

    /** Things that are allowed but probably not intended; shown to the user next to the result. */
    public List<String> getWarnings() {
        return warnings;
    }

    public ResolvedContext withOptions(List<OptionDefinition> newOptions) {
        return new ResolvedContext(name, allProjects, projects, allIssueTypes, issueTypes, newOptions, warnings);
    }

    public ResolvedContext withName(String newName) {
        return new ResolvedContext(newName, allProjects, projects, allIssueTypes, issueTypes, options, warnings);
    }

    public ResolvedContext withWarnings(List<String> newWarnings) {
        return new ResolvedContext(name, allProjects, projects, allIssueTypes, issueTypes, options, newWarnings);
    }
}
