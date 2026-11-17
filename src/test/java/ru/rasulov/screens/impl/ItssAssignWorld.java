package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.issue.operation.ScreenableIssueOperation;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.lookup.ProjectResolver;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In-memory Jira model for switching a project to another Issue Type Screen Scheme (ITSS). Issue types Task(1), Bug(2),
 * Story(3). Screens 1..9 (fields: 1 summary+description, 2 summary+description+labels, 3 summary, 4 summary+description,
 * 5 description only, 6 summary+description+customfield_100, 7-9 summary). Screen Schemes: 30 "Default SS" (DEFAULT 1), 31 "Rich SS"
 * (DEFAULT 1, CREATE 2, EDIT 3, VIEW 4), 32 "Lean SS" (DEFAULT 5), 33 "Lean edit SS" (DEFAULT 5, EDIT 3).
 * ITSS: 1 Jira's default (DEFAULT 30; used by DEFPR), 20 "Shared ITSS" (DEFAULT 31, bug 32; used by ALPHA and archived GAMMA),
 * 21 "Own ITSS" (DEFAULT 30; used by OWN), 22 "Spare ITSS" (DEFAULT 31, bug 30; unused), 23 "Twin ITSS" and 24 "twin itss"
 * (DEFAULT 30), 25 "DEFAULT" (DEFAULT 31; a name equal to the token), 26 "Lean ITSS" (DEFAULT 32; used by LEAN),
 * 27 "Bug-lean ITSS" (DEFAULT 30, bug 32), 28 "Lean-edit ITSS" (DEFAULT 33).
 * Projects: ALPHA(101, ITSS 20: task, bug), GAMMA(103, archived, ITSS 20: bug), OWN(104, ITSS 21: task, bug, field configuration 11:
 * summary and customfield_100 required, its context covers OWN), DEFPR(105, ITSS 1: task, bug), LEAN(107, ITSS 26: task, bug).
 * Everything else uses the default field configuration (summary required). Like Jira, the manager hands out the cached
 * association of a project after a write until {@code refresh()}; {@code usersByTop} is the association table.
 */
final class ItssAssignWorld {
    final IssueTypeScreenSchemeManager tops = mock(IssueTypeScreenSchemeManager.class);
    final ProjectManager projectManager = mock(ProjectManager.class);
    final FieldLayoutManager layouts = mock(FieldLayoutManager.class);
    final FieldManager fieldManager = mock(FieldManager.class);
    final ProjectResolver projects = new ProjectResolver(projectManager);
    final ScreenSchemeCreateRequirements requirements = new ScreenSchemeCreateRequirements(layouts, fieldManager);
    final ActionPlanCodec codec = new ActionPlanCodec();
    final Map<Long, FieldScreen> screens = new LinkedHashMap<Long, FieldScreen>();
    final Map<Long, List<String>> screenFields = new HashMap<Long, List<String>>();
    final Map<Long, FieldScreenScheme> fieldCatalog = new LinkedHashMap<Long, FieldScreenScheme>();
    final Map<Long, IssueTypeScreenScheme> topCatalog = new LinkedHashMap<Long, IssueTypeScreenScheme>();
    final Map<Long, Set<Long>> usersByTop = new HashMap<Long, Set<Long>>();
    /** The scheme each project is associated with. */
    final Map<Long, Long> associated = new HashMap<Long, Long>();
    final Map<Long, Project> projectCatalog = new LinkedHashMap<Long, Project>();
    final Map<Long, List<IssueType>> typesByProject = new HashMap<Long, List<IssueType>>();
    final Map<Long, String> projectNames = new HashMap<Long, String>();
    final Set<Long> archived = new HashSet<Long>();
    final Map<Long, Long> layoutOfProject = new HashMap<Long, Long>();
    final Map<Long, Map<String, Boolean>> requiredByLayout = new HashMap<Long, Map<String, Boolean>>();
    final Set<String> orderable = new HashSet<String>();
    final List<String> writes = new ArrayList<String>();
    final AtomicLong sequence = new AtomicLong(1000);
    final IssueType task, bug, story;
    final FieldScreenScheme defaultSs, richSs, leanSs, leanEditSs;
    final IssueTypeScreenScheme jiraDefault, shared, own, spare, twinA, twinB, tokenNamed, leanTop, bugLean, leanEdit;
    final Project alpha, gamma, ownPr, defPr, lean;
    /** The association write throws before any effect. */
    boolean failAssociation;
    /** The association write takes effect and then throws. */
    boolean associateThenFail;
    /** The association write returns normally but changes nothing. */
    boolean swallowAssociation;
    /** The write only adds the project to the target scheme's project list; the project's own link stays on the old scheme. */
    boolean listUnderTargetOnly;
    /** The write also leaves the project listed under its previous scheme. */
    boolean keepInPrevious;
    /** The write associates the project with this (third) scheme instead. */
    Long redirectTo;
    /** Reading the association fails after a write was attempted. */
    boolean failAssociationReadBack;
    /** {@code refresh()} fails. */
    boolean failRefresh;
    /** Reading the fields of the screens fails. */
    boolean failScreenFields;
    /** Reading the field configuration fails. */
    boolean failFieldLayout;
    int associationCalls, refreshCalls;
    private boolean writeAttempted;
    private final Map<Long, Long> cachedAssociation = new HashMap<Long, Long>();

    ItssAssignWorld() {
        task = OperationScreenWorld.type("1", "Task"); bug = OperationScreenWorld.type("2", "Bug"); story = OperationScreenWorld.type("3", "Story");
        screen(1, "Screen 1", "summary", "description"); screen(2, "Screen 2", "summary", "description", "labels");
        screen(3, "Screen 3", "summary"); screen(4, "Screen 4", "summary", "description"); screen(5, "Screen 5", "description");
        screen(6, "Screen 6", "summary", "description", "customfield_100"); screen(7, "Screen 7", "summary");
        screen(8, "Screen 8", "summary"); screen(9, "Screen 9", "summary");
        defaultSs = scheme(30L, "Default SS", 1); richSs = scheme(31L, "Rich SS", 1);
        explicit(richSs, IssueOperations.CREATE_ISSUE_OPERATION, 2); explicit(richSs, IssueOperations.EDIT_ISSUE_OPERATION, 3);
        explicit(richSs, IssueOperations.VIEW_ISSUE_OPERATION, 4);
        leanSs = scheme(32L, "Lean SS", 5); leanEditSs = scheme(33L, "Lean edit SS", 5);
        explicit(leanEditSs, IssueOperations.EDIT_ISSUE_OPERATION, 3);
        jiraDefault = top(1L, "Default Issue Type Screen Scheme", true, defaultSs);
        shared = top(20L, "Shared ITSS", false, richSs); row(shared, bug.getId(), leanSs);
        own = top(21L, "Own ITSS", false, defaultSs);
        spare = top(22L, "Spare ITSS", false, richSs); row(spare, bug.getId(), defaultSs);
        twinA = top(23L, "Twin ITSS", false, defaultSs); twinB = top(24L, "twin itss", false, defaultSs);
        tokenNamed = top(25L, "DEFAULT", false, richSs);
        leanTop = top(26L, "Lean ITSS", false, leanSs);
        bugLean = top(27L, "Bug-lean ITSS", false, defaultSs); row(bugLean, bug.getId(), leanSs);
        leanEdit = top(28L, "Lean-edit ITSS", false, leanEditSs);
        alpha = project(101, "ALPHA", shared, null, task, bug);
        gamma = project(103, "GAMMA", shared, null, bug); archived.add(103L);
        ownPr = project(104, "OWN", own, 11L, task, bug);
        defPr = project(105, "DEFPR", jiraDefault, null, task, bug);
        lean = project(107, "LEAN", leanTop, null, task, bug);
        when(projectManager.getProjectObj(anyLong())).thenAnswer(call -> projectCatalog.get(call.getArgument(0)));
        when(projectManager.getProjectByCurrentKeyIgnoreCase(any())).thenAnswer(call -> {
            for (Project candidate : projectCatalog.values()) if (candidate.getKey().equalsIgnoreCase(call.getArgument(0))) return candidate;
            return null;
        });
        when(projectManager.getProjects()).thenAnswer(call -> new ArrayList<Project>(projectCatalog.values()));
        when(tops.getIssueTypeScreenSchemes()).thenAnswer(call -> topCatalog.values());
        when(tops.getIssueTypeScreenScheme(any(Long.class))).thenAnswer(call -> topCatalog.get(call.getArgument(0)));
        when(tops.getIssueTypeScreenScheme(any(Project.class))).thenAnswer(call -> {
            if (failAssociationReadBack && writeAttempted) throw new IllegalStateException("read failed");
            Long id = ((Project) call.getArgument(0)).getId();
            return topCatalog.get(cachedAssociation.containsKey(id) ? cachedAssociation.get(id) : associated.get(id));
        });
        when(tops.getProjects(any(IssueTypeScreenScheme.class))).thenAnswer(call -> {
            IssueTypeScreenScheme value = call.getArgument(0);
            List<GenericValue> values = new ArrayList<GenericValue>();
            for (Long id : users(value.getId())) {
                GenericValue gv = mock(GenericValue.class); when(gv.getLong("id")).thenReturn(id); values.add(gv);
            }
            return values;
        });
        doAnswer(call -> {
            Project project = call.getArgument(0);
            IssueTypeScreenScheme target = call.getArgument(1);
            associationCalls++; writeAttempted = true;
            if (failAssociation) throw new IllegalStateException("Jira cannot switch the project");
            if (swallowAssociation) return null;
            if (listUnderTargetOnly) {
                users(target.getId()).add(project.getId());
                writes.add("list " + project.getKey() + " " + target.getId());
                return null;
            }
            Long previous = associated.get(project.getId());
            Long to = redirectTo != null ? redirectTo : target.getId();
            if (!keepInPrevious) users(previous).remove(project.getId());
            users(to).add(project.getId()); associated.put(project.getId(), to);
            cachedAssociation.put(project.getId(), previous);
            writes.add("associate " + project.getKey() + " " + to);
            if (associateThenFail) throw new IllegalStateException("late error after the switch");
            return null;
        }).when(tops).addSchemeAssociation(any(Project.class), any(IssueTypeScreenScheme.class));
        doAnswer(call -> {
            refreshCalls++;
            if (failRefresh) throw new IllegalStateException("cache refresh failed");
            cachedAssociation.clear();
            return null;
        }).when(tops).refresh();
        wireFieldRequirements();
    }

    /** Project ids Jira reports as users of a top scheme. */
    Set<Long> users(long topId) {
        Set<Long> set = usersByTop.get(topId);
        if (set == null) { set = new TreeSet<Long>(); usersByTop.put(topId, set); }
        return set;
    }

    FieldScreen screen(long id, String name, String... fieldIds) {
        FieldScreen value = OperationScreenWorld.screen(id, name);
        FieldScreenTab tab = mock(FieldScreenTab.class);
        screenFields.put(id, new ArrayList<String>(Arrays.asList(fieldIds)));
        when(tab.getFieldScreenLayoutItems()).thenAnswer(call -> {
            if (failScreenFields) throw new IllegalStateException("cannot read the fields of the screen");
            List<FieldScreenLayoutItem> items = new ArrayList<FieldScreenLayoutItem>();
            for (String fieldId : screenFields.get(id)) {
                FieldScreenLayoutItem item = mock(FieldScreenLayoutItem.class); when(item.getFieldId()).thenReturn(fieldId); items.add(item);
            }
            return items;
        });
        when(value.getTabs()).thenAnswer(call -> Collections.singletonList(tab));
        screens.put(id, value);
        return value;
    }

    /** A new project on {@code top}; {@code layout} null means Jira's default field configuration. */
    Project project(long id, String key, IssueTypeScreenScheme top, Long layout, IssueType... projectTypes) {
        Project project = mock(Project.class);
        projectNames.put(id, key + " name");
        when(project.getId()).thenReturn(id); when(project.getKey()).thenReturn(key);
        when(project.getName()).thenAnswer(call -> projectNames.get(id));
        when(project.isArchived()).thenAnswer(call -> archived.contains(id));
        typesByProject.put(id, new ArrayList<IssueType>(Arrays.asList(projectTypes)));
        when(project.getIssueTypes()).thenAnswer(call -> typesByProject.get(id));
        projectCatalog.put(id, project); users(top.getId()).add(id); associated.put(id, top.getId());
        if (layout != null) layoutOfProject.put(id, layout);
        return project;
    }

    FieldScreenScheme scheme(Long id, String name, long defaultScreen) {
        FieldScreenScheme value = OperationScreenWorld.fieldScheme(id, name);
        value.getFieldScreenSchemeItems().add(OperationScreenWorld.item(value, null, screens.get(defaultScreen)));
        fieldCatalog.put(id, value);
        return value;
    }

    FieldScreenSchemeItem explicit(FieldScreenScheme value, ScreenableIssueOperation operation, long screen) {
        FieldScreenSchemeItem item = OperationScreenWorld.item(value, operation, screens.get(screen));
        value.getFieldScreenSchemeItems().add(item);
        return item;
    }

    IssueTypeScreenScheme top(Long id, String name, boolean isDefault, FieldScreenScheme defaultRow) {
        IssueTypeScreenScheme value = OperationScreenWorld.topScheme(id, name);
        when(value.isDefault()).thenReturn(isDefault);
        value.getEntities().add(OperationScreenWorld.entity(value, null, defaultRow));
        topCatalog.put(id, value);
        return value;
    }

    void row(IssueTypeScreenScheme top, String typeId, FieldScreenScheme fieldScheme) {
        top.getEntities().add(OperationScreenWorld.entity(top, typeId, fieldScheme));
    }

    /** Required flags per field layout (0 is Jira's default field configuration) and the orderable fields. */
    private void wireFieldRequirements() {
        orderable.addAll(Arrays.asList("summary", "description", "labels", "customfield_100", "project", "issuetype"));
        requiredByLayout.put(0L, required("summary", "project", "issuetype"));
        requiredByLayout.put(11L, required("summary", "project", "issuetype", "customfield_100"));
        when(fieldManager.isOrderableField(any(String.class))).thenAnswer(call -> orderable.contains((String) call.getArgument(0)));
        when(fieldManager.isCustomFieldId(any(String.class))).thenAnswer(call -> ((String) call.getArgument(0)).startsWith("customfield_"));
        CustomField region = mock(CustomField.class);
        when(region.getId()).thenReturn("customfield_100"); when(region.getName()).thenReturn("Region");
        when(region.isInScope(any(Project.class), any())).thenAnswer(call -> "OWN".equals(((Project) call.getArgument(0)).getKey()));
        when(fieldManager.getCustomField("customfield_100")).thenReturn(region);
        when(layouts.getFieldConfigurationScheme(any(Project.class))).thenAnswer(call -> {
            Long layout = layoutOfProject.get(((Project) call.getArgument(0)).getId());
            if (layout == null) return null;
            FieldConfigurationScheme scheme = mock(FieldConfigurationScheme.class);
            when(scheme.getFieldLayoutId(any())).thenReturn(layout);
            return scheme;
        });
        when(layouts.getFieldLayout()).thenAnswer(call -> layout(0L));
        when(layouts.getFieldLayout(anyLong())).thenAnswer(call -> layout((Long) call.getArgument(0)));
    }

    private static Map<String, Boolean> required(String... ids) {
        Map<String, Boolean> result = new LinkedHashMap<String, Boolean>();
        for (String id : ids) result.put(id, true);
        return result;
    }

    private FieldLayout layout(long id) {
        if (failFieldLayout) throw new IllegalStateException("cannot read the field configuration");
        FieldLayout layout = mock(FieldLayout.class);
        when(layout.getId()).thenReturn(id == 0 ? null : id);
        List<FieldLayoutItem> items = new ArrayList<FieldLayoutItem>();
        for (Map.Entry<String, Boolean> entry : requiredByLayout.get(id).entrySet()) {
            FieldLayoutItem item = mock(FieldLayoutItem.class);
            OrderableField field = "customfield_100".equals(entry.getKey()) ? mock(CustomField.class) : mock(OrderableField.class);
            when(field.getId()).thenReturn(entry.getKey()); when(field.getName()).thenReturn(displayName(entry.getKey()));
            when(item.getOrderableField()).thenReturn(field);
            when(item.isRequired()).thenReturn(entry.getValue()); when(item.isHidden()).thenReturn(false);
            items.add(item);
        }
        when(layout.getFieldLayoutItems()).thenReturn(items);
        return layout;
    }

    static String displayName(String id) {
        switch (id) {
            case "summary": return "Summary";
            case "description": return "Description";
            case "customfield_100": return "Region";
            default: return id;
        }
    }

    /** Rows of a top scheme: type id or DEFAULT to Screen Scheme id. */
    Map<String, Long> table(IssueTypeScreenScheme top) {
        Map<String, Long> table = new TreeMap<String, Long>();
        for (com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity entity : top.getEntities()) {
            table.put(entity.getIssueTypeId() == null ? "DEFAULT" : entity.getIssueTypeId(), entity.getFieldScreenScheme().getId());
        }
        return table;
    }

    /** The item of an operation in a scheme ("DEFAULT", "CREATE", ...), or null. */
    FieldScreenSchemeItem item(FieldScreenScheme scheme, String operation) {
        for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
            String key = item.getIssueOperation() == null ? "DEFAULT"
                    : item.getIssueOperation().getId().equals(IssueOperations.CREATE_ISSUE_OPERATION.getId()) ? "CREATE"
                    : item.getIssueOperation().getId().equals(IssueOperations.EDIT_ISSUE_OPERATION.getId()) ? "EDIT" : "VIEW";
            if (key.equals(operation)) return item;
        }
        return null;
    }
}
