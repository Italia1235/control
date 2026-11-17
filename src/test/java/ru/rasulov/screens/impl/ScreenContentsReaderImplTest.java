package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.Field;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.screen.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.screens.api.dto.ScreenContentsResponse;
import ru.rasulov.screens.api.dto.ScreenContentsResponse.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ScreenContentsReaderImplTest {
    @Test
    public void readsActualOrderKindsEmptyTabsAndFlagsWithoutCatalogScans() throws Exception {
        Fixture f = new Fixture();
        f.tabs.add(tab(12, "Later", 2, item(3, "missing", 9, false)));
        f.tabs.add(tab(11, "First", 0, item(2, "customfield_1", 7, false), item(1, "summary", 1, true)));
        f.tabs.add(tab(13, "Empty", 1));
        f.field("summary", "Summary", false);
        f.field("customfield_1", "Custom", true);
        ScreenContentsResponse r = f.read(null);
        assertEquals(Status.READ, r.getStatus());
        assertTrue(r.isComplete());
        assertNull(r.getNextCursor());
        assertEquals(3, r.getTotalTabs());
        assertEquals(3, r.getTotalFields());
        assertEquals(Arrays.asList(11L, 13L, 12L), tabIds(r));
        TabView first = r.getTabs().get(0);
        assertEquals(2, first.getFieldCount());
        assertEquals(0, first.getPosition());
        assertEquals(1L, first.getFields().get(0).getLayoutItemId().longValue());
        assertEquals(Kind.SYSTEM, first.getFields().get(0).getKind());
        assertEquals("Summary", first.getFields().get(0).getName());
        assertTrue(first.getFields().get(0).isShownWhenEmpty());
        assertEquals(Kind.CUSTOM, first.getFields().get(1).getKind());
        assertFalse(first.getFields().get(1).isShownWhenEmpty());
        assertEquals("Custom", first.getFields().get(1).getName());
        assertTrue(r.getTabs().get(1).getFields().isEmpty());
        FieldView unknown = r.getTabs().get(2).getFields().get(0);
        assertEquals(Kind.UNKNOWN, unknown.getKind());
        assertNull(unknown.getName());
        assertEquals("missing", unknown.getFieldId());
        JsonNode json = new ObjectMapper().setSerializationInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL).valueToTree(r);
        assertFalse(json.has("nextCursor"));
        assertFalse(json.has("errorCode"));
        assertTrue(json.get("tabs").get(2).get("fields").get(0).get("name").isNull());
        f.verifyFields(Arrays.asList("summary", "customfield_1", "missing"));
        verify(f.screens, never()).getFieldScreens();
        verify(f.screens).getFieldScreen(10L);
        verifyNoMoreInteractions(f.screens);
    }

    @Test
    public void paginatesAcrossTabsAndEmptyBoundaryWithoutLossOrRepeatedMetadataLookups() {
        Fixture f = large();
        f.tabs.add(1, tab(13, "Empty boundary", 1));
        ScreenContentsResponse a = f.read(null);
        ScreenContentsResponse b = f.read(a.getNextCursor());
        ScreenContentsResponse c = f.read(b.getNextCursor());
        assertEquals(200, count(a));
        assertEquals(199, count(b));
        assertEquals(51, count(c));
        assertEquals(450, a.getTotalFields());
        assertEquals(3, a.getTotalTabs());
        assertFalse(a.isComplete());
        assertFalse(b.isComplete());
        assertTrue(c.isComplete());
        assertNull(c.getNextCursor());
        assertTrue(b.getTabs().get(0).getFields().isEmpty());
        assertEquals(13L, b.getTabs().get(0).getId().longValue());
        TabView split = b.getTabs().get(1);
        TabView tail = c.getTabs().get(0);
        assertEquals(split.getId(), tail.getId());
        assertEquals(split.getName(), tail.getName());
        assertEquals(split.getPosition(), tail.getPosition());
        assertEquals(250, tail.getFieldCount());
        Set<Long> ids = new HashSet<Long>();
        for (ScreenContentsResponse page : Arrays.asList(a, b, c)) {
            for (TabView tab : page.getTabs()) {
                for (FieldView field : tab.getFields()) {
                    assertTrue("duplicate layout item", ids.add(field.getLayoutItemId()));
                }
            }
        }
        for (long id = 1; id <= 450; id++) assertTrue(ids.contains(id));
        List<String> expected = new ArrayList<String>();
        for (int i = 1; i <= 450; i++) expected.add("field" + i);
        f.verifyFields(expected);
        verify(f.screens, never()).getFieldScreens();
        verify(f.screens, times(3)).getFieldScreen(10L);
        verifyNoMoreInteractions(f.screens);
    }

    @Test
    public void pages450FieldsAs20020050() {
        Fixture f = large();
        ScreenContentsResponse a = f.read(null);
        ScreenContentsResponse b = f.read(a.getNextCursor());
        ScreenContentsResponse c = f.read(b.getNextCursor());
        assertEquals(200, count(a));
        assertEquals(200, count(b));
        assertEquals(50, count(c));
        assertFalse(a.isComplete());
        assertFalse(b.isComplete());
        assertTrue(c.isComplete());
    }

    @Test
    public void rejectsChangedCompositionAndDescriptionBeforeResolvingPageFields() {
        for (boolean description : Arrays.asList(false, true)) {
            Fixture f = large();
            String cursor = f.read(null).getNextCursor();
            clearInvocations(f.fields);
            if (description) when(f.screen.getDescription()).thenReturn("changed");
            else when(f.tabs.get(1).getFieldScreenLayoutItems().get(0).isShownWhenEmpty()).thenReturn(true);
            String message = invalid(() -> f.read(cursor));
            assertEquals("Screen contents changed. Discard previous pages and restart get_screen_contents without cursor.", message);
            verifyNoInteractions(f.fields);
        }
    }

    @Test
    public void rejectsForeignMalformedAndInvalidOffsets() {
        Fixture f = large();
        String cursor = f.read(null).getNextCursor();
        String payload = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        for (String bad : Arrays.asList("!", "", cursor + "!", encode(payload.replace("1\n10\n", "1\n11\n")),
                encode(payload.substring(0, payload.lastIndexOf('\n') + 1) + "0"),
                encode(payload.substring(0, payload.lastIndexOf('\n') + 1) + "450"),
                encode(payload.substring(0, payload.lastIndexOf('\n') + 1) + "-1"),
                encode(payload.substring(0, payload.lastIndexOf('\n') + 1) + "201"),
                encode(payload.replaceFirst("1", "2")), String.join("", Collections.nCopies(513, "a")))) {
            String message = invalid(() -> f.read(bad));
            assertTrue(message, message.startsWith("Invalid screen cursor"));
            assertTrue(message, message.contains("restart without cursor"));
        }
    }

    @Test
    public void missingScreenIsDomainFailureAndGuardRefusalReadsNothing() {
        Fixture f = new Fixture();
        when(f.screens.getFieldScreen(10L)).thenReturn(null);
        ScreenContentsResponse r = f.read(null);
        assertEquals(Status.FAILED, r.getStatus());
        assertEquals(ErrorCode.SCREEN_NOT_FOUND, r.getErrorCode());
        assertTrue(r.getMessage().contains("get_project_screens"));
        Fixture denied = new Fixture();
        RuntimeException refusal = new RuntimeException("denied");
        when(denied.guard.requireAdministrator()).thenThrow(refusal);
        try {
            denied.read(null);
            fail();
        } catch (RuntimeException e) {
            assertSame(refusal, e);
        }
        verifyNoInteractions(denied.screens, denied.fields);
    }

    @Test
    public void screenWithoutTabsIsComplete() {
        Fixture f = new Fixture();
        when(f.screen.getDescription()).thenReturn(null);
        ScreenContentsResponse r = f.read(null);
        assertTrue(r.isComplete());
        assertEquals(0, r.getTotalTabs());
        assertEquals(0, r.getTotalFields());
        assertTrue(r.getTabs().isEmpty());
        assertNull(r.getNextCursor());
        JsonNode json = new ObjectMapper().setSerializationInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL).valueToTree(r);
        assertTrue(json.get("screen").get("description").isNull());
    }

    private static String invalid(Runnable call) {
        try {
            call.run();
        } catch (InvalidRequestException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected invalid request");
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int count(ScreenContentsResponse r) {
        int n = 0;
        for (TabView t : r.getTabs()) n += t.getFields().size();
        return n;
    }

    private static List<Long> tabIds(ScreenContentsResponse r) {
        List<Long> ids = new ArrayList<Long>();
        for (TabView t : r.getTabs()) ids.add(t.getId());
        return ids;
    }

    private static Fixture large() {
        Fixture f = new Fixture();
        List<FieldScreenLayoutItem> first = new ArrayList<FieldScreenLayoutItem>();
        List<FieldScreenLayoutItem> second = new ArrayList<FieldScreenLayoutItem>();
        for (int i = 1; i <= 450; i++) (i <= 200 ? first : second).add(item(i, "field" + i, i, false));
        f.tabs.add(tab(11, "First", 0, first.toArray(new FieldScreenLayoutItem[0])));
        f.tabs.add(tab(12, "Second", 2, second.toArray(new FieldScreenLayoutItem[0])));
        return f;
    }

    private static FieldScreenLayoutItem item(long id, String field, int position, boolean shown) {
        FieldScreenLayoutItem i = mock(FieldScreenLayoutItem.class);
        when(i.getId()).thenReturn(id);
        when(i.getFieldId()).thenReturn(field);
        when(i.getPosition()).thenReturn(position);
        when(i.isShownWhenEmpty()).thenReturn(shown);
        return i;
    }

    private static FieldScreenTab tab(long id, String name, int position, FieldScreenLayoutItem... items) {
        FieldScreenTab t = mock(FieldScreenTab.class);
        when(t.getId()).thenReturn(id);
        when(t.getName()).thenReturn(name);
        when(t.getPosition()).thenReturn(position);
        when(t.getFieldScreenLayoutItems()).thenReturn(Arrays.asList(items));
        return t;
    }

    private static class Fixture {
        final AdministratorGuard guard = mock(AdministratorGuard.class);
        final FieldScreenManager screens = mock(FieldScreenManager.class);
        final FieldManager fields = mock(FieldManager.class);
        final FieldScreen screen = mock(FieldScreen.class);
        final List<FieldScreenTab> tabs = new ArrayList<FieldScreenTab>();
        final ScreenContentsReaderImpl reader = new ScreenContentsReaderImpl(guard, screens, fields);

        Fixture() {
            when(screens.getFieldScreen(10L)).thenReturn(screen);
            when(screen.getId()).thenReturn(10L);
            when(screen.getName()).thenReturn("Screen");
            when(screen.getDescription()).thenReturn("Description");
            when(screen.getTabs()).thenReturn(tabs);
        }

        void field(String id, String name, boolean custom) {
            Field field = mock(Field.class);
            when(field.getName()).thenReturn(name);
            when(fields.getField(id)).thenReturn(field);
            when(fields.isCustomFieldId(id)).thenReturn(custom);
        }

        ScreenContentsResponse read(String cursor) {
            return reader.read(10L, cursor);
        }

        void verifyFields(List<String> ids) {
            for (String id : ids) {
                verify(fields).getField(id);
                verify(fields).isCustomFieldId(id);
            }
            verifyNoMoreInteractions(fields);
        }
    }
}
