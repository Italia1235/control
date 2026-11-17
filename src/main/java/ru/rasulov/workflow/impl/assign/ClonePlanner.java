package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.workflow.api.WorkflowAssignLimits;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.assign.SchemeView;
import ru.rasulov.workflow.api.dto.clone.CloneErrorCode;
import ru.rasulov.workflow.api.dto.clone.CloneScope;
import ru.rasulov.workflow.api.dto.clone.CloneVerdict;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsRequest;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ru.rasulov.common.impl.Normalizer.normalize;

/**
 * Decides what a clone would do, and writes nothing, so the dry run and the real call share one code path: which
 * workflows of the project's scheme other projects use too (those are worth a copy), what the scheme does (a copy when
 * others share it, a change in place when it is the project's own), the names of the copies, and the issues that move.
 */
@Named
public class ClonePlanner {

    /** Jira's limit for the name of a workflow or a scheme. */
    static final int MAX_NAME_LENGTH = 255;

    private final WorkflowSchemeManager schemeManager;
    private final WorkflowManager workflowManager;
    private final ProjectManager projectManager;
    private final AssignmentPlanner assignmentPlanner;

    @Inject
    public ClonePlanner(@ComponentImport WorkflowSchemeManager schemeManager,
                        @ComponentImport WorkflowManager workflowManager,
                        @ComponentImport ProjectManager projectManager, AssignmentPlanner assignmentPlanner) {
        this.schemeManager = schemeManager;
        this.workflowManager = workflowManager;
        this.projectManager = projectManager;
        this.assignmentPlanner = assignmentPlanner;
    }

    /**
     * @throws CloneFailureException {@code UNKNOWN_WORKFLOW}, {@code WORKFLOW_NOT_IN_SCHEME}, {@code NAME_TAKEN},
     *                               {@code DRAFT_EXISTS}, {@code TOO_MANY_ISSUES} or {@code VALIDATION_ERROR} for a
     *                               custom name that matches nothing that is copied
     */
    ClonePlan plan(Project project, CloneWorkflowsRequest request, IssueUsage usage) {
        AssignableWorkflowScheme scheme = schemeManager.getWorkflowSchemeObj(project);
        boolean usingDefault = schemeManager.isUsingDefaultScheme(project);
        List<Project> sharing = usingDefault ? Collections.<Project>emptyList()
                : assignmentPlanner.otherProjects(project, scheme);
        SchemeView current = assignmentPlanner.schemeView(project, scheme, sharing);
        boolean schemeShared = usingDefault || !sharing.isEmpty();

        List<String> names = workflowNamesOf(scheme);
        Set<String> requested = requestedNames(request, names);
        Map<String, List<Project>> users = otherUsers(project, names);

        List<ClonePlan.PlannedWorkflow> planned = new ArrayList<ClonePlan.PlannedWorkflow>();
        Set<String> copyNames = new LinkedHashSet<String>();
        List<String> drafted = new ArrayList<String>();
        for (String name : names) {
            List<Project> others = users.get(name);
            boolean wanted = request.getScope() == CloneScope.ALL_WORKFLOWS || requested.contains(normalize(name));
            CloneVerdict verdict = !wanted ? CloneVerdict.KEEP
                    : others.isEmpty() ? CloneVerdict.ALREADY_OWN : CloneVerdict.COPY;
            boolean hasDraft = false;
            if (verdict == CloneVerdict.COPY) {
                copyNames.add(name);
                hasDraft = workflowManager.getDraftWorkflow(name) != null;
                if (hasDraft) {
                    drafted.add(name);
                }
            }
            planned.add(new ClonePlan.PlannedWorkflow(name, workflowManager.getWorkflow(name), null, verdict,
                    others.size(), AssignmentPlanner.limited(AssignmentPlanner.names(others)), hasDraft));
        }

        SchemeAction action = schemeShared ? SchemeAction.CREATE
                : copyNames.isEmpty() ? SchemeAction.NONE : SchemeAction.UPDATE;
        if (action == SchemeAction.NONE) {
            return new ClonePlan(request.getScope(), action, scheme, current, null, null, planned,
                    Collections.<String, String>emptyMap(), Collections.<String>emptyList());
        }

        checkSchemeDraft(scheme, usingDefault);
        Set<String> takenWorkflows = names(workflowManager.getWorkflows());
        Map<String, String> replacements = workflowReplacements(request, copyNames, takenWorkflows, project, users);
        String schemeName = null;
        if (action == SchemeAction.CREATE) {
            schemeName = schemeName(request, scheme, project, sharing);
        } else if (request.getSchemeName() != null) {
            throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR, "schemeName was given, but the project's "
                    + "scheme '" + scheme.getName() + "' is its own and is changed in place, so there is no new "
                    + "scheme to name. Leave schemeName out.");
        }

        List<ClonePlan.PlannedWorkflow> named = new ArrayList<ClonePlan.PlannedWorkflow>();
        for (ClonePlan.PlannedWorkflow workflow : planned) {
            named.add(workflow.getVerdict() == CloneVerdict.COPY ? withName(workflow, replacements) : workflow);
        }
        Map<String, String> leaving = leaving(project, scheme, copyNames, usage);

        List<String> warnings = new ArrayList<String>();
        if (usingDefault) {
            warnings.add("Project " + project.getKey() + " used the system default workflow scheme, which cannot be "
                    + "changed, so it gets a scheme of its own.");
        }
        if (!drafted.isEmpty()) {
            Collections.sort(drafted, String.CASE_INSENSITIVE_ORDER);
            warnings.add("The workflow(s) " + String.join(", ", drafted) + " have a draft. The draft is not copied; "
                    + "use publish_workflow_draft first if it is needed.");
        }
        SchemeEdit edit = SchemeEdit.replacing(replacements, scheme.getActualDefaultWorkflow());
        return new ClonePlan(request.getScope(), action, scheme, current, schemeName, edit, named, leaving, warnings);
    }

    /** Workflows the scheme names: the one for unmapped types first, then the mapped ones, each once. */
    private static List<String> workflowNamesOf(AssignableWorkflowScheme scheme) {
        Set<String> names = new LinkedHashSet<String>();
        if (scheme.getActualDefaultWorkflow() != null) {
            names.add(scheme.getActualDefaultWorkflow());
        }
        names.addAll(scheme.getMappings().values());
        return new ArrayList<String>(names);
    }

    /** The names (normalized) the caller asked for with {@code SELECTED}; empty for the other scopes. */
    private Set<String> requestedNames(CloneWorkflowsRequest request, List<String> schemeNames) {
        Set<String> requested = new LinkedHashSet<String>();
        if (request.getScope() != CloneScope.SELECTED) {
            return requested;
        }
        Set<String> inScheme = new HashSet<String>();
        for (String name : schemeNames) {
            inScheme.add(normalize(name));
        }
        for (String name : request.getWorkflows()) {
            String wanted = normalize(name);
            if (!requested.add(wanted)) {
                throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR,
                        "Workflow '" + name.trim() + "' is listed twice. List each workflow once.");
            }
            if (!inScheme.contains(wanted)) {
                throw notInScheme(name.trim(), schemeNames);
            }
        }
        return requested;
    }

    private CloneFailureException notInScheme(String name, List<String> schemeNames) {
        String wanted = normalize(name);
        for (JiraWorkflow workflow : workflowManager.getWorkflows()) {
            if (normalize(workflow.getName()).equals(wanted)) {
                return new CloneFailureException(CloneErrorCode.WORKFLOW_NOT_IN_SCHEME, "Workflow '"
                        + workflow.getName() + "' exists but is not in the workflow scheme of this project, so this "
                        + "project does not use it. Use one of suggestions: the workflows of the project's scheme.",
                        schemeNames);
            }
        }
        List<String> similar = new ArrayList<String>();
        for (JiraWorkflow workflow : workflowManager.getWorkflows()) {
            String candidate = normalize(workflow.getName());
            if (candidate.contains(wanted) || wanted.contains(candidate)) {
                similar.add(workflow.getName());
            }
        }
        Collections.sort(similar);
        return new CloneFailureException(CloneErrorCode.UNKNOWN_WORKFLOW, "No workflow is named '" + name
                + "'. Use one of the workflows of the project's scheme (suggestions).", schemeNames.isEmpty() ? similar
                : schemeNames);
    }

    /**
     * Projects other than {@code project} whose issue types run each of the workflows. A project counts when one of
     * its own issue types gets the workflow from its scheme: a workflow the scheme maps to a type nobody has is not
     * used by anybody.
     */
    private Map<String, List<Project>> otherUsers(Project project, List<String> names) {
        Map<String, List<Project>> users = new LinkedHashMap<String, List<Project>>();
        for (String name : names) {
            users.put(name, new ArrayList<Project>());
        }
        // What each issue type runs in a scheme, read once per scheme: projects that share a scheme share the answer.
        Map<Long, Map<String, String>> workflowOf = new HashMap<Long, Map<String, String>>();
        for (Project other : projectManager.getProjects()) {
            if (other.getId().equals(project.getId())) {
                continue;
            }
            AssignableWorkflowScheme scheme = schemeManager.getWorkflowSchemeObj(other);
            Map<String, String> known = workflowOf.get(scheme.getId());
            if (known == null) {
                known = new HashMap<String, String>();
                workflowOf.put(scheme.getId(), known);
            }
            Set<String> used = new HashSet<String>();
            for (IssueType type : other.getIssueTypes()) {
                String typeId = type.getId();
                if (!known.containsKey(typeId)) {
                    known.put(typeId, scheme.getActualWorkflow(typeId));
                }
                used.add(known.get(typeId));
            }
            for (String name : names) {
                if (used.contains(name)) {
                    users.get(name).add(other);
                }
            }
        }
        for (List<Project> list : users.values()) {
            Collections.sort(list, new Comparator<Project>() {
                @Override
                public int compare(Project a, Project b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
        }
        return users;
    }

    /** A scheme draft would be left out of an exact copy, so it is refused instead of copied incompletely. */
    private void checkSchemeDraft(AssignableWorkflowScheme scheme, boolean usingDefault) {
        if (!usingDefault && schemeManager.hasDraft(scheme)) {
            throw new CloneFailureException(CloneErrorCode.DRAFT_EXISTS, "The workflow scheme '" + scheme.getName()
                    + "' has a draft, and a copy would not include it. Nothing was changed. Ask the user to publish or "
                    + "discard the draft in Jira, then repeat the call.");
        }
    }

    /**
     * Old name to the name of its copy: the caller's name, else the old name under this project's key ("BUS: Flow"
     * used by BUS becomes "SPMT: Flow"), with "v2", "v3"... only when that name is taken.
     */
    private Map<String, String> workflowReplacements(CloneWorkflowsRequest request, Set<String> copyNames,
                                                     Set<String> taken, Project project,
                                                     Map<String, List<Project>> users) {
        Map<String, Map.Entry<String, String>> chosen = new HashMap<String, Map.Entry<String, String>>();
        for (Map.Entry<String, String> entry : request.getWorkflowNames().entrySet()) {
            String key = null;
            for (String name : copyNames) {
                if (normalize(name).equals(normalize(entry.getKey()))) {
                    key = name;
                }
            }
            if (key == null) {
                throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR, "workflowNames has '"
                        + entry.getKey() + "', which is not a workflow that gets a copy. Keys are the names of the "
                        + "workflows with the verdict COPY: " + String.join(", ", copyNames) + ".");
            }
            Map.Entry<String, String> previous = chosen.put(key, entry);
            if (previous != null) {
                throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR, "workflowNames entries '"
                        + previous.getKey() + "' -> '" + previous.getValue() + "' and '" + entry.getKey()
                        + "' -> '" + entry.getValue() + "' both name the same source workflow '" + key
                        + "' (names are compared without case). Give each source only one target name.");
            }
        }
        Set<String> used = new HashSet<String>(taken);
        Map<String, String> replacements = new LinkedHashMap<String, String>();
        for (String name : copyNames) {
            Map.Entry<String, String> choice = chosen.get(name);
            String newName = choice == null ? null : choice.getValue().trim();
            if (newName != null) {
                if (!used.add(normalize(newName))) {
                    throw new CloneFailureException(CloneErrorCode.NAME_TAKEN, "The name '" + newName + "' for the "
                            + "copy of '" + name + "' is used by another workflow or by another copy of this call. "
                            + "Choose another name.");
                }
            } else {
                newName = freeName(titled(name, project, users.get(name)), used);
                used.add(normalize(newName));
            }
            replacements.put(name, newName);
        }
        return replacements;
    }

    private String schemeName(CloneWorkflowsRequest request, AssignableWorkflowScheme scheme, Project project,
                              List<Project> sharing) {
        Set<String> taken = new HashSet<String>();
        for (AssignableWorkflowScheme existing : schemeManager.getAssignableSchemes()) {
            taken.add(normalize(existing.getName()));
        }
        taken.add(normalize(schemeManager.getDefaultWorkflowScheme().getName()));
        String chosen = request.getSchemeName() == null ? null : request.getSchemeName().trim();
        if (chosen == null) {
            return freeName(titled(scheme.getName(), project, sharing), taken);
        }
        if (taken.contains(normalize(chosen))) {
            throw new CloneFailureException(CloneErrorCode.NAME_TAKEN, "A workflow scheme named '" + chosen
                    + "' exists already. Choose another name.");
        }
        return chosen;
    }

    /**
     * The old name under the key of {@code project}: a name that starts with the key of one of the projects that use
     * it ("BUS: Scheme", "BUS_Flow") gets this project's key in place of that one ("SPMT: Scheme", "SPMT_Flow"), a
     * name that already starts with this project's key stays, any other gets "KEY: " in front.
     */
    static String titled(String oldName, Project project, List<Project> usedBy) {
        String key = project.getKey();
        if (startsWithKey(oldName, key)) {
            return oldName;
        }
        for (Project other : usedBy) {
            if (startsWithKey(oldName, other.getKey())) {
                return key + oldName.substring(other.getKey().length());
            }
        }
        return key + ": " + oldName;
    }

    /** The name starts with the key and the key is followed by something that is not a letter or a digit. */
    private static boolean startsWithKey(String name, String key) {
        return name.length() > key.length() && name.regionMatches(true, 0, key, 0, key.length())
                && !Character.isLetterOrDigit(name.charAt(key.length()));
    }

    /** The name if it is free, else the next free "name v2", "name v3"... */
    static String freeName(String name, Set<String> taken) {
        String fitted = name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
        return taken.contains(normalize(fitted)) ? nextFreeName(fitted, taken) : fitted;
    }

    /** "Bug flow" gives "Bug flow v2"; "Bug flow v2" gives "Bug flow v3"; the first one whose name is not taken. */
    static String nextFreeName(String name, Set<String> taken) {
        String stem = name.replaceFirst("\\s+v\\d+$", "");
        for (int n = 2; ; n++) {
            String suffix = " v" + n;
            String fitted = stem.length() + suffix.length() > MAX_NAME_LENGTH
                    ? stem.substring(0, MAX_NAME_LENGTH - suffix.length()) : stem;
            String candidate = fitted + suffix;
            if (!taken.contains(normalize(candidate))) {
                return candidate;
            }
        }
    }

    private static Set<String> names(java.util.Collection<JiraWorkflow> workflows) {
        Set<String> names = new HashSet<String>();
        for (JiraWorkflow workflow : workflows) {
            names.add(normalize(workflow.getName()));
        }
        return names;
    }

    private static ClonePlan.PlannedWorkflow withName(ClonePlan.PlannedWorkflow workflow,
                                                      Map<String, String> replacements) {
        return new ClonePlan.PlannedWorkflow(workflow.getName(), workflow.getSource(),
                replacements.get(workflow.getName()), workflow.getVerdict(), workflow.otherProjects(),
                workflow.otherProjectNames(), workflow.sourceHasDraft());
    }

    /**
     * Issue types of the project (and those with issues that its issue type scheme no longer has) whose workflow gets
     * a copy, to that workflow. Refused when too many issues would move at once.
     */
    private Map<String, String> leaving(Project project, AssignableWorkflowScheme scheme, Set<String> copyNames,
                                        IssueUsage usage) {
        Set<String> typeIds = new LinkedHashSet<String>();
        for (IssueType type : project.getIssueTypes()) {
            typeIds.add(type.getId());
        }
        List<String> orphans = new ArrayList<String>(usage.issueTypeIds());
        Collections.sort(orphans);
        typeIds.addAll(orphans);

        Map<String, String> leaving = new LinkedHashMap<String, String>();
        int toMove = 0;
        for (String typeId : typeIds) {
            String workflow = scheme.getActualWorkflow(typeId);
            if (copyNames.contains(workflow)) {
                leaving.put(typeId, workflow);
                toMove += usage.issues(typeId);
            }
        }
        if (toMove > WorkflowAssignLimits.MAX_ISSUES_TO_MIGRATE) {
            throw new CloneFailureException(CloneErrorCode.TOO_MANY_ISSUES, toMove + " issues would have to move to "
                    + "the copies, and one call moves at most " + WorkflowAssignLimits.MAX_ISSUES_TO_MIGRATE
                    + ". Nothing was changed. Copy fewer workflows per call (scope SELECTED), or tell the user that "
                    + "this project is too big for this tool.");
        }
        return leaving;
    }
}
