package ru.rasulov.workflow.impl.edit;

import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.FunctionDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.TransitionResolution;
import ru.rasulov.workflow.api.dto.edit.EditErrorCode;
import ru.rasulov.workflow.api.dto.edit.ScreenPatch;
import ru.rasulov.workflow.api.dto.edit.WorkflowChange;
import ru.rasulov.workflow.impl.edit.DescriptorFacts.Located;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static ru.rasulov.workflow.impl.edit.EditFixtures.*;

/** What an edit may touch, and above all what it must leave alone. */
public class WorkflowDescriptorPatcherTest {

    private static final Set<Integer> NONE = null;

    private static WorkflowDescriptorPatcher.Result apply(WorkflowDescriptor d, Set<Integer> protectedSteps,
                                                          WorkflowChange... changes) {
        return WorkflowDescriptorPatcher.apply(d, Arrays.asList(changes), protectedSteps,
                Collections.emptyMap(), catalog());
    }

    private static EditFailure refused(WorkflowDescriptor d, Set<Integer> protectedSteps, WorkflowChange... changes) {
        try {
            apply(d, protectedSteps, changes);
        } catch (EditFailure e) {
            return e;
        }
        fail("the change was accepted");
        return null;
    }

    private static Map<Integer, String> xmlByAction(WorkflowDescriptor d) {
        Map<Integer, String> xml = new java.util.TreeMap<Integer, String>();
        for (Located l : DescriptorFacts.locate(d).values()) xml.put(l.id(), DescriptorFacts.xml(l.action()));
        return xml;
    }

    private static ActionDescriptor action(WorkflowDescriptor d, int id) {
        return DescriptorFacts.locate(d).get(id).action();
    }

    private static List<String> functionClasses(ActionDescriptor a) {
        List<String> classes = new ArrayList<String>();
        for (FunctionDescriptor f : DescriptorFacts.postFunctions(a.getUnconditionalResult())) {
            classes.add(DescriptorFacts.cls(f).replaceAll(".*\\.", ""));
        }
        return classes;
    }

    // ---- rename keeps everything else ---------------------------------------------------------------------------

    @Test
    public void renameChangesTheNameOfOneActionAndNothingElse() {
        WorkflowDescriptor d = workflow();
        Map<Integer, String> before = xmlByAction(d);
        String steps = DescriptorFacts.stepNames(d).toString();

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, rename(42, "Complete"));

        Map<Integer, String> after = xmlByAction(d);
        assertEquals("Complete", action(d, 42).getName());
        for (Integer id : before.keySet()) {
            if (id == 42) continue;
            assertEquals("action " + id + " must be byte-identical", before.get(id), after.get(id));
        }
        assertEquals(before.get(42).replace("name=\"Finish\"", "name=\"Complete\""), after.get(42));
        assertEquals(steps, DescriptorFacts.stepNames(d).toString());
        assertEquals(1, result.diff().size());
    }

    @Test
    public void renamingKeepsThirdPartyValidatorsFunctionsPropertiesAndTheirOrder() {
        WorkflowDescriptor d = workflow();
        apply(d, NONE, rename(11, "Begin"), rename(1 + 50, "Return"), description("Support v2"));

        assertEquals(Arrays.asList("UpdateIssueStatusFunction", "NotifyFunction", "GenerateChangeHistoryFunction",
                "IssueReindexFunction", "FireIssueEventFunction"), functionClasses(action(d, 11)));
        String xml = DescriptorFacts.xml(d);
        assertTrue(xml.contains("com.acme.jira.PolicyValidator"));
        assertTrue(xml.contains("<arg name=\"token\">hush</arg>"));
        assertTrue(xml.contains("<arg name=\"secret\">TOP-SECRET</arg>"));
        assertTrue(xml.contains("acme.owner"));
        assertTrue(xml.contains("jira.issue.editable"));
        assertTrue(xml.contains("com.acme.jira.WhenCondition"));
        assertTrue(xml.contains("com.acme.jira.PickCondition"));
        assertEquals("Support v2", DescriptorFacts.meta(d).get("jira.description"));
    }

    @Test
    public void parallelTransitionsWithOneNameAreToldApartByActionId() {
        WorkflowDescriptor d = workflow();
        apply(d, NONE, rename(13, "Fast start"));

        assertEquals("Start", action(d, 11).getName());
        assertEquals("Fast start", action(d, 13).getName());
    }

    @Test
    public void renameToTheSameNameWritesNothing() {
        WorkflowDescriptor d = workflow();
        String before = DescriptorFacts.definition(d);

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, rename(42, "Finish"));

        assertEquals(before, DescriptorFacts.definition(d));
        assertTrue(result.diff().isEmpty());
    }

    @Test
    public void everyExistingIdSurvivesAndNewOnesDoNotCollideWithInitialGlobalOrCommonIds() {
        WorkflowDescriptor d = workflow();
        Set<Integer> stepsBefore = DescriptorDiff.stepIds(d);
        Set<Integer> actionsBefore = new TreeSet<Integer>(DescriptorFacts.locate(d).keySet());

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, addStatus("10001", "review"),
                addTransition("To review", from(2), null, "review", null),
                addTransition("From review", fromKey("review"), 1, null, null));

        assertTrue(DescriptorDiff.stepIds(d).containsAll(stepsBefore));
        assertTrue(DescriptorFacts.locate(d).keySet().containsAll(actionsBefore));
        int maxBefore = Collections.max(actionsBefore);
        List<Integer> created = new ArrayList<Integer>(DescriptorFacts.locate(d).keySet());
        created.removeAll(actionsBefore);
        assertEquals(Arrays.asList(maxBefore + 1, maxBefore + 2), created);
        assertTrue(DescriptorFacts.step(d, 5) != null);
        assertEquals(new TreeSet<>(created), result.transitions().keySet());
        assertEquals(Collections.singletonMap(5, "review"), result.steps());
        assertEquals("10001", DescriptorFacts.statusId(DescriptorFacts.step(d, 5)));
    }

    // ---- screens ------------------------------------------------------------------------------------------------

    @Test
    public void screenSetAndClearTouchOnlyTheScreenProperty() {
        WorkflowDescriptor d = workflow();
        apply(d, NONE, screen(11, "SET", 11L), screen(42, "CLEAR", null));

        assertEquals("11", DescriptorFacts.meta(action(d, 11)).get("jira.fieldscreen.id"));
        assertFalse(DescriptorFacts.meta(action(d, 42)).containsKey("jira.fieldscreen.id"));
        assertEquals("keep me 42", DescriptorFacts.meta(action(d, 42)).get("jira.description"));
    }

    @Test
    public void unknownScreenIsRefused() {
        assertEquals(EditErrorCode.UNKNOWN_SCREEN, refused(workflow(), NONE, screen(11, "SET", 99L)).getCode());
    }

    /** Jira's parser gives an action without the attribute an empty view; a cleared one may be null. Both mean none. */
    private static void assertNoView(ActionDescriptor action) {
        String view = action.getView();
        assertTrue("view is '" + view + "'", view == null || view.isEmpty());
    }

    // ---- the screen is the meta and the view together --------------------------------------------------

    private static String screenLine(WorkflowDescriptorPatcher.Result result) {
        assertEquals(result.diff().toString(), 1, result.diff().size());
        return result.diff().get(0);
    }

    @Test
    public void settingAScreenWritesTheMetaAndTheFieldscreenView() {
        WorkflowDescriptor d = workflow();
        assertNoView(action(d, 11));

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, screen(11, "SET", 11L));

        assertEquals("11", DescriptorFacts.meta(action(d, 11)).get("jira.fieldscreen.id"));
        assertEquals("fieldscreen", action(d, 11).getView());
        assertTrue(screenLine(result), screenLine(result).contains("screen none -> 11"));
        String xml = DescriptorFacts.xml(action(d, 11));
        assertTrue(xml, xml.contains("view=\"fieldscreen\""));
        assertTrue(xml, xml.contains("<meta name=\"jira.fieldscreen.id\">11</meta>"));
        assertEquals("Start", action(d, 11).getName());
        assertEquals("keep me 11", DescriptorFacts.meta(action(d, 11)).get("jira.description"));
    }

    @Test
    public void settingAScreenReplacesAnotherViewOfTheAction() {
        WorkflowDescriptor d = workflow();
        action(d, 11).setView("resolveissue");

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, screen(11, "SET", 11L));

        assertEquals("fieldscreen", action(d, 11).getView());
        assertEquals("11", DescriptorFacts.meta(action(d, 11)).get("jira.fieldscreen.id"));
        assertTrue(screenLine(result), screenLine(result).contains("fieldscreen"));
    }

    @Test
    public void theSameScreenOnATransitionWithTheMetaButWithoutTheViewRepairsTheViewAndSaysSo() {
        WorkflowDescriptor d = workflow();
        action(d, 42).setView(null);
        assertEquals("9", DescriptorFacts.meta(action(d, 42)).get("jira.fieldscreen.id"));

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, screen(42, "SET", 9L));

        assertEquals("fieldscreen", action(d, 42).getView());
        assertEquals("9", DescriptorFacts.meta(action(d, 42)).get("jira.fieldscreen.id"));
        assertTrue(screenLine(result), screenLine(result).contains("view"));
    }

    @Test
    public void theSameScreenOnATransitionThatHasMetaAndViewWritesNothing() {
        WorkflowDescriptor d = workflow();
        String before = DescriptorFacts.definition(d);

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, screen(42, "SET", 9L));

        assertEquals(before, DescriptorFacts.definition(d));
        assertTrue(result.diff().isEmpty());
    }

    @Test
    public void anotherScreenReplacesTheIdAndTheViewStaysFieldscreen() {
        WorkflowDescriptor d = workflow();

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, screen(42, "SET", 11L));

        assertEquals("11", DescriptorFacts.meta(action(d, 42)).get("jira.fieldscreen.id"));
        assertEquals("fieldscreen", action(d, 42).getView());
        assertTrue(screenLine(result), screenLine(result).contains("screen 9 -> 11"));
    }

    @Test
    public void clearingTheScreenRemovesTheMetaAndTheFieldscreenViewAndSaysSo() {
        WorkflowDescriptor d = workflow();

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, screen(42, "CLEAR", null));

        assertFalse(DescriptorFacts.meta(action(d, 42)).containsKey("jira.fieldscreen.id"));
        assertNoView(action(d, 42));
        assertEquals("keep me 42", DescriptorFacts.meta(action(d, 42)).get("jira.description"));
        assertTrue(screenLine(result), screenLine(result).contains("screen 9 -> none"));
        assertFalse(DescriptorFacts.xml(action(d, 42)).contains("view="));
    }

    @Test
    public void clearingTheScreenLeavesAnotherViewOfTheActionAlone() {
        WorkflowDescriptor d = workflow();
        action(d, 42).setView("resolveissue");

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, screen(42, "CLEAR", null));

        assertFalse(DescriptorFacts.meta(action(d, 42)).containsKey("jira.fieldscreen.id"));
        assertEquals("resolveissue", action(d, 42).getView());
        assertTrue(screenLine(result), screenLine(result).contains("screen 9 -> none"));
    }

    @Test
    public void clearingAScreenViewThatHasNoMetaRemovesTheViewAndSaysSo() {
        WorkflowDescriptor d = workflow();
        action(d, 11).setView("fieldscreen");

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, screen(11, "CLEAR", null));

        assertNoView(action(d, 11));
        assertEquals(1, result.diff().size());
    }

    @Test
    public void clearingATransitionWithoutAScreenWritesNothing() {
        WorkflowDescriptor d = workflow();
        String before = DescriptorFacts.definition(d);

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, screen(11, "CLEAR", null));

        assertEquals(before, DescriptorFacts.definition(d));
        assertTrue(result.diff().isEmpty());
    }

    @Test
    public void aNewTransitionWithAScreenGetsTheMetaAndTheFieldscreenView() {
        WorkflowDescriptor d = workflow();
        int maxBefore = Collections.max(DescriptorFacts.locate(d).keySet());
        WorkflowChange add = new WorkflowChange("ADD_TRANSITION",null,null,null,null,"Back",null,from(2),1,null,new ScreenPatch("SET", 11L),null,null, null);

        apply(d, NONE, add);

        ActionDescriptor created = action(d, maxBefore + 1);
        assertEquals("Back", created.getName());
        assertEquals("11", DescriptorFacts.meta(created).get("jira.fieldscreen.id"));
        assertEquals("fieldscreen", created.getView());
    }

    @Test
    public void aNewTransitionWithoutAScreenHasNoView() {
        WorkflowDescriptor d = workflow();
        int maxBefore = Collections.max(DescriptorFacts.locate(d).keySet());

        apply(d, NONE, addTransition("Back", from(2), 1, null, null));

        ActionDescriptor created = action(d, maxBefore + 1);
        assertFalse(DescriptorFacts.meta(created).containsKey("jira.fieldscreen.id"));
        assertNoView(created);
    }

    @Test
    public void aScreenSurvivesJirasSaveAndReloadWithItsViewAndAClearLeavesNoTrace() {
        WorkflowDescriptor d = workflow();
        apply(d, NONE, screen(11, "SET", 11L), screen(42, "CLEAR", null));

        WorkflowDescriptor reloaded = ValidatorFixtures.reloaded(d);

        assertEquals("fieldscreen", action(reloaded, 11).getView());
        assertEquals("11", DescriptorFacts.meta(action(reloaded, 11)).get("jira.fieldscreen.id"));
        assertNoView(action(reloaded, 42));
        assertFalse(DescriptorFacts.meta(action(reloaded, 42)).containsKey("jira.fieldscreen.id"));
        assertEquals("fieldscreen", action(reloaded, 31).getView());
    }

    // ---- resolution ---------------------------------------------------------------------------------------------

    @Test
    public void setResolutionEditsTheStandardWriterInPlaceAndKeepsTheFunctionOrder() {
        WorkflowDescriptor d = workflow();
        List<String> order = functionClasses(action(d, 42));

        apply(d, NONE, resolution(42, "SET", "2"));

        assertEquals(order, functionClasses(action(d, 42)));
        assertEquals("2", DescriptorFacts.rule(action(d, 42)).value());
        assertEquals("SET", DescriptorFacts.rule(action(d, 42)).action());
    }

    @Test
    public void setResolutionOnATransitionWithoutWriterInsertsItBeforeHistoryReindexAndEvent() {
        WorkflowDescriptor d = workflow();
        // 41 Stop (to Open) has no writer; make a done-entering transition without one via ADD of a new one first.
        apply(d, NONE, addTransition("Skip", from(1), 3, null, new TransitionResolution("SET", "1")));
        int id = Collections.max(DescriptorFacts.locate(d).keySet());

        assertEquals(Arrays.asList("UpdateIssueStatusFunction", "CreateCommentFunction", "UpdateIssueFieldFunction",
                "GenerateChangeHistoryFunction", "IssueReindexFunction", "FireIssueEventFunction"),
                functionClasses(action(d, id)));
        assertEquals("1", DescriptorFacts.rule(action(d, id)).value());
    }

    @Test
    public void clearWritesTheNativeNullLiteral() {
        WorkflowDescriptor d = workflow();
        apply(d, NONE, retarget(42, 2), resolution(42, "CLEAR", null));

        FunctionDescriptor writer = DescriptorFacts.postFunctions(action(d, 42).getUnconditionalResult()).stream()
                .filter(DescriptorFacts::writesResolution).findFirst().get();
        assertEquals("null", writer.getArgs().get("field.value"));
        assertEquals("CLEAR", DescriptorFacts.rule(action(d, 42)).action());
    }

    @Test
    public void explicitPreserveRemovesOnlyTheStandardWriter() {
        WorkflowDescriptor d = workflow();
        apply(d, NONE, resolution(51, "PRESERVE", null));

        assertEquals("PRESERVE", DescriptorFacts.rule(action(d, 51)).action());
        assertEquals(Arrays.asList("UpdateIssueStatusFunction", "NotifyFunction", "GenerateChangeHistoryFunction",
                "IssueReindexFunction", "FireIssueEventFunction"), functionClasses(action(d, 51)));
    }

    @Test
    public void omittingResolutionLeavesTheWriterAlone() {
        WorkflowDescriptor d = workflow();
        Map<Integer, String> before = xmlByAction(d);

        apply(d, NONE, rename(42, "Complete"), screen(42, "SET", 11L));

        assertEquals("SET", DescriptorFacts.rule(action(d, 42)).action());
        assertEquals("1", DescriptorFacts.rule(action(d, 42)).value());
        assertEquals(before.get(51), xmlByAction(d).get(51));
    }

    @Test
    public void severalOrForeignOrConditionalResolutionWritersAreRefusedNotNormalised() {
        for (int id : new int[] {44, 45, 43}) {
            WorkflowDescriptor d = workflow();
            String before = DescriptorFacts.definition(d);
            EditFailure e = refused(d, NONE, resolution(id, "SET", "1"));
            assertEquals("action " + id, EditErrorCode.UNSUPPORTED_DESCRIPTOR, e.getCode());
        }
        assertEquals("UNKNOWN", DescriptorFacts.rule(action(workflow(), 44)).action());
        assertEquals("UNKNOWN", DescriptorFacts.rule(action(workflow(), 45)).action());
        assertEquals("UNKNOWN", DescriptorFacts.rule(action(workflow(), 43)).action());
        // but the name of such a transition can still change, with everything else byte-identical
        WorkflowDescriptor d = workflow();
        String before = DescriptorFacts.xml(action(d, 43));
        apply(d, NONE, rename(43, "Branch out"));
        assertEquals(before.replace("name=\"Branch\"", "name=\"Branch out\""), DescriptorFacts.xml(action(d, 43)));
    }

    @Test
    public void setOnANonDoneTargetAndClearIntoDoneAreRefused() {
        assertEquals(EditErrorCode.VALIDATION_ERROR, refused(workflow(), NONE, resolution(11, "SET", "1")).getCode());
        assertEquals(EditErrorCode.VALIDATION_ERROR, refused(workflow(), NONE, resolution(42, "CLEAR", null)).getCode());
        assertEquals(EditErrorCode.UNKNOWN_RESOLUTION, refused(workflow(), NONE, resolution(42, "SET", "77")).getCode());
    }

    @Test
    public void retargetingIntoDoneRequiresAnExplicitResolutionDecision() {
        // 11 Start (Open -> In Progress, no writer): pointing it at Resolved (done) needs SET.
        EditFailure e = refused(workflow(), NONE, retarget(11, 3));
        assertEquals(EditErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("Decide explicitly"));

        WorkflowDescriptor d = workflow();
        apply(d, NONE, retarget(11, 3), resolution(11, "SET", "1"));
        assertEquals(3, action(d, 11).getUnconditionalResult().getStep());
        assertEquals("1", DescriptorFacts.rule(action(d, 11)).value());
    }

    @Test
    public void retargetingOutOfDoneWithASetRuleRequiresClear() {
        // 42 Finish (SET 1) re-aimed at In Progress would keep resolving issues that are not done.
        EditFailure e = refused(workflow(), NONE, retarget(42, 2));
        assertEquals(EditErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("Decide explicitly"));
    }

    @Test
    public void aRuleThatAlreadyDidNotFitIsNotBlamedOnTheEdit() {
        // 51 Back (Resolved -> Open) clears; moving it to start from In Progress keeps CLEAR valid. Nothing to decide.
        WorkflowDescriptor d = workflow();
        apply(d, NONE, move(51, from(2)));
        assertEquals("CLEAR", DescriptorFacts.rule(action(d, 51)).action());
    }

    // ---- structure ----------------------------------------------------------------------------------------------

    @Test
    public void movingATransitionBetweenLocalAndGlobalKeepsItsIdAndDefinition() {
        WorkflowDescriptor d = workflow();
        String before = DescriptorFacts.xml(action(d, 51));
        apply(d, NONE, move(51, fromAny()));

        Located l = DescriptorFacts.locate(d).get(51);
        assertEquals(DescriptorFacts.Kind.GLOBAL, l.kind());
        assertEquals(before, DescriptorFacts.xml(l.action()));
        assertTrue(DescriptorFacts.actions(DescriptorFacts.step(d, 3)).stream().noneMatch(a -> a.getId() == 51));

        apply(d, NONE, move(51, from(2)));
        assertEquals(DescriptorFacts.Kind.LOCAL, DescriptorFacts.locate(d).get(51).kind());
        assertEquals(Collections.singletonList(2), DescriptorFacts.locate(d).get(51).sources());
        assertEquals(before, DescriptorFacts.xml(action(d, 51)));
    }

    @Test
    public void makingAPreservingTransitionGlobalNeedsAnExplicitClearInTheSameRequest() {
        EditFailure e = refused(workflow(), NONE, move(41, fromAny()));
        assertEquals(EditErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("Decide explicitly"));

        WorkflowDescriptor d = workflow();
        apply(d, NONE, resolution(41, "CLEAR", null), move(41, fromAny()));
        assertEquals("CLEAR", DescriptorFacts.rule(action(d, 41)).action());
        assertEquals(DescriptorFacts.Kind.GLOBAL, DescriptorFacts.locate(d).get(41).kind());
    }

    @Test
    public void commonConditionalAndInitialActionsAreNotRestructured() {
        assertEquals(EditErrorCode.UNSUPPORTED_DESCRIPTOR, refused(workflow(), NONE, retarget(31, 1)).getCode());
        assertEquals(EditErrorCode.UNSUPPORTED_DESCRIPTOR, refused(workflow(), NONE, removeTransition(31)).getCode());
        assertEquals(EditErrorCode.UNSUPPORTED_DESCRIPTOR, refused(workflow(), NONE, retarget(43, 2)).getCode());
        assertEquals(EditErrorCode.UNSUPPORTED_DESCRIPTOR, refused(workflow(), NONE, rename(1, "Make")).getCode());
        assertEquals(EditErrorCode.UNSUPPORTED_DESCRIPTOR, refused(workflow(), NONE, removeTransition(1)).getCode());
        // a common action may still get a name and a screen
        WorkflowDescriptor d = workflow();
        apply(d, NONE, rename(31, "Close it"), screen(31, "SET", 11L));
        assertEquals("Close it", action(d, 31).getName());
        assertEquals("11", DescriptorFacts.meta(action(d, 31)).get("jira.fieldscreen.id"));
    }

    @Test
    public void unknownIdsAreNamedPrecisely() {
        assertEquals(EditErrorCode.UNKNOWN_TRANSITION, refused(workflow(), NONE, rename(999, "x")).getCode());
        assertEquals(EditErrorCode.UNKNOWN_STEP, refused(workflow(), NONE, retarget(11, 99)).getCode());
        assertEquals(EditErrorCode.UNKNOWN_STATUS, refused(workflow(), NONE, addStatus("424242", null)).getCode());
    }

    @Test
    public void removingATransitionLeavesNoDanglingReference() {
        WorkflowDescriptor d = workflow();
        apply(d, NONE, removeTransition(41), removeTransition(21));

        assertFalse(DescriptorFacts.locate(d).containsKey(41));
        assertFalse(DescriptorFacts.locate(d).containsKey(21));
        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 31)), new TreeSet<Integer>(
                DescriptorFacts.locate(d).values().stream().filter(l -> l.kind() == DescriptorFacts.Kind.COMMON
                        || l.kind() == DescriptorFacts.Kind.INITIAL).map(Located::id).toList()));
        d.getSteps().forEach(s -> assertTrue(DescriptorFacts.step(d, ((com.opensymphony.workflow.loader.StepDescriptor) s)
                .getId()) != null));
    }

    @Test
    public void setInitialStatusRetargetsTheInitialActionAndKeepsItsValidatorsAndFunctions() {
        WorkflowDescriptor d = workflow();
        String before = DescriptorFacts.xml(action(d, 1));

        WorkflowDescriptorPatcher.Result result = apply(d, NONE, setInitial(2));

        assertEquals(2, action(d, 1).getUnconditionalResult().getStep());
        assertEquals(before.replace("step=\"1\"", "step=\"2\""), DescriptorFacts.xml(action(d, 1)));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Existing issues are not moved")));
    }

    // ---- statuses -----------------------------------------------------------------------------------------------

    @Test
    public void removingAStatusNeedsEveryReferenceHandledExplicitly() {
        // Resolved (3) has outgoing 51, 52 and incoming 13, 42, 43 (conditional), 44, 45.
        EditFailure out = refused(workflow(), NONE, removeStatus(3));
        assertEquals(EditErrorCode.VALIDATION_ERROR, out.getCode());
        assertTrue(out.getMessage().contains("REMOVE_TRANSITION"));

        EditFailure in = refused(workflow(), NONE, removeTransition(51), removeTransition(52), removeStatus(3));
        assertEquals(EditErrorCode.VALIDATION_ERROR, in.getCode());
        assertTrue(in.getMessage().contains("still the target of transitions"));
        assertTrue(in.getMessage().contains("43"));
    }

    @Test
    public void aStatusWithNothingLeadingInOrOutIsRemovedAndNothingElseGoes() {
        WorkflowDescriptor d = workflow();
        Map<Integer, String> before = xmlByAction(d);

        apply(d, NONE, removeTransition(41), removeTransition(42), removeTransition(43), removeTransition(44),
                removeTransition(45), removeTransition(11), removeStatus(2));

        assertEquals(new TreeSet<Integer>(Arrays.asList(1, 3, 4)), DescriptorDiff.stepIds(d));
        assertEquals(before.get(13), xmlByAction(d).get(13));
        assertEquals(before.get(51), xmlByAction(d).get(51));
        assertEquals(before.get(31), xmlByAction(d).get(31));
        assertTrue(DescriptorFacts.locate(d).values().stream()
                .noneMatch(l -> DescriptorFacts.targets(l.action()).contains(2)));
    }

    @Test
    public void replacingAnActiveStepOrRemovingAnOccupiedStepNeedsMigration() {
        Set<Integer> inActive = new TreeSet<Integer>(Arrays.asList(1, 2, 3, 4));
        assertEquals(EditErrorCode.MIGRATION_REQUIRED, refused(workflow(), inActive, replaceStatus(2, "10001")).getCode());
        try {
            WorkflowDescriptorPatcher.apply(workflow(), Collections.singletonList(removeStatus(4)), inActive,
                    Collections.singletonMap(4, 7L), catalog());
            fail("an occupied active-version step cannot be removed");
        } catch (EditFailure failure) {
            assertEquals(EditErrorCode.MIGRATION_REQUIRED, failure.getCode());
            assertTrue(failure.getMessage().contains("7"));
        }
    }

    @Test
    public void removingAnEmptyStepOfTheActiveVersionIsAllowed() throws Exception {
        Set<Integer> inActive = new TreeSet<Integer>(Arrays.asList(1, 2, 3, 4));
        WorkflowDescriptor empty = com.atlassian.jira.workflow.WorkflowUtil.convertXMLtoWorkflowDescriptor(DTD
                + "<workflow><initial-actions>" + EditFixtures.action(1, "Create", 1, "", STATUS) + "</initial-actions><steps>"
                + "<step id=\"1\" name=\"Open\"><meta name=\"jira.status.id\">1</meta><actions>"
                + EditFixtures.action(11, "Close", 4, "", STATUS + writer("1") + HISTORY)
                + EditFixtures.action(12, "Refresh", 1, "", STATUS + HISTORY) + "</actions></step>"
                + "<step id=\"4\" name=\"Closed\"><meta name=\"jira.status.id\">6</meta></step></steps></workflow>");
        WorkflowDescriptorPatcher.apply(empty, Arrays.asList(removeTransition(11), removeStatus(4)), inActive,
                Collections.emptyMap(), catalog());
        assertEquals(Collections.singleton(1), DescriptorDiff.stepIds(empty));
        assertFalse(DescriptorFacts.locate(empty).containsKey(11));
    }

    @Test
    public void replacingAStepOfAnInactiveWorkflowKeepsTheStepIdAndItsTransitions() {
        WorkflowDescriptor d = workflow();
        String transitions = DescriptorFacts.actions(DescriptorFacts.step(d, 2)).stream()
                .map(DescriptorFacts::xml).reduce("", String::concat);

        apply(d, NONE, replaceStatus(2, "10001"));

        assertEquals("10001", DescriptorFacts.statusId(DescriptorFacts.step(d, 2)));
        assertEquals("Review", DescriptorFacts.step(d, 2).getName());
        assertEquals(transitions, DescriptorFacts.actions(DescriptorFacts.step(d, 2)).stream()
                .map(DescriptorFacts::xml).reduce("", String::concat));
    }

    @Test
    public void aStepNewInTheDraftMayBeReplacedAndTheActiveStepsStillMayNot() {
        WorkflowDescriptor d = workflow();
        Set<Integer> inActive = new TreeSet<Integer>(Arrays.asList(1, 2, 3, 4));
        apply(d, inActive, addStatus("10001", "r"));
        Set<Integer> now = new TreeSet<Integer>(inActive);
        // the new step has id 5 and is not in the active version
        apply(d, now, replaceStatus(5, "10002"));
        assertEquals("10002", DescriptorFacts.statusId(DescriptorFacts.step(d, 5)));
    }

    @Test
    public void aStatusAlreadyInTheWorkflowCannotBeAddedTwice() {
        assertEquals(EditErrorCode.VALIDATION_ERROR, refused(workflow(), NONE, addStatus("3", null)).getCode());
        assertEquals(EditErrorCode.VALIDATION_ERROR, refused(workflow(), NONE, replaceStatus(1, "3")).getCode());
    }

    // ---- new transitions ----------------------------------------------------------------------------------------

    @Test
    public void aNewTransitionIntoDoneNeedsSetAndOutOfDoneGetsClear() {
        assertEquals(EditErrorCode.VALIDATION_ERROR,
                refused(workflow(), NONE, addTransition("Done", from(1), 3, null, null)).getCode());

        WorkflowDescriptor d = workflow();
        apply(d, NONE, addTransition("Back to work", from(3), 2, null, null));
        int id = Collections.max(DescriptorFacts.locate(d).keySet());
        assertEquals("CLEAR", DescriptorFacts.rule(action(d, id)).action());
        assertEquals(DescriptorFacts.Kind.LOCAL, DescriptorFacts.locate(d).get(id).kind());

        apply(d, NONE, addTransition("Anywhere", fromAny(), 2, null, null));
        int global = Collections.max(DescriptorFacts.locate(d).keySet());
        assertEquals(DescriptorFacts.Kind.GLOBAL, DescriptorFacts.locate(d).get(global).kind());
        assertEquals("CLEAR", DescriptorFacts.rule(action(d, global)).action());
    }

    @Test
    public void aNewTransitionBetweenUnfinishedStatusesPreservesUnlessClearIsRequested() {
        WorkflowDescriptor d = workflow();
        apply(d, NONE, addTransition("Plain", from(1), 2, null, null));
        int plain = Collections.max(DescriptorFacts.locate(d).keySet());
        assertEquals("PRESERVE", DescriptorFacts.rule(action(d, plain)).action());

        apply(d, NONE, addTransition("Reset", from(1), 2, null, new TransitionResolution("CLEAR", null)));
        int reset = Collections.max(DescriptorFacts.locate(d).keySet());
        assertEquals("CLEAR", DescriptorFacts.rule(action(d, reset)).action());

        assertEquals(EditErrorCode.VALIDATION_ERROR, refused(workflow(), NONE,
                addTransition("Clear done", from(1), 3, null, new TransitionResolution("CLEAR", null))).getCode());
    }

    // ---- conflicts and shape ------------------------------------------------------------------------------------

    @Test
    public void conflictingChangesOfOnePropertyFailTheWholeRequestWhateverTheOrder() {
        for (List<WorkflowChange> order : Arrays.asList(
                Arrays.asList(rename(11, "A"), rename(11, "B")),
                Arrays.asList(removeTransition(11), rename(11, "A")),
                Arrays.asList(rename(11, "A"), removeTransition(11)),
                Arrays.asList(replaceStatus(2, "10001"), removeStatus(2)),
                Arrays.asList(addStatus("10001", "k"), addStatus("10002", "k")),
                Arrays.asList(setInitial(2), setInitial(3)),
                Arrays.asList(description("a"), description("b")))) {
            WorkflowDescriptor d = workflow();
            try {
                WorkflowDescriptorPatcher.apply(d, order, NONE, Collections.emptyMap(), catalog());
                fail("accepted " + order.get(0).getOp());
            } catch (EditFailure e) {
                assertEquals(EditErrorCode.VALIDATION_ERROR, e.getCode());
            }
        }
    }

    @Test
    public void anEmptyOrOversizedChangeListAndAnEmptyUpdateAreRefused() {
        assertEquals(EditErrorCode.VALIDATION_ERROR, refused(workflow(), NONE).getCode());
        List<WorkflowChange> many = new ArrayList<WorkflowChange>();
        for (int i = 0; i < 101; i++) many.add(description("x" + i));
        try {
            WorkflowDescriptorPatcher.apply(workflow(), many, NONE, Collections.emptyMap(), catalog());
            fail();
        } catch (EditFailure e) {
            assertEquals(EditErrorCode.VALIDATION_ERROR, e.getCode());
        }
        assertEquals(EditErrorCode.VALIDATION_ERROR, refused(workflow(), NONE,
                new WorkflowChange("UPDATE_TRANSITION",11,null,null,null,null,null,null,null,null,null,null,null, null)).getCode());
    }

    @Test
    public void aDescriptorThatWasEditedStillSurvivesAJiraSaveAndReload() throws Exception {
        WorkflowDescriptor d = workflow();
        apply(d, NONE, rename(11, "Begin"), resolution(13, "SET", "2"), screen(11, "SET", 11L),
                addStatus("10001", "r"), addTransition("Review", from(2), null, "r", null),
                removeTransition(41), setInitial(2), description("x"));

        String saved = com.atlassian.jira.workflow.WorkflowUtil.convertDescriptorToXML(d);
        WorkflowDescriptor reloaded = com.atlassian.jira.workflow.WorkflowUtil.convertXMLtoWorkflowDescriptor(saved);
        assertEquals(DescriptorFacts.definition(d), DescriptorFacts.definition(reloaded));
        assertFalse(DescriptorFacts.definition(workflow()).equals(DescriptorFacts.definition(reloaded)));
    }
}
