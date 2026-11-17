package ru.rasulov.customfields.impl.screen;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.CustomFieldCreationLimits;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.common.api.dto.field.context.ContextSummary;
import ru.rasulov.customfields.api.dto.screen.AddToScreensRequest;
import ru.rasulov.customfields.api.dto.screen.AddToScreensResponse;
import ru.rasulov.customfields.api.dto.screen.ScreenPlan;
import ru.rasulov.customfields.api.dto.screen.ScreenErrorCode;
import ru.rasulov.customfields.api.dto.screen.ScreenStatus;
import ru.rasulov.customfields.api.dto.screen.FieldScreensResult;
import ru.rasulov.customfields.api.dto.screen.ScreenTarget;
import ru.rasulov.customfields.api.dto.screen.ScreenSummary;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.customfields.impl.JiraMocks;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.customfields.impl.context.ContextSummarizer;
import ru.rasulov.common.api.dto.screen.FieldPlace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Administrator check, finding the field, the lock around real writes, and the results of the call. */
public class FieldScreenServiceImplTest {
    @org.junit.After
    public void closeUndoDatabases() { ru.rasulov.customfields.impl.JiraMocks.closeJournals(); }
    private static final String FIELD = "customfield_10500";

    private CustomFieldManager customFieldManager;
    private FieldManager fieldManager;
    private JiraAuthenticationContext authenticationContext;
    private GlobalPermissionManager permissions;
    private ApplicationUser admin;
    private ClusterLock lock;
    private ScreenPlanner planner;
    private FieldScreenManager screenManager;
    private CustomField field;
    private FieldScreenServiceImpl service;
    private ru.rasulov.common.impl.ActionPlanStore plans;

    @Before
    public void setUp() throws Exception {
        customFieldManager = mock(CustomFieldManager.class);
        fieldManager = mock(FieldManager.class);
        authenticationContext = mock(JiraAuthenticationContext.class);
        permissions = mock(GlobalPermissionManager.class);
        admin = mock(ApplicationUser.class);
        when(admin.getKey()).thenReturn("admin");
        when(authenticationContext.getLoggedInUser()).thenReturn(admin);
        when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);

        lock = mock(ClusterLock.class);
        planner = mock(ScreenPlanner.class);
        screenManager = mock(FieldScreenManager.class);
        field = mock(CustomField.class);
        when(field.getId()).thenReturn(FIELD);
        when(field.getName()).thenReturn("One");
        when(customFieldManager.getCustomFieldObject(FIELD)).thenReturn(field);
        when(field.getConfigurationSchemes()).thenReturn(Collections.<com.atlassian.jira.issue.fields.config.FieldConfigScheme>emptyList());

        ru.rasulov.common.impl.undo.ChangeJournal journal =
                ru.rasulov.customfields.impl.FieldJournalTestSupport.journal("admin");
        plans = ru.rasulov.common.impl.InMemoryActionPlans.create();
        service = new FieldScreenServiceImpl(customFieldManager, fieldManager,
                new AdministratorGuard(authenticationContext, permissions),
                new NamedLock(JiraMocks.lockService(lock)), planner, new ScreenWriter(screenManager),
                new ContextSummarizer(), new ru.rasulov.common.impl.PreviewPlanExecutor(plans,
                        new ru.rasulov.common.impl.ActionPlanCodec()),
                new ru.rasulov.common.impl.ActionPlanCodec(), journal) {
            @Override
            public AddToScreensResponse addToScreens(AddToScreensRequest request) {
                return ru.rasulov.common.impl.LegacyWriteCalls.screens(request, super::addToScreens);
            }
        };
    }


    @Test
    public void explicitModeAndPreviewTokenAreRequiredByTheService() {
        try {
            service.addToScreens(new AddToScreensRequest(null, null, null, null));
            fail("missing dryRun must be rejected");
        } catch (InvalidRequestException expected) {
            assertTrue(expected.getMessage().contains("dryRun"));
        }
        AddToScreensResponse stale = service.addToScreens(new AddToScreensRequest(null, false, null, "missing"));
        assertEquals("PREVIEW_STALE", stale.getErrorCode());
    }
    // --- who and what ------------------------------------------------------------------------------------------

    @Test
    public void anonymousAndNonAdministratorCannotPlanOrAdd() {
        when(authenticationContext.getLoggedInUser()).thenReturn(null);
        assertDenied(true);

        when(authenticationContext.getLoggedInUser()).thenReturn(admin);
        when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(false);
        assertDenied(false);
        verifyNoInteractions(planner);
    }

    @Test
    public void anUnknownFieldIsAnInvalidRequest() {
        try {
            service.planScreens("customfield_1");
            fail("expected InvalidRequestException");
        } catch (InvalidRequestException expected) {
            // ok
        }
        try {
            service.addToScreens(new AddToScreensRequest(ids("customfield_1"), false, targets(5L)));
            fail("expected InvalidRequestException");
        } catch (InvalidRequestException expected) {
            // ok
        }
    }

    @Test
    public void aBlankFieldIdIsAnInvalidRequest() {
        try {
            service.planScreens("  ");
            fail("expected InvalidRequestException");
        } catch (InvalidRequestException expected) {
            // ok
        }
    }

    @Test
    public void aBareNumberIsTakenForACustomFieldId() {
        when(customFieldManager.getCustomFieldObject("customfield_10500")).thenReturn(field);
        ScreenPlan plan = emptyPlan();
        when(planner.plan(any(CustomField.class), any(List.class))).thenReturn(plan);

        assertSame(plan, service.planScreens("10500"));
    }

    @Test
    public void thePlanIsWorkedOutFromTheFieldsRealContexts() {
        ScreenPlan plan = emptyPlan();
        when(planner.plan(any(CustomField.class), any(List.class))).thenReturn(plan);

        assertSame(plan, service.planScreens(FIELD));

        verify(planner).plan(field, Collections.<ContextSummary>emptyList());
    }

    // --- adding ------------------------------------------------------------------------------------------------

    @Test
    public void noFieldsNoScreensAndTooManyOfEitherAreInvalidRequests() {
        assertInvalid(new AddToScreensRequest(null, false, targets(5L)));
        assertInvalid(new AddToScreensRequest(Collections.<String>emptyList(), false, targets(5L)));
        assertInvalid(new AddToScreensRequest(ids(FIELD), false, null));
        assertInvalid(new AddToScreensRequest(ids(FIELD), false, Collections.<ScreenTarget>emptyList()));
        List<ScreenTarget> tooManyScreens = new ArrayList<ScreenTarget>();
        for (long i = 0; i <= Limits.MAX_SCREENS_PER_CALL; i++) {
            tooManyScreens.add(new ScreenTarget(i, null, null, null));
        }
        assertInvalid(new AddToScreensRequest(ids(FIELD), false, tooManyScreens));
        List<String> tooManyFields = new ArrayList<String>();
        for (int i = 0; i <= CustomFieldCreationLimits.MAX_FIELDS_PER_CALL; i++) {
            tooManyFields.add("customfield_" + i);
        }
        assertInvalid(new AddToScreensRequest(tooManyFields, false, targets(5L)));
        assertInvalid(null);
    }

    @Test
    public void fieldsTimesScreensOverTheAdditionLimitIsRefusedBeforeAnythingIsLookedUpOrWritten() {
        int fields = Limits.MAX_SCREEN_ADDITIONS_PER_CALL / Limits.MAX_SCREENS_PER_CALL + 1;
        List<String> fieldIds = new ArrayList<String>();
        for (int i = 0; i < fields; i++) {
            fieldIds.add("customfield_" + i);
        }
        List<ScreenTarget> screens = new ArrayList<ScreenTarget>();
        for (long i = 0; i < Limits.MAX_SCREENS_PER_CALL; i++) {
            screens.add(new ScreenTarget(i, null, null, null));
        }

        assertInvalid(new AddToScreensRequest(fieldIds, false, screens));

        verifyNoInteractions(lock, screenManager);
    }

    @Test
    public void anUnknownRepeatedOrEmptyFieldRefusesTheWholeCallAndNothingIsWritten() throws Exception {
        FieldScreenTab tab = screen(5L, "A screen");
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);

        assertInvalid(new AddToScreensRequest(ids(FIELD, "customfield_404"), false, targets(5L)));
        assertInvalid(new AddToScreensRequest(ids(FIELD, "10500"), false, targets(5L)));
        assertInvalid(new AddToScreensRequest(ids(FIELD, "  "), false, targets(5L)));
        assertInvalid(new AddToScreensRequest(ids(FIELD, null), false, targets(5L)));

        verify(tab, never()).addFieldScreenLayoutItem(anyString(), anyInt());
        verifyNoInteractions(lock);
    }

    @Test
    public void realWritesRunUnderTheLockAndDryRunsDoNot() throws Exception {
        screen(5L, "A screen");
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);

        service.addToScreens(new AddToScreensRequest(ids(FIELD), true, targets(5L)));
        verifyNoInteractions(lock);

        AddToScreensResponse response = service.addToScreens(new AddToScreensRequest(ids(FIELD), false, targets(5L)));

        assertEquals(ScreenStatus.ADDED, response.getFields().get(0).getResults().get(0).getStatus());
        org.mockito.InOrder order = inOrder(lock);
        order.verify(lock).tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS);
        order.verify(lock).unlock();
    }

    @Test
    public void aBatchTakesTheLockOnceAndEveryFieldGoesToEveryScreen() throws Exception {
        FieldScreenTab first = screen(5L, "First");
        FieldScreenTab second = screen(6L, "Second");
        customField("customfield_10501", "Two");
        customField("customfield_10502", "Three");
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);

        AddToScreensResponse response = service.addToScreens(new AddToScreensRequest(
                ids(FIELD, "customfield_10501", "10502"), false, targets(5L, 6L)));

        assertEquals(Arrays.asList(FIELD, "customfield_10501", "customfield_10502"), fieldIdsOf(response));
        assertEquals("names come back so that the user can be told in names",
                Arrays.asList("One", "Two", "Three"), namesOf(response));
        for (FieldScreensResult one : response.getFields()) {
            assertEquals(Arrays.asList(ScreenStatus.ADDED, ScreenStatus.ADDED),
                    Arrays.asList(one.getResults().get(0).getStatus(), one.getResults().get(1).getStatus()));
        }
        for (FieldScreenTab tab : Arrays.asList(first, second)) {
            verify(tab).addFieldScreenLayoutItem(FIELD, 0);
            verify(tab).addFieldScreenLayoutItem("customfield_10501", 1);
            verify(tab).addFieldScreenLayoutItem("customfield_10502", 2);
        }
        verify(lock, org.mockito.Mockito.times(1)).tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS);
        verify(lock, org.mockito.Mockito.times(1)).unlock();
    }

    @Test
    public void aSystemFieldIsPreviewedAndAddedLikeACustomField() throws Exception {
        FieldScreenTab tab = screen(5L, "NOMI 11KP");
        OrderableField assignee = mock(OrderableField.class);
        when(assignee.getId()).thenReturn("assignee");
        when(assignee.getName()).thenReturn("Assignee");
        when(fieldManager.isOrderableField("assignee")).thenReturn(true);
        when(fieldManager.getOrderableField("assignee")).thenReturn(assignee);
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);

        AddToScreensResponse preview = service.addToScreens(new AddToScreensRequest(
                ids("assignee", FIELD), true, targets(5L)));
        assertEquals(Arrays.asList("assignee", FIELD), fieldIdsOf(preview));
        assertEquals(ScreenStatus.WOULD_ADD, preview.getFields().get(0).getResults().get(0).getStatus());
        verify(tab, never()).addFieldScreenLayoutItem(anyString(), anyInt());

        AddToScreensResponse applied = service.addToScreens(new AddToScreensRequest(
                null, false, null, preview.getPlanId()));
        assertEquals(ScreenStatus.ADDED, applied.getFields().get(0).getResults().get(0).getStatus());
        verify(tab).addFieldScreenLayoutItem("assignee", 0);
        verify(tab).addFieldScreenLayoutItem(FIELD, 1);
    }

    @Test
    public void aSystemFieldThatCannotBeOnAScreenRefusesTheWholeCall() throws Exception {
        FieldScreenTab tab = screen(5L, "A screen");
        when(fieldManager.isOrderableField("votes")).thenReturn(false);

        assertInvalid(new AddToScreensRequest(ids(FIELD, "votes"), true, targets(5L)));

        verify(fieldManager, never()).getOrderableField("votes");
        verify(tab, never()).addFieldScreenLayoutItem(anyString(), anyInt());
        verifyNoInteractions(lock);
    }

    @Test
    public void inABatchEachFieldIsJudgedOnItsOwnAndADryRunWritesNothing() throws Exception {
        FieldScreenTab tab = screen(5L, "A screen");
        customField("customfield_10501", "Two");
        when(screenManager.getFieldScreen(5L).containsField(FIELD)).thenReturn(true);

        AddToScreensResponse response = service.addToScreens(new AddToScreensRequest(
                ids(FIELD, "customfield_10501"), true, targets(5L)));

        assertEquals(ScreenStatus.ALREADY_PRESENT, response.getFields().get(0).getResults().get(0).getStatus());
        assertEquals(ScreenStatus.WOULD_ADD, response.getFields().get(1).getResults().get(0).getStatus());
        verify(tab, never()).addFieldScreenLayoutItem(anyString(), anyInt());
        verifyNoInteractions(lock);
    }

    @Test
    public void whenTheLockIsBusyNothingIsChanged() throws Exception {
        FieldScreenTab tab = screen(5L, "A screen");
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(false);

        try {
            service.addToScreens(new AddToScreensRequest(ids(FIELD), false, targets(5L)));
            fail("expected BusyException");
        } catch (BusyException expected) {
            // ok
        }

        verify(tab, never()).addFieldScreenLayoutItem(anyString(), anyInt());
        verify(lock, never()).unlock();
    }

    @Test
    public void everyScreenGetsItsOwnResultInRequestOrderAndOneFailureDoesNotStopTheRest() throws Exception {
        screen(5L, "First");
        screen(7L, "Third");
        customField("customfield_10501", "Two");
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);

        AddToScreensResponse response = service.addToScreens(new AddToScreensRequest(
                ids(FIELD, "customfield_10501"), false, targets(5L, 6L, 7L)));

        for (FieldScreensResult one : response.getFields()) {
            assertEquals(Arrays.asList(ScreenStatus.ADDED, ScreenStatus.FAILED, ScreenStatus.ADDED),
                    Arrays.asList(one.getResults().get(0).getStatus(), one.getResults().get(1).getStatus(),
                            one.getResults().get(2).getStatus()));
            assertEquals(ScreenErrorCode.UNKNOWN_SCREEN, one.getResults().get(1).getErrorCode());
        }
    }

    @Test
    public void anAmbiguousWriteOnALaterScreenKeepsTheEarlierResultAndNeverHidesWhatWasWritten() throws Exception {
        NativeTab one = nativeTab(50L, "Field Tab", "summary");
        NativeTab two = nativeTab(60L, "Field Tab", "summary");
        NativeTab three = nativeTab(70L, "Field Tab", "summary");
        nativeScreen(5L, "First", one);
        nativeScreen(6L, "Second", two);
        nativeScreen(7L, "Third", three);
        // Jira stores the item on the second screen, then the call fails: the caller cannot know it was written.
        doAnswer(call -> {
            two.insert(call.getArgument(0), call.getArgument(1));
            throw new IllegalStateException("connection reset");
        }).when(two.tab).addFieldScreenLayoutItem(anyString(), anyInt());
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);

        AddToScreensResponse response = service.addToScreens(new AddToScreensRequest(ids(FIELD), false, targets(5L, 6L, 7L)));

        List<ru.rasulov.customfields.api.dto.screen.ScreenResult> results = response.getFields().get(0).getResults();
        assertEquals(Arrays.asList(ScreenStatus.ADDED, ScreenStatus.UNKNOWN, ScreenStatus.NOT_ATTEMPTED),
                Arrays.asList(results.get(0).getStatus(), results.get(1).getStatus(), results.get(2).getStatus()));
        assertTrue(one.fieldIds().contains(FIELD));
        assertTrue("the ambiguous write really persisted", two.fieldIds().contains(FIELD));
        assertEquals("nothing is written after an ambiguous outcome", Arrays.asList("summary"), three.fieldIds());
    }

    @Test
    public void aReadFailureBeforeTheWriteOfALaterScreenIsAFailedResultNotAnAbortOfTheBatch() throws Exception {
        NativeTab one = nativeTab(50L, "Field Tab", "summary");
        NativeTab two = nativeTab(60L, "Field Tab", "summary");
        NativeTab three = nativeTab(70L, "Field Tab", "summary");
        nativeScreen(5L, "First", one);
        nativeScreen(6L, "Second", two);
        nativeScreen(7L, "Third", three);
        when(two.tab.getFieldScreenLayoutItem(0)).thenThrow(new IllegalStateException("tab unreadable"));
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);

        AddToScreensResponse response = service.addToScreens(new AddToScreensRequest(ids(FIELD), false, targets(5L, 6L, 7L)));

        List<ru.rasulov.customfields.api.dto.screen.ScreenResult> results = response.getFields().get(0).getResults();
        assertEquals(Arrays.asList(ScreenStatus.ADDED, ScreenStatus.FAILED, ScreenStatus.NOT_ATTEMPTED),
                Arrays.asList(results.get(0).getStatus(), results.get(1).getStatus(), results.get(2).getStatus()));
        assertEquals(ScreenErrorCode.JIRA_ERROR, results.get(1).getErrorCode());
        assertEquals(Arrays.asList("summary"), two.fieldIds());
        assertEquals(Arrays.asList("summary"), three.fieldIds());
    }

    @Test
    public void theSameScreenTwiceIsRefusedTheSecondTimeAndAddedOncePerField() throws Exception {
        FieldScreenTab tab = screen(5L, "A screen");
        customField("customfield_10501", "Two");
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);

        AddToScreensResponse response = service.addToScreens(new AddToScreensRequest(
                ids(FIELD, "customfield_10501"), false, targets(5L, 5L)));

        for (FieldScreensResult one : response.getFields()) {
            assertEquals(ScreenStatus.ADDED, one.getResults().get(0).getStatus());
            assertEquals(ScreenErrorCode.VALIDATION_ERROR, one.getResults().get(1).getErrorCode());
        }
        verify(tab, org.mockito.Mockito.times(1)).addFieldScreenLayoutItem(FIELD, 0);
        verify(tab, org.mockito.Mockito.times(1)).addFieldScreenLayoutItem("customfield_10501", 1);
    }

    @Test
    public void confirmed205ScreenSelectionIsAppliedInThreeExactBatches() throws Exception {
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        java.util.Set<Long> expected = new java.util.TreeSet<Long>();
        java.util.Set<Long> changed = new java.util.TreeSet<Long>();
        for (long id = 1000; id < 1205; id++) {
            screen(id, "Screen " + id);
            expected.add(id);
        }
        screen(9999L, "Unselected");
        List<AddToScreensResponse> previews = new ArrayList<AddToScreensResponse>();
        for (long start = 1000; start < 1205; start += 100) {
            List<ScreenTarget> selected = new ArrayList<ScreenTarget>();
            for (long id = start; id < Math.min(start + 100, 1205); id++) selected.add(new ScreenTarget(id, null, null, null));
            previews.add(service.addToScreens(new AddToScreensRequest(ids(FIELD), true, selected)));
        }
        for (long id : expected) assertFalse(screenManager.getFieldScreen(id).containsField(FIELD));
        for (AddToScreensResponse preview : previews) {
            AddToScreensResponse result = service.addToScreens(new AddToScreensRequest(
                    null, false, null, preview.getPlanId()));
            for (ru.rasulov.customfields.api.dto.screen.ScreenResult one : result.getFields().get(0).getResults()) {
                assertEquals(ScreenStatus.ADDED, one.getStatus());
                assertTrue(changed.add(one.getScreenId()));
            }
        }
        assertEquals(expected, changed);
        for (long id : expected) assertTrue(screenManager.getFieldScreen(id).containsField(FIELD));
        assertFalse(screenManager.getFieldScreen(9999L).containsField(FIELD));
    }

    @Test
    public void changedSelectedTargetMakesPreviewStaleWithoutWritingOtherTargets() throws Exception {
        screen(5L, "First");
        screen(6L, "Second");
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        AddToScreensResponse preview = service.addToScreens(new AddToScreensRequest(ids(FIELD), true, targets(5L, 6L)));
        when(screenManager.getFieldScreen(6L).getName()).thenReturn("Renamed");
        AddToScreensResponse result = service.addToScreens(new AddToScreensRequest(null, false, null, preview.getPlanId()));
        assertEquals("PREVIEW_STALE", result.getErrorCode());
        assertFalse(screenManager.getFieldScreen(5L).containsField(FIELD));
        assertFalse(screenManager.getFieldScreen(6L).containsField(FIELD));
    }
    @Test
    public void afterKeepsTwoAndThreeNewFieldsInRequestOrderAndPreviewUsesTheOriginalAnchor() throws Exception {
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        customField("customfield_10501", "Two");
        customField("customfield_10502", "Three");
        for (int count : new int[]{2, 3}) {
            long screenId = 20 + count;
            NativeTab tab = nativeTab(screenId * 10, "Field Tab", "summary", "description");
            nativeScreen(screenId, "Ordered", tab);
            List<String> fields = ids(FIELD, "customfield_10501", "customfield_10502").subList(0, count);
            AddToScreensResponse preview = service.addToScreens(new AddToScreensRequest(fields, true,
                    Collections.singletonList(new ScreenTarget(screenId, null, FieldPlace.AFTER, "summary"))));
            for (FieldScreensResult one : preview.getFields()) {
                assertEquals(ScreenStatus.WOULD_ADD, one.getResults().get(0).getStatus());
            }
            assertEquals(ids("summary", "description"), tab.fieldIds());
            verify(tab.tab, never()).addFieldScreenLayoutItem(anyString(), anyInt());
            AddToScreensResponse applied = apply(preview);
            assertAllAdded(applied);
            List<String> expected = new ArrayList<>(ids("summary"));
            expected.addAll(fields);
            expected.add("description");
            assertEquals(expected, tab.fieldIds());
        }
    }

    @Test
    public void firstLastAndBeforeKeepThreeNewFieldsInOrder() throws Exception {
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        customField("customfield_10501", "Two");
        customField("customfield_10502", "Three");
        List<String> fields = ids(FIELD, "customfield_10501", "customfield_10502");
        for (FieldPlace place : new FieldPlace[]{FieldPlace.FIRST, FieldPlace.LAST, FieldPlace.BEFORE}) {
            long screenId = 30 + place.ordinal();
            NativeTab tab = nativeTab(screenId * 10, "Chosen", "summary", "description");
            NativeTab other = nativeTab(screenId * 10 + 1, "Untouched", "assignee");
            nativeScreen(screenId, "Ordered", other, tab);
            AddToScreensResponse preview = service.addToScreens(new AddToScreensRequest(fields, true,
                    Collections.singletonList(new ScreenTarget(screenId, "Chosen", place,
                            place == FieldPlace.BEFORE ? "description" : null))));
            assertEquals(ids("summary", "description"), tab.fieldIds());
            AddToScreensResponse applied = apply(preview);
            assertAllAdded(applied);
            List<String> expected = new ArrayList<>();
            if (place != FieldPlace.FIRST) expected.add("summary");
            if (place == FieldPlace.LAST) expected.add("description");
            expected.addAll(fields);
            if (place == FieldPlace.FIRST) expected.add("summary");
            if (place != FieldPlace.LAST) expected.add("description");
            assertEquals(place.name(), expected, tab.fieldIds());
            assertEquals(ids("assignee"), other.fieldIds());
        }
    }

    @Test
    public void alreadyPresentFieldsOnAnotherTabNeverMoveOrReplaceTheSuccessfulInsertionCursor() throws Exception {
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        customField("customfield_10501", "Two");
        customField("customfield_10502", "Three");
        customField("customfield_10503", "Four");
        for (FieldPlace place : new FieldPlace[]{FieldPlace.FIRST, FieldPlace.AFTER}) {
            long screenId = 40 + place.ordinal();
            NativeTab chosen = nativeTab(screenId * 10, "Chosen", "summary", "description");
            NativeTab other = nativeTab(screenId * 10 + 1, "Other", FIELD, "customfield_10502");
            nativeScreen(screenId, "Ordered", chosen, other);
            AddToScreensResponse preview = service.addToScreens(new AddToScreensRequest(
                    ids(FIELD, "customfield_10501", "customfield_10502", "customfield_10503"), true,
                    Collections.singletonList(new ScreenTarget(screenId, "Chosen", place,
                            place == FieldPlace.AFTER ? "summary" : null))));
            AddToScreensResponse applied = apply(preview);
            assertEquals(ScreenStatus.ALREADY_PRESENT, applied.getFields().get(0).getResults().get(0).getStatus());
            assertEquals(ScreenStatus.ADDED, applied.getFields().get(1).getResults().get(0).getStatus());
            assertEquals(ScreenStatus.ALREADY_PRESENT, applied.getFields().get(2).getResults().get(0).getStatus());
            assertEquals(ScreenStatus.ADDED, applied.getFields().get(3).getResults().get(0).getStatus());
            assertEquals(place == FieldPlace.FIRST
                    ? ids("customfield_10501", "customfield_10503", "summary", "description")
                    : ids("summary", "customfield_10501", "customfield_10503", "description"), chosen.fieldIds());
            assertEquals(ids(FIELD, "customfield_10502"), other.fieldIds());
        }
    }

    @Test
    public void insertionCursorsAreIndependentForEachScreen() throws Exception {
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        customField("customfield_10501", "Two");
        customField("customfield_10502", "Three");
        NativeTab first = nativeTab(501, "Chosen", "summary", "description");
        NativeTab other = nativeTab(502, "Other", FIELD);
        nativeScreen(50, "First", first, other);
        NativeTab second = nativeTab(511, "Chosen", "summary", "description");
        nativeScreen(51, "Second", second);
        AddToScreensResponse preview = service.addToScreens(new AddToScreensRequest(
                ids(FIELD, "customfield_10501", "customfield_10502"), true, Arrays.asList(
                new ScreenTarget(50L, "Chosen", FieldPlace.AFTER, "summary"),
                new ScreenTarget(51L, "Chosen", FieldPlace.AFTER, "summary"))));
        AddToScreensResponse applied = apply(preview);
        assertEquals(ScreenStatus.ALREADY_PRESENT, applied.getFields().get(0).getResults().get(0).getStatus());
        assertEquals(ScreenStatus.ADDED, applied.getFields().get(0).getResults().get(1).getStatus());
        for (int i = 1; i < 3; i++) {
            for (ru.rasulov.customfields.api.dto.screen.ScreenResult result : applied.getFields().get(i).getResults()) {
                assertEquals(ScreenStatus.ADDED, result.getStatus());
            }
        }
        assertEquals(ids("summary", "customfield_10501", "customfield_10502", "description"), first.fieldIds());
        assertEquals(ids("summary", FIELD, "customfield_10501", "customfield_10502", "description"), second.fieldIds());
        assertEquals(ids(FIELD), other.fieldIds());
    }

    @Test
    public void anUnknownAnchorFailsOnlyItsScreenInPreviewAndApply() throws Exception {
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        customField("customfield_10501", "Two");
        NativeTab missing = nativeTab(601, "Chosen", "description");
        NativeTab wrongTab = nativeTab(602, "Other", "summary");
        nativeScreen(60, "Missing anchor", missing, wrongTab);
        NativeTab valid = nativeTab(611, "Chosen", "summary", "description");
        nativeScreen(61, "Valid", valid);
        AddToScreensResponse preview = service.addToScreens(new AddToScreensRequest(
                ids(FIELD, "customfield_10501"), true, Arrays.asList(
                new ScreenTarget(60L, "Chosen", FieldPlace.AFTER, "summary"),
                new ScreenTarget(61L, "Chosen", FieldPlace.AFTER, "summary"))));
        for (FieldScreensResult one : preview.getFields()) {
            assertEquals(ScreenErrorCode.UNKNOWN_ANCHOR, one.getResults().get(0).getErrorCode());
            assertEquals(ScreenStatus.WOULD_ADD, one.getResults().get(1).getStatus());
        }
        AddToScreensResponse applied = apply(preview);
        for (FieldScreensResult one : applied.getFields()) {
            assertEquals(ScreenErrorCode.UNKNOWN_ANCHOR, one.getResults().get(0).getErrorCode());
            assertEquals(ScreenStatus.ADDED, one.getResults().get(1).getStatus());
        }
        assertEquals(ids("description"), missing.fieldIds());
        assertEquals(ids("summary"), wrongTab.fieldIds());
        assertEquals(ids("summary", FIELD, "customfield_10501", "description"), valid.fieldIds());
    }

    @Test
    public void changedAnchorOrderOrOnlyRawPositionsMakesPositionedPlansStaleAndConsumesThem() throws Exception {
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        for (int change = 0; change < 3; change++) {
            long screenId = 70 + change;
            NativeTab changed = nativeTab(screenId * 10, "Chosen", "summary", "description");
            nativeScreen(screenId, "Changed", changed);
            NativeTab independent = nativeTab(screenId * 10 + 1, "Chosen", "summary");
            nativeScreen(screenId + 10, "Independent", independent);
            List<ScreenTarget> selected = Arrays.asList(
                    new ScreenTarget(screenId, null, FieldPlace.AFTER, "summary"),
                    new ScreenTarget(screenId + 10, null, FieldPlace.AFTER, "summary"));
            AddToScreensResponse preview = service.addToScreens(new AddToScreensRequest(ids(FIELD), true, selected));
            List<FieldScreenLayoutItem> original = new ArrayList<>(changed.items);
            if (change == 0) changed.items.remove(0);
            if (change == 1) {
                Collections.swap(changed.items, 0, 1);
                changed.resequence(0);
            }
            if (change == 2) when(changed.items.get(1).getPosition()).thenReturn(9);
            if (change != 0) {
                AddToScreensResponse sameStatuses = service.addToScreens(
                        new AddToScreensRequest(ids(FIELD), true, selected));
                assertEquals(ScreenStatus.WOULD_ADD, sameStatuses.getFields().get(0).getResults().get(0).getStatus());
            }
            assertEquals("PREVIEW_STALE", apply(preview).getErrorCode());
            assertFalse(changed.fieldIds().contains(FIELD));
            assertEquals(ids("summary"), independent.fieldIds());
            changed.items.clear();
            changed.items.addAll(original);
            changed.resequence(0);
            assertEquals("a stale plan is consumed even after the layout is restored",
                    "PREVIEW_STALE", apply(preview).getErrorCode());
            verify(changed.tab, never()).addFieldScreenLayoutItem(anyString(), anyInt());
            verify(independent.tab, never()).addFieldScreenLayoutItem(anyString(), anyInt());
        }
    }

    @Test
    public void unpositionedPreviewKeepsTheLegacyFingerprintAndSerializedResponse() throws Exception {
        NativeTab tab = nativeTab(901, "Field Tab", "summary");
        nativeScreen(90, "Legacy", tab);
        when(lock.tryLock(NamedLock.WAIT_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        AddToScreensResponse preview = service.addToScreens(new AddToScreensRequest(ids(FIELD), true, targets(90L)));
        AddToScreensResponse withoutPlan = new AddToScreensResponse(true, preview.getFields());
        ru.rasulov.common.impl.ActionPlanCodec codec = new ru.rasulov.common.impl.ActionPlanCodec();
        assertEquals(codec.fingerprint(withoutPlan),
                plans.find("add_field_to_screens", "admin", preview.getPlanId()).getFingerprint());
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String expected = "{\"dryRun\":true,\"undoWarning\":\"This operation cannot be rolled back through Undo.\","
                + "\"fields\":[{\"fieldId\":\"customfield_10500\",\"name\":\"One\",\"results\":["
                + "{\"screenId\":90,\"name\":\"Legacy\",\"status\":\"WOULD_ADD\",\"tab\":\"Field Tab\","
                + "\"errorCode\":null,\"message\":null}]}],\"planId\":null,\"expiresAt\":null,"
                + "\"errorCode\":null,\"message\":null}";
        assertEquals(mapper.readTree(expected), mapper.readTree(mapper.writeValueAsString(withoutPlan)));
        // Raw layout changes intentionally do not invalidate unpositioned previews; the write renumbers the gap first.
        when(tab.items.get(0).getPosition()).thenReturn(9);
        AddToScreensResponse applied = apply(preview);
        assertAllAdded(applied);
        com.fasterxml.jackson.databind.node.ObjectNode expectedApplied = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(expected);
        expectedApplied.put("dryRun", false);
        ((com.fasterxml.jackson.databind.node.ObjectNode) expectedApplied.get("fields").get(0).get("results").get(0))
                .put("status", "ADDED");
        assertEquals(expectedApplied, mapper.readTree(mapper.writeValueAsString(applied)));
        assertEquals(ids(FIELD), tab.fieldIds().subList(1, 2));
        verify(tab.tab).moveFieldScreenLayoutItemToPosition(org.mockito.ArgumentMatchers.anyMap());
        verify(tab.tab).addFieldScreenLayoutItem(FIELD, 1);
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private AddToScreensResponse apply(AddToScreensResponse preview) {
        assertTrue("a writable preview must issue a plan", preview.getPlanId() != null);
        return service.addToScreens(new AddToScreensRequest(null, false, null, preview.getPlanId()));
    }

    private static void assertAllAdded(AddToScreensResponse response) {
        assertEquals(null, response.getErrorCode());
        for (FieldScreensResult field : response.getFields()) {
            for (ru.rasulov.customfields.api.dto.screen.ScreenResult result : field.getResults()) {
                assertEquals(ScreenStatus.ADDED, result.getStatus());
            }
        }
    }

    private static NativeTab nativeTab(long id, String name, String... fields) {
        return new NativeTab(id, name, fields);
    }

    private void nativeScreen(long id, String name, NativeTab... tabs) {
        FieldScreen screen = mock(FieldScreen.class);
        when(screen.getId()).thenReturn(id);
        when(screen.getName()).thenReturn(name);
        List<FieldScreenTab> nativeTabs = new ArrayList<>();
        for (int i = 0; i < tabs.length; i++) {
            nativeTabs.add(tabs[i].tab);
            when(tabs[i].tab.getPosition()).thenReturn(i);
        }
        when(screen.getTabs()).thenReturn(nativeTabs);
        when(screen.containsField(anyString())).thenAnswer(call ->
                Arrays.stream(tabs).anyMatch(tab -> tab.fieldIds().contains(call.getArgument(0))));
        when(screenManager.getFieldScreen(id)).thenReturn(screen);
    }

    /** Native index insertion resequences only the tail, as Jira's indexed add does. */
    private static final class NativeTab {
        private final FieldScreenTab tab = mock(FieldScreenTab.class);
        private final List<FieldScreenLayoutItem> items = new ArrayList<>();
        private final long id;
        private long nextItemId;

        private NativeTab(long id, String name, String... fields) {
            this.id = id;
            when(tab.getId()).thenReturn(id);
            when(tab.getName()).thenReturn(name);
            when(tab.getFieldScreenLayoutItems()).thenAnswer(call -> new ArrayList<>(items));
            when(tab.getFieldScreenLayoutItem(anyInt())).thenAnswer(call -> items.get((Integer) call.getArgument(0)));
            doAnswer(call -> {
                java.util.Map<Integer, FieldScreenLayoutItem> order = call.getArgument(0);
                items.removeAll(order.values());
                for (java.util.Map.Entry<Integer, FieldScreenLayoutItem> entry : order.entrySet()) items.add(entry.getKey(), entry.getValue());
                resequence(0);
                return null;
            }).when(tab).moveFieldScreenLayoutItemToPosition(org.mockito.ArgumentMatchers.anyMap());
            doAnswer(call -> {
                insert(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(tab).addFieldScreenLayoutItem(anyString(), anyInt());
            for (String field : fields) insert(field, items.size());
        }

        private void insert(String field, int index) {
            FieldScreenLayoutItem item = mock(FieldScreenLayoutItem.class);
            when(item.getId()).thenReturn(id * 1000 + nextItemId++);
            when(item.getFieldId()).thenReturn(field);
            items.add(index, item);
            resequence(index);
        }

        private void resequence(int from) {
            for (int i = from; i < items.size(); i++) when(items.get(i).getPosition()).thenReturn(i);
        }

        private List<String> fieldIds() {
            List<String> fields = new ArrayList<>();
            for (FieldScreenLayoutItem item : items) fields.add(item.getFieldId());
            return fields;
        }
    }

    /** A screen with one tab; returns the tab. */
    private FieldScreenTab screen(long id, String name) {
        FieldScreenTab tab = mock(FieldScreenTab.class);
        when(tab.getName()).thenReturn("Field Tab");
        when(tab.getId()).thenReturn(id * 10);
        List<FieldScreenLayoutItem> items = new ArrayList<>();
        when(tab.getFieldScreenLayoutItems()).thenAnswer(call -> new ArrayList<>(items));
        when(tab.getFieldScreenLayoutItem(anyInt())).thenAnswer(call -> items.get((Integer) call.getArgument(0)));
        doAnswer(call -> {
            int index = call.getArgument(1);
            FieldScreenLayoutItem item = mock(FieldScreenLayoutItem.class);
            when(item.getId()).thenReturn(id * 1000 + items.size());
            when(item.getFieldId()).thenReturn(call.getArgument(0));
            when(item.getPosition()).thenReturn(index);
            items.add(index, item);
            return null;
        }).when(tab).addFieldScreenLayoutItem(anyString(), anyInt());
        FieldScreen screen = mock(FieldScreen.class);
        when(screen.getId()).thenReturn(id);
        when(screen.containsField(anyString())).thenAnswer(call ->
                items.stream().anyMatch(item -> item.getFieldId().equals(call.getArgument(0))));
        when(screen.getName()).thenReturn(name);
        when(screen.getTabs()).thenReturn(Arrays.asList(tab));
        when(screenManager.getFieldScreen(id)).thenReturn(screen);
        return tab;
    }

    private static ScreenPlan emptyPlan() {
        return new ScreenPlan(FIELD, "Field", Collections.<ContextSummary>emptyList(), 0, false,
                new ScreenSummary(0, 0, 0, 0), Collections.<ru.rasulov.customfields.api.dto.screen.ScreenCandidate>emptyList());
    }

    private static List<ScreenTarget> targets(Long... ids) {
        List<ScreenTarget> targets = new ArrayList<ScreenTarget>();
        for (Long id : ids) {
            targets.add(new ScreenTarget(id, null, null, null));
        }
        return targets;
    }

    private static List<String> ids(String... ids) {
        return Arrays.asList(ids);
    }

    /** Another custom field the manager knows, under the id and name given. */
    private void customField(String id, String name) {
        CustomField other = mock(CustomField.class);
        when(other.getId()).thenReturn(id);
        when(other.getName()).thenReturn(name);
        when(customFieldManager.getCustomFieldObject(id)).thenReturn(other);
    }

    private static List<String> fieldIdsOf(AddToScreensResponse response) {
        List<String> ids = new ArrayList<String>();
        for (FieldScreensResult one : response.getFields()) {
            ids.add(one.getFieldId());
        }
        return ids;
    }

    private static List<String> namesOf(AddToScreensResponse response) {
        List<String> names = new ArrayList<String>();
        for (FieldScreensResult one : response.getFields()) {
            names.add(one.getName());
        }
        return names;
    }

    private void assertInvalid(AddToScreensRequest request) {
        try {
            service.addToScreens(request);
            fail("expected InvalidRequestException");
        } catch (InvalidRequestException expected) {
            // ok
        }
    }

    private void assertDenied(boolean anonymous) {
        try {
            service.planScreens(FIELD);
            fail("expected AccessException");
        } catch (AccessException e) {
            assertEquals(anonymous, e.isAnonymous());
        }
        try {
            service.addToScreens(new AddToScreensRequest(ids(FIELD), true, targets(5L)));
            fail("expected AccessException");
        } catch (AccessException e) {
            assertEquals(anonymous, e.isAnonymous());
        }
    }
}
