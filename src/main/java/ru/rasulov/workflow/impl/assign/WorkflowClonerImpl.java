package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.workflow.api.WorkflowAssignLimits;
import ru.rasulov.workflow.api.WorkflowCloner;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.assign.SchemeChange;
import ru.rasulov.workflow.api.dto.clone.CloneErrorCode;
import ru.rasulov.workflow.api.dto.clone.CloneScope;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsRequest;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsResponse;
import ru.rasulov.workflow.impl.WorkflowWriter;
import ru.rasulov.workflow.impl.undo.WorkflowSnapshots;
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
import java.util.TreeMap;

import static ru.rasulov.common.impl.Normalizer.normalize;

/**
 * Orchestration of a clone: administrator check, read the request, plan, then (unless it is a dry run) write under a
 * lock in an order that can be undone step by step: copies of the workflows, the copy of the scheme, the move of the
 * issues, the assignment. Originals are never changed or deleted.
 */
@ExportAsService(WorkflowCloner.class)
@Named
public class WorkflowClonerImpl implements WorkflowCloner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowClonerImpl.class);

    private final AdministratorGuard administratorGuard;
    private final NamedLock namedLock;
    private final ProjectResolver projectResolver;
    private final IssueStatusCounter counter;
    private final ClonePlanner planner;
    private final WorkflowWriter workflowWriter;
    private final SchemeWriter schemeWriter;
    private final AssignmentVerifier verifier;
    private final IssueMigrator migrator;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec planCodec;
    private final ChangeJournal journal;
    private final OfBizDelegator delegator;

    @Inject
    public WorkflowClonerImpl(AdministratorGuard administratorGuard, NamedLock namedLock,
                              ProjectResolver projectResolver, IssueStatusCounter counter, ClonePlanner planner,
                              WorkflowWriter workflowWriter, SchemeWriter schemeWriter, AssignmentVerifier verifier,
                              IssueMigrator migrator, PreviewPlanExecutor executor, ActionPlanCodec planCodec,
                              ChangeJournal journal, @ComponentImport OfBizDelegator delegator) {
        this.administratorGuard = administratorGuard;
        this.namedLock = namedLock;
        this.projectResolver = projectResolver;
        this.counter = counter;
        this.planner = planner;
        this.workflowWriter = workflowWriter;
        this.schemeWriter = schemeWriter;
        this.verifier = verifier;
        this.migrator = migrator;
        this.executor = executor;
        this.planCodec = planCodec;
        this.journal = journal;
        this.delegator = delegator;
    }

    @Override
    public CloneWorkflowsResponse clone(CloneWorkflowsRequest request) {
        ApplicationUser user = administratorGuard.requireAdministrator();
        requireMode(request);
        if (request.isDryRun()) {
            PlannedClone current = plan(request, user);
            CloneWorkflowsResponse response = current.preview;
            if (response.getStatus() != ru.rasulov.workflow.api.dto.clone.CloneStatus.WOULD_CLONE) return response;
            return executor.issue("clone_project_workflows", user.getKey(), request, fingerprint(current),
                    response::withPlan);
        }
        return journal.execute("clone_project_workflows", user.getKey(),
                () -> namedLock.run(WorkflowAssignerImpl.LOCK_NAME, "workflow clones",
                        () -> apply(request.getPlanId(), user)));
    }

    private CloneWorkflowsResponse apply(String planId, ApplicationUser user) {
        return executor.apply("clone_project_workflows", user.getKey(), planId, CloneWorkflowsRequest.class,
                CloneWorkflowsResponse::stale, preview -> plan(preview, user), current -> true,
                this::fingerprint, current -> writeApproved(current, user));
    }

    private CloneWorkflowsResponse writeApproved(PlannedClone current, ApplicationUser user) {
        try {
            return write(current.project, current.preview.getProject(), current.clonePlan,
                    current.preview.getMessage(), user, current.sourceFingerprints);
        } catch (CloneFailureException e) {
            return CloneWorkflowsResponse.failed(false, current.preview.getProject(), current.clonePlan.getScope(),
                    e.getErrorCode(), e.getMessage(), e.getSuggestions());
        }
    }

    private static void requireMode(CloneWorkflowsRequest request) {
        if (request == null || request.isDryRun() == null) {
            throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        }
        if (request.isDryRun() && request.getPlanId() != null) {
            throw new InvalidRequestException("Preview takes clone details, not planId.");
        }
        if (!request.isDryRun() && (request.getProject() != null || request.getScope() != null
                || !request.getWorkflows().isEmpty() || request.getSchemeName() != null
                || !request.getWorkflowNames().isEmpty() || request.getPlanId() == null
                || request.getPlanId().trim().isEmpty())) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
    }

    private PlannedClone plan(CloneWorkflowsRequest request, ApplicationUser user) {
        ProjectRef projectRef = null;
        try {
            checkShape(request);
            Project project = resolveProject(request.getProject());
            projectRef = new ProjectRef(project.getKey(), project.getName());

            ClonePlan plan = planner.plan(project, request, IssueUsage.lazy(() -> counter.count(project)));
            String warnings = plan.getWarnings().isEmpty() ? null : String.join(" ", plan.getWarnings());
            if (plan.getAction() == SchemeAction.NONE) {
                return new PlannedClone(project, plan, CloneWorkflowsResponse.alreadySeparate(true, projectRef,
                        request.getScope(), plan.getCurrentScheme(), plan.views()));
            }
            log.info("Dry run: would clone the workflows of project {}: {} scheme, {} workflow(s) (user {})",
                    project.getKey(), plan.getAction(), plan.toCopy().size(), user.getUsername());
            return new PlannedClone(project, plan, CloneWorkflowsResponse.wouldClone(projectRef, request.getScope(),
                    plan.getCurrentScheme(), plannedChange(plan), plan.views(), warnings));
        } catch (CloneFailureException e) {
            return new PlannedClone(null, null, CloneWorkflowsResponse.failed(true, projectRef, request.getScope(),
                    e.getErrorCode(), e.getMessage(), e.getSuggestions()));
        }
    }

    private String fingerprint(PlannedClone plan) {
        Map<String, Object> confirmation = new LinkedHashMap<>();
        confirmation.put("preview", plan.preview);
        confirmation.put("sources", plan.sourceFingerprints);
        confirmation.put("sourceHasDraft", plan.sourceHasDraft);
        return planCodec.fingerprint(confirmation);
    }

    /** Request-local clone plan and resolved project used by both preview and write. */
    private final class PlannedClone {
        private final Project project;
        private final ClonePlan clonePlan;
        private final CloneWorkflowsResponse preview;
        private final Map<String, String> sourceFingerprints = new TreeMap<>();
        /** Which copied sources have a draft now; the draft is left out of the copy, only its presence is confirmed. */
        private final Map<String, Boolean> sourceHasDraft = new TreeMap<>();

        private PlannedClone(Project project, ClonePlan clonePlan, CloneWorkflowsResponse preview) {
            this.project = project;
            this.clonePlan = clonePlan;
            this.preview = preview;
            if (clonePlan != null && preview.getStatus() == ru.rasulov.workflow.api.dto.clone.CloneStatus.WOULD_CLONE) {
                for (ClonePlan.PlannedWorkflow workflow : clonePlan.toCopy()) {
                    sourceFingerprints.put(workflow.getName(),
                            WorkflowSnapshots.workflowFingerprint(workflow.getSource(), delegator));
                    sourceHasDraft.put(workflow.getName(), workflow.sourceHasDraft());
                }
            }
        }
    }

    private CloneWorkflowsResponse write(Project project, ProjectRef projectRef, ClonePlan plan, String warnings,
                                         ApplicationUser user, Map<String, String> sourceFingerprints) {
        Map<String, JiraWorkflow> copies = new LinkedHashMap<String, JiraWorkflow>();
        MigrationCount moved = new MigrationCount();
        AssignableWorkflowScheme createdScheme = null;
        String copying = null;
        boolean schemeCreateStarted = false;
        boolean schemeWriteStarted = false;
        try {
            for (ClonePlan.PlannedWorkflow workflow : plan.toCopy()) {
                journal.checkpoint();
                // Plugin locks do not exclude Jira admin edits. Check the confirmed source again at the write boundary.
                if (!sourceFingerprints.get(workflow.getName()).equals(
                        WorkflowSnapshots.workflowFingerprint(workflow.getSource(), delegator))) {
                    return CloneWorkflowsResponse.failed(false, projectRef, plan.getScope(), CloneErrorCode.PREVIEW_STALE,
                            "Source workflow '" + workflow.getName() + "' changed before copying. "
                                    + copies.size() + " completed workflow copy/copies are retained. Run preview again.", null);
                }
                copying = workflow.getNewName();
                JiraWorkflow copy = workflowWriter.copy(user, workflow.getSource(), workflow.getNewName());
                if (!copy.getLinkedStatusIds().equals(workflow.getSource().getLinkedStatusIds())) {
                    throw new IllegalStateException("The copy '" + copy.getName() + "' does not have the statuses of "
                            + "'" + workflow.getName() + "'");
                }
                copies.put(workflow.getName(), copy);
                copying = null;
            }
            if (plan.getAction() == SchemeAction.CREATE) {
                schemeCreateStarted = true;
                createdScheme = schemeWriter.create(user, plan.getNewSchemeName(), plan.getScheme(), plan.getEdit());
            }
            List<String> stuck = migrator.migrateByWorkflow(project, plan.workflowsToLeave(), copies, moved);
            if (!stuck.isEmpty()) {
                throw new IllegalStateException("Issues are in a status the copy does not have: "
                        + String.join(", ", stuck));
            }
            schemeWriteStarted = true;
            if (plan.getAction() == SchemeAction.CREATE) {
                schemeWriter.assign(project, createdScheme);
            } else {
                schemeWriter.update(plan.getScheme(), plan.getEdit());
            }
            String unconfirmed = verifier.verify(project, expectedWorkflows(plan, copies));

            String sweepMessage = sweep(project, plan, copies, moved);
            SchemeChange change = plan.getAction() == SchemeAction.CREATE
                    ? new SchemeChange(SchemeAction.CREATE, createdScheme.getId(), createdScheme.getName())
                    : new SchemeChange(SchemeAction.UPDATE, plan.getScheme().getId(), plan.getScheme().getName());
            log.info("Cloned the workflows of project {}: {} scheme '{}', {} workflow(s) copied, {} issue(s) moved "
                    + "(user {})", project.getKey(), plan.getAction(), change.getName(), copies.size(), moved.get(),
                    user.getUsername());
            String message = join(join(warnings, unconfirmed), sweepMessage);
            if (unconfirmed != null) {
                return CloneWorkflowsResponse.partiallyCloned(projectRef, plan.getScope(), plan.getCurrentScheme(),
                        change, plan.views(), message);
            }
            return CloneWorkflowsResponse.cloned(projectRef, plan.getScope(), plan.getCurrentScheme(), change,
                    plan.views(), message);
        } catch (JournalCancelledException | JournalCapacityException | CloneFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            boolean mayHaveWritten = copying != null || !copies.isEmpty() || schemeCreateStarted
                    || moved.attempted() > 0 || schemeWriteStarted;
            if (!mayHaveWritten) {
                throw e;
            }
            return afterError(e, project, projectRef, plan, warnings, copies, copying, createdScheme, moved,
                    schemeCreateStarted, schemeWriteStarted);
        }
    }

    /**
     * An error stopped {@link #write} after a native write may have been made. Nothing is repaired, retried or rolled
     * back and nothing is written here: Jira is only read, to separate what is confirmed from what is not.
     * {@code PARTIALLY_CLONED} when the readback shows the copies in use after the scheme write had started,
     * otherwise {@code UNKNOWN}; neither claims the clone is complete.
     */
    private CloneWorkflowsResponse afterError(RuntimeException error, Project project, ProjectRef projectRef,
                                              ClonePlan plan, String warnings, Map<String, JiraWorkflow> copies,
                                              String copying, AssignableWorkflowScheme createdScheme,
                                              MigrationCount moved, boolean schemeCreateStarted,
                                              boolean schemeWriteStarted) {
        log.error("Cloning the workflows of project {} stopped after a write may have been made: {} copy/copies, {} "
                + "issue(s) confirmed moved, {} attempted", project.getKey(), copies.size(), moved.get(),
                moved.attempted(), error);
        AssignmentVerifier.Readback readback = verifier.readback(project, expectedWorkflows(plan, copies));
        boolean schemeConfirmed = readback.confirmed && schemeWriteStarted;
        SchemeChange change;
        if (plan.getAction() == SchemeAction.CREATE) {
            change = createdScheme != null
                    ? new SchemeChange(SchemeAction.CREATE, createdScheme.getId(), createdScheme.getName())
                    : new SchemeChange(SchemeAction.CREATE, null, plan.getNewSchemeName());
        } else {
            change = new SchemeChange(SchemeAction.UPDATE, plan.getScheme().getId(), plan.getScheme().getName());
        }
        List<String> confirmed = new ArrayList<String>();
        List<String> unconfirmed = new ArrayList<String>();
        List<String> copyNames = new ArrayList<String>();
        for (JiraWorkflow copy : copies.values()) {
            copyNames.add("'" + copy.getName() + "'");
        }
        if (!copyNames.isEmpty()) {
            confirmed.add("workflow copies " + String.join(", ", copyNames) + " were created");
        }
        if (copying != null) {
            unconfirmed.add("whether the copy '" + copying + "' exists and is complete");
        }
        if (createdScheme != null) {
            confirmed.add("workflow scheme '" + createdScheme.getName() + "' (id " + createdScheme.getId()
                    + ") was created");
        } else if (schemeCreateStarted) {
            unconfirmed.add("whether the new workflow scheme '" + plan.getNewSchemeName() + "' was created");
        }
        if (moved.attempted() > 0) {
            confirmed.add(moved.get() + " issue(s) moved to the copies (each read back after its move)");
        }
        if (moved.attempted() > moved.get()) {
            unconfirmed.add("the issue whose move was in progress when the error occurred");
        }
        if (schemeConfirmed) {
            confirmed.add("Jira now resolves the project's issue types to the copies and reports them active "
                    + "(read after the error)");
            unconfirmed.add("the final pass for issues created while the call was running (it did not run)");
        } else {
            unconfirmed.add("the workflow scheme assignment (" + (schemeWriteStarted
                    ? "readback: " + readback.detail : "it was not started; readback: " + readback.detail) + ")");
        }
        String message = join(warnings, "The clone stopped with " + error.getClass().getSimpleName()
                + " (details are in the Jira log). Confirmed: "
                + (confirmed.isEmpty() ? "nothing" : String.join("; ", confirmed)) + ". Not confirmed: "
                + String.join("; ", unconfirmed) + ". Nothing was repaired, retried or rolled back; do not start the "
                + "same plan again, read get_project_workflows and ask a Jira administrator to check the project's "
                + "workflow scheme and issues still on the old workflows.");
        return schemeConfirmed
                ? CloneWorkflowsResponse.partiallyCloned(projectRef, plan.getScope(), plan.getCurrentScheme(), change,
                        plan.views(), message)
                : CloneWorkflowsResponse.unknown(projectRef, plan.getScope(), plan.getCurrentScheme(), change,
                        plan.views(), message);
    }

    /** Issue type id to the copy the project must resolve to after the write; types without a copy are not checked. */
    private static Map<String, String> expectedWorkflows(ClonePlan plan, Map<String, JiraWorkflow> copies) {
        Map<String, String> expected = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> leaving : plan.workflowsToLeave().entrySet()) {
            JiraWorkflow copy = copies.get(leaving.getValue());
            if (copy != null) {
                expected.put(leaving.getKey(), copy.getName());
            }
        }
        return expected;
    }

    /**
     * Issues created between planning and assigning still run the old workflow. Once the scheme is in place no new one
     * appears, so one more pass finds them all. Failing here does not undo the clone: it is already correct for
     * everything else, so the user is told what is left.
     */
    private String sweep(Project project, ClonePlan plan, Map<String, JiraWorkflow> copies, MigrationCount moved) {
        if (copies.isEmpty()) {
            return null;
        }
        int before = moved.get();
        try {
            journal.checkpoint();
            List<String> stuck = migrator.migrateByWorkflow(project, plan.workflowsToLeave(), copies, moved);
            String message = null;
            if (moved.get() > before) {
                message = (moved.get() - before) + " issue(s) created while the call was running were moved too.";
            }
            if (!stuck.isEmpty()) {
                message = join(message, "Issues created while the call was running are in a status the copy does not "
                        + "have and were not moved: " + String.join(", ", stuck) + ". Tell the user.");
            }
            return message;
        } catch (JournalCancelledException | JournalCapacityException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("The final pass for issues created during the clone of project {} failed", project.getKey(), e);
            return "The clone is done, but the check for issues created while it was running failed (details are in "
                    + "the Jira log). Ask a Jira administrator to inspect native workflow entries for the affected "
                    + "issue types. get_project_workflows reads configuration, not native entries.";
        }
    }


    /** What a dry run reports for the scheme: a scheme it would create has no id yet. */
    private static SchemeChange plannedChange(ClonePlan plan) {
        if (plan.getAction() == SchemeAction.UPDATE) {
            return new SchemeChange(SchemeAction.UPDATE, plan.getScheme().getId(), plan.getScheme().getName());
        }
        return new SchemeChange(SchemeAction.CREATE, null, plan.getNewSchemeName());
    }

    /** The shape of the request itself; whether the names exist is for the planner. */
    private static void checkShape(CloneWorkflowsRequest request) {
        if (request.getScope() == null) {
            throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR, "The scope is required: SCHEME_ONLY (only "
                    + "the scheme), ALL_WORKFLOWS (the scheme and all its workflows) or SELECTED (the scheme and the "
                    + "workflows named in 'workflows'). Ask the user, do not guess.");
        }
        boolean named = !request.getWorkflows().isEmpty();
        if (request.getScope() == CloneScope.SELECTED && !named) {
            throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR, "Scope SELECTED needs 'workflows': the "
                    + "names of the workflows to copy. Ask the user which ones.");
        }
        if (request.getScope() != CloneScope.SELECTED && named) {
            throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR, "'workflows' only goes with scope "
                    + "SELECTED. Use SELECTED, or leave 'workflows' out.");
        }
        if (request.getWorkflows().size() > WorkflowAssignLimits.MAX_WORKFLOWS_TO_CLONE) {
            throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR, "At most "
                    + WorkflowAssignLimits.MAX_WORKFLOWS_TO_CLONE + " workflows per call, got "
                    + request.getWorkflows().size() + ". Split the call.");
        }
        for (String name : request.getWorkflows()) {
            if (name == null || name.trim().isEmpty()) {
                throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR,
                        "'workflows' has an empty name. Each is the name of a workflow.");
            }
        }
        if (request.getSchemeName() != null) {
            checkName("schemeName", request.getSchemeName());
        }
        Set<String> chosen = new HashSet<String>();
        for (Map.Entry<String, String> entry : request.getWorkflowNames().entrySet()) {
            checkName("workflowNames['" + entry.getKey() + "']", entry.getValue());
            if (!chosen.add(normalize(entry.getValue()))) {
                throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR, "'workflowNames' gives the name '"
                        + entry.getValue().trim() + "' to two copies. Every copy needs its own name.");
            }
        }
    }

    private static void checkName(String what, String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR, what + " is blank. Give a name or leave "
                    + "it out to name it after the project.");
        }
        if (name.trim().length() > ClonePlanner.MAX_NAME_LENGTH) {
            throw new CloneFailureException(CloneErrorCode.VALIDATION_ERROR, what + " is longer than "
                    + ClonePlanner.MAX_NAME_LENGTH + " characters.");
        }
    }

    private Project resolveProject(String keyOrName) {
        try {
            return projectResolver.resolve(keyOrName);
        } catch (LookupFailure e) {
            throw new CloneFailureException(e.getKind() == LookupFailure.Kind.UNKNOWN_PROJECT
                    ? CloneErrorCode.UNKNOWN_PROJECT : CloneErrorCode.VALIDATION_ERROR, e.getMessage(),
                    e.getSuggestions());
        }
    }

    private static List<String> limited(List<String> keys) {
        return keys.size() > WorkflowAssignLimits.MAX_ISSUE_KEYS_SHOWN
                ? keys.subList(0, WorkflowAssignLimits.MAX_ISSUE_KEYS_SHOWN) : keys;
    }

    private static String join(String first, String second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first + " " + second;
    }
}
