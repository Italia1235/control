package ru.rasulov.customfields.impl;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.beehive.ClusterLockService;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldSearcher;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.api.dto.report.ChangeReport;
import ru.rasulov.common.api.dto.field.CreationStatus;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.NamedLock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.rasulov.customfields.impl.JiraMocks.SYSTEM;
import static ru.rasulov.customfields.impl.JiraMocks.TEXTFIELD;
import static ru.rasulov.customfields.impl.JiraMocks.TEXT_SEARCHER;
import static ru.rasulov.customfields.impl.JiraMocks.field;
import static ru.rasulov.customfields.impl.JiraMocks.searcher;
import static ru.rasulov.customfields.impl.JiraMocks.type;

/** What protects Jira and the caller around the creation itself: the lock, call-wide limits, honest EXISTS. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class CustomFieldCreatorGuardsTest {
    @org.junit.After
    public void closeUndoDatabases() { JiraMocks.closeJournals(); }

    private CustomFieldManager manager;
    private ClusterLock lock;
    private ClusterLockService lockService;
    private CustomFieldType<?, ?> textfield;
    private CustomFieldSearcher textSearcher;
    private CustomFieldCreatorImpl creator;

    @Before
    public void setUp() throws Exception {
        manager = mock(CustomFieldManager.class);
        lock = mock(ClusterLock.class);
        lockService = JiraMocks.lockService(lock);
        textfield = type(CustomFieldType.class, TEXTFIELD, "Text Field (single line)");
        textSearcher = searcher(TEXT_SEARCHER, TEXTFIELD);
        when(manager.getCustomFieldType(TEXTFIELD)).thenReturn((CustomFieldType) textfield);
        when(manager.getDefaultSearcher((CustomFieldType) textfield)).thenReturn(textSearcher);
        when(manager.getCustomFieldObjects()).thenReturn(Collections.<CustomField>emptyList());
        CustomField created = JiraMocks.createdField("customfield_10900");
        when(manager.createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList())).thenReturn(created);

        JiraAuthenticationContext authContext = mock(JiraAuthenticationContext.class);
        GlobalPermissionManager permissions = mock(GlobalPermissionManager.class);
        ApplicationUser admin = mock(ApplicationUser.class);
        when(authContext.getLoggedInUser()).thenReturn(admin);
        when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
        creator = JiraMocks.creator(manager, mock(OptionsManager.class), authContext, permissions, lockService);
    }

    // --- lock --------------------------------------------------------------------------------------------------

    @Test
    public void theLockIsTakenBeforeTheNamedFieldQueryAndReleasedAfterTheCreation() throws Exception {
        String planId = creator.createAll(request(true, plain("A"))).getPlanId();
        clearInvocations(lock, manager);
        creator.createAll(new CreateCustomFieldsRequest(false, null, planId));

        InOrder order = inOrder(lock, manager);
        order.verify(lock).tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS);
        order.verify(manager).getCustomFieldObjectsByNameIgnoreCase("A");
        order.verify(manager).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
        order.verify(lock).unlock();
    }

    // --- report ------------------------------------------------------------------------------------------------

    @Test
    public void reportSeparatesTheCreatedFieldFromTheOneThatAlreadyExistedAndIsOnlyInTheApply() {
        existingTextfield("Summary", "", textSearcher);
        CreateCustomFieldsResponse preview = creator.createAll(request(true, plain("Summary"), plain("Severity")));
        assertNull("a preview reports nothing", preview.getReport());

        CreateCustomFieldsResponse applied = creator.createAll(
                new CreateCustomFieldsRequest(false, null, preview.getPlanId()));

        ChangeReport report = applied.getReport();
        assertEquals(CreationStatus.EXISTS, applied.getResults().get(0).getStatus());
        assertEquals(1, report.getReused().size());
        assertEquals("customfield_10150", report.getReused().get(0).getId());
        assertEquals(ChangeKind.CUSTOM_FIELD, report.getReused().get(0).getKind());
        assertEquals(ChangeKind.CUSTOM_FIELD, report.getCreated().get(0).getKind());
        assertEquals("customfield_10900", report.getCreated().get(0).getId());
        assertEquals("Severity", report.getCreated().get(0).getName());
        assertEquals(ru.rasulov.common.impl.TestLinks.BASE_URL
                + "/secure/admin/ConfigureCustomField!default.jspa?customFieldId=10900",
                report.getCreated().get(0).getUrl());
    }


    @Test
    public void whenTheLockIsNotGrantedNothingIsTouchedAndTheLockIsNotReleased() throws Exception {
        String planId = creator.createAll(request(true, plain("A"))).getPlanId();
        clearInvocations(lock, manager);
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(false);

        try {
            creator.createAll(new CreateCustomFieldsRequest(false, null, planId));
            fail("expected BusyException");
        } catch (BusyException expected) {
            // ok
        }

        verify(lock, never()).unlock();
        verify(manager, never()).getCustomFieldObjectsByNameIgnoreCase("A");
    }

    @Test
    public void anInterruptedWaitIsBusyAndKeepsTheInterruptFlag() throws Exception {
        String planId = creator.createAll(request(true, plain("A"))).getPlanId();
        clearInvocations(lock, manager);
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

        try {
            creator.createAll(new CreateCustomFieldsRequest(false, null, planId));
            fail("expected BusyException");
        } catch (BusyException expected) {
            assertTrue("the interrupt must not be swallowed", Thread.interrupted());
        }
    }

    @Test
    public void invalidCallsNeverTakeTheLock() throws Exception {
        try {
            creator.createAll(request(true));
            fail("expected InvalidRequestException");
        } catch (InvalidRequestException expected) {
            // ok
        }

        verifyNoInteractions(lockService);
    }

    // --- call-wide options limit -------------------------------------------------------------------------------

    @Test
    public void optionsOfAllFieldsOfOneCallAreCappedIncludingChildren() throws Exception {
        registerOptionTypes();
        assertEquals(4, Limits.MAX_OPTIONS_PER_CALL / Limits.MAX_OPTIONS_PER_CONTEXT);
        List<FieldDefinition> atLimit = new ArrayList<FieldDefinition>();
        for (int i = 0; i < 4; i++) {
            atLimit.add(fullCascading("F" + i));
        }
        assertEquals(4, creator.createAll(new CreateCustomFieldsRequest(true, atLimit)).getResults().size());

        atLimit.add(new FieldDefinition("one more", null, "select", null, Arrays.asList(new OptionDefinition("a", null)), JiraMocks.GLOBAL, null));
        try {
            creator.createAll(new CreateCustomFieldsRequest(true, atLimit));
            fail("expected InvalidRequestException");
        } catch (InvalidRequestException expected) {
            // rejected as a whole, before anything was validated or locked
        }
        verify(lock, never()).tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS);
    }

    // --- EXISTS says what it ignored ---------------------------------------------------------------------------

    @Test
    public void existingFieldWithAnIdenticalRequestHasNoWarning() {
        CustomField existing = existingTextfield("Summary", "single line", textSearcher);

        FieldCreationResult result = create(new FieldDefinition("Summary", "single line", "textfield", null, null, JiraMocks.GLOBAL, null));

        assertEquals(CreationStatus.EXISTS, result.getStatus());
        assertNull(result.getMessage());
        assertNotNull(existing);
    }

    @Test
    public void existingFieldSaysWhichPartsOfTheRequestWereIgnored() {
        CustomFieldSearcher otherSearcher = searcher(SYSTEM + "exacttextsearcher", TEXTFIELD);
        when(manager.getCustomFieldSearcher(SYSTEM + "exacttextsearcher")).thenReturn(otherSearcher);
        existingTextfield("Summary", "old description", textSearcher);

        FieldCreationResult result = create(new FieldDefinition("Summary", "new description", "textfield", SYSTEM + "exacttextsearcher", null, JiraMocks.GLOBAL, null));

        assertEquals(CreationStatus.EXISTS, result.getStatus());
        assertNotNull(result.getMessage());
        assertTrue(result.getMessage(), result.getMessage().contains("searcherKey"));
        assertTrue(result.getMessage(), result.getMessage().contains("description"));
    }

    @Test
    public void existingSelectSaysThatItsOptionsWereNotApplied() {
        CustomFieldType select = type(MultipleSettableCustomFieldType.class, SYSTEM + "select", "Select List");
        when(manager.getCustomFieldType(SYSTEM + "select")).thenReturn(select);
        CustomField existing = field("customfield_10150", "Severity", select);
        when(manager.getCustomFieldObjectsByNameIgnoreCase("Severity")).thenReturn(Collections.singletonList(existing));

        FieldCreationResult result = create(new FieldDefinition("Severity", null, "select", null, Arrays.asList(new OptionDefinition("Low", null)), JiraMocks.GLOBAL, null));

        assertEquals(CreationStatus.EXISTS, result.getStatus());
        assertTrue(result.getMessage(), result.getMessage().contains("options"));
    }

    @Test
    public void aSearcherTheRequestDidNotNameIsNotReportedAsIgnored() {
        CustomField existing = existingTextfield("Summary", "", searcher(SYSTEM + "exacttextsearcher", TEXTFIELD));

        FieldCreationResult result = create(new FieldDefinition("Summary", null, "textfield", null, null, JiraMocks.GLOBAL, null));

        assertEquals(CreationStatus.EXISTS, result.getStatus());
        assertNull("the default searcher was not requested, so nothing was ignored", result.getMessage());
        assertNotNull(existing);
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private CustomField existingTextfield(String name, String description, CustomFieldSearcher searcher) {
        CustomField existing = field("customfield_10150", name, textfield);
        when(existing.getDescription()).thenReturn(description);
        when(existing.getCustomFieldSearcher()).thenReturn(searcher);
        JiraMocks.withContext(existing, "Global", true, Collections.<com.atlassian.jira.project.Project>emptyList(),
                true, Collections.<com.atlassian.jira.issue.issuetype.IssueType>emptyList());
        when(manager.getCustomFieldObjectsByNameIgnoreCase(name)).thenReturn(Collections.singletonList(existing));
        return existing;
    }

    private FieldCreationResult create(FieldDefinition definition) {
        return creator.createAll(request(true, definition)).getResults().get(0);
    }

    private static CreateCustomFieldsRequest request(boolean dryRun, FieldDefinition... fields) {
        return new CreateCustomFieldsRequest(dryRun, Arrays.asList(fields));
    }

    private static FieldDefinition plain(String name) {
        return new FieldDefinition(name, null, "textfield", null, null, JiraMocks.GLOBAL, null);
    }

    private void registerOptionTypes() {
        CustomFieldType select = type(MultipleSettableCustomFieldType.class, SYSTEM + "select", "Select List");
        CustomFieldType cascading = type(MultipleSettableCustomFieldType.class, SYSTEM + "cascadingselect",
                "Select List (cascading)");
        when(manager.getCustomFieldType(SYSTEM + "select")).thenReturn(select);
        when(manager.getCustomFieldType(SYSTEM + "cascadingselect")).thenReturn(cascading);
    }

    /** Valid under every per-list limit and holding exactly MAX_OPTIONS_PER_CONTEXT options: 5 parents x (1 + 99). */
    private static FieldDefinition fullCascading(String name) {
        List<OptionDefinition> parents = new ArrayList<OptionDefinition>();
        for (int p = 0; p < 5; p++) {
            List<OptionDefinition> children = new ArrayList<OptionDefinition>();
            for (int c = 0; c < 99; c++) {
                children.add(new OptionDefinition("child " + c, null));
            }
            parents.add(new OptionDefinition("parent " + p, children));
        }
        return new FieldDefinition(name, null, "cascadingselect", null, parents, JiraMocks.GLOBAL, null);
    }
}
