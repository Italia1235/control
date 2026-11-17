package ru.rasulov.projects.impl;

import com.atlassian.jira.bc.project.ProjectCreationData;
import com.atlassian.jira.bc.project.ProjectService;
import com.atlassian.jira.bc.user.search.UserSearchService;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.template.ProjectTemplate;
import com.atlassian.jira.project.template.ProjectTemplateKey;
import com.atlassian.jira.project.template.ProjectTemplateManager;
import com.atlassian.jira.user.ApplicationUser;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.TestLinks;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.common.impl.undo.UndoHandler;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.issuetypes.impl.CreatedIssueTypeUndoHandler;
import ru.rasulov.issuetypes.impl.IssueTypeBatchPlanner;
import ru.rasulov.issuetypes.impl.IssueTypeBatchWriter;
import ru.rasulov.issuetypes.impl.IssueTypeSetExecutor;
import ru.rasulov.issuetypes.impl.IssueTypeSetPlanner;
import ru.rasulov.issuetypes.impl.IssueTypeSetsImpl;
import ru.rasulov.issuetypes.impl.IssueTypeSetWriter;
import ru.rasulov.issuetypes.impl.ProjectIssueTypeServiceImpl;
import ru.rasulov.issuetypes.impl.ProjectTypeWorld;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real planner/writer/executor/creator/service and the AO/HSQL journal over {@link ProjectTypeWorld}; only Jira's
 * managers are doubles. One environment per test; close it.
 */
public final class ProjectFlowEnv implements AutoCloseable {
    public final ProjectTypeWorld world = new ProjectTypeWorld();
    public final UndoTestFixture fixture;
    public final ProjectCreatorImpl creator;
    public final ProjectIssueTypeServiceImpl service;
    public final ProjectResolver projectResolver = mock(ProjectResolver.class);
    public final ProjectService.CreateProjectValidationResult valid = mock(ProjectService.CreateProjectValidationResult.class);
    public int projectCreations;
    public String createdProjectName = "Alpha";
    public final ProjectTemplateManager templates = mock(ProjectTemplateManager.class);

    /** Compensation stand-in for project steps: these tests never undo, and it never claims it could. */
    private static final class ProjectStepHandler implements UndoHandler {
        public String kind() { return "project.created"; }
        public List<String> lockNames(UndoStep step) { return Collections.<String>emptyList(); }
        public String conflict(UndoStep step) { return "not exercised by these tests"; }
        public void undo(UndoStep step, ApplicationUser actor) { throw new AssertionError("undo must not run"); }
    }

    public ProjectFlowEnv() {
        fixture = UndoTestFixture.create("admin", new CreatedIssueTypeUndoHandler(world.undoSupport), new ProjectStepHandler());
        ApplicationUser actor = fixture.currentActor.get();
        when(actor.getUsername()).thenReturn("admin");
        when(fixture.users.getUserByName("admin")).thenReturn(actor);
        // templates is the field: tests read the catalog and the vanishing of a template through it
        when(templates.getProjectTemplate(any(ProjectTemplateKey.class))).thenAnswer(call -> {
            ProjectTemplateKey key = call.getArgument(0);
            ProjectTemplate template = mock(ProjectTemplate.class);
            when(template.getKey()).thenReturn(key);
            when(template.getProjectTypeKey()).thenReturn(new com.atlassian.jira.project.type.ProjectTypeKey(
                    key.getKey().startsWith("com.pyxis") ? "software" : "business"));
            return Optional.of(template);
        });
        when(valid.isValid()).thenReturn(true);
        when(world.projectService.validateCreateProject(any(), any(ProjectCreationData.class))).thenReturn(valid);
        when(world.projectService.createProject(valid)).thenAnswer(call -> {
            projectCreations++;
            return world.createProject("ALPHA", createdProjectName);
        });
        ProjectUndoSupport undoSupport = mock(ProjectUndoSupport.class);
        when(undoSupport.json(any())).thenReturn("{}");
        when(undoSupport.before(any())).thenReturn(new HashMap<String, Object>());
        when(undoSupport.intent(any())).thenReturn(new HashMap<String, Object>());
        when(undoSupport.after(any(), any())).thenReturn(new HashMap<String, Object>());

        IssueTypeBatchPlanner batchPlanner = new IssueTypeBatchPlanner(world.types, world.avatars);
        IssueTypeSetPlanner setPlanner = new IssueTypeSetPlanner(world.resolver, batchPlanner);
        IssueTypeSetWriter setWriter = new IssueTypeSetWriter(world.schemes, world.migrator, fixture.journal);
        IssueTypeSetExecutor executor = new IssueTypeSetExecutor(
                new IssueTypeBatchWriter(world.types, fixture.journal, world.undoSupport), world.changePlanner, setWriter);
        creator = new ProjectCreatorImpl(world.projectService, world.projectManager, templates, fixture.users,
                mock(UserSearchService.class), fixture.guard, fixture.locks, fixture.plans, new ActionPlanCodec(),
                fixture.journal, undoSupport, TestLinks.links(), new IssueTypeSetsImpl(setPlanner, executor, fixture.locks));
        service = new ProjectIssueTypeServiceImpl(fixture.guard, fixture.locks, projectResolver, world.changePlanner,
                setWriter, setPlanner, executor, new ru.rasulov.common.impl.PreviewPlanExecutor(fixture.plans, new ActionPlanCodec()),
                new ActionPlanCodec(), fixture.journal, TestLinks.links());
        when(projectResolver.resolve(any())).thenAnswer(call -> {
            Project project = world.projectByKey(call.getArgument(0));
            if (project == null) throw new AssertionError("unknown test project " + call.<String>getArgument(0));
            return project;
        });
    }

    @Override public void close() { fixture.close(); }

    /** Runs one apply the way the operation runner does: an owned journal operation with its own change set. */
    public <T> T runApply(String toolName, java.util.function.Supplier<T> apply) {
        String session = fixture.journal.createSession("admin");
        String set = fixture.journal.createChangeSet(session, "admin");
        String operation = fixture.journal.openOperation(toolName, "admin", session, set);
        lastOperation = operation;
        return fixture.journal.runOperation(operation, apply);
    }

    public String lastOperation;

}
