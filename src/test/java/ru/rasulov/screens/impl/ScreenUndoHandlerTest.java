package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.*;
import com.atlassian.jira.workflow.WorkflowManager;
import org.junit.Test;
import ru.rasulov.common.impl.undo.UndoStep;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Jira's mutable object model, not forwarding assertions: conflicts must preserve live data. */
public class ScreenUndoHandlerTest {
    private final FieldScreenManager screens = mock(FieldScreenManager.class);
    private final FieldScreenSchemeManager schemes = mock(FieldScreenSchemeManager.class);
    private final WorkflowManager workflows = mock(WorkflowManager.class);
    private final ScreenUndoHandler handler = new ScreenUndoHandler(screens, schemes, workflows);

    private static UndoStep step(long id, String resource, Map<String, Object> before, Map<String, Object> after) {
        return new UndoStep(id, "screen.change", resource, ScreenUndoHandler.json(before),
                after == null ? null : ScreenUndoHandler.json(after), after == null ? "PREPARED" : "APPLIED");
    }
    private FieldScreen screen(long id, String name, List<FieldScreenTab> tabs) {
        FieldScreen screen = mock(FieldScreen.class);
        when(screen.getId()).thenReturn(id); when(screen.getName()).thenReturn(name);
        when(screen.getTabs()).thenReturn(tabs);
        when(screens.getFieldScreen(id)).thenReturn(screen);
        return screen;
    }
    private FieldScreenScheme scheme(long id) {
        FieldScreenScheme scheme = mock(FieldScreenScheme.class);
        when(scheme.getId()).thenReturn(id); when(scheme.getName()).thenReturn("Scheme " + id);
        when(scheme.getFieldScreenSchemeItems()).thenReturn(new ArrayList<FieldScreenSchemeItem>());
        when(schemes.getFieldScreenScheme(id)).thenReturn(scheme);
        return scheme;
    }

    @Test public void reversesCreatedChildrenBeforeDeletingOnlyTheUnchangedCreatedScreen() {
        List<FieldScreenTab> tabs = new ArrayList<FieldScreenTab>();
        FieldScreen copy = screen(20L, "Copied", tabs);
        FieldScreen source = screen(10L, "Source", new ArrayList<FieldScreenTab>());
        doAnswer(call -> { when(screens.getFieldScreen(20L)).thenReturn(null); return null; })
                .when(screens).removeFieldScreen(20L);
        when(workflows.getWorkflowsIncludingDrafts()).thenReturn(Collections.emptyList());
        UndoStep create = step(1L, "screen:Copied", ScreenUndoHandler.absent("screen", "Copied"), handler.snapshot(copy));
        Map<String, Object> empty = handler.snapshot(copy);
        FieldScreenTab tab = mock(FieldScreenTab.class);
        when(tab.getId()).thenReturn(21L); when(tab.getName()).thenReturn("Fields");
        when(tab.getFieldScreenLayoutItems()).thenReturn(new ArrayList<FieldScreenLayoutItem>());
        tabs.add(tab);
        doAnswer(call -> { tabs.remove(tab); return null; }).when(screens).removeFieldScreenTab(21L);
        UndoStep addTab = step(2L, "screen:Copied", empty, handler.snapshot(copy));
        handler.undo(addTab, null); handler.undo(create, null); handler.undo(create, null);
        assertNull(screens.getFieldScreen(20L));
        assertSame(source, screens.getFieldScreen(10L));
    }

    @Test public void foreignSchemeReferenceBlocksCreatedScreenBeforeAnyUndo() {
        FieldScreen copy = screen(20L, "Copied", new ArrayList<FieldScreenTab>());
        FieldScreenScheme foreign = scheme(30L);
        when(schemes.getFieldScreenSchemes(copy)).thenReturn(Collections.singletonList(foreign));
        UndoStep create = step(1L, "screen:Copied", ScreenUndoHandler.absent("screen", "Copied"), handler.snapshot(copy));
        assertNotNull(handler.conflict(create, Collections.singletonList(create)));
        try { handler.undo(create, null); fail("Referenced screen must remain"); }
        catch (IllegalStateException expected) { assertSame(copy, screens.getFieldScreen(20L)); }
    }

    @Test public void workflowIncludingDraftReferenceIsSafeOnlyWithItsSelectedRemoval() {
        FieldScreen copy = screen(20L, "Copied", new ArrayList<FieldScreenTab>());
        com.atlassian.jira.workflow.JiraWorkflow workflow = mock(com.atlassian.jira.workflow.JiraWorkflow.class);
        when(workflow.getName()).thenReturn("Own workflow");
        com.opensymphony.workflow.loader.ActionDescriptor action = mock(com.opensymphony.workflow.loader.ActionDescriptor.class);
        when(action.getMetaAttributes()).thenReturn(Collections.singletonMap("jira.fieldscreen.id", "20"));
        when(workflow.getAllActions()).thenReturn(Collections.singletonList(action));
        when(workflows.getWorkflowsIncludingDrafts()).thenReturn(Collections.singletonList(workflow));
        UndoStep screen = step(1L, "screen:Copied", ScreenUndoHandler.absent("screen", "Copied"), handler.snapshot(copy));
        UndoStep createdWorkflow = new UndoStep(2L, "workflow.created", "workflow:Own workflow",
                "{\"absent\":true,\"name\":\"Own workflow\"}", "{\"name\":\"Own workflow\"}", "APPLIED");
        assertNotNull(handler.conflict(screen, Collections.singletonList(screen)));
        assertNull(handler.conflict(screen, Arrays.asList(createdWorkflow, screen)));
        try { handler.undo(screen, null); fail("Actual reference must still prevent deletion"); }
        catch (IllegalStateException expected) { assertSame(copy, screens.getFieldScreen(20L)); }
    }

    @Test public void durableJournalReopensAndReversesSuccessfulStandaloneWriterChildren() {
        List<FieldScreenTab> tabs = new ArrayList<FieldScreenTab>();
        FieldScreen copy = screen(20L, "Copied", tabs);
        FieldScreen source = screen(10L, "Source", new ArrayList<FieldScreenTab>());
        when(screens.getFieldScreen(20L)).thenReturn(null);
        FieldScreenFactory factory = mock(FieldScreenFactory.class);
        when(factory.createScreen()).thenReturn(copy);
        doAnswer(call -> { when(screens.getFieldScreen(20L)).thenReturn(copy); return null; })
                .when(screens).createFieldScreen(copy);
        doAnswer(call -> { when(screens.getFieldScreen(20L)).thenReturn(null); return null; })
                .when(screens).removeFieldScreen(20L);
        FieldScreenTab tab = mock(FieldScreenTab.class);
        when(tab.getId()).thenReturn(21L); when(tab.getName()).thenReturn(StandaloneScreenWriter.TAB_NAME);
        List<FieldScreenLayoutItem> fields = new ArrayList<FieldScreenLayoutItem>();
        when(tab.getFieldScreenLayoutItems()).thenReturn(fields);
        when(copy.addTab(StandaloneScreenWriter.TAB_NAME)).thenAnswer(call -> { tabs.add(tab); return tab; });
        doAnswer(call -> {
            String fieldId = call.getArgument(0);
            FieldScreenLayoutItem item = mock(FieldScreenLayoutItem.class);
            when(item.getId()).thenReturn(30L + fields.size());
            when(item.getFieldId()).thenReturn(fieldId);
            when(item.getPosition()).thenAnswer(ignored -> fields.indexOf(item));
            fields.add(item);
            return null;
        }).when(tab).addFieldScreenLayoutItem(anyString());
        when(tab.removeFieldScreenLayoutItem(anyInt())).thenAnswer(call -> fields.remove((int) call.getArgument(0)));
        doAnswer(call -> { tabs.remove(tab); return null; }).when(screens).removeFieldScreenTab(21L);
        when(workflows.getWorkflowsIncludingDrafts()).thenReturn(Collections.emptyList());
        try (ru.rasulov.common.impl.undo.UndoTestFixture database =
                     ru.rasulov.common.impl.undo.UndoTestFixture.create("admin", handler)) {
            ScreenJournal changes = new ScreenJournal(database.journal, handler);
            StandaloneScreenWriter writer = new StandaloneScreenWriter(factory, screens, changes);
            String session = database.journal.createSession("admin");
            String set = database.journal.createChangeSet(session, "admin");
            String operation = database.journal.openOperation("create_screen", "admin", session, set);
            database.journal.runOperation(operation, () -> {
                writer.reserve("Copied", null, 2);
                FieldScreen created = writer.write("Copied", null, Arrays.asList("summary", "customfield_1"));
                return Collections.singletonMap("screenId", created.getId());
            });
            assertEquals(Arrays.asList("summary", "customfield_1"),
                    Arrays.asList(fields.get(0).getFieldId(), fields.get(1).getFieldId()));
            ru.rasulov.common.impl.undo.ChangeJournal reopened = database.reopen();
            Map<String, Object> preview = reopened.rollback("admin", operation, null, null, true, null);
            assertEquals("READY", preview.get("status"));
            Map<String, Object> result = reopened.rollback("admin", null, null, null, false,
                    (String) preview.get("planId"));
            assertEquals("ROLLED_BACK", result.get("status"));
            assertNull(screens.getFieldScreen(20L));
            assertSame(source, screens.getFieldScreen(10L));
        }
    }

    @Test public void persistedForeignEditBlocksPreflightAndUndoDespiteStaleCachedCopy() {
        FieldScreen cached = screen(20L, "Copied", new ArrayList<FieldScreenTab>());
        UndoStep creation = step(1L, "screen:Copied", ScreenUndoHandler.absent("screen", "Copied"),
                handler.snapshot(cached));
        FieldScreen foreign = mock(FieldScreen.class);
        when(foreign.getId()).thenReturn(20L);
        when(foreign.getName()).thenReturn("Renamed by another administrator");
        when(foreign.getTabs()).thenReturn(new ArrayList<FieldScreenTab>());
        AtomicReference<FieldScreen> cachedRead = new AtomicReference<FieldScreen>(cached);
        when(screens.getFieldScreen(20L)).thenAnswer(call -> cachedRead.get());
        doAnswer(call -> { cachedRead.set(foreign); return null; }).when(screens).refresh();
        assertNotNull(handler.conflict(creation, Collections.singletonList(creation)));
        cachedRead.set(cached);
        assertNotNull(handler.conflict(creation));
        cachedRead.set(cached);
        try { handler.undo(creation, null); fail("Cached fingerprint must not authorize deletion"); }
        catch (IllegalStateException expected) {
            assertSame(foreign, screens.getFieldScreen(20L));
            assertEquals("Renamed by another administrator", screens.getFieldScreen(20L).getName());
        }
    }

    @Test public void unknownCreateOutcomeCannotDeleteObjectWithMatchingNaturalName() {
        FieldScreen unrelated = screen(20L, "Copied", new ArrayList<FieldScreenTab>());
        UndoStep uncertain = step(1L, "screen:Copied", ScreenUndoHandler.absent("screen", "Copied"), null);
        assertNotNull(handler.conflict(uncertain));
        assertSame(unrelated, screens.getFieldScreen(20L));
    }

    @Test public void alreadyUndoneWorkflowCannotAuthorizeDeletingAForeignReference() {
        FieldScreen copy = screen(20L, "Copied", new ArrayList<FieldScreenTab>());
        com.atlassian.jira.workflow.JiraWorkflow foreign = mock(com.atlassian.jira.workflow.JiraWorkflow.class);
        when(foreign.getName()).thenReturn("Own workflow");
        com.opensymphony.workflow.loader.ActionDescriptor action = mock(com.opensymphony.workflow.loader.ActionDescriptor.class);
        when(action.getMetaAttributes()).thenReturn(Collections.singletonMap("jira.fieldscreen.id", "20"));
        when(foreign.getAllActions()).thenReturn(Collections.singletonList(action));
        when(workflows.getWorkflowsIncludingDrafts()).thenReturn(Collections.singletonList(foreign));
        UndoStep screen = step(1L, "screen:Copied", ScreenUndoHandler.absent("screen", "Copied"), handler.snapshot(copy));
        UndoStep undoneWorkflow = new UndoStep(2L, "workflow.created", "workflow:Own workflow",
                "{\"absent\":true,\"name\":\"Own workflow\"}", "{\"name\":\"Own workflow\"}", "UNDONE");
        assertNotNull(handler.conflict(screen, Arrays.asList(undoneWorkflow, screen)));
        assertSame(copy, screens.getFieldScreen(20L));
    }
}
