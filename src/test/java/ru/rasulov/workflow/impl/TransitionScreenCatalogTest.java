package ru.rasulov.workflow.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.WorkflowErrorCode;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TransitionScreenCatalogTest {
    @Test
    public void resolvesByIdWithoutScanningScreenNames() {
        FieldScreenManager manager = mock(FieldScreenManager.class);
        FieldScreen screen = screen(12000L, "Release");
        when(manager.getFieldScreen(12000L)).thenReturn(screen);

        assertSame(screen, new TransitionScreenCatalog(manager).resolve("12000", "transition.screen"));
    }

    @Test
    public void resolvesOneExactNameIgnoringCase() {
        FieldScreenManager manager = mock(FieldScreenManager.class);
        FieldScreen screen = screen(12000L, "Release Screen");
        when(manager.getFieldScreens()).thenReturn(Collections.singletonList(screen));

        assertSame(screen, new TransitionScreenCatalog(manager).resolve(" release screen ", "transition.screen"));
    }

    @Test
    public void duplicateExactNamesRequireAnId() {
        FieldScreenManager manager = mock(FieldScreenManager.class);
        FieldScreen first = screen(12001L, "Release");
        FieldScreen second = screen(12000L, "RELEASE");
        when(manager.getFieldScreens()).thenReturn(Arrays.asList(first, second));

        try {
            new TransitionScreenCatalog(manager).resolve("Release", "transition.screen");
            fail("expected an ambiguous screen failure");
        } catch (WorkflowFailureException expected) {
            assertEquals(WorkflowErrorCode.AMBIGUOUS_SCREEN, expected.getErrorCode());
            assertEquals(Arrays.asList("12000 (RELEASE)", "12001 (Release)"), expected.getSuggestions());
        }
    }

    private static FieldScreen screen(long id, String name) {
        FieldScreen screen = mock(FieldScreen.class);
        when(screen.getId()).thenReturn(id);
        when(screen.getName()).thenReturn(name);
        return screen;
    }
}
