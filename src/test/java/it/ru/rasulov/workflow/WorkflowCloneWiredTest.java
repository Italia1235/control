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
import com.atlassian.jira.workflow.DraftWorkflowScheme;
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
import ru.rasulov.workflow.api.WorkflowCloner;
import ru.rasulov.workflow.api.WorkflowDraftPublisher;
import ru.rasulov.workflow.api.WorkflowConfigurer;
import ru.rasulov.workflow.api.WorkflowReader;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.WorkflowStatusView;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.clone.CloneErrorCode;
import ru.rasulov.workflow.api.dto.clone.CloneScope;
import ru.rasulov.workflow.api.dto.clone.CloneStatus;
import ru.rasulov.workflow.api.dto.clone.CloneVerdict;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsRequest;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsResponse;
import ru.rasulov.workflow.api.dto.clone.WorkflowCloneView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Cloning the workflows of a project in a real Jira: two temporary projects share a scheme and a workflow, one of them
 * is detached. It proves what mocks cannot: that the copy is exact, that the issues keep their status and follow the
 * copy, that the other project and the originals are not touched, and that drafts and repeats are handled.
 */
@RunWith(AtlassianPluginsTestRunner.class)
public class WorkflowCloneWiredTest {

    private final WiredTestScope scope = new WiredTestScope();
    private final String PREFIX = scope.prefix("wc");
    private final String KEY = scope.projectKey("WTC");
    private final String OTHER_KEY = scope.projectKey("WTD");
    private static final String BUILT_IN = JiraWorkflow.DEFAULT_WORKFLOW_NAME;

    private static final String DRAFT_MARK = "ru.rasulov.wired.draft";

    private WorkflowCloner cloner;
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
    private DraftWorkflowScheme createdSchemeDraft;
    private ApplicationUser previousUser;
    private ApplicationUser admin;
    private String workflowName;

    public WorkflowCloneWiredTest(WorkflowCloner cloner, WorkflowConfigurer creator, WorkflowManager workflowManager,
                                  WorkflowSchemeManager schemeManager, WorkflowSchemeService schemeService,
                                  IssueWorkflowManager issueWorkflowManager, IssueService issueService,
                                  IssueManager issueManager, ProjectService projectService,
                                  ProjectManager projectManager, JiraAuthenticationContext authenticationContext,
                                  UserManager userManager, OfBizDelegator delegator,
                                  StatusManager statusManager, StatusService statusService,
                                  WorkflowDraftPublisher publisher, WorkflowReader reader) {
        this.cloner = ru.rasulov.common.impl.LegacyWriteCalls.clone(cloner);
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
        createFlow();
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

    // --- the copies ----------------------------------------------------------------------------------------------

    @Test
    public void aSelectedWorkflowIsCopiedExactlyTheIssuesFollowItAndTheOtherProjectIsUntouched() throws Exception {
        AssignableWorkflowScheme shared = sharedScheme();
        Project mine = newProject(KEY, shared);
        Project theirs = newProject(OTHER_KEY, shared);
        IssueType type = mainType(mine);
        Issue open = createIssue(mine, type);
        Issue started = transition(createIssue(mine, type), "Start");
        Issue theirIssue = createIssue(theirs, type);
        JiraWorkflow original = workflowManager.getWorkflow(workflowName);
        String originalXml = withoutUpdateTime(original);

        CloneWorkflowsResponse dry = cloneWorkflows(request(true, CloneScope.SELECTED, workflowName));
        assertEquals(dry.getMessage(), CloneStatus.WOULD_CLONE, dry.getStatus());
        assertEquals(SchemeAction.CREATE, dry.getScheme().getAction());
        assertEquals(KEY + ": " + shared.getName(), dry.getScheme().getName());
        assertNull("a dry run creates nothing", workflowManager.getWorkflow(copyOf(workflowName)));
        assertEquals(shared.getId(), schemeManager.getWorkflowSchemeObj(mine).getId());

        CloneWorkflowsResponse response = cloneWorkflows(request(false, CloneScope.SELECTED, workflowName));

        assertEquals(response.getMessage(), CloneStatus.CLONED, response.getStatus());
        WorkflowCloneView view = viewOf(response, workflowName);
        assertEquals(CloneVerdict.COPY, view.getVerdict());
        assertEquals(copyOf(workflowName), view.getNewName());
        assertEquals(1, view.getOtherProjects());
        assertEquals(CloneVerdict.KEEP, viewOf(response, BUILT_IN).getVerdict());

        JiraWorkflow copy = workflowManager.getWorkflow(copyOf(workflowName));
        assertNotNull("the copy exists", copy);
        assertEquals("an exact copy (all but the time Jira stamps on it)", originalXml, withoutUpdateTime(copy));
        assertEquals(original.getLinkedStatusIds(), copy.getLinkedStatusIds());
        assertEquals("the original is not changed", originalXml,
                withoutUpdateTime(workflowManager.getWorkflow(workflowName)));

        AssignableWorkflowScheme mineNow = schemeManager.getWorkflowSchemeObj(mine);
        assertFalse("a scheme of its own", shared.getId().equals(mineNow.getId()));
        assertEquals(KEY + ": " + shared.getName(), mineNow.getName());
        assertEquals(copyOf(workflowName), mineNow.getActualWorkflow(type.getId()));
        assertEquals("what was not copied stays as it was", BUILT_IN, mineNow.getActualDefaultWorkflow());

        AssignableWorkflowScheme theirsNow = schemeManager.getWorkflowSchemeObj(theirs);
        assertEquals("the other project has the shared scheme still", shared.getId(), theirsNow.getId());
        assertEquals(workflowName, theirsNow.getActualWorkflow(type.getId()));
        assertEquals(workflowName, schemeManager.getWorkflowSchemeObj(shared.getId()).getActualWorkflow(type.getId()));
        assertEquals(workflowName, entryWorkflow(reload(theirIssue)));

        assertEquals(copyOf(workflowName), entryWorkflow(reload(open)));
        assertEquals("Open", reload(open).getStatus().getName());
        assertEquals("In Progress", reload(started).getStatus().getName());
        assertEquals(Arrays.asList("Start"), actions(reload(open)));
        assertEquals(Arrays.asList("Finish"), actions(reload(started)));
        assertEquals("Closed", transition(reload(started), "Finish").getStatus().getName());
    }

    /**
     * The copy is made after Jira's cache of active workflows was filled, so a copy that is reported inactive after
     * the scheme was connected means the cache was not reset: "Edit" would then change the live version.
     */
    @Test
    public void aCopyConnectedThroughANewSchemeIsActiveAtOnceAndIsEditedThroughADraft() throws Exception {
        AssignableWorkflowScheme shared = sharedScheme();
        Project mine = newProject(KEY, shared);
        newProject(OTHER_KEY, shared);
        IssueType type = mainType(mine);
        Issue open = createIssue(mine, type);
        Issue started = transition(createIssue(mine, type), "Start");
        assertTrue("the cache is filled before the clone", workflowManager.isActive(workflowManager.getWorkflow(workflowName)));
        assertNull(workflowManager.getWorkflow(copyOf(workflowName)));

        CloneWorkflowsResponse response = cloneWorkflows(request(false, CloneScope.SELECTED, workflowName));

        assertEquals(response.getMessage(), CloneStatus.CLONED, response.getStatus());
        assertEquals(SchemeAction.CREATE, response.getScheme().getAction());
        assertEquals(copyOf(workflowName), schemeManager.getWorkflowSchemeObj(mine).getActualWorkflow(type.getId()));
        assertEquals(copyOf(workflowName), entryWorkflow(reload(open)));
        assertEquals("In Progress", reload(started).getStatus().getName());
        WorkflowDraftLifecycle.assertActiveEditedThroughDraftAndPublished(workflowManager, reader, creator, publisher,
                copyOf(workflowName));
        assertEquals("the issues still follow the edited copy", Arrays.asList("Finish"), actions(reload(started)));
        assertEquals("Closed", transition(reload(started), "Finish").getStatus().getName());
    }

    @Test
    public void schemeOnlyGivesTheProjectItsOwnSchemeOnTheSameWorkflowsAndMovesNoIssue() throws Exception {
        AssignableWorkflowScheme shared = sharedScheme();
        Project mine = newProject(KEY, shared);
        newProject(OTHER_KEY, shared);
        IssueType type = mainType(mine);
        Issue issue = createIssue(mine, type);
        int workflowsBefore = workflowManager.getWorkflows().size();

        CloneWorkflowsResponse response = cloneWorkflows(request(false, CloneScope.SCHEME_ONLY));

        assertEquals(response.getMessage(), CloneStatus.CLONED, response.getStatus());
        for (WorkflowCloneView view : response.getWorkflows()) {
            assertEquals(CloneVerdict.KEEP, view.getVerdict());
        }
        assertEquals("no workflow was copied", workflowsBefore, workflowManager.getWorkflows().size());
        AssignableWorkflowScheme mineNow = schemeManager.getWorkflowSchemeObj(mine);
        assertFalse(shared.getId().equals(mineNow.getId()));
        assertEquals(workflowName, mineNow.getActualWorkflow(type.getId()));
        assertEquals(workflowName, entryWorkflow(reload(issue)));
        assertEquals(Arrays.asList("Start"), actions(reload(issue)));
    }

    @Test
    public void allWorkflowsCopiesEveryWorkflowOthersUseIncludingTheBuiltInOne() throws Exception {
        AssignableWorkflowScheme shared = sharedScheme();
        Project mine = newProject(KEY, shared);
        newProject(OTHER_KEY, shared);
        IssueType type = mainType(mine);
        IssueType subTask = subTaskType(mine);
        Issue open = createIssue(mine, type);
        Issue child = createSubTask(mine, open, subTask);
        assertEquals(BUILT_IN, entryWorkflow(child));

        CloneWorkflowsResponse response = cloneWorkflows(request(false, CloneScope.ALL_WORKFLOWS));

        assertEquals(response.getMessage(), CloneStatus.CLONED, response.getStatus());
        for (WorkflowCloneView view : response.getWorkflows()) {
            assertEquals(view.getName(), CloneVerdict.COPY, view.getVerdict());
            assertEquals(KEY + ": " + view.getName(), view.getNewName());
        }
        WorkflowCloneView builtIn = viewOf(response, BUILT_IN);
        AssignableWorkflowScheme mineNow = schemeManager.getWorkflowSchemeObj(mine);
        assertEquals(builtIn.getNewName(), mineNow.getActualDefaultWorkflow());
        assertEquals(copyOf(workflowName), mineNow.getActualWorkflow(type.getId()));
        assertEquals("the sub-task followed the copy of the built-in workflow", builtIn.getNewName(),
                entryWorkflow(reload(child)));
        assertEquals("Open", reload(child).getStatus().getName());
        assertEquals(workflowManager.getWorkflow(BUILT_IN).getLinkedStatusIds(),
                workflowManager.getWorkflow(builtIn.getNewName()).getLinkedStatusIds());
        assertEquals(BUILT_IN, schemeManager.getWorkflowSchemeObj(shared.getId()).getActualDefaultWorkflow());
    }

    @Test
    public void anOwnSchemeWithSharedWorkflowsIsChangedInPlace() throws Exception {
        Project mine = newProject(KEY, ownScheme("mine"));
        Project theirs = newProject(OTHER_KEY, ownScheme("theirs"));
        IssueType type = mainType(mine);
        Issue issue = createIssue(mine, type);
        Long mineSchemeId = schemeManager.getWorkflowSchemeObj(mine).getId();
        Long theirSchemeId = schemeManager.getWorkflowSchemeObj(theirs).getId();

        CloneWorkflowsResponse response = cloneWorkflows(request(false, CloneScope.SELECTED, workflowName));

        assertEquals(response.getMessage(), CloneStatus.CLONED, response.getStatus());
        assertEquals(SchemeAction.UPDATE, response.getScheme().getAction());
        AssignableWorkflowScheme mineNow = schemeManager.getWorkflowSchemeObj(mine);
        assertEquals("the scheme is the same, it points at the copy now", mineSchemeId, mineNow.getId());
        assertEquals(copyOf(workflowName), mineNow.getActualWorkflow(type.getId()));
        assertEquals(copyOf(workflowName), entryWorkflow(reload(issue)));
        AssignableWorkflowScheme theirsNow = schemeManager.getWorkflowSchemeObj(theirs);
        assertEquals(theirSchemeId, theirsNow.getId());
        assertEquals(workflowName, theirsNow.getActualWorkflow(type.getId()));
    }

    @Test
    public void aRepeatFindsNothingSharedAndChangesNothing() throws Exception {
        AssignableWorkflowScheme shared = sharedScheme();
        Project mine = newProject(KEY, shared);
        newProject(OTHER_KEY, shared);
        CloneWorkflowsResponse first = cloneWorkflows(request(false, CloneScope.SELECTED, workflowName));
        assertEquals(first.getMessage(), CloneStatus.CLONED, first.getStatus());
        Long schemeId = schemeManager.getWorkflowSchemeObj(mine).getId();
        int workflows = workflowManager.getWorkflows().size();

        CloneWorkflowsResponse again = cloneWorkflows(request(false, CloneScope.SELECTED, copyOf(workflowName)));

        assertEquals(again.getMessage(), CloneStatus.ALREADY_SEPARATE, again.getStatus());
        assertEquals(CloneVerdict.ALREADY_OWN, viewOf(again, copyOf(workflowName)).getVerdict());
        CloneWorkflowsResponse original = cloneWorkflows(request(false, CloneScope.SELECTED, workflowName));
        assertEquals("the original is not the project's any more", CloneErrorCode.WORKFLOW_NOT_IN_SCHEME,
                original.getErrorCode());
        assertEquals(schemeId, schemeManager.getWorkflowSchemeObj(mine).getId());
        assertEquals(workflows, workflowManager.getWorkflows().size());
    }

    // --- refusals ------------------------------------------------------------------------------------------------

    @Test
    public void aDraftOfTheSchemeRefusesTheCloneAndChangesNothing() throws Exception {
        AssignableWorkflowScheme shared = sharedScheme();
        Project mine = newProject(KEY, shared);
        newProject(OTHER_KEY, shared);
        createIssue(mine, mainType(mine));
        int workflows = workflowManager.getWorkflows().size();

        assertTrue("refusing an unowned scheme",
                scope.owns("scheme", shared.getId().toString(), shared.getName()));
        assertFalse("refusing an existing scheme draft", schemeManager.hasDraft(shared));
        createdSchemeDraft = schemeManager.createDraftOf(admin, shared);
        CloneWorkflowsResponse schemeDraft = cloneWorkflows(request(false, CloneScope.SCHEME_ONLY));
        assertEquals(schemeDraft.getMessage(), CloneErrorCode.DRAFT_EXISTS, schemeDraft.getErrorCode());

        assertEquals(shared.getId(), schemeManager.getWorkflowSchemeObj(mine).getId());
        assertEquals("no copy is left behind", workflows, workflowManager.getWorkflows().size());
    }

    @Test
    public void aDraftOfTheWorkflowIsWarnedAboutAndTheCloneCopiesTheMainVersionAndLeavesTheDraft() throws Exception {
        AssignableWorkflowScheme shared = sharedScheme();
        Project mine = newProject(KEY, shared);
        Project theirs = newProject(OTHER_KEY, shared);
        IssueType type = mainType(mine);
        createIssue(mine, type);
        createOwnedWorkflowDraft();
        try {
            JiraWorkflow draft = workflowManager.getDraftWorkflow(workflowName);
            draft.getDescriptor().getMetaAttributes().put(DRAFT_MARK, "draft only");
            workflowManager.updateWorkflow(admin, draft);

            CloneWorkflowsResponse preview = cloneWorkflows(request(true, CloneScope.ALL_WORKFLOWS));
            assertEquals(preview.getMessage(), CloneStatus.WOULD_CLONE, preview.getStatus());
            assertTrue(preview.getMessage(), preview.getMessage().contains(workflowName));
            assertTrue(preview.getMessage(), preview.getMessage().contains("publish_workflow_draft"));

            CloneWorkflowsResponse cloned = cloneWorkflows(request(false, CloneScope.ALL_WORKFLOWS));
            assertEquals(cloned.getMessage(), CloneStatus.CLONED, cloned.getStatus());
            assertTrue(cloned.getMessage(), cloned.getMessage().contains("publish_workflow_draft"));
            JiraWorkflow copy = workflowManager.getWorkflow(copyOf(workflowName));
            assertNotNull(copy);
            assertNull("the copy is the main version, not the draft",
                    copy.getDescriptor().getMetaAttributes().get(DRAFT_MARK));
            assertNull("the copy has no draft of its own", workflowManager.getDraftWorkflow(copyOf(workflowName)));
            assertEquals(copyOf(workflowName), schemeManager.getWorkflowSchemeObj(mine).getActualWorkflow(type.getId()));
            assertEquals("the other project keeps the original", workflowName,
                    schemeManager.getWorkflowSchemeObj(theirs).getActualWorkflow(type.getId()));
            assertTrue("the draft of the original stays with its owner", ownsWorkflowDraft(workflowName));
            assertEquals("the draft of the original is unchanged", "draft only",
                    workflowManager.getDraftWorkflow(workflowName).getDescriptor().getMetaAttributes().get(DRAFT_MARK));
            assertNull("the original main version is unchanged",
                    workflowManager.getWorkflow(workflowName).getDescriptor().getMetaAttributes().get(DRAFT_MARK));
        } finally {
            deleteOwnedWorkflowDraft(workflowName);
        }
    }

    @Test
    public void aTakenNameAndAWorkflowOfAnotherSchemeAreRefusedWithSuggestions() throws Exception {
        AssignableWorkflowScheme shared = sharedScheme();
        newProject(KEY, shared);
        newProject(OTHER_KEY, shared);

        CloneWorkflowsResponse taken = cloneWorkflows(new CloneWorkflowsRequest(false, KEY, CloneScope.SELECTED,
                Arrays.asList(workflowName), null, Collections.singletonMap(workflowName, BUILT_IN)));
        assertEquals(taken.getMessage(), CloneErrorCode.NAME_TAKEN, taken.getErrorCode());

        CloneWorkflowsResponse elsewhere = cloneWorkflows(request(false, CloneScope.SELECTED, "No such workflow here"));
        assertEquals(elsewhere.getMessage(), CloneErrorCode.UNKNOWN_WORKFLOW, elsewhere.getErrorCode());
        assertTrue(elsewhere.getSuggestions().contains(workflowName));
        assertNull("a refused call copies nothing", workflowManager.getWorkflow(copyOf(workflowName)));
    }

    /**
     * The service reports PARTIALLY_CLONED after it has really created the scheme and the copies (the verifier found
     * a difference from the plan). The cloner is decorated here only to make that answer: the real call runs and must
     * be CLONED, then only the returned status changes. What must hold is that the helper registers what exists, so
     * the regular cleanup removes it; the emergency removal below is limited to the objects this call created and
     * runs only after the regular cleanup was judged.
     */
    @Test
    public void aPartiallyClonedAnswerStillRegistersTheCreatedSchemeAndCopiesSoTheCleanupRemovesThem() throws Exception {
        Set<String> foreignBefore = foreignWorkflowAndSchemeNames();
        Project control = templateProject();
        AssignableWorkflowScheme controlSchemeBefore = schemeManager.getWorkflowSchemeObj(control);
        Set<Long> schemeIdsBefore = schemeIds();
        AssignableWorkflowScheme shared = sharedScheme();
        Project mine = newProject(KEY, shared);
        Project theirs = newProject(OTHER_KEY, shared);
        String copyName = copyOf(workflowName);
        assertNull("the copy must not exist before the call", workflowManager.getWorkflow(copyName));
        final WorkflowCloner real = cloner;
        cloner = new WorkflowCloner() {
            @Override
            public CloneWorkflowsResponse clone(CloneWorkflowsRequest request) {
                CloneWorkflowsResponse done = real.clone(request);
                assertEquals(done.getMessage(), CloneStatus.CLONED, done.getStatus());
                assertFalse(done.isDryRun());
                assertEquals(SchemeAction.CREATE, done.getScheme().getAction());
                assertNotNull(done.getScheme().getId());
                return CloneWorkflowsResponse.partiallyCloned(done.getProject(), done.getScope(),
                        done.getCurrentScheme(), done.getScheme(), done.getWorkflows(), "injected partial result");
            }
        };
        Long createdSchemeId = null;
        Long copyRowId = null;
        try {
            CloneWorkflowsResponse response;
            try {
                response = cloneWorkflows(request(false, CloneScope.SELECTED, workflowName));
            } finally {
                cloner = real;
            }
            assertEquals(CloneStatus.PARTIALLY_CLONED, response.getStatus());
            assertEquals(SchemeAction.CREATE, response.getScheme().getAction());
            assertEquals(CloneVerdict.COPY, viewOf(response, workflowName).getVerdict());
            assertEquals(copyName, viewOf(response, workflowName).getNewName());
            createdSchemeId = response.getScheme().getId();
            assertNotNull(createdSchemeId);
            assertFalse("the scheme is new", schemeIdsBefore.contains(createdSchemeId));
            List<GenericValue> copyRows = delegator.findByAnd("Workflow",
                    Collections.<String, Object>singletonMap("name", copyName));
            assertEquals("the copy was really created", 1, copyRows.size());
            copyRowId = copyRows.get(0).getLong("id");

            // what the call changed is real: the project runs the new scheme, the others and the originals are as before
            assertEquals(createdSchemeId, schemeManager.getWorkflowSchemeObj(mine).getId());
            assertEquals(copyName, schemeManager.getWorkflowSchemeObj(mine).getActualWorkflow(mainType(mine).getId()));
            assertEquals(shared.getId(), schemeManager.getWorkflowSchemeObj(theirs).getId());
            assertEquals(workflowName, schemeManager.getWorkflowSchemeObj(theirs).getActualWorkflow(mainType(theirs).getId()));
            assertNotNull("the original workflow is kept", workflowManager.getWorkflow(workflowName));

            cleanUp();

            // independent reads of Jira by the identities saved before the cleanup
            assertNull("the created scheme is gone", schemeManager.getWorkflowSchemeObj(createdSchemeId));
            assertNull("the copy row is gone", delegator.findByPrimaryKey("Workflow", copyRowId));
            assertNull("the copy is gone", workflowManager.getWorkflow(copyName));
            assertNull("the test project is gone", projectManager.getProjectObjByKey(KEY));
            assertNull("the other test project is gone", projectManager.getProjectObjByKey(OTHER_KEY));
            assertEquals("nothing but the test's objects was touched", foreignBefore, foreignWorkflowAndSchemeNames());
            AssignableWorkflowScheme controlSchemeAfter = schemeManager.getWorkflowSchemeObj(control);
            assertEquals(controlSchemeBefore.getId(), controlSchemeAfter.getId());
            assertEquals(controlSchemeBefore.getMappings(), controlSchemeAfter.getMappings());
            assertEquals(controlSchemeBefore.getConfiguredDefaultWorkflow(), controlSchemeAfter.getConfiguredDefaultWorkflow());
        } finally {
            cloner = real;
            removeWhatThisCallCreated(schemeIdsBefore, createdSchemeId, copyName);
        }
    }

    private Set<String> foreignWorkflowAndSchemeNames() {
        Set<String> names = new HashSet<String>();
        for (JiraWorkflow workflow : workflowManager.getWorkflows()) {
            if (!workflow.getName().contains(PREFIX) && !workflow.getName().startsWith(KEY + ": ")
                    && !workflow.getName().startsWith(OTHER_KEY + ": ")) {
                names.add("workflow:" + workflow.getName());
            }
        }
        for (AssignableWorkflowScheme scheme : schemeManager.getAssignableSchemes()) {
            if (!scheme.getName().contains(PREFIX)) {
                names.add("scheme:" + scheme.getId() + ":" + scheme.getName());
            }
        }
        return names;
    }

    private Set<Long> schemeIds() {
        Set<Long> ids = new HashSet<Long>();
        for (AssignableWorkflowScheme scheme : schemeManager.getAssignableSchemes()) {
            ids.add(scheme.getId());
        }
        return ids;
    }

    /**
     * Emergency removal after the regular cleanup was judged, so that it cannot hide a defect. Touches only the scheme
     * that did not exist before this call and the copy named after this run's unique project key.
     */
    private void removeWhatThisCallCreated(Set<Long> schemeIdsBefore, Long createdSchemeId, String copyName) {
        if (createdSchemeId != null && !schemeIdsBefore.contains(createdSchemeId)) {
            AssignableWorkflowScheme scheme = schemeManager.getWorkflowSchemeObj(createdSchemeId);
            if (scheme != null && schemeManager.getProjectsUsing(scheme).isEmpty()) {
                schemeService.deleteWorkflowScheme(admin, scheme);
            }
        }
        JiraWorkflow copy = copyName.startsWith(KEY + ": ") ? workflowManager.getWorkflow(copyName) : null;
        if (copy != null && workflowManager.getDraftWorkflow(copyName) == null) {
            workflowManager.deleteWorkflow(copy);
        }
    }

    /** The name a copy of the workflow gets by default in project {@code KEY}. */
    private String copyOf(String name) {
        return KEY + ": " + name;
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

    private CloneWorkflowsResponse cloneWorkflows(CloneWorkflowsRequest request) {
        Project project = projectManager.getProjectObjByKey(request.getProject());
        assertNotNull("the clone project must be owned by this test", project);
        requireOwnedProject(project);
        CloneWorkflowsResponse response = cloner.clone(request);
        if (!response.isDryRun() && (response.getStatus() == CloneStatus.CLONED
                || response.getStatus() == CloneStatus.PARTIALLY_CLONED)) {
            if (response.getScheme() != null && response.getScheme().getAction() == SchemeAction.CREATE
                    && response.getScheme().getId() != null) {
                scope.record("scheme", response.getScheme().getId().toString(), response.getScheme().getName());
            }
            for (WorkflowCloneView workflow : response.getWorkflows()) {
                if (workflow.getVerdict() == CloneVerdict.COPY && workflow.getNewName() != null) {
                    recordWorkflow(workflow.getNewName());
                }
            }
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

    private CloneWorkflowsRequest request(boolean dryRun, CloneScope scope, String... workflows) {
        return new CloneWorkflowsRequest(dryRun, KEY, scope, Arrays.asList(workflows), null,
                Collections.<String, String>emptyMap());
    }

    private static WorkflowCloneView viewOf(CloneWorkflowsResponse response, String workflow) {
        for (WorkflowCloneView view : response.getWorkflows()) {
            if (workflow.equals(view.getName())) {
                return view;
            }
        }
        throw new AssertionError("workflow " + workflow + " is not in the answer");
    }

    /** The descriptor as XML without the time of the last update, which the copy gets fresh. */
    private static String withoutUpdateTime(JiraWorkflow workflow) {
        return workflow.getDescriptor().asXML().replaceAll("<meta name=\"jira\\.updated\\.date\">\\d+</meta>", "");
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

    private static TransitionDefinition finish(String name, String from, String to) {
        com.atlassian.jira.issue.resolution.Resolution resolution =
                com.atlassian.jira.component.ComponentAccessor.getConstantsManager().getResolutions().iterator().next();
        return new TransitionDefinition(name, name, from, false, to, null, new ru.rasulov.workflow.api.dto.TransitionResolution("SET", resolution.getId()));
    }

    /** Main issue types run the test workflow, everything else the built-in one. */
    private AssignableWorkflowScheme sharedScheme() {
        return schemeMappingMainTypes(PREFIX + "shared");
    }

    private AssignableWorkflowScheme ownScheme(String label) {
        return schemeMappingMainTypes(PREFIX + label);
    }

    private AssignableWorkflowScheme schemeMappingMainTypes(String name) {
        AssignableWorkflowScheme.Builder builder = schemeManager.assignableBuilder().setName(name)
                .setDefaultWorkflow(BUILT_IN);
        for (IssueType type : templateProject().getIssueTypes()) {
            if (!type.isSubTask()) {
                builder.setMapping(type.getId(), workflowName);
            }
        }
        AssignableWorkflowScheme scheme = schemeManager.createScheme(builder.build());
        scope.record("scheme", scheme.getId().toString(), scheme.getName());
        return scheme;
    }

    private Project templateProject() {
        for (Project candidate : projectManager.getProjects()) {
            if (!KEY.equals(candidate.getKey()) && !OTHER_KEY.equals(candidate.getKey())
                    && mainType(candidate) != null && subTaskType(candidate) != null) {
                return candidate;
            }
        }
        throw new AssertionError("the instance needs a project with an issue type and a sub-task type");
    }

    /** A project made from an existing one (for its issue types) and given the scheme. */
    private Project newProject(String key, AssignableWorkflowScheme scheme) {
        assertNull("refusing an existing project key", projectManager.getProjectObjByKey(key));
        ProjectCreationData data = new ProjectCreationData.Builder().withName("Wired test " + key).withKey(key)
                .withLead(admin).withType("business").build();
        ProjectService.CreateProjectValidationResult validation =
                projectService.validateCreateProjectBasedOnExistingProject(admin, templateProject().getId(), data);
        assertTrue("cannot create the test project: " + validation.getErrorCollection(), validation.isValid());
        Project project = projectService.createProject(validation);
        scope.record("project", project.getId().toString(), project.getName());
        // addSchemeToProject only adds: a project made from a template already has the template's scheme
        detachProject(project);
        schemeManager.addSchemeToProject(project, schemeManager.getSchemeObject(scheme.getId()));
        return projectManager.getProjectObj(project.getId());
    }

    private static IssueType mainType(Project project) {
        for (IssueType type : project.getIssueTypes()) {
            if (!type.isSubTask()) {
                return type;
            }
        }
        return null;
    }

    private static IssueType subTaskType(Project project) {
        for (IssueType type : project.getIssueTypes()) {
            if (type.isSubTask()) {
                return type;
            }
        }
        return null;
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

    private Issue createSubTask(Project project, Issue parent, IssueType type) {
        IssueService.CreateValidationResult validation = issueService.validateSubTaskCreate(admin, parent.getId(),
                issueService.newIssueInputParameters().setProjectId(project.getId()).setIssueTypeId(type.getId())
                        .setSummary("wired test sub-task").setReporterId(admin.getName()));
        assertTrue("cannot create the sub-task: " + validation.getErrorCollection(), validation.isValid());
        IssueService.IssueResult created = issueService.create(admin, validation);
        assertTrue("sub-task not created: " + created.getErrorCollection(), created.isValid());
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
                IssueService.TransitionValidationResult validation = issueService.validateTransition(admin,
                        issue.getId(), action.getId(), issueService.newIssueInputParameters());
                assertTrue("cannot do '" + actionName + "': " + validation.getErrorCollection(), validation.isValid());
                IssueService.IssueResult result = issueService.transition(admin, validation);
                assertTrue("'" + actionName + "' failed: " + result.getErrorCollection(), result.isValid());
                return result.getIssue();
            }
        }
        throw new AssertionError("'" + actionName + "' is not available for " + issue.getKey() + ": " + actions(issue));
    }

    /** Order matters: projects (with their issues) first, then the schemes, then the workflows they used. */
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
        if (createdSchemeDraft != null) {
            DraftWorkflowScheme draft = schemeManager.getDraft(createdSchemeDraft.getId());
            if (draft != null && createdSchemeDraft.getName().equals(draft.getName())
                    && createdSchemeDraft.getParentScheme().getId().equals(draft.getParentScheme().getId())
                    && scope.owns("scheme", draft.getParentScheme().getId().toString(), draft.getParentScheme().getName())) {
                ServiceOutcome<Void> deleted = schemeService.deleteWorkflowScheme(admin, draft);
                assertTrue("cannot delete owned scheme draft: " + deleted.getErrorCollection(), deleted.isValid());
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
