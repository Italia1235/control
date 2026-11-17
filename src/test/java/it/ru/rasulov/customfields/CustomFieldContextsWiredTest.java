package it.ru.rasulov.customfields;

import com.atlassian.jira.bc.project.ProjectCreationData;
import com.atlassian.jira.bc.project.ProjectService;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
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
import ru.rasulov.common.api.LookupService;
import ru.rasulov.customfields.api.CustomFieldCreator;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.common.api.dto.field.CreationStatus;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;

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
 * Contexts in a real Jira: what Jira stored for the projects and issue types the caller named (scope, name, no
 * description, options per context). Two throw-away projects are created for each test and deleted afterwards.
 */
@RunWith(AtlassianPluginsTestRunner.class)
public class CustomFieldContextsWiredTest {

    private final WiredTestScope scope = new WiredTestScope();
    private final String PREFIX = scope.prefix("ctx");
    private final String KEY_A = scope.projectKey("WTA");
    private final String KEY_B = scope.projectKey("WTB");

    private final CustomFieldCreator creator;
    private final LookupService lookup;
    private final CustomFieldManager customFieldManager;
    private final OptionsManager optionsManager;
    private final ProjectService projectService;
    private final ProjectManager projectManager;
    private final IssueTypeManager issueTypeManager;
    private final JiraAuthenticationContext authenticationContext;
    private final UserManager userManager;
    private ApplicationUser previousUser;
    private boolean loggedIn;
    private ApplicationUser admin;
    private Project projectA;
    private Project projectB;
    private IssueType task;
    private IssueType subtask;

    public CustomFieldContextsWiredTest(CustomFieldCreator creator, LookupService lookup, CustomFieldManager customFieldManager,
                                        OptionsManager optionsManager, ProjectService projectService,
                                        ProjectManager projectManager, IssueTypeManager issueTypeManager,
                                        JiraAuthenticationContext authenticationContext, UserManager userManager) {
        this.creator = ru.rasulov.common.impl.LegacyWriteCalls.fields(creator);
        this.lookup = lookup;
        this.customFieldManager = customFieldManager;
        this.optionsManager = optionsManager;
        this.projectService = projectService;
        this.projectManager = projectManager;
        this.issueTypeManager = issueTypeManager;
        this.authenticationContext = authenticationContext;
        this.userManager = userManager;
    }

    @Before
    public void logInAndCreateProjects() throws Exception {
        scope.requireEnabled();
        previousUser = authenticationContext.getLoggedInUser();
        admin = userManager.getUserByName("admin");
        assertNotNull("the 'admin' user is required by this test", admin);
        authenticationContext.setLoggedInUser(admin);
        loggedIn = true;
        projectA = createProject(KEY_A);
        projectB = createProject(KEY_B);
        for (IssueType type : issueTypeManager.getIssueTypes()) {
            if (type.isSubTask() && subtask == null) {
                subtask = type;
            } else if (!type.isSubTask() && task == null) {
                task = type;
            }
        }
        assertNotNull("the instance needs an ordinary issue type", task);
        assertNotNull("the instance needs a sub-task issue type", subtask);
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

    // --- one context -------------------------------------------------------------------------------------------

    @Test
    public void projectAndIssueTypeContextIsStoredAndOnlyAppliesThere() {
        FieldCreationResult result = create("textfield", Contexts.inProjects(
                Arrays.asList(KEY_A), Arrays.asList(task.getName())));

        assertEquals(result.getMessage(), CreationStatus.CREATED, result.getStatus());
        CustomField field = customFieldManager.getCustomFieldObject(result.getCustomFieldId());
        assertEquals(1, field.getConfigurationSchemes().size());
        FieldConfigScheme scheme = field.getConfigurationSchemes().get(0);
        assertFalse(scheme.isGlobal());
        assertFalse(scheme.isAllProjects());
        assertFalse(scheme.isAllIssueTypes());
        assertEquals(Arrays.asList(KEY_A), keysOf(scheme.getAssociatedProjectObjects()));
        assertEquals(Arrays.asList(task.getId()), idsOf(scheme.getAssociatedIssueTypes()));
        assertEquals(KEY_A, scheme.getName());
        assertTrue("contexts are created without a description", isBlank(scheme.getDescription()));
        assertTrue(field.isInScope(projectA, Arrays.asList(task.getId())));
        assertFalse(field.isInScope(projectB, Arrays.asList(task.getId())));
        assertFalse(field.isInScope(projectA, Arrays.asList(subtask.getId())));
    }

    @Test
    public void allProjectsWithChosenIssueTypesIsNotTheGlobalContext() {
        FieldCreationResult result = create("textfield", new ContextDefinition(null, true, null, false,
                Arrays.asList(task.getId()), null));

        assertEquals(result.getMessage(), CreationStatus.CREATED, result.getStatus());
        FieldConfigScheme scheme = onlyScheme(result);
        assertTrue(scheme.isAllProjects());
        assertFalse(scheme.isAllIssueTypes());
        assertFalse(scheme.isGlobal());
        assertEquals(Arrays.asList(task.getId()), idsOf(scheme.getAssociatedIssueTypes()));
        assertEquals("Global", scheme.getName());
    }

    @Test
    public void aSubtaskTypeCanBeInTheContextWhenNamed() {
        FieldCreationResult result = create("textfield", Contexts.inProjects(
                Arrays.asList(KEY_A), Arrays.asList(subtask.getName(), task.getName())));

        assertEquals(result.getMessage(), CreationStatus.CREATED, result.getStatus());
        assertTrue(idsOf(onlyScheme(result).getAssociatedIssueTypes()).contains(subtask.getId()));
    }

    @Test
    public void globalContextIsNamedGlobalAndHasNoDescription() {
        FieldCreationResult result = create("textfield", Contexts.GLOBAL.get(0));

        FieldConfigScheme scheme = onlyScheme(result);
        assertTrue(scheme.isGlobal());
        assertEquals("Global", scheme.getName());
        assertTrue(isBlank(scheme.getDescription()));
    }

    @Test
    public void projectsCanBeNamedByKeyOrByName() {
        FieldCreationResult result = create("textfield", Contexts.inProjects(KEY_A.toLowerCase(), projectB.getName()));

        assertEquals(result.getMessage(), CreationStatus.CREATED, result.getStatus());
        assertEquals(Arrays.asList(KEY_A, KEY_B), sorted(keysOf(onlyScheme(result).getAssociatedProjectObjects())));
        assertEquals(KEY_A + ", " + KEY_B, onlyScheme(result).getName());
    }

    // --- several contexts --------------------------------------------------------------------------------------

    @Test
    public void separateContextsEachGetTheirOwnProjectsAndOptions() {
        FieldCreationResult result = create("select",
                Contexts.withOptions(Contexts.inProjects(KEY_A), new OptionDefinition("A1", null), new OptionDefinition("A2", null)),
                Contexts.withOptions(Contexts.inProjects(KEY_B), new OptionDefinition("B1", null)));

        assertEquals(result.getMessage(), CreationStatus.CREATED, result.getStatus());
        CustomField field = customFieldManager.getCustomFieldObject(result.getCustomFieldId());
        assertEquals(2, field.getConfigurationSchemes().size());
        FieldConfigScheme a = schemeNamed(field, KEY_A);
        FieldConfigScheme b = schemeNamed(field, KEY_B);
        assertEquals(Arrays.asList(KEY_A), keysOf(a.getAssociatedProjectObjects()));
        assertEquals(Arrays.asList(KEY_B), keysOf(b.getAssociatedProjectObjects()));
        assertEquals(Arrays.asList("A1", "A2"), values(optionsManager.getOptions(a.getOneAndOnlyConfig()).getRootOptions()));
        assertEquals(Arrays.asList("B1"), values(optionsManager.getOptions(b.getOneAndOnlyConfig()).getRootOptions()));
        assertTrue(field.isInScope(projectA, Arrays.asList(task.getId())));
        assertTrue(field.isInScope(projectB, Arrays.asList(task.getId())));
        assertEquals(2, result.getContexts().size());
    }

    @Test
    public void theFieldsOptionsFillEveryContextWithoutItsOwn() {
        FieldCreationResult result = createFields(new CreateCustomFieldsRequest(false, Collections.singletonList(
                new FieldDefinition(PREFIX + "shared", null, "select", null, Arrays.asList(new OptionDefinition("Same", null)), Arrays.asList(Contexts.inProjects(KEY_A), Contexts.inProjects(KEY_B)), null)))).getResults().get(0);

        assertEquals(result.getMessage(), CreationStatus.CREATED, result.getStatus());
        CustomField field = customFieldManager.getCustomFieldObject(result.getCustomFieldId());
        for (FieldConfigScheme scheme : field.getConfigurationSchemes()) {
            assertEquals(Arrays.asList("Same"), values(optionsManager.getOptions(scheme.getOneAndOnlyConfig()).getRootOptions()));
        }
    }

    @Test
    public void overlappingContextsAreRefusedAndNothingIsCreated() {
        int before = customFieldManager.getCustomFieldObjects().size();

        FieldCreationResult result = create("textfield", Contexts.inProjects(KEY_A), Contexts.inProjects(KEY_A, KEY_B));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, result.getErrorCode());
        assertEquals(before, customFieldManager.getCustomFieldObjects().size());
    }

    // --- nothing is assumed ------------------------------------------------------------------------------------

    @Test
    public void aFieldWithoutContextsIsNotCreated() {
        int before = customFieldManager.getCustomFieldObjects().size();

        FieldCreationResult result = createFields(new CreateCustomFieldsRequest(false, Collections.singletonList(
                new FieldDefinition(PREFIX + "nothing", null, "textfield", null, null, null, null)))).getResults().get(0);

        assertEquals(CustomFieldErrorCode.CONTEXT_REQUIRED, result.getErrorCode());
        assertEquals(before, customFieldManager.getCustomFieldObjects().size());
    }

    @Test
    public void unknownAndArchivedLikeInputIsRefusedWithSuggestions() {
        FieldCreationResult project = create("textfield", Contexts.inProjects(PREFIX));
        FieldCreationResult issueType = create("textfield", Contexts.inProjects(
                Arrays.asList(KEY_A), Arrays.asList("No such type")));

        assertEquals(CustomFieldErrorCode.UNKNOWN_PROJECT, project.getErrorCode());
        assertTrue(project.getSuggestions().containsAll(Arrays.asList(KEY_A, KEY_B)));
        assertEquals(CustomFieldErrorCode.UNKNOWN_ISSUE_TYPE, issueType.getErrorCode());
    }

    // --- preview and existing fields ---------------------------------------------------------------------------

    @Test
    public void thePreviewShowsTheContextsAndWritesNothing() {
        int before = customFieldManager.getCustomFieldObjects().size();

        FieldCreationResult result = createFields(new CreateCustomFieldsRequest(true, Collections.singletonList(
                new FieldDefinition(PREFIX + "preview", null, "textfield", null, null, Collections.singletonList(Contexts.inProjects(
                        Arrays.asList(KEY_A), Arrays.asList(task.getName()))), null))))
                .getResults().get(0);

        assertEquals(CreationStatus.WOULD_CREATE, result.getStatus());
        assertEquals(KEY_A, result.getContexts().get(0).getProjects().get(0).getKey());
        assertEquals(projectA.getName(), result.getContexts().get(0).getProjects().get(0).getName());
        assertEquals(task.getName(), result.getContexts().get(0).getIssueTypes().get(0).getName());
        assertEquals(before, customFieldManager.getCustomFieldObjects().size());
    }

    @Test
    public void anExistingFieldReportsItsRealContextsAndIsNotChanged() {
        FieldCreationResult first = create("textfield", Contexts.inProjects(KEY_A));

        FieldCreationResult again = create("textfield", Contexts.inProjects(KEY_B));

        assertEquals(CreationStatus.EXISTS, again.getStatus());
        assertEquals(first.getCustomFieldId(), again.getCustomFieldId());
        assertEquals(KEY_A, again.getContexts().get(0).getProjects().get(0).getKey());
        assertNotNull(again.getMessage());
        assertTrue(again.getMessage(), again.getMessage().contains("contexts"));
        CustomField field = customFieldManager.getCustomFieldObject(first.getCustomFieldId());
        assertEquals(Arrays.asList(KEY_A), keysOf(field.getConfigurationSchemes().get(0).getAssociatedProjectObjects()));
    }

    @Test
    public void repeatingTheSameRequestIsExistsWithoutWarning() {
        create("textfield", Contexts.inProjects(Arrays.asList(KEY_A), Arrays.asList(task.getName())));

        FieldCreationResult again = create("textfield", Contexts.inProjects(
                Arrays.asList(KEY_A), Arrays.asList(task.getName())));

        assertEquals(CreationStatus.EXISTS, again.getStatus());
        assertNull(again.getMessage());
    }

    // --- catalogs ----------------------------------------------------------------------------------------------

    @Test
    public void theCatalogsFindTheProjectsAndIssueTypesOfTheInstance() {
        List<String> found = new ArrayList<String>();
        for (ru.rasulov.common.api.dto.ProjectResponse project : lookup.listProjects(PREFIX, null).getProjects()) {
            found.add(project.getKey());
        }
        List<String> typeNames = new ArrayList<String>();
        for (ru.rasulov.common.api.dto.IssueTypeResponse type :
                lookup.listIssueTypes(null, null).getIssueTypes()) {
            typeNames.add(type.getName());
        }

        assertEquals(Arrays.asList(KEY_A, KEY_B), found);
        assertTrue(typeNames.contains(task.getName()));
        assertTrue(typeNames.contains(subtask.getName()));
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private CreateCustomFieldsResponse createFields(CreateCustomFieldsRequest request) {
        CreateCustomFieldsResponse response = creator.createAll(request);
        for (FieldCreationResult result : response.getResults()) {
            if (result.getStatus() == CreationStatus.CREATED) {
                scope.record("field", result.getCustomFieldId(), result.getName());
            }
        }
        return response;
    }

    private FieldCreationResult create(String typeKey, ContextDefinition... contexts) {
        try {
            return createFields(new CreateCustomFieldsRequest(false, Collections.singletonList(
                    new FieldDefinition(PREFIX + typeKey, null, typeKey, null, null, Arrays.asList(contexts), null))))
                    .getResults().get(0);
        } catch (RuntimeException e) {
            fail("unexpected " + e);
            return null;
        }
    }

    private FieldConfigScheme onlyScheme(FieldCreationResult result) {
        List<FieldConfigScheme> schemes = customFieldManager.getCustomFieldObject(result.getCustomFieldId())
                .getConfigurationSchemes();
        assertEquals(1, schemes.size());
        return schemes.get(0);
    }

    private static FieldConfigScheme schemeNamed(CustomField field, String name) {
        for (FieldConfigScheme scheme : field.getConfigurationSchemes()) {
            if (name.equals(scheme.getName())) {
                return scheme;
            }
        }
        throw new AssertionError("no context named " + name);
    }

    private Project createProject(String key) {
        assertNull("refusing to reuse an existing project key " + key,
                projectManager.getProjectByCurrentKeyIgnoreCase(key));
        ProjectCreationData data = new ProjectCreationData.Builder().withName(PREFIX + key).withKey(key)
                .withLead(admin).withType("business").build();
        ProjectService.CreateProjectValidationResult validation = projectService.validateCreateProject(admin, data);
        assertTrue("cannot create the test project " + key + ": " + validation.getErrorCollection(), validation.isValid());
        Project project = projectService.createProject(validation);
        scope.record("project", project.getId().toString(), project.getName());
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

    private static List<String> keysOf(List<Project> projects) {
        List<String> keys = new ArrayList<String>();
        for (Project project : projects) {
            keys.add(project.getKey());
        }
        return keys;
    }

    private static List<String> idsOf(java.util.Collection<IssueType> types) {
        List<String> ids = new ArrayList<String>();
        for (IssueType type : types) {
            ids.add(type.getId());
        }
        return ids;
    }

    private static List<String> values(List<Option> options) {
        List<String> values = new ArrayList<String>();
        for (Option option : options) {
            values.add(option.getValue());
        }
        return values;
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<String>(values);
        Collections.sort(copy);
        return copy;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
