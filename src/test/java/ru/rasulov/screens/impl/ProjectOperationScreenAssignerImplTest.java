package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensRequest;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensRequest.Mapping;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.AssignmentState;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.ErrorCode;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.MatrixSource;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.Status;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.MappingAction;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.SchemeMode;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.TypeAssignment;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real planner/writer orchestration over the in-memory scheme model of {@link OperationScreenWorld}. */
public class ProjectOperationScreenAssignerImplTest {
    private final OperationScreenWorld world = new OperationScreenWorld();
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final ActionPlanStore plans = mock(ActionPlanStore.class);
    private final ApplicationUser admin = mock(ApplicationUser.class);
    private final Map<String, ActionPlanStore.Plan> savedPlans = new HashMap<String, ActionPlanStore.Plan>();
    private final Map<String, String> planActors = new HashMap<String, String>();
    private ProjectOperationScreenAssignerImpl service;

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
        service = new ProjectOperationScreenAssignerImpl(guard, lock,
                new OperationScreenPlanner(world.projects, world.types, world.screenManager, world.fields, world.tops, world.projectManager, world.codec),
                new OperationScreenWriter(world.factory, world.fields, world.tops),
                new ru.rasulov.common.impl.PreviewPlanExecutor(plans, world.codec), world.codec);
    }

    @Test
    public void fiveTypesWithFiveDifferentScreensAreOnePlanOneNewTopSchemeAndOneSwitch() {
        int tops = world.topCount(), fields = world.fieldCount();
        SetProjectOperationScreensResponse preview = service.assign(request(
                row(types("Task"), screens("3", "4", "6")), row(types("Bug"), screens("4", "5", "7")),
                row(types("Story"), screens("6", "7", "8")), row(types("Epic"), screens("7", "8", "9")),
                row(types("Other"), screens("8", "9", "3"))));
        assertEquals(Status.WOULD_ASSIGN, preview.getStatus());
        assertEquals(MatrixSource.PLANNED, preview.getMatrixSource());
        assertEquals("a preview writes nothing", Collections.<String>emptyList(), world.writes);
        assertEquals("four copies of the shared default scheme with different results and one copy of Other's scheme",
                5, preview.getScreenSchemes().size());
        assertEquals(5, preview.getIssueTypes().size());
        assertEquals(Long.valueOf(8), planned(preview, "Other", "CREATE").getAfter().getId());

        SetProjectOperationScreensResponse done = apply(preview);
        assertEquals(done.getMessage(), Status.ASSIGNED, done.getStatus());
        assertEquals(MatrixSource.READ_BACK, done.getMatrixSource());
        assertEquals("exactly one new top scheme", tops + 1, world.topCount());
        assertEquals("five child copies, no per-row copies", fields + 5, world.fieldCount());
        assertEquals("one switch of the project", 1, count("associate"));
        assertEquals(Long.valueOf(world.associated.get().getId()), done.getAssignedSchemeId());
        assertNotSame(world.sourceTop, world.associated.get());
        long[][] expected = {{3, 4, 6}, {4, 5, 7}, {6, 7, 8}, {7, 8, 9}, {8, 9, 3}};
        IssueType[] order = {world.task, world.bug, world.story, world.epic, world.other};
        for (int i = 0; i < order.length; i++) {
            assertEquals(expected[i][0], world.effective(order[i], "CREATE"));
            assertEquals(expected[i][1], world.effective(order[i], "EDIT"));
            assertEquals(expected[i][2], world.effective(order[i], "VIEW"));
            TypeAssignment reported = byName(done, order[i].getName());
            assertTrue(reported.isChanged() && reported.isSelected() && reported.isExplicitMapping());
            assertEquals(Long.valueOf(expected[i][0]), reported.getOperations().get(0).getAfter().getId());
        }
        assertEquals(5, done.getCreated().getScreenSchemes().size());
        assertEquals(done.getAssignedSchemeId(), done.getCreated().getIssueTypeScreenScheme().getId());
        assertScreen(world.sourceField, "CREATE", 2); assertScreen(world.sourceField, "EDIT", 1);
        assertScreen(world.otherField, "CREATE", 5);
        assertSame("the old top scheme is kept", world.sourceTop, world.topCatalog.get(20L));
        assertEquals("the old rows are untouched", 2, world.sourceTop.getEntities().size());
    }

    @Test
    public void equalResultsOfSeveralRowsShareOneChildCopyAndUncoveredTypesKeepTheirScheme() {
        int fields = world.fieldCount();
        SetProjectOperationScreensResponse preview = service.assign(request(
                row(types("Task"), screens("3", "4", null)), row(types("Bug", "Story"), screens("3", "4", null))));
        assertEquals(Status.WOULD_ASSIGN, preview.getStatus());
        assertEquals(1, preview.getScreenSchemes().size());
        assertEquals(Arrays.asList("Task", "Bug", "Story").size(), preview.getScreenSchemes().get(0).getAppliesTo().size());
        assertEquals(Status.ASSIGNED, apply(preview).getStatus());
        assertEquals("one copy for three types", fields + 1, world.fieldCount());
        FieldScreenScheme shared = world.associated.get().getEffectiveFieldScreenScheme(world.task);
        assertSame(shared, world.associated.get().getEffectiveFieldScreenScheme(world.bug));
        assertSame(shared, world.associated.get().getEffectiveFieldScreenScheme(world.story));
        assertSame("Epic still follows the default row", world.sourceField, world.associated.get().getEffectiveFieldScreenScheme(world.epic));
        assertNull(world.associated.get().getEntity("epic"));
        assertSame(world.otherField, world.associated.get().getEffectiveFieldScreenScheme(world.other));
        assertSame(world.sourceField, world.associated.get().getEntity(null).getFieldScreenScheme());
    }

    @Test
    public void requestedNamesAreUsedVerbatimAndOmittedNamesStayGenerated() {
        SetProjectOperationScreensResponse preview = service.assign(new SetProjectOperationScreensRequest(true, "ABC",
                Arrays.asList(new Mapping(types("Task"), null, screens("3", null, null), "HR Vacation Screen Scheme"),
                        new Mapping(types("Other"), null, screens("3", null, null), null)),
                "HR Vacation Screens", null));
        assertEquals(preview.getMessage(), Status.WOULD_ASSIGN, preview.getStatus());
        assertEquals("HR Vacation Screens", preview.getNewSchemeName());
        List<String> names = new ArrayList<String>();
        for (SetProjectOperationScreensResponse.SchemeChange change : preview.getScreenSchemes()) names.add(change.getNewName());
        assertTrue(names.toString(), names.contains("HR Vacation Screen Scheme"));
        assertTrue(names.toString(), names.contains("ABC: Operation Screen Scheme " + world.otherField.getId()));
        assertEquals(Status.ASSIGNED, apply(preview).getStatus());
        assertEquals("HR Vacation Screens", world.associated.get().getName());
        assertEquals("HR Vacation Screen Scheme", world.associated.get().getEffectiveFieldScreenScheme(world.task).getName());
    }

    @Test
    public void aTakenOrConflictingRequestedNameIsRejectedNotSilentlyRenamed() {
        String taken = world.sourceField.getName();
        assertError(new SetProjectOperationScreensRequest(true, "ABC",
                Collections.singletonList(new Mapping(types("Task"), null, screens("3", null, null), taken)), null, null),
                ErrorCode.VALIDATION_ERROR);
        assertError(new SetProjectOperationScreensRequest(true, "ABC",
                Arrays.asList(new Mapping(types("Task"), null, screens("3", null, null), "Same"),
                        new Mapping(types("Bug"), null, screens("4", null, null), "Same")), null, null),
                ErrorCode.VALIDATION_ERROR);
        assertError(new SetProjectOperationScreensRequest(true, "ABC",
                Collections.singletonList(new Mapping(types("Task"), null, screens("3", null, null), "   ")), null, null),
                ErrorCode.VALIDATION_ERROR);
    }

    private SetProjectOperationScreensRequest named(String top, Mapping... rows) {
        return new SetProjectOperationScreensRequest(true, "ABC", Arrays.asList(rows), top, null);
    }

    @Test
    public void anAllIssueTypesNameReachesOnlyScopesNoTypedRowPatchesFurther() {
        SetProjectOperationScreensResponse preview = service.assign(named(null,
                new Mapping(null, true, screens("5", null, null), "HR"),
                new Mapping(types("Bug"), null, screens(null, null, "7"), null)));
        assertEquals(preview.getMessage(), Status.WOULD_ASSIGN, preview.getStatus());
        List<String> names = new ArrayList<String>();
        for (SetProjectOperationScreensResponse.SchemeChange change : preview.getScreenSchemes()) names.add(change.getNewName());
        assertEquals(names.toString(), 2, names.size());
        assertTrue(names.toString(), names.contains("HR"));

        assertError(named(null, new Mapping(null, true, screens("3", null, null), "HR")), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    public void aNameOnAnyRowOfASharedCopyWinsWhateverTheRowOrder() {
        for (boolean namedFirst : new boolean[]{false, true}) {
            Mapping plain = new Mapping(types("Task"), null, screens("3", null, null), null);
            Mapping named = new Mapping(types("Bug"), null, screens("3", null, null), "Shared Name");
            SetProjectOperationScreensResponse preview = service.assign(
                    named(null, namedFirst ? new Mapping[]{named, plain} : new Mapping[]{plain, named}));
            assertEquals(preview.getMessage(), Status.WOULD_ASSIGN, preview.getStatus());
            assertEquals(1, preview.getScreenSchemes().size());
            assertEquals("Shared Name", preview.getScreenSchemes().get(0).getNewName());
        }
    }

    @Test
    public void aDictatedNameThatCannotBeAppliedIsRefusedNotDropped() {
        world.usePrivateTop(30L, "ABC private top");
        assertError(named("HR Vacation Screens", new Mapping(types("Task"), null, screens("3", "4", "6"), null)), ErrorCode.VALIDATION_ERROR);
        assertError(named(null, new Mapping(types("Task"), null, screens("2", null, null), "Nothing New")), ErrorCode.VALIDATION_ERROR);
        assertError(named("  ", new Mapping(types("Task"), null, screens("3", null, null), null)), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    public void differentSourceSchemesWithTheSamePatchAreNotMerged() {
        SetProjectOperationScreensResponse preview = service.assign(request(row(types("Task", "Other"), screens("3", null, null))));
        assertEquals(2, preview.getScreenSchemes().size());
        assertEquals(Status.ASSIGNED, apply(preview).getStatus());
        assertScreen(world.associated.get().getEffectiveFieldScreenScheme(world.task), "EDIT", 1);
        assertScreen(world.associated.get().getEffectiveFieldScreenScheme(world.other), "EDIT", 5);
    }

    @Test
    public void singleRowPartialPatchKeepsOtherOperationsDefaultAndOtherTypes() {
        SetProjectOperationScreensResponse preview = service.assign(request(row(types("Task"), screens("3", null, null))));
        assertEquals(Status.WOULD_ASSIGN, preview.getStatus());
        assertEquals(Long.valueOf(2), planned(preview, "Task", "CREATE").getBefore().getId());
        assertEquals(1, preview.getScreenSchemes().size());
        assertEquals(Status.ASSIGNED, apply(preview).getStatus());
        IssueTypeScreenScheme now = world.associated.get();
        FieldScreenScheme selected = now.getEffectiveFieldScreenScheme(world.task);
        assertScreen(selected, "CREATE", 3); assertScreen(selected, "EDIT", 1); assertScreen(selected, "VIEW", 1);
        assertSame(world.sourceField, now.getEntity(null).getFieldScreenScheme());
        assertSame(world.otherField, now.getEffectiveFieldScreenScheme(world.other));
        assertSame(world.sourceField, now.getEffectiveFieldScreenScheme(world.bug));
        assertNull(world.sourceTop.getEntity("task"));
        assertEquals("Shared top description", now.getDescription());
        assertEquals("Shared field description", selected.getDescription());
    }

    @Test
    public void allTypesRowPatchesDefaultAndEveryExplicitRowWithoutNewExplicitRows() {
        SetProjectOperationScreensResponse preview = service.assign(request(row(null, screens("3", "4", "2"))));
        assertEquals(Status.WOULD_ASSIGN, preview.getStatus());
        assertTrue(preview.getWarnings().get(0), preview.getWarnings().get(0).contains("DEFAULT"));
        assertEquals(Status.ASSIGNED, apply(preview).getStatus());
        for (IssueType type : Arrays.asList(world.task, world.bug, world.story, world.epic, world.other)) {
            assertEquals(3, world.effective(type, "CREATE")); assertEquals(4, world.effective(type, "EDIT")); assertEquals(2, world.effective(type, "VIEW"));
        }
        assertNull("no new explicit rows", world.associated.get().getEntity("task"));
        assertEquals(2, world.associated.get().getEntities().size());
        assertNotSame(world.sourceField, world.associated.get().getEntity(null).getFieldScreenScheme());
        assertScreen(world.sourceField, "CREATE", 2); assertScreen(world.otherField, "VIEW", 5);
    }

    @Test
    public void namedRowAndAllTypesRowOnDifferentOperationsOrTheSameScreenCombine() {
        SetProjectOperationScreensResponse preview = service.assign(request(
                row(null, screens("3", null, null)), row(types("Task"), screens("3", "6", null))));
        assertEquals(Status.ASSIGNED, apply(preview).getStatus());
        assertEquals(3, world.effective(world.bug, "CREATE")); assertEquals(1, world.effective(world.bug, "EDIT"));
        assertEquals(3, world.effective(world.task, "CREATE")); assertEquals(6, world.effective(world.task, "EDIT"));
    }

    @Test
    public void contradictingRowsAreRejectedBeforeAPlanOrAnyWrite() {
        assertError(request(row(types("Task"), screens("3", null, null)), row(types("Task", "Bug"), screens("4", null, null))), ErrorCode.VALIDATION_ERROR);
        assertError(request(row(null, screens("3", null, null)), row(types("Task"), screens("4", null, null))), ErrorCode.VALIDATION_ERROR);
        assertError(request(row(null, screens("3", null, null)), row(null, screens("4", null, null))), ErrorCode.VALIDATION_ERROR);
        assertNoWrites();
        SetProjectOperationScreensResponse same = service.assign(request(
                row(types("Task"), screens("3", null, null)), row(types("Task"), screens("Screen 3", null, null))));
        assertEquals("the same Screen by id and by name is one assignment", Status.WOULD_ASSIGN, same.getStatus());
        assertEquals(1, same.getScreenSchemes().size());
    }

    @Test
    public void malformedRowsAreRejected() {
        assertError(request(), ErrorCode.VALIDATION_ERROR);
        assertError(request(row(types("Task"), screens(null, null, null))), ErrorCode.VALIDATION_ERROR);
        assertError(request(row(Collections.<String>emptyList(), screens("3", null, null))), ErrorCode.VALIDATION_ERROR);
        assertError(request(new Mapping(null, null, screens("3", null, null), null)), ErrorCode.VALIDATION_ERROR);
        assertError(request(new Mapping(types("Task"), true, screens("3", null, null), null)), ErrorCode.VALIDATION_ERROR);
        assertError(request(new Mapping(null, false, screens("3", null, null), null)), ErrorCode.VALIDATION_ERROR);
        assertError(request(row(types("Task", "task"), screens("3", null, null))), ErrorCode.VALIDATION_ERROR);
        Map<String, String> bad = new LinkedHashMap<String, String>(); bad.put("DELETE", "3");
        assertError(request(row(types("Task"), bad)), ErrorCode.VALIDATION_ERROR);
        assertError(request(row(types("Task"), screens(" ", null, null))), ErrorCode.VALIDATION_ERROR);
        assertNoWrites();
    }

    @Test
    public void unknownTypeOrScreenInAnyRowRejectsTheWholeBatchBeforeAnyWrite() {
        IssueType outsider = OperationScreenWorld.type("outsider", "Outside");
        when(world.types.resolve("Outside")).thenReturn(outsider);
        assertError(request(row(types("Task"), screens("3", null, null)), row(types("Outside"), screens("4", null, null))), ErrorCode.UNKNOWN_ISSUE_TYPE);
        assertError(request(row(types("Task"), screens("3", null, null)), row(types("Bug"), screens("Missing", null, null))), ErrorCode.UNKNOWN_SCREEN);
        world.screenCatalog.put(10L, OperationScreenWorld.screen(10L, "Screen 3"));
        assertError(request(row(types("Bug"), screens("Screen 3", null, null))), ErrorCode.UNKNOWN_SCREEN);
        assertNoWrites();
    }

    @Test
    public void fullyMatchingBatchIsNoOpWithoutPlanOrObjects() {
        int tops = world.topCount(), fields = world.fieldCount();
        SetProjectOperationScreensResponse response = service.assign(request(
                row(types("Task"), screens(null, null, "1")), row(types("Bug"), screens("2", "1", null)), row(types("Other"), screens(null, "5", null))));
        assertEquals(Status.ALREADY_ASSIGNED, response.getStatus());
        assertNull(response.getPlanId()); assertNull(response.getNewSchemeName());
        assertTrue(savedPlans.isEmpty()); assertNoWrites();
        assertEquals(tops, world.topCount()); assertEquals(fields, world.fieldCount());
        assertSame(world.sourceTop, world.associated.get());
        assertEquals("a fully matching batch still reports the effective matrix", 5, response.getIssueTypes().size());
    }

    @Test
    public void relevantStateChangeBetweenPreviewAndApplyMakesThePlanStaleBeforeAnyWrite() {
        SetProjectOperationScreensResponse preview = service.assign(request(row(types("Task"), screens("3", null, null)), row(types("Other"), screens(null, "4", null))));
        world.otherField.getFieldScreenSchemeItems().iterator().next().setFieldScreen(world.screenCatalog.get(6L));
        assertEquals(ErrorCode.PREVIEW_STALE, apply(preview).getErrorCode());
        assertFalse(savedPlans.containsKey(preview.getPlanId()));
        assertNoWrites(); assertSame(world.sourceTop, world.associated.get());
    }

    @Test
    public void generatedNameCollisionAndAnotherProjectSwitchMakeThePlanStale() {
        SetProjectOperationScreensResponse first = service.assign(request(row(types("Task"), screens("3", null, null))));
        world.topCatalog.put(99L, OperationScreenWorld.topScheme(99L, first.getNewSchemeName()));
        assertEquals(ErrorCode.PREVIEW_STALE, apply(first).getErrorCode());
        world.topCatalog.remove(99L);
        SetProjectOperationScreensResponse second = service.assign(request(row(types("Task"), screens("3", null, null))));
        world.associated.set(OperationScreenWorld.topScheme(98L, "Changed elsewhere"));
        assertEquals(ErrorCode.PREVIEW_STALE, apply(second).getErrorCode());
        assertNoWrites();
    }

    @Test
    public void replayingAFinishedPlanAndForeignActorsCreateNothing() {
        SetProjectOperationScreensResponse preview = service.assign(request(row(types("Task"), screens("3", null, null))));
        ApplicationUser foreign = mock(ApplicationUser.class); when(foreign.getKey()).thenReturn("foreign");
        when(guard.requireAdministrator()).thenReturn(foreign);
        assertEquals(ErrorCode.PREVIEW_STALE, apply(preview).getErrorCode()); assertTrue(savedPlans.containsKey(preview.getPlanId()));
        when(guard.requireAdministrator()).thenReturn(admin);
        assertEquals(Status.ASSIGNED, apply(preview).getStatus());
        int tops = world.topCount(), fields = world.fieldCount(), writes = world.writes.size();
        assertEquals(ErrorCode.PREVIEW_STALE, apply(preview).getErrorCode());
        assertEquals(tops, world.topCount()); assertEquals(fields, world.fieldCount()); assertEquals(writes, world.writes.size());
        SetProjectOperationScreensResponse again = service.assign(request(row(types("Task"), screens("3", null, null))));
        assertEquals("the same request after success is a no-op", Status.ALREADY_ASSIGNED, again.getStatus());
    }

    @Test
    public void applyAcceptsOnlyThePlanId() {
        SetProjectOperationScreensResponse preview = service.assign(request(row(types("Task"), screens("3", null, null))));
        SetProjectOperationScreensRequest mixed = new SetProjectOperationScreensRequest(false, "ABC", null, null, preview.getPlanId());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.assign(mixed).getErrorCode());
        SetProjectOperationScreensRequest withRows = new SetProjectOperationScreensRequest(false, null,
                Collections.singletonList(row(types("Task"), screens("3", null, null))), null, preview.getPlanId());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.assign(withRows).getErrorCode());
        SetProjectOperationScreensRequest previewWithPlan = new SetProjectOperationScreensRequest(true, "ABC",
                Collections.singletonList(row(types("Task"), screens("3", null, null))), null, preview.getPlanId());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.assign(previewWithPlan).getErrorCode());
        assertTrue(savedPlans.containsKey(preview.getPlanId())); assertSame(world.sourceTop, world.associated.get());
    }

    @Test
    public void failureCreatingAChildSchemeReportsTheEarlierOnesAndClaimsNoAssignment() {
        SetProjectOperationScreensResponse preview = fivePreview();
        world.failFieldSchemeNumber = 3;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(result.getMessage(), Status.PARTIALLY_ASSIGNED, result.getStatus());
        assertEquals(AssignmentState.NOT_ASSIGNED, result.getAssignment());
        assertEquals(ErrorCode.JIRA_ERROR, result.getErrorCode());
        assertNull(result.getAssignedSchemeId()); assertNull(result.getMatrixSource()); assertTrue(result.getIssueTypes().isEmpty());
        assertEquals(2, result.getCreated().getScreenSchemes().size());
        assertNull(result.getCreated().getIssueTypeScreenScheme());
        for (int i = 0; i < 2; i++) {
            assertTrue(result.getCreated().getScreenSchemes().get(i).isComplete());
            assertNotNull(world.fieldCatalog.get(result.getCreated().getScreenSchemes().get(i).getId()));
        }
        assertEquals("only the two created schemes exist", 4, world.fieldCount());
        assertSame(world.sourceTop, world.associated.get()); assertEquals(1, world.topCount());
        assertTrue(result.getMessage(), result.getMessage().contains("not removed") && result.getMessage().contains("Jira cannot store the scheme"));
        assertEquals("the failed plan is consumed", ErrorCode.PREVIEW_STALE, apply(preview).getErrorCode());
    }

    @Test
    public void failureOnTheFirstWriteIsFailedAndNothingExists() {
        SetProjectOperationScreensResponse preview = fivePreview();
        world.failFieldSchemeNumber = 1;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(Status.FAILED, result.getStatus()); assertNull(result.getCreated());
        assertEquals(AssignmentState.NOT_ASSIGNED, result.getAssignment());
        assertEquals(2, world.fieldCount()); assertEquals(1, world.topCount());
        assertTrue(result.getMessage(), result.getMessage().contains("Nothing was created"));
    }

    @Test
    public void failureWhileFillingASchemeMarksItIncomplete() {
        SetProjectOperationScreensResponse preview = fivePreview();
        world.failItemNumber = 2;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(Status.PARTIALLY_ASSIGNED, result.getStatus());
        assertEquals(1, result.getCreated().getScreenSchemes().size());
        assertFalse(result.getCreated().getScreenSchemes().get(0).isComplete());
    }

    @Test
    public void failureCreatingTopRowsLeavesTopAndCopiesReportedAndTheProjectOnTheSource() {
        SetProjectOperationScreensResponse preview = fivePreview();
        world.failEntityNumber = 3;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(Status.PARTIALLY_ASSIGNED, result.getStatus());
        assertEquals(5, result.getCreated().getScreenSchemes().size());
        assertFalse(result.getCreated().getIssueTypeScreenScheme().isComplete());
        assertNotNull(world.topCatalog.get(result.getCreated().getIssueTypeScreenScheme().getId()));
        assertSame(world.sourceTop, world.associated.get());
        assertEquals(AssignmentState.NOT_ASSIGNED, result.getAssignment());
        assertEquals(0, count("associate"));
    }

    @Test
    public void failureSwitchingTheProjectReportsEverythingCreatedAndNotAssigned() {
        SetProjectOperationScreensResponse preview = fivePreview();
        world.failAssociation = true;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(Status.PARTIALLY_ASSIGNED, result.getStatus());
        assertEquals(AssignmentState.NOT_ASSIGNED, result.getAssignment());
        assertTrue(result.getCreated().getIssueTypeScreenScheme().isComplete());
        assertEquals(5, result.getCreated().getScreenSchemes().size());
        assertNull(result.getAssignedSchemeId()); assertSame(world.sourceTop, world.associated.get());
        assertTrue(result.getMessage().contains("still uses Issue Type Screen Scheme 20"));
    }

    @Test
    public void errorAfterAnAppliedSwitchIsReadBackAsAssignedWithANote() {
        SetProjectOperationScreensResponse preview = fivePreview();
        world.associateThenFail = true;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(Status.ASSIGNED, result.getStatus()); assertEquals(MatrixSource.READ_BACK, result.getMatrixSource());
        assertTrue(result.getWarnings().toString(), result.getWarnings().toString().contains("already uses the new"));
        assertEquals(8, world.effective(world.other, "CREATE"));
    }

    @Test
    public void unreadableStateAfterAFailedSwitchIsUnknownNotAssigned() {
        SetProjectOperationScreensResponse preview = fivePreview();
        world.associateThenFail = true; world.failAssociationReadBack = true;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(Status.PARTIALLY_ASSIGNED, result.getStatus()); assertEquals(AssignmentState.UNKNOWN, result.getAssignment());
        assertNull(result.getAssignedSchemeId()); assertTrue(result.getIssueTypes().isEmpty());
    }

    @Test
    public void aCleanWriteWhoseReadBackFailsIsReportedFromThePlanAndSaysItWasNotRead() {
        SetProjectOperationScreensResponse preview = fivePreview();
        world.failAssociationReadBack = true;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(Status.ASSIGNED, result.getStatus());
        assertEquals("the matrix is the plan, never presented as a reading", MatrixSource.PLANNED, result.getMatrixSource());
        assertTrue(result.getWarnings().toString(), result.getWarnings().stream().anyMatch(w -> w.contains("could not be read back")));
    }

    // ---- F08: private top scheme is updated in place; shared/default stays copy-on-write ----

    @Test
    public void privateTopSchemeIsUpdatedInPlaceAndASecondBatchKeepsItsIdNameAndAssociation() {
        IssueTypeScreenScheme own = world.usePrivateTop(30L, "ABC private top");
        int tops = world.topCount(), fields = world.fieldCount();
        SetProjectOperationScreensResponse preview = service.assign(request(row(types("Task"), screens("3", "4", "6"))));
        assertEquals(preview.getMessage(), Status.WOULD_ASSIGN, preview.getStatus());
        assertEquals(SchemeMode.UPDATE_PRIVATE, preview.getTopScheme().getMode());
        assertEquals(Long.valueOf(30), preview.getTopScheme().getSourceId());
        assertEquals(0, preview.getTopScheme().getOtherProjects());
        assertNull("no new top scheme is planned", preview.getNewSchemeName());
        assertEquals(1, preview.getTopScheme().getMappings().size());
        assertEquals("Task inherited DEFAULT: an explicit mapping is materialized", MappingAction.ADD,
                preview.getTopScheme().getMappings().get(0).getAction());
        assertEquals("task", preview.getTopScheme().getMappings().get(0).getIssueType().getId());

        SetProjectOperationScreensResponse done = apply(preview);
        assertEquals(done.getMessage(), Status.ASSIGNED, done.getStatus());
        assertSame(own, world.associated.get());
        assertEquals(Long.valueOf(30), done.getAssignedSchemeId());
        assertEquals("ABC private top", own.getName()); assertEquals("ABC private top description", own.getDescription());
        assertEquals("no new top scheme", tops, world.topCount());
        assertEquals("one child copy", fields + 1, world.fieldCount());
        assertEquals(0, count("associate")); assertEquals(0, count("top"));
        assertNull(done.getCreated().getIssueTypeScreenScheme());
        assertEquals(1, done.getCreated().getScreenSchemes().size());
        assertSame("DEFAULT is kept", world.sourceField, own.getEntity(null).getFieldScreenScheme());
        assertNotSame(world.sourceField, own.getEntity("task").getFieldScreenScheme());
        assertEquals(3, world.effective(world.task, "CREATE")); assertEquals(6, world.effective(world.task, "VIEW"));
        assertEquals("Bug still inherits DEFAULT", 2, world.effective(world.bug, "CREATE"));
        assertScreen(world.sourceField, "CREATE", 2); assertScreen(world.otherField, "CREATE", 5);

        SetProjectOperationScreensResponse second = service.assign(request(row(types("Bug"), screens("7", "8", "9"))));
        assertEquals(SchemeMode.UPDATE_PRIVATE, second.getTopScheme().getMode());
        assertEquals(Status.ASSIGNED, apply(second).getStatus());
        assertSame("the same object, not a second generation", own, world.associated.get());
        assertEquals(Long.valueOf(30), own.getId()); assertEquals(tops, world.topCount());
        assertEquals(0, count("associate")); assertEquals(0, count("top"));
        assertEquals(7, world.effective(world.bug, "CREATE")); assertEquals(3, world.effective(world.task, "CREATE"));
    }

    @Test
    public void explicitRowsAndTheDefaultRowAreChangedInPlaceAndUncoveredRowsKept() {
        IssueTypeScreenScheme own = world.usePrivateTop(30L, "ABC private top");
        SetProjectOperationScreensResponse preview = service.assign(request(row(null, screens(null, null, "9"))));
        assertEquals(SchemeMode.UPDATE_PRIVATE, preview.getTopScheme().getMode());
        assertEquals(2, preview.getTopScheme().getMappings().size());
        assertNull("DEFAULT row", preview.getTopScheme().getMappings().get(0).getIssueType());
        for (SetProjectOperationScreensResponse.MappingChange change : preview.getTopScheme().getMappings()) {
            assertEquals(MappingAction.CHANGE, change.getAction());
        }
        assertEquals(Status.ASSIGNED, apply(preview).getStatus());
        assertEquals(2, own.getEntities().size());
        assertEquals(9, world.effective(world.epic, "VIEW")); assertEquals(9, world.effective(world.other, "VIEW"));
        assertEquals(2, world.effective(world.epic, "CREATE")); assertEquals(5, world.effective(world.other, "CREATE"));
        assertScreen(world.sourceField, "VIEW", 1); assertScreen(world.otherField, "VIEW", 5);
    }

    @Test
    public void sharedDefaultAndUnprovenTopSchemesAreStillCopiedNeverUpdatedInPlace() {
        SetProjectOperationScreensResponse shared = service.assign(request(row(types("Task"), screens("3", null, null))));
        assertEquals(SchemeMode.COPY_SHARED, shared.getTopScheme().getMode());
        assertEquals(1, shared.getTopScheme().getOtherProjects());
        assertEquals("P2", shared.getTopScheme().getOtherProjectNames().get(0).getKey());
        assertNotNull(shared.getNewSchemeName());
        assertEquals(Status.ASSIGNED, apply(shared).getStatus());
        assertEquals("one new top scheme, the source is unchanged", 2, world.topCount());
        assertSame(world.sourceTop, world.topCatalog.get(20L)); assertEquals(2, world.sourceTop.getEntities().size());
        assertSame(world.sourceField, world.sourceTop.getEntity(null).getFieldScreenScheme());

        world.associated.set(world.sourceTop); world.users(20).clear(); world.users(20).add(1L);
        when(world.sourceTop.isDefault()).thenReturn(true);
        SetProjectOperationScreensResponse byDefault = service.assign(request(row(types("Bug"), screens("3", null, null))));
        assertEquals("Jira's default scheme is never changed in place even for one project", SchemeMode.COPY_DEFAULT,
                byDefault.getTopScheme().getMode());
        assertTrue(byDefault.getTopScheme().isDefaultScheme());

        when(world.sourceTop.isDefault()).thenReturn(false); world.users(20).clear();
        SetProjectOperationScreensResponse unproven = service.assign(request(row(types("Bug"), screens("3", null, null))));
        assertEquals("exclusive use is not proven when Jira lists no association", SchemeMode.COPY_SHARED, unproven.getTopScheme().getMode());
        assertTrue(unproven.getTopScheme().getReason(), unproven.getTopScheme().getReason().contains("not proven"));
    }

    @Test
    public void sharingIsDecidedByAssociationsNotByTheNamePrefix() {
        IssueTypeScreenScheme prefixed = world.usePrivateTop(30L, "ABC: Operation Screens");
        world.users(30).add(2L);
        SetProjectOperationScreensResponse shared = service.assign(request(row(types("Task"), screens("3", null, null))));
        assertEquals(SchemeMode.COPY_SHARED, shared.getTopScheme().getMode());
        assertEquals(Status.ASSIGNED, apply(shared).getStatus());
        assertNotSame(prefixed, world.associated.get());
        assertEquals("the other project's scheme is unchanged", 2, prefixed.getEntities().size());
        assertNull(prefixed.getEntity("task"));

        world.usePrivateTop(31L, "Somebody else's name");
        SetProjectOperationScreensResponse own = service.assign(request(row(types("Bug"), screens("3", null, null))));
        assertEquals("exclusive by associations, whatever the name says", SchemeMode.UPDATE_PRIVATE, own.getTopScheme().getMode());
    }

    @Test
    public void anAssociationAddedAfterPreviewMakesAnInPlacePlanStaleBeforeAnyWrite() {
        world.usePrivateTop(30L, "ABC private top");
        SetProjectOperationScreensResponse preview = service.assign(request(row(types("Task"), screens("3", null, null))));
        assertEquals(SchemeMode.UPDATE_PRIVATE, preview.getTopScheme().getMode());
        world.users(30).add(2L);
        assertEquals(ErrorCode.PREVIEW_STALE, apply(preview).getErrorCode());
        assertTrue(world.writes.toString(), world.writes.isEmpty());
        assertEquals("the private scheme is untouched", 2, world.topCatalog.get(30L).getEntities().size());
    }

    @Test
    public void matchingStateOfAPrivateSchemeIsANoOpWithoutWritesOrPlan() {
        world.usePrivateTop(30L, "ABC private top");
        SetProjectOperationScreensResponse noOp = service.assign(request(row(types("Other"), screens("5", null, null))));
        assertEquals(Status.ALREADY_ASSIGNED, noOp.getStatus());
        assertEquals(SchemeMode.NO_CHANGE, noOp.getTopScheme().getMode());
        assertNull(noOp.getPlanId()); assertTrue(noOp.getTopScheme().getMappings().isEmpty());
        assertTrue(world.writes.toString(), world.writes.isEmpty()); assertTrue(savedPlans.isEmpty());
    }

    @Test
    public void failureAfterSomeMappingsReportsTheMappingsReadBackAsPartialWithTheCreatedChildren() {
        IssueTypeScreenScheme own = world.usePrivateTop(30L, "ABC private top");
        SetProjectOperationScreensResponse preview = service.assign(request(row(null, screens(null, null, "9"))));
        world.failUpdateNumber = 2;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(result.getMessage(), Status.PARTIALLY_ASSIGNED, result.getStatus());
        assertEquals(AssignmentState.PARTIAL, result.getAssignment());
        assertEquals(MatrixSource.READ_BACK, result.getMatrixSource());
        assertNull(result.getAssignedSchemeId()); assertNull(result.getCreated().getIssueTypeScreenScheme());
        assertEquals(2, result.getCreated().getScreenSchemes().size());
        for (SetProjectOperationScreensResponse.CreatedObject child : result.getCreated().getScreenSchemes()) {
            assertNotNull(child.getId()); assertTrue(child.isComplete()); assertNotNull(world.fieldCatalog.get(child.getId()));
        }
        assertSame(own, world.associated.get());
        assertNotSame("the DEFAULT row was already changed", world.sourceField, own.getEntity(null).getFieldScreenScheme());
        assertSame("the second row was not", world.otherField, own.getEntity("other").getFieldScreenScheme());
        assertEquals("read back, not planned", 5, planned(result, "Other", "VIEW").getAfter().getId().longValue());
        assertEquals(9, planned(result, "Task", "VIEW").getAfter().getId().longValue());
        assertTrue(result.getMessage(), result.getMessage().contains("only partly"));
        assertTrue(result.getWarnings().toString(), result.getWarnings().toString().contains("Mapping other"));
        assertEquals("the created children were not removed", 4, world.fieldCount());
    }

    @Test
    public void failureOnTheFirstMappingIsConfirmedUnchangedByReadBackNotByTheUnchangedId() {
        IssueTypeScreenScheme own = world.usePrivateTop(30L, "ABC private top");
        SetProjectOperationScreensResponse preview = service.assign(request(row(null, screens(null, null, "9"))));
        world.failUpdateNumber = 1;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals("the created children make it partial even though the mappings are intact",
                Status.PARTIALLY_ASSIGNED, result.getStatus());
        assertEquals(AssignmentState.NOT_ASSIGNED, result.getAssignment());
        assertTrue(result.getMessage(), result.getMessage().contains("confirmed by reading them back"));
        assertEquals(2, result.getCreated().getScreenSchemes().size());
        assertSame(world.sourceField, own.getEntity(null).getFieldScreenScheme());
        assertNull("no actual matrix is claimed", result.getMatrixSource());
    }

    @Test
    public void failureBeforeAnyMappingWriteDoesNotTouchTheMappingsAndNamesTheCreatedChild() {
        IssueTypeScreenScheme own = world.usePrivateTop(30L, "ABC private top");
        SetProjectOperationScreensResponse preview = service.assign(request(row(types("Task"), screens("3", null, null))));
        world.failItemNumber = 1;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(Status.PARTIALLY_ASSIGNED, result.getStatus()); assertEquals(AssignmentState.NOT_ASSIGNED, result.getAssignment());
        assertEquals(1, result.getCreated().getScreenSchemes().size());
        assertFalse(result.getCreated().getScreenSchemes().get(0).isComplete());
        assertTrue(result.getMessage(), result.getMessage().contains("no mapping write was attempted"));
        assertEquals(2, own.getEntities().size()); assertEquals(0, count("entity")); assertEquals(0, count("update"));
    }

    @Test
    public void unreadableMappingsAfterAFailureAreAnUnknownOutcome() {
        IssueTypeScreenScheme own = world.usePrivateTop(30L, "ABC private top");
        SetProjectOperationScreensResponse preview = service.assign(request(row(null, screens(null, null, "9"))));
        world.failUpdateNumber = 2; world.failMappingReadBack = true;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(Status.PARTIALLY_ASSIGNED, result.getStatus()); assertEquals(AssignmentState.UNKNOWN, result.getAssignment());
        assertTrue(result.getIssueTypes().isEmpty()); assertNull(result.getMatrixSource());
        assertTrue(result.getMessage(), result.getMessage().contains("may be partly changed"));
        assertEquals("the created children are still listed", 2, result.getCreated().getScreenSchemes().size());
        assertNotNull(own);
    }

    @Test
    public void errorAfterTheLastPersistedMappingIsReadBackAsAssigned() {
        world.usePrivateTop(30L, "ABC private top");
        SetProjectOperationScreensResponse preview = service.assign(request(row(null, screens(null, null, "9"))));
        world.failAfterUpdateNumber = 2;
        SetProjectOperationScreensResponse result = apply(preview);
        assertEquals(result.getMessage(), Status.ASSIGNED, result.getStatus()); assertEquals(MatrixSource.READ_BACK, result.getMatrixSource());
        assertTrue(result.getWarnings().toString(), result.getWarnings().toString().contains("every planned mapping is in place"));
        assertEquals(9, world.effective(world.other, "VIEW"));
    }

    private SetProjectOperationScreensResponse fivePreview() {
        SetProjectOperationScreensResponse preview = service.assign(request(
                row(types("Task"), screens("3", "4", "6")), row(types("Bug"), screens("4", "5", "7")),
                row(types("Story"), screens("6", "7", "8")), row(types("Epic"), screens("7", "8", "9")),
                row(types("Other"), screens("8", "9", "3"))));
        assertEquals(Status.WOULD_ASSIGN, preview.getStatus());
        return preview;
    }

    private static SetProjectOperationScreensRequest request(Mapping... rows) {
        return new SetProjectOperationScreensRequest(true, "ABC", Arrays.asList(rows), null, null);
    }
    private static Mapping row(List<String> types, Map<String, String> patch) {
        return types == null ? new Mapping(null, true, patch, null) : new Mapping(types, null, patch, null);
    }
    private static List<String> types(String... names) { return Arrays.asList(names); }
    private static Map<String, String> screens(String create, String edit, String view) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (create != null) result.put("CREATE", create);
        if (edit != null) result.put("EDIT", edit);
        if (view != null) result.put("VIEW", view);
        return result;
    }
    private SetProjectOperationScreensResponse apply(SetProjectOperationScreensResponse preview) {
        return service.assign(new SetProjectOperationScreensRequest(false, null, null, null, preview.getPlanId()));
    }
    private void assertError(SetProjectOperationScreensRequest request, ErrorCode code) {
        SetProjectOperationScreensResponse response = service.assign(request);
        assertEquals(response.getMessage(), Status.FAILED, response.getStatus());
        assertEquals(response.getMessage(), code, response.getErrorCode()); assertNull(response.getPlanId());
    }
    private void assertNoWrites() {
        assertTrue(world.writes.toString(), world.writes.isEmpty()); assertTrue(savedPlans.isEmpty());
        assertEquals(2, world.fieldCount()); assertEquals(1, world.topCount());
    }
    private int count(String prefix) {
        int found = 0; for (String write : world.writes) if (write.startsWith(prefix)) found++; return found;
    }
    private static TypeAssignment byName(SetProjectOperationScreensResponse response, String name) {
        for (TypeAssignment type : response.getIssueTypes()) if (type.getIssueType().getName().equals(name)) return type;
        throw new AssertionError(name);
    }
    private static SetProjectOperationScreensResponse.OperationChange planned(SetProjectOperationScreensResponse response, String type, String operation) {
        for (SetProjectOperationScreensResponse.OperationChange change : byName(response, type).getOperations()) {
            if (change.getOperation().equals(operation)) return change;
        }
        throw new AssertionError(operation);
    }
    private static void assertScreen(FieldScreenScheme scheme, String operation, long id) {
        assertEquals(Long.valueOf(id), OperationScreenPlan.effective(scheme, operation).getId());
    }
}
