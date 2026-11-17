package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.context.GlobalIssueContext;
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
import org.mockito.ArgumentCaptor;
import com.atlassian.beehive.ClusterLock;
import org.ofbiz.core.entity.GenericEntityException;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.common.api.dto.field.CreationStatus;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.customfields.api.dto.CustomFieldTypeResponse;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.FieldDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.rasulov.customfields.impl.JiraMocks.TEXTAREA;
import static ru.rasulov.customfields.impl.JiraMocks.TEXTFIELD;
import static ru.rasulov.customfields.impl.JiraMocks.TEXT_SEARCHER;
import static ru.rasulov.customfields.impl.JiraMocks.field;
import static ru.rasulov.customfields.impl.JiraMocks.searcher;
import static ru.rasulov.customfields.impl.JiraMocks.type;

@SuppressWarnings({"unchecked", "rawtypes"})
public class CustomFieldCreatorImplTest {
    @org.junit.After
    public void closeUndoDatabases() { JiraMocks.closeJournals(); }

    private CustomFieldManager manager;
    private GlobalPermissionManager permissions;
    private JiraAuthenticationContext authContext;
    private ApplicationUser admin;
    private CustomFieldType<?, ?> textfield;
    private CustomFieldType<?, ?> textarea;
    private CustomFieldSearcher textSearcher;
    private CustomFieldCreatorImpl creator;
    private OptionsManager optionsManager;

    @Before
    public void setUp() throws Exception {
        manager = mock(CustomFieldManager.class);
        permissions = mock(GlobalPermissionManager.class);
        authContext = mock(JiraAuthenticationContext.class);
        admin = mock(ApplicationUser.class);
        when(admin.getUsername()).thenReturn("admin");
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);

        textfield = type(CustomFieldType.class, TEXTFIELD, "Text Field (single line)");
        textarea = type(CustomFieldType.class, TEXTAREA, "Text Field (multi-line)");
        textSearcher = searcher(TEXT_SEARCHER, TEXTFIELD, TEXTAREA);
        when(manager.getCustomFieldType(TEXTFIELD)).thenReturn((CustomFieldType) textfield);
        when(manager.getCustomFieldType(TEXTAREA)).thenReturn((CustomFieldType) textarea);
        when(manager.getCustomFieldTypes()).thenReturn(Arrays.<CustomFieldType<?, ?>>asList(textfield, textarea));
        when(manager.getDefaultSearcher(any(CustomFieldType.class))).thenReturn(textSearcher);
        when(manager.getCustomFieldSearchers(any(CustomFieldType.class))).thenReturn(Collections.singletonList(textSearcher));
        when(manager.getCustomFieldObjects()).thenReturn(Collections.<CustomField>emptyList());
        stubCreation("customfield_10001", "customfield_10002", "customfield_10003");

        optionsManager = mock(OptionsManager.class);
        creator = JiraMocks.creator(manager, optionsManager, authContext, permissions,
                JiraMocks.lockService(mock(ClusterLock.class)));
    }

    @Test
    public void explicitModeAndPreviewTokenAreRequiredByTheService() {
        try {
            creator.createAll(new CreateCustomFieldsRequest(null, null, null));
            fail("missing dryRun must be rejected");
        } catch (InvalidRequestException expected) {
            assertTrue(expected.getMessage().contains("dryRun"));
        }
        CreateCustomFieldsResponse stale = creator.createAll(
                new CreateCustomFieldsRequest(false, null, "missing"));
        assertEquals("PREVIEW_STALE", stale.getErrorCode());
    }

    @Test
    public void applyCreatesWithTheSearcherResolvedForTheCheckedPlan() throws Exception {
        CustomFieldSearcher checkedSearcher = searcher(TEXT_SEARCHER, TEXTFIELD);
        CustomFieldSearcher replacementSearcher = searcher(JiraMocks.SYSTEM + "exacttextsearcher", TEXTFIELD);
        // Preview and lock-time planning agree; a further resolve would select another searcher.
        when(manager.getDefaultSearcher(textfield)).thenReturn(textSearcher, checkedSearcher, replacementSearcher);

        CreateCustomFieldsResponse preview = creator.createAll(request(true,
                new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null)));
        CreateCustomFieldsResponse applied = creator.createAll(
                new CreateCustomFieldsRequest(false, null, preview.getPlanId()));

        assertEquals(CreationStatus.CREATED, applied.getResults().get(0).getStatus());
        ArgumentCaptor<CustomFieldSearcher> writtenSearcher = ArgumentCaptor.forClass(CustomFieldSearcher.class);
        verify(manager).createCustomField(eq("Summary"), eq(""), eq(textfield),
                writtenSearcher.capture(), anyList(), anyList());
        assertSame("native create must use the lock-time checked plan, not resolve again",
                checkedSearcher, writtenSearcher.getValue());
    }

    @Test
    public void applyRejectsChangedDefaultSearcherWithoutCreatingAField() throws Exception {
        CreateCustomFieldsResponse preview = creator.createAll(request(true,
                new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null)));
        CustomFieldSearcher replacement = searcher(JiraMocks.SYSTEM + "exacttextsearcher", TEXTFIELD);
        when(manager.getDefaultSearcher(textfield)).thenReturn(replacement);

        CreateCustomFieldsResponse applied = creator.createAll(
                new CreateCustomFieldsRequest(false, null, preview.getPlanId()));

        assertEquals("PREVIEW_STALE", applied.getErrorCode());
        verify(manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    @Test
    public void applyRejectsAFieldCreatedAfterPreviewWithoutWriting() throws Exception {
        CreateCustomFieldsResponse preview = creator.createAll(request(true,
                new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null)));
        existing(field("customfield_10150", "Summary", textfield));

        CreateCustomFieldsResponse applied = creator.createAll(
                new CreateCustomFieldsRequest(false, null, preview.getPlanId()));

        assertEquals("PREVIEW_STALE", applied.getErrorCode());
        verify(manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    // --- creation ----------------------------------------------------------------------------------------------

    @Test
    public void createsTextfieldAndTextareaInGlobalContextForAllIssueTypes() throws Exception {
        CreateCustomFieldsResponse response = JiraMocks.previewAndApply(creator, request(true,
                new FieldDefinition("Summary", "Short text", "textfield", null, null, JiraMocks.GLOBAL, null),
                new FieldDefinition("Notes", null, "textarea", null, null, JiraMocks.GLOBAL, null)));

        assertEquals(Arrays.asList(CreationStatus.CREATED, CreationStatus.CREATED), statuses(response));
        assertEquals("customfield_10001", response.getResults().get(0).getCustomFieldId());
        assertEquals("customfield_10002", response.getResults().get(1).getCustomFieldId());

        ArgumentCaptor<List> contexts = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List> issueTypes = ArgumentCaptor.forClass(List.class);
        verify(manager).createCustomField(eq("Summary"), eq("Short text"), eq(textfield), eq(textSearcher),
                contexts.capture(), issueTypes.capture());
        assertEquals(1, contexts.getValue().size());
        assertSame(GlobalIssueContext.getInstance(), contexts.getValue().get(0));
        assertEquals(Collections.singletonList(null), issueTypes.getValue());
        verify(manager).createCustomField(eq("Notes"), eq(""), eq(textarea), eq(textSearcher), anyList(), anyList());
    }

    @Test
    public void resultsKeepRequestOrderAndOneFailureDoesNotStopTheRest() throws Exception {
        CreateCustomFieldsResponse response = JiraMocks.previewAndApply(creator, request(true,
                new FieldDefinition("A", null, "textfield", null, null, JiraMocks.GLOBAL, null),
                new FieldDefinition("B", null, "nosuchtype", null, null, JiraMocks.GLOBAL, null),
                new FieldDefinition("C", null, "textarea", null, null, JiraMocks.GLOBAL, null)));

        assertEquals(Arrays.asList(CreationStatus.CREATED, CreationStatus.FAILED, CreationStatus.CREATED), statuses(response));
        assertEquals(Arrays.asList("A", "B", "C"), names(response));
        assertEquals(CustomFieldErrorCode.UNKNOWN_TYPE, response.getResults().get(1).getErrorCode());
    }

    @Test(expected = IllegalStateException.class)
    public void uncertainCreationAbortsTheOperationForDurableRecovery() throws Exception {
        when(manager.createCustomField(eq("Broken"), any(), any(CustomFieldType.class),
                any(), anyList(), anyList())).thenThrow(new GenericEntityException("db down"));
        JiraMocks.previewAndApply(creator, request(true,
                new FieldDefinition("Broken", null, "textfield", null, null, JiraMocks.GLOBAL, null),
                new FieldDefinition("Fine", null, "textfield", null, null, JiraMocks.GLOBAL, null)));
    }

    // --- dry run -----------------------------------------------------------------------------------------------

    @Test
    public void dryRunValidatesButNeverWrites() throws Exception {
        CreateCustomFieldsResponse response = creator.createAll(request(true,
                new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null),
                new FieldDefinition("Bad", null, "nosuchtype", null, null, JiraMocks.GLOBAL, null)));

        assertTrue(response.isDryRun());
        assertEquals(Arrays.asList(CreationStatus.WOULD_CREATE, CreationStatus.FAILED), statuses(response));
        assertNull(response.getResults().get(0).getCustomFieldId());
        verify(manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    // --- idempotency -------------------------------------------------------------------------------------------

    @Test
    public void sameNameAndTypeIsExistsAndNotRecreated() throws Exception {
        existing(field("customfield_10150", "Summary", textfield));

        FieldCreationResult result = creator.createAll(request(true,
                new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null))).getResults().get(0);

        assertEquals(CreationStatus.EXISTS, result.getStatus());
        assertEquals("customfield_10150", result.getCustomFieldId());
        verify(manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    @Test
    public void nameMatchIsCaseInsensitiveAndIgnoresSurroundingSpaces() {
        existing(field("customfield_10150", "Summary", textfield));

        FieldCreationResult result = creator.createAll(request(true,
                new FieldDefinition(" summary ", null, "textfield", null, null, JiraMocks.GLOBAL, null))).getResults().get(0);

        assertEquals(CreationStatus.EXISTS, result.getStatus());
    }

    @Test
    public void dryRunReportsExistsForExistingField() {
        existing(field("customfield_10150", "Summary", textfield));

        FieldCreationResult result = creator.createAll(request(true,
                new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null))).getResults().get(0);

        assertEquals(CreationStatus.EXISTS, result.getStatus());
    }

    @Test
    public void sameNameWithAnotherTypeIsNameConflictWithExistingFieldInSuggestions() {
        existing(field("customfield_10150", "Summary", textarea));

        FieldCreationResult result = creator.createAll(request(true,
                new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null))).getResults().get(0);

        assertEquals(CustomFieldErrorCode.NAME_CONFLICT, result.getErrorCode());
        assertEquals(Collections.singletonList("customfield_10150 (" + TEXTAREA + ")"), result.getSuggestions());
    }

    @Test
    public void severalFieldsWithTheNameIsNameConflictEvenIfOneHasTheSameType() {
        existing(field("customfield_10150", "Summary", textfield), field("customfield_10151", "Summary", textfield));

        FieldCreationResult result = creator.createAll(request(true,
                new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null))).getResults().get(0);

        assertEquals(CustomFieldErrorCode.NAME_CONFLICT, result.getErrorCode());
        assertEquals(2, result.getSuggestions().size());
    }

    @Test
    public void fieldWhoseTypePluginIsDisabledIsNameConflict() {
        existing(field("customfield_10150", "Summary", null));

        FieldCreationResult result = creator.createAll(request(true,
                new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null))).getResults().get(0);

        assertEquals(CustomFieldErrorCode.NAME_CONFLICT, result.getErrorCode());
    }

    @Test
    public void repeatedNameInOneCallFailsTheSecondOccurrenceOnly() throws Exception {
        CreateCustomFieldsResponse response = JiraMocks.previewAndApply(creator, request(true,
                new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null),
                new FieldDefinition("SUMMARY", null, "textfield", null, null, JiraMocks.GLOBAL, null)));

        assertEquals(Arrays.asList(CreationStatus.CREATED, CreationStatus.FAILED), statuses(response));
        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, response.getResults().get(1).getErrorCode());
        verify(manager, times(1)).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    @Test
    public void nameOfAFieldThatFailedValidationMayBeReusedLaterInTheCall() {
        CreateCustomFieldsResponse response = creator.createAll(request(true,
                new FieldDefinition("Summary", null, "nosuchtype", null, null, JiraMocks.GLOBAL, null),
                new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null)));

        assertEquals(Arrays.asList(CreationStatus.FAILED, CreationStatus.WOULD_CREATE), statuses(response));
    }

    @Test
    public void existingFieldsAreQueriedOnlyByRequestedName() {
        creator.createAll(request(true,
                new FieldDefinition("A", null, "textfield", null, null, JiraMocks.GLOBAL, null),
                new FieldDefinition("B", null, "textfield", null, null, JiraMocks.GLOBAL, null),
                new FieldDefinition("C", null, "textarea", null, null, JiraMocks.GLOBAL, null)));

        verify(manager).getCustomFieldObjectsByNameIgnoreCase("A");
        verify(manager).getCustomFieldObjectsByNameIgnoreCase("B");
        verify(manager).getCustomFieldObjectsByNameIgnoreCase("C");
        verify(manager, never()).getCustomFieldObjects();
    }

    // --- request-level errors and permissions ------------------------------------------------------------------

    @Test
    public void emptyFieldListIsRejected() {
        assertRejected(request(false));
    }

    @Test
    public void nullRequestIsRejected() {
        assertRejected(null);
    }

    @Test
    public void moreThanFiftyFieldsAreRejectedAndFiftyAreAccepted() {
        FieldDefinition[] fifty = new FieldDefinition[50];
        for (int i = 0; i < 50; i++) {
            fifty[i] = new FieldDefinition("Field " + i, null, "textfield", null, null, JiraMocks.GLOBAL, null);
        }
        assertEquals(50, creator.createAll(request(true, fifty)).getResults().size());

        List<FieldDefinition> tooMany = new ArrayList<FieldDefinition>(Arrays.asList(fifty));
        tooMany.add(new FieldDefinition("Field 50", null, "textfield", null, null, JiraMocks.GLOBAL, null));
        assertRejected(new CreateCustomFieldsRequest(true, tooMany));
    }

    @Test
    public void anonymousUserGetsAnonymousAccessException() {
        when(authContext.getLoggedInUser()).thenReturn(null);
        try {
            creator.createAll(request(false, new FieldDefinition("A", null, "textfield", null, null, JiraMocks.GLOBAL, null)));
            fail("expected AccessException");
        } catch (AccessException e) {
            assertTrue(e.isAnonymous());
        }
        verify(manager, never()).getCustomFieldObjects();
    }

    @Test
    public void nonAdministratorIsForbiddenEvenForDryRunAndTypeListing() {
        when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(false);
        try {
            creator.createAll(request(true, new FieldDefinition("A", null, "textfield", null, null, JiraMocks.GLOBAL, null)));
            fail("expected AccessException");
        } catch (AccessException e) {
            assertEquals(false, e.isAnonymous());
        }
        try {
            creator.listAvailableTypes();
            fail("expected AccessException");
        } catch (AccessException e) {
            assertEquals(false, e.isAnonymous());
        }
    }

    // --- type listing ------------------------------------------------------------------------------------------

    @Test
    public void listsTypesSortedByKeyWithSearchersAndTruncatedDescription() {
        char[] longText = new char[500];
        Arrays.fill(longText, 'x');
        when(textfield.getDescription()).thenReturn(new String(longText));
        when(textarea.getDescription()).thenReturn(null);

        List<CustomFieldTypeResponse> types = creator.listAvailableTypes();

        assertEquals(TEXTAREA, types.get(0).getKey());
        assertEquals(TEXTFIELD, types.get(1).getKey());
        assertEquals("", types.get(0).getDescription());
        assertEquals(200, types.get(1).getDescription().length());
        assertEquals(Collections.singletonList(TEXT_SEARCHER), types.get(1).getSearcherKeys());
        assertEquals(false, types.get(1).isSupportsOptions());
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private void stubCreation(String... ids) throws Exception {
        CustomField first = null;
        CustomField[] rest = new CustomField[ids.length - 1];
        for (int i = 0; i < ids.length; i++) {
            CustomField created = JiraMocks.createdField(ids[i]);
            if (i == 0) {
                first = created;
            } else {
                rest[i - 1] = created;
            }
        }
        when(manager.createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList())).thenReturn(first, rest);
    }

    private void existing(CustomField... fields) {
        when(manager.getCustomFieldObjectsByNameIgnoreCase(anyString())).thenAnswer(invocation -> {
            String requested = invocation.getArgument(0);
            List<CustomField> matches = new ArrayList<CustomField>();
            for (CustomField field : fields) {
                if (field.getUntranslatedName().equalsIgnoreCase(requested)) matches.add(field);
            }
            return matches;
        });
    }

    private static CreateCustomFieldsRequest request(boolean dryRun, FieldDefinition... fields) {
        return new CreateCustomFieldsRequest(dryRun, Arrays.asList(fields));
    }

    private void assertRejected(CreateCustomFieldsRequest request) {
        try {
            creator.createAll(request);
            fail("expected InvalidRequestException");
        } catch (InvalidRequestException expected) {
            // ok
        }
    }

    private static List<CreationStatus> statuses(CreateCustomFieldsResponse response) {
        List<CreationStatus> statuses = new ArrayList<CreationStatus>();
        for (FieldCreationResult result : response.getResults()) {
            statuses.add(result.getStatus());
        }
        return statuses;
    }

    private static List<String> names(CreateCustomFieldsResponse response) {
        List<String> names = new ArrayList<String>();
        for (FieldCreationResult result : response.getResults()) {
            names.add(result.getName());
        }
        return names;
    }
}
