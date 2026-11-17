package ru.rasulov.screens.impl;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.screens.api.IssueTypeScreenSchemeAssigner;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeRequest;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.ErrorCode;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.Status;
import ru.rasulov.screens.impl.IssueTypeScreenSchemeAssignmentPlan.Failure;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Actor-bound, single-use preview/apply of switching ONE project to an existing Issue Type Screen Scheme. The write runs
 * under the existing global screen-scheme lock, which serializes Control operations only.
 */
@ExportAsService(IssueTypeScreenSchemeAssigner.class)
@Named
public class IssueTypeScreenSchemeAssignerImpl implements IssueTypeScreenSchemeAssigner {
    private static final String OPERATION = "assign_issue_type_screen_scheme";
    private static final Logger log = LoggerFactory.getLogger(IssueTypeScreenSchemeAssignerImpl.class);
    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final IssueTypeScreenSchemeAssignmentPlanner planner;
    private final IssueTypeScreenSchemeAssignmentWriter writer;
    private final PreviewPlanExecutor executor;

    @Inject
    public IssueTypeScreenSchemeAssignerImpl(AdministratorGuard guard, NamedLock lock,
            IssueTypeScreenSchemeAssignmentPlanner planner, IssueTypeScreenSchemeAssignmentWriter writer,
            PreviewPlanExecutor executor) {
        this.guard = guard; this.lock = lock; this.planner = planner; this.writer = writer; this.executor = executor;
    }

    @Override
    public AssignIssueTypeScreenSchemeResponse assign(AssignIssueTypeScreenSchemeRequest request) {
        ApplicationUser actor = guard.requireAdministrator();
        AssignIssueTypeScreenSchemeResponse invalid = validateMode(request);
        if (invalid != null) return invalid;
        if (!request.isDryRun()) {
            return lock.run(ScreenSchemeServiceImpl.LOCK_NAME, "issue type screen scheme assignment",
                    () -> apply(request.getPlanId(), actor));
        }
        try {
            IssueTypeScreenSchemeAssignmentPlan prepared = planner.plan(request);
            if (prepared.response.getStatus() != Status.WOULD_CHANGE) return prepared.response;
            return executor.issue(OPERATION, actor.getKey(), request, prepared.fingerprint, prepared.response::withPlan);
        } catch (Failure e) {
            return AssignIssueTypeScreenSchemeResponse.failed(true, e.code, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Could not preview the Issue Type Screen Scheme assignment", e);
            return AssignIssueTypeScreenSchemeResponse.failed(true, ErrorCode.JIRA_ERROR,
                    "Jira could not prepare the assignment. Nothing was changed; retry preview.");
        }
    }

    private AssignIssueTypeScreenSchemeResponse apply(String planId, ApplicationUser actor) {
        return executor.apply(OPERATION, actor.getKey(), planId, AssignIssueTypeScreenSchemeRequest.class,
                IssueTypeScreenSchemeAssignerImpl::stale, this::replan,
                current -> current != null && current.response.getStatus() == Status.WOULD_CHANGE,
                current -> current.fingerprint, current -> outcome(current, writer.write(current)));
    }

    /** The saved preview no longer plans (an object is gone or cannot be read): {@code null}, answered as stale. */
    private IssueTypeScreenSchemeAssignmentPlan replan(AssignIssueTypeScreenSchemeRequest request) {
        try { return planner.plan(request); }
        catch (Failure e) { return null; }
        catch (RuntimeException e) {
            log.warn("Could not plan the saved Issue Type Screen Scheme assignment again; answered as stale", e);
            return null;
        }
    }

    private static AssignIssueTypeScreenSchemeResponse outcome(IssueTypeScreenSchemeAssignmentPlan plan,
            IssueTypeScreenSchemeAssignmentWriter.Result written) {
        return plan.response.outcome(written.status, written.observed, written.readBack, written.notes, written.message);
    }

    private static AssignIssueTypeScreenSchemeResponse validateMode(AssignIssueTypeScreenSchemeRequest request) {
        if (request == null || request.isDryRun() == null) return AssignIssueTypeScreenSchemeResponse.failed(true,
                ErrorCode.VALIDATION_ERROR, "dryRun must explicitly be true or false.");
        if (request.isDryRun()) {
            if (request.getPlanId() != null) return AssignIssueTypeScreenSchemeResponse.failed(true,
                    ErrorCode.VALIDATION_ERROR, "Preview takes project and issueTypeScreenScheme, not planId.");
        } else if (request.getProject() != null || request.getIssueTypeScreenScheme() != null
                || request.getPlanId() == null || request.getPlanId().trim().isEmpty()) {
            return AssignIssueTypeScreenSchemeResponse.failed(false, ErrorCode.VALIDATION_ERROR,
                    "Apply takes only dryRun=false and planId from the confirmed preview.");
        }
        return null;
    }

    private static AssignIssueTypeScreenSchemeResponse stale() {
        return AssignIssueTypeScreenSchemeResponse.failed(false, ErrorCode.PREVIEW_STALE,
                "The preview is missing, expired, foreign, consumed, or Jira state changed. Run preview again and obtain new confirmation.");
    }
}
