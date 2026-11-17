package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.ChangeLinks;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.issuetypes.api.ProjectIssueTypeService;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.ErrorCode;
import ru.rasulov.common.api.dto.issuetype.SchemeAction;
import ru.rasulov.common.api.dto.issuetype.SchemeView;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.Status;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Collections;
import java.util.List;

/**
 * Preview/apply orchestration with exact-ID migration and native scheme cutover. A request that lists
 * {@code newIssueTypes} or a {@code defaultIssueType} also resolves the missing global types in the same plan and
 * operation, through the shared {@link IssueTypeSetPlanner} / {@link IssueTypeSetExecutor}.
 */
@ExportAsService(ProjectIssueTypeService.class)
@Named
public class ProjectIssueTypeServiceImpl implements ProjectIssueTypeService {
    public static final String LOCK_NAME = "ru.rasulov.control.set-project-issue-types";
    private static final String OPERATION = "set_project_issue_types";
    private static final Logger log = LoggerFactory.getLogger(ProjectIssueTypeServiceImpl.class);

    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final ProjectResolver projectResolver;
    private final IssueTypeChangePlanner planner;
    private final IssueTypeSetWriter writer;
    private final IssueTypeSetPlanner setPlanner;
    private final IssueTypeSetExecutor executor;
    private final PreviewPlanExecutor planExecutor;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;
    private final ChangeLinks links;

    @Inject
    public ProjectIssueTypeServiceImpl(AdministratorGuard guard, NamedLock lock, ProjectResolver projectResolver,
                                       IssueTypeChangePlanner planner, IssueTypeSetWriter writer,
                                       IssueTypeSetPlanner setPlanner, IssueTypeSetExecutor executor,
                                       PreviewPlanExecutor planExecutor, ActionPlanCodec codec, ChangeJournal journal,
                                       ChangeLinks links) {
        this.guard = guard; this.lock = lock; this.projectResolver = projectResolver; this.planner = planner;
        this.writer = writer; this.setPlanner = setPlanner; this.executor = executor; this.planExecutor = planExecutor;
        this.codec = codec; this.journal = journal; this.links = links;
    }

    @Override
    public SetProjectIssueTypesResponse set(SetProjectIssueTypesRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request);
        if (request.isDryRun()) {
            ResolvedChange current = prepare(request);
            SetProjectIssueTypesResponse response = current.preview;
            if (response.getStatus() != Status.WOULD_CHANGE) return response;
            return planExecutor.issue(OPERATION, user.getKey(), request, fingerprint(current), response::withPlan);
        }
        // Lock order is fixed everywhere (global types, then this tool): the undo handler takes them in the same order.
        return journal.execute(OPERATION, user.getKey(), () -> lock.run(IssueTypeCreatorImpl.LOCK_NAME, "global issue types",
                () -> lock.run(LOCK_NAME, "project issue types", () -> apply(request.getPlanId(), user))));
    }

    private SetProjectIssueTypesResponse apply(String planId, ApplicationUser user) {
        return planExecutor.apply(OPERATION, user.getKey(), planId, SetProjectIssueTypesRequest.class,
                ProjectIssueTypeServiceImpl::stale,
                preview -> new Applied(prepare(preview), preview), applied -> true,
                applied -> fingerprint(applied.current),
                applied -> applied.current.set != null ? applySet(applied.current, applied.preview, user)
                        : write(applied.current.plan, applied.current.project, applied.current.preview.getMessage(), user));
    }

    private record Applied(ResolvedChange current, SetProjectIssueTypesRequest preview) { }

    private ResolvedChange prepare(SetProjectIssueTypesRequest request) {
        ProjectRef projectView = null;
        try {
            Project project = projectResolver.resolve(request.getProject());
            projectView = new ProjectRef(project.getKey(), project.getName());
            ResolvedTypeSet set = null;
            if (!request.getNewIssueTypes().isEmpty() || request.getDefaultIssueType() != null) {
                set = setPlanner.resolve(request.getIssueTypes(), request.getNewIssueTypes(), request.getDefaultIssueType());
                if (!set.valid()) {
                    return new ResolvedChange(null, projectView, set, SetProjectIssueTypesResponse.failed(true, projectView,
                            set.errorCode(), set.message(), set.suggestions()));
                }
            }
            IssueTypeChangePlan plan;
            if (set == null) {
                plan = planner.plan(project, request);
            } else {
                ResolvedTypeSet.Member requested = set.requestedDefaultMember();
                String explicitDefault = requested == null || requested.type == null ? null : requested.type.getId();
                plan = planner.plan(project, new SetProjectIssueTypesRequest(true, request.getProject(), set.existingIds(),
                        request.getMappings(), null), set.pending(), explicitDefault);
            }
            SchemeView scheme = writer.schemeView(plan, false);
            if (plan.action == SchemeAction.NONE) {
                return new ResolvedChange(plan, projectView, set, attach(set, plan,
                        SetProjectIssueTypesResponse.already(true, projectView, scheme, plan.desiredViews)));
            }
            String message = plan.action == SchemeAction.CREATE
                    ? "The current issue type scheme is default or shared. A private scheme will be created for this "
                    + "project; the other projects listed in scheme.otherProjectNames will not change."
                    : null;
            return new ResolvedChange(plan, projectView, set, attach(set, plan, SetProjectIssueTypesResponse.wouldChange(
                    projectView, scheme, plan.desiredViews, plan.added, plan.removed, plan.mappingViews, message)));
        } catch (LookupFailure e) {
            return new ResolvedChange(null, projectView, null, SetProjectIssueTypesResponse.failed(true, projectView,
                    ErrorCode.UNKNOWN_PROJECT, e.getMessage(), e.getSuggestions()));
        } catch (IssueTypeChangeFailure e) {
            return new ResolvedChange(null, projectView, null, SetProjectIssueTypesResponse.failed(true, projectView,
                    e.getCode(), e.getMessage(), e.getSuggestions()));
        } catch (RuntimeException e) {
            log.error("Could not build issue type change", e);
            return new ResolvedChange(null, projectView, null, SetProjectIssueTypesResponse.failed(true, projectView,
                    ErrorCode.JIRA_ERROR, "Jira could not build the issue type change. Nothing was changed; retry preview.", null));
        }
    }

    private static SetProjectIssueTypesResponse attach(ResolvedTypeSet set, IssueTypeChangePlan plan,
                                                       SetProjectIssueTypesResponse response) {
        return set == null ? response : response.withSet(set.preview(plan.previousDefaultTypeId, plan.removed), null);
    }

    private String fingerprint(ResolvedChange current) {
        return codec.fingerprint(java.util.Arrays.asList(current.preview,
                current.plan == null ? null : current.plan.previousDefaultTypeId,
                current.set == null ? null : current.set.fingerprintSource()));
    }

    /** Kept only in this request: both migration passes and the scheme writer use this checked plan. */
    private static final class ResolvedChange {
        private final IssueTypeChangePlan plan;
        private final ProjectRef project;
        private final ResolvedTypeSet set;
        private final SetProjectIssueTypesResponse preview;

        private ResolvedChange(IssueTypeChangePlan plan, ProjectRef project, ResolvedTypeSet set,
                               SetProjectIssueTypesResponse preview) {
            this.plan = plan; this.project = project; this.set = set; this.preview = preview;
        }
    }

    /** Request with new definitions or a default: failures are results (nothing is undone behind them), not exceptions. */
    private SetProjectIssueTypesResponse applySet(ResolvedChange current, SetProjectIssueTypesRequest request,
                                                  ApplicationUser user) {
        ChangeLinks.Report report = links.report();
        IssueTypeSetExecutor.Applied applied = executor.apply(current.plan.project, current.set, request.getMappings(),
                current.plan.previousDefaultTypeId, user, report);
        if (applied.complete()) {
            IssueTypeChangePlan plan = applied.plan;
            SetProjectIssueTypesResponse response = applied.written == null
                    ? SetProjectIssueTypesResponse.already(false, current.project, writer.schemeView(plan, false),
                    plan.desiredViews)
                    : SetProjectIssueTypesResponse.changed(current.project, applied.written.scheme, plan.desiredViews,
                    plan.added, plan.removed, plan.mappingViews, join(null, applied.message()), true)
                    .withExecution(applied.written.execution);
            return response.withSet(applied.view(), report.build());
        }
        if (applied.typesChanged()) {
            return SetProjectIssueTypesResponse.incomplete(current.project, applied.errorCode(), applied.message(),
                    applied.view(), report.build());
        }
        return SetProjectIssueTypesResponse.failed(false, current.project, applied.errorCode(), applied.message(), null)
                .withSet(applied.view(), report.build());
    }

    private SetProjectIssueTypesResponse write(IssueTypeChangePlan plan, ProjectRef projectView, String message,
                                               ApplicationUser user) {
        IssueTypeSetWriter.Written written = writer.write(plan, user, writer.begin(plan));
        return SetProjectIssueTypesResponse.changed(projectView, written.scheme, plan.desiredViews, plan.added,
                plan.removed, plan.mappingViews, written.lateNote == null ? message : join(message, written.lateNote), true)
                .withExecution(written.execution);
    }

    private static void requireMode(SetProjectIssueTypesRequest request) {
        if (request == null || request.isDryRun() == null) throw new InvalidRequestException(
                "'dryRun' must be explicitly true or false.");
        if (request.isDryRun() && request.getPlanId() != null) throw new InvalidRequestException(
                "Preview takes project, issueTypes and mappings, not planId.");
        if (!request.isDryRun() && (request.getProject() != null || !request.getIssueTypes().isEmpty()
                || !request.getMappings().isEmpty() || !request.getNewIssueTypes().isEmpty()
                || request.getDefaultIssueType() != null || request.getPlanId() == null
                || request.getPlanId().trim().isEmpty())) throw new InvalidRequestException(
                "Apply takes dryRun=false and planId only.");
    }

    private static SetProjectIssueTypesResponse stale() {
        return SetProjectIssueTypesResponse.failed(false, null, ErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again.", null);
    }

    private static String join(String first, String second) {
        return first == null ? second : second == null ? first : first + " " + second;
    }
}
