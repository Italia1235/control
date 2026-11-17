package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.*;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.rasulov.common.impl.undo.UndoHandler;
import ru.rasulov.common.impl.undo.UndoStep;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/** Exact, ordered object snapshots: each child write shares its parent's resource history. */
@Named
public class ScreenUndoHandler implements UndoHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FieldScreenManager screens;
    private final FieldScreenSchemeManager schemes;
    private final WorkflowManager workflows;

    @Inject
    public ScreenUndoHandler(@ComponentImport FieldScreenManager screens,
            @ComponentImport FieldScreenSchemeManager schemes, @ComponentImport WorkflowManager workflows) {
        this.screens = screens; this.schemes = schemes; this.workflows = workflows;
    }

    @Override public String kind() { return "screen.change"; }
    @Override public List<String> lockNames(UndoStep step) {
        return Arrays.asList(ScreenSchemeServiceImpl.LOCK_NAME, StandaloneScreenCreatorImpl.LOCK_NAME,
                "ru.rasulov.control.edit-screens");
    }

    static Map<String, Object> value(Object... pairs) {
        Map<String, Object> result = new TreeMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    static String json(Map<String, Object> value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode screen journal state", e); }
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String json) {
        try { return JSON.readValue(json, TreeMap.class); }
        catch (Exception e) { throw new IllegalStateException("Cannot decode screen journal state", e); }
    }
    static Map<String, Object> absent(String type, String name) {
        return value("type", type, "name", name, "exists", false);
    }
    static long id(Map<String, Object> state) { return ((Number) state.get("id")).longValue(); }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> children(Map<String, Object> state) {
        return (List<Map<String, Object>>) state.get("children");
    }

    public Map<String, Object> snapshot(FieldScreen screen) {
        List<Map<String, Object>> tabs = new ArrayList<Map<String, Object>>();
        for (FieldScreenTab tab : screen.getTabs()) {
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (FieldScreenLayoutItem item : tab.getFieldScreenLayoutItems()) {
                items.add(value("id", item.getId(), "field", item.getFieldId(),
                        "position", item.getPosition(), "shown", item.isShownWhenEmpty()));
            }
            tabs.add(value("id", tab.getId(), "name", tab.getName(), "position", tab.getPosition(), "children", items));
        }
        return value("type", "screen", "exists", true, "id", screen.getId(), "name", screen.getName(),
                "description", screen.getDescription(), "children", tabs);
    }
    Map<String, Object> persisted(Map<String, Object> identity) {
        String type = (String) identity.get("type");
        if ("screen".equals(type)) screens.refresh();
        // Screen scheme references are cached independently of the screen.
        schemes.refresh();
        return current(identity);
    }
    private Map<String, Object> current(Map<String, Object> identity) {
        String type = (String) identity.get("type");
        long objectId = id(identity);
        if ("screen".equals(type)) {
            FieldScreen screen = screens.getFieldScreen(objectId);
            return screen == null ? absent(type, (String) identity.get("name")) : snapshot(screen);
        }
        throw new IllegalStateException("Unknown screen journal type: " + type);
    }
    private boolean same(Map<String, Object> a, Map<String, Object> b) { return json(a).equals(json(b)); }
    @Override public String conflict(UndoStep step) {
        if (step.getAfterJson() == null) return "Screen write outcome is uncertain; the created identity is not proven.";
        Map<String, Object> before = parse(step.getBeforeJson()), after = parse(step.getAfterJson());
        Map<String, Object> now = persisted(after);
        return same(now, before) || same(now, after) ? null
                : "Screen object changed after this operation: " + step.getResource();
    }

    @Override public String conflict(UndoStep step, List<UndoStep> selection) {
        if (step.getAfterJson() == null) return conflict(step);
        Map<String, Object> expected = parse(step.getAfterJson());
        Map<String, Object> live = persisted(expected);
        if (!same(live, parse(step.getBeforeJson())) && !same(live, expected)) {
            return "Screen object changed after this operation: " + step.getResource();
        }
        Map<String, Map<String, Object>> baseline = new HashMap<String, Map<String, Object>>();
        Set<String> removedWorkflows = new HashSet<String>();
        for (UndoStep candidate : selection) {
            if (candidate.getAfterJson() == null || "UNDONE".equals(candidate.getState())
                    || "DISCARDED".equals(candidate.getState())) continue;
            if (kind().equals(candidate.getKind())) {
                Map<String, Object> after = parse(candidate.getAfterJson());
                baseline.put(after.get("type") + ":" + id(after), parse(candidate.getBeforeJson()));
            } else if ("workflow.created".equals(candidate.getKind())
                    && Boolean.TRUE.equals(parse(candidate.getBeforeJson()).get("absent"))) {
                removedWorkflows.add((String) parse(candidate.getAfterJson()).get("name"));
            }
        }
        Map<String, Object> after = parse(step.getAfterJson());
        String type = (String) after.get("type");
        long objectId = id(after);
        Map<String, Object> earliest = baseline.get(type + ":" + objectId);
        if (earliest == null || Boolean.TRUE.equals(earliest.get("exists"))) return null;
        if (!Boolean.TRUE.equals(live.get("exists"))) return null;
        if ("screen".equals(type)) {
            if (!schemes.getFieldScreenSchemes(screens.getFieldScreen(objectId)).isEmpty()) {
                return "Created screen is referenced by a screen scheme outside the selected rollback";
            }
            for (JiraWorkflow workflow : workflows.getWorkflowsIncludingDrafts()) {
                if (references(workflow, objectId) && !removedWorkflows.contains(workflow.getName())) {
                    return "Created screen is referenced by workflow " + workflow.getName();
                }
            }
        }
        return null;
    }
    private static boolean references(JiraWorkflow workflow, long screenId) {
        for (Object actionObject : workflow.getAllActions()) {
            com.opensymphony.workflow.loader.ActionDescriptor action =
                    (com.opensymphony.workflow.loader.ActionDescriptor) actionObject;
            if (action.getMetaAttributes() != null && String.valueOf(screenId)
                    .equals(String.valueOf(action.getMetaAttributes().get("jira.fieldscreen.id")))) return true;
        }
        return false;
    }

    @Override public void undo(UndoStep step, ApplicationUser actor) {
        if (step.getAfterJson() == null) throw new IllegalStateException("Screen write outcome is uncertain");
        Map<String, Object> before = parse(step.getBeforeJson()), after = parse(step.getAfterJson());
        Map<String, Object> now = persisted(after);
        if (same(now, before)) return;
        if (!same(now, after)) throw new IllegalStateException("Screen object changed: " + step.getResource());
        long objectId = id(after);
        if (!Boolean.TRUE.equals(before.get("exists"))) {
            delete(objectId);
        } else {
            restoreScreen(screens.getFieldScreen(objectId), before);
        }
        if (!same(persisted(after), before)) throw new IllegalStateException("Jira did not restore the exact prior screen state");
    }
    private void restoreScreen(FieldScreen screen, Map<String, Object> before) {
        Map<Long, Map<String, Object>> keepTabs = indexed(children(before));
        for (FieldScreenTab tab : new ArrayList<FieldScreenTab>(screen.getTabs())) {
            Map<String, Object> priorTab = keepTabs.get(tab.getId());
            if (priorTab == null) { screens.removeFieldScreenTab(tab.getId()); continue; }
            Map<Long, Map<String, Object>> keep = indexed(children(priorTab));
            for (FieldScreenLayoutItem item : new ArrayList<FieldScreenLayoutItem>(tab.getFieldScreenLayoutItems())) {
                Map<String, Object> priorItem = keep.get(item.getId());
                if (priorItem == null) tab.removeFieldScreenLayoutItem(item.getPosition());
                else if (item.isShownWhenEmpty() != Boolean.TRUE.equals(priorItem.get("shown"))) {
                    item.setShowWhenEmpty(Boolean.TRUE.equals(priorItem.get("shown"))); item.store();
                }
            }
        }
    }
    private void delete(long objectId) {
        FieldScreen screen = screens.getFieldScreen(objectId);
        if (!schemes.getFieldScreenSchemes(screen).isEmpty()) throw new IllegalStateException("Created screen is referenced by a screen scheme");
        for (JiraWorkflow workflow : workflows.getWorkflowsIncludingDrafts()) {
            if (references(workflow, objectId)) {
                throw new IllegalStateException("Created screen is referenced by workflow " + workflow.getName());
            }
        }
        if (!screen.getTabs().isEmpty()) throw new IllegalStateException("Created screen still has tabs");
        screens.removeFieldScreen(objectId);
    }
    private static Map<Long, Map<String, Object>> indexed(List<Map<String, Object>> items) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> item : items) result.put(id(item), item);
        return result;
    }
}
