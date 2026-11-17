package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.impl.context.ContextScopeRules.Scope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The two-axis scope input shared by the context tools: exactly one form per axis, resolved with the shared
 * resolvers. Lookup failures stay {@code LookupFailure} so every tool keeps its suggestions.
 */
final class ScopeInput {
    private ScopeInput() { }

    /** An input that breaks the shape rules; the tool reports it as VALIDATION_ERROR. */
    static final class Invalid extends RuntimeException {
        Invalid(String message) { super(message); }
    }

    /** Exactly one of {@code all=true} and a nonempty list within {@code maximum}. */
    static void checkAxis(Boolean all, List<String> values, int maximum, String name) {
        if (all != null && !all || Boolean.TRUE.equals(all) && values != null
                || !Boolean.TRUE.equals(all) && (values == null || values.isEmpty()))
            throw new Invalid("Choose exactly one form for " + name + ": all=true or a nonempty list, not both.");
        if (values != null && values.size() > maximum)
            throw new Invalid(name + " exceeds the maximum of " + maximum + ".");
    }

    static Scope resolve(Boolean allProjects, List<String> projects, Boolean allIssueTypes, List<String> issueTypes,
                         ProjectResolver.Session projectLookup, IssueTypeResolver.Session typeLookup) {
        List<Project> resolvedProjects = new ArrayList<Project>();
        List<IssueType> resolvedTypes = new ArrayList<IssueType>();
        Set<Long> seenProjects = new HashSet<Long>();
        if (projects != null) for (String text : projects) {
            Project project = projectLookup.resolve(text);
            if (!seenProjects.add(project.getId())) throw new Invalid("A project is listed more than once.");
            resolvedProjects.add(project);
        }
        Set<String> seenTypes = new HashSet<String>();
        if (issueTypes != null) for (String text : issueTypes) {
            IssueType type = typeLookup.resolve(text);
            if (!seenTypes.add(type.getId())) throw new Invalid("An issue type is listed more than once.");
            resolvedTypes.add(type);
        }
        return new Scope(Boolean.TRUE.equals(allProjects), resolvedProjects,
                Boolean.TRUE.equals(allIssueTypes), resolvedTypes);
    }
}
