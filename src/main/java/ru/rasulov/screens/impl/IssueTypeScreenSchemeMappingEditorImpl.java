package ru.rasulov.screens.impl;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.screens.api.IssueTypeScreenSchemeMappingEditor;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsRequest;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.ErrorCode;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.Status;
import ru.rasulov.screens.impl.IssueTypeScreenSchemeMappingPlan.Failure;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Actor-bound, single-use preview/apply of an in-place edit of the rows of an existing Issue Type Screen Scheme. The write
 * runs under the existing global screen-scheme lock, which serializes Control operations only.
 */
@ExportAsService(IssueTypeScreenSchemeMappingEditor.class)
@Named
public class IssueTypeScreenSchemeMappingEditorImpl implements IssueTypeScreenSchemeMappingEditor {
    private static final String OPERATION = "set_issue_type_screen_scheme_mappings";
    private static final Logger log = LoggerFactory.getLogger(IssueTypeScreenSchemeMappingEditorImpl.class);
    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final IssueTypeScreenSchemeMappingPlanner planner;
    private final IssueTypeScreenSchemeMappingWriter writer;
    private final PreviewPlanExecutor executor;

    @Inject
    public IssueTypeScreenSchemeMappingEditorImpl(AdministratorGuard guard, NamedLock lock,
            IssueTypeScreenSchemeMappingPlanner planner, IssueTypeScreenSchemeMappingWriter writer,
            PreviewPlanExecutor executor) {
        this.guard = guard; this.lock = lock; this.planner = planner; this.writer = writer; this.executor = executor;
    }

    @Override
    public SetIssueTypeScreenSchemeMappingsResponse edit(SetIssueTypeScreenSchemeMappingsRequest request) {
        ApplicationUser actor = guard.requireAdministrator();
        SetIssueTypeScreenSchemeMappingsResponse invalid = validateMode(request);
        if (invalid != null) return invalid;
        if (!request.isDryRun()) {
            return lock.run(ScreenSchemeServiceImpl.LOCK_NAME, "issue type screen scheme mappings",
                    () -> apply(request.getPlanId(), actor));
        }
        try {
            IssueTypeScreenSchemeMappingPlan prepared = planner.plan(request);
            if (prepared.response.getStatus() != Status.WOULD_CHANGE) return prepared.response;
            return executor.issue(OPERATION, actor.getKey(), request, prepared.fingerprint, prepared.response::withPlan);
        } catch (Failure e) {
            return SetIssueTypeScreenSchemeMappingsResponse.failed(true, e.code, e.rowIndex, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Could not preview the Issue Type Screen Scheme mapping edit", e);
            return SetIssueTypeScreenSchemeMappingsResponse.failed(true, ErrorCode.JIRA_ERROR, null,
                    "Jira could not prepare the edit. Nothing was changed; retry preview.");
        }
    }

    private SetIssueTypeScreenSchemeMappingsResponse apply(String planId, ApplicationUser actor) {
        return executor.apply(OPERATION, actor.getKey(), planId, SetIssueTypeScreenSchemeMappingsRequest.class,
                IssueTypeScreenSchemeMappingEditorImpl::stale, this::replan,
                current -> current != null && current.response.getStatus() == Status.WOULD_CHANGE,
                current -> current.fingerprint, current -> outcome(current, writer.write(current)));
    }

    /** The saved preview no longer plans at all (a name, type or Screen Scheme is gone): {@code null}, answered as stale. */
    private IssueTypeScreenSchemeMappingPlan replan(SetIssueTypeScreenSchemeMappingsRequest request) {
        try { return planner.plan(request); }
        catch (Failure e) { return null; }
    }

    private static SetIssueTypeScreenSchemeMappingsResponse outcome(IssueTypeScreenSchemeMappingPlan plan,
            IssueTypeScreenSchemeMappingWriter.Result written) {
        return plan.response.outcome(written.status, written.rows, written.readBack, written.notes, written.message);
    }

    private static SetIssueTypeScreenSchemeMappingsResponse validateMode(SetIssueTypeScreenSchemeMappingsRequest request) {
        if (request == null || request.isDryRun() == null) return SetIssueTypeScreenSchemeMappingsResponse.failed(true,
                ErrorCode.VALIDATION_ERROR, null, "dryRun must explicitly be true or false.");
        if (request.isDryRun()) {
            if (request.getPlanId() != null) return SetIssueTypeScreenSchemeMappingsResponse.failed(true,
                    ErrorCode.VALIDATION_ERROR, null, "Preview takes issueTypeScreenScheme and rows, not planId.");
        } else if (request.getIssueTypeScreenScheme() != null || request.getRows() != null
                || request.getPlanId() == null || request.getPlanId().trim().isEmpty()) {
            return SetIssueTypeScreenSchemeMappingsResponse.failed(false, ErrorCode.VALIDATION_ERROR, null,
                    "Apply takes only dryRun=false and planId from the confirmed preview.");
        }
        return null;
    }

    private static SetIssueTypeScreenSchemeMappingsResponse stale() {
        return SetIssueTypeScreenSchemeMappingsResponse.failed(false, ErrorCode.PREVIEW_STALE, null,
                "The preview is missing, expired, foreign, consumed, or Jira state changed. Run preview again and obtain new confirmation.");
    }
}
