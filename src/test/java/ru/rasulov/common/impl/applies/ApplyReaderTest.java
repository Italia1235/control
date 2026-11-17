package ru.rasulov.common.impl.applies;

import net.java.ao.EntityManager;
import net.java.ao.atlassian.AtlassianTableNameConverter;
import net.java.ao.builder.EntityManagerBuilder;
import net.java.ao.schema.Case;
import net.java.ao.schema.UnderscoreFieldNameConverter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.api.ScreenLayoutLimits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class ApplyReaderTest {
    private EntityManager entities;
    private ApplyJournal journal;
    private ApplyReader reader;
    private int sequence;

    @Before
    public void setUp() throws Exception {
        entities = EntityManagerBuilder.url("jdbc:hsqldb:mem:reader_" + UUID.randomUUID().toString().replace("-", ""))
                .username("SA").password("").none()
                .fieldNameConverter(new UnderscoreFieldNameConverter(Case.UPPER))
                .tableNameConverter(new AtlassianTableNameConverter(name -> "AO_3E3015_" + name)).build();
        entities.migrate(ApplyRecord.class);
        ApplyDatabase database = new EntityApplyDatabase(entities);
        journal = new ApplyJournal(database, mock(Logger.class));
        reader = new ApplyReader(database);
    }

    @After
    public void tearDown() throws Exception {
        entities.getProvider().dispose();
    }

    private void add(long at, String actor, String actorName, String tool, String toolTitle, String state, String status) {
        journal.record(new ApplyEvent("APPLY:" + (sequence++), "APPLY", at, actor, actorName, tool, toolTitle, null, state,
                status, null, null, false, null, 0, null));
    }

    private static ApplyQuery query(String actor, String tool, String state, String status, String from, String to,
                                    String before, String limit) {
        return ApplyQuery.parse(actor, tool, state, status, from, to, before, limit);
    }

    private static ApplyQuery all() {
        return query(null, null, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static List<Object> ids(Map<String, Object> page) {
        List<Object> ids = new ArrayList<Object>();
        for (Map<String, Object> item : items(page)) ids.add(item.get("id"));
        return ids;
    }

    private static List<Object> field(Map<String, Object> page, String name) {
        List<Object> values = new ArrayList<Object>();
        for (Map<String, Object> item : items(page)) values.add(item.get(name));
        return values;
    }

    private void fixture() {
        add(100, "alice", "Alice", "create_screen", "Create screen", "COMPLETED", "CREATED");
        add(200, "bob", "Bob", "create_screen", "Create screen", "COMPLETED", "EXISTS");
        add(300, "alice", "Alice", "assign_workflow", "Assign workflow", "FAILED", "FAILED");
        add(400, "bob", "Bob", "assign_workflow", "Assign workflow", "CANCELLED", null);
    }

    @Test
    public void newestRowsComeFirstAndNothingFiltersByDefault() {
        fixture();
        Map<String, Object> page = reader.list(all());
        assertEquals(Arrays.<Object>asList(400L, 300L, 200L, 100L), field(page, "recordedAt"));
        assertNull(page.get("nextBefore"));
    }

    @Test
    public void everyFilterAloneAndTogether() {
        fixture();
        assertEquals(Arrays.<Object>asList(300L, 100L), field(reader.list(query("alice", null, null, null, null, null, null, null)), "recordedAt"));
        assertEquals(Arrays.<Object>asList(400L, 300L), field(reader.list(query(null, "assign_workflow", null, null, null, null, null, null)), "recordedAt"));
        assertEquals(Arrays.<Object>asList(400L), field(reader.list(query(null, null, "CANCELLED", null, null, null, null, null)), "recordedAt"));
        assertEquals(Arrays.<Object>asList(200L), field(reader.list(query(null, null, null, "EXISTS", null, null, null, null)), "recordedAt"));
        assertEquals(Arrays.<Object>asList(300L), field(reader.list(query("alice", "assign_workflow", "FAILED", "FAILED", "300", "301", null, null)), "recordedAt"));
        assertTrue(items(reader.list(query("alice", "assign_workflow", "COMPLETED", null, null, null, null, null))).isEmpty());
        // Filters are exact, not prefixes or patterns.
        assertTrue(items(reader.list(query("ali", null, null, null, null, null, null, null))).isEmpty());
        assertTrue(items(reader.list(query("%", null, null, null, null, null, null, null))).isEmpty());
    }

    @Test
    public void fromIsInclusiveAndToIsExclusive() {
        fixture();
        assertEquals(Arrays.<Object>asList(300L, 200L), field(reader.list(query(null, null, null, null, "200", "400", null, null)), "recordedAt"));
        assertEquals(Arrays.<Object>asList(200L), field(reader.list(query(null, null, null, null, "200", "201", null, null)), "recordedAt"));
        assertTrue(items(reader.list(query(null, null, null, null, "201", "300", null, null))).isEmpty());
        assertEquals(Arrays.<Object>asList(400L, 300L), field(reader.list(query(null, null, null, null, "300", null, null, null)), "recordedAt"));
        assertEquals(Arrays.<Object>asList(200L, 100L), field(reader.list(query(null, null, null, null, null, "300", null, null)), "recordedAt"));
    }

    @Test
    public void pagingWalksEveryRowOnceNewestFirstAndEndsWithNull() {
        for (int i = 0; i < 125; i++) add(1000 + i, "alice", "Alice", "t", "T", "COMPLETED", null);
        List<Object> seen = new ArrayList<Object>();
        String before = null;
        int pages = 0;
        while (true) {
            Map<String, Object> page = reader.list(query(null, null, null, null, null, null, before, "50"));
            seen.addAll(ids(page));
            pages++;
            Object next = page.get("nextBefore");
            if (next == null) break;
            assertEquals("nextBefore is the id of the last row", ids(page).get(ids(page).size() - 1), next);
            before = String.valueOf(next);
        }
        assertEquals(3, pages);
        assertEquals(125, seen.size());
        assertEquals(125, new HashSet<Object>(seen).size());
        List<Object> sorted = new ArrayList<Object>(seen);
        Collections.sort((List) sorted, Collections.reverseOrder());
        assertEquals(sorted, seen);
    }

    @Test
    public void aFullLastPageStillOffersACursorAndTheNextPageIsEmpty() {
        for (int i = 0; i < 4; i++) add(10 + i, "alice", "Alice", "t", "T", "COMPLETED", null);
        Map<String, Object> page = reader.list(query(null, null, null, null, null, null, null, "4"));
        assertEquals(4, items(page).size());
        assertNotNull(page.get("nextBefore"));
        assertEquals("total ignores the cursor", 4, page.get("total"));
        Map<String, Object> next = reader.list(query(null, null, null, null, null, null, String.valueOf(page.get("nextBefore")), "4"));
        assertTrue(items(next).isEmpty());
        assertNull(next.get("nextBefore"));
        assertEquals(4, next.get("total"));
    }

    @Test
    public void defaultLimitIs50AndMaximumIs200() {
        for (int i = 0; i < 210; i++) add(i + 1, "alice", "Alice", "t", "T", "COMPLETED", null);
        assertEquals(50, items(reader.list(all())).size());
        assertEquals(200, items(reader.list(query(null, null, null, null, null, null, null, "200"))).size());
        assertEquals(1, items(reader.list(query(null, null, null, null, null, null, null, "1"))).size());
    }

    @Test
    public void filtersApplyBeforeThePageIsCut() {
        for (int i = 0; i < 10; i++) add(i + 1, i % 2 == 0 ? "alice" : "bob", "N", "t", "T", "COMPLETED", null);
        Map<String, Object> page = reader.list(query("alice", null, null, null, null, null, null, "3"));
        assertEquals(3, items(page).size());
        for (Object actor : field(page, "actor")) assertEquals("alice", ((Map<?, ?>) actor).get("key"));
    }

    @Test
    public void everyInvalidParameterIsRejectedWithAMessageNamingIt() {
        String[][] bad = {
                {"from", "abc"}, {"to", "1.5"}, {"before", "x1"}, {"limit", "many"}, {"limit", "0"}, {"limit", "201"},
                {"limit", "-5"}, {"from", "9999999999999999999999"}};
        for (String[] pair : bad) {
            try {
                query(null, null, null, null, "from".equals(pair[0]) ? pair[1] : null, "to".equals(pair[0]) ? pair[1] : null,
                        "before".equals(pair[0]) ? pair[1] : null, "limit".equals(pair[0]) ? pair[1] : null);
                fail(pair[0] + "=" + pair[1] + " must be refused");
            } catch (InvalidRequestException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("'" + pair[0] + "'"));
            }
        }
        for (String[] range : new String[][] {{"5", "5"}, {"6", "5"}}) {
            try {
                query(null, null, null, null, range[0], range[1], null, null);
                fail("from >= to must be refused");
            } catch (InvalidRequestException expected) {
                assertTrue(expected.getMessage().contains("'from'"));
            }
        }
        // Blank counts as absent.
        assertEquals(50, query("", " ", "", "", "", "", "", "").limit);
    }

    @Test
    public void rowIsReturnedWithAllFieldsParsedTargetsAndNulls() {
        journal.record(new ApplyEvent("APPLY:x", "APPLY", 5000L, "alice", "Alice", "create_screen", "Create screen", "op-1",
                "COMPLETED", "CREATED", null, null, true,
                Arrays.asList(new ApplyTarget("SCREEN", "S", "10"), new ApplyTarget("PROJECT", "PAY", null)), 3,
                Arrays.asList(new ApplyLink("S", "http://j/s"), new ApplyLink(null, "http://j/t"))));
        Map<String, Object> item = items(reader.list(all())).get(0);
        assertEquals("APPLY", item.get("eventType"));
        assertEquals(5000L, item.get("recordedAt"));
        assertEquals("alice", ((Map<?, ?>) item.get("actor")).get("key"));
        assertEquals("Alice", ((Map<?, ?>) item.get("actor")).get("displayName"));
        assertEquals("create_screen", ((Map<?, ?>) item.get("tool")).get("name"));
        assertEquals("Create screen", ((Map<?, ?>) item.get("tool")).get("title"));
        assertEquals("op-1", item.get("operationRef"));
        assertEquals("CREATED", item.get("domainStatus"));
        assertNull(item.get("errorCode"));
        assertNull(item.get("errorMessage"));
        assertEquals(true, item.get("rollbackRequested"));
        Map<?, ?> targets = (Map<?, ?>) item.get("targets");
        assertEquals(3, targets.get("more"));
        List<?> targetItems = (List<?>) targets.get("items");
        assertEquals(2, targetItems.size());
        assertEquals("10", ((Map<?, ?>) targetItems.get(0)).get("id"));
        assertNull(((Map<?, ?>) targetItems.get(1)).get("id"));
        List<?> links = (List<?>) item.get("links");
        assertEquals("http://j/s", ((Map<?, ?>) links.get(0)).get("url"));
        assertNull(((Map<?, ?>) links.get(1)).get("name"));
    }

    private void layout(String key, ApplyFieldChanges changes) {
        journal.record(new ApplyEvent("APPLY:" + key, ApplyEvent.APPLY, 5000L, "alice", "Alice",
                "edit_screen_layout", "Edit screen layout", "op-" + key, "COMPLETED", "APPLIED",
                null, null, false, null, 0, null, changes));
    }

    private static Map<String, Object> change(String id, String action) {
        Map<String, Object> item = new HashMap<String, Object>();
        item.put("fieldId", id);
        item.put("action", action);
        return item;
    }

    private static ApplyFieldChanges changes(boolean available, List<Map<String, Object>> items) {
        Map<String, Object> section = new HashMap<String, Object>();
        section.put("available", available);
        section.put("items", items);
        return ApplyFieldChanges.from(section);
    }

    @Test
    public void layoutChangesSurviveStorageAndRepeatedReadsAtTheMaximumActionCount() throws Exception {
        List<Map<String, Object>> expected = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < ScreenLayoutLimits.MAX_FIELDS_PER_CALL; i++)
            expected.add(change("customfield_" + (10000 + i), i % 2 == 0 ? "added" : "deleted"));
        layout("max", changes(true, expected));
        assertEquals(1, entities.count(ApplyRecord.class));
        for (int read = 0; read < 2; read++) {
            Map<String, Object> item = items(new ApplyReader(new EntityApplyDatabase(entities)).list(all())).get(0);
            Map<?, ?> section = (Map<?, ?>) item.get("fieldChanges");
            assertEquals(true, section.get("available"));
            assertEquals(expected, section.get("items"));
            assertTrue(((List<?>) ((Map<?, ?>) item.get("targets")).get("items")).isEmpty());
        }
    }

    @Test
    public void fieldChangesDoNotInheritTargetStringOrJsonLimits() throws Exception {
        StringBuilder longId = new StringBuilder("customfield_");
        for (int i = 0; i < 1000; i++) longId.append('9');
        List<Map<String, Object>> expected = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < ScreenLayoutLimits.MAX_FIELDS_PER_CALL; i++)
            expected.add(change(longId.toString() + i, "added"));
        layout("unlimited", changes(true, expected));
        ApplyRecord stored = entities.find(ApplyRecord.class)[0];
        assertTrue(stored.getFieldChangesJson().length() > ApplyJournal.JSON_LIMIT);
        assertEquals(expected, ((Map<?, ?>) items(reader.list(all())).get(0).get("fieldChanges")).get("items"));
    }

    @Test
    public void oldLayoutRowsHaveNoSectionWhileUnknownAndKnownEmptyRemainDistinct() throws Exception {
        layout("old", null);
        layout("unknown", ApplyFieldChanges.unavailable());
        layout("empty", changes(true, Collections.<Map<String, Object>>emptyList()));
        List<Map<String, Object>> rows = items(reader.list(all()));
        assertEquals(true, ((Map<?, ?>) rows.get(0).get("fieldChanges")).get("available"));
        assertEquals(false, ((Map<?, ?>) rows.get(1).get("fieldChanges")).get("available"));
        assertTrue(((List<?>) ((Map<?, ?>) rows.get(1).get("fieldChanges")).get("items")).isEmpty());
        assertFalse(rows.get(2).containsKey("fieldChanges"));
        assertNull(entities.find(ApplyRecord.class, net.java.ao.Query.select().where("EVENT_KEY = ?", "APPLY:old"))[0].getFieldChangesJson());
    }

    @Test
    public void unrelatedToolsAndRollbackEventsNeverStoreAFieldChangesSection() throws Exception {
        ApplyFieldChanges changes = changes(true, Collections.singletonList(change("summary", "added")));
        journal.record(new ApplyEvent("APPLY:other", ApplyEvent.APPLY, 1L, "a", "A", "create_screen", "Screen",
                "op", "COMPLETED", "CREATED", null, null, false, null, 0, null, changes));
        journal.record(new ApplyEvent("ROLLBACK:layout", ApplyEvent.ROLLBACK, 2L, "a", "A", "edit_screen_layout", "Layout",
                "op", "ROLLED_BACK", null, null, null, false, null, 0, null, changes));
        for (ApplyRecord row : entities.find(ApplyRecord.class)) assertNull(row.getFieldChangesJson());
        for (Map<String, Object> row : items(reader.list(all()))) assertFalse(row.containsKey("fieldChanges"));
    }

    @Test
    public void brokenFieldChangeJsonOrItemsAreUnavailableRatherThanKnownEmpty() throws Exception {
        String[] broken = {"{not json", "null", "{}", "{\"available\":true,\"items\":[{\"fieldId\":\"summary\",\"action\":\"moved\"}]}",
                "{\"available\":true,\"items\":[{\"fieldId\":\"summary\",\"action\":\"added\"},{}]}"};
        for (int i = 0; i < broken.length; i++) {
            layout("broken" + i, null);
            ApplyRecord row = entities.find(ApplyRecord.class, net.java.ao.Query.select().where("EVENT_KEY = ?", "APPLY:broken" + i))[0];
            row.setFieldChangesJson(broken[i]);
            row.save();
        }
        for (Map<String, Object> row : items(reader.list(all()))) {
            Map<?, ?> section = (Map<?, ?>) row.get("fieldChanges");
            assertEquals(false, section.get("available"));
            assertTrue(((List<?>) section.get("items")).isEmpty());
        }
    }

    @Test
    public void brokenJsonInARowGivesEmptyTargetsAndLinksAndDoesNotFailThePage() throws Exception {
        add(1, "alice", "Alice", "t", "T", "COMPLETED", null);
        Map<String, Object> broken = new HashMap<String, Object>();
        for (String[] v : new String[][] {{"EVENT_KEY", "APPLY:broken"}, {"EVENT_TYPE", "APPLY"}, {"ACTOR_KEY", "a"},
                {"ACTOR_NAME", "A"}, {"TOOL_NAME", "t"}, {"TOOL_TITLE", "T"}, {"OPERATION_STATE", "COMPLETED"},
                {"TARGETS_JSON", "{not json"}, {"LINKS_JSON", "[{\"url\":"}}) broken.put(v[0], v[1]);
        broken.put("RECORDED_AT", 2L);
        broken.put("ROLLBACK_REQUESTED", false);
        entities.create(ApplyRecord.class, broken);
        broken.put("EVENT_KEY", "APPLY:shape");
        broken.put("TARGETS_JSON", "{\"items\":[{\"type\":1}],\"more\":2}");
        broken.put("LINKS_JSON", "{\"not\":\"an array\"}");
        entities.create(ApplyRecord.class, broken);
        Map<String, Object> page = reader.list(all());
        assertEquals(3, items(page).size());
        for (int i = 0; i < 2; i++) {
            Map<String, Object> item = items(page).get(i);
            Map<?, ?> targets = (Map<?, ?>) item.get("targets");
            assertTrue(((List<?>) targets.get("items")).isEmpty());
            assertEquals(0, targets.get("more"));
            assertTrue(((List<?>) item.get("links")).isEmpty());
        }
        assertEquals(0, ((Map<?, ?>) items(page).get(2).get("targets")).get("more"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void facetsAreDistinctSortedAndNamedFromTheNewestRow() {
        add(1, "u2", "Zed", "b_tool", "Beta", "COMPLETED", "CREATED");
        add(2, "u1", "Old Name", "a_tool", "Old title", "COMPLETED", "CREATED");
        add(3, "u1", "alice renamed", "a_tool", "Alpha", "FAILED", null);
        add(4, "u3", "bob", "c_tool", "Gamma", "BLOCKED", "BLOCKED");
        Map<String, Object> facets = reader.facets();
        List<Map<String, Object>> actors = (List<Map<String, Object>>) facets.get("actors");
        assertEquals(Arrays.asList("u1", "u3", "u2"), keys(actors, "key"));
        assertEquals("alice renamed", actors.get(0).get("displayName"));
        List<Map<String, Object>> tools = (List<Map<String, Object>>) facets.get("tools");
        assertEquals(Arrays.asList("a_tool", "b_tool", "c_tool"), keys(tools, "name"));
        assertEquals("Alpha", tools.get(0).get("title"));
        assertEquals(Arrays.asList("BLOCKED", "COMPLETED", "FAILED"), facets.get("states"));
        assertEquals(Arrays.asList("BLOCKED", "CREATED"), facets.get("statuses"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void facetsOfAnEmptyJournalAreEmpty() {
        Map<String, Object> facets = reader.facets();
        assertTrue(((List<?>) facets.get("actors")).isEmpty());
        assertTrue(((List<?>) facets.get("tools")).isEmpty());
        assertTrue(((List<?>) facets.get("states")).isEmpty());
        assertTrue(((List<?>) facets.get("statuses")).isEmpty());
        assertNull(facets.get("mostActive"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void mostActiveCountsTheWholeJournalAndATieGoesToTheMostRecentActor() {
        add(1, "u1", "Old Name", "a_tool", "Alpha", "COMPLETED", "CREATED");
        add(2, "u2", "bob", "a_tool", "Alpha", "COMPLETED", "CREATED");
        add(3, "u1", "alice", "a_tool", "Alpha", "COMPLETED", "CREATED");
        add(4, "u2", "bob", "a_tool", "Alpha", "COMPLETED", "CREATED");
        Map<String, Object> top = (Map<String, Object>) reader.facets().get("mostActive");
        assertEquals("u2", top.get("key"));
        assertEquals("bob", top.get("displayName"));
        assertEquals(2, top.get("count"));

        add(5, "u1", "alice", "a_tool", "Alpha", "FAILED", null);
        top = (Map<String, Object>) reader.facets().get("mostActive");
        assertEquals("u1", top.get("key"));
        assertEquals("alice", top.get("displayName"));
        assertEquals(3, top.get("count"));
    }

    private static List<Object> keys(List<Map<String, Object>> rows, String key) {
        List<Object> keys = new ArrayList<Object>();
        for (Map<String, Object> row : rows) keys.add(row.get(key));
        return keys;
    }
}
