package ru.rasulov.customfields.impl;

import com.atlassian.jira.exception.RemoveException;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldSearcher;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreationStatus;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.field.OptionDefinition;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.rasulov.customfields.impl.JiraMocks.SYSTEM;
import static ru.rasulov.customfields.impl.JiraMocks.field;
import static ru.rasulov.customfields.impl.JiraMocks.type;

/** Select-like fields: options follow creation; uncertain writes escape to the durable coordinator. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class CustomFieldCreatorOptionsTest {
    @org.junit.After
    public void closeUndoDatabases() { JiraMocks.closeJournals(); }

    private CustomFieldManager manager;
    private OptionsManager optionsManager;
    private CustomFieldType<?, ?> select;
    private FieldConfig config;
    private CustomField created;
    private CustomFieldCreatorImpl creator;

    @Before
    public void setUp() throws Exception {
        manager = mock(CustomFieldManager.class);
        optionsManager = mock(OptionsManager.class);
        select = type(MultipleSettableCustomFieldType.class, SYSTEM + "select", "Select List");
        when(manager.getCustomFieldType(SYSTEM + "select")).thenReturn((CustomFieldType) select);
        CustomFieldType<?, ?> cascading = type(MultipleSettableCustomFieldType.class, SYSTEM + "cascadingselect",
                "Select List (cascading)");
        when(manager.getCustomFieldType(SYSTEM + "cascadingselect")).thenReturn((CustomFieldType) cascading);
        when(manager.getCustomFieldObjects()).thenReturn(Collections.<CustomField>emptyList());

        created = JiraMocks.createdField("customfield_10700");
        config = created.getConfigurationSchemes().get(0).getOneAndOnlyConfig();
        when(manager.createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList())).thenReturn(created);
        when(manager.createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                (CustomFieldSearcher) isNull(), anyList(), anyList())).thenReturn(created);

        JiraAuthenticationContext authContext = mock(JiraAuthenticationContext.class);
        GlobalPermissionManager permissions = mock(GlobalPermissionManager.class);
        ApplicationUser admin = mock(ApplicationUser.class);
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);

        creator = JiraMocks.creator(manager, optionsManager, authContext, permissions,
                JiraMocks.lockService(mock(com.atlassian.beehive.ClusterLock.class)));
    }

    @Test
    public void selectIsCreatedAndItsOptionsAreWrittenInOrderIntoTheFieldConfig() {
        FieldCreationResult result = create("select", opt("Low"), opt("Medium"), opt("High"));

        assertEquals(CreationStatus.CREATED, result.getStatus());
        assertEquals("customfield_10700", result.getCustomFieldId());
        InOrder order = inOrder(optionsManager);
        order.verify(optionsManager).createOption(eq(config), (Long) isNull(), eq(0L), eq("Low"));
        order.verify(optionsManager).createOption(eq(config), (Long) isNull(), eq(1L), eq("Medium"));
        order.verify(optionsManager).createOption(eq(config), (Long) isNull(), eq(2L), eq("High"));
    }

    @Test
    public void cascadingChildrenAreCreatedUnderTheIdOfTheirParent() {
        Option eu = option(7L);
        Option us = option(8L);
        when(optionsManager.createOption(eq(config), (Long) isNull(), eq(0L), eq("EU"))).thenReturn(eu);
        when(optionsManager.createOption(eq(config), (Long) isNull(), eq(1L), eq("US"))).thenReturn(us);

        FieldCreationResult result = create("cascadingselect",
                opt("EU", opt("Berlin"), opt("Paris")), opt("US", opt("Boston")));

        assertEquals(CreationStatus.CREATED, result.getStatus());
        verify(optionsManager).createOption(config, 7L, 0L, "Berlin");
        verify(optionsManager).createOption(config, 7L, 1L, "Paris");
        verify(optionsManager).createOption(config, 8L, 0L, "Boston");
    }

    @Test
    public void selectWithoutOptionsIsCreatedEmptyWithoutTouchingOptions() {
        FieldCreationResult result = create("select");

        assertEquals(CreationStatus.CREATED, result.getStatus());
        verifyNoInteractions(optionsManager);
    }

    @Test
    public void dryRunValidatesOptionsButWritesNothing() throws Exception {
        CreateCustomFieldsRequest request = new CreateCustomFieldsRequest(true, Collections.singletonList(
                definition("select", opt("Low"), opt("low"))));

        FieldCreationResult duplicate = creator.createAll(request).getResults().get(0);
        FieldCreationResult fine = creator.createAll(new CreateCustomFieldsRequest(true,
                Collections.singletonList(definition("select", opt("Low"), opt("High"))))).getResults().get(0);

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, duplicate.getErrorCode());
        assertEquals(CreationStatus.WOULD_CREATE, fine.getStatus());
        verifyNoInteractions(optionsManager);
        verify(manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    @Test
    public void invalidOptionsFailBeforeAnythingIsCreated() throws Exception {
        FieldCreationResult result = create("select", opt("Low"), opt(" "));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, result.getErrorCode());
        verify(manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    @Test
    public void existingFieldIsReportedAndItsOptionsAreNotSynchronised() {
        CustomField existing = field("customfield_10150", "Field", select);
        when(manager.getCustomFieldObjectsByNameIgnoreCase("Field")).thenReturn(Collections.singletonList(existing));

        FieldCreationResult result = create("select", opt("Low"));

        assertEquals(CreationStatus.EXISTS, result.getStatus());
        assertEquals("customfield_10150", result.getCustomFieldId());
        verifyNoInteractions(optionsManager);
    }

    @Test(expected = IllegalStateException.class)
    public void failedOptionEscapesToDurableCompensation() throws Exception {
        when(optionsManager.createOption(eq(config), any(), anyLong(), eq("Boom")))
                .thenThrow(new IllegalStateException("db down"));
        create("select", opt("Low"), opt("Boom"));
    }


    @Test(expected = IllegalStateException.class)
    public void missingConfigurationEscapesToDurableCompensation() throws Exception {
        when(created.getConfigurationSchemes()).thenReturn(Collections.<FieldConfigScheme>emptyList());
        create("select", opt("Low"));
    }

    @Test
    public void aSelectWithoutOptionsIsCreatedButTheResultTellsTheCallerToAskTheUser() {
        FieldCreationResult created = create("select");
        FieldCreationResult previewed = creator.createAll(new CreateCustomFieldsRequest(true,
                Collections.singletonList(definition("select")))).getResults().get(0);

        assertEquals(CreationStatus.CREATED, created.getStatus());
        assertNotNull(created.getMessage());
        assertTrue(created.getMessage(), created.getMessage().contains("NO OPTIONS"));
        assertEquals(CreationStatus.WOULD_CREATE, previewed.getStatus());
        assertNotNull(previewed.getMessage());
    }

    @Test
    public void aSelectWithOptionsHasNoWarning() {
        assertNull(create("select", opt("Low")).getMessage());
    }

    private FieldCreationResult create(String typeKey, OptionDefinition... options) {
        return JiraMocks.previewAndApply(creator, new CreateCustomFieldsRequest(true,
                Collections.singletonList(definition(typeKey, options)))).getResults().get(0);
    }

    private static FieldDefinition definition(String typeKey, OptionDefinition... options) {
        return new FieldDefinition("Field", null, typeKey, null, Arrays.asList(options), JiraMocks.GLOBAL, null);
    }

    private static OptionDefinition opt(String value, OptionDefinition... children) {
        return new OptionDefinition(value, Arrays.asList(children));
    }

    private static Option option(long id) {
        Option option = mock(Option.class);
        when(option.getOptionId()).thenReturn(id);
        return option;
    }
}
