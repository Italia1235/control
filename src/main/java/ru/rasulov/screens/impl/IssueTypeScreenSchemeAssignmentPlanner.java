package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeRequest;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.Change;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.Effective;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.ErrorCode;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.FieldRef;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.OperationChange;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.SchemeInfo;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.Status;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.TypePlan;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.Warning;
import ru.rasulov.screens.impl.IssueTypeScreenSchemeAssignmentPlan.Failure;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/**
 * Plans the switch of ONE project to an EXISTING Issue Type Screen Scheme: the target is resolved (id, name or the token
 * {@code DEFAULT}), every issue type of the project is shown before and after with its Screen Scheme and its effective
 * CREATE/EDIT/VIEW screens, and the consequences are measured (an orphaned old scheme, other projects on the new one,
 * required fields missing from a changed CREATE screen). The fingerprint covers everything the preview relied on except the
 * required flags and the project's Field Configuration Scheme, so a changed source makes the apply stale.
 */
@Named
public class IssueTypeScreenSchemeAssignmentPlanner {
    static final String DEFAULT_TOKEN = "DEFAULT";
    private static final String DEFAULT_ROW = "DEFAULT";
    private static final String[] OPERATIONS = {"CREATE", "EDIT", "VIEW"};
    private static final int LISTED = 10;
    static final List<String> LIMITATIONS = Collections.unmodifiableList(Arrays.asList(
            "Меняется только связь проекта с Issue Type Screen Scheme: строки схем, Screen Scheme и экраны не меняются, копии не создаются, задачи не мигрируют.",
            "Без отката: переключение нельзя отменить через Undo; вернуть проект можно только новым превью и новым подтверждением. Прежняя схема не удаляется, даже если её больше не использует ни один проект.",
            "Откат create_project для проекта, созданного через Control, после переключения схемы завершится конфликтом: схема проекта уже не та, что была создана.",
            "Между превью и применением не отслеживаются флаг required в Field Configuration и смена Field Configuration Scheme проекта: предупреждение REQUIRED_FIELDS_MISSING_ON_CREATE считается по состоянию превью.",
            "Защита от параллельных правок ограничена: блокировка Control не действует на Jira UI, REST и другие плагины; перед записью состояние сверяется с превью, но между проверкой и записью остаётся короткое окно."));

    private final ProjectResolver projects;
    private final IssueTypeScreenSchemeManager tops;
    private final ProjectManager projectManager;
    private final ScreenSchemeCreateRequirements requirements;
    private final ActionPlanCodec codec;

    @Inject
    public IssueTypeScreenSchemeAssignmentPlanner(ProjectResolver projects, @ComponentImport IssueTypeScreenSchemeManager tops,
            @ComponentImport ProjectManager projectManager, ScreenSchemeCreateRequirements requirements, ActionPlanCodec codec) {
        this.projects = projects; this.tops = tops; this.projectManager = projectManager;
        this.requirements = requirements; this.codec = codec;
    }

    IssueTypeScreenSchemeAssignmentPlan plan(AssignIssueTypeScreenSchemeRequest request) {
        if (request.getProject() == null || request.getProject().trim().isEmpty()) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, "Preview requires project: the key or name of an active project.");
        }
        if (request.getIssueTypeScreenScheme() == null || request.getIssueTypeScreenScheme().trim().isEmpty()) {
            throw new Failure(ErrorCode.VALIDATION_ERROR,
                    "Preview requires issueTypeScreenScheme: an id, the exact name or DEFAULT.");
        }
        Project project;
        try { project = projects.resolve(request.getProject()); }
        catch (LookupFailure e) { throw new Failure(ErrorCode.UNKNOWN_PROJECT, e.getMessage()); }
        IssueTypeScreenScheme current = tops.getIssueTypeScreenScheme(project);
        if (current == null) throw new Failure(ErrorCode.JIRA_ERROR, "Jira reports no Issue Type Screen Scheme for project " + project.getKey() + ".");
        IssueTypeScreenScheme target = resolveTarget(request.getIssueTypeScreenScheme().trim());
        boolean same = current.getId().equals(target.getId());

        Map<String, FieldScreenScheme> before = table(current), after = table(target);
        List<IssueType> types = new ArrayList<IssueType>();
        if (project.getIssueTypes() != null) types.addAll(project.getIssueTypes());
        Collections.sort(types, new Comparator<IssueType>() {
            @Override public int compare(IssueType a, IssueType b) {
                int byName = String.valueOf(a.getName()).compareToIgnoreCase(String.valueOf(b.getName()));
                return byName != 0 ? byName : a.getId().compareTo(b.getId());
            }
        });

        // 1. Every issue type of the project: Screen Scheme and effective screens before and after.
        ScreenSchemeCreateRequirements.Session session = requirements.session();
        List<TypePlan> views = new ArrayList<TypePlan>();
        Map<String, String> typeState = new TreeMap<String, String>();
        Map<IssueType, FieldScreen> newCreate = new LinkedHashMap<IssueType, FieldScreen>();
        for (IssueType type : types) {
            typeState.put(type.getId(), type.getName());
            FieldScreenScheme schemeBefore = effective(before, type.getId()), schemeAfter = effective(after, type.getId());
            if (schemeBefore == null || schemeAfter == null) {
                throw new Failure(ErrorCode.JIRA_ERROR, "Issue type '" + type.getName() + "' has no Screen Scheme (no own row and no DEFAULT row) in "
                        + (schemeBefore == null ? "'" + current.getName() : "'" + target.getName()) + "'.");
            }
            boolean change = !schemeBefore.getId().equals(schemeAfter.getId());
            List<OperationChange> screens = new ArrayList<OperationChange>();
            for (String operation : OPERATIONS) {
                Resolved was = resolve(schemeBefore, operation), will = resolve(schemeAfter, operation);
                boolean differs = !Objects.equals(was == null ? null : id(was.screen), will == null ? null : id(will.screen));
                change |= differs;
                screens.add(new OperationChange(operation, was == null ? null : was.view(), will == null ? null : will.view()));
                if (differs && "CREATE".equals(operation) && will != null && will.screen != null) newCreate.put(type, will.screen);
            }
            views.add(new TypePlan(new IssueTypeRef(type.getId(), type.getName()), change ? Change.CHANGE : Change.NO_CHANGE,
                    new Effective(schemeBefore.getId(), schemeBefore.getName(), !before.containsKey(type.getId())),
                    new Effective(schemeAfter.getId(), schemeAfter.getName(), !after.containsKey(type.getId())), screens));
        }

        ProjectRef projectRef = new ProjectRef(project.getKey(), project.getName());
        SchemeInfo currentInfo = new SchemeInfo(current.getId(), current.getName(), current.isDefault());
        SchemeInfo targetInfo = new SchemeInfo(target.getId(), target.getName(), target.isDefault());
        if (same) {
            AssignIssueTypeScreenSchemeResponse response = new AssignIssueTypeScreenSchemeResponse(Status.NO_CHANGE, true, projectRef,
                    currentInfo, targetInfo, views, LIMITATIONS, Collections.<Warning>emptyList(), null, null, null,
                    "Project " + project.getKey() + " already uses Issue Type Screen Scheme " + target.getId() + " '" + target.getName()
                            + "'. Nothing changes and no plan is created.", null, null, null);
            return new IssueTypeScreenSchemeAssignmentPlan(project, current, target, response, "");
        }

        // 2. The consequences. N counts every project of a scheme, archived ones included.
        List<Warning> warnings = new ArrayList<Warning>();
        List<Project> currentUsers = linkedProjects(current), targetUsers = linkedProjects(target);
        boolean othersOnCurrent = false;
        for (Project user : currentUsers) othersOnCurrent |= !user.getId().equals(project.getId());
        if (!current.isDefault() && !othersOnCurrent) {
            warnings.add(new Warning("ITSS_ORPHANED", "Issue Type Screen Scheme " + current.getId() + " '" + current.getName()
                    + "' will be used by no project after the switch. It is not deleted: it stays in Jira unused.",
                    null, null, null, null));
        }
        List<ProjectRef> others = new ArrayList<ProjectRef>();
        int otherCount = 0;
        for (Project user : targetUsers) {
            if (user.getId().equals(project.getId())) continue;
            otherCount++;
            if (others.size() < LISTED) others.add(new ProjectRef(user.getKey(), user.getName()));
        }
        if (otherCount > 0) {
            warnings.add(new Warning("SHARED_TARGET", "Issue Type Screen Scheme " + target.getId() + " '" + target.getName() + "' is already used by "
                    + otherCount + " other project(s), archived ones included: from now on edits of its rows (set_issue_type_screen_scheme_mappings) "
                    + "and of the Screen Schemes it uses change the screens of this project too.", otherCount, others, null, null));
        }
        Map<String, String> missingFields = new TreeMap<String, String>();
        List<IssueTypeRef> missingTypes = new ArrayList<IssueTypeRef>();
        Map<Long, Set<String>> createFields = new TreeMap<Long, Set<String>>();
        for (Map.Entry<IssueType, FieldScreen> entry : newCreate.entrySet()) {
            IssueType type = entry.getKey();
            Map<String, String> missing;
            try {
                missing = session.missing(project, type, entry.getValue());
                createFields.put(entry.getValue().getId(), session.fieldsOf(entry.getValue()));
            } catch (RuntimeException e) {
                throw new Failure(ErrorCode.JIRA_ERROR, "The field configuration or the CREATE screen '" + entry.getValue().getName() + "' of issue type '"
                        + type.getName() + "' in project " + project.getKey() + " cannot be read, so the required fields cannot be checked: " + e.getMessage());
            }
            if (missing.isEmpty()) continue;
            missingFields.putAll(missing);
            missingTypes.add(new IssueTypeRef(type.getId(), type.getName()));
        }
        if (!missingTypes.isEmpty()) {
            List<FieldRef> fieldRefs = new ArrayList<FieldRef>();
            StringBuilder names = new StringBuilder(), typeNames = new StringBuilder();
            for (Map.Entry<String, String> field : missingFields.entrySet()) {
                fieldRefs.add(new FieldRef(field.getKey(), field.getValue()));
                if (names.length() > 0) names.append(", ");
                names.append(field.getValue()).append(" (").append(field.getKey()).append(")");
            }
            for (IssueTypeRef type : missingTypes) {
                if (typeNames.length() > 0) typeNames.append(", ");
                typeNames.append(type.getName());
            }
            warnings.add(new Warning("REQUIRED_FIELDS_MISSING_ON_CREATE", "For issue type(s) " + typeNames + " of project " + project.getKey()
                    + " the new CREATE screen lacks fields that are required in their field configuration: " + names
                    + ". Creating such issues will fail until the fields are put on the screen. The warning does not block the change; the user decides.",
                    null, null, missingTypes, fieldRefs));
        }

        int changing = 0;
        for (TypePlan view : views) if (view.getChange() == Change.CHANGE) changing++;
        String message = "Project " + project.getKey() + " is switched from Issue Type Screen Scheme " + current.getId() + " '" + current.getName()
                + "' to " + target.getId() + " '" + target.getName() + "': the screens of " + changing + " of " + views.size()
                + " issue type(s) change; no scheme, row or screen is edited and no issue is migrated. Tell the user the consequences, "
                + "then apply through start_operation after confirmation.";
        AssignIssueTypeScreenSchemeResponse response = new AssignIssueTypeScreenSchemeResponse(Status.WOULD_CHANGE, true, projectRef,
                currentInfo, targetInfo, views, LIMITATIONS, warnings, null, null, null, message, null, null, null);

        // 3. The fingerprint: the association, both schemes with their rows, every involved Screen Scheme's content, the issue
        // types, the projects of both schemes and the fields of the new CREATE screens the warning depends on.
        Map<Long, Object> schemeState = new TreeMap<Long, Object>();
        for (Map<String, FieldScreenScheme> table : Arrays.asList(before, after)) {
            for (FieldScreenScheme scheme : table.values()) {
                if (!schemeState.containsKey(scheme.getId())) schemeState.put(scheme.getId(), schemeContent(scheme));
            }
        }
        String fingerprint = codec.fingerprint(Arrays.asList(project.getId(), project.getKey(), project.getName(), project.isArchived(),
                topState(current, before), topState(target, after), schemeState, typeState,
                projectState(currentUsers), projectState(targetUsers), createFields));
        return new IssueTypeScreenSchemeAssignmentPlan(project, current, target, response, fingerprint);
    }

    /** An effective screen of an operation: through an explicit item or the DEFAULT one. */
    private static final class Resolved {
        final FieldScreen screen;
        final boolean viaDefault;
        Resolved(FieldScreen screen, boolean viaDefault) { this.screen = screen; this.viaDefault = viaDefault; }
        Effective view() { return screen == null ? null : new Effective(screen.getId(), screen.getName(), viaDefault); }
    }

    private static Resolved resolve(FieldScreenScheme scheme, String operation) {
        Long operationId = OperationScreenPlan.operation(operation).getId();
        FieldScreenSchemeItem fallback = null;
        for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
            if (item.getIssueOperation() == null) fallback = item;
            else if (operationId.equals(item.getIssueOperation().getId())) return new Resolved(item.getFieldScreen(), false);
        }
        return fallback == null ? null : new Resolved(fallback.getFieldScreen(), true);
    }

    private static Long id(FieldScreen screen) { return screen == null ? null : screen.getId(); }

    private static FieldScreenScheme effective(Map<String, FieldScreenScheme> table, String typeId) {
        return table.containsKey(typeId) ? table.get(typeId) : table.get(DEFAULT_ROW);
    }

    /** The stored table of a scheme: issue type id or DEFAULT to Screen Scheme. */
    private static Map<String, FieldScreenScheme> table(IssueTypeScreenScheme itss) {
        Map<String, FieldScreenScheme> table = new TreeMap<String, FieldScreenScheme>();
        for (IssueTypeScreenSchemeEntity entity : itss.getEntities()) {
            FieldScreenScheme scheme = entity.getFieldScreenScheme();
            if (scheme == null) throw new Failure(ErrorCode.JIRA_ERROR, "A row of Issue Type Screen Scheme '" + itss.getName() + "' has no Screen Scheme.");
            table.put(entity.getIssueTypeId() == null ? DEFAULT_ROW : entity.getIssueTypeId(), scheme);
        }
        return table;
    }

    private IssueTypeScreenScheme resolveTarget(String text) {
        if (DEFAULT_TOKEN.equals(text)) {
            for (IssueTypeScreenScheme candidate : tops.getIssueTypeScreenSchemes()) if (candidate.isDefault()) return candidate;
            throw new Failure(ErrorCode.ITSS_NOT_FOUND, "Jira has no default Issue Type Screen Scheme.");
        }
        Long id = positiveId(text);
        if (id != null) {
            IssueTypeScreenScheme byId = tops.getIssueTypeScreenScheme(id);
            if (byId != null) return byId;
        }
        List<IssueTypeScreenScheme> found = new ArrayList<IssueTypeScreenScheme>();
        for (IssueTypeScreenScheme candidate : tops.getIssueTypeScreenSchemes()) {
            if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(text)) found.add(candidate);
        }
        if (found.size() > 1) {
            List<Long> ids = new ArrayList<Long>();
            for (IssueTypeScreenScheme candidate : found) ids.add(candidate.getId());
            Collections.sort(ids);
            throw new Failure(ErrorCode.ITSS_AMBIGUOUS, "Several Issue Type Screen Schemes are named '" + text + "' (ids " + ids + "). Use the exact id.");
        }
        if (found.isEmpty()) throw new Failure(ErrorCode.ITSS_NOT_FOUND,
                "No Issue Type Screen Scheme has the id or exact name '" + text + "'. Read get_project_screens to find the id of the scheme a project uses; "
                        + "DEFAULT is Jira's default scheme.");
        return found.get(0);
    }

    private static Long positiveId(String text) {
        if (text.isEmpty() || text.length() > 18) return null;
        for (int i = 0; i < text.length(); i++) if (!Character.isDigit(text.charAt(i))) return null;
        long id = Long.parseLong(text);
        return id > 0 ? Long.valueOf(id) : null;
    }

    /** All projects Jira associates with the scheme, archived ones included, sorted by key; an unreadable one makes the counts inexact. */
    private List<Project> linkedProjects(IssueTypeScreenScheme itss) {
        Set<Long> ids = new TreeSet<Long>();
        Collection<GenericValue> values = tops.getProjects(itss);
        if (values != null) {
            for (GenericValue value : values) {
                Long id = value == null ? null : value.getLong("id");
                if (id == null) throw new Failure(ErrorCode.JIRA_ERROR, "Jira lists a project of Issue Type Screen Scheme " + itss.getId()
                        + " without an id; the project counts cannot be exact.");
                ids.add(id);
            }
        }
        List<Project> result = new ArrayList<Project>();
        for (Long id : ids) {
            Project project = projectManager.getProjectObj(id);
            if (project == null) throw new Failure(ErrorCode.JIRA_ERROR, "Jira lists project " + id + " as a user of Issue Type Screen Scheme "
                    + itss.getId() + " but cannot read it; the project counts cannot be exact.");
            result.add(project);
        }
        Collections.sort(result, new Comparator<Project>() {
            @Override public int compare(Project a, Project b) {
                int byKey = a.getKey().compareTo(b.getKey());
                return byKey != 0 ? byKey : a.getId().compareTo(b.getId());
            }
        });
        return result;
    }

    private static List<Object> topState(IssueTypeScreenScheme itss, Map<String, FieldScreenScheme> table) {
        Map<String, Long> ids = new TreeMap<String, Long>();
        for (Map.Entry<String, FieldScreenScheme> entry : table.entrySet()) ids.put(entry.getKey(), entry.getValue().getId());
        return Arrays.<Object>asList(itss.getId(), itss.getName(), itss.getDescription(), itss.isDefault(), ids);
    }

    private static List<List<Object>> projectState(List<Project> users) {
        List<List<Object>> state = new ArrayList<List<Object>>();
        for (Project user : users) state.add(Arrays.<Object>asList(user.getId(), user.getKey(), user.getName(), user.isArchived()));
        return state;
    }

    private static List<Object> schemeContent(FieldScreenScheme scheme) {
        Map<String, List<Object>> items = new TreeMap<String, List<Object>>();
        for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
            String key = OperationScreenPlan.operationKey(item);
            if (items.containsKey(key)) throw new Failure(ErrorCode.JIRA_ERROR, "Screen Scheme '" + scheme.getName() + "' has duplicate operation mappings.");
            items.put(key, item.getFieldScreen() == null ? null : Arrays.<Object>asList(item.getFieldScreen().getId(), item.getFieldScreen().getName()));
        }
        return Arrays.<Object>asList(scheme.getName(), scheme.getDescription(), items);
    }
}
