package ru.rasulov.workflow.impl.edit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.ConditionDescriptor;
import com.opensymphony.workflow.loader.ConditionsDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView.ConditionNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static ru.rasulov.workflow.impl.edit.ConditionFixtures.*;

/**
 * How conditions of a transition are read from and added to a stored descriptor, on XML in the forms that were
 * exported from the stand (the descriptor part).
 */
public class ConditionDescriptorsTest {

    private static final String FID = "11111111-2222-3333-4444-555555555555";
    private static final LongFunction<String> NAMES = id -> id == 10002L ? "Administrators"
            : id == 10101L ? "Service Desk Team" : id == 10100L ? "Service Desk Customers" : null;

    // ---- reading -----------------------------------------------------------------------------------------

    @Test
    public void theCfltbSampleIsReadAsAnAndRootWithThreeNodesInTheirOriginalOrder() {
        ConditionNode root = ConditionDescriptors.read(action(workflow(), 11), NAMES);

        assertNotNull(root);
        assertEquals("COMPOUND", root.getKind());
        assertEquals("AND", root.getOperator());
        assertEquals(3, root.getChildren().size());
        ConditionNode permission = root.getChildren().get(0);
        assertEquals("OTHER", permission.getKind());
        assertEquals(1, permission.getPosition());
        assertEquals("class", permission.getType());
        assertEquals("com.atlassian.jira.workflow.condition.PermissionCondition", permission.getClassName());
        assertNull(permission.getCannedScript());
        assertNull("only type and class are shown of another condition", permission.getGroup());
        assertNull(permission.getRoleIds());
        ConditionNode role = root.getChildren().get(1);
        assertEquals("PROJECT_ROLE", role.getKind());
        assertEquals(2, role.getPosition());
        assertEquals(Arrays.asList(10002L), role.getRoleIds());
        assertEquals(1, role.getRoles().size());
        assertEquals(10002L, role.getRoles().get(0).getId());
        assertEquals("Administrators", role.getRoles().get(0).getName());
        assertEquals(Boolean.FALSE, role.getInverted());
        ConditionNode group = root.getChildren().get(2);
        assertEquals("GROUP", group.getKind());
        assertEquals(3, group.getPosition());
        assertEquals("jira-administrators", group.getGroup());
    }

    @Test
    public void aRootWithoutTypeIsAndAndATransitionWithoutConditionsHasNoRoot() {
        ConditionNode untyped = ConditionDescriptors.read(action(workflow(), 12), NAMES);

        assertEquals("AND", untyped.getOperator());
        assertEquals(1, untyped.getChildren().size());
        assertNull(ConditionDescriptors.read(action(workflow(), 13), NAMES));
        assertNull(ConditionDescriptors.read(action(workflow(), 31), NAMES));
    }

    @Test
    public void aNestedGroupIsACompoundWithItsOwnChildrenAndOperator() {
        ConditionNode root = ConditionDescriptors.read(action(workflow(), 14), NAMES);

        assertEquals(2, root.getChildren().size());
        ConditionNode inner = root.getChildren().get(1);
        assertEquals("COMPOUND", inner.getKind());
        assertEquals("OR", inner.getOperator());
        assertEquals(2, inner.getPosition());
        assertEquals("GROUP", inner.getChildren().get(0).getKind());
        assertEquals("Inner", inner.getChildren().get(0).getGroup());
        assertEquals(1, inner.getChildren().get(0).getPosition());
        assertEquals("PROJECT_ROLE", inner.getChildren().get(1).getKind());
        assertEquals(Boolean.TRUE, inner.getChildren().get(1).getInverted());
        assertNull("a leaf has no children", root.getChildren().get(0).getChildren());
    }

    @Test
    public void severalRolesAreSplitAtTheCapturedSeparatorAndAnInvertedValueIsRead() {
        ConditionNode root = ConditionDescriptors.read(action(workflow(), 17), NAMES);

        ConditionNode two = root.getChildren().get(0);
        assertEquals(Arrays.asList(10002L, 10101L), two.getRoleIds());
        assertEquals("Service Desk Team", two.getRoles().get(1).getName());
        assertEquals(Boolean.FALSE, two.getInverted());
        assertEquals(Boolean.TRUE, root.getChildren().get(1).getInverted());
    }

    @Test
    public void aRoleThatJiraNoLongerHasIsShownWithItsIdAndNoName() {
        ConditionNode root = ConditionDescriptors.read(action(workflow(), 17), id -> null);

        assertEquals(10002L, root.getChildren().get(0).getRoles().get(0).getId());
        assertNull(root.getChildren().get(0).getRoles().get(0).getName());
        assertEquals(Arrays.asList(10002L, 10101L), root.getChildren().get(0).getRoleIds());
    }

    @Test
    public void formsThatWereNotCapturedAreOtherAndNeverShowTheirArguments() throws Exception {
        ConditionNode root = ConditionDescriptors.read(action(workflow(), 18), NAMES);

        assertEquals(4, root.getChildren().size());
        for (ConditionNode node : root.getChildren()) assertEquals(String.valueOf(node.getPosition()), "OTHER", node.getKind());
        assertEquals(ConditionDescriptors.GROUP_CLASS, root.getChildren().get(0).getClassName());
        assertEquals(ConditionDescriptors.ROLE_CANNED, root.getChildren().get(1).getCannedScript());
        assertEquals(ConditionDescriptors.ROLE_CLASS, root.getChildren().get(1).getClassName());
        String json = new ObjectMapper().writeValueAsString(root.getChildren().get(0).getClassName())
                + root.getChildren().get(0).getType();
        assertFalse(json, json.contains("TOP-SECRET-ARG"));
        assertNull(root.getChildren().get(0).getGroup());
        assertNull(root.getChildren().get(3).getGroup());
    }

    @Test
    public void theInvertedStandSampleInItsExportedArgumentOrderIsReadAsInvertedAndMatchesAnInvertedAddition() {
        ActionDescriptor action = action(workflow(), 20);

        ConditionNode role = ConditionDescriptors.read(action, NAMES).getChildren().get(0);

        assertEquals("PROJECT_ROLE", role.getKind());
        assertEquals(Arrays.asList(10100L), role.getRoleIds());
        assertEquals(Boolean.TRUE, role.getInverted());
        assertEquals(ConditionDescriptors.Outcome.UNCHANGED, ConditionDescriptors.check(action,
                ConditionDescriptors.Candidate.roles(Arrays.asList(10100L), true)).outcome());
        assertEquals(ConditionDescriptors.Outcome.ADD, ConditionDescriptors.check(action,
                ConditionDescriptors.Candidate.roles(Arrays.asList(10100L), false)).outcome());
    }

    // ---- adding a group ----------------------------------------------------------------------------------

    @Test
    public void aGroupIsAppendedAsOneConditionOfTheCapturedFormAndEverythingElseStays() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor action = action(d, 11);
        List<String> before = conditionXml(action);
        Map<Integer, String> others = actionsExcept(d, 11);

        ConditionDescriptors.Check check = ConditionDescriptors.check(action, ConditionDescriptors.Candidate.group("Team-B"));
        assertEquals(ConditionDescriptors.Outcome.ADD, check.outcome());
        int position = ConditionDescriptors.apply(action, ConditionDescriptors.Candidate.group("Team-B"), FID);

        assertEquals(4, position);
        List<String> after = conditionXml(action);
        assertEquals(4, after.size());
        assertEquals("the existing conditions and their arguments stay in their order", before, after.subList(0, 3));
        ConditionDescriptor added = conditions(action).get(3);
        assertEquals("class", added.getType());
        assertEquals(2, added.getArgs().size());
        assertEquals(ConditionDescriptors.GROUP_CLASS, added.getArgs().get("class.name"));
        assertEquals("Team-B", added.getArgs().get("group"));
        assertEquals("AND", root(action).getType());
        assertEquals(others, actionsExcept(d, 11));
        assertEquals(DescriptorFacts.definition(reloaded(d)), DescriptorFacts.definition(d));
    }

    @Test
    public void aRootWithoutTypeBecomesAnExplicitAndWhenAConditionIsAppendedBecauseJiraCannotReadBackAnEmptyType() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor action = action(d, 12);
        List<String> before = conditionXml(action);

        ConditionDescriptors.apply(action, ConditionDescriptors.Candidate.group("Team-B"), FID);

        assertEquals("AND", root(action).getType());
        assertEquals(2, conditions(action).size());
        assertEquals(before, conditionXml(action).subList(0, 1));
        assertEquals("Team-B", conditions(action).get(1).getArgs().get("group"));
        assertEquals("AND", ConditionDescriptors.read(action, NAMES).getOperator());
        assertFalse(DescriptorFacts.xml(d), DescriptorFacts.xml(d).contains("type=\"\""));
        assertEquals(DescriptorFacts.definition(reloaded(d)), DescriptorFacts.definition(d));
    }

    @Test
    public void aSingleConditionRootThatJiraStoredWithoutATypeTakesASecondConditionAndStaysReadable() {
        WorkflowDescriptor d = reloaded(workflow());
        ActionDescriptor action = action(d, 16);

        int position = ConditionDescriptors.apply(action, ConditionDescriptors.Candidate.group("Team-B"), FID);

        assertEquals(2, position);
        assertEquals("AND", ConditionDescriptors.read(action, NAMES).getOperator());
        assertEquals(DescriptorFacts.definition(reloaded(d)), DescriptorFacts.definition(d));
        assertEquals("Team-A", conditions(action).get(0).getArgs().get("group"));
    }

    // ---- adding project roles ----------------------------------------------------------------------------

    @Test
    public void rolesAreAppendedAsTheCapturedFiveArgumentConditionWithTheCapturedSeparator() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor action = action(d, 11);

        ConditionDescriptors.apply(action, ConditionDescriptors.Candidate.roles(Arrays.asList(10002L, 10001L), false), FID);

        ConditionDescriptor added = conditions(action).get(3);
        assertEquals("class", added.getType());
        assertEquals(5, added.getArgs().size());
        assertEquals("10002|||10001", added.getArgs().get("FIELD_PROJECT_ROLE_IDS"));
        assertEquals(FID, added.getArgs().get("FIELD_FUNCTION_ID"));
        assertEquals("", added.getArgs().get("FIELD_INVERT_CONDITION"));
        assertEquals(ConditionDescriptors.ROLE_CANNED, added.getArgs().get("canned-script"));
        assertEquals(ConditionDescriptors.ROLE_CLASS, added.getArgs().get("class.name"));
        assertEquals(DescriptorFacts.definition(reloaded(d)), DescriptorFacts.definition(d));
    }

    @Test
    public void invertWritesTheCapturedOnValueAndTheWrittenConditionIsReadBackAsTheSameNode() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor action = action(d, 11);

        int position = ConditionDescriptors.apply(action, ConditionDescriptors.Candidate.roles(Arrays.asList(10100L), true), FID);

        assertEquals("on", conditions(action).get(3).getArgs().get("FIELD_INVERT_CONDITION"));
        ConditionNode read = ConditionDescriptors.read(action, NAMES).getChildren().get(3);
        assertEquals("PROJECT_ROLE", read.getKind());
        assertEquals(4, read.getPosition());
        assertEquals(Boolean.TRUE, read.getInverted());
        assertEquals(Arrays.asList(10100L), read.getRoleIds());
        ConditionNode described = ConditionDescriptors.describe(ConditionDescriptors.Candidate.roles(Arrays.asList(10100L), true),
                position, NAMES);
        assertEquals(json(read), json(described));
    }

    @Test
    public void everyAddedConditionIsReadBackAsTheNodeThatWasDescribed() {
        for (ConditionDescriptors.Candidate candidate : Arrays.asList(ConditionDescriptors.Candidate.group("Team-B"),
                ConditionDescriptors.Candidate.roles(Arrays.asList(10002L, 10101L), false))) {
            WorkflowDescriptor d = workflow();
            ActionDescriptor action = action(d, 16);

            int position = ConditionDescriptors.apply(action, candidate, FID);

            ConditionNode read = ConditionDescriptors.read(action, NAMES).getChildren().get(position - 1);
            assertEquals(json(read),
                    json(ConditionDescriptors.describe(candidate, position, NAMES)));
        }
    }

    // ---- no conditions -----------------------------------------------------------------------------------

    @Test
    public void aTransitionWithoutConditionsGetsAnAndRootWithOnlyTheAddedConditionAndNothingElseChanges() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor action = action(d, 13);
        String results = DescriptorFacts.xml(action.getUnconditionalResult());
        String validators = String.valueOf(action.getValidators().size());
        String meta = String.valueOf(DescriptorFacts.meta(action));
        String view = action.getView();
        Map<Integer, String> others = actionsExcept(d, 13);

        assertNull(action.getRestriction());
        int position = ConditionDescriptors.apply(action, ConditionDescriptors.Candidate.group("Team-B"), FID);

        assertEquals(1, position);
        assertEquals("AND", root(action).getType());
        assertEquals(1, conditions(action).size());
        assertEquals("Team-B", conditions(action).get(0).getArgs().get("group"));
        assertEquals(results, DescriptorFacts.xml(action.getUnconditionalResult()));
        assertEquals(validators, String.valueOf(action.getValidators().size()));
        assertEquals(meta, String.valueOf(DescriptorFacts.meta(action)));
        assertEquals(view, action.getView());
        assertEquals(others, actionsExcept(d, 13));
        assertEquals(DescriptorFacts.definition(reloaded(d)), DescriptorFacts.definition(d));
    }

    @Test
    public void aGlobalTransitionWithoutConditionsGetsTheSameRoot() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor action = action(d, 31);

        ConditionDescriptors.apply(action, ConditionDescriptors.Candidate.roles(Arrays.asList(10002L), false), FID);

        assertEquals("AND", root(action).getType());
        assertEquals(1, conditions(action).size());
        assertEquals(DescriptorFacts.definition(reloaded(d)), DescriptorFacts.definition(d));
    }

    // ---- idempotence -------------------------------------------------------------------------------------

    @Test
    public void theSameGroupInAnotherCaseIsUnchangedAndAnotherGroupIsAnAddition() {
        ActionDescriptor action = action(workflow(), 16);

        assertEquals(ConditionDescriptors.Outcome.UNCHANGED,
                ConditionDescriptors.check(action, ConditionDescriptors.Candidate.group("team-a")).outcome());
        assertEquals(ConditionDescriptors.Outcome.UNCHANGED,
                ConditionDescriptors.check(action, ConditionDescriptors.Candidate.group("Team-A")).outcome());
        assertEquals(ConditionDescriptors.Outcome.ADD,
                ConditionDescriptors.check(action, ConditionDescriptors.Candidate.group("Team-B")).outcome());
    }

    @Test
    public void theSameRolesInAnotherOrderWithTheSameInvertAreUnchangedAnythingElseIsAnAddition() {
        ActionDescriptor action = action(workflow(), 17);

        assertEquals(ConditionDescriptors.Outcome.UNCHANGED, ConditionDescriptors.check(action,
                ConditionDescriptors.Candidate.roles(Arrays.asList(10101L, 10002L), false)).outcome());
        assertEquals("another invert", ConditionDescriptors.Outcome.ADD, ConditionDescriptors.check(action,
                ConditionDescriptors.Candidate.roles(Arrays.asList(10101L, 10002L), true)).outcome());
        assertEquals("another set", ConditionDescriptors.Outcome.ADD, ConditionDescriptors.check(action,
                ConditionDescriptors.Candidate.roles(Arrays.asList(10002L), false)).outcome());
        assertEquals("the inverted condition matches only inverted", ConditionDescriptors.Outcome.UNCHANGED,
                ConditionDescriptors.check(action, ConditionDescriptors.Candidate.roles(Arrays.asList(10100L), true)).outcome());
        assertEquals(ConditionDescriptors.Outcome.ADD, ConditionDescriptors.check(action,
                ConditionDescriptors.Candidate.roles(Arrays.asList(10100L), false)).outcome());
    }

    @Test
    public void aMatchingConditionInsideACompoundDoesNotCount() {
        ActionDescriptor action = action(workflow(), 19);

        assertEquals(ConditionDescriptors.Outcome.ADD,
                ConditionDescriptors.check(action, ConditionDescriptors.Candidate.group("Team-A")).outcome());
        assertEquals(ConditionDescriptors.Outcome.ADD, ConditionDescriptors.check(action,
                ConditionDescriptors.Candidate.roles(Arrays.asList(10002L), false)).outcome());
        ActionDescriptor nested = action(workflow(), 14);
        assertEquals(ConditionDescriptors.Outcome.ADD,
                ConditionDescriptors.check(nested, ConditionDescriptors.Candidate.group("Inner")).outcome());
    }

    @Test
    public void conditionsThatWereNotCapturedNeverMakeAnAdditionUnchanged() {
        ActionDescriptor action = action(workflow(), 18);

        assertEquals(ConditionDescriptors.Outcome.ADD,
                ConditionDescriptors.check(action, ConditionDescriptors.Candidate.group("Odd")).outcome());
        assertEquals(ConditionDescriptors.Outcome.ADD,
                ConditionDescriptors.check(action, ConditionDescriptors.Candidate.group("Negated")).outcome());
        assertEquals(ConditionDescriptors.Outcome.ADD, ConditionDescriptors.check(action,
                ConditionDescriptors.Candidate.roles(Arrays.asList(10002L), false)).outcome());
    }

    // ---- an OR root --------------------------------------------------------------------------------------

    @Test
    public void anOrRootIsRefusedForEveryTemplateEvenWhenTheConditionIsInIt() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor action = action(d, 15);
        String before = DescriptorFacts.definition(d);

        for (ConditionDescriptors.Candidate candidate : Arrays.asList(ConditionDescriptors.Candidate.group("Team-A"),
                ConditionDescriptors.Candidate.group("Other"),
                ConditionDescriptors.Candidate.roles(Arrays.asList(10002L), false))) {
            ConditionDescriptors.Check check = ConditionDescriptors.check(action, candidate);
            assertEquals(ConditionDescriptors.Outcome.UNSUPPORTED, check.outcome());
            assertTrue(check.reason(), check.reason().contains("OR"));
        }
        assertEquals("a check never changes the descriptor", before, DescriptorFacts.definition(d));
    }

    // ---- the group in the root (warning) -----------------------------------------------------------

    @Test
    public void theCheckSaysWhetherTheAndRootAlreadyHasAGroupConditionAmongItsDirectChildren() {
        WorkflowDescriptor d = workflow();
        ConditionDescriptors.Candidate candidate = ConditionDescriptors.Candidate.group("Other");

        assertTrue(ConditionDescriptors.check(action(d, 11), candidate).groupInRoot());
        assertTrue(ConditionDescriptors.check(action(d, 16), candidate).groupInRoot());
        assertFalse(ConditionDescriptors.check(action(d, 17), candidate).groupInRoot());
        assertFalse("a group inside a nested OR is not in the root", ConditionDescriptors.check(action(d, 14), candidate).groupInRoot());
        assertFalse(ConditionDescriptors.check(action(d, 13), candidate).groupInRoot());
        assertFalse("a group that was not captured is not a GROUP", ConditionDescriptors.check(action(d, 18), candidate).groupInRoot());
    }

    // ---- other parts of the workflow ---------------------------------------------------------------------

    @Test
    public void onlyTheRootOfTheTargetTransitionChanges() {
        for (int target : new int[] {11, 12, 13, 16, 31}) {
            WorkflowDescriptor d = workflow();
            Map<Integer, String> others = actionsExcept(d, target);
            ActionDescriptor action = action(d, target);
            String results = DescriptorFacts.xml(action.getUnconditionalResult());
            String initial = DescriptorFacts.xml(action(d, 1));

            ConditionDescriptors.apply(action, ConditionDescriptors.Candidate.group("Team-Z"), FID);

            List<ConditionDescriptor> now = conditions(action);
            assertEquals("action " + target + ": the one condition is appended", "Team-Z",
                    now.get(now.size() - 1).getArgs().get("group"));
            assertEquals("action " + target, others, actionsExcept(d, target));
            assertEquals(results, DescriptorFacts.xml(action.getUnconditionalResult()));
            assertEquals(initial, DescriptorFacts.xml(action(d, 1)));
            assertEquals(1, action.getValidators().size());
            assertTrue(DescriptorFacts.xml(d).contains("<meta name=\"jira.description\">keep " + target + "</meta>"));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static ConditionsDescriptor root(ActionDescriptor action) {
        return action.getRestriction().getConditionsDescriptor();
    }

    private static List<ConditionDescriptor> conditions(ActionDescriptor action) {
        List<ConditionDescriptor> list = new ArrayList<ConditionDescriptor>();
        for (Object o : root(action).getConditions()) list.add((ConditionDescriptor) o);
        return list;
    }

    private static List<String> conditionXml(ActionDescriptor action) {
        List<String> xml = new ArrayList<String>();
        for (Object o : root(action).getConditions()) xml.add(DescriptorFacts.xml((com.opensymphony.workflow.util.XMLizable) o));
        return xml;
    }

    private static Map<Integer, String> actionsExcept(WorkflowDescriptor d, int skip) {
        Map<Integer, String> xml = new LinkedHashMap<Integer, String>();
        for (DescriptorFacts.Located located : DescriptorFacts.locate(d).values()) {
            if (located.id() != skip) xml.put(located.id(), DescriptorFacts.xml(located.action()));
        }
        return xml;
    }

    /** A node as JSON text, to compare two nodes by what the model sees. */
    private static String json(ConditionNode node) {
        try {
            return new ObjectMapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
