package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeRequest;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.Change;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.ErrorCode;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.OperationChange;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.Status;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.TypePlan;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.Warning;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Real planner/writer/executor over the in-memory model of {@link ItssAssignWorld}. */
public class IssueTypeScreenSchemeAssignerImplTest {
    private final ItssAssignWorld world = new ItssAssignWorld();
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final ActionPlanStore plans = mock(ActionPlanStore.class);
    private final ApplicationUser admin = mock(ApplicationUser.class);
    private final Map<String, ActionPlanStore.Plan> savedPlans = new HashMap<String, ActionPlanStore.Plan>();
    private final Map<String, String> planActors = new HashMap<String, String>();
    private IssueTypeScreenSchemeAssignerImpl service;

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
        service = new IssueTypeScreenSchemeAssignerImpl(guard, lock,
                new IssueTypeScreenSchemeAssignmentPlanner(world.projects, world.tops, world.projectManager, world.requirements, world.codec),
                new IssueTypeScreenSchemeAssignmentWriter(world.tops),
                new PreviewPlanExecutor(plans, world.codec));
    }

    // ---- preview ---------------------------------------------------------------------------------------------

    @Test
    public void previewShowsCurrentAndNewSchemeAndTheScreensOfEveryIssueTypeBeforeAndAfter() {
        AssignIssueTypeScreenSchemeResponse preview = service.assign(preview("OWN", "Shared ITSS"));
        assertEquals(preview.getMessage(), Status.WOULD_CHANGE, preview.getStatus());
        assertTrue(preview.isDryRun());
        assertNotNull(preview.getPlanId());
        assertEquals("OWN", preview.getProject().getKey());
        assertEquals(Long.valueOf(21), preview.getCurrentIssueTypeScreenScheme().getId());
        assertEquals("Own ITSS", preview.getCurrentIssueTypeScreenScheme().getName());
        assertFalse(preview.getCurrentIssueTypeScreenScheme().isJiraDefault());
        assertEquals(Long.valueOf(20), preview.getNewIssueTypeScreenScheme().getId());
        assertEquals("Shared ITSS", preview.getNewIssueTypeScreenScheme().getName());
        assertEquals(2, preview.getIssueTypes().size());

        TypePlan bug = type(preview, "Bug");
        assertEquals(Change.CHANGE, bug.getChange());
        assertEquals(Long.valueOf(30), bug.getScreenSchemeBefore().getId());
        assertTrue("Bug follows the DEFAULT row of Own ITSS", bug.getScreenSchemeBefore().isViaDefault());
        assertEquals(Long.valueOf(32), bug.getScreenSchemeAfter().getId());
        assertEquals("Lean SS", bug.getScreenSchemeAfter().getName());
        assertFalse("Bug has an explicit row in Shared ITSS", bug.getScreenSchemeAfter().isViaDefault());
        assertEquals(Long.valueOf(1), op(bug, "CREATE").getBefore().getId());
        assertEquals(Long.valueOf(5), op(bug, "CREATE").getAfter().getId());
        assertTrue("Lean SS has only a DEFAULT item: the CREATE screen comes through it", op(bug, "CREATE").getAfter().isViaDefault());

        TypePlan task = type(preview, "Task");
        assertEquals(Change.CHANGE, task.getChange());
        assertEquals(Long.valueOf(31), task.getScreenSchemeAfter().getId());
        assertTrue(task.getScreenSchemeAfter().isViaDefault());
        assertEquals(Long.valueOf(1), op(task, "CREATE").getBefore().getId());
        assertEquals(Long.valueOf(2), op(task, "CREATE").getAfter().getId());
        assertFalse(op(task, "CREATE").getAfter().isViaDefault());
        assertEquals(Long.valueOf(3), op(task, "EDIT").getAfter().getId());
        assertEquals(Long.valueOf(4), op(task, "VIEW").getAfter().getId());
        assertTrue(op(task, "VIEW").getBefore().isViaDefault());
        assertTrue("a preview writes nothing", world.writes.isEmpty());
        verify(world.tops, never()).addSchemeAssociation(any(Project.class), any(IssueTypeScreenScheme.class));
    }

    @Test
    public void aProjectAlreadyOnTheTargetIsNoChangeWithoutAPlan() {
        AssignIssueTypeScreenSchemeResponse preview = service.assign(preview("ALPHA", "Shared ITSS"));
        assertEquals(preview.getMessage(), Status.NO_CHANGE, preview.getStatus());
        assertNull(preview.getPlanId());
        assertTrue(savedPlans.isEmpty());
        for (TypePlan type : preview.getIssueTypes()) assertEquals(Change.NO_CHANGE, type.getChange());
        assertTrue(preview.getWarnings().isEmpty());
        assertTrue(world.writes.isEmpty());
    }

    @Test
    public void rowsAreChangeOrNoChangePerIssueTypeAndTheSwitchIsStillAChange() {
        AssignIssueTypeScreenSchemeResponse mixed = service.assign(preview("DEFPR", "Spare ITSS"));
        assertEquals(Status.WOULD_CHANGE, mixed.getStatus());
        assertEquals("Task goes from Default SS to Rich SS", Change.CHANGE, type(mixed, "Task").getChange());
        assertEquals("Bug keeps Default SS through an explicit row", Change.NO_CHANGE, type(mixed, "Bug").getChange());
        assertTrue("Bug has no row of its own in the default scheme", type(mixed, "Bug").getScreenSchemeBefore().isViaDefault());
        assertFalse("and an explicit row in Spare ITSS", type(mixed, "Bug").getScreenSchemeAfter().isViaDefault());

        AssignIssueTypeScreenSchemeResponse same = service.assign(preview("OWN", "Default Issue Type Screen Scheme"));
        assertEquals("the association differs, so the switch is planned", Status.WOULD_CHANGE, same.getStatus());
        assertNotNull(same.getPlanId());
        for (TypePlan type : same.getIssueTypes()) assertEquals(Change.NO_CHANGE, type.getChange());
    }

    // ---- target and project ----------------------------------------------------------------------------------

    @Test
    public void theTargetIsAnIdAnExactNameIgnoringCaseOrTheUppercaseDefaultToken() {
        assertEquals(Long.valueOf(20), service.assign(preview("OWN", "20")).getNewIssueTypeScreenScheme().getId());
        assertEquals(Long.valueOf(20), service.assign(preview("OWN", "sHaReD itss")).getNewIssueTypeScreenScheme().getId());
        AssignIssueTypeScreenSchemeResponse token = service.assign(preview("OWN", "DEFAULT"));
        assertEquals("the token wins over the scheme that is merely named DEFAULT", Long.valueOf(1), token.getNewIssueTypeScreenScheme().getId());
        assertTrue(token.getNewIssueTypeScreenScheme().isJiraDefault());
        AssignIssueTypeScreenSchemeResponse byName = service.assign(preview("OWN", "default"));
        assertEquals("a lowercase word is a name, not the token", Long.valueOf(25), byName.getNewIssueTypeScreenScheme().getId());
    }

    @Test
    public void anUnknownOrAmbiguousTargetIsAnErrorWithoutAPlan() {
        AssignIssueTypeScreenSchemeResponse unknown = service.assign(preview("OWN", "No such ITSS"));
        assertEquals(Status.FAILED, unknown.getStatus());
        assertEquals(ErrorCode.ITSS_NOT_FOUND, unknown.getErrorCode());
        AssignIssueTypeScreenSchemeResponse ambiguous = service.assign(preview("OWN", "TWIN ITSS"));
        assertEquals(ErrorCode.ITSS_AMBIGUOUS, ambiguous.getErrorCode());
        assertTrue(ambiguous.getMessage(), ambiguous.getMessage().contains("23") && ambiguous.getMessage().contains("24"));
        assertNull(unknown.getPlanId()); assertNull(ambiguous.getPlanId());
        assertTrue(savedPlans.isEmpty());
    }

    @Test
    public void anUnknownOrArchivedProjectIsRejectedWithoutAPlan() {
        AssignIssueTypeScreenSchemeResponse unknown = service.assign(preview("NOPE", "Shared ITSS"));
        assertEquals(ErrorCode.UNKNOWN_PROJECT, unknown.getErrorCode());
        AssignIssueTypeScreenSchemeResponse archivedProject = service.assign(preview("GAMMA", "Own ITSS"));
        assertEquals(ErrorCode.UNKNOWN_PROJECT, archivedProject.getErrorCode());
        assertTrue(archivedProject.getMessage(), archivedProject.getMessage().contains("archived"));
        assertTrue(savedPlans.isEmpty());
        assertTrue(world.writes.isEmpty());
    }

    @Test
    public void blankInputAndWrongModesAreValidationErrors() {
        assertEquals(ErrorCode.VALIDATION_ERROR, service.assign(preview(" ", "Shared ITSS")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.assign(preview("OWN", null)).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.assign(preview("OWN", " ")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.assign(new AssignIssueTypeScreenSchemeRequest(null, "OWN", "Shared ITSS", null)).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.assign(new AssignIssueTypeScreenSchemeRequest(true, "OWN", "Shared ITSS", "plan-1")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.assign(new AssignIssueTypeScreenSchemeRequest(false, "OWN", null, "plan-1")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.assign(new AssignIssueTypeScreenSchemeRequest(false, null, "Shared ITSS", "plan-1")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, service.assign(new AssignIssueTypeScreenSchemeRequest(false, null, null, " ")).getErrorCode());
        assertTrue(savedPlans.isEmpty());
    }

    // ---- warnings --------------------------------------------------------------------------------------------

    @Test
    public void theOldSchemeLeftWithoutProjectsIsReportedAsOrphanedButNotDeleted() {
        AssignIssueTypeScreenSchemeResponse preview = service.assign(preview("OWN", "Shared ITSS"));
        Warning orphan = warning(preview, "ITSS_ORPHANED");
        assertNotNull(preview.getWarnings().toString(), orphan);
        assertTrue(orphan.getMessage(), orphan.getMessage().contains("Own ITSS"));
        assertEquals("the plan is still issued", Status.WOULD_CHANGE, preview.getStatus());
        assertTrue(world.topCatalog.containsKey(21L));
    }

    @Test
    public void theOldSchemeIsNotOrphanedWhileAnotherProjectEvenAnArchivedOneStillUsesIt() {
        assertNull(warning(service.assign(preview("ALPHA", "Own ITSS")), "ITSS_ORPHANED"));
    }

    @Test
    public void jirasDefaultSchemeIsNeverOrphaned() {
        assertNull("DEFPR is the only user of the default scheme", warning(service.assign(preview("DEFPR", "Shared ITSS")), "ITSS_ORPHANED"));
    }

    @Test
    public void aTargetUsedByOtherProjectsIncludingArchivedOnesIsReportedAsShared() {
        Warning shared = warning(service.assign(preview("OWN", "Shared ITSS")), "SHARED_TARGET");
        assertNotNull(shared);
        assertEquals(Integer.valueOf(2), shared.getCount());
        List<String> keys = new ArrayList<String>();
        for (ru.rasulov.common.api.dto.ProjectRef project : shared.getProjects()) keys.add(project.getKey());
        assertEquals(Arrays.asList("ALPHA", "GAMMA"), keys);
        assertNull("an unused target is not shared", warning(service.assign(preview("OWN", "Spare ITSS")), "SHARED_TARGET"));
    }

    @Test
    public void requiredFieldsMissingOnTheNewCreateScreenAreReportedForEveryTypeWhoseCreateScreenChangesAndDoNotBlock() {
        AssignIssueTypeScreenSchemeResponse preview = service.assign(preview("OWN", "Lean ITSS"));
        Warning missing = warning(preview, "REQUIRED_FIELDS_MISSING_ON_CREATE");
        assertNotNull(preview.getWarnings().toString(), missing);
        assertEquals(Status.WOULD_CHANGE, preview.getStatus());
        assertNotNull("the warning does not block the plan", preview.getPlanId());
        List<String> types = new ArrayList<String>();
        for (ru.rasulov.common.api.dto.IssueTypeRef type : missing.getIssueTypes()) types.add(type.getName());
        assertEquals(Arrays.asList("Bug", "Task"), types);
        List<String> fields = new ArrayList<String>();
        for (AssignIssueTypeScreenSchemeResponse.FieldRef field : missing.getFields()) fields.add(field.getId());
        assertEquals("summary and the Region custom field of OWN", Arrays.asList("customfield_100", "summary"), fields);
    }

    @Test
    public void onlyTheTypesWhoseCreateScreenChangesAreChecked() {
        Warning missing = warning(service.assign(preview("DEFPR", "Bug-lean ITSS")), "REQUIRED_FIELDS_MISSING_ON_CREATE");
        assertNotNull(missing);
        assertEquals(1, missing.getIssueTypes().size());
        assertEquals("Bug", missing.getIssueTypes().get(0).getName());
        assertEquals("summary", missing.getFields().get(0).getId());
        assertEquals(1, missing.getFields().size());
    }

    @Test
    public void noRequiredFieldsWarningWhenTheNewCreateScreensHaveThem() {
        assertNull(warning(service.assign(preview("ALPHA", "Spare ITSS")), "REQUIRED_FIELDS_MISSING_ON_CREATE"));
    }

    @Test
    public void noRequiredFieldsWarningWhenTheCreateScreenDoesNotChangeEvenIfItLacksTheFields() {
        AssignIssueTypeScreenSchemeResponse preview = service.assign(preview("LEAN", "Lean-edit ITSS"));
        assertEquals(Status.WOULD_CHANGE, preview.getStatus());
        assertEquals("the EDIT screen changes, the CREATE screen stays", Change.CHANGE, type(preview, "Task").getChange());
        assertNull(warning(preview, "REQUIRED_FIELDS_MISSING_ON_CREATE"));
    }

    @Test
    public void anUnreadableFieldConfigurationOrScreenIsAJiraErrorWithoutAPlan() {
        world.failFieldLayout = true;
        AssignIssueTypeScreenSchemeResponse layout = service.assign(preview("OWN", "Lean ITSS"));
        assertEquals(Status.FAILED, layout.getStatus());
        assertEquals(ErrorCode.JIRA_ERROR, layout.getErrorCode());
        world.failFieldLayout = false;
        world.failScreenFields = true;
        AssignIssueTypeScreenSchemeResponse tabs = service.assign(preview("OWN", "Lean ITSS"));
        assertEquals(ErrorCode.JIRA_ERROR, tabs.getErrorCode());
        assertTrue(savedPlans.isEmpty());
    }

    // ---- apply and outcome -----------------------------------------------------------------------------------

    @Test
    public void applyAssignsTheProjectUnderTheLockAndReadsTheAssociationBackAfterAnExplicitRefresh() {
        Map<String, Long> sharedRows = world.table(world.shared), ownRows = world.table(world.own);
        int tops = world.topCatalog.size(), fields = world.fieldCatalog.size();
        AssignIssueTypeScreenSchemeResponse preview = service.assign(preview("OWN", "Shared ITSS"));
        AssignIssueTypeScreenSchemeResponse done = apply(preview);
        assertEquals(done.getMessage(), Status.CHANGED, done.getStatus());
        assertFalse(done.isDryRun());
        assertTrue(done.getReadBack());
        assertNull(done.getErrorCode());
        assertEquals(Long.valueOf(20), done.getObservedIssueTypeScreenScheme().getId());
        assertEquals(Arrays.asList("associate OWN 20"), world.writes);
        assertEquals(1, world.associationCalls);
        assertTrue("the scheme cache was dropped before the read-back", world.refreshCalls >= 1);
        verify(world.tops).addSchemeAssociation(world.ownPr, world.shared);
        verify(lock).run(eq(ScreenSchemeServiceImpl.LOCK_NAME), anyString(), any());
        assertEquals("rows of the schemes are untouched", sharedRows, world.table(world.shared));
        assertEquals(ownRows, world.table(world.own));
        assertEquals("no new scheme or Screen Scheme", tops, world.topCatalog.size());
        assertEquals(fields, world.fieldCatalog.size());
        verify(world.tops, never()).createIssueTypeScreenScheme(any());
        assertTrue(world.users(20).contains(104L));
        assertFalse(world.users(21).contains(104L));
        assertTrue("the old scheme is not deleted", world.topCatalog.containsKey(21L));
    }

    @Test
    public void theDefaultTokenSwitchesTheProjectToJirasDefaultSchemeWithTheSameCall() {
        AssignIssueTypeScreenSchemeResponse done = apply(service.assign(preview("OWN", "DEFAULT")));
        assertEquals(done.getMessage(), Status.CHANGED, done.getStatus());
        verify(world.tops).addSchemeAssociation(world.ownPr, world.jiraDefault);
        assertEquals(Long.valueOf(1), done.getObservedIssueTypeScreenScheme().getId());
    }

    @Test
    public void aPreviewIsSingleUseAndAnUnknownPlanIsStale() {
        AssignIssueTypeScreenSchemeResponse preview = service.assign(preview("OWN", "Shared ITSS"));
        assertEquals(Status.CHANGED, apply(preview).getStatus());
        AssignIssueTypeScreenSchemeResponse again = apply(preview);
        assertEquals(ErrorCode.PREVIEW_STALE, again.getErrorCode());
        assertEquals(1, world.associationCalls);
        assertEquals(ErrorCode.PREVIEW_STALE, service.assign(new AssignIssueTypeScreenSchemeRequest(false, null, null, "no-such-plan")).getErrorCode());
    }

    @Test
    public void noErrorAndTheNewSchemeReadBackWithoutTheProjectInTheOldOneIsChanged() {
        AssignIssueTypeScreenSchemeResponse done = apply(service.assign(preview("OWN", "Shared ITSS")));
        assertEquals(Status.CHANGED, done.getStatus());
        assertTrue(done.getNotes().isEmpty());
    }

    @Test
    public void noErrorButTheProjectStillListedUnderTheOldSchemeIsUnknown() {
        world.keepInPrevious = true;
        AssignIssueTypeScreenSchemeResponse done = apply(service.assign(preview("OWN", "Shared ITSS")));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
        assertEquals(ErrorCode.JIRA_ERROR, done.getErrorCode());
    }

    @Test
    public void noErrorButTheOldSchemeReadBackIsFailedBecauseTheWriteHadNoEffect() {
        world.swallowAssociation = true;
        AssignIssueTypeScreenSchemeResponse done = apply(service.assign(preview("OWN", "Shared ITSS")));
        assertEquals(done.getMessage(), Status.FAILED, done.getStatus());
        assertEquals(Long.valueOf(21), done.getObservedIssueTypeScreenScheme().getId());
        assertEquals(ErrorCode.JIRA_ERROR, done.getErrorCode());
        assertTrue(done.getReadBack());
    }

    @Test
    public void failedDoesNotClaimNothingChangedWhenTheWriteOnlyListedTheProjectUnderTheTarget() {
        world.listUnderTargetOnly = true;
        AssignIssueTypeScreenSchemeResponse done = apply(service.assign(preview("OWN", "Shared ITSS")));
        assertEquals(done.getMessage(), Status.FAILED, done.getStatus());
        assertEquals(Long.valueOf(21), done.getObservedIssueTypeScreenScheme().getId());
        assertTrue("the write did leave a trace in the target's project list", world.users(20).contains(104L));
        assertFalse(done.getMessage(), done.getMessage().toLowerCase().contains("nothing changed"));
        for (String note : done.getNotes()) assertFalse(note, note.toLowerCase().contains("nothing changed"));
    }

    @Test
    public void noErrorButAThirdSchemeReadBackIsUnknown() {
        world.redirectTo = 22L;
        AssignIssueTypeScreenSchemeResponse done = apply(service.assign(preview("OWN", "Shared ITSS")));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
        assertEquals(Long.valueOf(22), done.getObservedIssueTypeScreenScheme().getId());
    }

    @Test
    public void noErrorButAnUnreadableAssociationIsUnknown() {
        world.failAssociationReadBack = true;
        AssignIssueTypeScreenSchemeResponse done = apply(service.assign(preview("OWN", "Shared ITSS")));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
        assertFalse(done.getReadBack());
        assertNull(done.getObservedIssueTypeScreenScheme());
    }

    @Test
    public void noErrorButAFailedRefreshIsUnknownBecauseTheCacheCannotBeTrusted() {
        world.failRefresh = true;
        AssignIssueTypeScreenSchemeResponse done = apply(service.assign(preview("OWN", "Shared ITSS")));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
        assertFalse(done.getReadBack());
    }

    @Test
    public void anErrorWithTheNewSchemeReadBackAndTheProjectGoneFromTheOldOneIsChangedWithANote() {
        world.associateThenFail = true;
        AssignIssueTypeScreenSchemeResponse done = apply(service.assign(preview("OWN", "Shared ITSS")));
        assertEquals(done.getMessage(), Status.CHANGED, done.getStatus());
        assertNull(done.getErrorCode());
        assertEquals(1, done.getNotes().size());
        assertTrue(done.getNotes().get(0), done.getNotes().get(0).contains("late error after the switch"));
    }

    @Test
    public void anErrorWithTheNewSchemeReadBackButTheProjectStillUnderTheOldOneIsUnknown() {
        world.associateThenFail = true; world.keepInPrevious = true;
        AssignIssueTypeScreenSchemeResponse done = apply(service.assign(preview("OWN", "Shared ITSS")));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
    }

    @Test
    public void anErrorWithTheOldSchemeReadBackIsFailed() {
        world.failAssociation = true;
        AssignIssueTypeScreenSchemeResponse done = apply(service.assign(preview("OWN", "Shared ITSS")));
        assertEquals(done.getMessage(), Status.FAILED, done.getStatus());
        assertEquals(Long.valueOf(21), done.getObservedIssueTypeScreenScheme().getId());
        assertEquals(ErrorCode.JIRA_ERROR, done.getErrorCode());
        assertTrue(world.users(21).contains(104L));
    }

    @Test
    public void anErrorWithAThirdSchemeOrAnUnreadableAssociationIsUnknown() {
        world.associateThenFail = true; world.redirectTo = 22L;
        assertEquals(Status.UNKNOWN, apply(service.assign(preview("OWN", "Shared ITSS"))).getStatus());
        ItssAssignWorld other = new ItssAssignWorld();
        other.associateThenFail = true; other.failAssociationReadBack = true;
        IssueTypeScreenSchemeAssignerImpl second = new IssueTypeScreenSchemeAssignerImpl(guard, lock,
                new IssueTypeScreenSchemeAssignmentPlanner(other.projects, other.tops, other.projectManager, other.requirements, other.codec),
                new IssueTypeScreenSchemeAssignmentWriter(other.tops), new PreviewPlanExecutor(plans, other.codec));
        AssignIssueTypeScreenSchemeResponse preview = second.assign(preview("OWN", "Shared ITSS"));
        AssignIssueTypeScreenSchemeResponse done = second.assign(new AssignIssueTypeScreenSchemeRequest(false, null, null, preview.getPlanId()));
        assertEquals(done.getMessage(), Status.UNKNOWN, done.getStatus());
        assertFalse(done.getReadBack());
    }

    // ---- stale ------------------------------------------------------------------------------------------------

    @Test public void staleWhenTheProjectWasSwitchedMeanwhile() {
        assertStale(() -> { world.users(21).remove(104L); world.users(22).add(104L); world.associated.put(104L, 22L); });
    }
    @Test public void staleWhenTheCurrentSchemeIsRenamed() { assertStale(() -> world.own.setName("Renamed own")); }
    @Test public void staleWhenTheCurrentSchemeDescriptionChanges() { assertStale(() -> world.own.setDescription("other description")); }
    @Test public void staleWhenTheCurrentSchemeBecomesJirasDefault() { assertStale(() -> when(world.own.isDefault()).thenReturn(true)); }
    @Test public void staleWhenTheTargetSchemeIsRenamed() { assertStale(() -> world.shared.setName("Renamed shared")); }
    @Test public void staleWhenTheTargetSchemeDescriptionChanges() { assertStale(() -> world.shared.setDescription("other description")); }
    @Test public void staleWhenTheTargetSchemeBecomesJirasDefault() { assertStale(() -> when(world.shared.isDefault()).thenReturn(true)); }
    @Test public void staleWhenTheCurrentSchemeRowsChange() { assertStale(() -> world.own.getEntities().iterator().next().setFieldScreenScheme(world.richSs)); }
    @Test public void staleWhenTheTargetSchemeGetsANewRow() { assertStale(() -> world.row(world.shared, "1", world.leanSs)); }
    @Test public void staleWhenAnIssueTypeIsAddedToTheProject() { assertStale(() -> world.typesByProject.get(104L).add(world.story)); }
    @Test public void staleWhenAnIssueTypeOfTheProjectIsRenamed() { assertStale(() -> when(world.task.getName()).thenReturn("Job")); }
    @Test public void staleWhenAnotherProjectJoinsTheCurrentScheme() { assertStale(() -> world.project(110, "NEWP", world.own, null, world.task)); }
    @Test public void staleWhenAProjectLeavesTheTargetScheme() { assertStale(() -> world.users(20).remove(101L)); }
    @Test public void staleWhenAProjectOfTheTargetIsRenamed() { assertStale(() -> world.projectNames.put(101L, "Renamed alpha")); }
    @Test public void staleWhenAProjectOfTheTargetChangesItsKey() { assertStale(() -> when(world.alpha.getKey()).thenReturn("ALP2")); }
    @Test public void staleWhenAProjectOfTheTargetIsArchived() { assertStale(() -> world.archived.add(101L)); }
    @Test public void staleWhenAScreenOfTheTargetSchemeIsReplaced() { assertStale(() -> world.item(world.richSs, "EDIT").setFieldScreen(world.screens.get(9L))); }
    @Test public void staleWhenAScreenOfTheCurrentSchemeIsReplaced() { assertStale(() -> world.item(world.defaultSs, "DEFAULT").setFieldScreen(world.screens.get(9L))); }
    @Test public void staleWhenATargetScreenSchemeIsRenamed() { assertStale(() -> world.richSs.setName("Renamed rich")); }
    @Test public void staleWhenATargetScreenSchemeLosesAnOperationRow() {
        assertStale(() -> { FieldScreenSchemeItem item = world.item(world.richSs, "VIEW"); world.richSs.getFieldScreenSchemeItems().remove(item); });
    }
    @Test public void staleWhenAScreenIsRenamed() { assertStale(() -> when(world.screens.get(2L).getName()).thenReturn("Renamed screen")); }
    @Test public void staleWhenAScreenOfTheTargetSchemeDisappears() { assertStale(() -> world.item(world.richSs, "EDIT").setFieldScreen(null)); }
    @Test public void staleWhenTheFieldsOfTheNewCreateScreenChange() {
        assertStale("OWN", "Lean ITSS", () -> world.screenFields.get(5L).add("summary"));
    }
    @Test public void staleWhenReplanningFailsAtApply() { assertStale(() -> world.failFieldLayout = true); }

    @Test
    public void aChangedRequiredFlagIsDeliberatelyNotTracked() {
        AssignIssueTypeScreenSchemeResponse preview = service.assign(preview("OWN", "Shared ITSS"));
        world.requiredByLayout.get(11L).put("description", true);
        assertEquals("the flag is not part of the fingerprint", Status.CHANGED, apply(preview).getStatus());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void assertStale(Runnable change) { assertStale("OWN", "Shared ITSS", change); }

    private void assertStale(String project, String target, Runnable change) {
        AssignIssueTypeScreenSchemeResponse preview = service.assign(preview(project, target));
        assertEquals(preview.getMessage(), Status.WOULD_CHANGE, preview.getStatus());
        change.run();
        AssignIssueTypeScreenSchemeResponse result = apply(preview);
        assertEquals(result.getMessage(), ErrorCode.PREVIEW_STALE, result.getErrorCode());
        assertEquals(Status.FAILED, result.getStatus());
        assertTrue("nothing is written", world.writes.isEmpty());
        verify(world.tops, never()).addSchemeAssociation(any(Project.class), any(IssueTypeScreenScheme.class));
    }

    private AssignIssueTypeScreenSchemeResponse apply(AssignIssueTypeScreenSchemeResponse preview) {
        assertNotNull("the preview must carry a plan: " + preview.getMessage(), preview.getPlanId());
        return service.assign(new AssignIssueTypeScreenSchemeRequest(false, null, null, preview.getPlanId()));
    }

    private static AssignIssueTypeScreenSchemeRequest preview(String project, String target) {
        return new AssignIssueTypeScreenSchemeRequest(true, project, target, null);
    }

    private static TypePlan type(AssignIssueTypeScreenSchemeResponse response, String name) {
        for (TypePlan type : response.getIssueTypes()) if (name.equals(type.getIssueType().getName())) return type;
        throw new AssertionError("no issue type " + name + " in " + response.getIssueTypes().size() + " rows");
    }

    private static OperationChange op(TypePlan type, String operation) {
        for (OperationChange change : type.getScreens()) if (operation.equals(change.getOperation())) return change;
        throw new AssertionError("no operation " + operation);
    }

    private static Warning warning(AssignIssueTypeScreenSchemeResponse response, String code) {
        for (Warning warning : response.getWarnings()) if (code.equals(warning.getCode())) return warning;
        return null;
    }
}
