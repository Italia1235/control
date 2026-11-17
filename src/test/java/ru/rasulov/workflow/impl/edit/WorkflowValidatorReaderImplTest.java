package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.issue.fields.Field;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.user.ApplicationUser;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.workflow.api.dto.edit.ScriptRunnerStatus;
import ru.rasulov.workflow.api.dto.edit.WorkflowValidatorsView;
import ru.rasulov.workflow.api.dto.edit.WorkflowValidatorsView.TransitionValidators;
import ru.rasulov.workflow.api.dto.edit.WorkflowValidatorsView.ValidatorView;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.edit.ValidatorFixtures.*;

/** {@code get_workflow_validators}: what a transition's validators are read as, from the stored descriptor. */
public class WorkflowValidatorReaderImplTest {

    private static final String NAME = "Support";
    private static final ObjectMapper GETTER_BLIND = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false)
            .configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false);

    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final WorkflowInspector inspector = mock(WorkflowInspector.class);
    private final FieldManager fields = mock(FieldManager.class);
    private final FieldScreenManager screens = mock(FieldScreenManager.class);
    private final ScriptRunnerGate gate = mock(ScriptRunnerGate.class);

    private WorkflowDescriptor active;
    private WorkflowDescriptor draft;
    private ScriptRunnerStatus scriptRunner = new ScriptRunnerStatus(true, true, "9.46.0", true, null);
    private WorkflowValidatorReaderImpl reader;

    @Before
    public void setUp() {
        active = workflow();
        when(guard.requireAdministrator()).thenReturn(mock(ApplicationUser.class));
        when(inspector.snapshot(anyString())).thenAnswer(call -> NAME.equals(call.getArgument(0))
                ? new WorkflowInspector.Snapshot(NAME, jw(active), draft == null ? null : jw(draft), draft != null,
                false, "rev-7")
                : null);
        when(gate.status()).thenAnswer(call -> scriptRunner);
        field("assignee", "Assignee");
        field("customfield_10118", "Причина для \"На рассмотрении\"");
        field("description", "Description");
        FieldScreen screen = mock(FieldScreen.class);
        when(screen.getName()).thenReturn("Start screen");
        when(screens.getFieldScreen(9L)).thenReturn(screen);
        FieldScreen close = mock(FieldScreen.class);
        when(close.getName()).thenReturn("Close screen");
        when(screens.getFieldScreen(7L)).thenReturn(close);
        reader = new WorkflowValidatorReaderImpl(guard, inspector, fields, screens, gate);
    }

    private void field(String id, String name) {
        Field field = mock(Field.class);
        when(field.getId()).thenReturn(id);
        when(field.getName()).thenReturn(name);
        when(fields.getField(id)).thenReturn(field);
    }

    private static JiraWorkflow jw(WorkflowDescriptor d) {
        JiraWorkflow workflow = mock(JiraWorkflow.class);
        when(workflow.getName()).thenReturn(NAME);
        when(workflow.getDescriptor()).thenReturn(d);
        return workflow;
    }

    private static TransitionValidators transition(WorkflowValidatorsView view, int actionId) {
        for (TransitionValidators t : view.getTransitions()) if (t.getActionId() == actionId) return t;
        throw new AssertionError("transition " + actionId + " is not in the view");
    }

    @Test
    public void aTransitionShowsItsValidatorsInDescriptorOrderWithFieldNamesAndTheOtherOneWithoutArguments() {
        WorkflowValidatorsView view = reader.read(NAME, "ACTIVE");

        assertEquals(NAME, view.getWorkflow());
        assertEquals("ACTIVE", view.getVersion());
        List<ValidatorView> validators = transition(view, 21).getValidators();
        assertEquals(3, validators.size());

        ValidatorView first = validators.get(0);
        assertEquals(1, first.getPosition());
        assertEquals("REQUIRED_FIELDS", first.getKind());
        assertEquals(Arrays.asList("assignee", "customfield_10118"), first.getFieldIds());
        assertEquals(2, first.getFields().size());
        assertEquals("assignee", first.getFields().get(0).getId());
        assertEquals("Assignee", first.getFields().get(0).getName());
        assertEquals("customfield_10118", first.getFields().get(1).getId());
        assertEquals("Причина для \"На рассмотрении\"", first.getFields().get(1).getName());
        assertEquals(Boolean.FALSE, first.getConditional());

        ValidatorView second = validators.get(1);
        assertEquals(2, second.getPosition());
        assertEquals("REQUIRED_FIELDS", second.getKind());
        assertEquals(Arrays.asList("description"), second.getFieldIds());
        assertEquals(Boolean.TRUE, second.getConditional());

        ValidatorView third = validators.get(2);
        assertEquals(3, third.getPosition());
        assertEquals("OTHER", third.getKind());
        assertEquals("class", third.getType());
        assertEquals(RequiredFieldsDescriptor.CLASS_NAME, third.getClassName());
        assertEquals(COMMENT_CANNED, third.getCannedScript());
        assertNull(third.getFieldIds());
        assertNull(third.getFields());
        assertNull(third.getConditional());
    }

    @Test
    public void theRevisionIsTheOneThatGetWorkflowGivesForTheSameWorkflow() {
        assertEquals("rev-7", reader.read(NAME, "ACTIVE").getRevision());
        draft = workflow();
        assertEquals("rev-7", reader.read(NAME, "DRAFT").getRevision());
    }

    @Test
    public void validatorsOfOtherKindsNeverRevealTheirArguments() throws Exception {
        String json = GETTER_BLIND.writeValueAsString(reader.read(NAME, "ACTIVE"));

        assertFalse(json, json.contains("TOP-SECRET-NOTE"));
        assertFalse(json, json.contains("NOTE"));
        assertFalse(json, json.contains("Resolve Issue"));
        assertFalse("the function id is not part of the answer", json.contains(ID_1));
        assertTrue(json, json.contains("com.atlassian.jira.workflow.validator.PermissionValidator"));
    }

    @Test
    public void everyKindOfTransitionIsListedWithItsOwnValidators() {
        WorkflowValidatorsView view = reader.read(NAME, "ACTIVE");

        assertEquals(Arrays.asList(1, 31, 41, 11, 21, 22, 23, 24, 25, 26),
                view.getTransitions().stream().map(TransitionValidators::getActionId).collect(java.util.stream.Collectors.toList()));
        assertEquals(1, transition(view, 1).getValidators().size());
        assertEquals("OTHER", transition(view, 1).getValidators().get(0).getKind());
        assertTrue(transition(view, 31).getValidators().isEmpty());
        assertEquals("a common action is shown with the validators it really has", "REQUIRED_FIELDS",
                transition(view, 41).getValidators().get(0).getKind());
        assertEquals("a validator of a form that was not captured is shown as OTHER", "OTHER",
                transition(view, 25).getValidators().get(0).getKind());
        assertEquals(RequiredFieldsDescriptor.CANNED_SCRIPT, transition(view, 25).getValidators().get(0).getCannedScript());
    }

    @Test
    public void theTransitionScreenIsShownWithItsName() {
        WorkflowValidatorsView view = reader.read(NAME, "ACTIVE");

        assertEquals("9", transition(view, 11).getScreenId());
        assertEquals("Start screen", transition(view, 11).getScreenName());
        assertEquals("7", transition(view, 41).getScreenId());
        assertEquals("Close screen", transition(view, 41).getScreenName());
        assertNull(transition(view, 21).getScreenId());
        assertNull(transition(view, 21).getScreenName());
    }

    @Test
    public void aFieldThatJiraNoLongerKnowsKeepsItsIdAndHasNoName() {
        when(fields.getField("customfield_10118")).thenReturn(null);

        ValidatorView first = transition(reader.read(NAME, "ACTIVE"), 21).getValidators().get(0);

        assertEquals(Arrays.asList("assignee", "customfield_10118"), first.getFieldIds());
        assertEquals("Assignee", first.getFields().get(0).getName());
        assertEquals("customfield_10118", first.getFields().get(1).getId());
        assertNull(first.getFields().get(1).getName());
    }

    @Test
    public void versionDraftShowsTheDraftAndVersionActiveTheActiveVersion() {
        draft = workflow();
        RequiredFieldsDescriptor.apply(action(draft, 26), RequiredFieldsDescriptor.plan(action(draft, 26),
                Arrays.asList("description")), "3f6a9d62-2a4e-4a33-b2f0-4f0c4d5e6f70");

        assertTrue(transition(reader.read(NAME, "ACTIVE"), 26).getValidators().isEmpty());
        List<ValidatorView> inDraft = transition(reader.read(NAME, "DRAFT"), 26).getValidators();
        assertEquals(1, inDraft.size());
        assertEquals(Arrays.asList("description"), inDraft.get(0).getFieldIds());
        assertEquals("DRAFT", reader.read(NAME, "DRAFT").getVersion());
    }

    @Test
    public void readingWorksWithoutScriptRunnerAndSaysWhyItCannotBeConfigured() {
        scriptRunner = new ScriptRunnerStatus(false, false, null, false,
                "ScriptRunner for Jira is not installed. Verified versions: 9.46.0.");

        WorkflowValidatorsView view = reader.read(NAME, "ACTIVE");

        assertFalse(view.getScriptRunner().isInstalled());
        assertFalse(view.getScriptRunner().isSupported());
        assertTrue(view.getScriptRunner().getReason().contains("not installed"));
        assertEquals("the descriptor is read all the same", Arrays.asList("assignee", "customfield_10118"),
                transition(view, 21).getValidators().get(0).getFieldIds());
    }

    @Test
    public void theScriptRunnerStateOfTheGateIsReportedAsItIs() {
        ScriptRunnerStatus status = reader.read(NAME, "ACTIVE").getScriptRunner();

        assertTrue(status.isInstalled());
        assertTrue(status.isEnabled());
        assertEquals("9.46.0", status.getVersion());
        assertTrue(status.isSupported());
        assertNull(status.getReason());
    }

    @Test
    public void aWorkflowThatDoesNotExistOrAVersionThatIsMissingIsRefused() {
        for (String[] bad : new String[][] {{"Nope", "ACTIVE"}, {" ", "ACTIVE"}, {null, "ACTIVE"}, {NAME, "BOTH"},
                {NAME, null}, {NAME, "DRAFT"}}) {
            try {
                reader.read(bad[0], bad[1]);
                fail("expected a refusal for " + Arrays.toString(bad));
            } catch (InvalidRequestException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void onlyAnAdministratorReads() {
        when(guard.requireAdministrator()).thenThrow(new InvalidRequestException("administrators only"));

        try {
            reader.read(NAME, "ACTIVE");
            fail();
        } catch (InvalidRequestException expected) {
            verifyNoInteractions(inspector);
        }
    }

    // ---- the comment validator -------------------------------------------------------------

    @Test
    public void theCommentValidatorIsReadAsItsKindAtItsPositionWithoutArguments() {
        active = commentWorkflow();

        List<ValidatorView> validators = transition(reader.read(NAME, "ACTIVE"), 21).getValidators();

        assertEquals(2, validators.size());
        assertEquals("REQUIRED_FIELDS", validators.get(0).getKind());
        ValidatorView comment = validators.get(1);
        assertEquals(2, comment.getPosition());
        assertEquals("COMMENT_REQUIRED", comment.getKind());
        assertNull(comment.getFieldIds());
        assertNull(comment.getFields());
        assertNull(comment.getConditional());
        assertNull(comment.getType());
        assertNull(comment.getClassName());
        assertNull(comment.getCannedScript());
        assertNull("a usable screen is no problem", comment.getProblem());
        assertNull(validators.get(0).getProblem());
    }

    @Test
    public void theCommentScriptInAnotherFormIsOtherWithItsTypeAndClass() {
        active = commentWorkflow();
        WorkflowValidatorsView view = reader.read(NAME, "ACTIVE");

        for (int actionId : new int[] {19, 20}) {
            ValidatorView other = transition(view, actionId).getValidators().get(0);
            assertEquals("action " + actionId, "OTHER", other.getKind());
            assertEquals("class", other.getType());
            assertEquals(RequiredFieldsDescriptor.CLASS_NAME, other.getClassName());
            assertEquals(COMMENT_CANNED, other.getCannedScript());
            assertNull(other.getProblem());
        }
    }

    @Test
    public void aCommentValidatorOnATransitionWithoutAUsableScreenIsDiagnosed() {
        active = commentWorkflow();
        WorkflowValidatorsView view = reader.read(NAME, "ACTIVE");

        for (int actionId : new int[] {13, 16, 18}) {
            ValidatorView comment = null;
            for (ValidatorView v : transition(view, actionId).getValidators()) {
                if ("COMMENT_REQUIRED".equals(v.getKind())) comment = v;
            }
            assertNotNull("action " + actionId, comment);
            assertNotNull("action " + actionId, comment.getProblem());
            assertFalse("action " + actionId, comment.getProblem().trim().isEmpty());
            assertTrue(comment.getProblem(), comment.getProblem().toLowerCase().contains("screen"));
        }
        assertNull(transition(view, 12).getValidators().get(0).getProblem());
        assertNull(transition(view, 21).getValidators().get(1).getProblem());
    }

    @Test
    public void aCommentValidatorOnAScreenWithoutTheFieldscreenViewIsDiagnosedAndAFullyUsableScreenIsNot() {
        active = commentWorkflow();
        action(active, 12).setView(null);
        action(active, 21).setView("resolveissue");
        WorkflowValidatorsView view = reader.read(NAME, "ACTIVE");

        ValidatorView withoutView = transition(view, 12).getValidators().get(0);
        ValidatorView otherView = transition(view, 21).getValidators().get(1);
        for (ValidatorView comment : new ValidatorView[] {withoutView, otherView}) {
            assertEquals("COMMENT_REQUIRED", comment.getKind());
            assertNotNull(comment.getProblem());
            assertFalse(comment.getProblem().trim().isEmpty());
            assertTrue(comment.getProblem(), comment.getProblem().contains("view=\"fieldscreen\""));
        }
        assertEquals("the screen itself is still reported", "9", transition(view, 12).getScreenId());
        assertEquals("Start screen", transition(view, 12).getScreenName());

        active = commentWorkflow();
        WorkflowValidatorsView usable = reader.read(NAME, "ACTIVE");
        assertNull(transition(usable, 12).getValidators().get(0).getProblem());
        assertNull(transition(usable, 21).getValidators().get(1).getProblem());
    }

    @Test
    public void theScreenOfTheTransitionIsReportedAsBeforeAndTheLayoutIsNotLookedAt() {
        active = commentWorkflow();
        WorkflowValidatorsView view = reader.read(NAME, "ACTIVE");

        assertEquals("9", transition(view, 12).getScreenId());
        assertEquals("Start screen", transition(view, 12).getScreenName());
        assertNull(transition(view, 13).getScreenId());
        assertEquals("abc", transition(view, 16).getScreenId());
        assertNull(transition(view, 16).getScreenName());
        assertEquals("99", transition(view, 18).getScreenId());
        assertNull(transition(view, 18).getScreenName());
    }
}
