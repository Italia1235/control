package it.ru.rasulov.workflow;

import com.atlassian.jira.bc.ServiceOutcome;
import com.atlassian.jira.bc.config.StatusService;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.bc.project.ProjectCreationData;
import com.atlassian.jira.bc.project.ProjectService;
import com.atlassian.jira.bc.workflow.WorkflowSchemeService;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.IssueWorkflowManager;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugins.osgi.test.AtlassianPluginsTestRunner;
import com.opensymphony.workflow.loader.ActionDescriptor;
import it.ru.rasulov.WiredTestScope;
import org.ofbiz.core.entity.GenericValue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.rasulov.workflow.api.WorkflowAssigner;
import ru.rasulov.workflow.api.WorkflowConfigurer;
import ru.rasulov.workflow.api.WorkflowDraftPublisher;
import ru.rasulov.workflow.api.WorkflowReader;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.WorkflowStatusView;
import ru.rasulov.workflow.api.dto.assign.AssignErrorCode;
import ru.rasulov.workflow.api.dto.assign.AssignStatus;
import ru.rasulov.workflow.api.dto.assign.AssignVerdict;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowRequest;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowResponse;
import ru.rasulov.workflow.api.dto.assign.IssueTypeAssignment;
import ru.rasulov.workflow.api.dto.assign.IssueTypeWorkflowView;
import ru.rasulov.workflow.api.dto.assign.ProjectWorkflowsResponse;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.common.api.dto.StatusMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Assigning a workflow to a project in a real Jira. It proves what mocks cannot: that the scheme changes, that the
 * issues run on the new workflow afterwards (their osworkflow entry, and the transitions they are offered) with the
 * status they had, and that a refused call changes nothing.
 * <p>
 * Two uniquely keyed throw-away projects, each on a scheme of its own whose default workflow is the built-in
 * {@code jira}. The target workflow is made with the workflow creator from statuses every Jira has. Only resources
 * recorded after successful creation are removed afterwards: projects first (with their issues), then schemes,
 * workflows and newly created statuses.
 */
@RunWith(AtlassianPluginsTestRunner.class)
public class WorkflowAssignWiredTest {

    private final WiredTestScope scope = new WiredTestScope();
    private final String PREFIX = scope.prefix("wa");
    private final String KEY = scope.projectKey("WTA");
    private final String OTHER_KEY = scope.projectKey("WTB");
    private static final String BUILT_IN = JiraWorkflow.DEFAULT_WORKFLOW_NAME;

    private final WorkflowAssigner assigner;
    private final WorkflowDraftPublisher publisher;
    private final WorkflowReader reader;
    private final WorkflowConfigurer creator;
    private final WorkflowManager workflowManager;
    private final WorkflowSchemeManager schemeManager;
    private final WorkflowSchemeService schemeService;
    private final IssueWorkflowManager issueWorkflowManager;
    private final IssueService issueService;
    private final IssueManager issueManager;
    private final ProjectService projectService;
    private final ProjectManager projectManager;
    private final JiraAuthenticationContext authenticationContext;
    private final UserManager userManager;
    private final OfBizDelegator delegator;
    private final StatusManager statusManager;
    private final StatusService statusService;
    private boolean loggedIn;
    private Long createdWorkflowDraftId;
    private ApplicationUser previousUser;
    private ApplicationUser admin;
    private String workflowName;

    public WorkflowAssignWiredTest(WorkflowAssigner assigner, WorkflowConfigurer creator, WorkflowManager workflowManager,
                                   WorkflowSchemeManager schemeManager, WorkflowSchemeService schemeService,
                                   WorkflowDraftPublisher publisher, WorkflowReader reader,
                                   IssueWorkflowManager issueWorkflowManager, IssueService issueService,
                                   IssueManager issueManager, ProjectService projectService,
                                   ProjectManager projectManager, JiraAuthenticationContext authenticationContext,
                                   UserManager userManager, OfBizDelegator delegator,
                                   StatusManager statusManager, StatusService statusService) {
        this.assigner = ru.rasulov.common.impl.LegacyWriteCalls.assignment(assigner);
        this.publisher = publisher;
        this.reader = reader;
        this.creator = creator;
        this.workflowManager = workflowManager;
        this.schemeManager = schemeManager;
        this.schemeService = schemeService;
        this.issueWorkflowManager = issueWorkflowManager;
        this.issueService = issueService;
        this.issueManager = issueManager;
        this.projectService = projectService;
        this.projectManager = projectManager;
        this.authenticationContext = authenticationContext;
        this.userManager = userManager;
        this.delegator = delegator;
        this.statusManager = statusManager;
        this.statusService = statusService;
    }

    @Before
    public void logInAndSetUp() throws Exception {
        scope.requireEnabled();
        previousUser = authenticationContext.getLoggedInUser();
        admin = userManager.getUserByName("admin");
        assertNotNull("the 'admin' user is required by this test", admin);
        authenticationContext.setLoggedInUser(admin);
        loggedIn = true;
        workflowName = PREFIX + "workflow";
    }

    @After
    public void cleanUpAndRestoreUser() throws Exception {
        if (!loggedIn) {
            return;
        }
        try {
            cleanUp();
        } finally {
            authenticationContext.setLoggedInUser(previousUser);
            loggedIn = false;
        }
    }

    // --- reading -------------------------------------------------------------------------------------------------

    @Test
    public void readingShowsTheWorkflowOfEveryTypeAndTheIssuesByStatus() throws Exception {
        Project project = newProject(KEY);
        IssueType type = mainType(project);
        createIssue(project, type);
        Issue moving = createIssue(project, type);
        transitionTo(moving, "In Progress");

        ProjectWorkflowsResponse response = assigner.projectWorkflows(KEY, true);

        assertEquals(KEY, response.getProject().getKey());
        assertFalse(response.getScheme().isDefault());
        assertEquals(0, response.getScheme().getOtherProjects());
        IssueTypeWorkflowView view = viewOf(response, type);
        assertEquals(BUILT_IN, view.getWorkflow());
        assertEquals(Integer.valueOf(2), view.getIssues());
        assertEquals("In Progress", view.getStatuses().get(0).getName());
        assertEquals("Open", view.getStatuses().get(1).getName());
        assertNull(view.getStatuses().get(0).getInWorkflow());
    }

    // --- assigning -----------------------------------------------------------------------------------------------

    @Test
    public void anEmptyTypeIsReadyAndOnlyTheOwnSchemeChangesInPlace() throws Exception {
        Project project = newProject(KEY);
        IssueType type = mainType(project);
        createFlow();
        Long schemeId = schemeManager.getWorkflowSchemeObj(project).getId();

        AssignWorkflowResponse response = assignWorkflow(request(false, KEY, false, false, type.getName()));

        assertEquals(response.getMessage(), AssignStatus.ASSIGNED, response.getStatus());
        assertEquals(SchemeAction.UPDATE, response.getScheme().getAction());
        assertEquals(AssignVerdict.READY, response.getIssueTypes().get(0).getVerdict());
        AssignableWorkflowScheme scheme = schemeManager.getWorkflowSchemeObj(project);
        assertEquals("the same scheme", schemeId, scheme.getId());
        assertEquals(workflowName, scheme.getActualWorkflow(type.getId()));
        assertEquals("the other types keep their workflow", BUILT_IN,
                scheme.getActualWorkflow(subTaskType(project).getId()));
    }

    /**
     * Jira's cache of active workflows stayed stale after the in-place scheme update: the new workflow was reported
     * inactive, so "Edit" opened it live and a draft could not be made or published.
     */
    @Test
    public void aWorkflowAssignedInPlaceIsActiveAtOnceSoItCanBeEditedThroughADraft() throws Exception {
        newProject(KEY);
        createFlow();

        AssignWorkflowResponse response = assignWorkflow(request(false, KEY, true, false));

        assertEquals(response.getMessage(), SchemeAction.UPDATE, response.getScheme().getAction());
        assertTrue("the assigned workflow is active", workflowManager.getWorkflow(workflowName).isActive());
        createOwnedWorkflowDraft();
        try {
            requireOwnedWorkflow(workflowName);
            assertTrue("refusing an unowned or replaced draft", ownsWorkflowDraft(workflowName));
            workflowManager.overwriteActiveWorkflow(admin, workflowName);
        } finally {
            deleteOwnedWorkflowDraft(workflowName);
        }
    }

    /**
     * The CREATE branch connects a new scheme with remove + add, which does not reset the cache of active workflows.
     * The cache is filled first (the workflow is inactive), then the scheme is connected.
     */
    @Test
    public void aWorkflowConnectedThroughANewSchemeIsActiveAtOnceAndIsEditedThroughADraft() throws Exception {
        Project project = newProject(KEY);
        IssueType type = mainType(project);
        createFlow();
        Issue open = createIssue(project, type);
        Issue started = transitionTo(createIssue(project, type), "In Progress");
        assertFalse("not used by any project yet", workflowManager.isActive(workflowManager.getWorkflow(workflowName)));

        AssignWorkflowResponse response = assignWorkflow(request(false, KEY, false, true, type.getName()));

        assertEquals(response.getMessage(), AssignStatus.ASSIGNED, response.getStatus());
        assertEquals(SchemeAction.CREATE, response.getScheme().getAction());
        assertEquals(workflowName, schemeManager.getWorkflowSchemeObj(project).getActualWorkflow(type.getId()));
        assertEquals(workflowName, entryWorkflow(reload(open)));
        assertEquals("Open", reload(open).getStatus().getName());
        assertEquals("In Progress", reload(started).getStatus().getName());
        WorkflowDraftLifecycle.assertActiveEditedThroughDraftAndPublished(workflowManager, reader, creator, publisher,
                workflowName);
        assertEquals(Arrays.asList("Start"), actions(reload(open)));
        assertEquals(Arrays.asList("Finish"), actions(reload(started)));
        assertEquals("Closed", transition(reload(started), "Finish").getStatus().getName());
    }

    @Test
    public void issuesMoveKeepingTheirStatusAndThenFollowTheNewWorkflow() throws Exception {
        Project project = newProject(KEY);
        IssueType type = mainType(project);
        createFlow();
        Issue open = createIssue(project, type);
        Issue started = createIssue(project, type);
        started = transitionTo(started, "In Progress");
        assertEquals(BUILT_IN, entryWorkflow(open));

        AssignWorkflowResponse dry = assignWorkflow(request(true, KEY, false, false, type.getName()));
        assertEquals(dry.getMessage(), AssignStatus.WOULD_ASSIGN, dry.getStatus());
        assertEquals(AssignVerdict.MIGRATE, dry.getIssueTypes().get(0).getVerdict());
        assertEquals(2, dry.getIssueTypes().get(0).getIssues());
        assertEquals("a dry run moves nothing", BUILT_IN, entryWorkflow(open));
        assertEquals("a dry run changes no scheme", BUILT_IN,
                schemeManager.getWorkflowSchemeObj(project).getActualWorkflow(type.getId()));

        AssignWorkflowResponse response = assignWorkflow(request(false, KEY, false, false, type.getName()));

        assertEquals(response.getMessage(), AssignStatus.ASSIGNED, response.getStatus());
        open = reload(open);
        started = reload(started);
        assertEquals("Open", open.getStatus().getName());
        assertEquals("In Progress", started.getStatus().getName());
        assertEquals("the entry of the issue runs the new workflow", workflowName, entryWorkflow(open));
        assertEquals(workflowName, entryWorkflow(started));
        assertEquals("only the transitions of the new workflow are offered", Arrays.asList("Start"), actions(open));
        assertEquals(Arrays.asList("Finish"), actions(started));
        Issue finished = transition(started, "Finish");
        assertEquals("Closed", finished.getStatus().getName());
        assertEquals(Arrays.asList("Reopen"), actions(finished));
    }

    @Test
    public void aMissingStatusRefusesTheWholeCallUntilItIsExplicitlyMapped() throws Exception {
        Project project = newProject(KEY);
        IssueType main = mainType(project);
        IssueType subTask = subTaskType(project);
        createSmallFlow();
        // the sub-task is fine (Open), its parent is not (In Progress, which the workflow lacks)
        Issue parent = createIssue(project, main);
        Issue fine = createSubTask(project, parent, subTask);
        Issue blocked = transitionTo(parent, "In Progress");
        AssignableWorkflowScheme before = schemeManager.getWorkflowSchemeObj(project);

        AssignWorkflowResponse response = assignWorkflow(request(false, KEY, false, false, subTask.getName(), main.getName()));

        assertEquals(AssignStatus.FAILED, response.getStatus());
        assertEquals(AssignErrorCode.STATUS_CONFLICT, response.getErrorCode());
        assertEquals(AssignVerdict.MIGRATE, assignmentOf(response, subTask).getVerdict());
        IssueTypeAssignment conflict = assignmentOf(response, main);
        assertEquals(AssignVerdict.CONFLICT, conflict.getVerdict());
        assertEquals("In Progress", conflict.getStatuses().get(0).getName());
        assertFalse(conflict.getStatuses().get(0).getInWorkflow());
        AssignableWorkflowScheme after = schemeManager.getWorkflowSchemeObj(project);
        assertEquals(before.getId(), after.getId());
        assertEquals(BUILT_IN, after.getActualWorkflow(subTask.getId()));
        assertEquals("the type that was fine was not moved either", BUILT_IN, entryWorkflow(reload(fine)));
        assertEquals(BUILT_IN, entryWorkflow(reload(blocked)));

        AssignWorkflowRequest mappedRequest = new AssignWorkflowRequest(false, KEY, workflowName, false,
                Arrays.asList(subTask.getName(), main.getName()), false,
                Collections.singletonList(new StatusMapping("In Progress", "Closed")), null);
        AssignWorkflowResponse migrated = assignWorkflow(mappedRequest);

        assertEquals(migrated.getMessage(), AssignStatus.ASSIGNED, migrated.getStatus());
        assertEquals("Closed", reload(blocked).getStatus().getName());
        assertEquals("Open", reload(fine).getStatus().getName());
        assertEquals(workflowName, entryWorkflow(reload(blocked)));
        assertEquals(workflowName, entryWorkflow(reload(fine)));
    }

    @Test
    public void allIssueTypesMakesTheWorkflowTheDefaultAndDropsTheExplicitMappings() throws Exception {
        Project project = newProject(KEY);
        IssueType type = mainType(project);
        createFlow();
        Issue issue = createIssue(project, type);

        AssignWorkflowResponse response = assignWorkflow(request(false, KEY, true, false));

        assertEquals(response.getMessage(), AssignStatus.ASSIGNED, response.getStatus());
        AssignableWorkflowScheme scheme = schemeManager.getWorkflowSchemeObj(project);
        assertEquals(workflowName, scheme.getActualDefaultWorkflow());
        for (IssueType each : project.getIssueTypes()) {
            assertEquals(workflowName, scheme.getActualWorkflow(each.getId()));
        }
        assertEquals(workflowName, entryWorkflow(reload(issue)));
    }

    @Test
    public void aRepeatIsAlreadyAssignedAndWritesNothing() throws Exception {
        Project project = newProject(KEY);
        IssueType type = mainType(project);
        createFlow();
        assertEquals(AssignStatus.ASSIGNED, assignWorkflow(request(false, KEY, false, false, type.getName())).getStatus());

        AssignWorkflowResponse again = assignWorkflow(request(false, KEY, false, false, type.getName()));

        assertEquals(AssignStatus.ALREADY_ASSIGNED, again.getStatus());
        assertEquals(SchemeAction.NONE, again.getScheme().getAction());
        assertEquals(AssignVerdict.ALREADY, again.getIssueTypes().get(0).getVerdict());
    }

    // --- schemes -------------------------------------------------------------------------------------------------

    @Test
    public void aSchemeSharedWithAnotherProjectIsRefusedAndANewSchemeLeavesTheOtherProjectAlone() throws Exception {
        Project project = newProject(KEY);
        Project other = newProject(OTHER_KEY);
        AssignableWorkflowScheme shared = schemeManager.getWorkflowSchemeObj(project);
        detachProject(other);
        schemeManager.addSchemeToProject(other, schemeManager.getSchemeObject(shared.getId()));
        IssueType type = mainType(project);
        createFlow();
        Issue issue = createIssue(project, type);

        AssignWorkflowResponse refused = assignWorkflow(request(false, KEY, false, false, type.getName()));

        assertEquals(AssignErrorCode.SCHEME_SHARED, refused.getErrorCode());
        assertEquals(Collections.singletonList(other.getName()), refused.getSuggestions());
        assertEquals("nothing was moved", BUILT_IN, entryWorkflow(reload(issue)));
        assertEquals(shared.getId(), schemeManager.getWorkflowSchemeObj(project).getId());

        AssignWorkflowResponse response = assignWorkflow(request(false, KEY, false, true, type.getName()));

        assertEquals(response.getMessage(), AssignStatus.ASSIGNED, response.getStatus());
        assertEquals(SchemeAction.CREATE, response.getScheme().getAction());
        assertEquals(KEY + ": Workflow Scheme", response.getScheme().getName());
        AssignableWorkflowScheme mine = schemeManager.getWorkflowSchemeObj(project);
        assertEquals(response.getScheme().getId(), mine.getId());
        assertEquals(workflowName, mine.getActualWorkflow(type.getId()));
        assertEquals("the copy keeps the other mappings", BUILT_IN,
                mine.getActualWorkflow(subTaskType(project).getId()));
        AssignableWorkflowScheme theirs = schemeManager.getWorkflowSchemeObj(other);
        assertEquals("the other project still has the shared scheme", shared.getId(), theirs.getId());
        assertEquals(BUILT_IN, theirs.getActualWorkflow(type.getId()));
        assertEquals(workflowName, entryWorkflow(reload(issue)));
    }

    @Test
    public void aProjectOnTheSystemDefaultSchemeGetsANewOneAndTheDefaultIsNotTouched() throws Exception {
        Project project = newProject(KEY);
        detachProject(project);
        assertTrue(schemeManager.isUsingDefaultScheme(project));
        IssueType type = mainType(project);
        createFlow();
        String defaultWorkflowBefore = schemeManager.getDefaultWorkflowScheme().getActualDefaultWorkflow();

        AssignWorkflowResponse response = assignWorkflow(request(false, KEY, false, false, type.getName()));

        assertEquals(response.getMessage(), AssignStatus.ASSIGNED, response.getStatus());
        assertEquals(SchemeAction.CREATE, response.getScheme().getAction());
        assertTrue(response.getMessage(), response.getMessage().contains("system default"));
        assertFalse(schemeManager.isUsingDefaultScheme(project));
        assertEquals(workflowName, schemeManager.getWorkflowSchemeObj(project).getActualWorkflow(type.getId()));
        assertEquals(defaultWorkflowBefore, schemeManager.getDefaultWorkflowScheme().getActualDefaultWorkflow());
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private ConfigureWorkflowResponse createWorkflow(ConfigureWorkflowRequest request) {
        ConfigureWorkflowResponse response = creator.configure(new ConfigureWorkflowRequest(true, request.create(),
                request.existing(), request.newScreens(), request.transitionRules(), null));
        if (!Boolean.TRUE.equals(request.isDryRun()) && response.getPlanId() != null) {
            response = creator.configure(new ConfigureWorkflowRequest(false, null, null, null, null, response.getPlanId()));
        }
        if (!response.isDryRun() && "CREATED".equals(response.getStatus())) {
            for (WorkflowStatusView status : response.getStatuses()) {
                if (status.isNew() && status.getId() != null) {
                    scope.record("status", status.getId(), status.getName());
                }
            }
            recordWorkflow(response.getWorkflow());
        }
        return response;
    }

    private AssignWorkflowResponse assignWorkflow(AssignWorkflowRequest request) {
        Project project = projectManager.getProjectObjByKey(request.getProject());
        assertNotNull("the assignment project must be owned by this test", project);
        requireOwnedProject(project);
        AssignWorkflowResponse response = assigner.assign(request);
        if (!response.isDryRun() && (response.getStatus() == AssignStatus.ASSIGNED
                || response.getStatus() == AssignStatus.PARTIALLY_ASSIGNED)
                && response.getScheme() != null && response.getScheme().getAction() == SchemeAction.CREATE
                && response.getScheme().getId() != null) {
            scope.record("scheme", response.getScheme().getId().toString(), response.getScheme().getName());
        }
        return response;
    }

    private void recordWorkflow(String name) {
        List<GenericValue> rows = delegator.findByAnd("Workflow", Collections.<String, Object>singletonMap("name", name));
        if (rows.size() == 1) {
            scope.record("workflow", rows.get(0).getLong("id").toString(), name);
        }
    }

    private boolean ownsWorkflow(String name) {
        List<GenericValue> rows = delegator.findByAnd("Workflow", Collections.<String, Object>singletonMap("name", name));
        return rows.size() == 1 && scope.owns("workflow", rows.get(0).getLong("id").toString(), name);
    }

    private void requireOwnedWorkflow(String name) {
        assertTrue("refusing an unowned or replaced workflow", ownsWorkflow(name));
    }

    private void createOwnedWorkflowDraft() {
        requireOwnedWorkflow(workflowName);
        assertNull("refusing an existing workflow draft", workflowManager.getDraftWorkflow(workflowName));
        JiraWorkflow draft = workflowManager.createDraftWorkflow(admin, workflowName);
        List<GenericValue> rows = delegator.findByAnd("DraftWorkflow",
                Collections.<String, Object>singletonMap("parentname", draft.getName()));
        if (rows.size() == 1) {
            createdWorkflowDraftId = rows.get(0).getLong("id");
        }
    }

    private boolean ownsWorkflowDraft(String name) {
        if (createdWorkflowDraftId == null || !ownsWorkflow(name)) {
            return false;
        }
        GenericValue row = delegator.findByPrimaryKey("DraftWorkflow", createdWorkflowDraftId);
        return row != null && name.equals(row.getString("parentname"));
    }

    private void deleteOwnedWorkflowDraft(String name) {
        if (ownsWorkflowDraft(name) && workflowManager.getDraftWorkflow(name) != null) {
            workflowManager.deleteDraftWorkflow(name);
        }
    }

    private void requireOwnedProject(Project project) {
        Project live = projectManager.getProjectObj(project.getId());
        assertTrue("refusing an unowned or changed project", live != null
                && project.getKey().equals(live.getKey())
                && scope.owns("project", live.getId().toString(), live.getName()));
        Project byKey = projectManager.getProjectObjByKey(live.getKey());
        assertTrue("project key now belongs to another project", byKey != null && live.getId().equals(byKey.getId()));
    }

    private void detachProject(Project project) {
        requireOwnedProject(project);
        schemeManager.removeSchemesFromProject(project);
    }

    private AssignWorkflowRequest request(boolean dryRun, String project, boolean all, boolean newScheme, String... types) {
        return new AssignWorkflowRequest(dryRun, project, workflowName, all, Arrays.asList(types), newScheme);
    }

    /** Open -> In Progress -> Closed -> Open: only statuses every Jira has, with names unlike the built-in transitions. */
    private void createFlow() {
        ConfigureWorkflowRequest flow = new ConfigureWorkflowRequest(false,
                new WorkflowDefinition(workflowName, "wired test",
                        Arrays.asList(new StatusDefinition("Open", null, null), new StatusDefinition("In Progress", null, null),
                                new StatusDefinition("Closed", null, null)), "Open",
                        Arrays.asList(new TransitionDefinition("Start", "Start", "Open", false, "In Progress", null, null),
                                finish("Finish", "In Progress", "Closed"),
                                new TransitionDefinition("Reopen", "Reopen", "Closed", false, "Open", null, null))),
                null, null, null, null);
        assertEquals("CREATED", createWorkflow(flow).getStatus());
    }

    /** Open -> Closed -> Open: no "In Progress", so an issue in it has nowhere to stay. */
    private void createSmallFlow() {
        ConfigureWorkflowRequest flow = new ConfigureWorkflowRequest(false,
                new WorkflowDefinition(workflowName, "wired test",
                        Arrays.asList(new StatusDefinition("Open", null, null), new StatusDefinition("Closed", null, null)),
                        "Open", Arrays.asList(finish("Close", "Open", "Closed"),
                                new TransitionDefinition("Reopen", "Reopen", "Closed", false, "Open", null, null))),
                null, null, null, null);
        assertEquals("CREATED", createWorkflow(flow).getStatus());
    }

    private static TransitionDefinition finish(String name, String from, String to) {
        com.atlassian.jira.issue.resolution.Resolution resolution =
                com.atlassian.jira.component.ComponentAccessor.getConstantsManager().getResolutions().iterator().next();
        return new TransitionDefinition(name, name, from, false, to, null, new ru.rasulov.workflow.api.dto.TransitionResolution("SET", resolution.getId()));
    }

    /** A project made from an existing one (for its issue types) and given a scheme of its own on the built-in workflow. */
    private Project newProject(String key) {
        assertNull("refusing an existing project key", projectManager.getProjectObjByKey(key));
        Project template = null;
        for (Project candidate : projectManager.getProjects()) {
            if (!KEY.equals(candidate.getKey()) && !OTHER_KEY.equals(candidate.getKey())
                    && mainType(candidate) != null && subTaskType(candidate) != null) {
                template = candidate;
                break;
            }
        }
        assertNotNull("the instance needs a project with an issue type and a sub-task type", template);
        ProjectCreationData data = new ProjectCreationData.Builder().withName("Wired test " + key).withKey(key)
                .withLead(admin).withType("business").build();
        ProjectService.CreateProjectValidationResult validation =
                projectService.validateCreateProjectBasedOnExistingProject(admin, template.getId(), data);
        assertTrue("cannot create the test project: " + validation.getErrorCollection(), validation.isValid());
        Project project = projectService.createProject(validation);
        scope.record("project", project.getId().toString(), project.getName());

        AssignableWorkflowScheme scheme = schemeManager.createScheme(schemeManager.assignableBuilder()
                .setName(PREFIX + "scheme-" + key).setDefaultWorkflow(BUILT_IN).build());
        scope.record("scheme", scheme.getId().toString(), scheme.getName());
        // addSchemeToProject only adds: a project made from a template already has the template's scheme
        detachProject(project);
        schemeManager.addSchemeToProject(project, schemeManager.getSchemeObject(scheme.getId()));
        return projectManager.getProjectObj(project.getId());
    }

    /** The first issue type that is not a sub-task; {@code null} if the project has none. */
    private static IssueType mainType(Project project) {
        for (IssueType type : project.getIssueTypes()) {
            if (!type.isSubTask()) {
                return type;
            }
        }
        return null;
    }

    /** The first sub-task type; {@code null} if the project has none. */
    private static IssueType subTaskType(Project project) {
        for (IssueType type : project.getIssueTypes()) {
            if (type.isSubTask()) {
                return type;
            }
        }
        return null;
    }

    private Issue createSubTask(Project project, Issue parent, IssueType type) {
        IssueService.CreateValidationResult validation = issueService.validateSubTaskCreate(admin, parent.getId(),
                issueService.newIssueInputParameters().setProjectId(project.getId()).setIssueTypeId(type.getId())
                        .setSummary("wired test sub-task").setReporterId(admin.getName()));
        assertTrue("cannot create the sub-task: " + validation.getErrorCollection(), validation.isValid());
        IssueService.IssueResult created = issueService.create(admin, validation);
        assertTrue("sub-task not created: " + created.getErrorCollection(), created.isValid());
        return created.getIssue();
    }

    private static IssueTypeAssignment assignmentOf(AssignWorkflowResponse response, IssueType type) {
        for (IssueTypeAssignment assignment : response.getIssueTypes()) {
            if (type.getId().equals(assignment.getIssueType().getId())) {
                return assignment;
            }
        }
        throw new AssertionError("issue type " + type.getName() + " is not in the answer");
    }

    private static IssueTypeWorkflowView viewOf(ProjectWorkflowsResponse response, IssueType type) {
        for (IssueTypeWorkflowView view : response.getIssueTypes()) {
            if (type.getId().equals(view.getIssueType().getId())) {
                return view;
            }
        }
        throw new AssertionError("issue type " + type.getName() + " is not in the answer");
    }

    private Issue createIssue(Project project, IssueType type) {
        IssueService.CreateValidationResult validation = issueService.validateCreate(admin,
                issueService.newIssueInputParameters().setProjectId(project.getId()).setIssueTypeId(type.getId())
                        .setSummary("wired test issue").setReporterId(admin.getName()));
        assertTrue("cannot create the issue: " + validation.getErrorCollection(), validation.isValid());
        IssueService.IssueResult created = issueService.create(admin, validation);
        assertTrue("issue not created: " + created.getErrorCollection(), created.isValid());
        return created.getIssue();
    }

    private Issue reload(Issue issue) {
        return issueManager.getIssueObject(issue.getId());
    }

    /** The name of the workflow the issue runs, from its osworkflow entry (not from the scheme). */
    private String entryWorkflow(Issue issue) throws Exception {
        return workflowManager.getStore().findEntry(issueManager.getIssueObject(issue.getId()).getWorkflowId())
                .getWorkflowName();
    }

    private List<String> actions(Issue issue) {
        List<String> names = new ArrayList<String>();
        for (ActionDescriptor action : issueWorkflowManager.getSortedAvailableActions(issue, admin)) {
            names.add(action.getName());
        }
        Collections.sort(names);
        return names;
    }

    private Issue transition(Issue issue, String actionName) {
        for (ActionDescriptor action : issueWorkflowManager.getAvailableActions(issue, admin)) {
            if (actionName.equals(action.getName())) {
                return run(issue, action);
            }
        }
        throw new AssertionError("'" + actionName + "' is not available for " + issue.getKey() + ": " + actions(issue));
    }

    /** Moves the issue with whichever available transition leads to the status (the built-in workflow names differ by locale). */
    private Issue transitionTo(Issue issue, String statusName) {
        for (ActionDescriptor action : issueWorkflowManager.getAvailableActions(issue, admin)) {
            String nextId = workflowManager.getNextStatusIdForAction(issue, action.getId());
            if (nextId != null && statusName.equals(workflowManagerStatusName(nextId))) {
                return run(issue, action);
            }
        }
        throw new AssertionError("no transition of " + issue.getKey() + " leads to '" + statusName + "': " + actions(issue));
    }

    private String workflowManagerStatusName(String statusId) {
        for (Status status : workflowManager.getWorkflow(BUILT_IN).getLinkedStatusObjects()) {
            if (status.getId().equals(statusId)) {
                return status.getName();
            }
        }
        return null;
    }

    private Issue run(Issue issue, ActionDescriptor action) {
        IssueService.TransitionValidationResult validation = issueService.validateTransition(admin, issue.getId(),
                action.getId(), issueService.newIssueInputParameters());
        assertTrue("cannot do '" + action.getName() + "': " + validation.getErrorCollection(), validation.isValid());
        IssueService.IssueResult result = issueService.transition(admin, validation);
        assertTrue("'" + action.getName() + "' failed: " + result.getErrorCollection(), result.isValid());
        return result.getIssue();
    }

    /** Order matters: projects (with their issues) first, then the schemes and workflows they used. */
    private void cleanUp() throws Exception {
        for (String id : scope.ids("project")) {
            Project project = projectManager.getProjectObj(Long.valueOf(id));
            if (project != null && scope.owns("project", id, project.getName())) {
                detachProject(project);
                ProjectService.DeleteProjectValidationResult validation =
                        projectService.validateDeleteProject(admin, project.getKey());
                assertTrue("cannot delete the test project: " + validation.getErrorCollection(), validation.isValid());
                Project validated = validation.getProject();
                assertTrue("refusing deletion of a foreign validated project", validated != null
                        && id.equals(validated.getId().toString())
                        && scope.owns("project", id, validated.getName()));
                requireOwnedProject(project);
                projectService.deleteProject(admin, validation);
            }
        }
        for (String id : scope.ids("scheme")) {
            AssignableWorkflowScheme scheme = schemeManager.getWorkflowSchemeObj(Long.parseLong(id));
            if (scheme != null && scope.owns("scheme", id, scheme.getName())) {
                assertFalse("refusing deletion of a scheme with an unowned draft", schemeManager.hasDraft(scheme));
                ServiceOutcome<Void> deleted = schemeService.deleteWorkflowScheme(admin, scheme);
                assertTrue("cannot delete scheme " + scheme.getName() + ": " + deleted.getErrorCollection(), deleted.isValid());
            }
        }
        for (String id : scope.ids("workflow")) {
            GenericValue row = delegator.findByPrimaryKey("Workflow", Long.valueOf(id));
            if (row != null && scope.owns("workflow", id, row.getString("name")) && ownsWorkflow(row.getString("name"))) {
                String name = row.getString("name");
                deleteOwnedWorkflowDraft(name);
                assertNull("refusing deletion of a workflow with an unowned draft", workflowManager.getDraftWorkflow(name));
                JiraWorkflow workflow = workflowManager.getWorkflow(name);
                if (workflow != null && ownsWorkflow(name)) {
                    workflowManager.deleteWorkflow(workflow);
                }
            }
        }
        for (String id : scope.ids("status")) {
            Status status = statusManager.getStatus(id);
            if (status != null && scope.owns("status", id, status.getName())) {
                assertTrue("cannot remove status " + status.getName(), statusService.removeStatus(admin, status).isValid());
            }
        }
    }
}
