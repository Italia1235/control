package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsRequest;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.Change;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.EffectiveScreen;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.ErrorCode;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.FieldRef;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.ItssRef;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.ItssUsage;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.LinkedProject;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.ProjectImpact;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RequiredFields;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RequiredProject;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RowAction;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RowPlan;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.SchemeInfo;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.Status;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.Warning;
import ru.rasulov.screens.impl.ScreenSchemeOperationPlan.Failure;
import ru.rasulov.screens.impl.ScreenSchemeOperationPlan.PlannedRow;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

import static ru.rasulov.screens.impl.ScreenSchemeOperationPlan.DEFAULT;
import static ru.rasulov.screens.impl.ScreenSchemeOperationPlan.SCREENED;

/**
 * Plans the edit of the rows of ONE existing Screen Scheme against its final table: every row of the request is resolved and
 * validated first, before/after are computed from the table after ALL rows (so the order of rows never matters), and every
 * project that reaches the scheme through an Issue Type Screen Scheme (archived ones included) is measured. The fingerprint
 * covers everything the preview relied on except the required flags and Field Configuration Schemes, which the preview
 * names as untracked.
 */
@Named
public class ScreenSchemeOperationPlanner {
    static final int MAX_ROWS = 50;
    private static final int LISTED = 10;
    static final List<String> LIMITATIONS = Collections.unmodifiableList(Arrays.asList(
            "Задачи не мигрируют: меняется только таблица «операция → экран» этой Screen Scheme; экраны переходов workflow не затрагиваются.",
            "Копии не создаются: Issue Type Screen Scheme и связи проектов не меняются.",
            "Проекты без изменения (N − M) сейчас других экранов не получат, но продолжат использовать изменённую общую Screen Scheme.",
            "Без отката: при сбое часть строк может остаться применённой; после сбоя — только новое превью оставшихся "
                    + "изменений и новое подтверждение, не повтор старого запроса.",
            "Защита от параллельных правок ограничена: блокировка Control не действует на Jira UI, REST и другие плагины; "
                    + "перед записью состояние сверяется с превью, но между проверкой и записью остаётся короткое окно.",
            "Между превью и применением не отслеживаются изменение флага required в field configuration и смена "
                    + "Field Configuration Scheme проекта: предупреждение об обязательных полях рассчитано по состоянию на момент превью."));

    private final FieldScreenSchemeManager schemes;
    private final FieldScreenManager screens;
    private final IssueTypeScreenSchemeManager tops;
    private final ProjectManager projectManager;
    private final ScreenSchemeCreateRequirements requirements;
    private final ActionPlanCodec codec;

    @Inject
    public ScreenSchemeOperationPlanner(@ComponentImport FieldScreenSchemeManager schemes, @ComponentImport FieldScreenManager screens,
            @ComponentImport IssueTypeScreenSchemeManager tops, @ComponentImport ProjectManager projectManager,
            ScreenSchemeCreateRequirements requirements, ActionPlanCodec codec) {
        this.schemes = schemes; this.screens = screens; this.tops = tops; this.projectManager = projectManager;
        this.requirements = requirements; this.codec = codec;
    }

    ScreenSchemeOperationPlan plan(SetScreenSchemeOperationsRequest request) {
        if (request.getScreenScheme() == null || request.getScreenScheme().trim().isEmpty()) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, null, "Preview requires screenScheme: an id or the exact name.");
        }
        if (request.getRows() == null || request.getRows().isEmpty()) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, null, "Preview requires a nonempty rows list.");
        }
        if (request.getRows().size() > MAX_ROWS) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, null, "rows accepts at most " + MAX_ROWS + " rows.");
        }
        FieldScreenScheme scheme = resolveScheme(request.getScreenScheme().trim());
        Map<String, FieldScreen> before = ScreenSchemeOperationPlan.table(scheme);
        if (!before.containsKey(DEFAULT)) throw new Failure(ErrorCode.JIRA_ERROR, null,
                "Screen Scheme " + scheme.getId() + " has no DEFAULT row; Jira data is inconsistent, nothing can be planned.");
        Map<String, FieldScreen> after = new TreeMap<String, FieldScreen>(before);

        // 1. Every row is resolved and validated; nothing is computed from an unvalidated row.
        Map<String, FieldScreen> resolvedScreens = new HashMap<String, FieldScreen>();
        Set<String> seen = new HashSet<String>();
        List<Draft> drafts = new ArrayList<Draft>();
        int index = 0;
        for (SetScreenSchemeOperationsRequest.Row row : request.getRows()) {
            int at = index++;
            String where = "rows[" + at + "]";
            if (row == null) throw new Failure(ErrorCode.INVALID_ROW, at, where + " must be an object {action, operation, screen}.");
            RowAction requested = action(row.getAction(), at, where);
            String operation = operation(row.getOperation(), at, where);
            if (requested == RowAction.REMOVE) {
                if (row.getScreen() != null) throw new Failure(ErrorCode.INVALID_ROW, at, where + ".screen is not allowed for REMOVE.");
            } else if (row.getScreen() == null || row.getScreen().trim().isEmpty()) {
                throw new Failure(ErrorCode.INVALID_ROW, at, where + ".screen (an id or exact name) is required for " + requested + ".");
            }
            if (!seen.add(operation)) throw new Failure(ErrorCode.DUPLICATE_ROW, at, where + " repeats " + operation
                    + ": an operation has at most one row per request.");
            FieldScreen target = null;
            if (requested != RowAction.REMOVE) {
                String reference = row.getScreen().trim();
                target = resolvedScreens.get(reference);
                if (target == null) { target = resolveScreen(reference, at, where); resolvedScreens.put(reference, target); }
            }
            FieldScreen prior = before.get(operation);
            RowAction effective = requested;
            boolean change;
            switch (requested) {
                case REMOVE:
                    if (DEFAULT.equals(operation)) throw new Failure(ErrorCode.DEFAULT_ROW_REQUIRED, at, where + ": the DEFAULT row cannot be removed (replace it instead).");
                    if (prior == null) throw new Failure(ErrorCode.ROW_NOT_FOUND, at, where + ": " + operation + " has no explicit row to remove.");
                    change = true; after.remove(operation);
                    break;
                case REPLACE:
                    if (prior == null) throw new Failure(ErrorCode.ROW_NOT_FOUND, at, where + ": " + operation + " has no explicit row to replace (use ADD).");
                    change = !prior.getId().equals(target.getId()); after.put(operation, target);
                    break;
                default:
                    if (prior != null) { effective = RowAction.REPLACE; change = !prior.getId().equals(target.getId()); }
                    else change = true;
                    after.put(operation, target);
            }
            drafts.add(new Draft(at, operation, requested, effective, change, prior, target));
        }
        // A screen of the table that cannot be found any more makes the plan unreliable: stale at apply, an error now.
        for (Map<String, FieldScreen> table : Arrays.asList(before, after)) {
            for (FieldScreen screen : table.values()) {
                if (screens.getFieldScreen(screen.getId()) == null) throw new Failure(ErrorCode.JIRA_ERROR, null,
                        "Screen " + screen.getId() + " of the Screen Scheme cannot be read.");
            }
        }

        // 2. Per-row view against the final table, and the warnings of the rows.
        List<PlannedRow> rows = new ArrayList<PlannedRow>();
        List<Warning> warnings = new ArrayList<Warning>();
        boolean any = false;
        for (Draft draft : drafts) {
            RowPlan view = new RowPlan(draft.index, draft.operation, draft.requested, draft.effective,
                    draft.change ? Change.CHANGE : Change.NO_CHANGE, effective(before, draft.operation), effective(after, draft.operation), null, null);
            rows.add(new PlannedRow(draft.index, draft.operation, draft.effective, draft.change, draft.prior, draft.target, view));
            any |= draft.change;
            if (draft.requested == RowAction.ADD && draft.effective == RowAction.REPLACE) {
                warnings.add(new Warning("ADD_AS_REPLACE", draft.index, "rows[" + draft.index + "]: " + draft.operation
                        + " already has an explicit row (" + draft.prior.getName() + "); ADD is treated as REPLACE with " + draft.target.getName()
                        + (draft.change ? " and is applied only after this preview is confirmed." : ": the screen is the same, nothing changes.")));
            }
        }

        // 3. Every Issue Type Screen Scheme that contains the scheme, and every project of them (archived included).
        List<Top> using = containing(scheme);
        Map<Long, Project> projectById = new HashMap<Long, Project>();
        Map<Long, Top> topOfProject = new HashMap<Long, Top>();
        for (Top top : using) {
            for (Project project : projectsOf(top.scheme)) {
                if (topOfProject.containsKey(project.getId())) continue;
                projectById.put(project.getId(), project); topOfProject.put(project.getId(), top);
            }
        }
        List<Project> linked = new ArrayList<Project>(projectById.values());
        Collections.sort(linked, new Comparator<Project>() {
            @Override public int compare(Project a, Project b) {
                int byKey = a.getKey().compareTo(b.getKey());
                return byKey != 0 ? byKey : a.getId().compareTo(b.getId());
            }
        });
        ScreenSchemeCreateRequirements.Session session = requirements.session();
        List<LinkedProject> listed = new ArrayList<LinkedProject>();
        List<List<Object>> projectState = new ArrayList<List<Object>>();
        int changing = 0;
        int pairs = 0;
        Map<String, String> missingFields = new TreeMap<String, String>();
        List<RequiredProject> requiredProjects = new ArrayList<RequiredProject>();
        int requiredProjectCount = 0;
        FieldScreen newCreate = after.containsKey("CREATE") ? after.get("CREATE") : after.get(DEFAULT);
        for (int i = 0; i < linked.size(); i++) {
            Project project = linked.get(i);
            Top top = topOfProject.get(project.getId());
            Map<String, IssueType> projectTypes = new TreeMap<String, IssueType>();
            if (project.getIssueTypes() != null) for (IssueType type : project.getIssueTypes()) projectTypes.put(type.getId(), type);
            int changed = 0;
            List<IssueTypeRef> missingTypes = new ArrayList<IssueTypeRef>();
            for (IssueType type : projectTypes.values()) {
                FieldScreenScheme effectiveScheme = top.effective(type.getId());
                if (effectiveScheme == null || !scheme.getId().equals(effectiveScheme.getId())) continue;
                boolean typeChanges = false, createChanges = false;
                for (String operation : SCREENED) {
                    if (!Objects.equals(effectiveId(before, operation), effectiveId(after, operation))) {
                        typeChanges = true;
                        createChanges |= "CREATE".equals(operation);
                    }
                }
                if (typeChanges) changed++;
                if (createChanges) {
                    Map<String, String> missing = missingOn(session, project, type, newCreate);
                    if (!missing.isEmpty()) {
                        pairs++;
                        missingFields.putAll(missing);
                        missingTypes.add(new IssueTypeRef(type.getId(), type.getName()));
                    }
                }
            }
            if (changed > 0) changing++;
            if (!missingTypes.isEmpty()) {
                requiredProjectCount++;
                if (requiredProjects.size() < LISTED) requiredProjects.add(new RequiredProject(project.getKey(), project.getName(), missingTypes));
            }
            projectState.add(Arrays.<Object>asList(project.getId(), project.getKey(), project.getName(), project.isArchived(), top.scheme.getId(),
                    typeStates(projectTypes)));
            if (i < LISTED) {
                listed.add(new LinkedProject(project.getKey(), project.getName(), project.isArchived(), changed,
                        new ItssRef(top.scheme.getId(), top.scheme.getName(), top.scheme.isDefault())));
            }
        }
        if (pairs > 0) {
            List<FieldRef> fieldRefs = new ArrayList<FieldRef>();
            StringBuilder names = new StringBuilder();
            for (Map.Entry<String, String> field : missingFields.entrySet()) {
                fieldRefs.add(new FieldRef(field.getKey(), field.getValue()));
                if (names.length() > 0) names.append(", ");
                names.append(field.getValue()).append(" (").append(field.getKey()).append(")");
            }
            warnings.add(new Warning("REQUIRED_FIELDS_MISSING_ON_CREATE", null,
                    "For " + pairs + " (project, issue type) pair(s) in " + requiredProjectCount + " project(s) the new CREATE screen '"
                            + newCreate.getName() + "' lacks fields that are required in their field configuration: " + names
                            + ". Creating such issues will fail until the fields are put on the screen. The warning does not block the change; the user decides.",
                    new RequiredFields(pairs, requiredProjectCount > LISTED, requiredProjects, fieldRefs)));
        }
        if (linked.isEmpty()) warnings.add(new Warning("NO_LINKED_PROJECTS", null,
                "No project uses this Screen Scheme through an Issue Type Screen Scheme: editing it changes no project now."));
        List<ItssRef> listedTops = new ArrayList<ItssRef>();
        List<List<Object>> topState = new ArrayList<List<Object>>();
        for (int i = 0; i < using.size(); i++) {
            Top top = using.get(i);
            if (i < LISTED) listedTops.add(new ItssRef(top.scheme.getId(), top.scheme.getName(), top.scheme.isDefault()));
            topState.add(Arrays.<Object>asList(top.scheme.getId(), top.scheme.getName(), top.scheme.getDescription(), top.scheme.isDefault(), top.ids()));
        }
        ItssUsage usage = new ItssUsage(using.size(), using.size() > LISTED, listedTops);
        ProjectImpact projects = new ProjectImpact(linked.size(), changing, linked.size() > LISTED, listed);

        int changes = 0;
        for (PlannedRow row : rows) if (row.change) changes++;
        SchemeInfo info = new SchemeInfo(scheme.getId(), scheme.getName(), scheme.getDescription());
        String message = any
                ? "Screen Scheme " + scheme.getId() + " '" + scheme.getName() + "' is edited in place: " + changes + " of " + rows.size()
                    + " row(s) change; no copy is created and no project is switched. Tell the user the consequences, then apply through start_operation after confirmation."
                : "Every row already matches the stored table of Screen Scheme " + scheme.getId() + " '" + scheme.getName()
                    + "'. Nothing changes and no plan is created.";
        List<RowPlan> views = new ArrayList<RowPlan>();
        for (PlannedRow row : rows) views.add(row.view);
        SetScreenSchemeOperationsResponse response = new SetScreenSchemeOperationsResponse(
                any ? Status.WOULD_CHANGE : Status.NO_CHANGE, true, info, views, usage, projects,
                IssueTypeScreenSchemeMappingPlanner.scopeText(linked.size(), changing), LIMITATIONS, warnings, null, null, message,
                null, null, null, null);

        // 4. The fingerprint: the stored table and its screens, the rows, the containing Issue Type Screen Schemes and their
        // projects with their issue types, and the fields of the CREATE screens before and after. The warnings are NOT
        // part of it: required flags and Field Configuration Schemes are not tracked.
        Map<Long, String> screenState = new TreeMap<Long, String>();
        for (Map<String, FieldScreen> table : Arrays.asList(before, after)) {
            for (FieldScreen screen : table.values()) screenState.put(screen.getId(), screen.getName());
        }
        Map<Long, Set<String>> createFields = new TreeMap<Long, Set<String>>();
        for (Map<String, FieldScreen> table : Arrays.asList(before, after)) {
            FieldScreen create = table.containsKey("CREATE") ? table.get("CREATE") : table.get(DEFAULT);
            createFields.put(create.getId(), readFields(session, create));
        }
        List<List<Object>> rowState = new ArrayList<List<Object>>();
        for (PlannedRow row : rows) rowState.add(Arrays.<Object>asList(row.index, row.operation, row.action, row.change));
        String fingerprint = codec.fingerprint(Arrays.asList(scheme.getId(), scheme.getName(), scheme.getDescription(),
                ids(before), ids(after), screenState, createFields, rowState, topState, projectState));
        return new ScreenSchemeOperationPlan(scheme, rows, before, after, response, fingerprint);
    }

    private static final class Draft {
        final int index;
        final String operation;
        final RowAction requested, effective;
        final boolean change;
        final FieldScreen prior, target;
        Draft(int index, String operation, RowAction requested, RowAction effective, boolean change, FieldScreen prior, FieldScreen target) {
            this.index = index; this.operation = operation; this.requested = requested; this.effective = effective;
            this.change = change; this.prior = prior; this.target = target;
        }
    }

    /** An Issue Type Screen Scheme that contains the scheme, with its rows read once. */
    private static final class Top {
        final IssueTypeScreenScheme scheme;
        final Map<String, FieldScreenScheme> rows;
        Top(IssueTypeScreenScheme scheme, Map<String, FieldScreenScheme> rows) { this.scheme = scheme; this.rows = rows; }

        /** The Screen Scheme an issue type gets: its own row, or the DEFAULT row. */
        FieldScreenScheme effective(String typeId) { return rows.containsKey(typeId) ? rows.get(typeId) : rows.get(DEFAULT); }

        Map<String, Long> ids() {
            Map<String, Long> ids = new TreeMap<String, Long>();
            for (Map.Entry<String, FieldScreenScheme> row : rows.entrySet()) ids.put(row.getKey(), row.getValue().getId());
            return ids;
        }
    }

    private Map<String, String> missingOn(ScreenSchemeCreateRequirements.Session session, Project project, IssueType type, FieldScreen screen) {
        try { return session.missing(project, type, screen); }
        catch (Failure e) { throw e; }
        catch (RuntimeException e) {
            throw new Failure(ErrorCode.JIRA_ERROR, null, "The field configuration or the CREATE screen of project " + project.getKey()
                    + " cannot be read, so the required fields cannot be checked: " + e.getMessage());
        }
    }

    private static Set<String> readFields(ScreenSchemeCreateRequirements.Session session, FieldScreen screen) {
        try { return session.fieldsOf(screen); }
        catch (Failure e) { throw e; }
        catch (RuntimeException e) {
            throw new Failure(ErrorCode.JIRA_ERROR, null, "The fields of screen " + screen.getId() + " cannot be read: " + e.getMessage());
        }
    }

    private static RowAction action(String text, int index, String where) {
        String value = text == null ? "" : text.trim();
        for (RowAction action : RowAction.values()) if (action.name().equals(value)) return action;
        throw new Failure(ErrorCode.INVALID_ROW, index, where + ".action must be ADD, REPLACE or REMOVE.");
    }

    private static String operation(String text, int index, String where) {
        String value = text == null ? "" : text.trim();
        if (ScreenSchemeOperationPlan.OPERATIONS.contains(value)) return value;
        throw new Failure(ErrorCode.INVALID_OPERATION, index, where + ".operation must be DEFAULT, CREATE, EDIT or VIEW.");
    }

    private FieldScreenScheme resolveScheme(String text) {
        Long id = positiveId(text);
        if (id != null) {
            FieldScreenScheme byId = schemes.getFieldScreenScheme(id);
            if (byId != null) return byId;
        }
        FieldScreenScheme found = null;
        for (FieldScreenScheme candidate : schemes.getFieldScreenSchemes()) {
            if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(text)) {
                if (found != null) throw new Failure(ErrorCode.SCREEN_SCHEME_AMBIGUOUS, null,
                        "Several Screen Schemes are named '" + text + "'. Use the exact id.");
                found = candidate;
            }
        }
        if (found == null) throw new Failure(ErrorCode.SCREEN_SCHEME_NOT_FOUND, null,
                "No Screen Scheme has the id or exact name '" + text + "'. Read get_project_screens to find the Screen Scheme an issue type uses.");
        return found;
    }

    private FieldScreen resolveScreen(String text, int index, String where) {
        Long id = positiveId(text);
        if (id != null) {
            FieldScreen byId = screens.getFieldScreen(id);
            if (byId != null) return byId;
        }
        FieldScreen found = null;
        for (FieldScreen candidate : screens.getFieldScreens()) {
            if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(text)) {
                if (found != null) throw new Failure(ErrorCode.SCREEN_AMBIGUOUS, index,
                        where + ".screen: several screens are named '" + text + "'. Use the exact id.");
                found = candidate;
            }
        }
        if (found == null) throw new Failure(ErrorCode.SCREEN_NOT_FOUND, index,
                where + ".screen: no screen has the id or exact name '" + text + "'. Only existing screens can be assigned; create_screen makes a new one.");
        return found;
    }

    private static Long positiveId(String text) {
        if (text.isEmpty() || text.length() > 18) return null;
        for (int i = 0; i < text.length(); i++) if (!Character.isDigit(text.charAt(i))) return null;
        long id = Long.parseLong(text);
        return id > 0 ? Long.valueOf(id) : null;
    }

    /** Every Issue Type Screen Scheme with a row (explicit or DEFAULT) for the scheme, by id. */
    private List<Top> containing(FieldScreenScheme scheme) {
        List<Top> result = new ArrayList<Top>();
        for (IssueTypeScreenScheme top : tops.getIssueTypeScreenSchemes()) {
            Map<String, FieldScreenScheme> rows = new TreeMap<String, FieldScreenScheme>();
            boolean uses = false;
            for (IssueTypeScreenSchemeEntity entity : top.getEntities()) {
                FieldScreenScheme row = entity.getFieldScreenScheme();
                if (row == null) continue;
                rows.put(entity.getIssueTypeId() == null ? DEFAULT : entity.getIssueTypeId(), row);
                uses |= scheme.getId().equals(row.getId());
            }
            if (uses) result.add(new Top(top, rows));
        }
        Collections.sort(result, new Comparator<Top>() {
            @Override public int compare(Top a, Top b) { return a.scheme.getId().compareTo(b.scheme.getId()); }
        });
        return result;
    }

    /** All projects Jira associates with the scheme; one that cannot be read would make N and M inexact. */
    private List<Project> projectsOf(IssueTypeScreenScheme top) {
        Set<Long> ids = new TreeSet<Long>();
        Collection<GenericValue> values = tops.getProjects(top);
        if (values != null) {
            for (GenericValue value : values) {
                Long id = value == null ? null : value.getLong("id");
                if (id == null) throw new Failure(ErrorCode.JIRA_ERROR, null,
                        "Jira lists a project of Issue Type Screen Scheme " + top.getId() + " without an id; the project counts cannot be exact.");
                ids.add(id);
            }
        }
        List<Project> projects = new ArrayList<Project>();
        for (Long id : ids) {
            Project project = projectManager.getProjectObj(id);
            if (project == null) throw new Failure(ErrorCode.JIRA_ERROR, null,
                    "Jira lists project " + id + " as a user of Issue Type Screen Scheme " + top.getId() + " but cannot read it; the project counts cannot be exact.");
            projects.add(project);
        }
        return projects;
    }

    private static EffectiveScreen effective(Map<String, FieldScreen> table, String operation) {
        FieldScreen own = table.get(operation);
        if (own != null) return new EffectiveScreen(own.getId(), own.getName(), false);
        FieldScreen fallback = table.get(DEFAULT);
        return fallback == null ? null : new EffectiveScreen(fallback.getId(), fallback.getName(), true);
    }

    private static Long effectiveId(Map<String, FieldScreen> table, String operation) {
        FieldScreen screen = table.containsKey(operation) ? table.get(operation) : table.get(DEFAULT);
        return screen == null ? null : screen.getId();
    }

    private static Map<String, Long> ids(Map<String, FieldScreen> table) {
        Map<String, Long> ids = new TreeMap<String, Long>();
        for (Map.Entry<String, FieldScreen> entry : table.entrySet()) ids.put(entry.getKey(), entry.getValue().getId());
        return ids;
    }

    private static Map<String, String> typeStates(Map<String, IssueType> types) {
        Map<String, String> state = new TreeMap<String, String>();
        for (IssueType type : types.values()) state.put(type.getId(), type.getName());
        return state;
    }
}
