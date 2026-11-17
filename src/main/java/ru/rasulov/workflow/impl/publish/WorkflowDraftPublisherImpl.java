package ru.rasulov.workflow.impl.publish;

import com.atlassian.jira.bc.JiraServiceContext;
import com.atlassian.jira.bc.JiraServiceContextImpl;
import com.atlassian.jira.bc.workflow.WorkflowService;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowScheme;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.opensymphony.workflow.loader.AbstractDescriptor;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.workflow.api.WorkflowAssignLimits;
import ru.rasulov.workflow.api.WorkflowDraftPublisher;
import ru.rasulov.workflow.api.dto.publish.DraftChanges;
import ru.rasulov.workflow.api.dto.publish.PublishErrorCode;
import ru.rasulov.workflow.api.dto.publish.PublishStatus;
import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftRequest;
import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftResponse;
import ru.rasulov.workflow.impl.assign.WorkflowAssignerImpl;
import ru.rasulov.workflow.impl.edit.DescriptorDiff;
import ru.rasulov.workflow.impl.edit.StepOccupancy;
import ru.rasulov.workflow.impl.undo.WorkflowSnapshots;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Publishes the draft of a shared workflow through Jira's own publication, under the workflow lock and only for the
 * confirmed plan: the active version, the draft and the set of schemes and projects must be what the preview showed.
 * The previous active version is not kept, so there is no undo.
 */
@ExportAsService(WorkflowDraftPublisher.class)
@Named
public class WorkflowDraftPublisherImpl implements WorkflowDraftPublisher {

    static final String TOOL = "publish_workflow_draft";
    private static final Logger log = LoggerFactory.getLogger(WorkflowDraftPublisherImpl.class);

    private final AdministratorGuard guard;
    private final NamedLock namedLock;
    private final WorkflowManager workflowManager;
    private final WorkflowService workflowService;
    private final WorkflowSchemeManager schemeManager;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec planCodec;
    private final ChangeJournal journal;
    private final OfBizDelegator delegator;
    private final StepOccupancy stepOccupancy;

    @Inject
    public WorkflowDraftPublisherImpl(AdministratorGuard guard, NamedLock namedLock,
                                      @ComponentImport WorkflowManager workflowManager,
                                      @ComponentImport WorkflowService workflowService,
                                      @ComponentImport WorkflowSchemeManager schemeManager, PreviewPlanExecutor executor,
                                      ActionPlanCodec planCodec, ChangeJournal journal,
                                      @ComponentImport OfBizDelegator delegator, StepOccupancy stepOccupancy) {
        this.guard = guard;
        this.namedLock = namedLock;
        this.workflowManager = workflowManager;
        this.workflowService = workflowService;
        this.schemeManager = schemeManager;
        this.executor = executor;
        this.planCodec = planCodec;
        this.journal = journal;
        this.delegator = delegator;
        this.stepOccupancy = stepOccupancy;
    }

    @Override
    public PublishWorkflowDraftResponse publish(PublishWorkflowDraftRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request);
        if (request.isDryRun()) {
            Planned current = plan(request.getWorkflow(), user);
            if (current.preview.getStatus() != PublishStatus.WOULD_PUBLISH) return current.preview;
            return executor.issue(TOOL, user.getKey(), request, current.fingerprint, current.preview::withPlan);
        }
        return journal.execute(TOOL, user.getKey(), () -> namedLock.run(WorkflowAssignerImpl.LOCK_NAME,
                "workflow draft publications", () -> apply(request.getPlanId(), user)));
    }

    private static void requireMode(PublishWorkflowDraftRequest request) {
        if (request == null || request.isDryRun() == null) {
            throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        }
        if (request.isDryRun() && request.getPlanId() != null) {
            throw new InvalidRequestException("Preview takes the workflow name, not planId.");
        }
        if (!request.isDryRun() && (request.getWorkflow() != null || request.getPlanId() == null
                || request.getPlanId().trim().isEmpty())) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
    }

    private PublishWorkflowDraftResponse apply(String planId, ApplicationUser user) {
        return executor.apply(TOOL, user.getKey(), planId, PublishWorkflowDraftRequest.class,
                PublishWorkflowDraftResponse::stale, preview -> plan(preview.getWorkflow(), user),
                current -> current.fingerprint != null, current -> current.fingerprint,
                current -> writePublish(current, user));
    }

    private PublishWorkflowDraftResponse writePublish(Planned current, ApplicationUser user) {
        journal.checkpoint();
        JiraServiceContext context = new JiraServiceContextImpl(user);
        workflowService.overwriteActiveWorkflow(context, current.name);
        if (context.getErrorCollection().hasAnyErrors()) {
            return PublishWorkflowDraftResponse.failed(false, current.name, PublishErrorCode.JIRA_ERROR,
                    "Jira did not publish the draft of '" + current.name + "': "
                            + errors(context.getErrorCollection()) + " Run preview again before any new attempt.",
                    null);
        }
        JiraWorkflow now = workflowManager.getWorkflow(current.name);
        if (workflowManager.getDraftWorkflow(current.name) != null || now == null
                || !current.draftDefinition.equals(definition(now.getDescriptor()))) {
            return PublishWorkflowDraftResponse.failed(false, current.name, PublishErrorCode.JIRA_ERROR,
                    "Jira reported no error, but the active workflow '" + current.name + "' is not the confirmed "
                            + "draft, or the draft is still there. The state is uncertain: do not repeat the "
                            + "publication; ask a Jira administrator to check the workflow.", null);
        }
        log.info("Published the draft of workflow '{}' for {} project(s) (user {})", current.name,
                current.projects.size(), user.getUsername());
        return PublishWorkflowDraftResponse.published(current.name, current.changes, current.schemes.size(),
                current.projects.size(), shown(current.projects), "The draft of '" + current.name + "' is now "
                        + "the active workflow for " + current.projects.size() + " project(s). Preview "
                        + "clone_project_workflows again before copying: an earlier clone plan is stale.");
    }

    /** Everything the preview shows and the plan is bound to; read again under the lock before publishing. */
    private Planned plan(String requested, ApplicationUser user) {
        String name = requested == null ? "" : requested.trim();
        if (name.isEmpty()) {
            return new Planned(PublishWorkflowDraftResponse.failed(true, null, PublishErrorCode.VALIDATION_ERROR,
                    "'workflow' is blank. Give the exact name of the workflow whose draft is published.", null));
        }
        JiraWorkflow active = workflowManager.getWorkflow(name);
        if (active == null) {
            return new Planned(PublishWorkflowDraftResponse.failed(true, name, PublishErrorCode.UNKNOWN_WORKFLOW,
                    "No workflow is named '" + name + "'. suggestions are the workflows that have a draft.",
                    workflowsWithDrafts()));
        }
        JiraWorkflow draft = workflowManager.getDraftWorkflow(active.getName());
        if (draft == null) {
            return new Planned(PublishWorkflowDraftResponse.noDraft(true, active.getName()));
        }
        TreeSet<Integer> removedSteps = DescriptorDiff.stepIds(active.getDescriptor());
        removedSteps.removeAll(DescriptorDiff.stepIds(draft.getDescriptor()));
        Map<Integer, Long> occupancy = removedSteps.isEmpty() ? Collections.emptyMap()
                : stepOccupancy.occupied(active.getName(), removedSteps);
        Map<Integer, Long> occupied = new TreeMap<>();
        for (Map.Entry<Integer, Long> entry : occupancy.entrySet()) {
            if (entry.getValue() > 0) occupied.put(entry.getKey(), entry.getValue());
        }
        if (!occupied.isEmpty()) {
            return new Planned(PublishWorkflowDraftResponse.failed(true, active.getName(),
                    PublishErrorCode.PUBLISH_REFUSED, "Cannot publish the draft of '" + active.getName()
                            + "': removed steps contain issues (step id to issue count): " + occupied
                            + ". Nothing was changed.", null));
        }
        JiraServiceContext context = new JiraServiceContextImpl(user);
        workflowService.validateOverwriteWorkflow(context, active.getName());
        if (context.getErrorCollection().hasAnyErrors()) {
            return new Planned(PublishWorkflowDraftResponse.failed(true, active.getName(),
                    PublishErrorCode.PUBLISH_REFUSED, "Jira refuses to publish the draft of '" + active.getName()
                            + "': " + errors(context.getErrorCollection()) + " Nothing was changed.", null));
        }
        return new Planned(active, draft, consumerSchemes(active), consumerProjects(active), occupancy);
    }

    private final class Planned {
        private final String name;
        private final PublishWorkflowDraftResponse preview;
        private final String fingerprint;
        private final DraftChanges changes;
        private final TreeSet<Long> schemes;
        private final Map<String, String> projects;
        private final String draftDefinition;

        private Planned(PublishWorkflowDraftResponse refusal) {
            this.name = refusal.getWorkflow();
            this.preview = refusal;
            this.fingerprint = null;
            this.changes = null;
            this.schemes = null;
            this.projects = null;
            this.draftDefinition = null;
        }

        private Planned(JiraWorkflow active, JiraWorkflow draft, TreeSet<Long> schemes, Map<String, String> projects,
                        Map<Integer, Long> occupancy) {
            this.name = active.getName();
            this.changes = changes(active, draft);
            this.schemes = schemes;
            this.projects = projects;
            this.draftDefinition = definition(draft.getDescriptor());
            List<String> names = shown(projects);
            int hidden = projects.size() - names.size();
            this.preview = PublishWorkflowDraftResponse.wouldPublish(name, changes, schemes.size(), projects.size(),
                    names, "Publishing replaces the active workflow '" + name + "' with its draft for every project "
                            + "that uses it: " + projects.size() + " project(s)"
                            + (names.isEmpty() ? "" : " (" + String.join(", ", names)
                            + (hidden > 0 ? " and " + hidden + " more" : "") + ")")
                            + " in " + schemes.size() + " scheme(s). This changes the shared original, not one "
                            + "project's copy. The active version is not kept and there is no automatic undo. After "
                            + "PUBLISHED, preview clone_project_workflows again: an earlier clone plan is stale.");
            Map<String, Object> confirmation = new LinkedHashMap<String, Object>();
            confirmation.put("preview", preview);
            confirmation.put("active", WorkflowSnapshots.workflowFingerprint(active, delegator));
            confirmation.put("draft", WorkflowSnapshots.hash(WorkflowSnapshots.json(WorkflowSnapshots.workflow(draft))));
            confirmation.put("schemes", new ArrayList<Long>(schemes));
            confirmation.put("projects", new ArrayList<String>(projects.keySet()));
            confirmation.put("occupancy", occupancy);
            this.fingerprint = planCodec.fingerprint(confirmation);
        }
    }

    private TreeSet<Long> consumerSchemes(JiraWorkflow active) {
        TreeSet<Long> ids = new TreeSet<Long>();
        for (WorkflowScheme scheme : schemeManager.getSchemesForWorkflowIncludingDrafts(active)) {
            ids.add(scheme.getId());
        }
        return ids;
    }

    /** Key to name of every project on a scheme that uses the workflow now; a scheme draft has no projects yet. */
    private Map<String, String> consumerProjects(JiraWorkflow active) {
        Map<String, String> projects = new TreeMap<String, String>();
        for (WorkflowScheme scheme : schemeManager.getSchemesForWorkflowIncludingDrafts(active)) {
            if (scheme instanceof AssignableWorkflowScheme) {
                for (Project project : schemeManager.getProjectsUsing((AssignableWorkflowScheme) scheme)) {
                    projects.put(project.getKey(), project.getName());
                }
            }
        }
        return projects;
    }

    private static List<String> shown(Map<String, String> projects) {
        List<String> names = new ArrayList<String>(projects.values());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names.size() > WorkflowAssignLimits.MAX_PROJECTS_SHOWN
                ? new ArrayList<String>(names.subList(0, WorkflowAssignLimits.MAX_PROJECTS_SHOWN)) : names;
    }

    private List<String> workflowsWithDrafts() {
        TreeSet<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (JiraWorkflow workflow : workflowManager.getWorkflows()) {
            if (workflow.hasDraftWorkflow()) names.add(workflow.getName());
        }
        return new ArrayList<String>(names);
    }

    private static DraftChanges changes(JiraWorkflow active, JiraWorkflow draft) {
        Map<String, String> before = statuses(active);
        Map<String, String> after = statuses(draft);
        Map<Integer, ActionDescriptor> oldActions = actions(active.getDescriptor());
        Map<Integer, ActionDescriptor> newActions = actions(draft.getDescriptor());
        List<String> added = new ArrayList<String>();
        List<String> changed = new ArrayList<String>();
        for (Map.Entry<Integer, ActionDescriptor> entry : newActions.entrySet()) {
            ActionDescriptor old = oldActions.get(entry.getKey());
            if (old == null) {
                added.add(entry.getValue().getName());
            } else if (!xml(old).equals(xml(entry.getValue()))) {
                changed.add(entry.getValue().getName());
            }
        }
        List<String> removed = new ArrayList<String>();
        for (Map.Entry<Integer, ActionDescriptor> entry : oldActions.entrySet()) {
            if (!newActions.containsKey(entry.getKey())) removed.add(entry.getValue().getName());
        }
        return new DraftChanges(missingFrom(after, before), missingFrom(before, after), added, removed, changed,
                !definition(active.getDescriptor()).equals(definition(draft.getDescriptor())));
    }

    /** Status id to name, in the workflow's order. */
    private static Map<String, String> statuses(JiraWorkflow workflow) {
        Map<String, String> statuses = new LinkedHashMap<String, String>();
        for (Status status : workflow.getLinkedStatusObjects()) {
            statuses.put(status.getId(), status.getName());
        }
        return statuses;
    }

    private static List<String> missingFrom(Map<String, String> present, Map<String, String> other) {
        List<String> names = new ArrayList<String>();
        for (Map.Entry<String, String> entry : present.entrySet()) {
            if (!other.containsKey(entry.getKey())) names.add(entry.getValue());
        }
        return names;
    }

    /** Every transition of the workflow by id: initial, global, common and those of the steps. */
    private static Map<Integer, ActionDescriptor> actions(WorkflowDescriptor descriptor) {
        Map<Integer, ActionDescriptor> actions = new TreeMap<Integer, ActionDescriptor>();
        add(actions, descriptor.getInitialActions());
        add(actions, descriptor.getGlobalActions());
        add(actions, descriptor.getCommonActions().values());
        for (Object step : descriptor.getSteps()) {
            add(actions, ((StepDescriptor) step).getActions());
        }
        return actions;
    }

    private static void add(Map<Integer, ActionDescriptor> actions, Collection<?> items) {
        for (Object item : items) {
            ActionDescriptor action = (ActionDescriptor) item;
            actions.putIfAbsent(action.getId(), action);
        }
    }

    private static String xml(AbstractDescriptor descriptor) {
        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        if (descriptor instanceof ActionDescriptor) {
            ((ActionDescriptor) descriptor).writeXML(writer, 0);
        } else {
            ((WorkflowDescriptor) descriptor).writeXML(writer, 0);
        }
        writer.flush();
        return text.toString();
    }

    /** The workflow definition without the save time Jira stamps on every edit. */
    private static String definition(WorkflowDescriptor descriptor) {
        return xml(descriptor).replaceAll("<meta name=\"jira\\.updated\\.date\">[^<]*</meta>", "");
    }

    private static String errors(ErrorCollection errors) {
        List<String> messages = new ArrayList<String>(errors.getErrorMessages());
        messages.addAll(errors.getErrors().values());
        return String.join(" ", messages);
    }
}
