package ru.rasulov.workflow.impl;

import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.ResultDescriptor;
import com.opensymphony.workflow.loader.FunctionDescriptor;
import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.ValidatorDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowStatus;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowTransition;

import javax.inject.Named;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ru.rasulov.common.impl.Normalizer.normalize;

/**
 * Whether the workflow that already has the requested name is the requested one: the same statuses, the same initial
 * status, the same transitions (name, source, target) and the same "from any status" transitions. Then the request is a
 * repeat and nothing is written; otherwise the name is taken and the caller is told how the two differ.
 */
@Named
public class ExistingWorkflowMatcher {

    private static final int MAX_LISTED = 5;
    /** The transition's result leaves the issue in the step it started from. */
    private static final int SAME_STEP = -1;

    /** @return what differs, one sentence each; empty when the existing workflow is the requested one */
    public List<String> differences(ResolvedWorkflow wanted, JiraWorkflow actual) {
        Layout layout = read(actual);
        List<String> differences = new ArrayList<String>();

        Map<String, String> wantedStatuses = new LinkedHashMap<String, String>();
        for (WorkflowStatus status : wanted.getStatuses()) {
            wantedStatuses.put(normalize(status.getName()), status.getName());
        }
        addDifference(differences, "statuses only in the requested workflow", missing(wantedStatuses, layout.statuses));
        addDifference(differences, "statuses only in the existing workflow", missing(layout.statuses, wantedStatuses));

        String wantedInitial = wanted.getInitial().getName();
        if (layout.initial == null || !normalize(layout.initial).equals(normalize(wantedInitial))) {
            differences.add("the initial status is '" + (layout.initial == null ? "none" : layout.initial)
                    + "' in Jira and '" + wantedInitial + "' in the request");
        }

        Map<TransitionKey, String> wantedTransitions = new LinkedHashMap<TransitionKey, String>();
        for (WorkflowTransition transition : wanted.getTransitions()) {
            String from = transition.isFromAny() ? null : transition.getFrom().getName();
            String screen = transition.getScreen() == null ? null : String.valueOf(transition.getScreen().getId());
            String resolution = transition.getResolution().getAction()
                    + ("SET".equals(transition.getResolution().getAction()) ? ":" + transition.getResolution().getValue() : "");
            String view = screen == null ? "" : "fieldscreen";
            wantedTransitions.put(key(transition.getName(), from, transition.getTo().getName(), screen, view, resolution),
                    describe(transition.getName(), from, transition.getTo().getName(), screen) + " view '" + view + "' resolution " + resolution);
        }
        addDifference(differences, "transitions only in the requested workflow",
                missing(wantedTransitions, layout.transitions));
        addDifference(differences, "transitions only in the existing workflow",
                missing(layout.transitions, wantedTransitions));
        addDifference(differences, "existing actions with conditions, validators, conditional results or extra initial "
                + "actions that the comparison cannot confirm", layout.unconfirmed);
        return differences;
    }

    private static void addDifference(List<String> differences, String what, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        List<String> shown = items.size() > MAX_LISTED ? items.subList(0, MAX_LISTED) : items;
        differences.add(what + ": " + String.join(", ", shown)
                + (items.size() > MAX_LISTED ? " and " + (items.size() - MAX_LISTED) + " more" : ""));
    }

    /** Values of {@code from} whose keys {@code other} lacks. */
    private static <K> List<String> missing(Map<K, String> from, Map<K, String> other) {
        List<String> missing = new ArrayList<String>();
        for (Map.Entry<K, String> entry : from.entrySet()) {
            if (!other.containsKey(entry.getKey())) {
                missing.add(entry.getValue());
            }
        }
        return missing;
    }

    private static Layout read(JiraWorkflow workflow) {
        WorkflowDescriptor descriptor = workflow.getDescriptor();
        Layout layout = new Layout();
        Map<Integer, String> stepNames = new HashMap<Integer, String>();
        for (Object item : descriptor.getSteps()) {
            StepDescriptor step = (StepDescriptor) item;
            Status status = workflow.getLinkedStatusObject(step);
            String name = status == null ? step.getName() : status.getName();
            stepNames.put(step.getId(), name);
            layout.statuses.put(normalize(name), name);
        }
        boolean firstInitial = true;
        for (Object item : descriptor.getInitialActions()) {
            ActionDescriptor action = (ActionDescriptor) item;
            if (!firstInitial || isBeyondPlain(action, true)) {
                layout.unconfirmed.add("initial action " + label(action));
            }
            firstInitial = false;
            String target = targetOf(action, null, stepNames);
            if (layout.initial == null && target != null) {
                layout.initial = target;
            }
        }
        for (Object item : descriptor.getGlobalActions()) {
            ActionDescriptor action = (ActionDescriptor) item;
            if (isBeyondPlain(action, false)) layout.unconfirmed.add(label(action) + " (from any status)");
            String target = targetOf(action, null, stepNames);
            if (target != null) {
                String resolution = resolutionOf(action);
                layout.transitions.put(key(action.getName(), null, target, screenOf(action), action.getView(), resolution),
                        describe(action.getName(), null, target, screenOf(action)) + " view '" + (action.getView() == null ? "" : action.getView()) + "' resolution " + resolution);
            }
        }
        for (Object item : descriptor.getSteps()) {
            StepDescriptor step = (StepDescriptor) item;
            String source = stepNames.get(step.getId());
            List<ActionDescriptor> actions = new ArrayList<ActionDescriptor>();
            for (Object action : step.getActions()) {
                actions.add((ActionDescriptor) action);
            }
            for (Object commonId : step.getCommonActions()) {
                Object common = descriptor.getCommonActions().get(commonId);
                if (common != null) {
                    actions.add((ActionDescriptor) common);
                }
            }
            for (ActionDescriptor action : actions) {
                if (isBeyondPlain(action, false)) layout.unconfirmed.add(label(action) + " in '" + source + "'");
                String target = targetOf(action, source, stepNames);
                if (target != null) {
                    String resolution = resolutionOf(action);
                    layout.transitions.put(key(action.getName(), source, target, screenOf(action), action.getView(), resolution),
                            describe(action.getName(), source, target, screenOf(action)) + " view '" + (action.getView() == null ? "" : action.getView()) + "' resolution " + resolution);
                }
            }
        }
        return layout;
    }

    /** The status an action leads to; {@code null} when it has no plain result (conditional results only). */
    private static String targetOf(ActionDescriptor action, String source, Map<Integer, String> stepNames) {
        ResultDescriptor result = action.getUnconditionalResult();
        if (result == null) {
            return null;
        }
        if (result.getStep() == SAME_STEP) {
            return source;
        }
        return stepNames.get(result.getStep());
    }

    /** Only the generated native sequence is known to preserve the confirmed resolution semantics. */
    private static String resolutionOf(ActionDescriptor action) {
        if (!action.getConditionalResults().isEmpty() || !action.getPreFunctions().isEmpty()
                || !action.getPostFunctions().isEmpty()) return "UNSUPPORTED";
        ResultDescriptor result = action.getUnconditionalResult();
        if (result == null || !result.getPreFunctions().isEmpty()) return "UNSUPPORTED";
        List functions = result.getPostFunctions();
        if (functions.size() != 5 && functions.size() != 6) return "UNSUPPORTED";
        String[] classes = {
                "com.atlassian.jira.workflow.function.issue.UpdateIssueStatusFunction",
                "com.atlassian.jira.workflow.function.misc.CreateCommentFunction",
                "com.atlassian.jira.workflow.function.issue.GenerateChangeHistoryFunction",
                "com.atlassian.jira.workflow.function.issue.IssueReindexFunction",
                "com.atlassian.jira.workflow.function.event.FireIssueEventFunction"
        };
        String resolution = "PRESERVE";
        int standard = 0;
        for (int i = 0; i < functions.size(); i++) {
            FunctionDescriptor function = (FunctionDescriptor) functions.get(i);
            Map args = function.getArgs();
            if (!"class".equals(function.getType())) return "UNSUPPORTED";
            if (functions.size() == 6 && i == 2) {
                if (!"com.atlassian.jira.workflow.function.issue.UpdateIssueFieldFunction".equals(args.get("class.name"))
                        || !"resolution".equals(args.get("field.name"))) return "UNSUPPORTED";
                Object value = args.get("field.value");
                if (!(value instanceof String) || ((String) value).isEmpty()
                        || !value.equals(((String) value).trim())) return "UNSUPPORTED";
                resolution = "null".equals(value) ? "CLEAR" : "SET:" + value;
            } else {
                if (!classes[standard++].equals(args.get("class.name"))) return "UNSUPPORTED";
                if (i == functions.size() - 1 && !"13".equals(String.valueOf(args.get("eventTypeId")))) {
                    return "UNSUPPORTED";
                }
            }
        }
        return resolution;
    }

    private static String screenOf(ActionDescriptor action) {
        Object screen = action.getMetaAttributes().get("jira.fieldscreen.id");
        return screen == null || screen.toString().trim().isEmpty() ? null : screen.toString().trim();
    }

    private static TransitionKey key(String name, String from, String to, String screen, String view, String resolution) {
        return new TransitionKey(name, from, to, screen, view, resolution);
    }

    private static final class TransitionKey {
        private final String name;
        private final boolean global;
        private final String from;
        private final String to;
        private final String screen;
        private final String view;
        private final String resolution;

        private TransitionKey(String name, String from, String to, String screen, String view, String resolution) {
            this.name = normalize(name);
            this.global = from == null;
            this.from = global ? "" : normalize(from);
            this.to = normalize(to);
            this.screen = screen == null ? "" : screen;
            this.view = view == null ? "" : view;
            this.resolution = resolution;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TransitionKey)) return false;
            TransitionKey key = (TransitionKey) other;
            return global == key.global && name.equals(key.name) && from.equals(key.from)
                    && to.equals(key.to) && screen.equals(key.screen) && view.equals(key.view) && resolution.equals(key.resolution);
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + (global ? 1 : 0);
            result = 31 * result + from.hashCode();
            result = 31 * result + to.hashCode();
            result = 31 * result + screen.hashCode();
            result = 31 * result + view.hashCode();
            return 31 * result + resolution.hashCode();
        }
    }

    /**
     * Whether the action has anything beyond a plain result: a condition, a validator, a result chosen by condition or
     * no plain result at all. The generated "Create" action carries exactly one standard permission validator.
     */
    private static boolean isBeyondPlain(ActionDescriptor action, boolean initial) {
        ResultDescriptor result = action.getUnconditionalResult();
        if (result == null || !action.getConditionalResults().isEmpty() || action.getRestriction() != null
                || !result.getValidators().isEmpty()) return true;
        List validators = action.getValidators();
        if (!initial) return !validators.isEmpty();
        if (validators.size() != 1) return true;
        ValidatorDescriptor validator = (ValidatorDescriptor) validators.get(0);
        return !"class".equals(validator.getType())
                || !"com.atlassian.jira.workflow.validator.PermissionValidator".equals(validator.getArgs().get("class.name"))
                || !"Create Issue".equals(validator.getArgs().get("permission"));
    }

    private static String label(ActionDescriptor action) {
        return "'" + action.getName() + "'";
    }

    private static String describe(String name, String from, String to, String screen) {
        return "'" + name + "' (" + (from == null ? "from any status" : "'" + from + "'") + " to '" + to + "'"
                + (screen == null ? "" : ", screen id " + screen) + ")";
    }

    private static final class Layout {
        private final Map<String, String> statuses = new LinkedHashMap<String, String>();
        private String initial;
        private final Map<TransitionKey, String> transitions = new LinkedHashMap<TransitionKey, String>();
        /** Actions with behaviour the comparison does not model: conditions, validators, conditional results, extra initial actions. */
        private final List<String> unconfirmed = new ArrayList<String>();
    }
}
