package ru.rasulov.customfields.impl.configuration;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.InMemoryActionPlans;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.customfields.impl.FieldJournalTestSupport;
import ru.rasulov.customfields.impl.JiraMocks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In-memory field configurations, schemes, scheme rows and project associations behind a mocked FieldLayoutManager,
 * plus the guard, lock, plan store and journal every configuration service needs.
 */
final class FieldLayoutFake {
    final FieldLayoutManager manager = mock(FieldLayoutManager.class);
    final FieldLayoutCopyFactory copies = mock(FieldLayoutCopyFactory.class);
    final ProjectResolver projects = mock(ProjectResolver.class);
    final IssueTypeResolver issueTypes = mock(IssueTypeResolver.class);
    final AdministratorGuard guard;
    final NamedLock lock = new NamedLock(JiraMocks.lockService(mock(ClusterLock.class)));
    final ActionPlanStore plans = InMemoryActionPlans.create();
    final ActionPlanCodec codec = new ActionPlanCodec();
    final ChangeJournal journal = FieldJournalTestSupport.journal("admin-key");
    final FieldConfigurationCatalog catalog = new FieldConfigurationCatalog(manager);

    final FieldLayout defaultLayout;
    final Map<Long, EditableFieldLayout> layouts = new LinkedHashMap<Long, EditableFieldLayout>();
    final Map<Long, FieldLayoutScheme> schemes = new LinkedHashMap<Long, FieldLayoutScheme>();
    /** Scheme id -> issue type id (null = DEFAULT row) -> layout id (null = Jira's default configuration). */
    final Map<Long, Map<String, Long>> rows = new HashMap<Long, Map<String, Long>>();
    final Map<Long, Long> associations = new HashMap<Long, Long>();
    final List<Project> allProjects = new ArrayList<Project>();
    final Map<String, Map<String, boolean[]>> items = new HashMap<String, Map<String, boolean[]>>();
    private final AtomicLong ids = new AtomicLong(500);

    FieldLayoutFake() {
        JiraAuthenticationContext authentication = mock(JiraAuthenticationContext.class);
        GlobalPermissionManager permissions = mock(GlobalPermissionManager.class);
        ApplicationUser admin = mock(ApplicationUser.class);
        when(authentication.getLoggedInUser()).thenReturn(admin);
        when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
        when(admin.getKey()).thenReturn("admin-key");
        guard = new AdministratorGuard(authentication, permissions);

        defaultLayout = layoutMock(FieldLayout.class, null, "Default Field Configuration", true);
        when(manager.getFieldLayout()).thenReturn(defaultLayout);
        when(manager.getFieldLayout((Long) null)).thenReturn(defaultLayout);
        when(manager.getEditableFieldLayouts()).thenAnswer(call -> new ArrayList<EditableFieldLayout>(layouts.values()));
        when(manager.getEditableFieldLayout(anyLong())).thenAnswer(call -> layouts.get(call.<Long>getArgument(0)));
        when(manager.storeAndReturnEditableFieldLayout(any(EditableFieldLayout.class))).thenAnswer(call -> {
            EditableFieldLayout draft = call.getArgument(0);
            String name = draft.getName();
            String description = draft.getDescription();
            EditableFieldLayout stored = layout(ids.incrementAndGet(), name);
            when(stored.getDescription()).thenReturn(description);
            return stored;
        });
        when(copies.copyOf(any(FieldLayout.class), anyString())).thenAnswer(call -> {
            EditableFieldLayout draft = mock(EditableFieldLayout.class);
            String[] description = {call.<FieldLayout>getArgument(0).getDescription()};
            when(draft.getName()).thenReturn(call.<String>getArgument(1));
            when(draft.getDescription()).thenAnswer(inner -> description[0]);
            doAnswer(inner -> { description[0] = inner.getArgument(0); return null; }).when(draft).setDescription(anyString());
            return draft;
        });

        when(manager.getFieldLayoutSchemes()).thenAnswer(call -> new ArrayList<FieldLayoutScheme>(schemes.values()));
        when(manager.getMutableFieldLayoutScheme(anyLong())).thenAnswer(call -> schemes.get(call.<Long>getArgument(0)));
        when(manager.createFieldLayoutScheme(anyString(), nullable(String.class))).thenAnswer(call ->
                scheme(ids.incrementAndGet(), call.getArgument(0), call.getArgument(1)));
        when(manager.getFieldLayoutSchemeEntities(any(FieldLayoutScheme.class))).thenAnswer(call -> {
            Long id = call.<FieldLayoutScheme>getArgument(0).getId();
            List<FieldLayoutSchemeEntity> entities = new ArrayList<FieldLayoutSchemeEntity>();
            for (String type : new ArrayList<String>(rows.get(id).keySet())) entities.add(entity(id, type));
            return entities;
        });
        when(manager.createFieldLayoutSchemeEntity(any(FieldLayoutScheme.class), nullable(String.class), nullable(Long.class)))
                .thenAnswer(call -> {
                    Long scheme = call.<FieldLayoutScheme>getArgument(0).getId();
                    rows.get(scheme).put(call.getArgument(1), call.getArgument(2));
                    return entity(scheme, call.getArgument(1));
                });
        doAnswer(call -> {
            FieldLayoutSchemeEntity entity = call.getArgument(0);
            rows.get(entity.getFieldLayoutScheme().getId()).put(entity.getIssueTypeId(), entity.getFieldLayoutId());
            return null;
        }).when(manager).updateFieldLayoutSchemeEntity(any(FieldLayoutSchemeEntity.class));
        doAnswer(call -> {
            FieldLayoutSchemeEntity entity = call.getArgument(0);
            rows.get(entity.getFieldLayoutScheme().getId()).remove(entity.getIssueTypeId());
            return null;
        }).when(manager).removeFieldLayoutSchemeEntity(any(FieldLayoutSchemeEntity.class));

        when(manager.getFieldConfigurationScheme(any(Project.class))).thenAnswer(call -> {
            Long scheme = associations.get(call.<Project>getArgument(0).getId());
            return scheme == null ? null : view(schemes.get(scheme));
        });
        doAnswer(call -> {
            associations.put(call.<Project>getArgument(0).getId(), call.getArgument(1));
            return null;
        }).when(manager).addSchemeAssociation(any(Project.class), anyLong());
        doAnswer(call -> {
            Long id = call.<Project>getArgument(0).getId();
            if (Objects.equals(associations.get(id), call.getArgument(1))) associations.remove(id);
            return null;
        }).when(manager).removeSchemeAssociation(any(Project.class), anyLong());
        when(manager.getProjectsUsing(any(FieldConfigurationScheme.class))).thenAnswer(call ->
                projectsOn(call.<FieldConfigurationScheme>getArgument(0).getId()));
        when(manager.getProjectsUsing(any(FieldLayoutScheme.class))).thenAnswer(call ->
                projectsOn(call.<FieldLayoutScheme>getArgument(0).getId()));
        when(manager.getProjectsUsing(any(FieldLayout.class))).thenAnswer(call -> {
            String key = FieldConfigurationCatalog.key(call.getArgument(0));
            List<Project> users = new ArrayList<Project>();
            for (Project project : allProjects) {
                for (IssueType type : project.getIssueTypes()) {
                    if (key.equals(FieldConfigurationCatalog.key(effective(project, type.getId())))) {
                        users.add(project);
                        break;
                    }
                }
            }
            return users;
        });
        when(manager.getFieldConfigurationSchemes(any(FieldLayout.class))).thenAnswer(call -> {
            FieldLayout layout = call.getArgument(0);
            Long id = layout.isDefault() ? null : layout.getId();
            List<FieldConfigurationScheme> result = new ArrayList<FieldConfigurationScheme>();
            for (Map.Entry<Long, Map<String, Long>> scheme : rows.entrySet()) {
                if (scheme.getValue().containsValue(id)) result.add(view(schemes.get(scheme.getKey())));
            }
            return result;
        });
        when(manager.getFieldLayout(any(Project.class), anyString())).thenAnswer(call ->
                effective(call.getArgument(0), call.getArgument(1)));
    }

    FieldLayout effective(Project project, String issueTypeId) {
        Long scheme = associations.get(project.getId());
        if (scheme == null) return defaultLayout;
        Map<String, Long> schemeRows = rows.get(scheme);
        Long layout = schemeRows.containsKey(issueTypeId) ? schemeRows.get(issueTypeId) : schemeRows.get(null);
        return layout == null ? defaultLayout : layouts.get(layout);
    }

    EditableFieldLayout layout(long id, String name) {
        EditableFieldLayout layout = layoutMock(EditableFieldLayout.class, id, name, false);
        layouts.put(id, layout);
        return layout;
    }

    /** Sets a field's required/hidden flag in a configuration; the items list reflects every flag set so far. */
    void item(FieldLayout layout, OrderableField field, boolean required, boolean hidden) {
        String key = FieldConfigurationCatalog.key(layout);
        items.computeIfAbsent(key, k -> new LinkedHashMap<String, boolean[]>()).put(field.getId(), new boolean[] {required, hidden});
        FieldLayoutItem item = mock(FieldLayoutItem.class);
        when(item.getOrderableField()).thenReturn(field);
        when(item.isRequired()).thenAnswer(call -> items.get(key).get(field.getId())[0]);
        when(item.isHidden()).thenAnswer(call -> items.get(key).get(field.getId())[1]);
        when(layout.getFieldLayoutItem(field.getId())).thenReturn(item);
        List<FieldLayoutItem> all = new ArrayList<FieldLayoutItem>(layout.getFieldLayoutItems());
        all.removeIf(existing -> existing.getOrderableField().getId().equals(field.getId()));
        all.add(item);
        when(layout.getFieldLayoutItems()).thenReturn(all);
    }

    FieldLayoutScheme scheme(long id, String name, String description) {
        FieldLayoutScheme scheme = mock(FieldLayoutScheme.class);
        when(scheme.getId()).thenReturn(id);
        when(scheme.getName()).thenReturn(name);
        when(scheme.getDescription()).thenReturn(description);
        when(scheme.getEntity(nullable(String.class))).thenAnswer(call ->
                rows.get(id).containsKey(call.<String>getArgument(0)) ? entity(id, call.getArgument(0)) : null);
        schemes.put(id, scheme);
        rows.put(id, new LinkedHashMap<String, Long>());
        return scheme;
    }

    Project project(long id, String key, IssueType... types) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        when(project.getKey()).thenReturn(key);
        when(project.getName()).thenReturn(key + " project");
        when(project.getIssueTypes()).thenReturn(java.util.Arrays.asList(types));
        when(projects.resolve(key)).thenReturn(project);
        allProjects.add(project);
        return project;
    }

    IssueType issueType(String id, String name) {
        IssueType type = mock(IssueType.class);
        when(type.getId()).thenReturn(id);
        when(type.getName()).thenReturn(name);
        when(issueTypes.resolve(name)).thenReturn(type);
        when(issueTypes.resolve(id)).thenReturn(type);
        return type;
    }

    static OrderableField field(String id, String name) {
        OrderableField field = mock(OrderableField.class);
        when(field.getId()).thenReturn(id);
        when(field.getName()).thenReturn(name);
        return field;
    }

    private Collection<Project> projectsOn(Long scheme) {
        List<Project> users = new ArrayList<Project>();
        for (Project project : allProjects) if (Objects.equals(associations.get(project.getId()), scheme)) users.add(project);
        return users;
    }

    private FieldConfigurationScheme view(FieldLayoutScheme scheme) {
        FieldConfigurationScheme view = mock(FieldConfigurationScheme.class);
        Long id = scheme.getId();
        String name = scheme.getName();
        when(view.getId()).thenReturn(id);
        when(view.getName()).thenReturn(name);
        return view;
    }

    private FieldLayoutSchemeEntity entity(Long scheme, String issueTypeId) {
        FieldLayoutSchemeEntity entity = mock(FieldLayoutSchemeEntity.class);
        Long[] target = {rows.get(scheme).get(issueTypeId)};
        FieldLayoutScheme owner = schemes.get(scheme);
        when(entity.getIssueTypeId()).thenReturn(issueTypeId);
        when(entity.getFieldLayoutId()).thenAnswer(call -> target[0]);
        doAnswer(call -> { target[0] = call.getArgument(0); return null; }).when(entity).setFieldLayoutId(nullable(Long.class));
        when(entity.getFieldLayoutScheme()).thenReturn(owner);
        return entity;
    }

    private static <T extends FieldLayout> T layoutMock(Class<T> type, Long id, String name, boolean isDefault) {
        T layout = mock(type);
        when(layout.getId()).thenReturn(id);
        when(layout.getName()).thenReturn(name);
        when(layout.isDefault()).thenReturn(isDefault);
        when(layout.getFieldLayoutItems()).thenReturn(new ArrayList<FieldLayoutItem>());
        return layout;
    }
}
