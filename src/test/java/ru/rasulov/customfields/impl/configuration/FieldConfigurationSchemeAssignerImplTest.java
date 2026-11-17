package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.customfields.api.dto.configuration.AssignFieldConfigurationSchemeRequest;
import ru.rasulov.customfields.api.dto.configuration.AssignFieldConfigurationSchemeResponse;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.IssueTypeChange;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.Warning;
import ru.rasulov.customfields.api.dto.configuration.SchemeMappingInput;
import ru.rasulov.customfields.api.dto.configuration.SetFieldConfigurationMappingsRequest;
import ru.rasulov.customfields.api.dto.configuration.SetFieldConfigurationMappingsResponse;
import ru.rasulov.customfields.impl.FieldJournalTestSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Field-level previews, sharing warnings, staleness and persisted associations and rows. */
public class FieldConfigurationSchemeAssignerImplTest {
    private FieldLayoutFake jira;
    private FieldConfigurationSchemeAssignerImpl assigner;
    private IssueType task;
    private IssueType bug;
    private Project pay;
    private EditableFieldLayout taskFields;
    private CustomField severity;

    @Before
    public void setUp() {
        jira = new FieldLayoutFake();
        assigner = new FieldConfigurationSchemeAssignerImpl(jira.guard, jira.lock, jira.projects, jira.catalog,
                new SchemeRows(jira.issueTypes, jira.catalog), jira.manager,
                new ru.rasulov.common.impl.PreviewPlanExecutor(jira.plans, jira.codec), jira.codec, jira.journal);
        task = jira.issueType("1", "Task");
        bug = jira.issueType("2", "Bug");
        pay = jira.project(1L, "PAY", task, bug);
        taskFields = jira.layout(10L, "Task fields");
        severity = customField("customfield_100", "Severity", true);
        jira.item(jira.defaultLayout, severity, false, false);
        jira.item(taskFields, severity, true, false);
        OrderableField dueDate = FieldLayoutFake.field("duedate", "Due Date");
        jira.item(jira.defaultLayout, dueDate, false, false);
        jira.item(taskFields, dueDate, false, true);
    }

    @After
    public void close() { FieldJournalTestSupport.close(); }

    @Test
    public void assigningASchemePreviewsEveryChangedFieldThenAssignsIt() {
        taskScheme(70L);

        AssignFieldConfigurationSchemeResponse preview = assigner.assign(
                new AssignFieldConfigurationSchemeRequest(true, "PAY", "70", null));

        assertEquals(preview.getMessage(), AssignFieldConfigurationSchemeResponse.Status.WOULD_ASSIGN, preview.getStatus());
        assertTrue(preview.getSchemeBefore().isDefaultScheme());
        assertEquals(Long.valueOf(70L), preview.getSchemeAfter().getId());
        assertEquals("only Task changes configuration", 1, preview.getChanges().size());
        IssueTypeChange change = preview.getChanges().get(0);
        assertEquals("Task", change.getIssueTypeName());
        assertEquals(2, change.getFields().size());
        assertEquals("customfield_100", change.getFields().get(0).getField().getId());
        assertTrue(change.getFields().get(0).isRequiredAfter());
        assertEquals("duedate", change.getFields().get(1).getField().getId());
        assertTrue(change.getFields().get(1).isHiddenAfter());
        assertEquals(Arrays.asList("NEWLY_REQUIRED", "NEWLY_HIDDEN"), codes(preview.getWarnings()));
        assertTrue(jira.associations.isEmpty());

        AssignFieldConfigurationSchemeResponse applied = assigner.assign(
                new AssignFieldConfigurationSchemeRequest(false, null, null, preview.getPlanId()));

        assertEquals(AssignFieldConfigurationSchemeResponse.Status.ASSIGNED, applied.getStatus());
        assertEquals(Long.valueOf(70L), jira.associations.get(1L));
    }

    @Test
    public void customFieldOutsideItsContextIsNotReported() {
        when(severity.isInScope(anyLong(), anyString())).thenReturn(false);
        taskScheme(70L);

        AssignFieldConfigurationSchemeResponse preview = assigner.assign(
                new AssignFieldConfigurationSchemeRequest(true, "PAY", "70", null));

        assertEquals(1, preview.getChanges().get(0).getFields().size());
        assertEquals(Collections.singletonList("NEWLY_HIDDEN"), codes(preview.getWarnings()));
    }

    @Test
    public void theAssignedSchemeNeedsNoPlan() {
        taskScheme(70L);
        jira.associations.put(1L, 70L);

        AssignFieldConfigurationSchemeResponse preview = assigner.assign(
                new AssignFieldConfigurationSchemeRequest(true, "PAY", "70", null));

        assertEquals(AssignFieldConfigurationSchemeResponse.Status.ALREADY_ASSIGNED, preview.getStatus());
        assertNull(preview.getPlanId());
    }

    @Test
    public void returningToTheDefaultSchemeWarnsThatTheOldOneIsLeftUnused() {
        taskScheme(70L);
        jira.associations.put(1L, 70L);

        AssignFieldConfigurationSchemeResponse preview = assigner.assign(
                new AssignFieldConfigurationSchemeRequest(true, "PAY", "DEFAULT", null));

        assertTrue(codes(preview.getWarnings()).contains("SCHEME_ORPHANED"));
        assertFalse("relaxing rules is not a NEWLY_REQUIRED warning", codes(preview.getWarnings()).contains("NEWLY_REQUIRED"));

        AssignFieldConfigurationSchemeResponse applied = assigner.assign(
                new AssignFieldConfigurationSchemeRequest(false, null, null, preview.getPlanId()));

        assertEquals(AssignFieldConfigurationSchemeResponse.Status.ASSIGNED, applied.getStatus());
        assertFalse(jira.associations.containsKey(1L));
    }

    @Test
    public void aSchemeOtherProjectsUseIsReported() {
        taskScheme(70L);
        jira.project(2L, "OPS", task);
        jira.associations.put(2L, 70L);

        AssignFieldConfigurationSchemeResponse preview = assigner.assign(
                new AssignFieldConfigurationSchemeRequest(true, "PAY", "70", null));

        assertEquals(1, preview.getSchemeAfterOtherProjects().getTotal());
        assertTrue(codes(preview.getWarnings()).contains("SHARED_SCHEME"));
    }

    @Test
    public void configurationChangedAfterPreviewMakesTheAssignmentStale() {
        taskScheme(70L);
        AssignFieldConfigurationSchemeResponse preview = assigner.assign(
                new AssignFieldConfigurationSchemeRequest(true, "PAY", "70", null));
        jira.items.get("10").get("customfield_100")[0] = false;

        AssignFieldConfigurationSchemeResponse applied = assigner.assign(
                new AssignFieldConfigurationSchemeRequest(false, null, null, preview.getPlanId()));

        assertEquals(ErrorCode.PREVIEW_STALE, applied.getErrorCode());
        assertTrue(jira.associations.isEmpty());
    }

    @Test
    public void unknownSchemeIsRefused() {
        AssignFieldConfigurationSchemeResponse preview = assigner.assign(
                new AssignFieldConfigurationSchemeRequest(true, "PAY", "999", null));

        assertEquals(ErrorCode.UNKNOWN_SCHEME, preview.getErrorCode());
    }

    @Test
    public void attachingAConfigurationToTaskAffectsEveryProjectOfTheScheme() {
        jira.scheme(70L, "Shared", null);
        jira.rows.get(70L).put(null, null);
        jira.associations.put(1L, 70L);
        jira.project(2L, "OPS", task, bug);
        jira.associations.put(2L, 70L);

        SetFieldConfigurationMappingsResponse preview = assigner.setMappings(new SetFieldConfigurationMappingsRequest(
                true, "70", Collections.singletonList(new SchemeMappingInput("Task", "10")), null));

        assertEquals(preview.getMessage(), SetFieldConfigurationMappingsResponse.Status.WOULD_CHANGE, preview.getStatus());
        assertNull(preview.getRows().get(0).getBefore());
        assertEquals(Long.valueOf(10L), preview.getRows().get(0).getAfter().getId());
        assertEquals(2, preview.getProjectsTotal());
        assertEquals(2, preview.getAffected().size());
        assertEquals("Task", preview.getAffected().get(1).getIssueTypes().get(0).getIssueTypeName());
        assertTrue(codes(preview.getWarnings()).contains("SHARED_SCHEME"));
        assertTrue(codes(preview.getWarnings()).contains("NEWLY_REQUIRED"));

        SetFieldConfigurationMappingsResponse applied = assigner.setMappings(new SetFieldConfigurationMappingsRequest(
                false, null, null, preview.getPlanId()));

        assertEquals(SetFieldConfigurationMappingsResponse.Status.CHANGED, applied.getStatus());
        assertEquals(Long.valueOf(10L), jira.rows.get(70L).get("1"));
        assertFalse(jira.rows.get(70L).containsKey("2"));
    }

    @Test
    public void inheritDeletesTheIssueTypesOwnRow() {
        taskScheme(70L);
        jira.associations.put(1L, 70L);

        SetFieldConfigurationMappingsResponse preview = assigner.setMappings(new SetFieldConfigurationMappingsRequest(
                true, "70", Collections.singletonList(new SchemeMappingInput("Task", "INHERIT")), null));
        assertNull(preview.getRows().get(0).getAfter());
        SetFieldConfigurationMappingsResponse applied = assigner.setMappings(new SetFieldConfigurationMappingsRequest(
                false, null, null, preview.getPlanId()));

        assertEquals(SetFieldConfigurationMappingsResponse.Status.CHANGED, applied.getStatus());
        assertFalse(jira.rows.get(70L).containsKey("1"));
        assertTrue(jira.rows.get(70L).containsKey(null));
    }

    @Test
    public void unchangedRowsNeedNoPlan() {
        taskScheme(70L);

        SetFieldConfigurationMappingsResponse preview = assigner.setMappings(new SetFieldConfigurationMappingsRequest(
                true, "70", Collections.singletonList(new SchemeMappingInput("Task", "10")), null));

        assertEquals(SetFieldConfigurationMappingsResponse.Status.NO_CHANGE, preview.getStatus());
        assertNull(preview.getPlanId());
    }

    @Test
    public void jirasDefaultSchemeCannotBeEdited() {
        SetFieldConfigurationMappingsResponse preview = assigner.setMappings(new SetFieldConfigurationMappingsRequest(
                true, "DEFAULT", Collections.singletonList(new SchemeMappingInput("Task", "10")), null));

        assertEquals(ErrorCode.DEFAULT_SCHEME, preview.getErrorCode());
    }

    @Test
    public void theDefaultRowCannotInherit() {
        taskScheme(70L);

        SetFieldConfigurationMappingsResponse preview = assigner.setMappings(new SetFieldConfigurationMappingsRequest(
                true, "70", Collections.singletonList(new SchemeMappingInput("DEFAULT", "INHERIT")), null));

        assertEquals(ErrorCode.VALIDATION_ERROR, preview.getErrorCode());
    }

    private void taskScheme(long id) {
        jira.scheme(id, "PAY scheme", null);
        jira.rows.get(id).put(null, null);
        jira.rows.get(id).put("1", 10L);
    }

    private static CustomField customField(String id, String name, boolean inScope) {
        CustomField field = mock(CustomField.class);
        CustomFieldType<?, ?> type = mock(CustomFieldType.class);
        when(field.getId()).thenReturn(id);
        when(field.getName()).thenReturn(name);
        when(field.getCustomFieldType()).thenReturn(type);
        when(type.getKey()).thenReturn("select");
        when(field.isInScope(anyLong(), anyString())).thenReturn(inScope);
        return field;
    }

    private static List<String> codes(List<Warning> warnings) {
        List<String> codes = new ArrayList<String>();
        for (Warning warning : warnings) codes.add(warning.getCode());
        return codes;
    }
}
