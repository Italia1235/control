package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.fields.Field;
import com.atlassian.jira.issue.fields.FieldManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.common.impl.screen.ScreenLayout;
import ru.rasulov.common.api.dto.screen.FieldPlace;
import ru.rasulov.screens.api.dto.layout.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ScreenLayoutPlannerTest {
    private final FieldManager fields = mock(FieldManager.class);
    private final ScreenLayoutPlanner planner = new ScreenLayoutPlanner(fields);

    @Test public void laterAnchorUsesTheOrderProducedByEarlierMoves() {
        ScreenLayout before = layout(tab(1, "Main", "summary", "customfield_123", "labels", "assignee"));
        ScreenLayoutPlanner.Plan plan = planner.plan(before, List.of(
                move(" 123 ", null, FieldPlace.AFTER, "assignee", null),
                move("labels", null, FieldPlace.AFTER, " CUSTOMFIELD_123 ", null)));
        assertNull(plan.errorCode());
        assertEquals(List.of("summary", "assignee", "customfield_123", "labels"), ids(plan.after(), 1));
        assertEquals(List.of(LayoutStatus.WOULD_MOVE, LayoutStatus.WOULD_MOVE), statuses(plan));
        assertEquals("customfield_123", plan.moves().get(0).getFieldId());
        assertEquals(List.of("summary", "customfield_123", "labels", "assignee"), ids(before, 1));
        assertEquals(Set.of(1L), plan.affected());
        assertEquals(1, plan.tabs().size());
    }

    @Test public void beforeAndAfterCalculateAnchorIndexAfterSourceRemoval() {
        ScreenLayout before = layout(tab(1, "Main", "summary", "labels", "assignee", "priority"));
        ScreenLayoutPlanner.Plan beforeAnchor = planner.plan(before,
                List.of(move("summary", null, FieldPlace.BEFORE, "assignee", null)));
        assertEquals(List.of("labels", "summary", "assignee", "priority"), ids(beforeAnchor.after(), 1));
        assertEquals(before.item("summary").layoutItemId(), beforeAnchor.after().item("summary").layoutItemId());
        assertEquals(before.item("summary").shownWhenEmpty(), beforeAnchor.after().item("summary").shownWhenEmpty());
        ScreenLayoutPlanner.Plan afterAnchor = planner.plan(before,
                List.of(move("summary", null, FieldPlace.AFTER, "assignee", null)));
        assertEquals(List.of("labels", "assignee", "summary", "priority"), ids(afterAnchor.after(), 1));
        ScreenLayoutPlanner.Plan backward = planner.plan(before,
                List.of(move("priority", null, FieldPlace.BEFORE, "labels", null)));
        assertEquals(List.of("summary", "priority", "labels", "assignee"), ids(backward.after(), 1));
    }

    @Test public void upAndDownDefaultToOneStepWithoutClampingAtAnExactBoundary() {
        ScreenLayout before = layout(tab(1, "Main", "summary", "labels", "assignee"));
        ScreenLayoutPlanner.Plan up = planner.plan(before, List.of(move("labels", null, FieldPlace.UP, null, null)));
        assertEquals(List.of("labels", "summary", "assignee"), ids(up.after(), 1));
        assertTrue(up.warnings().isEmpty());
        ScreenLayoutPlanner.Plan down = planner.plan(before, List.of(move("labels", null, FieldPlace.DOWN, null, 1)));
        assertEquals(List.of("summary", "assignee", "labels"), ids(down.after(), 1));
        assertTrue(down.warnings().isEmpty());
        ScreenLayoutPlanner.Plan exact = planner.plan(before, List.of(move("summary", null, FieldPlace.DOWN, null, 2)));
        assertTrue(exact.warnings().isEmpty());
        assertEquals(List.of("labels", "assignee", "summary"), ids(exact.after(), 1));
    }

    @Test public void oversteppingBoundsClampsAndAlsoWarnsWhenAlreadyAtTheBoundary() {
        ScreenLayout before = layout(tab(1, "Main", "summary", "labels", "assignee"));
        ScreenLayoutPlanner.Plan up = planner.plan(before, List.of(move("labels", null, FieldPlace.UP, null, 200)));
        assertEquals(List.of("labels", "summary", "assignee"), ids(up.after(), 1));
        assertEquals(List.of("CLAMPED"), warningCodes(up.warnings()));
        assertEquals(List.of("CLAMPED"), warningCodes(up.moves().get(0).getWarnings()));
        ScreenLayoutPlanner.Plan down = planner.plan(before, List.of(move("summary", null, FieldPlace.DOWN, null, 200)));
        assertEquals(List.of("labels", "assignee", "summary"), ids(down.after(), 1));
        assertEquals(List.of("CLAMPED"), warningCodes(down.warnings()));
        ScreenLayoutPlanner.Plan atStart = planner.plan(before, List.of(move("summary", null, FieldPlace.UP, null, 1)));
        assertEquals(LayoutStatus.ALREADY_IN_PLACE, atStart.moves().get(0).getStatus());
        assertEquals(List.of("CLAMPED"), warningCodes(atStart.warnings()));
        assertSame(before, atStart.after());
        ScreenLayoutPlanner.Plan atEnd = planner.plan(before, List.of(move("assignee", null, FieldPlace.DOWN, null, 1)));
        assertEquals(LayoutStatus.ALREADY_IN_PLACE, atEnd.moves().get(0).getStatus());
        assertEquals(List.of("CLAMPED"), warningCodes(atEnd.warnings()));
    }

    @Test public void repeatedNormalizedFieldRejectsTheEntirePlan() {
        ScreenLayout before = layout(tab(1, "Main", "customfield_123", "summary", "labels"));
        ScreenLayoutPlanner.Plan plan = planner.plan(before, List.of(
                move("123", null, FieldPlace.LAST, null, null),
                move("customfield_123", null, FieldPlace.FIRST, null, null)));
        assertEquals(LayoutErrorCode.VALIDATION_ERROR, plan.errorCode());
        assertEquals(List.of(LayoutStatus.NOT_ATTEMPTED, LayoutStatus.FAILED), statuses(plan));
        assertNull(plan.after()); assertTrue(plan.tabs().isEmpty());
    }

    @Test public void noOpComparesListIndicesInsteadOfGappedNativePositions() {
        ScreenLayout before = layout(new ScreenLayout.Tab(1L, "Main", 8, List.of(
                new ScreenLayout.Item(101L, "summary", 2, true),
                new ScreenLayout.Item(102L, "labels", 7, false),
                new ScreenLayout.Item(103L, "assignee", 11, true))));
        ScreenLayoutPlanner.Plan plan = planner.plan(before, List.of(
                move("labels", null, FieldPlace.AFTER, "summary", null),
                move("summary", null, FieldPlace.FIRST, null, null),
                move("assignee", null, FieldPlace.LAST, null, null)));
        assertEquals(List.of(LayoutStatus.ALREADY_IN_PLACE, LayoutStatus.ALREADY_IN_PLACE,
                LayoutStatus.ALREADY_IN_PLACE), statuses(plan));
        assertSame(before, plan.after());
        assertEquals(7, plan.after().item("labels").position());
        assertTrue(plan.tabs().isEmpty());
        verifyNoInteractions(fields);
    }

    @Test public void transferResolvesExactTabIdOrCaseInsensitiveNameAndPreservesFlags() {
        ScreenLayout before = layout(tab(1, "Main", "summary", "labels"),
                tab(2, "Details", "assignee"), tab(3, "Untouched", "priority"));
        ScreenLayoutPlanner.Plan plan = planner.plan(before, List.of(
                move("labels", " dEtAiLs ", FieldPlace.BEFORE, "assignee", null),
                move("summary", "2", FieldPlace.LAST, null, null)));
        assertNull(plan.errorCode());
        assertTrue(ids(plan.after(), 1).isEmpty());
        assertEquals(List.of("labels", "assignee", "summary"), ids(plan.after(), 2));
        assertFalse(plan.after().item("labels").shownWhenEmpty());
        assertTrue(plan.after().item("summary").shownWhenEmpty());
        assertNull(plan.after().item("labels").layoutItemId());
        assertNull(plan.after().item("summary").layoutItemId());
        assertEquals(before.item("assignee").layoutItemId(), plan.after().item("assignee").layoutItemId());
        assertEquals(before.item("priority"), plan.after().item("priority"));
        assertEquals(Set.of(1L, 2L), plan.affected());
        assertEquals(List.of(1L, 2L), plan.tabs().stream().map(TabLayoutDiff::getTabId).toList());
    }

    @Test public void repeatedFieldAfterTransferIsStillRejected() {
        ScreenLayout before = layout(tab(1, "Main", "summary", "labels"), tab(2, "Details", "assignee"));
        ScreenLayoutPlanner.Plan plan = planner.plan(before, List.of(
                move("labels", "Details", FieldPlace.LAST, null, null),
                move("labels", null, FieldPlace.FIRST, null, null)));
        assertEquals(LayoutErrorCode.VALIDATION_ERROR, plan.errorCode());
        assertNull(plan.after());
    }

    @Test public void invalidAddressingRejectsTheWholeCallWithoutPlannedMoves() {
        ScreenLayout before = layout(tab(1, "Main", "summary", "labels"), tab(2, "Details", "assignee"));
        assertRejected(before, move("labels", "Missing", FieldPlace.FIRST, null, null), LayoutErrorCode.UNKNOWN_TAB);
        assertRejected(before, move("labels", "Det", FieldPlace.FIRST, null, null), LayoutErrorCode.UNKNOWN_TAB);
        assertRejected(before, move("labels", "", FieldPlace.FIRST, null, null), LayoutErrorCode.UNKNOWN_TAB);
        assertRejected(before, move("labels", null, FieldPlace.AFTER, "assignee", null), LayoutErrorCode.UNKNOWN_ANCHOR);
        assertRejected(before, move("labels", null, FieldPlace.BEFORE, "missing", null), LayoutErrorCode.UNKNOWN_ANCHOR);
        assertRejected(before, move("Labels", null, FieldPlace.FIRST, null, null), LayoutErrorCode.FIELD_NOT_ON_SCREEN);
        assertRejected(before, move("missing", null, FieldPlace.FIRST, null, null), LayoutErrorCode.FIELD_NOT_ON_SCREEN);
        verifyNoInteractions(fields);
    }

    @Test public void invalidMoveShapesRejectTheWholeCall() {
        ScreenLayout before = layout(tab(1, "Main", "customfield_123", "summary", "labels"));
        List<ScreenFieldMove> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(move(null, null, FieldPlace.FIRST, null, null));
        invalid.add(move(" ", null, FieldPlace.FIRST, null, null));
        invalid.add(move("labels", null, null, null, null));
        invalid.add(move("labels", null, FieldPlace.AFTER, null, null));
        invalid.add(move("labels", null, FieldPlace.BEFORE, " ", null));
        invalid.add(move("123", null, FieldPlace.AFTER, "customfield_123", null));
        invalid.add(move("labels", "Main", FieldPlace.UP, null, null));
        invalid.add(move("labels", "", FieldPlace.DOWN, null, null));
        invalid.add(move("labels", null, FieldPlace.UP, null, 0));
        invalid.add(move("labels", null, FieldPlace.UP, null, -1));
        invalid.add(move("labels", null, FieldPlace.DOWN, null, 201));
        for (FieldPlace place : List.of(FieldPlace.FIRST, FieldPlace.LAST, FieldPlace.BEFORE, FieldPlace.AFTER))
            invalid.add(move("labels", null, place, place == FieldPlace.BEFORE || place == FieldPlace.AFTER ? "summary" : null, 1));
        for (FieldPlace place : List.of(FieldPlace.FIRST, FieldPlace.LAST, FieldPlace.UP, FieldPlace.DOWN))
            invalid.add(move("labels", null, place, "", null));
        for (ScreenFieldMove move : invalid) assertRejected(before, move, LayoutErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(fields);
    }

    @Test public void emptyNullAndOversizedRequestsAreSafeAndNeverReturnAnAfterState() {
        ScreenLayout before = layout(tab(1, "Main", "summary"));
        for (List<ScreenFieldMove> moves : List.of(Collections.<ScreenFieldMove>emptyList(),
                Collections.nCopies(51, move("summary", null, FieldPlace.FIRST, null, null)))) {
            ScreenLayoutPlanner.Plan plan = planner.plan(before, moves);
            assertNull(plan.after());
            assertEquals(LayoutErrorCode.VALIDATION_ERROR, plan.errorCode());
            assertTrue(plan.moves().stream().allMatch(row -> row.getStatus() == LayoutStatus.FAILED));
        }
        assertEquals(LayoutErrorCode.VALIDATION_ERROR, planner.plan(before, null).errorCode());
        assertEquals(LayoutErrorCode.VALIDATION_ERROR,
                planner.plan(null, List.of(move("summary", null, FieldPlace.FIRST, null, null))).errorCode());
        assertNull(planner.plan(before, null).after());
    }

    @Test public void maximumFiftyDistinctMovesAreAcceptedAndExecutedInRequestOrder() {
        ScreenLayout before = layout(longTab(1, 50));
        List<ScreenFieldMove> moves = new ArrayList<>();
        for (int i = 0; i < 50; i++) moves.add(move("field" + i, null, FieldPlace.FIRST, null, null));
        ScreenLayoutPlanner.Plan plan = planner.plan(before, moves);
        assertNull(plan.errorCode()); assertEquals(50, plan.moves().size());
        assertEquals(LayoutStatus.ALREADY_IN_PLACE, plan.moves().get(0).getStatus());
        assertTrue(plan.moves().subList(1, 50).stream().allMatch(row -> row.getStatus() == LayoutStatus.WOULD_MOVE));
        assertEquals(IntStream.range(0, 50).mapToObj(i -> "field" + (49 - i)).toList(), ids(plan.after(), 1));
        assertTrue(plan.warnings().isEmpty());
    }

    @Test public void longDiffShowsUnionOfWindowsAroundAllFarApartChangesWithAbsoluteCoordinates() throws Exception {
        ScreenLayout before = layout(longTab(1, 120), tab(2, "Untouched", "summary"));
        ScreenLayoutPlanner.Plan plan = planner.plan(before, List.of(
                move("field10", null, FieldPlace.UP, null, 1),
                move("field100", null, FieldPlace.DOWN, null, 1)));
        assertEquals(1, plan.tabs().size());
        TabLayoutDiff diff = plan.tabs().get(0);
        assertTrue(diff.isTruncated());
        List<Integer> expectedIndices = new ArrayList<>();
        IntStream.rangeClosed(4, 15).forEach(expectedIndices::add);
        IntStream.rangeClosed(95, 106).forEach(expectedIndices::add);
        assertEquals(expectedIndices, diff.getBefore().stream().map(TabLayoutDiff.FieldRef::getIndex).toList());
        assertEquals(expectedIndices, diff.getAfter().stream().map(TabLayoutDiff.FieldRef::getIndex).toList());
        assertEquals("field4", diff.getBefore().get(0).getFieldId());
        assertEquals(14, diff.getBefore().get(0).getPosition());
        assertEquals(4, diff.getAfter().get(0).getPosition());
        assertEquals("field10", diff.getAfter().get(5).getFieldId());
        assertTrue(diff.getAfter().stream().anyMatch(ref -> ref.getIndex() == 101 && ref.getFieldId().equals("field100")));
        JsonNode json = new ObjectMapper().valueToTree(diff);
        assertEquals(95, json.get("before").get(12).get("index").intValue());
        assertEquals(287, json.get("before").get(12).get("position").intValue());
        assertNull(json.get("before").get(12).get("name").textValue());
    }

    @Test public void longMoveIncludesEveryShiftedIndexInsteadOfOnlyEndpointWindows() {
        ScreenLayout before = layout(longTab(1, 120));
        ScreenLayoutPlanner.Plan plan = planner.plan(before,
                List.of(move("field0", null, FieldPlace.LAST, null, null)));
        assertTrue(plan.tabs().get(0).isTruncated());
        assertEquals(120, plan.tabs().get(0).getBefore().size());
        assertEquals(120, plan.tabs().get(0).getAfter().size());
        assertEquals("field0", plan.tabs().get(0).getAfter().get(119).getFieldId());
    }

    @Test public void longTransferIncludesSourceEndpointAndDestinationEvenWhenSourceBecomesShorter() {
        ScreenLayout before = layout(longTab(1, 100), tab(2, "Empty"));
        ScreenLayoutPlanner.Plan plan = planner.plan(before,
                List.of(move("field99", "Empty", FieldPlace.FIRST, null, null)));
        TabLayoutDiff source = plan.tabs().get(0);
        assertTrue(source.isTruncated());
        assertEquals(List.of(94, 95, 96, 97, 98, 99), source.getBefore().stream().map(TabLayoutDiff.FieldRef::getIndex).toList());
        assertEquals(List.of(94, 95, 96, 97, 98), source.getAfter().stream().map(TabLayoutDiff.FieldRef::getIndex).toList());
        TabLayoutDiff destination = plan.tabs().get(1);
        assertFalse(destination.isTruncated());
        assertTrue(destination.getBefore().isEmpty());
        assertEquals("field99", destination.getAfter().get(0).getFieldId());
    }

    @Test public void metadataIsCachedPerInvocationAndMissingFieldsKeepNullNames() {
        Field summary = mock(Field.class);
        when(summary.getName()).thenReturn("Summary");
        when(fields.getField("summary")).thenReturn(summary);
        ScreenLayout before = layout(tab(1, "Main", "summary", "deleted_field"));
        ScreenLayoutPlanner.Plan plan = planner.plan(before,
                List.of(move("deleted_field", null, FieldPlace.FIRST, null, null)));
        TabLayoutDiff diff = plan.tabs().get(0);
        assertEquals("Summary", diff.getBefore().get(0).getName());
        assertNull(diff.getBefore().get(1).getName());
        assertNull(diff.getAfter().get(0).getName());
        verify(fields, times(1)).getField("summary");
        verify(fields, times(1)).getField("deleted_field");
        verifyNoMoreInteractions(fields);
        planner.diff(before, plan.after());
        verify(fields, times(2)).getField("summary");
        verify(fields, times(2)).getField("deleted_field");
    }

    @Test public void actualReportsOnlyIndependentlyObservedAffectedTabsInFull() {
        ScreenLayout observed = layout(longTab(1, 120), tab(2, "Other", "labels"));
        List<TabLayoutDiff.TabLayout> actual = planner.actual(observed, Set.of(1L, 999L));
        assertEquals(1, actual.size());
        assertEquals(Long.valueOf(1), actual.get(0).getTabId());
        assertEquals(120, actual.get(0).getFields().size());
        assertEquals("field119", actual.get(0).getFields().get(119).getFieldId());
        assertEquals(119, actual.get(0).getFields().get(119).getIndex());
        assertEquals(359, actual.get(0).getFields().get(119).getPosition());
        assertNull(planner.actual(null, Set.of(1L)));
        assertTrue(planner.actual(observed, Set.of()).isEmpty());
        verify(fields, times(1)).getField("field119");
        verify(fields, never()).getField("labels");
    }

    private void assertRejected(ScreenLayout before, ScreenFieldMove invalid, LayoutErrorCode expected) {
        List<ScreenFieldMove> request = new ArrayList<>();
        request.add(move("summary", null, FieldPlace.LAST, null, null));
        request.add(invalid);
        request.add(move("labels", null, FieldPlace.FIRST, null, null));
        ScreenLayoutPlanner.Plan plan = planner.plan(before, request);
        assertNull(plan.after());
        assertEquals(expected, plan.errorCode());
        assertEquals(List.of(LayoutStatus.NOT_ATTEMPTED, LayoutStatus.FAILED, LayoutStatus.NOT_ATTEMPTED), statuses(plan));
        assertEquals(expected, plan.moves().get(1).getErrorCode());
        assertEquals(1, plan.moves().get(1).getIndex());
        assertTrue(plan.tabs().isEmpty());
        assertNotNull(plan.message());
    }

    private static ScreenFieldMove move(String fieldId, String tab, FieldPlace place, String anchor, Integer steps) {
        return new ScreenFieldMove(fieldId, tab, place, anchor, steps);
    }
    private static ScreenLayout layout(ScreenLayout.Tab... tabs) {
        return new ScreenLayout(5L, "Screen", "Description", List.of(tabs));
    }
    private static ScreenLayout.Tab tab(long id, String name, String... fields) {
        List<ScreenLayout.Item> items = new ArrayList<>();
        for (int i = 0; i < fields.length; i++) items.add(new ScreenLayout.Item(id * 100 + i, fields[i], i, i % 2 == 0));
        return new ScreenLayout.Tab(id, name, (int) id, items);
    }
    private static ScreenLayout.Tab longTab(long id, int size) {
        List<ScreenLayout.Item> items = new ArrayList<>();
        for (int i = 0; i < size; i++) items.add(new ScreenLayout.Item(id * 1000 + i, "field" + i, i * 3 + 2, true));
        return new ScreenLayout.Tab(id, "Long", (int) id, items);
    }
    private static List<String> ids(ScreenLayout layout, long tabId) {
        return layout.tab(tabId).items().stream().map(ScreenLayout.Item::fieldId).toList();
    }
    private static List<LayoutStatus> statuses(ScreenLayoutPlanner.Plan plan) {
        return plan.moves().stream().map(EditScreenLayoutResponse.MoveResult::getStatus).toList();
    }
    private static List<String> warningCodes(List<LayoutWarning> warnings) {
        return warnings.stream().map(LayoutWarning::getCode).toList();
    }
}
