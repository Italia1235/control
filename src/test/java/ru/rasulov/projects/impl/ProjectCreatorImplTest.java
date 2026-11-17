package ru.rasulov.projects.impl;

import com.atlassian.jira.bc.project.ProjectCreationData;
import com.atlassian.jira.bc.project.ProjectService;
import com.atlassian.jira.bc.user.search.UserSearchParams;
import com.atlassian.jira.bc.user.search.UserSearchService;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.project.template.ProjectTemplate;
import com.atlassian.jira.project.template.ProjectTemplateKey;
import com.atlassian.jira.project.template.ProjectTemplateManager;
import com.atlassian.jira.project.type.ProjectTypeKey;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.jira.util.ErrorCollection;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.projects.api.dto.CreateProjectRequest;
import ru.rasulov.projects.api.dto.CreateProjectResponse;
import ru.rasulov.projects.api.dto.CreateProjectResponse.ErrorCode;
import ru.rasulov.projects.api.dto.CreateProjectResponse.Status;
import ru.rasulov.projects.api.dto.ProjectLeadView;
import ru.rasulov.projects.api.dto.ProjectTemplatesResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ProjectCreatorImplTest {
    private final ProjectService projectService = mock(ProjectService.class);
    private final ProjectManager projectManager = mock(ProjectManager.class);
    private final ProjectTemplateManager templates = mock(ProjectTemplateManager.class);
    private final UserManager users = mock(UserManager.class);
    private final UserSearchService search = mock(UserSearchService.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final ActionPlanStore plans = mock(ActionPlanStore.class);
    private final ChangeJournal journal = mock(ChangeJournal.class);
    private final ProjectUndoSupport undo = mock(ProjectUndoSupport.class);
    private final ApplicationUser admin = user("admin", "admin-key", true);
    private final ApplicationUser lead = user("alice", "alice-key", true);
    private final AtomicReference<ActionPlanStore.Plan> issued = new AtomicReference<ActionPlanStore.Plan>();
    private final AtomicBoolean insideLock = new AtomicBoolean();
    private final ProjectService.CreateProjectValidationResult valid =
            mock(ProjectService.CreateProjectValidationResult.class);
    private final ProjectCreatorImpl creator = new ProjectCreatorImpl(projectService, projectManager, templates, users,
            search, guard, lock, plans, new ActionPlanCodec(), journal, undo,
            ru.rasulov.common.impl.TestLinks.links(), new ru.rasulov.issuetypes.impl.IssueTypeSetsImpl(
            org.mockito.Mockito.mock(ru.rasulov.issuetypes.impl.IssueTypeSetPlanner.class),
            org.mockito.Mockito.mock(ru.rasulov.issuetypes.impl.IssueTypeSetExecutor.class), lock));

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        when(guard.requireAdministrator()).thenReturn(admin);
        when(users.getUserByName("alice")).thenReturn(lead);
        when(users.getUserByKey("alice-key")).thenReturn(lead);
        when(projectManager.getProjectObjects()).thenReturn(Collections.<Project>emptyList());
        when(projectManager.getArchivedProjects()).thenReturn(Collections.<Project>emptyList());
        Optional<ProjectTemplate> projectManagement = Optional.of(template(ProjectTemplates.PROJECT_MANAGEMENT, "business"));
        when(templates.getProjectTemplate(any(ProjectTemplateKey.class))).thenReturn(projectManagement);
        when(valid.isValid()).thenReturn(true);
        when(projectService.validateCreateProject(eq(admin), any(ProjectCreationData.class))).thenReturn(valid);
        when(journal.execute(anyString(), anyString(), any())).thenAnswer(call ->
                ((Supplier<Object>) call.getArgument(2)).get());
        when(lock.run(anyString(), anyString(), any())).thenAnswer(invocation -> {
            insideLock.set(true);
            try {
                return ((Supplier<Object>) invocation.getArgument(2)).get();
            } finally {
                insideLock.set(false);
            }
        });
        when(plans.issue(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    ActionPlanStore.Plan plan = new ActionPlanStore.Plan("plan-1", invocation.getArgument(0),
                            invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3),
                            123456789L);
                    issued.set(plan);
                    return plan;
                });
        when(plans.find(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            ActionPlanStore.Plan plan = issued.get();
            return plan != null && "admin-key".equals(invocation.getArgument(1))
                    && plan.getId().equals(invocation.getArgument(2)) ? plan : null;
        });
        doAnswer(invocation -> {
            ActionPlanStore.Plan plan = issued.get();
            if (plan != null && plan.getId().equals(invocation.getArgument(0))) {
                issued.set(null);
            }
            return null;
        }).when(plans).remove(anyString());
    }

    private static ProjectTemplate template(String key, String type) {
        ProjectTemplate result = mock(ProjectTemplate.class);
        when(result.getKey()).thenReturn(new ProjectTemplateKey(key));
        when(result.getProjectTypeKey()).thenReturn(new ProjectTypeKey(type));
        return result;
    }

    private void templateExists(String key, String type) {
        Optional<ProjectTemplate> found = Optional.of(template(key, type));
        when(templates.getProjectTemplate(eq(new ProjectTemplateKey(key)))).thenReturn(found);
    }

    private static ApplicationUser user(String name, String key, boolean active) {
        ApplicationUser result = mock(ApplicationUser.class);
        when(result.getUsername()).thenReturn(name);
        when(result.getKey()).thenReturn(key);
        when(result.getDisplayName()).thenReturn(name + " Smith");
        when(result.isActive()).thenReturn(active);
        return result;
    }

    private static CreateProjectRequest previewRequest() {
        return ProjectRequests.preview("Project Alpha", "ALPHA", "alice");
    }

    private static CreateProjectRequest applyRequest(String planId) {
        return ProjectRequests.apply(planId);
    }

    private CreateProjectResponse preview() {
        return creator.create(previewRequest());
    }

    private CreateProjectResponse previewAndApply() {
        CreateProjectResponse preview = preview();
        assertEquals(Status.WOULD_CREATE, preview.getStatus());
        return creator.create(applyRequest(preview.getPlanId()));
    }

    private Project existing(String name, String key, String leadKey) {
        Project result = mock(Project.class);
        when(result.getName()).thenReturn(name);
        when(result.getKey()).thenReturn(key);
        when(result.getId()).thenReturn(19L);
        when(result.getLeadUserKey()).thenReturn(leadKey);
        when(result.getProjectTypeKey()).thenReturn(new ProjectTypeKey("business"));
        return result;
    }

    @Test
    public void previewValidatesWithoutWritingOrLockingAndReturnsExpiringPlanId() {
        CreateProjectResponse response = preview();
        assertEquals(Status.WOULD_CREATE, response.getStatus());
        assertTrue(response.isDryRun());
        assertEquals("plan-1", response.getPlanId());
        assertEquals(Long.valueOf(123456789L), response.getExpiresAt());
        assertNull(response.getProjectId());
        assertEquals("business", response.getProjectType());
        assertEquals(ProjectTemplates.PROJECT_MANAGEMENT, response.getTemplateKey());
        assertEquals("alice-key", response.getLead().getUserKey());
        verify(projectService).validateCreateProject(eq(admin), any(ProjectCreationData.class));
        verify(projectService, never()).createProject(any(ProjectService.CreateProjectValidationResult.class));
        verifyNoInteractions(lock);
    }

    @Test
    public void applyRebuildsMatchingPlanAndWritesUnderClusterLock() {
        CreateProjectResponse preview = preview();
        Project created = existing("Project Alpha", "ALPHA", "alice-key");
        when(created.getLead()).thenReturn(lead);
        when(projectService.validateCreateProject(eq(admin), any(ProjectCreationData.class)))
                .thenAnswer(invocation -> {
                    assertTrue("apply validation must be inside the cluster lock", insideLock.get());
                    return valid;
                });
        when(projectService.createProject(valid)).thenAnswer(invocation -> {
            assertTrue("Jira write must be inside the cluster lock", insideLock.get());
            return created;
        });

        CreateProjectResponse response = creator.create(applyRequest(preview.getPlanId()));

        assertEquals(Status.CREATED, response.getStatus());
        assertEquals(Long.valueOf(19), response.getProjectId());
        assertFalse(response.isDryRun());
        verify(lock).run(eq(ProjectCreatorImpl.LOCK_NAME), eq("projects"), any());
        ArgumentCaptor<ProjectCreationData> data = ArgumentCaptor.forClass(ProjectCreationData.class);
        verify(projectService, times(2)).validateCreateProject(eq(admin), data.capture());
        assertEquals("Project Alpha", data.getValue().getName());
        assertEquals("ALPHA", data.getValue().getKey());
        assertEquals(lead, data.getValue().getLead());
        assertEquals("business", data.getValue().getProjectTypeKey().getKey());
        assertEquals(ProjectTemplates.PROJECT_MANAGEMENT, data.getValue().getProjectTemplateKey().getKey());
        verify(projectService).createProject(valid);
        verify(plans).remove("plan-1");
    }

    @Test
    public void applyWithoutPreviewAndApplyByAnotherActorAreStaleAndNeverWrite() {
        assertEquals(ErrorCode.PREVIEW_STALE, creator.create(applyRequest("unknown")).getErrorCode());

        CreateProjectResponse preview = preview();
        ApplicationUser otherAdmin = user("other", "other-key", true);
        when(guard.requireAdministrator()).thenReturn(otherAdmin);
        CreateProjectResponse foreign = creator.create(applyRequest(preview.getPlanId()));
        assertEquals(ErrorCode.PREVIEW_STALE, foreign.getErrorCode());
        verify(plans, never()).remove("plan-1");
        verify(projectService, never()).createProject(any(ProjectService.CreateProjectValidationResult.class));
    }

    @Test
    public void changedJiraStateMakesPreviewStaleUnderLock() {
        CreateProjectResponse preview = preview();
        when(projectManager.getProjectObjects()).thenAnswer(invocation -> {
            assertTrue("state must be rebuilt under the lock", insideLock.get());
            return Collections.singletonList(existing("Project Alpha", "ALPHA", "alice-key"));
        });

        CreateProjectResponse response = creator.create(applyRequest(preview.getPlanId()));

        assertEquals(ErrorCode.PREVIEW_STALE, response.getErrorCode());
        verify(projectService, never()).createProject(any(ProjectService.CreateProjectValidationResult.class));
        verify(plans).remove("plan-1");
    }

    @Test
    public void changedLeadTemplateOrValidationMakesPreviewStale() {
        CreateProjectResponse preview = preview();
        when(lead.isActive()).thenReturn(false);
        assertEquals(ErrorCode.PREVIEW_STALE, creator.create(applyRequest(preview.getPlanId())).getErrorCode());

        when(lead.isActive()).thenReturn(true);
        preview = preview();
        when(templates.getProjectTemplate(any(ProjectTemplateKey.class))).thenReturn(Optional.empty());
        assertEquals(ErrorCode.PREVIEW_STALE, creator.create(applyRequest(preview.getPlanId())).getErrorCode());

        Optional<ProjectTemplate> back = Optional.of(template(ProjectTemplates.PROJECT_MANAGEMENT, "business"));
        when(templates.getProjectTemplate(any(ProjectTemplateKey.class))).thenReturn(back);
        preview = preview();
        when(valid.isValid()).thenReturn(false);
        assertEquals(ErrorCode.PREVIEW_STALE, creator.create(applyRequest(preview.getPlanId())).getErrorCode());
        verify(projectService, never()).createProject(any(ProjectService.CreateProjectValidationResult.class));
    }

    @Test
    public void appliedPlanCannotBeReplayed() {
        Project created = existing("Project Alpha", "ALPHA", "alice-key");
        when(projectService.createProject(valid)).thenReturn(created);
        CreateProjectResponse preview = preview();
        assertEquals(Status.CREATED, creator.create(applyRequest(preview.getPlanId())).getStatus());
        assertEquals(ErrorCode.PREVIEW_STALE, creator.create(applyRequest(preview.getPlanId())).getErrorCode());
        verify(projectService).createProject(valid);
    }

    @Test
    public void existingAndConflictingProjectsAreReportedByPreviewWithoutPlan() {
        Project project = existing("project alpha", "alpha", "alice-key");
        when(projectManager.getProjectObjects()).thenReturn(Collections.singletonList(project));
        CreateProjectResponse exists = preview();
        assertEquals(Status.EXISTS, exists.getStatus());
        assertNull(exists.getPlanId());
        assertNull(exists.getProjectId());

        Project archived = existing("Old Project", "alpha", "alice-key");
        when(archived.isArchived()).thenReturn(true);
        when(projectManager.getProjectObjects()).thenReturn(Collections.<Project>emptyList());
        when(projectManager.getArchivedProjects()).thenReturn(Collections.singletonList(archived));
        assertEquals(ErrorCode.KEY_CONFLICT, preview().getErrorCode());
        verifyNoInteractions(plans);
    }

    @Test
    public void nameAndHistoricalKeyConflictsAreFoundCaseInsensitively() {
        Project project = existing("project alpha", "CURRENT", "alice-key");
        when(projectManager.getProjectObjects()).thenReturn(Collections.singletonList(project));
        when(projectManager.getAllProjectKeys(19L))
                .thenReturn(new HashSet<String>(Arrays.asList("CURRENT", "ALPHA")));
        assertEquals(ErrorCode.KEY_CONFLICT, preview().getErrorCode());

        when(projectManager.getAllProjectKeys(19L)).thenReturn(Collections.singleton("CURRENT"));
        assertEquals(ErrorCode.NAME_CONFLICT, preview().getErrorCode());
        verifyNoInteractions(plans);
    }

    @Test
    public void unknownInactiveAmbiguousAndDisplayNameLeadsAreRejected() {
        assertEquals(ErrorCode.UNKNOWN_LEAD, creator.create(ProjectRequests.preview("X", "X", "nobody")).getErrorCode());
        ApplicationUser inactive = user("inactive", "inactive-key", false);
        when(users.getUserByName("inactive")).thenReturn(inactive);
        assertEquals(ErrorCode.UNKNOWN_LEAD, creator.create(ProjectRequests.preview("X", "X", "inactive")).getErrorCode());
        ApplicationUser other = user("bob", "alice", true);
        when(users.getUserByKey("alice")).thenReturn(other);
        assertEquals(ErrorCode.AMBIGUOUS_LEAD, preview().getErrorCode());
        assertEquals(ErrorCode.UNKNOWN_LEAD, creator.create(
                ProjectRequests.preview("Project Alpha", "ALPHA", "alice Smith")).getErrorCode());
        verify(projectService, never()).createProject(any(ProjectService.CreateProjectValidationResult.class));
    }

    @Test
    public void jiraValidationErrorsAreReturnedByPreviewWithoutPlan() {
        ErrorCollection errors = mock(ErrorCollection.class);
        Map<String, String> fields = new HashMap<String, String>();
        fields.put("projectKey", "Key must start with a letter");
        when(errors.getErrors()).thenReturn(fields);
        when(errors.getErrorMessages()).thenReturn(Collections.singletonList("Invalid project name"));
        when(valid.getErrorCollection()).thenReturn(errors);
        when(valid.isValid()).thenReturn(false);

        CreateProjectResponse response = preview();

        assertEquals(ErrorCode.VALIDATION_ERROR, response.getErrorCode());
        assertTrue(response.getMessage(), response.getMessage().contains("projectKey: Key must start with a letter"));
        assertTrue(response.getMessage(), response.getMessage().contains("Invalid project name"));
        assertNull(response.getPlanId());
        verifyNoInteractions(plans);
    }

    @Test
    public void postWriteExceptionEscapesForJournalCompensationAndConsumesPlan() {
        IllegalStateException failure = new IllegalStateException("database details");
        when(projectService.createProject(valid)).thenThrow(failure);
        try { previewAndApply(); fail("Write failure must escape to the journal"); }
        catch (IllegalStateException expected) { assertTrue(expected == failure); }
        assertNull(issued.get());
    }

    @Test
    public void dryRunMustBePresentAndNonNullBeforeAnyProjectWork() {
        for (CreateProjectRequest request : new CreateProjectRequest[]{
                null,
                new CreateProjectRequest("create", ProjectTemplates.PROJECT_MANAGEMENT, null, "Project Alpha", "ALPHA", "alice", null, null, null, null)
        }) {
            try {
                creator.create(request);
                fail("Explicit dryRun is mandatory");
            } catch (InvalidRequestException expected) {
                assertTrue(expected.getMessage().contains("dryRun"));
            }
        }
        verifyNoInteractions(projectService, projectManager, lock, plans);
    }

    @Test
    public void previewAndApplyShapesAreEnforcedInServiceLayer() {
        List<CreateProjectRequest> invalid = Arrays.asList(
                new CreateProjectRequest("create", ProjectTemplates.PROJECT_MANAGEMENT, true, "Project Alpha", "ALPHA", "alice", "plan-1", null, null, null),
                new CreateProjectRequest(null, null, false, null, null, null, null, null, null, null),
                new CreateProjectRequest(null, null, false, "Project Alpha", "ALPHA", "alice", "plan-1", null, null, null),
                new CreateProjectRequest("create", ProjectTemplates.PROJECT_MANAGEMENT, false, null, null, null, "plan-1", null, null, null),
                new CreateProjectRequest("list_templates", null, false, null, null, null, "plan-1", null, null, null));
        for (CreateProjectRequest request : invalid) {
            try {
                creator.create(request);
                fail("Mixed preview/apply payload must be refused");
            } catch (InvalidRequestException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        verifyNoInteractions(projectService, projectManager, lock, plans);
    }

    @Test
    public void administratorPermissionIsCheckedOnBothOperations() {
        when(guard.requireAdministrator()).thenThrow(new AccessException("Forbidden", false));
        try {
            creator.create(applyRequest("plan-1"));
            fail("No unauthorised creation");
        } catch (AccessException expected) {
            assertEquals("Forbidden", expected.getMessage());
        }
        try {
            creator.listLeads("alice");
            fail("No unauthorised lookup");
        } catch (AccessException expected) {
            assertEquals("Forbidden", expected.getMessage());
        }
        verifyNoInteractions(projectService, projectManager, users, search, lock, plans);
    }

    @Test
    public void searchRequiresQueryReturnsAtMostTwentyActiveUsersAndNoEmail() {
        try {
            creator.listLeads("   ");
            fail("Blank search would reveal user directory");
        } catch (InvalidRequestException expected) {
            assertTrue(expected.getMessage().contains("non-empty"));
        }
        List<ApplicationUser> matches = new ArrayList<ApplicationUser>();
        matches.add(user("inactive", "inactive-key", false));
        for (int i = 0; i < 25; i++) {
            matches.add(user("user" + i, "key" + i, true));
        }
        when(search.findUsers(eq("alice"), any(UserSearchParams.class))).thenReturn(matches);
        List<ProjectLeadView> result = creator.listLeads(" alice ");
        assertEquals(20, result.size());
        assertEquals("user0", result.get(0).getUsername());
        ArgumentCaptor<UserSearchParams> params = ArgumentCaptor.forClass(UserSearchParams.class);
        verify(search).findUsers(eq("alice"), params.capture());
        assertTrue(params.getValue().includeActive());
        assertFalse(params.getValue().includeInactive());
        assertFalse(params.getValue().canMatchEmail());
        assertEquals(Integer.valueOf(20), params.getValue().getMaxResults());
    }

    // ---- project templates ------------------------------------------------------------------------

    private static ProjectTemplate described(String key, String type, String name, String description) {
        ProjectTemplate result = template(key, type);
        when(result.getName()).thenReturn(name);
        when(result.getDescription()).thenReturn(description);
        return result;
    }

    private static List<String> keys(ProjectTemplatesResponse response) {
        List<String> result = new ArrayList<String>();
        for (ProjectTemplatesResponse.Template row : response.getTemplates()) {
            result.add(row.getProjectTypeKey() + "|" + row.getTemplateKey() + "|" + row.isSupported());
        }
        return result;
    }

    @Test
    public void catalogListsJiraTemplatesSortedAndSupportedOnlyWhenKeyAndTypeMatchTheTable() {
        String jsm = "com.atlassian.servicedesk:itil-service-desk-project";
        List<ProjectTemplate> rows = Arrays.asList(
                described(ProjectTemplates.SCRUM, "business", "Scrum under a foreign type", "d1"),
                described(ProjectTemplates.KANBAN, "software", "Kanban", "d2"),
                described(jsm, "service_desk", "IT service management", "d3"),
                described(ProjectTemplates.TASK_MANAGEMENT, "business", "Task management", "d4"),
                described(ProjectTemplates.PROJECT_MANAGEMENT, "business", "Project management", "d5"),
                described("com.vendor:custom", "business", "Third party", null));
        when(templates.getProjectTemplates()).thenReturn(rows);

        ProjectTemplatesResponse response = creator.listTemplates();

        assertEquals(ProjectTemplatesResponse.Status.OK, response.getStatus());
        assertEquals(Arrays.asList(
                "business|com.atlassian.jira-core-project-templates:jira-core-project-management|true",
                "business|com.atlassian.jira-core-project-templates:jira-core-task-management|true",
                "business|com.pyxis.greenhopper.jira:gh-scrum-template|false",
                "business|com.vendor:custom|false",
                "service_desk|com.atlassian.servicedesk:itil-service-desk-project|false",
                "software|com.pyxis.greenhopper.jira:gh-kanban-template|true"), keys(response));
        ProjectTemplatesResponse.Template first = response.getTemplates().get(0);
        assertEquals("Project management", first.getName());
        assertEquals("d5", first.getDescription());
        assertNull(response.getErrorCode());
    }

    @Test
    public void catalogEmptyAndCatalogErrorAreDistinctAndNeitherPlansJournalsNorWrites() {
        when(templates.getProjectTemplates()).thenReturn(Collections.<ProjectTemplate>emptyList());
        ProjectTemplatesResponse empty = creator.listTemplates();
        assertEquals(ProjectTemplatesResponse.Status.OK, empty.getStatus());
        assertTrue(empty.getTemplates().isEmpty());
        assertNull(empty.getErrorCode());

        when(templates.getProjectTemplates()).thenThrow(new IllegalStateException("registry down"));
        ProjectTemplatesResponse failed = creator.listTemplates();
        assertEquals(ProjectTemplatesResponse.Status.FAILED, failed.getStatus());
        assertEquals("JIRA_ERROR", failed.getErrorCode());
        assertTrue(failed.getTemplates().isEmpty());
        assertFalse(failed.getMessage(), failed.getMessage().contains("registry down"));

        verifyNoInteractions(plans, journal, projectService, projectManager, lock, undo);
    }

    @Test
    public void catalogIsAdministratorOnly() {
        when(guard.requireAdministrator()).thenThrow(new AccessException("Forbidden", false));
        try {
            creator.listTemplates();
            fail("No catalog for non-administrators");
        } catch (AccessException expected) {
            assertEquals("Forbidden", expected.getMessage());
        }
        verifyNoInteractions(templates, plans, journal);
    }

    private static final String[][] SUPPORTED = {
            {ProjectTemplates.PROJECT_MANAGEMENT, "business"}, {ProjectTemplates.TASK_MANAGEMENT, "business"},
            {ProjectTemplates.PROCESS_MANAGEMENT, "business"}, {ProjectTemplates.SCRUM, "software"},
            {ProjectTemplates.KANBAN, "software"}, {ProjectTemplates.BASIC, "software"}};

    @Test
    public void everySupportedTemplateIsPreviewedAndCreatedWithItsOwnTypeAndKey() {
        for (String[] row : SUPPORTED) {
            templateExists(row[0], row[1]);
            CreateProjectResponse preview = creator.create(ProjectRequests.preview(row[0], "Project Alpha", "ALPHA", "alice"));
            assertEquals(row[0], Status.WOULD_CREATE, preview.getStatus());
            assertEquals(row[1], preview.getProjectType());
            assertEquals(row[0], preview.getTemplateKey());

            Project created = existing("Project Alpha", "ALPHA", "alice-key");
            when(created.getProjectTypeKey()).thenReturn(new ProjectTypeKey(row[1]));
            when(created.getLead()).thenReturn(lead);
            when(projectService.createProject(valid)).thenReturn(created);
            CreateProjectResponse applied = creator.create(applyRequest(preview.getPlanId()));
            assertEquals(row[0], Status.CREATED, applied.getStatus());
            assertEquals(row[1], applied.getProjectType());
            assertEquals(row[0], applied.getTemplateKey());

            ArgumentCaptor<ProjectCreationData> data = ArgumentCaptor.forClass(ProjectCreationData.class);
            verify(projectService, org.mockito.Mockito.atLeastOnce()).validateCreateProject(eq(admin), data.capture());
            ProjectCreationData last = data.getValue();
            assertEquals(row[1], last.getProjectTypeKey().getKey());
            assertEquals(row[0], last.getProjectTemplateKey().getKey());
            org.mockito.Mockito.clearInvocations(projectService);
        }
    }

    @Test
    public void absentTemplateIsUnavailableWhileKeyOutsideTheTableOrForeignTypeIsUnsupported() {
        String jsm = "com.atlassian.servicedesk:itil-service-desk-project";
        when(templates.getProjectTemplate(eq(new ProjectTemplateKey("com.vendor:gone")))).thenReturn(Optional.empty());
        templateExists(jsm, "service_desk");
        templateExists("com.vendor:custom", "business");
        templateExists(ProjectTemplates.SCRUM, "business");

        assertEquals(ErrorCode.TEMPLATE_UNAVAILABLE,
                creator.create(ProjectRequests.preview("com.vendor:gone", "Project Alpha", "ALPHA", "alice")).getErrorCode());
        for (String key : new String[] {jsm, "com.vendor:custom", ProjectTemplates.SCRUM}) {
            CreateProjectResponse refused = creator.create(ProjectRequests.preview(key, "Project Alpha", "ALPHA", "alice"));
            assertEquals(key, Status.FAILED, refused.getStatus());
            assertEquals(key, ErrorCode.UNSUPPORTED_TEMPLATE, refused.getErrorCode());
        }
        verify(projectService, never()).validateCreateProject(any(), any(ProjectCreationData.class));
        verify(projectService, never()).createProject(any(ProjectService.CreateProjectValidationResult.class));
        verifyNoInteractions(plans);
    }

    @Test
    public void templateKeyAndTypeAreInTheFingerprint() {
        templateExists(ProjectTemplates.TASK_MANAGEMENT, "business");
        creator.create(ProjectRequests.preview(ProjectTemplates.PROJECT_MANAGEMENT, "Project Alpha", "ALPHA", "alice"));
        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(plans).issue(anyString(), anyString(), anyString(), fingerprint.capture());
        String projectManagement = fingerprint.getValue();
        org.mockito.Mockito.clearInvocations(plans);
        creator.create(ProjectRequests.preview(ProjectTemplates.TASK_MANAGEMENT, "Project Alpha", "ALPHA", "alice"));
        verify(plans).issue(anyString(), anyString(), anyString(), fingerprint.capture());
        assertFalse("another template is another plan", projectManagement.equals(fingerprint.getValue()));
    }

    @Test
    public void templateVanishingBeforeApplyIsStaleBeforeAnyJournalOrJiraWrite() {
        templateExists(ProjectTemplates.KANBAN, "software");
        CreateProjectResponse preview = creator.create(
                ProjectRequests.preview(ProjectTemplates.KANBAN, "Project Alpha", "ALPHA", "alice"));
        assertEquals(Status.WOULD_CREATE, preview.getStatus());
        when(templates.getProjectTemplate(eq(new ProjectTemplateKey(ProjectTemplates.KANBAN)))).thenReturn(Optional.empty());

        CreateProjectResponse applied = creator.create(applyRequest(preview.getPlanId()));

        assertEquals(ErrorCode.PREVIEW_STALE, applied.getErrorCode());
        verify(projectService, never()).createProject(any(ProjectService.CreateProjectValidationResult.class));
        verify(journal, never()).prepare(any(), any(), any(), any());
        verify(plans).remove("plan-1");
    }

    @Test
    public void existingProjectOfAnotherNativeTypeIsKeyConflictWithoutPlanOrWrite() {
        templateExists(ProjectTemplates.SCRUM, "software");
        Project business = existing("Project Alpha", "ALPHA", "alice-key");
        when(projectManager.getProjectObjects()).thenReturn(Collections.singletonList(business));

        CreateProjectResponse conflict = creator.create(
                ProjectRequests.preview(ProjectTemplates.SCRUM, "Project Alpha", "ALPHA", "alice"));

        assertEquals(Status.FAILED, conflict.getStatus());
        assertEquals(ErrorCode.KEY_CONFLICT, conflict.getErrorCode());
        verifyNoInteractions(plans);
        verify(projectService, never()).createProject(any(ProjectService.CreateProjectValidationResult.class));

        Project software = existing("Project Alpha", "ALPHA", "alice-key");
        when(software.getProjectTypeKey()).thenReturn(new ProjectTypeKey("software"));
        when(projectManager.getProjectObjects()).thenReturn(Collections.singletonList(software));
        CreateProjectResponse same = creator.create(
                ProjectRequests.preview(ProjectTemplates.SCRUM, "Project Alpha", "ALPHA", "alice"));
        assertEquals(Status.EXISTS, same.getStatus());
        assertNull("an existing project's template is not known", same.getTemplateKey());
        verifyNoInteractions(plans);
    }

    @Test
    public void theOldCreateFormAndAMissingTemplateKeyAreRefusedBeforeAnyWork() {
        List<CreateProjectRequest> refused = Arrays.asList(
                new CreateProjectRequest(null, null, true, "Project Alpha", "ALPHA", "alice", null, null, null, null),
                new CreateProjectRequest("create", null, true, "Project Alpha", "ALPHA", "alice", null, null, null, null),
                new CreateProjectRequest("create", "  ", true, "Project Alpha", "ALPHA", "alice", null, null, null, null),
                new CreateProjectRequest(null, ProjectTemplates.PROJECT_MANAGEMENT, true, "Project Alpha", "ALPHA", "alice", null, null, null, null),
                new CreateProjectRequest("list_templates", null, true, "Project Alpha", "ALPHA", "alice", null, null, null, null),
                new CreateProjectRequest("unknown", ProjectTemplates.PROJECT_MANAGEMENT, true, "Project Alpha", "ALPHA", "alice", null, null, null, null));
        for (CreateProjectRequest request : refused) {
            try {
                creator.create(request);
                fail("Refused: " + request.getAction() + "/" + request.getTemplateKey());
            } catch (InvalidRequestException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        verifyNoInteractions(projectService, projectManager, lock, plans, templates);
    }
}
