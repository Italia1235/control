package ru.rasulov.screens.impl;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.screens.api.ProjectOperationScreenAssigner;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensRequest;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.ErrorCode;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.Status;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.AssignmentState;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.Created;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.SchemeMode;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.TypeAssignment;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.SchemeChange;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import ru.rasulov.screens.impl.OperationScreenPlan.Failure;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Actor-bound, single-use preview/apply of a batch of operation-screen assignments for one project. */
@ExportAsService(ProjectOperationScreenAssigner.class)
@Named
public class ProjectOperationScreenAssignerImpl implements ProjectOperationScreenAssigner {
    private static final String OPERATION = "set_project_operation_screens";
    private static final Logger log = LoggerFactory.getLogger(ProjectOperationScreenAssignerImpl.class);
    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final OperationScreenPlanner planner;
    private final OperationScreenWriter writer;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;

    @Inject
    public ProjectOperationScreenAssignerImpl(AdministratorGuard guard, NamedLock lock, OperationScreenPlanner planner,
            OperationScreenWriter writer, PreviewPlanExecutor executor, ActionPlanCodec codec) {
        this.guard = guard; this.lock = lock; this.planner = planner; this.writer = writer;
        this.executor = executor; this.codec = codec;
    }

    @Override
    public SetProjectOperationScreensResponse assign(SetProjectOperationScreensRequest request) {
        ApplicationUser actor = guard.requireAdministrator();
        SetProjectOperationScreensResponse invalid = validateMode(request);
        if (invalid != null) return invalid;
        if (!request.isDryRun()) {
            return lock.run(ScreenSchemeServiceImpl.LOCK_NAME, "project operation screens",
                    () -> apply(request.getPlanId(), actor));
        }
        try {
            OperationScreenPlan prepared = planner.plan(request);
            if (prepared.response.getStatus() != Status.WOULD_ASSIGN) return prepared.response;
            return executor.issue(OPERATION, actor.getKey(), request, prepared.fingerprint, prepared.response::withPlan);
        } catch (Failure e) {
            return SetProjectOperationScreensResponse.failed(true, e.code, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Could not preview operation screen assignment", e);
            return SetProjectOperationScreensResponse.failed(true, ErrorCode.JIRA_ERROR,
                    "Jira could not prepare operation screen assignments. Nothing was changed; retry preview.");
        }
    }

    private SetProjectOperationScreensResponse apply(String planId, ApplicationUser actor) {
        return executor.apply(OPERATION, actor.getKey(), planId, SetProjectOperationScreensRequest.class,
                ProjectOperationScreenAssignerImpl::stale, this::replan,
                current -> current != null && current.response.getStatus() == Status.WOULD_ASSIGN,
                current -> current.fingerprint, current -> outcome(current, writer.write(current)));
    }

    /** The saved preview no longer plans at all: {@code null}, which apply answers as stale. */
    private OperationScreenPlan replan(SetProjectOperationScreensRequest request) {
        try { return planner.plan(request); }
        catch (Failure e) { return null; }
    }

    /** Reports what exists: read-back matrix on success, created objects and association/mapping state otherwise. */
    private SetProjectOperationScreensResponse outcome(OperationScreenPlan plan, OperationScreenWriter.Result written) {
        boolean inPlace = plan.mode == SchemeMode.UPDATE_PRIVATE;
        List<SchemeChange> schemes = new ArrayList<SchemeChange>();
        Set<Long> createdIds = new HashSet<Long>();
        for (int i = 0; i < plan.copies.size(); i++) {
            SchemeChange planned = plan.response.getScreenSchemes().get(i);
            FieldScreenScheme made = written.made.get(plan.copies.get(i));
            if (made != null) createdIds.add(made.getId());
            schemes.add(new SchemeChange(planned.getSourceId(), planned.getSourceName(), planned.getNewName(),
                    made == null ? null : made.getId(), planned.getAppliesTo(), planned.getOperations()));
        }
        Created created = new Created(written.top, written.schemes);
        List<String> notes = new ArrayList<String>(plan.response.getWarnings());
        if (written.note != null) notes.add(written.note);
        notes.addAll(written.details);
        Long topId = inPlace ? plan.source.getId() : written.top == null ? null : written.top.getId();
        if (written.complete()) {
            try {
                return plan.response.assigned(topId, planner.readBack(plan, topId, createdIds, notes), schemes, created, notes);
            } catch (RuntimeException e) {
                log.error("Could not read back the assigned operation screens", e);
                notes.add("The assignment succeeded but Jira's state could not be read back; the matrix is the plan, not a reading.");
                return plan.response.assignedUnverified(topId, schemes, created, notes);
            }
        }
        boolean any = written.top != null || !written.schemes.isEmpty();
        List<TypeAssignment> actual = null;
        if (inPlace && written.state == AssignmentState.PARTIAL) {
            try { actual = planner.readBack(plan, topId, createdIds, notes); }
            catch (RuntimeException e) {
                log.error("Could not read back the matrix after a partial in-place update", e);
                notes.add("The per-type matrix could not be read back; use the mapping rows above.");
            }
        }
        String text = (written.error == null ? "The assignment did not complete." : written.error + ".")
                + stateText(plan, written, inPlace)
                + (any ? " Objects that were created are listed in 'created' and were not removed; incomplete ones are marked complete=false."
                : " Nothing was created.")
                + " Nothing is rolled back automatically. Run a new preview before retrying.";
        boolean changedOrUnknown = written.state == AssignmentState.UNKNOWN || written.state == AssignmentState.PARTIAL;
        return plan.response.incomplete(any || changedOrUnknown ? Status.PARTIALLY_ASSIGNED : Status.FAILED,
                written.state, actual, any ? created : null, schemes, notes, text);
    }

    private static String stateText(OperationScreenPlan plan, OperationScreenWriter.Result written, boolean inPlace) {
        Long id = plan.source.getId();
        if (!inPlace) {
            return written.state == AssignmentState.UNKNOWN
                    ? " The project's current Issue Type Screen Scheme could not be determined: check it in Jira."
                    : " The project still uses Issue Type Screen Scheme " + id + ".";
        }
        switch (written.state) {
            case PARTIAL: return " Issue Type Screen Scheme " + id + " was updated only partly: some mappings already changed "
                    + "(rows and matrix are read back from Jira, not planned).";
            case UNKNOWN: return " The mappings of Issue Type Screen Scheme " + id
                    + " may be partly changed and could not be determined: check them in Jira.";
            default: return " The mappings of Issue Type Screen Scheme " + id + " were not changed "
                    + (written.mappingsStarted ? "(confirmed by reading them back from Jira)." : "(no mapping write was attempted).");
        }
    }

    private static SetProjectOperationScreensResponse validateMode(SetProjectOperationScreensRequest request) {
        if (request == null || request.isDryRun() == null) return SetProjectOperationScreensResponse.failed(true,
                ErrorCode.VALIDATION_ERROR, "dryRun must explicitly be true or false.");
        if (request.isDryRun()) {
            if (request.getPlanId() != null) return SetProjectOperationScreensResponse.failed(true,
                    ErrorCode.VALIDATION_ERROR, "Preview takes project and mappings, not planId.");
        } else if (request.getProject() != null || request.getMappings() != null
                || request.getPlanId() == null || request.getPlanId().trim().isEmpty()) {
            return SetProjectOperationScreensResponse.failed(false, ErrorCode.VALIDATION_ERROR,
                    "Apply takes only dryRun=false and planId from the confirmed preview.");
        }
        return null;
    }
    private static SetProjectOperationScreensResponse stale() {
        return SetProjectOperationScreensResponse.failed(false, ErrorCode.PREVIEW_STALE,
                "The preview is missing, expired, foreign, consumed, or Jira state changed. Run preview again and obtain new confirmation.");
    }
}
