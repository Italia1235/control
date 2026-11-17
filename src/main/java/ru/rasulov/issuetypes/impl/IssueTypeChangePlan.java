package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.JiraWorkflow;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.MappingView;
import ru.rasulov.common.api.dto.issuetype.SchemeAction;

import java.util.List;
import java.util.Map;

final class IssueTypeChangePlan {
    final Project project;
    final FieldConfigScheme sourceScheme;
    final SchemeAction action;
    final String newSchemeName;
    final List<IssueType> desired;
    final List<String> previousTypeIds;
    final String previousDefaultTypeId;
    final List<IssueTypeRef> desiredViews;
    final List<IssueTypeRef> added;
    final List<IssueTypeRef> removed;
    final List<Mapping> mappings;
    final List<MappingView> mappingViews;
    final List<String> otherProjectNames;
    /** A type of {@link #desired} that must become the default, or null: then the previous default stays if it stays. */
    final String explicitDefaultTypeId;

    IssueTypeChangePlan(Project project, FieldConfigScheme sourceScheme, SchemeAction action, String newSchemeName,
                        List<IssueType> desired, List<String> previousTypeIds, String previousDefaultTypeId,
                        List<IssueTypeRef> desiredViews, List<IssueTypeRef> added, List<IssueTypeRef> removed,
                        List<Mapping> mappings, List<MappingView> mappingViews, List<String> otherProjectNames,
                        String explicitDefaultTypeId) {
        this.project = project; this.sourceScheme = sourceScheme; this.action = action; this.newSchemeName = newSchemeName;
        this.desired = desired; this.previousTypeIds = previousTypeIds;
        this.previousDefaultTypeId = previousDefaultTypeId; this.desiredViews = desiredViews;
        this.added = added; this.removed = removed; this.mappings = mappings; this.mappingViews = mappingViews;
        this.otherProjectNames = otherProjectNames; this.explicitDefaultTypeId = explicitDefaultTypeId;
    }

    static final class Mapping {
        final IssueType from;
        final IssueType to;
        final JiraWorkflow fromWorkflow;
        final JiraWorkflow toWorkflow;
        final Map<String, Status> statusMappings;
        final int issues;

        Mapping(IssueType from, IssueType to, JiraWorkflow fromWorkflow, JiraWorkflow toWorkflow,
                Map<String, Status> statusMappings, int issues) {
            this.from = from; this.to = to; this.fromWorkflow = fromWorkflow; this.toWorkflow = toWorkflow;
            this.statusMappings = statusMappings; this.issues = issues;
        }
    }
}
