package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenFactory;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.issue.operation.ScreenableIssueOperation;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In-memory Jira screen-scheme model: project ABC with Task, Bug, Story, Epic and Other, Screens 1..9, a shared source
 * Issue Type Screen Scheme (20) whose DEFAULT row uses Screen Scheme 10 (default 1, CREATE 2) and whose "other" row uses
 * Screen Scheme 11 (default 5). The catalogs are the truth the tests compare with. Only Jira's managers are doubles.
 */
final class OperationScreenWorld {
    final FieldScreenFactory factory = mock(FieldScreenFactory.class);
    final FieldScreenManager screenManager = mock(FieldScreenManager.class);
    final FieldScreenSchemeManager fields = mock(FieldScreenSchemeManager.class);
    final IssueTypeScreenSchemeManager tops = mock(IssueTypeScreenSchemeManager.class);
    final ProjectResolver projects = mock(ProjectResolver.class);
    final IssueTypeResolver types = mock(IssueTypeResolver.class);
    final ActionPlanCodec codec = new ActionPlanCodec();
    final Project project = mock(Project.class);
    final Map<Long, FieldScreen> screenCatalog = new LinkedHashMap<Long, FieldScreen>();
    final Map<Long, FieldScreenScheme> fieldCatalog = new LinkedHashMap<Long, FieldScreenScheme>();
    final Map<Long, IssueTypeScreenScheme> topCatalog = new LinkedHashMap<Long, IssueTypeScreenScheme>();
    final AtomicReference<IssueTypeScreenScheme> associated = new AtomicReference<IssueTypeScreenScheme>();
    final AtomicLong sequence = new AtomicLong(100);
    final List<String> writes = new ArrayList<String>();
    final FieldScreenScheme sourceField;
    final FieldScreenScheme otherField;
    final IssueTypeScreenScheme sourceTop;
    final IssueType task, bug, story, epic, other;
    /** Failure injection: 1-based number of the call that throws; 0 disables. */
    int failFieldSchemeNumber, failItemNumber, failEntityNumber, failUpdateNumber, failAfterUpdateNumber;
    boolean failAssociation, associateThenFail, failAssociationReadBack, failMappingReadBack;
    private int fieldSchemeCalls, itemCalls, entityCalls, updateCalls;
    final Map<Long, Set<Long>> usersByTop = new HashMap<Long, Set<Long>>();
    private final Map<IssueTypeScreenSchemeEntity, FieldScreenScheme> committed = new IdentityHashMap<IssueTypeScreenSchemeEntity, FieldScreenScheme>();
    final ProjectManager projectManager = mock(ProjectManager.class);

    OperationScreenWorld() {
        when(project.getId()).thenReturn(1L); when(project.getKey()).thenReturn("ABC"); when(project.getName()).thenReturn("Example");
        when(projects.resolve("ABC")).thenReturn(project);
        task = type("task", "Task"); bug = type("bug", "Bug"); story = type("story", "Story");
        epic = type("epic", "Epic"); other = type("other", "Other");
        when(project.getIssueTypes()).thenReturn(Arrays.asList(task, bug, story, epic, other));
        for (IssueType type : Arrays.asList(task, bug, story, epic, other)) {
            when(types.resolve(type.getName())).thenReturn(type);
            when(types.resolve(type.getName().toLowerCase(Locale.ROOT))).thenReturn(type);
        }
        for (long id = 1; id <= 9; id++) screenCatalog.put(id, screen(id, "Screen " + id));
        when(screenManager.getFieldScreen(anyLong())).thenAnswer(call -> screenCatalog.get(call.getArgument(0)));
        when(screenManager.getFieldScreens()).thenAnswer(call -> screenCatalog.values());
        sourceField = fieldScheme(10L, "Shared field");
        sourceField.getFieldScreenSchemeItems().add(item(sourceField, null, screenCatalog.get(1L)));
        sourceField.getFieldScreenSchemeItems().add(item(sourceField, IssueOperations.CREATE_ISSUE_OPERATION, screenCatalog.get(2L)));
        otherField = fieldScheme(11L, "Other field");
        otherField.getFieldScreenSchemeItems().add(item(otherField, null, screenCatalog.get(5L)));
        fieldCatalog.put(10L, sourceField); fieldCatalog.put(11L, otherField);
        sourceTop = topScheme(20L, "Shared top");
        sourceTop.getEntities().add(entity(sourceTop, null, sourceField));
        sourceTop.getEntities().add(entity(sourceTop, "other", otherField));
        topCatalog.put(20L, sourceTop); associated.set(sourceTop);
        when(projectManager.getProjectObj(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0); Project value = mock(Project.class);
            when(value.getId()).thenReturn(id); when(value.getKey()).thenReturn("P" + id); when(value.getName()).thenReturn("Project " + id);
            return value;
        });
        users(20).add(1L); users(20).add(2L); commit(sourceTop);
        when(tops.getProjects(any(IssueTypeScreenScheme.class))).thenAnswer(call -> {
            IssueTypeScreenScheme scheme = call.getArgument(0); List<GenericValue> values = new ArrayList<GenericValue>();
            for (Long id : users(scheme.getId())) {
                GenericValue value = mock(GenericValue.class); when(value.getLong("id")).thenReturn(id); values.add(value);
            }
            return values;
        });
        doAnswer(call -> {
            IssueTypeScreenSchemeEntity value = call.getArgument(0);
            if (++updateCalls == failUpdateNumber) { value.setFieldScreenScheme(committed.get(value)); throw new IllegalStateException("Jira cannot update the mapping"); }
            committed.put(value, value.getFieldScreenScheme()); writes.add("update " + value.getIssueTypeScreenScheme().getId() + " " + value.getIssueTypeId());
            if (updateCalls == failAfterUpdateNumber) throw new IllegalStateException("late error after the update");
            return null;
        }).when(tops).updateIssueTypeScreenSchemeEntity(any());
        when(fields.getFieldScreenSchemes()).thenAnswer(call -> fieldCatalog.values());
        when(tops.getIssueTypeScreenSchemes()).thenAnswer(call -> topCatalog.values());
        when(tops.getIssueTypeScreenScheme(project)).thenAnswer(call -> {
            if (failAssociationReadBack && associated.get() != sourceTop) throw new IllegalStateException("read failed");
            if (failMappingReadBack && updateCalls > 0) throw new IllegalStateException("read failed");
            return associated.get();
        });
        when(factory.createFieldScreenScheme()).thenAnswer(call -> fieldScheme(null, null));
        when(factory.createIssueTypeScreenScheme()).thenAnswer(call -> topScheme(null, null));
        when(factory.createFieldScreenSchemeItem()).thenAnswer(call -> item(null, null, null));
        when(factory.createIssueTypeScreenSchemeEntity()).thenAnswer(call -> entity(null, null, null));
        doAnswer(call -> {
            if (++fieldSchemeCalls == failFieldSchemeNumber) throw new IllegalStateException("Jira cannot store the scheme");
            FieldScreenScheme value = call.getArgument(0); value.setId(sequence.incrementAndGet()); fieldCatalog.put(value.getId(), value);
            writes.add("fieldScheme " + value.getId()); return null;
        }).when(fields).createFieldScreenScheme(any());
        doAnswer(call -> {
            if (++itemCalls == failItemNumber) throw new IllegalStateException("Jira cannot store the item");
            FieldScreenSchemeItem value = call.getArgument(0); value.getFieldScreenScheme().getFieldScreenSchemeItems().add(value); return null;
        }).when(fields).createFieldScreenSchemeItem(any());
        doAnswer(call -> {
            IssueTypeScreenScheme value = call.getArgument(0); value.setId(sequence.incrementAndGet()); topCatalog.put(value.getId(), value);
            writes.add("top " + value.getId()); return null;
        }).when(tops).createIssueTypeScreenScheme(any());
        doAnswer(call -> {
            if (++entityCalls == failEntityNumber) throw new IllegalStateException("Jira cannot store the mapping");
            IssueTypeScreenSchemeEntity value = call.getArgument(0); value.getIssueTypeScreenScheme().getEntities().add(value);
            committed.put(value, value.getFieldScreenScheme()); writes.add("entity " + value.getIssueTypeScreenScheme().getId() + " " + value.getIssueTypeId()); return null;
        }).when(tops).createIssueTypeScreenSchemeEntity(any());
        doAnswer(call -> {
            IssueTypeScreenScheme value = call.getArgument(1);
            if (associateThenFail) { associated.set(value); users(20).remove(1L); users(value.getId()).add(1L); writes.add("associate " + value.getId()); throw new IllegalStateException("late error"); }
            if (failAssociation) throw new IllegalStateException("Jira cannot switch the project");
            for (Set<Long> set : usersByTop.values()) set.remove(1L);
            users(value.getId()).add(1L); associated.set(value); writes.add("associate " + value.getId());
            return null;
        }).when(tops).addSchemeAssociation(eq(project), any());
    }

    /** Project ids Jira reports as users of a top scheme (the association table). */
    Set<Long> users(long topId) {
        Set<Long> set = usersByTop.get(topId);
        if (set == null) { set = new TreeSet<Long>(); usersByTop.put(topId, set); }
        return set;
    }

    /** The scheme the committed (persisted) state of an entity: a failed update restores it. */
    void commit(IssueTypeScreenScheme top) {
        for (IssueTypeScreenSchemeEntity entity : top.getEntities()) committed.put(entity, entity.getFieldScreenScheme());
    }

    /** Registers a top scheme with the given DEFAULT and "other" rows used only by project ABC (id 1) and associates it. */
    IssueTypeScreenScheme usePrivateTop(long id, String name) {
        IssueTypeScreenScheme top = topScheme(id, name);
        top.getEntities().add(entity(top, null, sourceField));
        top.getEntities().add(entity(top, "other", otherField));
        topCatalog.put(id, top); users(20).remove(1L); users(id).add(1L);
        commit(top); associated.set(top);
        return top;
    }

    int entityCalls() { return entityCalls; }
    int topCount() { return topCatalog.size(); }
    int fieldCount() { return fieldCatalog.size(); }

    /** The effective Screen id of {@code type} for {@code operation} in the scheme the project uses now. */
    long effective(IssueType type, String operation) {
        return OperationScreenPlan.effective(associated.get().getEffectiveFieldScreenScheme(type), operation).getId();
    }

    static IssueType type(String id, String name) {
        IssueType result = mock(IssueType.class); when(result.getId()).thenReturn(id); when(result.getName()).thenReturn(name); return result;
    }
    static FieldScreen screen(Long id, String name) {
        FieldScreen result = mock(FieldScreen.class); when(result.getId()).thenReturn(id); when(result.getName()).thenReturn(name); return result;
    }
    static FieldScreenScheme fieldScheme(Long initialId, String initialName) {
        FieldScreenScheme value = mock(FieldScreenScheme.class);
        AtomicReference<Long> id = new AtomicReference<Long>(initialId);
        AtomicReference<String> name = new AtomicReference<String>(initialName);
        AtomicReference<String> description = new AtomicReference<String>(initialName == null ? null : initialName + " description");
        List<FieldScreenSchemeItem> items = new ArrayList<FieldScreenSchemeItem>();
        when(value.getId()).thenAnswer(call -> id.get()); doAnswer(call -> { id.set(call.getArgument(0)); return null; }).when(value).setId(any());
        when(value.getName()).thenAnswer(call -> name.get()); doAnswer(call -> { name.set(call.getArgument(0)); return null; }).when(value).setName(any());
        when(value.getDescription()).thenAnswer(call -> description.get()); doAnswer(call -> { description.set(call.getArgument(0)); return null; }).when(value).setDescription(any());
        when(value.getFieldScreenSchemeItems()).thenReturn(items); return value;
    }
    static IssueTypeScreenScheme topScheme(Long initialId, String initialName) {
        IssueTypeScreenScheme value = mock(IssueTypeScreenScheme.class);
        AtomicReference<Long> id = new AtomicReference<Long>(initialId);
        AtomicReference<String> name = new AtomicReference<String>(initialName);
        AtomicReference<String> description = new AtomicReference<String>(initialName == null ? null : initialName + " description");
        List<IssueTypeScreenSchemeEntity> entities = new ArrayList<IssueTypeScreenSchemeEntity>();
        when(value.getId()).thenAnswer(call -> id.get()); doAnswer(call -> { id.set(call.getArgument(0)); return null; }).when(value).setId(any());
        when(value.getName()).thenAnswer(call -> name.get()); doAnswer(call -> { name.set(call.getArgument(0)); return null; }).when(value).setName(any());
        when(value.getDescription()).thenAnswer(call -> description.get()); doAnswer(call -> { description.set(call.getArgument(0)); return null; }).when(value).setDescription(any());
        when(value.getEntities()).thenReturn(entities);
        when(value.getEntity(any())).thenAnswer(call -> {
            for (IssueTypeScreenSchemeEntity entity : entities) if (Objects.equals(entity.getIssueTypeId(), call.getArgument(0))) return entity;
            return null;
        });
        when(value.getEffectiveFieldScreenScheme(any())).thenAnswer(call -> {
            IssueType type = call.getArgument(0); IssueTypeScreenSchemeEntity explicit = value.getEntity(type.getId());
            if (explicit == null) explicit = value.getEntity(null); return explicit == null ? null : explicit.getFieldScreenScheme();
        });
        return value;
    }
    static FieldScreenSchemeItem item(FieldScreenScheme initialScheme, ScreenableIssueOperation initialOperation, FieldScreen initialScreen) {
        FieldScreenSchemeItem value = mock(FieldScreenSchemeItem.class);
        AtomicReference<FieldScreenScheme> scheme = new AtomicReference<FieldScreenScheme>(initialScheme);
        AtomicReference<ScreenableIssueOperation> operation = new AtomicReference<ScreenableIssueOperation>(initialOperation);
        AtomicReference<FieldScreen> screen = new AtomicReference<FieldScreen>(initialScreen);
        when(value.getFieldScreenScheme()).thenAnswer(call -> scheme.get()); doAnswer(call -> { scheme.set(call.getArgument(0)); return null; }).when(value).setFieldScreenScheme(any());
        when(value.getIssueOperation()).thenAnswer(call -> operation.get()); doAnswer(call -> { operation.set(call.getArgument(0)); return null; }).when(value).setIssueOperation(any());
        when(value.getFieldScreen()).thenAnswer(call -> screen.get()); doAnswer(call -> { screen.set(call.getArgument(0)); return null; }).when(value).setFieldScreen(any());
        return value;
    }
    static IssueTypeScreenSchemeEntity entity(IssueTypeScreenScheme initialTop, String initialType, FieldScreenScheme initialField) {
        IssueTypeScreenSchemeEntity value = mock(IssueTypeScreenSchemeEntity.class);
        AtomicReference<IssueTypeScreenScheme> top = new AtomicReference<IssueTypeScreenScheme>(initialTop);
        AtomicReference<String> type = new AtomicReference<String>(initialType);
        AtomicReference<FieldScreenScheme> field = new AtomicReference<FieldScreenScheme>(initialField);
        when(value.getIssueTypeScreenScheme()).thenAnswer(call -> top.get()); doAnswer(call -> { top.set(call.getArgument(0)); return null; }).when(value).setIssueTypeScreenScheme(any());
        when(value.getIssueTypeId()).thenAnswer(call -> type.get()); doAnswer(call -> { type.set(call.getArgument(0)); return null; }).when(value).setIssueTypeId(any());
        when(value.getFieldScreenScheme()).thenAnswer(call -> field.get()); doAnswer(call -> { field.set(call.getArgument(0)); return null; }).when(value).setFieldScreenScheme(any());
        return value;
    }
}
