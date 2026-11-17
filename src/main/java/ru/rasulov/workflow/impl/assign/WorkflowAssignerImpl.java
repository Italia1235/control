package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.workflow.api.WorkflowAssignLimits;
import ru.rasulov.workflow.api.WorkflowAssigner;
import ru.rasulov.workflow.api.dto.assign.AssignErrorCode;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowRequest;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowResponse;
import ru.rasulov.workflow.api.dto.assign.IssueTypeAssignment;
import ru.rasulov.workflow.api.dto.assign.IssueTypeWorkflowView;
import ru.rasulov.workflow.api.dto.assign.ProjectWorkflowsResponse;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.assign.SchemeChange;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.JournalCancelledException;
import ru.rasulov.common.impl.undo.JournalCapacityException;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ru.rasulov.common.impl.Normalizer.normalize;

/**
 * Orchestration of {@code get_project_workflows} and {@code assign_workflow}. A write is done under a cluster-wide lock
 * and is planned again under it (a dry run may be old, issues may have changed). The order is: create the new scheme
 * (not yet assigned) or remember the old state, move the issues, assign or change the scheme, then a second sweep for
 * issues created in between. A failure before the sweep undoes what was done, in reverse. The rules are in
 * {@link AssignmentPlanner}.
 */
@ExportAsService(WorkflowAssigner.class)
@Named
public class WorkflowAssignerImpl implements WorkflowAssigner {

    public static final String LOCK_NAME = "ru.rasulov.control.assign-workflow";

    private static final Logger log = LoggerFactory.getLogger(WorkflowAssignerImpl.class);

    private final AdministratorGuard administratorGuard;
    private final NamedLock namedLock;
    private final ProjectResolver projectResolver;
    private final IssueTypeResolver issueTypeResolver;
    private final WorkflowManager workflowManager;
    private final WorkflowSchemeManager schemeManager;
    private final IssueStatusCounter counter;
    private final AssignmentPlanner planner;
    private final SchemeWriter schemeWriter;
    private final AssignmentVerifier verifier;
    private final IssueMigrator migrator;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec planCodec;
    private final ChangeJournal journal;

    private static final class SweepResult {
        private final boolean complete;
        private final String message;

        private SweepResult(boolean complete, String message) {
            this.complete = complete;
            this.message = message;
        }

        private static SweepResult complete(String message) {
            return new SweepResult(true, message);
        }

        private static SweepResult partial(String message) {
            return new SweepResult(false, message);
        }
    }

    @Inject
    public WorkflowAssignerImpl(AdministratorGuard administratorGuard,
                                NamedLock namedLock,
                                ProjectResolver projectResolver,
                                IssueTypeResolver issueTypeResolver,
                                @ComponentImport WorkflowManager workflowManager,
                                @ComponentImport WorkflowSchemeManager schemeManager,
                                IssueStatusCounter counter,
                                AssignmentPlanner planner,
                                SchemeWriter schemeWriter,
                                AssignmentVerifier verifier,
                                IssueMigrator migrator,
                                PreviewPlanExecutor executor, ActionPlanCodec planCodec, ChangeJournal journal) {
        this.administratorGuard = administratorGuard;
        this.namedLock = namedLock;
        this.projectResolver = projectResolver;
        this.issueTypeResolver = issueTypeResolver;
        this.workflowManager = workflowManager;
        this.schemeManager = schemeManager;
        this.counter = counter;
        this.planner = planner;
        this.schemeWriter = schemeWriter;
        this.verifier = verifier;
        this.migrator = migrator;
        this.executor = executor;
        this.planCodec = planCodec;
        this.journal = journal;
    }

    @Override
    public ProjectWorkflowsResponse projectWorkflows(String projectKeyOrName, boolean includeIssueCounts) {
        administratorGuard.requireAdministrator();
        Project project;
        try {
            project = projectResolver.resolve(projectKeyOrName);
        } catch (LookupFailure e) {
            throw new InvalidRequestException(e.getMessage()
                    + (e.getSuggestions().isEmpty() ? "" : " Suggestions: " + String.join(", ", e.getSuggestions())));
        }
        AssignableWorkflowScheme scheme = schemeManager.getWorkflowSchemeObj(project);
        List<IssueTypeWorkflowView> issueTypes = null;
        ProjectWorkflowsResponse.IssueCountsStatus countsStatus =
                ProjectWorkflowsResponse.IssueCountsStatus.NOT_REQUESTED;
        String countsError = null;
        if (includeIssueCounts) {
            try {
                issueTypes = planner.describe(project, scheme, counter.count(project));
                countsStatus = ProjectWorkflowsResponse.IssueCountsStatus.COMPLETE;
            } catch (RuntimeException e) {
                log.warn("Could not count issues in project {}", project.getKey(), e);
                countsStatus = ProjectWorkflowsResponse.IssueCountsStatus.ERROR;
                countsError = "Could not count project issues. Statistics are unavailable; configuration is complete.";
            }
        }
        if (issueTypes == null) issueTypes = planner.describe(project, scheme, null);
        return new ProjectWorkflowsResponse(new ProjectRef(project.getKey(), project.getName()),
                planner.schemeView(project, scheme), issueTypes,
                scheme.getActualDefaultWorkflow(), countsStatus, countsError);
    }

    @Override
    public AssignWorkflowResponse assign(AssignWorkflowRequest request) {
        ApplicationUser user = administratorGuard.requireAdministrator();
        requireMode(request);
        if (request.isDryRun()) {
            AssignWorkflowResponse response = plan(request, user).preview;
            if (response.getStatus() != ru.rasulov.workflow.api.dto.assign.AssignStatus.WOULD_ASSIGN) return response;
            return executor.issue("assign_workflow", user.getKey(), request, planCodec.fingerprint(response),
                    response::withPlan);
        }
        return journal.execute("assign_workflow", user.getKey(),
                () -> namedLock.run(LOCK_NAME, "workflow assignments", () -> apply(request.getPlanId(), user)));
    }

    private AssignWorkflowResponse apply(String planId, ApplicationUser user) {
        return executor.apply("assign_workflow", user.getKey(), planId, AssignWorkflowRequest.class,
                AssignWorkflowResponse::stale, preview -> plan(preview, user), current -> true,
                current -> planCodec.fingerprint(current.preview), current -> writeApproved(current, user));
    }

    private AssignWorkflowResponse writeApproved(PlannedAssignment current, ApplicationUser user) {
        try {
            return write(current.project, current.preview.getProject(), current.workflow, current.assignment,
                    current.preview.getMessage(), user);
        } catch (AssignFailureException e) {
            return AssignWorkflowResponse.failed(false, current.preview.getProject(), current.preview.getWorkflow(),
                    e.getErrorCode(), e.getMessage(), e.getSuggestions(), e.getIssueTypes());
        }
    }

    private static void requireMode(AssignWorkflowRequest request) {
        if (request == null || request.isDryRun() == null) {
            throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        }
        if (request.isDryRun() && request.getPlanId() != null) {
            throw new InvalidRequestException("Preview takes assignment details, not planId.");
        }
        if (!request.isDryRun() && (request.getProject() != null || request.getWorkflow() != null
                || request.isAllIssueTypes() || !request.getIssueTypes().isEmpty() || request.isNewScheme()
                || !request.getStatusMappings().isEmpty() || request.getPlanId() == null
                || request.getPlanId().trim().isEmpty())) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
    }

    private PlannedAssignment plan(AssignWorkflowRequest request, ApplicationUser user) {
        ProjectRef projectRef = null;
        String workflowName = request.getWorkflow() == null ? null : request.getWorkflow().trim();
        try {
            checkShape(request);
            Project project = resolveProject(request.getProject());
            projectRef = new ProjectRef(project.getKey(), project.getName());
            JiraWorkflow workflow = findWorkflow(request.getWorkflow());
            workflowName = workflow.getName();
            List<IssueType> requested = request.isAllIssueTypes() ? null : resolveIssueTypes(request.getIssueTypes());
            Set<String> selectedTypes = null;
            if (requested != null) {
                selectedTypes = new HashSet<String>();
                for (IssueType type : requested) selectedTypes.add(type.getId());
            }
            AssignableWorkflowScheme scheme = schemeManager.getWorkflowSchemeObj(project);
            IssueStatusCounter.Scan scan = counter.scan(project, scheme, selectedTypes, workflowManager);
            if (scan.diagnosis != null) {
                throw new AssignFailureException(AssignErrorCode.JIRA_ERROR, scan.diagnosis);
            }
            AssignmentPlan plan = planner.plan(project, workflow, requested, request.isNewScheme(),
                    scan.usage, request.getStatusMappings(), scheme);
            String warnings = plan.getWarnings().isEmpty() ? null : String.join(" ", plan.getWarnings());
            if (plan.getAction() == SchemeAction.NONE) {
                return new PlannedAssignment(project, workflow, plan,
                        AssignWorkflowResponse.alreadyAssigned(true, projectRef, workflowName, plan.getAssignments()));
            }
            log.info("Dry run: would assign workflow '{}' to project {}: {} scheme (user {})", workflowName,
                    project.getKey(), plan.getAction(), user.getUsername());
            return new PlannedAssignment(project, workflow, plan, AssignWorkflowResponse.wouldAssign(projectRef,
                    workflowName, plannedChange(plan), plan.getAssignments(), warnings));
        } catch (AssignFailureException e) {
            return new PlannedAssignment(null, null, null, AssignWorkflowResponse.failed(true, projectRef,
                    workflowName, e.getErrorCode(), e.getMessage(), e.getSuggestions(), e.getIssueTypes()));
        }
    }

    /** Request-local assignment and resolved native objects used by both preview and write. */
    private static final class PlannedAssignment {
        private final Project project;
        private final JiraWorkflow workflow;
        private final AssignmentPlan assignment;
        private final AssignWorkflowResponse preview;

        private PlannedAssignment(Project project, JiraWorkflow workflow, AssignmentPlan assignment,
                                  AssignWorkflowResponse preview) {
            this.project = project;
            this.workflow = workflow;
            this.assignment = assignment;
            this.preview = preview;
        }
    }

    private AssignWorkflowResponse write(Project project, ProjectRef projectRef, JiraWorkflow workflow,
                                         AssignmentPlan plan, String warnings, ApplicationUser user) {
        MigrationCount moved = new MigrationCount();
        AssignableWorkflowScheme created = null;
        boolean schemeCreateStarted = false;
        boolean schemeWriteStarted = false;
        try {
            if (plan.getAction() == SchemeAction.CREATE) {
                schemeCreateStarted = true;
                created = schemeWriter.create(user, plan.getNewSchemeName(), plan.getScheme(), plan.getEdit());
            }
            List<String> stuck = migrator.migrate(project, plan.workflowsToLeave(), workflow,
                    plan.getStatusMappings(), moved);
            if (!stuck.isEmpty()) {
                throw new IllegalStateException("Issues changed to a status the workflow does not have while the "
                        + "assignment was running: " + String.join(", ", stuck));
            }
            schemeWriteStarted = true;
            if (plan.getAction() == SchemeAction.CREATE) {
                schemeWriter.assign(project, created);
            } else {
                schemeWriter.update(plan.getScheme(), plan.getEdit());
            }
            String unconfirmed = verifier.verify(project, expectedWorkflows(plan));

            SweepResult sweep = sweep(project, plan, workflow, moved);
            SchemeChange change = plan.getAction() == SchemeAction.CREATE
                    ? new SchemeChange(SchemeAction.CREATE, created.getId(), created.getName())
                    : new SchemeChange(SchemeAction.UPDATE, plan.getScheme().getId(), plan.getScheme().getName());
            log.info("Assigned workflow '{}' to project {}: {} scheme '{}', {} issue(s) moved (user {})",
                    workflow.getName(), project.getKey(), plan.getAction(), change.getName(), moved.get(),
                    user.getUsername());
            String message = join(join(warnings, unconfirmed), sweep.message);
            return sweep.complete && unconfirmed == null
                    ? AssignWorkflowResponse.assigned(projectRef, workflow.getName(), change, plan.getAssignments(), message)
                    : AssignWorkflowResponse.partiallyAssigned(projectRef, workflow.getName(), change,
                            plan.getAssignments(), message);
        } catch (JournalCancelledException | JournalCapacityException | AssignFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            boolean mayHaveWritten = schemeCreateStarted || moved.attempted() > 0 || schemeWriteStarted;
            if (!mayHaveWritten) {
                throw e;
            }
            return afterError(e, project, projectRef, workflow, plan, warnings, created, moved, schemeCreateStarted,
                    schemeWriteStarted);
        }
    }

    /**
     * An error stopped {@link #write} after a native write may have been made. Nothing is repaired, retried or rolled
     * back and nothing is written here: Jira is only read, to separate what is confirmed from what is not. The status
     * is {@code PARTIALLY_ASSIGNED} when the readback shows the requested resolution after the scheme write had
     * started, otherwise {@code UNKNOWN}; neither claims the assignment is complete (the final pass for issues created
     * during the call did not run).
     */
    private AssignWorkflowResponse afterError(RuntimeException error, Project project, ProjectRef projectRef,
                                              JiraWorkflow workflow, AssignmentPlan plan, String warnings,
                                              AssignableWorkflowScheme created, MigrationCount moved,
                                              boolean schemeCreateStarted, boolean schemeWriteStarted) {
        log.error("Assigning workflow '{}' to project {} stopped after a write may have been made: {} issue(s) "
                + "confirmed moved, {} attempted", workflow.getName(), project.getKey(), moved.get(), moved.attempted(),
                error);
        AssignmentVerifier.Readback readback = verifier.readback(project, expectedWorkflows(plan));
        boolean schemeConfirmed = readback.confirmed && schemeWriteStarted;
        SchemeChange change;
        if (plan.getAction() == SchemeAction.CREATE) {
            change = created != null ? new SchemeChange(SchemeAction.CREATE, created.getId(), created.getName())
                    : new SchemeChange(SchemeAction.CREATE, null, plan.getNewSchemeName());
        } else {
            change = new SchemeChange(SchemeAction.UPDATE, plan.getScheme().getId(), plan.getScheme().getName());
        }
        List<String> confirmed = new ArrayList<String>();
        List<String> unconfirmed = new ArrayList<String>();
        if (created != null) {
            confirmed.add("workflow scheme '" + created.getName() + "' (id " + created.getId() + ") was created");
        } else if (schemeCreateStarted) {
            unconfirmed.add("whether the new workflow scheme '" + plan.getNewSchemeName() + "' was created");
        }
        confirmed.add(moved.get() + " issue(s) moved to '" + workflow.getName() + "' (each read back after its move)");
        if (moved.attempted() > moved.get()) {
            unconfirmed.add("the issue whose move was in progress when the error occurred");
        }
        if (schemeConfirmed) {
            confirmed.add("Jira now resolves the requested issue type(s) to '" + workflow.getName()
                    + "' and reports the workflow active (read after the error)");
        } else {
            unconfirmed.add("the workflow scheme assignment (" + (schemeWriteStarted
                    ? "readback: " + readback.detail : "it was not started; readback: " + readback.detail) + ")");
        }
        if (schemeConfirmed) {
            unconfirmed.add("the final pass for issues created while the call was running (it did not run)");
        }
        String message = join(warnings, "The assignment stopped with " + error.getClass().getSimpleName()
                + " (details are in the Jira log). Confirmed: " + String.join("; ", confirmed) + ". Not confirmed: "
                + String.join("; ", unconfirmed) + ". Nothing was repaired, retried or rolled back; do not start the "
                + "same plan again, read get_project_workflows and ask a Jira administrator to check issues still on "
                + "the old workflow.");
        return schemeConfirmed
                ? AssignWorkflowResponse.partiallyAssigned(projectRef, workflow.getName(), change,
                        plan.getAssignments(), message)
                : AssignWorkflowResponse.unknown(projectRef, workflow.getName(), change, plan.getAssignments(),
                        message);
    }

    /**
     * Issues created between planning and assigning still run the old workflow. Once the scheme is in place no new
     * one appears, so one more pass finds them all. Failing here does not undo the assignment: it is already correct
     * for everything else, so the user is told what is left.
     */
    private SweepResult sweep(Project project, AssignmentPlan plan, JiraWorkflow workflow, MigrationCount moved) {
        int before = moved.get();
        try {
            journal.checkpoint();
            List<String> stuck = migrator.migrate(project, plan.workflowsToLeave(), workflow,
                    plan.getStatusMappings(), moved);
            String message = null;
            if (moved.get() > before) {
                message = (moved.get() - before) + " issue(s) created while the call was running were moved too.";
            }
            if (!stuck.isEmpty()) {
                return SweepResult.partial(join(message, "Issues created while the call was running are in a status "
                        + "the new workflow does not have and were not moved: " + keys(stuck) + ". Tell the user and "
                        + "ask a Jira administrator to inspect native workflow entries for the affected issue types. "
                        + "get_project_workflows reads configuration, not native entries. No automatic repair is performed."));
            }
            return SweepResult.complete(message);
        } catch (JournalCancelledException | JournalCapacityException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("The final pass for issues created during the assignment to project {} failed", project.getKey(),
                    e);
            return SweepResult.partial("The workflow scheme was assigned, but the final check for issues created "
                    + "while the call was running failed (details are in the Jira log). Ask a Jira administrator to "
                    + "inspect native workflow entries for the affected issue types; get_project_workflows reads "
                    + "configuration, not native entries. No automatic repair is performed.");
        }
    }

    /** Issue type id to the workflow the project must resolve to after the write. */
    private static Map<String, String> expectedWorkflows(AssignmentPlan plan) {
        Map<String, String> expected = new LinkedHashMap<String, String>();
        for (IssueTypeAssignment assignment : plan.getAssignments()) {
            expected.put(assignment.getIssueType().getId(), assignment.getTo());
        }
        return expected;
    }

    /** What a dry run reports for the scheme: a scheme it would create has no id yet. */
    private static SchemeChange plannedChange(AssignmentPlan plan) {
        if (plan.getAction() == SchemeAction.UPDATE) {
            return new SchemeChange(SchemeAction.UPDATE, plan.getScheme().getId(), plan.getScheme().getName());
        }
        return new SchemeChange(SchemeAction.CREATE, null, plan.getNewSchemeName());
    }

    private static void checkShape(AssignWorkflowRequest request) {
        if (request.getWorkflow() == null || request.getWorkflow().trim().isEmpty()) {
            throw new AssignFailureException(AssignErrorCode.VALIDATION_ERROR,
                    "The workflow name is required: the name of an existing workflow.");
        }
        boolean named = !request.getIssueTypes().isEmpty();
        if (request.isAllIssueTypes() == named) {
            throw new AssignFailureException(AssignErrorCode.VALIDATION_ERROR, request.isAllIssueTypes()
                    ? "allIssueTypes: true and a list of issueTypes were both given. Give exactly one."
                    : "Say which issue types get the workflow: allIssueTypes: true for the whole project, or a list of "
                            + "issueTypes. Ask the user, do not guess.");
        }
        if (request.getIssueTypes().size() > WorkflowAssignLimits.MAX_ISSUE_TYPES) {
            throw new AssignFailureException(AssignErrorCode.VALIDATION_ERROR, "At most "
                    + WorkflowAssignLimits.MAX_ISSUE_TYPES + " issue types per call, got "
                    + request.getIssueTypes().size() + ". Use allIssueTypes: true or split the call.");
        }
    }

    private Project resolveProject(String keyOrName) {
        try {
            return projectResolver.resolve(keyOrName);
        } catch (LookupFailure e) {
            throw new AssignFailureException(code(e), e.getMessage(), e.getSuggestions());
        }
    }

    private List<IssueType> resolveIssueTypes(List<String> namesOrIds) {
        List<IssueType> types = new ArrayList<IssueType>();
        Set<String> seen = new HashSet<String>();
        for (String nameOrId : namesOrIds) {
            IssueType type;
            try {
                type = issueTypeResolver.resolve(nameOrId);
            } catch (LookupFailure e) {
                throw new AssignFailureException(code(e), e.getMessage(), e.getSuggestions());
            }
            if (!seen.add(type.getId())) {
                throw new AssignFailureException(AssignErrorCode.VALIDATION_ERROR,
                        "Issue type '" + type.getName() + "' is listed twice. List each issue type once.");
            }
            types.add(type);
        }
        return types;
    }

    /** Jira compares workflow names without case; so do we. */
    private JiraWorkflow findWorkflow(String name) {
        String wanted = normalize(name);
        List<String> names = new ArrayList<String>();
        for (JiraWorkflow workflow : workflowManager.getWorkflows()) {
            if (normalize(workflow.getName()).equals(wanted)) {
                return workflow;
            }
            String candidate = normalize(workflow.getName());
            if (candidate.contains(wanted) || wanted.contains(candidate)) {
                names.add(workflow.getName());
            }
        }
        Collections.sort(names);
        throw new AssignFailureException(AssignErrorCode.UNKNOWN_WORKFLOW, "No workflow is named '" + name.trim()
                + "'. Use one of suggestions, or create the workflow first.",
                names.size() > 5 ? new ArrayList<String>(names.subList(0, 5)) : names);
    }

    private static AssignErrorCode code(LookupFailure failure) {
        switch (failure.getKind()) {
            case UNKNOWN_PROJECT:
                return AssignErrorCode.UNKNOWN_PROJECT;
            case UNKNOWN_ISSUE_TYPE:
                return AssignErrorCode.UNKNOWN_ISSUE_TYPE;
            default:
                return AssignErrorCode.VALIDATION_ERROR;
        }
    }

    private static String keys(List<String> keys) {
        List<String> shown = keys.size() > WorkflowAssignLimits.MAX_ISSUE_KEYS_SHOWN
                ? keys.subList(0, WorkflowAssignLimits.MAX_ISSUE_KEYS_SHOWN) : keys;
        return String.join(", ", shown) + (keys.size() > shown.size() ? " and " + (keys.size() - shown.size())
                + " more" : "");
    }

    private static String join(String first, String second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first + " " + second;
    }
}
