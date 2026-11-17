package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
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
import ru.rasulov.common.api.dto.IssueTypeRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RequiredOnCreateTest {
    private static final String FIELD = "customfield_10000";
    private final ProjectManager projects = mock(ProjectManager.class);
    private final FieldScreenSchemeManager schemes = mock(FieldScreenSchemeManager.class);
    private final IssueTypeScreenSchemeManager tops = mock(IssueTypeScreenSchemeManager.class);
    private final FieldLayoutManager layouts = mock(FieldLayoutManager.class);
    private final FieldScreen screen = screen(100L);
    private final FieldScreen other = screen(200L);
    private final FieldScreenScheme candidate = scheme(item(null, screen));
    private final IssueTypeScreenScheme top = mock(IssueTypeScreenScheme.class);
    private final List<GenericValue> associations = new ArrayList<>();

    public RequiredOnCreateTest() {
        when(schemes.getFieldScreenSchemes(screen)).thenReturn(Collections.singletonList(candidate));
        when(tops.getIssueTypeScreenSchemes(candidate)).thenReturn(Collections.singletonList(top));
        when(tops.getProjects(top)).thenReturn(associations);
    }

    @Test
    public void checksOnlyMandatoryPairsWhoseEffectiveCreateScreenMatches() {
        IssueType mandatory = type("1"), optional = type("2"), edit = type("3"), view = type("4");
        Project project = project(1L, "ONE", view, optional, edit, mandatory);
        when(top.getEffectiveFieldScreenScheme(mandatory)).thenReturn(candidate);
        when(top.getEffectiveFieldScreenScheme(optional)).thenReturn(candidate);
        doReturn(scheme(item(null, other), item(IssueOperations.EDIT_ISSUE_OPERATION, screen))).when(top).getEffectiveFieldScreenScheme(edit);
        doReturn(scheme(item(null, other), item(IssueOperations.VIEW_ISSUE_OPERATION, screen))).when(top).getEffectiveFieldScreenScheme(view);
        FieldConfigurationScheme configuration = configuration(project);
        when(configuration.getFieldLayoutId("1")).thenReturn(10L);
        when(configuration.getFieldLayoutId("2")).thenReturn(20L);
        layout(10L, FIELD, true); layout(20L, FIELD, false);

        RequiredOnCreate.Session session = session();
        assertTrue(session.usedOnCreate(screen));
        RequiredOnCreate.Result result = session.check(screen, FIELD);

        assertEquals(1, result.getPairs());
        assertEquals(1, result.getProjects().size());
        assertEquals("ONE", result.getProjects().get(0).getProjectKey());
        assertEquals("Project ONE", result.getProjects().get(0).getProjectName());
        assertEquals("1", result.getProjects().get(0).getIssueTypes().get(0).getId());
        assertEquals("Type 1", result.getProjects().get(0).getIssueTypes().get(0).getName());
        assertEquals(1, result.getProjects().get(0).getIssueTypes().size());
        assertFalse(result.isTruncated());
        verify(configuration, never()).getFieldLayoutId("3");
        verify(configuration, never()).getFieldLayoutId("4");
    }

    @Test
    public void explicitCreateOverrideExcludesDefaultScreenAndUnusedIssueTypes() {
        IssueType current = type("current"), stale = type("removed");
        project(1L, "ONE", current);
        doReturn(scheme(item(null, screen), item(IssueOperations.CREATE_ISSUE_OPERATION, other))).when(top).getEffectiveFieldScreenScheme(current);
        when(top.getEffectiveFieldScreenScheme(stale)).thenReturn(candidate);

        RequiredOnCreate.Session session = session();
        assertFalse(session.usedOnCreate(screen));
        assertEquals(0, session.check(screen, FIELD).getPairs());
        verifyNoInteractions(layouts);
        verify(top, never()).getEffectiveFieldScreenScheme(stale);
    }

    @Test
    public void effectiveConfigurationMappingHandlesExplicitAndDefaultLayouts() {
        IssueType inherited = type("inherited"), explicit = type("explicit");
        Project project = project(1L, "ONE", inherited, explicit);
        when(top.getEffectiveFieldScreenScheme(inherited)).thenReturn(candidate);
        when(top.getEffectiveFieldScreenScheme(explicit)).thenReturn(candidate);
        FieldConfigurationScheme configuration = configuration(project);
        // getFieldLayoutId is Jira's effective API: its result already includes the default mapping.
        when(configuration.getFieldLayoutId("inherited")).thenReturn(10L);
        when(configuration.getFieldLayoutId("explicit")).thenReturn(20L);
        layout(10L, FIELD, true); layout(20L, FIELD, false);

        RequiredOnCreate.Result result = session().check(screen, FIELD);

        assertEquals(1, result.getPairs());
        assertEquals("inherited", result.getProjects().get(0).getIssueTypes().get(0).getId());
        verify(layouts).getFieldLayout(10L);
        verify(layouts).getFieldLayout(20L);
        verify(layouts, never()).getFieldLayout();
    }

    @Test
    public void cachesDistinctLayoutsAcrossProjectsFieldsAndScreensWithinOneSession() {
        IssueType first = type("1"), second = type("2");
        Project a = project(1L, "AAA", first, second), b = project(2L, "BBB", first);
        FieldScreenScheme mixed = scheme(item(null, screen), item(IssueOperations.CREATE_ISSUE_OPERATION, other));
        when(top.getEffectiveFieldScreenScheme(first)).thenReturn(candidate);
        when(top.getEffectiveFieldScreenScheme(second)).thenReturn(mixed);
        when(schemes.getFieldScreenSchemes(other)).thenReturn(Collections.singletonList(mixed));
        when(tops.getIssueTypeScreenSchemes(mixed)).thenReturn(Collections.singletonList(top));
        FieldConfigurationScheme ca = configuration(a), cb = configuration(b);
        when(ca.getFieldLayoutId("1")).thenReturn(10L); when(ca.getFieldLayoutId("2")).thenReturn(10L);
        when(cb.getFieldLayoutId("1")).thenReturn(10L);
        FieldLayout layout = layout(10L, FIELD, true);
        FieldLayoutItem optional = mock(FieldLayoutItem.class);
        when(layout.getFieldLayoutItem("customfield_10001")).thenReturn(optional);

        RequiredOnCreate.Session session = session();
        assertEquals(2, session.check(screen, FIELD).getPairs());
        assertEquals(1, session.check(other, FIELD).getPairs());
        assertEquals(0, session.check(screen, "customfield_10001").getPairs());
        assertEquals(0, session.check(other, "customfield_10001").getPairs());
        verify(layouts, times(1)).getFieldLayout(10L);
        verify(layouts, times(1)).getFieldConfigurationScheme(a);
        verify(layouts, times(1)).getFieldConfigurationScheme(b);
        verify(layout, times(1)).getFieldLayoutItem(FIELD);
        verify(layout, times(1)).getFieldLayoutItem("customfield_10001");
        assertEquals(2, session().check(screen, FIELD).getPairs());
        verify(layouts, times(2)).getFieldLayout(10L);
    }

    @Test
    public void cachesDefaultLayoutForMissingSchemeAndNullEffectiveMapping() {
        IssueType type = type("1");
        project(1L, "AAA", type);
        Project b = project(2L, "BBB", type);
        when(top.getEffectiveFieldScreenScheme(type)).thenReturn(candidate);
        // A configured default mapping may also have a null layout id.
        when(configuration(b).getFieldLayoutId("1")).thenReturn(null);
        FieldLayout layout = mock(FieldLayout.class);
        FieldLayoutItem required = mock(FieldLayoutItem.class);
        when(required.isRequired()).thenReturn(true);
        when(layout.getFieldLayoutItem(FIELD)).thenReturn(required);
        when(layouts.getFieldLayout()).thenReturn(layout);

        RequiredOnCreate.Session session = session();
        assertEquals(2, session.check(screen, FIELD).getPairs());
        assertEquals(2, session.check(screen, FIELD).getPairs());
        verify(layouts, times(1)).getFieldLayout();
        verify(layout, times(1)).getFieldLayoutItem(FIELD);
    }

    @Test
    public void groupsEveryMandatoryPairByProjectAndTruncatesBeyondTheProjectLimit() {
        IssueType first = type("1"), second = type("2");
        int count = RequiredOnCreate.MAX_PROJECTS + 2;
        for (int i = count; i >= 1; i--) project(i, String.format("P%02d", i), second, first);
        when(top.getEffectiveFieldScreenScheme(first)).thenReturn(candidate);
        when(top.getEffectiveFieldScreenScheme(second)).thenReturn(candidate);
        FieldLayout layout = mock(FieldLayout.class);
        FieldLayoutItem item = mock(FieldLayoutItem.class);
        when(item.isRequired()).thenReturn(true);
        when(layout.getFieldLayoutItem(FIELD)).thenReturn(item);
        when(layouts.getFieldLayout()).thenReturn(layout);

        RequiredOnCreate.Result result = session().check(screen, FIELD);

        assertEquals(count * 2, result.getPairs());
        assertTrue(result.isTruncated());
        assertEquals(RequiredOnCreate.MAX_PROJECTS, result.getProjects().size());
        assertEquals("P01", result.getProjects().get(0).getProjectKey());
        assertEquals(List.of("1", "2"), result.getProjects().get(0).getIssueTypes().stream().map(IssueTypeRef::getId).toList());
        assertEquals(String.format("P%02d", RequiredOnCreate.MAX_PROJECTS),
                result.getProjects().get(RequiredOnCreate.MAX_PROJECTS - 1).getProjectKey());
        verify(layouts, times(1)).getFieldLayout();
    }

    @Test
    public void noProjectsOrNoCurrentIssueTypesNeedNoFieldConfigurationReads() {
        RequiredOnCreate.Session session = session();
        assertFalse(session.usedOnCreate(screen));
        assertEquals(0, session.check(screen, FIELD).getPairs());
        assertTrue(session.check(screen, FIELD).getProjects().isEmpty());
        project(1L, "EMPTY");
        assertEquals(0, session().check(screen, FIELD).getPairs());
        verifyNoInteractions(layouts);
    }

    @Test
    public void unreadableEffectiveConfigurationFailsClosed() {
        IssueType type = type("1");
        Project project = project(1L, "ONE", type);
        when(top.getEffectiveFieldScreenScheme(type)).thenReturn(candidate);
        when(configuration(project).getFieldLayoutId("1")).thenReturn(10L);
        expectFailure(() -> session().check(screen, FIELD), "Could not read effective field configuration 10");
        FieldLayout layout = mock(FieldLayout.class);
        when(layouts.getFieldLayout(10L)).thenReturn(layout);
        expectFailure(() -> session().check(screen, FIELD), "absent from field configuration 10");
    }

    @Test
    public void missingDefaultLayoutAndScreenConfigurationFailClosed() {
        IssueType type = type("1");
        Project project = project(1L, "ONE", type);
        when(top.getEffectiveFieldScreenScheme(type)).thenReturn(candidate);
        expectFailure(() -> session().check(screen, FIELD), "Could not read effective field configuration null");
        when(tops.getIssueTypeScreenScheme(project)).thenReturn(null);
        expectFailure(() -> session().check(screen, FIELD), "No issue type screen scheme");
        when(tops.getIssueTypeScreenScheme(project)).thenReturn(top);
        when(top.getEffectiveFieldScreenScheme(type)).thenReturn(null);
        expectFailure(() -> session().check(screen, FIELD), "No effective screen scheme");
        doReturn(scheme()).when(top).getEffectiveFieldScreenScheme(type);
        expectFailure(() -> session().check(screen, FIELD), "No default screen for CREATE");
    }

    private RequiredOnCreate.Session session() { return new RequiredOnCreate(projects, schemes, tops, layouts).session(); }
    private Project project(long id, String key, IssueType... types) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id); when(project.getKey()).thenReturn(key);
        when(project.getName()).thenReturn("Project " + key); when(project.getIssueTypes()).thenReturn(Arrays.asList(types));
        GenericValue value = mock(GenericValue.class); when(value.getLong("id")).thenReturn(id);
        associations.add(value); when(projects.getProjectObj(id)).thenReturn(project);
        when(tops.getIssueTypeScreenScheme(project)).thenReturn(top);
        return project;
    }
    private FieldConfigurationScheme configuration(Project project) {
        FieldConfigurationScheme configuration = mock(FieldConfigurationScheme.class);
        when(layouts.getFieldConfigurationScheme(project)).thenReturn(configuration); return configuration;
    }
    private FieldLayout layout(long id, String field, boolean required) {
        FieldLayout layout = mock(FieldLayout.class); FieldLayoutItem item = mock(FieldLayoutItem.class);
        when(item.isRequired()).thenReturn(required); when(layout.getFieldLayoutItem(field)).thenReturn(item);
        when(layouts.getFieldLayout(id)).thenReturn(layout); return layout;
    }
    private static IssueType type(String id) {
        IssueType type = mock(IssueType.class); when(type.getId()).thenReturn(id); when(type.getName()).thenReturn("Type " + id); return type;
    }
    private static FieldScreen screen(long id) {
        FieldScreen screen = mock(FieldScreen.class); when(screen.getId()).thenReturn(id); return screen;
    }
    private static FieldScreenScheme scheme(FieldScreenSchemeItem... items) {
        FieldScreenScheme scheme = mock(FieldScreenScheme.class); when(scheme.getFieldScreenSchemeItems()).thenReturn(Arrays.asList(items)); return scheme;
    }
    private static FieldScreenSchemeItem item(ScreenableIssueOperation operation, FieldScreen screen) {
        FieldScreenSchemeItem item = mock(FieldScreenSchemeItem.class);
        when(item.getIssueOperation()).thenReturn(operation); when(item.getFieldScreen()).thenReturn(screen); return item;
    }
    private static void expectFailure(Runnable action, String message) {
        try { action.run(); fail("Unreadable configuration must not be treated as optional"); }
        catch (IllegalStateException e) { assertTrue(e.getMessage(), e.getMessage().contains(message)); }
    }
}
