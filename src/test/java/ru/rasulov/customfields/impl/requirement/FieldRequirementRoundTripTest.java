package ru.rasulov.customfields.impl.requirement;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.InMemoryActionPlans;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.api.dto.requirement.PartialEffect;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredRequest;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse;
import ru.rasulov.customfields.impl.JiraMocks;
import ru.rasulov.customfields.impl.configuration.ConfigurableFieldResolver;
import ru.rasulov.customfields.impl.configuration.FieldLayoutCopyFactory;
import ru.rasulov.customfields.impl.context.FieldSelector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * set_field_required against a stateful stand-in for Jira's layout storage: rows are written on store and every read
 * rebuilds the layout from them in the opposite order (Jira does not order the rows it reloads). Covers the verifier
 * and the compensation of a split that fails after its copy was created.
 */
public class FieldRequirementRoundTripTest {
    private static final String TARGET = "customfield_10100";

    private World world;
    private FieldRequirementServiceImpl service;

    @After
    public void closeUndoDatabases() { JiraMocks.closeJournals(); }

    @Before
    public void setUp() {
        world = new World();
        FieldManager systemFields = mock(FieldManager.class);
        CustomFieldManager customFields = mock(CustomFieldManager.class);
        when(customFields.getCustomFieldObject(anyString())).thenAnswer(call -> {
            OrderableField resolved = world.orderable(call.getArgument(0));
            return resolved instanceof CustomField ? resolved : null;
        });
        when(customFields.getCustomFieldObjectsByNameIgnoreCase("Severity")).thenReturn(Collections.singletonList(world.target));
        ProjectResolver projects = mock(ProjectResolver.class);
        when(projects.resolve("PAY")).thenReturn(world.project);
        IssueTypeResolver issueTypes = mock(IssueTypeResolver.class);
        when(issueTypes.resolve("Task")).thenReturn(world.task);
        when(issueTypes.resolve("Bug")).thenReturn(world.bug);

        JiraAuthenticationContext authentication = mock(JiraAuthenticationContext.class);
        GlobalPermissionManager permissions = mock(GlobalPermissionManager.class);
        ApplicationUser admin = mock(ApplicationUser.class);
        when(authentication.getLoggedInUser()).thenReturn(admin);
        when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
        when(admin.getKey()).thenReturn("admin-key");

        service = new FieldRequirementServiceImpl(new AdministratorGuard(authentication, permissions),
                new NamedLock(JiraMocks.lockService(mock(ClusterLock.class))), projects, issueTypes,
                new ConfigurableFieldResolver(new FieldSelector(customFields), systemFields), world.manager,
                new RequirementScreens().manager,
                new ru.rasulov.common.impl.PreviewPlanExecutor(InMemoryActionPlans.create(), new ActionPlanCodec()), new ActionPlanCodec(),
                ru.rasulov.customfields.impl.FieldJournalTestSupport.journal("admin-key"), world.copies);
    }

    @Test
    public void threeFieldsForOneTypeUseOneCopyAndPreserveTheOtherTypeAndAttributes() {
        world.row(10L, "customfield_10101").required = false;
        SetFieldRequiredResponse preview = batchPreview();
        assertEquals(3, preview.getFields().size());
        assertEquals(3, preview.getTargets().get(0).getFields().size());
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals(2, world.rows.size());
        assertEquals(51L, world.nextId);
        assertEquals(Collections.singletonMap("10001", 50L), world.explicit);
        assertEquals(Long.valueOf(10L), world.effectiveId("10002"));
        for (String id : batchFields()) {
            assertTrue(world.row(50L, id).required);
            assertFalse(world.row(10L, id).required);
        }
        for (Row original : world.rows.get(10L)) {
            Row copy = world.row(50L, original.fieldId);
            assertEquals(original.hidden, copy.hidden);
            assertEquals(original.description, copy.description);
            assertEquals(original.renderer, copy.renderer);
            if (!batchFields().contains(original.fieldId)) assertEquals(original.required, copy.required);
        }
    }

    @Test
    public void mixedAlreadyRequiredFieldsKeepTheirOwnStatuses() {
        SetFieldRequiredResponse preview = batchPreview();
        assertEquals(SetFieldRequiredResponse.TargetStatus.WOULD_MAKE_REQUIRED,
                preview.getTargets().get(0).getFields().get(0).getStatus());
        assertEquals(SetFieldRequiredResponse.TargetStatus.ALREADY_REQUIRED,
                preview.getTargets().get(0).getFields().get(1).getStatus());
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.TargetStatus.MADE_REQUIRED,
                applied.getTargets().get(0).getFields().get(0).getStatus());
        assertEquals(SetFieldRequiredResponse.TargetStatus.ALREADY_REQUIRED,
                applied.getTargets().get(0).getFields().get(1).getStatus());
        assertTrue(world.row(10L, "customfield_10101").required);
        assertEquals(2, world.rows.size());
    }

    @Test
    public void alreadyRequiredBatchHasPerFieldStatusesAndNeedsNoApplyOrCopy() {
        for (String id : batchFields()) world.row(10L, id).required = true;
        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", batchFields(), null, Collections.singletonList("Task")));
        assertEquals(SetFieldRequiredResponse.Status.ALREADY_AS_REQUESTED, preview.getStatus());
        assertNull(preview.getPlanId());
        for (SetFieldRequiredResponse.FieldStatus field : preview.getTargets().get(0).getFields()) {
            assertEquals(SetFieldRequiredResponse.TargetStatus.ALREADY_REQUIRED, field.getStatus());
        }
        assertEquals(1, world.rows.size());
        assertEquals(50L, world.nextId);
        assertTrue(world.explicit.isEmpty());
    }

    @Test
    public void threeOptionalFieldsUseOneCopyAndLeaveTheOriginalRequired() {
        for (String id : batchFields()) world.row(10L, id).required = true;
        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", batchFields(), false, null, Collections.singletonList("Task")));
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals(2, world.rows.size());
        for (String id : batchFields()) {
            assertFalse(world.row(50L, id).required);
            assertTrue(world.row(10L, id).required);
        }
        assertEquals(Long.valueOf(10L), world.effectiveId("10002"));
    }

    @Test
    public void changingAnUntargetedLayoutAttributeMakesTheWholeBatchStaleBeforeCopy() {
        SetFieldRequiredResponse preview = batchPreview();
        world.row(10L, "priority").description = "Edited by another administrator";
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.ErrorCode.PREVIEW_STALE, applied.getErrorCode());
        assertEquals(1, world.rows.size());
        assertEquals(50L, world.nextId);
        assertTrue(world.explicit.isEmpty());
        assertFalse(world.row(10L, TARGET).required);
    }

    @Test
    public void changingAnAlreadyRequiredFieldsContextWithoutChangingApplicabilityMakesTheBatchStale() {
        CustomField alreadyRequired = (CustomField) world.orderable("customfield_10101");
        com.atlassian.jira.issue.fields.config.FieldConfigScheme context =
                mock(com.atlassian.jira.issue.fields.config.FieldConfigScheme.class);
        when(context.getId()).thenReturn(200L);
        when(context.getName()).thenReturn("Selected project");
        when(context.isAllIssueTypes()).thenReturn(true);
        List<Long> contextProjects = new ArrayList<Long>(Collections.singletonList(1L));
        when(context.getAssociatedProjectIds()).thenAnswer(call -> contextProjects);
        when(alreadyRequired.getConfigurationSchemes()).thenReturn(Collections.singletonList(context));
        SetFieldRequiredResponse preview = batchPreview();
        assertEquals(SetFieldRequiredResponse.TargetStatus.ALREADY_REQUIRED,
                preview.getTargets().get(0).getFields().get(1).getStatus());

        contextProjects.add(2L);
        assertTrue(alreadyRequired.isInScope(1L, "10001"));
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.ErrorCode.PREVIEW_STALE, applied.getErrorCode());
        assertEquals(1, world.rows.size());
        assertEquals(50L, world.nextId);
        assertTrue(world.explicit.isEmpty());
        assertFalse(world.row(10L, TARGET).required);
    }

    @Test
    public void changingAnExplicitSchemeRowToTheSameEffectiveLayoutStillMakesTheBatchStale() {
        SetFieldRequiredResponse preview = batchPreview();
        world.explicit.put("10001", 10L);
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.ErrorCode.PREVIEW_STALE, applied.getErrorCode());
        assertEquals(50L, world.nextId);
        assertEquals(1, world.rows.size());
    }

    @Test
    public void secondFieldFailureReportsAppliedUnknownAndNotStartedWithoutACopy() {
        failSecondFieldStore();
        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", batchFields(), true, null));
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.Status.PARTIALLY_CHANGED, applied.getStatus());
        PartialEffect effect = applied.getPartialEffect();
        assertEquals(1, effect.getApplied().size());
        assertFlag(effect.getApplied().get(0), TARGET, PartialEffect.State.APPLIED);
        assertEquals(1, effect.getLeftBehind().size());
        assertFlag(effect.getLeftBehind().get(0), "customfield_10101", PartialEffect.State.UNKNOWN);
        assertEquals(1, effect.getNotStarted().size());
        assertFlag(effect.getNotStarted().get(0), "customfield_10102", PartialEffect.State.NOT_STARTED);
        assertFalse(world.row(10L, "customfield_10102").required);
        assertEquals(50L, world.nextId);
    }

    @Test
    public void aLayoutReadFailureBeforeAnyWriteIsNotReportedAsAPossibleChange() {
        world.row(10L, "customfield_10101").required = false;
        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", batchFields(), true, null));
        world.editableReadBudget = 0;
        try {
            applyPlan(preview);
            org.junit.Assert.fail("nothing was attempted: there is nothing to report, so Jira's own failure propagates");
        } catch (IllegalStateException expected) {
            assertEquals("layout unreadable", expected.getMessage());
        }
        assertFalse("nothing was written", world.row(10L, TARGET).required);
    }

    @Test
    public void aLayoutReadFailureAfterAConfirmedWriteKeepsThePrefixAndSaysTheRestWasNotStarted() {
        world.row(10L, "customfield_10101").required = false;
        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", batchFields(), true, null));
        // the first flag: one read before the store, one for its readback; the second flag's read then fails
        world.editableReadBudget = 2;
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.Status.PARTIALLY_CHANGED, applied.getStatus());
        PartialEffect effect = applied.getPartialEffect();
        assertEquals(1, effect.getApplied().size());
        assertFlag(effect.getApplied().get(0), TARGET, PartialEffect.State.APPLIED);
        assertTrue("a read failure before any write is not an unknown write", effect.getLeftBehind().isEmpty());
        assertEquals(2, effect.getNotStarted().size());
        assertFlag(effect.getNotStarted().get(0), "customfield_10101", PartialEffect.State.NOT_STARTED);
        assertFalse(world.row(10L, "customfield_10101").required);
    }

    @Test
    public void firstWriteFailureWithoutACopyKeepsTheUnconfirmedFlagAsUnknownInsteadOfEscaping() {
        world.row(10L, "customfield_10101").required = false;
        // Jira stores the first flag and then the call fails: the caller cannot know the flag was written.
        world.afterStore = rows -> { throw new IllegalStateException("answer lost after the store"); };
        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", batchFields(), true, null));
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.Status.PARTIALLY_CHANGED, applied.getStatus());
        PartialEffect effect = applied.getPartialEffect();
        assertTrue(effect.getApplied().isEmpty());
        assertEquals(1, effect.getLeftBehind().size());
        assertFlag(effect.getLeftBehind().get(0), TARGET, PartialEffect.State.UNKNOWN);
        assertEquals(2, effect.getNotStarted().size());
        assertTrue("the write really persisted", world.row(10L, TARGET).required);
    }

    @Test
    public void secondFieldFailureDeletesOnlyTheOwnedCopyAndReportsCompensatedFlags() {
        failSecondFieldStore();
        SetFieldRequiredResponse applied = applyPlan(batchPreview());
        assertEquals(SetFieldRequiredResponse.Status.FAILED, applied.getStatus());
        assertNull(world.rows.get(50L));
        assertTrue(world.explicit.isEmpty());
        PartialEffect effect = applied.getPartialEffect();
        assertTrue(effect.getApplied().isEmpty());
        assertTrue(effect.getLeftBehind().isEmpty());
        assertEquals(3, effect.getCompensated().size());
        assertEquals(PartialEffect.Kind.FIELD_CONFIGURATION_COPY, effect.getCompensated().get(0).getKind());
        assertFlag(effect.getCompensated().get(1), TARGET, PartialEffect.State.COMPENSATED);
        assertFlag(effect.getCompensated().get(2), "customfield_10101", PartialEffect.State.COMPENSATED);
        assertEquals(1, effect.getNotStarted().size());
        assertFlag(effect.getNotStarted().get(0), "customfield_10102", PartialEffect.State.NOT_STARTED);
        for (String id : batchFields()) assertFalse(world.row(10L, id).required);
    }

    @Test
    public void secondFieldFailureWithFailedCopyDeletionRetainsPerFieldProgress() {
        failSecondFieldStore();
        world.failDelete = true;
        SetFieldRequiredResponse applied = applyPlan(batchPreview());
        PartialEffect effect = applied.getPartialEffect();
        assertEquals(SetFieldRequiredResponse.Status.PARTIALLY_CHANGED, applied.getStatus());
        assertEquals(1, effect.getApplied().size());
        assertFlag(effect.getApplied().get(0), TARGET, PartialEffect.State.APPLIED);
        assertEquals(2, effect.getLeftBehind().size());
        assertFlag(effect.getLeftBehind().get(0), "customfield_10101", PartialEffect.State.UNKNOWN);
        assertEquals(PartialEffect.Kind.FIELD_CONFIGURATION_COPY, effect.getLeftBehind().get(1).getKind());
        assertEquals(1, effect.getNotStarted().size());
        assertFlag(effect.getNotStarted().get(0), "customfield_10102", PartialEffect.State.NOT_STARTED);
        assertNotNull(world.rows.get(50L));
        assertTrue(world.explicit.isEmpty());
    }

    @Test
    public void theMaximumFieldBatchIsAccepted() {
        List<String> ids = new ArrayList<String>(batchFields());
        for (int i = ids.size(); i < ru.rasulov.customfields.api.Limits.MAX_FIELDS_PER_REQUIREMENT_CALL; i++) {
            String id = "customfield_" + (10200 + i);
            ids.add(id);
            world.rows.get(10L).add(new Row(id, false, false, "help", null));
        }
        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", ids, null, Collections.singletonList("Task")));
        assertEquals(SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, preview.getStatus());
        assertEquals(ru.rasulov.customfields.api.Limits.MAX_FIELDS_PER_REQUIREMENT_CALL, preview.getFields().size());
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals(2, world.rows.size());
        for (String id : ids) assertTrue(world.row(50L, id).required);
    }

    private void failSecondFieldStore() {
        world.row(10L, "customfield_10101").required = false;
        java.util.concurrent.atomic.AtomicInteger stores = new java.util.concurrent.atomic.AtomicInteger();
        world.afterStore = rows -> { if (stores.incrementAndGet() == 2) throw new IllegalStateException("second write"); };
    }

    private static void assertFlag(PartialEffect.Item item, String fieldId, PartialEffect.State state) {
        assertEquals(PartialEffect.Kind.REQUIRED_FLAG, item.getKind());
        assertEquals(fieldId, item.getFieldId());
        assertEquals(state, item.getState());
        assertNotNull(item.getId());
        assertNotNull(item.getName());
    }

    private SetFieldRequiredResponse batchPreview() {
        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", batchFields(), null, Collections.singletonList("Task")));
        assertEquals(preview.getMessage(), SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, preview.getStatus());
        assertNotNull(preview.getPlanId());
        return preview;
    }

    private static List<String> batchFields() {
        return Arrays.asList(TARGET, "customfield_10101", "customfield_10102");
    }

    private static SetFieldRequiredRequest.Row row(boolean required, String type, String... fields) {
        return new SetFieldRequiredRequest.Row(Arrays.asList(fields), required, null, Collections.singletonList(type));
    }

    private SetFieldRequiredResponse rowsPreview(SetFieldRequiredRequest.Row... rows) {
        return service.setRequired(new SetFieldRequiredRequest(true, "PAY", Arrays.asList(rows)));
    }

    @Test
    public void rowsSetDifferentFlagsPerTypeInOnePlanWithOneCopyPerOutcome() {
        SetFieldRequiredResponse preview = rowsPreview(row(true, "Task", TARGET), row(true, "Bug", "customfield_10102"));
        assertEquals(preview.getMessage(), SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, preview.getStatus());
        assertNotNull(preview.getPlanId());
        assertEquals(2, preview.getTargets().size());
        assertEquals(50L, world.nextId);

        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals(3, world.rows.size());
        Map<String, Long> expectedRows = new LinkedHashMap<String, Long>();
        expectedRows.put("10001", 50L);
        expectedRows.put("10002", 51L);
        assertEquals(expectedRows, world.explicit);
        assertTrue(world.row(50L, TARGET).required);
        assertFalse(world.row(50L, "customfield_10102").required);
        assertFalse(world.row(51L, TARGET).required);
        assertTrue(world.row(51L, "customfield_10102").required);
        assertTrue("Summary keeps its flag", world.row(50L, "summary").required && world.row(51L, "summary").required);
        assertFalse("the shared original is untouched", world.row(10L, TARGET).required);
        assertFalse(world.row(10L, "customfield_10102").required);
    }

    @Test
    public void rowsWithTheSameOutcomeEditTheSharedLayoutInPlaceWithoutCopies() {
        SetFieldRequiredResponse preview = rowsPreview(row(true, "Task", TARGET), row(true, "Bug", TARGET));
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals(1, world.rows.size());
        assertEquals(50L, world.nextId);
        assertTrue(world.explicit.isEmpty());
        assertTrue(world.row(10L, TARGET).required);
    }

    @Test
    public void rowsMixRequiredAndOptionalAndAnAlreadySatisfiedTypeKeepsTheOriginal() {
        SetFieldRequiredResponse preview = rowsPreview(row(false, "Task", "customfield_10101"),
                row(true, "Bug", "customfield_10101"));
        assertEquals(SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, preview.getStatus());
        SetFieldRequiredResponse applied = applyPlan(preview);
        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals(2, world.rows.size());
        assertEquals(Collections.singletonMap("10001", 50L), world.explicit);
        assertFalse(world.row(50L, "customfield_10101").required);
        assertTrue("Bug already satisfied: keeps the original", world.row(10L, "customfield_10101").required);
        assertEquals(Long.valueOf(10L), world.effectiveId("10002"));
    }

    @Test
    public void overlappingOrContradictingRowsAreRefusedWithoutAPlanOrAnyWrite() {
        SetFieldRequiredResponse preview = service.setRequired(new SetFieldRequiredRequest(true, "PAY", Arrays.asList(
                row(true, "Task", TARGET),
                new SetFieldRequiredRequest.Row(Collections.singletonList(TARGET), false, null,
                        Arrays.asList("Task", "Bug")))));
        assertEquals(SetFieldRequiredResponse.Status.FAILED, preview.getStatus());
        assertEquals(SetFieldRequiredResponse.ErrorCode.ROW_CONFLICT, preview.getErrorCode());
        assertEquals(1, preview.getFieldErrors().size());
        assertEquals(TARGET, preview.getFieldErrors().get(0).getSelector());
        assertTrue(preview.getFieldErrors().get(0).getMessage().contains("contradict"));
        assertNull(preview.getPlanId());
        assertEquals(1, world.rows.size());
        assertEquals(50L, world.nextId);
    }

    @Test
    public void rowsAreRequiredAndEveryRowNeedsAnExplicitFlag() {
        SetFieldRequiredResponse combined = service.setRequired(new SetFieldRequiredRequest(true, "PAY",
                Collections.<SetFieldRequiredRequest.Row>emptyList()));
        assertEquals(SetFieldRequiredResponse.ErrorCode.VALIDATION_ERROR, combined.getErrorCode());
        SetFieldRequiredResponse noFlag = service.setRequired(new SetFieldRequiredRequest(true, "PAY",
                Collections.singletonList(new SetFieldRequiredRequest.Row(Collections.singletonList(TARGET), null, null,
                        Collections.singletonList("Task")))));
        assertEquals(SetFieldRequiredResponse.ErrorCode.VALIDATION_ERROR, noFlag.getErrorCode());
        assertNull(combined.getPlanId());
        assertNull(noFlag.getPlanId());
    }

    @Test
    public void rowFieldErrorsAreCollectedAcrossRows() {
        SetFieldRequiredResponse preview = rowsPreview(row(true, "Task", TARGET, "NoSuchField"),
                row(true, "Bug", "customfield_10102", "NoOtherField"));
        assertEquals(SetFieldRequiredResponse.Status.FAILED, preview.getStatus());
        assertEquals(2, preview.getFieldErrors().size());
        assertEquals("NoSuchField", preview.getFieldErrors().get(0).getSelector());
        assertEquals("NoOtherField", preview.getFieldErrors().get(1).getSelector());
        assertNull(preview.getPlanId());
        assertEquals(50L, world.nextId);
    }

    @Test
    public void secondCopyFailureKeepsTheFirstOutcomeAndCompensatesOnlyTheSecondCopy() {
        java.util.concurrent.atomic.AtomicInteger stores = new java.util.concurrent.atomic.AtomicInteger();
        world.afterStore = rows -> { if (stores.incrementAndGet() == 2) throw new IllegalStateException("second copy flag"); };
        SetFieldRequiredResponse preview = rowsPreview(row(true, "Task", TARGET), row(true, "Bug", "customfield_10102"));
        SetFieldRequiredResponse applied = applyPlan(preview);

        assertEquals(SetFieldRequiredResponse.Status.PARTIALLY_CHANGED, applied.getStatus());
        PartialEffect effect = applied.getPartialEffect();
        List<PartialEffect.Kind> appliedKinds = new ArrayList<PartialEffect.Kind>();
        for (PartialEffect.Item item : effect.getApplied()) appliedKinds.add(item.getKind());
        assertTrue(appliedKinds.contains(PartialEffect.Kind.FIELD_CONFIGURATION_COPY));
        assertTrue(appliedKinds.contains(PartialEffect.Kind.REQUIRED_FLAG));
        assertTrue(appliedKinds.contains(PartialEffect.Kind.ISSUE_TYPE_MAPPING));
        boolean secondCopyCompensated = false;
        for (PartialEffect.Item item : effect.getCompensated()) {
            secondCopyCompensated |= item.getKind() == PartialEffect.Kind.FIELD_CONFIGURATION_COPY
                    && "51".equals(item.getId());
        }
        assertTrue(secondCopyCompensated);
        assertEquals(Collections.singletonMap("10001", 50L), world.explicit);
        assertTrue(world.row(50L, TARGET).required);
        assertNull(world.rows.get(51L));
        assertEquals(Long.valueOf(10L), world.effectiveId("10002"));
    }

    /** Ids of the items of one kind, in list order. */
    private static List<String> ids(List<PartialEffect.Item> items, PartialEffect.Kind kind) {
        List<String> result = new ArrayList<String>();
        for (PartialEffect.Item item : items) if (item.getKind() == kind) result.add(item.getId());
        return result;
    }

    @Test
    public void failureAfterTheSecondCopysMappingWasWrittenRestoresItAndDeletesOnlyThatCopy() {
        world.failAfterNthAssignment = 2;
        SetFieldRequiredResponse applied = applyPlan(
                rowsPreview(row(true, "Task", TARGET), row(true, "Bug", "customfield_10102")));

        // real state: Task stays on its finished copy 50; Bug's row written for copy 51 was restored, copy 51 deleted
        assertEquals(Collections.singletonMap("10001", 50L), world.explicit);
        assertEquals(Long.valueOf(50L), world.effectiveId("10001"));
        assertEquals(Long.valueOf(10L), world.effectiveId("10002"));
        assertNull(world.rows.get(51L));
        assertTrue(world.row(50L, TARGET).required);
        assertFalse(world.row(10L, TARGET).required);
        assertFalse(world.row(10L, "customfield_10102").required);

        // the reported effect is exactly that state
        assertEquals(SetFieldRequiredResponse.Status.PARTIALLY_CHANGED, applied.getStatus());
        PartialEffect effect = applied.getPartialEffect();
        assertEquals(Collections.singletonList("50"), ids(effect.getApplied(), PartialEffect.Kind.FIELD_CONFIGURATION_COPY));
        assertEquals(Collections.singletonList("50"), ids(effect.getApplied(), PartialEffect.Kind.REQUIRED_FLAG));
        assertEquals(Collections.singletonList("10001"), ids(effect.getApplied(), PartialEffect.Kind.ISSUE_TYPE_MAPPING));
        assertEquals(Collections.singletonList("51"), ids(effect.getCompensated(), PartialEffect.Kind.FIELD_CONFIGURATION_COPY));
        assertEquals(Collections.singletonList("10002"), ids(effect.getCompensated(), PartialEffect.Kind.ISSUE_TYPE_MAPPING));
        assertEquals(Collections.singletonList("51"), ids(effect.getCompensated(), PartialEffect.Kind.REQUIRED_FLAG));
        assertTrue(effect.getLeftBehind().isEmpty());
        assertTrue(effect.getNotStarted().isEmpty());
        assertTrue(applied.getMessage(), applied.getMessage().contains("failed after the row was written"));
    }

    @Test
    public void failureAfterTheSecondCopysMappingWithARefusedRestoreReportsTheStillAssignedCopyHonestly() {
        world.failAfterNthAssignment = 2;
        world.failRestore = true;
        SetFieldRequiredResponse applied = applyPlan(
                rowsPreview(row(true, "Task", TARGET), row(true, "Bug", "customfield_10102")));

        // real state: both rows and both copies still exist, Bug still points at copy 51
        Map<String, Long> expected = new LinkedHashMap<String, Long>();
        expected.put("10001", 50L);
        expected.put("10002", 51L);
        assertEquals(expected, world.explicit);
        assertNotNull("a copy that is still assigned is never deleted", world.rows.get(51L));
        assertTrue(world.row(51L, "customfield_10102").required);

        PartialEffect effect = applied.getPartialEffect();
        assertEquals(SetFieldRequiredResponse.Status.PARTIALLY_CHANGED, applied.getStatus());
        assertEquals(Collections.singletonList("10001"), ids(effect.getApplied(), PartialEffect.Kind.ISSUE_TYPE_MAPPING));
        assertEquals(Collections.singletonList("10002"), ids(effect.getLeftBehind(), PartialEffect.Kind.ISSUE_TYPE_MAPPING));
        assertEquals(PartialEffect.State.UNKNOWN, onlyLeftBehind(applied, PartialEffect.Kind.ISSUE_TYPE_MAPPING).getState());
        PartialEffect.Item copy = onlyLeftBehind(applied, PartialEffect.Kind.FIELD_CONFIGURATION_COPY);
        assertEquals("51", copy.getId());
        assertEquals(PartialEffect.State.LEFT_BEHIND, copy.getState());
        assertTrue(copy.getDetail(), copy.getDetail().contains("still assigned"));
        assertTrue("the first copy is complete and untouched", ids(effect.getApplied(),
                PartialEffect.Kind.FIELD_CONFIGURATION_COPY).contains("50"));
        assertTrue(effect.getCompensated().isEmpty());
    }

    @Test
    public void reloadInAnotherOrderIsNotAForeignChange() {
        SetFieldRequiredResponse applied = applyTaskRequired();

        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertNull(applied.getPartialEffect());
        Long copyId = applied.getTargets().get(0).getNewFieldConfigurationId();
        assertNotNull(copyId);
        assertEquals(copyId, world.effectiveId("10001"));
        assertEquals("Bug keeps the original configuration", Long.valueOf(10L), world.effectiveId("10002"));
        assertTrue(world.row(copyId, TARGET).required);
        assertFalse(world.row(10L, TARGET).required);
        for (Row original : world.rows.get(10L)) {
            Row copy = world.row(copyId, original.fieldId);
            assertEquals(original.hidden, copy.hidden);
            assertEquals(original.description, copy.description);
            assertEquals(original.renderer, copy.renderer);
            if (!original.fieldId.equals(TARGET)) assertEquals(original.required, copy.required);
        }
    }

    @Test
    public void aRealChangeToAnotherFieldIsStillRefusedAndTheCopyIsRemoved() {
        world.afterStore = rows -> world.row(rows, "customfield_10101").renderer = "wiki-style-renderer";

        SetFieldRequiredResponse applied = applyTaskRequired();

        assertEquals(SetFieldRequiredResponse.Status.FAILED, applied.getStatus());
        assertTrue(applied.getMessage(), applied.getMessage().contains("changed beyond the requested required flag"));
        assertTrue("names the field and the attribute, not its value", applied.getMessage().contains("customfield_10101 renderer"));
        assertEquals(2, applied.getPartialEffect().getCompensated().size());
        assertEquals(PartialEffect.Kind.REQUIRED_FLAG, applied.getPartialEffect().getCompensated().get(1).getKind());
        assertEquals(TARGET, applied.getPartialEffect().getCompensated().get(1).getFieldId());
        assertNull("copy is gone", world.rows.get(50L));
        assertEquals(Long.valueOf(10L), world.effectiveId("10001"));
    }

    @Test
    public void failureAfterTheCopyWasCreatedDeletesItAndKeepsTheOriginalMapping() {
        world.explicit.clear();
        world.failAssignment = true;

        SetFieldRequiredResponse applied = applyTaskRequired();

        assertEquals(SetFieldRequiredResponse.Status.FAILED, applied.getStatus());
        PartialEffect effect = applied.getPartialEffect();
        assertTrue(effect.getApplied().isEmpty());
        assertTrue(effect.getLeftBehind().isEmpty());
        assertEquals(2, effect.getCompensated().size());
        assertEquals(PartialEffect.Kind.REQUIRED_FLAG, effect.getCompensated().get(1).getKind());
        assertEquals(TARGET, effect.getCompensated().get(1).getFieldId());
        assertEquals(PartialEffect.Kind.FIELD_CONFIGURATION_COPY, effect.getCompensated().get(0).getKind());
        assertEquals("50", effect.getCompensated().get(0).getId());
        assertEquals(PartialEffect.State.COMPENSATED, effect.getCompensated().get(0).getState());
        assertNull(world.rows.get(50L));
        assertTrue(world.explicit.isEmpty());
        assertEquals(Long.valueOf(10L), world.effectiveId("10001"));
        assertFalse("no claim that nothing was written", applied.getMessage().contains("nothing was changed"));
    }

    @Test
    public void failedVerificationAfterAssignmentRestoresTheMappingAndDeletesTheCopy() {
        world.explicit.put("10001", 10L);
        world.explicit.put("10002", 10L);
        world.assignmentsInvisible = true;

        SetFieldRequiredResponse applied = applyTaskRequired();

        PartialEffect effect = applied.getPartialEffect();
        assertEquals(SetFieldRequiredResponse.Status.FAILED, applied.getStatus());
        assertEquals(Long.valueOf(10L), world.explicit.get("10001"));
        assertEquals(Long.valueOf(10L), world.explicit.get("10002"));
        assertNull(world.rows.get(50L));
        assertEquals(Arrays.asList(PartialEffect.Kind.ISSUE_TYPE_MAPPING, PartialEffect.Kind.FIELD_CONFIGURATION_COPY),
                Arrays.asList(effect.getCompensated().get(0).getKind(), effect.getCompensated().get(1).getKind()));
        assertTrue(effect.getLeftBehind().isEmpty());
    }

    @Test
    public void failedCompensationListsTheLeftBehindCopyWithIdAndName() {
        world.explicit.clear();
        world.failAssignment = true;
        world.failDelete = true;

        SetFieldRequiredResponse applied = applyTaskRequired();

        assertEquals(SetFieldRequiredResponse.Status.PARTIALLY_CHANGED, applied.getStatus());
        PartialEffect effect = applied.getPartialEffect();
        assertTrue(effect.getCompensated().isEmpty());
        assertEquals(1, effect.getLeftBehind().size());
        PartialEffect.Item left = effect.getLeftBehind().get(0);
        assertEquals("50", left.getId());
        assertEquals("PAY: Shared fields (Task)", left.getName());
        assertEquals(PartialEffect.State.UNKNOWN, left.getState());
        assertTrue(applied.getMessage(), applied.getMessage().contains("id 50"));
        assertNotNull(world.rows.get(50L));
    }

    @Test
    public void aCopyStillAssignedIsNeverDeleted() {
        world.explicit.clear();
        world.failAfterAssignment = true;
        world.failRestore = true;

        SetFieldRequiredResponse applied = applyTaskRequired();

        PartialEffect effect = applied.getPartialEffect();
        assertNotNull("the copy is still referenced by the scheme", world.rows.get(50L));
        boolean copyListed = false, mappingListed = false;
        for (PartialEffect.Item item : effect.getLeftBehind()) {
            if (item.getKind() == PartialEffect.Kind.FIELD_CONFIGURATION_COPY) {
                copyListed = true;
                assertEquals(PartialEffect.State.LEFT_BEHIND, item.getState());
            }
            if (item.getKind() == PartialEffect.Kind.ISSUE_TYPE_MAPPING) mappingListed = true;
        }
        assertTrue(copyListed);
        assertTrue(mappingListed);
    }

    @Test
    public void aSchemeRowRepointedByAnotherAdminDuringTheOperationIsNotRestored() {
        world.addLayout(11L, "Someone else's configuration");
        world.explicit.put("10001", 10L);
        world.explicit.put("10002", 10L);
        world.afterAssign = () -> world.explicit.put("10001", 11L);

        SetFieldRequiredResponse applied = applyTaskRequired();

        assertEquals("the foreign mapping is untouched", Long.valueOf(11L), world.explicit.get("10001"));
        assertEquals(Long.valueOf(10L), world.explicit.get("10002"));
        assertEquals(SetFieldRequiredResponse.Status.PARTIALLY_CHANGED, applied.getStatus());
        PartialEffect.Item mapping = onlyLeftBehind(applied, PartialEffect.Kind.ISSUE_TYPE_MAPPING);
        assertEquals("10001", mapping.getId());
        assertEquals(PartialEffect.State.UNKNOWN, mapping.getState());
        assertEquals("changed by someone else during the operation; not restored", mapping.getDetail());
        assertNull("nothing refers to the copy, so it is deleted", world.rows.get(50L));
    }

    @Test
    public void aForeignRowThatAppearedWhereNoneExistedIsNotRemoved() {
        world.addLayout(11L, "Someone else's configuration");
        world.explicit.clear();
        world.failAfterAssignment = true;
        world.afterAssign = () -> world.explicit.put("10001", 11L);

        SetFieldRequiredResponse applied = applyTaskRequired();

        assertEquals("the foreign row is untouched", Long.valueOf(11L), world.explicit.get("10001"));
        PartialEffect.Item mapping = onlyLeftBehind(applied, PartialEffect.Kind.ISSUE_TYPE_MAPPING);
        assertEquals(PartialEffect.State.UNKNOWN, mapping.getState());
        assertTrue(mapping.getDetail().contains("someone else"));
    }

    @Test
    public void aConfigurationFoundOnlyByNameAfterAnUnconfirmedCreateIsReportedNeverDeleted() {
        world.explicit.clear();
        world.failStoreAfterCreate = true;

        SetFieldRequiredResponse applied = applyTaskRequired();

        assertEquals(SetFieldRequiredResponse.Status.PARTIALLY_CHANGED, applied.getStatus());
        PartialEffect.Item copy = onlyLeftBehind(applied, PartialEffect.Kind.FIELD_CONFIGURATION_COPY);
        assertEquals("50", copy.getId());
        assertEquals("PAY: Shared fields (Task)", copy.getName());
        assertEquals(PartialEffect.State.UNKNOWN, copy.getState());
        assertEquals("found by name after an unconfirmed create; verify and delete manually", copy.getDetail());
        assertNotNull("never deleted on a name match alone", world.rows.get(50L));
        assertTrue(applied.getPartialEffect().getCompensated().isEmpty());
    }

    private static PartialEffect.Item onlyLeftBehind(SetFieldRequiredResponse response, PartialEffect.Kind kind) {
        PartialEffect.Item found = null;
        for (PartialEffect.Item item : response.getPartialEffect().getLeftBehind()) {
            if (item.getKind() != kind) continue;
            assertNull("one item of kind " + kind, found);
            found = item;
        }
        assertNotNull("leftBehind has " + kind, found);
        return found;
    }

    @Test
    public void previewAfterAFailedApplyDoesNotInventAVersionedName() {
        world.explicit.clear();
        world.failAssignment = true;
        SetFieldRequiredResponse first = service.setRequired(previewRequest());
        String firstName = first.getTargets().get(0).getNewFieldConfigurationName();
        applyPlan(first);

        SetFieldRequiredResponse second = service.setRequired(previewRequest());

        assertEquals(firstName, second.getTargets().get(0).getNewFieldConfigurationName());
        assertFalse(firstName.contains(" v1"));
    }

    // ---- helpers ----

    private static SetFieldRequiredRequest previewRequest() {
        return RequirementRequests.oneRow(true, "PAY", Collections.singletonList("Severity"), null, Collections.singletonList("Task"));
    }

    private SetFieldRequiredResponse applyTaskRequired() {
        SetFieldRequiredResponse preview = service.setRequired(previewRequest());
        assertEquals(preview.getMessage(), SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, preview.getStatus());
        return applyPlan(preview);
    }

    private SetFieldRequiredResponse applyPlan(SetFieldRequiredResponse preview) {
        return service.setRequired(RequirementRequests.apply(preview.getPlanId()));
    }

    private static final class Row {
        final String fieldId;
        boolean required, hidden;
        String description, renderer;

        Row(String fieldId, boolean required, boolean hidden, String description, String renderer) {
            this.fieldId = fieldId; this.required = required; this.hidden = hidden;
            this.description = description; this.renderer = renderer;
        }

        Row copy() { return new Row(fieldId, required, hidden, description, renderer); }
    }

    /** Stateful layout storage; the project has Task and Bug on layout 10 inside private scheme 20. */
    private static final class World {
        final Project project = mock(Project.class);
        final IssueType task = issueType("10001", "Task");
        final IssueType bug = issueType("10002", "Bug");
        final CustomField target = mock(CustomField.class);
        final FieldLayoutManager manager = mock(FieldLayoutManager.class);
        final FieldLayoutCopyFactory copies = mock(FieldLayoutCopyFactory.class);
        final Map<Long, String> names = new LinkedHashMap<Long, String>();
        final Map<Long, List<Row>> rows = new LinkedHashMap<Long, List<Row>>();
        final Map<String, Long> explicit = new LinkedHashMap<String, Long>();
        final Map<String, OrderableField> fieldObjects = new LinkedHashMap<String, OrderableField>();
        long nextId = 50;
        boolean failAssignment, failAfterAssignment, failRestore, failDelete, assignmentsInvisible, failStoreAfterCreate;
        /** The Nth scheme row written fails after it was stored (0 = never); counts every created row. */
        int failAfterNthAssignment;
        /** Editable-layout reads the writer may still make before they fail (-1 = unlimited, 0 = fail the next one). */
        int editableReadBudget = -1;
        private int assignments;
        Consumer<List<Row>> afterStore = rows -> { };
        Runnable afterAssign = () -> { };

        void addLayout(Long id, String name) {
            names.put(id, name);
            rows.put(id, new ArrayList<Row>(rows.get(10L)));
        }

        private void assigned() {
            Runnable hook = afterAssign;
            afterAssign = () -> { };
            hook.run();
        }

        World() {
            when(project.getId()).thenReturn(1L);
            when(project.getKey()).thenReturn("PAY");
            when(project.getName()).thenReturn("Payments");
            when(project.getIssueTypes()).thenReturn(Arrays.asList(task, bug));
            CustomFieldType<?, ?> type = mock(CustomFieldType.class);
            when(type.getKey()).thenReturn("select");
            when(type.getName()).thenReturn("Select List");
            when(target.getId()).thenReturn(TARGET);
            when(target.getName()).thenReturn("Severity");
            when(target.getCustomFieldType()).thenReturn(type);
            when(target.isInScope(eq(1L), anyString())).thenReturn(true);

            names.put(10L, "Shared fields");
            List<Row> shared = new ArrayList<Row>();
            shared.add(new Row("summary", true, false, "Summary help", "text-renderer"));
            shared.add(new Row(TARGET, false, false, "How bad", null));
            shared.add(new Row("customfield_10101", true, false, null, "text-renderer"));
            shared.add(new Row("customfield_10102", false, false, "", "wiki-renderer"));
            shared.add(new Row("priority", false, true, "Prio help", null));
            rows.put(10L, shared);
            wire();
        }

        Row row(Long layoutId, String fieldId) { return row(rows.get(layoutId), fieldId); }

        Row row(List<Row> list, String fieldId) {
            for (Row row : list) if (row.fieldId.equals(fieldId)) return row;
            throw new AssertionError("no row " + fieldId);
        }

        Long effectiveId(String typeId) { return explicit.getOrDefault(typeId, 10L); }

        private OrderableField orderable(String id) {
            return fieldObjects.computeIfAbsent(id, key -> {
                if (key.equals(TARGET)) return target;
                OrderableField field = key.startsWith("customfield_") ? mock(CustomField.class) : mock(OrderableField.class);
                when(field.getId()).thenReturn(key);
                when(field.getName()).thenReturn(key);
                if (field instanceof CustomField) when(((CustomField) field).isInScope(eq(1L), anyString())).thenReturn(true);
                return field;
            });
        }

        private FieldLayoutItem item(Row row) {
            OrderableField orderable = orderable(row.fieldId);
            FieldLayoutItem item = mock(FieldLayoutItem.class);
            when(item.getOrderableField()).thenReturn(orderable);
            when(item.isRequired()).thenReturn(row.required);
            when(item.isHidden()).thenReturn(row.hidden);
            when(item.getRawFieldDescription()).thenReturn(row.description);
            when(item.getRendererType()).thenReturn(row.renderer);
            return item;
        }

        /** A layout rebuilt from stored rows, in reverse order; makeRequired/makeOptional replace in place. */
        private EditableFieldLayout load(Long id) {
            List<Row> stored = rows.get(id);
            if (stored == null) return null;
            List<FieldLayoutItem> items = new ArrayList<FieldLayoutItem>();
            for (int i = stored.size() - 1; i >= 0; i--) items.add(item(stored.get(i).copy()));
            return layout(id, names.get(id), "Shared description", items);
        }

        private EditableFieldLayout layout(Long id, String name, String description, List<FieldLayoutItem> items) {
            EditableFieldLayout layout = mock(EditableFieldLayout.class);
            when(layout.getId()).thenReturn(id);
            when(layout.getName()).thenReturn(name);
            when(layout.getDescription()).thenReturn(description);
            when(layout.isDefault()).thenReturn(false);
            when(layout.getFieldLayoutItems()).thenAnswer(call -> new ArrayList<FieldLayoutItem>(items));
            when(layout.getFieldLayoutItem(anyString())).thenAnswer(call -> {
                for (FieldLayoutItem item : items) {
                    if (item.getOrderableField().getId().equals(call.getArgument(0))) return item;
                }
                return null;
            });
            doAnswer(call -> { replace(items, call.getArgument(0), true); return null; }).when(layout).makeRequired(any());
            doAnswer(call -> { replace(items, call.getArgument(0), false); return null; }).when(layout).makeOptional(any());
            return layout;
        }

        private void replace(List<FieldLayoutItem> items, FieldLayoutItem old, boolean required) {
            int at = items.indexOf(old);
            Row row = new Row(old.getOrderableField().getId(), required, old.isHidden(), old.getRawFieldDescription(),
                    old.getRendererType());
            items.set(at, item(row));
        }

        private List<Row> rowsOf(List<FieldLayoutItem> items) {
            List<Row> result = new ArrayList<Row>();
            for (FieldLayoutItem item : items) {
                result.add(new Row(item.getOrderableField().getId(), item.isRequired(), item.isHidden(),
                        item.getRawFieldDescription(), item.getRendererType()));
            }
            return result;
        }

        private void wire() {
            doAnswer(call -> null).when(manager).refresh();
            FieldConfigurationScheme scheme = mock(FieldConfigurationScheme.class);
            when(scheme.getId()).thenReturn(20L);
            when(scheme.getName()).thenReturn("PAY: Field scheme");
            when(manager.getFieldConfigurationScheme(project)).thenReturn(scheme);
            when(manager.getProjectsUsing(scheme)).thenReturn(Collections.singletonList(project));
            when(manager.getProjectsUsing(any(FieldLayout.class))).thenReturn(Collections.singletonList(project));
            when(manager.getFieldLayout(eq(project), anyString())).thenAnswer(call -> {
                String typeId = call.getArgument(1);
                Long id = explicit.getOrDefault(typeId, 10L);
                if (assignmentsInvisible && id >= 50) id = 10L;
                return load(id);
            });
            when(manager.getEditableFieldLayout(any(Long.class))).thenAnswer(call -> {
                if (editableReadBudget == 0) throw new IllegalStateException("layout unreadable");
                if (editableReadBudget > 0) editableReadBudget--;
                return load(call.getArgument(0));
            });
            when(manager.getEditableFieldLayouts()).thenAnswer(call -> {
                List<EditableFieldLayout> all = new ArrayList<EditableFieldLayout>();
                for (Long id : rows.keySet()) all.add(load(id));
                return all;
            });
            when(manager.storeAndReturnEditableFieldLayout(any())).thenAnswer(call -> {
                EditableFieldLayout draft = call.getArgument(0);
                Long id = nextId++;
                names.put(id, draft.getName());
                rows.put(id, rowsOf(draft.getFieldLayoutItems()));
                if (failStoreAfterCreate) throw new IllegalStateException("create answer lost");
                return load(id);
            });
            doAnswer(call -> {
                EditableFieldLayout layout = call.getArgument(0);
                List<Row> stored = rowsOf(layout.getFieldLayoutItems());
                rows.put(layout.getId(), stored);
                afterStore.accept(stored);
                return null;
            }).when(manager).storeEditableFieldLayout(any());
            doAnswer(call -> {
                if (failDelete) throw new IllegalStateException("delete refused");
                Long id = ((FieldLayout) call.getArgument(0)).getId();
                rows.remove(id);
                names.remove(id);
                return null;
            }).when(manager).deleteFieldLayout(any());

            FieldLayoutScheme mutable = mock(FieldLayoutScheme.class);
            when(mutable.getId()).thenReturn(20L);
            when(mutable.getName()).thenReturn("PAY: Field scheme");
            when(manager.getMutableFieldLayoutScheme(20L)).thenReturn(mutable);
            when(manager.getFieldLayoutSchemes()).thenReturn(Collections.singletonList(mutable));
            when(mutable.getEntity(anyString())).thenAnswer(call -> {
                String typeId = call.getArgument(0);
                return explicit.containsKey(typeId) ? entity(typeId) : null;
            });
            when(mutable.getEntities()).thenAnswer(call -> {
                List<FieldLayoutSchemeEntity> entities = new ArrayList<FieldLayoutSchemeEntity>();
                for (String typeId : explicit.keySet()) entities.add(entity(typeId));
                return entities;
            });
            when(manager.createFieldLayoutSchemeEntity(any(FieldLayoutScheme.class), anyString(), any())).thenAnswer(call -> {
                if (failAssignment) throw new IllegalStateException("scheme write refused");
                explicit.put(call.getArgument(1), call.getArgument(2));
                assigned();
                if (failAfterAssignment || (failAfterNthAssignment > 0 && ++assignments == failAfterNthAssignment)) {
                    throw new IllegalStateException("failed after the row was written");
                }
                return null;
            });
            doAnswer(call -> {
                FieldLayoutSchemeEntity entity = call.getArgument(0);
                if (failRestore && explicit.get(entity.getIssueTypeId()) != null && explicit.get(entity.getIssueTypeId()) < 50) {
                    throw new IllegalStateException("restore refused");
                }
                if (explicit.get(entity.getIssueTypeId()) != null && explicit.get(entity.getIssueTypeId()) == 50L) assigned();
                return null;
            }).when(manager).updateFieldLayoutSchemeEntity(any());
            doAnswer(call -> {
                if (failRestore) throw new IllegalStateException("restore refused");
                explicit.remove(((FieldLayoutSchemeEntity) call.getArgument(0)).getIssueTypeId());
                return null;
            }).when(manager).removeFieldLayoutSchemeEntity(any());

            when(copies.copyOf(any(), anyString())).thenAnswer(call -> {
                FieldLayout source = call.getArgument(0);
                List<FieldLayoutItem> sourceItems = source.getFieldLayoutItems();
                String draftName = call.getArgument(1);
                EditableFieldLayout draft = mock(EditableFieldLayout.class);
                when(draft.getName()).thenReturn(draftName);
                when(draft.getFieldLayoutItems()).thenReturn(sourceItems);
                return draft;
            });
        }

        private FieldLayoutSchemeEntity entity(String typeId) {
            FieldLayoutSchemeEntity entity = mock(FieldLayoutSchemeEntity.class);
            when(entity.getIssueTypeId()).thenReturn(typeId);
            when(entity.getFieldLayoutId()).thenAnswer(call -> explicit.get(typeId));
            doAnswer(call -> { explicit.put(typeId, call.getArgument(0)); return null; }).when(entity).setFieldLayoutId(any());
            return entity;
        }

        private static IssueType issueType(String id, String name) {
            IssueType type = mock(IssueType.class);
            when(type.getId()).thenReturn(id);
            when(type.getName()).thenReturn(name);
            return type;
        }
    }
}
