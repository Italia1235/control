package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldSearcher;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreationStatus;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.FieldDefinition;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.rasulov.customfields.impl.JiraMocks.SYSTEM;
import static ru.rasulov.customfields.impl.JiraMocks.searcher;
import static ru.rasulov.customfields.impl.JiraMocks.type;

/**
 * Every built-in type without options is created by its short key with the type's own default searcher:
 * text, textarea, date picker, date time picker, number, URL, user picker.
 */
@RunWith(Parameterized.class)
@SuppressWarnings({"unchecked", "rawtypes"})
public class SupportedTypesTest {
    @org.junit.After
    public void closeUndoDatabases() { JiraMocks.closeJournals(); }

    @Parameters
    public static Collection<Object[]> types() {
        return Arrays.asList(new Object[][]{
                {"textfield", "textsearcher"},
                {"textarea", "textsearcher"},
                {"datepicker", "daterange"},
                {"datetime", "datetimerange"},
                {"float", "exactnumber"},
                {"url", "exacttextsearcher"},
                {"userpicker", "userpickergroupsearcher"},
                {"labels", "labelsearcher"},
                {"multiuserpicker", "userpickergroupsearcher"},
                {"grouppicker", "grouppickersearcher"},
                {"multigrouppicker", "grouppickersearcher"},
                {"project", "projectsearcher"},
                {"version", "versionsearcher"},
                {"multiversion", "versionsearcher"},
        });
    }

    private final String shortKey;
    private final String defaultSearcherModule;

    private CustomFieldManager manager;
    private CustomFieldType<?, ?> type;
    private CustomFieldSearcher defaultSearcher;
    private CustomFieldCreatorImpl creator;

    public SupportedTypesTest(String shortKey, String defaultSearcherModule) {
        this.shortKey = shortKey;
        this.defaultSearcherModule = defaultSearcherModule;
    }

    @Before
    public void setUp() throws Exception {
        manager = mock(CustomFieldManager.class);
        type = type(CustomFieldType.class, SYSTEM + shortKey, shortKey);
        defaultSearcher = searcher(SYSTEM + defaultSearcherModule, SYSTEM + shortKey);
        when(manager.getCustomFieldType(SYSTEM + shortKey)).thenReturn((CustomFieldType) type);
        when(manager.getDefaultSearcher((CustomFieldType) type)).thenReturn(defaultSearcher);
        when(manager.getCustomFieldSearchers((CustomFieldType) type)).thenReturn(Collections.singletonList(defaultSearcher));
        when(manager.getCustomFieldObjects()).thenReturn(Collections.<CustomField>emptyList());
        CustomField created = JiraMocks.createdField("customfield_10500");
        when(manager.createCustomField(anyString(), anyString(), (CustomFieldType) eq(type), eq(defaultSearcher),
                anyList(), anyList())).thenReturn(created);

        JiraAuthenticationContext authContext = mock(JiraAuthenticationContext.class);
        GlobalPermissionManager permissions = mock(GlobalPermissionManager.class);
        ApplicationUser admin = mock(ApplicationUser.class);
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
        creator = JiraMocks.creator(manager, mock(OptionsManager.class), authContext, permissions,
                JiraMocks.lockService(mock(com.atlassian.beehive.ClusterLock.class)));
    }

    @Test
    public void createdByShortKeyWithTheDefaultSearcherOfTheType() throws Exception {
        FieldCreationResult result = create(new FieldDefinition("Field " + shortKey, "d", shortKey, null, null, JiraMocks.GLOBAL, null));

        assertEquals(CreationStatus.CREATED, result.getStatus());
        assertEquals("customfield_10500", result.getCustomFieldId());
        verify(manager).createCustomField(eq("Field " + shortKey), eq("d"), (CustomFieldType) eq(type),
                eq(defaultSearcher), anyList(), anyList());
    }

    @Test
    public void createdByFullKey() {
        FieldCreationResult result = create(new FieldDefinition("Field " + shortKey, null, SYSTEM + shortKey, null, null, JiraMocks.GLOBAL, null));

        assertEquals(CreationStatus.CREATED, result.getStatus());
    }

    @Test
    public void searcherOfAnotherTypeIsRejectedWithTheCompatibleOnesAsSuggestions() {
        String foreign = SYSTEM + "foreignsearcher";
        CustomFieldSearcher foreignSearcher = searcher(foreign, SYSTEM + "some-other-type");
        when(manager.getCustomFieldSearcher(foreign)).thenReturn(foreignSearcher);

        FieldCreationResult result = create(new FieldDefinition("Field " + shortKey, null, shortKey, foreign, null, JiraMocks.GLOBAL, null));

        assertEquals(CreationStatus.FAILED, result.getStatus());
        assertEquals(CustomFieldErrorCode.INCOMPATIBLE_SEARCHER, result.getErrorCode());
        assertEquals(Collections.singletonList(SYSTEM + defaultSearcherModule), result.getSuggestions());
    }

    private FieldCreationResult create(FieldDefinition definition) {
        return JiraMocks.previewAndApply(creator, new CreateCustomFieldsRequest(true, Collections.singletonList(definition)))
                .getResults().get(0);
    }
}
