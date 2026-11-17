package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.*;
import com.atlassian.jira.issue.fields.layout.field.*;
import com.atlassian.jira.issue.fields.screen.*;
import com.atlassian.jira.issue.fields.screen.issuetype.*;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.project.*;
import com.atlassian.jira.user.ApplicationUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.*;
import ru.rasulov.common.impl.screen.ScreenLocks;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.screens.api.dto.layout.*;
import ru.rasulov.screens.api.dto.layout.RemoveFieldFromScreensRequest.ScreenTarget;
import ru.rasulov.screens.api.dto.layout.RemoveFieldFromScreensResponse.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real planner and native writer over mutable persisted screens, with owned single-use plans. */
public class ScreenFieldRemoverImplTest {
    private final LayoutFixture jira = new LayoutFixture();
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final ChangeJournal journal = mock(ChangeJournal.class);
    private final CustomFieldManager custom = mock(CustomFieldManager.class);
    private final FieldManager fields = mock(FieldManager.class);
    private final ActionPlanStore plans = mock(ActionPlanStore.class);
    private final ApplicationUser admin = mock(ApplicationUser.class);
    private final ScreenLayoutImpactReader impact = mock(ScreenLayoutImpactReader.class);
    private final ProjectManager projects = mock(ProjectManager.class);
    private final FieldScreenSchemeManager schemes = mock(FieldScreenSchemeManager.class);
    private final IssueTypeScreenSchemeManager tops = mock(IssueTypeScreenSchemeManager.class);
    private final FieldLayoutManager layouts = mock(FieldLayoutManager.class);
    private final Map<String, ActionPlanStore.Plan> saved = new HashMap<>();
    private final Map<String, String> owners = new HashMap<>();
    private final AtomicBoolean locked = new AtomicBoolean();
    private ScreenFieldRemoverImpl service;

    @Before @SuppressWarnings("unchecked") public void setUp() {
        when(admin.getKey()).thenReturn("admin"); when(guard.requireAdministrator()).thenReturn(admin);
        when(lock.run(anyString(), anyString(), any())).thenAnswer(call -> {
            assertEquals(ScreenLocks.EDIT_SCREENS, call.getArgument(0)); locked.set(true);
            try { return ((Supplier<Object>) call.getArgument(2)).get(); } finally { locked.set(false); }
        });
        when(journal.execute(anyString(), anyString(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
        when(plans.issue(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            String id = UUID.randomUUID().toString();
            ActionPlanStore.Plan plan = new ActionPlanStore.Plan(id, call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), Long.MAX_VALUE);
            saved.put(id, plan); owners.put(id, call.getArgument(1)); return plan;
        });
        when(plans.find(anyString(), anyString(), anyString())).thenAnswer(call -> {
            String id = call.getArgument(2);
            return Objects.equals(owners.get(id), call.getArgument(1)) ? saved.get(id) : null;
        });
        doAnswer(call -> { saved.remove(call.getArgument(0)); return null; }).when(plans).remove(anyString());
        when(impact.read(any())).thenAnswer(call -> {
            FieldScreen screen = call.getArgument(0);
            return new ScreenImpact(screen.getId(), screen.getName(), List.of("EDIT", "VIEW"), 1, List.of(), false, List.of());
        });
        for (String id : List.of("summary", "labels", "assignee", "priority")) orderable(id);
        ActionPlanCodec codec = new ActionPlanCodec();
        service = new ScreenFieldRemoverImpl(guard, lock, new PreviewPlanExecutor(plans, codec), codec, journal,
                custom, fields, jira.manager, impact, new RequiredOnCreate(projects, schemes, tops, layouts), new ScreenLayoutWriter(jira.manager));
        jira.beforeRemove = field -> assertTrue("native writes require the common screen lock", locked.get());
    }
    private void orderable(String id) {
        OrderableField field = mock(OrderableField.class); when(field.getId()).thenReturn(id); when(field.getName()).thenReturn(id);
        when(fields.isOrderableField(id)).thenReturn(true); when(fields.getOrderableField(id)).thenReturn(field);
    }
    private RemoveFieldFromScreensRequest request(List<String> ids, Long... screens) {
        return new RemoveFieldFromScreensRequest(true, ids, Arrays.stream(screens).map(ScreenTarget::new).toList(), null);
    }
    private RemoveFieldFromScreensResponse apply(RemoveFieldFromScreensResponse preview) {
        return service.remove(new RemoveFieldFromScreensRequest(false, null, null, preview.getPlanId()));
    }
    private void createRequired(LayoutFixture.Screen screen, String fieldId) {
        Project project = mock(Project.class); IssueType type = mock(IssueType.class);
        when(project.getId()).thenReturn(7L); when(project.getKey()).thenReturn("ABC"); when(project.getName()).thenReturn("Example");
        when(type.getId()).thenReturn("task"); when(type.getName()).thenReturn("Task"); when(project.getIssueTypes()).thenReturn(List.of(type));
        FieldScreenScheme scheme = mock(FieldScreenScheme.class); IssueTypeScreenScheme top = mock(IssueTypeScreenScheme.class);
        FieldScreenSchemeItem item = mock(FieldScreenSchemeItem.class);
        when(item.getIssueOperation()).thenReturn(IssueOperations.CREATE_ISSUE_OPERATION); when(item.getFieldScreen()).thenReturn(screen.screen);
        when(scheme.getFieldScreenSchemeItems()).thenReturn(List.of(item));
        when(schemes.getFieldScreenSchemes(screen.screen)).thenReturn(List.of(scheme));
        when(tops.getIssueTypeScreenSchemes(scheme)).thenReturn(List.of(top));
        GenericValue association = mock(GenericValue.class); when(association.getLong("id")).thenReturn(7L);
        when(tops.getProjects(top)).thenReturn(List.of(association)); when(projects.getProjectObj(7L)).thenReturn(project);
        when(tops.getIssueTypeScreenScheme(project)).thenReturn(top); when(top.getEffectiveFieldScreenScheme(type)).thenReturn(scheme);
        FieldConfigurationScheme config = mock(FieldConfigurationScheme.class); FieldLayout layout = mock(FieldLayout.class);
        when(layouts.getFieldConfigurationScheme(project)).thenReturn(config); when(config.getFieldLayoutId("task")).thenReturn(42L);
        when(layouts.getFieldLayout(42L)).thenReturn(layout); FieldLayoutItem required = mock(FieldLayoutItem.class);
        when(layout.getFieldLayoutItem(fieldId)).thenReturn(required); when(required.isRequired()).thenReturn(true);
    }

    @Test public void previewShowsOriginalTabRawPositionNeighborsAndWritesOnlyAfterApply() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        screen.rows.set(1, LayoutFixture.row(101, "labels", 2, false)); screen.rows.set(2, LayoutFixture.row(102, "assignee", 5, true));
        RemoveFieldFromScreensResponse preview = service.remove(request(List.of("labels"), 1L));
        assertEquals(LayoutStatus.WOULD_REMOVE, preview.getStatus()); assertNotNull(preview.getPlanId());
        FieldResult row = preview.getResults().get(0);
        assertEquals("Main", row.getTab()); assertEquals(Long.valueOf(10), row.getTabId()); assertEquals(Integer.valueOf(2), row.getPosition());
        assertEquals("summary", row.getBefore()); assertEquals("assignee", row.getAfter());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields()); verifyNoInteractions(lock, journal);
        RemoveFieldFromScreensResponse result = apply(preview);
        assertEquals(LayoutStatus.REMOVED, result.getStatus()); assertEquals(LayoutStatus.REMOVED, result.getResults().get(0).getStatus());
        assertEquals(List.of("summary", "assignee"), screen.fields());
        assertEquals("assignee", result.getActual().get(0).getTabs().get(0).getFields().get(1).getFieldId());
        assertEquals(1, result.getActual().get(0).getTabs().get(0).getFields().get(1).getPosition());
    }
    @Test public void absentFieldIsNoOpWithoutPlan() {
        jira.screen(1, "summary"); RemoveFieldFromScreensResponse result = service.remove(request(List.of("labels"), 1L));
        assertEquals(LayoutStatus.NOT_PRESENT, result.getStatus()); assertNull(result.getPlanId()); assertEquals(0, jira.removalCalls);
    }
    @Test public void requiredCreateFieldIsRemovableWithWarningAndProjectsForMakingItOptional() {
        LayoutFixture.Screen screen = jira.screen(1, "labels"); LayoutFixture.Screen other = jira.screen(2, "labels");
        createRequired(screen, "labels");
        RemoveFieldFromScreensResponse preview = service.remove(request(List.of("labels"), 1L, 2L));
        assertEquals(LayoutStatus.WOULD_REMOVE, preview.getStatus()); assertNull(preview.getErrorCode());
        assertNotNull(preview.getPlanId());
        FieldResult required = preview.getResults().get(0);
        assertEquals(LayoutStatus.WOULD_REMOVE, required.getStatus()); assertNull(required.getErrorCode());
        assertEquals(1, required.getRequiredPairs()); assertFalse(required.isRequiredProjectsTruncated());
        assertEquals("ABC", required.getRequiredProjects().get(0).getProjectKey());
        assertEquals(List.of("task"), required.getRequiredProjects().get(0).getIssueTypes().stream()
                .map(ru.rasulov.common.api.dto.IssueTypeRef::getId).toList());
        assertEquals("REQUIRED_ON_CREATE", required.getWarnings().get(0).getCode());
        FieldResult optional = preview.getResults().get(1);
        assertEquals(LayoutStatus.WOULD_REMOVE, optional.getStatus()); assertEquals(0, optional.getRequiredPairs());
        assertTrue(optional.getWarnings().isEmpty());
        assertEquals(LayoutStatus.REMOVED, apply(preview).getStatus());
        assertEquals(List.of(), screen.fields()); assertEquals(List.of(), other.fields());
    }
    @Test public void summaryIsProtectedOnlyOnCreateButRemovableFromView() {
        LayoutFixture.Screen screen = jira.screen(1, "summary");
        when(impact.read(screen.screen)).thenReturn(new ScreenImpact(1L, "Create", List.of("CREATE"), 1, List.of(), false, List.of()));
        RemoveFieldFromScreensResponse protectedResult = service.remove(request(List.of("summary"), 1L));
        assertEquals(LayoutErrorCode.PROTECTED_FIELD, protectedResult.getResults().get(0).getErrorCode()); assertNull(protectedResult.getPlanId());
        when(impact.read(screen.screen)).thenReturn(new ScreenImpact(1L, "View", List.of("VIEW"), 1, List.of(), false, List.of()));
        assertEquals(LayoutStatus.REMOVED, apply(service.remove(request(List.of("summary"), 1L))).getStatus());
        assertEquals(List.of(), screen.fields());
    }
    @Test public void viewAndEmptyTabWarningsAreComputedForTheCombinedRemovalOncePerScreen() {
        jira.screen(1, "labels", "priority");
        RemoveFieldFromScreensResponse preview = service.remove(request(List.of("labels", "priority"), 1L));
        assertEquals(1, preview.getScreens().size());
        assertEquals(List.of("HIDES_VALUES", "EMPTY_TAB"), preview.getScreens().get(0).getWarnings().stream().map(LayoutWarning::getCode).toList());
        assertEquals(LayoutStatus.REMOVED, apply(preview).getStatus());
    }
    @Test public void unknownOrRepeatedFieldRejectsTheWholeCallBeforeAnyScreenIsRead() {
        jira.screen(1, "labels");
        RemoveFieldFromScreensResponse unknown = service.remove(request(List.of("labels", "unknown"), 1L));
        assertEquals(LayoutErrorCode.UNKNOWN_FIELD, unknown.getErrorCode()); assertNull(unknown.getPlanId());
        assertEquals(LayoutErrorCode.VALIDATION_ERROR, service.remove(request(List.of("labels", "labels"), 1L)).getErrorCode());
        verify(jira.manager, never()).getFieldScreen(anyLong()); assertEquals(0, jira.removalCalls);
    }
    @Test public void bareCustomIdNormalizesAndAliasDuplicatesAreRejected() {
        CustomField field = mock(CustomField.class); when(field.getId()).thenReturn("customfield_123"); when(field.getName()).thenReturn("Detail");
        when(custom.getCustomFieldObject("customfield_123")).thenReturn(field); jira.screen(1, "customfield_123");
        assertEquals(LayoutStatus.WOULD_REMOVE, service.remove(request(List.of("123"), 1L)).getStatus());
        RemoveFieldFromScreensResponse repeated = service.remove(request(List.of("123", "customfield_123"), 1L));
        assertEquals(LayoutErrorCode.VALIDATION_ERROR, repeated.getErrorCode()); assertNull(repeated.getPlanId());
    }
    @Test public void rejectsLimitsAndDuplicateScreenIdsWithoutReadingScreens() {
        List<RemoveFieldFromScreensRequest> invalid = List.of(request(Collections.nCopies(51, "labels"), 1L),
                request(List.of("labels"), Collections.nCopies(101, 1L).toArray(new Long[0])),
                request(Collections.nCopies(50, "labels"), 1L,2L,3L,4L,5L,6L,7L,8L,9L,10L,11L),
                request(List.of("labels"), 1L, 1L), request(List.of("labels"), 0L), request(List.of(), 1L));
        for (RemoveFieldFromScreensRequest request : invalid) {
            RemoveFieldFromScreensResponse result = service.remove(request);
            assertEquals(LayoutErrorCode.VALIDATION_ERROR, result.getErrorCode()); assertNull(result.getPlanId());
        }
        verify(jira.manager, never()).getFieldScreen(anyLong());
    }
    @Test public void strictModeRejectsUnconfirmedApplyAndExtraPreviewInputs() {
        for (RemoveFieldFromScreensRequest invalid : Arrays.asList(null,
                new RemoveFieldFromScreensRequest(null, null, null, null),
                new RemoveFieldFromScreensRequest(false, List.of("labels"), List.of(new ScreenTarget(1L)), "plan"),
                new RemoveFieldFromScreensRequest(false, List.of(), null, "plan"),
                new RemoveFieldFromScreensRequest(false, null, null, " "),
                new RemoveFieldFromScreensRequest(true, List.of("labels"), List.of(new ScreenTarget(1L)), "plan"))) {
            try { service.remove(invalid); fail("Mode should be rejected"); } catch (InvalidRequestException expected) { }
        }
        assertEquals(LayoutErrorCode.PREVIEW_STALE, service.remove(new RemoveFieldFromScreensRequest(false,null,null,"missing")).getErrorCode());
    }
    @Test public void administratorGuardRunsBeforeModeValidationOrJiraReads() {
        when(guard.requireAdministrator()).thenThrow(new AccessException("Denied", true));
        try { service.remove(null); fail("Expected access denial"); } catch (AccessException expected) { }
        verifyNoInteractions(fields, custom, jira.manager, plans, lock, journal);
    }
    @Test public void changedUnrelatedFieldOrderMakesPlanStaleAndConsumesIt() {
        LayoutFixture.Screen screen = jira.screen(1, "labels", "summary", "assignee", "priority");
        RemoveFieldFromScreensResponse preview = service.remove(request(List.of("labels"), 1L));
        when(screen.rows.get(2).getPosition()).thenReturn(3); when(screen.rows.get(3).getPosition()).thenReturn(2);
        assertEquals(LayoutErrorCode.PREVIEW_STALE, apply(preview).getErrorCode()); assertEquals(0, jira.removalCalls);
        assertEquals(LayoutErrorCode.PREVIEW_STALE, apply(preview).getErrorCode());
    }
    @Test public void rawPositionChangeWithTheSameOrderAlsoMakesPlanStale() {
        LayoutFixture.Screen screen = jira.screen(1, "labels", "summary", "assignee");
        RemoveFieldFromScreensResponse preview = service.remove(request(List.of("labels"), 1L));
        when(screen.rows.get(2).getPosition()).thenReturn(7);
        assertEquals(LayoutErrorCode.PREVIEW_STALE, apply(preview).getErrorCode()); assertEquals(0, jira.removalCalls);
    }
    @Test public void foreignPlanCannotWriteOrConsumeTheOwnersPlanAndSuccessfulApplyIsSingleUse() {
        jira.screen(1, "summary", "labels"); RemoveFieldFromScreensResponse preview = service.remove(request(List.of("labels"), 1L));
        when(admin.getKey()).thenReturn("other"); assertEquals(LayoutErrorCode.PREVIEW_STALE, apply(preview).getErrorCode());
        when(admin.getKey()).thenReturn("admin"); assertEquals(LayoutStatus.REMOVED, apply(preview).getStatus());
        assertEquals(LayoutErrorCode.PREVIEW_STALE, apply(preview).getErrorCode()); assertEquals(1, jira.removalCalls);
    }
    @Test public void failedScreenDoesNotPreventIndependentScreensAndUnstartedFieldsAreExplicit() {
        LayoutFixture.Screen bad = jira.screen(1, "labels", "assignee", "priority");
        LayoutFixture.Screen good = jira.screen(2, "labels", "assignee", "priority");
        jira.beforeRemove = field -> { assertTrue(locked.get()); if (jira.removalCalls == 2) throw new IllegalStateException("failed second field"); };
        RemoveFieldFromScreensResponse result = apply(service.remove(request(List.of("labels","assignee","priority"),1L,2L)));
        assertEquals(LayoutStatus.PARTIAL, result.getStatus()); assertEquals(LayoutErrorCode.JIRA_ERROR,result.getErrorCode());
        assertEquals(List.of(LayoutStatus.REMOVED,LayoutStatus.FAILED,LayoutStatus.NOT_ATTEMPTED,
                LayoutStatus.REMOVED,LayoutStatus.REMOVED,LayoutStatus.REMOVED), result.getResults().stream().map(FieldResult::getStatus).toList());
        assertEquals(List.of("assignee", "priority"), bad.fields()); assertEquals(List.of(), good.fields());
    }
    @Test public void failedAfterPersistenceStillReportsRemovedWithActualState() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels");
        jira.afterRemove = field -> { throw new IllegalStateException("after write"); };
        RemoveFieldFromScreensResponse result = apply(service.remove(request(List.of("labels"),1L)));
        assertEquals(LayoutStatus.PARTIAL, result.getStatus()); assertEquals(LayoutStatus.REMOVED,result.getResults().get(0).getStatus());
        assertEquals(List.of("summary"),screen.fields()); assertEquals(1,jira.removalCalls);
    }
    @Test public void readbackMismatchIsReportedOnActualScreenWhileTheRemovedFieldStaysRemoved() {
        LayoutFixture.Screen screen = jira.screen(1,"summary","labels");
        jira.afterRemove = field -> when(screen.rows.get(0).isShownWhenEmpty()).thenReturn(false);
        RemoveFieldFromScreensResponse result = apply(service.remove(request(List.of("labels"),1L)));
        assertEquals(LayoutStatus.PARTIAL,result.getStatus()); assertEquals(LayoutErrorCode.JIRA_ERROR,result.getErrorCode());
        assertEquals(LayoutStatus.REMOVED,result.getResults().get(0).getStatus());
        assertNull(result.getResults().get(0).getErrorCode());
        assertNull(result.getResults().get(0).getMessage());
        assertEquals(LayoutStatus.FAILED, result.getActual().get(0).getStatus());
        assertTrue(result.getActual().get(0).getMessage().contains("differs"));
        assertFalse(result.getActual().get(0).getTabs().get(0).getFields().get(0).isShownWhenEmpty());
        assertEquals(List.of("summary"),screen.fields());
    }
    @Test public void failedReadbackSerializesUnknownWithNoInventedTabs() throws Exception {
        jira.screen(1,"summary","labels"); jira.readbackFails = true;
        RemoveFieldFromScreensResponse result = apply(service.remove(request(List.of("labels"),1L)));
        assertEquals(LayoutStatus.UNKNOWN,result.getStatus()); assertEquals(LayoutStatus.UNKNOWN,result.getResults().get(0).getStatus());
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(result));
        assertEquals("UNKNOWN",json.get("actual").get(0).get("status").textValue());
        assertTrue(json.get("actual").get(0).get("tabs").isNull()); assertEquals(1,jira.removalCalls);
    }
    @Test public void observedLayoutSerializesThroughJacksonOneWithoutInternalRecords() throws Exception {
        jira.screen(1,"summary","labels");
        RemoveFieldFromScreensResponse result = apply(service.remove(request(List.of("labels"),1L)));
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(result));
        JsonNode field = json.get("actual").get(0).get("tabs").get(0).get("fields").get(0);
        assertEquals("summary",field.get("fieldId").textValue()); assertEquals(100L,field.get("layoutItemId").longValue());
        assertEquals(0,field.get("position").intValue()); assertTrue(field.get("shownWhenEmpty").booleanValue());
        assertTrue(json.get("undoWarning").textValue().contains("add_field_to_screens"));
    }
    @Test public void checkpointFailureAfterAWritePreservesItsResultAndMarksNextScreensNotAttempted() {
        jira.screen(1,"labels"); LayoutFixture.Screen untouched = jira.screen(2,"labels");
        AtomicInteger boundaries = new AtomicInteger();
        doAnswer(call -> { if (boundaries.incrementAndGet() == 2) throw new IllegalStateException("journal boundary failed"); return null; }).when(journal).checkpoint();
        RemoveFieldFromScreensResponse result = apply(service.remove(request(List.of("labels"),1L,2L)));
        assertEquals(LayoutStatus.PARTIAL,result.getStatus());
        assertEquals(List.of(LayoutStatus.REMOVED,LayoutStatus.NOT_ATTEMPTED),result.getResults().stream().map(FieldResult::getStatus).toList());
        assertEquals(List.of("labels"),untouched.fields()); assertEquals(1,jira.removalCalls);
    }
    @Test public void unknownScreenRemainsFailedWhileOtherExplicitScreensCanBeRemoved() {
        jira.screen(1, "summary", "labels");
        RemoveFieldFromScreensResponse preview = service.remove(request(List.of("labels"), 99L, 1L));
        assertNotNull(preview.getPlanId());
        assertEquals(LayoutErrorCode.UNKNOWN_SCREEN, preview.getResults().get(0).getErrorCode());
        RemoveFieldFromScreensResponse result = apply(preview);
        assertEquals(LayoutStatus.PARTIAL, result.getStatus());
        assertEquals(LayoutErrorCode.UNKNOWN_SCREEN, result.getResults().get(0).getErrorCode());
        assertEquals(LayoutStatus.REMOVED, result.getResults().get(1).getStatus());
        assertEquals(List.of("summary"), jira.screens.get(1L).fields());
    }
    @Test public void acceptsExactlyFiveHundredPairsAcrossOneHundredExplicitScreens() {
        List<String> ids = List.of("summary", "labels", "assignee", "priority", "description");
        orderable("description");
        Long[] targets = new Long[100];
        for (int i = 0; i < targets.length; i++) {
            targets[i] = i + 1L;
            jira.screen(targets[i], ids.toArray(new String[0]));
        }
        RemoveFieldFromScreensResponse preview = service.remove(request(ids, targets));
        assertNotNull(preview.getPlanId());
        assertEquals(LayoutStatus.REMOVED, apply(preview).getStatus());
        for (LayoutFixture.Screen screen : jira.screens.values()) assertEquals(List.of(), screen.fields());
        assertEquals(500, jira.removalCalls);
    }
    @Test public void previewRefreshesOnceBeforeReadingTheWholeScreenBatch() {
        jira.screen(1, "labels");
        jira.screen(2, "labels");
        RemoveFieldFromScreensResponse result = service.remove(request(List.of("labels"), 1L, 2L));
        assertEquals(List.of(LayoutStatus.WOULD_REMOVE, LayoutStatus.WOULD_REMOVE),
                result.getResults().stream().map(FieldResult::getStatus).toList());
        org.mockito.InOrder reads = inOrder(jira.manager);
        reads.verify(jira.manager).refresh();
        reads.verify(jira.manager).getFieldScreen(1L);
        reads.verify(jira.manager).getFieldScreen(2L);
        reads.verifyNoMoreInteractions();
        assertEquals(0, jira.removalCalls);
    }
}
