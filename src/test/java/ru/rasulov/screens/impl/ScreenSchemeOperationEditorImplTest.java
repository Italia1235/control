package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsRequest;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsRequest.Row;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.Change;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.ErrorCode;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.FieldRef;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.LinkedProject;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RequiredFields;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RequiredProject;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RowAction;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RowPlan;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.RowResult;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.Status;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse.Warning;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real planner/writer/executor over the in-memory model of {@link ScreenSchemeOperationWorld}. */
public class ScreenSchemeOperationEditorImplTest {
    private static final String CREATE_WARNING = "REQUIRED_FIELDS_MISSING_ON_CREATE";
    private final ScreenSchemeOperationWorld world = new ScreenSchemeOperationWorld();
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final ActionPlanStore plans = mock(ActionPlanStore.class);
    private final ApplicationUser admin = mock(ApplicationUser.class);
    private final Map<String, ActionPlanStore.Plan> savedPlans = new HashMap<String, ActionPlanStore.Plan>();
    private final Map<String, String> planActors = new HashMap<String, String>();
    private ScreenSchemeOperationEditorImpl service;

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
        service = new ScreenSchemeOperationEditorImpl(guard, lock,
                new ScreenSchemeOperationPlanner(world.fields, world.screenManager, world.tops, world.projectManager,
                        new ScreenSchemeCreateRequirements(world.layouts, world.fieldManager), world.codec),
                new ScreenSchemeOperationWriter(world.factory, world.fields),
                new PreviewPlanExecutor(plans, world.codec));
    }

    // ---- preview against the FINAL table -------------------------------------------------------------------

    @Test
    public void previewShowsBeforeAndAfterPerOperationFromTheFinalTableInAnyRowOrder() {
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS",
                rows(replace("DEFAULT", "Screen 8"), remove("CREATE"), add("VIEW", "Screen 9"))));
        assertEquals(preview.getMessage(), Status.WOULD_CHANGE, preview.getStatus());
        assertTrue(preview.getPlanId() != null && preview.getPlanExpiresAt() != null);
        assertEquals(Long.valueOf(30), preview.getScreenScheme().getId());
        assertEquals("Shared SS", preview.getScreenScheme().getName());
        assertEquals("Shared SS description", preview.getScreenScheme().getDescription());
        assertEquals("a preview writes nothing", Collections.<String>emptyList(), world.writes);
        RowPlan def = preview.getRows().get(0), create = preview.getRows().get(1), view = preview.getRows().get(2);
        assertEquals("DEFAULT", def.getOperation());
        assertEquals(RowAction.REPLACE, def.getAction()); assertEquals(Change.CHANGE, def.getChange());
        assertEquals(Long.valueOf(1), def.getBefore().getId()); assertFalse(def.getBefore().isViaDefault());
        assertEquals(Long.valueOf(8), def.getAfter().getId());
        assertEquals("CREATE", create.getOperation());
        assertEquals(RowAction.REMOVE, create.getAction());
        assertEquals(Long.valueOf(2), create.getBefore().getId()); assertFalse(create.getBefore().isViaDefault());
        assertEquals("CREATE moves to the NEW default screen", Long.valueOf(8), create.getAfter().getId());
        assertTrue(create.getAfter().isViaDefault());
        assertEquals("VIEW used the old default", Long.valueOf(1), view.getBefore().getId());
        assertTrue(view.getBefore().isViaDefault());
        assertEquals(RowAction.ADD, view.getAction()); assertEquals(Long.valueOf(9), view.getAfter().getId());
        assertFalse(view.getAfter().isViaDefault());
        assertEquals(Arrays.asList(0, 1, 2), rowIndexes(preview));
        for (String token : Arrays.asList("Задачи не мигрируют", "Копии не создаются", "Без отката", "после сбоя — только новое превью",
                "Защита от параллельных правок ограничена", "required", "Field Configuration Scheme", "переходов")) {
            assertTrue(token, String.join("\n", preview.getLimitations()).contains(token));
        }
        SetScreenSchemeOperationsResponse reversed = service.edit(request("Shared SS",
                rows(add("VIEW", "Screen 9"), remove("CREATE"), replace("DEFAULT", "Screen 8"))));
        assertEquals(Status.WOULD_CHANGE, reversed.getStatus());
        assertEquals("the same per-row result in any order", Long.valueOf(8), reversed.getRows().get(1).getAfter().getId());
        assertTrue(reversed.getRows().get(1).getAfter().isViaDefault());
        assertEquals(Long.valueOf(9), reversed.getRows().get(0).getAfter().getId());
        assertEquals(Collections.<String>emptyList(), world.writes);
    }

    @Test
    public void addOfAnExistingRowIsTreatedAsReplaceWithAWarningAndTheSameScreenIsNoChange() {
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS",
                rows(add("CREATE", "Screen 8"), add("EDIT", "Screen 3"), add("DEFAULT", "Screen 9"))));
        assertEquals(Status.WOULD_CHANGE, preview.getStatus());
        RowPlan changed = preview.getRows().get(0), same = preview.getRows().get(1), def = preview.getRows().get(2);
        assertEquals(RowAction.ADD, changed.getRequestedAction()); assertEquals(RowAction.REPLACE, changed.getAction());
        assertEquals(Change.CHANGE, changed.getChange());
        assertEquals(Long.valueOf(2), changed.getBefore().getId()); assertEquals(Long.valueOf(8), changed.getAfter().getId());
        assertEquals(Change.NO_CHANGE, same.getChange()); assertEquals(RowAction.REPLACE, same.getAction());
        assertEquals("ADD DEFAULT is an ADD of an existing row", RowAction.REPLACE, def.getAction());
        assertEquals(Change.CHANGE, def.getChange());
        List<Warning> addWarnings = new ArrayList<Warning>();
        for (Warning warning : preview.getWarnings()) if ("ADD_AS_REPLACE".equals(warning.getCode())) addWarnings.add(warning);
        assertEquals(3, addWarnings.size());
        assertEquals(Integer.valueOf(0), addWarnings.get(0).getRowIndex());
        assertTrue(addWarnings.get(0).getMessage(), addWarnings.get(0).getMessage().contains("Screen 2")
                && addWarnings.get(0).getMessage().contains("Screen 8"));
        assertTrue(addWarnings.get(1).getMessage(), addWarnings.get(1).getMessage().contains("nothing changes"));
        assertEquals(Collections.<String>emptyList(), world.writes);
    }

    @Test
    public void allRowsNoChangeIsNoChangeWithoutAPlanOrAWrite() {
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS",
                rows(replace("CREATE", "2"), replace("EDIT", "screen 3"), add("DEFAULT", "Screen 1"))));
        assertEquals(preview.getMessage(), Status.NO_CHANGE, preview.getStatus());
        assertNull(preview.getPlanId());
        assertTrue(savedPlans.isEmpty());
        assertEquals(Arrays.asList(Change.NO_CHANGE, Change.NO_CHANGE, Change.NO_CHANGE), changes(preview));
        assertEquals(Collections.<String>emptyList(), world.writes);
    }

    @Test
    public void theSchemeIsFoundByIdOrExactNameIgnoringCase() {
        for (String reference : Arrays.asList("30", "Shared SS", "shared ss", "SHARED SS", " 30 ")) {
            SetScreenSchemeOperationsResponse preview = service.edit(request(reference, rows(replace("CREATE", "Screen 8"))));
            assertEquals(reference + ": " + preview.getMessage(), Status.WOULD_CHANGE, preview.getStatus());
            assertEquals(Long.valueOf(30), preview.getScreenScheme().getId());
        }
    }

    // ---- scale ---------------------------------------------------------------------------------------------

    @Test
    public void usageCountsEveryIssueTypeScreenSchemeAndProjectAndSeparatesLinkedFromChanging() {
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", rows(replace("CREATE", "Screen 8"))));
        assertEquals("explicit row (20), DEFAULT row (22) and Jira's default scheme (1); 21 holds another scheme", 3, preview.getUsage().getLinked());
        assertFalse(preview.getUsage().isTruncated());
        assertEquals(Arrays.asList(1L, 20L, 22L), new ArrayList<Long>(ids(preview.getUsage().getListed())));
        assertTrue(preview.getUsage().getListed().get(0).isJiraDefault());
        assertFalse(preview.getUsage().getListed().get(1).isJiraDefault());
        assertEquals(5, preview.getProjects().getLinked());
        assertEquals("EPICPR is linked, but none of its types uses the scheme", 4, preview.getProjects().getChanging());
        assertFalse(preview.getProjects().isTruncated());
        assertEquals(Arrays.asList("ALPHA", "BETA", "DEFPR", "EPICPR", "GAMMA"), keys(preview.getProjects().getListed()));
        Map<String, LinkedProject> byKey = new HashMap<String, LinkedProject>();
        for (LinkedProject project : preview.getProjects().getListed()) byKey.put(project.getKey(), project);
        assertEquals(1, byKey.get("ALPHA").getChangedIssueTypes());
        assertEquals(2, byKey.get("BETA").getChangedIssueTypes());
        assertEquals(2, byKey.get("DEFPR").getChangedIssueTypes());
        assertEquals(0, byKey.get("EPICPR").getChangedIssueTypes());
        assertEquals(1, byKey.get("GAMMA").getChangedIssueTypes());
        assertTrue("archived projects are counted and listed", byKey.get("GAMMA").isArchived());
        assertFalse(byKey.get("ALPHA").isArchived());
        assertEquals(Long.valueOf(20), byKey.get("ALPHA").getIssueTypeScreenScheme().getId());
        assertEquals(Long.valueOf(1), byKey.get("DEFPR").getIssueTypeScreenScheme().getId());
        assertTrue(byKey.get("DEFPR").getIssueTypeScreenScheme().isJiraDefault());
        assertEquals("Общую настройку используют 5 проектов. Фактический выбор экранов изменится в 4 проектах. "
                + "Ниже показаны все 5 связанных проектов.", preview.getScopeText());
    }

    @Test
    public void aRowThatOnlyMovesTheDefaultLeavesProjectsWithExplicitRowsUnchanged() {
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", rows(replace("DEFAULT", "Screen 8"))));
        assertEquals("CREATE and EDIT have explicit rows; only VIEW follows the default, and every user of the scheme has it",
                4, preview.getProjects().getChanging());
        SetScreenSchemeOperationsResponse remove = service.edit(request("Shared SS", rows(remove("EDIT"))));
        assertEquals(4, remove.getProjects().getChanging());
        world.persist(world.item(world.shared, "EDIT"), 1);
        SetScreenSchemeOperationsResponse sameEffective = service.edit(request("Shared SS", rows(remove("EDIT"))));
        assertEquals("the row is written, but the effective screen stays the default one", Change.CHANGE, sameEffective.getRows().get(0).getChange());
        assertEquals(0, sameEffective.getProjects().getChanging());
        assertEquals(5, sameEffective.getProjects().getLinked());
    }

    @Test
    public void moreThanTenLinkedProjectsAndSchemesAreCountedExactlyAndOnlyTheListsAreCut() {
        for (int i = 1; i <= 8; i++) world.project(200 + i, "P0" + i, false, world.jiraDefault, null, world.task);
        for (int i = 0; i < 10; i++) world.top(50L + i, "Extra ITSS " + i, false, world.shared);
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", rows(replace("CREATE", "Screen 8"))));
        assertEquals(13, preview.getProjects().getLinked());
        assertEquals(12, preview.getProjects().getChanging());
        assertTrue(preview.getProjects().isTruncated());
        assertEquals(10, preview.getProjects().getListed().size());
        assertEquals(Arrays.asList("ALPHA", "BETA", "DEFPR", "EPICPR", "GAMMA", "P01", "P02", "P03", "P04", "P05"),
                keys(preview.getProjects().getListed()));
        assertEquals(13, preview.getUsage().getLinked());
        assertTrue(preview.getUsage().isTruncated());
        assertEquals(Arrays.asList(1L, 20L, 22L, 50L, 51L, 52L, 53L, 54L, 55L, 56L), new ArrayList<Long>(ids(preview.getUsage().getListed())));
        assertEquals("Общую настройку используют 13 проектов. Фактический выбор экранов изменится в 12 проектах. "
                + "Ниже показаны первые 10 связанных проектов.", preview.getScopeText());
    }

    @Test
    public void aSchemeThatNoIssueTypeScreenSchemeUsesIsAllowedWithAWarning() {
        world.scheme(32L, "Lonely SS", 1L);
        SetScreenSchemeOperationsResponse preview = service.edit(request("Lonely SS", rows(replace("DEFAULT", "Screen 8"))));
        assertEquals(preview.getMessage(), Status.WOULD_CHANGE, preview.getStatus());
        assertEquals(0, preview.getUsage().getLinked());
        assertEquals(0, preview.getProjects().getLinked()); assertEquals(0, preview.getProjects().getChanging());
        assertEquals(Collections.singletonList("NO_LINKED_PROJECTS"), warningCodes(preview));
        assertTrue(preview.getScopeText(), preview.getScopeText().contains("не использует ни один проект"));
    }

    @Test
    public void aLinkedProjectThatCannotBeReadIsNotSilentlyDroppedFromTheCounts() {
        when(world.projectManager.getProjectObj(105L)).thenReturn(null);
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", rows(replace("CREATE", "Screen 8"))));
        assertEquals(Status.FAILED, preview.getStatus());
        assertEquals(ErrorCode.JIRA_ERROR, preview.getErrorCode());
        assertNull(preview.getPlanId());
        assertTrue(savedPlans.isEmpty());
    }

    @Test
    public void aSchemeWithoutADefaultRowIsBrokenDataAndAJiraErrorWithoutAPlan() {
        world.drop(world.shared, "DEFAULT");
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", rows(replace("CREATE", "Screen 8"))));
        assertEquals(ErrorCode.JIRA_ERROR, preview.getErrorCode());
        assertNull(preview.getPlanId());
        assertTrue(savedPlans.isEmpty());
        assertTrue(world.writes.isEmpty());
    }

    // ---- validation ----------------------------------------------------------------------------------------

    @Test
    public void invalidRequestsAreStructuredErrorsWithTheRowNumberAndNoPlan() {
        world.scheme(40L, "Dup SS", 1L); world.scheme(41L, "dup ss", 1L);
        world.screen(20, "Twin", "summary"); world.screen(21, "twin", "summary");
        Object[][] cases = {
                {"nope", rows(replace("CREATE", "Screen 8")), ErrorCode.SCREEN_SCHEME_NOT_FOUND, null},
                {"9999", rows(replace("CREATE", "Screen 8")), ErrorCode.SCREEN_SCHEME_NOT_FOUND, null},
                {"Dup SS", rows(replace("CREATE", "Screen 8")), ErrorCode.SCREEN_SCHEME_AMBIGUOUS, null},
                {"Shared SS", rows(replace("CREATE", "Screen 8"), replace("EDIT", "No such screen")), ErrorCode.SCREEN_NOT_FOUND, 1},
                {"Shared SS", rows(replace("CREATE", "777")), ErrorCode.SCREEN_NOT_FOUND, 0},
                {"Shared SS", rows(replace("CREATE", "Twin")), ErrorCode.SCREEN_AMBIGUOUS, 0},
                {"Shared SS", rows(replace("CREATE", "Screen 8"), replace("TRANSITION", "Screen 8")), ErrorCode.INVALID_OPERATION, 1},
                {"Shared SS", rows(replace("create", "Screen 8")), ErrorCode.INVALID_OPERATION, 0},
                {"Shared SS", rows(new Row("REPLACE", null, "Screen 8")), ErrorCode.INVALID_OPERATION, 0},
                {"Shared SS", rows(new Row("REPLACE", "  ", "Screen 8")), ErrorCode.INVALID_OPERATION, 0},
                {"Shared SS", rows(remove("DEFAULT")), ErrorCode.DEFAULT_ROW_REQUIRED, 0},
                {"Shared SS", rows(replace("VIEW", "Screen 8")), ErrorCode.ROW_NOT_FOUND, 0},
                {"Shared SS", rows(replace("CREATE", "Screen 8"), remove("VIEW")), ErrorCode.ROW_NOT_FOUND, 1},
                {"Shared SS", rows(replace("CREATE", "Screen 8"), remove("CREATE")), ErrorCode.DUPLICATE_ROW, 1},
                {"Shared SS", rows(replace("DEFAULT", "Screen 8"), add("DEFAULT", "Screen 9")), ErrorCode.DUPLICATE_ROW, 1},
                {"Shared SS", rows(new Row("REMOVE", "EDIT", "Screen 8")), ErrorCode.INVALID_ROW, 0},
                {"Shared SS", rows(new Row("ADD", "VIEW", null)), ErrorCode.INVALID_ROW, 0},
                {"Shared SS", rows(new Row("REPLACE", "CREATE", "  ")), ErrorCode.INVALID_ROW, 0},
                {"Shared SS", rows(new Row("MOVE", "CREATE", "Screen 8")), ErrorCode.INVALID_ROW, 0},
                {"Shared SS", rows(new Row(null, "CREATE", "Screen 8")), ErrorCode.INVALID_ROW, 0},
                {"Shared SS", rows((Row) null), ErrorCode.INVALID_ROW, 0},
                {" ", rows(replace("CREATE", "Screen 8")), ErrorCode.VALIDATION_ERROR, null},
                {null, rows(replace("CREATE", "Screen 8")), ErrorCode.VALIDATION_ERROR, null},
                {"Shared SS", new ArrayList<Row>(), ErrorCode.VALIDATION_ERROR, null},
                {"Shared SS", null, ErrorCode.VALIDATION_ERROR, null},
        };
        for (Object[] one : cases) {
            @SuppressWarnings("unchecked") List<Row> list = (List<Row>) one[1];
            SetScreenSchemeOperationsResponse response = service.edit(request((String) one[0], list));
            String label = one[0] + " " + (list == null ? "null" : list.size()) + " " + one[2] + ": " + response.getMessage();
            assertEquals(label, Status.FAILED, response.getStatus());
            assertEquals(label, one[2], response.getErrorCode());
            assertEquals(label, one[3], response.getErrorRowIndex());
            assertNull(label, response.getPlanId());
        }
        List<Row> tooMany = new ArrayList<Row>();
        for (int i = 0; i < 51; i++) tooMany.add(add("VIEW", "Screen 8"));
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(request("Shared SS", tooMany)).getErrorCode());
        assertTrue("no validation error plans or writes", savedPlans.isEmpty() && world.writes.isEmpty());
    }

    @Test
    public void previewAndApplyShapesAreTold() {
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(new SetScreenSchemeOperationsRequest(null, "Shared SS", rows(replace("CREATE", "Screen 8")), null)).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(new SetScreenSchemeOperationsRequest(true, "Shared SS", rows(replace("CREATE", "Screen 8")), "p")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(new SetScreenSchemeOperationsRequest(false, "Shared SS", null, "p")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(new SetScreenSchemeOperationsRequest(false, null, rows(replace("CREATE", "Screen 8")), "p")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(new SetScreenSchemeOperationsRequest(false, null, null, null)).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.edit(new SetScreenSchemeOperationsRequest(false, null, null, " ")).getErrorCode());
    }

    // ---- required fields on CREATE -------------------------------------------------------------------------

    @Test
    public void aNewCreateScreenWithoutARequiredFieldWarnsPerPairAndDoesNotBlockTheApply() {
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", rows(replace("CREATE", "Screen 5"))));
        assertEquals(preview.getMessage(), Status.WOULD_CHANGE, preview.getStatus());
        assertNotNull("the warning does not block the plan", preview.getPlanId());
        RequiredFields required = requiredFields(preview);
        assertEquals("ALPHA bug, BETA task+story, GAMMA (archived) bug, DEFPR task+bug", 6, required.getPairs());
        assertFalse(required.isTruncated());
        assertEquals(Arrays.asList("ALPHA", "BETA", "DEFPR", "GAMMA"), requiredKeys(required));
        assertEquals(Arrays.asList("2"), typeIds(required.getProjects().get(0)));
        assertEquals(Arrays.asList("1", "3"), typeIds(required.getProjects().get(1)));
        assertEquals(Arrays.asList("1", "2"), typeIds(required.getProjects().get(2)));
        assertEquals(Arrays.asList("2"), typeIds(required.getProjects().get(3)));
        assertEquals("project, issuetype and a non-orderable field are never listed", Arrays.asList("customfield_100", "summary"), fieldIds(required));
        assertEquals("Region", required.getFields().get(0).getName()); assertEquals("Summary", required.getFields().get(1).getName());
        assertNull(requiredWarning(preview).getRowIndex());
        assertTrue(requiredWarning(preview).getMessage(), requiredWarning(preview).getMessage().contains("Summary"));
        SetScreenSchemeOperationsResponse done = apply(preview);
        assertEquals(done.getMessage(), Status.CHANGED, done.getStatus());
    }

    @Test
    public void aCustomFieldOutsideTheContextOfThePairIsNotCounted() {
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", rows(replace("CREATE", "Screen 7"))));
        RequiredFields required = requiredFields(preview);
        assertEquals("only BETA (task, story) is in the context of customfield_100; ALPHA uses the same layout out of scope", 2, required.getPairs());
        assertEquals(Collections.singletonList("BETA"), requiredKeys(required));
        assertEquals(Collections.singletonList("customfield_100"), fieldIds(required));
    }

    // The required-field check is never skipped silently: whatever cannot be read fails the whole preview.
    private void assertUnreadableRequirementsFailThePreview() {
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", rows(replace("CREATE", "Screen 5"))));
        assertEquals(preview.getMessage(), Status.FAILED, preview.getStatus());
        assertEquals(ErrorCode.JIRA_ERROR, preview.getErrorCode());
        assertNull(preview.getPlanId());
        assertTrue(savedPlans.isEmpty());
        assertTrue(world.writes.isEmpty());
    }

    @Test
    public void aDefaultFieldConfigurationThatCannotBeReadIsAJiraErrorWithoutAPlan() {
        when(world.layouts.getFieldLayout()).thenReturn(null);
        assertUnreadableRequirementsFailThePreview();
    }

    @Test
    public void aMappedFieldConfigurationThatCannotBeReadIsAJiraErrorWithoutAPlan() {
        when(world.layouts.getFieldLayout(anyLong())).thenReturn(null);
        assertUnreadableRequirementsFailThePreview();
    }

    @Test
    public void aFieldConfigurationThatThrowsIsAJiraErrorWithoutAPlan() {
        when(world.layouts.getFieldLayout()).thenThrow(new IllegalStateException("layout down"));
        assertUnreadableRequirementsFailThePreview();
    }

    @Test
    public void aNewCreateScreenWhoseTabsCannotBeReadIsAJiraErrorWithoutAPlan() {
        when(world.screens.get(5L).getTabs()).thenReturn(null);
        assertUnreadableRequirementsFailThePreview();
    }

    @Test
    public void aRequiredCustomFieldThatCannotBeReadIsAJiraErrorWithoutAPlan() {
        when(world.fieldManager.getCustomField("customfield_100")).thenReturn(null);
        assertUnreadableRequirementsFailThePreview();
    }

    @Test
    public void noWarningWhenEverythingRequiredIsOnTheNewCreateScreen() {
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", rows(replace("CREATE", "Screen 6"))));
        assertEquals(Status.WOULD_CHANGE, preview.getStatus());
        assertFalse(warningCodes(preview).contains(CREATE_WARNING));
    }

    @Test
    public void createReachedThroughTheNewDefaultIsCheckedInAnyRowOrderAndAnUnchangedCreateIsNot() {
        for (List<Row> request : Arrays.asList(rows(replace("DEFAULT", "Screen 5"), remove("CREATE")),
                rows(remove("CREATE"), replace("DEFAULT", "Screen 5")))) {
            SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", request));
            assertEquals(Status.WOULD_CHANGE, preview.getStatus());
            assertEquals(6, requiredFields(preview).getPairs());
            assertEquals(Arrays.asList("customfield_100", "summary"), fieldIds(requiredFields(preview)));
        }
        SetScreenSchemeOperationsResponse defaultOnly = service.edit(request("Shared SS", rows(replace("DEFAULT", "Screen 5"))));
        assertFalse("CREATE keeps its own row, so no CREATE screen changes", warningCodes(defaultOnly).contains(CREATE_WARNING));
        SetScreenSchemeOperationsResponse editOnly = service.edit(request("Shared SS", rows(replace("EDIT", "Screen 5"))));
        assertFalse(warningCodes(editOnly).contains(CREATE_WARNING));
    }

    @Test
    public void theRequiredFieldProjectListIsCutToTenButThePairsAreExact() {
        for (int i = 1; i <= 8; i++) world.project(200 + i, "P0" + i, false, world.jiraDefault, null, world.task);
        RequiredFields required = requiredFields(service.edit(request("Shared SS", rows(replace("CREATE", "Screen 5")))));
        assertEquals(14, required.getPairs());
        assertTrue(required.isTruncated());
        assertEquals(Arrays.asList("ALPHA", "BETA", "DEFPR", "GAMMA", "P01", "P02", "P03", "P04", "P05", "P06"), requiredKeys(required));
    }

    // ---- apply ---------------------------------------------------------------------------------------------

    @Test
    public void confirmedMixedPlanEditsTheSameSchemeInPlaceSkipsNoChangeRowsAndReadsBack() {
        int tops = world.topCatalog.size(), fields = world.fieldCatalog.size(), screens = world.screens.size();
        Map<Long, Map<String, Long>> itssBefore = itssTables();
        Map<Long, Set<Long>> usersBefore = copyUsers();
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS",
                rows(replace("CREATE", "Screen 5"), replace("EDIT", "Screen 3"), add("VIEW", "Screen 9"))));
        assertEquals(Change.NO_CHANGE, preview.getRows().get(1).getChange());
        SetScreenSchemeOperationsResponse done = apply(preview);
        assertEquals(done.getMessage(), Status.CHANGED, done.getStatus());
        assertNull(done.getErrorCode());
        assertEquals(Boolean.TRUE, done.getReadBack());
        assertEquals(Arrays.asList(RowResult.APPLIED, RowResult.NO_CHANGE, RowResult.APPLIED), results(done));
        assertEquals(Long.valueOf(5), done.getRows().get(0).getObserved().getId());
        assertEquals(Long.valueOf(30), done.getScreenScheme().getId());
        Map<String, Long> expected = new TreeMap<String, Long>();
        expected.put("DEFAULT", 1L); expected.put("CREATE", 5L); expected.put("EDIT", 3L); expected.put("VIEW", 9L);
        assertEquals(expected, world.table(30));
        assertEquals("the NO_CHANGE row is never written", Arrays.asList("update 30 CREATE", "create 30 VIEW"), world.writes);
        assertEquals("no new ITSS, no new Screen Scheme, no new Screen", tops, world.topCatalog.size());
        assertEquals(fields, world.fieldCatalog.size());
        assertEquals(screens, world.screens.size());
        verify(world.fields, never()).createFieldScreenScheme(any());
        verify(world.tops, never()).createIssueTypeScreenScheme(any());
        verify(world.tops, never()).addSchemeAssociation(any(com.atlassian.jira.project.Project.class), any());
        verify(world.tops, never()).updateIssueTypeScreenSchemeEntity(any());
        verify(world.tops, never()).createIssueTypeScreenSchemeEntity(any());
        verify(world.tops, never()).removeIssueTypeScreenSchemeEntity(any());
        assertEquals("ITSS rows untouched", itssBefore, itssTables());
        assertEquals("project links untouched", usersBefore, copyUsers());
        assertEquals("the plan is used up", ErrorCode.PREVIEW_STALE, service.edit(applyRequest(preview.getPlanId())).getErrorCode());
    }

    @Test
    public void explicitRowsAreWrittenFirstInRequestOrderThenDefaultThenRemovals() {
        SetScreenSchemeOperationsResponse first = apply(service.edit(request("Shared SS",
                rows(remove("CREATE"), replace("DEFAULT", "Screen 8"), replace("EDIT", "Screen 9"), add("VIEW", "Screen 7")))));
        assertEquals(first.getMessage(), Status.CHANGED, first.getStatus());
        assertEquals(Arrays.asList("update 30 EDIT", "create 30 VIEW", "update 30 DEFAULT", "remove 30 CREATE"), world.writes);
        Map<String, Long> expected = new TreeMap<String, Long>();
        expected.put("DEFAULT", 8L); expected.put("EDIT", 9L); expected.put("VIEW", 7L);
        assertEquals(expected, world.table(30));
        assertEquals(Arrays.asList(RowResult.APPLIED, RowResult.APPLIED, RowResult.APPLIED, RowResult.APPLIED), results(first));

        ScreenSchemeOperationEditorImplTest other = new ScreenSchemeOperationEditorImplTest();
        other.setUp();
        SetScreenSchemeOperationsResponse second = other.apply(other.service.edit(other.request("Shared SS",
                rows(add("VIEW", "Screen 7"), replace("EDIT", "Screen 9"), replace("DEFAULT", "Screen 8"), remove("CREATE")))));
        assertEquals(Status.CHANGED, second.getStatus());
        assertEquals(Arrays.asList("create 30 VIEW", "update 30 EDIT", "update 30 DEFAULT", "remove 30 CREATE"), other.world.writes);
        assertEquals("the same request in another row order ends in the same table", expected, other.world.table(30));
    }

    @Test
    public void errorAfterAWriteThatLandedIsReportedAsDoneWithANote() {
        world.failAfterUpdateNumber = 1;
        SetScreenSchemeOperationsResponse done = apply(service.edit(request("Shared SS", rows(replace("CREATE", "Screen 8")))));
        assertEquals(done.getMessage(), Status.CHANGED, done.getStatus());
        assertEquals(Long.valueOf(8), world.table(30).get("CREATE"));
        assertTrue(done.getNotes().toString(), done.getNotes().toString().contains("Jira reported an error"));
    }

    @Test
    public void failureOfARowStopsTheRestKeepsEarlierRowsAndReportsPartial() {
        world.failUpdateNumber = 2;
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS",
                rows(replace("CREATE", "Screen 5"), replace("EDIT", "Screen 9"), replace("DEFAULT", "Screen 8"))));
        SetScreenSchemeOperationsResponse done = apply(preview);
        assertEquals(done.getMessage(), Status.PARTIALLY_CHANGED, done.getStatus());
        assertEquals(ErrorCode.JIRA_ERROR, done.getErrorCode());
        assertEquals("CREATE applied, EDIT fails, DEFAULT is never attempted; results in request order",
                Arrays.asList(RowResult.APPLIED, RowResult.NOT_APPLIED, RowResult.NOT_ATTEMPTED), results(done));
        assertEquals(Arrays.asList("update 30 CREATE"), world.writes);
        assertEquals("not rolled back", Long.valueOf(5), world.table(30).get("CREATE"));
        assertEquals(Long.valueOf(3), world.table(30).get("EDIT"));
        assertEquals(Long.valueOf(1), world.table(30).get("DEFAULT"));
        assertEquals(Boolean.TRUE, done.getReadBack());
        assertEquals("the plan is used up", ErrorCode.PREVIEW_STALE, service.edit(applyRequest(preview.getPlanId())).getErrorCode());
    }

    @Test
    public void failureOfTheFirstWriteChangesNothingAndTheCachedRowIsNotTakenForTheNewValue() {
        world.failUpdateNumber = 1;
        SetScreenSchemeOperationsResponse done = apply(service.edit(request("Shared SS",
                rows(replace("CREATE", "Screen 5"), replace("DEFAULT", "Screen 8")))));
        assertEquals(done.getMessage(), Status.FAILED, done.getStatus());
        assertEquals(Arrays.asList(RowResult.NOT_APPLIED, RowResult.NOT_ATTEMPTED), results(done));
        assertEquals("the cached item holds the new screen after a failed update; the stored value is the old one",
                Long.valueOf(2), done.getRows().get(0).getObserved().getId());
        assertEquals(Collections.<String>emptyList(), world.writes);
        verify(world.fields, atLeastOnce()).refresh();
    }

    @Test
    public void mixedNoChangeAndFailingRowIsFailedByActualChangesNotPartial() {
        world.failUpdateNumber = 1;
        SetScreenSchemeOperationsResponse done = apply(service.edit(request("Shared SS",
                rows(replace("EDIT", "Screen 3"), replace("CREATE", "Screen 5")))));
        assertEquals(done.getMessage(), Status.FAILED, done.getStatus());
        assertEquals(Arrays.asList(RowResult.NO_CHANGE, RowResult.NOT_APPLIED), results(done));
    }

    @Test
    public void failureOfAnAddOrARemoveIsReportedLikeAnUpdate() {
        world.failCreateNumber = 1;
        SetScreenSchemeOperationsResponse added = apply(service.edit(request("Shared SS", rows(add("VIEW", "Screen 9")))));
        assertEquals(Status.FAILED, added.getStatus());
        assertEquals(Arrays.asList(RowResult.NOT_APPLIED), results(added));
        assertFalse(world.table(30).containsKey("VIEW"));
        world.failRemoveNumber = 1;
        SetScreenSchemeOperationsResponse removed = apply(service.edit(request("Shared SS",
                rows(replace("DEFAULT", "Screen 8"), remove("CREATE")))));
        assertEquals(Status.PARTIALLY_CHANGED, removed.getStatus());
        assertEquals(Arrays.asList(RowResult.APPLIED, RowResult.NOT_APPLIED), results(removed));
        assertEquals(Long.valueOf(2), world.table(30).get("CREATE"));
    }

    @Test
    public void unreadableSchemeAfterWritesIsUnknownNotSuccess() {
        world.failReadBack = true;
        SetScreenSchemeOperationsResponse done = apply(service.edit(request("Shared SS", rows(replace("CREATE", "Screen 8")))));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
        assertEquals(Boolean.FALSE, done.getReadBack());
        assertEquals(Arrays.asList(RowResult.UNKNOWN), results(done));
        assertNull(done.getRows().get(0).getObserved());
    }

    @Test
    public void cacheThatCannotBeDroppedLeavesTheStateUntrustedAndTheResultUnknown() {
        doThrow(new IllegalStateException("cache")).when(world.fields).refresh();
        SetScreenSchemeOperationsResponse done = apply(service.edit(request("Shared SS", rows(replace("CREATE", "Screen 8")))));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
        assertEquals(Boolean.FALSE, done.getReadBack());
        assertEquals(Arrays.asList(RowResult.UNKNOWN), results(done));
    }

    @Test
    public void writeThatReturnsButIsMissingFromTheReadBackIsNotSuccess() {
        world.swallowUpdateNumber = 1;
        SetScreenSchemeOperationsResponse done = apply(service.edit(request("Shared SS", rows(replace("CREATE", "Screen 8")))));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
        assertEquals(Arrays.asList(RowResult.UNKNOWN), results(done));
        assertEquals(Long.valueOf(2), done.getRows().get(0).getObserved().getId());
        assertEquals(ErrorCode.JIRA_ERROR, done.getErrorCode());
    }

    @Test
    public void aConcurrentEditToAThirdValueIsReportedUnknownWithTheObservedValue() {
        int[] writes = {0};
        world.afterWrite = () -> { if (++writes[0] == 1) world.persist(world.item(world.shared, "CREATE"), 7); };
        SetScreenSchemeOperationsResponse done = apply(service.edit(request("Shared SS",
                rows(replace("CREATE", "Screen 5"), replace("DEFAULT", "Screen 8")))));
        assertEquals(done.getMessage(), Status.PARTIALLY_CHANGED, done.getStatus());
        assertEquals(Arrays.asList(RowResult.UNKNOWN, RowResult.APPLIED), results(done));
        assertEquals(Long.valueOf(7), done.getRows().get(0).getObserved().getId());
    }

    @Test
    public void aRowRemovedByAnotherEditorIsNotCountedAsApplied() {
        world.afterWrite = () -> world.drop(world.shared, "EDIT");
        SetScreenSchemeOperationsResponse done = apply(service.edit(request("Shared SS",
                rows(replace("CREATE", "Screen 5"), replace("EDIT", "Screen 9")))));
        assertFalse(done.getMessage(), done.getStatus() == Status.CHANGED);
        assertEquals(RowResult.UNKNOWN, done.getRows().get(1).getResult());
    }

    // ---- stale ---------------------------------------------------------------------------------------------

    @Test
    public void changeOfAnyFingerprintedSourceIsPreviewStaleWithoutAWrite() {
        int count = drifts().size();
        assertEquals("every drift of the AC-6 list is exercised", 19, count);
        for (int i = 0; i < count; i++) {
            ScreenSchemeOperationEditorImplTest fresh = new ScreenSchemeOperationEditorImplTest();
            fresh.setUp();
            SetScreenSchemeOperationsResponse preview = fresh.service.edit(fresh.request("Shared SS",
                    rows(replace("CREATE", "Screen 5"), add("VIEW", "Screen 9"), remove("EDIT"))));
            assertEquals("drift " + i + ": " + preview.getMessage(), Status.WOULD_CHANGE, preview.getStatus());
            fresh.drifts().get(i).run();
            SetScreenSchemeOperationsResponse result = fresh.service.edit(fresh.applyRequest(preview.getPlanId()));
            assertEquals("drift " + i, ErrorCode.PREVIEW_STALE, result.getErrorCode());
            assertEquals("drift " + i, Status.FAILED, result.getStatus());
            assertTrue("drift " + i + " must not write", fresh.world.writes.isEmpty());
            assertTrue("the plan is consumed", fresh.savedPlans.isEmpty());
        }
    }

    private List<Runnable> drifts() {
        List<Runnable> drifts = new ArrayList<Runnable>();
        drifts.add(() -> world.persist(world.item(world.shared, "DEFAULT"), 9));                                // a scheme row changed
        drifts.add(() -> world.persist(world.item(world.shared, "EDIT"), 7));
        drifts.add(() -> world.explicit(world.shared, com.atlassian.jira.issue.operation.IssueOperations.VIEW_ISSUE_OPERATION, 7));
        drifts.add(() -> world.drop(world.shared, "EDIT"));
        drifts.add(() -> world.shared.setName("Renamed SS"));                                                   // name / description
        drifts.add(() -> world.shared.setDescription("Another description"));
        drifts.add(() -> world.top(150L, "New ITSS", false, world.shared));                                     // set of ITSS with the scheme
        drifts.add(() -> world.row(world.own, "1", world.shared));
        drifts.add(() -> world.sharedTop.getEntities().stream().filter(e -> "2".equals(e.getIssueTypeId())).findFirst().get()
                .setFieldScreenScheme(world.otherScheme));                                                      // ITSS rows
        drifts.add(() -> world.users(22).add(104L));                                                            // projects of an ITSS
        drifts.add(() -> world.users(1).remove(105L));
        drifts.add(() -> world.project(150, "NEWP", false, world.defaultSsTop, null, world.task));
        drifts.add(() -> world.typesByProject.get(102L).add(world.bug));                                        // issue types of a project
        drifts.add(() -> world.typesByProject.get(101L).remove(world.bug));
        drifts.add(() -> world.screens.remove(5L));                                                             // a screen of the table is gone
        drifts.add(() -> when(world.screens.get(2L).getName()).thenReturn("Screen 2 renamed"));                 // a screen of the table changed
        drifts.add(() -> world.screenFields.get(5L).add("summary"));                                            // fields of the new CREATE screen
        drifts.add(() -> world.screenFields.get(2L).remove("labels"));                                          // fields of the old CREATE screen
        drifts.add(() -> world.screens.remove(2L));                                                             // a screen of the table before is gone
        return drifts;
    }

    @Test
    public void aChangeOfARequiredFlagOrOfAProjectsFieldConfigurationSchemeIsNotTrackedAndNamedAsALimitation() {
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", rows(replace("CREATE", "Screen 5"))));
        world.requiredByLayout.get(0L).put("description", true);
        world.layoutOfProject.put(105L, 11L);
        SetScreenSchemeOperationsResponse done = service.edit(applyRequest(preview.getPlanId()));
        assertEquals(done.getMessage(), Status.CHANGED, done.getStatus());
        assertTrue(String.join("\n", preview.getLimitations()).contains("Field Configuration Scheme"));
    }

    @Test
    public void aPlanOfAnotherActorOrAnUnknownPlanIsStale() {
        SetScreenSchemeOperationsResponse preview = service.edit(request("Shared SS", rows(replace("CREATE", "Screen 8"))));
        planActors.put(preview.getPlanId(), "someone-else");
        assertEquals(ErrorCode.PREVIEW_STALE, service.edit(applyRequest(preview.getPlanId())).getErrorCode());
        assertEquals(ErrorCode.PREVIEW_STALE, service.edit(applyRequest("unknown")).getErrorCode());
        assertTrue(world.writes.isEmpty());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private SetScreenSchemeOperationsResponse apply(SetScreenSchemeOperationsResponse preview) {
        assertEquals(preview.getMessage(), Status.WOULD_CHANGE, preview.getStatus());
        return service.edit(applyRequest(preview.getPlanId()));
    }

    private static SetScreenSchemeOperationsRequest applyRequest(String planId) {
        return new SetScreenSchemeOperationsRequest(false, null, null, planId);
    }

    private SetScreenSchemeOperationsRequest request(String scheme, List<Row> rows) {
        return new SetScreenSchemeOperationsRequest(true, scheme, rows, null);
    }

    private Map<Long, Set<Long>> copyUsers() {
        Map<Long, Set<Long>> copy = new TreeMap<Long, Set<Long>>();
        for (Map.Entry<Long, Set<Long>> entry : world.usersByTop.entrySet()) copy.put(entry.getKey(), new TreeSet<Long>(entry.getValue()));
        return copy;
    }

    private Map<Long, Map<String, Long>> itssTables() {
        Map<Long, Map<String, Long>> tables = new TreeMap<Long, Map<String, Long>>();
        for (IssueTypeScreenScheme top : world.topCatalog.values()) {
            Map<String, Long> table = new TreeMap<String, Long>();
            for (IssueTypeScreenSchemeEntity entity : top.getEntities()) {
                table.put(entity.getIssueTypeId() == null ? "DEFAULT" : entity.getIssueTypeId(), entity.getFieldScreenScheme().getId());
            }
            tables.put(top.getId(), table);
        }
        return tables;
    }

    private static List<Row> rows(Row... rows) { return new ArrayList<Row>(Arrays.asList(rows)); }
    private Row add(String operation, String screen) { return new Row("ADD", operation, screen); }
    private Row replace(String operation, String screen) { return new Row("REPLACE", operation, screen); }
    private Row remove(String operation) { return new Row("REMOVE", operation, null); }

    private static List<Integer> rowIndexes(SetScreenSchemeOperationsResponse response) {
        List<Integer> result = new ArrayList<Integer>();
        for (RowPlan row : response.getRows()) result.add(row.getRowIndex());
        return result;
    }

    private static List<RowResult> results(SetScreenSchemeOperationsResponse response) {
        List<RowResult> result = new ArrayList<RowResult>();
        for (RowPlan row : response.getRows()) result.add(row.getResult());
        return result;
    }

    private static List<Change> changes(SetScreenSchemeOperationsResponse response) {
        List<Change> result = new ArrayList<Change>();
        for (RowPlan row : response.getRows()) result.add(row.getChange());
        return result;
    }

    private static List<String> keys(List<LinkedProject> projects) {
        List<String> keys = new ArrayList<String>();
        for (LinkedProject project : projects) keys.add(project.getKey());
        return keys;
    }

    private static List<Long> ids(List<SetScreenSchemeOperationsResponse.ItssRef> refs) {
        List<Long> ids = new ArrayList<Long>();
        for (SetScreenSchemeOperationsResponse.ItssRef ref : refs) ids.add(ref.getId());
        return ids;
    }

    private static List<String> warningCodes(SetScreenSchemeOperationsResponse response) {
        List<String> codes = new ArrayList<String>();
        for (Warning warning : response.getWarnings()) codes.add(warning.getCode());
        return codes;
    }

    private static Warning requiredWarning(SetScreenSchemeOperationsResponse response) {
        for (Warning warning : response.getWarnings()) if (CREATE_WARNING.equals(warning.getCode())) return warning;
        throw new AssertionError("no " + CREATE_WARNING + " in " + warningCodes(response));
    }

    private static RequiredFields requiredFields(SetScreenSchemeOperationsResponse response) {
        return requiredWarning(response).getRequiredFields();
    }

    private static List<String> requiredKeys(RequiredFields required) {
        List<String> keys = new ArrayList<String>();
        for (RequiredProject project : required.getProjects()) keys.add(project.getKey());
        return keys;
    }

    private static List<String> typeIds(RequiredProject project) {
        List<String> ids = new ArrayList<String>();
        for (IssueTypeRef type : project.getIssueTypes()) ids.add(type.getId());
        return ids;
    }

    private static List<String> fieldIds(RequiredFields required) {
        List<String> ids = new ArrayList<String>();
        for (FieldRef field : required.getFields()) ids.add(field.getId());
        return ids;
    }
}
