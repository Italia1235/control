package ru.rasulov.projects.impl;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import org.junit.Test;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.api.dto.report.ChangedObject;
import ru.rasulov.common.api.dto.issuetype.IssueTypeDefinition;
import ru.rasulov.common.api.dto.issuetype.ItemStatus;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView.AssignmentState;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest.TypeMapping;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse;
import ru.rasulov.issuetypes.impl.ProjectTypeWorld;
import ru.rasulov.projects.api.dto.CreateProjectRequest;
import ru.rasulov.projects.api.dto.CreateProjectResponse;
import ru.rasulov.projects.api.dto.CreateProjectResponse.ErrorCode;
import ru.rasulov.projects.api.dto.CreateProjectResponse.Status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F06 on a stateful fake Jira with the real planners, writers, journal and tools: a project is created together with its
 * complete issue-type set in one plan; every assertion reads the fake Jira's state.
 */
public class ProjectTypeSetFlowTest {
    private static final List<IssueTypeDefinition> FIVE = Arrays.asList(def("pay", "Оплаты"), def("consult", "Консультация"),
            def("paper", "Бумажные документы"), def("supply", "Снабжение"), def("ka", "Работа с КА"));

    // --- the target scenario ----------------------------------------------------------------------------------

    @Test
    public void newProjectGetsExactlyTheFiveTypesWithoutTheTemplateOnesAndOtherProjectsKeepTheirs() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            IssueType consult = w.add("7", "Консультация", false);
            w.schemeStates.get(ProjectTypeWorld.DEFAULT_SCHEME).typeIds.add("7");
            List<String> defaultSchemeBefore = new ArrayList<String>(w.schemeStates.get(ProjectTypeWorld.DEFAULT_SCHEME).typeIds);

            CreateProjectResponse preview = env.creator.create(request(true, null, FIVE, null));

            assertEquals(preview.getMessage(), Status.WOULD_CREATE, preview.getStatus());
            IssueTypeSetView view = preview.getIssueTypeSet();
            assertEquals(5, view.getEntries().size());
            assertEquals(Arrays.asList(ItemStatus.WOULD_CREATE, ItemStatus.EXISTS, ItemStatus.WOULD_CREATE,
                    ItemStatus.WOULD_CREATE, ItemStatus.WOULD_CREATE), statuses(view));
            assertEquals("7", view.getEntries().get(1).getId());
            assertEquals("a default is chosen by a stated rule, not silently", "FIRST_STANDARD", view.getDefaultSource());
            assertEquals("Оплаты", view.getDefaultIssueType().getName());
            assertNull("template types are not known before the project exists", view.getExcludedTypes());
            assertTrue(view.getExcludedNote().contains("never deletes it globally"));
            assertTrue(view.getGlobalImpact().contains("4 new types is global"));
            assertFalse(view.getResidualRisks().isEmpty());
            assertEquals("a preview writes nothing", 0, env.projectCreations);
            assertTrue(w.createCalls.isEmpty());
            assertNull(w.projectByKey("ALPHA"));

            CreateProjectResponse applied = env.runApply("create_project", () -> env.creator.create(request(false, preview.getPlanId(), null, null)));

            assertEquals(applied.getMessage(), Status.CREATED, applied.getStatus());
            assertEquals(1, env.projectCreations);
            Project project = w.projectByKey("ALPHA");
            assertEquals(Long.valueOf(project.getId()), applied.getProjectId());
            assertEquals("four types were created, in request order", Arrays.asList("Оплаты", "Бумажные документы", "Снабжение", "Работа с КА"), w.createCalls);
            ProjectTypeWorld.SchemeState own = w.schemeOf(project);
            assertFalse("the project got a private scheme, the shared one was not edited", own.id == ProjectTypeWorld.DEFAULT_SCHEME);
            assertEquals(Collections.singletonList(project), own.associated);
            assertEquals("exactly five types, no template types", Arrays.asList("Оплаты", "Консультация", "Бумажные документы", "Снабжение", "Работа с КА"), w.typeNamesOf(project));
            assertEquals(w.type("Оплаты").getId(), own.defaultTypeId);
            IssueTypeSetView.Assignment assignment = applied.getIssueTypeSet().getAssignment();
            assertEquals(AssignmentState.COMPLETED, assignment.getState());
            assertEquals(5, assignment.getIssueTypes().size());
            assertEquals(Arrays.asList("Task", "Sub-task", "Bug"), names(assignment.getExcludedTypes()));
            assertEquals("Оплаты", assignment.getDefaultIssueType().getName());
            assertEquals(Arrays.asList(ItemStatus.CREATED, ItemStatus.EXISTS, ItemStatus.CREATED, ItemStatus.CREATED, ItemStatus.CREATED),
                    statuses(applied.getIssueTypeSet()));
            for (IssueTypeSetView.Entry entry : applied.getIssueTypeSet().getEntries()) assertEquals(w.type(entry.getName()).getId(), entry.getId());
            assertEquals("the shared scheme keeps every type it had, plus the four new ones Jira adds",
                    defaultSchemeBefore, w.schemeStates.get(ProjectTypeWorld.DEFAULT_SCHEME).typeIds.subList(0, defaultSchemeBefore.size()));
            for (String other : Arrays.asList("OTHER", "NEIGHBOR")) {
                assertEquals(Long.valueOf(ProjectTypeWorld.DEFAULT_SCHEME), w.projectScheme.get(w.projectByKey(other).getId()));
                assertTrue(w.typeNamesOf(w.projectByKey(other)).containsAll(Arrays.asList("Task", "Sub-task", "Bug")));
            }
            assertEquals("a type leaving a project is never deleted", 0, w.removeCalls);
            assertNotNull(w.type("Task"));
            assertEquals(1, applied.getReport().getReused().size());
            assertEquals(consult.getId(), applied.getReport().getReused().get(0).getId());
            List<ChangedObject> created = applied.getReport().getCreated();
            assertEquals(5, created.size());
            assertEquals(ChangeKind.PROJECT, created.get(0).getKind());
            assertEquals(String.valueOf(project.getId()), created.get(0).getId());
            assertEquals(w.type("Оплаты").getId(), created.get(1).getId());
            assertEquals("the progress receipt of the assignment is kept on the operation", "COMPLETED",
                    ((java.util.Map<?, ?>) env.fixture.journal.getOperation(env.lastOperation, "admin").get("execution")).get("outcome"));
        }
    }

    @Test
    public void explicitDefaultIsHonoredAndShownAndAnInvalidOneIsRefusedBeforeAnyWrite() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            CreateProjectResponse preview = env.creator.create(request(true, null, FIVE, "supply"));
            assertEquals("REQUESTED", preview.getIssueTypeSet().getDefaultSource());
            assertEquals("Снабжение", preview.getIssueTypeSet().getDefaultIssueType().getName());
            env.runApply("create_project", () -> env.creator.create(request(false, preview.getPlanId(), null, null)));
            assertEquals(w.type("Снабжение").getId(), w.schemeOf(w.projectByKey("ALPHA")).defaultTypeId);

            try (ProjectFlowEnv other = new ProjectFlowEnv()) {
                other.world.add("8", "Подзадача заказа", true);
                for (String bad : Arrays.asList("missing", "Подзадача заказа")) {
                    List<IssueTypeDefinition> defs = Arrays.asList(def("a", "Заказ"), new IssueTypeDefinition("sub", "Подзадача заказа", null, true));
                    CreateProjectResponse refused = other.creator.create(request(true, null, defs, bad));
                    assertEquals(refused.getMessage(), ErrorCode.VALIDATION_ERROR, refused.getErrorCode());
                    assertNull(refused.getPlanId());
                }
                CreateProjectResponse noSet = other.creator.create(ProjectRequests.preview("Alpha", "ALPHA", "admin",
                        null, null, "Task"));
                assertEquals(ErrorCode.VALIDATION_ERROR, noSet.getErrorCode());
                assertEquals(0, other.projectCreations);
            }
        }
    }

    @Test
    public void everyTypeExistingStillCreatesTheProjectWithItsSetAndWritesNoType() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            CreateProjectResponse preview = env.creator.create(ProjectRequests.preview("Alpha", "ALPHA", "admin",
                    Arrays.asList("Task", "3"), null, null));
            assertEquals(preview.getMessage(), Status.WOULD_CREATE, preview.getStatus());
            assertEquals(0, preview.getIssueTypeSet().getEntries().get(0).getKey() == null ? 0 : 1);

            CreateProjectResponse applied = env.runApply("create_project", () -> env.creator.create(request(false, preview.getPlanId(), null, null)));

            assertEquals(applied.getMessage(), Status.CREATED, applied.getStatus());
            assertTrue(w.createCalls.isEmpty());
            assertEquals(Arrays.asList("Task", "Bug"), w.typeNamesOf(w.projectByKey("ALPHA")));
            assertEquals(2, applied.getReport().getReused().size());
            assertEquals(1, applied.getReport().getCreated().size());
            assertEquals("Task", applied.getIssueTypeSet().getAssignment().getDefaultIssueType().getName());
        }
    }

    // --- everything is validated before the first write -------------------------------------------------------

    @Test
    public void conflictsAreFoundBeforeAnythingIsWritten() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            int before = w.catalog.size();
            assertRefused(env, ErrorCode.VALIDATION_ERROR, request(true, null, Arrays.asList(def("k", "Один"), def("k", "Два")), null));
            assertRefused(env, ErrorCode.VALIDATION_ERROR, request(true, null, Arrays.asList(def("a", "Один"), def("b", "ОДИН")), null));
            assertRefused(env, ErrorCode.VALIDATION_ERROR,
                    request(true, null, Collections.singletonList(new IssueTypeDefinition("t", "Task", null, true)), null)); // Task is standard
            assertRefused(env, ErrorCode.VALIDATION_ERROR,
                    request(true, null, Collections.singletonList(new IssueTypeDefinition("s", "Sub-task", null, false)), null));
            assertRefused(env, ErrorCode.UNKNOWN_ISSUE_TYPE, ProjectRequests.preview("Alpha", "ALPHA", "admin",
                    Arrays.asList("Task", "Нет такого"), null, null));
            assertRefused(env, ErrorCode.VALIDATION_ERROR, ProjectRequests.preview("Alpha", "ALPHA", "admin",
                    Collections.singletonList("Task"), Collections.singletonList(def("t", "task")), null)); // reference + definition of one type
            assertRefused(env, ErrorCode.VALIDATION_ERROR, ProjectRequests.preview("Alpha", "ALPHA", "admin",
                    null, Collections.singletonList(new IssueTypeDefinition("s", "Только подзадача", null, true)), null)); // no standard type
            assertRefused(env, ErrorCode.VALIDATION_ERROR, ProjectRequests.preview("Alpha", "ALPHA", "admin",
                    Arrays.asList("Task", "1"), null, null)); // the same type twice
            Project taken = w.project("ALPHA", ProjectTypeWorld.DEFAULT_SCHEME);
            when(taken.getName()).thenReturn("Alpha");
            when(taken.getLeadUserKey()).thenReturn("admin");
            CreateProjectResponse keyTaken = env.creator.create(request(true, null, FIVE, null));
            assertEquals(keyTaken.getMessage(), ErrorCode.KEY_CONFLICT, keyTaken.getErrorCode());
            assertTrue(keyTaken.getMessage().contains("set_project_issue_types"));

            assertEquals(0, env.projectCreations);
            assertTrue(w.createCalls.isEmpty());
            assertEquals(before, w.catalog.size());
        }
    }

    // --- the selected template drives type and templateKey of plan and result ------------------------

    @Test
    public void softwareTemplateWithAnExplicitSetPlansAndReportsItsOwnTypeAndKeyAndKeepsTheSet() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            CreateProjectResponse preview = env.creator.create(new CreateProjectRequest("create", ProjectTemplates.SCRUM,
                    true, "Alpha", "ALPHA", "admin", null, null, Arrays.asList(def("a", "Оплаты"), def("b", "Консультация")), null));
            assertEquals(preview.getMessage(), Status.WOULD_CREATE, preview.getStatus());
            assertEquals("software", preview.getProjectType());
            assertEquals(ProjectTemplates.SCRUM, preview.getTemplateKey());

            CreateProjectResponse applied = env.runApply("create_project", () -> env.creator.create(request(false, preview.getPlanId(), null, null)));

            assertEquals(applied.getMessage(), Status.CREATED, applied.getStatus());
            assertEquals("software", applied.getProjectType());
            assertEquals(ProjectTemplates.SCRUM, applied.getTemplateKey());
            assertEquals(AssignmentState.COMPLETED, applied.getIssueTypeSet().getAssignment().getState());
            assertEquals(Arrays.asList("Оплаты", "Консультация"), w.typeNamesOf(w.projectByKey("ALPHA")));
        }
    }

    @Test
    public void partialOutcomeUnderASoftwareTemplateKeepsItsTypeAndKey() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            CreateProjectResponse preview = env.creator.create(new CreateProjectRequest("create", ProjectTemplates.KANBAN,
                    true, "Alpha", "ALPHA", "admin", null, null, Arrays.asList(def("a", "Оплаты"), def("c", "Снабжение")), null));
            w.failCreationOf = "Снабжение";

            CreateProjectResponse applied = env.runApply("create_project", () -> env.creator.create(request(false, preview.getPlanId(), null, null)));

            assertEquals(applied.getMessage(), Status.PARTIALLY_CHANGED, applied.getStatus());
            assertEquals("software", applied.getProjectType());
            assertEquals(ProjectTemplates.KANBAN, applied.getTemplateKey());
            assertNotNull(applied.getProjectId());
        }
    }

    // --- failures after the project exists --------------------------------------------------------------------

    @Test
    public void failureAfterTheProjectAndPartOfTheTypesLeavesThemAndNeverAssignsTheIncompleteSet() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            List<IssueTypeDefinition> defs = Arrays.asList(def("a", "Оплаты"), def("b", "Бумажные"), def("c", "Снабжение"), def("d", "Работа"));
            CreateProjectResponse preview = env.creator.create(request(true, null, defs, null));
            w.failCreationOf = "Снабжение";

            CreateProjectResponse applied = env.runApply("create_project", () -> env.creator.create(request(false, preview.getPlanId(), null, null)));

            assertEquals(applied.getMessage(), Status.PARTIALLY_CHANGED, applied.getStatus());
            assertNotNull("the project is real and its id is returned", applied.getProjectId());
            Project project = w.projectByKey("ALPHA");
            assertEquals(Long.valueOf(project.getId()), applied.getProjectId());
            assertEquals(Arrays.asList(ItemStatus.CREATED, ItemStatus.CREATED, ItemStatus.FAILED, ItemStatus.NOT_EXECUTED),
                    statuses(applied.getIssueTypeSet()));
            assertNotNull(w.type("Оплаты"));
            assertNotNull(w.type("Бумажные"));
            assertNull(w.type("Снабжение"));
            assertEquals(IssueTypeSetViewAssignment.NOT_STARTED, applied.getIssueTypeSet().getAssignment().getState());
            assertEquals("the project keeps the shared template scheme: its types were not touched",
                    Long.valueOf(ProjectTypeWorld.DEFAULT_SCHEME), w.projectScheme.get(project.getId()));
            assertEquals(1, w.schemeStates.size());
            assertEquals(1, applied.getReport().getCreated().size() - 2);
            assertTrue(applied.getMessage().contains("WAS created"));
            assertTrue(applied.getMessage().contains("NOT assigned"));

            // The reported state is real: a new plan for the existing project finishes the set without duplicates.
            w.failCreationOf = null;
            SetProjectIssueTypesResponse again = env.service.set(new SetProjectIssueTypesRequest(true, "ALPHA", Collections.<String>emptyList(),
                    null, null, defs, null));
            assertEquals(again.getMessage(), SetProjectIssueTypesResponse.Status.WOULD_CHANGE, again.getStatus());
            assertEquals(Arrays.asList(ItemStatus.EXISTS, ItemStatus.EXISTS, ItemStatus.WOULD_CREATE, ItemStatus.WOULD_CREATE),
                    statuses(again.getIssueTypeSet()));
            SetProjectIssueTypesResponse finished = env.runApply("set_project_issue_types",
                    () -> env.service.set(new SetProjectIssueTypesRequest(false, null, null, null, again.getPlanId())));
            assertEquals(finished.getMessage(), SetProjectIssueTypesResponse.Status.CHANGED, finished.getStatus());
            assertEquals(Arrays.asList("Оплаты", "Бумажные", "Снабжение", "Работа"), w.typeNamesOf(project));
            assertEquals(4, countNames(w, "Оплаты", "Бумажные", "Снабжение", "Работа"));
        }
    }

    @Test
    public void unknownOutcomeOfATypeBlocksTheAssignment() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            CreateProjectResponse preview = env.creator.create(request(true, null, Arrays.asList(def("a", "Оплаты"), def("b", "Снабжение")), null));
            w.failCreationOf = "Оплаты";
            w.failReadAfterFailure = true;

            CreateProjectResponse applied = env.runApply("create_project", () -> env.creator.create(request(false, preview.getPlanId(), null, null)));

            assertEquals(Status.PARTIALLY_CHANGED, applied.getStatus());
            assertEquals(Arrays.asList(ItemStatus.UNKNOWN, ItemStatus.NOT_EXECUTED), statuses(applied.getIssueTypeSet()));
            assertEquals(IssueTypeSetViewAssignment.NOT_STARTED, applied.getIssueTypeSet().getAssignment().getState());
            assertEquals(Long.valueOf(ProjectTypeWorld.DEFAULT_SCHEME), w.projectScheme.get(w.projectByKey("ALPHA").getId()));
            assertEquals("no scheme was created or changed", 1, w.schemeStates.size());
            assertTrue(applied.getReport().getCreated().size() == 1);
            assertFalse(applied.getMessage().contains("nothing was created"));
        }
    }

    @Test
    public void observedTypeAfterAnExceptionIsNeverTakenAsTheCreatedOneForTheAssignment() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            CreateProjectResponse preview = env.creator.create(request(true, null, Arrays.asList(def("a", "Оплаты"), def("b", "Снабжение")), null));
            w.failCreationOf = "Оплаты";
            w.failureLeavesType = true;

            CreateProjectResponse applied = env.runApply("create_project", () -> env.creator.create(request(false, preview.getPlanId(), null, null)));

            assertEquals(Status.PARTIALLY_CHANGED, applied.getStatus());
            IssueTypeSetView.Entry observed = applied.getIssueTypeSet().getEntries().get(0);
            assertEquals(ItemStatus.UNKNOWN, observed.getStatus());
            assertEquals("the id is shown as observed", w.type("Оплаты").getId(), observed.getId());
            assertEquals(IssueTypeSetViewAssignment.NOT_STARTED, applied.getIssueTypeSet().getAssignment().getState());
            assertEquals(1, w.schemeStates.size());
            assertEquals("only the project is in report.created", 1, applied.getReport().getCreated().size());
            assertEquals(0, w.removeCalls);
            assertNotNull("never removed", w.type("Оплаты"));
        }
    }

    @Test
    public void anIssueThatAppearsInAnExcludedTemplateTypeIsNeverMigratedSilently() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            CreateProjectResponse preview = env.creator.create(request(true, null, Arrays.asList(def("a", "Оплаты"), def("b", "Снабжение")), null));
            w.afterProjectCreated = () -> w.issueRows.add(new String[] {"1", "10001"}); // someone files a Task right away

            CreateProjectResponse applied = env.runApply("create_project", () -> env.creator.create(request(false, preview.getPlanId(), null, null)));

            assertEquals(applied.getMessage(), Status.PARTIALLY_CHANGED, applied.getStatus());
            assertEquals(ErrorCode.MAPPING_REQUIRED, applied.getErrorCode());
            assertEquals(Arrays.asList(ItemStatus.CREATED, ItemStatus.CREATED), statuses(applied.getIssueTypeSet()));
            assertEquals(IssueTypeSetViewAssignment.NOT_STARTED, applied.getIssueTypeSet().getAssignment().getState());
            assertTrue(applied.getMessage().contains("new plan"));
            w.verifyMigrationCalls(0);
            assertEquals(Long.valueOf(ProjectTypeWorld.DEFAULT_SCHEME), w.projectScheme.get(w.projectByKey("ALPHA").getId()));
            assertEquals(1, w.schemeStates.size());
            assertNotNull(w.type("Оплаты"));
        }
    }

    @Test
    public void failureInsideTheAssignmentIsAResultWithItsReceiptAndIsNotRolledBack() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            CreateProjectResponse preview = env.creator.create(request(true, null, Arrays.asList(def("a", "Оплаты"), def("b", "Снабжение")), null));
            w.failSchemeCreate = true;

            CreateProjectResponse applied = env.runApply("create_project", () -> env.creator.create(request(false, preview.getPlanId(), null, null)));

            assertEquals(applied.getMessage(), Status.PARTIALLY_CHANGED, applied.getStatus());
            IssueTypeSetView.Assignment assignment = applied.getIssueTypeSet().getAssignment();
            assertEquals("whether the scheme write happened is not known", AssignmentState.UNKNOWN, assignment.getState());
            assertNotNull(assignment.getExecution());
            assertEquals("UNKNOWN", assignment.getExecution().getOutcome().name());
            assertNotNull("the project and both types stay", w.projectByKey("ALPHA"));
            assertNotNull(w.type("Оплаты"));
            assertEquals(0, w.removeCalls);
            assertNotNull(env.fixture.journal.getOperation(env.lastOperation, "admin").get("execution"));
        }
    }

    // --- an existing project ----------------------------------------------------------------------------------

    @Test
    public void existingProjectReplacesItsSetWithNewTypesAnOccupiedLeavingTypeNeedsItsMappingAndNothingIsDeletedGlobally() {
        try (ProjectFlowEnv env = new ProjectFlowEnv()) {
            ProjectTypeWorld w = env.world;
            w.add("7", "Консультация", false);
            ProjectTypeWorld.SchemeState own = new ProjectTypeWorld.SchemeState(600L, "BETA types");
            own.typeIds.addAll(Arrays.asList("1", "3"));
            own.defaultTypeId = "1";
            w.schemeStates.put(600L, own);
            Project beta = w.project("BETA", 600L);
            w.issueRows.add(new String[] {"3", "1"});
            w.issueRows.add(new String[] {"3", "1"});
            w.issueRows.add(new String[] {"3", "1"});
            com.atlassian.jira.workflow.JiraWorkflow workflow = mock(com.atlassian.jira.workflow.JiraWorkflow.class);
            when(workflow.getName()).thenReturn("wf");
            when(workflow.getLinkedStatusIds()).thenReturn(Collections.singleton("1"));
            when(workflow.getLinkedStatusObjects()).thenReturn(Collections.<com.atlassian.jira.issue.status.Status>emptyList());
            com.atlassian.jira.workflow.AssignableWorkflowScheme wfScheme = mock(com.atlassian.jira.workflow.AssignableWorkflowScheme.class);
            when(wfScheme.getActualWorkflow(anyString())).thenReturn("wf");
            when(w.workflowSchemes.getWorkflowSchemeObj(beta)).thenReturn(wfScheme);
            when(w.workflows.getWorkflow("wf")).thenReturn(workflow);
            List<IssueTypeDefinition> defs = Arrays.asList(def("consult", "Консультация"), def("order", "Заказ"));

            // The occupied type Bug leaves: without its mapping nothing is created.
            SetProjectIssueTypesResponse refused = env.service.set(new SetProjectIssueTypesRequest(true, "BETA",
                    Collections.singletonList("Task"), null, null, defs, null));
            assertEquals(refused.getMessage(), SetProjectIssueTypesResponse.ErrorCode.MAPPING_REQUIRED, refused.getErrorCode());
            assertTrue(w.createCalls.isEmpty());

            // A sub-task target for a standard type is refused.
            SetProjectIssueTypesResponse guard = env.service.set(new SetProjectIssueTypesRequest(true, "BETA",
                    Arrays.asList("Task", "Sub-task"), Collections.singletonList(new TypeMapping("Bug", "Sub-task", null)), null, defs, null));
            assertEquals(SetProjectIssueTypesResponse.ErrorCode.INVALID_MAPPING, guard.getErrorCode());

            SetProjectIssueTypesResponse preview = env.service.set(new SetProjectIssueTypesRequest(true, "BETA",
                    Collections.singletonList("Task"), Collections.singletonList(new TypeMapping("Bug", "Task", null)), null,
                    defs, "order"));
            assertEquals(preview.getMessage(), SetProjectIssueTypesResponse.Status.WOULD_CHANGE, preview.getStatus());
            assertEquals(1, preview.getMappings().size());
            assertEquals(3, preview.getMappings().get(0).getIssues());
            assertEquals("REQUESTED", preview.getIssueTypeSet().getDefaultSource());
            assertEquals(Arrays.asList(null, ItemStatus.EXISTS, ItemStatus.WOULD_CREATE), statusesNullable(preview.getIssueTypeSet()));
            assertTrue(w.createCalls.isEmpty());

            SetProjectIssueTypesResponse applied = env.runApply("set_project_issue_types",
                    () -> env.service.set(new SetProjectIssueTypesRequest(false, null, null, null, preview.getPlanId())));

            assertEquals(applied.getMessage(), SetProjectIssueTypesResponse.Status.CHANGED, applied.getStatus());
            assertEquals(Collections.singletonList("Заказ"), w.createCalls);
            assertEquals(Arrays.asList("Task", "Консультация", "Заказ"), w.typeNamesOf(beta));
            assertEquals("the explicit default", w.type("Заказ").getId(), own.defaultTypeId);
            w.verifyMigrationCalls(2);
            assertNotNull("Bug still exists globally", w.type("Bug"));
            assertEquals(0, w.removeCalls);
            assertEquals(Long.valueOf(ProjectTypeWorld.DEFAULT_SCHEME), w.projectScheme.get(w.projectByKey("OTHER").getId()));
            assertEquals(1, applied.getReport().getCreated().size());
            assertEquals("the kept reference and the reused definition", 2, applied.getReport().getReused().size());
            assertEquals(AssignmentState.COMPLETED, applied.getIssueTypeSet().getAssignment().getState());
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    /** Short alias so the assertions read as states. */
    private static final class IssueTypeSetViewAssignment {
        static final AssignmentState NOT_STARTED = AssignmentState.NOT_STARTED;
    }

    private static void assertRefused(ProjectFlowEnv env, ErrorCode code, CreateProjectRequest request) {
        CreateProjectResponse refused = env.creator.create(request);
        assertEquals(refused.getMessage(), Status.FAILED, refused.getStatus());
        assertEquals(refused.getMessage(), code, refused.getErrorCode());
        assertNull(refused.getPlanId());
    }

    private static CreateProjectRequest request(boolean dryRun, String planId, List<IssueTypeDefinition> defs, String defaultType) {
        if (!dryRun) return ProjectRequests.apply(planId);
        return ProjectRequests.preview("Alpha", "ALPHA", "admin", null, defs, defaultType);
    }

    private static IssueTypeDefinition def(String key, String name) {
        return new IssueTypeDefinition(key, name, null, false);
    }

    private static List<ItemStatus> statuses(IssueTypeSetView view) {
        List<ItemStatus> statuses = new ArrayList<ItemStatus>();
        for (IssueTypeSetView.Entry entry : view.getEntries()) statuses.add(entry.getStatus());
        return statuses;
    }

    /** Existing references have status EXISTS too; the first entry of a list of one reference is not part of this check. */
    private static List<ItemStatus> statusesNullable(IssueTypeSetView view) {
        List<ItemStatus> statuses = statuses(view);
        statuses.set(0, null);
        return statuses;
    }

    private static List<String> names(List<IssueTypeRef> refs) {
        List<String> names = new ArrayList<String>();
        for (IssueTypeRef ref : refs) names.add(ref.getName());
        return names;
    }

    private static int countNames(ProjectTypeWorld world, String... names) {
        int count = 0;
        for (IssueType type : world.catalog) if (Arrays.asList(names).contains(type.getName())) count++;
        return count;
    }
}
