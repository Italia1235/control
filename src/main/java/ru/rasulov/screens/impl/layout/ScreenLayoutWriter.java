package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.impl.screen.ScreenLayout;
import ru.rasulov.common.impl.screen.ScreenTabPositions;
import javax.inject.Inject;
import ru.rasulov.screens.api.dto.layout.LayoutStatus;
import javax.inject.Named;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.TreeMap;

/** Native screen writes followed by independent observation. No retries or rollback on removal. */
@Named
public class ScreenLayoutWriter {
    private static final Logger LOG = LoggerFactory.getLogger(ScreenLayoutWriter.class);
    private final FieldScreenManager screens;
    @Inject public ScreenLayoutWriter(@ComponentImport FieldScreenManager screens) { this.screens = screens; }

    /** Internal result only: records must never be sent through Jackson 1. */
    public record Result(ScreenLayout actual, Set<String> attempted, boolean mismatch, String message) {
        public Result { attempted = Set.copyOf(attempted); }
        public boolean succeeded() { return message == null; }
    }

    public Result remove(ScreenLayout before, List<String> fields) {
        Set<String> attempted = new LinkedHashSet<>();
        ScreenLayout expected = before;
        for (String field : fields) expected = expected.remove(field);
        String failure = null;
        try {
            screens.refresh();
            FieldScreen screen = screens.getFieldScreen(before.id());
            if (screen == null || !before.fingerprint().equals(ScreenLayout.of(screen).fingerprint()))
                throw new IllegalStateException("Screen changed after the checked preview; no removal was started.");
            for (String field : fields) {
                FieldScreenTab tab = null;
                int index = -1;
                for (FieldScreenTab candidate : screen.getTabs()) {
                    List<FieldScreenLayoutItem> items = candidate.getFieldScreenLayoutItems();
                    for (int i = 0; i < items.size(); i++) {
                        if (field.equals(items.get(i).getFieldId())) { tab = candidate; index = i; break; }
                    }
                    if (tab != null) break;
                }
                if (tab == null) throw new IllegalStateException("Field disappeared before its removal: " + field);
                FieldScreenLayoutItem current = tab.getFieldScreenLayoutItem(index);
                if (current == null || !field.equals(current.getFieldId()))
                    throw new IllegalStateException("Screen tab changed before removal of " + field + "; no removal was started for this field.");
                attempted.add(field);
                // Jira 10.3.24 bytecode: LayoutItems.remove uses List.remove(index), then resequence(); never pass raw position.
                // SPIKE §9 п.2: remove calls item.remove() and tab.store(); live persistence/readback remains unverified.
                tab.removeFieldScreenLayoutItem(index);
            }
        } catch (RuntimeException e) {
            LOG.error("Screen field removal failed for screen {}", before.id(), e);
            failure = "Jira removal stopped; no retry or rollback was performed. " + e.getMessage();
        }
        try {
            // SPIKE §9 п.6: refresh before local readback is assumed sufficient; second-node DC cache propagation is unverified.
            screens.refresh();
            FieldScreen persisted = screens.getFieldScreen(before.id());
            if (persisted == null) throw new IllegalStateException("Screen is no longer available.");
            ScreenLayout actual = ScreenLayout.of(persisted);
            if (failure != null) return new Result(actual, attempted, false, failure);
            if (!expected.matches(actual)) return new Result(actual, attempted, true,
                    "Persisted screen differs from the approved removal. Actual state was read; no retry or rollback was performed.");
            return new Result(actual, attempted, false, null);
        } catch (RuntimeException e) {
            LOG.error("Cannot read screen {} after field removal", before.id(), e);
            return new Result(null, attempted, false,
                    (failure == null ? "Removal was attempted. " : failure + " ")
                            + "Actual screen state could not be read; it is UNKNOWN. " + e.getMessage());
        }
    }

    /** Internal only; actual is independently read even when a native call throws after persistence. */
    public record MoveWriteResult(LayoutStatus status, ScreenLayout actual, String message,
            Set<String> attemptedFields, String restoredField) {
        public MoveWriteResult { attemptedFields = Set.copyOf(attemptedFields); }
        public boolean attempted() { return !attemptedFields.isEmpty(); }
    }

    public MoveWriteResult edit(ScreenLayout before, ScreenLayout membership, ScreenLayout after) {
        Set<String> attempted = new LinkedHashSet<>();
        String failure = null;
        ScreenLayout restoration = null;
        String restoredField = null;
        try {
            screens.refresh();
            FieldScreen screen = screens.getFieldScreen(before.id());
            if (screen == null || !before.fingerprint().equals(ScreenLayout.of(screen).fingerprint()))
                throw new IllegalStateException("Screen changed after preview; no move was started.");
            // Check every affected list index before the first write, including destination tabs.
            for (ScreenLayout.Tab tab : before.tabs()) if (!sameOrder(tab, after.tab(tab.id())))
                checkedItems(nativeTab(screen, tab.id()), tab);
            ScreenLayout working = before;
            // Membership changes are completed before transfers and final per-tab ordering.
            for (ScreenLayout.Tab original : before.tabs()) for (ScreenLayout.Item item : original.items()) {
                if (membership.item(item.fieldId()) != null) continue;
                ScreenLayout.Tab source = working.containing(item.fieldId());
                FieldScreenTab tab = nativeTab(screen, source.id());
                checkedItems(tab, source);
                int sourceIndex = index(source, item.fieldId());
                checkedItem(tab, sourceIndex, item.fieldId());
                attempted.add(item.fieldId());
                tab.removeFieldScreenLayoutItem(sourceIndex);
                working = working.remove(item.fieldId());
            }
            for (ScreenLayout.Tab target : membership.tabs()) for (ScreenLayout.Item item : target.items()) {
                if (before.item(item.fieldId()) != null) continue;
                FieldScreenTab tab = nativeTab(screen, target.id());
                ScreenLayout.Tab current = working.tab(target.id());
                checkedItems(tab, current);
                attempted.add(item.fieldId());
                if (ScreenTabPositions.normalize(tab)) checkedItems(tab, current);
                int targetIndex = Math.min(index(target, item.fieldId()), current.items().size());
                tab.addFieldScreenLayoutItem(item.fieldId(), targetIndex);
                FieldScreenLayoutItem added = checkedItem(tab, targetIndex, item.fieldId());
                added.setShowWhenEmpty(item.shownWhenEmpty());
                added.store();
                working = working.insert(target.id(), item.fieldId(), targetIndex, item.shownWhenEmpty());
            }
            for (ScreenLayout.Tab original : before.tabs()) for (ScreenLayout.Item item : original.items()) {
                ScreenLayout.Tab target = after.containing(item.fieldId());
                if (target == null) continue; // Already removed above.
                ScreenLayout.Tab source = working.containing(item.fieldId());
                if (Objects.equals(source.id(), target.id())) continue;
                FieldScreenTab from = nativeTab(screen, source.id()), to = nativeTab(screen, target.id());
                int sourceIndex = index(source, item.fieldId());
                int targetIndex = Math.min(index(target, item.fieldId()), working.tab(target.id()).items().size());
                checkedItems(from, source);
                checkedItems(to, working.tab(target.id()));
                checkedItem(from, sourceIndex, item.fieldId());
                attempted.add(item.fieldId());
                // Jira's indexed add renumbers only the tail: renumber the target tab 0..n-1 first (order unchanged).
                // Done before the removal so a failure here cannot leave the field off the screen.
                if (ScreenTabPositions.normalize(to)) checkedItems(to, working.tab(target.id()));
                // SPIKE §9 п.2: native remove resequences and stores; live persistence remains unverified.
                from.removeFieldScreenLayoutItem(sourceIndex);
                try {
                    checkedItems(to, working.tab(target.id()));
                    // add accepts an insertion index (including size), not a raw Jira position.
                    // Keep remove -> add as required; native add itself has no screen-wide duplicate check.
                    // SPIKE §9 п.3: indexed add shifts/stores per bytecode; live persistence remains unverified.
                    to.addFieldScreenLayoutItem(item.fieldId(), targetIndex);
                    FieldScreenLayoutItem added = checkedItem(to, targetIndex, item.fieldId());
                    added.setShowWhenEmpty(item.shownWhenEmpty());
                    added.store();
                } catch (RuntimeException e) {
                    // Exactly one compensation attempt, including an add that persisted before throwing.
                    restoration = working.remove(item.fieldId()).insert(source.id(), item.fieldId(),
                            sourceIndex, item.shownWhenEmpty());
                    restoredField = item.fieldId();
                    try {
                        restore(screen, from, to, item, sourceIndex);
                    } catch (RuntimeException restoreFailure) {
                        e.addSuppressed(restoreFailure);
                    }
                    throw e;
                }
                working = working.move(item.fieldId(), target.id(), targetIndex);
            }
            for (ScreenLayout.Tab target : after.tabs()) {
                ScreenLayout.Tab current = working.tab(target.id());
                if (sameOrder(current, target)) continue;
                FieldScreenTab tab = nativeTab(screen, target.id());
                List<FieldScreenLayoutItem> items = checkedItems(tab, current);
                // Native Jira removes all values first, then inserts in Map iteration order.
                // Insertion keys are final list indices; source iteration can produce keys 1,2,0.
                TreeMap<Integer, FieldScreenLayoutItem> positions = new TreeMap<>();
                for (FieldScreenLayoutItem item : items) positions.put(index(target, item.getFieldId()), item);
                for (FieldScreenLayoutItem item : items) attempted.add(item.getFieldId());
                // SPIKE §9 п.1: bytecode calls resequence and tab.store; live persistence remains unverified.
                tab.moveFieldScreenLayoutItemToPosition(positions);
            }
        } catch (RuntimeException e) {
            LOG.error("Screen field move stopped for screen {}", before.id(), e);
            failure = "Jira move stopped. " + e.getMessage();
        }
        try {
            // SPIKE §9 п.6: local refresh/readback is assumed sufficient; second-node DC propagation is unverified.
            screens.refresh();
            FieldScreen persisted = screens.getFieldScreen(before.id());
            if (persisted == null) throw new IllegalStateException("Screen is no longer available.");
            ScreenLayout actual = ScreenLayout.of(persisted);
            if (restoration != null) return new MoveWriteResult(
                    before.matches(actual) ? LayoutStatus.RESTORED : LayoutStatus.PARTIAL, actual,
                    failure + " One restoration was attempted for the failed transfer; earlier completed moves may remain.",
                    attempted, restoration.matches(actual) ? restoredField : null);
            if (failure != null) return new MoveWriteResult(attempted.isEmpty() ? LayoutStatus.FAILED : LayoutStatus.PARTIAL,
                    actual, failure + " No further moves were attempted.", attempted, null);
            if (!after.matches(actual) || !positionsContiguous(before, after, actual)) return new MoveWriteResult(LayoutStatus.FAILED, actual,
                    "Persisted screen differs from the approved layout (order, flags or positions 0..n-1 of a changed tab). Actual state was read; no retry or rollback was performed.", attempted, null);
            return new MoveWriteResult(LayoutStatus.EDITED, actual, null, attempted, null);
        } catch (RuntimeException e) {
            LOG.error("Cannot read screen {} after field move", before.id(), e);
            return new MoveWriteResult(LayoutStatus.UNKNOWN, null,
                    (failure == null ? "" : failure + " ") + "Actual screen state could not be read; it is UNKNOWN. " + e.getMessage(), attempted, restoredField);
        }
    }

    private static void restore(FieldScreen screen, FieldScreenTab from, FieldScreenTab to,
            ScreenLayout.Item item, int sourceIndex) {
        List<FieldScreenLayoutItem> destination = to.getFieldScreenLayoutItems();
        for (int i = 0; i < destination.size(); i++) if (item.fieldId().equals(destination.get(i).getFieldId())) {
            checkedItem(to, i, item.fieldId());
            to.removeFieldScreenLayoutItem(i);
            break;
        }
        // A concurrent insertion on the source must not be duplicated by compensation.
        for (FieldScreenTab tab : screen.getTabs()) for (FieldScreenLayoutItem row : tab.getFieldScreenLayoutItems())
            if (item.fieldId().equals(row.getFieldId())) throw new IllegalStateException("Field already exists before restoration.");
        List<FieldScreenLayoutItem> source = from.getFieldScreenLayoutItems();
        if (sourceIndex > source.size()) throw new IllegalStateException("Source insertion index changed before restoration.");
        for (int i = 0; i < source.size(); i++) checkedItem(from, i, source.get(i).getFieldId());
        from.addFieldScreenLayoutItem(item.fieldId(), sourceIndex);
        FieldScreenLayoutItem restored = checkedItem(from, sourceIndex, item.fieldId());
        restored.setShowWhenEmpty(item.shownWhenEmpty());
        restored.store();
    }

    /** A changed tab must be saved with positions 0..n-1: matches() sorts by position and would hide duplicates. */
    private static boolean positionsContiguous(ScreenLayout before, ScreenLayout after, ScreenLayout actual) {
        for (ScreenLayout.Tab tab : after.tabs())
            if (!sameOrder(before.tab(tab.id()), tab) && !ScreenTabPositions.isSequential(actual.tab(tab.id()))) return false;
        return true;
    }

    private static FieldScreenTab nativeTab(FieldScreen screen, Long id) {
        return screen.getTabs().stream().filter(tab -> Objects.equals(id, tab.getId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Screen tab disappeared: " + id));
    }

    private static List<FieldScreenLayoutItem> checkedItems(FieldScreenTab tab, ScreenLayout.Tab expected) {
        List<FieldScreenLayoutItem> items = tab.getFieldScreenLayoutItems();
        if (items.size() != expected.items().size()) throw new IllegalStateException("Tab size changed before move.");
        for (int i = 0; i < items.size(); i++) {
            String field = expected.items().get(i).fieldId();
            if (!field.equals(items.get(i).getFieldId())) throw new IllegalStateException("Tab order changed before move.");
            checkedItem(tab, i, field);
        }
        return items;
    }

    private static FieldScreenLayoutItem checkedItem(FieldScreenTab tab, int index, String field) {
        FieldScreenLayoutItem item = tab.getFieldScreenLayoutItem(index);
        if (item == null || !field.equals(item.getFieldId()))
            throw new IllegalStateException("Tab index changed before move of " + field + ".");
        return item;
    }

    private static int index(ScreenLayout.Tab tab, String field) {
        for (int i = 0; i < tab.items().size(); i++) if (field.equals(tab.items().get(i).fieldId())) return i;
        throw new IllegalArgumentException("Field not on target tab: " + field);
    }

    private static boolean sameOrder(ScreenLayout.Tab left, ScreenLayout.Tab right) {
        if (left.items().size() != right.items().size()) return false;
        for (int i = 0; i < left.items().size(); i++)
            if (!left.items().get(i).fieldId().equals(right.items().get(i).fieldId())) return false;
        return true;
    }
}
