package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.ChangeLinks;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.issuetypes.api.IssueTypeCreator;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesRequest;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.ErrorCode;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.ItemResult;
import ru.rasulov.common.api.dto.issuetype.ItemStatus;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.Status;
import ru.rasulov.issuetypes.impl.IssueTypeBatchPlanner.Plan;
import ru.rasulov.issuetypes.impl.IssueTypeBatchWriter.Outcome;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code create_issue_types}: actor-bound plans for one batch of global issue types. {@link IssueTypeBatchPlanner}
 * resolves the whole batch and {@link IssueTypeBatchWriter} executes it; this class owns the preview/apply protocol,
 * the plan fingerprint and the report. Jira's manager owns persistence, default-scheme addition and cache events.
 */
@ExportAsService(IssueTypeCreator.class)
@Named
public class IssueTypeCreatorImpl implements IssueTypeCreator {
    public static final String LOCK_NAME = "ru.rasulov.control.create-issue-type";
    private static final String OPERATION = "create_issue_types";
    private static final String GLOBAL_EFFECT = "Each new type is global: Jira automatically adds it to its default "
            + "issue type scheme, making it available to every project that uses that scheme. No project's scheme is "
            + "reassigned and existing issues are not changed.";
    private static final Logger log = LoggerFactory.getLogger(IssueTypeCreatorImpl.class);

    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;
    private final IssueTypeBatchPlanner planner;
    private final IssueTypeBatchWriter writer;
    private final ChangeLinks links;

    @Inject
    public IssueTypeCreatorImpl(AdministratorGuard guard, NamedLock lock, PreviewPlanExecutor executor, ActionPlanCodec codec,
                                ChangeJournal journal, IssueTypeBatchPlanner planner, IssueTypeBatchWriter writer,
                                ChangeLinks links) {
        this.guard = guard;
        this.lock = lock;
        this.executor = executor;
        this.codec = codec;
        this.journal = journal;
        this.planner = planner;
        this.writer = writer;
        this.links = links;
    }

    @Override
    public CreateIssueTypesResponse create(CreateIssueTypesRequest request) {
        ApplicationUser administrator = guard.requireAdministrator();
        CreateIssueTypesResponse invalid = validateMode(request);
        if (invalid != null) return invalid;
        if (!request.isDryRun()) {
            return journal.execute(OPERATION, administrator.getKey(), () ->
                    lock.run(LOCK_NAME, "global issue types", () -> apply(request.getPlanId().trim(), administrator)));
        }
        try {
            Plan plan = planner.resolve(request.getIssueTypes());
            if (!plan.valid()) {
                return CreateIssueTypesResponse.failed(true, plan.preview(), plan.errorCode, plan.message);
            }
            if (plan.creates() == 0) {
                return CreateIssueTypesResponse.exists(plan.preview(), "Every requested type already exists. Reuse "
                        + "the ids in results; nothing needs to be created and no plan was issued. Existing names "
                        + "and descriptions are not changed.");
            }
            return executor.issue(OPERATION, administrator.getKey(), request,
                    codec.fingerprint(plan.fingerprintSource()), (id, expiresAt) ->
                    CreateIssueTypesResponse.wouldCreate(plan.preview(), "Create " + plan.creates() + " of "
                    + plan.items.size() + " requested types; the others exist and are reused. " + GLOBAL_EFFECT
                    + " The whole batch is confirmed once and applied in one operation. Show the table and apply the "
                    + "planId only after confirmation.").withPlan(id, expiresAt));
        } catch (RuntimeException e) {
            log.error("Could not preview global issue type creation", e);
            return CreateIssueTypesResponse.failed(true, null, ErrorCode.JIRA_ERROR,
                    "Jira could not build the issue type creation plan. Nothing was changed; retry preview.");
        }
    }

    private CreateIssueTypesResponse apply(String planId, ApplicationUser administrator) {
        return executor.apply(OPERATION, administrator.getKey(), planId, CreateIssueTypesRequest.class,
                IssueTypeCreatorImpl::stale,
                preview -> planner.resolve(preview.getIssueTypes()), current -> current.valid() && current.creates() != 0,
                current -> codec.fingerprint(current.fingerprintSource()), current -> answer(writer.write(current)));
    }

    private CreateIssueTypesResponse answer(List<Outcome> outcomes) {
        List<ItemResult> results = new ArrayList<ItemResult>();
        ChangeLinks.Report report = links.report();
        int created = 0;
        int missing = 0;
        int unknown = 0;
        Outcome failure = null;
        for (Outcome outcome : outcomes) {
            results.add(outcome.result());
            if (outcome.status == ItemStatus.CREATED) {
                created++;
                report.created(ChangeKind.ISSUE_TYPE, outcome.type.getId(), outcome.type.getName(), outcome.type.getId());
            } else if (outcome.status == ItemStatus.EXISTS) {
                report.reused(ChangeKind.ISSUE_TYPE, outcome.type.getId(), outcome.type.getName(), outcome.type.getId());
            } else {
                missing++;
                if (outcome.status == ItemStatus.UNKNOWN) unknown++;
                if (failure == null && (outcome.status == ItemStatus.FAILED || outcome.status == ItemStatus.UNKNOWN)) {
                    failure = outcome;
                }
            }
        }
        if (missing == 0) {
            return CreateIssueTypesResponse.applied(Status.CREATED, results, "Created " + created + " global issue "
                    + "type(s); the other requested types already existed and were reused. " + GLOBAL_EFFECT
                    + " There is no automatic undo.", null, report.build());
        }
        ErrorCode code = failure == null ? ErrorCode.JIRA_ERROR : failure.errorCode;
        String detail = failure == null ? "" : " First failure: " + failure.item.key + ": " + failure.message;
        String unknownNote = unknown == 0 ? "" : " " + unknown + " item(s) have an UNKNOWN outcome: the type may or "
                + "may not exist. An id shown on such an item is only observed, not confirmed as created by this "
                + "operation, and is not in report.created.";
        if (created == 0 && unknown == 0) {
            return CreateIssueTypesResponse.applied(Status.FAILED, results, "No issue type was created: every write "
                    + "that was attempted is known to have failed. Nothing was changed, apart from what results "
                    + "lists as existing." + detail, code, report.build());
        }
        return CreateIssueTypesResponse.applied(Status.PARTIALLY_CHANGED, results, created + " type(s) were created "
                + "and REMAIN in Jira (see results and report, with their ids); " + missing + " were not completed."
                + unknownNote + " Nothing was rolled back and nothing is retried automatically. " + GLOBAL_EFFECT
                + " To finish, check Jira and preview the original list again: types that exist are then reused, "
                + "only the missing ones are planned." + detail, code, report.build());
    }

    private static CreateIssueTypesResponse validateMode(CreateIssueTypesRequest request) {
        if (request == null || request.isDryRun() == null) return CreateIssueTypesResponse.failed(true, null,
                ErrorCode.VALIDATION_ERROR, "Send an issue types request with dryRun explicitly set to true or false.");
        if (request.isDryRun()) {
            if (request.getPlanId() != null) return CreateIssueTypesResponse.failed(true, null, ErrorCode.VALIDATION_ERROR,
                    "A preview takes issueTypes, not planId.");
        } else if (!request.getIssueTypes().isEmpty() || request.getPlanId() == null
                || request.getPlanId().trim().isEmpty()) {
            return CreateIssueTypesResponse.failed(false, null, ErrorCode.VALIDATION_ERROR,
                    "An apply request takes dryRun=false and the planId from preview only.");
        }
        return null;
    }

    private static CreateIssueTypesResponse stale() {
        return CreateIssueTypesResponse.failed(false, null, ErrorCode.PREVIEW_STALE,
                "The preview is missing, expired, belongs to another administrator, was already used, or Jira's "
                        + "issue type state changed. Nothing was written. Run preview again and obtain a new "
                        + "confirmation.");
    }
}
