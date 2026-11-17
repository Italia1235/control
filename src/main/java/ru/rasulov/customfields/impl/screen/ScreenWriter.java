package ru.rasulov.customfields.impl.screen;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.impl.screen.ScreenLayout;
import ru.rasulov.customfields.api.dto.screen.ScreenErrorCode;
import ru.rasulov.customfields.api.dto.screen.ScreenResult;
import ru.rasulov.customfields.api.dto.screen.ScreenTarget;
import ru.rasulov.common.api.dto.screen.FieldPlace;
import ru.rasulov.common.impl.screen.ScreenTabPositions;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;

/** Adds an absent field to an existing tab at an index; an omitted place means LAST. Gapped tabs are renumbered first. */
@Named
public class ScreenWriter {

    private static final Logger log = LoggerFactory.getLogger(ScreenWriter.class);

    private final FieldScreenManager screenManager;

    @Inject
    public ScreenWriter(@ComponentImport FieldScreenManager screenManager) {
        this.screenManager = screenManager;
    }

    /**
     * Never throws for a bad screen or tab: the problem is the result, so the other screens still get done. A failure of
     * the Jira call itself is a result too, never an exception: the caller must keep what earlier screens already got.
     * Before the first write it is FAILED ("nothing was changed"); once a write may have started it is UNKNOWN.
     */
    public ScreenResult add(String fieldId, ScreenTarget target, boolean dryRun) {
        Progress progress = new Progress();
        if (dryRun) return addChecked(fieldId, target, true, progress);
        try {
            return addChecked(fieldId, target, false, progress);
        } catch (RuntimeException failure) {
            return interrupted(fieldId, target.getScreenId(), progress, failure);
        }
    }

    private static final class Progress {
        String screenName;
        boolean writeStarted;
    }

    private ScreenResult interrupted(String fieldId, Long screenId, Progress progress, RuntimeException failure) {
        String why = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        log.error("Adding {} to screen {} failed (write started: {})", fieldId, screenId, progress.writeStarted, failure);
        if (!progress.writeStarted) {
            return ScreenResult.failed(screenId, progress.screenName, ScreenErrorCode.JIRA_ERROR,
                    "Jira failed before any change to screen " + screenId + ": " + why + ". Nothing was changed on this screen.");
        }
        return ScreenResult.unknown(screenId, progress.screenName, "The write to screen " + screenId
                + " started but was not confirmed: " + why + ". " + observed(fieldId, screenId)
                + " Read the screen before any retry.");
    }

    private String observed(String fieldId, Long screenId) {
        try {
            screenManager.refresh();
            FieldScreen now = screenManager.getFieldScreen(screenId);
            if (now == null) return "The screen could not be found afterwards.";
            return now.containsField(fieldId) ? "The field is on the screen now." : "The field is not on the screen now.";
        } catch (RuntimeException unreadable) {
            return "The screen could not be read afterwards.";
        }
    }

    private ScreenResult addChecked(String fieldId, ScreenTarget target, boolean dryRun, Progress progress) {
        Long screenId = target == null ? null : target.getScreenId();
        if (screenId == null) {
            return ScreenResult.failed(null, null, ScreenErrorCode.VALIDATION_ERROR,
                    "A screen needs a 'screenId' from the screen plan.");
        }
        String problem = placementProblem(target);
        if (problem != null) return ScreenResult.failed(screenId, null, ScreenErrorCode.VALIDATION_ERROR, problem);
        screenManager.refresh();
        FieldScreen screen = screenManager.getFieldScreen(screenId);
        if (screen == null) {
            return ScreenResult.failed(screenId, null, ScreenErrorCode.UNKNOWN_SCREEN,
                    "No screen has the id " + screenId + ". Use an id from the screen plan.");
        }
        progress.screenName = screen.getName();
        if (screen.containsField(fieldId)) {
            return ScreenResult.alreadyPresent(screenId, screen.getName());
        }
        FieldScreenTab tab = chooseTab(screen, target.getTab());
        if (tab == null) {
            return ScreenResult.failed(screenId, screen.getName(), ScreenErrorCode.UNKNOWN_TAB, tabProblem(screen, target));
        }
        ScreenLayout before = ScreenLayout.of(screen);
        // An omitted place is LAST: the same normalized indexed insertion, so the field really ends up last.
        int index = insertionIndex(before.tab(tab.getId()), target);
        if (index < 0) return ScreenResult.failed(screenId, screen.getName(), ScreenErrorCode.UNKNOWN_ANCHOR,
                "The anchor '" + target.getAnchor() + "' is not on target tab '" + tab.getName() + "'. Use an exact field ID from get_screen_contents.");
        if (dryRun) {
            return ScreenResult.wouldAdd(screenId, screen.getName(), tab.getName());
        }
        Long tabId = tab.getId();
        verifyIndices(tab, before.tab(tabId));
        progress.writeStarted = true;
        // Jira's indexed add renumbers only the tail: make the tab's positions 0..n-1 first (order unchanged).
        if (ScreenTabPositions.normalize(tab)) verifyIndices(tab, before.tab(tabId));
        // SPIKE §9 п.3: indexed add inserts and stores per bytecode; live persistence is unverified.
        tab.addFieldScreenLayoutItem(fieldId, index);
        verifyScreen(before, screenId, tabId, fieldId, index);
        log.info("Added {} to screen {} ({}), tab '{}'", fieldId, screenId, screen.getName(), tab.getName());
        return ScreenResult.added(screenId, screen.getName(), tab.getName());
    }

    private void verifyScreen(ScreenLayout before, Long screenId, Long tabId, String fieldId, int index) {
        // SPIKE §9 п.6: local refresh/readback is assumed sufficient; second-node DC cache propagation is unverified.
        screenManager.refresh();
        FieldScreen persisted = screenManager.getFieldScreen(screenId);
        if (persisted == null) throw new IllegalStateException("Screen disappeared after field addition: " + screenId);
        // Read by saved position, as the screen itself does: expected order, new field in place, positions 0..n-1.
        ScreenLayout actual = ScreenLayout.of(persisted);
        if (!before.insert(tabId, fieldId, index, false).matches(actual)
                || !ScreenTabPositions.isSequential(actual.tab(tabId)))
            throw new IllegalStateException("Screen changed beyond the requested field addition.");
    }

    /** Canonical raw-position snapshots for positioned add plans, read after one refresh; null for a missing screen. */
    public List<String> fingerprints(List<Long> screenIds) {
        screenManager.refresh();
        List<String> result = new ArrayList<String>(screenIds.size());
        for (Long id : screenIds) {
            FieldScreen screen = id == null ? null : screenManager.getFieldScreen(id);
            result.add(screen == null ? null : ScreenLayout.of(screen).fingerprint());
        }
        return result;
    }

    private static String placementProblem(ScreenTarget target) {
        FieldPlace place = target.getPlace();
        if (place == FieldPlace.UP || place == FieldPlace.DOWN) return "UP and DOWN are only supported by edit_screen_layout moves.";
        boolean anchored = place == FieldPlace.BEFORE || place == FieldPlace.AFTER;
        if (anchored && (target.getAnchor() == null || target.getAnchor().trim().isEmpty()))
            return "BEFORE and AFTER require a nonblank anchor field ID.";
        if (!anchored && target.getAnchor() != null) return "anchor is only allowed with BEFORE or AFTER.";
        return null;
    }

    private static int insertionIndex(ScreenLayout.Tab tab, ScreenTarget target) {
        if (target.getPlace() == null) return tab.items().size();
        return switch (target.getPlace()) {
            case FIRST -> 0;
            case LAST -> tab.items().size();
            case BEFORE, AFTER -> {
                String anchor = target.getAnchor().trim();
                if (anchor.chars().allMatch(c -> c >= '0' && c <= '9')) anchor = "customfield_" + anchor;
                int found = -1;
                for (int i = 0; i < tab.items().size(); i++) if (anchor.equals(tab.items().get(i).fieldId())) { found = i; break; }
                yield found < 0 ? -1 : found + (target.getPlace() == FieldPlace.AFTER ? 1 : 0);
            }
            default -> throw new IllegalArgumentException("Unsupported add placement.");
        };
    }

    private static void verifyIndices(FieldScreenTab tab, ScreenLayout.Tab expected) {
        List<FieldScreenLayoutItem> items = tab.getFieldScreenLayoutItems();
        if (items.size() != expected.items().size()) throw new IllegalStateException("Target tab size changed before field addition.");
        for (int i = 0; i < items.size(); i++) {
            FieldScreenLayoutItem indexed = tab.getFieldScreenLayoutItem(i);
            String field = expected.items().get(i).fieldId();
            if (!field.equals(items.get(i).getFieldId()) || indexed == null || !field.equals(indexed.getFieldId()))
                throw new IllegalStateException("Target tab index changed before field addition.");
        }
    }

    /** The first tab when none is asked for; else by id or by name ignoring case. {@code null} if there is none. */
    private static FieldScreenTab chooseTab(FieldScreen screen, String wanted) {
        List<FieldScreenTab> tabs = screen.getTabs();
        if (tabs.isEmpty()) {
            return null;
        }
        String text = wanted == null ? "" : wanted.trim();
        if (text.isEmpty()) {
            return tabs.get(0);
        }
        for (FieldScreenTab tab : tabs) {
            if (text.equals(String.valueOf(tab.getId())) || text.equalsIgnoreCase(tab.getName())) {
                return tab;
            }
        }
        return null;
    }

    private static String tabProblem(FieldScreen screen, ScreenTarget target) {
        List<String> names = new ArrayList<String>();
        for (FieldScreenTab tab : screen.getTabs()) {
            names.add("'" + tab.getName() + "'");
        }
        if (names.isEmpty()) {
            return "The screen has no tab to put the field on.";
        }
        return "The screen has no tab '" + target.getTab() + "'. Its tabs: " + String.join(", ", names)
                + ". Leave 'tab' out to use the first one.";
    }
}
