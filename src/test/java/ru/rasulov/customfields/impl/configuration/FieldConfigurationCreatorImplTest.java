package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationRequest;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationResponse;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationSchemeRequest;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationSchemeResponse;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.SchemeMappingInput;
import ru.rasulov.customfields.impl.FieldJournalTestSupport;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Creation-only previews, name uniqueness, DEFAULT rows, staleness and persisted results. */
public class FieldConfigurationCreatorImplTest {
    private FieldLayoutFake jira;
    private FieldConfigurationCreatorImpl creator;

    @Before
    public void setUp() {
        jira = new FieldLayoutFake();
        creator = new FieldConfigurationCreatorImpl(jira.guard, jira.lock, jira.catalog,
                new SchemeRows(jira.issueTypes, jira.catalog), jira.manager, jira.copies,
                new ru.rasulov.common.impl.PreviewPlanExecutor(jira.plans, jira.codec), jira.codec,
                jira.journal, ru.rasulov.common.impl.TestLinks.links());
    }

    @After
    public void close() { FieldJournalTestSupport.close(); }

    @Test
    public void copyOfAnExistingConfigurationIsCreatedOnlyAfterItsPreview() {
        EditableFieldLayout source = jira.layout(10L, "Shared fields");

        CreateFieldConfigurationResponse preview = creator.createConfiguration(
                new CreateFieldConfigurationRequest(true, "PAY: Task fields", "Task only", "10", null));

        assertEquals(CreateFieldConfigurationResponse.Status.WOULD_CREATE, preview.getStatus());
        assertEquals("Shared fields", preview.getCopyFrom().getName());
        assertNull(preview.getFieldConfiguration());
        assertEquals(1, jira.layouts.size());

        CreateFieldConfigurationResponse applied = creator.createConfiguration(
                new CreateFieldConfigurationRequest(false, null, null, null, preview.getPlanId()));

        assertEquals(CreateFieldConfigurationResponse.Status.CREATED, applied.getStatus());
        assertNotNull(applied.getFieldConfiguration().getId());
        EditableFieldLayout created = jira.layouts.get(applied.getFieldConfiguration().getId());
        assertEquals("PAY: Task fields", created.getName());
        assertEquals("Task only", created.getDescription());
        assertTrue(jira.associations.isEmpty());
        org.mockito.Mockito.verify(jira.copies).copyOf(source, "PAY: Task fields");
    }

    @Test
    public void omittedSourceCopiesJirasDefaultConfiguration() {
        CreateFieldConfigurationResponse preview = creator.createConfiguration(
                new CreateFieldConfigurationRequest(true, "Blank", null, null, null));

        assertTrue(preview.getCopyFrom().isDefaultConfiguration());
    }

    @Test
    public void takenNameFailsCaseInsensitivelyWithAFreeSuggestion() {
        jira.layout(10L, "Task fields");

        CreateFieldConfigurationResponse preview = creator.createConfiguration(
                new CreateFieldConfigurationRequest(true, "task FIELDS", null, null, null));

        assertEquals(ErrorCode.NAME_TAKEN, preview.getErrorCode());
        assertEquals("task FIELDS v1", preview.getSuggestions().get(0));
        assertNull(preview.getPlanId());
    }

    @Test
    public void unknownSourceIsRefused() {
        CreateFieldConfigurationResponse preview = creator.createConfiguration(
                new CreateFieldConfigurationRequest(true, "Copy", null, "999", null));

        assertEquals(ErrorCode.UNKNOWN_FIELD_CONFIGURATION, preview.getErrorCode());
    }

    @Test
    public void sourceChangedAfterPreviewMakesThePlanStale() {
        EditableFieldLayout source = jira.layout(10L, "Shared fields");
        OrderableField severity = FieldLayoutFake.field("customfield_1", "Severity");
        jira.item(source, severity, false, false);
        CreateFieldConfigurationResponse preview = creator.createConfiguration(
                new CreateFieldConfigurationRequest(true, "Copy", null, "10", null));
        jira.items.get("10").get("customfield_1")[0] = true;

        CreateFieldConfigurationResponse applied = creator.createConfiguration(
                new CreateFieldConfigurationRequest(false, null, null, null, preview.getPlanId()));

        assertEquals(ErrorCode.PREVIEW_STALE, applied.getErrorCode());
        assertEquals(1, jira.layouts.size());
    }

    @Test
    public void schemeIsCreatedWithEveryRowAndNoProject() {
        jira.layout(10L, "Task fields");
        jira.issueType("1", "Task");

        CreateFieldConfigurationSchemeResponse preview = creator.createScheme(new CreateFieldConfigurationSchemeRequest(
                true, "PAY fields", "PAY only", Arrays.asList(new SchemeMappingInput("DEFAULT", "DEFAULT"),
                new SchemeMappingInput("Task", "10")), null));

        assertEquals(CreateFieldConfigurationSchemeResponse.Status.WOULD_CREATE, preview.getStatus());
        assertEquals(2, preview.getMappings().size());
        assertNull(preview.getMappings().get(0).getIssueTypeId());
        assertTrue(preview.getMappings().get(0).getFieldConfiguration().isDefaultConfiguration());
        assertTrue(jira.schemes.isEmpty());

        CreateFieldConfigurationSchemeResponse applied = creator.createScheme(new CreateFieldConfigurationSchemeRequest(
                false, null, null, null, preview.getPlanId()));

        assertEquals(CreateFieldConfigurationSchemeResponse.Status.CREATED, applied.getStatus());
        assertFalse(applied.isAssigned());
        Map<String, Long> rows = jira.rows.get(applied.getScheme().getId());
        assertEquals(2, rows.size());
        assertNull(rows.get(null));
        assertEquals(Long.valueOf(10L), rows.get("1"));
        assertTrue(jira.associations.isEmpty());
    }

    @Test
    public void schemeNeedsItsDefaultRow() {
        jira.layout(10L, "Task fields");
        jira.issueType("1", "Task");

        CreateFieldConfigurationSchemeResponse preview = creator.createScheme(new CreateFieldConfigurationSchemeRequest(
                true, "PAY fields", null, Collections.singletonList(new SchemeMappingInput("Task", "10")), null));

        assertEquals(ErrorCode.VALIDATION_ERROR, preview.getErrorCode());
        assertTrue(preview.getMessage().contains("DEFAULT"));
    }

    @Test
    public void repeatedIssueTypeIsRefused() {
        jira.issueType("1", "Task");

        CreateFieldConfigurationSchemeResponse preview = creator.createScheme(new CreateFieldConfigurationSchemeRequest(
                true, "PAY fields", null, Arrays.asList(new SchemeMappingInput("DEFAULT", "DEFAULT"),
                new SchemeMappingInput("Task", "DEFAULT"), new SchemeMappingInput("1", "DEFAULT")), null));

        assertEquals(ErrorCode.VALIDATION_ERROR, preview.getErrorCode());
        assertTrue(preview.getMessage().contains("more than once"));
    }

    @Test
    public void takenSchemeNameIncludesJirasDefaultScheme() {
        CreateFieldConfigurationSchemeResponse preview = creator.createScheme(new CreateFieldConfigurationSchemeRequest(
                true, "Default Field Configuration Scheme", null,
                Collections.singletonList(new SchemeMappingInput("DEFAULT", "DEFAULT")), null));

        assertEquals(ErrorCode.NAME_TAKEN, preview.getErrorCode());
    }
}
