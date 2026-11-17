package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.screens.api.ScreenSchemeService;
import ru.rasulov.screens.api.dto.CloneProjectScreensRequest;
import ru.rasulov.screens.api.dto.CloneProjectScreensResponse;
import ru.rasulov.screens.api.dto.CloneProjectScreensResponse.CopyView;
import ru.rasulov.screens.api.dto.CloneProjectScreensResponse.ErrorCode;
import ru.rasulov.screens.api.dto.ProjectScreensResponse;
import ru.rasulov.screens.api.dto.ProjectScreensResponse.IssueTypeScreensView;
import ru.rasulov.screens.api.dto.ProjectScreensResponse.OperationScreenView;
import ru.rasulov.screens.api.dto.ProjectScreensResponse.ScreenSchemeView;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@ExportAsService(ScreenSchemeService.class)
@Named
public class ScreenSchemeServiceImpl implements ScreenSchemeService {
    static final String LOCK_NAME = "ru.rasulov.control.screen-scheme-clone";
    private static final String OPERATION = "clone_project_screens";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ScreenSchemeServiceImpl.class);
    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final ProjectResolver projectResolver;
    private final ProjectManager projectManager;
    private final FieldScreenSchemeManager screenSchemeManager;
    private final IssueTypeScreenSchemeManager issueTypeScreenSchemeManager;
    private final ScreenClonePlanner planner;
    private final ScreenCloneWriter writer;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;
    private final ScreenUsage usage;

    @Inject
    public ScreenSchemeServiceImpl(AdministratorGuard guard, NamedLock lock, ProjectResolver projectResolver,
                                   @ComponentImport ProjectManager projectManager,
                                   @ComponentImport FieldScreenSchemeManager screenSchemeManager,
                                   @ComponentImport IssueTypeScreenSchemeManager issueTypeScreenSchemeManager,
                                   ScreenClonePlanner planner, ScreenCloneWriter writer,
                                   PreviewPlanExecutor executor, ActionPlanCodec codec) {
        this.guard = guard; this.lock = lock; this.projectResolver = projectResolver;
        this.projectManager = projectManager; this.screenSchemeManager = screenSchemeManager;
        this.issueTypeScreenSchemeManager = issueTypeScreenSchemeManager; this.planner = planner; this.writer = writer;
        this.executor = executor; this.codec = codec;
        this.usage = new ScreenUsage(projectManager, screenSchemeManager, issueTypeScreenSchemeManager);
    }

    @Override
    public ProjectScreensResponse projectScreens(String projectName) {
        guard.requireAdministrator();
        Project project;
        try { project = projectResolver.resolve(projectName); }
        catch (LookupFailure e) { throw invalidLookup(e); }
        IssueTypeScreenScheme scheme = issueTypeScreenSchemeManager.getIssueTypeScreenScheme(project);
        if (scheme == null) throw new InvalidRequestException("Project " + project.getKey() + " has no issue type screen scheme.");
        List<Project> others = usage.others(usage.projects(scheme), project);
        ProjectScreensResponse.SchemeView schemeView = new ProjectScreensResponse.SchemeView(scheme.getId(),
                scheme.getName(), scheme.isDefault(), others.size(), usage.refs(others));
        List<IssueType> types = new ArrayList<IssueType>(project.getIssueTypes());
        types.sort(Comparator.comparing(IssueType::getName).thenComparing(IssueType::getId));
        List<IssueTypeScreensView> rows = new ArrayList<IssueTypeScreensView>();
        for (IssueType type : types) {
            FieldScreenScheme fieldScheme = scheme.getEffectiveFieldScreenScheme(type);
            rows.add(new IssueTypeScreensView(new IssueTypeRef(type.getId(), type.getName()), view(project, fieldScheme)));
        }
        return new ProjectScreensResponse(new ProjectRef(project.getKey(), project.getName()), schemeView, rows);
    }

    private ScreenSchemeView view(Project project, FieldScreenScheme scheme) {
        if (scheme == null) return null;
        List<Project> others = usage.others(usage.projects(scheme), project);
        List<FieldScreenSchemeItem> items = new ArrayList<FieldScreenSchemeItem>(scheme.getFieldScreenSchemeItems());
        items.sort(Comparator.comparing(item -> operation(item)));
        List<OperationScreenView> screens = new ArrayList<OperationScreenView>();
        for (FieldScreenSchemeItem item : items) {
            FieldScreen screen = item.getFieldScreen();
            if (screen == null) continue;
            List<Project> screenOthers = usage.others(usage.projects(screen), project);
            screens.add(new OperationScreenView(operation(item), screen.getId(), screen.getName(), screenOthers.size(),
                    usage.refs(screenOthers)));
        }
        return new ScreenSchemeView(scheme.getId(), scheme.getName(), others.size(), usage.refs(others), screens);
    }

    private static String operation(FieldScreenSchemeItem item) {
        String name = item.getIssueOperationName();
        return name == null || name.trim().isEmpty() ? "DEFAULT" : name;
    }

    @Override
    public CloneProjectScreensResponse cloneProjectScreens(CloneProjectScreensRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request);
        if (request.isDryRun()) {
            CloneProjectScreensResponse response = prepare(request).response;
            if (response.getStatus() != CloneProjectScreensResponse.Status.WOULD_CLONE) return response;
            return executor.issue(OPERATION, user.getKey(), request, codec.fingerprint(response), response::withPlan);
        }
        return lock.run(LOCK_NAME, "screen schemes", () -> apply(request.getPlanId(), user));
    }

    private CloneProjectScreensResponse apply(String planId, ApplicationUser user) {
        return executor.apply(OPERATION, user.getKey(), planId, CloneProjectScreensRequest.class,
                CloneProjectScreensResponse::stale, this::prepare,
                current -> current.response.getStatus() == CloneProjectScreensResponse.Status.WOULD_CLONE,
                current -> codec.fingerprint(current.response), this::write);
    }

    private CloneProjectScreensResponse write(Prepared current) {
        ScreenClonePlan plan = current.plan;
        ProjectRef ref = new ProjectRef(plan.project.getKey(), plan.project.getName());
        try {
            ScreenCloneWriter.Written written = writer.write(plan);
            return CloneProjectScreensResponse.success(false, ref, plan.scope, plan.copyScreens,
                    written.schemeView(plan.schemeView), written.screenSchemeViews(plan.screenSchemeViews),
                    written.screenViews(plan.screenViews));
        } catch (ScreenCloneWriter.Interrupted interrupted) {
            LOG.error("clone_project_screens stopped after the first write for project {}", ref.getKey(), interrupted);
            if (!interrupted.partial()) {
                return CloneProjectScreensResponse.failed(false, ref, plan.scope, plan.copyScreens,
                        CloneProjectScreensResponse.ErrorCode.JIRA_ERROR, interrupted.explain(), null);
            }
            ScreenCloneWriter.Written known = interrupted.written;
            return CloneProjectScreensResponse.partial(ref, plan.scope, plan.copyScreens,
                    known.createdSchemeView(plan.schemeView), known.createdScreenSchemeViews(plan.screenSchemeViews),
                    known.createdScreenViews(plan.screenViews), interrupted.explain());
        } catch (ScreenCloneFailure e) {
            return CloneProjectScreensResponse.failed(false, ref, plan.scope, plan.copyScreens,
                    e.getCode(), e.getMessage(), e.getSuggestions());
        }
    }

    private Prepared prepare(CloneProjectScreensRequest request) {
        ProjectRef ref = null;
        try {
            Project project;
            try { project = projectResolver.resolve(request.getProject()); }
            catch (LookupFailure e) { throw projectFailure(e); }
            ref = new ProjectRef(project.getKey(), project.getName());
            ScreenClonePlan plan = planner.plan(project, request);
            CloneProjectScreensResponse response = !plan.separationNeeded
                    ? CloneProjectScreensResponse.alreadySeparate(true, ref, plan.scope, plan.copyScreens,
                            plan.schemeView, plan.screenSchemeViews, plan.screenViews)
                    : CloneProjectScreensResponse.success(true, ref, plan.scope, plan.copyScreens,
                            plan.schemeView, plan.screenSchemeViews, plan.screenViews);
            return new Prepared(plan, response);
        } catch (ScreenCloneFailure e) {
            return new Prepared(null, CloneProjectScreensResponse.failed(true, ref, request.getScope(),
                    request.isCopyScreens(), e.getCode(), e.getMessage(), e.getSuggestions()));
        }
    }

    private static final class Prepared {
        final ScreenClonePlan plan;
        final CloneProjectScreensResponse response;

        Prepared(ScreenClonePlan plan, CloneProjectScreensResponse response) {
            this.plan = plan;
            this.response = response;
        }
    }

    private static void requireMode(CloneProjectScreensRequest request) {
        if (request == null || request.isDryRun() == null) throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        if (request.isDryRun() && request.getPlanId() != null) throw new InvalidRequestException("Preview takes clone details, not planId.");
        if (!request.isDryRun() && (request.getProject() != null || request.getScope() != null
                || !request.getIssueTypes().isEmpty() || request.isCopyScreens() != null || request.getPlanId() == null
                || request.getPlanId().trim().isEmpty())) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
    }

    private static ScreenCloneFailure projectFailure(LookupFailure e) {
        ErrorCode code = e.getKind() == LookupFailure.Kind.UNKNOWN_PROJECT ? ErrorCode.UNKNOWN_PROJECT : ErrorCode.VALIDATION_ERROR;
        return new ScreenCloneFailure(code, e.getMessage(), e.getSuggestions());
    }
    private static InvalidRequestException invalidLookup(LookupFailure e) {
        return new InvalidRequestException(e.getMessage() + (e.getSuggestions().isEmpty() ? "" : " Suggestions: "
                + String.join(", ", e.getSuggestions())));
    }
}
