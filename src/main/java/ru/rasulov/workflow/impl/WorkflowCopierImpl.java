package ru.rasulov.workflow.impl;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.api.dto.report.ChangeReport;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.ChangeLinks;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.JournalCancelledException;
import ru.rasulov.workflow.api.WorkflowCopier;
import ru.rasulov.workflow.api.WorkflowLimits;
import ru.rasulov.workflow.api.dto.CopyWorkflowRequest;
import ru.rasulov.workflow.api.dto.CopyWorkflowResponse;
import ru.rasulov.workflow.api.dto.WorkflowErrorCode;
import ru.rasulov.workflow.api.dto.WorkflowResultStatus;
import ru.rasulov.workflow.impl.edit.DescriptorFacts;
import ru.rasulov.workflow.impl.undo.WorkflowSnapshots;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ru.rasulov.common.impl.Normalizer.normalize;

/** Exact main-version copies, under the same name-allocation lock as configure_workflow. */
@ExportAsService(WorkflowCopier.class)
@Named
public final class WorkflowCopierImpl implements WorkflowCopier {
    private static final String TOOL = "copy_workflow";
    private final WorkflowManager workflowManager;
    private final OfBizDelegator delegator;
    private final AdministratorGuard administratorGuard;
    private final NamedLock namedLock;
    private final WorkflowWriter workflowWriter;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec planCodec;
    private final ChangeJournal journal;
    private final ChangeLinks links;

    @Inject
    public WorkflowCopierImpl(@ComponentImport WorkflowManager workflowManager,
            @ComponentImport OfBizDelegator delegator, AdministratorGuard administratorGuard, NamedLock namedLock,
            WorkflowWriter workflowWriter, PreviewPlanExecutor executor, ActionPlanCodec planCodec,
            ChangeJournal journal, ChangeLinks links) {
        this.workflowManager = workflowManager; this.delegator = delegator;
        this.administratorGuard = administratorGuard; this.namedLock = namedLock;
        this.workflowWriter = workflowWriter; this.executor = executor; this.planCodec = planCodec;
        this.journal = journal; this.links = links;
    }

    @Override public CopyWorkflowResponse copy(CopyWorkflowRequest request) {
        ApplicationUser user = administratorGuard.requireAdministrator();
        requireMode(request);
        if (request.isDryRun()) {
            CopyPlan current = plan(request);
            if (current.preview.getStatus() != WorkflowResultStatus.WOULD_CREATE) return current.preview;
            return executor.issue(TOOL, user.getKey(), request, current.fingerprint, current.preview::withPlan);
        }
        return journal.execute(TOOL, user.getKey(), () -> namedLock.run(
                ru.rasulov.workflow.impl.edit.WorkflowConfigurerImpl.CREATE_LOCK,
                "workflows", () -> apply(request.getPlanId(), user)));
    }

    private static void requireMode(CopyWorkflowRequest request) {
        if (request == null || request.isDryRun() == null)
            throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        if (request.isDryRun() && request.getPlanId() != null)
            throw new InvalidRequestException("Preview takes workflow details, not planId.");
        if (!request.isDryRun() && (request.getWorkflow() != null || request.getName() != null
                || request.getDescription() != null || request.getPlanId() == null
                || request.getPlanId().trim().isEmpty()))
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
    }

    private CopyWorkflowResponse apply(String planId, ApplicationUser user) {
        return executor.apply(TOOL, user.getKey(), planId, CopyWorkflowRequest.class, CopyWorkflowResponse::stale,
                this::plan, current -> current.fingerprint != null, current -> current.fingerprint,
                current -> writeCopy(current, user));
    }

    private CopyWorkflowResponse writeCopy(CopyPlan current, ApplicationUser user) {
        // Capture before Jira can mutate either object. Only the confirmed description may differ.
        String expectedDefinition;
        try {
            WorkflowDescriptor expected = com.atlassian.jira.workflow.WorkflowUtil.convertXMLtoWorkflowDescriptor(
                    com.atlassian.jira.workflow.WorkflowUtil.convertDescriptorToXML(current.source.getDescriptor()));
            if (current.preview.getDescription() != null) {
                expected.getMetaAttributes().put("jira.description", current.preview.getDescription());
            }
            expectedDefinition = DescriptorFacts.definition(expected);
        } catch (Exception e) {
            return current.preview.failed(WorkflowErrorCode.JIRA_ERROR,
                    "The source definition could not be snapshotted; no copy was created. " + e.getMessage(),
                    current.preview.getWarnings(), null);
        }
        journal.checkpoint();
        JiraWorkflow copy;
        try {
            copy = workflowWriter.copyCreated(user, current.source, current.preview.getName(),
                    current.preview.getDescription());
        } catch (RuntimeException e) {
            if (e instanceof JournalCancelledException) throw e;
            List<String> warnings = new ArrayList<>(current.preview.getWarnings());
            String warning = "Jira failed while copying. A copy may already exist; do not repeat automatically. "
                    + "Check '" + current.preview.getName() + "' in Jira and the Audit journal before retrying.";
            warnings.add(warning);
            ChangeReport partialReport = null;
            try {
                JiraWorkflow partial = workflowManager.getWorkflow(current.preview.getName());
                if (partial != null) {
                    partialReport = links.report().created(ChangeKind.WORKFLOW, null, partial.getName(),
                            partial.getName()).build();
                }
            } catch (RuntimeException inspectionFailure) {
                warnings.add("Jira could not read back the copy; its creation is unconfirmed.");
            }
            return current.preview.failed(WorkflowErrorCode.JIRA_ERROR, warning + " " + e.getMessage(),
                    warnings, partialReport);
        }
        ChangeReport report = links.report().created(ChangeKind.WORKFLOW, null, current.preview.getName(),
                current.preview.getName()).build();
        if (copy == null || !expectedDefinition.equals(DescriptorFacts.definition(copy.getDescriptor()))) {
            List<String> warnings = new ArrayList<>(current.preview.getWarnings());
            String warning = "The copy was created but differs from the source; do not repeat. Check it in Jira. "
                    + "The copy remains in the Audit journal and can be rolled back normally.";
            warnings.add(warning);
            return current.preview.failed(WorkflowErrorCode.JIRA_ERROR, warning, warnings, report);
        }
        return current.preview.created(report);
    }

    private CopyPlan plan(CopyWorkflowRequest request) {
        String sourceName = request.getWorkflow();
        String name = request.getName() == null ? null : request.getName().trim();
        if (sourceName == null || sourceName.trim().isEmpty() || name == null || name.isEmpty()
                || name.length() > WorkflowLimits.MAX_NAME_LENGTH) {
            return refusal(sourceName, name, WorkflowErrorCode.VALIDATION_ERROR,
                    "'workflow' and 'name' must be non-blank; name must be at most "
                            + WorkflowLimits.MAX_NAME_LENGTH + " characters.", null);
        }
        JiraWorkflow source = workflowManager.getWorkflow(sourceName);
        if (source == null || !sourceName.equals(source.getName()) || source.isDraftWorkflow())
            return refusal(sourceName, name, WorkflowErrorCode.UNKNOWN_WORKFLOW,
                    "No main workflow is named '" + sourceName + "'. Source names are exact.", null);
        for (JiraWorkflow existing : workflowManager.getWorkflows()) {
            if (normalize(existing.getName()).equals(normalize(name)))
                return refusal(sourceName, name, WorkflowErrorCode.NAME_TAKEN,
                        "Workflow '" + existing.getName() + "' already exists. Choose another copy name.",
                        Collections.singletonList(existing.getName()));
        }
        boolean hasDraft = workflowManager.getDraftWorkflow(sourceName) != null;
        List<String> warnings = hasDraft ? Collections.singletonList(
                "The source has a draft. The draft is not copied; use publish_workflow_draft first if it is needed.")
                : Collections.emptyList();
        WorkflowDescriptor descriptor = source.getDescriptor();
        int transitions = descriptor.getGlobalActions().size() + descriptor.getCommonActions().size();
        for (Object step : descriptor.getSteps()) transitions += ((StepDescriptor) step).getActions().size();
        String description = request.getDescription() == null ? source.getDescription() : request.getDescription();
        CopyWorkflowResponse response = CopyWorkflowResponse.wouldCreate(sourceName, name, description,
                descriptor.getSteps().size(), transitions, hasDraft, warnings);
        Map<String, Object> confirmation = new LinkedHashMap<>();
        confirmation.put("preview", response);
        confirmation.put("source", WorkflowSnapshots.workflowFingerprint(source, delegator));
        confirmation.put("sourceHasDraft", hasDraft);
        return new CopyPlan(source, response, planCodec.fingerprint(confirmation));
    }

    private static CopyPlan refusal(String source, String name, WorkflowErrorCode code, String message,
            List<String> suggestions) {
        return new CopyPlan(null, CopyWorkflowResponse.failed(true, source, name, code, message, suggestions), null);
    }

    private static final class CopyPlan {
        private final JiraWorkflow source;
        private final CopyWorkflowResponse preview;
        private final String fingerprint;
        private CopyPlan(JiraWorkflow source, CopyWorkflowResponse preview, String fingerprint) {
            this.source = source; this.preview = preview; this.fingerprint = fingerprint;
        }
    }
}
