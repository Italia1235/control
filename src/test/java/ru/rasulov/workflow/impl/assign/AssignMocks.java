package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.type.ProjectTypeKey;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import org.ofbiz.core.entity.GenericValue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Small factories for the Jira objects the assignment tests share. */
final class AssignMocks {

    private AssignMocks() {
    }

    static IssueType issueType(String id, String name) {
        IssueType type = mock(IssueType.class);
        when(type.getId()).thenReturn(id);
        when(type.getName()).thenReturn(name);
        return type;
    }

    static Status status(String id, String name) {
        Status status = mock(Status.class);
        when(status.getId()).thenReturn(id);
        when(status.getName()).thenReturn(name);
        return status;
    }

    static Project project(long id, String key, String name, String projectType, IssueType... types) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        when(project.getKey()).thenReturn(key);
        when(project.getName()).thenReturn(name);
        when(project.getProjectTypeKey()).thenReturn(new ProjectTypeKey(projectType));
        when(project.getIssueTypes()).thenReturn(Arrays.asList(types));
        return project;
    }

    /** A workflow that has exactly the given statuses. */
    static JiraWorkflow workflow(String name, Status... statuses) {
        JiraWorkflow workflow = mock(JiraWorkflow.class);
        when(workflow.getName()).thenReturn(name);
        when(workflow.getLinkedStatusObjects()).thenReturn(Arrays.asList(statuses));
        java.util.Set<String> ids = new HashSet<String>();
        for (Status status : statuses) {
            ids.add(status.getId());
        }
        when(workflow.getLinkedStatusIds()).thenReturn(ids);
        return workflow;
    }

    /**
     * A scheme whose issue types have the given workflows; every other type has {@code defaultWorkflow}.
     *
     * @param explicit issue type id to workflow name
     */
    static AssignableWorkflowScheme scheme(Long id, String name, String defaultWorkflow, Map<String, String> explicit) {
        AssignableWorkflowScheme scheme = mock(AssignableWorkflowScheme.class);
        when(scheme.getId()).thenReturn(id);
        when(scheme.getName()).thenReturn(name);
        when(scheme.getActualDefaultWorkflow()).thenReturn(defaultWorkflow);
        when(scheme.getMappings()).thenReturn(explicit);
        when(scheme.getActualWorkflow(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            String workflow = explicit.get((String) invocation.getArgument(0));
            return workflow == null ? defaultWorkflow : workflow;
        });
        return scheme;
    }

    static AssignableWorkflowScheme scheme(Long id, String name, String defaultWorkflow) {
        return scheme(id, name, defaultWorkflow, new HashMap<String, String>());
    }

    static Map<String, String> mapping(String... issueTypeIdAndWorkflow) {
        Map<String, String> map = new HashMap<String, String>();
        for (int i = 0; i < issueTypeIdAndWorkflow.length; i += 2) {
            map.put(issueTypeIdAndWorkflow[i], issueTypeIdAndWorkflow[i + 1]);
        }
        return map;
    }

    /** An issue row as the database returns it. */
    static GenericValue issueRow(long id, String key, String statusId, Long workflowEntryId) {
        GenericValue row = mock(GenericValue.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getString("key")).thenReturn(key);
        when(row.getString("status")).thenReturn(statusId);
        when(row.getLong("workflowId")).thenReturn(workflowEntryId);
        return row;
    }

    static IssueUsage usage(String... issueTypeStatusCount) {
        IssueUsage usage = new IssueUsage();
        for (int i = 0; i < issueTypeStatusCount.length; i += 3) {
            for (int n = Integer.parseInt(issueTypeStatusCount[i + 2]); n > 0; n--) {
                usage.add(issueTypeStatusCount[i], issueTypeStatusCount[i + 1]);
            }
        }
        return usage;
    }

    static <T> java.util.List<T> list(T... items) {
        return items.length == 0 ? Collections.<T>emptyList() : Arrays.asList(items);
    }
}
