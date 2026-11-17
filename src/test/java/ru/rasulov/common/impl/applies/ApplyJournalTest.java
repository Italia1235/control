package ru.rasulov.common.impl.applies;

import net.java.ao.EntityManager;
import net.java.ao.Query;
import net.java.ao.builder.EntityManagerBuilder;
import net.java.ao.atlassian.AtlassianTableNameConverter;
import net.java.ao.schema.Case;
import net.java.ao.schema.UnderscoreFieldNameConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ApplyJournalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private EntityManager entities;
    private Logger log;
    private ApplyJournal journal;

    @Before
    public void setUp() throws Exception {
        entities = EntityManagerBuilder.url("jdbc:hsqldb:mem:applies_" + UUID.randomUUID().toString().replace("-", ""))
                .username("SA").password("").none()
                .fieldNameConverter(new UnderscoreFieldNameConverter(Case.UPPER))
                .tableNameConverter(new AtlassianTableNameConverter(name -> "AO_3E3015_" + name)).build();
        entities.migrate(ApplyRecord.class);
        log = mock(Logger.class);
        journal = new ApplyJournal(new EntityApplyDatabase(entities), log);
    }

    @After
    public void tearDown() throws Exception {
        entities.getProvider().dispose();
    }

    /** Arguments of every error(...) call, varargs flattened. */
    private List<List<Object>> errors() {
        List<List<Object>> calls = new ArrayList<List<Object>>();
        for (org.mockito.invocation.Invocation call : mockingDetails(log).getInvocations()) {
            if (call.getMethod().getName().equals("error")) calls.add(Arrays.asList(call.getArguments()));
        }
        return calls;
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static ApplyEvent event(String key, List<ApplyTarget> targets, int more, List<ApplyLink> links) {
        return new ApplyEvent(key, "APPLY", 5_000L, "alice", "Alice", "create_screen", "Create screen", "op-1",
                "COMPLETED", "CREATED", null, null, false, targets, more, links);
    }

    private ApplyRecord[] rows() throws SQLException {
        return entities.find(ApplyRecord.class, Query.select().order("ID ASC"));
    }

    @Test
    public void tableNameFitsJiraLimit() {
        String table = new AtlassianTableNameConverter(name -> "AO_3E3015_" + name).getName(ApplyRecord.class);
        assertTrue(table, table.length() <= 30);
    }

    @Test
    public void valuesAtTheLimitAreKeptAndAboveItAreCutWithAnEllipsis() throws Exception {
        ApplyEvent atLimit = new ApplyEvent(repeat('k', 300), "APPLY", 1L, repeat('a', 255), repeat('n', 255),
                repeat('t', 100), repeat('T', 255), repeat('r', 255), "COMPLETED", repeat('d', 100),
                repeat('c', 100), repeat('m', 1000), true, null, 0, null);
        journal.record(atLimit);
        ApplyEvent over = new ApplyEvent(repeat('K', 301), "APPLY", 2L, repeat('a', 256), repeat('n', 256),
                repeat('t', 101), repeat('T', 256), repeat('r', 256), "COMPLETED", repeat('d', 101),
                repeat('c', 101), repeat('m', 1001), false, null, 0, null);
        journal.record(over);

        assertEquals(Collections.emptyList(), errors());
        ApplyRecord[] rows = rows();
        assertEquals(2, rows.length);
        assertEquals(repeat('k', 300), rows[0].getEventKey());
        assertEquals(repeat('m', 1000), rows[0].getErrorMessage());
        assertEquals(repeat('K', 299) + "\u2026", rows[1].getEventKey());
        assertEquals(repeat('a', 254) + "\u2026", rows[1].getActorKey());
        assertEquals(repeat('n', 254) + "\u2026", rows[1].getActorName());
        assertEquals(repeat('t', 99) + "\u2026", rows[1].getToolName());
        assertEquals(repeat('T', 254) + "\u2026", rows[1].getToolTitle());
        assertEquals(repeat('r', 254) + "\u2026", rows[1].getOperationRef());
        assertEquals(repeat('d', 99) + "\u2026", rows[1].getDomainStatus());
        assertEquals(repeat('c', 99) + "\u2026", rows[1].getErrorCode());
        assertEquals(repeat('m', 999) + "\u2026", rows[1].getErrorMessage());
        assertEquals(Collections.emptyList(), errors());
    }

    @Test
    public void cutNeverSplitsASurrogatePair() {
        String value = repeat('x', 98) + "\uD83D\uDE00" + "tail"; // pair occupies chars 98..99
        String cut = ApplyJournal.truncate(value, 100);
        assertEquals(repeat('x', 98) + "\u2026", cut);
    }

    @Test
    public void targetsAreLimitedToTenWithMoreCountingEveryDroppedOne() throws Exception {
        List<ApplyTarget> targets = new ArrayList<ApplyTarget>();
        for (int i = 0; i < 12; i++) targets.add(new ApplyTarget("PROJECT", "P" + i, i % 2 == 0 ? String.valueOf(i) : null));
        journal.record(event("APPLY:a", targets, 3, null));
        JsonNode node = JSON.readTree(rows()[0].getTargetsJson());
        assertEquals(10, node.get("items").size());
        assertEquals(5, node.get("more").intValue());
        assertEquals("0", node.get("items").get(0).get("id").textValue());
        assertFalse(node.get("items").get(1).has("id"));
    }

    @Test
    public void emptyTargetsAreAnEmptyListAndLinksAnEmptyArray() throws Exception {
        journal.record(event("APPLY:e", null, 0, null));
        assertEquals("{\"items\":[],\"more\":0}", rows()[0].getTargetsJson());
        assertEquals("[]", rows()[0].getLinksJson());
    }

    @Test
    public void targetFieldsAreLimitedAndJsonStaysWithin8000ByDroppingFromTheEnd() throws Exception {
        List<ApplyTarget> targets = new ArrayList<ApplyTarget>();
        for (int i = 0; i < 10; i++) targets.add(new ApplyTarget(repeat('T', 60), repeat((char) ('a' + i), 300), repeat('9', 120)));
        // A control character escapes to six chars: ten 255-char labels exceed 8000 once serialized.
        List<ApplyTarget> heavy = new ArrayList<ApplyTarget>();
        for (int i = 0; i < 10; i++) heavy.add(new ApplyTarget("T", "x" + repeat('\u0001', 299), null));
        journal.record(event("APPLY:t", targets, 0, null));
        journal.record(event("APPLY:h", heavy, 0, null));
        ApplyRecord[] rows = rows();
        JsonNode first = JSON.readTree(rows[0].getTargetsJson());
        assertEquals(repeat('T', 49) + "\u2026", first.get("items").get(0).get("type").textValue());
        assertEquals(repeat('a', 254) + "\u2026", first.get("items").get(0).get("label").textValue());
        assertEquals(repeat('9', 99) + "\u2026", first.get("items").get(0).get("id").textValue());

        String json = rows[1].getTargetsJson();
        assertTrue("length " + json.length(), json.length() <= 8000);
        JsonNode node = JSON.readTree(json);
        assertTrue(node.get("items").size() < 10);
        assertEquals(10 - node.get("items").size(), node.get("more").intValue());
        String label = node.get("items").get(0).get("label").textValue();
        assertEquals(255, label.length());
        assertTrue(label.startsWith("x") && label.endsWith("\u2026"));
    }

    @Test
    public void linksOverTheUrlLimitAreDroppedWholeAndTheRestIsCapped() throws Exception {
        List<ApplyLink> links = new ArrayList<ApplyLink>();
        links.add(new ApplyLink("long", "http://x/" + repeat('u', 1992)));      // 2001 chars: dropped
        links.add(new ApplyLink(repeat('n', 300), "http://x/" + repeat('u', 1991))); // 2000 chars: kept
        links.add(new ApplyLink(null, "http://x/no-name"));
        for (int i = 0; i < 12; i++) links.add(new ApplyLink("l" + i, "http://x/" + i));
        journal.record(event("APPLY:l", null, 0, links));
        String stored = rows()[0].getLinksJson();
        assertTrue("length " + stored.length(), stored.length() <= 8000);
        JsonNode node = JSON.readTree(stored);
        assertTrue(node.size() >= 1 && node.size() <= 10);
        assertEquals(repeat('n', 254) + "\u2026", node.get(0).get("name").textValue());
        assertEquals(2000, node.get(0).get("url").textValue().length());
        for (JsonNode link : node) assertTrue(link.get("url").textValue().length() <= 2000);
    }

    @Test
    public void tenShortLinksAreKeptTogether() throws Exception {
        List<ApplyLink> links = new ArrayList<ApplyLink>();
        for (int i = 0; i < 12; i++) links.add(new ApplyLink("l" + i, "http://x/" + i));
        journal.record(event("APPLY:s", null, 0, links));
        JsonNode node = JSON.readTree(rows()[0].getLinksJson());
        assertEquals(10, node.size());
        assertEquals("http://x/9", node.get(9).get("url").textValue());
    }

    @Test
    public void optionalValuesAreNullNotInvented() throws Exception {
        journal.record(new ApplyEvent("APPLY:n", "APPLY", 1L, "alice", "Alice", "t", "T", null, "FAILED", "  ", null, "",
                false, null, 0, null));
        ApplyRecord row = rows()[0];
        assertNull(row.getOperationRef());
        assertNull(row.getDomainStatus());
        assertNull(row.getErrorCode());
        assertNull(row.getErrorMessage());
    }

    @Test
    public void repeatedKeyLeavesOneUnchangedRowAndLogsNoError() throws Exception {
        journal.record(event("APPLY:dup", null, 0, null));
        journal.record(new ApplyEvent("APPLY:dup", "APPLY", 9_000L, "bob", "Bob", "other", "Other", null, "FAILED",
                "FAILED", "X", "boom", true, null, 0, null));
        ApplyRecord[] rows = rows();
        assertEquals(1, rows.length);
        assertEquals("alice", rows[0].getActorKey());
        assertEquals("COMPLETED", rows[0].getOperationState());
        assertEquals(Collections.emptyList(), errors());
    }

    @Test
    public void racingInsertsOfOneKeyGiveOneRowAndNoError() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<Future<?>>();
        for (int i = 0; i < threads; i++) {
            done.add(pool.submit(() -> {
                go.await();
                journal.record(event("APPLY:race", null, 0, null));
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : done) f.get();
        pool.shutdown();
        assertEquals(1, rows().length);
        assertEquals(Collections.emptyList(), errors());
    }

    @Test
    public void storageFailureIsLoggedWithTheEventFieldsAndDoesNotEscape() {
        ApplyDatabase broken = new FailingStore() {
            public void insert(Map<String, Object> columns) { throw new IllegalStateException("db down"); }
            public boolean exists(String key) { return false; }
        };
        ApplyJournal failing = new ApplyJournal(broken, log);
        failing.record(new ApplyEvent("APPLY:f", "APPLY", 1L, "alice", "Alice", "create_screen", "T", null, "FAILED",
                "BLOCKED", null, null, false, null, 0, null));
        assertEquals(1, errors().size());
        List<Object> values = errors().get(0).subList(1, errors().get(0).size());
        assertEquals(Arrays.<Object>asList("APPLY:f", "APPLY", "alice", "create_screen", "FAILED", "BLOCKED"),
                values.subList(0, 6));
        assertTrue(values.get(6) instanceof IllegalStateException);
    }

    @Test
    public void failureOfTheDuplicateCheckIsAlsoSwallowedAndLogged() {
        ApplyDatabase broken = new FailingStore() {
            public void insert(Map<String, Object> columns) { throw new IllegalStateException("insert"); }
            public boolean exists(String key) { throw new IllegalStateException("exists"); }
        };
        new ApplyJournal(broken, log).record(event("APPLY:g", null, 0, null));
        assertEquals(1, errors().size());
    }

    @Test
    public void unrecognisedOrMissingRequiredValuesWriteNoRowAndLog() throws Exception {
        journal.record(new ApplyEvent("APPLY:x", "APPLY", 1L, "alice", "Alice", "t", "T", null, "RUNNING", null, null,
                null, false, null, 0, null));
        journal.record(new ApplyEvent("APPLY:y", "APPLY", 1L, "alice", null, "t", "T", null, "COMPLETED", null, null,
                null, false, null, 0, null));
        journal.record(null);
        assertEquals(0, rows().length);
        assertEquals(3, errors().size());
    }

    @Test
    public void virtualMachineErrorsAreNotSwallowed() {
        ApplyDatabase oom = new FailingStore() {
            public void insert(Map<String, Object> columns) { throw new OutOfMemoryError("test"); }
            public boolean exists(String key) { return false; }
        };
        try {
            new ApplyJournal(oom, log).record(event("APPLY:o", null, 0, null));
            fail();
        } catch (OutOfMemoryError expected) {
            assertEquals("test", expected.getMessage());
        }
    }

    @Test
    public void statesAfterCompensationAreKeptAsTheOperationJournalReportsThem() throws Exception {
        int n = 0;
        for (String state : new String[] {"COMPLETED", "FAILED", "CANCELLED", "ROLLED_BACK", "PARTIAL", "BLOCKED"}) {
            journal.record(new ApplyEvent("APPLY:s" + n++, "APPLY", 1L, "alice", "Alice", "t", "T", null, state, null, null,
                    null, false, null, 0, null));
        }
        ApplyRecord[] rows = rows();
        assertEquals(6, rows.length);
        assertEquals("ROLLED_BACK", rows[3].getOperationState());
        assertEquals("BLOCKED", rows[5].getOperationState());
    }
}
