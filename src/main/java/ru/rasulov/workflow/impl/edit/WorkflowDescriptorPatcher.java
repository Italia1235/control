package ru.rasulov.workflow.impl.edit;

import com.opensymphony.workflow.InvalidWorkflowDescriptorException;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.DescriptorFactory;
import com.opensymphony.workflow.loader.FunctionDescriptor;
import com.opensymphony.workflow.loader.ResultDescriptor;
import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import ru.rasulov.workflow.api.dto.TransitionResolution;
import ru.rasulov.workflow.api.dto.edit.EditErrorCode;
import ru.rasulov.workflow.api.dto.edit.ScreenPatch;
import ru.rasulov.workflow.api.dto.edit.TransitionSource;
import ru.rasulov.workflow.api.dto.edit.WorkflowChange;
import ru.rasulov.workflow.impl.edit.DescriptorFacts.Kind;
import ru.rasulov.workflow.impl.edit.DescriptorFacts.Located;
import ru.rasulov.workflow.impl.edit.DescriptorFacts.ResolutionRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Applies a list of point changes to a detached copy of a workflow descriptor. Only what a change names is touched:
 * ids, properties, conditions, validators, functions and the order of everything else stay as they are. Nothing here
 * talks to Jira; what Jira knows (statuses, screens, resolutions) arrives through {@link Catalog}.
 */
public final class WorkflowDescriptorPatcher {

    public record StatusInfo(String id, String name, String categoryKey) { }

    public record ResolutionInfo(String id, String name) { }

    public interface Catalog {
        /** @return {@code null} when no status has the id */
        StatusInfo status(String id);

        boolean screenExists(long id);

        /** @return {@code null} when no resolution has the canonical id */
        ResolutionInfo resolution(String id);
    }

    /** Effects and allocated ids; local keys may be absent for an allocated object. */
    public record Result(List<String> diff, List<String> warnings,
                         Map<Integer,String> steps, Map<Integer,String> transitions) { }

    /** Structural operations, in the order they are applied. */
    public static final List<String> OPS = Arrays.asList("ADD_STATUS", "REPLACE_STATUS", "UPDATE_TRANSITION",
            "ADD_TRANSITION", "REMOVE_TRANSITION", "SET_INITIAL_STATUS", "UPDATE_METADATA", "REMOVE_STATUS");
    private static final List<String> BEFORE_HISTORY = Arrays.asList(
            "com.atlassian.jira.workflow.function.issue.GenerateChangeHistoryFunction",
            "com.atlassian.jira.workflow.function.issue.IssueReindexFunction",
            "com.atlassian.jira.workflow.function.event.FireIssueEventFunction");
    private static final int MAX_NAME = 255;

    private final WorkflowDescriptor d;
    private final Catalog catalog;
    private final Set<Integer> protectedSteps;
    private final Map<Integer, Long> occupiedSteps;
    private final Map<String, Integer> clientKeys = new HashMap<String, Integer>();
    private final Set<Integer> removedSteps = new LinkedHashSet<Integer>();
    private final List<String> diff = new ArrayList<String>();
    private final List<String> warnings = new ArrayList<String>();
    private final Map<Integer,String> addedSteps = new java.util.LinkedHashMap<>();
    private final Map<Integer,String> addedTransitions = new java.util.LinkedHashMap<>();
    private int nextStep;
    private int nextAction;
    private final Map<Integer, Edge> edgesBefore = new HashMap<Integer, Edge>();
    private final List<WorkflowChange> pendingResolutions = new ArrayList<WorkflowChange>();
    private final List<String> pendingWhere = new ArrayList<String>();

    /** Where a transition starts and leads, as far as resolution rules care, and the rule it carries. */
    private record Edge(boolean global, Boolean sourceDone, boolean targetDone, ResolutionRule rule) {
        boolean sameGraph(Edge o) {
            return global == o.global && java.util.Objects.equals(sourceDone, o.sourceDone)
                    && targetDone == o.targetDone;
        }
    }

    private WorkflowDescriptorPatcher(WorkflowDescriptor d, Set<Integer> protectedSteps,
                                      Map<Integer, Long> occupiedSteps, Catalog catalog) {
        this.d = d;
        this.protectedSteps = protectedSteps;
        this.occupiedSteps = occupiedSteps;
        this.catalog = catalog;
    }

    /**
     * @param d              a private copy of the descriptor; it is changed in place
     * @param protectedSteps step ids that exist in the active version of an active workflow,
     *                       or {@code null} for an inactive workflow
     * @param occupiedSteps  protected steps requested for removal that currently hold issues, with their counts
     * @throws EditFailure the whole change set is refused; {@code d} must then be dropped
     */
    public static Result apply(WorkflowDescriptor d, List<WorkflowChange> changes, Set<Integer> protectedSteps,
                               Map<Integer, Long> occupiedSteps, Catalog catalog) {
        return new WorkflowDescriptorPatcher(d, protectedSteps, occupiedSteps, catalog).run(changes);
    }

    private Result run(List<WorkflowChange> changes) {
        if (changes == null || changes.isEmpty()) throw invalid("'changes' must have at least one change.");
        if (changes.size() > ru.rasulov.workflow.api.WorkflowLimits.MAX_CHANGES) {
            throw invalid("At most " + ru.rasulov.workflow.api.WorkflowLimits.MAX_CHANGES
                    + " changes per call; got " + changes.size() + ".");
        }
        checkShapeAndConflicts(changes);
        boolean validBefore = validates();
        Set<Integer> looseBefore = DescriptorFacts.unreachable(d);
        Set<Integer> deadBefore = DescriptorFacts.deadEnds(d);
        nextStep = maxStep() + 1;
        for (Located l : DescriptorFacts.locate(d).values()) {
            if ((l.kind() == Kind.LOCAL || l.kind() == Kind.GLOBAL) && DescriptorFacts.structureBlocker(l) == null) {
                edgesBefore.put(l.id(), edge(l));
            }
        }
        nextAction = maxAction() + 1;
        if (nextStep <= 0 || nextAction <= 0) throw invalid("The workflow has no free step or action ids left.");

        List<Integer> order = new ArrayList<Integer>();
        for (String op : OPS) {
            for (int i = 0; i < changes.size(); i++) if (op.equals(changes.get(i).getOp())) order.add(i);
        }
        for (int i : order) {
            WorkflowChange c = changes.get(i);
            String where = "changes[" + i + "] " + c.getOp();
            switch (c.getOp()) {
                case "ADD_STATUS": addStatus(c, where); break;
                case "REPLACE_STATUS": replaceStatus(c, where); break;
                case "UPDATE_TRANSITION": updateTransition(c, where); break;
                case "ADD_TRANSITION": addTransition(c, where); break;
                case "REMOVE_TRANSITION": removeTransition(c, where); break;
                case "SET_INITIAL_STATUS": setInitial(c, where); break;
                case "UPDATE_METADATA": updateMetadata(c, where); break;
                default: removeStatus(c, where); break;
            }
        }
        applyPendingResolutions();
        checkResolutionRules();
        finishRemovals();
        checkReferences();
        if (validBefore && !validates()) {
            try {
                d.validate();
            } catch (InvalidWorkflowDescriptorException e) {
                throw new EditFailure(EditErrorCode.VALIDATION_ERROR,
                        "Jira's workflow validation refuses the result: " + e.getMessage());
            }
        }
        for (int id : DescriptorFacts.unreachable(d)) {
            if (!looseBefore.contains(id)) warnings.add("Step " + label(id) + " has no transition leading to it.");
        }
        for (int id : DescriptorFacts.deadEnds(d)) {
            if (!deadBefore.contains(id)) warnings.add("Step " + label(id) + " has no transition out of it.");
        }
        return new Result(diff, warnings, addedSteps, addedTransitions);
    }

    // ---- shape and conflicts ------------------------------------------------------------------------------------

    private void checkShapeAndConflicts(List<WorkflowChange> changes) {
        Set<String> claimed = new HashSet<String>();
        Set<Integer> updated = new HashSet<Integer>();
        Set<Integer> removed = new HashSet<Integer>();
        Set<String> keys = new HashSet<String>();
        Set<String> statuses = new HashSet<String>();
        Set<String> transitionKeys = new HashSet<>();
        boolean initial = false;
        boolean meta = false;
        for (int i = 0; i < changes.size(); i++) {
            WorkflowChange c = changes.get(i);
            String where = "changes[" + i + "]";
            if (c == null || c.getOp() == null || !OPS.contains(c.getOp())) {
                throw invalid(where + ".op must be one of " + OPS + ".");
            }
            where += " " + c.getOp();
            if (c.getTransitionKey() != null) {
                String key = c.getTransitionKey();
                if (!"ADD_TRANSITION".equals(c.getOp()) || key.isEmpty() || key.length() > MAX_NAME
                        || !key.equals(key.trim()) || !transitionKeys.add(key)) {
                    throw invalid(where + ".transitionKey must be unique, non-blank, at most 255 characters, "
                            + "without leading/trailing whitespace, and is allowed only for ADD_TRANSITION.");
                }
            }
            switch (c.getOp()) {
                case "UPDATE_TRANSITION": {
                    int id = requireAction(c, where);
                    if (c.getName() == null && c.getSource() == null && c.getTargetStepId() == null
                            && c.getTargetClientKey() == null && c.getScreen() == null && c.getResolution() == null) {
                        throw invalid(where + " changes nothing: give name, source, target, screen or resolution.");
                    }
                    for (String field : touched(c)) {
                        if (!claimed.add(id + ":" + field)) {
                            throw invalid(where + " changes '" + field + "' of action " + id + " twice.");
                        }
                    }
                    updated.add(id);
                    break;
                }
                case "REMOVE_TRANSITION": {
                    int id = requireAction(c, where);
                    if (!removed.add(id)) throw invalid(where + " removes action " + id + " twice.");
                    break;
                }
                case "ADD_STATUS": {
                    if (c.getStatusId() == null || c.getStatusId().trim().isEmpty()) {
                        throw invalid(where + " needs statusId of an existing status.");
                    }
                    if (!statuses.add(c.getStatusId().trim())) {
                        throw invalid(where + " adds status " + c.getStatusId() + " twice.");
                    }
                    if (c.getClientKey() != null && (c.getClientKey().trim().isEmpty() || !keys.add(c.getClientKey()))) {
                        throw invalid(where + " clientKey must be non-blank and unique in the request.");
                    }
                    break;
                }
                case "REPLACE_STATUS": {
                    if (c.getStepId() == null || c.getStatusId() == null || c.getStatusId().trim().isEmpty()) {
                        throw invalid(where + " needs stepId and the new statusId.");
                    }
                    if (!claimed.add("step:" + c.getStepId())) {
                        throw invalid(where + " conflicts with another change of step " + c.getStepId() + ".");
                    }
                    if (!statuses.add(c.getStatusId().trim())) {
                        throw invalid(where + " uses status " + c.getStatusId() + " twice in the request.");
                    }
                    break;
                }
                case "REMOVE_STATUS": {
                    if (c.getStepId() == null) throw invalid(where + " needs stepId.");
                    if (!claimed.add("step:" + c.getStepId())) {
                        throw invalid(where + " conflicts with another change of step " + c.getStepId() + ".");
                    }
                    break;
                }
                case "SET_INITIAL_STATUS": {
                    if (initial) throw invalid(where + " appears twice.");
                    initial = true;
                    if ((c.getStepId() == null) == (c.getClientKey() == null)) {
                        throw invalid(where + " needs exactly one of stepId and clientKey.");
                    }
                    break;
                }
                case "ADD_TRANSITION":
                    break;
                default: {
                    if (meta) throw invalid(where + " appears twice.");
                    meta = true;
                    if (c.getDescription() == null) throw invalid(where + " needs description.");
                }
            }
        }
        for (int id : removed) {
            if (updated.contains(id)) {
                throw invalid("Action " + id + " is both removed and updated in one request.");
            }
        }
    }

    private static List<String> touched(WorkflowChange c) {
        List<String> fields = new ArrayList<String>();
        if (c.getName() != null) fields.add("name");
        if (c.getSource() != null) fields.add("source");
        if (c.getTargetStepId() != null || c.getTargetClientKey() != null) fields.add("target");
        if (c.getScreen() != null) fields.add("screen");
        if (c.getResolution() != null) fields.add("resolution");
        return fields;
    }

    private static int requireAction(WorkflowChange c, String where) {
        if (c.getActionId() == null) throw invalid(where + " needs actionId.");
        return c.getActionId();
    }

    // ---- statuses -----------------------------------------------------------------------------------------------

    private void addStatus(WorkflowChange c, String where) {
        String statusId = c.getStatusId().trim();
        StatusInfo status = requireStatus(statusId, where);
        if (stepOfStatus(statusId) != null) {
            throw invalid(where + ": status '" + status.name() + "' is already a step of this workflow.");
        }
        StepDescriptor step = DescriptorFactory.getFactory().createStepDescriptor();
        step.setId(nextStep++);
        step.setName(status.name());
        step.setParent(d);
        Map<String, String> meta = new HashMap<String, String>();
        meta.put(DescriptorFacts.STATUS_META, statusId);
        step.setMetaAttributes(meta);
        d.addStep(step);
        if (c.getClientKey() != null) clientKeys.put(c.getClientKey(), step.getId());
        addedSteps.put(step.getId(), c.getClientKey());
        diff.add(addedStatusDiff(step.getId(),status.name(),statusId));
    }

    static String addedStatusDiff(int stepId,String name,String statusId) {
        return "ADD_STATUS: new step " + stepId + " linked to status '" + name + "' (" + statusId
                + "), no transitions yet";
    }

    private void replaceStatus(WorkflowChange c, String where) {
        StepDescriptor step = requireStep(c.getStepId(), where);
        guardExistingStep(step.getId(), where);
        String statusId = c.getStatusId().trim();
        StatusInfo status = requireStatus(statusId, where);
        StepDescriptor holder = stepOfStatus(statusId);
        if (holder != null && holder != step) {
            throw invalid(where + ": status '" + status.name() + "' is already step " + holder.getId() + ".");
        }
        String old = DescriptorFacts.statusId(step);
        if (statusId.equals(old)) return;
        diff.add("REPLACE_STATUS: step " + step.getId() + " status " + old + " ('" + step.getName() + "') -> "
                + statusId + " ('" + status.name() + "'); the step id and its transitions stay");
        DescriptorFacts.meta(step).put(DescriptorFacts.STATUS_META, statusId);
        step.setName(status.name());
    }

    private void removeStatus(WorkflowChange c, String where) {
        StepDescriptor step = requireStep(c.getStepId(), where);
        Long occupied = occupiedSteps.get(step.getId());
        if (protectedSteps != null && protectedSteps.contains(step.getId()) && occupied != null && occupied > 0) {
            throw new EditFailure(EditErrorCode.MIGRATION_REQUIRED, where + ": step " + step.getId()
                    + " has " + occupied + " issue(s); Jira will not publish a draft without it. Use copy_workflow, "
                    + "edit the inactive copy, then assign_workflow with statusMappings. Alternatively, move those "
                    + "issues before removing this step.");
        }
        if (!DescriptorFacts.commonRefs(step).isEmpty()) {
            throw new EditFailure(EditErrorCode.UNSUPPORTED_DESCRIPTOR, where + ": step " + step.getId()
                    + " uses common actions " + DescriptorFacts.commonRefs(step) + "; they cannot be detached here.");
        }
        removedSteps.add(step.getId());
    }

    private void guardExistingStep(int stepId, String where) {
        if (protectedSteps != null && protectedSteps.contains(stepId)) {
            throw new EditFailure(EditErrorCode.MIGRATION_REQUIRED, where + ": step " + stepId
                    + " belongs to the active version. Jira refuses to publish a draft that links an existing "
                    + "step to another status, even with no issues. Use ADD_STATUS for the new status, move the "
                    + "transitions and REMOVE_STATUS for the empty step; or use copy_workflow, configure_workflow "
                    + "on the copy, then assign_workflow with statusMappings.");
        }
    }

    private void finishRemovals() {
        for (int id : removedSteps) {
            StepDescriptor step = DescriptorFacts.step(d, id);
            List<ActionDescriptor> own = DescriptorFacts.actions(step);
            if (!own.isEmpty()) {
                List<Integer> ids = new ArrayList<Integer>();
                for (ActionDescriptor a : own) ids.add(a.getId());
                throw invalid("REMOVE_STATUS step " + id + " still has outgoing transitions " + ids
                        + ". Remove them explicitly with REMOVE_TRANSITION; nothing is removed by cascade.");
            }
            List<Integer> incoming = new ArrayList<Integer>();
            for (Located l : DescriptorFacts.locate(d).values()) {
                if (DescriptorFacts.targets(l.action()).contains(id)) incoming.add(l.id());
            }
            if (!incoming.isEmpty()) {
                throw invalid("REMOVE_STATUS step " + id + " is still the target of transitions " + incoming
                        + ". Remove them or point them elsewhere in the same request"
                        + (incoming.contains(initialId()) ? " (the initial action needs SET_INITIAL_STATUS)" : "")
                        + "; nothing is removed by cascade.");
            }
            DescriptorFacts.steps(d).remove(step);
            diff.add("REMOVE_STATUS: step " + id + " ('" + step.getName() + "') removed from the graph; the status "
                    + "itself still exists in Jira");
        }
    }

    private int initialId() {
        List<ActionDescriptor> initial = DescriptorFacts.initialActions(d);
        return initial.size() == 1 ? initial.get(0).getId() : Integer.MIN_VALUE;
    }

    // ---- transitions --------------------------------------------------------------------------------------------

    private void updateTransition(WorkflowChange c, String where) {
        Located l = requireAction(c.getActionId(), where);
        if (l.duplicated() || l.kind() == Kind.INITIAL) {
            throw new EditFailure(EditErrorCode.UNSUPPORTED_DESCRIPTOR,
                    where + ": action " + l.id() + ": " + DescriptorFacts.structureBlocker(l) + ".");
        }
        ActionDescriptor action = l.action();
        String label = "action " + l.id() + " '" + action.getName() + "'";
        boolean structural = c.getSource() != null || c.getTargetStepId() != null || c.getTargetClientKey() != null
                || c.getResolution() != null;
        String blocker = structural ? DescriptorFacts.structureBlocker(l) : null;
        if (blocker != null) {
            throw new EditFailure(EditErrorCode.UNSUPPORTED_DESCRIPTOR, where + ": " + label
                    + " cannot change source, target or resolution: " + blocker + ".");
        }
        if (c.getName() != null) {
            String name = requireName(c.getName(), where);
            if (!name.equals(action.getName())) {
                diff.add("UPDATE_TRANSITION: " + label + " name '" + action.getName() + "' -> '" + name + "'");
                action.setName(name);
            }
        }
        if (c.getScreen() != null) applyScreen(action, c.getScreen(), label, where);

        if (c.getSource() != null) {
            Integer to = sourceStep(c.getSource(), where);
            boolean wantGlobal = to == null;
            boolean oldGlobal = l.kind() == Kind.GLOBAL;
            int oldSource = l.sources().isEmpty() ? -1 : l.sources().get(0);
            if (wantGlobal != oldGlobal || (to != null && to != oldSource)) {
                move(l, wantGlobal ? null : DescriptorFacts.step(d, to));
                diff.add("UPDATE_TRANSITION: " + label + " source " + (oldGlobal ? "any status" : "step " + oldSource)
                        + " -> " + (wantGlobal ? "any status (global)" : "step " + to));
            }
        }
        if (c.getTargetStepId() != null || c.getTargetClientKey() != null) {
            int target = stepRef(c.getTargetStepId(), c.getTargetClientKey(), where + " target");
            int old = action.getUnconditionalResult().getStep();
            if (target != old) {
                diff.add("UPDATE_TRANSITION: " + label + " target step " + old + " -> step " + target);
                action.getUnconditionalResult().setStep(target);
            }
        }
        if (c.getResolution() != null) {
            pendingResolutions.add(c);
            pendingWhere.add(where);
        }
    }

    private Edge edge(Located l) {
        boolean global = l.kind() == Kind.GLOBAL;
        Boolean sourceDone = global ? null : doneOf(l.sources().isEmpty() ? null : l.sources().get(0));
        boolean targetDone = Boolean.TRUE.equals(doneOf(l.action().getUnconditionalResult().getStep()));
        return new Edge(global, sourceDone, targetDone, DescriptorFacts.rule(l.action()));
    }

    /** Resolution changes run after every structural change, so the order of the list never matters. */
    private void applyPendingResolutions() {
        for (int i = 0; i < pendingResolutions.size(); i++) {
            WorkflowChange c = pendingResolutions.get(i);
            Located l = DescriptorFacts.locate(d).get(c.getActionId());
            Edge e = edge(l);
            applyResolution(l.action(), c.getResolution(), e.rule(), e.global(), e.sourceDone(), e.targetDone(),
                    "action " + l.id() + " '" + l.action().getName() + "'", pendingWhere.get(i));
        }
    }

    /**
     * After all changes: a transition whose source or target (or the category of either status) changed must still fit
     * its resolution rule. A rule that did not fit before is not blamed on the edit, and nothing is inserted silently.
     */
    private void checkResolutionRules() {
        Map<Integer, Located> now = DescriptorFacts.locate(d);
        for (Map.Entry<Integer, Edge> entry : edgesBefore.entrySet()) {
            Located l = now.get(entry.getKey());
            if (l == null || DescriptorFacts.structureBlocker(l) != null) continue;
            Edge before = entry.getValue();
            Edge after = edge(l);
            if (before.sameGraph(after)) continue;
            String label = "action " + l.id() + " '" + l.action().getName() + "'";
            if ("UNKNOWN".equals(after.rule().action())) {
                warnings.add(label + ": its resolution rule is UNKNOWN (" + after.rule().reason() + "); whether it "
                        + "fits the new source, target or status category was not checked.");
                continue;
            }
            String was = incompatible(before.rule().action(), before.global(), before.sourceDone(), before.targetDone());
            String is = incompatible(after.rule().action(), after.global(), after.sourceDone(), after.targetDone());
            if (is != null && was == null) {
                throw invalid(label + " keeps resolution rule " + after.rule().action() + " but, after the changes, it "
                        + is + ". Decide explicitly: add an UPDATE_TRANSITION with resolution SET, CLEAR or PRESERVE "
                        + "for action " + l.id() + " and preview again; nothing is inserted automatically.");
            }
        }
    }

    /**
     * A transition screen is the meta and the action's {@code view="fieldscreen"} together: with the meta alone Jira
     * shows "Screen: None" and never opens the screen. SET writes both (replacing another view) and repairs a meta
     * that lacks the view; CLEAR removes both and leaves another view alone.
     */
    private void applyScreen(ActionDescriptor action, ScreenPatch patch, String label, String where) {
        if ("CLEAR".equals(patch.getAction())) {
            if (patch.getScreenId() != null) throw invalid(where + ".screen.screenId is forbidden for CLEAR.");
        } else if ("SET".equals(patch.getAction())) {
            if (patch.getScreenId() == null) throw invalid(where + ".screen SET needs screenId.");
            if (!catalog.screenExists(patch.getScreenId())) {
                throw new EditFailure(EditErrorCode.UNKNOWN_SCREEN, where + ": no screen has id "
                        + patch.getScreenId() + ".");
            }
        } else {
            throw invalid(where + ".screen.action must be SET or CLEAR.");
        }
        applyScreen(action, patch.getScreenId() == null ? null : String.valueOf(patch.getScreenId()), label, diff);
    }

    /** Shared descriptor mutation and truthful diff, after the caller has resolved/validated the screen reference. */
    static void applyScreen(ActionDescriptor action, String value, String label, List<String> diff) {
        String current = DescriptorFacts.meta(action).get(DescriptorFacts.SCREEN_META);
        String view = action.getView();
        boolean screenView = DescriptorFacts.SCREEN_VIEW.equals(view);
        if (value == null) {
            if (current != null || screenView) {
                DescriptorFacts.meta(action).remove(DescriptorFacts.SCREEN_META);
                if (screenView) action.setView(null);
                diff.add("UPDATE_TRANSITION: " + label + " screen " + (current == null ? "none" : current)
                        + " -> none" + (screenView ? " (view " + DescriptorFacts.SCREEN_VIEW + " removed)" : ""));
            }
        } else {
            boolean sameScreen = value.equals(current);
            if (!sameScreen || !screenView) {
                DescriptorFacts.meta(action).put(DescriptorFacts.SCREEN_META, value);
                action.setView(DescriptorFacts.SCREEN_VIEW);
                diff.add("SET_SCREEN: " + label + " screen " + (current == null ? "none" : current)
                        + " -> " + value + "; view '" + (view == null || view.isEmpty() ? "none" : view)
                        + "' -> " + DescriptorFacts.SCREEN_VIEW);
            }
        }
    }

    private void applyResolution(ActionDescriptor action, TransitionResolution r, ResolutionRule current,
                                 boolean global, Boolean sourceDone, boolean targetDone, String label, String where) {
        if ("UNKNOWN".equals(current.action())) {
            throw new EditFailure(EditErrorCode.UNSUPPORTED_DESCRIPTOR, where + ": " + label + " has a resolution "
                    + "rule that cannot be recognised (" + current.reason() + "); it is not changed.");
        }
        String requested = r.getAction();
        ResultDescriptor result = action.getUnconditionalResult();
        List<FunctionDescriptor> functions = DescriptorFacts.postFunctions(result);
        FunctionDescriptor writer = null;
        for (FunctionDescriptor f : functions) if (DescriptorFacts.writesResolution(f)) writer = f;
        String wantValue;
        if ("SET".equals(requested)) {
            String id = r.getValue();
            if (id == null || id.isEmpty() || !id.equals(id.trim())) {
                throw invalid(where + ".resolution.value must be a canonical resolution id from get_resolutions.");
            }
            ResolutionInfo info = catalog.resolution(id);
            if (info == null) {
                throw new EditFailure(EditErrorCode.UNKNOWN_RESOLUTION, where + ": '" + id
                        + "' is not an existing canonical resolution id.");
            }
            if (!targetDone) throw invalid(where + ": SET is only allowed when the transition enters a DONE status.");
            wantValue = id;
        } else if ("CLEAR".equals(requested)) {
            if (r.getValue() != null) throw invalid(where + ".resolution.value is forbidden for CLEAR.");
            if (targetDone) throw invalid(where + ": CLEAR is not allowed when the transition enters a DONE status.");
            wantValue = "null";
        } else if ("PRESERVE".equals(requested)) {
            if (r.getValue() != null) throw invalid(where + ".resolution.value is forbidden for PRESERVE.");
            if (targetDone && !Boolean.TRUE.equals(sourceDone)) {
                throw invalid(where + ": PRESERVE is not allowed when the transition enters a DONE status from "
                        + "another category; use SET.");
            }
            if (writer != null) {
                functions.remove(writer);
                diff.add("UPDATE_TRANSITION: " + label + " resolution " + describe(current) + " -> PRESERVE (the "
                        + "standard resolution writer is removed; issues keep whatever resolution they have)");
            }
            return;
        } else {
            throw invalid(where + ".resolution.action must be SET, CLEAR or PRESERVE.");
        }
        if (writer != null) {
            if (wantValue.equals(String.valueOf(writer.getArgs().get("field.value")).trim())) return;
            writer.getArgs().put("field.value", wantValue);
        } else {
            FunctionDescriptor created = DescriptorFactory.getFactory().createFunctionDescriptor();
            created.setType("class");
            created.setParent(result);
            created.getArgs().put("class.name", DescriptorFacts.UPDATE_FIELD_FUNCTION);
            created.getArgs().put("field.name", "resolution");
            created.getArgs().put("field.value", wantValue);
            int at = functions.size();
            for (int i = 0; i < functions.size(); i++) {
                if (BEFORE_HISTORY.contains(DescriptorFacts.cls(functions.get(i)))) { at = i; break; }
            }
            functions.add(at, created);
        }
        diff.add("UPDATE_TRANSITION: " + label + " resolution " + describe(current) + " -> "
                + ("null".equals(wantValue) ? "CLEAR" : "SET " + wantValue + " (" + catalog.resolution(wantValue).name()
                + ")") + "; applies to future executions only, existing issues are not changed");
    }

    private static String describe(ResolutionRule rule) {
        return "SET".equals(rule.action()) ? "SET " + rule.value() : rule.action();
    }

    /** Why a rule does not fit an edge (null if it fits); new-transition rules match configure_workflow. */
    private static String incompatible(String rule, boolean global, Boolean sourceDone, boolean targetDone) {
        if (targetDone) {
            return !"SET".equals(rule) && !Boolean.TRUE.equals(sourceDone) ? "enters a DONE status without SET" : null;
        }
        if ("SET".equals(rule)) return "is not a DONE status, where SET is meaningless";
        if ("PRESERVE".equals(rule) && (global || Boolean.TRUE.equals(sourceDone))) {
            return "leaves DONE (or starts anywhere) and needs CLEAR";
        }
        return null;
    }

    private void addTransition(WorkflowChange c, String where) {
        String name = requireName(c.getName(), where);
        if (c.getSource() == null) throw invalid(where + " needs source.");
        if (c.getTargetStepId() == null && c.getTargetClientKey() == null) {
            throw invalid(where + " needs targetStepId or targetClientKey.");
        }
        Integer from = sourceStep(c.getSource(), where);
        int target = stepRef(c.getTargetStepId(), c.getTargetClientKey(), where + " target");
        boolean global = from == null;
        Boolean sourceDone = global ? null : doneOf(from);
        boolean targetDone = Boolean.TRUE.equals(doneOf(target));
        boolean mustSet = targetDone && (global || from != target);
        boolean mustClear = !targetDone && (global || Boolean.TRUE.equals(sourceDone));
        TransitionResolution r = c.getResolution();
        if (r == null) {
            if (mustSet) throw invalid(where + ".resolution requires SET with a resolution id from get_resolutions.");
            r = new TransitionResolution(mustClear ? "CLEAR" : "PRESERVE", null);
        } else if ("CLEAR".equals(r.getAction()) && targetDone) {
            throw invalid(where + ": CLEAR is not allowed when the transition enters a DONE status.");
        }
        if (nextAction <= 0 || nextAction >= Integer.MAX_VALUE - 1) throw invalid(where + ": no free action id.");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE workflow PUBLIC "
                + "\"-//OpenSymphony Group//DTD OSWorkflow 2.8//EN\" "
                + "\"http://www.opensymphony.com/osworkflow/workflow_2_8.dtd\">\n<workflow><initial-actions>"
                + "<action id=\"" + (nextAction + 1) + "\" name=\"x\"><results><unconditional-result old-status=\"null\" status=\"open\" "
                + "step=\"1\"/></results></action></initial-actions><steps><step id=\"1\" name=\"x\"><actions>"
                + "<action id=\"" + nextAction + "\" name=\"" + escape(name) + "\"><meta name=\"jira.description\">"
                + "</meta><results><unconditional-result old-status=\"Not Done\" status=\"Done\" step=\"" + target
                + "\"><post-functions>"
                + function("com.atlassian.jira.plugin.system.workflowupdateissuestatus-function",
                "com.atlassian.jira.workflow.function.issue.UpdateIssueStatusFunction", null)
                + function("com.atlassian.jira.plugin.system.workflowcreatecomment-function",
                "com.atlassian.jira.workflow.function.misc.CreateCommentFunction", null)
                + function("com.atlassian.jira.plugin.system.workflowgeneratechangehistory-function",
                "com.atlassian.jira.workflow.function.issue.GenerateChangeHistoryFunction", null)
                + function("com.atlassian.jira.plugin.system.workflowreindexissue-function",
                "com.atlassian.jira.workflow.function.issue.IssueReindexFunction", null)
                + function("com.atlassian.jira.plugin.system.workflowfireevent-function",
                "com.atlassian.jira.workflow.function.event.FireIssueEventFunction", 13)
                + "</post-functions></unconditional-result></results></action></actions></step></steps></workflow>";
        ActionDescriptor action;
        try {
            WorkflowDescriptor parsed = com.atlassian.jira.workflow.WorkflowUtil.convertXMLtoWorkflowDescriptor(xml);
            action = DescriptorFacts.actions(DescriptorFacts.steps(parsed).get(0)).get(0);
            DescriptorFacts.actions(DescriptorFacts.steps(parsed).get(0)).clear();
        } catch (Exception e) {
            throw new EditFailure(EditErrorCode.JIRA_ERROR, "Could not build the new transition: " + e.getMessage());
        }
        if (!"PRESERVE".equals(r.getAction())) {
            TransitionResolution wanted = r;
            applyResolution(action, wanted, new ResolutionRule("PRESERVE", null, null), global, sourceDone,
                    targetDone, "new action " + nextAction, where);
            diff.remove(diff.size() - 1);
        } else if (mustSet) {
            throw invalid(where + ".resolution requires SET for a transition that enters DONE.");
        }
        if (c.getScreen() != null) applyScreen(action, c.getScreen(), "new action " + nextAction, where);
        int id = nextAction++;
        if (global) {
            action.setParent(d);
            DescriptorFacts.globalActions(d).add(action);
        } else {
            StepDescriptor step = DescriptorFacts.step(d, from);
            action.setParent(step);
            DescriptorFacts.actions(step).add(action);
        }
        addedTransitions.put(id, c.getTransitionKey());
        diff.add("ADD_TRANSITION: new action " + id + " '" + name + "' " + (global ? "from any status"
                : "from step " + from) + " to step " + target + ", resolution "
                + ("SET".equals(r.getAction()) ? "SET " + r.getValue() : r.getAction()));
    }

    private static String function(String moduleKey, String className, Integer eventTypeId) {
        return "<function type=\"class\">" + (eventTypeId == null ? "" : "<arg name=\"eventTypeId\">" + eventTypeId
                + "</arg>") + "<arg name=\"full.module.key\">" + moduleKey + "</arg><arg name=\"class.name\">"
                + className + "</arg></function>";
    }

    private void removeTransition(WorkflowChange c, String where) {
        Located l = requireAction(c.getActionId(), where);
        String blocker = DescriptorFacts.structureBlocker(l);
        if (l.duplicated() || l.kind() == Kind.INITIAL || l.kind() == Kind.COMMON) {
            throw new EditFailure(EditErrorCode.UNSUPPORTED_DESCRIPTOR, where + ": action " + l.id() + " cannot be "
                    + "removed: " + blocker + ".");
        }
        if (l.kind() == Kind.GLOBAL) {
            DescriptorFacts.globalActions(d).remove(l.action());
        } else {
            DescriptorFacts.actions(DescriptorFacts.step(d, l.sources().get(0))).remove(l.action());
        }
        diff.add("REMOVE_TRANSITION: action " + l.id() + " '" + l.action().getName() + "' ("
                + (l.kind() == Kind.GLOBAL ? "global" : "from step " + l.sources().get(0)) + ") with its conditions, "
                + "validators and functions");
    }

    private void setInitial(WorkflowChange c, String where) {
        int target = stepRef(c.getStepId(), c.getClientKey(), where);
        List<ActionDescriptor> initial = DescriptorFacts.initialActions(d);
        if (initial.size() != 1) {
            throw new EditFailure(EditErrorCode.UNSUPPORTED_DESCRIPTOR, where + ": the workflow has "
                    + initial.size() + " initial actions; only one can be retargeted.");
        }
        ActionDescriptor action = initial.get(0);
        if (DescriptorFacts.hasConditionalResults(action) || action.getUnconditionalResult() == null
                || DescriptorFacts.hasJoinOrSplit(action)) {
            throw new EditFailure(EditErrorCode.UNSUPPORTED_DESCRIPTOR, where + ": the initial action has "
                    + "conditional results or a join/split.");
        }
        int old = action.getUnconditionalResult().getStep();
        if (old == target) return;
        action.getUnconditionalResult().setStep(target);
        diff.add("SET_INITIAL_STATUS: initial action " + action.getId() + " step " + old + " -> step " + target
                + "; its validators and functions stay");
        warnings.add("Existing issues are not moved: only new issues start in step " + target + ".");
    }

    private void updateMetadata(WorkflowChange c, String where) {
        String current = DescriptorFacts.meta(d).get(DescriptorFacts.DESCRIPTION_META);
        String value = c.getDescription();
        if (value.length() > 4000) throw invalid(where + ": description is longer than 4000 characters.");
        if (value.equals(current == null ? "" : current)) return;
        DescriptorFacts.meta(d).put(DescriptorFacts.DESCRIPTION_META, value);
        diff.add("UPDATE_METADATA: description '" + (current == null ? "" : current) + "' -> '" + value + "'");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private Located requireAction(Integer actionId, String where) {
        Located l = DescriptorFacts.locate(d).get(actionId);
        if (l == null) {
            throw new EditFailure(EditErrorCode.UNKNOWN_TRANSITION, where + ": the workflow has no action with id "
                    + actionId + ".");
        }
        return l;
    }

    private StepDescriptor requireStep(Integer stepId, String where) {
        StepDescriptor step = stepId == null ? null : DescriptorFacts.step(d, stepId);
        if (step == null || removedSteps.contains(stepId)) {
            throw new EditFailure(EditErrorCode.UNKNOWN_STEP, where + ": the workflow has no step with id "
                    + stepId + ".");
        }
        return step;
    }

    /** The step id of a stepId/clientKey reference, checked to exist and not to be removed. */
    private int stepRef(Integer stepId, String clientKey, String where) {
        if ((stepId == null) == (clientKey == null)) {
            throw invalid(where + ": give exactly one of stepId and clientKey.");
        }
        if (clientKey != null) {
            Integer id = clientKeys.get(clientKey);
            if (id == null) {
                throw new EditFailure(EditErrorCode.UNKNOWN_STEP, where + ": clientKey '" + clientKey
                        + "' is not the clientKey of an ADD_STATUS in this request.");
            }
            return id;
        }
        if (removedSteps.contains(stepId)) {
            throw invalid(where + ": step " + stepId + " is removed by this request.");
        }
        return requireStep(stepId, where).getId();
    }

    /** @return the step id, or {@code null} for "from any status" */
    private Integer sourceStep(TransitionSource s, String where) {
        boolean any = Boolean.TRUE.equals(s.getFromAny());
        if (Boolean.FALSE.equals(s.getFromAny()) && s.getStepId() == null && s.getClientKey() == null) {
            throw invalid(where + ".source: fromAny=false needs a stepId or clientKey.");
        }
        if (any) {
            if (s.getStepId() != null || s.getClientKey() != null) {
                throw invalid(where + ".source: fromAny excludes stepId and clientKey.");
            }
            return null;
        }
        return stepRef(s.getStepId(), s.getClientKey(), where + ".source");
    }

    /** Moves a local or global action to a step (or to the global actions when {@code to} is null). */
    private void move(Located l, StepDescriptor to) {
        if (l.kind() == Kind.GLOBAL) {
            DescriptorFacts.globalActions(d).remove(l.action());
        } else {
            DescriptorFacts.actions(DescriptorFacts.step(d, l.sources().get(0))).remove(l.action());
        }
        if (to == null) {
            l.action().setParent(d);
            DescriptorFacts.globalActions(d).add(l.action());
        } else {
            l.action().setParent(to);
            DescriptorFacts.actions(to).add(l.action());
        }
    }

    private StatusInfo requireStatus(String statusId, String where) {
        StatusInfo status = catalog.status(statusId);
        if (status == null) {
            throw new EditFailure(EditErrorCode.UNKNOWN_STATUS, where + ": no status has id '" + statusId
                    + "'. Use list_statuses.");
        }
        return status;
    }

    private StepDescriptor stepOfStatus(String statusId) {
        for (StepDescriptor step : DescriptorFacts.steps(d)) {
            if (statusId.equals(DescriptorFacts.statusId(step))) return step;
        }
        return null;
    }

    /** TRUE/FALSE for a step whose status category is known, null when unknown. */
    private Boolean doneOf(Integer stepId) {
        StepDescriptor step = stepId == null ? null : DescriptorFacts.step(d, stepId);
        String statusId = step == null ? null : DescriptorFacts.statusId(step);
        StatusInfo status = statusId == null ? null : catalog.status(statusId);
        return status == null || status.categoryKey() == null ? null : "done".equals(status.categoryKey());
    }

    private String label(int stepId) {
        StepDescriptor step = DescriptorFacts.step(d, stepId);
        return stepId + (step == null ? "" : " ('" + step.getName() + "')");
    }

    private int maxStep() {
        int max = 0;
        for (StepDescriptor s : DescriptorFacts.steps(d)) max = Math.max(max, s.getId());
        return max;
    }

    private int maxAction() {
        int max = 0;
        for (int id : DescriptorFacts.locate(d).keySet()) max = Math.max(max, id);
        return max;
    }

    private boolean validates() {
        try {
            d.validate();
            return true;
        } catch (InvalidWorkflowDescriptorException | RuntimeException e) {
            return false;
        }
    }

    private void checkReferences() {
        Set<Integer> ids = new TreeSet<Integer>();
        for (StepDescriptor s : DescriptorFacts.steps(d)) ids.add(s.getId());
        for (Located l : DescriptorFacts.locate(d).values()) {
            for (int target : DescriptorFacts.targets(l.action())) {
                if (target > 0 && !ids.contains(target)) {
                    throw new EditFailure(EditErrorCode.UNKNOWN_STEP, "Action " + l.id() + " would lead to step "
                            + target + ", which does not exist after the changes.");
                }
            }
        }
    }

    private static String requireName(String name, String where) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw invalid(where + ": name is blank.");
        if (trimmed.length() > MAX_NAME) throw invalid(where + ": name is longer than " + MAX_NAME + " characters.");
        return trimmed;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static EditFailure invalid(String message) {
        return new EditFailure(EditErrorCode.VALIDATION_ERROR, message);
    }
}
