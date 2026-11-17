package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.user.ApplicationUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.dto.screen.FieldPlace;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.*;
import ru.rasulov.common.impl.screen.ScreenLocks;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.screens.api.dto.layout.*;
import ru.rasulov.screens.api.dto.layout.EditScreenLayoutResponse.MoveResult;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public preview/apply behavior over the real planner and writer, owned single-use plans and native list semantics. */
public class ScreenLayoutEditorImplTest {
    private final LayoutFixture jira = new LayoutFixture();
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final ChangeJournal journal = mock(ChangeJournal.class);
    private final FieldManager fields = mock(FieldManager.class);
    private final ActionPlanStore plans = mock(ActionPlanStore.class);
    private final ApplicationUser admin = mock(ApplicationUser.class);
    private final ScreenLayoutImpactReader impact = mock(ScreenLayoutImpactReader.class);
    private final Map<String, ActionPlanStore.Plan> saved = new HashMap<>();
    private final AtomicBoolean locked = new AtomicBoolean();
    private final Map<String, String> owners = new HashMap<>();
    private final RequiredOnCreate required = new RequiredOnCreate(
            mock(com.atlassian.jira.project.ProjectManager.class),
            mock(com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager.class),
            mock(com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager.class),
            mock(com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager.class));
    private final com.atlassian.jira.issue.CustomFieldManager customFields = mock(com.atlassian.jira.issue.CustomFieldManager.class);
    private ScreenLayoutEditorImpl service;

    @Before @SuppressWarnings("unchecked") public void setUp() {
        when(admin.getKey()).thenReturn("admin"); when(guard.requireAdministrator()).thenReturn(admin);
        when(lock.run(anyString(), anyString(), any())).thenAnswer(call -> {
            assertEquals(ScreenLocks.EDIT_SCREENS, call.getArgument(0)); assertEquals("screens", call.getArgument(1));
            locked.set(true);
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
            ActionPlanStore.Plan plan = saved.get(call.getArgument(2));
            return plan != null && Objects.equals(owners.get(call.getArgument(2)), call.getArgument(1)) ? plan : null;
        });
        doAnswer(call -> { saved.remove(call.getArgument(0)); return null; }).when(plans).remove(anyString());
        when(impact.read(any())).thenAnswer(call -> {
            FieldScreen screen = call.getArgument(0);
            return new ScreenImpact(screen.getId(), screen.getName(), List.of("EDIT", "VIEW"), 1, List.of(), false, List.of());
        });
        for (String id : List.of("summary", "labels", "assignee", "priority", "duedate", "description")) {
            OrderableField field = mock(OrderableField.class); when(field.getId()).thenReturn(id); when(field.getName()).thenReturn("Name " + id);
            when(fields.getOrderableField(id)).thenReturn(field); when(fields.isOrderableField(id)).thenReturn(true);
            when(fields.getField(id)).thenReturn(field);
        }
        ActionPlanCodec codec = new ActionPlanCodec();
        service = new ScreenLayoutEditorImpl(guard, lock, new PreviewPlanExecutor(plans, codec), codec, journal, jira.manager,
                customFields, fields, required, impact, new ScreenLayoutPlanner(fields), new ScreenLayoutWriter(jira.manager));
        jira.beforeMove = tab -> assertTrue("native map write requires common screen lock", locked.get());
        jira.beforeRemove = field -> assertTrue("native removal requires common screen lock", locked.get());
        jira.beforeAdd = field -> assertTrue("native insertion requires common screen lock", locked.get());
    }

    private ScreenFieldMove move(String field, FieldPlace place) { return new ScreenFieldMove(field, null, place, null, null); }
    private EditScreenLayoutRequest request(ScreenFieldMove... moves) { return new EditScreenLayoutRequest(true, 1L, null, List.of(moves), null, null); }
    private EditScreenLayoutResponse apply(EditScreenLayoutResponse preview) { return service.edit(new EditScreenLayoutRequest(false, null, null, null, null, preview.getPlanId())); }
    private List<LayoutStatus> statuses(EditScreenLayoutResponse response) { return response.getMoves().stream().map(MoveResult::getStatus).toList(); }

    private List<String> changes(EditScreenLayoutResponse response) {
        assertTrue(response.getFieldChanges().isAvailable());
        return response.getFieldChanges().getItems().stream().map(c -> c.getFieldId() + " — " + c.getAction()).toList();
    }

    @Test public void previewDoesNotWriteAndApplyUsesObservedOrderUnderSharedLock() throws Exception {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        screen.rows.get(1).setPosition(2); screen.rows.get(2).setPosition(5);
        EditScreenLayoutResponse preview = service.edit(request(move("assignee", FieldPlace.FIRST)));
        assertEquals(LayoutStatus.WOULD_EDIT, preview.getStatus()); assertNotNull(preview.getPlanId());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields()); assertEquals(0, jira.moveCalls);
        assertEquals("Name assignee", preview.getTabs().get(0).getAfter().get(0).getName()); verifyNoInteractions(lock, journal);
        verify(jira.manager, times(1)).refresh();
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.EDITED, result.getStatus()); assertEquals(List.of(LayoutStatus.MOVED), statuses(result));
        assertEquals(List.of("assignee", "summary", "labels"), screen.fields()); assertEquals(1, jira.moveCalls);
        assertEquals("assignee", result.getActual().get(0).getFields().get(0).getFieldId());
        JsonNode json = new ObjectMapper().valueToTree(result);
        assertEquals("assignee", json.path("actual").get(0).path("fields").get(0).path("fieldId").asText());
        assertTrue(json.path("actual").get(0).path("fields").get(0).has("shownWhenEmpty"));
        assertTrue(json.path("undoWarning").asText().contains("new edit_screen_layout preview"));
        assertNull(result.getPlanId()); verify(journal, times(2)).checkpoint();
        assertEquals(List.of(), changes(result));
    }

    @Test public void anyPlanningErrorRejectsAllOtherMovesWithoutPlan() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        EditScreenLayoutResponse preview = service.edit(request(move("labels", FieldPlace.FIRST),
                new ScreenFieldMove("assignee", null, FieldPlace.AFTER, "missing", null), move("summary", FieldPlace.LAST)));
        assertEquals(LayoutStatus.FAILED, preview.getStatus()); assertNull(preview.getPlanId());
        assertFalse(statuses(preview).contains(LayoutStatus.WOULD_MOVE));
        assertEquals(LayoutErrorCode.UNKNOWN_ANCHOR, preview.getErrorCode());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields()); verifyNoInteractions(plans, lock, journal);
    }

    @Test public void alreadyPlacedIssuesNoPlanAndRepeatedMovesAreRejected() {
        jira.screen(1, "summary", "labels");
        EditScreenLayoutResponse noOp = service.edit(request(move("summary", FieldPlace.FIRST)));
        assertEquals(LayoutStatus.ALREADY_IN_PLACE, noOp.getStatus()); assertNull(noOp.getPlanId());
        EditScreenLayoutResponse repeated = service.edit(request(move("labels", FieldPlace.FIRST), move("labels", FieldPlace.LAST)));
        assertEquals(LayoutErrorCode.VALIDATION_ERROR, repeated.getErrorCode()); assertNull(repeated.getPlanId());
        assertEquals(1, repeated.getValidationErrors().size()); verifyNoInteractions(plans);
    }

    @Test public void sequentialMovedAnchorAndTransferAreAppliedAsOneFinalLayout() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee"); screen.tab(11, "Details", "priority");
        EditScreenLayoutResponse preview = service.edit(request(new ScreenFieldMove("labels", "Details", FieldPlace.FIRST, null, null),
                new ScreenFieldMove("assignee", "Details", FieldPlace.AFTER, "labels", null)));
        assertEquals(2, preview.getTabs().size());
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.EDITED, result.getStatus()); assertEquals(List.of(LayoutStatus.MOVED, LayoutStatus.MOVED), statuses(result));
        assertEquals(List.of("summary"), screen.fields()); assertEquals(2, jira.additionCalls);
        assertEquals(List.of("labels", "assignee", "priority"), result.getActual().get(1).getFields().stream().map(TabLayoutDiff.FieldRef::getFieldId).toList());
        assertFalse(result.getActual().get(1).getFields().get(0).isShownWhenEmpty()); assertTrue(result.getActual().get(1).getFields().get(1).isShownWhenEmpty());
        assertEquals(List.of(), changes(result));
    }

    @Test public void shiftingByTransferCanSatisfyAnotherRequestedMoveWithoutMapCall() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee"); screen.tab(11, "Details");
        EditScreenLayoutResponse preview = service.edit(request(move("labels", FieldPlace.FIRST),
                new ScreenFieldMove("summary", "Details", FieldPlace.FIRST, null, null)));
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.EDITED, result.getStatus()); assertEquals(List.of(LayoutStatus.MOVED, LayoutStatus.MOVED), statuses(result));
        assertEquals(List.of("labels", "assignee"), screen.fields()); assertEquals(0, jira.moveCalls);
    }

    @Test public void strictPreviewAndApplyModesRejectExtraScopeIncludingEmptyMoves() throws Exception {
        List<EditScreenLayoutRequest> invalid = Arrays.asList(null, new EditScreenLayoutRequest(null, 1L, null, List.of(), null, null),
                new EditScreenLayoutRequest(true, 1L, null, List.of(move("labels", FieldPlace.FIRST)), null, "plan"),
                new EditScreenLayoutRequest(false, null, null, null, null, null), new EditScreenLayoutRequest(false, null, null, null, null, " "),
                new EditScreenLayoutRequest(false, 1L, null, null, null, "plan"), new EditScreenLayoutRequest(false, null, null, List.of(), null, "plan"));
        for (EditScreenLayoutRequest request : invalid) {
            try { service.edit(request); fail("Mode must reject extra scope"); } catch (InvalidRequestException expected) { }
        }
        EditScreenLayoutRequest jsonApply = new ObjectMapper().readValue("{\"dryRun\":false,\"planId\":\"missing\"}", EditScreenLayoutRequest.class);
        assertEquals(LayoutErrorCode.PREVIEW_STALE, service.edit(jsonApply).getErrorCode());
        verifyNoInteractions(jira.manager);
    }

    @Test public void guardRunsBeforeModeValidationAndReads() {
        when(guard.requireAdministrator()).thenThrow(new AccessException("Denied", true));
        try { service.edit(null); fail("Expected administrator denial"); } catch (AccessException expected) { }
        verifyNoInteractions(jira.manager, fields, plans, lock, journal);
    }

    @Test public void missingScreenAndInvalidLimitsNeverIssuePlans() {
        assertEquals(LayoutErrorCode.UNKNOWN_SCREEN, service.edit(request(move("labels", FieldPlace.FIRST))).getErrorCode());
        for (EditScreenLayoutRequest invalid : List.of(new EditScreenLayoutRequest(true, 0L, null, List.of(move("labels", FieldPlace.FIRST)), null, null),
                new EditScreenLayoutRequest(true, 1L, null, null, null, null), new EditScreenLayoutRequest(true, 1L, null, List.of(), null, null),
                new EditScreenLayoutRequest(true, 1L, null, Collections.nCopies(51, move("labels", FieldPlace.FIRST)), null, null))) {
            EditScreenLayoutResponse result = service.edit(invalid);
            assertEquals(LayoutErrorCode.VALIDATION_ERROR, result.getErrorCode()); assertNull(result.getPlanId());
        }
        verifyNoInteractions(plans);
    }

    @Test public void unknownForeignAndConsumedPlansCannotWriteOrConsumeOwnersPlan() {
        jira.screen(1, "summary", "labels"); EditScreenLayoutResponse preview = service.edit(request(move("labels", FieldPlace.FIRST)));
        when(admin.getKey()).thenReturn("other"); assertEquals(LayoutErrorCode.PREVIEW_STALE, apply(preview).getErrorCode());
        assertTrue(saved.containsKey(preview.getPlanId())); assertEquals(0, jira.moveCalls);
        when(admin.getKey()).thenReturn("admin"); assertEquals(LayoutStatus.EDITED, apply(preview).getStatus());
        assertEquals(LayoutErrorCode.PREVIEW_STALE, apply(preview).getErrorCode()); assertEquals(1, jira.moveCalls);
        assertEquals(LayoutErrorCode.PREVIEW_STALE, service.edit(new EditScreenLayoutRequest(false, null, null, null, null, "missing")).getErrorCode());
    }

    @Test public void changedOrderInvalidatesAndConsumesPreview() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        EditScreenLayoutResponse preview = service.edit(request(move("assignee", FieldPlace.FIRST)));
        Collections.swap(screen.rows, 0, 1); for (int i = 0; i < screen.rows.size(); i++) screen.rows.get(i).setPosition(i);
        assertEquals(LayoutErrorCode.PREVIEW_STALE, apply(preview).getErrorCode()); assertEquals(0, jira.moveCalls);
        assertFalse(saved.containsKey(preview.getPlanId())); assertEquals(List.of("labels", "summary", "assignee"), screen.fields());
    }

    @Test public void rawPositionChangeOnUnaffectedTabIsStillPreviewStale() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels"); LayoutFixture.Tab untouched = screen.tab(11, "Details", "priority");
        EditScreenLayoutResponse preview = service.edit(request(move("labels", FieldPlace.FIRST))); untouched.rows.get(0).setPosition(7);
        assertEquals(LayoutErrorCode.PREVIEW_STALE, apply(preview).getErrorCode()); assertEquals(0, jira.moveCalls);
    }

    @Test public void changedImpactInvalidatesPreviewInsteadOfExpandingApproval() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels"); EditScreenLayoutResponse preview = service.edit(request(move("labels", FieldPlace.FIRST)));
        when(impact.read(screen.screen)).thenReturn(new ScreenImpact(1L, "Screen 1", List.of("EDIT", "VIEW"), 2, List.of(), true,
                List.of(new LayoutWarning("SHARED_SCREEN", "Shared with another project"), new LayoutWarning("DEFAULT_SCREEN", "Default screen"))));
        assertEquals(LayoutErrorCode.PREVIEW_STALE, apply(preview).getErrorCode()); assertEquals(0, jira.moveCalls);
    }

    @Test public void partialResultUsesReadbackForMovedFailedAndNotAttemptedRows() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels"); screen.tab(11, "Details", "priority", "duedate"); screen.tab(12, "Other", "assignee", "description");
        EditScreenLayoutResponse preview = service.edit(request(move("labels", FieldPlace.FIRST), move("duedate", FieldPlace.FIRST), move("description", FieldPlace.FIRST)));
        jira.beforeMove = tab -> { assertTrue(locked.get()); if (tab == 11) throw new IllegalStateException("second tab failed"); };
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.PARTIAL, result.getStatus()); assertEquals(LayoutErrorCode.JIRA_ERROR, result.getErrorCode());
        assertEquals(List.of(LayoutStatus.MOVED, LayoutStatus.FAILED, LayoutStatus.NOT_ATTEMPTED), statuses(result));
        assertEquals("labels", result.getActual().get(0).getFields().get(0).getFieldId());
        assertEquals("priority", result.getActual().get(1).getFields().get(0).getFieldId()); assertEquals(2, jira.moveCalls);
        assertFalse(saved.containsKey(preview.getPlanId()));
    }

    @Test public void persistedMismatchFailsScreenButDoesNotMislabelObservedMovedField() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        EditScreenLayoutResponse preview = service.edit(request(move("assignee", FieldPlace.FIRST)));
        jira.afterMove = tab -> screen.rows.get(1).setShowWhenEmpty(false);
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.FAILED, result.getStatus()); assertEquals(LayoutErrorCode.JIRA_ERROR, result.getErrorCode());
        assertEquals(LayoutStatus.MOVED, result.getMoves().get(0).getStatus());
        assertEquals("summary", result.getActual().get(0).getFields().get(1).getFieldId());
        assertFalse(result.getActual().get(0).getFields().get(1).isShownWhenEmpty()); assertEquals(1, jira.moveCalls);
    }

    @Test public void changedRequestedFlagCannotBeReportedMovedJustBecauseIndexMatches() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        EditScreenLayoutResponse preview = service.edit(request(move("assignee", FieldPlace.FIRST)));
        jira.afterMove = tab -> screen.rows.get(0).setShowWhenEmpty(false);
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.FAILED, result.getStatus()); assertEquals(LayoutStatus.FAILED, result.getMoves().get(0).getStatus());
        assertFalse(result.getActual().get(0).getFields().get(0).isShownWhenEmpty());
    }

    @Test public void unreadableActualIsUnknownAndNeverSerializedAsPlannedAfter() throws Exception {
        jira.screen(1, "summary", "labels"); EditScreenLayoutResponse preview = service.edit(request(move("labels", FieldPlace.FIRST))); jira.readbackFails = true;
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.UNKNOWN, result.getStatus()); assertEquals(LayoutStatus.UNKNOWN, result.getMoves().get(0).getStatus()); assertNull(result.getActual());
        assertTrue(new ObjectMapper().valueToTree(result).path("actual").isNull()); assertEquals(1, jira.moveCalls);
    }

    @Test public void failedTransferRestorationIsReportedFromReadback() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee"); screen.tab(11, "Details", "priority");
        EditScreenLayoutResponse preview = service.edit(request(new ScreenFieldMove("assignee", "Details", FieldPlace.FIRST, null, null)));
        AtomicInteger adds = new AtomicInteger(); jira.beforeAdd = field -> { assertTrue(locked.get()); if (adds.incrementAndGet() == 1) throw new IllegalStateException("target add failed"); };
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.PARTIAL, result.getStatus()); assertEquals(LayoutStatus.RESTORED, result.getMoves().get(0).getStatus());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields()); assertTrue(result.getActual().get(0).getFields().get(2).isShownWhenEmpty());
        assertEquals(2, jira.additionCalls); assertFalse(saved.containsKey(preview.getPlanId()));
        assertEquals(List.of(), changes(result));
    }

    @Test public void secondTransferRestorationKeepsEarlierMoveAndReportsPartialCall() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        screen.tab(11, "Details", "priority");
        EditScreenLayoutResponse preview = service.edit(request(
                new ScreenFieldMove("labels", "Details", FieldPlace.FIRST, null, null),
                new ScreenFieldMove("assignee", "Details", FieldPlace.AFTER, "labels", null)));
        AtomicInteger adds = new AtomicInteger();
        jira.beforeAdd = field -> { if (adds.incrementAndGet() == 2) throw new IllegalStateException("second target add failed"); };
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.PARTIAL, result.getStatus());
        assertEquals(List.of(LayoutStatus.MOVED, LayoutStatus.RESTORED), statuses(result));
        assertEquals(List.of("summary", "assignee"), screen.fields());
        assertEquals(List.of("labels", "priority"), result.getActual().get(1).getFields().stream().map(TabLayoutDiff.FieldRef::getFieldId).toList());
        assertEquals(3, jira.additionCalls);
    }

    @Test public void failedReturnWithReadableScreenIsFailedRowNotUnknown() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        screen.tab(11, "Details", "priority");
        EditScreenLayoutResponse preview = service.edit(request(new ScreenFieldMove("assignee", "Details", FieldPlace.FIRST, null, null)));
        jira.beforeAdd = field -> { throw new IllegalStateException("target and return add failed"); };
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.PARTIAL, result.getStatus());
        assertEquals(LayoutStatus.FAILED, result.getMoves().get(0).getStatus());
        assertEquals(LayoutErrorCode.JIRA_ERROR, result.getMoves().get(0).getErrorCode());
        assertEquals(List.of("summary", "labels"), screen.fields());
        assertEquals(List.of("summary", "labels"), result.getActual().get(0).getFields().stream().map(TabLayoutDiff.FieldRef::getFieldId).toList());
        assertEquals(2, jira.additionCalls);
    }

    @Test public void transferToTheEndOfAGappedTabIsAppliedAndReadBackByPosition() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        LayoutFixture.Tab details = screen.tab(11, "Details", "priority", "duedate", "description");
        details.rows.get(1).setPosition(2); details.rows.get(2).setPosition(5);
        EditScreenLayoutResponse preview = service.edit(request(new ScreenFieldMove("labels", "Details", FieldPlace.LAST, null, null)));
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.EDITED, result.getStatus()); assertEquals(List.of(LayoutStatus.MOVED), statuses(result));
        ru.rasulov.common.impl.screen.ScreenLayout saved = ru.rasulov.common.impl.screen.ScreenLayout.of(screen.screen);
        assertEquals(List.of("priority", "duedate", "description", "labels"),
                saved.tab(11L).items().stream().map(ru.rasulov.common.impl.screen.ScreenLayout.Item::fieldId).toList());
        assertEquals(List.of(0, 1, 2, 3),
                saved.tab(11L).items().stream().map(ru.rasulov.common.impl.screen.ScreenLayout.Item::position).toList());
    }

    @Test public void checkpointBeforeWritePreventsAllMovesWithoutFabricatingActual() {
        jira.screen(1, "summary", "labels"); EditScreenLayoutResponse preview = service.edit(request(move("labels", FieldPlace.FIRST)));
        doThrow(new IllegalStateException("checkpoint failed")).when(journal).checkpoint();
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.FAILED, result.getStatus()); assertEquals(List.of(LayoutStatus.NOT_ATTEMPTED), statuses(result));
        assertNull(result.getActual()); assertEquals(0, jira.moveCalls); assertFalse(saved.containsKey(preview.getPlanId()));
    }

    @Test public void checkpointAfterWritePreservesObservedMovedRowsAndActual() {
        jira.screen(1, "summary", "labels"); EditScreenLayoutResponse preview = service.edit(request(move("labels", FieldPlace.FIRST)));
        AtomicInteger boundaries = new AtomicInteger(); doAnswer(call -> { if (boundaries.incrementAndGet() == 2) throw new IllegalStateException("checkpoint failed"); return null; }).when(journal).checkpoint();
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.PARTIAL, result.getStatus()); assertEquals(List.of(LayoutStatus.MOVED), statuses(result));
        assertEquals("labels", result.getActual().get(0).getFields().get(0).getFieldId()); assertEquals(1, jira.moveCalls);
    }

    @Test public void combinedActionsIssueOnePlanAndApplyOneFinalLayoutWithFullReadback() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");
        screen.tab(11, "Untouched", "description");
        EditScreenLayoutResponse preview = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("priority", null, FieldPlace.AFTER, "summary")),
                List.of(new ScreenFieldMove("assignee", null, FieldPlace.BEFORE, "priority", null)),
                List.of("labels"), null));
        assertEquals(LayoutStatus.WOULD_EDIT, preview.getStatus()); assertNotNull(preview.getPlanId());
        assertEquals(LayoutStatus.WOULD_ADD, preview.getAdd().get(0).getStatus());
        assertEquals(LayoutStatus.WOULD_REMOVE, preview.getRemove().get(0).getStatus());
        assertEquals("summary", preview.getRemove().get(0).getBefore());
        assertEquals("assignee", preview.getRemove().get(0).getAfter());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields());
        assertEquals(0, jira.additionCalls + jira.removalCalls + jira.moveCalls);
        verify(plans, times(1)).issue(eq("edit_screen_layout"), eq("admin"), anyString(), anyString());
        EditScreenLayoutResponse applied = apply(preview);
        assertEquals(LayoutStatus.EDITED, applied.getStatus());
        assertEquals(LayoutStatus.ADDED, applied.getAdd().get(0).getStatus());
        assertEquals(LayoutStatus.MOVED, applied.getMoves().get(0).getStatus());
        assertEquals(LayoutStatus.REMOVED, applied.getRemove().get(0).getStatus());
        assertEquals(List.of("summary", "assignee", "priority"), screen.fields());
        assertEquals(2, applied.getActual().size());
        assertEquals("description", applied.getActual().get(1).getFields().get(0).getFieldId());
        assertEquals(List.of("labels — deleted", "priority — added"), changes(applied));
        verify(journal, times(1)).execute(eq("edit_screen_layout"), eq("admin"), any());
        verify(journal, times(2)).checkpoint();
    }

    @Test public void addOnlyAndRemoveOnlyKeepLegacyNoOpBehavior() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels");
        EditScreenLayoutResponse present = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("labels", "nonexistent", FieldPlace.LAST, null)), null, null, null));
        assertEquals(LayoutStatus.ALREADY_PRESENT, present.getAdd().get(0).getStatus()); assertNull(present.getPlanId());
        EditScreenLayoutResponse absent = service.edit(new EditScreenLayoutRequest(true, 1L, null, null, List.of("priority"), null));
        assertEquals(LayoutStatus.NOT_PRESENT, absent.getRemove().get(0).getStatus()); assertNull(absent.getPlanId());
        EditScreenLayoutResponse add = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("priority", null, null, null)), null, null, null));
        assertEquals(LayoutStatus.ADDED, apply(add).getAdd().get(0).getStatus());
        assertEquals(List.of("summary", "labels", "priority"), screen.fields());
        EditScreenLayoutResponse remove = service.edit(new EditScreenLayoutRequest(true, 1L, null, null, List.of("priority"), null));
        assertEquals(LayoutStatus.REMOVED, apply(remove).getRemove().get(0).getStatus());
        assertEquals(List.of("summary", "labels"), screen.fields());
    }

    @Test public void allNormalizedDuplicatesOverlapsAndRemovedAnchorsAreReportedBeforePlanning() {
        EditScreenLayoutResponse result = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("123", null, FieldPlace.AFTER, "labels"),
                        new ScreenFieldAdd(" CUSTOMFIELD_123 ", null, FieldPlace.FIRST, null)),
                List.of(move("123", FieldPlace.FIRST), move("priority", FieldPlace.FIRST), move("priority", FieldPlace.LAST),
                        new ScreenFieldMove("assignee", null, FieldPlace.AFTER, "labels", null)),
                List.of("labels", "labels", "123"), null));
        assertEquals(LayoutErrorCode.VALIDATION_ERROR, result.getErrorCode()); assertNull(result.getPlanId());
        assertEquals(7, result.getValidationErrors().size());
        assertTrue(result.getValidationErrors().stream().anyMatch(e -> e.contains("customfield_123")));
        assertEquals(2, result.getValidationErrors().stream().filter(e -> e.contains("anchor 'labels'")).count());
        verifyNoInteractions(jira.manager, plans, lock, journal);
    }

    @Test public void allIndependentInvalidAddressesAreReturnedWithoutAnyPlan() {
        jira.screen(1, "summary", "labels", "assignee");
        EditScreenLayoutResponse result = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("priority", "Missing", null, null),
                        new ScreenFieldAdd("duedate", null, FieldPlace.AFTER, "absent")),
                List.of(new ScreenFieldMove("labels", "Missing", FieldPlace.LAST, null, null),
                        new ScreenFieldMove("assignee", null, FieldPlace.AFTER, "absent", null)), null, null));
        assertEquals(4, result.getValidationErrors().size()); assertNull(result.getPlanId());
        assertEquals(LayoutStatus.FAILED, result.getStatus()); verifyNoInteractions(plans, lock, journal);
        assertEquals(0, jira.additionCalls + jira.removalCalls + jira.moveCalls);
    }

    @Test public void unknownCustomAdditionRefusesOtherValidActions() {
        jira.screen(1, "summary", "labels");
        EditScreenLayoutResponse result = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("999", null, null, null)),
                List.of(move("labels", FieldPlace.FIRST)), null, null));
        assertEquals(LayoutErrorCode.UNKNOWN_FIELD, result.getErrorCode()); assertNull(result.getPlanId());
        assertEquals(LayoutStatus.NOT_ATTEMPTED, result.getMoves().get(0).getStatus());
        assertEquals(0, jira.moveCalls); verifyNoInteractions(plans);
    }

    @Test public void customContextRemainsUnchangedAndItsChangeMakesTheWholePlanStale() {
        jira.screen(1, "summary", "labels");
        com.atlassian.jira.issue.fields.CustomField custom = mock(com.atlassian.jira.issue.fields.CustomField.class);
        com.atlassian.jira.issue.fields.config.FieldConfigScheme context = mock(com.atlassian.jira.issue.fields.config.FieldConfigScheme.class);
        when(custom.getId()).thenReturn("customfield_123"); when(custom.getName()).thenReturn("Custom");
        when(customFields.getCustomFieldObject("customfield_123")).thenReturn(custom);
        when(custom.getConfigurationSchemes()).thenReturn(List.of(context));
        when(context.getId()).thenReturn(123L); when(context.getName()).thenReturn("Restricted");
        when(context.getAssociatedProjectObjects()).thenReturn(List.of()); when(context.getAssociatedIssueTypes()).thenReturn(List.of());
        EditScreenLayoutResponse preview = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("123", null, null, null)), List.of(move("labels", FieldPlace.FIRST)), null, null));
        assertNotNull(preview.getPlanId());
        assertEquals("FIELD_CONTEXT_UNCHANGED", preview.getAdd().get(0).getWarnings().get(0).getCode());
        when(context.isAllProjects()).thenReturn(true);
        assertEquals(LayoutErrorCode.PREVIEW_STALE, apply(preview).getErrorCode());
        assertEquals(0, jira.additionCalls + jira.removalCalls + jira.moveCalls);
    }

    @Test public void aGlobalContextWhoseIssueTypeListHoldsNullStillPreviewsTheAddition() {
        // Jira 10.3.24 returns [null] from getAssociatedIssueTypes() for an "all issue types" context.
        jira.screen(1, "summary", "labels");
        com.atlassian.jira.issue.fields.CustomField custom = mock(com.atlassian.jira.issue.fields.CustomField.class);
        com.atlassian.jira.issue.fields.config.FieldConfigScheme context = mock(com.atlassian.jira.issue.fields.config.FieldConfigScheme.class);
        when(custom.getId()).thenReturn("customfield_123"); when(custom.getName()).thenReturn("Custom");
        when(customFields.getCustomFieldObject("customfield_123")).thenReturn(custom);
        when(custom.getConfigurationSchemes()).thenReturn(List.of(context));
        when(context.getId()).thenReturn(123L); when(context.isAllProjects()).thenReturn(true); when(context.isAllIssueTypes()).thenReturn(true);
        when(context.getAssociatedProjectObjects()).thenReturn(java.util.Arrays.asList((com.atlassian.jira.project.Project) null));
        when(context.getAssociatedIssueTypes()).thenReturn(java.util.Arrays.asList((com.atlassian.jira.issue.issuetype.IssueType) null));
        EditScreenLayoutResponse preview = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("123", null, null, null)), null, null, null));
        assertNull(preview.getErrorCode());
        assertNotNull(preview.getPlanId());
        assertEquals(LayoutStatus.WOULD_ADD, preview.getAdd().get(0).getStatus());
    }

    @Test public void earlierAddAndRemovalSurviveFailedTransferAndRestorationIsOnlyPerField() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee"); screen.tab(11, "Details");
        EditScreenLayoutResponse preview = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("priority", null, null, null)),
                List.of(new ScreenFieldMove("assignee", "Details", FieldPlace.LAST, null, null)), List.of("labels"), null));
        AtomicInteger adds = new AtomicInteger();
        jira.beforeAdd = field -> { if (adds.incrementAndGet() == 2) throw new IllegalStateException("transfer add failed"); };
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.PARTIAL, result.getStatus());
        assertEquals(LayoutStatus.FAILED, result.getAdd().get(0).getStatus());
        assertEquals(LayoutStatus.REMOVED, result.getRemove().get(0).getStatus());
        assertEquals(LayoutStatus.RESTORED, result.getMoves().get(0).getStatus());
        assertEquals(List.of("summary", "assignee", "priority"), screen.fields());
        assertEquals(3, jira.additionCalls);
        // The added field is still present even though the subsequent transfer shifted it from its planned position.
        assertEquals(List.of("labels — deleted", "priority — added"), changes(result));
    }

    @Test public void protectedSummaryRejectsTheEntireCombinedCreateScreenEdit() {
        LayoutFixture.Screen screen = jira.screen(1, "summary", "labels");
        when(impact.read(screen.screen)).thenReturn(new ScreenImpact(1L, "Screen 1", List.of("CREATE"), 1, List.of(), false, List.of()));
        EditScreenLayoutResponse preview = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("priority", null, null, null)), null, List.of("summary"), null));
        assertEquals(LayoutErrorCode.PROTECTED_FIELD, preview.getErrorCode()); assertNull(preview.getPlanId());
        assertEquals(LayoutStatus.FAILED, preview.getRemove().get(0).getStatus());
        assertEquals(LayoutStatus.NOT_ATTEMPTED, preview.getAdd().get(0).getStatus());
        assertEquals(List.of("summary", "labels"), screen.fields()); verifyNoInteractions(plans, lock, journal);
    }

    @Test public void unreadableCombinedReadbackNeverPretendsExpectedLayoutWasObserved() {
        jira.screen(1, "summary", "labels", "assignee");
        EditScreenLayoutResponse preview = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("priority", null, null, null)),
                List.of(move("assignee", FieldPlace.FIRST)), List.of("labels"), null));
        jira.readbackFails = true;
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.UNKNOWN, result.getStatus()); assertNull(result.getActual());
        assertEquals(LayoutStatus.UNKNOWN, result.getAdd().get(0).getStatus());
        assertEquals(LayoutStatus.UNKNOWN, result.getMoves().get(0).getStatus());
        assertEquals(LayoutStatus.UNKNOWN, result.getRemove().get(0).getStatus());
        assertFalse(result.getFieldChanges().isAvailable());
        assertEquals(List.of(), result.getFieldChanges().getItems());
    }

    @Test public void oneRemovalAndThreeAdditionsReportFourObservedChangesAlongsideMovesAndNoOps() {
        jira.screen(1, "summary", "labels", "assignee");
        EditScreenLayoutResponse preview = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("priority", null, null, null),
                        new ScreenFieldAdd("duedate", null, null, null),
                        new ScreenFieldAdd("description", null, null, null),
                        new ScreenFieldAdd("summary", null, null, null)),
                List.of(move("assignee", FieldPlace.FIRST)), List.of("labels"), null));
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.EDITED, result.getStatus());
        assertEquals(List.of("labels — deleted", "priority — added", "duedate — added", "description — added"), changes(result));
        verify(journal, times(1)).execute(eq("edit_screen_layout"), eq("admin"), any());
    }

    @Test public void failureDuringAddReportsOnlyMembershipThatSurvivedReadback() {
        jira.screen(1, "summary", "labels");
        EditScreenLayoutResponse preview = service.edit(new EditScreenLayoutRequest(true, 1L,
                List.of(new ScreenFieldAdd("priority", null, null, null), new ScreenFieldAdd("duedate", null, null, null)),
                null, List.of("labels"), null));
        jira.beforeAdd = field -> { if ("duedate".equals(field)) throw new IllegalStateException("add failed"); };
        EditScreenLayoutResponse result = apply(preview);
        assertEquals(LayoutStatus.PARTIAL, result.getStatus());
        assertEquals(List.of("labels — deleted", "priority — added"), changes(result));
    }
}
