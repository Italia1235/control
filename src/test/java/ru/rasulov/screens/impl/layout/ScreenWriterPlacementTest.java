package ru.rasulov.screens.impl.layout;

import org.junit.Test;
import ru.rasulov.customfields.api.dto.screen.ScreenErrorCode;
import ru.rasulov.customfields.api.dto.screen.ScreenResult;
import ru.rasulov.customfields.api.dto.screen.ScreenStatus;
import ru.rasulov.customfields.api.dto.screen.ScreenTarget;
import ru.rasulov.common.impl.screen.ScreenLayout;
import ru.rasulov.customfields.impl.screen.ScreenWriter;
import ru.rasulov.common.api.dto.screen.FieldPlace;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Indexed native insertion semantics, including raw gaps and independent readback. */
public class ScreenWriterPlacementTest {
    private final LayoutFixture jira = new LayoutFixture();
    private final ScreenWriter writer = new ScreenWriter(jira.manager);
    private final LayoutFixture.Screen screen = jira.screen(1, "summary", "labels", "assignee");

    private ScreenTarget target(FieldPlace place, String anchor) { return new ScreenTarget(1L, null, place, anchor); }
    private void gaps() { screen.rows.get(1).setPosition(2); screen.rows.get(2).setPosition(5); }

    @Test public void firstInsertsAtIndexZeroAndPreservesExistingIdsAndFlags() {
        gaps(); Long summaryId = screen.rows.get(0).getId();
        ScreenResult result = writer.add("priority", target(FieldPlace.FIRST, null), false);
        assertEquals(ScreenStatus.ADDED, result.getStatus()); assertEquals("Main", result.getTab());
        assertEquals(List.of("priority", "summary", "labels", "assignee"), screen.fields());
        assertEquals(summaryId, screen.rows.get(1).getId()); assertTrue(screen.rows.get(1).isShownWhenEmpty());
        assertFalse(screen.rows.get(2).isShownWhenEmpty()); assertEquals(1, jira.additionCalls);
    }

    @Test public void beforeUsesAnchorIndexNotItsRawPosition() {
        gaps();
        assertEquals(ScreenStatus.ADDED, writer.add("priority", target(FieldPlace.BEFORE, "assignee"), false).getStatus());
        assertEquals(List.of("summary", "labels", "priority", "assignee"), screen.fields());
        assertEquals(2, screen.rows.get(2).getPosition()); assertEquals(3, screen.rows.get(3).getPosition());
    }

    @Test public void afterUsesAnchorIndexOnWideRawGapsAndKeepsIdsAndFlags() {
        screen.rows.get(1).setPosition(5); screen.rows.get(2).setPosition(9);
        assertEquals(ScreenStatus.ADDED, writer.add("priority", target(FieldPlace.AFTER, "labels"), false).getStatus());
        assertEquals(List.of("summary", "labels", "priority", "assignee"), screen.fields());
        assertEquals(List.of(0, 1, 2, 3), screen.rows.stream().map(row -> row.getPosition()).toList());
        assertFalse(screen.rows.get(1).isShownWhenEmpty()); assertTrue(screen.rows.get(3).isShownWhenEmpty());
    }

    @Test public void omittedPlaceOnGappedTabIsReallyLastAndPositionsAreContiguous() {
        gaps();
        assertEquals(ScreenStatus.ADDED, writer.add("priority", target(null, null), false).getStatus());
        assertEquals(List.of("summary", "labels", "assignee", "priority"), savedFields(screen, 10));
        assertEquals(List.of(0, 1, 2, 3), savedPositions(screen, 10));
    }

    private List<String> savedFields(LayoutFixture.Screen target, long tabId) {
        return ScreenLayout.of(target.screen).tab(tabId).items().stream().map(ScreenLayout.Item::fieldId).toList();
    }
    private List<Integer> savedPositions(LayoutFixture.Screen target, long tabId) {
        return ScreenLayout.of(target.screen).tab(tabId).items().stream().map(ScreenLayout.Item::position).toList();
    }

    @Test public void afterOnGappedTabPersistsContiguousPositionsInTheExpectedOrder() {
        gaps();
        assertEquals(ScreenStatus.ADDED, writer.add("priority", target(FieldPlace.AFTER, "labels"), false).getStatus());
        assertEquals(List.of("summary", "labels", "priority", "assignee"), savedFields(screen, 10));
        assertEquals(List.of(0, 1, 2, 3), savedPositions(screen, 10));
    }

    @Test public void lastOnGappedTabIsReallyLastAndPositionsAreContiguous() {
        gaps();
        assertEquals(ScreenStatus.ADDED, writer.add("priority", target(FieldPlace.LAST, null), false).getStatus());
        assertEquals(List.of("summary", "labels", "assignee", "priority"), savedFields(screen, 10));
        assertEquals(List.of(0, 1, 2, 3), savedPositions(screen, 10));
    }

    @Test public void firstAndBeforeOnGappedTabPersistContiguousPositions() {
        gaps();
        assertEquals(ScreenStatus.ADDED, writer.add("priority", target(FieldPlace.FIRST, null), false).getStatus());
        assertEquals(List.of("priority", "summary", "labels", "assignee"), savedFields(screen, 10));
        assertEquals(List.of(0, 1, 2, 3), savedPositions(screen, 10));
        assertEquals(ScreenStatus.ADDED, writer.add("duedate", target(FieldPlace.BEFORE, "assignee"), false).getStatus());
        assertEquals(List.of("priority", "summary", "labels", "duedate", "assignee"), savedFields(screen, 10));
        assertEquals(List.of(0, 1, 2, 3, 4), savedPositions(screen, 10));
    }

    @Test public void gappedTabIsRenumberedOnceBeforeTheInsertionWithoutChangingItsOrder() {
        gaps();
        java.util.List<String> events = new java.util.ArrayList<>();
        jira.beforeMove = tab -> events.add("renumber:" + screen.fields() + screen.rows.stream().map(row -> row.getPosition()).toList());
        jira.beforeAdd = field -> events.add("add:" + screen.fields() + screen.rows.stream().map(row -> row.getPosition()).toList());
        writer.add("priority", target(FieldPlace.AFTER, "labels"), false);
        assertEquals(List.of("renumber:[summary, labels, assignee][0, 2, 5]", "add:[summary, labels, assignee][0, 1, 2]"), events);
        assertEquals(1, jira.moveCalls); assertEquals(1, jira.additionCalls);
    }

    @Test public void contiguousTabIsNotRenumberedBeforeInsertion() {
        assertEquals(ScreenStatus.ADDED, writer.add("priority", target(FieldPlace.AFTER, "labels"), false).getStatus());
        assertEquals(0, jira.moveCalls); assertEquals(List.of(0, 1, 2, 3), savedPositions(screen, 10));
    }

    @Test public void dryRunResolvesAnchorButDoesNotInsert() {
        gaps(); ScreenResult result = writer.add("priority", target(FieldPlace.AFTER, "labels"), true);
        assertEquals(ScreenStatus.WOULD_ADD, result.getStatus()); assertEquals("Main", result.getTab());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields()); assertEquals(0, jira.additionCalls);
    }

    @Test public void anchorOnAnotherTabIsUnknownAndNoFieldIsInserted() {
        screen.tab(11, "Details", "priority");
        for (boolean dryRun : List.of(true, false)) {
            ScreenResult result = writer.add("duedate", target(FieldPlace.AFTER, "priority"), dryRun);
            assertEquals(ScreenErrorCode.UNKNOWN_ANCHOR, result.getErrorCode()); assertEquals(ScreenStatus.FAILED, result.getStatus());
        }
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields()); assertEquals(0, jira.additionCalls);
    }

    @Test public void missingAnchorCannotSilentlyAppend() {
        ScreenResult result = writer.add("priority", target(FieldPlace.BEFORE, "not_a_field"), false);
        assertEquals(ScreenErrorCode.UNKNOWN_ANCHOR, result.getErrorCode()); assertEquals(0, jira.additionCalls);
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields());
    }

    @Test public void bareCustomFieldIdAnchorIsResolvedWithoutNameLookup() {
        LayoutFixture.Tab details = screen.tab(11, "Details", "customfield_42", "duedate");
        ScreenResult result = writer.add("priority", new ScreenTarget(1L, "details", FieldPlace.AFTER, "42"), false);
        assertEquals(ScreenStatus.ADDED, result.getStatus()); assertEquals(List.of("customfield_42", "priority", "duedate"), details.fields());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields());
    }

    @Test public void invalidPlaceAndAnchorCombinationsCannotWrite() {
        for (ScreenTarget invalid : List.of(target(FieldPlace.UP, null), target(FieldPlace.DOWN, null),
                target(FieldPlace.BEFORE, null), target(FieldPlace.AFTER, " "), target(FieldPlace.FIRST, "labels"),
                target(FieldPlace.LAST, "labels"), target(null, "labels"))) {
            ScreenResult result = writer.add("priority", invalid, false);
            assertEquals(ScreenStatus.FAILED, result.getStatus()); assertEquals(ScreenErrorCode.VALIDATION_ERROR, result.getErrorCode());
        }
        assertEquals(0, jira.additionCalls); assertEquals(List.of("summary", "labels", "assignee"), screen.fields());
    }

    @Test public void existingFieldIsNotMovedToRequestedTabOrAnchor() {
        LayoutFixture.Tab details = screen.tab(11, "Details", "priority");
        ScreenResult result = writer.add("labels", new ScreenTarget(1L, "Details", FieldPlace.AFTER, "priority"), false);
        assertEquals(ScreenStatus.ALREADY_PRESENT, result.getStatus()); assertNull(result.getTab());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields()); assertEquals(List.of("priority"), details.fields());
        assertEquals(0, jira.additionCalls);
    }

    @Test public void mismatchedIndexLookupRefusesBeforeAnyWrite() {
        when(screen.tab.getFieldScreenLayoutItem(1)).thenReturn(screen.rows.get(0));
        ScreenResult result = writer.add("priority", target(FieldPlace.AFTER, "labels"), false);
        assertEquals("the stale index is refused, nothing written", ScreenStatus.FAILED, result.getStatus());
        assertEquals(ScreenErrorCode.JIRA_ERROR, result.getErrorCode());
        assertEquals(List.of("summary", "labels", "assignee"), screen.fields()); assertEquals(0, jira.additionCalls);
    }

    @Test public void wrongReadbackIndexCannotBeCalledAdded() {
        jira.afterAdd = field -> { Collections.swap(screen.rows, 0, 1); resequenceRows(); };
        ScreenResult result = writer.add("priority", target(FieldPlace.FIRST, null), false);
        assertEquals("wrong insertion index must fail readback", ScreenStatus.UNKNOWN, result.getStatus());
        assertEquals(List.of("summary", "priority", "labels", "assignee"), screen.fields()); assertEquals(1, jira.additionCalls);
    }

    @Test public void reorderedOldRowsAreRejected() {
        jira.afterAdd = field -> { Collections.swap(screen.rows, 1, 2); resequenceRows(); };
        ScreenResult result = writer.add("priority", target(FieldPlace.LAST, null), false);
        assertEquals("existing order must be preserved", ScreenStatus.UNKNOWN, result.getStatus());
        assertEquals(List.of("summary", "assignee", "labels", "priority"), screen.fields()); assertEquals(1, jira.additionCalls);
    }

    @Test public void changedOldShownFlagIsRejectedWithoutRetry() {
        jira.afterAdd = field -> screen.rows.get(0).setShowWhenEmpty(false);
        ScreenResult result = writer.add("priority", target(FieldPlace.LAST, null), false);
        assertEquals("existing flags must be preserved", ScreenStatus.UNKNOWN, result.getStatus());
        assertFalse(screen.rows.get(0).isShownWhenEmpty()); assertEquals(1, jira.additionCalls);
    }

    @Test public void unavailableReadbackDoesNotClaimAdded() {
        jira.readbackFails = true;
        ScreenResult result = writer.add("priority", target(FieldPlace.FIRST, null), false);
        assertEquals("unreadable state must not be accepted", ScreenStatus.UNKNOWN, result.getStatus());
        assertEquals(List.of("priority", "summary", "labels", "assignee"), screen.fields()); assertEquals(1, jira.additionCalls);
    }

    private void resequenceRows() { for (int i = 0; i < screen.rows.size(); i++) screen.rows.get(i).setPosition(i); }
}
