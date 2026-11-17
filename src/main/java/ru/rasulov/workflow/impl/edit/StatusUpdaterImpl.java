package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.bc.ServiceOutcome;
import com.atlassian.jira.bc.ServiceResult;
import com.atlassian.jira.bc.config.StatusService;
import com.atlassian.jira.config.StatusCategoryManager;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.issue.status.category.StatusCategory;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.workflow.api.StatusUpdater;
import ru.rasulov.workflow.api.WorkflowAssignLimits;
import ru.rasulov.workflow.api.WorkflowLimits;
import ru.rasulov.workflow.api.dto.StatusCategoryKey;
import ru.rasulov.workflow.api.dto.edit.UpdateStatusRequest;
import ru.rasulov.workflow.api.dto.edit.UpdateStatusResponse;
import ru.rasulov.workflow.api.dto.edit.UpdateStatusResponse.ErrorCode;
import ru.rasulov.workflow.impl.assign.WorkflowAssignerImpl;
import ru.rasulov.workflow.impl.undo.WorkflowSnapshots;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code update_status}: the name, description or category of one global status through Jira's {@code StatusService},
 * which validates the name against every status. The change is global, so the preview names every workflow (active,
 * inactive and draft) and project that uses the status, and the plan is bound to that complete set.
 */
@Named
public class StatusUpdaterImpl implements StatusUpdater {

    static final String TOOL = "update_status";
    private static final Logger log = LoggerFactory.getLogger(StatusUpdaterImpl.class);

    private final AdministratorGuard guard;
    private final NamedLock namedLock;
    private final StatusService statusService;
    private final StatusCategoryManager categoryManager;
    private final WorkflowManager workflowManager;
    private final WorkflowInspector inspector;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec planCodec;
    private final ChangeJournal journal;

    @Inject
    public StatusUpdaterImpl(AdministratorGuard guard, NamedLock namedLock,
                             @ComponentImport StatusService statusService,
                             @ComponentImport StatusCategoryManager categoryManager,
                             @ComponentImport WorkflowManager workflowManager, WorkflowInspector inspector,
                             PreviewPlanExecutor executor, ActionPlanCodec planCodec, ChangeJournal journal) {
        this.guard = guard;
        this.namedLock = namedLock;
        this.statusService = statusService;
        this.categoryManager = categoryManager;
        this.workflowManager = workflowManager;
        this.inspector = inspector;
        this.executor = executor;
        this.planCodec = planCodec;
        this.journal = journal;
    }

    @Override
    public UpdateStatusResponse update(UpdateStatusRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        if (request == null || request.isDryRun() == null) {
            throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        }
        if (request.isDryRun()) {
            if (request.getPlanId() != null) throw new InvalidRequestException("Preview takes the fields, not planId.");
            Planned current = plan(request, user);
            if (current.fingerprint == null) return current.response;
            return executor.issue(TOOL, user.getKey(), request, current.fingerprint, current.response::withPlan);
        }
        if (request.getStatusId() != null || request.getName() != null || request.getDescription() != null
                || request.getCategory() != null || request.getPlanId() == null
                || request.getPlanId().trim().isEmpty()) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
        return journal.execute(TOOL, user.getKey(), () -> namedLock.run(WorkflowAssignerImpl.LOCK_NAME,
                "global statuses and workflows", () -> apply(request.getPlanId(), user)));
    }

    private UpdateStatusResponse apply(String planId, ApplicationUser user) {
        return executor.apply(TOOL, user.getKey(), planId, UpdateStatusRequest.class, UpdateStatusResponse::stale,
                preview -> plan(preview, user), current -> current.fingerprint != null,
                current -> current.fingerprint, current -> writeStatus(current, user));
    }

    private UpdateStatusResponse writeStatus(Planned current, ApplicationUser user) {
        journal.checkpoint();
        Status status = current.status;
        ServiceOutcome<Status> outcome;
        try {
            outcome = statusService.editStatus(user, status, current.name, current.description,
                    status.getIconUrl(), current.category);
        } catch (RuntimeException e) {
            return failedWrite(current, "Jira failed while updating: " + e.getMessage());
        }
        if (!outcome.isValid()) {
            return failedWrite(current, "Jira refused: " + reasons(outcome.getErrorCollection()));
        }
        Status now = statusService.getStatusById(user, status.getId());
        if (now == null || !Objects.equals(now.getName(), current.name)
                || !Objects.equals(emptyIfNull(now.getDescription()), emptyIfNull(current.description))
                || now.getStatusCategory() == null
                || !current.category.getKey().equals(now.getStatusCategory().getKey())) {
            return UpdateStatusResponse.failed(false, status.getId(), ErrorCode.JIRA_ERROR, "Jira reported no "
                    + "error, but the status does not read back with the confirmed values. The state is "
                    + "uncertain: do not repeat the update; read the status (list_statuses) and check it.");
        }
        log.info("Updated status {} ('{}') (user {})", status.getId(), current.name, user.getUsername());
        return UpdateStatusResponse.updated(status.getId(), current.changes, current.workflowCount,
                current.projectCount, current.response.getWorkflows(), current.response.getProjects(),
                current.response.getWarnings(), "Status " + status.getId() + " now reads '" + current.name
                        + "' in all " + current.workflowCount + " workflow(s) that use it. No issue was moved and "
                        + "no resolution was changed.");
    }

    private static UpdateStatusResponse failedWrite(Planned current, String reason) {
        return UpdateStatusResponse.failed(false, current.status.getId(), ErrorCode.JIRA_ERROR, reason + ". A write "
                + "may have started: read the status (list_statuses) before assuming nothing changed. Do not repeat "
                + "the update automatically.");
    }

    private static final class Planned {
        final UpdateStatusResponse response;
        final String fingerprint;
        final Status status;
        final String name;
        final String description;
        final StatusCategory category;
        final List<String> changes;
        final int workflowCount;
        final int projectCount;

        Planned(UpdateStatusResponse refusal) {
            this.response = refusal;
            this.fingerprint = null;
            this.status = null;
            this.name = null;
            this.description = null;
            this.category = null;
            this.changes = null;
            this.workflowCount = 0;
            this.projectCount = 0;
        }

        Planned(UpdateStatusResponse response, String fingerprint, Status status, String name, String description,
                StatusCategory category, List<String> changes, int workflowCount, int projectCount) {
            this.response = response;
            this.fingerprint = fingerprint;
            this.status = status;
            this.name = name;
            this.description = description;
            this.category = category;
            this.changes = changes;
            this.workflowCount = workflowCount;
            this.projectCount = projectCount;
        }
    }

    private Planned plan(UpdateStatusRequest request, ApplicationUser user) {
        String id = request.getStatusId() == null ? "" : request.getStatusId().trim();
        if (id.isEmpty()) return refuse(null, ErrorCode.VALIDATION_ERROR, "'statusId' is blank. Use list_statuses.");
        Status status = statusService.getStatusById(user, id);
        if (status == null) return refuse(id, ErrorCode.UNKNOWN_STATUS, "No status has id '" + id + "'.");
        if (request.getName() == null && request.getDescription() == null && request.getCategory() == null) {
            return refuse(id, ErrorCode.VALIDATION_ERROR, "Nothing to change: give name, description or category.");
        }
        String name = status.getName();
        if (request.getName() != null) {
            name = request.getName().trim();
            if (name.isEmpty() || name.length() > WorkflowLimits.MAX_STATUS_NAME_LENGTH) {
                return refuse(id, ErrorCode.VALIDATION_ERROR, "'name' must be 1 to "
                        + WorkflowLimits.MAX_STATUS_NAME_LENGTH + " characters.");
            }
        }
        String description = request.getDescription() == null ? status.getDescription() : request.getDescription();
        StatusCategory category = status.getStatusCategory();
        if (request.getCategory() != null) {
            StatusCategoryKey key = StatusCategoryKey.parse(request.getCategory());
            if (key == null) {
                return refuse(id, ErrorCode.VALIDATION_ERROR, "'category' must be TO_DO, IN_PROGRESS or DONE.");
            }
            category = categoryManager.getStatusCategoryByKey(key.jiraKey());
        }
        if (category == null) return refuse(id, ErrorCode.VALIDATION_ERROR, "The status has no category to keep; "
                + "give 'category'.");
        List<String> changes = new ArrayList<String>();
        if (!name.equals(status.getName())) changes.add("name '" + status.getName() + "' -> '" + name + "'");
        if (!emptyIfNull(description).equals(emptyIfNull(status.getDescription()))) {
            changes.add("description '" + emptyIfNull(status.getDescription()) + "' -> '" + emptyIfNull(description)
                    + "'");
        }
        String oldCategory = status.getStatusCategory() == null ? null : status.getStatusCategory().getKey();
        if (!category.getKey().equals(oldCategory)) changes.add("category " + oldCategory + " -> " + category.getKey());
        if (changes.isEmpty()) return new Planned(UpdateStatusResponse.unchanged(id));
        ServiceResult valid = statusService.validateEditStatus(user, status, name, description, status.getIconUrl(),
                category);
        if (!valid.isValid()) {
            return refuse(id, ErrorCode.UPDATE_REFUSED, "Jira refuses: " + reasons(valid.getErrorCollection())
                    + " Nothing was changed.");
        }
        Map<String, String> workflows = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> projects = new TreeMap<String, String>();
        TreeSet<String> checked = new TreeSet<String>();
        for (JiraWorkflow workflow : workflowManager.getWorkflowsIncludingDrafts()) {
            if (!workflow.getLinkedStatusIds().contains(id)) continue;
            workflows.put(workflow.getName() + (workflow.isDraftWorkflow() ? " (draft)" : ""),
                    workflow.isDraftWorkflow() ? "DRAFT" : "SAVED");
            JiraWorkflow main = workflowManager.getWorkflow(workflow.getName());
            if (main != null && checked.add(main.getName())) projects.putAll(inspector.consumers(main).projects());
        }
        List<String> warnings = new ArrayList<String>();
        warnings.add("The status is global: the change applies to every workflow and every issue that uses it, not "
                + "to one project. For a name that differs only in one project, create a separate status and replace "
                + "the link instead.");
        if (!name.equals(status.getName())) {
            warnings.add("Saved filters, boards and automation that match this status by its old name in JQL stop "
                    + "matching; by id they keep working.");
        }
        if (!category.getKey().equals(oldCategory)) {
            warnings.add("The category changes reporting and what counts as done. It does not set or clear the "
                    + "resolution of any existing issue and does not change any transition's resolution rule; review "
                    + "the transitions into and out of this status with get_workflow.");
        }
        int shown = WorkflowAssignLimits.MAX_PROJECTS_SHOWN;
        List<String> workflowNames = new ArrayList<String>(workflows.keySet());
        List<String> projectNames = new ArrayList<String>();
        for (Map.Entry<String, String> e : projects.entrySet()) projectNames.add(e.getKey() + " (" + e.getValue() + ")");
        UpdateStatusResponse preview = UpdateStatusResponse.wouldUpdate(id, changes, workflowNames.size(),
                projectNames.size(), workflowNames.size() > shown ? workflowNames.subList(0, shown) : workflowNames,
                projectNames.size() > shown ? projectNames.subList(0, shown) : projectNames, warnings,
                "Status " + id + " is used by " + workflowNames.size() + " workflow(s) and " + projectNames.size()
                        + " project(s); the change applies to all of them.");
        Map<String, Object> confirmation = new LinkedHashMap<String, Object>();
        confirmation.put("preview", preview);
        confirmation.put("status", WorkflowSnapshots.status(status));
        confirmation.put("target", name + "|" + emptyIfNull(description) + "|" + category.getKey());
        confirmation.put("workflows", workflows);
        confirmation.put("projects", new ArrayList<String>(projects.keySet()));
        return new Planned(preview, planCodec.fingerprint(confirmation), status, name, description, category, changes,
                workflowNames.size(), projectNames.size());
    }

    private static String emptyIfNull(String text) {
        return text == null ? "" : text;
    }

    private static String reasons(ErrorCollection errors) {
        List<String> messages = new ArrayList<String>(errors.getErrorMessages());
        messages.addAll(errors.getErrors().values());
        return messages.isEmpty() ? "no reason given" : String.join(" ", messages);
    }

    private static Planned refuse(String id, ErrorCode code, String message) {
        return new Planned(UpdateStatusResponse.failed(true, id, code, message));
    }
}
