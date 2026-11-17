package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.junit.Before;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.screens.api.dto.CloneProjectScreensRequest;
import ru.rasulov.screens.api.dto.ScreenCloneScope;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ScreenClonePlannerTest {
    private final IssueTypeResolver resolver = mock(IssueTypeResolver.class);
    private final ProjectManager projectManager = mock(ProjectManager.class);
    private final FieldScreenManager screenManager = mock(FieldScreenManager.class);
    private final FieldScreenSchemeManager fieldSchemeManager = mock(FieldScreenSchemeManager.class);
    private final IssueTypeScreenSchemeManager topManager = mock(IssueTypeScreenSchemeManager.class);
    private final Project alpha = project(1L, "ABC", "Alpha");
    private final Project beta = project(2L, "BBB", "Beta");
    private final IssueType bug = type("10001", "Bug");
    private final IssueType task = type("10002", "Task");
    private final IssueTypeScreenScheme top = mock(IssueTypeScreenScheme.class);
    private final FieldScreenScheme fieldScheme = mock(FieldScreenScheme.class);
    private final FieldScreen screen = mock(FieldScreen.class);
    private final GenericValue alphaValue = projectValue(1L);
    private final GenericValue betaValue = projectValue(2L);
    private ScreenClonePlanner planner;

    @Before
    public void setUp() {
        when(alpha.getIssueTypes()).thenReturn(Arrays.asList(bug, task));
        when(topManager.getIssueTypeScreenScheme(alpha)).thenReturn(top);
        when(top.getId()).thenReturn(10L); when(top.getName()).thenReturn("BBB: Project screens");
        when(top.getEffectiveFieldScreenScheme(bug)).thenReturn(fieldScheme);
        when(top.getEffectiveFieldScreenScheme(task)).thenReturn(fieldScheme);
        when(fieldScheme.getId()).thenReturn(20L); when(fieldScheme.getName()).thenReturn("BBB: Work scheme");
        FieldScreenSchemeItem item = mock(FieldScreenSchemeItem.class);
        when(item.getFieldScreen()).thenReturn(screen);
        when(fieldScheme.getFieldScreenSchemeItems()).thenReturn(Collections.singletonList(item));
        when(screen.getId()).thenReturn(30L); when(screen.getName()).thenReturn("ABC: Work screen");
        when(topManager.getIssueTypeScreenSchemes()).thenReturn(Collections.singletonList(top));
        when(fieldSchemeManager.getFieldScreenSchemes()).thenReturn(Collections.singletonList(fieldScheme));
        when(screenManager.getFieldScreens()).thenReturn(Collections.singletonList(screen));
        when(topManager.getProjects(top)).thenReturn(Arrays.asList(alphaValue, betaValue));
        when(topManager.getIssueTypeScreenSchemes(fieldScheme)).thenReturn(Collections.singletonList(top));
        when(fieldSchemeManager.getFieldScreenSchemes(screen)).thenReturn(Collections.singletonList(fieldScheme));
        when(projectManager.getProjectObj(1L)).thenReturn(alpha);
        when(projectManager.getProjectObj(2L)).thenReturn(beta);
        when(resolver.resolve("Bug")).thenReturn(bug);
        planner = new ScreenClonePlanner(resolver, projectManager, screenManager, fieldSchemeManager, topManager);
    }

    @Test
    public void selectedCopiesOnlyItsEffectiveSchemeAndCanCopyItsScreens() {
        ScreenClonePlan plan = planner.plan(alpha, new CloneProjectScreensRequest(true, "ABC",
                ScreenCloneScope.SELECTED, Collections.singletonList("Bug"), true));

        assertEquals(Collections.singleton("10001"), plan.selectedIssueTypeIds);
        assertEquals("ABC: Project screens", plan.newSchemeName);
        assertEquals("ABC: Work scheme", plan.screenSchemes.get(fieldScheme));
        assertEquals("ABC: Work screen v1", plan.screens.get(screen));
        assertTrue(plan.separationNeeded);
        assertEquals(1, plan.schemeView.getOtherProjects());
        assertEquals(1, plan.screenSchemeViews.size());
        assertEquals(1, plan.screenViews.size());
    }

    @Test
    public void privateRequestedLevelsAreAlreadySeparate() {
        when(topManager.getProjects(top)).thenReturn(Collections.singletonList(alphaValue));

        ScreenClonePlan plan = planner.plan(alpha, new CloneProjectScreensRequest(true, "ABC",
                ScreenCloneScope.SCHEME_ONLY, null, false));

        assertFalse(plan.separationNeeded);
        assertTrue(plan.screenSchemes.isEmpty());
        assertTrue(plan.screens.isEmpty());
    }

    @Test
    public void privateSchemesAndScreensAreKeptNotCopiedWhenNothingIsShared() {
        when(topManager.getProjects(top)).thenReturn(Collections.singletonList(alphaValue));

        ScreenClonePlan plan = planner.plan(alpha, new CloneProjectScreensRequest(true, "ABC",
                ScreenCloneScope.ALL, null, true));

        assertFalse(plan.separationNeeded);
        assertTrue(plan.screenSchemes.isEmpty());
        assertTrue(plan.screens.isEmpty());
        assertEquals(ru.rasulov.screens.api.dto.CloneProjectScreensResponse.Verdict.KEEP,
                plan.screenSchemeViews.get(0).getVerdict());
        org.junit.Assert.assertNull(plan.screenSchemeViews.get(0).getNewName());
        assertEquals(ru.rasulov.screens.api.dto.CloneProjectScreensResponse.Verdict.KEEP,
                plan.screenViews.get(0).getVerdict());
        org.junit.Assert.assertNull(plan.screenViews.get(0).getNewName());
    }

    @Test
    public void privateTopKeepsItsIdentityWhenChildrenAreSharedThroughAnotherTop() {
        IssueTypeScreenScheme otherTop = mock(IssueTypeScreenScheme.class);
        when(topManager.getProjects(top)).thenReturn(Collections.singletonList(alphaValue));
        when(topManager.getProjects(otherTop)).thenReturn(Collections.singletonList(betaValue));
        when(topManager.getIssueTypeScreenSchemes(fieldScheme)).thenReturn(Arrays.asList(top, otherTop));

        ScreenClonePlan plan = planner.plan(alpha, new CloneProjectScreensRequest(true, "ABC",
                ScreenCloneScope.ALL, null, true));

        assertTrue(plan.separationNeeded);
        assertEquals(ru.rasulov.screens.api.dto.CloneProjectScreensResponse.Verdict.KEEP,
                plan.schemeView.getVerdict());
        assertEquals(0, plan.schemeView.getOtherProjects());
        org.junit.Assert.assertNull(plan.newSchemeName);
        assertEquals(1, plan.screenSchemeViews.get(0).getOtherProjects());
        assertEquals(1, plan.screenViews.get(0).getOtherProjects());
        assertTrue(plan.screenSchemes.containsKey(fieldScheme));
        assertTrue(plan.screens.containsKey(screen));
    }

    @Test
    public void defaultTopMustBeCopiedEvenWithOnlyOneCurrentProject() {
        when(topManager.getProjects(top)).thenReturn(Collections.singletonList(alphaValue));
        when(top.isDefault()).thenReturn(true);
        ScreenClonePlan plan = planner.plan(alpha, new CloneProjectScreensRequest(true, "ABC",
                ScreenCloneScope.SCHEME_ONLY, null, false));
        assertTrue(plan.separationNeeded);
        assertEquals(ru.rasulov.screens.api.dto.CloneProjectScreensResponse.Verdict.COPY,
                plan.schemeView.getVerdict());
    }

    @Test
    public void archivedAssociationStillPreventsUpdatingTheTopInPlace() {
        when(beta.isArchived()).thenReturn(true);
        ScreenClonePlan plan = planner.plan(alpha, new CloneProjectScreensRequest(true, "ABC",
                ScreenCloneScope.SCHEME_ONLY, null, false));
        assertTrue(plan.separationNeeded);
        assertEquals(0, plan.schemeView.getOtherProjects());
        assertEquals(ru.rasulov.screens.api.dto.CloneProjectScreensResponse.Verdict.COPY,
                plan.schemeView.getVerdict());
    }

    private static Project project(Long id, String key, String name) {
        Project value = mock(Project.class); when(value.getId()).thenReturn(id); when(value.getKey()).thenReturn(key);
        when(value.getName()).thenReturn(name); return value;
    }
    private static IssueType type(String id, String name) {
        IssueType value = mock(IssueType.class); when(value.getId()).thenReturn(id); when(value.getName()).thenReturn(name);
        return value;
    }
    private static GenericValue projectValue(Long id) {
        GenericValue value = mock(GenericValue.class); when(value.getLong("id")).thenReturn(id); return value;
    }
}
