package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.api.FieldConfigurationCloneService;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsRequest;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse.ErrorCode;

import ru.rasulov.common.impl.undo.ChangeJournal;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@ExportAsService(FieldConfigurationCloneService.class)
@Named
public class FieldConfigurationCloneServiceImpl implements FieldConfigurationCloneService {
    private static final String OPERATION = "clone_project_field_configurations";
    private static final Logger LOG = LoggerFactory.getLogger(FieldConfigurationCloneServiceImpl.class);

    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final ProjectResolver projectResolver;
    private final FieldConfigurationClonePlanner planner;
    private final FieldConfigurationCloneWriter writer;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;

    @Inject
    public FieldConfigurationCloneServiceImpl(AdministratorGuard guard, NamedLock lock,
                                               ProjectResolver projectResolver,
                                               FieldConfigurationClonePlanner planner,
                                               FieldConfigurationCloneWriter writer,
                                               PreviewPlanExecutor executor, ActionPlanCodec codec, ChangeJournal journal) {
        this.guard = guard;
        this.lock = lock;
        this.projectResolver = projectResolver;
        this.planner = planner;
        this.writer = writer;
        this.executor = executor;
        this.codec = codec;
        this.journal = journal;
    }

    @Override
    public CloneProjectFieldConfigurationsResponse cloneProjectFieldConfigurations(
            CloneProjectFieldConfigurationsRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request);
        if (request.isDryRun()) {
            ClonePlan current = prepare(request);
            CloneProjectFieldConfigurationsResponse response = current.preview;
            if (response.getStatus() != CloneProjectFieldConfigurationsResponse.Status.WOULD_CLONE) return response;
            return executor.issue(OPERATION, user.getKey(), request, fingerprint(current), response::withPlan);
        }
        return journal.execute(OPERATION, user.getKey(),
                () -> lock.run(FieldConfigurationLocks.EDIT, "field configurations", () -> apply(request.getPlanId(), user)));
    }

    private CloneProjectFieldConfigurationsResponse apply(String planId, ApplicationUser user) {
        return executor.apply(OPERATION, user.getKey(), planId, CloneProjectFieldConfigurationsRequest.class,
                CloneProjectFieldConfigurationsResponse::stale, this::prepare, current -> true,
                this::fingerprint, this::write);
    }

    private ClonePlan prepare(CloneProjectFieldConfigurationsRequest request) {
        ProjectRef ref = null;
        try {
            Project project;
            try {
                project = projectResolver.resolve(request.getProject());
            } catch (LookupFailure e) {
                ErrorCode code = e.getKind() == LookupFailure.Kind.UNKNOWN_PROJECT
                        ? ErrorCode.UNKNOWN_PROJECT : ErrorCode.VALIDATION_ERROR;
                throw new FieldConfigurationCloneFailure(code, e.getMessage(), e.getSuggestions());
            }
            ref = new ProjectRef(project.getKey(), project.getName());
            FieldConfigurationClonePlan plan = planner.plan(project);
            if (!plan.separationNeeded) {
                return new ClonePlan(ref, plan, CloneProjectFieldConfigurationsResponse.alreadySeparate(true, ref,
                        plan.schemeView, plan.layoutViews));
            }
            return new ClonePlan(ref, plan,
                    CloneProjectFieldConfigurationsResponse.success(true, ref, plan.schemeView, plan.layoutViews));
        } catch (FieldConfigurationCloneFailure e) {
            return new ClonePlan(ref, null, CloneProjectFieldConfigurationsResponse.failed(true, ref, e.getCode(),
                    e.getMessage(), e.getSuggestions()));
        } catch (RuntimeException e) {
            LOG.error("Failed to clone field configuration setup", e);
            return new ClonePlan(ref, null, CloneProjectFieldConfigurationsResponse.failed(true, ref, ErrorCode.JIRA_ERROR,
                    "Jira failed while reading the field configuration setup; nothing was changed. Details are in the Jira log.",
                    null));
        }
    }

    private CloneProjectFieldConfigurationsResponse write(ClonePlan plan) {
        try {
            FieldConfigurationCloneWriter.Written written = writer.write(plan.resolved);
            return CloneProjectFieldConfigurationsResponse.success(false, plan.project,
                    written.schemeView(plan.resolved.schemeView), written.layoutViews(plan.resolved.layoutViews));
        } catch (FieldConfigurationCloneFailure e) {
            return CloneProjectFieldConfigurationsResponse.failed(false, plan.project, e.getCode(),
                    e.getMessage(), e.getSuggestions());
        }
    }

    private String fingerprint(ClonePlan plan) {
        return codec.fingerprint(Arrays.asList(plan.preview, plan.sourceContent));
    }

    /** Scalar snapshot only; source objects remain local to the resolved plan, never in ActionPlanStore. */
    private static List<Object> sourceContent(FieldConfigurationClonePlan plan) {
        if (plan == null) return null;
        List<List<Object>> layouts = new ArrayList<>(plan.layouts.size());
        for (FieldLayout layout : plan.layouts.keySet()) {
            List<FieldLayoutItem> sourceItems = layout.getFieldLayoutItems();
            List<List<Object>> items = new ArrayList<>(sourceItems.size());
            for (FieldLayoutItem item : sourceItems) {
                items.add(Arrays.asList(item.getOrderableField().getId(), item.getRawFieldDescription(),
                        item.isHidden(), item.isRequired(), item.getRendererType()));
            }
            items.sort(Comparator.comparing(row -> (String) row.get(0), Comparator.nullsFirst(String::compareTo)));
            layouts.add(Arrays.asList(layout.getId(), layout.getDescription(), items));
        }
        layouts.sort(Comparator.comparing(row -> (Long) row.get(0), Comparator.nullsFirst(Long::compareTo)));
        List<List<Object>> mappings = new ArrayList<>(plan.mappings.size());
        for (FieldConfigurationClonePlan.Mapping mapping : plan.mappings) {
            mappings.add(Arrays.asList(mapping.issueTypeId, mapping.layout.getId()));
        }
        mappings.sort(Comparator.comparing(row -> (String) row.get(0), Comparator.nullsFirst(String::compareTo)));
        return Arrays.asList(plan.sourceSchemeId, plan.sourceDescription, layouts, mappings);
    }

    /** Request-local only: the fingerprint and writer use the same resolved source/mapping plan. */
    private static final class ClonePlan {
        private final ProjectRef project;
        private final FieldConfigurationClonePlan resolved;
        private final CloneProjectFieldConfigurationsResponse preview;
        private final List<Object> sourceContent;

        private ClonePlan(ProjectRef project, FieldConfigurationClonePlan resolved,
                          CloneProjectFieldConfigurationsResponse preview) {
            this.project = project;
            this.resolved = resolved;
            this.preview = preview;
            this.sourceContent = sourceContent(resolved);
        }
    }

    private static void requireMode(CloneProjectFieldConfigurationsRequest request) {
        if (request == null || request.isDryRun() == null) {
            throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        }
        if (request.isDryRun() && (trim(request.getProject()).isEmpty() || request.getPlanId() != null)) {
            throw new InvalidRequestException("Preview takes a nonblank project and no planId.");
        }
        if (!request.isDryRun() && (request.getProject() != null || trim(request.getPlanId()).isEmpty())) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
