package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.jira.project.Project;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FieldConfigurationClonePlannerTest {
    @Test
    public void sharedSchemeAndRepeatedMappingsProduceOnePrivateCopyPerConfiguration() {
        FieldLayoutManager manager = mock(FieldLayoutManager.class);
        Project selected = project(1L, "PAY");
        FieldConfigurationScheme sourceView = mock(FieldConfigurationScheme.class);
        FieldLayoutScheme source = mock(FieldLayoutScheme.class);
        FieldLayoutSchemeEntity defaultMapping = mapping(null, 10L);
        FieldLayoutSchemeEntity bugMapping = mapping("10001", 10L);
        FieldLayout layout = layout(10L, "Shared fields");
        Collection<Project> sharedProjects = projects(selected, 9);

        when(manager.getFieldConfigurationScheme(selected)).thenReturn(sourceView);
        when(sourceView.getId()).thenReturn(20L);
        when(sourceView.getName()).thenReturn("Shared scheme");
        when(sourceView.getDescription()).thenReturn("scheme description");
        when(manager.getMutableFieldLayoutScheme(20L)).thenReturn(source);
        when(source.getEntities()).thenReturn(Arrays.asList(defaultMapping, bugMapping));
        when(manager.getFieldLayout(10L)).thenReturn(layout);
        when(manager.getEditableFieldLayouts()).thenReturn(Collections.emptyList());
        when(manager.getFieldLayoutSchemes()).thenReturn(Collections.emptyList());
        when(manager.getProjectsUsing(sourceView)).thenReturn(sharedProjects);
        when(manager.getProjectsUsing(layout)).thenReturn(sharedProjects);

        FieldConfigurationClonePlan plan = new FieldConfigurationClonePlanner(manager).plan(selected);

        assertTrue(plan.separationNeeded);
        assertEquals("PAY: Shared scheme", plan.newSchemeName);
        assertEquals(2, plan.mappings.size());
        assertEquals(1, plan.layouts.size());
        assertEquals(9, plan.schemeView.getOtherProjectsTotal());
        assertEquals(9, plan.layoutViews.get(0).getOtherProjectsTotal());
        assertEquals(9, plan.layoutViews.get(0).getOtherProjects().size());
    }

    @Test
    public void setupUsedOnlyByTheSelectedProjectNeedsNoCopy() {
        FieldLayoutManager manager = mock(FieldLayoutManager.class);
        Project selected = project(1L, "PAY");
        FieldConfigurationScheme sourceView = mock(FieldConfigurationScheme.class);
        FieldLayoutScheme source = mock(FieldLayoutScheme.class);
        FieldLayout layout = layout(10L, "PAY: Fields");
        FieldLayoutSchemeEntity defaultMapping = mapping(null, 10L);
        when(manager.getFieldConfigurationScheme(selected)).thenReturn(sourceView);
        when(sourceView.getId()).thenReturn(20L);
        when(sourceView.getName()).thenReturn("PAY: Scheme");
        when(manager.getMutableFieldLayoutScheme(20L)).thenReturn(source);
        when(source.getEntities()).thenReturn(Collections.singletonList(defaultMapping));
        when(manager.getFieldLayout(10L)).thenReturn(layout);
        when(manager.getEditableFieldLayouts()).thenReturn(Collections.emptyList());
        when(manager.getFieldLayoutSchemes()).thenReturn(Collections.emptyList());
        when(manager.getProjectsUsing(sourceView)).thenReturn(Collections.singletonList(selected));
        when(manager.getProjectsUsing(layout)).thenReturn(Collections.singletonList(selected));

        FieldConfigurationClonePlan plan = new FieldConfigurationClonePlanner(manager).plan(selected);

        assertFalse(plan.separationNeeded);
        assertTrue("nothing may be planned as a copy", plan.layouts.isEmpty());
        assertNull(plan.schemeView.getNewName());
        assertEquals(1, plan.layoutViews.size());
        assertNull(plan.layoutViews.get(0).getNewName());
    }

    @Test
    public void defaultSetupBecomesAnExplicitSchemeAndConfigurationCopy() {
        FieldLayoutManager manager = mock(FieldLayoutManager.class);
        Project selected = project(1L, "PAY");
        FieldLayout defaultLayout = layout(null, "Default Field Configuration");
        Collection<Project> sharedProjects = projects(selected, 2);
        when(defaultLayout.isDefault()).thenReturn(true);
        when(manager.getFieldLayout()).thenReturn(defaultLayout);
        when(manager.getEditableFieldLayouts()).thenReturn(Collections.emptyList());
        when(manager.getFieldLayoutSchemes()).thenReturn(Collections.emptyList());
        when(manager.getProjectsUsing(defaultLayout)).thenReturn(sharedProjects);

        FieldConfigurationClonePlan plan = new FieldConfigurationClonePlanner(manager).plan(selected);

        assertTrue(plan.separationNeeded);
        assertNull(plan.sourceSchemeId);
        assertEquals("PAY: Default Field Configuration Scheme", plan.newSchemeName);
        assertEquals(1, plan.mappings.size());
        assertNull(plan.mappings.get(0).issueTypeId);
        assertEquals("PAY: Default Field Configuration", plan.layouts.get(defaultLayout));
    }

    private static FieldLayoutSchemeEntity mapping(String typeId, Long layoutId) {
        FieldLayoutSchemeEntity entity = mock(FieldLayoutSchemeEntity.class);
        when(entity.getIssueTypeId()).thenReturn(typeId);
        when(entity.getFieldLayoutId()).thenReturn(layoutId);
        return entity;
    }

    private static FieldLayout layout(Long id, String name) {
        FieldLayout layout = mock(FieldLayout.class);
        when(layout.getId()).thenReturn(id);
        when(layout.getName()).thenReturn(name);
        return layout;
    }

    private static Project project(Long id, String key) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        when(project.getKey()).thenReturn(key);
        when(project.getName()).thenReturn(key + " project");
        return project;
    }

    private static Collection<Project> projects(Project selected, int others) {
        Collection<Project> result = new ArrayList<Project>();
        result.add(selected);
        for (int i = 0; i < others; i++) result.add(project(100L + i, "P" + i));
        return result;
    }
}
