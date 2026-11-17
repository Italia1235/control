package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.fields.Field;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.ScreenLayoutLimits;
import ru.rasulov.common.api.dto.screen.FieldPlace;
import ru.rasulov.common.impl.screen.ScreenLayout;
import ru.rasulov.screens.api.dto.layout.*;
import ru.rasulov.screens.api.dto.layout.EditScreenLayoutResponse.MoveResult;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/** Pure sequential layout planning; field metadata is used only to label the returned order. */
@Named
public class ScreenLayoutPlanner {
    private static final int FULL_DIFF_LIMIT = 50;
    private static final int WINDOW_RADIUS = 5;
    private final FieldManager fields;

    @Inject
    public ScreenLayoutPlanner(@ComponentImport FieldManager fields) { this.fields = fields; }

    public record Plan(ScreenLayout after, List<MoveResult> moves, List<TabLayoutDiff> tabs,
                       List<LayoutWarning> warnings, LayoutErrorCode errorCode, String message,
                       Set<Long> affected) {
        public Plan {
            moves = List.copyOf(moves);
            tabs = List.copyOf(tabs);
            warnings = List.copyOf(warnings);
            affected = Collections.unmodifiableSet(new LinkedHashSet<>(affected));
        }
    }

    public Plan plan(ScreenLayout before, List<ScreenFieldMove> moves) {
        return plan(before, moves, true);
    }

    /** Compose one move into a combined plan without resolving an intermediate display diff. */
    public Plan step(ScreenLayout before, ScreenFieldMove move) {
        return plan(before, Collections.singletonList(move), false);
    }

    private Plan plan(ScreenLayout before, List<ScreenFieldMove> moves, boolean includeDiff) {
        if (before == null || moves == null || moves.isEmpty()
                || moves.size() > ScreenLayoutLimits.MAX_FIELDS_PER_CALL) {
            String message = before == null ? "A screen layout is required."
                    : "moves must contain 1.." + ScreenLayoutLimits.MAX_FIELDS_PER_CALL + " entries.";
            List<MoveResult> rejected = new ArrayList<>();
            if (moves != null) for (int i = 0; i < moves.size(); i++) {
                rejected.add(new MoveResult(i, moves.get(i) == null ? null : normalize(moves.get(i).getFieldId()),
                        LayoutStatus.FAILED, LayoutErrorCode.VALIDATION_ERROR, message, List.of()));
            }
            return new Plan(null, rejected, List.of(), List.of(), LayoutErrorCode.VALIDATION_ERROR, message, Set.of());
        }
        ScreenLayout current = before;
        List<MoveResult> results = new ArrayList<>();
        List<LayoutWarning> warnings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<Long> affected = new LinkedHashSet<>();
        for (int i = 0; i < moves.size(); i++) {
            ScreenFieldMove move = moves.get(i);
            String fieldId = move == null ? null : normalize(move.getFieldId());
            List<LayoutWarning> stepWarnings = new ArrayList<>();
            try {
                validate(move, fieldId);
                if (!seen.add(fieldId)) throw failure(LayoutErrorCode.VALIDATION_ERROR,
                        "Field '" + fieldId + "' is listed more than once in moves.");
                ScreenLayout.Tab source = current.containing(fieldId);
                if (source == null) throw failure(LayoutErrorCode.FIELD_NOT_ON_SCREEN,
                        "Field '" + fieldId + "' is not on this screen. Use field IDs, not field names.");
                ScreenLayout.Tab target = target(current, source, move.getTab());
                int sourceIndex = index(source, fieldId);
                int destination = destination(source, target, sourceIndex, fieldId, move, stepWarnings);
                affected.add(source.id());
                affected.add(target.id());
                boolean unchanged = Objects.equals(source.id(), target.id()) && sourceIndex == destination;
                if (!unchanged) current = current.move(fieldId, target.id(), destination);
                results.add(new MoveResult(i, fieldId, unchanged ? LayoutStatus.ALREADY_IN_PLACE : LayoutStatus.WOULD_MOVE,
                        null, null, stepWarnings));
                warnings.addAll(stepWarnings);
            } catch (InvalidMove e) {
                warnings.addAll(stepWarnings);
                for (int j = 0; j < results.size(); j++) {
                    MoveResult previous = results.get(j);
                    results.set(j, new MoveResult(previous.getIndex(), previous.getFieldId(), LayoutStatus.NOT_ATTEMPTED,
                            null, "The entire call was rejected because another move is invalid; nothing was changed.", previous.getWarnings()));
                }
                results.add(new MoveResult(i, fieldId, LayoutStatus.FAILED, e.code, e.getMessage(), stepWarnings));
                for (int j = i + 1; j < moves.size(); j++) results.add(new MoveResult(j,
                        moves.get(j) == null ? null : normalize(moves.get(j).getFieldId()), LayoutStatus.NOT_ATTEMPTED,
                        null, "Not planned because an earlier move is invalid; nothing was changed.", List.of()));
                return new Plan(null, results, List.of(), warnings, e.code, e.getMessage(), affected);
            }
        }
        return new Plan(current, results, includeDiff ? diff(before, current) : List.of(), warnings, null, null, affected);
    }

    private static void validate(ScreenFieldMove move, String fieldId) {
        if (move == null || fieldId == null || fieldId.isEmpty() || move.getPlace() == null)
            throw failure(LayoutErrorCode.VALIDATION_ERROR, "Each move requires a nonblank fieldId and place.");
        boolean relative = move.getPlace() == FieldPlace.UP || move.getPlace() == FieldPlace.DOWN;
        boolean anchored = move.getPlace() == FieldPlace.BEFORE || move.getPlace() == FieldPlace.AFTER;
        if (relative && move.getTab() != null)
            throw failure(LayoutErrorCode.VALIDATION_ERROR, "UP and DOWN cannot specify tab; they stay on the current tab.");
        if (relative && move.getSteps() != null && (move.getSteps() < 1 || move.getSteps() > 200))
            throw failure(LayoutErrorCode.VALIDATION_ERROR, "steps must be between 1 and 200.");
        if (!relative && move.getSteps() != null)
            throw failure(LayoutErrorCode.VALIDATION_ERROR, "steps is only allowed with UP or DOWN.");
        if (!anchored && move.getAnchor() != null)
            throw failure(LayoutErrorCode.VALIDATION_ERROR, "anchor is only allowed with BEFORE or AFTER.");
        String anchor = normalize(move.getAnchor());
        if (anchored && (anchor == null || anchor.isEmpty()))
            throw failure(LayoutErrorCode.VALIDATION_ERROR, "BEFORE and AFTER require a nonblank anchor field ID.");
        if (anchored && fieldId.equals(anchor))
            throw failure(LayoutErrorCode.VALIDATION_ERROR, "A field cannot be its own anchor.");
    }

    private static ScreenLayout.Tab target(ScreenLayout layout, ScreenLayout.Tab source, String wanted) {
        if (wanted == null) return source;
        String text = wanted.trim();
        if (!text.isEmpty()) for (ScreenLayout.Tab tab : layout.tabs()) {
            if (text.equals(String.valueOf(tab.id())) || text.equalsIgnoreCase(tab.name())) return tab;
        }
        throw failure(LayoutErrorCode.UNKNOWN_TAB, "No tab with ID or exact name '" + wanted + "' exists on this screen.");
    }

    private static int destination(ScreenLayout.Tab source, ScreenLayout.Tab target, int sourceIndex,
                                   String fieldId, ScreenFieldMove move, List<LayoutWarning> warnings) {
        boolean sameTab = Objects.equals(source.id(), target.id());
        int remaining = target.items().size() - (sameTab ? 1 : 0);
        return switch (move.getPlace()) {
            case FIRST -> 0;
            case LAST -> remaining;
            case UP, DOWN -> {
                int steps = move.getSteps() == null ? 1 : move.getSteps();
                int requested = sourceIndex + (move.getPlace() == FieldPlace.UP ? -steps : steps);
                int bounded = Math.max(0, Math.min(remaining, requested));
                if (requested != bounded) warnings.add(new LayoutWarning("CLAMPED",
                        "Move for '" + fieldId + "' exceeds the tab boundary and stops at "
                                + (move.getPlace() == FieldPlace.UP ? "FIRST." : "LAST.")));
                yield bounded;
            }
            case BEFORE, AFTER -> {
                String anchor = normalize(move.getAnchor());
                int anchorIndex = index(target, anchor);
                if (anchorIndex < 0) throw failure(LayoutErrorCode.UNKNOWN_ANCHOR,
                        "Anchor '" + anchor + "' is not on destination tab '" + target.name() + "'.");
                // Compute the anchor index in the destination with the source removed.
                if (sameTab && sourceIndex < anchorIndex) anchorIndex--;
                yield anchorIndex + (move.getPlace() == FieldPlace.AFTER ? 1 : 0);
            }
        };
    }

    private static int index(ScreenLayout.Tab tab, String fieldId) {
        for (int i = 0; i < tab.items().size(); i++) if (tab.items().get(i).fieldId().equals(fieldId)) return i;
        return -1;
    }

    public static String normalize(String input) {
        if (input == null) return null;
        String id = input.trim();
        if (!id.isEmpty() && id.chars().allMatch(c -> c >= '0' && c <= '9')) return "customfield_" + id;
        if (id.regionMatches(true, 0, "customfield_", 0, "customfield_".length()))
            return "customfield_" + id.substring("customfield_".length());
        return id;
    }

    public List<TabLayoutDiff> diff(ScreenLayout before, ScreenLayout after) {
        Map<String, String> names = new HashMap<>();
        List<TabLayoutDiff> diffs = new ArrayList<>();
        for (ScreenLayout.Tab previous : before.tabs()) {
            ScreenLayout.Tab next = after.tab(previous.id());
            BitSet changed = changed(previous.items(), next.items());
            if (changed.isEmpty()) continue;
            boolean truncated = Math.max(previous.items().size(), next.items().size()) > FULL_DIFF_LIMIT;
            BitSet included = null;
            if (truncated) {
                included = new BitSet();
                for (int i = changed.nextSetBit(0); i >= 0; i = changed.nextSetBit(i + 1))
                    included.set(Math.max(0, i - WINDOW_RADIUS), i + WINDOW_RADIUS + 1);
            }
            diffs.add(new TabLayoutDiff(previous.id(), previous.name(), refs(previous, included, names),
                    refs(next, included, names), truncated));
        }
        return List.copyOf(diffs);
    }

    private static BitSet changed(List<ScreenLayout.Item> before, List<ScreenLayout.Item> after) {
        BitSet changed = new BitSet();
        int size = Math.max(before.size(), after.size());
        for (int i = 0; i < size; i++) {
            if (i >= before.size() || i >= after.size()
                    || !before.get(i).fieldId().equals(after.get(i).fieldId())
                    || before.get(i).shownWhenEmpty() != after.get(i).shownWhenEmpty()) changed.set(i);
        }
        return changed;
    }

    public List<TabLayoutDiff.TabLayout> actual(ScreenLayout actual, Set<Long> affected) {
        if (actual == null) return null;
        Map<String, String> names = new HashMap<>();
        List<TabLayoutDiff.TabLayout> observed = new ArrayList<>();
        for (ScreenLayout.Tab tab : actual.tabs()) if (affected != null && affected.contains(tab.id()))
            observed.add(new TabLayoutDiff.TabLayout(tab.id(), tab.name(), refs(tab, null, names)));
        return List.copyOf(observed);
    }

    private List<TabLayoutDiff.FieldRef> refs(ScreenLayout.Tab tab, BitSet included, Map<String, String> names) {
        List<TabLayoutDiff.FieldRef> refs = new ArrayList<>();
        for (int i = 0; i < tab.items().size(); i++) {
            if (included != null && !included.get(i)) continue;
            ScreenLayout.Item item = tab.items().get(i);
            if (!names.containsKey(item.fieldId())) {
                Field field = fields.getField(item.fieldId());
                names.put(item.fieldId(), field == null ? null : field.getName());
            }
            refs.add(new TabLayoutDiff.FieldRef(item.fieldId(), names.get(item.fieldId()), i, item.position(),
                    item.layoutItemId(), item.shownWhenEmpty()));
        }
        return refs;
    }

    private static InvalidMove failure(LayoutErrorCode code, String message) { return new InvalidMove(code, message); }
    private static final class InvalidMove extends RuntimeException {
        private final LayoutErrorCode code;
        private InvalidMove(LayoutErrorCode code, String message) { super(message); this.code = code; }
    }
}
