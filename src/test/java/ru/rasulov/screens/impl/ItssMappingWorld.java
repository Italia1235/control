package ru.rasulov.screens.impl;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenFactory;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In-memory Jira model for Issue Type Screen Scheme row edits. Issue types Task, Bug, Story, Epic, Other, Vacation and
 * Orphan (in no project); Screen Schemes 30 "Default SS", 31 "HR SS", 32 "Bug SS", 33 "Alt SS"; Issue Type Screen Scheme
 * 20 "Shared ITSS" (DEFAULT 30, bug 32, vacation 31) used by ALPHA(101: task, bug, vacation), BETA(102: task, story, epic)
 * and archived GAMMA(103: bug, other); Jira's default scheme 1 (DEFAULT 30) used by DEFPR(105) and a private scheme 21 used
 * by OWN(104). Like Jira, the manager hands out the cached scheme object: a FAILED update leaves the new value in the
 * cached entity until {@code refresh()} (the committed rows are the truth). After any write {@code getIssueTypeScreenScheme}
 * keeps returning the pre-write table (a stale cache) until {@code refresh()} is called.
 */
final class ItssMappingWorld {
    final FieldScreenFactory factory = mock(FieldScreenFactory.class);
    final FieldScreenSchemeManager fields = mock(FieldScreenSchemeManager.class);
    final IssueTypeScreenSchemeManager tops = mock(IssueTypeScreenSchemeManager.class);
    final IssueTypeManager issueTypeManager = mock(IssueTypeManager.class);
    final ProjectManager projectManager = mock(ProjectManager.class);
    final IssueTypeResolver types = new IssueTypeResolver(issueTypeManager);
    final ActionPlanCodec codec = new ActionPlanCodec();
    final Map<Long, FieldScreen> screens = new LinkedHashMap<Long, FieldScreen>();
    final Map<Long, FieldScreenScheme> fieldCatalog = new LinkedHashMap<Long, FieldScreenScheme>();
    final Map<Long, IssueTypeScreenScheme> topCatalog = new LinkedHashMap<Long, IssueTypeScreenScheme>();
    final Map<Long, Set<Long>> usersByTop = new HashMap<Long, Set<Long>>();
    final Map<Long, Project> projectCatalog = new LinkedHashMap<Long, Project>();
    final Map<Long, List<IssueType>> typesByProject = new HashMap<Long, List<IssueType>>();
    final List<IssueType> typeCatalog = new ArrayList<IssueType>();
    final List<String> writes = new ArrayList<String>();
    final AtomicLong sequence = new AtomicLong(1000);
    final IssueType task, bug, story, epic, other, vacation, orphan;
    final FieldScreenScheme defaultField, hrField, bugField, altField;
    final IssueTypeScreenScheme shared, jiraDefault, own;
    /** 1-based number of the call (per kind, over the whole test) that throws; 0 disables. */
    int failUpdateNumber, failCreateNumber, failRemoveNumber, failAfterUpdateNumber;
    /** The n-th update returns normally but is not persisted (Jira drift without an error). */
    int swallowUpdateNumber;
    boolean failReadBack;
    /** Runs after every successful write (a concurrent editor). */
    Runnable afterWrite;
    private int updateCalls, createCalls, removeCalls, refreshCalls;
    private boolean written;
    /** True from the first write until {@code refresh()}: reads return {@link #staleViews}, the table as it was before the write. */
    private boolean stale;
    private final Map<Long, IssueTypeScreenScheme> staleViews = new HashMap<Long, IssueTypeScreenScheme>();
    private final Map<IssueTypeScreenSchemeEntity, FieldScreenScheme> committed = new IdentityHashMap<IssueTypeScreenSchemeEntity, FieldScreenScheme>();

    ItssMappingWorld() {
        task = type("1", "Task"); bug = type("2", "Bug"); story = type("3", "Story"); epic = type("4", "Epic");
        other = type("5", "Other"); vacation = type("6", "Vacation"); orphan = type("7", "Orphan");
        typeCatalog.addAll(Arrays.asList(task, bug, story, epic, other, vacation, orphan));
        when(issueTypeManager.getIssueTypes()).thenAnswer(call -> typeCatalog);
        when(issueTypeManager.getIssueType(any())).thenAnswer(call -> {
            for (IssueType candidate : typeCatalog) if (candidate.getId().equals(call.getArgument(0))) return candidate;
            return null;
        });
        for (long id = 1; id <= 9; id++) screens.put(id, OperationScreenWorld.screen(id, "Screen " + id));
        defaultField = scheme(30L, "Default SS", 1, 2); hrField = scheme(31L, "HR SS", 3, 4);
        bugField = scheme(32L, "Bug SS", 5, 6); altField = scheme(33L, "Alt SS", 7, 8);
        when(fields.getFieldScreenScheme(anyLong())).thenAnswer(call -> fieldCatalog.get(call.getArgument(0)));
        when(fields.getFieldScreenSchemes()).thenAnswer(call -> fieldCatalog.values());
        shared = top(20L, "Shared ITSS", false, defaultField);
        row(shared, bug.getId(), bugField); row(shared, vacation.getId(), hrField);
        jiraDefault = top(1L, "Default Issue Type Screen Scheme", true, defaultField);
        own = top(21L, "Own ITSS", false, defaultField);
        project(101, "ALPHA", false, shared, task, bug, vacation);
        project(102, "BETA", false, shared, task, story, epic);
        project(103, "GAMMA", true, shared, bug, other);
        project(104, "OWN", false, own, task);
        project(105, "DEFPR", false, jiraDefault, task, bug);
        for (IssueTypeScreenScheme value : topCatalog.values()) commit(value);
        when(projectManager.getProjectObj(anyLong())).thenAnswer(call -> projectCatalog.get(call.getArgument(0)));
        when(tops.getIssueTypeScreenSchemes()).thenAnswer(call -> topCatalog.values());
        when(tops.getIssueTypeScreenScheme(any(Long.class))).thenAnswer(call -> {
            if (failReadBack && written) throw new IllegalStateException("read failed");
            Long id = call.getArgument(0);
            return stale && staleViews.containsKey(id) ? staleViews.get(id) : topCatalog.get(id);
        });
        when(tops.getProjects(any(IssueTypeScreenScheme.class))).thenAnswer(call -> {
            IssueTypeScreenScheme scheme = call.getArgument(0);
            List<GenericValue> values = new ArrayList<GenericValue>();
            for (Long id : users(scheme.getId())) {
                GenericValue value = mock(GenericValue.class); when(value.getLong("id")).thenReturn(id); values.add(value);
            }
            return values;
        });
        when(factory.createIssueTypeScreenSchemeEntity()).thenAnswer(call -> OperationScreenWorld.entity(null, null, null));
        doAnswer(call -> {
            IssueTypeScreenSchemeEntity value = call.getArgument(0);
            beginWrite();
            if (++updateCalls == failUpdateNumber) throw new IllegalStateException("Jira cannot update the row");
            if (updateCalls == swallowUpdateNumber) { value.setFieldScreenScheme(committed.get(value)); return null; }
            committed.put(value, value.getFieldScreenScheme());
            writes.add("update " + value.getIssueTypeScreenScheme().getId() + " " + keyOf(value));
            written = true;
            if (updateCalls == failAfterUpdateNumber) throw new IllegalStateException("late error after the update");
            if (afterWrite != null) afterWrite.run();
            return null;
        }).when(tops).updateIssueTypeScreenSchemeEntity(any());
        doAnswer(call -> {
            IssueTypeScreenSchemeEntity value = call.getArgument(0);
            beginWrite();
            if (++createCalls == failCreateNumber) throw new IllegalStateException("Jira cannot store the row");
            value.getIssueTypeScreenScheme().getEntities().add(value);
            committed.put(value, value.getFieldScreenScheme());
            writes.add("create " + value.getIssueTypeScreenScheme().getId() + " " + keyOf(value));
            written = true;
            if (afterWrite != null) afterWrite.run();
            return null;
        }).when(tops).createIssueTypeScreenSchemeEntity(any());
        doAnswer(call -> {
            IssueTypeScreenSchemeEntity value = call.getArgument(0);
            beginWrite();
            if (++removeCalls == failRemoveNumber) throw new IllegalStateException("Jira cannot remove the row");
            value.getIssueTypeScreenScheme().getEntities().remove(value);
            committed.remove(value);
            writes.add("remove " + value.getIssueTypeScreenScheme().getId() + " " + keyOf(value));
            written = true;
            if (afterWrite != null) afterWrite.run();
            return null;
        }).when(tops).removeIssueTypeScreenSchemeEntity(any());
        doAnswer(call -> {
            refreshCalls++;
            stale = false; staleViews.clear();
            for (Map.Entry<IssueTypeScreenSchemeEntity, FieldScreenScheme> entry : committed.entrySet()) {
                entry.getKey().setFieldScreenScheme(entry.getValue());
            }
            return null;
        }).when(tops).refresh();
    }

    /** The first write after a refresh freezes what readers see (the committed table of every scheme) until the next refresh. */
    private void beginWrite() {
        if (stale) return;
        stale = true;
        for (IssueTypeScreenScheme top : topCatalog.values()) {
            IssueTypeScreenScheme view = OperationScreenWorld.topScheme(top.getId(), top.getName());
            for (IssueTypeScreenSchemeEntity entity : top.getEntities()) {
                FieldScreenScheme value = committed.containsKey(entity) ? committed.get(entity) : entity.getFieldScreenScheme();
                view.getEntities().add(OperationScreenWorld.entity(view, entity.getIssueTypeId(), value));
            }
            staleViews.put(top.getId(), view);
        }
    }

    /** How many times Jira's scheme cache was dropped. */
    int refreshCalls() { return refreshCalls; }

    private static String keyOf(IssueTypeScreenSchemeEntity entity) { return entity.getIssueTypeId() == null ? "DEFAULT" : entity.getIssueTypeId(); }

    /** Project ids Jira reports as users of a top scheme. */
    Set<Long> users(long topId) {
        Set<Long> set = usersByTop.get(topId);
        if (set == null) { set = new TreeSet<Long>(); usersByTop.put(topId, set); }
        return set;
    }

    /** A new project on {@code top}; the id also fixes the sort of its key in tests that need many. */
    Project project(long id, String key, boolean archived, IssueTypeScreenScheme top, IssueType... projectTypes) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id); when(project.getKey()).thenReturn(key); when(project.getName()).thenReturn(key + " name");
        when(project.isArchived()).thenReturn(archived);
        typesByProject.put(id, new ArrayList<IssueType>(Arrays.asList(projectTypes)));
        when(project.getIssueTypes()).thenAnswer(call -> typesByProject.get(id));
        projectCatalog.put(id, project); users(top.getId()).add(id);
        return project;
    }

    FieldScreenScheme scheme(Long id, String name, long defaultScreen, long createScreen) {
        FieldScreenScheme value = OperationScreenWorld.fieldScheme(id, name);
        value.getFieldScreenSchemeItems().add(OperationScreenWorld.item(value, null, screens.get(defaultScreen)));
        value.getFieldScreenSchemeItems().add(OperationScreenWorld.item(value, IssueOperations.CREATE_ISSUE_OPERATION, screens.get(createScreen)));
        fieldCatalog.put(id, value);
        return value;
    }

    IssueTypeScreenScheme top(Long id, String name, boolean isDefault, FieldScreenScheme defaultRow) {
        IssueTypeScreenScheme value = OperationScreenWorld.topScheme(id, name);
        when(value.isDefault()).thenReturn(isDefault);
        value.getEntities().add(OperationScreenWorld.entity(value, null, defaultRow));
        topCatalog.put(id, value);
        return value;
    }

    IssueTypeScreenSchemeEntity row(IssueTypeScreenScheme top, String typeId, FieldScreenScheme fieldScheme) {
        IssueTypeScreenSchemeEntity entity = OperationScreenWorld.entity(top, typeId, fieldScheme);
        top.getEntities().add(entity); committed.put(entity, fieldScheme);
        return entity;
    }

    void commit(IssueTypeScreenScheme top) {
        for (IssueTypeScreenSchemeEntity entity : top.getEntities()) committed.put(entity, entity.getFieldScreenScheme());
    }

    /** Rows of a top scheme as Jira persists them: type id or DEFAULT to Screen Scheme id. */
    Map<String, Long> table(IssueTypeScreenScheme top) {
        Map<String, Long> table = new TreeMap<String, Long>();
        for (IssueTypeScreenSchemeEntity entity : top.getEntities()) {
            FieldScreenScheme value = committed.containsKey(entity) ? committed.get(entity) : entity.getFieldScreenScheme();
            table.put(keyOf(entity), value.getId());
        }
        return table;
    }

    static IssueType type(String id, String name) { return OperationScreenWorld.type(id, name); }
}
