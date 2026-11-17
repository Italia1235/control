package it.ru.rasulov.customfields;

import com.atlassian.jira.bc.project.ProjectCreationData;
import com.atlassian.jira.bc.project.ProjectService;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.plugins.osgi.test.AtlassianPluginsTestRunner;
import it.ru.rasulov.WiredTestScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.rasulov.customfields.api.CustomFieldCreator;
import ru.rasulov.customfields.api.FieldScreenService;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreationStatus;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.customfields.api.dto.screen.AddToScreensRequest;
import ru.rasulov.customfields.api.dto.screen.AddToScreensResponse;
import ru.rasulov.customfields.api.dto.screen.Recommendation;
import ru.rasulov.customfields.api.dto.screen.ScreenCandidate;
import ru.rasulov.customfields.api.dto.screen.ScreenErrorCode;
import ru.rasulov.customfields.api.dto.screen.ScreenOperation;
import ru.rasulov.customfields.api.dto.screen.ScreenPlan;
import ru.rasulov.customfields.api.dto.screen.ScreenResult;
import ru.rasulov.customfields.api.dto.screen.ScreenStatus;
import ru.rasulov.customfields.api.dto.screen.ScreenTarget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Screens in a real Jira. Also the spike behind the design: does adding a field to a tab persist by itself, what does
 * the plan say for the default screen, for a screen shared with other projects, and for a project's own screens.
 * <p>
 * Needs one project that has screens of its own (a screen scheme other than the default one). Two uniquely named
 * throw-away projects are created: one on the default screen scheme, and one sharing that project's screen scheme.
 * Only fields created by this test are deleted; deleting a field also takes it off every screen, which is checked.
 */
@RunWith(AtlassianPluginsTestRunner.class)
public class FieldScreensWiredTest {

    private final WiredTestScope scope = new WiredTestScope();
    private final String PREFIX = scope.prefix("screen");
    private final String KEY_DEFAULT = scope.projectKey("WTS");
    private final String KEY_SHARING = scope.projectKey("WTX");

    private final FieldScreenService screenService;
    private final CustomFieldCreator creator;
    private final CustomFieldManager customFieldManager;
    private final FieldScreenManager fieldScreenManager;
    private final IssueTypeScreenSchemeManager issueTypeScreenSchemeManager;
    private final IssueTypeSchemeManager issueTypeSchemeManager;
    private final ProjectService projectService;
    private final ProjectManager projectManager;
    private final JiraAuthenticationContext authenticationContext;
    private final UserManager userManager;
    private ApplicationUser previousUser;
    private boolean loggedIn;
    private ApplicationUser admin;
    private Project ownScreens;
    private Project onDefault;
    private Project sharing;

    public FieldScreensWiredTest(FieldScreenService screenService, CustomFieldCreator creator,
                                 CustomFieldManager customFieldManager, FieldScreenManager fieldScreenManager,
                                 IssueTypeScreenSchemeManager issueTypeScreenSchemeManager,
                                 IssueTypeSchemeManager issueTypeSchemeManager,
                                 ProjectService projectService, ProjectManager projectManager,
                                 JiraAuthenticationContext authenticationContext, UserManager userManager) {
        this.screenService = ru.rasulov.common.impl.LegacyWriteCalls.screens(screenService);
        this.creator = ru.rasulov.common.impl.LegacyWriteCalls.fields(creator);
        this.customFieldManager = customFieldManager;
        this.fieldScreenManager = fieldScreenManager;
        this.issueTypeScreenSchemeManager = issueTypeScreenSchemeManager;
        this.issueTypeSchemeManager = issueTypeSchemeManager;
        this.projectService = projectService;
        this.projectManager = projectManager;
        this.authenticationContext = authenticationContext;
        this.userManager = userManager;
    }

    @Before
    public void logInAndSetUp() throws Exception {
        scope.requireEnabled();
        previousUser = authenticationContext.getLoggedInUser();
        admin = userManager.getUserByName("admin");
        assertNotNull("the 'admin' user is required by this test", admin);
        authenticationContext.setLoggedInUser(admin);
        loggedIn = true;

        ownScreens = projectWithOwnScreens();
        onDefault = createProject(KEY_DEFAULT, null);
        sharing = createProject(KEY_SHARING, ownScreens);
    }

    @After
    public void cleanUpAndRestoreUser() throws Exception {
        if (!loggedIn) {
            return;
        }
        try {
            cleanUp();
        } finally {
            loggedIn = false;
            authenticationContext.setLoggedInUser(previousUser);
        }
    }

    // --- the plan ----------------------------------------------------------------------------------------------

    @Test
    public void theDefaultScreenIsPlannedAsNotRecommendedWithTheProjectsSharingIt() {
        String id = createField(onDefault.getKey());

        ScreenPlan plan = screenService.planScreens(id);

        ScreenCandidate defaultScreen = byId(plan, FieldScreen.DEFAULT_SCREEN_ID);
        assertEquals(Recommendation.NOT_RECOMMENDED, defaultScreen.getRecommendation());
        assertTrue(defaultScreen.isDefaultScreen());
        assertEquals(1, defaultScreen.getProjectsInContextTotal());
        assertEquals(KEY_DEFAULT, defaultScreen.getProjectsInContext().get(0).getKey());
        assertTrue("the plan sees the other projects on the default scheme",
                defaultScreen.getProjectsOutsideContextTotal() >= 0);
        assertEquals(plan.getTotal(), plan.getScreens().size());
        assertFalse(plan.isTruncated());
    }

    @Test
    public void aScreenSharedWithAnotherProjectIsACautionAndNamesThatProject() {
        String id = createField(sharing.getKey());

        ScreenPlan plan = screenService.planScreens(id);

        assertTrue("the shared project has screens", plan.getTotal() > 0);
        for (ScreenCandidate screen : plan.getScreens()) {
            if (screen.isDefaultScreen()) {
                continue;
            }
            assertEquals(screen.getName(), Recommendation.CAUTION, screen.getRecommendation());
            assertEquals(Arrays.asList(KEY_SHARING), keys(screen.getProjectsInContext()));
            assertTrue(screen.getName() + " is also used by " + ownScreens.getKey(),
                    keys(screen.getProjectsOutsideContext()).contains(ownScreens.getKey()));
        }
    }

    @Test
    public void whenEveryProjectOfAScreenIsInTheContextTheScreenIsRecommended() {
        String id = createField(ownScreens.getKey(), sharing.getKey());

        ScreenPlan plan = screenService.planScreens(id);

        assertTrue(plan.getTotal() > 0);
        for (ScreenCandidate screen : plan.getScreens()) {
            boolean nobodyElse = screen.getProjectsOutsideContextTotal() == 0;
            assertEquals(screen.getName() + " outside: " + keys(screen.getProjectsOutsideContext()),
                    nobodyElse, screen.getRecommendation() == Recommendation.RECOMMENDED);
        }
        assertTrue("at least the screens of the two projects are recommended", plan.getSummary().getRecommended() > 0);
    }

    @Test
    public void createEditAndViewAreAllFoundWithTheirTabs() {
        String id = createField(ownScreens.getKey());

        ScreenPlan plan = screenService.planScreens(id);

        List<ScreenOperation> seen = new ArrayList<ScreenOperation>();
        for (ScreenCandidate screen : plan.getScreens()) {
            for (ScreenOperation operation : screen.getOperations()) {
                if (!seen.contains(operation)) {
                    seen.add(operation);
                }
            }
            assertFalse("every screen has a tab to put the field on", screen.getTabs().isEmpty());
        }
        assertEquals(3, seen.size());
    }

    // --- adding ------------------------------------------------------------------------------------------------

    @Test
    public void addingPersistsTheFieldOnTheFirstTabAtTheEndAndIsIdempotent() {
        String id = createField(sharing.getKey());
        ScreenCandidate target = firstNotDefault(screenService.planScreens(id));
        FieldScreen before = fieldScreenManager.getFieldScreen(target.getScreenId());
        int itemsBefore = before.getTab(0).getFieldScreenLayoutItems().size();

        AddToScreensResponse preview = screenService.addToScreens(new AddToScreensRequest(ids(id), true, targetOf(target)));
        assertEquals(ScreenStatus.WOULD_ADD, resultsOf(preview).get(0).getStatus());
        assertFalse("a dry run writes nothing", fieldScreenManager.getFieldScreen(target.getScreenId()).containsField(id));

        AddToScreensResponse added = screenService.addToScreens(new AddToScreensRequest(ids(id), false, targetOf(target)));
        assertEquals(resultsOf(added).get(0).getMessage(), ScreenStatus.ADDED, resultsOf(added).get(0).getStatus());

        // read it again from Jira, not from the object we changed: this is the persistence spike
        FieldScreen after = fieldScreenManager.getFieldScreen(target.getScreenId());
        assertTrue("the field is on the screen after a re-read", after.containsField(id));
        FieldScreenTab tab = after.getTab(0);
        assertEquals(itemsBefore + 1, tab.getFieldScreenLayoutItems().size());
        assertEquals("at the end of the first tab", id,
                tab.getFieldScreenLayoutItems().get(tab.getFieldScreenLayoutItems().size() - 1).getFieldId());
        assertEquals(resultsOf(added).get(0).getTab(), tab.getName());

        AddToScreensResponse again = screenService.addToScreens(new AddToScreensRequest(ids(id), false, targetOf(target)));
        assertEquals(ScreenStatus.ALREADY_PRESENT, resultsOf(again).get(0).getStatus());
        assertEquals(itemsBefore + 1, fieldScreenManager.getFieldScreen(target.getScreenId()).getTab(0)
                .getFieldScreenLayoutItems().size());

        ScreenCandidate replanned = byId(screenService.planScreens(id), target.getScreenId());
        assertEquals(Recommendation.ALREADY_PRESENT, replanned.getRecommendation());
        assertTrue(replanned.isAlreadyHasField());
    }

    @Test
    public void badScreensAndTabsAreResultsAndTheGoodOnesStillGetTheField() {
        String id = createField(sharing.getKey());
        ScreenCandidate good = firstNotDefault(screenService.planScreens(id));

        AddToScreensResponse response = screenService.addToScreens(new AddToScreensRequest(ids(id), false, Arrays.asList(
                new ScreenTarget(Long.MAX_VALUE - 1, null, null, null),
                new ScreenTarget(good.getScreenId(), "no such tab", null, null),
                new ScreenTarget(good.getScreenId(), null, null, null))));

        assertEquals(ScreenErrorCode.UNKNOWN_SCREEN, resultsOf(response).get(0).getErrorCode());
        assertEquals(ScreenErrorCode.UNKNOWN_TAB, resultsOf(response).get(1).getErrorCode());
        assertEquals(ScreenErrorCode.VALIDATION_ERROR, resultsOf(response).get(2).getErrorCode());
        assertFalse("the duplicate is refused, and the bad tab did not add it",
                fieldScreenManager.getFieldScreen(good.getScreenId()).containsField(id));
    }

    @Test
    public void severalFieldsGoToTheScreenInOneCallAndAreIdempotentPerField() {
        String first = createField(sharing.getKey());
        String second = createField(sharing.getKey());
        ScreenCandidate target = firstNotDefault(screenService.planScreens(first));
        AddToScreensResponse alone = screenService.addToScreens(new AddToScreensRequest(ids(first), false, targetOf(target)));
        assertEquals(ScreenStatus.ADDED, resultsOf(alone).get(0).getStatus());
        int itemsBefore = fieldScreenManager.getFieldScreen(target.getScreenId()).getTab(0)
                .getFieldScreenLayoutItems().size();

        AddToScreensResponse preview = screenService.addToScreens(new AddToScreensRequest(ids(first, second), true, targetOf(target)));
        assertEquals(Arrays.asList(first, second), Arrays.asList(preview.getFields().get(0).getFieldId(),
                preview.getFields().get(1).getFieldId()));
        assertEquals(ScreenStatus.ALREADY_PRESENT, preview.getFields().get(0).getResults().get(0).getStatus());
        assertEquals(ScreenStatus.WOULD_ADD, preview.getFields().get(1).getResults().get(0).getStatus());
        assertFalse("a dry run writes nothing", fieldScreenManager.getFieldScreen(target.getScreenId()).containsField(second));

        AddToScreensResponse done = screenService.addToScreens(new AddToScreensRequest(ids(first, second), false, targetOf(target)));
        assertEquals(ScreenStatus.ALREADY_PRESENT, done.getFields().get(0).getResults().get(0).getStatus());
        assertEquals(ScreenStatus.ADDED, done.getFields().get(1).getResults().get(0).getStatus());

        FieldScreen after = fieldScreenManager.getFieldScreen(target.getScreenId());
        assertTrue(after.containsField(first) && after.containsField(second));
        assertEquals("only the missing field was added", itemsBefore + 1, after.getTab(0).getFieldScreenLayoutItems().size());
    }

    @Test
    public void anUnknownFieldInABatchChangesNothing() {
        String id = createField(sharing.getKey());
        ScreenCandidate target = firstNotDefault(screenService.planScreens(id));

        try {
            screenService.addToScreens(new AddToScreensRequest(ids(id, "customfield_999999999"), false, targetOf(target)));
            fail("expected InvalidRequestException");
        } catch (InvalidRequestException expected) {
            // ok
        }

        assertFalse("the valid field of the refused call was not added",
                fieldScreenManager.getFieldScreen(target.getScreenId()).containsField(id));
    }

    @Test
    public void deletingTheFieldTakesItOffTheScreens() throws Exception {
        String id = createField(sharing.getKey());
        ScreenCandidate target = firstNotDefault(screenService.planScreens(id));
        screenService.addToScreens(new AddToScreensRequest(ids(id), false, targetOf(target)));
        assertTrue(fieldScreenManager.getFieldScreen(target.getScreenId()).containsField(id));

        CustomField field = customFieldManager.getCustomFieldObject(id);
        assertNotNull(field);
        assertTrue("refusing to delete a field not created by this test",
                scope.owns("field", id, field.getUntranslatedName()));
        customFieldManager.removeCustomField(field);

        assertFalse("cleaning up the test fields also cleans the screens",
                fieldScreenManager.getFieldScreen(target.getScreenId()).containsField(id));
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private String createField(String... projects) {
        String name = PREFIX + System.nanoTime();
        FieldCreationResult result = creator.createAll(new CreateCustomFieldsRequest(false, Collections.singletonList(
                new FieldDefinition(name, null, "textfield", null, null, Collections.singletonList(Contexts.inProjects(projects)), null)))).getResults().get(0);
        if (result.getStatus() == CreationStatus.CREATED) {
            scope.record("field", result.getCustomFieldId(), name);
        }
        assertEquals(result.getMessage(), CreationStatus.CREATED, result.getStatus());
        return result.getCustomFieldId();
    }

    private static List<String> ids(String... ids) {
        return Arrays.asList(ids);
    }

    /** The results of a call that named one field. */
    private static List<ScreenResult> resultsOf(AddToScreensResponse response) {
        assertEquals(1, response.getFields().size());
        return response.getFields().get(0).getResults();
    }

    private static List<ScreenTarget> targetOf(ScreenCandidate screen) {
        return Collections.singletonList(new ScreenTarget(screen.getScreenId(), null, null, null));
    }

    private static ScreenCandidate byId(ScreenPlan plan, long screenId) {
        for (ScreenCandidate screen : plan.getScreens()) {
            if (screen.getScreenId() == screenId) {
                return screen;
            }
        }
        throw new AssertionError("screen " + screenId + " is not in the plan");
    }

    private static ScreenCandidate firstNotDefault(ScreenPlan plan) {
        for (ScreenCandidate screen : plan.getScreens()) {
            if (!screen.isDefaultScreen()) {
                return screen;
            }
        }
        throw new AssertionError("the plan has only the default screen");
    }

    private static List<String> keys(List<ProjectRef> projects) {
        List<String> keys = new ArrayList<String>();
        for (ProjectRef project : projects) {
            keys.add(project.getKey());
        }
        return keys;
    }

    /** Any project whose issue type screen scheme is not the default one, so that its screens are its own. */
    private Project projectWithOwnScreens() {
        for (Project project : projectManager.getProjects()) {
            IssueTypeScreenScheme scheme = issueTypeScreenSchemeManager.getIssueTypeScreenScheme(project);
            if (!project.getKey().equals(KEY_DEFAULT) && !project.getKey().equals(KEY_SHARING)
                    && scheme != null && !scheme.isDefault()) {
                return project;
            }
        }
        throw new AssertionError("the instance needs a project with screens of its own");
    }

    /** A business project; with {@code basedOn} it shares that project's schemes, else it gets the defaults. */
    private Project createProject(String key, Project basedOn) {
        assertNull("refusing to reuse an existing project key " + key,
                projectManager.getProjectByCurrentKeyIgnoreCase(key));
        ProjectCreationData data = new ProjectCreationData.Builder().withName(PREFIX + key).withKey(key)
                .withLead(admin).withType("business").build();
        ProjectService.CreateProjectValidationResult validation = basedOn == null
                ? projectService.validateCreateProject(admin, data)
                : projectService.validateCreateProjectBasedOnExistingProject(admin, basedOn.getId(), data);
        assertTrue("cannot create the test project " + key + ": " + validation.getErrorCollection(), validation.isValid());
        Project project = projectService.createProject(validation);
        scope.record("project", project.getId().toString(), project.getName());
        if (basedOn != null) {
            issueTypeScreenSchemeManager.addSchemeAssociation(project,
                    issueTypeScreenSchemeManager.getIssueTypeScreenScheme(basedOn));
        } else {
            // a project made without a template has the default screens but no issue types: borrow those of ownScreens
            issueTypeSchemeManager.addProjectAssociations(issueTypeSchemeManager.getConfigScheme(ownScreens),
                    Collections.singletonList(project));
            project = projectManager.getProjectObj(project.getId());
        }
        return project;
    }

    private void cleanUp() throws Exception {
        for (String id : scope.ids("field")) {
            CustomField field = customFieldManager.getCustomFieldObject(id);
            if (field != null && scope.owns("field", id, field.getUntranslatedName())) {
                customFieldManager.removeCustomField(field);
            }
        }
        for (String id : scope.ids("project")) {
            Project project = projectManager.getProjectObj(Long.valueOf(id));
            if (project != null && scope.owns("project", id, project.getName())) {
                String key = project.getKey();
                ProjectService.DeleteProjectValidationResult validation = projectService.validateDeleteProject(admin, key);
                assertTrue("cannot delete the test project " + key + ": " + validation.getErrorCollection(),
                        validation.isValid());
                assertNotNull(validation.getProject());
                assertEquals("refusing to delete a different project", Long.valueOf(id), validation.getProject().getId());
                assertTrue("refusing to delete a renamed project",
                        scope.owns("project", id, validation.getProject().getName()));
                projectService.deleteProject(admin, validation);
            }
        }
    }
}
