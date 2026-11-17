package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.workflow.api.dto.edit.ScriptRunnerStatus;
import ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView;
import ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView.ConditionNode;
import ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView.TransitionConditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.edit.ConditionFixtures.workflow;

/** {@code get_workflow_conditions}: what a transition's conditions are read as, from the stored descriptor. */
public class WorkflowConditionReaderImplTest {

    private static final String NAME = "Support";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper GETTER_BLIND = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false)
            .configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false);

    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final WorkflowInspector inspector = mock(WorkflowInspector.class);
    private final ScriptRunnerGate gate = mock(ScriptRunnerGate.class);
    private final ProjectRoleManager roles = mock(ProjectRoleManager.class);

    private WorkflowDescriptor active;
    private WorkflowDescriptor draft;
    private ScriptRunnerStatus scriptRunner = new ScriptRunnerStatus(true, true, "9.46.0", true, null);
    private WorkflowConditionReaderImpl reader;

    @Before
    public void setUp() {
        active = workflow();
        when(guard.requireAdministrator()).thenReturn(mock(ApplicationUser.class));
        when(inspector.snapshot(anyString())).thenAnswer(call -> NAME.equals(call.getArgument(0))
                ? new WorkflowInspector.Snapshot(NAME, jw(active), draft == null ? null : jw(draft), draft != null,
                false, "rev-7")
                : null);
        when(gate.conditionStatus()).thenAnswer(call -> scriptRunner);
        role(10002L, "Administrators");
        role(10101L, "Service Desk Team");
        role(10100L, "Service Desk Customers");
        reader = new WorkflowConditionReaderImpl(guard, inspector, gate, roles);
    }

    private void role(long id, String name) {
        ProjectRole role = mock(ProjectRole.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        when(roles.getProjectRole(id)).thenReturn(role);
    }

    private static JiraWorkflow jw(WorkflowDescriptor d) {
        JiraWorkflow workflow = mock(JiraWorkflow.class);
        when(workflow.getName()).thenReturn(NAME);
        when(workflow.getDescriptor()).thenReturn(d);
        return workflow;
    }

    private static TransitionConditions transition(WorkflowConditionsView view, int actionId) {
        for (TransitionConditions t : view.getTransitions()) if (t.getActionId() == actionId) return t;
        throw new AssertionError("transition " + actionId + " is not in the view");
    }

    @Test
    public void theCfltbSampleTransitionIsReadWithRoleNamesTheRevisionAndTheScriptRunnerStatus() {
        WorkflowConditionsView view = reader.read(NAME, "ACTIVE");

        assertEquals(NAME, view.getWorkflow());
        assertEquals("ACTIVE", view.getVersion());
        assertEquals("rev-7", view.getRevision());
        assertTrue(view.getScriptRunner().isSupported());
        TransitionConditions done = transition(view, 11);
        assertEquals("Done", done.getName());
        ConditionNode root = done.getRoot();
        assertEquals("COMPOUND", root.getKind());
        assertEquals("AND", root.getOperator());
        assertEquals(Arrays.asList("OTHER", "PROJECT_ROLE", "GROUP"), kinds(root));
        assertEquals(Arrays.asList(10002L), root.getChildren().get(1).getRoleIds());
        assertEquals("Administrators", root.getChildren().get(1).getRoles().get(0).getName());
        assertEquals(Boolean.FALSE, root.getChildren().get(1).getInverted());
        assertEquals("jira-administrators", root.getChildren().get(2).getGroup());
        assertEquals("com.atlassian.jira.workflow.condition.PermissionCondition", root.getChildren().get(0).getClassName());
    }

    @Test
    public void everyKindOfTransitionIsListedAndOnesWithoutConditionsHaveNoRoot() {
        WorkflowConditionsView view = reader.read(NAME, "ACTIVE");

        List<Integer> ids = new ArrayList<Integer>();
        for (TransitionConditions t : view.getTransitions()) ids.add(t.getActionId());
        assertEquals(Arrays.asList(1, 31, 41, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 19), ids);
        assertNull(transition(view, 13).getRoot());
        assertNull(transition(view, 31).getRoot());
        assertEquals("AND", transition(view, 12).getRoot().getOperator());
        assertEquals("a common action is read like the others", "AND", transition(view, 41).getRoot().getOperator());
        assertEquals("OR", transition(view, 15).getRoot().getOperator());
        assertEquals("OR", transition(view, 14).getRoot().getChildren().get(1).getOperator());
    }

    @Test
    public void aRoleThatJiraDoesNotHaveIsShownWithItsIdAndNoName() {
        when(roles.getProjectRole(10101L)).thenReturn(null);

        WorkflowConditionsView view = reader.read(NAME, "ACTIVE");

        ConditionNode two = transition(view, 17).getRoot().getChildren().get(0);
        assertEquals(Arrays.asList(10002L, 10101L), two.getRoleIds());
        assertEquals("Administrators", two.getRoles().get(0).getName());
        assertEquals(10101L, two.getRoles().get(1).getId());
        assertNull(two.getRoles().get(1).getName());
    }

    @Test
    public void readingNeedsNoScriptRunnerAndSaysWhyItCannotBeConfigured() {
        scriptRunner = new ScriptRunnerStatus(false, false, null, false, "ScriptRunner for Jira is not installed.");

        WorkflowConditionsView view = reader.read(NAME, "ACTIVE");

        assertFalse(view.getScriptRunner().isSupported());
        assertEquals("ScriptRunner for Jira is not installed.", view.getScriptRunner().getReason());
        assertEquals("PROJECT_ROLE", transition(view, 11).getRoot().getChildren().get(1).getKind());
    }

    @Test
    public void anUnsupportedScriptRunnerReportsTheConditionFormatWithoutPreventingTheRead() {
        com.atlassian.plugin.PluginAccessor plugins = mock(com.atlassian.plugin.PluginAccessor.class);
        com.atlassian.plugin.Plugin plugin = mock(com.atlassian.plugin.Plugin.class);
        com.atlassian.plugin.PluginInformation information = mock(com.atlassian.plugin.PluginInformation.class);
        when(plugins.getPlugin(ScriptRunnerGate.PLUGIN_KEY)).thenReturn(plugin);
        when(plugins.isPluginEnabled(ScriptRunnerGate.PLUGIN_KEY)).thenReturn(true);
        when(plugin.getPluginInformation()).thenReturn(information);
        when(information.getVersion()).thenReturn("9.46.1");
        WorkflowConditionReaderImpl actualGateReader =
                new WorkflowConditionReaderImpl(guard, inspector, new ScriptRunnerGate(plugins), roles);

        WorkflowConditionsView view = actualGateReader.read(NAME, "ACTIVE");

        assertFalse(view.getScriptRunner().isSupported());
        String reason = view.getScriptRunner().getReason();
        assertTrue(reason, reason.contains("Project role(s) condition"));
        assertFalse(reason, reason.contains("Field(s) required"));
        assertTrue(reason, reason.contains("9.46.1"));
        assertTrue(reason, reason.contains("9.46.0"));
        assertEquals("PROJECT_ROLE", transition(view, 11).getRoot().getChildren().get(1).getKind());
    }

    @Test
    public void anonymousAndNonAdministratorReadsAreDeniedBeforeAnyWorkflowOrPluginIsRead() {
        for (boolean anonymous : new boolean[] {true, false}) {
            AccessException denied = new AccessException(anonymous ? "Authentication required" : "Administrator required", anonymous);
            doThrow(denied).when(guard).requireAdministrator();
            try {
                reader.read(NAME, "ACTIVE");
                fail("unauthorized conditions must not be disclosed");
            } catch (AccessException expected) {
                org.junit.Assert.assertSame(denied, expected);
            }
            verifyNoInteractions(inspector, gate, roles);
        }
    }

    @Test
    public void versionDraftReadsTheDraftAndVersionActiveTheActiveOne() {
        draft = workflow();
        ConditionDescriptors.apply(ConditionFixtures.action(draft, 13), ConditionDescriptors.Candidate.group("Only-draft"),
                "11111111-2222-3333-4444-555555555555");

        WorkflowConditionsView fromDraft = reader.read(NAME, "DRAFT");
        WorkflowConditionsView fromActive = reader.read(NAME, "ACTIVE");

        assertEquals("Only-draft", transition(fromDraft, 13).getRoot().getChildren().get(0).getGroup());
        assertNull(transition(fromActive, 13).getRoot());
    }

    @Test
    public void aMissingDraftAWrongVersionAnUnknownWorkflowAndABlankNameAreRefusedWithoutSubstitution() {
        for (String[] args : new String[][] {{NAME, "DRAFT"}, {NAME, "BOTH"}, {NAME, null}, {"Nope", "ACTIVE"}, {" ", "ACTIVE"},
                {null, "ACTIVE"}}) {
            try {
                reader.read(args[0], args[1]);
                fail(Arrays.toString(args));
            } catch (InvalidRequestException expected) {
                assertTrue(expected.getMessage(), !expected.getMessage().isEmpty());
            }
        }
    }

    @Test
    public void theAnswerSerializesWithAllDocumentedKeysIncludingNulls() throws Exception {
        WorkflowConditionsView view = reader.read(NAME, "ACTIVE");

        JsonNode json = MAPPER.readTree(GETTER_BLIND.writeValueAsString(view));

        assertEquals(keys("workflow", "version", "revision", "scriptRunner", "transitions"), names(json));
        JsonNode transition = json.get("transitions").get(4);
        assertEquals(keys("actionId", "name", "root"), names(transition));
        Set<String> nodeKeys = keys("position", "kind", "operator", "children", "group", "roleIds", "roles", "inverted",
                "type", "className", "cannedScript");
        JsonNode root = transition.get("root");
        assertEquals(nodeKeys, names(root));
        for (JsonNode child : root.get("children")) assertEquals(nodeKeys, names(child));
        assertTrue(root.get("children").get(2).get("roleIds").isNull());
        assertEquals(keys("id", "name"), names(root.get("children").get(1).get("roles").get(0)));
        assertTrue(json.get("transitions").get(6).get("root").isNull());
    }

    private static List<String> kinds(ConditionNode node) {
        List<String> kinds = new ArrayList<String>();
        for (ConditionNode child : node.getChildren()) kinds.add(child.getKind());
        return kinds;
    }

    private static Set<String> keys(String... names) {
        return new TreeSet<String>(Arrays.asList(names));
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new TreeSet<String>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
