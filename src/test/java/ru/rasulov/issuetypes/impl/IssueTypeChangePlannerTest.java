package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import org.junit.Before;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest.TypeMapping;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.ErrorCode;
import ru.rasulov.common.api.dto.issuetype.SchemeAction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IssueTypeChangePlannerTest {
    private final IssueTypeSchemeManager typeSchemes = mock(IssueTypeSchemeManager.class);
    private final WorkflowSchemeManager workflowSchemes = mock(WorkflowSchemeManager.class);
    private final WorkflowManager workflows = mock(WorkflowManager.class);
    private final IssueTypeManager types = mock(IssueTypeManager.class);
    private final StatusManager statuses = mock(StatusManager.class);
    private final OfBizDelegator delegator = mock(OfBizDelegator.class);
    private final Project project = mock(Project.class);
    private final FieldConfigScheme scheme = mock(FieldConfigScheme.class);
    private final AssignableWorkflowScheme workflowScheme = mock(AssignableWorkflowScheme.class);
    private final IssueType task = type("1", "Task", false);
    private final IssueType bug = type("2", "Bug", false);
    private final IssueType subtask = type("3", "Sub-task", true);
    private IssueTypeChangePlanner planner;

    @Before
    public void setUp() {
        when(project.getId()).thenReturn(10L); when(project.getKey()).thenReturn("ABC");
        when(typeSchemes.getConfigScheme(project)).thenReturn(scheme);
        when(typeSchemes.getIssueTypesForProject(project)).thenReturn(Collections.singletonList(task));
        when(typeSchemes.getDefaultIssueType(project)).thenReturn(task);
        when(typeSchemes.getAllSchemes()).thenReturn(Collections.singletonList(scheme));
        when(scheme.getName()).thenReturn("Shared types"); when(scheme.getAssociatedProjectObjects()).thenReturn(Collections.singletonList(project));
        when(workflowSchemes.getWorkflowSchemeObj(project)).thenReturn(workflowScheme);
        when(types.getIssueTypes()).thenReturn(Arrays.asList(task, bug, subtask));
        when(types.getIssueType("1")).thenReturn(task); when(types.getIssueType("2")).thenReturn(bug);
        when(types.getIssueType("3")).thenReturn(subtask);
        emptyIssues();
        planner = new IssueTypeChangePlanner(typeSchemes, workflowSchemes, workflows, types, statuses,
                new IssueTypeResolver(types), delegator);
    }

    @Test
    public void addingATypeToASharedSchemeCreatesAPrivateScheme() {
        Project other = mock(Project.class); when(other.getId()).thenReturn(11L); when(other.getKey()).thenReturn("OTHER");
        when(scheme.getAssociatedProjectObjects()).thenReturn(Arrays.asList(project, other));

        IssueTypeChangePlan plan = planner.plan(project, request(Arrays.asList("Task", "Bug"), null));

        assertEquals(SchemeAction.CREATE, plan.action);
        assertEquals("ABC: Issue Type Scheme", plan.newSchemeName);
        assertEquals(Collections.singletonList("OTHER"), plan.otherProjectNames);
        assertEquals("Bug", plan.added.get(0).getName());
    }

    @Test
    public void aProjectMayUseThreeHundredIssueTypes() {
        List<IssueType> available = new ArrayList<IssueType>();
        List<String> desired = new ArrayList<String>();
        available.add(task);
        desired.add("Task");
        for (int i = 1; i < 300; i++) {
            IssueType type = type(String.valueOf(1000 + i), String.format("Type %03d", i), false);
            available.add(type);
            desired.add(type.getName());
        }
        when(types.getIssueTypes()).thenReturn(available);

        IssueTypeChangePlan plan = planner.plan(project, request(desired, null));

        assertEquals(300, plan.desired.size());
        assertEquals(299, plan.added.size());
    }

    @Test
    public void anOccupiedRemovedTypeRequiresAnExplicitMapping() {
        when(typeSchemes.getIssueTypesForProject(project)).thenReturn(Arrays.asList(task, bug));
        issues(row("2", "10000"));

        IssueTypeChangeFailure failure = failure(request(Collections.singletonList("Task"), null));

        assertEquals(ErrorCode.MAPPING_REQUIRED, failure.getCode());
        assertEquals(Collections.singletonList("Task"), failure.getSuggestions());
    }

    @Test
    public void aStandardIssueCannotBecomeASubtask() {
        when(typeSchemes.getIssueTypesForProject(project)).thenReturn(Arrays.asList(task, bug));
        issues(row("2", "10000"));
        TypeMapping mapping = new TypeMapping("Bug", "Sub-task", null);

        IssueTypeChangeFailure failure = failure(request(Arrays.asList("Task", "Sub-task"),
                Collections.singletonList(mapping)));

        assertEquals(ErrorCode.INVALID_MAPPING, failure.getCode());
    }

    @Test
    public void addOnlyPlansWithoutReadingIssueUsage() {
        when(delegator.findListIteratorByCondition(any(String.class), any(), isNull(), anyList(), isNull(), isNull()))
                .thenThrow(new AssertionError("Add-only must not scan issue usage"));
        IssueTypeChangePlan plan = planner.plan(project, request(Arrays.asList("Task", "Bug"), null));
        assertEquals(SchemeAction.UPDATE, plan.action);
        assertEquals(Collections.singletonList("1"), plan.previousTypeIds);
        assertEquals("Bug", plan.added.get(0).getName());
        assertEquals(Collections.emptyList(), plan.removed);
        assertEquals(Collections.emptyList(), plan.mappings);
    }

    private IssueTypeChangeFailure failure(SetProjectIssueTypesRequest request) {
        try { planner.plan(project, request); fail("expected failure"); return null; }
        catch (IssueTypeChangeFailure expected) { return expected; }
    }

    private SetProjectIssueTypesRequest request(java.util.List<String> desired, java.util.List<TypeMapping> mappings) {
        return new SetProjectIssueTypesRequest(true, "ABC", desired, mappings, null);
    }

    private void emptyIssues() { issues(); }

    private void issues(GenericValue... rows) {
        OfBizListIterator iterator = mock(OfBizListIterator.class);
        if (rows.length == 0) when(iterator.next()).thenReturn(null);
        else if (rows.length == 1) when(iterator.next()).thenReturn(rows[0], (GenericValue) null);
        else throw new AssertionError("test helper supports up to one row");
        when(delegator.findListIteratorByCondition(any(String.class), any(), isNull(), anyList(), isNull(), isNull()))
                .thenReturn(iterator);
    }

    private static GenericValue row(String type, String status) {
        GenericValue row = mock(GenericValue.class);
        when(row.getString("type")).thenReturn(type); when(row.getString("status")).thenReturn(status);
        return row;
    }

    private static IssueType type(String id, String name, boolean subtask) {
        IssueType type = mock(IssueType.class);
        when(type.getId()).thenReturn(id); when(type.getName()).thenReturn(name); when(type.isSubTask()).thenReturn(subtask);
        return type;
    }
}
