package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.avatar.Avatar;
import com.atlassian.jira.avatar.AvatarManager;
import com.atlassian.jira.bc.project.ProjectCreationData;
import com.atlassian.jira.bc.project.ProjectService;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.project.type.ProjectTypeKey;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A small stateful Jira for project/issue-type-set tests: the global issue types, issue type schemes with their
 * project associations and defaults, projects and a project-creation hook. Native managers are doubles over this
 * state; every assertion of the F06 tests reads the state, not only the returned DTO.
 */
public final class ProjectTypeWorld {
    public static final long DEFAULT_SCHEME = 10L;

    public final IssueTypeManager types = mock(IssueTypeManager.class);
    public final IssueTypeSchemeManager schemes = mock(IssueTypeSchemeManager.class);
    public final AvatarManager avatars = mock(AvatarManager.class);
    public final OfBizDelegator delegator = mock(OfBizDelegator.class);
    public final WorkflowSchemeManager workflowSchemes = mock(WorkflowSchemeManager.class);
    public final WorkflowManager workflows = mock(WorkflowManager.class);
    public final StatusManager statuses = mock(StatusManager.class);
    public final IssueTypeMigrator migrator = mock(IssueTypeMigrator.class);
    public final ProjectService projectService = mock(ProjectService.class);
    public final ProjectManager projectManager = mock(ProjectManager.class);

    public final List<IssueType> catalog = new ArrayList<IssueType>();
    public final List<String> createCalls = new ArrayList<String>();
    public final List<Project> projects = new ArrayList<Project>();
    public final Map<Long, SchemeState> schemeStates = new LinkedHashMap<Long, SchemeState>();
    public final Map<Long, Long> projectScheme = new HashMap<Long, Long>();
    /** Issue rows (type id, status id) the planner sees in the project it is planning. */
    public final List<String[]> issueRows = new ArrayList<String[]>();
    public final IssueTypeUndoSupport undoSupport;
    public final IssueTypeResolver resolver = new IssueTypeResolver(types);
    public final IssueTypeChangePlanner changePlanner;

    public String failCreationOf;
    public boolean failReadAfterFailure;
    public boolean readsFailing;
    public boolean failSchemeCreate;
    public boolean failureLeavesType;
    /** Runs right after Jira created the project: simulates activity in the new project before its types change. */
    public Runnable afterProjectCreated;
    public int removeCalls;
    private int nextId = 100;
    private long nextScheme = 500;
    private long nextProject = 70;

    /** Scheme model behind a FieldConfigScheme double. */
    public static final class SchemeState {
        public final long id;
        public String name;
        public final List<String> typeIds = new ArrayList<String>();
        public String defaultTypeId;
        public final List<Project> associated = new ArrayList<Project>();
        public final FieldConfigScheme scheme = mock(FieldConfigScheme.class);
        public final FieldConfig config = mock(FieldConfig.class);

        public SchemeState(long id, String name) {
            this.id = id;
            this.name = name;
            when(scheme.getId()).thenReturn(id);
            when(scheme.getName()).thenAnswer(call -> this.name);
            when(scheme.getAssociatedProjectObjects()).thenAnswer(call -> new ArrayList<Project>(associated));
            when(scheme.getOneAndOnlyConfig()).thenReturn(config);
        }
    }

    public ProjectTypeWorld() {
        IssueType task = add("1", "Task", false);
        IssueType subtask = add("2", "Sub-task", true);
        add("3", "Bug", false);
        SchemeState shared = new SchemeState(DEFAULT_SCHEME, "Default Issue Type Scheme");
        shared.typeIds.addAll(Arrays.asList("1", "2", "3"));
        shared.defaultTypeId = "1";
        schemeStates.put(DEFAULT_SCHEME, shared);
        wire();
        undoSupport = new IssueTypeUndoSupport(types, schemes, delegator, workflowSchemes, mock(IssueTypeScreenSchemeManager.class));
        changePlanner = new IssueTypeChangePlanner(schemes, workflowSchemes, workflows, types, statuses, resolver, delegator);
        project("OTHER", DEFAULT_SCHEME);
        project("NEIGHBOR", DEFAULT_SCHEME);
        if (task == null || subtask == null) throw new AssertionError();
    }

    public IssueType add(String id, String name, boolean subtask) {
        IssueType type = mock(IssueType.class);
        when(type.getId()).thenReturn(id);
        when(type.getName()).thenReturn(name);
        when(type.getDescription()).thenReturn("");
        when(type.isSubTask()).thenReturn(subtask);
        catalog.add(type);
        return type;
    }

    public IssueType type(String name) {
        for (IssueType type : catalog) if (type.getName().equals(name)) return type;
        return null;
    }

    public IssueType typeById(String id) {
        for (IssueType type : catalog) if (type.getId().equals(id)) return type;
        return null;
    }

    public Project project(String key, long schemeId) {
        Project project = mock(Project.class);
        long id = nextProject++;
        when(project.getId()).thenReturn(id);
        when(project.getKey()).thenReturn(key);
        when(project.getName()).thenReturn(key + " project");
        when(project.getLeadUserKey()).thenReturn("admin-key");
        when(project.getProjectTypeKey()).thenReturn(new ProjectTypeKey("business"));
        projects.add(project);
        projectScheme.put(id, schemeId);
        schemeStates.get(schemeId).associated.add(project);
        return project;
    }

    public SchemeState schemeOf(Project project) {
        return schemeStates.get(projectScheme.get(project.getId()));
    }

    public Project projectByKey(String key) {
        for (Project project : projects) if (project.getKey().equals(key)) return project;
        return null;
    }

    /** Type names of the project's own scheme, in scheme order. */
    public List<String> typeNamesOf(Project project) {
        List<String> names = new ArrayList<String>();
        for (String id : schemeOf(project).typeIds) names.add(typeById(id).getName());
        return names;
    }

    public List<String> names() {
        List<String> names = new ArrayList<String>();
        for (IssueType type : catalog) names.add(type.getName());
        return names;
    }

    /** A project-creation double: registers a project on Jira's shared default scheme, as the template does. */
    public Project createProject(String key, String name) {
        Project project = project(key, DEFAULT_SCHEME);
        when(project.getName()).thenReturn(name);
        if (afterProjectCreated != null) afterProjectCreated.run();
        return project;
    }

    private void wire() {
        when(avatars.getDefaultAvatarId(Avatar.Type.ISSUETYPE)).thenReturn(17L);
        when(types.getIssueTypes()).thenAnswer(call -> {
            if (readsFailing) throw new IllegalStateException("catalog unreadable");
            return new ArrayList<IssueType>(catalog);
        });
        when(types.getIssueType(anyString())).thenAnswer(call -> typeById(call.getArgument(0)));
        when(types.createIssueType(anyString(), anyString(), any(Long.class))).thenAnswer(call -> create(call.getArgument(0), false));
        when(types.createSubTaskIssueType(anyString(), anyString(), any(Long.class))).thenAnswer(call -> create(call.getArgument(0), true));
        doAnswer(call -> { removeCalls++; return null; }).when(types).removeIssueType(anyString(), any());

        when(schemes.getDefaultIssueTypeSchemeId()).thenReturn(DEFAULT_SCHEME);
        when(schemes.getDefaultIssueTypeScheme()).thenAnswer(call -> schemeStates.get(DEFAULT_SCHEME).scheme);
        when(schemes.isDefaultIssueTypeScheme(any(FieldConfigScheme.class))).thenAnswer(call ->
                call.<FieldConfigScheme>getArgument(0).getId() == DEFAULT_SCHEME);
        when(schemes.getIssueTypesForScheme(any(FieldConfigScheme.class))).thenAnswer(call ->
                typesOf(schemeStates.get(call.<FieldConfigScheme>getArgument(0).getId())));
        when(schemes.getDefaultValue(any(FieldConfig.class))).thenAnswer(call -> {
            for (SchemeState state : schemeStates.values()) {
                if (state.config == call.getArgument(0)) return state.defaultTypeId == null ? null : typeById(state.defaultTypeId);
            }
            return null;
        });
        when(schemes.getConfigScheme(any(Project.class))).thenAnswer(call ->
                schemeOf(call.<Project>getArgument(0)).scheme);
        when(schemes.getIssueTypesForProject(any(Project.class))).thenAnswer(call ->
                typesOf(schemeOf(call.<Project>getArgument(0))));
        when(schemes.getDefaultIssueType(any(Project.class))).thenAnswer(call -> {
            SchemeState state = schemeOf(call.<Project>getArgument(0));
            return state.defaultTypeId == null ? null : typeById(state.defaultTypeId);
        });
        when(schemes.getAllSchemes()).thenAnswer(call -> {
            List<FieldConfigScheme> all = new ArrayList<FieldConfigScheme>();
            for (SchemeState state : schemeStates.values()) all.add(state.scheme);
            return all;
        });
        when(schemes.create(anyString(), anyString(), anyList())).thenAnswer(call -> {
            if (failSchemeCreate) throw new IllegalStateException("Jira could not create the scheme");
            SchemeState state = new SchemeState(nextScheme++, call.getArgument(0));
            state.typeIds.addAll(call.<List<String>>getArgument(2));
            schemeStates.put(state.id, state);
            return state.scheme;
        });
        when(schemes.update(any(FieldConfigScheme.class), anyCollection())).thenAnswer(call -> {
            SchemeState state = schemeStates.get(call.<FieldConfigScheme>getArgument(0).getId());
            state.typeIds.clear();
            state.typeIds.addAll(call.<Collection<String>>getArgument(1));
            return state.scheme;
        });
        doAnswer(call -> {
            for (SchemeState state : schemeStates.values()) {
                if (state.config == call.getArgument(0)) state.defaultTypeId = call.getArgument(1);
            }
            return null;
        }).when(schemes).setDefaultValue(any(FieldConfig.class), anyString());
        doAnswer(call -> {
            SchemeState target = schemeStates.get(call.<FieldConfigScheme>getArgument(0).getId());
            for (Project project : call.<Collection<Project>>getArgument(1)) {
                schemeStates.get(projectScheme.get(project.getId())).associated.remove(project);
                target.associated.add(project);
                projectScheme.put(project.getId(), target.id);
            }
            return null;
        }).when(schemes).addProjectAssociations(any(FieldConfigScheme.class), anyCollection());

        when(delegator.findListIteratorByCondition(anyString(), any(), isNull(), anyList(), isNull(), isNull()))
                .thenAnswer(call -> iterator());
        when(projectManager.getProjectObjects()).thenAnswer(call -> new ArrayList<Project>(projects));
        when(projectManager.getArchivedProjects()).thenReturn(Collections.<Project>emptyList());
        when(projectManager.getAllProjectKeys(anyLong())).thenReturn(Collections.<String>emptySet());
        when(workflowSchemes.getWorkflowSchemeObj(any(Project.class))).thenReturn(mock(AssignableWorkflowScheme.class));
        when(migrator.migrate(any(), anyList(), any(), any())).thenReturn(0);
    }

    public void verifyMigrationCalls(int times) {
        verify(migrator, times(times)).migrate(any(), anyList(), any(), any());
    }

    private IssueType create(String name, boolean subtask) {
        createCalls.add(name);
        String id = String.valueOf(nextId++);
        IssueType type = mock(IssueType.class);
        when(type.getId()).thenReturn(id);
        when(type.getName()).thenReturn(name);
        when(type.getDescription()).thenReturn("");
        when(type.isSubTask()).thenReturn(subtask);
        if (name.equals(failCreationOf)) {
            if (failureLeavesType) catalog.add(type);
            if (failReadAfterFailure) readsFailing = true;
            throw new IllegalStateException("Jira storage error");
        }
        catalog.add(type);
        schemeStates.get(DEFAULT_SCHEME).typeIds.add(id); // Jira adds every new type to its default scheme
        return type;
    }

    private List<IssueType> typesOf(SchemeState state) {
        List<IssueType> result = new ArrayList<IssueType>();
        for (String id : state.typeIds) result.add(typeById(id));
        return result;
    }

    private OfBizListIterator iterator() {
        final List<String[]> rows = new ArrayList<String[]>(issueRows);
        OfBizListIterator iterator = mock(OfBizListIterator.class);
        when(iterator.next()).thenAnswer(call -> {
            if (rows.isEmpty()) return null;
            String[] row = rows.remove(0);
            GenericValue value = mock(GenericValue.class);
            when(value.getString("type")).thenReturn(row[0]);
            when(value.getString("status")).thenReturn(row[1]);
            return value;
        });
        return iterator;
    }
}
