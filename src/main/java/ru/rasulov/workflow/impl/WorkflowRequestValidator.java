package ru.rasulov.workflow.impl;

import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.workflow.api.dto.TransitionResolution;
import com.atlassian.jira.user.ApplicationUser;
import ru.rasulov.workflow.api.WorkflowLimits;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import ru.rasulov.workflow.api.dto.StatusCategoryKey;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.WorkflowErrorCode;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowStatus;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowTransition;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ru.rasulov.common.impl.Normalizer.normalize;

/**
 * The rules of a request, in one order: name, statuses, initial status, transitions, then warnings. Writes nothing to
 * Jira, so a dry run and a real creation run the same code and cannot disagree.
 */
@Named
public class WorkflowRequestValidator {

    private static final String CATEGORIES = "TO_DO, IN_PROGRESS, DONE";

    private final StatusCatalog statusCatalog;
    private final TransitionScreenCatalog screenCatalog;

    private final ConstantsManager constants;

    @Inject
    public WorkflowRequestValidator(StatusCatalog statusCatalog, TransitionScreenCatalog screenCatalog,
                                    @ComponentImport ConstantsManager constants) {
        this.statusCatalog = statusCatalog;
        this.screenCatalog = screenCatalog;
        this.constants = constants;
    }

    /** @throws WorkflowFailureException the workflow cannot be created as requested */
    public ResolvedWorkflow validate(WorkflowDefinition request, ApplicationUser user) {
        String name = checkName("name", request.getName(), WorkflowLimits.MAX_NAME_LENGTH);
        checkXmlText("description", request.getDescription());
        String description = request.getDescription() == null ? "" : request.getDescription().trim();

        List<String> warnings = new ArrayList<String>();
        Map<String, WorkflowStatus> statuses = resolveStatuses(request.getStatuses(), user, warnings);
        WorkflowStatus initial = resolveInitial(request.getInitialStatus(), statuses);
        List<WorkflowTransition> transitions = resolveTransitions(request.getTransitions(), statuses);

        warnings.addAll(reachabilityWarnings(new ArrayList<WorkflowStatus>(statuses.values()), initial, transitions));
        warnings.addAll(redundancyWarnings(transitions));
        return new ResolvedWorkflow(name, description, new ArrayList<WorkflowStatus>(statuses.values()), initial,
                transitions, warnings);
    }

    private Map<String, WorkflowStatus> resolveStatuses(List<StatusDefinition> definitions, ApplicationUser user,
                                                        List<String> warnings) {
        if (definitions.size() < WorkflowLimits.MIN_STATUSES) {
            throw invalid("'statuses' has " + definitions.size() + " element(s); a workflow needs at least "
                    + WorkflowLimits.MIN_STATUSES + ": the initial status and a status to move to.");
        }
        if (definitions.size() > WorkflowLimits.MAX_STATUSES) {
            throw invalid("'statuses' has " + definitions.size() + " elements; the maximum is "
                    + WorkflowLimits.MAX_STATUSES + ". Split the process or drop statuses the user does not need.");
        }
        Map<String, WorkflowStatus> resolved = new LinkedHashMap<String, WorkflowStatus>();
        for (int i = 0; i < definitions.size(); i++) {
            StatusDefinition definition = definitions.get(i);
            if (definition == null) {
                throw invalid("statuses[" + i + "] is empty. Give every status a name.");
            }
            String name = checkName("statuses[" + i + "].name", definition.getName(), WorkflowLimits.MAX_STATUS_NAME_LENGTH);
            WorkflowStatus status = resolveStatus(name, definition, user, warnings);
            if (resolved.put(normalize(status.getName()), status) != null) {
                throw invalid("Status '" + status.getName() + "' is listed more than once in 'statuses' "
                        + "(names are compared without case). List each status once.");
            }
        }
        return resolved;
    }

    private WorkflowStatus resolveStatus(String name, StatusDefinition definition, ApplicationUser user,
                                         List<String> warnings) {
        return resolveStatus(name, definition, user, warnings, statusCatalog.find(name));
    }

    /** Existing-workflow intent: unlike create, category is mandatory even when reusing a status. */
    public WorkflowStatus resolveExistingStatus(StatusDefinition definition, ApplicationUser user, List<String> warnings) {
        String name = checkName("newStatus.name", definition.getName(), WorkflowLimits.MAX_STATUS_NAME_LENGTH);
        if (StatusCategoryKey.parse(definition.getCategory()) == null)
            throw invalid("newStatus.category is required and must be one of " + CATEGORIES + ".");
        List<Status> candidates = statusCatalog.findAll(name);
        if (candidates.size() > 1) {
            List<String> choices = new ArrayList<>();
            for (Status candidate : candidates)
                choices.add(candidate.getId() + ": " + candidate.getName() + " (" + StatusCatalog.categoryOf(candidate) + ")");
            throw new WorkflowFailureException(WorkflowErrorCode.AMBIGUOUS_STATUS,
                    "newStatus.name '" + name + "' matches multiple global statuses: " + String.join(", ", choices) + ". Choose statusId.", choices);
        }
        return resolveStatus(name, definition, user, warnings, candidates.isEmpty() ? null : candidates.get(0));
    }

    private WorkflowStatus resolveStatus(String name, StatusDefinition definition, ApplicationUser user,
                                         List<String> warnings, Status existing) {
        String requested = definition.getCategory() == null ? "" : definition.getCategory().trim();
        if (existing != null) {
            String actual = StatusCatalog.categoryOf(existing);
            StatusCategoryKey wanted = StatusCategoryKey.parse(requested);
            if (wanted != null && !wanted.name().equals(actual)) {
                warnings.add("Status '" + existing.getName() + "' already exists in category " + actual
                        + "; the category " + wanted.name() + " from the request was ignored.");
            }
            return new WorkflowStatus(existing.getName(), existing, actual, null);
        }
        if (requested.isEmpty()) {
            throw new WorkflowFailureException(WorkflowErrorCode.CATEGORY_REQUIRED,
                    "Status '" + name + "' does not exist in Jira, so it needs a category: " + CATEGORIES
                            + ". Do not guess: ask the user which category the status belongs to (not started / being worked on / finished).",
                    categoryNames());
        }
        StatusCategoryKey category = StatusCategoryKey.parse(requested);
        if (category == null) {
            throw new WorkflowFailureException(WorkflowErrorCode.VALIDATION_ERROR,
                    "Category '" + requested + "' of status '" + name + "' is not known. Use one of " + CATEGORIES + ".",
                    categoryNames());
        }
        checkXmlText("description of status '" + name + "'", definition.getDescription());
        String description = definition.getDescription() == null ? "" : definition.getDescription().trim();
        String problems = statusCatalog.creationProblems(user, name, description, category);
        if (problems != null) {
            throw invalid("Jira would not accept the new status '" + name + "': " + problems + ".");
        }
        return new WorkflowStatus(name, null, category.name(), description);
    }

    private static WorkflowStatus resolveInitial(String initialStatus, Map<String, WorkflowStatus> statuses) {
        if (initialStatus == null || initialStatus.trim().isEmpty()) {
            throw invalid("'initialStatus' is required: the status a new issue starts in. Choose one of 'statuses'.");
        }
        WorkflowStatus initial = statuses.get(normalize(initialStatus));
        if (initial == null) {
            throw unknownStatus("initialStatus", initialStatus, statuses);
        }
        return initial;
    }

    private List<WorkflowTransition> resolveTransitions(List<TransitionDefinition> definitions,
                                                        Map<String, WorkflowStatus> statuses) {
        if (definitions.isEmpty()) {
            throw invalid("'transitions' is empty; a workflow without transitions can never leave its initial status.");
        }
        if (definitions.size() > WorkflowLimits.MAX_TRANSITIONS) {
            throw invalid("'transitions' has " + definitions.size() + " elements; the maximum is "
                    + WorkflowLimits.MAX_TRANSITIONS + ".");
        }
        List<WorkflowTransition> resolved = new ArrayList<WorkflowTransition>(definitions.size());
        Set<TransitionKey> seen = new HashSet<TransitionKey>();
        for (int i = 0; i < definitions.size(); i++) {
            TransitionDefinition definition = definitions.get(i);
            String where = "transitions[" + i + "]";
            if (definition == null) {
                throw invalid(where + " is empty. Give every transition a name, a target and a source.");
            }
            String name = checkName(where + ".name", definition.getName(), WorkflowLimits.MAX_NAME_LENGTH);
            boolean hasFrom = definition.getFrom() != null && !definition.getFrom().trim().isEmpty();
            if (hasFrom == definition.isFromAny()) {
                throw invalid(where + " ('" + name + "') must have exactly one of 'from' (a status) and "
                        + "'fromAny': true (available from every status).");
            }
            WorkflowStatus from = null;
            if (hasFrom) {
                from = statuses.get(normalize(definition.getFrom()));
                if (from == null) {
                    throw unknownStatus(where + ".from", definition.getFrom(), statuses);
                }
            }
            WorkflowStatus to = definition.getTo() == null ? null : statuses.get(normalize(definition.getTo()));
            if (to == null) {
                throw unknownStatus(where + ".to", definition.getTo(), statuses);
            }
            TransitionKey key = new TransitionKey(name, from, to);
            if (!seen.add(key)) {
                throw invalid(where + " ('" + name + "') repeats a transition "
                        + (from == null ? "from any status" : "from '" + from.getName() + "'") + " to '" + to.getName()
                        + "'. Keep one.");
            }
            resolved.add(new WorkflowTransition(name, from, to, resolveScreen(definition, where),
                    resolveResolution(definition.getResolution(), from, to, where)));
        }
        return resolved;
    }

    private TransitionResolution resolveResolution(TransitionResolution requested, WorkflowStatus from,
                                                   WorkflowStatus to, String where) {
        boolean targetDone = "DONE".equals(to.getCategory());
        boolean sourceDone = from != null && "DONE".equals(from.getCategory());
        boolean mustSet = targetDone && (from == null || from != to);
        boolean mustClear = !targetDone && (from == null || sourceDone);
        if (requested == null) {
            if (mustSet) {
                throw invalid(where + ".resolution is missing when entering '" + to.getName()
                        + "' (DONE). Use {\"action\":\"SET\",\"value\":\"<canonical ID from get_resolutions>\"}.");
            }
            return new TransitionResolution(mustClear ? "CLEAR" : "PRESERVE", null);
        }
        String action = requested.getAction();
        if (!"SET".equals(action) && !"CLEAR".equals(action)) {
            throw invalid(where + ".resolution.action '" + action + "' must be SET or CLEAR. "
                    + (mustSet ? "Use {\"action\":\"SET\",\"value\":\"<canonical ID from get_resolutions>\"}."
                            : "Omit resolution to select the category-based default."));
        }
        if ("CLEAR".equals(action)) {
            if (requested.getValue() != null) {
                throw invalid(where + ".resolution.value is forbidden for CLEAR. Remove value and use "
                        + "{\"action\":\"CLEAR\"} on a transition into a non-DONE status.");
            }
            if (targetDone) {
                throw invalid(where + ".resolution.action CLEAR is not allowed for "
                        + (from == null ? "fromAny" : "'" + from.getName() + "' (" + from.getCategory() + ")")
                        + " -> '" + to.getName() + "' (" + to.getCategory() + "). "
                        + "CLEAR requires a non-DONE target. "
                        + "Use {\"action\":\"SET\",\"value\":\"<canonical ID from get_resolutions>\"}, "
                        + "or omit resolution on a local DONE self-loop. "
                        + "If that is not the requested effect, clarify it before another preview; do not retry unchanged.");
            }
            return new TransitionResolution("CLEAR", null);
        }
        if (!targetDone) {
            throw invalid(where + ".resolution SET is only allowed when entering a DONE status; target '"
                    + to.getName() + "' is " + to.getCategory()
                    + ". Omit resolution to select the category-based default (CLEAR on DONE/fromAny -> non-DONE, "
                    + "otherwise PRESERVE).");
        }
        String id = requested.getValue();
        if (id == null || id.isEmpty() || !id.equals(id.trim())) {
            throw invalid(where + ".resolution.value must be a nonempty canonical resolution ID without "
                    + "leading/trailing spaces or control characters. Replace value with an ID from get_resolutions.");
        }
        Resolution resolution = constants.getResolution(id);
        if (resolution == null || !id.equals(resolution.getId())) {
            throw invalid(where + ".resolution.value '" + id + "' is not an existing canonical resolution ID. "
                    + "Replace value with an ID returned by get_resolutions, not a name or an invented ID.");
        }
        return new TransitionResolution("SET", resolution.getId(), resolution.getName(), resolution.getDescription());
    }

    private static final class TransitionKey {
        private final String name;
        private final boolean global;
        private final String from;
        private final String to;

        private TransitionKey(String name, WorkflowStatus from, WorkflowStatus to) {
            this.name = normalize(name);
            this.global = from == null;
            this.from = global ? "" : normalize(from.getName());
            this.to = normalize(to.getName());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TransitionKey)) return false;
            TransitionKey key = (TransitionKey) other;
            return global == key.global && name.equals(key.name) && from.equals(key.from) && to.equals(key.to);
        }

        @Override
        public int hashCode() {
            int result = 31 * name.hashCode() + (global ? 1 : 0);
            result = 31 * result + from.hashCode();
            return 31 * result + to.hashCode();
        }
    }

    private com.atlassian.jira.issue.fields.screen.FieldScreen resolveScreen(TransitionDefinition definition,
                                                                             String where) {
        if (definition.getScreen() == null || definition.getScreen().trim().isEmpty()) return null;
        if (screenCatalog == null) {
            throw invalid(where + ".screen cannot be resolved in this context.");
        }
        return screenCatalog.resolve(definition.getScreen(), where + ".screen ('" + definition.getScreen().trim() + "')");
    }

    /** Statuses nobody can reach and statuses nobody can leave: legal, usually a slip in the description or the photo. */
    private static List<String> reachabilityWarnings(List<WorkflowStatus> statuses, WorkflowStatus initial,
                                                     List<WorkflowTransition> transitions) {
        List<String> warnings = new ArrayList<String>();
        Set<WorkflowStatus> reachable = new HashSet<WorkflowStatus>();
        Deque<WorkflowStatus> queue = new ArrayDeque<WorkflowStatus>();
        reachable.add(initial);
        queue.add(initial);
        // A "from any status" transition is available wherever the issue is, so its target is reachable.
        for (WorkflowTransition transition : transitions) {
            if (transition.isFromAny() && reachable.add(transition.getTo())) {
                queue.add(transition.getTo());
            }
        }
        while (!queue.isEmpty()) {
            WorkflowStatus current = queue.poll();
            for (WorkflowTransition transition : transitions) {
                if (transition.getFrom() == current && reachable.add(transition.getTo())) {
                    queue.add(transition.getTo());
                }
            }
        }
        List<String> unreachable = new ArrayList<String>();
        List<String> deadEnds = new ArrayList<String>();
        for (WorkflowStatus status : statuses) {
            if (!reachable.contains(status)) {
                unreachable.add("'" + status.getName() + "'");
            }
            if (!hasWayOut(status, transitions) && !"DONE".equals(status.getCategory())) {
                deadEnds.add("'" + status.getName() + "'");
            }
        }
        if (!unreachable.isEmpty()) {
            warnings.add("No transition leads to " + String.join(", ", unreachable) + " from the initial status; an "
                    + "issue can never get there. Check the transitions with the user.");
        }
        if (!deadEnds.isEmpty()) {
            warnings.add("No transition leaves " + String.join(", ", deadEnds) + ", and the status is not in category "
                    + "DONE: an issue that gets there stays there. Check with the user.");
        }
        return warnings;
    }

    /** A way out is a transition to another status: one from this status, or one available from every status. */
    private static boolean hasWayOut(WorkflowStatus status, List<WorkflowTransition> transitions) {
        for (WorkflowTransition transition : transitions) {
            boolean leavesStatus = transition.isFromAny() || transition.getFrom() == status;
            if (leavesStatus && transition.getTo() != status) {
                return true;
            }
        }
        return false;
    }

    private static List<String> redundancyWarnings(List<WorkflowTransition> transitions) {
        List<String> warnings = new ArrayList<String>();
        for (WorkflowTransition local : transitions) {
            if (local.isFromAny()) continue;
            Long localScreen = local.getScreen() == null ? null : local.getScreen().getId();
            for (WorkflowTransition global : transitions) {
                if (!global.isFromAny() || global.getTo() != local.getTo()) continue;
                boolean sameResolution = local.getResolution().getAction().equals(global.getResolution().getAction())
                        && java.util.Objects.equals(local.getResolution().getValue(), global.getResolution().getValue());
                Long globalScreen = global.getScreen() == null ? null : global.getScreen().getId();
                boolean sameEffect = sameResolution && java.util.Objects.equals(localScreen, globalScreen);
                String edge = "'" + local.getName() + "' ('" + local.getFrom().getName() + "' to '"
                        + local.getTo().getName() + "')";
                warnings.add(sameEffect
                        ? "Redundant: " + edge + " has the same resolution and screen as global transition '"
                                + global.getName() + "' from this source."
                        : "Overlap: " + edge + " also reaches the target of global transition '"
                                + global.getName() + "', with a different resolution or screen.");
            }
        }
        return warnings;
    }

    /** @return the trimmed text */
    private static String checkName(String what, String text, int maxLength) {
        checkXmlText(what, text);
        String name = text == null ? "" : text.trim();
        if (name.isEmpty()) {
            throw invalid("'" + what + "' is required and must not be blank.");
        }
        if (name.length() > maxLength) {
            throw invalid("'" + what + "' has " + name.length() + " characters; the maximum is " + maxLength + ".");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw invalid("'" + what + "' contains a control character (line break, tab); use plain text.");
            }
        }
        return name;
    }

    /** XML 1.0 Char ranges, checked before trim so invalid boundary characters cannot disappear. */
    private static void checkXmlText(String what, String text) {
        if (text == null) return;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            if (!(codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                    || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                    || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                    || (codePoint >= 0x10000 && codePoint <= 0x10FFFF))) {
                throw invalid("'" + what + "' contains a character not allowed in XML 1.0: U+"
                        + Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT) + ".");
            }
            i += Character.charCount(codePoint);
        }
    }

    private static WorkflowFailureException invalid(String message) {
        return new WorkflowFailureException(WorkflowErrorCode.VALIDATION_ERROR, message);
    }

    private static WorkflowFailureException unknownStatus(String where, String name,
                                                          Map<String, WorkflowStatus> statuses) {
        List<String> names = new ArrayList<String>();
        for (WorkflowStatus status : statuses.values()) {
            names.add(status.getName());
        }
        return new WorkflowFailureException(WorkflowErrorCode.UNKNOWN_STATUS,
                "'" + where + "' names status '" + (name == null ? "" : name.trim()) + "', which is not in "
                        + "'statuses'. Use one of the suggestions, or add the status to 'statuses'.", names);
    }

    private static List<String> categoryNames() {
        List<String> names = new ArrayList<String>();
        for (StatusCategoryKey key : StatusCategoryKey.values()) {
            names.add(key.name());
        }
        return names;
    }
}
