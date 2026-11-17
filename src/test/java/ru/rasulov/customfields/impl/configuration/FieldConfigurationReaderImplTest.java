package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.ProjectFieldConfigurationsResponse;
import ru.rasulov.customfields.impl.context.FieldSelector;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Effective mappings, DEFAULT rows, foreign rows, sharing and per-configuration field flags of one project. */
public class FieldConfigurationReaderImplTest {
    private FieldLayoutManager layouts;
    private FieldManager systemFields;
    private CustomFieldManager customFields;
    private FieldConfigurationReaderImpl reader;
    private Project project;
    private IssueType task;
    private IssueType bug;
    private FieldLayout defaultLayout;

    @Before
    public void setUp() {
        layouts = mock(FieldLayoutManager.class);
        systemFields = mock(FieldManager.class);
        customFields = mock(CustomFieldManager.class);
        ProjectResolver projects = mock(ProjectResolver.class);
        project = project(1L, "PAY");
        task = type("1", "Task");
        bug = type("2", "Bug");
        when(project.getIssueTypes()).thenReturn(Arrays.asList(task, bug));
        when(projects.resolve("PAY")).thenReturn(project);
        defaultLayout = layout(null, "Default Field Configuration", true);
        when(layouts.getFieldLayout()).thenReturn(defaultLayout);
        reader = new FieldConfigurationReaderImpl(mock(AdministratorGuard.class), projects,
                new ConfigurableFieldResolver(new FieldSelector(customFields), systemFields),
                new FieldConfigurationCatalog(layouts), layouts, mock(FieldConfigurationSearcher.class));
    }

    @Test
    public void explicitSchemeShowsEffectiveMappingsDefaultRowForeignRowsAndSharing() {
        com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout taskLayout = editable(10L, "Task fields");
        FieldConfigurationScheme assigned = mock(FieldConfigurationScheme.class);
        when(assigned.getId()).thenReturn(20L);
        when(assigned.getName()).thenReturn("PAY scheme");
        when(layouts.getFieldConfigurationScheme(project)).thenReturn(assigned);
        FieldLayoutScheme scheme = mock(FieldLayoutScheme.class);
        when(layouts.getMutableFieldLayoutScheme(20L)).thenReturn(scheme);
        IssueType epic = type("3", "Epic");
        FieldLayoutSchemeEntity taskRow = entity("1", 10L, task);
        FieldLayoutSchemeEntity defaultRow = entity(null, null, null);
        FieldLayoutSchemeEntity epicRow = entity("3", 10L, epic);
        when(layouts.getFieldLayoutSchemeEntities(scheme)).thenReturn(Arrays.asList(defaultRow, taskRow, epicRow));
        when(layouts.getEditableFieldLayouts()).thenReturn(Collections.singletonList(taskLayout));
        when(layouts.getFieldLayout(project, "1")).thenReturn(taskLayout);
        when(layouts.getFieldLayout(project, "2")).thenReturn(defaultLayout);
        Project other = project(2L, "OPS");
        when(layouts.getProjectsUsing(assigned)).thenReturn(Arrays.asList(project, other));
        when(layouts.getProjectsUsing(taskLayout)).thenReturn(Arrays.asList(project, other));

        ProjectFieldConfigurationsResponse response = reader.read("PAY", null);

        assertEquals(ProjectFieldConfigurationsResponse.Status.OK, response.getStatus());
        assertEquals(Long.valueOf(20L), response.getScheme().getId());
        assertEquals(1, response.getSchemeOtherProjects().getTotal());
        assertEquals("OPS", response.getSchemeOtherProjects().getProjects().get(0).getKey());
        assertTrue(response.getDefaultMapping().isDefaultConfiguration());
        assertEquals("Bug", response.getMappings().get(0).getIssueTypeName());
        assertFalse("Bug falls to the DEFAULT row", response.getMappings().get(0).isExplicit());
        assertEquals("Task", response.getMappings().get(1).getIssueTypeName());
        assertTrue(response.getMappings().get(1).isExplicit());
        assertEquals(Long.valueOf(10L), response.getMappings().get(1).getFieldConfiguration().getId());
        assertEquals(1, response.getOtherEntities().size());
        assertEquals("Epic", response.getOtherEntities().get(0).getIssueTypeName());
        assertEquals(2, response.getFieldConfigurations().size());
        assertEquals(1, response.getFieldConfigurations().get(1).getOtherProjects().getTotal());
    }

    @Test
    public void defaultSchemeProjectReportsFlagsOfSystemAndLockedFields() {
        when(layouts.getFieldLayout(project, "1")).thenReturn(defaultLayout);
        when(layouts.getFieldLayout(project, "2")).thenReturn(defaultLayout);
        OrderableField dueDate = system("duedate", "Due Date");
        OrderableField summary = system("summary", "Summary");
        when(systemFields.isMandatoryField(summary)).thenReturn(true);
        item(defaultLayout, dueDate, true, false);
        item(defaultLayout, summary, true, false);

        ProjectFieldConfigurationsResponse response = reader.read("PAY", Arrays.asList("duedate", "summary"));

        assertTrue(response.getScheme().isDefaultScheme());
        assertNull(response.getScheme().getId());
        assertNull(response.getSchemeOtherProjects());
        assertEquals(1, response.getFieldConfigurations().size());
        assertEquals("duedate", response.getFields().get(0).getField().getId());
        assertFalse(response.getFields().get(0).isLocked());
        assertTrue(response.getFields().get(0).getConfigurations().get(0).isRequired());
        assertTrue(response.getFields().get(1).isLocked());
    }

    @Test
    public void tooManyFieldsAreRefusedBeforeReadingJira() {
        ProjectFieldConfigurationsResponse response = reader.read("PAY", Collections.nCopies(51, "duedate"));

        assertEquals(ErrorCode.VALIDATION_ERROR, response.getErrorCode());
    }

    @Test
    public void unknownFieldIsReported() {
        when(customFields.getCustomFieldObjectsByNameIgnoreCase("Nope")).thenReturn(Collections.emptyList());

        ProjectFieldConfigurationsResponse response = reader.read("PAY", Collections.singletonList("Nope"));

        assertEquals(ProjectFieldConfigurationsResponse.Status.FAILED, response.getStatus());
        assertEquals(ErrorCode.UNKNOWN_FIELD, response.getErrorCode());
    }

    private OrderableField system(String id, String name) {
        OrderableField field = mock(OrderableField.class);
        when(field.getId()).thenReturn(id);
        when(field.getName()).thenReturn(name);
        when(systemFields.isOrderableField(id)).thenReturn(true);
        when(systemFields.getOrderableField(id)).thenReturn(field);
        return field;
    }

    private static void item(FieldLayout layout, OrderableField field, boolean required, boolean hidden) {
        FieldLayoutItem item = mock(FieldLayoutItem.class);
        when(item.getOrderableField()).thenReturn(field);
        when(item.isRequired()).thenReturn(required);
        when(item.isHidden()).thenReturn(hidden);
        when(layout.getFieldLayoutItem(field.getId())).thenReturn(item);
    }

    private static FieldLayout layout(Long id, String name, boolean isDefault) {
        FieldLayout layout = mock(FieldLayout.class);
        when(layout.getId()).thenReturn(id);
        when(layout.getName()).thenReturn(name);
        when(layout.isDefault()).thenReturn(isDefault);
        return layout;
    }

    private static com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout editable(Long id, String name) {
        com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout layout =
                mock(com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout.class);
        when(layout.getId()).thenReturn(id);
        when(layout.getName()).thenReturn(name);
        return layout;
    }

    private static FieldLayoutSchemeEntity entity(String issueTypeId, Long layoutId, IssueType type) {
        FieldLayoutSchemeEntity entity = mock(FieldLayoutSchemeEntity.class);
        when(entity.getIssueTypeId()).thenReturn(issueTypeId);
        when(entity.getFieldLayoutId()).thenReturn(layoutId);
        when(entity.getIssueTypeObject()).thenReturn(type);
        return entity;
    }

    private static IssueType type(String id, String name) {
        IssueType type = mock(IssueType.class);
        when(type.getId()).thenReturn(id);
        when(type.getName()).thenReturn(name);
        return type;
    }

    private static Project project(long id, String key) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        when(project.getKey()).thenReturn(key);
        when(project.getName()).thenReturn(key + " project");
        return project;
    }
}
