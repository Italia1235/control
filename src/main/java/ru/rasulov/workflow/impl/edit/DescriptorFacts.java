package ru.rasulov.workflow.impl.edit;

import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.FunctionDescriptor;
import com.opensymphony.workflow.loader.ResultDescriptor;
import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import com.opensymphony.workflow.util.XMLizable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Read-only facts about a workflow descriptor that the reader and the patcher share. */
public final class DescriptorFacts {

    static final String STATUS_META = "jira.status.id";
    static final String SCREEN_META = "jira.fieldscreen.id";
    /** The {@code view} of an action whose transition shows a screen: without it Jira does not open the screen. */
    static final String SCREEN_VIEW = "fieldscreen";
    static final String DESCRIPTION_META = "jira.description";
    static final String UPDATE_FIELD_FUNCTION = "com.atlassian.jira.workflow.function.issue.UpdateIssueFieldFunction";
    private static final String SAVE_STAMP_META = "<meta name=\"jira\\.(?:updated\\.date|update\\.author\\.name|update\\.author\\.key)\">[^<]*</meta>";
    private static final Pattern SAVE_STAMPS = Pattern.compile("(?m)^[\\t ]*" + SAVE_STAMP_META
            + "[\\t ]*(?:\\r?\\n|$)|" + SAVE_STAMP_META);

    enum Kind { INITIAL, LOCAL, GLOBAL, COMMON }

    /** Where an action id lives. {@code sources} are the steps holding it (or referencing a common action). */
    record Located(int id, Kind kind, ActionDescriptor action, List<Integer> sources, boolean duplicated) { }

    /** SET (value), CLEAR, PRESERVE (no writer) or UNKNOWN (reason, never the arguments of the function). */
    record ResolutionRule(String action, String value, String reason) { }

    private DescriptorFacts() { }

    @SuppressWarnings("unchecked")
    static List<StepDescriptor> steps(WorkflowDescriptor d) {
        return (List<StepDescriptor>) d.getSteps();
    }

    @SuppressWarnings("unchecked")
    static List<ActionDescriptor> initialActions(WorkflowDescriptor d) {
        return (List<ActionDescriptor>) d.getInitialActions();
    }

    @SuppressWarnings("unchecked")
    static List<ActionDescriptor> globalActions(WorkflowDescriptor d) {
        return (List<ActionDescriptor>) d.getGlobalActions();
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, ActionDescriptor> commonActions(WorkflowDescriptor d) {
        return (Map<Integer, ActionDescriptor>) d.getCommonActions();
    }

    @SuppressWarnings("unchecked")
    static List<ActionDescriptor> actions(StepDescriptor s) {
        return (List<ActionDescriptor>) s.getActions();
    }

    @SuppressWarnings("unchecked")
    static List<Object> commonRefs(StepDescriptor s) {
        return (List<Object>) s.getCommonActions();
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> meta(StepDescriptor s) {
        return (Map<String, String>) s.getMetaAttributes();
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> meta(ActionDescriptor a) {
        return (Map<String, String>) a.getMetaAttributes();
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> meta(WorkflowDescriptor d) {
        return (Map<String, String>) d.getMetaAttributes();
    }

    @SuppressWarnings("unchecked")
    static List<FunctionDescriptor> postFunctions(ResultDescriptor r) {
        return (List<FunctionDescriptor>) r.getPostFunctions();
    }

    @SuppressWarnings("unchecked")
    static List<ResultDescriptor> conditionalResults(ActionDescriptor a) {
        return (List<ResultDescriptor>) a.getConditionalResults();
    }

    static String statusId(StepDescriptor step) {
        Map<String, String> meta = meta(step);
        return meta == null ? null : meta.get(STATUS_META);
    }

    static StepDescriptor step(WorkflowDescriptor d, int id) {
        for (StepDescriptor step : steps(d)) {
            if (step.getId() == id) return step;
        }
        return null;
    }

    /** Every action by id, in document order: initial, global, common, then the steps'. */
    static Map<Integer, Located> locate(WorkflowDescriptor d) {
        Map<Integer, Located> found = new LinkedHashMap<Integer, Located>();
        for (ActionDescriptor a : initialActions(d)) put(found, a, Kind.INITIAL, Collections.<Integer>emptyList());
        for (ActionDescriptor a : globalActions(d)) put(found, a, Kind.GLOBAL, Collections.<Integer>emptyList());
        for (Map.Entry<Integer, ActionDescriptor> e : commonActions(d).entrySet()) {
            List<Integer> users = new ArrayList<Integer>();
            for (StepDescriptor s : steps(d)) {
                for (Object ref : commonRefs(s)) {
                    if (ref instanceof Number && ((Number) ref).intValue() == e.getKey()) users.add(s.getId());
                }
            }
            put(found, e.getValue(), Kind.COMMON, users);
        }
        for (StepDescriptor s : steps(d)) {
            for (ActionDescriptor a : actions(s)) {
                if (!a.isCommon()) put(found, a, Kind.LOCAL, List.of(s.getId()));
            }
        }
        return found;
    }

    private static void put(Map<Integer, Located> found, ActionDescriptor a, Kind kind, List<Integer> sources) {
        Located old = found.get(a.getId());
        if (old == null) {
            found.put(a.getId(), new Located(a.getId(), kind, a, sources, false));
        } else {
            found.put(a.getId(), new Located(a.getId(), old.kind(), old.action(), old.sources(), true));
        }
    }

    /** Targets of every result of the action: the unconditional one first, then the conditional ones. */
    static List<Integer> targets(ActionDescriptor a) {
        List<Integer> targets = new ArrayList<Integer>();
        if (a.getUnconditionalResult() != null) targets.add(a.getUnconditionalResult().getStep());
        for (ResultDescriptor r : conditionalResults(a)) {
            if (!targets.contains(r.getStep())) targets.add(r.getStep());
        }
        return targets;
    }

    static boolean hasConditionalResults(ActionDescriptor a) {
        return !conditionalResults(a).isEmpty();
    }

    /** A result into a join or a split is a structure that a step id alone does not address. */
    static boolean hasJoinOrSplit(ActionDescriptor a) {
        ResultDescriptor r = a.getUnconditionalResult();
        return r != null && (r.getJoin() != 0 || r.getSplit() != 0);
    }

    /** Why the structure fields (source, target, resolution, removal) of the action cannot be patched; null if they can. */
    static String structureBlocker(Located l) {
        if (l.duplicated()) return "the action id is used more than once";
        if (l.kind() == Kind.INITIAL) return "initial action: use SET_INITIAL_STATUS";
        if (l.kind() == Kind.COMMON) return "common action shared by steps " + l.sources();
        if (hasConditionalResults(l.action())) return "conditional results";
        if (l.action().getUnconditionalResult() == null) return "no unconditional result";
        if (hasJoinOrSplit(l.action())) return "result goes into a join or split";
        return null;
    }

    static ResolutionRule rule(ActionDescriptor action) {
        if (hasConditionalResults(action)) return new ResolutionRule("UNKNOWN", null, "conditional results");
        List<FunctionDescriptor> unconditional = new ArrayList<FunctionDescriptor>();
        int elsewhere = 0;
        String elsewhereClass = null;
        for (Object f : action.getPreFunctions()) {
            if (writesResolution((FunctionDescriptor) f)) { elsewhere++; elsewhereClass = cls((FunctionDescriptor) f); }
        }
        for (Object f : action.getPostFunctions()) {
            if (writesResolution((FunctionDescriptor) f)) { elsewhere++; elsewhereClass = cls((FunctionDescriptor) f); }
        }
        List<ResultDescriptor> others = new ArrayList<ResultDescriptor>(conditionalResults(action));
        for (ResultDescriptor r : others) {
            for (Object f : r.getPreFunctions()) {
                if (writesResolution((FunctionDescriptor) f)) { elsewhere++; elsewhereClass = cls((FunctionDescriptor) f); }
            }
            for (FunctionDescriptor f : postFunctions(r)) {
                if (writesResolution(f)) { elsewhere++; elsewhereClass = cls(f); }
            }
        }
        ResultDescriptor result = action.getUnconditionalResult();
        if (result != null) {
            for (Object f : result.getPreFunctions()) {
                if (writesResolution((FunctionDescriptor) f)) { elsewhere++; elsewhereClass = cls((FunctionDescriptor) f); }
            }
            for (FunctionDescriptor f : postFunctions(result)) {
                if (writesResolution(f)) unconditional.add(f);
            }
        }
        if (elsewhere > 0) {
            return new ResolutionRule("UNKNOWN", null, "a function outside the unconditional result post-functions "
                    + "writes resolution (" + elsewhereClass + ")");
        }
        if (unconditional.isEmpty()) return new ResolutionRule("PRESERVE", null, null);
        if (unconditional.size() > 1) return new ResolutionRule("UNKNOWN", null, "several functions write resolution");
        FunctionDescriptor writer = unconditional.get(0);
        if (!UPDATE_FIELD_FUNCTION.equals(cls(writer))) {
            return new ResolutionRule("UNKNOWN", null, "resolution is written by " + cls(writer));
        }
        Object value = writer.getArgs().get("field.value");
        if (value == null) return new ResolutionRule("UNKNOWN", null, "the resolution writer has no value");
        String text = String.valueOf(value).trim();
        if ("null".equals(text)) return new ResolutionRule("CLEAR", null, null);
        if (text.matches("\\d+")) return new ResolutionRule("SET", text, null);
        return new ResolutionRule("UNKNOWN", null, "the resolution writer has a value that is not a resolution id");
    }

    static boolean writesResolution(FunctionDescriptor f) {
        Object field = f.getArgs().get("field.name");
        return field != null && "resolution".equals(String.valueOf(field).trim());
    }

    static String cls(FunctionDescriptor f) {
        Object c = f.getArgs().get("class.name");
        return c == null ? "a function without class" : String.valueOf(c);
    }

    /** XML of a descriptor part. */
    static String xml(XMLizable part) {
        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        part.writeXML(writer, 0);
        writer.flush();
        return text.toString();
    }

    /** Ignore Jira save stamps, including a whole stamp-only line, but never neighboring substantive XML. */
    public static String definition(WorkflowDescriptor d) {
        return SAVE_STAMPS.matcher(xml(d)).replaceAll("");
    }

    /** Step ids that are the target of no action and not initial (nothing leads there), as a sorted set. */
    static java.util.Set<Integer> unreachable(WorkflowDescriptor d) {
        java.util.Set<Integer> reached = new java.util.TreeSet<Integer>();
        for (Located l : locate(d).values()) reached.addAll(targets(l.action()));
        java.util.Set<Integer> loose = new java.util.TreeSet<Integer>();
        for (StepDescriptor s : steps(d)) {
            if (!reached.contains(s.getId())) loose.add(s.getId());
        }
        return loose;
    }

    /** Step ids with no way out: no own actions, no common actions and no global actions at all. */
    static java.util.Set<Integer> deadEnds(WorkflowDescriptor d) {
        java.util.Set<Integer> dead = new java.util.TreeSet<Integer>();
        boolean anyGlobal = !globalActions(d).isEmpty();
        for (StepDescriptor s : steps(d)) {
            if (actions(s).isEmpty() && commonRefs(s).isEmpty() && !anyGlobal) dead.add(s.getId());
        }
        return dead;
    }

    static Map<Integer, String> stepNames(WorkflowDescriptor d) {
        Map<Integer, String> names = new TreeMap<Integer, String>();
        for (StepDescriptor s : steps(d)) names.put(s.getId(), s.getName());
        return names;
    }
}
