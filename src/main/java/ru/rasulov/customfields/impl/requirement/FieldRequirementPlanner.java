package ru.rasulov.customfields.impl.requirement;

import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.IssueFieldConstants;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredRequest;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse.ErrorCode;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse.FieldView;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse.SchemeView;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse.FieldError;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import static ru.rasulov.customfields.impl.undo.FieldUndoJson.map;
import ru.rasulov.customfields.impl.configuration.ConfigurableFieldResolver;
import ru.rasulov.customfields.impl.configuration.FieldConfigurationClonePlanner;
import ru.rasulov.customfields.impl.requirement.RequirementPlan.AffectedProjects;
import ru.rasulov.customfields.impl.requirement.RequirementPlan.LayoutChange;
import ru.rasulov.customfields.impl.requirement.RequirementPlan.Plan;
import ru.rasulov.customfields.impl.requirement.RequirementPlan.Split;
import ru.rasulov.customfields.impl.requirement.RequirementPlan.Target;
import ru.rasulov.customfields.impl.undo.FieldLayoutState;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import ru.rasulov.common.impl.screen.EffectiveScreens;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import java.util.TreeMap;
import java.util.Collection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only half of {@code set_field_required}: resolves project, issue types and field, decides which layouts change or
 * must be split into a private copy, and names the copies. Never writes.
 */
final class FieldRequirementPlanner {
    private static final Logger LOG = LoggerFactory.getLogger(FieldRequirementPlanner.class);

    private final ProjectResolver projectResolver;
    private final IssueTypeResolver issueTypeResolver;
    private final ConfigurableFieldResolver fields;
    private final FieldLayoutManager fieldLayoutManager;
    private final IssueTypeScreenSchemeManager screenSchemes;

    FieldRequirementPlanner(ProjectResolver projectResolver, IssueTypeResolver issueTypeResolver,
                            ConfigurableFieldResolver fields, FieldLayoutManager fieldLayoutManager,
                            IssueTypeScreenSchemeManager screenSchemes) {
        this.projectResolver = projectResolver;
        this.issueTypeResolver = issueTypeResolver;
        this.fields = fields;
        this.fieldLayoutManager = fieldLayoutManager;
        this.screenSchemes = screenSchemes;
    }

    RequirementPlan prepare(SetFieldRequiredRequest request) {
        ProjectRef projectRef = null;
        try {
            Project project = resolveProject(request.getProject());
            projectRef = new ProjectRef(project.getKey(), project.getName());
            List<SetFieldRequiredRequest.Row> rows = rows(request);
            FieldErrors sink = new FieldErrors();
            List<boolean[]> flags = new ArrayList<boolean[]>();
            List<List<OrderableField>> rowFields = new ArrayList<List<OrderableField>>();
            for (int i = 0; i < rows.size(); i++) {
                SetFieldRequiredRequest.Row row = rows.get(i);
                if (row.getRequired() == null) {
                    throw invalid("Row " + (i + 1) + ": 'required' must be explicitly true or false.");
                }
                boolean rowRequired = row.getRequired();
                flags.add(new boolean[] {rowRequired});
                rowFields.add(resolveFields(row.getFields(), rowRequired, sink));
            }
            sink.throwIfAny();
            Map<String, OrderableField> fieldsById = new LinkedHashMap<String, OrderableField>();
            Map<String, IssueType> typesById = new LinkedHashMap<String, IssueType>();
            Map<String, Map<String, Boolean>> desired = new LinkedHashMap<String, Map<String, Boolean>>();
            Map<String, Integer> origin = new java.util.HashMap<String, Integer>();
            List<FieldError> conflicts = new ArrayList<FieldError>();
            for (int i = 0; i < rows.size(); i++) {
                SetFieldRequiredRequest.Row row = rows.get(i);
                boolean rowRequired = flags.get(i)[0];
                for (IssueType type : resolveIssueTypes(project, row.isAllIssueTypes(), row.getIssueTypes())) {
                    typesById.putIfAbsent(type.getId(), type);
                    Map<String, Boolean> wanted = desired.computeIfAbsent(type.getId(),
                            key -> new LinkedHashMap<String, Boolean>());
                    for (OrderableField field : rowFields.get(i)) {
                        fieldsById.putIfAbsent(field.getId(), field);
                        Integer previous = origin.putIfAbsent(type.getId() + "|" + field.getId(), i);
                        if (previous == null) { wanted.put(field.getId(), rowRequired); continue; }
                        boolean same = wanted.get(field.getId()) == rowRequired;
                        conflicts.add(new FieldError(field.getId(), ErrorCode.ROW_CONFLICT, "Field '" + field.getName()
                                + "' (" + field.getId() + ") is set in rows " + (previous + 1) + " and " + (i + 1)
                                + " for issue type '" + type.getName() + "' (" + type.getId() + "): "
                                + (same ? "the same flag is repeated" : "the required flags contradict each other")
                                + ". Rows must not overlap.", null));
                    }
                }
            }
            if (!conflicts.isEmpty()) {
                List<String> messages = new ArrayList<String>();
                for (FieldError conflict : conflicts) messages.add(conflict.getMessage());
                throw new FieldSelectionFailure(ErrorCode.ROW_CONFLICT, String.join(" ", messages), null, null, conflicts);
            }
            List<OrderableField> selectedFields = new ArrayList<OrderableField>(fieldsById.values());
            Plan plan = plan(project, selectedFields, new ArrayList<IssueType>(typesById.values()), desired);
            SetFieldRequiredResponse preview = SetFieldRequiredResponse.success(true, plan.hasChanges(), projectRef,
                    plan.fieldViews(), plan.scheme, plan.views(true), plan.note());
            return new RequirementPlan(projectRef, selectedFields, plan, preview);
        } catch (FieldSelectionFailure e) {
            return new RequirementPlan(projectRef, null, null,
                    SetFieldRequiredResponse.failed(true, projectRef, e.code, e.getMessage(), e.suggestions, e.candidates)
                            .withFieldErrors(e.fieldErrors));
        } catch (RuntimeException e) {
            LOG.error("Failed to change field requirement", e);
            return new RequirementPlan(projectRef, null, null, SetFieldRequiredResponse.failed(true, projectRef,
                    ErrorCode.JIRA_ERROR,
                    "Jira failed while reading the field configurations or screens; nothing was changed. Details are in the Jira log.",
                    null, null));
        }
    }

    static FieldView view(OrderableField field) {
        if (!(field instanceof CustomField)) return new FieldView(field.getId(), field.getName(), null, "System field");
        CustomFieldType<?, ?> type = ((CustomField) field).getCustomFieldType();
        return new FieldView(field.getId(), field.getName(), type == null ? null : type.getKey(),
                type == null ? null : type.getName());
    }

    /**
     * Name of a system field that Jira does not let a Field Configuration make required (Attachment, Assignee), or
     * null. A custom field merely named "Attachment" or "Assignee" is never matched.
     */
    private static String unrequirableSystemField(OrderableField field) {
        if (field instanceof CustomField) return null;
        if (IssueFieldConstants.ATTACHMENT.equals(field.getId())) return "Attachment";
        if (IssueFieldConstants.ASSIGNEE.equals(field.getId())) return "Assignee";
        return null;
    }

    private Project resolveProject(String text) {
        try {
            return projectResolver.resolve(text);
        } catch (LookupFailure e) {
            ErrorCode code = e.getKind() == LookupFailure.Kind.UNKNOWN_PROJECT
                    ? ErrorCode.UNKNOWN_PROJECT : ErrorCode.VALIDATION_ERROR;
            throw new FieldSelectionFailure(code, e.getMessage(), e.getSuggestions(), null);
        }
    }

    private OrderableField resolveField(String nameOrId) {
        try {
            return fields.resolve(nameOrId);
        } catch (ConfigurableFieldResolver.Failure e) {
            List<FieldView> candidates = new ArrayList<FieldView>();
            for (OrderableField candidate : e.getCandidates()) candidates.add(view(candidate));
            throw new FieldSelectionFailure(ErrorCode.valueOf(e.getCode().name()), e.getMessage(), null, candidates);
        }
    }

    /** Collects field errors of every row, so one refusal lists them all. */
    private static final class FieldErrors {
        private final List<FieldError> errors = new ArrayList<FieldError>();
        private final List<FieldView> candidates = new ArrayList<FieldView>();
        private final List<String> messages = new ArrayList<String>();

        void add(String selector, FieldSelectionFailure failure) {
            errors.add(new FieldError(selector, failure.code, failure.getMessage(), failure.candidates));
            if (failure.candidates != null) candidates.addAll(failure.candidates);
            messages.add("'" + selector + "': " + failure.getMessage());
        }

        void throwIfAny() {
            if (errors.isEmpty()) return;
            throw new FieldSelectionFailure(errors.get(0).getErrorCode(), String.join(" ", messages), null,
                    candidates, errors);
        }
    }

    private static List<SetFieldRequiredRequest.Row> rows(SetFieldRequiredRequest request) {
        if (request.getRows().isEmpty() || request.getRows().size() > Limits.MAX_REQUIREMENT_ROWS) {
            throw invalid("'rows' must contain between 1 and " + Limits.MAX_REQUIREMENT_ROWS + " rows.");
        }
        for (SetFieldRequiredRequest.Row row : request.getRows()) {
            if (row == null) throw invalid("'rows' must not contain empty elements.");
        }
        return request.getRows();
    }

    private List<OrderableField> resolveFields(List<String> requested, boolean required, FieldErrors sink) {
        if (requested.isEmpty() || requested.size() > Limits.MAX_FIELDS_PER_REQUIREMENT_CALL) {
            throw invalid("'fields' must contain between 1 and " + Limits.MAX_FIELDS_PER_REQUIREMENT_CALL + " fields.");
        }
        List<OrderableField> resolved = new ArrayList<OrderableField>();
        Set<String> ids = new HashSet<String>();
        for (String selector : requested) {
            try {
                OrderableField field = resolveField(selector);
                if (!ids.add(field.getId())) {
                    throw invalid("Field '" + selector + "' resolves to " + field.getId() + ", which is listed more than once.");
                }
                String unrequirable = required ? unrequirableSystemField(field) : null;
                if (unrequirable != null) {
                    throw new FieldSelectionFailure(ErrorCode.FIELD_REQUIRED_UNSUPPORTED, "Поле " + unrequirable
                            + " нельзя сделать обязательным через Field Configuration. Это ограничение Jira, "
                            + "подтверждённое владельцем Jira; оно не зависит от того, есть ли поле на CREATE-экране. "
                            + "Поле не становится необязательным автоматически, требование остаётся невыполненным; "
                            + "ничего не изменено.", null, null);
                }
                if (!required && fields.isMandatory(field)) {
                    throw new FieldSelectionFailure(ErrorCode.LOCKED_FIELD, "Jira requires field '" + field.getName()
                            + "' (" + field.getId() + ") everywhere; it cannot become optional.", null, null);
                }
                resolved.add(field);
            } catch (FieldSelectionFailure failure) {
                sink.add(selector, failure);
            }
        }
        return resolved;
    }


    private List<IssueType> resolveIssueTypes(Project project, Boolean allIssueTypes, List<String> requestedTypes) {
        boolean all = Boolean.TRUE.equals(allIssueTypes);
        if (allIssueTypes != null && !all) {
            throw invalid("'allIssueTypes', when supplied, must be true.");
        }
        if (all == !requestedTypes.isEmpty()) {
            throw invalid("Choose exactly one scope: allIssueTypes=true, or a non-empty issueTypes list.");
        }
        if (requestedTypes.size() > Limits.MAX_ISSUE_TYPES_PER_CONTEXT) {
            throw invalid("'issueTypes' has " + requestedTypes.size() + " elements; the maximum is "
                    + Limits.MAX_ISSUE_TYPES_PER_CONTEXT + ".");
        }
        List<IssueType> projectTypes = new ArrayList<IssueType>(project.getIssueTypes());
        projectTypes.sort(Comparator.comparing(IssueType::getName).thenComparing(IssueType::getId));
        if (all) return projectTypes;

        Map<String, IssueType> available = new LinkedHashMap<String, IssueType>();
        for (IssueType type : projectTypes) available.put(type.getId(), type);
        List<IssueType> selected = new ArrayList<IssueType>();
        Set<String> seen = new HashSet<String>();
        for (String requested : requestedTypes) {
            IssueType type;
            try {
                type = issueTypeResolver.resolve(requested);
            } catch (LookupFailure e) {
                ErrorCode code = e.getKind() == LookupFailure.Kind.UNKNOWN_ISSUE_TYPE
                        ? ErrorCode.UNKNOWN_ISSUE_TYPE : ErrorCode.VALIDATION_ERROR;
                throw new FieldSelectionFailure(code, e.getMessage(), e.getSuggestions(), null);
            }
            if (!available.containsKey(type.getId())) {
                throw new FieldSelectionFailure(ErrorCode.UNKNOWN_ISSUE_TYPE,
                        "Issue type '" + type.getName() + "' (" + type.getId() + ") is not available in project "
                                + project.getKey() + ".", null, null);
            }
            if (!seen.add(type.getId())) throw invalid("Issue type '" + requested + "' is listed more than once.");
            selected.add(type);
        }
        return selected;
    }

    private static final class Outcome {
        final IssueType type;
        final FieldLayout layout;
        final Map<String, Boolean> wanted;
        final List<OrderableField> fields = new ArrayList<OrderableField>();
        final List<RequirementPlan.Wanted> changing = new ArrayList<RequirementPlan.Wanted>();
        final List<String> changedIds = new ArrayList<String>();

        Outcome(IssueType type, FieldLayout layout, Map<String, Boolean> wanted) {
            this.type = type; this.layout = layout; this.wanted = wanted;
        }
    }

    private static String signature(List<RequirementPlan.Wanted> changing) {
        Map<String, Boolean> sorted = new TreeMap<String, Boolean>();
        for (RequirementPlan.Wanted wanted : changing) sorted.put(wanted.field.getId(), wanted.required);
        return sorted.toString();
    }

    private Plan plan(Project project, List<OrderableField> selectedFields, List<IssueType> issueTypes,
                      Map<String, Map<String, Boolean>> desired) {
        fieldLayoutManager.refresh();
        if (issueTypes.isEmpty()) throw invalid("The project has no issue types to change.");
        FieldConfigurationScheme scheme = fieldLayoutManager.getFieldConfigurationScheme(project);
        SchemeView schemeView = scheme == null ? new SchemeView(null, null, true)
                : new SchemeView(scheme.getId(), scheme.getName(), false);
        Map<String, OrderableField> byId = new java.util.HashMap<String, OrderableField>();
        for (OrderableField field : selectedFields) byId.put(field.getId(), field);
        Set<String> selectedIds = new HashSet<String>();
        for (IssueType selected : issueTypes) selectedIds.add(selected.getId());
        Map<String, String> layoutStates = new TreeMap<String, String>();
        Map<String, Object> createStates = new TreeMap<String, Object>();
        List<FieldError> problems = new ArrayList<FieldError>();
        List<Outcome> outcomes = new ArrayList<Outcome>();
        IssueTypeScreenScheme top = null;
        for (IssueType issueType : issueTypes) {
            FieldLayout layout = fieldLayoutManager.getFieldLayout(project, issueType.getId());
            if (layout == null) throw new IllegalStateException("No effective field configuration for " + issueType.getId());
            layoutStates.put(layoutKey(layout), FieldLayoutState.canonicalSnapshot(layout.getName(), layout));
            Outcome outcome = new Outcome(issueType, layout, desired.get(issueType.getId()));
            outcomes.add(outcome);
            FieldScreen create = null;
            for (Map.Entry<String, Boolean> entry : outcome.wanted.entrySet()) {
                OrderableField field = byId.get(entry.getKey());
                boolean required = entry.getValue();
                outcome.fields.add(field);
                String who = "Field '" + field.getName() + "' (" + field.getId() + ")";
                String where = "issue type '" + issueType.getName() + "' (" + issueType.getId() + ")";
                if (!isApplicable(project, issueType, field)) {
                    problems.add(new FieldError(field.getId(), ErrorCode.FIELD_NOT_APPLICABLE, "Custom field '"
                            + field.getName() + "' (" + field.getId() + ") is not in the context of project "
                            + project.getKey() + " and issue type '" + issueType.getName() + "'.", null));
                    continue;
                }
                FieldLayoutItem item = layout.getFieldLayoutItem(field.getId());
                if (item == null) {
                    problems.add(new FieldError(field.getId(), ErrorCode.FIELD_NOT_APPLICABLE, who
                            + " is not configurable in field configuration '" + layout.getName() + "'.", null));
                    continue;
                }
                if (required) {
                    // The CREATE screen is what a user fills when creating an issue; EDIT, VIEW or an unassigned
                    // screen does not make a required field fillable. Unreadable screens throw: never a pass.
                    if (create == null) {
                        if (top == null) top = topScheme(project);
                        create = EffectiveScreens.effectiveScreen(top.getEffectiveFieldScreenScheme(issueType),
                                "CREATE");
                    }
                    String screen = "CREATE screen '" + create.getName() + "' (" + create.getId() + ")";
                    boolean present = create.containsField(field.getId());
                    if (item.isHidden()) {
                        problems.add(new FieldError(field.getId(), ErrorCode.FIELD_HIDDEN, who + " is hidden in field "
                                + "configuration '" + layout.getName() + "', so it cannot be filled when creating " + where
                                + "; it cannot be required. " + screen + (present ? " contains it." : " does not contain it."),
                                null));
                    }
                    if (!present) {
                        problems.add(new FieldError(field.getId(), ErrorCode.FIELD_NOT_ON_CREATE_SCREEN, who
                                + " is not on the effective " + screen + " of " + where + ", so users could not fill it "
                                + "when creating an issue. Add it to that screen (edit_screen_layout) or assign a CREATE "
                                + "screen that has it (set_project_operation_screens), then set it required.", null));
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> state = (Map<String, Object>) createStates.computeIfAbsent(issueType.getId(),
                            key -> new TreeMap<String, Object>());
                    state.put("screen", create.getId());
                    state.put(field.getId(), present);
                }
                if (item.isRequired() != required) {
                    outcome.changing.add(new RequirementPlan.Wanted(field, required));
                    outcome.changedIds.add(field.getId());
                }
            }
        }
        if (!problems.isEmpty()) {
            List<String> messages = new ArrayList<String>();
            for (FieldError problem : problems) messages.add(problem.getMessage());
            throw new FieldSelectionFailure(problems.get(0).getErrorCode(), String.join(" ", messages), null, null,
                    problems);
        }
        Map<String, List<Outcome>> byLayout = new LinkedHashMap<String, List<Outcome>>();
        for (Outcome outcome : outcomes) {
            byLayout.computeIfAbsent(layoutKey(outcome.layout), key -> new ArrayList<Outcome>()).add(outcome);
        }
        List<LayoutChange> changes = new ArrayList<LayoutChange>();
        List<Split> splits = new ArrayList<Split>();
        Map<Outcome, Split> splitOf = new java.util.IdentityHashMap<Outcome, Split>();
        for (List<Outcome> group : byLayout.values()) {
            FieldLayout layout = group.get(0).layout;
            Map<String, List<Outcome>> bySignature = new LinkedHashMap<String, List<Outcome>>();
            List<IssueTypeRef> stayers = new ArrayList<IssueTypeRef>();
            for (Outcome outcome : group) {
                if (outcome.changing.isEmpty()) stayers.add(new IssueTypeRef(outcome.type.getId(), outcome.type.getName()));
                else bySignature.computeIfAbsent(signature(outcome.changing), key -> new ArrayList<Outcome>()).add(outcome);
            }
            if (bySignature.isEmpty()) continue;
            // Types that keep this layout: unselected ones sharing it, and selected ones that need no change.
            List<IssueTypeRef> keep = unselectedSharing(project, layout, selectedIds);
            keep.addAll(stayers);
            keep.sort(Comparator.comparing(IssueTypeRef::getName).thenComparing(IssueTypeRef::getId));
            if (keep.isEmpty() && bySignature.size() == 1) {
                changes.add(new LayoutChange(layout, bySignature.values().iterator().next().get(0).changing));
                continue;
            }
            for (List<Outcome> same : bySignature.values()) {
                Split split = new Split(layout, keep, same.get(0).changing);
                for (Outcome outcome : same) { split.issueTypes.add(outcome.type); splitOf.put(outcome, split); }
                splits.add(split);
            }
        }
        List<Target> targets = new ArrayList<Target>();
        for (Outcome outcome : outcomes) {
            Split split = splitOf.get(outcome);
            AffectedProjects others = split == null ? otherProjects(outcome.layout, project) : new AffectedProjects(0,
                    Collections.<ProjectRef>emptyList());
            targets.add(new Target(outcome.type, outcome.layout, outcome.changedIds, outcome.wanted, others, split,
                    outcome.fields));
        }
        if (!splits.isEmpty()) {
            requirePrivateScheme(project, scheme, splits);
            nameCopies(project, splits);
        }
        Map<String, Long> mappings = new TreeMap<String, Long>();
        if (scheme != null) {
            FieldLayoutScheme mutable = fieldLayoutManager.getMutableFieldLayoutScheme(scheme.getId());
            if (mutable == null) throw new IllegalStateException("The field configuration scheme could not be read.");
            for (FieldLayoutSchemeEntity entity : mutable.getEntities()) {
                mappings.put(entity.getIssueTypeId() == null ? "" : entity.getIssueTypeId(), entity.getFieldLayoutId());
            }
        }
        List<Long> schemeProjects = new ArrayList<Long>();
        if (scheme != null) {
            Collection<Project> users = fieldLayoutManager.getProjectsUsing(scheme);
            if (users != null) for (Project user : users) schemeProjects.add(user.getId());
            Collections.sort(schemeProjects);
        }
        List<Object> state = java.util.Arrays.<Object>asList(layoutStates, mappings,
                scheme == null ? null : scheme.getDescription(), schemeProjects, fieldState(selectedFields), createStates);
        return new Plan(project, schemeView, targets, changes, splits, selectedFields, state);
    }

    private IssueTypeScreenScheme topScheme(Project project) {
        IssueTypeScreenScheme top = screenSchemes.getIssueTypeScreenScheme(project);
        if (top == null) throw new IllegalStateException("No issue type screen scheme for " + project.getKey());
        return top;
    }

    /** The context definition, not only whether it still happens to apply to the selected issue types. */
    private static Map<String, Object> fieldState(List<OrderableField> selected) {
        Map<String, Object> result = new TreeMap<String, Object>();
        for (OrderableField field : selected) {
            if (!(field instanceof CustomField)) continue;
            CustomField custom = (CustomField) field;
            List<FieldConfigScheme> schemes = new ArrayList<FieldConfigScheme>(custom.getConfigurationSchemes());
            schemes.sort(Comparator.comparing(FieldConfigScheme::getId));
            List<Object> contexts = new ArrayList<Object>();
            for (FieldConfigScheme scheme : schemes) {
                Collection<Long> projectIds = scheme.getAssociatedProjectIds();
                List<Long> projects = new ArrayList<Long>(projectIds == null ? Collections.<Long>emptyList() : projectIds);
                projects.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
                Collection<String> issueTypeIds = scheme.getAssociatedIssueTypeIds();
                List<String> types = new ArrayList<String>(issueTypeIds == null ? Collections.<String>emptyList() : issueTypeIds);
                types.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
                Map<String, Long> configs = new TreeMap<String, Long>();
                for (Map.Entry<String, FieldConfig> entry : scheme.getConfigs().entrySet()) {
                    configs.put(entry.getKey() == null ? "" : entry.getKey(), entry.getValue().getId());
                }
                contexts.add(map("id", scheme.getId(), "name", scheme.getName(), "description", scheme.getDescription(),
                        "global", scheme.isAllProjects(), "allTypes", scheme.isAllIssueTypes(),
                        "projects", projects, "types", types, "configs", configs));
            }
            result.put(field.getId(), map("description", custom.getDescription(), "contexts", contexts));
        }
        return result;
    }

    /** Issue types of the project, not selected, whose effective configuration is the given layout. */
    private List<IssueTypeRef> unselectedSharing(Project project, FieldLayout layout, Set<String> selectedIds) {
        List<IssueTypeRef> result = new ArrayList<IssueTypeRef>();
        for (IssueType other : project.getIssueTypes()) {
            if (selectedIds.contains(other.getId())) continue;
            FieldLayout otherLayout = fieldLayoutManager.getFieldLayout(project, other.getId());
            if (otherLayout != null && layoutKey(otherLayout).equals(layoutKey(layout))) {
                result.add(new IssueTypeRef(other.getId(), other.getName()));
            }
        }
        result.sort(Comparator.comparing(IssueTypeRef::getName).thenComparing(IssueTypeRef::getId));
        return result;
    }

    /** A copy can be mapped only in a scheme that belongs to this project alone; Jira's default scheme cannot be edited. */
    private void requirePrivateScheme(Project project, FieldConfigurationScheme scheme, Collection<Split> splits) {
        String reason = null;
        if (scheme == null) {
            reason = "the project uses Jira's default field configuration scheme";
        } else {
            Collection<Project> users = fieldLayoutManager.getProjectsUsing(scheme);
            int others = 0;
            if (users != null) for (Project user : users) if (!project.getId().equals(user.getId())) others++;
            if (others > 0) reason = "its field configuration scheme '" + scheme.getName() + "' is shared with "
                    + others + " other project(s)";
        }
        if (reason == null) return;
        Set<String> sharing = new java.util.TreeSet<String>();
        for (Split split : splits) {
            for (IssueTypeRef type : split.unchanged) sharing.add(type.getName());
        }
        String change = "Changing the required flags";
        String cause = sharing.isEmpty()
                ? "The selected issue types share a field configuration but need different required flags"
                : "The selected issue types share a field configuration with issue types that were not changed ("
                        + String.join(", ", sharing) + ")";
        throw new FieldSelectionFailure(ErrorCode.SHARED_FIELD_CONFIGURATION_SCHEME,
                cause + ". " + change + " only for the selected ones needs "
                        + "their own copy of that configuration in the project's field configuration scheme, but "
                        + reason + ". Give project " + project.getKey() + " its own field configurations with "
                        + "clone_project_field_configurations first, or include those issue types.", null, null);
    }

    private void nameCopies(Project project, Collection<Split> splits) {
        Set<String> taken = new HashSet<String>();
        for (FieldLayout layout : fieldLayoutManager.getEditableFieldLayouts()) {
            if (layout.getName() != null) taken.add(layout.getName().trim().toLowerCase(Locale.ROOT));
        }
        for (Split split : splits) {
            List<String> names = new ArrayList<String>();
            for (IssueType type : split.issueTypes) names.add(type.getName());
            String base = FieldConfigurationClonePlanner.titled(split.source.getName(), project,
                    Collections.<Project>emptyList());
            split.newName = FieldConfigurationClonePlanner.freeName(base + " (" + String.join(", ", names) + ")", taken);
            taken.add(split.newName.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** System fields have no contexts: they apply to every project and issue type. */
    private static boolean isApplicable(Project project, IssueType issueType, OrderableField field) {
        return !(field instanceof CustomField) || ((CustomField) field).isInScope(project.getId(), issueType.getId());
    }

    private AffectedProjects otherProjects(FieldLayout layout, Project selected) {
        Collection<Project> users = fieldLayoutManager.getProjectsUsing(layout);
        List<Project> others = new ArrayList<Project>();
        if (users != null) for (Project project : users) {
            if (!selected.getId().equals(project.getId())) others.add(project);
        }
        others.sort(Comparator.comparing(Project::getKey));
        List<ProjectRef> refs = new ArrayList<ProjectRef>();
        for (int i = 0; i < others.size() && i < Limits.MAX_PROJECTS_SHOWN; i++) {
            refs.add(new ProjectRef(others.get(i).getKey(), others.get(i).getName()));
        }
        return new AffectedProjects(others.size(), refs);
    }

    static String layoutKey(FieldLayout layout) { return layout.isDefault() ? "default" : String.valueOf(layout.getId()); }

    private static FieldSelectionFailure invalid(String message) {
        return new FieldSelectionFailure(ErrorCode.VALIDATION_ERROR, message, null, null);
    }

    private static final class FieldSelectionFailure extends RuntimeException {
        private final ErrorCode code;
        private final List<String> suggestions;
        private final List<FieldView> candidates;
        private final List<FieldError> fieldErrors;

        private FieldSelectionFailure(ErrorCode code, String message, List<String> suggestions,
                                      List<FieldView> candidates) {
            this(code, message, suggestions, candidates, null);
        }

        private FieldSelectionFailure(ErrorCode code, String message, List<String> suggestions,
                                      List<FieldView> candidates, List<FieldError> fieldErrors) {
            super(message, null, false, false);
            this.code = code;
            this.suggestions = suggestions;
            this.candidates = candidates;
            this.fieldErrors = fieldErrors;
        }
    }
}
