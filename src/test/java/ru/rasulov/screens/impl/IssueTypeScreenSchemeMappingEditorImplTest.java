package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsRequest;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsRequest.Row;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.Change;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.ErrorCode;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.LinkedProject;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.RowAction;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.RowPlan;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.RowResult;
import ru.rasulov.screens.api.dto.SetIssueTypeScreenSchemeMappingsResponse.Status;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real planner/writer/executor over the in-memory model of {@link ItssMappingWorld}. */
public class IssueTypeScreenSchemeMappingEditorImplTest {
    private final ItssMappingWorld world = new ItssMappingWorld();
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final ActionPlanStore plans = mock(ActionPlanStore.class);
    private final ApplicationUser admin = mock(ApplicationUser.class);
    private final Map<String, ActionPlanStore.Plan> savedPlans = new HashMap<String, ActionPlanStore.Plan>();
    private final Map<String, String> planActors = new HashMap<String, String>();
    private IssueTypeScreenSchemeMappingEditorImpl service;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        when(admin.getKey()).thenReturn("admin"); when(guard.requireAdministrator()).thenReturn(admin);
        when(lock.run(anyString(), anyString(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
        when(plans.issue(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            String id = "plan-" + world.sequence.incrementAndGet();
            ActionPlanStore.Plan plan = new ActionPlanStore.Plan(id, call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), Long.MAX_VALUE);
            savedPlans.put(id, plan); planActors.put(id, call.getArgument(1)); return plan;
        });
        when(plans.find(anyString(), anyString(), anyString())).thenAnswer(call -> {
            String id = call.getArgument(2); return Objects.equals(planActors.get(id), call.getArgument(1)) ? savedPlans.get(id) : null;
        });
        doAnswer(call -> { savedPlans.remove(call.getArgument(0)); return null; }).when(plans).remove(anyString());
        service = new IssueTypeScreenSchemeMappingEditorImpl(guard, lock,
                new IssueTypeScreenSchemeMappingPlanner(world.types, world.fields, world.tops, world.projectManager, world.codec),
                new IssueTypeScreenSchemeMappingWriter(world.factory, world.tops),
                new PreviewPlanExecutor(plans, world.codec));
    }

    // ---- preview against the FINAL table -------------------------------------------------------------------

    @Test
    public void previewShowsBeforeAndAfterPerRowFromTheFinalTableInAnyRowOrder() {
        List<Row> rows = Arrays.asList(replace("DEFAULT", "Alt SS"), remove("Vacation"), add("Story", "HR SS"));
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Shared ITSS", rows));
        assertEquals(preview.getMessage(), Status.WOULD_CHANGE, preview.getStatus());
        assertTrue(preview.getPlanId() != null && preview.getPlanExpiresAt() != null);
        assertEquals(Long.valueOf(20), preview.getIssueTypeScreenScheme().getId());
        assertEquals("Shared ITSS", preview.getIssueTypeScreenScheme().getName());
        assertFalse(preview.getIssueTypeScreenScheme().isJiraDefault());
        assertEquals("a preview writes nothing", Collections.<String>emptyList(), world.writes);
        RowPlan def = preview.getRows().get(0), vacation = preview.getRows().get(1), story = preview.getRows().get(2);
        assertNull("DEFAULT has no issue type", def.getIssueType());
        assertEquals("DEFAULT", def.getScope());
        assertEquals(RowAction.REPLACE, def.getAction()); assertEquals(Change.CHANGE, def.getChange());
        assertEquals(Long.valueOf(30), def.getBefore().getId()); assertFalse(def.getBefore().isViaDefault());
        assertEquals(Long.valueOf(33), def.getAfter().getId());
        assertEquals("Vacation", vacation.getIssueType().getName());
        assertEquals(RowAction.REMOVE, vacation.getAction());
        assertEquals(Long.valueOf(31), vacation.getBefore().getId()); assertFalse(vacation.getBefore().isViaDefault());
        assertEquals("Vacation moves to the NEW default scheme", Long.valueOf(33), vacation.getAfter().getId());
        assertTrue(vacation.getAfter().isViaDefault());
        assertEquals("Story used the old default", Long.valueOf(30), story.getBefore().getId());
        assertTrue(story.getBefore().isViaDefault());
        assertEquals(RowAction.ADD, story.getAction()); assertEquals(Long.valueOf(31), story.getAfter().getId());
        assertFalse(story.getAfter().isViaDefault());
        assertEquals(Arrays.asList(0, 1, 2), rowIndexes(preview));
        for (String token : Arrays.asList("Задачи не мигрируют", "Копии не создаются", "Без отката",
                "Защита от параллельных правок ограничена")) {
            assertTrue(token, String.join("\n", preview.getLimitations()).contains(token));
        }
        SetIssueTypeScreenSchemeMappingsResponse reversed = service.edit(request("Shared ITSS",
                Arrays.asList(add("Story", "HR SS"), remove("Vacation"), replace("DEFAULT", "Alt SS"))));
        assertEquals(Status.WOULD_CHANGE, reversed.getStatus());
        assertEquals("the same per-row result in any order", Long.valueOf(33), reversed.getRows().get(1).getAfter().getId());
        assertEquals(Long.valueOf(31), reversed.getRows().get(0).getAfter().getId());
        assertEquals(Collections.<String>emptyList(), world.writes);
    }

    @Test
    public void addOfAnExistingRowIsTreatedAsReplaceWithAWarningAndTheSameSchemeIsNoChange() {
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Shared ITSS",
                Arrays.asList(add("Bug", "HR SS"), add("Vacation", "HR SS"))));
        assertEquals(Status.WOULD_CHANGE, preview.getStatus());
        RowPlan changed = preview.getRows().get(0), same = preview.getRows().get(1);
        assertEquals(RowAction.ADD, changed.getRequestedAction()); assertEquals(RowAction.REPLACE, changed.getAction());
        assertEquals(Change.CHANGE, changed.getChange());
        assertEquals(Long.valueOf(32), changed.getBefore().getId()); assertEquals(Long.valueOf(31), changed.getAfter().getId());
        assertEquals(Change.NO_CHANGE, same.getChange()); assertEquals(RowAction.REPLACE, same.getAction());
        assertEquals(2, preview.getWarnings().size());
        SetIssueTypeScreenSchemeMappingsResponse.Warning warning = preview.getWarnings().get(0);
        assertEquals("ADD_AS_REPLACE", warning.getCode()); assertEquals(Integer.valueOf(0), warning.getRowIndex());
        assertTrue(warning.getMessage(), warning.getMessage().contains("Bug SS") && warning.getMessage().contains("HR SS"));
        assertEquals("ADD_AS_REPLACE", preview.getWarnings().get(1).getCode());
        assertEquals(Integer.valueOf(1), preview.getWarnings().get(1).getRowIndex());
    }

    @Test
    public void allRowsNoChangeIsNoChangeWithoutAPlanOrAWrite() {
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Shared ITSS",
                Arrays.asList(replace("DEFAULT", "Default SS"), replace("Bug", "32"), add("Vacation", "HR SS"))));
        assertEquals(preview.getMessage(), Status.NO_CHANGE, preview.getStatus());
        assertNull(preview.getPlanId());
        assertTrue("no plan stored", savedPlans.isEmpty());
        for (RowPlan row : preview.getRows()) assertEquals(Change.NO_CHANGE, row.getChange());
        assertTrue(world.writes.isEmpty());
        assertEquals(0, preview.getProjects().getChanging());
    }

    @Test
    public void itssIsFoundByIdOrExactNameIgnoringCaseAndTheDefaultSchemeIsFlagged() {
        for (String reference : Arrays.asList("20", "shared itss", "SHARED ITSS")) {
            SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request(reference, Arrays.asList(replace("Bug", "Alt SS"))));
            assertEquals(reference + preview.getMessage(), Long.valueOf(20), preview.getIssueTypeScreenScheme().getId());
        }
        SetIssueTypeScreenSchemeMappingsResponse jira = service.edit(request("Default Issue Type Screen Scheme",
                Arrays.asList(replace("DEFAULT", "Alt SS"))));
        assertEquals(Status.WOULD_CHANGE, jira.getStatus());
        assertTrue(jira.getIssueTypeScreenScheme().isJiraDefault());
        assertEquals(1, jira.getProjects().getLinked());
    }

    // ---- projects ----------------------------------------------------------------------------------------------

    @Test
    public void projectCountsSeparateLinkedFromChangingAndListEveryLinkedProjectIncludingArchived() {
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Shared ITSS",
                Arrays.asList(replace("Vacation", "Bug SS"))));
        SetIssueTypeScreenSchemeMappingsResponse.ProjectImpact projects = preview.getProjects();
        assertEquals(3, projects.getLinked()); assertEquals("only ALPHA has Vacation", 1, projects.getChanging());
        assertFalse(projects.isTruncated());
        assertEquals(Arrays.asList("ALPHA", "BETA", "GAMMA"), keys(projects.getListed()));
        LinkedProject alpha = projects.getListed().get(0), beta = projects.getListed().get(1), gamma = projects.getListed().get(2);
        assertEquals(1, alpha.getChangedIssueTypes()); assertTrue(alpha.getRowTypesMissing().isEmpty());
        assertEquals(0, beta.getChangedIssueTypes());
        assertEquals("Vacation", beta.getRowTypesMissing().get(0).getName());
        assertEquals(0, gamma.getChangedIssueTypes()); assertTrue(gamma.isArchived()); assertFalse(alpha.isArchived());
        assertEquals("ALPHA name", alpha.getName());
        assertEquals("Общую настройку используют 3 проекта. Фактический выбор экранов изменится в 1 проекте. "
                + "Ниже показаны все 3 связанных проекта.", preview.getScopeText());
        assertFalse(preview.getScopeText().contains("во всех"));
    }

    @Test
    public void replacingTheDefaultRowCountsEffectiveChangesThroughDefaultOnly() {
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Shared ITSS",
                Arrays.asList(replace("DEFAULT", "Alt SS"))));
        assertEquals(3, preview.getProjects().getLinked());
        assertEquals(3, preview.getProjects().getChanging());
        assertEquals("ALPHA: Task only (Bug and Vacation have own rows)", 1, preview.getProjects().getListed().get(0).getChangedIssueTypes());
        assertEquals("BETA: all three types follow DEFAULT", 3, preview.getProjects().getListed().get(1).getChangedIssueTypes());
        assertEquals("GAMMA: Other only", 1, preview.getProjects().getListed().get(2).getChangedIssueTypes());
        assertTrue("DEFAULT is not an issue type missing from a project", preview.getProjects().getListed().get(0).getRowTypesMissing().isEmpty());
    }

    @Test
    public void moreThanTenLinkedProjectsAreCountedExactlyAndOnlyTheListIsCut() {
        for (int i = 0; i < 12; i++) {
            world.project(300 + i, String.format("Z%02d", 11 - i), i == 3, world.shared, world.task);
        }
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Shared ITSS", Arrays.asList(replace("DEFAULT", "Alt SS"))));
        SetIssueTypeScreenSchemeMappingsResponse.ProjectImpact projects = preview.getProjects();
        assertEquals(15, projects.getLinked()); assertEquals(15, projects.getChanging());
        assertTrue(projects.isTruncated()); assertEquals(10, projects.getListed().size());
        assertEquals(Arrays.asList("ALPHA", "BETA", "GAMMA", "Z00", "Z01", "Z02", "Z03", "Z04", "Z05", "Z06"), keys(projects.getListed()));
        assertEquals("Общую настройку используют 15 проектов. Фактический выбор экранов изменится в 15 проектах. "
                + "Ниже показаны первые 10 связанных проектов.", preview.getScopeText());
        assertFalse(preview.getScopeText().contains("в перечисленных проектах"));
    }

    @Test
    public void anItssWithoutProjectsIsAllowedWithAWarning() {
        IssueTypeScreenScheme lonely = world.top(40L, "Lonely ITSS", false, world.defaultField);
        world.commit(lonely);
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Lonely ITSS", Arrays.asList(replace("DEFAULT", "Alt SS"))));
        assertEquals(Status.WOULD_CHANGE, preview.getStatus());
        assertEquals(0, preview.getProjects().getLinked()); assertEquals(0, preview.getProjects().getChanging());
        assertTrue(preview.getProjects().getListed().isEmpty());
        assertEquals("NO_PROJECTS", preview.getWarnings().get(0).getCode());
    }

    @Test
    public void aLinkedProjectThatCannotBeReadIsNotSilentlyDroppedFromTheCounts() {
        world.projectCatalog.remove(102L);
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Shared ITSS", Arrays.asList(replace("DEFAULT", "Alt SS"))));
        assertEquals(Status.FAILED, preview.getStatus()); assertEquals(ErrorCode.JIRA_ERROR, preview.getErrorCode());
        assertNull(preview.getPlanId()); assertTrue(savedPlans.isEmpty());
    }

    // ---- validation ----------------------------------------------------------------------------------------

    @Test
    public void invalidRequestsAreStructuredErrorsWithTheRowNumberAndNoPlan() {
        world.typeCatalog.add(ItssMappingWorld.type("8", "Twin")); world.typeCatalog.add(ItssMappingWorld.type("9", "twin"));
        world.top(41L, "Dup ITSS", false, world.defaultField); world.top(42L, "dup itss", false, world.defaultField);
        world.scheme(34L, "Twin SS", 1, 2); world.scheme(35L, "TWIN ss", 1, 2);
        Object[][] cases = {
                {"nope", rows(replace("Bug", "Alt SS")), ErrorCode.ITSS_NOT_FOUND, null},
                {"9999", rows(replace("Bug", "Alt SS")), ErrorCode.ITSS_NOT_FOUND, null},
                {"Dup ITSS", rows(replace("Bug", "Alt SS")), ErrorCode.ITSS_AMBIGUOUS, null},
                {"Shared ITSS", rows(replace("Bug", "Alt SS"), replace("Vacation", "No such SS")), ErrorCode.SCREEN_SCHEME_NOT_FOUND, 1},
                {"Shared ITSS", rows(replace("Bug", "Twin SS")), ErrorCode.SCREEN_SCHEME_AMBIGUOUS, 0},
                {"Shared ITSS", rows(replace("Bug", "Alt SS"), add("Nothing", "Alt SS")), ErrorCode.ISSUE_TYPE_NOT_FOUND, 1},
                {"Shared ITSS", rows(add("Twin", "Alt SS")), ErrorCode.INVALID_ROW, 0},
                {"Shared ITSS", rows(remove("DEFAULT")), ErrorCode.DEFAULT_ROW_REQUIRED, 0},
                {"Shared ITSS", rows(replace("Task", "Alt SS")), ErrorCode.ROW_NOT_FOUND, 0},
                {"Shared ITSS", rows(replace("Bug", "Alt SS"), remove("Story")), ErrorCode.ROW_NOT_FOUND, 1},
                {"Shared ITSS", rows(replace("Bug", "Alt SS"), remove("2")), ErrorCode.DUPLICATE_ROW, 1},
                {"Shared ITSS", rows(replace("DEFAULT", "Alt SS"), add("DEFAULT", "HR SS")), ErrorCode.DUPLICATE_ROW, 1},
                {"Shared ITSS", rows(new Row("REMOVE", "Bug", "Alt SS")), ErrorCode.INVALID_ROW, 0},
                {"Shared ITSS", rows(new Row("ADD", "Story", null)), ErrorCode.INVALID_ROW, 0},
                {"Shared ITSS", rows(new Row("REPLACE", "Bug", "  ")), ErrorCode.INVALID_ROW, 0},
                {"Shared ITSS", rows(new Row("MOVE", "Bug", "Alt SS")), ErrorCode.INVALID_ROW, 0},
                {"Shared ITSS", rows(new Row(null, "Bug", "Alt SS")), ErrorCode.INVALID_ROW, 0},
                {"Shared ITSS", rows(new Row("REPLACE", " ", "Alt SS")), ErrorCode.INVALID_ROW, 0},
                {"Shared ITSS", rows((Row) null), ErrorCode.INVALID_ROW, 0},
                {" ", rows(replace("Bug", "Alt SS")), ErrorCode.VALIDATION_ERROR, null},
                {null, rows(replace("Bug", "Alt SS")), ErrorCode.VALIDATION_ERROR, null},
                {"Shared ITSS", new ArrayList<Row>(), ErrorCode.VALIDATION_ERROR, null},
                {"Shared ITSS", null, ErrorCode.VALIDATION_ERROR, null},
        };
        for (Object[] one : cases) {
            @SuppressWarnings("unchecked") List<Row> list = (List<Row>) one[1];
            SetIssueTypeScreenSchemeMappingsResponse response = service.edit(request((String) one[0], list));
            String label = one[0] + " " + (list == null ? "null" : list.size()) + " " + one[2] + ": " + response.getMessage();
            assertEquals(label, Status.FAILED, response.getStatus());
            assertEquals(label, one[2], response.getErrorCode());
            assertEquals(label, one[3], response.getErrorRowIndex());
            assertNull(label, response.getPlanId());
        }
        List<Row> tooMany = new ArrayList<Row>();
        for (int i = 0; i < 51; i++) tooMany.add(add("Bug", "Alt SS"));
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(request("Shared ITSS", tooMany)).getErrorCode());
        assertTrue("no plan was created", savedPlans.isEmpty());
        assertTrue("nothing was written", world.writes.isEmpty());
    }

    @Test
    public void previewAndApplyShapesAreTold() {
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(new SetIssueTypeScreenSchemeMappingsRequest(null, "Shared ITSS",
                rows(replace("Bug", "Alt SS")), null)).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(new SetIssueTypeScreenSchemeMappingsRequest(true, "Shared ITSS",
                rows(replace("Bug", "Alt SS")), "plan-1")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(new SetIssueTypeScreenSchemeMappingsRequest(false, "Shared ITSS",
                rows(replace("Bug", "Alt SS")), "plan-1")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(new SetIssueTypeScreenSchemeMappingsRequest(false, null, null, " ")).getErrorCode());
        assertTrue(world.writes.isEmpty());
    }

    // ---- apply ----------------------------------------------------------------------------------------------

    @Test
    public void confirmedMixedPlanEditsTheSameItssInPlaceSkipsNoChangeRowsAndReadsBack() {
        int tops = world.topCatalog.size(), fields = world.fieldCatalog.size();
        IssueTypeScreenScheme before = world.topCatalog.get(20L);
        Map<Long, Set<Long>> usersBefore = copyUsers();
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Shared ITSS", Arrays.asList(
                remove("Vacation"), replace("Bug", "32"), add("Story", "HR SS"), replace("DEFAULT", "Alt SS"))));
        assertEquals(Status.WOULD_CHANGE, preview.getStatus());
        SetIssueTypeScreenSchemeMappingsResponse done = apply(preview);
        assertEquals(done.getMessage(), Status.CHANGED, done.getStatus());
        assertNull(done.getErrorCode()); assertEquals(Boolean.TRUE, done.getReadBack());
        assertSame("the same ITSS object", before, world.topCatalog.get(20L));
        Map<String, Long> expected = new TreeMap<String, Long>();
        expected.put("DEFAULT", 33L); expected.put("2", 32L); expected.put("3", 31L);
        assertEquals(expected, world.table(world.shared));
        assertEquals("DEFAULT first, then the request order; the NO_CHANGE row is not written",
                Arrays.asList("update 20 DEFAULT", "remove 20 6", "create 20 3"), world.writes);
        assertEquals(Arrays.asList(RowResult.APPLIED, RowResult.NO_CHANGE, RowResult.APPLIED, RowResult.APPLIED), results(done));
        assertEquals(Long.valueOf(33), done.getRows().get(3).getObserved().getId());
        assertNull("the removed row reads back as absent", done.getRows().get(0).getObserved());
        assertEquals(tops, world.topCatalog.size()); assertEquals(fields, world.fieldCatalog.size());
        assertEquals("project links untouched", usersBefore, copyUsers());
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(lock).run(name.capture(), anyString(), any());
        assertEquals("the existing global screen-scheme lock", ScreenSchemeServiceImpl.LOCK_NAME, name.getValue());
        assertTrue("the plan is single-use", savedPlans.isEmpty());
        assertEquals(ErrorCode.PREVIEW_STALE, service.edit(applyRequest(preview.getPlanId())).getErrorCode());
        assertEquals("a replay writes nothing", 3, world.writes.size());
    }

    @Test
    public void sameRequestAppliedFromTheOtherRowOrderEndsInTheSameTable() {
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Shared ITSS", Arrays.asList(
                add("Story", "HR SS"), replace("DEFAULT", "Alt SS"), remove("Vacation"))));
        assertEquals(Status.CHANGED, apply(preview).getStatus());
        Map<String, Long> expected = new TreeMap<String, Long>();
        expected.put("DEFAULT", 33L); expected.put("2", 32L); expected.put("3", 31L);
        assertEquals(expected, world.table(world.shared));
        assertEquals("update 20 DEFAULT", world.writes.get(0));
    }

    @Test
    public void errorAfterAWriteThatLandedIsReportedAsDoneWithANote() {
        world.failAfterUpdateNumber = 1;
        SetIssueTypeScreenSchemeMappingsResponse done = apply(service.edit(request("Shared ITSS", Arrays.asList(replace("Bug", "Alt SS")))));
        assertEquals(done.getMessage(), Status.CHANGED, done.getStatus());
        assertEquals(RowResult.APPLIED, done.getRows().get(0).getResult());
        assertFalse(done.getNotes().isEmpty());
        assertEquals(Long.valueOf(33), world.table(world.shared).get("2"));
    }

    // ---- stale ---------------------------------------------------------------------------------------------

    @Test
    public void changeOfAnyFingerprintedSourceIsPreviewStaleWithoutAWrite() {
        int count = drifts().size();
        assertEquals("every drift of the AC-5 list is exercised", 16, count);
        for (int i = 0; i < count; i++) {
            IssueTypeScreenSchemeMappingEditorImplTest fresh = new IssueTypeScreenSchemeMappingEditorImplTest();
            fresh.setUp();
            SetIssueTypeScreenSchemeMappingsResponse preview = fresh.service.edit(fresh.request("Shared ITSS", Arrays.asList(
                    fresh.replace("Bug", "Alt SS"), fresh.add("Story", "HR SS"), fresh.remove("Vacation"))));
            assertEquals("drift " + i + ": " + preview.getMessage(), Status.WOULD_CHANGE, preview.getStatus());
            fresh.drifts().get(i).run();
            SetIssueTypeScreenSchemeMappingsResponse result = fresh.service.edit(fresh.applyRequest(preview.getPlanId()));
            assertEquals("drift " + i, ErrorCode.PREVIEW_STALE, result.getErrorCode());
            assertEquals("drift " + i, Status.FAILED, result.getStatus());
            assertTrue("drift " + i + " must not write", fresh.world.writes.isEmpty());
            assertTrue("the plan is consumed", fresh.savedPlans.isEmpty());
        }
    }

    private List<Runnable> drifts() {
        List<Runnable> drifts = new ArrayList<Runnable>();
        drifts.add(() -> world.row(world.shared, "3", world.hrField));
        drifts.add(() -> world.shared.getEntities().stream().filter(e -> "2".equals(e.getIssueTypeId())).findFirst().get()
                .setFieldScreenScheme(world.altField));
        drifts.add(() -> world.shared.setName("Renamed ITSS"));
        drifts.add(() -> world.shared.setDescription("Another description"));
        drifts.add(() -> world.users(20).add(104L));
        drifts.add(() -> world.users(20).remove(103L));
        drifts.add(() -> world.typesByProject.get(102L).add(world.bug));
        drifts.add(() -> world.typesByProject.get(101L).remove(world.task));
        drifts.add(() -> world.project(150, "NEWP", false, world.shared, world.task));
        drifts.add(() -> itemOf(world.fieldCatalog.get(32L), 0).setFieldScreen(world.screens.get(9L)));
        drifts.add(() -> world.fieldCatalog.get(33L).getFieldScreenSchemeItems().remove(itemOf(world.fieldCatalog.get(33L), 1)));
        drifts.add(() -> world.fieldCatalog.get(30L).setName("Default SS renamed"));
        drifts.add(() -> world.fieldCatalog.remove(31L));
        drifts.add(() -> world.fieldCatalog.remove(33L));
        drifts.add(() -> world.typeCatalog.remove(world.story));
        drifts.add(() -> world.typeCatalog.remove(world.vacation));
        return drifts;
    }

    @Test
    public void aPlanOfAnotherActorOrAnUnknownPlanIsStale() {
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Shared ITSS", Arrays.asList(replace("Bug", "Alt SS"))));
        planActors.put(preview.getPlanId(), "someone-else");
        assertEquals(ErrorCode.PREVIEW_STALE, service.edit(applyRequest(preview.getPlanId())).getErrorCode());
        assertEquals(ErrorCode.PREVIEW_STALE, service.edit(applyRequest("unknown")).getErrorCode());
        assertTrue(world.writes.isEmpty());
    }

    // ---- failures and conflicts ---------------------------------------------------------------------------

    @Test
    public void failureOfARowStopsTheRestKeepsEarlierRowsAndReportsPartial() {
        world.failRemoveNumber = 1;
        SetIssueTypeScreenSchemeMappingsResponse preview = service.edit(request("Shared ITSS", Arrays.asList(
                remove("Vacation"), add("Story", "HR SS"), replace("DEFAULT", "Alt SS"))));
        SetIssueTypeScreenSchemeMappingsResponse done = apply(preview);
        assertEquals(done.getMessage(), Status.PARTIALLY_CHANGED, done.getStatus());
        assertEquals(ErrorCode.JIRA_ERROR, done.getErrorCode());
        assertEquals("DEFAULT first (applied), then the failing REMOVE, then the story ADD is never attempted; results in request order",
                Arrays.asList(RowResult.NOT_APPLIED, RowResult.NOT_ATTEMPTED, RowResult.APPLIED), results(done));
        assertEquals(Arrays.asList("update 20 DEFAULT"), world.writes);
        assertEquals("not rolled back", Long.valueOf(33), world.table(world.shared).get("DEFAULT"));
        assertEquals(Long.valueOf(31), world.table(world.shared).get("6"));
        assertFalse(world.table(world.shared).containsKey("3"));
        assertEquals(Boolean.TRUE, done.getReadBack());
        assertEquals("the plan is used up", ErrorCode.PREVIEW_STALE, service.edit(applyRequest(preview.getPlanId())).getErrorCode());
    }

    @Test
    public void failureOfTheFirstWriteChangesNothingAndTheCachedRowIsNotTakenForTheNewValue() {
        world.failUpdateNumber = 1;
        SetIssueTypeScreenSchemeMappingsResponse done = apply(service.edit(request("Shared ITSS", Arrays.asList(
                replace("Bug", "Alt SS"), add("Story", "HR SS")))));
        assertEquals(done.getMessage(), Status.FAILED, done.getStatus());
        assertEquals(Arrays.asList(RowResult.NOT_APPLIED, RowResult.NOT_ATTEMPTED), results(done));
        assertTrue(world.writes.isEmpty());
        assertEquals(Long.valueOf(32), world.table(world.shared).get("2"));
        assertEquals("the stale cached entity was refreshed", Long.valueOf(32), done.getRows().get(0).getObserved().getId());
    }

    @Test
    public void mixedNoChangeAndFailingRowIsFailedByActualChangesNotPartial() {
        world.failUpdateNumber = 1;
        SetIssueTypeScreenSchemeMappingsResponse done = apply(service.edit(request("Shared ITSS", Arrays.asList(
                replace("DEFAULT", "Default SS"), replace("Bug", "Alt SS")))));
        assertEquals(done.getMessage(), Status.FAILED, done.getStatus());
        assertEquals(Arrays.asList(RowResult.NO_CHANGE, RowResult.NOT_APPLIED), results(done));
    }

    @Test
    public void unreadableTableAfterAWriteIsUnknownNotSuccess() {
        world.failReadBack = true;
        SetIssueTypeScreenSchemeMappingsResponse done = apply(service.edit(request("Shared ITSS", Arrays.asList(
                replace("Bug", "Alt SS"), replace("Vacation", "Bug SS")))));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
        assertEquals(Boolean.FALSE, done.getReadBack());
        assertEquals(Arrays.asList(RowResult.UNKNOWN, RowResult.UNKNOWN), results(done));
        assertEquals("both writes happened", 2, world.writes.size());
    }

    @Test
    public void writeThatReturnsButIsMissingFromTheReadBackTableIsNotSuccess() {
        world.swallowUpdateNumber = 2;
        SetIssueTypeScreenSchemeMappingsResponse done = apply(service.edit(request("Shared ITSS", Arrays.asList(
                replace("Bug", "Alt SS"), replace("Vacation", "Bug SS")))));
        assertEquals(done.getMessage(), Status.PARTIALLY_CHANGED, done.getStatus());
        assertEquals(Arrays.asList(RowResult.APPLIED, RowResult.UNKNOWN), results(done));
        assertEquals(Long.valueOf(31), done.getRows().get(1).getObserved().getId());
        assertTrue(done.getStatus() != Status.CHANGED);
    }

    @Test
    public void successfulWritesAreReadBackFromJiraAfterTheCacheIsDropped() {
        // The model keeps returning the pre-write table until refresh(): a read-back without it would show REPLACE/ADD/REMOVE as not applied.
        SetIssueTypeScreenSchemeMappingsResponse done = apply(service.edit(request("Shared ITSS", Arrays.asList(
                replace("Bug", "Alt SS"), add("Story", "HR SS"), remove("Vacation")))));
        assertEquals(done.getMessage(), Status.CHANGED, done.getStatus());
        assertEquals(Boolean.TRUE, done.getReadBack());
        assertEquals(Arrays.asList(RowResult.APPLIED, RowResult.APPLIED, RowResult.APPLIED), results(done));
        assertEquals(Long.valueOf(33), done.getRows().get(0).getObserved().getId());
        assertEquals(Long.valueOf(31), done.getRows().get(1).getObserved().getId());
        assertNull("the removed row is absent from the table read back", done.getRows().get(2).getObserved());
        assertEquals(1, world.refreshCalls());
    }

    @Test
    public void cacheThatCannotBeDroppedLeavesTheTableUntrustedAndTheResultUnknown() {
        doAnswer(call -> { throw new IllegalStateException("refresh failed"); }).when(world.tops).refresh();
        SetIssueTypeScreenSchemeMappingsResponse done = apply(service.edit(request("Shared ITSS", Arrays.asList(
                replace("Bug", "Alt SS"), replace("Vacation", "Bug SS")))));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
        assertEquals(Boolean.FALSE, done.getReadBack());
        assertEquals(Arrays.asList(RowResult.UNKNOWN, RowResult.UNKNOWN), results(done));
    }

    @Test
    public void nothingVisibleAfterWritesThatReportedSuccessIsUnknownNotFailed() {
        world.swallowUpdateNumber = 1;
        SetIssueTypeScreenSchemeMappingsResponse done = apply(service.edit(request("Shared ITSS", Arrays.asList(replace("Bug", "Alt SS")))));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
        assertEquals(Arrays.asList(RowResult.UNKNOWN), results(done));
    }

    @Test
    public void aConcurrentEditToAThirdValueIsReportedUnknownWithTheObservedValue() {
        IssueTypeScreenSchemeEntity bugRow = world.shared.getEntity("2");
        int[] writes = {0};
        world.afterWrite = () -> {
            if (++writes[0] == 2) { bugRow.setFieldScreenScheme(world.hrField); world.commit(world.shared); }
        };
        SetIssueTypeScreenSchemeMappingsResponse done = apply(service.edit(request("Shared ITSS", Arrays.asList(
                replace("Bug", "Alt SS"), replace("DEFAULT", "Alt SS")))));
        assertEquals(done.getMessage(), Status.PARTIALLY_CHANGED, done.getStatus());
        assertEquals(Arrays.asList(RowResult.UNKNOWN, RowResult.APPLIED), results(done));
        assertEquals(Long.valueOf(31), done.getRows().get(0).getObserved().getId());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private SetIssueTypeScreenSchemeMappingsResponse apply(SetIssueTypeScreenSchemeMappingsResponse preview) {
        assertEquals(preview.getMessage(), Status.WOULD_CHANGE, preview.getStatus());
        return service.edit(applyRequest(preview.getPlanId()));
    }

    private static SetIssueTypeScreenSchemeMappingsRequest applyRequest(String planId) {
        return new SetIssueTypeScreenSchemeMappingsRequest(false, null, null, planId);
    }

    private SetIssueTypeScreenSchemeMappingsRequest request(String itss, List<Row> rows) {
        return new SetIssueTypeScreenSchemeMappingsRequest(true, itss, rows, null);
    }

    private Map<Long, Set<Long>> copyUsers() {
        Map<Long, Set<Long>> copy = new TreeMap<Long, Set<Long>>();
        for (Map.Entry<Long, Set<Long>> entry : world.usersByTop.entrySet()) copy.put(entry.getKey(), new TreeSet<Long>(entry.getValue()));
        return copy;
    }

    private static List<Row> rows(Row... rows) { return new ArrayList<Row>(Arrays.asList(rows)); }
    private Row add(String type, String scheme) { return new Row("ADD", type, scheme); }
    private Row replace(String type, String scheme) { return new Row("REPLACE", type, scheme); }
    private Row remove(String type) { return new Row("REMOVE", type, null); }

    private static List<Integer> rowIndexes(SetIssueTypeScreenSchemeMappingsResponse response) {
        List<Integer> result = new ArrayList<Integer>();
        for (RowPlan row : response.getRows()) result.add(row.getRowIndex());
        return result;
    }

    private static List<RowResult> results(SetIssueTypeScreenSchemeMappingsResponse response) {
        List<RowResult> result = new ArrayList<RowResult>();
        for (RowPlan row : response.getRows()) result.add(row.getResult());
        return result;
    }

    private static List<String> keys(List<LinkedProject> projects) {
        List<String> keys = new ArrayList<String>();
        for (LinkedProject project : projects) keys.add(project.getKey());
        return keys;
    }

    private static com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem itemOf(
            com.atlassian.jira.issue.fields.screen.FieldScreenScheme scheme, int index) {
        return new ArrayList<com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem>(scheme.getFieldScreenSchemeItems()).get(index);
    }
}
