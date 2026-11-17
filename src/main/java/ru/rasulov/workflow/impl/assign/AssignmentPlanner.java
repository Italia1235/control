package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.workflow.api.WorkflowAssignLimits;
import ru.rasulov.workflow.api.dto.assign.AssignErrorCode;
import ru.rasulov.workflow.api.dto.assign.AssignVerdict;
import ru.rasulov.workflow.api.dto.assign.IssueTypeAssignment;
import ru.rasulov.workflow.api.dto.assign.IssueTypeWorkflowView;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.assign.SchemeView;
import ru.rasulov.common.api.dto.StatusMapping;
import ru.rasulov.common.api.dto.StatusUsage;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ru.rasulov.common.impl.Normalizer.normalize;

/**
 * Decides what an assignment would do, and writes nothing, so the dry run and the real call share one code path:
 * a verdict per issue type (already there, nothing to move, move, conflict) and what happens to the workflow scheme
 * (change the project's own, create a new one, refuse because it is shared or has a draft).
 */
@Named
public class AssignmentPlanner {

    private static final String SOFTWARE_PROJECT_TYPE = "software";

    private final WorkflowSchemeManager schemeManager;
    private final IssueTypeManager issueTypeManager;
    private final StatusManager statusManager;

    @Inject
    public AssignmentPlanner(@ComponentImport WorkflowSchemeManager schemeManager,
                             @ComponentImport IssueTypeManager issueTypeManager,
                             @ComponentImport StatusManager statusManager) {
        this.schemeManager = schemeManager;
        this.issueTypeManager = issueTypeManager;
        this.statusManager = statusManager;
    }

    /** The project's scheme and who else uses it. The other projects of the system default scheme are not listed. */
    SchemeView schemeView(Project project, AssignableWorkflowScheme scheme) {
        boolean usingDefault = schemeManager.isUsingDefaultScheme(project);
        return schemeView(project, scheme, usingDefault ? Collections.<Project>emptyList()
                : otherProjects(project, scheme));
    }

    /** Same, for a caller that has already read the other users of the scheme and must not read them again. */
    SchemeView schemeView(Project project, AssignableWorkflowScheme scheme, List<Project> otherUsers) {
        boolean usingDefault = schemeManager.isUsingDefaultScheme(project);
        List<String> others = names(otherUsers);
        boolean hasDraft = !usingDefault && schemeManager.hasDraft(scheme);
        List<String> shown = others.size() > WorkflowAssignLimits.MAX_PROJECTS_SHOWN
                ? new ArrayList<String>(others.subList(0, WorkflowAssignLimits.MAX_PROJECTS_SHOWN)) : others;
        return new SchemeView(scheme.getId(), scheme.getName(), scheme.isDefault(), hasDraft, others.size(), shown);
    }

    /** Null usage means statistics unavailable: current configuration only, with no fabricated zero counts. */
    List<IssueTypeWorkflowView> describe(Project project, AssignableWorkflowScheme scheme, IssueUsage usage) {
        List<IssueTypeWorkflowView> views = new ArrayList<IssueTypeWorkflowView>();
        for (IssueTypeRef type : allIssueTypes(project, usage)) {
            String id = type.getId();
            views.add(new IssueTypeWorkflowView(type, scheme.getActualWorkflow(id),
                    usage == null ? null : usage.issues(id),
                    usage == null ? null : statusUsage(id, usage, null, Collections.<String, Status>emptyMap())));
        }
        return views;
    }

    /**
     * @param requested the issue types to change, or {@code null} for the whole project
     * @param newScheme the caller asked for a scheme of its own instead of changing the current one
     * @throws AssignFailureException {@code ISSUE_TYPE_NOT_IN_PROJECT}, {@code STATUS_CONFLICT}, {@code TOO_MANY_ISSUES},
     *                                {@code SCHEME_SHARED} or {@code DRAFT_EXISTS}
     */
    AssignmentPlan plan(Project project, JiraWorkflow target, List<IssueType> requested, boolean newScheme,
                        IssueUsage usage) {
        return plan(project, target, requested, newScheme, usage, Collections.<StatusMapping>emptyList());
    }

    AssignmentPlan plan(Project project, JiraWorkflow target, List<IssueType> requested, boolean newScheme,
                        IssueUsage usage, List<StatusMapping> requestedMappings) {
        return plan(project, target, requested, newScheme, usage, requestedMappings,
                schemeManager.getWorkflowSchemeObj(project));
    }

    AssignmentPlan plan(Project project, JiraWorkflow target, List<IssueType> requested, boolean newScheme,
                        IssueUsage usage, List<StatusMapping> requestedMappings, AssignableWorkflowScheme scheme) {
        List<IssueTypeRef> types = requested == null ? allIssueTypes(project, usage) : requestedTypes(project, requested);

        Set<String> targetStatuses = target.getLinkedStatusIds();
        Map<String, Status> statusMappings = resolveStatusMappings(target, requestedMappings);
        List<IssueTypeAssignment> assignments = new ArrayList<IssueTypeAssignment>();
        Set<String> changed = new LinkedHashSet<String>();
        int toMigrate = 0;
        for (IssueTypeRef type : types) {
            IssueTypeAssignment assignment = assess(type, scheme.getActualWorkflow(type.getId()), target,
                    targetStatuses, statusMappings, usage);
            assignments.add(assignment);
            if (assignment.getVerdict() != AssignVerdict.ALREADY) {
                changed.add(type.getId());
            }
            if (assignment.getVerdict() == AssignVerdict.MIGRATE) {
                toMigrate += assignment.getIssues();
            }
        }

        checkConflicts(assignments, target);
        checkMappingsUsed(types, scheme, target, usage, statusMappings);
        boolean usingDefault = schemeManager.isUsingDefaultScheme(project);
        if (changed.isEmpty()) {
            return new AssignmentPlan(assignments, SchemeAction.NONE, scheme, usingDefault, null, null,
                    Collections.<String>emptyList(), statusMappings);
        }
        if (toMigrate > WorkflowAssignLimits.MAX_ISSUES_TO_MIGRATE) {
            throw new AssignFailureException(AssignErrorCode.TOO_MANY_ISSUES, toMigrate + " issues would have to move "
                    + "to the new workflow, and one call moves at most " + WorkflowAssignLimits.MAX_ISSUES_TO_MIGRATE
                    + ". Nothing was changed. Assign the workflow to fewer issue types per call, or tell the user that "
                    + "this project is too big for this tool.", null, assignments);
        }

        SchemeEdit edit = requested == null ? SchemeEdit.allIssueTypes(target.getName())
                : SchemeEdit.issueTypes(target.getName(), changed);
        List<String> warnings = new ArrayList<String>();
        if (project.getProjectTypeKey() != null && SOFTWARE_PROJECT_TYPE.equals(project.getProjectTypeKey().getKey())) {
            warnings.add("Project " + project.getKey() + " is a Jira Software project: the boards keep their "
                    + "columns, so a status the previous workflow did not have is not on any column until someone maps "
                    + "it in the board settings. Tell the user.");
        }
        if (usingDefault || newScheme) {
            if (usingDefault && !newScheme) {
                warnings.add("Project " + project.getKey() + " used the system default workflow scheme, which cannot "
                        + "be changed, so it gets a scheme of its own.");
            }
            if (!usingDefault && otherProjects(project, scheme).isEmpty()) {
                warnings.add("The previous scheme '" + scheme.getName() + "' is left without projects; it was not "
                        + "deleted. Tell the user.");
            }
            return new AssignmentPlan(assignments, SchemeAction.CREATE, scheme, usingDefault, newSchemeName(project),
                    edit, warnings, statusMappings);
        }
        List<Project> others = otherProjects(project, scheme);
        if (!others.isEmpty()) {
            List<String> names = names(others);
            throw new AssignFailureException(AssignErrorCode.SCHEME_SHARED, "The workflow scheme '" + scheme.getName()
                    + "' of project " + project.getKey() + " is shared with " + others.size() + " other project(s); "
                    + "changing it would change their workflows too, and their issues are not checked. Nothing was "
                    + "changed. Ask the user whether the project should get a scheme of its own, then repeat the call "
                    + "with newScheme: true.", limited(names), assignments);
        }
        if (schemeManager.hasDraft(scheme)) {
            throw new AssignFailureException(AssignErrorCode.DRAFT_EXISTS, "The workflow scheme '" + scheme.getName()
                    + "' has a draft; publishing the draft would overwrite this change. Nothing was changed. Ask the "
                    + "user to publish or discard the draft in Jira, or to give the project a scheme of its own "
                    + "(newScheme: true).", null, assignments);
        }
        return new AssignmentPlan(assignments, SchemeAction.UPDATE, scheme, false, null, edit, warnings,
                statusMappings);
    }

    private List<IssueTypeRef> requestedTypes(Project project, List<IssueType> requested) {
        Set<String> inProject = new HashSet<String>();
        for (IssueType type : project.getIssueTypes()) {
            inProject.add(type.getId());
        }
        List<IssueTypeRef> refs = new ArrayList<IssueTypeRef>();
        for (IssueType type : requested) {
            if (!inProject.contains(type.getId())) {
                List<String> names = new ArrayList<String>();
                for (IssueType candidate : project.getIssueTypes()) {
                    names.add(candidate.getName());
                }
                Collections.sort(names);
                throw new AssignFailureException(AssignErrorCode.ISSUE_TYPE_NOT_IN_PROJECT, "Issue type '"
                        + type.getName() + "' is not one of the issue types of project " + project.getKey()
                        + "; its issue type scheme does not have it. Use one of suggestions.", names);
            }
            refs.add(ref(type));
        }
        return refs;
    }

    private IssueTypeAssignment assess(IssueTypeRef type, String from, JiraWorkflow target, Set<String> targetStatuses,
                                       Map<String, Status> statusMappings, IssueUsage usage) {
        String id = type.getId();
        List<StatusUsage> statuses = statusUsage(id, usage, targetStatuses, statusMappings);
        boolean missing = false;
        for (StatusUsage status : statuses) {
            missing |= !status.getInWorkflow() && status.getMappedTo() == null;
        }
        int issues = usage.issues(id);
        AssignVerdict verdict;
        if (target.getName().equals(from)) {
            verdict = AssignVerdict.ALREADY;
        } else if (issues == 0) {
            verdict = AssignVerdict.READY;
        } else {
            verdict = missing ? AssignVerdict.CONFLICT : AssignVerdict.MIGRATE;
        }
        return new IssueTypeAssignment(type, from, target.getName(), verdict, issues, statuses);
    }

    private Map<String, Status> resolveStatusMappings(JiraWorkflow target, List<StatusMapping> requested) {
        Map<String, Status> statuses = new LinkedHashMap<String, Status>();
        for (Status status : statusManager.getStatuses()) {
            statuses.put(normalize(status.getName()), status);
        }
        Map<String, Status> targetStatuses = new LinkedHashMap<String, Status>();
        for (Status status : target.getLinkedStatusObjects()) {
            targetStatuses.put(normalize(status.getName()), status);
        }
        Map<String, Status> resolved = new LinkedHashMap<String, Status>();
        for (StatusMapping mapping : requested) {
            String from = mapping == null ? null : mapping.getFrom();
            String to = mapping == null ? null : mapping.getTo();
            if (from == null || from.trim().isEmpty() || to == null || to.trim().isEmpty()) {
                throw new AssignFailureException(AssignErrorCode.VALIDATION_ERROR,
                        "Every statusMappings item needs non-blank from and to status names.");
            }
            Status source = statuses.get(normalize(from));
            if (source == null) {
                throw new AssignFailureException(AssignErrorCode.VALIDATION_ERROR,
                        "No Jira status is named '" + from.trim() + "'. Use the blocking status names returned by "
                                + "the assign_workflow STATUS_CONFLICT preview.");
            }
            Status destination = targetStatuses.get(normalize(to));
            if (destination == null) {
                throw new AssignFailureException(AssignErrorCode.VALIDATION_ERROR, "Workflow '" + target.getName()
                        + "' has no status named '" + to.trim() + "'.", statusNames(target), null);
            }
            if (resolved.put(source.getId(), destination) != null) {
                throw new AssignFailureException(AssignErrorCode.VALIDATION_ERROR,
                        "Status '" + source.getName() + "' is mapped more than once.");
            }
        }
        return resolved;
    }

    private void checkMappingsUsed(List<IssueTypeRef> types, AssignableWorkflowScheme scheme, JiraWorkflow target,
                                   IssueUsage usage, Map<String, Status> mappings) {
        Set<String> needed = new HashSet<String>();
        Set<String> targetStatuses = target.getLinkedStatusIds();
        for (IssueTypeRef type : types) {
            if (target.getName().equals(scheme.getActualWorkflow(type.getId()))) {
                continue;
            }
            for (String statusId : usage.statuses(type.getId()).keySet()) {
                if (!targetStatuses.contains(statusId)) {
                    needed.add(statusId);
                }
            }
        }
        for (String sourceId : mappings.keySet()) {
            if (!needed.contains(sourceId)) {
                Status source = statusManager.getStatus(sourceId);
                throw new AssignFailureException(AssignErrorCode.VALIDATION_ERROR, "Status '"
                        + (source == null ? sourceId : source.getName()) + "' does not need mapping for the selected "
                        + "issue types: it has no issues that must change status.");
            }
        }
    }

    private static List<String> statusNames(JiraWorkflow workflow) {
        List<String> names = new ArrayList<String>();
        for (Status status : workflow.getLinkedStatusObjects()) {
            names.add(status.getName());
        }
        return names;
    }

    /** Whole call refused (Q2): a type that is ready does not make up for one that is not. */
    private static void checkConflicts(List<IssueTypeAssignment> assignments, JiraWorkflow target) {
        List<String> problems = new ArrayList<String>();
        for (IssueTypeAssignment assignment : assignments) {
            if (assignment.getVerdict() != AssignVerdict.CONFLICT) {
                continue;
            }
            List<String> statuses = new ArrayList<String>();
            for (StatusUsage status : assignment.getStatuses()) {
                if (!status.getInWorkflow() && status.getMappedTo() == null) {
                    statuses.add("'" + status.getName() + "' (" + status.getIssues() + " issue(s))");
                }
            }
            problems.add(assignment.getIssueType().getName() + ": " + String.join(", ", statuses));
        }
        if (problems.isEmpty()) {
            return;
        }
        List<String> workflowStatuses = new ArrayList<String>();
        for (Status status : target.getLinkedStatusObjects()) {
            workflowStatuses.add(status.getName());
        }
        throw new AssignFailureException(AssignErrorCode.STATUS_CONFLICT, "Issues stand in statuses that workflow '"
                + target.getName() + "' does not have: " + String.join("; ", problems) + ". Ask the user which target "
                + "status each of these statuses should map to, then preview again with statusMappings. Nothing was "
                + "changed. suggestions are the statuses the workflow has.",
                workflowStatuses, assignments);
    }

    /** Issue types of the project, then those that have issues in it but are not in its issue type scheme any more. */
    private List<IssueTypeRef> allIssueTypes(Project project, IssueUsage usage) {
        Map<String, IssueTypeRef> types = new LinkedHashMap<String, IssueTypeRef>();
        for (IssueType type : project.getIssueTypes()) {
            types.put(type.getId(), ref(type));
        }
        if (usage == null) return new ArrayList<IssueTypeRef>(types.values());
        List<String> orphans = new ArrayList<String>();
        for (String id : usage.issueTypeIds()) {
            if (!types.containsKey(id)) {
                orphans.add(id);
            }
        }
        Collections.sort(orphans);
        for (String id : orphans) {
            IssueType type = issueTypeManager.getIssueType(id);
            types.put(id, type == null ? new IssueTypeRef(id, id) : ref(type));
        }
        return new ArrayList<IssueTypeRef>(types.values());
    }

    /** @param targetStatuses ids of the statuses of the target workflow, or {@code null} when reading only */
    private List<StatusUsage> statusUsage(String issueTypeId, IssueUsage usage, Set<String> targetStatuses,
                                          Map<String, Status> statusMappings) {
        List<StatusUsage> statuses = new ArrayList<StatusUsage>();
        for (Map.Entry<String, Integer> entry : usage.statuses(issueTypeId).entrySet()) {
            Status status = statusManager.getStatus(entry.getKey());
            String name = status == null ? "status " + entry.getKey() : status.getName();
            Status mapped = statusMappings.get(entry.getKey());
            statuses.add(new StatusUsage(name, entry.getValue(),
                    targetStatuses == null ? null : targetStatuses.contains(entry.getKey()),
                    mapped == null ? null : mapped.getName()));
        }
        Collections.sort(statuses, new Comparator<StatusUsage>() {
            @Override
            public int compare(StatusUsage a, StatusUsage b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return statuses;
    }

    /** Projects other than {@code project} that use the scheme, by name. */
    List<Project> otherProjects(Project project, AssignableWorkflowScheme scheme) {
        List<Project> others = new ArrayList<Project>();
        for (Project user : schemeManager.getProjectsUsing(scheme)) {
            if (!user.getId().equals(project.getId())) {
                others.add(user);
            }
        }
        Collections.sort(others, new Comparator<Project>() {
            @Override
            public int compare(Project a, Project b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return others;
    }

    static List<String> names(List<Project> projects) {
        List<String> names = new ArrayList<String>();
        for (Project project : projects) {
            names.add(project.getName());
        }
        return names;
    }

    static List<String> limited(List<String> names) {
        return names.size() > WorkflowAssignLimits.MAX_PROJECTS_SHOWN
                ? new ArrayList<String>(names.subList(0, WorkflowAssignLimits.MAX_PROJECTS_SHOWN)) : names;
    }

    /** {@code KEY: Workflow Scheme}, as Jira names the scheme of a new project; a taken name gets " (2)", " (3)"... */
    private String newSchemeName(Project project) {
        Set<String> taken = new HashSet<String>();
        for (AssignableWorkflowScheme existing : schemeManager.getAssignableSchemes()) {
            taken.add(normalize(existing.getName()));
        }
        String base = project.getKey().toUpperCase(Locale.ROOT) + ": Workflow Scheme";
        String name = base;
        for (int n = 2; taken.contains(normalize(name)); n++) {
            name = base + " (" + n + ")";
        }
        return name;
    }

    private static IssueTypeRef ref(IssueType type) {
        return new IssueTypeRef(type.getId(), type.getName());
    }
}
