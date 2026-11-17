package ru.rasulov.workflow.impl.edit;

import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.ValidatorDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.edit.EditErrorCode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static ru.rasulov.workflow.impl.edit.ValidatorFixtures.*;

/**
 * The descriptor side of the required-fields validator on real XML of ScriptRunner 9.46.0: what is read from it and
 * exactly what is written.
 */
public class RequiredFieldsDescriptorTest {

    private static final String NEW_ID = "5b0e0f84-6f7e-4a5e-9c55-5e9d8f1a2b3c";

    // ---- reading -----------------------------------------------------------------------------------------

    @Test
    public void aTransitionIsReadAsItsValidatorsInDescriptorOrder() {
        List<RequiredFieldsDescriptor.Entry> entries = RequiredFieldsDescriptor.scan(action(workflow(), 21));

        assertEquals(3, entries.size());
        assertEquals(Arrays.asList(1, 2, 3), Arrays.asList(entries.get(0).position(), entries.get(1).position(),
                entries.get(2).position()));
        assertTrue(entries.get(0).requiredFields());
        assertEquals(Arrays.asList("assignee", "customfield_10118"), entries.get(0).fieldIds());
        assertFalse(entries.get(0).conditional());
        assertTrue(entries.get(1).requiredFields());
        assertEquals(Arrays.asList("description"), entries.get(1).fieldIds());
        assertTrue("a non-empty FIELD_CONDITION makes the validator conditional", entries.get(1).conditional());
        assertFalse(entries.get(2).requiredFields());
        assertEquals(RequiredFieldsDescriptor.CLASS_NAME, entries.get(2).className());
        assertEquals(COMMENT_CANNED, entries.get(2).cannedScript());
        assertEquals("class", entries.get(2).type());
    }

    @Test
    public void aRequiredFieldsValidatorInAFormThatWasNotCapturedIsNotReadAsOne() {
        List<RequiredFieldsDescriptor.Entry> entries = RequiredFieldsDescriptor.scan(action(workflow(), 25));

        assertEquals(1, entries.size());
        assertFalse(entries.get(0).requiredFields());
        assertEquals(RequiredFieldsDescriptor.CANNED_SCRIPT, entries.get(0).cannedScript());
    }

    @Test
    public void aTransitionWithoutValidatorsHasNoEntries() {
        assertTrue(RequiredFieldsDescriptor.scan(action(workflow(), 26)).isEmpty());
    }

    // ---- adding to an action with an existing unconditional validator ----------------------------------

    @Test
    public void missingFieldsBecomeANewValidatorAndTheExistingOneIsNeverRewritten() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor target = action(d, 22);
        Map<Integer, String> before = actionsExcept(d, 22);
        String existingBefore = DescriptorFacts.xml(validators(target).get(0));

        RequiredFieldsDescriptor.Change change = RequiredFieldsDescriptor.plan(target, Arrays.asList("assignee", "description"));
        RequiredFieldsDescriptor.apply(target, change, NEW_ID);

        assertEquals(Arrays.asList("assignee"), change.alreadyRequired());
        assertEquals(Arrays.asList("description"), change.added());
        assertNull("a new validator is written, none receives the fields", change.position());
        assertEquals(3, validators(target).size());
        assertEquals("the existing validators are byte-for-byte unchanged and keep their order", existingBefore,
                DescriptorFacts.xml(validators(target).get(0)));
        assertEquals("description", validators(target).get(2).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals(NEW_ID, validators(target).get(2).getArgs().get("FIELD_FUNCTION_ID"));
        assertEquals(before, actionsExcept(d, 22));
    }

    @Test
    public void theNewValidatorHoldsOnlyTheMissingFieldsInRequestOrder() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor target = action(d, 22);

        RequiredFieldsDescriptor.apply(target,
                RequiredFieldsDescriptor.plan(target, Arrays.asList("description", "assignee", "customfield_10118")), NEW_ID);

        assertEquals("assignee", validators(target).get(0).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals("description|||customfield_10118", validators(target).get(2).getArgs().get("FIELD_REQUIRED_FIELDS"));
    }

    // ---- a new validator --------------------------------------------------------------------------

    @Test
    public void withoutAnUnconditionalValidatorExactlyOneNewOneIsAddedAtTheEndInTheShapeOfTheSample() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor target = action(d, 11);
        Map<Integer, String> before = actionsExcept(d, 11);
        List<String> oldValidators = Arrays.asList(DescriptorFacts.xml(validators(target).get(0)),
                DescriptorFacts.xml(validators(target).get(1)));

        RequiredFieldsDescriptor.Change change = RequiredFieldsDescriptor.plan(target,
                Arrays.asList("assignee", "customfield_10118"));
        RequiredFieldsDescriptor.apply(target, change, NEW_ID);

        assertNull(change.position());
        assertTrue(change.alreadyRequired().isEmpty());
        assertEquals(Arrays.asList("assignee", "customfield_10118"), change.added());
        assertEquals(3, validators(target).size());
        assertEquals(oldValidators, Arrays.asList(DescriptorFacts.xml(validators(target).get(0)),
                DescriptorFacts.xml(validators(target).get(1))));
        ValidatorDescriptor created = validators(target).get(2);
        Map<String, String> args = new LinkedHashMap<String, String>();
        for (Object e : created.getArgs().entrySet()) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) e;
            args.put((String) entry.getKey(), (String) entry.getValue());
        }
        assertEquals(new TreeSet<String>(Arrays.asList("FIELD_FUNCTION_ID", "FIELD_REQUIRED_FIELDS", "canned-script",
                "FIELD_CONDITION", "class.name")), new TreeSet<String>(args.keySet()));
        assertEquals(NEW_ID, args.get("FIELD_FUNCTION_ID"));
        assertEquals("assignee|||customfield_10118", args.get("FIELD_REQUIRED_FIELDS"));
        assertEquals("", args.get("FIELD_CONDITION"));
        assertEquals("the new validator is written exactly like the sample ScriptRunner stored",
                DescriptorFacts.xml(validator(required(NEW_ID, "assignee|||customfield_10118", ""))),
                DescriptorFacts.xml(created));
        assertEquals(before, actionsExcept(d, 11));
    }

    @Test
    public void aConditionalValidatorDoesNotCountAndIsNeverChanged() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor target = action(d, 24);
        String conditional = DescriptorFacts.xml(validators(target).get(0));

        RequiredFieldsDescriptor.Change change = RequiredFieldsDescriptor.plan(target, Arrays.asList("assignee"));
        RequiredFieldsDescriptor.apply(target, change, NEW_ID);

        assertNull(change.position());
        assertTrue("the conditional validator is not an unconditional requirement", change.alreadyRequired().isEmpty());
        assertEquals(Arrays.asList("assignee"), change.added());
        assertEquals(2, validators(target).size());
        assertEquals(conditional, DescriptorFacts.xml(validators(target).get(0)));
        assertEquals("assignee", validators(target).get(1).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals("", validators(target).get(1).getArgs().get("FIELD_CONDITION"));
    }

    // ---- idempotence and union --------------------------------------------------------------------------

    @Test
    public void fieldsAlreadyRequiredByTwoValidatorsTogetherAreAnUnchangedRequest() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor target = action(d, 23);
        String before = DescriptorFacts.xml(target);

        RequiredFieldsDescriptor.Change change = RequiredFieldsDescriptor.plan(target, Arrays.asList("assignee", "description"));

        assertTrue(change.added().isEmpty());
        assertEquals(Arrays.asList("assignee", "description"), change.alreadyRequired());
        assertEquals("planning never touches the descriptor", before, DescriptorFacts.xml(target));
    }

    @Test
    public void aNewFieldGoesIntoOneNewValidatorAfterTheTwoUnconditionalOnes() {
        WorkflowDescriptor d = workflow();
        ActionDescriptor target = action(d, 23);
        String first = DescriptorFacts.xml(validators(target).get(0));
        String second = DescriptorFacts.xml(validators(target).get(1));

        RequiredFieldsDescriptor.Change change = RequiredFieldsDescriptor.plan(target,
                Arrays.asList("assignee", "description", "customfield_10118"));
        RequiredFieldsDescriptor.apply(target, change, NEW_ID);

        assertEquals(Arrays.asList("assignee", "description"), change.alreadyRequired());
        assertEquals(Arrays.asList("customfield_10118"), change.added());
        assertNull(change.position());
        assertEquals(3, validators(target).size());
        assertEquals(first, DescriptorFacts.xml(validators(target).get(0)));
        assertEquals(second, DescriptorFacts.xml(validators(target).get(1)));
        assertEquals("customfield_10118", validators(target).get(2).getArgs().get("FIELD_REQUIRED_FIELDS"));
    }

    // ---- refusals ----------------------------------------------------------------------------------------

    @Test
    public void aRequiredFieldsValidatorOfAnotherFormIsRefusedInsteadOfBeingDuplicated() {
        ActionDescriptor target = action(workflow(), 25);
        try {
            RequiredFieldsDescriptor.plan(target, Arrays.asList("description"));
            fail("a validator that is only almost the captured form must stop the change");
        } catch (EditFailure e) {
            assertEquals(EditErrorCode.UNSUPPORTED_DESCRIPTOR, e.getCode());
            assertTrue(e.getMessage(), e.getMessage().contains("position 1"));
        }
    }

    @Test
    public void aFieldListThatIsNotExactlyIdsJoinedBySeparatorsIsNotTakenForTheCapturedForm() {
        for (String fields : new String[] {"", "a|||", "|||a", "a||||b", "a|||b|||", "a b", "a|||b c", " a", "a |||b"}) {
            ActionDescriptor target = (ActionDescriptor) validator(required(ID_3, fields, "")).getParent();

            assertFalse("'" + fields + "'", RequiredFieldsDescriptor.scan(target).get(0).requiredFields());
            try {
                RequiredFieldsDescriptor.plan(target, Arrays.asList("description"));
                fail("'" + fields + "' must stop the change");
            } catch (EditFailure e) {
                assertEquals(EditErrorCode.UNSUPPORTED_DESCRIPTOR, e.getCode());
            }
        }
    }

    // ---- the descriptor must survive Jira's own save -----------------------------------------------------------

    @Test
    public void bothKindsOfChangeSurviveSaveAndReloadExactly() {
        WorkflowDescriptor d = workflow();
        RequiredFieldsDescriptor.apply(action(d, 22), RequiredFieldsDescriptor.plan(action(d, 22),
                Arrays.asList("assignee", "description")), NEW_ID);
        RequiredFieldsDescriptor.apply(action(d, 11), RequiredFieldsDescriptor.plan(action(d, 11),
                Arrays.asList("assignee")), "2b5f6b57-3f6c-4e0c-9d63-0a8f7e1d2c3b");

        assertEquals("description", validators(action(d, 22)).get(2).getArgs().get("FIELD_REQUIRED_FIELDS"));
        assertEquals(3, validators(action(d, 11)).size());
        assertEquals(DescriptorFacts.definition(d), DescriptorFacts.definition(reloaded(d)));
    }

    private static Map<Integer, String> actionsExcept(WorkflowDescriptor d, int skip) {
        Map<Integer, String> xml = new LinkedHashMap<Integer, String>();
        for (DescriptorFacts.Located l : DescriptorFacts.locate(d).values()) {
            if (l.id() != skip) xml.put(l.id(), DescriptorFacts.xml(l.action()));
        }
        return xml;
    }
}
