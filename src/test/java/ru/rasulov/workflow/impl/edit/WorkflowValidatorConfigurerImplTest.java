package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.issue.fields.Field;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowUtil;
import com.opensymphony.workflow.loader.ValidatorDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.InMemoryActionPlans;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.api.dto.edit.ScriptRunnerStatus;
import ru.rasulov.workflow.api.dto.edit.ValidatorFieldRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.edit.ValidatorFixtures.*;

/**
 * Plan and apply of {@code configure_workflow} against a small fake of Jira's workflow store: what is stored
 * changes only through Jira's own calls, so the tests can say exactly what was and was not written.
 */
public class WorkflowValidatorConfigurerImplTest {

    private static final String NAME = "Support";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final ApplicationUser user = mock(ApplicationUser.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final WorkflowManager manager = mock(WorkflowManager.class);
    private final WorkflowInspector inspector = mock(WorkflowInspector.class);
    private final FieldManager fields = mock(FieldManager.class);
    private final FieldScreenManager screens = mock(FieldScreenManager.class);
    private final ScriptRunnerGate gate = mock(ScriptRunnerGate.class);
    private final ValidatorFieldPlacement placement = mock(ValidatorFieldPlacement.class);
    private final com.atlassian.jira.ofbiz.OfBizDelegator delegator =
            mock(com.atlassian.jira.ofbiz.OfBizDelegator.class);

    /** What Jira holds. */
    private WorkflowDescriptor active;
    private WorkflowDescriptor draft;
    private boolean isActive;
    private boolean system;
    private int revision;
    private int consumersVersion;
    private ScriptRunnerStatus scriptRunner = new ScriptRunnerStatus(true, true, "9.46.0", true, null);
    private List<String> placementWarnings = new ArrayList<String>();
    private String assigneeName = "Assignee";
    private boolean screenNineExists = true;
    private UndoTestFixture journalFixture;
    private WorkflowConfigurerImpl configurer;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        journalFixture = UndoTestFixture.create("admin");
        when(user.getKey()).thenReturn("admin");
        when(user.getUsername()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(user);
        when(lock.run(anyString(), anyString(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
        active = workflow();
        isActive = false;

        when(inspector.snapshot(anyString())).thenAnswer(call -> {
            if (!NAME.equals(call.getArgument(0))) return null;
            return new WorkflowInspector.Snapshot(NAME, jw(active, false), draft == null ? null : jw(copy(draft), true),
                    isActive, system, "rev-" + revision);
        });
        when(inspector.consumers(any())).thenAnswer(call -> new WorkflowInspector.Consumers(
                new TreeSet<Long>(Collections.singleton(7L + consumersVersion)), Arrays.asList("Scheme [7]: Bug"),
                new TreeMap<String, String>(Collections.singletonMap("ABC", "Abc"))));
        when(inspector.referenced(any())).thenReturn(Collections.<String, Object>emptyMap());
        when(inspector.catalog()).thenReturn(EditFixtures.catalog());

        when(gate.status()).thenAnswer(call -> scriptRunner);
        when(placement.warnings(any(), any(), anyList(), any())).thenAnswer(call -> new ArrayList<String>(placementWarnings));
        orderable("assignee", () -> assigneeName);
        orderable("description", () -> "Description");
        orderable("customfield_10118", () -> "Причина");
        orderable("customfield_10200", () -> "Team");
        Field status = mock(Field.class);
        when(status.getId()).thenReturn("status");
        when(status.getName()).thenReturn("Status");
        when(fields.getField("status")).thenReturn(status);

        when(manager.getWorkflowClone(NAME)).thenAnswer(call -> jw(copy(active), false));
        when(manager.getWorkflow(NAME)).thenAnswer(call -> jw(active, false));
        when(manager.getDraftWorkflow(NAME)).thenAnswer(call -> draft == null ? null : jw(copy(draft), true));
        when(manager.createDraftWorkflow(any(ApplicationUser.class), anyString())).thenAnswer(call -> {
            draft = copy(active);
            revision++;
            return jw(copy(draft), true);
        });
        doAnswer(call -> {
            JiraWorkflow saved = call.getArgument(1);
            if (saved.isDraftWorkflow()) draft = copy(saved.getDescriptor()); else active = copy(saved.getDescriptor());
            revision++;
            return null;
        }).when(manager).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        org.ofbiz.core.entity.GenericValue workflowRow = mock(org.ofbiz.core.entity.GenericValue.class);
        when(workflowRow.getLong("id")).thenReturn(101L);
        when(delegator.findByCondition(org.mockito.ArgumentMatchers.eq("Workflow"), any(), any(), any()))
                .thenReturn(Collections.singletonList(workflowRow));

        FieldScreen screenNine = mock(FieldScreen.class);
        when(screenNine.getId()).thenReturn(9L);
        when(screenNine.getName()).thenReturn("Transition");
        when(screenNine.containsField(anyString())).thenReturn(true);
        when(screens.getFieldScreen(9L)).thenAnswer(call -> screenNineExists ? screenNine : null);

        configurer = new WorkflowConfigurerImpl(guard, lock,
                new WorkflowConfigurationPlanner(manager, inspector, null, null, null, null, null, fields, screens,
                        null, null, null, gate, placement, new ActionPlanCodec(), mock(StepOccupancy.class)),
                new WorkflowConfigurationWriter(manager, inspector, null, null, null, screens,
                        delegator, journalFixture.journal),
                new PreviewPlanExecutor(InMemoryActionPlans.create(), new ActionPlanCodec()), journalFixture.journal);
    }

    @After
    public void closeJournal() {
        journalFixture.close();
    }

    private void orderable(String id, Supplier<String> name) {
        OrderableField field = mock(OrderableField.class);
        when(field.getId()).thenReturn(id);
        when(field.getName()).thenAnswer(call -> name.get());
        when(fields.getOrderableField(id)).thenReturn(field);
        when(fields.getField(id)).thenReturn(field);
    }

    // ---- preview ------------------------------------------------------------------------------------------------

    @Test
    public void previewShowsTheTransitionTheFieldsTheErrorTextAndWritesNothing() {
        String before = DescriptorFacts.definition(active);

        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 22, "assignee", "description"));

        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertTrue(preview.isDryRun());
        assertNotNull(preview.getPlanId());
        assertEquals(22, transition(preview).get("actionId"));
        assertEquals("Finish", transition(preview).get("name"));
        assertEquals(Arrays.asList("In Progress"), transition(preview).get("from"));
        assertEquals(Arrays.asList("Done"), transition(preview).get("to"));
        List<Map<String,Object>> finalRules = finalValidators(preview);
        assertEquals(Arrays.asList("assignee"), finalRules.get(0).get("fieldIds"));
        assertEquals("Assignee", ((List<ValidatorFieldRef>) finalRules.get(0).get("fields")).get(0).getName());
        assertEquals(Arrays.asList("description"), finalRules.get(2).get("fieldIds"));
        assertEquals("Description", ((List<ValidatorFieldRef>) finalRules.get(2).get("fields")).get(0).getName());
        assertEquals(3, finalRules.get(2).get("position"));
        assertEquals("This field is required", finalRules.get(2).get("errorText"));
        assertTrue(preview.getTargetVersion().equals(isActive ? "DRAFT" : "ACTIVE"));
        assertEquals(1, ((ru.rasulov.workflow.api.dto.edit.WorkflowView.Consumers) preview.getConsumers()).getProjectCount());
        assertEquals(isActive ? "DRAFT" : "ACTIVE", preview.getTargetVersion());
        assertEquals("the stored workflow is not touched by a preview", before, DescriptorFacts.definition(active));
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void aSingleRequiredFieldGetsTheSingleFieldMessageOfScriptRunner() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 26, "assignee"));

        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertEquals("This field is required", finalValidators(preview).get(0).get("errorText"));
        assertEquals("required_fields", finalValidators(preview).get(0).get("kind"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void whatTheScreensAndFieldConfigurationsSayIsOnlyAWarningAndTheFieldsOfTheChangeAreWhatIsChecked() {
        placementWarnings.add("Field 'Description' is on no screen of ABC / Bug");

        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee", "description"));

        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertNotNull("a warning never withholds the plan", preview.getPlanId());
        assertEquals(Arrays.asList(
                "Workflow changes are shared by every project using this workflow. Control locks do not protect against Jira UI or other plugins.",
                placementWarnings.get(0),
                "Transition 11: 2 unrecognized validator(s) preserved and not analyzed.",
                "All conditions of an AND root must be satisfied. Conditions narrow access for every project using this workflow, including future projects; group membership is only an observation and may change."),
                preview.getWarnings());
        ArgumentCaptor<List<ValidatorFieldRef>> checked = ArgumentCaptor.forClass(List.class);
        verify(placement).warnings(any(), org.mockito.ArgumentMatchers.eq("9"), checked.capture(), any());
        assertEquals("only the fields that are added are checked", Arrays.asList("assignee", "description"),
                ids(checked.getValue()));
    }

    @Test
    public void noPlacementWarningDoesNotHideFinalScreenAndUnrecognizedValidatorWarnings() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 22, "assignee", "description"));

        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertEquals(Arrays.asList(
                "Workflow changes are shared by every project using this workflow. Control locks do not protect against Jira UI or other plugins.",
                "Transition 22: required field 'assignee' is absent from the final transition screen; a user may be unable to fill it in.",
                "Transition 22: required field 'description' is absent from the final transition screen; a user may be unable to fill it in.",
                "Transition 22: 1 unrecognized validator(s) preserved and not analyzed."),
                preview.getWarnings());
    }

    // ---- apply: inactive workflow -----------------------------------------------------------

    @Test
    public void appliedToAnInactiveWorkflowMissingFieldsBecomeASeparateValidatorAfterExistingRules() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 22, "assignee", "description"));
        Map<Integer, String> others = actionsExcept(active, 22);

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("UPDATED_INACTIVE", done.getStatus());
        assertFalse(done.isDryRun());
        List<ValidatorDescriptor> validators = validators(action(active, 22));
        assertEquals("a separate validator is appended after both existing rules", 3, validators.size());
        assertEquals("assignee", validators.get(0).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals("description", validators.get(2).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals(ID_1, validators.get(0).getArgs().get("FIELD_FUNCTION_ID"));
        assertEquals(RequiredFieldsDescriptor.CANNED_SCRIPT, validators.get(0).getArgs().get("canned-script"));
        assertEquals("", validators.get(0).getArgs().get("FIELD_CONDITION"));
        assertEquals(others, actionsExcept(active, 22));
        verify(manager, times(1)).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void appliedWithoutAnUnconditionalValidatorOneNewValidatorIsAddedAtTheEndWithAFreshFunctionId() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee", "customfield_10118"));
        Map<Integer, String> others = actionsExcept(active, 11);
        List<String> neighbours = Arrays.asList(DescriptorFacts.xml(validators(action(active, 11)).get(0)),
                DescriptorFacts.xml(validators(action(active, 11)).get(1)));

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("UPDATED_INACTIVE", done.getStatus());
        List<ValidatorDescriptor> validators = validators(action(active, 11));
        assertEquals(3, validators.size());
        assertEquals(neighbours, Arrays.asList(DescriptorFacts.xml(validators.get(0)), DescriptorFacts.xml(validators.get(1))));
        ValidatorDescriptor created = validators.get(2);
        assertEquals("assignee|||customfield_10118", created.getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals("", created.getArgs().get("FIELD_CONDITION"));
        assertEquals(RequiredFieldsDescriptor.CANNED_SCRIPT, created.getArgs().get("canned-script"));
        assertEquals(RequiredFieldsDescriptor.CLASS_NAME, created.getArgs().get("class.name"));
        String functionId = (String) created.getArgs().get("FIELD_FUNCTION_ID");
        assertTrue(functionId, functionId.matches(UUID_PATTERN));
        assertEquals(functionId, UUID.fromString(functionId).toString());
        assertFalse(functionId, ID_1.equals(functionId) || ID_2.equals(functionId));
        assertEquals(5, created.getArgs().size());
        assertEquals(others, actionsExcept(active, 11));
    }

    @Test
    public void everyOtherPartOfTheWorkflowSurvivesTheWriteExactly() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        String conditions = DescriptorFacts.xml(action(active, 11).getRestriction());
        String results = DescriptorFacts.xml(action(active, 11).getUnconditionalResult());
        String initial = DescriptorFacts.xml(action(active, 1));

        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(preview)).getStatus());
        assertEquals("the validator really was added", 3, validators(action(active, 11)).size());

        assertEquals(conditions, DescriptorFacts.xml(action(active, 11).getRestriction()));
        assertEquals(results, DescriptorFacts.xml(action(active, 11).getUnconditionalResult()));
        assertEquals(initial, DescriptorFacts.xml(action(active, 1)));
        assertEquals(DescriptorFacts.definition(reloaded(active)), DescriptorFacts.definition(active));
        assertTrue(DescriptorFacts.xml(active).contains("TOP-SECRET-NOTE"));
    }

    // ---- idempotence and union --------------------------------------------------------------------------

    @Test
    public void fieldsThatAreAlreadyRequiredByTwoValidatorsTogetherGiveNoPlanAndNoDraft() {
        isActive = true;

        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 23, "assignee", "description"));

        assertEquals("UNCHANGED", preview.getStatus());
        assertNull(preview.getPlanId());
        assertEquals(Arrays.asList("assignee", "description"), required(preview));
        assertNull("no draft is created for an unchanged request", draft);
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aNewFieldIsAppendedSeparatelyAndBothExistingValidatorsArePreserved() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 23, "assignee", "description",
                "customfield_10118"));
        assertEquals(Arrays.asList("assignee", "description", "customfield_10118"), required(preview));
        assertEquals(Arrays.asList("customfield_10118"), finalValidators(preview).get(2).get("fieldIds"));
        String second = DescriptorFacts.xml(validators(action(active, 23)).get(1));

        configurer.configure(apply(preview));

        List<ValidatorDescriptor> validators = validators(action(active, 23));
        assertEquals(3, validators.size());
        assertEquals("assignee", validators.get(0).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals("customfield_10118", validators.get(2).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals(second, DescriptorFacts.xml(validators.get(1)));
    }

    @Test
    public void aConditionalValidatorDoesNotMakeAFieldAlreadyRequired() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 24, "assignee"));

        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertTrue((Boolean) finalValidators(preview).get(0).get("conditional"));
        assertEquals(Arrays.asList("assignee"), finalValidators(preview).get(1).get("fieldIds"));
        assertEquals(Arrays.asList("assignee"), required(preview));
    }

    // ---- active workflow: draft -------------------------------------------------------------------------

    @Test
    public void anActiveWorkflowGetsADraftWithTheChangeAndItsActiveVersionStaysByteForByteTheSame() {
        isActive = true;
        String activeBefore = DescriptorFacts.xml(active);
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertTrue(preview.getTargetVersion().equals(isActive ? "DRAFT" : "ACTIVE"));
        assertNull("a preview must not create the draft", draft);

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("DRAFT_SAVED", done.getStatus());
        assertEquals(activeBefore, DescriptorFacts.xml(active));
        List<ValidatorDescriptor> inDraft = validators(action(draft, 11));
        assertEquals(3, inDraft.size());
        assertEquals("assignee", inDraft.get(2).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertTrue(((String) inDraft.get(2).getArgs().get("FIELD_FUNCTION_ID")).matches(UUID_PATTERN));
        verify(manager, times(1)).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).overwriteActiveWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).deleteDraftWorkflow(anyString());
    }

    @Test
    public void anExistingDraftIsNeverChangedBehindTheBackOfVersionActive() {
        isActive = true;
        draft = copy(active);
        revision++;

        ConfigureWorkflowResponse refused = configurer.configure(preview("ACTIVE", 11, "assignee"));

        assertEquals("DRAFT_EXISTS", refused.getErrorCode());
        assertNull(refused.getPlanId());
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void aSecondChangeOfTheChosenDraftKeepsEveryOtherChangeInIt() {
        isActive = true;
        draft = copy(active);
        // somebody else added a validator to the draft in Jira
        RequiredFieldsDescriptor.apply(action(draft, 26), RequiredFieldsDescriptor.plan(action(draft, 26),
                Arrays.asList("description")), "4d3b2a19-8c7e-4f6a-9b5d-1e2f3a4b5c6d");
        revision++;
        String activeBefore = DescriptorFacts.xml(active);

        ConfigureWorkflowResponse preview = configurer.configure(preview("DRAFT", 22, "assignee", "description"));
        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("DRAFT_SAVED", done.getStatus());
        assertEquals("assignee", validators(action(draft, 22)).get(0).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals("description", validators(action(draft, 22)).get(2).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals("description", validators(action(draft, 26)).get(0).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals(activeBefore, DescriptorFacts.xml(active));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void versionDraftWithoutADraftIsAVersionConflict() {
        isActive = true;

        assertEquals("VERSION_CONFLICT",
                configurer.configure(preview("DRAFT", 11, "assignee")).getErrorCode());
        isActive = false;
        assertEquals("VERSION_CONFLICT",
                configurer.configure(preview("DRAFT", 11, "assignee")).getErrorCode());
    }

    @Test
    public void aDraftCreatedFromADifferentActiveVersionIsNotWrittenAndIsReported() {
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        when(manager.createDraftWorkflow(any(ApplicationUser.class), anyString())).thenAnswer(call -> {
            draft = copy(active);
            RequiredFieldsDescriptor.apply(action(draft, 26), RequiredFieldsDescriptor.plan(action(draft, 26),
                    Arrays.asList("description")), "4d3b2a19-8c7e-4f6a-9b5d-1e2f3a4b5c6d");
            revision++;
            return jw(copy(draft), true);
        });

        ConfigureWorkflowResponse result = configurer.configure(apply(preview));

        assertEquals("JIRA_ERROR", result.getErrorCode());
        assertEquals("PARTIAL", result.getStatus());
        assertEquals("NOT_ATTEMPTED", step(result, "workflow-save").get("writeStatus"));
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).deleteDraftWorkflow(anyString());
    }

    // ---- the ScriptRunner gate --------------------------------------------------------------------------

    @Test
    public void withoutScriptRunnerThereIsAReasonAndNeitherAPlanNorAWrite() {
        scriptRunner = new ScriptRunnerStatus(false, false, null, false, "ScriptRunner for Jira is not installed.");

        ConfigureWorkflowResponse refused = configurer.configure(preview("ACTIVE", 11, "assignee"));

        assertEquals("FAILED", refused.getStatus());
        assertEquals("SCRIPTRUNNER_UNAVAILABLE", refused.getErrorCode());
        assertTrue(refused.getMessage(), refused.getMessage().contains("not installed"));
        assertNull(refused.getPlanId());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void aDisabledScriptRunnerIsRefusedAsUnavailable() {
        scriptRunner = new ScriptRunnerStatus(true, false, "9.46.0", false, "ScriptRunner for Jira is not enabled.");

        ConfigureWorkflowResponse refused = configurer.configure(preview("ACTIVE", 11, "assignee"));

        assertEquals("SCRIPTRUNNER_UNAVAILABLE", refused.getErrorCode());
        assertNull(refused.getPlanId());
    }

    @Test
    public void aVersionOutsideTheVerifiedListIsRefusedWithTheInstalledAndTheVerifiedVersions() {
        scriptRunner = new ScriptRunnerStatus(true, true, "9.46.1", false, "ScriptRunner 9.46.1 is installed; verified "
                + "versions: 9.46.0.");

        ConfigureWorkflowResponse refused = configurer.configure(preview("ACTIVE", 11, "assignee"));

        assertEquals("SCRIPTRUNNER_VERSION_UNSUPPORTED", refused.getErrorCode());
        assertTrue(refused.getMessage(), refused.getMessage().contains("9.46.1"));
        assertTrue(refused.getMessage(), refused.getMessage().contains("9.46.0"));
        assertNull(refused.getPlanId());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aScriptRunnerThatBecameUnavailableAfterThePreviewMakesThePlanStaleWithoutWriting() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        scriptRunner = new ScriptRunnerStatus(true, false, "9.46.0", false, "ScriptRunner for Jira is not enabled.");

        ConfigureWorkflowResponse result = configurer.configure(apply(preview));

        assertEquals("PREVIEW_STALE", result.getErrorCode());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void aScriptRunnerUpdatedAfterThePreviewMakesThePlanStaleWithoutWriting() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        scriptRunner = new ScriptRunnerStatus(true, true, "9.46.1", false, "ScriptRunner 9.46.1 is installed.");

        assertEquals("PREVIEW_STALE", configurer.configure(apply(preview)).getErrorCode());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    // ---- refusals before anything is planned ------------------------------------------------------------

    @Test
    public void unknownSystemAndStaleTargetsAreRefusedWithoutAPlan() {
        assertEquals("UNKNOWN_WORKFLOW", configurer.configure(request(true,
                "Nope", "ACTIVE", "rev-0", 11, "required_fields", params("assignee"), null)).getErrorCode());
        assertEquals("VERSION_CONFLICT", configurer.configure(request(true,
                NAME, "ACTIVE", "rev-99", 11, "required_fields", params("assignee"), null)).getErrorCode());
        system = true;
        assertEquals("SYSTEM_WORKFLOW", configurer.configure(preview("ACTIVE", 11, "assignee")).getErrorCode());
        system = false;
        ConfigureWorkflowResponse unknownAction = configurer.configure(preview("ACTIVE", 999, "assignee"));
        assertEquals("UNKNOWN_TRANSITION", unknownAction.getErrorCode());
        assertNull(unknownAction.getPlanId());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void unknownAndNotOrderableFieldsAreRefused() {
        ConfigureWorkflowResponse unknown = configurer.configure(preview("ACTIVE", 11, "assignee", "customfield_99999"));
        assertEquals("UNKNOWN_FIELD", unknown.getErrorCode());
        assertTrue(unknown.getMessage(), unknown.getMessage().contains("customfield_99999"));
        assertNull(unknown.getPlanId());

        ConfigureWorkflowResponse notOrderable = configurer.configure(preview("ACTIVE", 11, "status"));
        assertEquals("VALIDATION_ERROR", notOrderable.getErrorCode());
        assertTrue(notOrderable.getMessage(), notOrderable.getMessage().contains("status"));
        assertNull(notOrderable.getPlanId());
    }

    @Test
    public void malformedFieldListsAndTemplatesAndMissingValuesAreRefused() {
        List<String> tooMany = new ArrayList<String>();
        for (int i = 0; i <= 50; i++) tooMany.add("customfield_" + (20000 + i));
        List<ConfigureWorkflowRequest> bad = Arrays.asList(
                request(true, NAME, "ACTIVE", "rev-0", 11, "required_fields", params(), null),
                request(true, NAME, "ACTIVE", "rev-0", 11, "required_fields", params("assignee", "assignee"), null),
                request(true, NAME, "ACTIVE", "rev-0", 11, "required_fields", params("assignee", " "), null),
                request(true, NAME, "ACTIVE", "rev-0", 11, "required_fields",
                        new ConfigureWorkflowRequest.Parameters(Arrays.asList("assignee", null), null, null, null), null),
                request(true, NAME, "ACTIVE", "rev-0", 11, "required_fields",
                        new ConfigureWorkflowRequest.Parameters(tooMany, null, null, null), null),
                request(true, NAME, "ACTIVE", "rev-0", 11, "required_fields",
                        new ConfigureWorkflowRequest.Parameters(null, null, null, null), null),
                request(true, NAME, "ACTIVE", "rev-0", 11, "required_fields", null, null),
                request(true, NAME, "ACTIVE", "rev-0", 11, "comment_required", params("assignee"), null),
                request(true, NAME, "ACTIVE", "rev-0", 11, null, params("assignee"), null),
                request(true, NAME, "ACTIVE", "rev-0", null, "required_fields", params("assignee"), null),
                request(true, NAME, "ACTIVE", null, 11, "required_fields", params("assignee"), null),
                request(true, NAME, "ACTIVE", " ", 11, "required_fields", params("assignee"), null),
                request(true, NAME, "BOTH", "rev-0", 11, "required_fields", params("assignee"), null),
                request(true, NAME, null, "rev-0", 11, "required_fields", params("assignee"), null),
                request(true, " ", "ACTIVE", "rev-0", 11, "required_fields", params("assignee"), null),
                request(true, null, "ACTIVE", "rev-0", 11, "required_fields", params("assignee"), null));
        for (ConfigureWorkflowRequest request : bad) {
            ConfigureWorkflowResponse refused = configurer.configure(request);
            assertEquals(request.toString(),
                    "VALIDATION_ERROR", refused.getErrorCode());
            assertNull(refused.getPlanId());
        }
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void applyTakesOnlyThePlanAndAPreviewTakesNoPlanAndTheModeIsExplicit() {
        for (ConfigureWorkflowRequest invalid : Arrays.asList(
                request(false, NAME, "ACTIVE", "rev-0", 11, "required_fields", params("assignee"), "p"),
                request(false, NAME, null, null, null, null, null, "p"),
                request(false, null, null, null, null, null, null, null),
                request(false, null, null, null, null, null, null, " "),
                request(true, NAME, "ACTIVE", "rev-0", 11, "required_fields", params("assignee"), "p"),
                request(null, NAME, "ACTIVE", "rev-0", 11, "required_fields", params("assignee"), null))) {
            try {
                configurer.configure(invalid);
                fail("expected a refusal");
            } catch (InvalidRequestException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void aTransitionThatCannotBePatchedExactlyIsRefusedAsUnsupported() {
        for (int actionId : new int[] {41, 25}) {
            ConfigureWorkflowResponse refused = configurer.configure(preview("ACTIVE", actionId, "description"));
            assertEquals("action " + actionId, "UNSUPPORTED_DESCRIPTOR", refused.getErrorCode());
            assertNull(refused.getPlanId());
        }
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aGlobalAndAnInitialTransitionAreSupported() {
        assertEquals("WOULD_CHANGE", configurer.configure(preview("ACTIVE", 31, "assignee")).getStatus());
        assertEquals("WOULD_CHANGE", configurer.configure(preview("ACTIVE", 1, "assignee")).getStatus());
    }

    @Test
    public void aDefinitionThatJirasOwnSaveWouldNotReproduceIsNotChanged() {
        // Jira would not store this exactly as it is now: the plan must not rest on such a base
        // Jira's copy of such a definition is the stored one itself: the copy does not hide the defect
        when(manager.getWorkflowClone(NAME)).thenAnswer(call -> jw(active, false));
        DescriptorFacts.meta(action(active, 26)).put("acme.note", "bad\u0001value");

        ConfigureWorkflowResponse refused = configurer.configure(preview("ACTIVE", 26, "assignee"));

        assertEquals("UNSUPPORTED_DESCRIPTOR", refused.getErrorCode());
        assertNull(refused.getPlanId());
    }

    @Test
    public void aFieldIdThatWouldNotBeStoredExactlyIsNotWritten() {
        orderable("odd<&\u0001id", () -> "Odd");

        ConfigureWorkflowResponse refused = configurer.configure(preview("ACTIVE", 26, "odd<&\u0001id"));

        assertEquals("UNSUPPORTED_DESCRIPTOR", refused.getErrorCode());
        assertNull(refused.getPlanId());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    // ---- stale plans ------------------------------------------------------------------------------------

    @Test
    public void aPlanIsSingleUse() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 22, "assignee", "description"));
        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(preview)).getStatus());

        ConfigureWorkflowResponse again = configurer.configure(apply(preview));

        assertEquals("PREVIEW_STALE", again.getErrorCode());
        verify(manager, times(1)).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aPlanThatNeverExistedIsStale() {
        ConfigureWorkflowResponse result = configurer.configure(request(false, null, null, null,
                null, null, null, "no-such-plan"));

        assertEquals("PREVIEW_STALE", result.getErrorCode());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aWorkflowThatChangedAfterThePreviewIsNotWritten() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        // someone changes the workflow in Jira between the preview and the confirmation
        DescriptorFacts.locate(active).get(26).action().setName("Plain!");
        revision++;

        assertEquals("PREVIEW_STALE", configurer.configure(apply(preview)).getErrorCode());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        assertEquals(2, validators(action(active, 11)).size());
    }

    @Test
    public void aChangeOfTheUsersOfTheWorkflowAfterThePreviewMakesThePlanStale() {
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        consumersVersion++;

        assertEquals("PREVIEW_STALE", configurer.configure(apply(preview)).getErrorCode());
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void aDraftThatAppearedAfterThePreviewIsNotOverwrittenByTheDraftCreationPlan() {
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        draft = copy(active);
        revision++;

        assertEquals("PREVIEW_STALE", configurer.configure(apply(preview)).getErrorCode());
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aFieldThatChangedAfterThePreviewMakesThePlanStale() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        assigneeName = "Responsible";

        assertEquals("PREVIEW_STALE", configurer.configure(apply(preview)).getErrorCode());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aFieldThatStoppedBeingUsableAfterThePreviewMakesThePlanStale() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        when(fields.getOrderableField("assignee")).thenReturn(null);

        assertEquals("PREVIEW_STALE", configurer.configure(apply(preview)).getErrorCode());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    // ---- failures after a write may have started ---------------------------------------------------------------

    @Test
    public void aFailureAfterTheDraftWasCreatedSaysSoAndNeverRetriesOrDeletes() {
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        doThrow(new IllegalStateException("db down")).when(manager)
                .updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));

        ConfigureWorkflowResponse result = configurer.configure(apply(preview));

        assertEquals("PARTIAL", result.getStatus());
        assertEquals("JIRA_ERROR", result.getErrorCode());
        assertEquals("APPLIED", step(result, "draft").get("writeStatus"));
        assertEquals("UNKNOWN", step(result, "workflow-save").get("writeStatus"));
        assertNotNull("the draft that Jira created is still there", draft);
        verify(manager, times(1)).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, times(1)).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).deleteDraftWorkflow(anyString());
    }

    @Test
    public void aSaveThatDoesNotReadBackIsReportedAsUncertainNotAsSuccess() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        doAnswer(call -> {
            revision++; // Jira reports success but stores nothing
            return null;
        }).when(manager).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));

        ConfigureWorkflowResponse result = configurer.configure(apply(preview));

        assertEquals("PARTIAL", result.getStatus());
        assertEquals("JIRA_ERROR", result.getErrorCode());
        assertEquals("APPLIED", step(result, "workflow-save").get("writeStatus"));
        assertEquals("MISMATCH", step(result, "workflow-save").get("verification"));
        assertTrue(result.getMessage(), result.getMessage().contains("No automatic retry"));
    }

    @Test
    public void aFailureBeforeAnyChangedByteIsStillNotReportedAsNothingChanged() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 11, "assignee"));
        doThrow(new IllegalStateException("timeout")).when(manager)
                .updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));

        ConfigureWorkflowResponse result = configurer.configure(apply(preview));

        assertEquals("JIRA_ERROR", result.getErrorCode());
        assertEquals("UNKNOWN", result.getStatus());
        assertEquals("UNKNOWN", step(result, "workflow-save").get("writeStatus"));
        assertTrue(result.getMessage(), result.getMessage().contains("No automatic retry"));
    }

    // ---- the write runs under the workflow lock ----------------------------------------------------------------

    @Test
    public void theWriteRunsUnderTheWorkflowLockExactlyOnce() {
        ConfigureWorkflowResponse preview = configurer.configure(preview("ACTIVE", 22, "assignee", "description"));

        configurer.configure(apply(preview));

        verify(lock, times(4)).run(anyString(), anyString(), any());
    }

    // ---- the comment validator ----------------------------------------------------------------------

    private static final String NO_COMMENT_MESSAGE = "Please provide a comment for this transition.";
    private static final String NO_SCREEN_MESSAGE = "You need to provide a comment for this transition but no screen is in use";

    private ConfigureWorkflowRequest commentPreview(String version, int actionId) {
        return request(true, NAME, version, "rev-" + revision, actionId, "comment_required",
                null, null);
    }

    private static List<String> xmlOfValidators(WorkflowDescriptor d, int actionId) {
        List<String> xml = new ArrayList<String>();
        for (ValidatorDescriptor v : validators(action(d, actionId))) xml.add(DescriptorFacts.xml(v));
        return xml;
    }

    @Test
    public void theCommentPreviewDescribesTheCommentAndWritesNothing() {
        active = commentWorkflow();
        isActive = true;
        placementWarnings.add("Field 'Assignee' is on no screen");
        String before = DescriptorFacts.xml(active);

        ConfigureWorkflowResponse preview = configurer.configure(commentPreview("ACTIVE", 11));

        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertTrue(preview.isDryRun());
        assertNotNull(preview.getPlanId());
        assertEquals(11, transition(preview).get("actionId"));
        assertEquals("Plain", transition(preview).get("name"));
        assertEquals(Arrays.asList("Open"), transition(preview).get("from"));
        assertEquals(Arrays.asList("Done"), transition(preview).get("to"));
        assertEquals("ADD_VALIDATOR: 11 comment_required", preview.getDiff().get(0));
        assertEquals(1, preview.getDiff().size());
        assertEquals(Arrays.asList("assignee"), required(preview));
        assertEquals("comment_required", finalValidators(preview).get(2).get("kind"));
        String errorText = finalValidators(preview).get(2).get("errorText").toString();
        assertTrue(errorText, errorText.contains(NO_COMMENT_MESSAGE));
        assertTrue(errorText, errorText.contains(NO_SCREEN_MESSAGE));
        assertEquals(Arrays.asList(
                "Workflow changes are shared by every project using this workflow. Control locks do not protect against Jira UI or other plugins.",
                "The draft is shared by all administrators. Publishing it later affects every project using this workflow; use clone_project_workflows first when only one project should change. No publication or project assignment is performed.",
                placementWarnings.get(0),
                "Transition 11: 1 unrecognized validator(s) preserved and not analyzed.",
                "All conditions of an AND root must be satisfied. Conditions narrow access for every project using this workflow, including future projects; group membership is only an observation and may change."),
                preview.getWarnings());
        assertEquals(isActive ? "DRAFT" : "ACTIVE", preview.getTargetVersion());
        assertEquals(isActive ? "DRAFT" : "ACTIVE", preview.getTargetVersion());
        assertEquals(before, DescriptorFacts.xml(active));
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
        ArgumentCaptor<List<ValidatorFieldRef>> checked = ArgumentCaptor.forClass(List.class);
        verify(placement).warnings(any(), org.mockito.ArgumentMatchers.eq("9"), checked.capture(), any());
        assertEquals("The existing required assignee is checked on the final screen", Arrays.asList("assignee"), ids(checked.getValue()));
    }

    @Test
    public void appliedToAnInactiveWorkflowOneValidatorOfTheSampleFormIsAddedAtTheEnd() {
        active = commentWorkflow();
        ConfigureWorkflowResponse preview = configurer.configure(commentPreview("ACTIVE", 11));
        List<String> others = new ArrayList<String>();
        for (DescriptorFacts.Located l : DescriptorFacts.locate(active).values()) {
            if (l.id() != 11) others.add(DescriptorFacts.xml(l.action()));
        }
        List<String> neighbours = xmlOfValidators(active, 11);
        String conditions = DescriptorFacts.xml(action(active, 11).getRestriction());
        String results = DescriptorFacts.xml(action(active, 11).getUnconditionalResult());

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("UPDATED_INACTIVE", done.getStatus());
        assertFalse(done.isDryRun());
        List<ValidatorDescriptor> validators = validators(action(active, 11));
        assertEquals(3, validators.size());
        assertEquals(neighbours, xmlOfValidators(active, 11).subList(0, 2));
        ValidatorDescriptor created = validators.get(2);
        assertEquals(Arrays.asList("FIELD_FUNCTION_ID", "canned-script", "class.name"),
                new ArrayList<Object>(created.getArgs().keySet()));
        assertEquals(COMMENT_CANNED, created.getArgs().get("canned-script"));
        assertEquals(RequiredFieldsDescriptor.CLASS_NAME, created.getArgs().get("class.name"));
        String functionId = (String) created.getArgs().get("FIELD_FUNCTION_ID");
        assertTrue(functionId, functionId.matches(UUID_PATTERN));
        assertFalse(functionId, ID_1.equals(functionId) || ID_2.equals(functionId));
        assertEquals(DescriptorFacts.xml(validator(commentRequired(functionId))), DescriptorFacts.xml(created));
        assertEquals(conditions, DescriptorFacts.xml(action(active, 11).getRestriction()));
        assertEquals(results, DescriptorFacts.xml(action(active, 11).getUnconditionalResult()));
        List<String> after = new ArrayList<String>();
        for (DescriptorFacts.Located l : DescriptorFacts.locate(active).values()) {
            if (l.id() != 11) after.add(DescriptorFacts.xml(l.action()));
        }
        assertEquals(others, after);
        assertEquals(DescriptorFacts.definition(reloaded(active)), DescriptorFacts.definition(active));
        verify(manager, times(1)).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void anExistingRequiredFieldsValidatorAndTheOrderStayWhenTheCommentIsAdded() {
        active = commentWorkflow();
        ConfigureWorkflowResponse preview = configurer.configure(commentPreview("ACTIVE", 11));
        List<String> before = xmlOfValidators(active, 11);

        configurer.configure(apply(preview));

        List<String> after = xmlOfValidators(active, 11);
        assertEquals(3, after.size());
        assertEquals(before, after.subList(0, 2));
        assertTrue(after.get(1).contains("FIELD_REQUIRED_FIELDS"));
    }

    @Test
    public void anActiveWorkflowGetsADraftWithTheCommentValidatorAndItsActiveVersionStaysTheSame() {
        active = commentWorkflow();
        isActive = true;
        String activeBefore = DescriptorFacts.xml(active);
        ConfigureWorkflowResponse preview = configurer.configure(commentPreview("ACTIVE", 11));
        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertTrue(preview.getTargetVersion().equals(isActive ? "DRAFT" : "ACTIVE"));
        assertNull(draft);

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("DRAFT_SAVED", done.getStatus());
        assertEquals(activeBefore, DescriptorFacts.xml(active));
        List<ValidatorDescriptor> inDraft = validators(action(draft, 11));
        assertEquals(3, inDraft.size());
        assertTrue(CommentRequiredDescriptor.captured(inDraft.get(2)));
        verify(manager, times(1)).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).overwriteActiveWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).deleteDraftWorkflow(anyString());
    }

    @Test
    public void anExistingDraftIsRefusedForVersionActiveAndEditedForVersionDraft() {
        active = commentWorkflow();
        isActive = true;
        draft = copy(active);
        revision++;

        ConfigureWorkflowResponse refused = configurer.configure(commentPreview("ACTIVE", 11));
        assertEquals("DRAFT_EXISTS", refused.getErrorCode());
        assertNull(refused.getPlanId());

        ConfigureWorkflowResponse preview = configurer.configure(commentPreview("DRAFT", 11));
        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertEquals("DRAFT_SAVED", configurer.configure(apply(preview)).getStatus());
        assertEquals(3, validators(action(draft, 11)).size());
        assertEquals(2, validators(action(active, 11)).size());
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void withoutAUsableScreenNothingIsPlannedWrittenOrDrafted() {
        active = commentWorkflow();
        isActive = true;
        for (int actionId : new int[] {14, 15, 17}) {
            ConfigureWorkflowResponse refused = configurer.configure(commentPreview("ACTIVE", actionId));

            assertEquals("action " + actionId, "FAILED", refused.getStatus());
            assertEquals("action " + actionId, "TRANSITION_SCREEN_MISSING", refused.getErrorCode());
            assertNull(refused.getPlanId());
        }
        assertNull(draft);
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void aScreenThatJiraNoLongerHasIsTheSameAsNoScreen() {
        active = commentWorkflow();
        screenNineExists = false;

        ConfigureWorkflowResponse refused = configurer.configure(commentPreview("ACTIVE", 11));

        assertEquals("TRANSITION_SCREEN_MISSING", refused.getErrorCode());
        assertNull(refused.getPlanId());
    }

    @Test
    public void aScreenMetaWithoutTheFieldscreenViewIsRefusedNamingTheViewAlsoWhenTheValidatorIsThere() {
        active = commentWorkflow();
        isActive = true;
        action(active, 11).setView(null);
        action(active, 12).setView(null);
        action(active, 21).setView("resolveissue");
        for (int actionId : new int[] {11, 12, 21}) {
            ConfigureWorkflowResponse refused = configurer.configure(commentPreview("ACTIVE", actionId));

            assertEquals("action " + actionId, "FAILED", refused.getStatus());
            assertEquals("action " + actionId, "TRANSITION_SCREEN_MISSING", refused.getErrorCode());
            assertNull(refused.getPlanId());
        }
        assertNull(draft);
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void anExistingCommentValidatorWithoutAUsableScreenIsRefusedNotUnchanged() {
        active = commentWorkflow();
        isActive = true;
        for (int actionId : new int[] {13, 16, 18}) {
            ConfigureWorkflowResponse refused = configurer.configure(commentPreview("ACTIVE", actionId));

            assertEquals("action " + actionId, "TRANSITION_SCREEN_MISSING", refused.getErrorCode());
            assertNull(refused.getPlanId());
        }
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void anExistingCommentValidatorOnAUsableScreenIsUnchangedWithoutPlanDraftOrWrite() {
        active = commentWorkflow();
        isActive = true;
        String before = DescriptorFacts.xml(active);
        for (int actionId : new int[] {12, 21}) {
            ConfigureWorkflowResponse preview = configurer.configure(commentPreview("ACTIVE", actionId));

            assertEquals("action " + actionId, "UNCHANGED", preview.getStatus());
            assertNull(preview.getPlanId());
            assertNull(preview.getErrorCode());
            assertEquals(actionId, transition(preview).get("actionId"));
            assertFalse(preview.getMessage(), preview.getMessage().toLowerCase().contains("field"));
            assertTrue(preview.getMessage(), preview.getMessage().toLowerCase().contains("comment"));
        }
        assertEquals(before, DescriptorFacts.xml(active));
        assertNull(draft);
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void theCommentScriptInAnotherFormIsRefusedAsUnsupported() {
        active = commentWorkflow();
        for (int actionId : new int[] {19, 20}) {
            ConfigureWorkflowResponse refused = configurer.configure(commentPreview("ACTIVE", actionId));

            assertEquals("action " + actionId, "UNSUPPORTED_DESCRIPTOR", refused.getErrorCode());
            assertNull(refused.getPlanId());
        }
    }

    @Test
    public void aCommonOrUnknownTransitionIsRefusedLikeForRequiredFields() {
        active = workflow();

        ConfigureWorkflowResponse common = configurer.configure(commentPreview("ACTIVE", 41));
        assertEquals("UNSUPPORTED_DESCRIPTOR", common.getErrorCode());
        assertNull(common.getPlanId());
        ConfigureWorkflowResponse unknown = configurer.configure(commentPreview("ACTIVE", 999));
        assertEquals("UNKNOWN_TRANSITION", unknown.getErrorCode());
        assertNull(unknown.getPlanId());
    }

    @Test
    public void aScreenRemovedFromTheTransitionAfterThePreviewMakesTheApplyStale() {
        active = commentWorkflow();
        ConfigureWorkflowResponse preview = configurer.configure(commentPreview("ACTIVE", 11));
        String before = DescriptorFacts.xml(active);
        DescriptorFacts.meta(action(active, 11)).remove(DescriptorFacts.SCREEN_META);
        String changed = DescriptorFacts.xml(active);

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("PREVIEW_STALE", done.getErrorCode());
        assertFalse(before.equals(changed));
        assertEquals(changed, DescriptorFacts.xml(active));
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aScreenReplacedByANonNumberAfterThePreviewMakesTheApplyStale() {
        active = commentWorkflow();
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(commentPreview("ACTIVE", 11));
        DescriptorFacts.meta(action(active, 11)).put(DescriptorFacts.SCREEN_META, "abc");

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("PREVIEW_STALE", done.getErrorCode());
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aScreenDeletedFromJiraAfterThePreviewMakesTheApplyStaleWithoutAnyWrite() {
        active = commentWorkflow();
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(commentPreview("ACTIVE", 11));
        assertNotNull(preview.getPlanId());
        screenNineExists = false;

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("PREVIEW_STALE", done.getErrorCode());
        assertNull(draft);
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void theFieldscreenViewRemovedFromTheTransitionAfterThePreviewMakesTheApplyStale() {
        active = commentWorkflow();
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(commentPreview("ACTIVE", 11));
        assertNotNull(preview.getPlanId());
        action(active, 11).setView(null);
        String changed = DescriptorFacts.xml(active);

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("PREVIEW_STALE", done.getErrorCode());
        assertEquals(changed, DescriptorFacts.xml(active));
        assertNull(draft);
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void anotherViewSetOnTheTransitionAfterThePreviewMakesTheApplyStale() {
        active = commentWorkflow();
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(commentPreview("ACTIVE", 11));
        assertNotNull(preview.getPlanId());
        action(active, 11).setView("resolveissue");

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("PREVIEW_STALE", done.getErrorCode());
        assertNull(draft);
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aChangeOfTheWorkflowTheUsersOrScriptRunnerAfterThePreviewMakesTheApplyStale() {
        active = commentWorkflow();
        ConfigureWorkflowResponse byUsers = configurer.configure(commentPreview("ACTIVE", 11));
        consumersVersion++;
        assertEquals("PREVIEW_STALE", configurer.configure(apply(byUsers)).getErrorCode());

        ConfigureWorkflowResponse byWorkflow = configurer.configure(commentPreview("ACTIVE", 11));
        revision++;
        assertEquals("PREVIEW_STALE", configurer.configure(apply(byWorkflow)).getErrorCode());

        ConfigureWorkflowResponse byScriptRunner = configurer.configure(commentPreview("ACTIVE", 11));
        scriptRunner = new ScriptRunnerStatus(true, true, "9.46.0", false, "updated");
        assertEquals("PREVIEW_STALE", configurer.configure(apply(byScriptRunner)).getErrorCode());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void commentRequiredTakesNoParametersAndEveryOtherInputIsRefusedWithoutAPlan() {
        active = commentWorkflow();
        List<ConfigureWorkflowRequest> bad = Arrays.asList(
                request(true, NAME, "ACTIVE", "rev-0", 11, "comment_required", params("assignee"), null),
                request(true, NAME, "ACTIVE", "rev-0", 11, "comment_required", params(), null),
                request(true, NAME, "ACTIVE", "rev-0", 11, "comment_required",
                        new ConfigureWorkflowRequest.Parameters(Arrays.asList((String) null), null, null, null), null),
                request(true, NAME, "ACTIVE", "rev-0", 11, "Comment_Required", null, null),
                request(true, NAME, "ACTIVE", "rev-0", 11, "other", null, null));
        for (ConfigureWorkflowRequest request : bad) {
            ConfigureWorkflowResponse refused = configurer.configure(request);
            assertEquals(request.toString(), "VALIDATION_ERROR", refused.getErrorCode());
            assertNull(refused.getPlanId());
        }

        ConfigureWorkflowResponse unknown = configurer.configure(request(true, "Nope", "ACTIVE",
                "rev-0", 11, "comment_required", null, null));
        assertEquals("UNKNOWN_WORKFLOW", unknown.getErrorCode());
        assertNull(unknown.getPlanId());
        ConfigureWorkflowResponse conflict = configurer.configure(request(true, NAME, "ACTIVE",
                "rev-99", 11, "comment_required", null, null));
        assertEquals("VERSION_CONFLICT", conflict.getErrorCode());
        assertNull(conflict.getPlanId());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));

        ConfigureWorkflowResponse emptyParameters = configurer.configure(request(true, NAME,
                "ACTIVE", "rev-" + revision, 11, "comment_required", new ConfigureWorkflowRequest.Parameters(null, null, null, null), null));
        assertEquals("WOULD_CHANGE", emptyParameters.getStatus());
    }

    @Test
    public void requiredFieldsStillNeedsItsFieldIdsInTheCode() {
        ConfigureWorkflowResponse refused = configurer.configure(request(true, NAME, "ACTIVE",
                "rev-0", 11, "required_fields", new ConfigureWorkflowRequest.Parameters(null, null, null, null), null));

        assertEquals("VALIDATION_ERROR", refused.getErrorCode());
        assertNull(refused.getPlanId());
    }

    @Test
    public void anApplyOfTheCommentPlanIsSingleUseAndTakesOnlyThePlan() {
        active = commentWorkflow();
        ConfigureWorkflowResponse preview = configurer.configure(commentPreview("ACTIVE", 11));
        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(preview)).getStatus());

        assertEquals("PREVIEW_STALE", configurer.configure(apply(preview)).getErrorCode());
        assertEquals(3, validators(action(active, 11)).size());
        try {
            configurer.configure(request(false, NAME, null, null, null, "comment_required", null,
                    "p"));
            fail("expected a refusal");
        } catch (InvalidRequestException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private ConfigureWorkflowRequest preview(String version, int actionId, String... fieldIds) {
        return request(true, NAME, version, "rev-" + revision, actionId, "required_fields",
                params(fieldIds), null);
    }

    private static ConfigureWorkflowRequest.Parameters params(String... fieldIds) {
        return new ConfigureWorkflowRequest.Parameters(Arrays.asList(fieldIds), null, null, null);
    }

    private static ConfigureWorkflowRequest apply(ConfigureWorkflowResponse preview) {
        return request(false, null, null, null, null, null, null, preview.getPlanId());
    }

    private static ConfigureWorkflowRequest request(Boolean dryRun, String workflow, String version,
            String revision, Integer actionId, String template, ConfigureWorkflowRequest.Parameters parameters, String planId) {
        boolean target = workflow != null || version != null || revision != null;
        boolean rule = actionId != null || template != null || parameters != null;
        return new ConfigureWorkflowRequest(dryRun, null,
                target ? new ConfigureWorkflowRequest.Existing(workflow,version,revision,null) : null, null,
                rule ? Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, actionId, null,
                        Collections.singletonList(new ConfigureWorkflowRequest.Validator(template, parameters)), null)) : null,
                planId);
    }

    private static Map<String,Object> transition(ConfigureWorkflowResponse response) { return response.getTransitions().get(0); }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> finalValidators(ConfigureWorkflowResponse response) {
        return (List<Map<String,Object>>) transition(response).get("validators");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(ConfigureWorkflowResponse response) {
        java.util.LinkedHashSet<String> fields = new java.util.LinkedHashSet<>();
        for (Map<String,Object> validator : finalValidators(response))
            if ("required_fields".equals(validator.get("kind")) && Boolean.FALSE.equals(validator.get("conditional")))
                fields.addAll((List<String>) validator.get("fieldIds"));
        return new ArrayList<>(fields);
    }

    private static Map<String,Object> step(ConfigureWorkflowResponse response, String key) {
        for (Object raw : (List<?>) response.getExecution().get("steps")) {
            Map<String,Object> step = (Map<String,Object>) raw;
            if (key.equals(step.get("key"))) return step;
        }
        throw new AssertionError("Missing step " + key);
    }

    private static List<String> ids(List<ValidatorFieldRef> refs) {
        List<String> ids = new ArrayList<String>();
        for (ValidatorFieldRef ref : refs) ids.add(ref.getId());
        return ids;
    }

    private static Map<Integer, String> actionsExcept(WorkflowDescriptor d, int skip) {
        Map<Integer, String> xml = new LinkedHashMap<Integer, String>();
        for (DescriptorFacts.Located l : DescriptorFacts.locate(d).values()) {
            if (l.id() != skip) xml.put(l.id(), DescriptorFacts.xml(l.action()));
        }
        return xml;
    }

    private static WorkflowDescriptor copy(WorkflowDescriptor d) {
        try {
            return WorkflowUtil.convertXMLtoWorkflowDescriptor(WorkflowUtil.convertDescriptorToXML(d));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JiraWorkflow jw(WorkflowDescriptor d, boolean isDraft) {
        JiraWorkflow workflow = mock(JiraWorkflow.class);
        when(workflow.getName()).thenReturn(NAME);
        when(workflow.getDescriptor()).thenReturn(d);
        when(workflow.isDraftWorkflow()).thenReturn(isDraft);
        when(workflow.getDescription()).thenReturn("Support");
        return workflow;
    }
}
