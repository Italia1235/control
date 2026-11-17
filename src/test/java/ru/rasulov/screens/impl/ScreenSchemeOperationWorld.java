package ru.rasulov.screens.impl;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenFactory;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
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

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In-memory Jira model for Screen Scheme row edits. Screens 1..9 (fields: 1 summary+description, 2 summary+description+labels,
 * 3 summary, 4 summary+description, 5 description only, 6 summary+description+customfield_100, 7 summary, 8-9 summary+description);
 * Screen Schemes 30 "Shared SS" (DEFAULT 1, CREATE 2, EDIT 3) and 31 "Other SS" (DEFAULT 4); Issue Type Screen Schemes 20
 * "Shared ITSS" (DEFAULT 31, bug 30), 22 "Default-SS ITSS" (DEFAULT 30, epic 31), 1 Jira's default (DEFAULT 30) and 21 "Own ITSS"
 * (DEFAULT 31). Projects: ALPHA(101, ITSS 20: task, bug), BETA(102, ITSS 22: task, story), archived GAMMA(103, ITSS 20: bug, other),
 * OWN(104, ITSS 21: task), DEFPR(105, ITSS 1: task, bug), EPICPR(106, ITSS 22: epic only: linked but no type uses scheme 30).
 * So scheme 30 is used by 4 of its 5 linked projects, through 3 Issue Type Screen Schemes. Field configurations: ALPHA and BETA use
 * layout 11 (summary and customfield_100 required; the custom field's context covers BETA only); the others use the default layout
 * (summary, project, issuetype and the non-orderable "fakeField" required). The manager hands out the cached item: a FAILED update
 * leaves the new screen in the cached item until {@code refresh()} (the committed rows are the truth).
 */
final class ScreenSchemeOperationWorld {
    final FieldScreenFactory factory = mock(FieldScreenFactory.class);
    final FieldScreenSchemeManager fields = mock(FieldScreenSchemeManager.class);
    final FieldScreenManager screenManager = mock(FieldScreenManager.class);
    final IssueTypeScreenSchemeManager tops = mock(IssueTypeScreenSchemeManager.class);
    final IssueTypeManager issueTypeManager = mock(IssueTypeManager.class);
    final ProjectManager projectManager = mock(ProjectManager.class);
    final FieldLayoutManager layouts = mock(FieldLayoutManager.class);
    final FieldManager fieldManager = mock(FieldManager.class);
    final ActionPlanCodec codec = new ActionPlanCodec();
    final Map<Long, FieldScreen> screens = new LinkedHashMap<Long, FieldScreen>();
    final Map<Long, List<String>> screenFields = new HashMap<Long, List<String>>();
    final Map<Long, FieldScreenScheme> fieldCatalog = new LinkedHashMap<Long, FieldScreenScheme>();
    final Map<Long, IssueTypeScreenScheme> topCatalog = new LinkedHashMap<Long, IssueTypeScreenScheme>();
    final Map<Long, Set<Long>> usersByTop = new HashMap<Long, Set<Long>>();
    final Map<Long, Project> projectCatalog = new LinkedHashMap<Long, Project>();
    final Map<Long, List<IssueType>> typesByProject = new HashMap<Long, List<IssueType>>();
    final Map<Long, Long> layoutOfProject = new HashMap<Long, Long>();
    final Map<Long, Map<String, Boolean>> requiredByLayout = new HashMap<Long, Map<String, Boolean>>();
    final Set<String> orderable = new HashSet<String>();
    final List<IssueType> typeCatalog = new ArrayList<IssueType>();
    final List<String> writes = new ArrayList<String>();
    final AtomicLong sequence = new AtomicLong(1000);
    final IssueType task, bug, story, epic, other;
    final FieldScreenScheme shared, otherScheme;
    final IssueTypeScreenScheme sharedTop, defaultSsTop, jiraDefault, own;
    /** 1-based number of the call (per kind, over the whole test) that throws; 0 disables. */
    int failUpdateNumber, failCreateNumber, failRemoveNumber, failAfterUpdateNumber;
    /** The n-th update returns normally but is not persisted (Jira drift without an error). */
    int swallowUpdateNumber;
    boolean failReadBack;
    /** Runs after every successful write (a concurrent editor). */
    Runnable afterWrite;
    private int updateCalls, createCalls, removeCalls;
    private boolean written;
    private final Map<FieldScreenSchemeItem, FieldScreen> committed = new IdentityHashMap<FieldScreenSchemeItem, FieldScreen>();
    /** Items that exist in the database (removed ones leave this set). */
    private final Set<FieldScreenSchemeItem> stored = Collections.newSetFromMap(new IdentityHashMap<FieldScreenSchemeItem, Boolean>());

    ScreenSchemeOperationWorld() {
        task = type("1", "Task"); bug = type("2", "Bug"); story = type("3", "Story"); epic = type("4", "Epic"); other = type("5", "Other");
        typeCatalog.addAll(Arrays.asList(task, bug, story, epic, other));
        when(issueTypeManager.getIssueTypes()).thenAnswer(call -> typeCatalog);
        screen(1, "Screen 1", "summary", "description"); screen(2, "Screen 2", "summary", "description", "labels");
        screen(3, "Screen 3", "summary"); screen(4, "Screen 4", "summary", "description");
        screen(5, "Screen 5", "description"); screen(6, "Screen 6", "summary", "description", "customfield_100");
        screen(7, "Screen 7", "summary"); screen(8, "Screen 8", "summary", "description"); screen(9, "Screen 9", "summary", "description");
        when(screenManager.getFieldScreen(anyLong())).thenAnswer(call -> screens.get(call.getArgument(0)));
        when(screenManager.getFieldScreens()).thenAnswer(call -> screens.values());
        shared = scheme(30L, "Shared SS", 1L); explicit(shared, IssueOperations.CREATE_ISSUE_OPERATION, 2L); explicit(shared, IssueOperations.EDIT_ISSUE_OPERATION, 3L);
        otherScheme = scheme(31L, "Other SS", 4L);
        when(fields.getFieldScreenScheme(anyLong())).thenAnswer(call -> {
            if (failReadBack && written) throw new IllegalStateException("read failed");
            return fieldCatalog.get(call.getArgument(0));
        });
        when(fields.getFieldScreenSchemes()).thenAnswer(call -> fieldCatalog.values());
        sharedTop = top(20L, "Shared ITSS", false, otherScheme); row(sharedTop, bug.getId(), shared);
        defaultSsTop = top(22L, "Default-SS ITSS", false, shared); row(defaultSsTop, epic.getId(), otherScheme);
        jiraDefault = top(1L, "Default Issue Type Screen Scheme", true, shared);
        own = top(21L, "Own ITSS", false, otherScheme);
        project(101, "ALPHA", false, sharedTop, 11L, task, bug);
        project(102, "BETA", false, defaultSsTop, 11L, task, story);
        project(103, "GAMMA", true, sharedTop, null, bug, other);
        project(104, "OWN", false, own, null, task);
        project(105, "DEFPR", false, jiraDefault, null, task, bug);
        project(106, "EPICPR", false, defaultSsTop, null, epic);
        when(projectManager.getProjectObj(anyLong())).thenAnswer(call -> projectCatalog.get(call.getArgument(0)));
        when(tops.getIssueTypeScreenSchemes()).thenAnswer(call -> topCatalog.values());
        when(tops.getProjects(any(IssueTypeScreenScheme.class))).thenAnswer(call -> {
            IssueTypeScreenScheme value = call.getArgument(0);
            List<GenericValue> values = new ArrayList<GenericValue>();
            for (Long id : users(value.getId())) {
                GenericValue gv = mock(GenericValue.class); when(gv.getLong("id")).thenReturn(id); values.add(gv);
            }
            return values;
        });
        wireFieldRequirements();
        when(factory.createFieldScreenSchemeItem()).thenAnswer(call -> OperationScreenWorld.item(null, null, null));
        doAnswer(call -> {
            FieldScreenSchemeItem value = call.getArgument(0);
            if (++updateCalls == failUpdateNumber) throw new IllegalStateException("Jira cannot update the row");
            if (updateCalls == swallowUpdateNumber) { value.setFieldScreen(committed.get(value)); return null; }
            committed.put(value, value.getFieldScreen());
            writes.add("update " + value.getFieldScreenScheme().getId() + " " + name(value));
            written = true;
            if (updateCalls == failAfterUpdateNumber) throw new IllegalStateException("late error after the update");
            if (afterWrite != null) afterWrite.run();
            return null;
        }).when(fields).updateFieldScreenSchemeItem(any());
        doAnswer(call -> {
            FieldScreenSchemeItem value = call.getArgument(0);
            if (++createCalls == failCreateNumber) throw new IllegalStateException("Jira cannot store the row");
            value.getFieldScreenScheme().getFieldScreenSchemeItems().add(value);
            stored.add(value); committed.put(value, value.getFieldScreen());
            writes.add("create " + value.getFieldScreenScheme().getId() + " " + name(value));
            written = true;
            if (afterWrite != null) afterWrite.run();
            return null;
        }).when(fields).createFieldScreenSchemeItem(any());
        doAnswer(call -> {
            FieldScreenSchemeItem value = call.getArgument(0);
            if (++removeCalls == failRemoveNumber) throw new IllegalStateException("Jira cannot remove the row");
            value.getFieldScreenScheme().getFieldScreenSchemeItems().remove(value);
            stored.remove(value); committed.remove(value);
            writes.add("remove " + value.getFieldScreenScheme().getId() + " " + name(value));
            written = true;
            if (afterWrite != null) afterWrite.run();
            return null;
        }).when(fields).removeFieldScreenSchemeItem(any());
        doAnswer(call -> {
            for (Map.Entry<FieldScreenSchemeItem, FieldScreen> entry : committed.entrySet()) entry.getKey().setFieldScreen(entry.getValue());
            return null;
        }).when(fields).refresh();
    }

    /** How the operation shows in {@link #writes} and {@link #table}. */
    static String name(FieldScreenSchemeItem item) {
        if (item.getIssueOperation() == null) return "DEFAULT";
        long id = item.getIssueOperation().getId();
        if (id == IssueOperations.CREATE_ISSUE_OPERATION.getId()) return "CREATE";
        if (id == IssueOperations.EDIT_ISSUE_OPERATION.getId()) return "EDIT";
        if (id == IssueOperations.VIEW_ISSUE_OPERATION.getId()) return "VIEW";
        return String.valueOf(id);
    }

    /** The persisted explicit rows of a Screen Scheme: operation to Screen id. */
    Map<String, Long> table(FieldScreenScheme scheme) {
        Map<String, Long> table = new TreeMap<String, Long>();
        for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
            FieldScreen value = committed.containsKey(item) ? committed.get(item) : item.getFieldScreen();
            table.put(name(item), value.getId());
        }
        return table;
    }

    Map<String, Long> table(long schemeId) { return table(fieldCatalog.get(schemeId)); }

    /** Registers a screen with the given fields on one tab; the field list stays editable through {@link #screenFields}. */
    FieldScreen screen(long id, String name, String... fieldIds) {
        FieldScreen value = OperationScreenWorld.screen(id, name);
        screenFields.put(id, new ArrayList<String>(Arrays.asList(fieldIds)));
        FieldScreenTab tab = mock(FieldScreenTab.class);
        when(tab.getId()).thenReturn(id * 10); when(tab.getName()).thenReturn("Tab"); when(tab.getPosition()).thenReturn(0);
        when(tab.getFieldScreenLayoutItems()).thenAnswer(call -> {
            List<FieldScreenLayoutItem> items = new ArrayList<FieldScreenLayoutItem>();
            int position = 0;
            for (String fieldId : screenFields.get(id)) {
                FieldScreenLayoutItem item = mock(FieldScreenLayoutItem.class);
                when(item.getFieldId()).thenReturn(fieldId); when(item.getPosition()).thenReturn(position++);
                items.add(item);
            }
            return items;
        });
        when(value.getTabs()).thenAnswer(call -> Collections.singletonList(tab));
        screens.put(id, value);
        return value;
    }

    /** Project ids Jira reports as users of a top scheme. */
    Set<Long> users(long topId) {
        Set<Long> set = usersByTop.get(topId);
        if (set == null) { set = new TreeSet<Long>(); usersByTop.put(topId, set); }
        return set;
    }

    /** A new project on {@code top} with the field layout {@code layout} (null: Jira's default configuration). */
    Project project(long id, String key, boolean archived, IssueTypeScreenScheme top, Long layout, IssueType... projectTypes) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id); when(project.getKey()).thenReturn(key); when(project.getName()).thenReturn(key + " name");
        when(project.isArchived()).thenReturn(archived);
        typesByProject.put(id, new ArrayList<IssueType>(Arrays.asList(projectTypes)));
        when(project.getIssueTypes()).thenAnswer(call -> typesByProject.get(id));
        projectCatalog.put(id, project); users(top.getId()).add(id);
        if (layout != null) layoutOfProject.put(id, layout);
        return project;
    }

    FieldScreenScheme scheme(Long id, String name, long defaultScreen) {
        FieldScreenScheme value = OperationScreenWorld.fieldScheme(id, name);
        FieldScreenSchemeItem item = OperationScreenWorld.item(value, null, screens.get(defaultScreen));
        value.getFieldScreenSchemeItems().add(item); stored.add(item); committed.put(item, item.getFieldScreen());
        fieldCatalog.put(id, value);
        return value;
    }

    FieldScreenSchemeItem explicit(FieldScreenScheme value, ScreenableIssueOperation operation, long screen) {
        FieldScreenSchemeItem item = OperationScreenWorld.item(value, operation, screens.get(screen));
        value.getFieldScreenSchemeItems().add(item); stored.add(item); committed.put(item, item.getFieldScreen());
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

    /** The item of an operation in a scheme ("DEFAULT", "CREATE", ...), or null. */
    FieldScreenSchemeItem item(FieldScreenScheme scheme, String operation) {
        for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) if (name(item).equals(operation)) return item;
        return null;
    }

    /** A change made behind Control's back (UI, REST, another plugin): the row of the item now holds the screen, persisted. */
    void persist(FieldScreenSchemeItem item, long screenId) {
        item.setFieldScreen(screens.get(screenId)); committed.put(item, item.getFieldScreen());
    }

    /** A concurrent removal of a row. */
    void drop(FieldScreenScheme scheme, String operation) {
        FieldScreenSchemeItem item = item(scheme, operation);
        scheme.getFieldScreenSchemeItems().remove(item); stored.remove(item); committed.remove(item);
    }

    /** Required flags per field layout (0 is Jira's default field configuration) and the orderable fields. */
    private void wireFieldRequirements() {
        orderable.addAll(Arrays.asList("summary", "description", "labels", "customfield_100", "project", "issuetype"));
        requiredByLayout.put(0L, required("summary", "project", "issuetype", "fakeField"));
        requiredByLayout.put(11L, required("summary", "project", "issuetype", "customfield_100"));
        requiredByLayout.get(0L).put("description", false); requiredByLayout.get(11L).put("description", false);
        when(fieldManager.isOrderableField(any(String.class))).thenAnswer(call -> orderable.contains((String) call.getArgument(0)));
        when(fieldManager.isCustomFieldId(any(String.class))).thenAnswer(call -> ((String) call.getArgument(0)).startsWith("customfield_"));
        CustomField region = mock(CustomField.class);
        when(region.getId()).thenReturn("customfield_100"); when(region.getName()).thenReturn("Region");
        when(region.isInScope(any(Project.class), any())).thenAnswer(call -> "BETA".equals(((Project) call.getArgument(0)).getKey()));
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

    static IssueType type(String id, String name) { return OperationScreenWorld.type(id, name); }
}
