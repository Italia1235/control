package ru.rasulov.customfields.impl.screen;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.customfields.api.dto.screen.ScreenErrorCode;
import ru.rasulov.customfields.api.dto.screen.ScreenResult;
import ru.rasulov.customfields.api.dto.screen.ScreenStatus;
import ru.rasulov.customfields.api.dto.screen.ScreenTarget;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Adding the field to one screen: which tab, last by default, idempotent, and problems are results, not exceptions. */
public class ScreenWriterTest {
    @org.junit.After
    public void closeUndoDatabases() { ru.rasulov.customfields.impl.JiraMocks.closeJournals(); }
    private static final String FIELD = "customfield_10500";

    private FieldScreenManager screenManager;
    private FieldScreen screen;
    private FieldScreenTab first;
    private FieldScreenTab second;
    private ScreenWriter writer;

    @Before
    public void setUp() {
        screenManager = mock(FieldScreenManager.class);
        writer = new ScreenWriter(screenManager);
        first = tab(11L, "General");
        second = tab(12L, "Details");
        screen = mock(FieldScreen.class);
        when(screen.getId()).thenReturn(5L);
        when(screen.getName()).thenReturn("A screen");
        when(screen.getTabs()).thenReturn(Arrays.asList(first, second));
        when(screenManager.getFieldScreen(5L)).thenReturn(screen);
    }

    @Test
    public void theFieldGoesToTheFirstTabWhenNoTabIsAskedFor() {
        ScreenResult result = writer.add(FIELD, new ScreenTarget(5L, null, null, null), false);

        assertEquals(ScreenStatus.ADDED, result.getStatus());
        assertEquals("General", result.getTab());
        assertEquals("A screen", result.getName());
        assertEquals(FIELD, first.getFieldScreenLayoutItems().get(0).getFieldId());
        assertEquals(Collections.emptyList(), second.getFieldScreenLayoutItems());
    }

    @Test
    public void aTabIsFoundByNameIgnoringCaseAndById() {
        ScreenResult byName = writer.add(FIELD, new ScreenTarget(5L, "details", null, null), true);
        ScreenResult byId = writer.add(FIELD, new ScreenTarget(5L, "12", null, null), true);

        assertEquals("Details", byName.getTab());
        assertEquals("Details", byId.getTab());
    }

    @Test
    public void aFieldAlreadyOnTheScreenIsLeftAloneWhateverTheTab() {
        when(screen.containsField(FIELD)).thenReturn(true);

        ScreenResult result = writer.add(FIELD, new ScreenTarget(5L, "Details", null, null), false);

        assertEquals(ScreenStatus.ALREADY_PRESENT, result.getStatus());
        verify(first, never()).addFieldScreenLayoutItem(eq(FIELD), anyInt());
        verify(second, never()).addFieldScreenLayoutItem(eq(FIELD), anyInt());
    }

    @Test
    public void aDryRunReportsWhatWouldHappenAndWritesNothing() {
        ScreenResult result = writer.add(FIELD, new ScreenTarget(5L, null, null, null), true);

        assertEquals(ScreenStatus.WOULD_ADD, result.getStatus());
        verify(first, never()).addFieldScreenLayoutItem(eq(FIELD), anyInt());
    }

    @Test
    public void anUnknownTabReturnsAnErrorWithoutChangingTheScreen() {
        ScreenResult result = writer.add(FIELD, new ScreenTarget(5L, "Nope", null, null), false);

        assertEquals(ScreenStatus.FAILED, result.getStatus());
        assertEquals(ScreenErrorCode.UNKNOWN_TAB, result.getErrorCode());
        verify(first, never()).addFieldScreenLayoutItem(eq(FIELD), anyInt());
    }

    @Test
    public void aScreenWithoutTabsCannotTakeTheField() {
        when(screen.getTabs()).thenReturn(Collections.<FieldScreenTab>emptyList());

        ScreenResult result = writer.add(FIELD, new ScreenTarget(5L, null, null, null), false);

        assertEquals(ScreenErrorCode.UNKNOWN_TAB, result.getErrorCode());
    }

    @Test
    public void anUnknownScreenIsAResult() {
        ScreenResult result = writer.add(FIELD, new ScreenTarget(99L, null, null, null), false);

        assertEquals(ScreenErrorCode.UNKNOWN_SCREEN, result.getErrorCode());
        assertEquals(Long.valueOf(99L), result.getScreenId());
        assertNull(result.getName());
    }

    @Test
    public void aMissingScreenIdIsAValidationError() {
        assertEquals(ScreenErrorCode.VALIDATION_ERROR, writer.add(FIELD, new ScreenTarget(null, null, null, null), false).getErrorCode());
        assertEquals(ScreenErrorCode.VALIDATION_ERROR, writer.add(FIELD, null, false).getErrorCode());
    }

    @Test
    public void aFailedJiraWriteIsAnUnknownResultNotAnExceptionAndTheScreenIsReadBack() {
        doThrow(new IllegalStateException("db down")).when(first).addFieldScreenLayoutItem(FIELD, 0);

        ScreenResult result = writer.add(FIELD, new ScreenTarget(5L, null, null, null), false);

        assertEquals(ScreenStatus.UNKNOWN, result.getStatus());
        assertEquals(ScreenErrorCode.JIRA_ERROR, result.getErrorCode());
        assertEquals(Long.valueOf(5L), result.getScreenId());
        assertEquals("A screen", result.getName());
    }

    @Test
    public void aFailureBeforeAnyWriteIsFailedAndPromisesNothingWasChanged() {
        when(screenManager.getFieldScreen(5L)).thenThrow(new IllegalStateException("db down"));

        ScreenResult result = writer.add(FIELD, new ScreenTarget(5L, null, null, null), false);

        assertEquals(ScreenStatus.FAILED, result.getStatus());
        assertEquals(ScreenErrorCode.JIRA_ERROR, result.getErrorCode());
        verify(first, never()).addFieldScreenLayoutItem(eq(FIELD), anyInt());
    }

    private static FieldScreenTab tab(long id, String name) {
        FieldScreenTab tab = mock(FieldScreenTab.class);
        when(tab.getId()).thenReturn(id);
        when(tab.getName()).thenReturn(name);
        List<FieldScreenLayoutItem> items = new ArrayList<>();
        when(tab.getFieldScreenLayoutItems()).thenAnswer(call -> new ArrayList<>(items));
        when(tab.getFieldScreenLayoutItem(anyInt())).thenAnswer(call -> items.get(call.<Integer>getArgument(0)));
        doAnswer(call -> {
            int index = call.getArgument(1);
            FieldScreenLayoutItem item = mock(FieldScreenLayoutItem.class);
            when(item.getId()).thenReturn(id * 100 + items.size());
            when(item.getFieldId()).thenReturn(call.getArgument(0));
            when(item.getPosition()).thenReturn(index);
            items.add(index, item);
            return null;
        }).when(tab).addFieldScreenLayoutItem(anyString(), anyInt());
        return tab;
    }
}
