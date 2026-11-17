package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.issue.operation.ScreenableIssueOperation;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.screen.EffectiveScreens;
import ru.rasulov.screens.api.dto.layout.ScreenImpact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ScreenLayoutImpactReaderTest {
    private final ProjectManager projects = mock(ProjectManager.class);
    private final FieldScreenSchemeManager schemes = mock(FieldScreenSchemeManager.class);
    private final IssueTypeScreenSchemeManager tops = mock(IssueTypeScreenSchemeManager.class);
    private final FieldScreen screen = screen(100L);
    private final FieldScreen other = screen(200L);
    private final FieldScreenScheme candidate = scheme(item(null, screen));
    private final IssueTypeScreenScheme top = mock(IssueTypeScreenScheme.class);
    private final List<GenericValue> associations = new ArrayList<>();

    public ScreenLayoutImpactReaderTest() {
        when(schemes.getFieldScreenSchemes(screen)).thenReturn(Collections.singletonList(candidate));
        when(tops.getIssueTypeScreenSchemes(candidate)).thenReturn(Collections.singletonList(top));
        when(tops.getProjects(top)).thenReturn(associations);
    }

    @Test
    public void defaultOperationServesAllThreeOperations() {
        IssueType type = type("task"); project(1L, "ONE", type);
        when(top.getEffectiveFieldScreenScheme(type)).thenReturn(candidate);

        ScreenImpact result = reader().read(screen);

        assertEquals(Long.valueOf(100L), result.getScreenId());
        assertEquals("Screen 100", result.getName());
        assertEquals(Arrays.asList("CREATE", "EDIT", "VIEW"), result.getOperations());
        assertEquals(1, result.getProjects());
        assertEquals("ONE", result.getProjectNames().get(0).getKey());
        assertEquals("Project ONE", result.getProjectNames().get(0).getName());
        assertFalse(result.isDefaultScreen());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    public void explicitOperationOverridesDefaultRegardlessOfItemOrder() {
        IssueType type = type("task"); project(1L, "ONE", type);
        doReturn(scheme(item(IssueOperations.CREATE_ISSUE_OPERATION, other), item(null, screen),
                item(IssueOperations.VIEW_ISSUE_OPERATION, other))).when(top).getEffectiveFieldScreenScheme(type);

        ScreenImpact result = reader().read(screen);

        assertEquals(Collections.singletonList("EDIT"), result.getOperations());
        assertEquals(1, result.getProjects());
        assertFalse(EffectiveScreens.uses(screen, scheme(item(null, screen), item(IssueOperations.CREATE_ISSUE_OPERATION, other)), "CREATE"));
        assertTrue(EffectiveScreens.uses(screen, scheme(item(null, other), item(IssueOperations.CREATE_ISSUE_OPERATION, screen)), "CREATE"));
    }

    @Test
    public void reverseAssociationsDoNotCountUnusedTypesOrNonmatchingOperations() {
        IssueType current = type("current"), removed = type("removed");
        project(1L, "ONE", current);
        doReturn(scheme(item(null, other))).when(top).getEffectiveFieldScreenScheme(current);
        when(top.getEffectiveFieldScreenScheme(removed)).thenReturn(candidate);

        ScreenImpact result = reader().read(screen);

        assertEquals(0, result.getProjects());
        assertTrue(result.getProjectNames().isEmpty());
        assertTrue(result.getOperations().isEmpty());
        assertTrue(result.getWarnings().isEmpty());
        verify(top, never()).getEffectiveFieldScreenScheme(removed);
    }

    @Test
    public void sharedWarningReportsTotalAndTenSortedNamesWithoutInventingOutsideScope() {
        IssueType create = type("create"), edit = type("edit"), view = type("view");
        for (int i = 12; i >= 1; i--) project(i, String.format("P%02d", i), view, edit, create);
        Project archived = project(13L, "ARCHIVED", create); when(archived.isArchived()).thenReturn(true);
        project(14L, "EMPTY");
        // Duplicate reverse references must not inflate the project total.
        associations.add(associations.get(0));
        doReturn(scheme(item(null, other), item(IssueOperations.CREATE_ISSUE_OPERATION, screen))).when(top).getEffectiveFieldScreenScheme(create);
        doReturn(scheme(item(null, other), item(IssueOperations.EDIT_ISSUE_OPERATION, screen))).when(top).getEffectiveFieldScreenScheme(edit);
        doReturn(scheme(item(null, other), item(IssueOperations.VIEW_ISSUE_OPERATION, screen))).when(top).getEffectiveFieldScreenScheme(view);

        ScreenImpact result = reader().read(screen);

        assertEquals(Arrays.asList("CREATE", "EDIT", "VIEW"), result.getOperations());
        assertEquals(12, result.getProjects());
        assertEquals(10, result.getProjectNames().size());
        assertEquals("P01", result.getProjectNames().get(0).getKey());
        assertEquals("P10", result.getProjectNames().get(9).getKey());
        assertEquals(1, result.getWarnings().size());
        assertEquals("SHARED_SCREEN", result.getWarnings().get(0).getCode());
        String message = result.getWarnings().get(0).getMessage();
        assertTrue(message, message.contains("12 projects"));
        assertTrue(message, message.contains("No project scope was supplied"));
        assertFalse(message, message.contains("outside"));
        verify(tops, never()).getIssueTypeScreenScheme(archived);
    }

    @Test
    public void defaultAndSharedWarningsAreIndependentAndStable() {
        when(screen.getId()).thenReturn(FieldScreen.DEFAULT_SCREEN_ID);
        IssueType type = type("task"); project(1L, "ONE", type); project(2L, "TWO", type);
        when(top.getEffectiveFieldScreenScheme(type)).thenReturn(candidate);

        ScreenImpact result = reader().read(screen);

        assertTrue(result.isDefaultScreen());
        assertEquals(2, result.getWarnings().size());
        assertEquals("SHARED_SCREEN", result.getWarnings().get(0).getCode());
        assertEquals("DEFAULT_SCREEN", result.getWarnings().get(1).getCode());
        assertTrue(result.getWarnings().get(1).getMessage().contains("default screen"));
    }

    @Test
    public void unassociatedAndEmptyProjectsHaveNoEffectiveOperations() {
        ScreenImpact result = reader().read(screen);
        assertEquals(0, result.getProjects());
        assertTrue(result.getProjectNames().isEmpty());
        assertTrue(result.getOperations().isEmpty());
        assertTrue(result.getWarnings().isEmpty());
        project(1L, "EMPTY");
        result = reader().read(screen);
        assertEquals(0, result.getProjects());
        assertTrue(result.getOperations().isEmpty());
        when(screen.getId()).thenReturn(FieldScreen.DEFAULT_SCREEN_ID);
        result = reader().read(screen);
        assertEquals(0, result.getProjects());
        assertTrue(result.isDefaultScreen());
        assertEquals(1, result.getWarnings().size());
        assertEquals("DEFAULT_SCREEN", result.getWarnings().get(0).getCode());
    }

    @Test
    public void unreadableEffectiveScreenConfigurationIsNotReportedAsUnused() {
        IssueType type = type("task"); Project project = project(1L, "ONE", type);
        expectFailure(() -> reader().read(screen), "No effective screen scheme");
        doReturn(scheme()).when(top).getEffectiveFieldScreenScheme(type);
        expectFailure(() -> reader().read(screen), "No default screen for CREATE");
        doReturn(scheme(item(null, screen), item(IssueOperations.CREATE_ISSUE_OPERATION, null))).when(top).getEffectiveFieldScreenScheme(type);
        expectFailure(() -> reader().read(screen), "Could not read CREATE screen");
        when(tops.getIssueTypeScreenScheme(project)).thenReturn(null);
        expectFailure(() -> reader().read(screen), "Project has no issue type screen scheme");
    }

    private ScreenLayoutImpactReader reader() { return new ScreenLayoutImpactReader(projects, schemes, tops); }
    private Project project(long id, String key, IssueType... types) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id); when(project.getKey()).thenReturn(key);
        when(project.getName()).thenReturn("Project " + key); when(project.getIssueTypes()).thenReturn(Arrays.asList(types));
        GenericValue value = mock(GenericValue.class); when(value.getLong("id")).thenReturn(id);
        associations.add(value); when(projects.getProjectObj(id)).thenReturn(project);
        when(tops.getIssueTypeScreenScheme(project)).thenReturn(top); return project;
    }
    private static IssueType type(String id) {
        IssueType type = mock(IssueType.class); when(type.getId()).thenReturn(id); return type;
    }
    private static FieldScreen screen(long id) {
        FieldScreen screen = mock(FieldScreen.class); when(screen.getId()).thenReturn(id);
        when(screen.getName()).thenReturn("Screen " + id); return screen;
    }
    private static FieldScreenScheme scheme(FieldScreenSchemeItem... items) {
        FieldScreenScheme scheme = mock(FieldScreenScheme.class); when(scheme.getFieldScreenSchemeItems()).thenReturn(Arrays.asList(items)); return scheme;
    }
    private static FieldScreenSchemeItem item(ScreenableIssueOperation operation, FieldScreen screen) {
        FieldScreenSchemeItem item = mock(FieldScreenSchemeItem.class);
        when(item.getIssueOperation()).thenReturn(operation); when(item.getFieldScreen()).thenReturn(screen); return item;
    }
    private static void expectFailure(Runnable action, String message) {
        try { action.run(); fail("Unreadable effective screens must fail closed"); }
        catch (IllegalStateException e) { assertTrue(e.getMessage(), e.getMessage().contains(message)); }
    }
}
