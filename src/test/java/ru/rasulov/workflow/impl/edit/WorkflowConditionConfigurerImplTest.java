package ru.rasulov.workflow.impl.edit;

import com.atlassian.crowd.embedded.api.Group;
import com.atlassian.jira.security.groups.GroupManager;
import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowUtil;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.ConditionDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.AccessException;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.edit.ConditionFixtures.action;
import static ru.rasulov.workflow.impl.edit.ConditionFixtures.workflow;

/**
 * Plan and apply of {@code configure_workflow_conditions} against a small fake of Jira's workflow store: what is stored
 * changes only through Jira's own calls, so the tests can say exactly what was and was not written.
 */
public class WorkflowConditionConfigurerImplTest {

    private static final String NAME = "Support";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final ApplicationUser user = mock(ApplicationUser.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final WorkflowManager manager = mock(WorkflowManager.class);
    private final WorkflowInspector inspector = mock(WorkflowInspector.class);
    private final ScriptRunnerGate gate = mock(ScriptRunnerGate.class);
    private final GroupManager groups = mock(GroupManager.class);
    private final ProjectRoleManager roles = mock(ProjectRoleManager.class);

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
    /** Jira's groups by lower-case name: the name Jira stores and the number of members. */
    private final Map<String, String> groupNames = new LinkedHashMap<String, String>();
    private final Map<String, Integer> groupMembers = new LinkedHashMap<String, Integer>();
    private final List<ProjectRole> allRoles = new ArrayList<ProjectRole>();
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
        when(gate.conditionStatus()).thenAnswer(call -> scriptRunner);

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

        group("Team-B", 3);
        group("Team-A", 5);
        group("jira-administrators", 1);
        group("Empty-G", 0);
        when(groups.getGroup(anyString())).thenAnswer(call -> {
            String stored = groupNames.get(String.valueOf((Object) call.getArgument(0)).toLowerCase());
            if (stored == null) return null;
            Group group = mock(Group.class);
            when(group.getName()).thenReturn(stored);
            return group;
        });
        when(groups.getUsersInGroupCount(any(Group.class))).thenAnswer(call -> {
            Integer members = groupMembers.get(((Group) call.getArgument(0)).getName().toLowerCase());
            return members == null ? 0 : members;
        });
        when(groups.getUsersInGroupCount(anyString())).thenAnswer(call -> {
            Integer members = groupMembers.get(String.valueOf((Object) call.getArgument(0)).toLowerCase());
            return members == null ? 0 : members;
        });
        when(groups.groupExists(anyString())).thenAnswer(call -> groupNames.containsKey(String.valueOf((Object) call.getArgument(0)).toLowerCase()));

        role(10002L, "Administrators");
        role(10101L, "Service Desk Team");
        role(10100L, "Service Desk Customers");
        role(10001L, "Developers");
        role(10300L, "Twin");
        role(10301L, "Twin");
        when(roles.getProjectRoles()).thenAnswer(call -> new ArrayList<ProjectRole>(allRoles));

        configurer = new WorkflowConfigurerImpl(guard, lock,
                new WorkflowConfigurationPlanner(manager, inspector, null, null, null, null, null,
                        mock(com.atlassian.jira.issue.fields.FieldManager.class),
                        mock(com.atlassian.jira.issue.fields.screen.FieldScreenManager.class), null, groups, roles,
                        gate, mock(ValidatorFieldPlacement.class), new ActionPlanCodec(), mock(StepOccupancy.class)),
                new WorkflowConfigurationWriter(manager, inspector, null, null, null,
                        mock(com.atlassian.jira.issue.fields.screen.FieldScreenManager.class),
                        delegator, journalFixture.journal),
                new PreviewPlanExecutor(InMemoryActionPlans.create(), new ActionPlanCodec()), journalFixture.journal);
    }

    @After
    public void closeJournal() {
        journalFixture.close();
    }

    private void group(String name, int members) {
        groupNames.put(name.toLowerCase(), name);
        groupMembers.put(name.toLowerCase(), members);
    }

    private void role(long id, String name) {
        ProjectRole role = mock(ProjectRole.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        allRoles.add(role);
        when(roles.getProjectRole(id)).thenAnswer(call -> {
            for (ProjectRole r : allRoles) if (r.getId() == id) return r;
            return null;
        });
    }

    @Test
    public void anonymousAndNonAdministratorsCannotPreviewOrConsumeAnApprovedPlan() {
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 13, "Team-B"));
        String before = DescriptorFacts.definition(active);
        clearInvocations(inspector, manager, gate, groups, roles, lock);
        for (boolean anonymous : new boolean[] {true, false}) {
            AccessException denied = new AccessException(anonymous ? "Authentication required" : "Administrator required", anonymous);
            doThrow(denied).when(guard).requireAdministrator();
            for (ConfigureWorkflowRequest request : Arrays.asList(group("ACTIVE", 13, "Team-B"), apply(preview))) {
                try {
                    configurer.configure(request);
                    fail("unauthorized preview/apply must be denied");
                } catch (AccessException expected) {
                    org.junit.Assert.assertSame(denied, expected);
                }
                verifyNoInteractions(inspector, manager, gate, groups, roles, lock);
                assertEquals(before, DescriptorFacts.definition(active));
                assertNull(draft);
            }
        }
        doReturn(user).when(guard).requireAdministrator();
        assertEquals("denial must not consume the administrator's plan",
                "UPDATED_INACTIVE", configurer.configure(apply(preview)).getStatus());
        assertEquals("Team-B", conditions(active, 13).get(0).getArgs().get("group"));
    }

    // ---- preview ------------------------------------------------------------------------------------------------

    @Test
    public void aGroupPreviewShowsTheTransitionTheCurrentConditionsTheAdditionAndItsEffectAndWritesNothing() {
        String before = DescriptorFacts.definition(active);

        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));

        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertTrue(preview.isDryRun());
        assertNotNull(preview.getPlanId());
        assertEquals(11, transition(preview).get("actionId"));
        assertEquals("Done", transition(preview).get("name"));
        assertEquals(Arrays.asList("In Progress"), transition(preview).get("from"));
        assertEquals(Arrays.asList("Done"), transition(preview).get("to"));
        assertEquals("AND", conditionTree(preview).getOperator());
        assertEquals(Arrays.asList("OTHER", "PROJECT_ROLE", "GROUP", "GROUP"), kinds(preview));
        assertEquals("GROUP", lastCondition(preview).getKind());
        assertEquals("Team-B", lastCondition(preview).getGroup());
        assertEquals(4, lastCondition(preview).getPosition());
        assertTrue(transition(preview).get("conditionSemantics").toString().contains("all conditions"));
        assertTrue(String.join(" ", preview.getWarnings()).contains("narrow access"));
        assertTrue(String.join(" ", preview.getWarnings()).contains("every project"));
        assertEquals("ACTIVE", preview.getTargetVersion());
        assertEquals(1, ((ru.rasulov.workflow.api.dto.edit.WorkflowView.Consumers) preview.getConsumers()).getProjectCount());
        assertTrue(preview.getMessage(), preview.getMessage().contains("start_operation"));
        assertTrue(preview.getMessage(), preview.getMessage().contains("no automatic rollback"));
        assertEquals("the stored workflow is not touched by a preview", before, DescriptorFacts.definition(active));
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void aRolePreviewNamesTheRolesAndAnInvertedOneSaysEveryoneExceptTheirMembers() {
        ConfigureWorkflowResponse plain = configurer.configure(roles("ACTIVE", 11, false, "Administrators", "10101"));
        ConfigureWorkflowResponse inverted = configurer.configure(roles("ACTIVE", 11, true, "Administrators"));

        assertEquals("WOULD_CHANGE", plain.getStatus());
        assertEquals("PROJECT_ROLE", lastCondition(plain).getKind());
        assertEquals(Arrays.asList(10002L, 10101L), lastCondition(plain).getRoleIds());
        assertEquals("Administrators", lastCondition(plain).getRoles().get(0).getName());
        assertEquals("Service Desk Team", lastCondition(plain).getRoles().get(1).getName());
        assertEquals(Boolean.FALSE, lastCondition(plain).getInverted());
        assertTrue(transition(plain).get("conditionSemantics").toString().contains("issue project"));
        assertTrue(transition(plain).get("conditionSemantics").toString(), transition(plain).get("conditionSemantics").toString().toLowerCase().contains("any listed role"));
        assertTrue(String.join(" ", plain.getWarnings()).contains("narrow access"));
        assertEquals(Boolean.TRUE, lastCondition(inverted).getInverted());
        assertTrue(transition(inverted).get("conditionSemantics").toString(), transition(inverted).get("conditionSemantics").toString().toLowerCase().contains("member of none"));
    }

    @Test
    public void theGroupIsWrittenUnderTheNameJiraStoresNotAsTyped() {
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 13, "team-b"));

        assertEquals("Team-B", lastCondition(preview).getGroup());
        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(preview)).getStatus());
        assertEquals("Team-B", conditions(active, 13).get(0).getArgs().get("group"));
    }

    // ---- apply: inactive workflow -----------------------------------------------------

    @Test
    public void appliedToAnInactiveWorkflowOneGroupConditionIsAppendedAtTheEndInPlace() {
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));
        Map<Integer, String> others = actionsExcept(active, 11);
        List<String> existing = conditionXml(active, 11);

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("UPDATED_INACTIVE", done.getStatus());
        assertFalse(done.isDryRun());
        assertEquals(existing, conditionXml(active, 11).subList(0, 3));
        List<ConditionDescriptor> list = conditions(active, 11);
        assertEquals(4, list.size());
        assertEquals("class", list.get(3).getType());
        assertEquals(2, list.get(3).getArgs().size());
        assertEquals(ConditionDescriptors.GROUP_CLASS, list.get(3).getArgs().get("class.name"));
        assertEquals("Team-B", list.get(3).getArgs().get("group"));
        assertEquals(others, actionsExcept(active, 11));
        assertEquals(DescriptorFacts.definition(ConditionFixtures.reloaded(active)), DescriptorFacts.definition(active));
        verify(manager, times(1)).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void appliedRolesAreWrittenAsTheCapturedConditionWithAFreshFunctionIdAndInvertAsOn() {
        ConfigureWorkflowResponse preview = configurer.configure(roles("ACTIVE", 11, false, "Administrators", "10001"));
        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(preview)).getStatus());
        ConfigureWorkflowResponse invertedPreview = configurer.configure(roles("ACTIVE", 12, true, "Developers"));
        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(invertedPreview)).getStatus());

        ConditionDescriptor plain = conditions(active, 11).get(3);
        assertEquals("class", plain.getType());
        assertEquals(5, plain.getArgs().size());
        assertEquals("10002|||10001", plain.getArgs().get("FIELD_PROJECT_ROLE_IDS"));
        assertEquals("", plain.getArgs().get("FIELD_INVERT_CONDITION"));
        assertEquals(ConditionDescriptors.ROLE_CANNED, plain.getArgs().get("canned-script"));
        assertEquals(ConditionDescriptors.ROLE_CLASS, plain.getArgs().get("class.name"));
        String functionId = (String) plain.getArgs().get("FIELD_FUNCTION_ID");
        assertTrue(functionId, functionId.matches(UUID_PATTERN));
        assertEquals(functionId, UUID.fromString(functionId).toString());
        assertFalse(functionId, ConditionFixtures.ID_1.equals(functionId));
        ConditionDescriptor inverted = conditions(active, 12).get(1);
        assertEquals("10001", inverted.getArgs().get("FIELD_PROJECT_ROLE_IDS"));
        assertEquals("on", inverted.getArgs().get("FIELD_INVERT_CONDITION"));
        assertFalse("every plan has its own function id", functionId.equals(inverted.getArgs().get("FIELD_FUNCTION_ID")));
        assertEquals("the existing function id is not touched", ConditionFixtures.ID_1,
                conditions(active, 11).get(1).getArgs().get("FIELD_FUNCTION_ID"));
    }

    @Test
    public void aTransitionWithoutConditionsGetsAnAndRootWithTheOneConditionForBothTemplates() {
        ConfigureWorkflowResponse group = configurer.configure(group("ACTIVE", 13, "Team-B"));
        Map<Integer, String> others = actionsExcept(active, 13);
        String results = DescriptorFacts.xml(action(active, 13).getUnconditionalResult());

        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(group)).getStatus());

        assertEquals("a root of one condition is stored without a type, which reads as AND", "AND",
                ConditionDescriptors.read(action(active, 13), id -> null).getOperator());
        assertEquals(1, conditions(active, 13).size());
        assertEquals(others, actionsExcept(active, 13));
        assertEquals(results, DescriptorFacts.xml(action(active, 13).getUnconditionalResult()));
        assertEquals(1, action(active, 13).getValidators().size());
        assertTrue(DescriptorFacts.xml(active).contains("keep 13"));

        ConfigureWorkflowResponse role = configurer.configure(roles("ACTIVE", 31, false, "Administrators"));
        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(role)).getStatus());
        assertEquals("AND", ConditionDescriptors.read(action(active, 31), id -> null).getOperator());
        assertEquals("10002", conditions(active, 31).get(0).getArgs().get("FIELD_PROJECT_ROLE_IDS"));
    }

    @Test
    public void onlyTheRootOfTheTargetTransitionChangesAndTheRestOfTheWorkflowSurvives() {
        for (int target : new int[] {10, 11, 12, 13, 16, 31}) {
            active = workflow();
            revision++;
            Map<Integer, String> others = actionsExcept(active, target);
            String results = DescriptorFacts.xml(action(active, target).getUnconditionalResult());
            String initial = DescriptorFacts.xml(action(active, 1));

            ConfigureWorkflowResponse done = configurer.configure(apply(configurer.configure(
                    group("ACTIVE", target, "Team-B"))));

            assertEquals("action " + target, "UPDATED_INACTIVE", done.getStatus());
            assertEquals(others, actionsExcept(active, target));
            assertEquals(results, DescriptorFacts.xml(action(active, target).getUnconditionalResult()));
            assertEquals(initial, DescriptorFacts.xml(action(active, 1)));
            assertEquals(DescriptorFacts.definition(ConditionFixtures.reloaded(active)), DescriptorFacts.definition(active));
        }
    }

    // ---- active workflow and drafts ----------------------------------------------------------------------

    @Test
    public void anActiveWorkflowGetsADraftWithTheConditionAndItsActiveVersionStaysByteForByteTheSame() {
        isActive = true;
        String activeBefore = DescriptorFacts.xml(active);
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));
        assertEquals("WOULD_CHANGE", preview.getStatus());
        assertEquals("DRAFT", preview.getTargetVersion());
        assertEquals("DRAFT", preview.getTargetVersion());
        assertNull("a preview must not create the draft", draft);

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("DRAFT_SAVED", done.getStatus());
        assertEquals(activeBefore, DescriptorFacts.xml(active));
        assertEquals(4, conditions(draft, 11).size());
        assertEquals("Team-B", conditions(draft, 11).get(3).getArgs().get("group"));
        verify(manager, times(1)).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).overwriteActiveWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).deleteDraftWorkflow(anyString());
    }

    @Test
    public void theDraftThatIsCreatedForARoleGetsTheSameFunctionIdTheReplayWasVerifiedWith() {
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(roles("ACTIVE", 13, true, "Administrators"));

        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("DRAFT_SAVED", done.getStatus());
        ConditionDescriptor added = conditions(draft, 13).get(0);
        assertTrue((String) added.getArgs().get("FIELD_FUNCTION_ID"), ((String) added.getArgs().get("FIELD_FUNCTION_ID")).matches(UUID_PATTERN));
        assertEquals("on", added.getArgs().get("FIELD_INVERT_CONDITION"));
    }

    @Test
    public void anExistingDraftIsNeverChangedBehindTheBackOfVersionActive() {
        isActive = true;
        draft = copy(active);
        revision++;

        ConfigureWorkflowResponse refused = configurer.configure(group("ACTIVE", 11, "Team-B"));

        assertEquals("DRAFT_EXISTS", refused.getErrorCode());
        assertNull(refused.getPlanId());
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void aSecondChangeOfTheChosenDraftKeepsEveryOtherChangeInIt() {
        isActive = true;
        draft = copy(active);
        ConditionDescriptors.apply(action(draft, 13), ConditionDescriptors.Candidate.group("Team-A"),
                "4d3b2a19-8c7e-4f6a-9b5d-1e2f3a4b5c6d");
        revision++;
        String activeBefore = DescriptorFacts.xml(active);

        ConfigureWorkflowResponse preview = configurer.configure(group("DRAFT", 11, "Team-B"));
        ConfigureWorkflowResponse done = configurer.configure(apply(preview));

        assertEquals("DRAFT_SAVED", done.getStatus());
        assertEquals("Team-B", conditions(draft, 11).get(3).getArgs().get("group"));
        assertEquals("the change somebody else made in the draft stays", "Team-A",
                conditions(draft, 13).get(0).getArgs().get("group"));
        assertEquals(activeBefore, DescriptorFacts.xml(active));
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void versionDraftWithoutADraftIsAVersionConflict() {
        isActive = true;

        assertEquals("VERSION_CONFLICT", configurer.configure(group("DRAFT", 11, "Team-B")).getErrorCode());
        isActive = false;
        assertEquals("VERSION_CONFLICT", configurer.configure(group("DRAFT", 11, "Team-B")).getErrorCode());
    }

    @Test
    public void aDraftCreatedFromADifferentActiveVersionIsNotWrittenAndIsReported() {
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));
        when(manager.createDraftWorkflow(any(ApplicationUser.class), anyString())).thenAnswer(call -> {
            draft = copy(active);
            ConditionDescriptors.apply(action(draft, 13), ConditionDescriptors.Candidate.group("Team-A"),
                    "4d3b2a19-8c7e-4f6a-9b5d-1e2f3a4b5c6d");
            revision++;
            return jw(copy(draft), true);
        });

        ConfigureWorkflowResponse result = configurer.configure(apply(preview));

        assertEquals("JIRA_ERROR", result.getErrorCode());
        assertEquals("PARTIAL", result.getStatus());
        assertEquals("NOT_ATTEMPTED", receiptStep(result, "workflow-save").get("writeStatus"));
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
        verify(manager, never()).deleteDraftWorkflow(anyString());
    }

    // ---- idempotence -------------------------------------------------------------------------------------

    @Test
    public void theSameGroupInAnotherCaseGivesNoPlanAndNoDraftAnotherGroupDoes() {
        isActive = true;

        ConfigureWorkflowResponse same = configurer.configure(group("ACTIVE", 16, "team-a"));

        assertEquals("UNCHANGED", same.getStatus());
        assertNull(same.getPlanId());
        assertNull("no draft is created for an unchanged request", draft);
        assertEquals("GROUP", conditionTree(same).getChildren().get(0).getKind());
        assertEquals("WOULD_CHANGE", configurer.configure(group("ACTIVE", 16, "Team-B")).getStatus());
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void theSameRolesInAnotherOrderWithTheSameInvertGiveNoPlanAnotherInvertOrSetDoes() {
        isActive = true;

        ConfigureWorkflowResponse same = configurer.configure(roles("ACTIVE", 17, false, "10101", "Administrators"));

        assertEquals("UNCHANGED", same.getStatus());
        assertNull(same.getPlanId());
        assertNull(draft);
        assertEquals("WOULD_CHANGE", configurer.configure(roles("ACTIVE", 17, true, "10101", "10002")).getStatus());
        assertEquals("WOULD_CHANGE", configurer.configure(roles("ACTIVE", 17, false, "10002")).getStatus());
        assertNotNull(configurer.configure(roles("ACTIVE", 17, false, "10002")).getPlanId());
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void aMatchingConditionInACompoundDoesNotCountAndAnAppliedAdditionIsThenUnchanged() {
        assertEquals("WOULD_CHANGE", configurer.configure(group("ACTIVE", 19, "Team-A")).getStatus());
        assertEquals("WOULD_CHANGE", configurer.configure(roles("ACTIVE", 19, false, "Administrators")).getStatus());
        assertEquals("WOULD_CHANGE", configurer.configure(group("ACTIVE", 14, "Team-B")).getStatus());

        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 19, "Team-A"));
        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(preview)).getStatus());

        assertEquals("UNCHANGED", configurer.configure(group("ACTIVE", 19, "Team-A")).getStatus());
    }

    // ---- an OR root --------------------------------------------------------------------------------------

    @Test
    public void anOrRootIsRefusedForBothTemplatesWithAReasonAndNothingIsWritten() {
        isActive = true;

        ConfigureWorkflowResponse group = configurer.configure(group("ACTIVE", 15, "Team-A"));
        ConfigureWorkflowResponse role = configurer.configure(roles("ACTIVE", 15, false, "Administrators"));

        for (ConfigureWorkflowResponse refused : Arrays.asList(group, role)) {
            assertEquals("UNSUPPORTED_DESCRIPTOR", refused.getErrorCode());
            assertEquals("FAILED", refused.getStatus());
            assertNull(refused.getPlanId());
            assertTrue(refused.getMessage(), refused.getMessage().contains("OR"));
        }
        assertNull(draft);
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    // ---- the ScriptRunner gate --------------------------------------------------------------------------

    @Test
    public void withoutScriptRunnerARoleConditionIsRefusedWithAReasonButAGroupConditionStillWorks() {
        scriptRunner = new ScriptRunnerStatus(false, false, null, false, "ScriptRunner for Jira is not installed.");

        ConfigureWorkflowResponse refused = configurer.configure(roles("ACTIVE", 11, false, "Administrators"));

        assertEquals("SCRIPTRUNNER_UNAVAILABLE", refused.getErrorCode());
        assertNull(refused.getPlanId());
        assertTrue(refused.getMessage(), refused.getMessage().contains("not installed"));
        ConfigureWorkflowResponse group = configurer.configure(group("ACTIVE", 11, "Team-B"));
        assertEquals("WOULD_CHANGE", group.getStatus());
        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(group)).getStatus());
    }

    @Test
    public void aDisabledScriptRunnerIsRefusedAsUnavailableForRoles() {
        scriptRunner = new ScriptRunnerStatus(true, false, "9.46.0", false, "ScriptRunner for Jira 9.46.0 is installed but not enabled.");

        ConfigureWorkflowResponse refused = configurer.configure(roles("ACTIVE", 11, false, "Administrators"));

        assertEquals("SCRIPTRUNNER_UNAVAILABLE", refused.getErrorCode());
        assertNull(refused.getPlanId());
        assertEquals("WOULD_CHANGE", configurer.configure(group("ACTIVE", 11, "Team-B")).getStatus());
    }

    @Test
    public void aVersionThatDiffersOnlyInThePatchPartIsRefusedWithTheInstalledAndTheVerifiedVersions() {
        scriptRunner = new ScriptRunnerStatus(true, true, "9.46.1", false, "gate text");

        ConfigureWorkflowResponse refused = configurer.configure(roles("ACTIVE", 11, false, "Administrators"));

        assertEquals("SCRIPTRUNNER_VERSION_UNSUPPORTED", refused.getErrorCode());
        assertNull(refused.getPlanId());
        assertTrue(refused.getMessage(), refused.getMessage().contains("9.46.1"));
        assertTrue(refused.getMessage(), refused.getMessage().contains("9.46.0"));
        assertTrue(refused.getMessage(), refused.getMessage().contains("Project role"));
        assertFalse("the gate's text about another format is not repeated", refused.getMessage().contains("Field(s) required"));
        assertEquals("WOULD_CHANGE", configurer.configure(group("ACTIVE", 11, "Team-B")).getStatus());
    }

    @Test
    public void aScriptRunnerThatChangedAfterTheRolePreviewMakesThePlanStaleWithoutWriting() {
        ConfigureWorkflowResponse unavailable = configurer.configure(roles("ACTIVE", 13, false, "Administrators"));
        scriptRunner = new ScriptRunnerStatus(true, false, "9.46.0", false, "not enabled");
        ConfigureWorkflowResponse r1 = configurer.configure(apply(unavailable));
        assertEquals("PREVIEW_STALE", r1.getErrorCode());

        scriptRunner = new ScriptRunnerStatus(true, true, "9.46.0", true, null);
        ConfigureWorkflowResponse updated = configurer.configure(roles("ACTIVE", 13, false, "Administrators"));
        scriptRunner = new ScriptRunnerStatus(true, true, "9.46.1", false, "text");
        assertEquals("PREVIEW_STALE", configurer.configure(apply(updated)).getErrorCode());

        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aGroupPlanDoesNotDependOnScriptRunnerAtApplyTime() {
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));
        scriptRunner = new ScriptRunnerStatus(false, false, null, false, "gone");

        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(preview)).getStatus());
    }

    // ---- validation -------------------------------------------------------------------------------------

    @Test
    public void unknownSystemAndStaleTargetsAreRefusedWithoutAPlan() {
        assertEquals("UNKNOWN_WORKFLOW", configurer.configure(new ConfigureWorkflowRequest(true, null, new ConfigureWorkflowRequest.Existing("Nope","ACTIVE","rev-0",null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, 11, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition("group", groupParams("Team-B")))))), null)).getErrorCode());
        assertEquals("VERSION_CONFLICT", configurer.configure(new ConfigureWorkflowRequest(true, null, new ConfigureWorkflowRequest.Existing(NAME,"ACTIVE","rev-99",null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, 11, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition("group", groupParams("Team-B")))))), null)).getErrorCode());
        system = true;
        assertEquals("SYSTEM_WORKFLOW", configurer.configure(group("ACTIVE", 11, "Team-B")).getErrorCode());
        system = false;
        ConfigureWorkflowResponse unknownAction = configurer.configure(group("ACTIVE", 999, "Team-B"));
        assertEquals("UNKNOWN_TRANSITION", unknownAction.getErrorCode());
        assertNull(unknownAction.getPlanId());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aCommonTransitionIsRefusedAsUnsupportedAndAGlobalAndAnInitialOneAreSupported() {
        ConfigureWorkflowResponse common = configurer.configure(group("ACTIVE", 41, "Team-B"));

        assertEquals("UNSUPPORTED_DESCRIPTOR", common.getErrorCode());
        assertNull(common.getPlanId());
        assertEquals("WOULD_CHANGE", configurer.configure(group("ACTIVE", 31, "Team-B")).getStatus());
        assertEquals("WOULD_CHANGE", configurer.configure(group("ACTIVE", 1, "Team-B")).getStatus());
    }

    @Test
    public void unknownAndBlankGroupsAreRefusedWithoutAPlan() {
        ConfigureWorkflowResponse unknown = configurer.configure(group("ACTIVE", 11, "No-Such-Group"));
        assertEquals("UNKNOWN_GROUP", unknown.getErrorCode());
        assertNull(unknown.getPlanId());
        assertTrue(unknown.getMessage(), unknown.getMessage().contains("No-Such-Group"));
        for (String blank : new String[] {"", "  ", null}) {
            ConfigureWorkflowResponse refused = configurer.configure(group("ACTIVE", 11, blank));
            assertEquals(String.valueOf(blank), "VALIDATION_ERROR", refused.getErrorCode());
            assertNull(refused.getPlanId());
        }
    }

    @Test
    public void unknownAndAmbiguousRolesAreRefusedAndNumbersAreIdsAndNamesAreExact() {
        ConfigureWorkflowResponse unknownName = configurer.configure(roles("ACTIVE", 11, false, "Nobody"));
        ConfigureWorkflowResponse unknownId = configurer.configure(roles("ACTIVE", 11, false, "99999"));
        ConfigureWorkflowResponse ambiguous = configurer.configure(roles("ACTIVE", 11, false, "Administrators", "Twin"));
        ConfigureWorkflowResponse wrongCase = configurer.configure(roles("ACTIVE", 11, false, "administrators"));

        assertEquals("UNKNOWN_ROLE", unknownName.getErrorCode());
        assertEquals("UNKNOWN_ROLE", unknownId.getErrorCode());
        assertEquals("AMBIGUOUS_ROLE", ambiguous.getErrorCode());
        assertEquals("names are exact", "UNKNOWN_ROLE", wrongCase.getErrorCode());
        assertTrue(unknownName.getMessage(), unknownName.getMessage().contains("Nobody"));
        assertTrue(ambiguous.getMessage(), ambiguous.getMessage().contains("Twin"));
        for (ConfigureWorkflowResponse refused : Arrays.asList(unknownName, unknownId, ambiguous, wrongCase)) {
            assertNull(refused.getPlanId());
        }
        assertEquals("WOULD_CHANGE", configurer.configure(roles("ACTIVE", 13, false, "10002")).getStatus());
    }

    @Test
    public void emptyRepeatedTooLongAndBlankRoleListsAreRefused() {
        List<String> tooMany = new ArrayList<String>();
        for (int i = 0; i <= 20; i++) tooMany.add("1" + (1000 + i));
        List<ConfigureWorkflowRequest> bad = Arrays.asList(
                rolesRequest(true, 11, "project_role", new ConfigureWorkflowRequest.Parameters(null, null, Collections.<String>emptyList(), null)),
                rolesRequest(true, 11, "project_role", new ConfigureWorkflowRequest.Parameters(null, null, null, null)),
                rolesRequest(true, 11, "project_role", new ConfigureWorkflowRequest.Parameters(null, null, Arrays.asList("Administrators", "10002"), null)),
                rolesRequest(true, 11, "project_role", new ConfigureWorkflowRequest.Parameters(null, null, Arrays.asList("10002", "10002"), null)),
                rolesRequest(true, 11, "project_role", new ConfigureWorkflowRequest.Parameters(null, null, Arrays.asList("10002", " "), null)),
                rolesRequest(true, 11, "project_role", new ConfigureWorkflowRequest.Parameters(null, null, Arrays.asList("10002", null), null)),
                rolesRequest(true, 11, "project_role", new ConfigureWorkflowRequest.Parameters(null, null, tooMany, null)));
        for (ConfigureWorkflowRequest request : bad) {
            ConfigureWorkflowResponse refused = configurer.configure(request);
            assertEquals(request.transitionRules().get(0).addConditions().rules().get(0).parameters().roles() + "", "VALIDATION_ERROR", refused.getErrorCode());
            assertNull(refused.getPlanId());
        }
        String maxOk = configurer.configure(rolesRequest(true, 11, "project_role",
                new ConfigureWorkflowRequest.Parameters(null, null, Arrays.asList("10002", "10101", "10100", "10001"), null))).getErrorCode();
        assertNull(maxOk);
    }

    @Test
    public void anUnknownTemplateParametersOfAnotherTemplateAndMissingValuesAreRefused() {
        List<ConfigureWorkflowRequest> bad = Arrays.asList(
                rolesRequest(true, 11, "comment_required", null),
                rolesRequest(true, 11, null, groupParams("Team-B")),
                rolesRequest(true, 11, "group", null),
                rolesRequest(true, 11, "group", new ConfigureWorkflowRequest.Parameters(null, "Team-B", Arrays.asList("10002"), null)),
                rolesRequest(true, 11, "group", new ConfigureWorkflowRequest.Parameters(null, "Team-B", null, Boolean.TRUE)),
                rolesRequest(true, 11, "project_role", new ConfigureWorkflowRequest.Parameters(null, "Team-B", Arrays.asList("10002"), null)),
                rolesRequest(true, 11, "project_role", groupParams("Team-B")),
                new ConfigureWorkflowRequest(true, null, new ConfigureWorkflowRequest.Existing(" ","ACTIVE","rev-0",null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, 11, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition("group", groupParams("Team-B")))))), null),
                new ConfigureWorkflowRequest(true, null, new ConfigureWorkflowRequest.Existing(NAME,"BOTH","rev-0",null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, 11, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition("group", groupParams("Team-B")))))), null),
                new ConfigureWorkflowRequest(true, null, new ConfigureWorkflowRequest.Existing(NAME,"ACTIVE"," ",null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, 11, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition("group", groupParams("Team-B")))))), null),
                new ConfigureWorkflowRequest(true, null, new ConfigureWorkflowRequest.Existing(NAME,"ACTIVE","rev-0",null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, null, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition("group", groupParams("Team-B")))))), null));
        for (ConfigureWorkflowRequest request : bad) {
            ConfigureWorkflowResponse refused = configurer.configure(request);
            assertEquals(request.transitionRules().get(0).addConditions().rules().get(0).template() + " " + request.transitionRules().get(0).addConditions().rules().get(0).parameters(), "VALIDATION_ERROR",
                    refused.getErrorCode());
            assertNull(refused.getPlanId());
        }
        assertEquals("the same request with valid values is planned", "WOULD_CHANGE",
                configurer.configure(group("ACTIVE", 11, "Team-B")).getStatus());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void applyTakesOnlyThePlanAndAPreviewTakesNoPlanAndTheModeIsExplicit() {
        for (ConfigureWorkflowRequest invalid : Arrays.asList(
                new ConfigureWorkflowRequest(false, null, new ConfigureWorkflowRequest.Existing(NAME,"ACTIVE","rev-0",null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, 11, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition("group", groupParams("Team-B")))))), "p"),
                new ConfigureWorkflowRequest(false, null, new ConfigureWorkflowRequest.Existing(NAME,null,null,null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, null, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition(null, null))))), "p"),
                new ConfigureWorkflowRequest(false, null, new ConfigureWorkflowRequest.Existing(null,null,null,null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, null, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition(null, null))))), null),
                new ConfigureWorkflowRequest(false, null, new ConfigureWorkflowRequest.Existing(null,null,null,null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, null, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition(null, null))))), " "),
                new ConfigureWorkflowRequest(true, null, new ConfigureWorkflowRequest.Existing(NAME,"ACTIVE","rev-0",null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, 11, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition("group", groupParams("Team-B")))))), "p"),
                new ConfigureWorkflowRequest(null, null, new ConfigureWorkflowRequest.Existing(NAME,"ACTIVE","rev-0",null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, 11, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition("group", groupParams("Team-B")))))), null))) {
            try {
                configurer.configure(invalid);
                fail("expected a refusal");
            } catch (InvalidRequestException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void aDefinitionThatJirasOwnSaveWouldNotReproduceIsNotChanged() {
        when(manager.getWorkflowClone(NAME)).thenAnswer(call -> jw(active, false));
        DescriptorFacts.meta(action(active, 13)).put("acme.note", "bad\u0001value");

        ConfigureWorkflowResponse refused = configurer.configure(group("ACTIVE", 13, "Team-B"));

        assertEquals("UNSUPPORTED_DESCRIPTOR", refused.getErrorCode());
        assertNull(refused.getPlanId());
    }

    @Test
    public void aGroupNameThatWouldNotBeStoredExactlyIsNotWritten() {
        group("Odd<&\u0001group", 1);

        ConfigureWorkflowResponse refused = configurer.configure(group("ACTIVE", 13, "Odd<&\u0001group"));

        assertEquals("UNSUPPORTED_DESCRIPTOR", refused.getErrorCode());
        assertNull(refused.getPlanId());
    }

    // ---- stale plans ------------------------------------------------------------------------------------

    @Test
    public void aPlanIsSingleUse() {
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));
        assertEquals("UPDATED_INACTIVE", configurer.configure(apply(preview)).getStatus());

        ConfigureWorkflowResponse again = configurer.configure(apply(preview));

        assertEquals("PREVIEW_STALE", again.getErrorCode());
        verify(manager, times(1)).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aPlanThatNeverExistedIsStale() {
        ConfigureWorkflowResponse result = configurer.configure(new ConfigureWorkflowRequest(false, null, null, null, null, "no-such-plan"));

        assertEquals("PREVIEW_STALE", result.getErrorCode());
        assertEquals("FAILED", result.getStatus());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aWorkflowThatChangedAfterThePreviewIsNotWritten() {
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));
        DescriptorFacts.locate(active).get(13).action().setName("Plain!");

        ConfigureWorkflowResponse result = configurer.configure(apply(preview));

        assertEquals("PREVIEW_STALE", result.getErrorCode());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aChangeOfTheUsersOfTheWorkflowAfterThePreviewMakesThePlanStale() {
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));
        consumersVersion++;

        assertEquals("PREVIEW_STALE", configurer.configure(apply(preview)).getErrorCode());
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
    }

    @Test
    public void aDraftThatAppearedAfterThePreviewIsNotOverwrittenByTheDraftCreationPlan() {
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));
        draft = copy(active);
        revision++;

        assertEquals("PREVIEW_STALE", configurer.configure(apply(preview)).getErrorCode());
        verify(manager, never()).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aGroupThatWasDeletedOrRenamedAfterThePreviewMakesThePlanStale() {
        ConfigureWorkflowResponse deleted = configurer.configure(group("ACTIVE", 11, "Team-B"));
        groupNames.remove("team-b");
        assertEquals("PREVIEW_STALE", configurer.configure(apply(deleted)).getErrorCode());

        group("Team-B", 3);
        ConfigureWorkflowResponse renamed = configurer.configure(group("ACTIVE", 11, "Team-B"));
        groupNames.put("team-b", "TEAM-B");
        assertEquals("PREVIEW_STALE", configurer.configure(apply(renamed)).getErrorCode());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    @Test
    public void aRoleThatWasDeletedOrRenamedAfterThePreviewMakesThePlanStale() {
        ConfigureWorkflowResponse deleted = configurer.configure(roles("ACTIVE", 11, false, "Administrators", "Developers"));
        allRoles.removeIf(r -> r.getId() == 10001L);
        assertEquals("PREVIEW_STALE", configurer.configure(apply(deleted)).getErrorCode());

        ConfigureWorkflowResponse renamed = configurer.configure(roles("ACTIVE", 13, false, "10002"));
        for (ProjectRole r : allRoles) if (r.getId() == 10002L) when(r.getName()).thenReturn("Admins");
        assertEquals("PREVIEW_STALE", configurer.configure(apply(renamed)).getErrorCode());
        verify(manager, never()).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));
    }

    // ---- warnings ---------------------------------------------------------------------------------------

    @Test
    public void addedConditionsExplainTheAndRootAndNestedConditionsRemainNested() {
        ConfigureWorkflowResponse withGroup = configurer.configure(group("ACTIVE", 11, "Team-B"));
        ConfigureWorkflowResponse without = configurer.configure(group("ACTIVE", 12, "Team-B"));
        ConfigureWorkflowResponse noConditions = configurer.configure(group("ACTIVE", 13, "Team-B"));
        ConfigureWorkflowResponse inCompound = configurer.configure(group("ACTIVE", 14, "Team-B"));
        ConfigureWorkflowResponse role = configurer.configure(roles("ACTIVE", 11, false, "Administrators"));

        assertNotNull("a warning never withholds the plan", withGroup.getPlanId());
        assertTrue(String.join(" ", withGroup.getWarnings()).contains("All conditions"));
        assertEquals(Arrays.asList("OTHER", "PROJECT_ROLE", "GROUP", "GROUP"), kinds(withGroup));
        assertTrue(String.join(" ", without.getWarnings()).contains("All conditions"));
        assertTrue(String.join(" ", noConditions.getWarnings()).contains("All conditions"));
        assertEquals("COMPOUND", conditionTree(inCompound).getChildren().get(1).getKind());
        assertEquals("OR", conditionTree(inCompound).getChildren().get(1).getOperator());
        assertEquals("UNCHANGED", role.getStatus());
        assertEquals("PROJECT_ROLE", conditionTree(role).getChildren().get(1).getKind());
    }

    @Test
    public void aGroupWithoutMembersWarnsThatNobodyCanExecuteTheTransition() {
        ConfigureWorkflowResponse empty = configurer.configure(group("ACTIVE", 13, "Empty-G"));
        ConfigureWorkflowResponse members = configurer.configure(group("ACTIVE", 13, "Team-B"));

        assertNotNull(empty.getPlanId());
        String observation = String.join(" ", empty.getWarnings());
        assertTrue(observation, observation.contains("Empty-G"));
        assertTrue(observation, observation.contains("nobody") && observation.contains("remains empty"));
        assertTrue(observation, observation.contains("membership may change"));
        assertTrue(String.join(" ", members.getWarnings()).contains("3 users including inactive"));
    }

    @Test
    public void invertWarnsThatEveryoneExceptTheMembersGetsAccessAndPlainDoesNot() {
        ConfigureWorkflowResponse inverted = configurer.configure(roles("ACTIVE", 13, true, "Administrators"));
        ConfigureWorkflowResponse plain = configurer.configure(roles("ACTIVE", 13, false, "Administrators"));

        assertNotNull(inverted.getPlanId());
        assertEquals(Boolean.TRUE, lastCondition(inverted).getInverted());
        assertTrue(transition(inverted).get("conditionSemantics").toString().contains("member of none"));
        assertEquals(Boolean.FALSE, lastCondition(plain).getInverted());
        assertTrue(transition(plain).get("conditionSemantics").toString().contains("member of any"));
    }

    @Test
    public void aSharedDraftWarnsThatPublishingChangesEveryProjectOfTheWorkflow() {
        isActive = true;

        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 13, "Team-B"));

        assertEquals(Arrays.asList(
                "Workflow changes are shared by every project using this workflow. Control locks do not protect against Jira UI or other plugins.",
                "The draft is shared by all administrators. Publishing it later affects every project using this workflow; use clone_project_workflows first when only one project should change. No publication or project assignment is performed.",
                "Group 'Team-B': observed 3 users including inactive users; membership may change.",
                "Transition 13: 1 unrecognized validator(s) preserved and not analyzed.",
                "All conditions of an AND root must be satisfied. Conditions narrow access for every project using this workflow, including future projects; group membership is only an observation and may change."),
                preview.getWarnings());
    }

    // ---- failures of the write ----------------------------------------------------------------------------------

    @Test
    public void aFailureAfterTheDraftWasCreatedSaysSoAndNeverRetriesOrDeletes() {
        isActive = true;
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));
        doThrow(new IllegalStateException("db down")).when(manager)
                .updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));

        ConfigureWorkflowResponse result = configurer.configure(apply(preview));

        assertEquals("PARTIAL", result.getStatus());
        assertEquals("JIRA_ERROR", result.getErrorCode());
        assertEquals("APPLIED", receiptStep(result, "draft").get("writeStatus"));
        assertEquals("UNKNOWN", receiptStep(result, "workflow-save").get("writeStatus"));
        assertNotNull("the draft that Jira created is still there", draft);
        verify(manager, times(1)).createDraftWorkflow(any(ApplicationUser.class), anyString());
        verify(manager, never()).deleteDraftWorkflow(anyString());
    }

    @Test
    public void aSaveThatDoesNotReadBackIsReportedAsUncertainNotAsSuccess() {
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));
        doAnswer(call -> {
            revision++; // Jira reports success but stores nothing
            return null;
        }).when(manager).updateWorkflow(any(ApplicationUser.class), any(JiraWorkflow.class));

        ConfigureWorkflowResponse result = configurer.configure(apply(preview));

        assertEquals("PARTIAL", result.getStatus());
        assertEquals("JIRA_ERROR", result.getErrorCode());
        assertEquals("APPLIED", receiptStep(result, "workflow-save").get("writeStatus"));
        assertEquals("MISMATCH", receiptStep(result, "workflow-save").get("verification"));
        assertTrue(result.getMessage(), result.getMessage().contains("No automatic retry"));
    }

    @Test
    public void theWriteRunsUnderTheWorkflowLockExactlyOnce() {
        ConfigureWorkflowResponse preview = configurer.configure(group("ACTIVE", 11, "Team-B"));

        configurer.configure(apply(preview));

        verify(lock, times(4)).run(anyString(), anyString(), any());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private ConfigureWorkflowRequest group(String version, int actionId, String group) {
        return new ConfigureWorkflowRequest(true, null, new ConfigureWorkflowRequest.Existing(NAME,version,"rev-" + revision,null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, actionId, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition("group", groupParams(group)))))), null);
    }

    private ConfigureWorkflowRequest roles(String version, int actionId, boolean invert, String... roles) {
        return new ConfigureWorkflowRequest(true, null, new ConfigureWorkflowRequest.Existing(NAME,version,"rev-" + revision,null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, actionId, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition("project_role", new ConfigureWorkflowRequest.Parameters(null, null, Arrays.asList(roles), invert)))))), null);
    }

    private ConfigureWorkflowRequest rolesRequest(boolean dryRun, int actionId, String template,
                                                    ConfigureWorkflowRequest.Parameters parameters) {
        return new ConfigureWorkflowRequest(dryRun, null, new ConfigureWorkflowRequest.Existing(NAME,"ACTIVE","rev-" + revision,null), null, Collections.singletonList(new ConfigureWorkflowRequest.TransitionRule(null, actionId, null, null, new ConfigureWorkflowRequest.Conditions("AND", Collections.singletonList(new ConfigureWorkflowRequest.Condition(template, parameters))))), null);
    }

    private static ConfigureWorkflowRequest.Parameters groupParams(String group) {
        return new ConfigureWorkflowRequest.Parameters(null, group, null, null);
    }

    private static ConfigureWorkflowRequest apply(ConfigureWorkflowResponse preview) {
        return new ConfigureWorkflowRequest(false, null, null, null, null, preview.getPlanId());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> receiptStep(ConfigureWorkflowResponse response, String key) {
        return ((List<Map<String, Object>>) response.getExecution().get("steps")).stream()
                .filter(step -> key.equals(step.get("key"))).findFirst().orElseThrow();
    }

    private static Map<String, Object> transition(ConfigureWorkflowResponse response) {
        return response.getTransitions().get(0);
    }

    private static ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView.ConditionNode conditionTree(ConfigureWorkflowResponse response) {
        return (ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView.ConditionNode) transition(response).get("conditions");
    }

    private static ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView.ConditionNode lastCondition(ConfigureWorkflowResponse response) {
        List<ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView.ConditionNode> nodes = conditionTree(response).getChildren();
        return nodes.get(nodes.size() - 1);
    }

    private static List<String> kinds(ConfigureWorkflowResponse preview) {
        List<String> kinds = new ArrayList<String>();
        for (ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView.ConditionNode node : conditionTree(preview).getChildren()) {
            kinds.add(node.getKind());
        }
        return kinds;
    }

    private static List<ConditionDescriptor> conditions(WorkflowDescriptor d, int actionId) {
        List<ConditionDescriptor> list = new ArrayList<ConditionDescriptor>();
        ActionDescriptor action = action(d, actionId);
        for (Object o : action.getRestriction().getConditionsDescriptor().getConditions()) list.add((ConditionDescriptor) o);
        return list;
    }

    private static List<String> conditionXml(WorkflowDescriptor d, int actionId) {
        List<String> xml = new ArrayList<String>();
        for (ConditionDescriptor c : conditions(d, actionId)) xml.add(DescriptorFacts.xml(c));
        return xml;
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
