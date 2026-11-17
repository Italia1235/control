package ru.rasulov.screens.impl;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.screens.api.ScreenSchemeOperationEditor;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsRequest;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.ErrorCode;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.Status;
import ru.rasulov.screens.impl.ScreenSchemeOperationPlan.Failure;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Actor-bound, single-use preview/apply of an in-place edit of the rows of an existing Screen Scheme. The write runs
 * under the existing global screen-scheme lock, which serializes Control operations only.
 */
@ExportAsService(ScreenSchemeOperationEditor.class)
@Named
public class ScreenSchemeOperationEditorImpl implements ScreenSchemeOperationEditor {
    private static final String OPERATION = "set_screen_scheme_operations";
    private static final Logger log = LoggerFactory.getLogger(ScreenSchemeOperationEditorImpl.class);
    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final ScreenSchemeOperationPlanner planner;
    private final ScreenSchemeOperationWriter writer;
    private final PreviewPlanExecutor executor;

    @Inject
    public ScreenSchemeOperationEditorImpl(AdministratorGuard guard, NamedLock lock, ScreenSchemeOperationPlanner planner,
            ScreenSchemeOperationWriter writer, PreviewPlanExecutor executor) {
        this.guard = guard; this.lock = lock; this.planner = planner; this.writer = writer; this.executor = executor;
    }

    @Override
    public SetScreenSchemeOperationsResponse edit(SetScreenSchemeOperationsRequest request) {
        ApplicationUser actor = guard.requireAdministrator();
        SetScreenSchemeOperationsResponse invalid = validateMode(request);
        if (invalid != null) return invalid;
        if (!request.isDryRun()) {
            return lock.run(ScreenSchemeServiceImpl.LOCK_NAME, "screen scheme operations",
                    () -> apply(request.getPlanId(), actor));
        }
        try {
            ScreenSchemeOperationPlan prepared = planner.plan(request);
            if (prepared.response.getStatus() != Status.WOULD_CHANGE) return prepared.response;
            return executor.issue(OPERATION, actor.getKey(), request, prepared.fingerprint, prepared.response::withPlan);
        } catch (Failure e) {
            return SetScreenSchemeOperationsResponse.failed(true, e.code, e.rowIndex, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Could not preview the Screen Scheme operation edit", e);
            return SetScreenSchemeOperationsResponse.failed(true, ErrorCode.JIRA_ERROR, null,
                    "Jira could not prepare the edit. Nothing was changed; retry preview.");
        }
    }

    private SetScreenSchemeOperationsResponse apply(String planId, ApplicationUser actor) {
        return executor.apply(OPERATION, actor.getKey(), planId, SetScreenSchemeOperationsRequest.class,
                ScreenSchemeOperationEditorImpl::stale, this::replan,
                current -> current != null && current.response.getStatus() == Status.WOULD_CHANGE,
                current -> current.fingerprint, current -> outcome(current, writer.write(current)));
    }

    /** The saved preview no longer plans at all (a name, screen or scheme is gone, Jira state is unreadable): {@code null}, answered as stale. */
    private ScreenSchemeOperationPlan replan(SetScreenSchemeOperationsRequest request) {
        try { return planner.plan(request); }
        catch (Failure e) { return null; }
        catch (RuntimeException e) {
            log.error("Could not re-plan the Screen Scheme operation edit at apply", e);
            return null;
        }
    }

    private static SetScreenSchemeOperationsResponse outcome(ScreenSchemeOperationPlan plan,
            ScreenSchemeOperationWriter.Result written) {
        return plan.response.outcome(written.status, written.rows, written.readBack, written.notes, written.message);
    }

    private static SetScreenSchemeOperationsResponse validateMode(SetScreenSchemeOperationsRequest request) {
        if (request == null || request.isDryRun() == null) return SetScreenSchemeOperationsResponse.failed(true,
                ErrorCode.VALIDATION_ERROR, null, "dryRun must explicitly be true or false.");
        if (request.isDryRun()) {
            if (request.getPlanId() != null) return SetScreenSchemeOperationsResponse.failed(true,
                    ErrorCode.VALIDATION_ERROR, null, "Preview takes screenScheme and rows, not planId.");
        } else if (request.getScreenScheme() != null || request.getRows() != null
                || request.getPlanId() == null || request.getPlanId().trim().isEmpty()) {
            return SetScreenSchemeOperationsResponse.failed(false, ErrorCode.VALIDATION_ERROR, null,
                    "Apply takes only dryRun=false and planId from the confirmed preview.");
        }
        return null;
    }

    private static SetScreenSchemeOperationsResponse stale() {
        return SetScreenSchemeOperationsResponse.failed(false, ErrorCode.PREVIEW_STALE, null,
                "The preview is missing, expired, foreign, consumed, or Jira state changed. Run preview again and obtain new confirmation.");
    }
}
