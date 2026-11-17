package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.bc.JiraServiceContext;
import com.atlassian.jira.bc.JiraServiceContextImpl;
import com.atlassian.jira.bc.workflow.WorkflowService;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowUtil;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.ofbiz.core.entity.EntityFieldMap;
import org.ofbiz.core.entity.EntityOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.workflow.api.WorkflowLimits;
import ru.rasulov.workflow.api.WorkflowRenamer;
import ru.rasulov.workflow.api.dto.edit.RenameWorkflowRequest;
import ru.rasulov.workflow.api.dto.edit.RenameWorkflowResponse;
import ru.rasulov.workflow.api.dto.edit.RenameWorkflowResponse.ErrorCode;
import ru.rasulov.workflow.impl.assign.WorkflowAssignerImpl;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code rename_workflow} through Jira's own rename ({@code WorkflowManager.updateWorkflowNameAndDescription}), which
 * re-stores the workflow under the new name and rewrites the scheme mappings. Jira offers it only for a workflow that
 * is not active, and it would orphan issue entries of the old name, so both are refused before any write.
 */
@Named
public class WorkflowRenamerImpl implements WorkflowRenamer {

    static final String TOOL = "rename_workflow";
    private static final Logger log = LoggerFactory.getLogger(WorkflowRenamerImpl.class);

    private final AdministratorGuard guard;
    private final NamedLock namedLock;
    private final WorkflowManager workflowManager;
    private final WorkflowService workflowService;
    private final OfBizDelegator delegator;
    private final WorkflowInspector inspector;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec planCodec;
    private final ChangeJournal journal;

    @Inject
    public WorkflowRenamerImpl(AdministratorGuard guard, NamedLock namedLock,
                               @ComponentImport WorkflowManager workflowManager,
                               @ComponentImport WorkflowService workflowService,
                               @ComponentImport OfBizDelegator delegator, WorkflowInspector inspector,
                               PreviewPlanExecutor executor, ActionPlanCodec planCodec, ChangeJournal journal) {
        this.guard = guard;
        this.namedLock = namedLock;
        this.workflowManager = workflowManager;
        this.workflowService = workflowService;
        this.delegator = delegator;
        this.inspector = inspector;
        this.executor = executor;
        this.planCodec = planCodec;
        this.journal = journal;
    }

    @Override
    public RenameWorkflowResponse rename(RenameWorkflowRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        if (request == null || request.isDryRun() == null) {
            throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        }
        if (request.isDryRun()) {
            if (request.getPlanId() != null) throw new InvalidRequestException("Preview takes the names, not planId.");
            Planned current = plan(request, user);
            if (current.fingerprint == null) return current.response;
            return executor.issue(TOOL, user.getKey(), request, current.fingerprint, current.response::withPlan);
        }
        if (request.getWorkflow() != null || request.getNewName() != null || request.getPlanId() == null
                || request.getPlanId().trim().isEmpty()) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
        return journal.execute(TOOL, user.getKey(), () -> namedLock.run(WorkflowAssignerImpl.LOCK_NAME,
                "workflow renames", () -> apply(request.getPlanId(), user)));
    }

    private RenameWorkflowResponse apply(String planId, ApplicationUser user) {
        return executor.apply(TOOL, user.getKey(), planId, RenameWorkflowRequest.class,
                RenameWorkflowResponse::stale, preview -> plan(preview, user), current -> current.fingerprint != null,
                current -> current.fingerprint, current -> writeRename(current, user));
    }

    private RenameWorkflowResponse writeRename(Planned current, ApplicationUser user) {
        journal.checkpoint();
        String before = DescriptorFacts.definition(current.main.getDescriptor());
        try {
            workflowManager.updateWorkflowNameAndDescription(user, workflowManager.getWorkflowClone(current.name),
                    current.newName, current.main.getDescription());
        } catch (RuntimeException e) {
            return RenameWorkflowResponse.failed(false, current.name, ErrorCode.JIRA_ERROR, "Jira failed while "
                    + "renaming: " + e.getMessage() + ". A write may have started: read both names "
                    + "('" + current.name + "' and '" + current.newName + "') before assuming nothing changed. Do "
                    + "not repeat the rename automatically.");
        }
        JiraWorkflow now = workflowManager.getWorkflow(current.newName);
        boolean ok = now != null && workflowManager.getWorkflow(current.name) == null
                && before.equals(DescriptorFacts.definition(now.getDescriptor()))
                && inspector.consumers(now).schemeIds().equals(current.schemeIds);
        if (!ok) {
            return RenameWorkflowResponse.failed(false, current.name, ErrorCode.JIRA_ERROR, "Jira reported no "
                    + "error, but the workflow does not read back as renamed with its definition and scheme "
                    + "mappings intact. The state is uncertain: do not repeat the rename; read both names "
                    + "('" + current.name + "' and '" + current.newName + "') and check the schemes.");
        }
        log.info("Renamed workflow '{}' to '{}' (user {})", current.name, current.newName, user.getUsername());
        return RenameWorkflowResponse.renamed(current.name, current.newName, current.schemes,
                "Renamed '" + current.name + "' to '" + current.newName + "' with Jira's own rename; its "
                        + current.schemes.size() + " scheme mapping(s) now use the new name. Jira stores a renamed "
                        + "workflow as a new record under the new name; anything outside Jira that refers to the "
                        + "old name (scripts, saved REST calls) must be updated.");
    }

    private static final class Planned {
        final RenameWorkflowResponse response;
        final String fingerprint;
        final String name;
        final String newName;
        final JiraWorkflow main;
        final List<String> schemes;
        final java.util.TreeSet<Long> schemeIds;

        Planned(RenameWorkflowResponse refusal) {
            this.response = refusal;
            this.fingerprint = null;
            this.name = refusal.getWorkflow();
            this.newName = null;
            this.main = null;
            this.schemes = null;
            this.schemeIds = null;
        }

        Planned(RenameWorkflowResponse response, String fingerprint, String name, String newName, JiraWorkflow main,
                List<String> schemes, java.util.TreeSet<Long> schemeIds) {
            this.response = response;
            this.fingerprint = fingerprint;
            this.name = name;
            this.newName = newName;
            this.main = main;
            this.schemes = schemes;
            this.schemeIds = schemeIds;
        }
    }

    private Planned plan(RenameWorkflowRequest request, ApplicationUser user) {
        String name = request.getWorkflow() == null ? "" : request.getWorkflow().trim();
        String newName = request.getNewName() == null ? "" : request.getNewName().trim();
        if (name.isEmpty()) return refuse(null, ErrorCode.VALIDATION_ERROR, "'workflow' is blank.");
        if (newName.isEmpty() || newName.length() > WorkflowLimits.MAX_NAME_LENGTH
                || !WorkflowUtil.isAcceptableName(newName)) {
            return refuse(name, ErrorCode.VALIDATION_ERROR, "'newName' must be a non-blank name of at most "
                    + WorkflowLimits.MAX_NAME_LENGTH + " characters without leading or trailing spaces.");
        }
        WorkflowInspector.Snapshot s = inspector.snapshot(name);
        if (s == null) {
            return refuse(name, ErrorCode.UNKNOWN_WORKFLOW, "No workflow is named '" + name + "'. Names are exact.");
        }
        if (s.system()) {
            return refuse(s.name(), ErrorCode.SYSTEM_WORKFLOW, "'" + s.name() + "' is Jira's built-in workflow.");
        }
        if (s.active()) {
            return refuse(s.name(), ErrorCode.ACTIVE_WORKFLOW, "'" + s.name() + "' is used by a project, and Jira "
                    + "renames only a workflow that is not used. Copying it under the new name and assigning the copy "
                    + "is a different operation (copy, assign with migration), not a rename; it needs its own "
                    + "decision.");
        }
        if (workflowManager.getDraftWorkflow(s.name()) != null) {
            return refuse(s.name(), ErrorCode.RENAME_REFUSED, "'" + s.name() + "' has a draft; Jira does not rename "
                    + "it. Nothing was changed.");
        }
        if (newName.equals(s.name())) return new Planned(RenameWorkflowResponse.unchanged(s.name()));
        if (!delegator.findByCondition("OSWorkflowEntry", new EntityFieldMap(Collections.<String, Object>singletonMap(
                "name", s.name()), EntityOperator.AND), Arrays.asList("id"), null).isEmpty()) {
            return refuse(s.name(), ErrorCode.IN_USE, "Issues have workflow entries under the name '" + s.name()
                    + "'; renaming would detach them. Nothing was changed.");
        }
        JiraServiceContext context = new JiraServiceContextImpl(user);
        workflowService.validateUpdateWorkflowNameAndDescription(context, s.main(), newName);
        if (context.getErrorCollection().hasAnyErrors()) {
            boolean nameProblem = context.getErrorCollection().getErrors().containsKey("newWorkflowName");
            List<String> messages = new ArrayList<String>(context.getErrorCollection().getErrorMessages());
            messages.addAll(context.getErrorCollection().getErrors().values());
            return refuse(s.name(), nameProblem ? ErrorCode.NAME_TAKEN : ErrorCode.RENAME_REFUSED,
                    "Jira refuses: " + String.join(" ", messages) + " Nothing was changed.");
        }
        WorkflowInspector.Consumers consumers = inspector.consumers(s.main());
        RenameWorkflowResponse preview = RenameWorkflowResponse.wouldRename(s.name(), newName,
                consumers.schemeLines(), "Jira's own rename stores '" + s.name() + "' as '" + newName + "' and "
                        + "rewrites " + consumers.schemeIds().size() + " scheme mapping(s) (including drafts). No "
                        + "project uses the workflow and no issue has an entry for it. The definition is not "
                        + "changed. Anything outside Jira that refers to the old name must be updated.");
        Map<String, Object> confirmation = new LinkedHashMap<String, Object>();
        confirmation.put("preview", preview);
        confirmation.put("revision", s.revision());
        confirmation.put("consumers", consumers.fingerprint());
        return new Planned(preview, planCodec.fingerprint(confirmation), s.name(), newName, s.main(),
                consumers.schemeLines(), consumers.schemeIds());
    }

    private static Planned refuse(String name, ErrorCode code, String message) {
        return new Planned(RenameWorkflowResponse.failed(true, name, code, message));
    }
}
