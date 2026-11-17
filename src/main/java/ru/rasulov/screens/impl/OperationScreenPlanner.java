package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.ofbiz.core.entity.GenericValue;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensRequest;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.*;
import ru.rasulov.screens.impl.OperationScreenPlan.Failure;
import ru.rasulov.screens.impl.OperationScreenPlan.Copy;
import ru.rasulov.screens.impl.OperationScreenPlan.Row;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/** Resolves current Jira state and plans the whole batch of operation assignments against one source state. */
@Named
public class OperationScreenPlanner {
    private final ProjectResolver projects;
    private final IssueTypeResolver types;
    private final FieldScreenManager screens;
    private final FieldScreenSchemeManager fieldSchemes;
    private final IssueTypeScreenSchemeManager topSchemes;
    private final ProjectManager projectManager;
    private final ActionPlanCodec codec;
    private static final String DEFAULT = "DEFAULT";
    private static final int MAX_MAPPINGS = 50;

    @Inject
    public OperationScreenPlanner(ProjectResolver projects, IssueTypeResolver types,
            @ComponentImport FieldScreenManager screens, @ComponentImport FieldScreenSchemeManager fieldSchemes,
            @ComponentImport IssueTypeScreenSchemeManager topSchemes, @ComponentImport ProjectManager projectManager,
            ActionPlanCodec codec) {
        this.projects = projects; this.types = types; this.screens = screens;
        this.fieldSchemes = fieldSchemes; this.topSchemes = topSchemes; this.projectManager = projectManager; this.codec = codec;
    }

    OperationScreenPlan plan(SetProjectOperationScreensRequest request) {
        if (request.getProject() == null || request.getProject().trim().isEmpty()
                || request.getMappings() == null || request.getMappings().isEmpty()) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, "Preview requires project and a nonempty mappings list.");
        }
        if (request.getMappings().size() > MAX_MAPPINGS) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, "mappings accepts at most " + MAX_MAPPINGS + " rows.");
        }
        Project project;
        try { project = projects.resolve(request.getProject()); }
        catch (LookupFailure e) { throw new Failure(ErrorCode.UNKNOWN_PROJECT, e.getMessage()); }
        IssueTypeScreenScheme source = topSchemes.getIssueTypeScreenScheme(project);
        if (source == null) throw new Failure(ErrorCode.JIRA_ERROR, "The project has no issue type screen scheme.");
        Map<String, IssueType> currentTypes = new TreeMap<String, IssueType>();
        for (IssueType type : project.getIssueTypes()) currentTypes.put(type.getId(), type);

        // 1. Rows -> one merged patch per scope. Contradictions are rejected before anything else is read or written.
        Map<String, FieldScreen> allPatch = new TreeMap<String, FieldScreen>();
        Map<String, Map<String, FieldScreen>> typePatch = new TreeMap<String, Map<String, FieldScreen>>();
        Map<String, FieldScreen> resolvedScreens = new HashMap<String, FieldScreen>();
        Map<String, String> schemeNameByType = new TreeMap<String, String>();
        String schemeNameForAll = null;
        boolean anyAll = false;
        int index = 0;
        for (SetProjectOperationScreensRequest.Mapping mapping : request.getMappings()) {
            String where = "mappings[" + index++ + "]";
            if (mapping == null || mapping.getScreens() == null || mapping.getScreens().isEmpty()) {
                throw new Failure(ErrorCode.VALIDATION_ERROR, where + ".screens must be a nonempty patch.");
            }
            boolean all = Boolean.TRUE.equals(mapping.getAllIssueTypes());
            if (mapping.getAllIssueTypes() != null && !all) {
                throw new Failure(ErrorCode.VALIDATION_ERROR,
                        where + ".allIssueTypes may only be true; omit it and list issueTypes for named types.");
            }
            if (all == (mapping.getIssueTypes() != null)) {
                throw new Failure(ErrorCode.VALIDATION_ERROR,
                        where + " needs exactly one of issueTypes (nonempty) or allIssueTypes:true.");
            }
            Map<String, FieldScreen> patch = new TreeMap<String, FieldScreen>();
            for (Map.Entry<String, String> entry : mapping.getScreens().entrySet()) {
                if (entry.getKey() == null) throw new Failure(ErrorCode.VALIDATION_ERROR, "Operation names cannot be null.");
                OperationScreenPlan.operation(entry.getKey());
                String reference = entry.getValue() == null ? "" : entry.getValue().trim();
                FieldScreen screen = resolvedScreens.get(reference);
                if (screen == null) { screen = resolveScreen(reference); resolvedScreens.put(reference, screen); }
                patch.put(entry.getKey(), screen);
            }
            String schemeName = explicitName(mapping.getScreenSchemeName(), where + ".screenSchemeName");
            if (all) {
                anyAll = true;
                merge(allPatch, patch, "all issue types");
                if (schemeName != null) {
                    if (schemeNameForAll != null && !schemeNameForAll.equals(schemeName)) {
                        throw new Failure(ErrorCode.VALIDATION_ERROR, "Two allIssueTypes rows name the Screen Scheme differently.");
                    }
                    schemeNameForAll = schemeName;
                }
                continue;
            }
            if (mapping.getIssueTypes().isEmpty()) {
                throw new Failure(ErrorCode.VALIDATION_ERROR, where + ".issueTypes must be nonempty.");
            }
            Set<String> inRow = new TreeSet<String>();
            for (String name : mapping.getIssueTypes()) {
                IssueType type;
                try { type = types.resolve(name); }
                catch (LookupFailure e) { throw new Failure(ErrorCode.UNKNOWN_ISSUE_TYPE, e.getMessage()); }
                if (!currentTypes.containsKey(type.getId())) throw new Failure(ErrorCode.UNKNOWN_ISSUE_TYPE,
                        "Issue type '" + type.getName() + "' is not available in project " + project.getKey() + ".");
                if (!inRow.add(type.getId())) throw new Failure(ErrorCode.VALIDATION_ERROR,
                        where + ".issueTypes resolves to a duplicate type: " + type.getName() + ".");
                Map<String, FieldScreen> target = typePatch.get(type.getId());
                if (target == null) { target = new TreeMap<String, FieldScreen>(); typePatch.put(type.getId(), target); }
                merge(target, patch, "issue type '" + type.getName() + "'");
                if (schemeName != null) {
                    String named = schemeNameByType.get(type.getId());
                    if (named != null && !named.equals(schemeName)) throw new Failure(ErrorCode.VALIDATION_ERROR,
                            "Issue type '" + type.getName() + "' is given two different screenSchemeName values.");
                    schemeNameByType.put(type.getId(), schemeName);
                }
            }
        }
        for (Map.Entry<String, Map<String, FieldScreen>> entry : typePatch.entrySet()) {
            for (Map.Entry<String, FieldScreen> operation : entry.getValue().entrySet()) {
                FieldScreen general = allPatch.get(operation.getKey());
                if (general != null && !general.getId().equals(operation.getValue().getId())) {
                    throw new Failure(ErrorCode.VALIDATION_ERROR, "Conflicting assignments for issue type '"
                            + currentTypes.get(entry.getKey()).getName() + "' " + operation.getKey() + ": the all-types row sets "
                            + describe(general) + ", another row sets " + describe(operation.getValue()) + ".");
                }
            }
        }

        // 2. The source state: explicit rows, referenced schemes, effective scheme of every current type.
        Map<Long, FieldScreenScheme> referenced = new TreeMap<Long, FieldScreenScheme>();
        Map<String, Long> mappings = new TreeMap<String, Long>();
        Map<String, FieldScreenScheme> sourceRows = new TreeMap<String, FieldScreenScheme>();
        for (IssueTypeScreenSchemeEntity entity : source.getEntities()) {
            FieldScreenScheme scheme = entity.getFieldScreenScheme();
            if (scheme == null) throw new Failure(ErrorCode.JIRA_ERROR, "A screen-scheme mapping is missing its scheme.");
            String key = entity.getIssueTypeId() == null ? DEFAULT : entity.getIssueTypeId();
            mappings.put(key, scheme.getId());
            sourceRows.put(key, scheme);
            referenced.put(scheme.getId(), scheme);
        }
        Map<String, FieldScreenScheme> before = new TreeMap<String, FieldScreenScheme>();
        for (IssueType type : currentTypes.values()) {
            FieldScreenScheme scheme = source.getEffectiveFieldScreenScheme(type);
            if (scheme == null) throw new Failure(ErrorCode.JIRA_ERROR, "No effective screen scheme for issue type " + type.getId() + ".");
            referenced.put(scheme.getId(), scheme);
            before.put(type.getId(), scheme);
        }

        // 3. The whole final matrix at once: one derived scheme per distinct (source scheme, changing patch).
        Deriver deriver = new Deriver(project, currentTypes, schemeNameByType, schemeNameForAll, typePatch.keySet());
        List<Row> rows = new ArrayList<Row>();
        Map<String, Row> rowByType = new HashMap<String, Row>();
        Row defaultRow = null;
        for (Map.Entry<String, FieldScreenScheme> entry : sourceRows.entrySet()) {
            String key = entry.getKey();
            Map<String, FieldScreen> patch = new TreeMap<String, FieldScreen>(allPatch);
            if (!DEFAULT.equals(key) && typePatch.containsKey(key)) patch.putAll(typePatch.get(key));
            Copy copy = deriver.derive(entry.getValue(), patch, key);
            Row row = new Row(DEFAULT.equals(key) ? null : key, copy == null ? entry.getValue() : null, copy, true);
            rows.add(row);
            if (DEFAULT.equals(key)) defaultRow = row; else rowByType.put(key, row);
        }
        for (Map.Entry<String, Map<String, FieldScreen>> entry : typePatch.entrySet()) {
            if (sourceRows.containsKey(entry.getKey())) continue;
            Map<String, FieldScreen> patch = new TreeMap<String, FieldScreen>(allPatch);
            patch.putAll(entry.getValue());
            Copy copy = deriver.derive(before.get(entry.getKey()), patch, entry.getKey());
            if (copy == null) continue;
            Row row = new Row(entry.getKey(), null, copy, false);
            rows.add(row); rowByType.put(entry.getKey(), row);
        }
        List<Copy> copies = deriver.copies;
        for (String name : schemeNameByType.values()) deriver.requireApplied(name);
        deriver.requireApplied(schemeNameForAll);
        Sharing sharing = sharing(source, project);
        SchemeMode mode = copies.isEmpty() ? SchemeMode.NO_CHANGE : source.isDefault() ? SchemeMode.COPY_DEFAULT
                : sharing.exclusive ? SchemeMode.UPDATE_PRIVATE : SchemeMode.COPY_SHARED;
        String newTopName = null;
        String requestedTop = explicitName(request.getIssueTypeScreenSchemeName(), "issueTypeScreenSchemeName");
        if (requestedTop != null && mode != SchemeMode.COPY_SHARED && mode != SchemeMode.COPY_DEFAULT) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, "issueTypeScreenSchemeName '" + requestedTop + "' cannot be applied: "
                    + (mode == SchemeMode.NO_CHANGE ? "the requested screens already match, so no new Issue Type Screen Scheme is created."
                    : "the project's own Issue Type Screen Scheme is updated in place and keeps its name.") + " Omit it.");
        }
        if (mode == SchemeMode.COPY_SHARED || mode == SchemeMode.COPY_DEFAULT) {
            Set<String> usedTopNames = new HashSet<String>();
            for (IssueTypeScreenScheme existing : topSchemes.getIssueTypeScreenSchemes()) {
                usedTopNames.add(normalize(existing.getName()));
            }
            if (requestedTop == null) newTopName = freeName(project.getKey() + ": Operation Screens", usedTopNames);
            else {
                if (usedTopNames.contains(normalize(requestedTop))) throw new Failure(ErrorCode.VALIDATION_ERROR,
                        "An Issue Type Screen Scheme named '" + requestedTop + "' already exists.");
                newTopName = requestedTop;
            }
        }

        // 4. The report: per-type matrix, new child schemes, effect on DEFAULT and untouched types.
        List<IssueType> ordered = new ArrayList<IssueType>(currentTypes.values());
        Collections.sort(ordered, new Comparator<IssueType>() {
            @Override public int compare(IssueType a, IssueType b) {
                int byName = normalize(a.getName()).compareTo(normalize(b.getName()));
                return byName != 0 ? byName : a.getId().compareTo(b.getId());
            }
        });
        List<TypeAssignment> matrix = new ArrayList<TypeAssignment>();
        List<String> inheritingChanged = new ArrayList<String>();
        List<IssueTypeRef> projectTypeRefs = new ArrayList<IssueTypeRef>();
        for (IssueType type : ordered) {
            projectTypeRefs.add(new IssueTypeRef(type.getId(), type.getName()));
            FieldScreenScheme beforeScheme = before.get(type.getId());
            Row row = rowByType.containsKey(type.getId()) ? rowByType.get(type.getId()) : defaultRow;
            if (row == null) throw new Failure(ErrorCode.JIRA_ERROR, "The project's scheme has no default mapping.");
            boolean changed = row.copy != null || (row.existing != null && !row.existing.getId().equals(beforeScheme.getId()));
            List<OperationChange> operations = new ArrayList<OperationChange>();
            for (String operation : OperationScreenPlan.OPERATIONS) {
                FieldScreen was = OperationScreenPlan.effective(beforeScheme, operation);
                FieldScreen will = row.effective(operation);
                changed |= !sameScreen(was, will);
                operations.add(new OperationChange(operation, ref(was), ref(will)));
            }
            boolean explicit = rowByType.containsKey(type.getId());
            boolean selected = anyAll || typePatch.containsKey(type.getId());
            if (changed && !explicit) inheritingChanged.add(type.getName());
            SchemeRef afterRef = row.copy != null ? new SchemeRef(null, row.copy.name, true)
                    : new SchemeRef(row.existing.getId(), row.existing.getName(), false);
            matrix.add(new TypeAssignment(new IssueTypeRef(type.getId(), type.getName()), selected, explicit, changed,
                    new SchemeRef(beforeScheme.getId(), beforeScheme.getName(), false), afterRef, operations));
        }
        List<SchemeChange> changes = new ArrayList<SchemeChange>();
        for (Copy copy : copies) {
            List<OperationChange> operations = new ArrayList<OperationChange>();
            for (String operation : OperationScreenPlan.OPERATIONS) {
                if (!copy.patch.containsKey(operation)) continue;
                operations.add(new OperationChange(operation, ref(OperationScreenPlan.effective(copy.source, operation)),
                        ref(copy.patch.get(operation))));
            }
            changes.add(new SchemeChange(copy.source.getId(), copy.source.getName(), copy.name, null, copy.appliesTo, operations));
        }
        List<String> warnings = new ArrayList<String>();
        if (defaultRow != null && defaultRow.copy != null) {
            warnings.add("The DEFAULT mapping changes. Issue types without their own mapping follow it"
                    + (inheritingChanged.isEmpty() ? "." : ": " + String.join(", ", inheritingChanged) + "."));
        }
        int outside = 0;
        for (Row row : rows) {
            if (row.typeId != null && row.copy != null && !currentTypes.containsKey(row.typeId)) outside++;
        }
        if (outside > 0) warnings.add(outside + " explicit mapping(s) of issue types that are not in this project also change, "
                + "because an all-types row covers every mapping of the project's scheme.");
        List<MappingChange> mappingChanges = new ArrayList<MappingChange>();
        for (Row row : rows) {
            if (row.copy == null) continue;
            IssueTypeRef typeRef = null;
            if (row.typeId != null) {
                IssueType known = currentTypes.get(row.typeId);
                typeRef = new IssueTypeRef(row.typeId, known == null ? null : known.getName());
            }
            mappingChanges.add(new MappingChange(typeRef, row.inSource ? MappingAction.CHANGE : MappingAction.ADD,
                    new SchemeRef(row.copy.source.getId(), row.copy.source.getName(), false),
                    new SchemeRef(null, row.copy.name, true)));
        }
        String reason;
        String message;
        switch (mode) {
            case NO_CHANGE:
                reason = "The requested effective screens already match.";
                message = "The requested effective screens already match. Nothing changes.";
                break;
            case UPDATE_PRIVATE:
                reason = "Issue Type Screen Scheme " + source.getId() + " is not Jira's default and Jira reports only this project as using it.";
                message = "Issue Type Screen Scheme " + source.getId() + " '" + source.getName() + "' is updated in place: its ID, name, "
                        + "description and the project's association are kept, " + mappingChanges.size() + " mapping(s) change and "
                        + copies.size() + " new Screen Scheme copy(ies) hold only the changed operations. No new Issue Type Screen Scheme "
                        + "is created. Issue types that are not covered, omitted operations, the source Screen Schemes and the contents of "
                        + "the Screens are not changed.";
                warnings.add("Mappings of the existing Issue Type Screen Scheme are changed in place, so the previous mappings are not kept "
                        + "as a separate scheme. If Jira fails midway nothing is rolled back; the result then reports the mappings read back from Jira.");
                break;
            case COPY_DEFAULT:
                reason = "The project uses Jira's default Issue Type Screen Scheme, which is never changed in place.";
                message = copyMessage(copies.size());
                break;
            default:
                reason = sharing.others.isEmpty()
                        ? "Exclusive use of Issue Type Screen Scheme " + source.getId() + " by this project is not proven by Jira's associations."
                        : "Issue Type Screen Scheme " + source.getId() + " is also used by " + sharing.others.size() + " other project(s).";
                message = copyMessage(copies.size());
        }
        TopSchemePlan topPlan = new TopSchemePlan(mode, reason, source.getId(), source.getName(), source.isDefault(),
                sharing.others.size(), sharing.names, mappingChanges);
        SetProjectOperationScreensResponse response = new SetProjectOperationScreensResponse(
                mode == SchemeMode.NO_CHANGE ? Status.ALREADY_ASSIGNED : Status.WOULD_ASSIGN, true,
                new ProjectRef(project.getKey(), project.getName()), source.getId(), source.getName(), newTopName,
                topPlan, null, MatrixSource.PLANNED, matrix, changes, null, null, warnings, message, null, null, null);
        Map<Long, Object> fieldState = new TreeMap<Long, Object>();
        for (FieldScreenScheme scheme : referenced.values()) {
            Map<String, ScreenRef> items = new TreeMap<String, ScreenRef>();
            for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
                String key = OperationScreenPlan.operationKey(item);
                if (items.containsKey(key)) throw new Failure(ErrorCode.JIRA_ERROR, "A screen scheme has duplicate operation mappings.");
                items.put(key, ref(item.getFieldScreen()));
            }
            fieldState.put(scheme.getId(), Arrays.asList(scheme.getName(), scheme.getDescription(), items));
        }
        // The basis of the mode (default flag and every associated project, archived ones included) is part of the checked plan.
        String fingerprint = codec.fingerprint(Arrays.asList(project.getId(), source.getId(), source.getName(),
                source.getDescription(), projectTypeRefs, mappings, fieldState, mode, source.isDefault(), sharing.ids,
                sharing.exclusive, response));
        return new OperationScreenPlan(project, source, mode, mappings, copies, rows, response, fingerprint);
    }

    private static String copyMessage(int copies) {
        return "One new Issue Type Screen Scheme replaces the project's association; " + copies
                + " new Screen Scheme copy(ies) hold only the changed operations. Issue types that are not covered, "
                + "omitted operations, other projects, the source schemes and the contents of the Screens are not changed.";
    }

    /** The associations of the source top scheme as Jira reports them; exclusive only when this project is the sole user. */
    private Sharing sharing(IssueTypeScreenScheme source, Project project) {
        Set<Long> others = new TreeSet<Long>();
        boolean listed = false, unreadable = false;
        for (GenericValue value : topSchemes.getProjects(source)) {
            Long id = value == null ? null : value.getLong("id");
            if (id == null) { unreadable = true; continue; }
            if (id.equals(project.getId())) listed = true; else others.add(id);
        }
        List<Project> named = new ArrayList<Project>();
        for (Long id : others) {
            Project other = projectManager.getProjectObj(id);
            if (other != null) named.add(other);
        }
        Collections.sort(named, new Comparator<Project>() {
            @Override public int compare(Project a, Project b) { return a.getKey().compareTo(b.getKey()); }
        });
        List<ProjectRef> refs = new ArrayList<ProjectRef>();
        for (int i = 0; i < named.size() && i < 10; i++) refs.add(new ProjectRef(named.get(i).getKey(), named.get(i).getName()));
        return new Sharing(new ArrayList<Long>(others), refs, listed && !unreadable && others.isEmpty());
    }

    private static final class Sharing {
        final List<Long> ids;
        final List<Long> others;
        final List<ProjectRef> names;
        final boolean exclusive;
        Sharing(List<Long> others, List<ProjectRef> names, boolean exclusive) {
            this.ids = others; this.others = others; this.names = names; this.exclusive = exclusive;
        }
    }

    /**
     * What Jira returns after the switch, per current project type. Throws when the project does not use the created
     * top scheme or a type has no readable scheme: the caller then must not present the plan as the outcome.
     */
    List<TypeAssignment> readBack(OperationScreenPlan plan, Long topId, Set<Long> createdSchemeIds, List<String> notes) {
        IssueTypeScreenScheme now = topSchemes.getIssueTypeScreenScheme(plan.project);
        if (now == null || !topId.equals(now.getId())) {
            throw new IllegalStateException("The project does not use Issue Type Screen Scheme " + topId + ".");
        }
        Map<String, IssueType> current = new HashMap<String, IssueType>();
        for (IssueType type : plan.project.getIssueTypes()) current.put(type.getId(), type);
        List<TypeAssignment> actual = new ArrayList<TypeAssignment>();
        for (TypeAssignment planned : plan.response.getIssueTypes()) {
            IssueType type = current.get(planned.getIssueType().getId());
            if (type == null) {
                notes.add("Issue type " + planned.getIssueType().getName() + " left the project during the apply.");
                continue;
            }
            FieldScreenScheme scheme = now.getEffectiveFieldScreenScheme(type);
            if (scheme == null) throw new IllegalStateException("No effective screen scheme for " + type.getName() + ".");
            boolean changed = !planned.getScreenSchemeBefore().getId().equals(scheme.getId());
            List<OperationChange> operations = new ArrayList<OperationChange>();
            for (OperationChange was : planned.getOperations()) {
                FieldScreen screen = OperationScreenPlan.effective(scheme, was.getOperation());
                changed |= !sameRef(screen, was.getBefore());
                if (!sameRef(screen, was.getAfter())) {
                    notes.add("Jira returned a different Screen than planned for " + type.getName() + " " + was.getOperation() + ".");
                }
                operations.add(new OperationChange(was.getOperation(), was.getBefore(), ref(screen)));
            }
            actual.add(new TypeAssignment(planned.getIssueType(), planned.isSelected(), now.getEntity(type.getId()) != null,
                    changed, planned.getScreenSchemeBefore(),
                    new SchemeRef(scheme.getId(), scheme.getName(), createdSchemeIds.contains(scheme.getId())), operations));
        }
        return actual;
    }

    /** Derives child schemes for the batch: equal (source scheme, changing patch) pairs share one copy. */
    private final class Deriver {
        final List<Copy> copies = new ArrayList<Copy>();
        private final Map<String, Copy> byKey = new HashMap<String, Copy>();
        private final Project project;
        private final Map<String, IssueType> currentTypes;
        private final Map<String, String> nameByType;
        private final String nameForAll;
        private final Set<String> typedKeys;
        private final Set<String> applied = new HashSet<String>();
        private final Set<String> batchNames = new HashSet<String>();
        private Set<String> usedNames;

        Deriver(Project project, Map<String, IssueType> currentTypes, Map<String, String> nameByType, String nameForAll,
                Set<String> typedKeys) {
            this.project = project; this.currentTypes = currentTypes; this.nameByType = nameByType; this.nameForAll = nameForAll;
            this.typedKeys = typedKeys;
        }

        /** A dictated name that no new Screen Scheme received must not be silently dropped. */
        void requireApplied(String name) {
            if (name != null && !applied.contains(name)) throw new Failure(ErrorCode.VALIDATION_ERROR, "screenSchemeName '" + name
                    + "' cannot be applied: no new Screen Scheme is created for its row (the requested screens already match, "
                    + "or the row's types get a differently named scheme). Omit it.");
        }

        private void claim(String name) {
            if (usedNames.add(normalize(name))) { batchNames.add(normalize(name)); return; }
            throw new Failure(ErrorCode.VALIDATION_ERROR, batchNames.contains(normalize(name))
                    ? "screenSchemeName '" + name + "' would name two different new Screen Schemes (they differ in source scheme or "
                        + "screens). Ask the user which scope gets the name, list the other affected types in an issueTypes row "
                        + "(with their own name or none), or omit it."
                    : "A Screen Scheme named '" + name + "' already exists.");
        }

        /** Null when every patched operation already has that Screen (no copy, the row keeps its scheme). */
        Copy derive(FieldScreenScheme scheme, Map<String, FieldScreen> patch, String rowKey) {
            Map<String, FieldScreen> changing = new TreeMap<String, FieldScreen>();
            for (Map.Entry<String, FieldScreen> entry : patch.entrySet()) {
                if (!sameScreen(OperationScreenPlan.effective(scheme, entry.getKey()), entry.getValue())) {
                    changing.put(entry.getKey(), entry.getValue());
                }
            }
            if (changing.isEmpty()) return null;
            StringBuilder key = new StringBuilder().append(scheme.getId());
            for (Map.Entry<String, FieldScreen> entry : changing.entrySet()) {
                key.append('|').append(entry.getKey()).append('=').append(entry.getValue().getId());
            }
            Copy copy = byKey.get(key.toString());
            // A row-level name from an allIssueTypes row reaches only scopes that no issueTypes row patches further.
            String requested = nameByType.containsKey(rowKey) ? nameByType.get(rowKey) : typedKeys.contains(rowKey) ? null : nameForAll;
            if (copy != null && requested != null) {
                if (copy.explicitName && !normalize(requested).equals(normalize(copy.name))) {
                    throw new Failure(ErrorCode.VALIDATION_ERROR, "Rows that need the same new Screen Scheme must give it the same "
                            + "screenSchemeName (or none): '" + requested + "' conflicts with '" + copy.name + "'.");
                }
                if (!copy.explicitName) {
                    usedNames.remove(normalize(copy.name));
                    claim(requested);
                    copy.name = requested;
                    copy.explicitName = true;
                }
                applied.add(requested);
            }
            if (copy == null) {
                if (usedNames == null) {
                    usedNames = new HashSet<String>();
                    for (FieldScreenScheme existing : fieldSchemes.getFieldScreenSchemes()) usedNames.add(normalize(existing.getName()));
                }
                String name;
                if (requested == null) name = freeName(project.getKey() + ": Operation Screen Scheme " + scheme.getId(), usedNames);
                else { claim(requested); name = requested; applied.add(requested); }
                copy = new Copy(scheme, changing, name, requested != null, new ArrayList<String>());
                byKey.put(key.toString(), copy);
                copies.add(copy);
            }
            if (DEFAULT.equals(rowKey)) copy.appliesTo.add(DEFAULT);
            else copy.appliesTo.add(currentTypes.containsKey(rowKey) ? currentTypes.get(rowKey).getName()
                    : "issue type " + rowKey + " (not in the project)");
            return copy;
        }
    }

    private static void merge(Map<String, FieldScreen> target, Map<String, FieldScreen> patch, String scope) {
        for (Map.Entry<String, FieldScreen> entry : patch.entrySet()) {
            FieldScreen existing = target.get(entry.getKey());
            if (existing != null && !existing.getId().equals(entry.getValue().getId())) {
                throw new Failure(ErrorCode.VALIDATION_ERROR, "Conflicting assignments for " + scope + " "
                        + entry.getKey() + ": " + describe(existing) + " and " + describe(entry.getValue()) + ".");
            }
            target.put(entry.getKey(), entry.getValue());
        }
    }

    private static String describe(FieldScreen screen) { return "'" + screen.getName() + "' (" + screen.getId() + ")"; }

    private static boolean sameRef(FieldScreen screen, ScreenRef ref) {
        return screen == null ? ref == null : ref != null && screen.getId().equals(ref.getId());
    }

    private static boolean sameScreen(FieldScreen a, FieldScreen b) {
        return a == null ? b == null : b != null && a.getId().equals(b.getId());
    }

    private FieldScreen resolveScreen(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) throw new Failure(ErrorCode.VALIDATION_ERROR, "Screen references must be nonblank.");
        try {
            FieldScreen byId = screens.getFieldScreen(Long.valueOf(text));
            if (byId != null) return byId;
        } catch (NumberFormatException ignored) { /* Exact screen name, not a numeric id. */ }
        FieldScreen found = null;
        for (FieldScreen candidate : screens.getFieldScreens()) {
            if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(text)) {
                if (found != null) throw new Failure(ErrorCode.UNKNOWN_SCREEN,
                        "Several Screens match '" + text + "'. Use an exact screen id.");
                found = candidate;
            }
        }
        if (found == null) throw new Failure(ErrorCode.UNKNOWN_SCREEN,
                "No Screen has the id or exact name '" + text + "'. Create the requested Screen with create_screen first.");
        return found;
    }

    private static ScreenRef ref(FieldScreen screen) {
        return screen == null ? null : new ScreenRef(screen.getId(), screen.getName());
    }
    /** Trimmed explicit name, or null when none was given (the generated name is used). */
    private static String explicitName(String name, String where) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255 || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, where + " must be 1-255 characters without control characters; omit it to use the generated name.");
        }
        return trimmed;
    }
    private static String normalize(String name) { return name == null ? "" : name.toLowerCase(Locale.ROOT); }
    private static String freeName(String base, Set<String> used) {
        if (base.length() > 240) base = base.substring(0, 240);
        String name = base;
        for (int index = 2; used.contains(normalize(name)); index++) name = base + " (" + index + ")";
        used.add(normalize(name));
        return name;
    }
}
