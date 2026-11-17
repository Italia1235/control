package it.ru.rasulov.workflow;

import com.atlassian.jira.bc.ServiceOutcome;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.bc.config.StatusService;
import com.atlassian.jira.bc.project.ProjectCreationData;
import com.atlassian.jira.bc.project.ProjectService;
import com.atlassian.jira.bc.workflow.WorkflowSchemeService;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.Issue;
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
import ru.rasulov.workflow.api.WorkflowConfigurer;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import ru.rasulov.workflow.api.WorkflowStatusReader;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.StatusResponse;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.WorkflowStatusView;

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
 * Workflows in a real Jira. Besides the stored shape, it proves the part that matters: an issue of a throw-away
 * project, on a workflow scheme that maps every issue type to the created workflow, starts in the initial status and
 * moves along the transitions, including the "from any status" one.
 * <p>
 * Names and project keys are unique to this test instance. Only resources recorded after successful creation are
 * removed afterwards: the project first (with its issues), then its schemes, workflows and newly created statuses.
 */
@RunWith(AtlassianPluginsTestRunner.class)
public class WorkflowWiredTest {

    private final WiredTestScope scope = new WiredTestScope();
    private final String PREFIX = scope.prefix("wf");
    private final String STATUS_PREFIX = scope.prefix("ws");
    private final String KEY = scope.projectKey("WTW");

    private final WorkflowConfigurer creator;
    private final WorkflowStatusReader statusReader;
    private final WorkflowManager workflowManager;
    private final WorkflowSchemeManager schemeManager;
    private final WorkflowSchemeService schemeService;
    private final IssueWorkflowManager issueWorkflowManager;
    private final IssueService issueService;
    private final StatusManager statusManager;
    private final StatusService statusService;
    private final ProjectService projectService;
    private final ProjectManager projectManager;
    private final JiraAuthenticationContext authenticationContext;
    private final UserManager userManager;
    private final OfBizDelegator delegator;
    private boolean loggedIn;
    private ApplicationUser previousUser;
    private ApplicationUser admin;
    private String workflowName;
    private String reviewName;

    public WorkflowWiredTest(WorkflowConfigurer creator, WorkflowStatusReader statusReader, WorkflowManager workflowManager,
                             WorkflowSchemeManager schemeManager, WorkflowSchemeService schemeService,
                             IssueWorkflowManager issueWorkflowManager, IssueService issueService,
                             StatusManager statusManager, StatusService statusService,
                             ProjectService projectService, ProjectManager projectManager,
                             JiraAuthenticationContext authenticationContext, UserManager userManager,
                             OfBizDelegator delegator) {
        this.creator = creator;
        this.statusReader = statusReader;
        this.workflowManager = workflowManager;
        this.schemeManager = schemeManager;
        this.schemeService = schemeService;
        this.issueWorkflowManager = issueWorkflowManager;
        this.issueService = issueService;
        this.statusManager = statusManager;
        this.statusService = statusService;
        this.projectService = projectService;
        this.projectManager = projectManager;
        this.authenticationContext = authenticationContext;
        this.userManager = userManager;
        this.delegator = delegator;
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
        reviewName = STATUS_PREFIX + "Review";
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

    // --- what is stored ------------------------------------------------------------------------------------------

    @Test
    public void createsTheWorkflowWithItsStatusesTransitionsAndTheNewStatus() {
        ConfigureWorkflowResponse response = createWorkflow(request(false));

        assertEquals(response.getMessage(), "CREATED", response.getStatus());
        assertTrue(response.getStatuses().get(1).isNew());
        JiraWorkflow stored = workflowManager.getWorkflow(workflowName);
        assertNotNull(stored);
        assertEquals("wired test", stored.getDescription());
        assertEquals(Arrays.asList("Open", reviewName, "Done"), statusNames(stored));
        assertEquals(1, stored.getDescriptor().getGlobalActions().size());
        assertEquals("Reopen", ((ActionDescriptor) stored.getDescriptor().getGlobalActions().get(0)).getName());
        Status review = statusNamed(reviewName);
        assertNotNull("the missing status was created", review);
        assertEquals("indeterminate", review.getStatusCategory().getKey());
        assertEquals(review.getId(), response.getStatuses().get(1).getId());
    }

    @Test
    public void aDryRunCreatesNeitherTheWorkflowNorTheStatus() {
        ConfigureWorkflowResponse response = createWorkflow(request(true));

        assertEquals("WOULD_CREATE", response.getStatus());
        assertTrue(response.getStatuses().get(1).isNew());
        assertNull(response.getStatuses().get(1).getId());
        assertFalse(workflowManager.workflowExists(workflowName));
        assertNull(statusNamed(reviewName));
    }

    @Test
    public void aRepeatIsExistsAndAChangedRepeatIsANameConflictThatChangesNothing() {
        assertEquals("CREATED", createWorkflow(request(false)).getStatus());
        int statusesBefore = statusManager.getStatuses().size();

        ConfigureWorkflowResponse again = createWorkflow(request(false));
        assertEquals("EXISTS", again.getStatus());
        assertNull(again.getMessage());

        ConfigureWorkflowResponse changed = createWorkflow(new ConfigureWorkflowRequest(false,
                new WorkflowDefinition(workflowName, "wired test", statuses(), "Open", Arrays.asList(
                        new TransitionDefinition("Send to review", "Send to review", "Open", false, reviewName, null, null),
                        finish("Approve", reviewName, "Done"),
                        new TransitionDefinition("Reopen", "Reopen", null, true, "Open", null, null),
                        new TransitionDefinition("Extra", "Extra", "Done", false, "Open", null, null))),
                null, null, null, null));
        assertEquals("FAILED", changed.getStatus());
        assertEquals("NAME_CONFLICT", changed.getErrorCode());
        assertTrue(changed.getMessage(), changed.getMessage().contains("'Extra'"));

        assertEquals(statusesBefore, statusManager.getStatuses().size());
        for (Object action : workflowManager.getWorkflow(workflowName).getAllActions()) {
            assertFalse("the conflicting request changed the workflow",
                    "Extra".equals(((ActionDescriptor) action).getName()));
        }
    }

    @Test
    public void theNewStatusIsInTheListing() {
        createWorkflow(request(false));

        StatusResponse listed = null;
        for (StatusResponse status : statusReader.listStatuses(reviewName, null).getStatuses()) {
            if (reviewName.equals(status.getName())) {
                listed = status;
            }
        }
        assertNotNull(listed);
        assertEquals("IN_PROGRESS", listed.getCategory());
    }

    @Test
    public void anExistingStatusIsReusedNotDuplicated() {
        int before = statusManager.getStatuses().size();

        ConfigureWorkflowResponse response = createWorkflow(request(false));

        assertEquals("CREATED", response.getStatus());
        assertEquals("only the review status is new", before + 1, statusManager.getStatuses().size());
        assertFalse(response.getStatuses().get(0).isNew());
        assertFalse(response.getStatuses().get(2).isNew());
    }

    // --- what Jira does with it ----------------------------------------------------------------------------------

    @Test
    public void anIssueStartsInTheInitialStatusAndMovesAlongTheTransitionsIncludingFromAnyStatus() throws Exception {
        assertEquals("CREATED", createWorkflow(request(false)).getStatus());
        Project project = projectOnTheWorkflow();
        Issue issue = createIssue(project);

        assertEquals("Open", issue.getStatus().getName());
        assertEquals(Arrays.asList("Reopen", "Send to review"), availableActions(issue));

        issue = transition(issue, "Send to review");
        assertEquals(reviewName, issue.getStatus().getName());
        assertEquals(Arrays.asList("Approve", "Reopen"), availableActions(issue));

        issue = transition(issue, "Approve");
        assertEquals("Done", issue.getStatus().getName());
        assertEquals(request(true).create().getTransitions().get(1).getResolution().getValue(), issue.getResolutionId());
        assertEquals("from Done only the global transition is left", Arrays.asList("Reopen"), availableActions(issue));

        issue = transition(issue, "Reopen");
        assertEquals("Open", issue.getStatus().getName());
        assertNull("Reopen must persist an empty resolution", issue.getResolutionId());
        List<com.atlassian.jira.issue.history.ChangeItemBean> history =
                com.atlassian.jira.component.ComponentAccessor.getChangeHistoryManager()
                        .getChangeItemsForField(issue, "resolution");
        assertEquals("both completion and reopening record resolution history", 2, history.size());
        assertEquals(request(true).create().getTransitions().get(1).getResolution().getValue(), history.get(0).getTo());
        assertEquals(request(true).create().getTransitions().get(1).getResolution().getValue(), history.get(1).getFrom());
        assertNull(history.get(1).getTo());
        com.atlassian.jira.bc.issue.search.SearchService search =
                com.atlassian.jira.component.ComponentAccessor.getComponent(
                        com.atlassian.jira.bc.issue.search.SearchService.class);
        com.atlassian.jira.bc.issue.search.SearchService.ParseResult query =
                search.parseQuery(admin, "key = " + issue.getKey() + " AND resolution is empty");
        assertTrue(query.getErrors().toString(), query.isValid());
        assertEquals("reopened issue is reindexed as unresolved", 1L, search.searchCount(admin, query.getQuery()));
    }

    @Test
    public void terminalOutcomesLoopsAndBothGlobalDirectionsUseNativeResolutionFunctions() throws Exception {
        List<com.atlassian.jira.issue.resolution.Resolution> catalog = new ArrayList<>(
                com.atlassian.jira.component.ComponentAccessor.getConstantsManager().getResolutions());
        assertTrue("the instance needs two real resolutions", catalog.size() >= 2);
        String first = catalog.get(0).getId();
        String second = catalog.get(1).getId();
        ru.rasulov.workflow.api.dto.TransitionResolution setFirst =
                new ru.rasulov.workflow.api.dto.TransitionResolution("SET", first);
        ru.rasulov.workflow.api.dto.TransitionResolution setSecond =
                new ru.rasulov.workflow.api.dto.TransitionResolution("SET", second);
        ConfigureWorkflowRequest request = new ConfigureWorkflowRequest(false,
                new WorkflowDefinition(workflowName, "native resolution table",
                        Arrays.asList(new StatusDefinition("Open", null, null), new StatusDefinition("Done", null, null),
                                new StatusDefinition("Closed", null, null)), "Open", Arrays.asList(
                                new TransitionDefinition("Finish", "Finish", "Open", false, "Done", null, setFirst),
                                new TransitionDefinition("New outcome", "New outcome", "Done", false, "Closed", null, setSecond),
                                new TransitionDefinition("Keep outcome", "Keep outcome", "Closed", false, "Closed", null, null),
                                new TransitionDefinition("Change outcome", "Change outcome", "Done", false, "Done", null, setSecond),
                                new TransitionDefinition("Global finish", "Global finish", null, true, "Done", null, setFirst),
                                new TransitionDefinition("Global reopen", "Global reopen", null, true, "Open", null, null))),
                null, null, null, null);
        assertEquals("CREATED", createWorkflow(request).getStatus());
        Issue issue = createIssue(projectOnTheWorkflow());
        issue = transition(issue, "Finish");
        assertEquals(first, issue.getResolutionId());
        issue = transition(issue, "New outcome");
        assertEquals(second, issue.getResolutionId());
        issue = transition(issue, "Keep outcome");
        assertEquals(second, issue.getResolutionId());
        issue = transition(issue, "Global finish");
        assertEquals(first, issue.getResolutionId());
        issue = transition(issue, "Change outcome");
        assertEquals(second, issue.getResolutionId());
        issue = transition(issue, "Global finish");
        assertEquals(first, issue.getResolutionId());
        issue = transition(issue, "Global reopen");
        assertNull(issue.getResolutionId());
        issue = transition(issue, "Global reopen");
        assertNull(issue.getResolutionId());
        issue = transition(issue, "Global finish");
        assertEquals(first, issue.getResolutionId());
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private ConfigureWorkflowRequest request(boolean dryRun) {
        return new ConfigureWorkflowRequest(dryRun, new WorkflowDefinition(workflowName, "wired test", statuses(), "Open", Arrays.asList(
                new TransitionDefinition("Send to review", "Send to review", "Open", false, reviewName, null, null),
                finish("Approve", reviewName, "Done"),
                new TransitionDefinition("Reopen", "Reopen", null, true, "Open", null, null))), null, null, null, null);
    }

    private static TransitionDefinition finish(String name, String from, String to) {
        com.atlassian.jira.issue.resolution.Resolution resolution =
                com.atlassian.jira.component.ComponentAccessor.getConstantsManager().getResolutions().iterator().next();
        return new TransitionDefinition(name, name, from, false, to, null, new ru.rasulov.workflow.api.dto.TransitionResolution("SET", resolution.getId()));
    }

    private List<StatusDefinition> statuses() {
        return Arrays.asList(new StatusDefinition("Open", null, null),
                new StatusDefinition(reviewName, "IN_PROGRESS", null),
                new StatusDefinition("Done", null, null));
    }

    private static List<String> statusNames(JiraWorkflow workflow) {
        List<String> names = new ArrayList<String>();
        for (Status status : workflow.getLinkedStatusObjects()) {
            names.add(status.getName());
        }
        return names;
    }

    private Status statusNamed(String name) {
        for (Status status : statusManager.getStatuses()) {
            if (name.equals(status.getName())) {
                return status;
            }
        }
        return null;
    }

    /** A project with issue types (borrowed from an existing one), then given a scheme that maps all types to the workflow. */
    private Project projectOnTheWorkflow() {
        assertNull("refusing an existing project key", projectManager.getProjectObjByKey(KEY));
        Project template = null;
        for (Project candidate : projectManager.getProjects()) {
            if (!KEY.equals(candidate.getKey()) && !candidate.getIssueTypes().isEmpty()) {
                template = candidate;
                break;
            }
        }
        assertNotNull("the instance needs a project with issue types", template);
        ProjectCreationData data = new ProjectCreationData.Builder().withName("Wired test " + KEY).withKey(KEY)
                .withLead(admin).withType("business").build();
        ProjectService.CreateProjectValidationResult validation =
                projectService.validateCreateProjectBasedOnExistingProject(admin, template.getId(), data);
        assertTrue("cannot create the test project: " + validation.getErrorCollection(), validation.isValid());
        Project project = projectService.createProject(validation);
        scope.record("project", project.getId().toString(), project.getName());

        AssignableWorkflowScheme scheme = schemeManager.createScheme(schemeManager.assignableBuilder()
                .setName(PREFIX + "scheme")
                .setDefaultWorkflow(workflowName).build());
        scope.record("scheme", scheme.getId().toString(), scheme.getName());
        // addSchemeToProject only adds: the project made from a template already has the template's scheme
        detachProject(project);
        schemeManager.addSchemeToProject(project, schemeManager.getSchemeObject(scheme.getId()));
        assertEquals(workflowName, schemeManager.getWorkflowSchemeObj(project).getActualDefaultWorkflow());
        return projectManager.getProjectObj(project.getId());
    }

    private Issue createIssue(Project project) {
        IssueType type = null;
        for (IssueType candidate : project.getIssueTypes()) {
            if (!candidate.isSubTask()) {
                type = candidate;
                break;
            }
        }
        assertNotNull("the project has an issue type that is not a sub-task", type);
        IssueService.CreateValidationResult validation = issueService.validateCreate(admin,
                issueService.newIssueInputParameters().setProjectId(project.getId()).setIssueTypeId(type.getId())
                        .setSummary("wired test issue").setReporterId(admin.getName()));
        assertTrue("cannot create the issue: " + validation.getErrorCollection(), validation.isValid());
        IssueService.IssueResult created = issueService.create(admin, validation);
        assertTrue("issue not created: " + created.getErrorCollection(), created.isValid());
        return created.getIssue();
    }

    private List<String> availableActions(Issue issue) {
        List<String> names = new ArrayList<String>();
        for (ActionDescriptor action : issueWorkflowManager.getSortedAvailableActions(issue, admin)) {
            names.add(action.getName());
        }
        // Jira orders them by its own sequence; compare in a fixed order
        java.util.Collections.sort(names);
        return names;
    }

    private Issue transition(Issue issue, String actionName) {
        for (ActionDescriptor action : issueWorkflowManager.getAvailableActions(issue, admin)) {
            if (actionName.equals(action.getName())) {
                IssueService.TransitionValidationResult validation = issueService.validateTransition(admin,
                        issue.getId(), action.getId(), issueService.newIssueInputParameters());
                assertTrue("cannot do '" + actionName + "': " + validation.getErrorCollection(), validation.isValid());
                IssueService.IssueResult result = issueService.transition(admin, validation);
                assertTrue("'" + actionName + "' failed: " + result.getErrorCollection(), result.isValid());
                return com.atlassian.jira.component.ComponentAccessor.getIssueManager().getIssueObject(issue.getId());
            }
        }
        throw new AssertionError("'" + actionName + "' is not available for " + issue.getKey() + ": "
                + availableActions(issue));
    }

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
            List<GenericValue> rows = delegator.findByAnd("Workflow",
                    Collections.<String, Object>singletonMap("name", response.getWorkflow()));
            if (rows.size() == 1) {
                scope.record("workflow", rows.get(0).getLong("id").toString(), response.getWorkflow());
            }
        }
        return response;
    }

    private boolean ownsWorkflow(String name) {
        List<GenericValue> rows = delegator.findByAnd("Workflow", Collections.<String, Object>singletonMap("name", name));
        return rows.size() == 1 && scope.owns("workflow", rows.get(0).getLong("id").toString(), name);
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

    /** Order matters: a workflow in use cannot be deleted, a scheme in use cannot either, a status in a workflow neither. */
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
            if (row != null && scope.owns("workflow", id, row.getString("name"))) {
                JiraWorkflow workflow = workflowManager.getWorkflow(row.getString("name"));
                if (workflow != null && ownsWorkflow(workflow.getName())) {
                    assertNull("refusing deletion of a workflow with an unowned draft",
                            workflowManager.getDraftWorkflow(workflow.getName()));
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
