package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
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
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsRequest;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.Change;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.EffectiveScheme;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.ErrorCode;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.LinkedProject;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.ProjectImpact;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.RowAction;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.RowPlan;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.SchemeInfo;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.Status;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.Warning;
import ru.rasulov.screens.impl.IssueTypeScreenSchemeMappingPlan.Failure;
import ru.rasulov.screens.impl.IssueTypeScreenSchemeMappingPlan.PlannedRow;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

import static ru.rasulov.screens.impl.IssueTypeScreenSchemeMappingPlan.DEFAULT;

/**
 * Plans the edit of the rows of ONE existing Issue Type Screen Scheme against its final table: every row of the request is
 * resolved and validated first, before/after are computed from the table after ALL rows (so the order of rows never
 * matters), and every linked project (archived ones included) is measured. The fingerprint covers everything the
 * preview relied on, so a changed source makes the apply stale.
 */
@Named
public class IssueTypeScreenSchemeMappingPlanner {
    static final int MAX_ROWS = 50;
    private static final int LISTED_PROJECTS = 10;
    static final List<String> LIMITATIONS = Collections.unmodifiableList(Arrays.asList(
            "Задачи не мигрируют: меняется только таблица «тип задачи → Screen Scheme» этой Issue Type Screen Scheme.",
            "Копии не создаются, связи проектов с Issue Type Screen Scheme не меняются.",
            "Проекты без изменения (N − M) сейчас других экранов не получат, но продолжат использовать изменённую общую "
                    + "таблицу (например, когда в проект добавят нужный тип задач).",
            "Без отката: при сбое часть строк может остаться применённой; после сбоя — только новое превью оставшихся "
                    + "изменений и новое подтверждение, не повтор старого запроса.",
            "Защита от параллельных правок ограничена: блокировка Control не действует на Jira UI, REST и другие плагины; "
                    + "перед записью состояние сверяется с превью, но между проверкой и записью остаётся короткое окно."));

    private final IssueTypeResolver types;
    private final FieldScreenSchemeManager fields;
    private final IssueTypeScreenSchemeManager tops;
    private final ProjectManager projectManager;
    private final ActionPlanCodec codec;

    @Inject
    public IssueTypeScreenSchemeMappingPlanner(IssueTypeResolver types, @ComponentImport FieldScreenSchemeManager fields,
            @ComponentImport IssueTypeScreenSchemeManager tops, @ComponentImport ProjectManager projectManager,
            ActionPlanCodec codec) {
        this.types = types; this.fields = fields; this.tops = tops; this.projectManager = projectManager; this.codec = codec;
    }

    IssueTypeScreenSchemeMappingPlan plan(SetIssueTypeScreenSchemeMappingsRequest request) {
        if (request.getIssueTypeScreenScheme() == null || request.getIssueTypeScreenScheme().trim().isEmpty()) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, null, "Preview requires issueTypeScreenScheme: an id or the exact name.");
        }
        if (request.getRows() == null || request.getRows().isEmpty()) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, null, "Preview requires a nonempty rows list.");
        }
        if (request.getRows().size() > MAX_ROWS) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, null, "rows accepts at most " + MAX_ROWS + " rows.");
        }
        IssueTypeScreenScheme itss = resolveItss(request.getIssueTypeScreenScheme().trim());

        Map<String, FieldScreenScheme> before = new TreeMap<String, FieldScreenScheme>();
        for (IssueTypeScreenSchemeEntity entity : itss.getEntities()) {
            FieldScreenScheme scheme = entity.getFieldScreenScheme();
            if (scheme == null) throw new Failure(ErrorCode.JIRA_ERROR, null, "A row of the Issue Type Screen Scheme has no Screen Scheme.");
            before.put(entity.getIssueTypeId() == null ? DEFAULT : entity.getIssueTypeId(), scheme);
        }
        Map<String, FieldScreenScheme> after = new TreeMap<String, FieldScreenScheme>(before);

        // 1. Every row is resolved and validated; nothing is computed from an unvalidated row.
        IssueTypeResolver.Session typeSession = types.session();
        Map<String, FieldScreenScheme> resolvedSchemes = new HashMap<String, FieldScreenScheme>();
        Map<String, IssueType> rowTypes = new TreeMap<String, IssueType>();
        Set<String> seen = new HashSet<String>();
        List<Draft> drafts = new ArrayList<Draft>();
        int index = 0;
        for (SetIssueTypeScreenSchemeMappingsRequest.Row row : request.getRows()) {
            int at = index++;
            String where = "rows[" + at + "]";
            if (row == null) throw new Failure(ErrorCode.INVALID_ROW, at, where + " must be an object {action, issueType, screenScheme}.");
            RowAction requested = action(row.getAction(), at, where);
            String typeText = row.getIssueType() == null ? "" : row.getIssueType().trim();
            if (typeText.isEmpty()) throw new Failure(ErrorCode.INVALID_ROW, at, where + ".issueType must be an issue type id, exact name or DEFAULT.");
            if (requested == RowAction.REMOVE) {
                if (row.getScreenScheme() != null) throw new Failure(ErrorCode.INVALID_ROW, at, where + ".screenScheme is not allowed for REMOVE.");
            } else if (row.getScreenScheme() == null || row.getScreenScheme().trim().isEmpty()) {
                throw new Failure(ErrorCode.INVALID_ROW, at, where + ".screenScheme (an id or exact name) is required for " + requested + ".");
            }
            IssueType type = null;
            String key = DEFAULT;
            if (!DEFAULT.equals(typeText)) {
                try { type = typeSession.resolve(typeText); }
                catch (LookupFailure e) {
                    throw new Failure(e.getKind() == LookupFailure.Kind.UNKNOWN_ISSUE_TYPE ? ErrorCode.ISSUE_TYPE_NOT_FOUND : ErrorCode.INVALID_ROW,
                            at, where + ".issueType: " + e.getMessage() + " The default row is addressed as DEFAULT.");
                }
                key = type.getId();
            }
            String label = type == null ? "DEFAULT" : "issue type '" + type.getName() + "'";
            if (!seen.add(key)) throw new Failure(ErrorCode.DUPLICATE_ROW, at, where + " repeats " + label + ": a type has at most one row per request.");
            FieldScreenScheme target = null;
            if (requested != RowAction.REMOVE) {
                String reference = row.getScreenScheme().trim();
                target = resolvedSchemes.get(reference);
                if (target == null) { target = resolveScreenScheme(reference, at, where); resolvedSchemes.put(reference, target); }
            }
            FieldScreenScheme prior = before.get(key);
            RowAction effective = requested;
            boolean change;
            switch (requested) {
                case REMOVE:
                    if (type == null) throw new Failure(ErrorCode.DEFAULT_ROW_REQUIRED, at, where + ": the DEFAULT row cannot be removed (replace it instead).");
                    if (prior == null) throw new Failure(ErrorCode.ROW_NOT_FOUND, at, where + ": " + label + " has no explicit row to remove.");
                    change = true; after.remove(key);
                    break;
                case REPLACE:
                    if (prior == null) throw new Failure(ErrorCode.ROW_NOT_FOUND, at, where + ": " + label + " has no explicit row to replace (use ADD).");
                    change = !prior.getId().equals(target.getId()); after.put(key, target);
                    break;
                default:
                    if (prior != null) { effective = RowAction.REPLACE; change = !prior.getId().equals(target.getId()); }
                    else change = true;
                    after.put(key, target);
            }
            if (type != null) rowTypes.put(type.getId(), type);
            drafts.add(new Draft(at, key, type, requested, effective, change, prior, target));
        }

        // 2. Per-row view against the final table, and the warnings of the rows.
        List<PlannedRow> rows = new ArrayList<PlannedRow>();
        List<Warning> warnings = new ArrayList<Warning>();
        boolean any = false;
        for (Draft draft : drafts) {
            IssueTypeRef ref = draft.type == null ? null : new IssueTypeRef(draft.type.getId(), draft.type.getName());
            RowPlan view = new RowPlan(draft.index, ref, draft.requested, draft.effective, draft.change ? Change.CHANGE : Change.NO_CHANGE,
                    effective(before, draft.key), effective(after, draft.key), null, null);
            rows.add(new PlannedRow(draft.index, draft.key, draft.type == null ? null : draft.type.getId(),
                    draft.type == null ? null : draft.type.getName(), draft.effective, draft.change, draft.prior, draft.target, view));
            any |= draft.change;
            if (draft.requested == RowAction.ADD && draft.effective == RowAction.REPLACE) {
                warnings.add(new Warning("ADD_AS_REPLACE", draft.index, "rows[" + draft.index + "]: "
                        + (draft.type == null ? "DEFAULT" : "issue type '" + draft.type.getName() + "'") + " already has an explicit row ("
                        + draft.prior.getName() + "); ADD is treated as REPLACE with " + draft.target.getName()
                        + (draft.change ? " and is applied only after this preview is confirmed." : ": the scheme is the same, nothing changes.")));
            }
        }

        // 3. Every linked project, archived ones included: N and M count all of them, only the list is cut.
        List<Project> linked = linkedProjects(itss);
        List<LinkedProject> listed = new ArrayList<LinkedProject>();
        List<List<Object>> projectState = new ArrayList<List<Object>>();
        int changing = 0;
        for (int i = 0; i < linked.size(); i++) {
            Project project = linked.get(i);
            Map<String, IssueType> projectTypes = new TreeMap<String, IssueType>();
            if (project.getIssueTypes() != null) for (IssueType type : project.getIssueTypes()) projectTypes.put(type.getId(), type);
            int changed = 0;
            for (String typeId : projectTypes.keySet()) {
                if (!Objects.equals(effectiveId(before, typeId), effectiveId(after, typeId))) changed++;
            }
            if (changed > 0) changing++;
            projectState.add(Arrays.<Object>asList(project.getId(), project.getKey(), project.getName(), project.isArchived(),
                    new ArrayList<String>(projectTypes.keySet())));
            if (i < LISTED_PROJECTS) {
                List<IssueTypeRef> missing = new ArrayList<IssueTypeRef>();
                for (IssueType type : rowTypes.values()) {
                    if (!projectTypes.containsKey(type.getId())) missing.add(new IssueTypeRef(type.getId(), type.getName()));
                }
                listed.add(new LinkedProject(project.getKey(), project.getName(), project.isArchived(), changed, missing));
            }
        }
        if (linked.isEmpty()) warnings.add(new Warning("NO_PROJECTS", null,
                "No project uses this Issue Type Screen Scheme: editing it changes no project now."));
        ProjectImpact projects = new ProjectImpact(linked.size(), changing, linked.size() > LISTED_PROJECTS, listed);

        int changes = 0;
        for (PlannedRow row : rows) if (row.change) changes++;
        SchemeInfo info = new SchemeInfo(itss.getId(), itss.getName(), itss.isDefault());
        String message = any
                ? "Issue Type Screen Scheme " + itss.getId() + " '" + itss.getName() + "' is edited in place: " + changes + " of " + rows.size()
                    + " row(s) change; no copy is created and no project is switched. Tell the user the consequences, then apply through start_operation after confirmation."
                : "Every row already matches the stored table of Issue Type Screen Scheme " + itss.getId() + " '" + itss.getName()
                    + "'. Nothing changes and no plan is created.";
        List<RowPlan> views = new ArrayList<RowPlan>();
        for (PlannedRow row : rows) views.add(row.view);
        SetIssueTypeScreenSchemeMappingsResponse response = new SetIssueTypeScreenSchemeMappingsResponse(
                any ? Status.WOULD_CHANGE : Status.NO_CHANGE, true, info, views, projects,
                scopeText(linked.size(), changing), LIMITATIONS, warnings, null, null, message, null, null, null, null);

        // 4. The fingerprint: the stored table, its name/description, projects with their types, every involved Screen Scheme's content.
        Map<Long, Object> schemeState = new TreeMap<Long, Object>();
        for (Map<String, FieldScreenScheme> table : Arrays.asList(before, after)) {
            for (FieldScreenScheme scheme : table.values()) {
                if (!schemeState.containsKey(scheme.getId())) schemeState.put(scheme.getId(), schemeContent(scheme));
            }
        }
        Map<String, String> typeState = new TreeMap<String, String>();
        for (IssueType type : rowTypes.values()) typeState.put(type.getId(), type.getName());
        String fingerprint = codec.fingerprint(Arrays.asList(itss.getId(), itss.getName(), itss.getDescription(), itss.isDefault(),
                ids(before), ids(after), schemeState, projectState, typeState, response));
        return new IssueTypeScreenSchemeMappingPlan(itss, rows, before, after, response, fingerprint);
    }

    private static final class Draft {
        final int index;
        final String key;
        final IssueType type;
        final RowAction requested, effective;
        final boolean change;
        final FieldScreenScheme prior, target;
        Draft(int index, String key, IssueType type, RowAction requested, RowAction effective, boolean change,
              FieldScreenScheme prior, FieldScreenScheme target) {
            this.index = index; this.key = key; this.type = type; this.requested = requested; this.effective = effective;
            this.change = change; this.prior = prior; this.target = target;
        }
    }

    private static RowAction action(String text, int index, String where) {
        String value = text == null ? "" : text.trim();
        for (RowAction action : RowAction.values()) if (action.name().equals(value)) return action;
        throw new Failure(ErrorCode.INVALID_ROW, index, where + ".action must be ADD, REPLACE or REMOVE.");
    }

    private IssueTypeScreenScheme resolveItss(String text) {
        Long id = positiveId(text);
        if (id != null) {
            IssueTypeScreenScheme byId = tops.getIssueTypeScreenScheme(id);
            if (byId != null) return byId;
        }
        IssueTypeScreenScheme found = null;
        for (IssueTypeScreenScheme candidate : tops.getIssueTypeScreenSchemes()) {
            if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(text)) {
                if (found != null) throw new Failure(ErrorCode.ITSS_AMBIGUOUS, null,
                        "Several Issue Type Screen Schemes are named '" + text + "'. Use the exact id.");
                found = candidate;
            }
        }
        if (found == null) throw new Failure(ErrorCode.ITSS_NOT_FOUND, null,
                "No Issue Type Screen Scheme has the id or exact name '" + text + "'. Read get_project_screens to find the id of the scheme a project uses.");
        return found;
    }

    private FieldScreenScheme resolveScreenScheme(String text, int index, String where) {
        Long id = positiveId(text);
        if (id != null) {
            FieldScreenScheme byId = fields.getFieldScreenScheme(id);
            if (byId != null) return byId;
        }
        FieldScreenScheme found = null;
        for (FieldScreenScheme candidate : fields.getFieldScreenSchemes()) {
            if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(text)) {
                if (found != null) throw new Failure(ErrorCode.SCREEN_SCHEME_AMBIGUOUS, index,
                        where + ".screenScheme: several Screen Schemes are named '" + text + "'. Use the exact id.");
                found = candidate;
            }
        }
        if (found == null) throw new Failure(ErrorCode.SCREEN_SCHEME_NOT_FOUND, index,
                where + ".screenScheme: no Screen Scheme has the id or exact name '" + text + "'. Only existing Screen Schemes can be assigned.");
        return found;
    }

    private static Long positiveId(String text) {
        if (text.isEmpty() || text.length() > 18) return null;
        for (int i = 0; i < text.length(); i++) if (!Character.isDigit(text.charAt(i))) return null;
        long id = Long.parseLong(text);
        return id > 0 ? Long.valueOf(id) : null;
    }

    /** All projects Jira associates with the scheme, sorted by key; one that cannot be read would make N and M inexact. */
    private List<Project> linkedProjects(IssueTypeScreenScheme itss) {
        Set<Long> ids = new TreeSet<Long>();
        Collection<GenericValue> values = tops.getProjects(itss);
        if (values != null) {
            for (GenericValue value : values) {
                Long id = value == null ? null : value.getLong("id");
                if (id == null) throw new Failure(ErrorCode.JIRA_ERROR, null, "Jira lists a project of this scheme without an id; the project counts cannot be exact.");
                ids.add(id);
            }
        }
        List<Project> projects = new ArrayList<Project>();
        for (Long id : ids) {
            Project project = projectManager.getProjectObj(id);
            if (project == null) throw new Failure(ErrorCode.JIRA_ERROR, null,
                    "Jira lists project " + id + " as a user of this scheme but cannot read it; the project counts cannot be exact.");
            projects.add(project);
        }
        Collections.sort(projects, new Comparator<Project>() {
            @Override public int compare(Project a, Project b) {
                int byKey = a.getKey().compareTo(b.getKey());
                return byKey != 0 ? byKey : a.getId().compareTo(b.getId());
            }
        });
        return projects;
    }

    private static EffectiveScheme effective(Map<String, FieldScreenScheme> table, String key) {
        FieldScreenScheme own = table.get(key);
        if (own != null) return new EffectiveScheme(own.getId(), own.getName(), false);
        FieldScreenScheme fallback = table.get(DEFAULT);
        return fallback == null ? null : new EffectiveScheme(fallback.getId(), fallback.getName(), true);
    }

    private static Long effectiveId(Map<String, FieldScreenScheme> table, String typeId) {
        FieldScreenScheme scheme = table.containsKey(typeId) ? table.get(typeId) : table.get(DEFAULT);
        return scheme == null ? null : scheme.getId();
    }

    private static Map<String, Long> ids(Map<String, FieldScreenScheme> table) {
        Map<String, Long> ids = new TreeMap<String, Long>();
        for (Map.Entry<String, FieldScreenScheme> entry : table.entrySet()) ids.put(entry.getKey(), entry.getValue().getId());
        return ids;
    }

    private static List<Object> schemeContent(FieldScreenScheme scheme) {
        Map<String, List<Object>> items = new TreeMap<String, List<Object>>();
        for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
            String key = OperationScreenPlan.operationKey(item);
            if (items.containsKey(key)) throw new Failure(ErrorCode.JIRA_ERROR, null, "A Screen Scheme has duplicate operation mappings.");
            items.put(key, item.getFieldScreen() == null ? null
                    : Arrays.<Object>asList(item.getFieldScreen().getId(), item.getFieldScreen().getName()));
        }
        return Arrays.<Object>asList(scheme.getName(), scheme.getDescription(), items);
    }

    /** The scale sentence the assistant shows before confirmation: N linked and M really changing, never "all N". */
    static String scopeText(int linked, int changing) {
        if (linked == 0) return "Общую настройку не использует ни один проект. Фактический выбор экранов не изменится ни в одном проекте.";
        String shown = linked > LISTED_PROJECTS ? "Ниже показаны первые " + LISTED_PROJECTS + " связанных проектов."
                : linked == 1 ? "Ниже показан 1 связанный проект."
                : "Ниже показаны все " + linked + " " + form(linked, "связанный проект", "связанных проекта", "связанных проектов") + ".";
        return "Общую настройку используют " + linked + " " + form(linked, "проект", "проекта", "проектов") + ". "
                + "Фактический выбор экранов изменится в " + changing + " " + (changing % 10 == 1 && changing % 100 != 11 ? "проекте" : "проектах") + ". "
                + shown;
    }

    private static String form(int n, String one, String few, String many) {
        int tail = n % 100;
        if (tail >= 11 && tail <= 14) return many;
        switch (n % 10) {
            case 1: return one;
            case 2: case 3: case 4: return few;
            default: return many;
        }
    }
}
