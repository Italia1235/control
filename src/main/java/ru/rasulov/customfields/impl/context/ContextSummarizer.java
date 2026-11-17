package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import ru.rasulov.common.api.dto.field.context.ContextSummary;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;

import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Describes contexts for the caller, with names instead of ids: what will be created (from the resolved request) or
 * what an existing field really has (from Jira).
 */
@Named
public class ContextSummarizer {

    /** What was understood from the request. */
    public List<ContextSummary> summarize(List<ResolvedContext> contexts) {
        List<ContextSummary> summaries = new ArrayList<ContextSummary>(contexts.size());
        for (ResolvedContext context : contexts) {
            summaries.add(summary(context.getName(), context.isAllProjects(), context.getProjects(),
                    context.isAllIssueTypes(), context.getIssueTypes()));
        }
        return summaries;
    }

    /** What an existing field has. */
    public List<ContextSummary> summarize(CustomField field) {
        List<ContextSummary> summaries = new ArrayList<ContextSummary>();
        for (FieldConfigScheme scheme : field.getConfigurationSchemes()) {
            summaries.add(summary(scheme.getName(), scheme.isAllProjects(), scheme.getAssociatedProjectObjects(),
                    scheme.isAllIssueTypes(), scheme.getAssociatedIssueTypes()));
        }
        return summaries;
    }

    /**
     * Whether both lists cover the same projects and issue types, in any order and whatever the contexts are called.
     */
    public boolean sameScope(List<ContextSummary> a, List<ContextSummary> b) {
        return scopes(a).equals(scopes(b));
    }

    private static ContextSummary summary(String name, boolean allProjects, Collection<Project> projects,
                                          boolean allIssueTypes, Collection<IssueType> issueTypes) {
        List<ProjectRef> projectRefs = new ArrayList<ProjectRef>();
        if (!allProjects && projects != null) {
            for (Project project : projects) {
                projectRefs.add(new ProjectRef(project.getKey(), project.getName()));
            }
        }
        List<IssueTypeRef> issueTypeRefs = new ArrayList<IssueTypeRef>();
        if (!allIssueTypes && issueTypes != null) {
            for (IssueType issueType : issueTypes) {
                issueTypeRefs.add(new IssueTypeRef(issueType.getId(), issueType.getName()));
            }
        }
        return new ContextSummary(name, allProjects, projectRefs, allIssueTypes, issueTypeRefs);
    }

    private static List<String> scopes(List<ContextSummary> summaries) {
        List<String> scopes = new ArrayList<String>();
        for (ContextSummary summary : summaries) {
            List<String> projectKeys = new ArrayList<String>();
            for (ProjectRef project : summary.getProjects()) {
                projectKeys.add(project.getKey());
            }
            List<String> issueTypeIds = new ArrayList<String>();
            for (IssueTypeRef issueType : summary.getIssueTypes()) {
                issueTypeIds.add(issueType.getId());
            }
            Collections.sort(projectKeys);
            Collections.sort(issueTypeIds);
            scopes.add((summary.isAllProjects() ? "*" : projectKeys.toString()) + "|"
                    + (summary.isAllIssueTypes() ? "*" : issueTypeIds.toString()));
        }
        Collections.sort(scopes);
        return scopes;
    }
}
