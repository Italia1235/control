package ru.rasulov.customfields.impl.context;

import org.junit.Test;
import ru.rasulov.customfields.api.dto.context.OptionsChange;
import ru.rasulov.customfields.api.dto.context.OptionsChange.Add;
import ru.rasulov.customfields.api.dto.context.OptionsChange.Order;
import ru.rasulov.customfields.api.dto.context.OptionsChange.Rename;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsResponse.ErrorCode;
import ru.rasulov.customfields.impl.context.OptionTree.Node;

import java.util.*;

import static org.junit.Assert.*;

/** Pure rules of an options change: no Jira, no snapshots. */
public class OptionsChangeRulesTest {
    /** High, Medium, Low(disabled); EU(DE, FR) and US(disabled; NY disabled). */
    private static List<Node> current() {
        return new ArrayList<>(Arrays.asList(
                node(1, null, 0, "High", false), node(2, null, 1, "Medium", false), node(3, null, 2, "Low", true),
                node(10, null, 3, "EU", false), node(11, 10L, 0, "DE", false), node(12, 10L, 1, "FR", false),
                node(20, null, 4, "US", true), node(21, 20L, 0, "NY", true)));
    }

    private static Node node(long id, Long parent, long sequence, String value, boolean disabled) {
        return new Node(id, parent, sequence, value, disabled);
    }

    private static OptionsChange change(List<Add> add, List<Rename> rename, List<Long> disable, List<Long> enable,
                                        List<Order> order) {
        return new OptionsChange(add, rename, disable, enable, order);
    }
    private static OptionsChange adding(String... values) {
        List<Add> adds = new ArrayList<>();
        for (String value : values) adds.add(new Add(value, null));
        return change(adds, null, null, null, null);
    }
    private static List<Node> many(Long parent, int count) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) nodes.add(node(i + 1, parent, i, "r" + i, false));
        return nodes;
    }
    private static OptionsChange renaming(long id, String value) {
        return change(null, Collections.singletonList(new Rename(id, value)), null, null, null);
    }
    private static OptionsChange disabling(Long... ids) { return change(null, null, Arrays.asList(ids), null, null); }
    private static OptionsChange enabling(Long... ids) { return change(null, null, null, Arrays.asList(ids), null); }
    private static OptionsChange ordering(Long parent, Long... ids) {
        return change(null, null, null, null, Collections.singletonList(new Order(parent, Arrays.asList(ids))));
    }

    private static OptionsPlan plan(OptionsChange change) {
        return OptionsChangeRules.plan(current(), change, Collections.<Long>emptySet(), true);
    }
    private static OptionsPlan plan(OptionsChange change, Set<Long> defaults, boolean cascading) {
        return OptionsChangeRules.plan(current(), change, defaults, cascading);
    }
    private static void refused(ErrorCode code, OptionsChange change) { refused(code, change, true); }
    private static void refused(ErrorCode code, OptionsChange change, boolean cascading) {
        try {
            OptionsChangeRules.plan(current(), change, Collections.<Long>emptySet(), cascading);
            fail("expected " + code);
        } catch (OptionsChangeRules.Failure e) {
            assertEquals(e.getMessage(), code, e.code);
            assertFalse(e.getMessage().trim().isEmpty());
        }
    }

    @Test public void addAppendsAfterTheExistingOptionsOfTheLevelInAddOrderAndTrimsValues() {
        OptionsPlan plan = plan(adding("  Urgent ", "Critical"));
        assertTrue(plan.changed());
        assertEquals(2, plan.adds.size());
        assertNull(plan.adds.get(0).parentId);
        assertEquals("Urgent", plan.adds.get(0).value);
        assertEquals(5L, plan.adds.get(0).sequence);
        assertEquals("Critical", plan.adds.get(1).value);
        assertEquals(6L, plan.adds.get(1).sequence);
        assertTrue(plan.renames.isEmpty() && plan.disables.isEmpty() && plan.enables.isEmpty() && plan.sequences.isEmpty());
        assertEquals(Collections.singletonList("ADDED"), plan.rows.get(0).changes);
        assertNull(plan.rows.get(0).optionId);
        assertNull(plan.rows.get(0).before);
    }

    @Test public void addsAChildOnlyUnderATopLevelOptionOfACascadingSelect() {
        OptionsPlan plan = plan(change(Collections.singletonList(new Add("IT", 10L)), null, null, null, null));
        assertEquals(Long.valueOf(10), plan.adds.get(0).parentId);
        assertEquals(2L, plan.adds.get(0).sequence);
        refused(ErrorCode.VALIDATION_ERROR, change(Collections.singletonList(new Add("IT", 10L)), null, null, null, null), false);
        refused(ErrorCode.VALIDATION_ERROR, change(Collections.singletonList(new Add("IT", 11L)), null, null, null, null));
        refused(ErrorCode.OPTION_NOT_IN_CONTEXT, change(Collections.singletonList(new Add("IT", 999L)), null, null, null, null));
    }

    @Test public void renameChangesOnlyRealDifferencesAndAcceptsACaseChange() {
        OptionsPlan plan = plan(change(null, Arrays.asList(new Rename(1L, "HIGH"), new Rename(2L, " Medium ")), null, null, null));
        assertEquals(Collections.singletonMap(1L, "HIGH"), plan.renames);
        assertEquals(1, plan.unchanged.size());
        assertTrue(plan.unchanged.get(0), plan.unchanged.get(0).contains("без изменений"));
        assertEquals(Collections.singletonList("RENAMED"), plan.rows.get(0).changes);
        assertEquals("High", plan.rows.get(0).before.get("value"));
        assertEquals("HIGH", plan.rows.get(0).after.get("value"));
        assertEquals("High → HIGH", plan.rows.get(0).summary);
    }

    @Test public void swappingTwoValuesInOnePlanIsNotADuplicate() {
        OptionsPlan plan = plan(change(null, Arrays.asList(new Rename(1L, "Medium"), new Rename(2L, "High")), null, null, null));
        assertEquals(2, plan.renames.size());
    }

    @Test public void duplicatesAreFoundInTheFinalStateOfALevelIgnoringCaseAndIncludingDisabledOptions() {
        refused(ErrorCode.OPTION_DUPLICATE, renaming(1, "medium"));
        refused(ErrorCode.OPTION_DUPLICATE, renaming(1, "LOW"));
        refused(ErrorCode.OPTION_DUPLICATE, adding("high"));
        refused(ErrorCode.OPTION_DUPLICATE, adding("New", "NEW"));
        plan(change(Collections.singletonList(new Add("High", 10L)), null, null, null, null)); // another level
    }

    @Test public void valuesMustBeNonBlankAfterTrimmingAndAtMost255Characters() {
        char[] longValue = new char[256];
        Arrays.fill(longValue, 'x');
        for (String bad : Arrays.asList("", "   ", new String(longValue), null)) {
            refused(ErrorCode.OPTION_VALUE_INVALID, adding(bad));
            refused(ErrorCode.OPTION_VALUE_INVALID, renaming(1, bad));
        }
        char[] exact = new char[255];
        Arrays.fill(exact, 'x');
        assertTrue(plan(adding(new String(exact))).changed());
    }

    @Test public void anEmptyOrMalformedBlockIsAValidationError() {
        refused(ErrorCode.VALIDATION_ERROR, change(null, null, null, null, null));
        refused(ErrorCode.VALIDATION_ERROR, change(Collections.singletonList((Add) null), null, null, null, null));
        refused(ErrorCode.VALIDATION_ERROR, change(null, null, Collections.singletonList((Long) null), null, null));
        refused(ErrorCode.VALIDATION_ERROR, change(null, Collections.singletonList(new Rename(null, "x")), null, null, null));
    }

    @Test public void unknownOrForeignOptionIdsAreRefused() {
        refused(ErrorCode.OPTION_NOT_IN_CONTEXT, renaming(999, "x"));
        refused(ErrorCode.OPTION_NOT_IN_CONTEXT, disabling(999L));
        refused(ErrorCode.OPTION_NOT_IN_CONTEXT, enabling(999L));
        refused(ErrorCode.OPTION_NOT_IN_CONTEXT, ordering(null, 1L, 2L, 3L, 10L, 20L, 999L));
        refused(ErrorCode.OPTION_NOT_IN_CONTEXT, ordering(999L, 1L));
    }

    @Test public void conflictsAreAnOptionInDisableAndEnableOrARepeatedIdInOneList() {
        refused(ErrorCode.OPTION_CONFLICT, change(null, null, Collections.singletonList(1L), Collections.singletonList(1L), null));
        refused(ErrorCode.OPTION_CONFLICT, change(null, Arrays.asList(new Rename(1L, "a"), new Rename(1L, "b")), null, null, null));
        refused(ErrorCode.OPTION_CONFLICT, disabling(1L, 1L));
        refused(ErrorCode.OPTION_CONFLICT, enabling(3L, 3L));
        refused(ErrorCode.OPTION_CONFLICT, ordering(null, 1L, 1L, 2L, 3L, 10L, 20L));
    }

    @Test public void orderListsEveryExistingOptionOfOneLevelIncludingDisabledOnes() {
        OptionsPlan plan = plan(ordering(null, 20L, 3L, 1L, 10L, 2L));
        // stored sequences are 1:0, 2:1, 3:2, 10:3, 20:4; new indexes 20:0, 3:1, 1:2, 10:3, 2:4. Option 10 stays put.
        Map<Long, Long> expected = new LinkedHashMap<>();
        expected.put(20L, 0L); expected.put(3L, 1L); expected.put(1L, 2L); expected.put(2L, 4L);
        assertEquals(expected, plan.sequences);
        assertTrue(plan.changed());
        boolean moved = false;
        for (OptionsPlan.Row row : plan.rows) if (row.changes.contains("MOVED")) moved = true;
        assertTrue(moved);
        refused(ErrorCode.OPTION_ORDER_INVALID, ordering(null, 1L, 2L, 3L, 10L));        // incomplete
        refused(ErrorCode.OPTION_ORDER_INVALID, ordering(null, 1L, 2L, 3L, 10L, 20L, 11L)); // option of another level
        refused(ErrorCode.OPTION_ORDER_INVALID, ordering(10L, 11L));                      // incomplete child level
        refused(ErrorCode.OPTION_ORDER_INVALID, ordering(10L, 11L, 12L, 21L));            // child of another parent
        refused(ErrorCode.OPTION_ORDER_INVALID, ordering(10L, 1L, 2L));                   // top-level ids under a parent
        refused(ErrorCode.OPTION_ORDER_INVALID, change(null, null, null, null, Arrays.asList(
                new Order(10L, Arrays.asList(11L, 12L)), new Order(10L, Arrays.asList(12L, 11L)))));      // level listed twice
    }

    @Test public void anOrderEqualToTheCurrentOneIsNoChange() {
        OptionsPlan plan = plan(ordering(null, 1L, 2L, 3L, 10L, 20L));
        assertFalse(plan.changed());
        assertTrue(plan.sequences.isEmpty());
        assertFalse(plan.unchanged.isEmpty());
        assertTrue(plan.unchanged.get(0).contains("без изменений"));
    }

    @Test public void newOptionsFollowTheOrderedExistingOnesOfTheirLevel() {
        OptionsPlan plan = plan(change(Collections.singletonList(new Add("Urgent", null)), null, null, null,
                Collections.singletonList(new Order(null, Arrays.asList(3L, 1L, 2L, 10L, 20L)))));
        assertEquals(5L, plan.adds.get(0).sequence);
        assertEquals(Long.valueOf(0), plan.sequences.get(3L));
        assertEquals(Long.valueOf(1), plan.sequences.get(1L));
        assertEquals(Long.valueOf(2), plan.sequences.get(2L));
        assertFalse(plan.sequences.containsKey(10L));
        assertFalse(plan.sequences.containsKey(20L));
    }

    @Test public void renameAndDisableOfOneOptionAreBothApplied() {
        OptionsPlan plan = plan(change(null, Collections.singletonList(new Rename(1L, "Urgent")), Collections.singletonList(1L),
                null, null));
        assertEquals("Urgent", plan.renames.get(1L));
        assertEquals(Collections.singletonList(1L), plan.disables);
        assertEquals(1, plan.rows.size());
        assertEquals(Arrays.asList("RENAMED", "DISABLED"), plan.rows.get(0).changes);
        assertEquals("High → Urgent, отключена", plan.rows.get(0).summary);
    }

    @Test public void renameAndEnableOfOneOptionAreBothApplied() {
        OptionsPlan plan = plan(change(null, Collections.singletonList(new Rename(3L, "Lowest")), null,
                Collections.singletonList(3L), null));
        assertEquals("Lowest", plan.renames.get(3L));
        assertEquals(Collections.singletonList(3L), plan.enables);
    }

    @Test public void noOpOperationsAreSkippedAndAWholeNoOpRequestIsReportedAsUnchanged() {
        OptionsPlan nothing = plan(change(null, Collections.singletonList(new Rename(1L, "High")),
                Collections.singletonList(3L), Collections.singletonList(2L), null));
        assertFalse(nothing.changed());
        assertEquals(3, nothing.unchanged.size());
        for (String line : nothing.unchanged) assertTrue(line, line.contains("без изменений"));
        assertTrue(nothing.rows.isEmpty());
        OptionsPlan mixed = plan(change(null, Collections.singletonList(new Rename(1L, "Urgent")),
                Collections.singletonList(3L), null, null));
        assertTrue(mixed.changed());
        assertEquals(Collections.singletonMap(1L, "Urgent"), mixed.renames);
        assertTrue(mixed.disables.isEmpty());
        assertEquals(1, mixed.unchanged.size());
    }

    @Test public void aParentCanBeDisabledOnlyTogetherWithItsEnabledChildren() {
        try {
            plan(disabling(10L));
            fail();
        } catch (OptionsChangeRules.Failure e) {
            assertEquals(ErrorCode.OPTION_PARENT_HAS_ENABLED_CHILDREN, e.code);
            assertTrue(e.getMessage(), e.getMessage().contains("DE") && e.getMessage().contains("FR"));
            assertTrue(e.getMessage(), e.getMessage().contains("11") && e.getMessage().contains("12"));
        }
        refused(ErrorCode.OPTION_PARENT_HAS_ENABLED_CHILDREN, disabling(10L, 11L));
        OptionsPlan together = plan(disabling(10L, 11L, 12L));
        assertEquals(Arrays.asList(10L, 11L, 12L), new ArrayList<>(new TreeSet<>(together.disables)));
        // children already disabled: the parent alone is fine (NY is disabled; US is already disabled too -> no-op)
        assertFalse(plan(disabling(20L)).changed());
    }

    @Test public void aChildCannotBeAddedOrEnabledUnderADisabledParent() {
        refused(ErrorCode.OPTION_PARENT_DISABLED, change(Collections.singletonList(new Add("LA", 20L)), null, null, null, null));
        refused(ErrorCode.OPTION_PARENT_DISABLED, enabling(21L));
        refused(ErrorCode.OPTION_PARENT_DISABLED, change(Collections.singletonList(new Add("IT", 10L)), null,
                Arrays.asList(10L, 11L, 12L), null, null));
        OptionsPlan both = plan(enabling(20L, 21L));
        assertEquals(2, both.enables.size());
    }

    @Test public void disablingTheDefaultIsAllowedAndFlaggedWithoutChangingIt() {
        OptionsPlan plan = plan(disabling(1L), Collections.singleton(1L), true);
        assertTrue(plan.rows.get(0).defaultValue);
        boolean flagged = false;
        for (Map<String, Object> warning : plan.warnings) flagged |= "DEFAULT_OPTION_DISABLED".equals(warning.get("code"));
        assertTrue(plan.warnings.toString(), flagged);
        assertFalse(plan(disabling(2L), Collections.singleton(1L), true).rows.get(0).defaultValue);
    }

    @Test public void renameAndDisableCarryTheirConsequencesAsWarnings() {
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> warning : plan(change(null, Collections.singletonList(new Rename(1L, "Urgent")),
                Collections.singletonList(2L), null, null)).warnings) {
            codes.add((String) warning.get("code"));
            assertFalse(String.valueOf(warning.get("message")).isEmpty());
        }
        assertTrue(codes.toString(), codes.contains("RENAME_CHANGES_ALL_ISSUES") && codes.contains("DISABLE_KEEPS_ISSUE_VALUES"));
        assertTrue(plan(adding("Urgent")).warnings.isEmpty());
    }

    @Test public void limitsApplyToTheFinalStateOfEachLevelAndOfTheContext() {
        OptionsPlan roomForOne = OptionsChangeRules.plan(many(null, 99), adding("one more"), Collections.<Long>emptySet(), true);
        assertEquals(99L, roomForOne.adds.get(0).sequence);
        try {
            OptionsChangeRules.plan(many(null, 100), adding("one too many"), Collections.<Long>emptySet(), true);
            fail();
        } catch (OptionsChangeRules.Failure e) { assertEquals(ErrorCode.OPTIONS_LIMIT, e.code); }
        List<Node> children = many(null, 1);
        for (int i = 0; i < 100; i++) children.add(node(1000 + i, 1L, i, "c" + i, false));
        try {
            OptionsChangeRules.plan(children, change(Collections.singletonList(new Add("c", 1L)), null, null, null, null),
                    Collections.<Long>emptySet(), true);
            fail();
        } catch (OptionsChangeRules.Failure e) { assertEquals(ErrorCode.OPTIONS_LIMIT, e.code); }
        List<Node> total = new ArrayList<>();
        int id = 1;
        for (int parent = 0; parent < 5; parent++) {
            long parentId = id++;
            total.add(node(parentId, null, parent, "p" + parent, false));
            for (int child = 0; child < 99; child++) total.add(node(id++, parentId, child, "c" + child, false));
        }
        // 5 parents + 495 children = 500: one more child would reach 501
        try {
            OptionsChangeRules.plan(total, change(Collections.singletonList(new Add("x", 1L)), null, null, null, null),
                    Collections.<Long>emptySet(), true);
            fail();
        } catch (OptionsChangeRules.Failure e) { assertEquals(ErrorCode.OPTIONS_LIMIT, e.code); }
    }

    private static OptionsPlan planOver(List<Node> nodes, OptionsChange change) {
        return OptionsChangeRules.plan(nodes, change, Collections.<Long>emptySet(), true);
    }
    private static void limitRefused(List<Node> nodes, OptionsChange change) {
        try {
            planOver(nodes, change);
            fail("expected OPTIONS_LIMIT");
        } catch (OptionsChangeRules.Failure e) { assertEquals(e.getMessage(), ErrorCode.OPTIONS_LIMIT, e.code); }
    }

    @Test public void aContextAlreadyOverTheLimitAllowsDisableRenameEnableAndOrderWithoutAdd() {
        List<Node> over = many(null, 150);
        over.set(149, node(150, null, 149, "r149", true));
        assertEquals(Collections.singletonList(1L), planOver(over, disabling(1L)).disables);
        assertEquals(Collections.singletonMap(2L, "renamed"), planOver(over, renaming(2, "renamed")).renames);
        assertEquals(Collections.singletonList(150L), planOver(over, enabling(150L)).enables);
        List<Long> reversed = new ArrayList<>();
        for (long id = 150; id >= 1; id--) reversed.add(id);
        OptionsPlan ordered = planOver(over, change(null, null, null, null,
                Collections.singletonList(new Order(null, reversed))));
        assertEquals(149L, ordered.sequences.get(1L).longValue());
        assertTrue(ordered.adds.isEmpty());
    }

    @Test public void anAddInAContextAlreadyOverTheLimitIsRefused() {
        limitRefused(many(null, 150), adding("one more"));
        // the add also names a rename: the context is still judged because of the add
        limitRefused(many(null, 150), change(Collections.singletonList(new Add("one more", null)),
                Collections.singletonList(new Rename(1L, "renamed")), null, null, null));
    }

    @Test public void anAddJudgesOnlyTheLevelsItTouches() {
        // 150 top-level options are over the root limit, but the add goes under a parent: the root level is not judged
        List<Node> roots = many(null, 150);
        roots.add(node(1000, null, 150, "p", false));
        OptionsPlan child = planOver(roots, change(Collections.singletonList(new Add("c", 1000L)), null, null, null, null));
        assertEquals(1, child.adds.size());
        assertEquals(Long.valueOf(1000), child.adds.get(0).parentId);

        // parent 1 holds 101 children (over the child limit); an add under parent 2 does not judge parent 1
        List<Node> cascade = new ArrayList<>(Arrays.asList(node(1, null, 0, "p1", false), node(2, null, 1, "p2", false)));
        for (int i = 0; i < 101; i++) cascade.add(node(100 + i, 1L, i, "c" + i, false));
        OptionsPlan other = planOver(cascade, change(Collections.singletonList(new Add("n", 2L)), null, null, null, null));
        assertEquals(Long.valueOf(2), other.adds.get(0).parentId);
        // an add under parent 1 (over the child limit) is refused
        limitRefused(cascade, change(Collections.singletonList(new Add("n", 1L)), null, null, null, null));
        // disabling a child of an over-limit level needs no room
        assertTrue(planOver(cascade, disabling(100L)).changed());
    }

    @Test public void aRootAddDoesNotJudgeTheChildLevelsButTheContextSizeStillApplies() {
        // 5 parents with 110 children each: 555 options, every parent over the child limit
        List<Node> big = new ArrayList<>();
        int id = 1;
        for (int parent = 0; parent < 5; parent++) {
            long parentId = id++;
            big.add(node(parentId, null, parent, "p" + parent, false));
            for (int child = 0; child < 110; child++) big.add(node(id++, parentId, child, "c" + child, false));
        }
        limitRefused(big, adding("root"));
        // without an add the same context is fully editable
        assertTrue(planOver(big, renaming(1, "renamed")).changed());
        // an over-limit child level is not judged by a root add that fits the context
        List<Node> fits = new ArrayList<>();
        fits.add(node(1, null, 0, "p", false));
        for (int child = 0; child < 101; child++) fits.add(node(10 + child, 1L, child, "c" + child, false));
        assertEquals(1, planOver(fits, adding("root")).adds.size());
    }

    @Test public void touchedCountsEveryNamedOperationForThePerCallLimit() {
        OptionsPlan plan = plan(change(Arrays.asList(new Add("a", null), new Add("b", null)),
                Collections.singletonList(new Rename(1L, "Urgent")), Collections.singletonList(2L), null,
                Collections.singletonList(new Order(10L, Arrays.asList(12L, 11L)))));
        assertEquals(2 + 1 + 1 + 0 + 2, plan.touched);
    }

    @Test public void usageIsWantedForRenamedAndDisabledOptionsOnlyAndNamesTheParentOfAChild() {
        OptionsPlan plan = plan(change(Collections.singletonList(new Add("new", null)),
                Arrays.asList(new Rename(1L, "Urgent"), new Rename(11L, "Germany")), Collections.singletonList(2L),
                Collections.singletonList(3L), null));
        Map<Long, Long> expected = new LinkedHashMap<>();
        expected.put(1L, null); expected.put(11L, 10L); expected.put(2L, null);
        assertEquals(expected, plan.usageTargets());
    }

    // --- verification of the persisted state -------------------------------------------------------------------

    private static List<List<Object>> rows(Object[]... rows) {
        List<List<Object>> result = new ArrayList<>();
        for (Object[] row : rows) result.add(Arrays.asList(row));
        return result;
    }
    private static Object[] row(long id, Long parent, long sequence, String value, String disabled) {
        return new Object[] {id, parent, sequence, value, disabled};
    }
    private static List<List<Object>> persisted(List<Node> nodes) {
        List<List<Object>> result = new ArrayList<>();
        for (Node n : nodes) result.add(Arrays.<Object>asList(n.id, n.parentId, n.sequence, n.value, n.disabled ? "true" : "false"));
        return result;
    }

    @Test public void theExpectedFinalStateMatchesItsOwnRowsAndNothingElse() {
        OptionsChange change = change(Collections.singletonList(new Add("Urgent", null)),
                Collections.singletonList(new Rename(2L, "Normal")), Collections.singletonList(1L),
                Collections.singletonList(3L),
                Collections.singletonList(new Order(null, Arrays.asList(3L, 2L, 1L, 10L, 20L))));
        OptionsPlan plan = plan(change);
        // approved result: order Low, Normal, High, EU, US, then the new one; High disabled, Low enabled
        List<List<Object>> good = persisted(current());
        good.set(0, Arrays.<Object>asList(1L, null, 2L, "High", "true"));
        good.set(1, Arrays.<Object>asList(2L, null, 1L, "Normal", "false"));
        good.set(2, Arrays.<Object>asList(3L, null, 0L, "Low", "false"));
        good.add(Arrays.<Object>asList(99L, null, 5L, "Urgent", "false"));
        assertNull(plan.mismatch(good));

        List<List<Object>> wrongValue = new ArrayList<>(good);
        wrongValue.set(1, Arrays.<Object>asList(2L, null, 1L, "Medium", "false"));
        assertNotNull(plan.mismatch(wrongValue));
        List<List<Object>> notDisabled = new ArrayList<>(good);
        notDisabled.set(0, Arrays.<Object>asList(1L, null, 2L, "High", "false"));
        assertNotNull(plan.mismatch(notDisabled));
        List<List<Object>> wrongOrder = new ArrayList<>(good);
        wrongOrder.set(1, Arrays.<Object>asList(2L, null, 0L, "Normal", "false"));
        wrongOrder.set(2, Arrays.<Object>asList(3L, null, 1L, "Low", "false"));
        assertNotNull(plan.mismatch(wrongOrder));
        List<List<Object>> newNotLast = new ArrayList<>(good);
        newNotLast.set(newNotLast.size() - 1, Arrays.<Object>asList(99L, null, 0L, "Urgent", "false"));
        assertNotNull(plan.mismatch(newNotLast));
        List<List<Object>> missingNew = new ArrayList<>(good);
        missingNew.remove(missingNew.size() - 1);
        assertNotNull(plan.mismatch(missingNew));
        List<List<Object>> extra = new ArrayList<>(good);
        extra.add(Arrays.<Object>asList(100L, null, 6L, "Surprise", "false"));
        assertNotNull(plan.mismatch(extra));
        List<List<Object>> removed = new ArrayList<>(good);
        removed.remove(3);
        assertNotNull(plan.mismatch(removed));
        List<List<Object>> reparented = new ArrayList<>(good);
        reparented.set(4, Arrays.<Object>asList(11L, 20L, 0L, "DE", "false"));
        assertNotNull(plan.mismatch(reparented));
    }

    @Test public void optionsOutsideTheRequestMustKeepTheirStoredSequenceAndState() {
        OptionsPlan plan = plan(renaming(1, "Urgent"));
        List<List<Object>> good = persisted(current());
        good.set(0, Arrays.<Object>asList(1L, null, 0L, "Urgent", "false"));
        assertNull(plan.mismatch(good));
        List<List<Object>> moved = new ArrayList<>(good);
        moved.set(3, Arrays.<Object>asList(10L, null, 9L, "EU", "false"));
        assertNotNull(plan.mismatch(moved));
        List<List<Object>> flipped = new ArrayList<>(good);
        flipped.set(4, Arrays.<Object>asList(11L, 10L, 0L, "DE", "true"));
        assertNotNull(plan.mismatch(flipped));
    }
}
