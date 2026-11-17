package ru.rasulov.customfields.impl.requirement;

import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.customfields.api.FieldRequirementService;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredRequest;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse;
import ru.rasulov.customfields.impl.configuration.ConfigurableFieldResolver;
import ru.rasulov.customfields.impl.configuration.FieldConfigurationLocks;
import ru.rasulov.customfields.impl.configuration.FieldLayoutCopyFactory;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Orchestrates the preview / plan / apply protocol of {@code set_field_required}. Planning is
 * {@link FieldRequirementPlanner}; native writes, verification and compensation are {@link FieldRequirementWriter}.
 * A layout that unselected issue types of the project also use is not changed: the selected types get a private copy of
 * it in the project's own field configuration scheme, and only the copy changes. Custom fields and system fields (by
 * their Jira id) are both accepted; Jira's mandatory fields cannot become optional.
 */
@ExportAsService(FieldRequirementService.class)
@Named
public class FieldRequirementServiceImpl implements FieldRequirementService {
    private static final String OPERATION = "set_field_required";
    private static final Logger LOG = LoggerFactory.getLogger(FieldRequirementServiceImpl.class);

    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;
    private final FieldRequirementPlanner planner;
    private final FieldRequirementWriter writer;

    @Inject
    public FieldRequirementServiceImpl(AdministratorGuard guard, NamedLock lock,
                                       ProjectResolver projectResolver, IssueTypeResolver issueTypeResolver,
                                       ConfigurableFieldResolver fields,
                                       @ComponentImport FieldLayoutManager fieldLayoutManager,
                                       @ComponentImport IssueTypeScreenSchemeManager screenSchemes,
                                       PreviewPlanExecutor executor, ActionPlanCodec codec, ChangeJournal journal,
                                       FieldLayoutCopyFactory copies) {
        this.guard = guard;
        this.lock = lock;
        this.executor = executor;
        this.codec = codec;
        this.journal = journal;
        this.planner = new FieldRequirementPlanner(projectResolver, issueTypeResolver, fields, fieldLayoutManager,
                screenSchemes);
        this.writer = new FieldRequirementWriter(fieldLayoutManager, copies, journal);
    }

    @Override
    public SetFieldRequiredResponse setRequired(SetFieldRequiredRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request);
        if (request.isDryRun()) {
            RequirementPlan prepared = planner.prepare(request);
            SetFieldRequiredResponse response = prepared.preview;
            if (response.getStatus() != SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS) return response;
            return executor.issue(OPERATION, user.getKey(), request, fingerprint(prepared), response::withPlan);
        }
        return journal.execute(OPERATION, user.getKey(),
                () -> lock.run(FieldConfigurationLocks.EDIT, "field configurations", () -> apply(request.getPlanId(), user)));
    }

    private SetFieldRequiredResponse apply(String planId, ApplicationUser user) {
        return executor.apply(OPERATION, user.getKey(), planId, SetFieldRequiredRequest.class,
                SetFieldRequiredResponse::stale, planner::prepare, current -> true, this::fingerprint, this::write);
    }

    private SetFieldRequiredResponse write(RequirementPlan current) {
        try {
            writer.write(current.resolved);
        } catch (FieldRequirementWriter.WriteFailure failure) {
            LOG.error("set_field_required failed after writing started", failure);
            return SetFieldRequiredResponse.incomplete(current.project, current.resolved.fieldViews(),
                    failure.getMessage(), failure.effect);
        }
        return SetFieldRequiredResponse.success(false, current.resolved.hasChanges(), current.project,
                current.resolved.fieldViews(), current.resolved.scheme, current.resolved.views(false),
                current.resolved.note());
    }

    private String fingerprint(RequirementPlan plan) {
        return codec.fingerprint(java.util.Arrays.asList(plan.preview, plan.resolved == null ? null : plan.resolved.state));
    }

    private static void requireMode(SetFieldRequiredRequest request) {
        if (request == null || request.isDryRun() == null) {
            throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        }
        if (request.isDryRun() && request.getPlanId() != null) {
            throw new InvalidRequestException("Preview takes project and rows, not planId.");
        }
        if (!request.isDryRun() && (request.getProject() != null || !request.getRows().isEmpty()
                || trim(request.getPlanId()).isEmpty())) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
    }

    private static String trim(String text) { return text == null ? "" : text.trim(); }
}
