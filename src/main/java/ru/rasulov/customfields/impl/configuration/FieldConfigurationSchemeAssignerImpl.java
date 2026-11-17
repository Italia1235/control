package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.customfields.api.FieldConfigurationSchemeAssigner;
import ru.rasulov.customfields.api.dto.configuration.AssignFieldConfigurationSchemeRequest;
import ru.rasulov.customfields.api.dto.configuration.AssignFieldConfigurationSchemeResponse;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ConfigurationRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.FieldChange;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.IssueTypeChange;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ProjectImpact;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.Usage;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.Warning;
import ru.rasulov.customfields.api.dto.configuration.SetFieldConfigurationMappingsRequest;
import ru.rasulov.customfields.api.dto.configuration.SetFieldConfigurationMappingsResponse;
import ru.rasulov.customfields.api.dto.configuration.SetFieldConfigurationMappingsResponse.RowChange;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Assigns a field configuration scheme to a project and changes rows of an existing scheme. Neither touches issue
 * data; both change the required/hidden rules existing issues are edited under, so every affected project, issue type
 * and field is previewed. Jira's implicit default scheme cannot be edited.
 */
@ExportAsService(FieldConfigurationSchemeAssigner.class)
@Named
public class FieldConfigurationSchemeAssignerImpl implements FieldConfigurationSchemeAssigner {
    static final String ASSIGN = "assign_field_configuration_scheme";
    static final String SET_MAPPINGS = "set_field_configuration_mappings";
    static final int MAX_FIELDS_SHOWN = 50;
    static final int MAX_PROJECTS_SHOWN = 50;
    private static final Logger LOG = LoggerFactory.getLogger(FieldConfigurationSchemeAssignerImpl.class);

    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final ProjectResolver projects;
    private final FieldConfigurationCatalog catalog;
    private final SchemeRows schemeRows;
    private final FieldLayoutManager manager;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;

    @Inject
    public FieldConfigurationSchemeAssignerImpl(AdministratorGuard guard, NamedLock lock, ProjectResolver projects,
                                                FieldConfigurationCatalog catalog, SchemeRows schemeRows,
                                                @ComponentImport FieldLayoutManager manager, PreviewPlanExecutor executor,
                                                ActionPlanCodec codec, ChangeJournal journal) {
        this.guard = guard;
        this.lock = lock;
        this.projects = projects;
        this.catalog = catalog;
        this.schemeRows = schemeRows;
        this.manager = manager;
        this.executor = executor;
        this.codec = codec;
        this.journal = journal;
    }

    // --- assign_field_configuration_scheme ---------------------------------------------------------------------------

    @Override
    public AssignFieldConfigurationSchemeResponse assign(AssignFieldConfigurationSchemeRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        FieldConfigurationCreatorImpl.requireMode(request == null ? null : request.isDryRun(),
                request == null ? null : request.getPlanId(),
                request != null && (request.getProject() != null || request.getScheme() != null));
        if (request.isDryRun()) {
            AssignPlan current = prepare(request);
            if (current.preview.getStatus() != AssignFieldConfigurationSchemeResponse.Status.WOULD_ASSIGN) {
                return current.preview;
            }
            return executor.issue(ASSIGN, user.getKey(), request, current.fingerprint, current.preview::withPlan);
        }
        return journal.execute(ASSIGN, user.getKey(), () -> lock.run(FieldConfigurationLocks.EDIT,
                "field configurations", () -> applyAssign(request.getPlanId(), user)));
    }

    private AssignFieldConfigurationSchemeResponse applyAssign(String planId, ApplicationUser user) {
        return executor.apply(ASSIGN, user.getKey(), planId, AssignFieldConfigurationSchemeRequest.class,
                AssignFieldConfigurationSchemeResponse::stale, this::prepare, current -> true,
                current -> current.fingerprint, this::writeAssign);
    }

    private AssignFieldConfigurationSchemeResponse writeAssign(AssignPlan current) {
        journal.checkpoint();
        if (current.target == null) manager.removeSchemeAssociation(current.project, current.assigned.getId());
        else manager.addSchemeAssociation(current.project, current.target.getId());
        manager.refresh();
        FieldConfigurationScheme now = manager.getFieldConfigurationScheme(current.project);
        Long expected = current.target == null ? null : current.target.getId();
        if (!Objects.equals(expected, now == null ? null : now.getId())) {
            throw new IllegalStateException("Jira did not persist the requested field configuration scheme.");
        }
        journal.checkpoint();
        AssignFieldConfigurationSchemeResponse preview = current.preview;
        return AssignFieldConfigurationSchemeResponse.success(false, true, preview.getProject(),
                preview.getSchemeBefore(), preview.getSchemeAfter(), preview.getSchemeAfterOtherProjects(),
                preview.getChanges(), preview.getWarnings());
    }

    private AssignPlan prepare(AssignFieldConfigurationSchemeRequest request) {
        ProjectRef ref = null;
        try {
            manager.refresh();
            Project project = projects.resolve(request.getProject());
            ref = new ProjectRef(project.getKey(), project.getName());
            if (request.getScheme() == null || request.getScheme().trim().isEmpty()) {
                throw new FieldConfigurationCatalog.Failure(ErrorCode.VALIDATION_ERROR,
                        "'scheme' is required: a field configuration scheme id, or DEFAULT.");
            }
            FieldLayoutScheme target = catalog.scheme(request.getScheme());
            FieldConfigurationScheme assigned = manager.getFieldConfigurationScheme(project);
            Long assignedId = assigned == null ? null : assigned.getId();
            Long targetId = target == null ? null : target.getId();
            List<FieldLayout> touched = new ArrayList<FieldLayout>();
            if (Objects.equals(assignedId, targetId)) {
                return new AssignPlan(project, assigned, target, AssignFieldConfigurationSchemeResponse.success(true,
                        false, ref, FieldConfigurationCatalog.ref(assigned), FieldConfigurationCatalog.ref(target),
                        null, null, null), null);
            }
            List<IssueTypeChange> changes = changes(project, typeId -> catalog.effective(target, typeId), touched);
            List<Warning> warnings = fieldWarnings(Arrays.asList(changes));
            Usage afterOthers = target == null ? null : catalog.otherProjects(target, project);
            if (afterOthers != null && afterOthers.getTotal() > 0) {
                warnings.add(new Warning("SHARED_SCHEME", "The scheme is also used by " + afterOthers.getTotal()
                        + " other project(s); later changes to the scheme affect them too."));
            }
            if (assigned != null && catalog.otherProjects(assigned, project).getTotal() == 0) {
                warnings.add(new Warning("SCHEME_ORPHANED", "Scheme '" + assigned.getName() + "' will no longer be "
                        + "used by any project. It is not deleted."));
            }
            AssignFieldConfigurationSchemeResponse preview = AssignFieldConfigurationSchemeResponse.success(true, true,
                    ref, FieldConfigurationCatalog.ref(assigned), FieldConfigurationCatalog.ref(target), afterOthers,
                    changes, warnings);
            String fingerprint = codec.fingerprint(Arrays.asList(preview, assignedId,
                    target == null ? null : keyed(catalog.rows(target)), catalog.snapshots(touched)));
            return new AssignPlan(project, assigned, target, preview, fingerprint);
        } catch (LookupFailure e) {
            return new AssignPlan(null, null, null, AssignFieldConfigurationSchemeResponse.failed(true, ref,
                    e.getKind() == LookupFailure.Kind.UNKNOWN_PROJECT ? ErrorCode.UNKNOWN_PROJECT
                            : ErrorCode.VALIDATION_ERROR, e.getMessage(), e.getSuggestions()), null);
        } catch (FieldConfigurationCatalog.Failure e) {
            return new AssignPlan(null, null, null, AssignFieldConfigurationSchemeResponse.failed(true, ref,
                    e.getCode(), e.getMessage(), e.suggestions()), null);
        } catch (RuntimeException e) {
            LOG.error("Failed to plan a field configuration scheme assignment", e);
            return new AssignPlan(null, null, null, AssignFieldConfigurationSchemeResponse.failed(true, ref,
                    ErrorCode.JIRA_ERROR, "Jira failed while reading field configurations; nothing was changed. "
                            + "Details are in the Jira log.", null), null);
        }
    }

    /** Request-local resolved assignment. */
    private static final class AssignPlan {
        private final Project project;
        private final FieldConfigurationScheme assigned;
        private final FieldLayoutScheme target;
        private final AssignFieldConfigurationSchemeResponse preview;
        private final String fingerprint;

        private AssignPlan(Project project, FieldConfigurationScheme assigned, FieldLayoutScheme target,
                           AssignFieldConfigurationSchemeResponse preview, String fingerprint) {
            this.project = project; this.assigned = assigned; this.target = target; this.preview = preview;
            this.fingerprint = fingerprint;
        }
    }

    // --- set_field_configuration_mappings ----------------------------------------------------------------------------

    @Override
    public SetFieldConfigurationMappingsResponse setMappings(SetFieldConfigurationMappingsRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        FieldConfigurationCreatorImpl.requireMode(request == null ? null : request.isDryRun(),
                request == null ? null : request.getPlanId(),
                request != null && (request.getScheme() != null || !request.getMappings().isEmpty()));
        if (request.isDryRun()) {
            MappingPlan current = prepare(request);
            if (current.preview.getStatus() != SetFieldConfigurationMappingsResponse.Status.WOULD_CHANGE) {
                return current.preview;
            }
            return executor.issue(SET_MAPPINGS, user.getKey(), request, current.fingerprint, current.preview::withPlan);
        }
        return journal.execute(SET_MAPPINGS, user.getKey(), () -> lock.run(FieldConfigurationLocks.EDIT,
                "field configurations", () -> applyMappings(request.getPlanId(), user)));
    }

    private SetFieldConfigurationMappingsResponse applyMappings(String planId, ApplicationUser user) {
        return executor.apply(SET_MAPPINGS, user.getKey(), planId, SetFieldConfigurationMappingsRequest.class,
                SetFieldConfigurationMappingsResponse::stale, this::prepare, current -> true,
                current -> current.fingerprint, this::writeMappings);
    }

    private SetFieldConfigurationMappingsResponse writeMappings(MappingPlan current) {
        Long schemeId = current.scheme.getId();
        for (Map.Entry<String, FieldLayout> row : current.requested.rows.entrySet()) {
            String issueTypeId = row.getKey();
            if (!current.changedRows.contains(issueTypeId)) continue;
            journal.checkpoint();
            manager.refresh();
            FieldLayoutScheme scheme = manager.getMutableFieldLayoutScheme(schemeId);
            FieldLayoutSchemeEntity existing = scheme.getEntity(issueTypeId);
            if (row.getValue() == null) {
                if (existing != null) manager.removeFieldLayoutSchemeEntity(existing);
            } else if (existing == null) {
                manager.createFieldLayoutSchemeEntity(scheme, issueTypeId, current.requested.layoutId(issueTypeId));
            } else {
                existing.setFieldLayoutId(current.requested.layoutId(issueTypeId));
                manager.updateFieldLayoutSchemeEntity(existing);
            }
            journal.checkpoint();
        }
        manager.refresh();
        Map<String, Long> actual = catalog.rows(manager.getMutableFieldLayoutScheme(schemeId));
        for (String issueTypeId : current.requested.rows.keySet()) {
            if (actual.containsKey(issueTypeId) != current.after.containsKey(issueTypeId)
                    || !Objects.equals(actual.get(issueTypeId), current.after.get(issueTypeId))) {
                throw new IllegalStateException("Jira did not persist the requested scheme rows.");
            }
        }
        SetFieldConfigurationMappingsResponse preview = current.preview;
        return SetFieldConfigurationMappingsResponse.success(false, true, preview.getScheme(), preview.getRows(),
                preview.getProjectsTotal(), preview.getAffected(), preview.getWarnings());
    }

    private MappingPlan prepare(SetFieldConfigurationMappingsRequest request) {
        try {
            manager.refresh();
            String text = request.getScheme() == null ? "" : request.getScheme().trim();
            if (FieldConfigurationCatalog.DEFAULT.equals(text)) {
                throw new FieldConfigurationCatalog.Failure(ErrorCode.DEFAULT_SCHEME, "Jira's default field "
                        + "configuration scheme cannot be edited. Give the project its own scheme first: "
                        + "clone_project_field_configurations, or create_field_configuration_scheme and "
                        + "assign_field_configuration_scheme.");
            }
            if (text.isEmpty()) throw new FieldConfigurationCatalog.Failure(ErrorCode.VALIDATION_ERROR,
                    "'scheme' is required: a field configuration scheme id.");
            FieldLayoutScheme scheme = catalog.scheme(text);
            SchemeRows.Resolved requested = schemeRows.resolve(request.getMappings(), true);
            Map<String, Long> before = catalog.rows(scheme);
            Map<String, Long> after = new LinkedHashMap<String, Long>(before);
            List<RowChange> rows = new ArrayList<RowChange>();
            List<String> changedRows = new ArrayList<String>();
            for (Map.Entry<String, FieldLayout> row : requested.rows.entrySet()) {
                String issueTypeId = row.getKey();
                if (row.getValue() == null) after.remove(issueTypeId);
                else after.put(issueTypeId, requested.layoutId(issueTypeId));
                boolean changed = before.containsKey(issueTypeId) != after.containsKey(issueTypeId)
                        || !Objects.equals(before.get(issueTypeId), after.get(issueTypeId));
                if (changed) changedRows.add(issueTypeId);
                rows.add(new RowChange(issueTypeId, requested.names.get(issueTypeId), rowRef(before, issueTypeId),
                        rowRef(after, issueTypeId), changed));
            }
            List<Project> users = catalog.projectsUsing(scheme);
            List<FieldLayout> touched = new ArrayList<FieldLayout>();
            List<List<IssueTypeChange>> all = new ArrayList<List<IssueTypeChange>>();
            List<ProjectImpact> affected = new ArrayList<ProjectImpact>();
            if (!changedRows.isEmpty()) {
                for (Project project : users) {
                    List<IssueTypeChange> changes = changes(project, typeId -> effective(after, typeId), touched);
                    if (changes.isEmpty()) continue;
                    all.add(changes);
                    if (affected.size() < MAX_PROJECTS_SHOWN) {
                        affected.add(new ProjectImpact(new ProjectRef(project.getKey(), project.getName()), changes));
                    }
                }
            }
            List<Warning> warnings = fieldWarnings(all);
            if (!changedRows.isEmpty() && users.size() > 1) {
                warnings.add(new Warning("SHARED_SCHEME", "The scheme is used by " + users.size() + " projects; "
                        + all.size() + " of them get a different field configuration for some issue types."));
            }
            SetFieldConfigurationMappingsResponse preview = SetFieldConfigurationMappingsResponse.success(true,
                    !changedRows.isEmpty(), FieldConfigurationCatalog.ref(scheme), rows, users.size(), affected,
                    warnings);
            String fingerprint = codec.fingerprint(Arrays.asList(preview, keyed(before), catalog.snapshots(touched)));
            return new MappingPlan(scheme, requested, after, changedRows, preview, fingerprint);
        } catch (FieldConfigurationCatalog.Failure e) {
            return new MappingPlan(null, null, null, null, SetFieldConfigurationMappingsResponse.failed(true,
                    e.getCode(), e.getMessage(), e.suggestions()), null);
        } catch (RuntimeException e) {
            LOG.error("Failed to plan field configuration scheme rows", e);
            return new MappingPlan(null, null, null, null, SetFieldConfigurationMappingsResponse.failed(true,
                    ErrorCode.JIRA_ERROR, "Jira failed while reading field configurations; nothing was changed. "
                            + "Details are in the Jira log.", null), null);
        }
    }

    /** Request-local resolved row change. */
    private static final class MappingPlan {
        private final FieldLayoutScheme scheme;
        private final SchemeRows.Resolved requested;
        private final Map<String, Long> after;
        private final List<String> changedRows;
        private final SetFieldConfigurationMappingsResponse preview;
        private final String fingerprint;

        private MappingPlan(FieldLayoutScheme scheme, SchemeRows.Resolved requested, Map<String, Long> after,
                            List<String> changedRows, SetFieldConfigurationMappingsResponse preview,
                            String fingerprint) {
            this.scheme = scheme; this.requested = requested; this.after = after; this.changedRows = changedRows;
            this.preview = preview; this.fingerprint = fingerprint;
        }
    }

    // --- shared ------------------------------------------------------------------------------------------------------

    /** Issue types of the project whose effective configuration changes, with their field differences. */
    private List<IssueTypeChange> changes(Project project, Function<String, FieldLayout> after,
                                          List<FieldLayout> touched) {
        List<IssueType> types = new ArrayList<IssueType>(project.getIssueTypes());
        types.sort(Comparator.comparing(IssueType::getName).thenComparing(IssueType::getId));
        List<IssueTypeChange> changes = new ArrayList<IssueTypeChange>();
        for (IssueType type : types) {
            FieldLayout before = manager.getFieldLayout(project, type.getId());
            FieldLayout next = after.apply(type.getId());
            if (FieldConfigurationCatalog.sameLayout(before, next)) continue;
            touched.add(before);
            touched.add(next);
            List<FieldChange> fields = catalog.diff(before, next, field -> applies(field, project, type));
            changes.add(new IssueTypeChange(type.getId(), type.getName(), FieldConfigurationCatalog.ref(before),
                    FieldConfigurationCatalog.ref(next), fields.subList(0, Math.min(fields.size(), MAX_FIELDS_SHOWN)),
                    fields.size()));
        }
        return changes;
    }

    private FieldLayout effective(Map<String, Long> rows, String issueTypeId) {
        Long id = rows.containsKey(issueTypeId) ? rows.get(issueTypeId) : rows.get(null);
        FieldLayout layout = catalog.configuration(id);
        if (layout == null) throw new IllegalStateException("Scheme row points at missing field configuration " + id);
        return layout;
    }

    private ConfigurationRef rowRef(Map<String, Long> rows, String issueTypeId) {
        if (!rows.containsKey(issueTypeId)) return null;
        FieldLayout layout = catalog.configuration(rows.get(issueTypeId));
        return layout == null ? new ConfigurationRef(rows.get(issueTypeId), null, false)
                : FieldConfigurationCatalog.ref(layout);
    }

    private static boolean applies(OrderableField field, Project project, IssueType type) {
        return !(field instanceof CustomField) || ((CustomField) field).isInScope(project.getId(), type.getId());
    }

    /** Scheme rows with the DEFAULT row under a text key: JSON maps cannot hold a null key. */
    private static Map<String, Long> keyed(Map<String, Long> rows) {
        Map<String, Long> keyed = new java.util.TreeMap<String, Long>();
        for (Map.Entry<String, Long> row : rows.entrySet()) {
            keyed.put(row.getKey() == null ? FieldConfigurationCatalog.DEFAULT : row.getKey(), row.getValue());
        }
        return keyed;
    }

    private static List<Warning> fieldWarnings(List<List<IssueTypeChange>> projects) {
        int required = 0, hidden = 0;
        boolean truncated = false;
        for (List<IssueTypeChange> changes : projects) {
            for (IssueTypeChange change : changes) {
                truncated |= change.isTruncated();
                for (FieldChange field : change.getFields()) {
                    if (field.isRequiredAfter() && !field.isRequiredBefore()) required++;
                    if (field.isHiddenAfter() && !field.isHiddenBefore()) hidden++;
                }
            }
        }
        String atLeast = truncated ? "At least " : "";
        List<Warning> warnings = new ArrayList<Warning>();
        if (required > 0) warnings.add(new Warning("NEWLY_REQUIRED", atLeast + required + " field/issue-type pair(s) "
                + "become required: creating issues, and editing existing issues that have no value, will require a value."));
        if (hidden > 0) warnings.add(new Warning("NEWLY_HIDDEN", atLeast + hidden + " field/issue-type pair(s) become "
                + "hidden: values stay in issues but are not shown or editable."));
        return warnings;
    }
}
